/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.webapp

import groovy.transform.CompileStatic
import org.moqui.context.ArtifactTarpitException
import org.moqui.context.AuthenticationRequiredException
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.screen.ScreenRenderImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

import javax.servlet.ServletConfig
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.ServletException


@CompileStatic
class MoquiServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiServlet.class)

    MoquiServlet() { super() }

    @Override
    void init(ServletConfig config) throws ServletException {
        super.init(config)
        String webappName = config.getInitParameter("moqui-name") ?: config.getServletContext().getInitParameter("moqui-name")
        logger.info("${config.getServletName()} initialized for webapp ${webappName}")
    }

    @Override
    void service(HttpServletRequest request, HttpServletResponse response) {
        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) getServletContext().getAttribute("executionContextFactory")
        String webappName = getInitParameter("moqui-name") ?: getServletContext().getInitParameter("moqui-name")

        // check for and cleanly handle when executionContextFactory is not in place in ServletContext attr
        if (ecfi == null || webappName == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "System is initializing, try again soon.")
            return
        }

        if ("Upgrade".equals(request.getHeader("Connection")) || "websocket".equals(request.getHeader("Upgrade"))) {
            logger.warn("Got request for Connection:Upgrade or Upgrade:websocket which should have been handled by servlet container, returning error")
            response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED)
            return
        }

        if (!request.characterEncoding) request.setCharacterEncoding("UTF-8")
        long startTime = System.currentTimeMillis()
        String pathInfo = request.getPathInfo()

        if (logger.traceEnabled) logger.trace("Start request to [${pathInfo}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")

        if (MDC.get("moqui_userId") != null) logger.warn("In MoquiServlet.service there is already a userId in thread (${Thread.currentThread().id}:${Thread.currentThread().name}), removing")
        MDC.remove("moqui_userId")
        MDC.remove("moqui_visitorId")

        ExecutionContextImpl activeEc = ecfi.activeContext.get()
        if (activeEc != null && activeEc.forThreadId != Thread.currentThread().id) {
            logger.warn("In MoquiServlet.service there is already an ExecutionContext (from ${activeEc.forThreadId}:${activeEc.forThreadName}) in this thread (${Thread.currentThread().id}:${Thread.currentThread().name}), destroying")
            ecfi.destroyActiveExecutionContext()
        }
        ExecutionContextImpl ec = ecfi.getEci()

        /** NOTE to set render settings manually do something like this, but it is not necessary to set these things
         * for a web page render because if we call render(request, response) it can figure all of this out as defaults
         *
         * ScreenRender render = ec.screen.makeRender().webappName(moquiWebappName).renderMode("html")
         *         .rootScreenFromHost(request.getServerName()).screenPath(pathInfo.split("/") as List)
         */

        ScreenRenderImpl sri = null
        try {
            ec.initWebFacade(webappName, request, response)
            ec.web.requestAttributes.put("moquiRequestStartTime", startTime)

            sri = (ScreenRenderImpl) ec.screenFacade.makeRender().saveHistory(true)
            sri.render(request, response)
        } catch (AuthenticationRequiredException e) {
            logger.warn("Web Unauthorized (no authc): " + e.message)
            sendErrorResponse(request, response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", e.message, e, ecfi, webappName, sri)
        } catch (ArtifactAuthorizationException e) {
            // SC_UNAUTHORIZED 401 used when authc/login fails, use SC_FORBIDDEN 403 for authz failures
            // See ScreenRenderImpl.checkWebappSettings for authc and SC_UNAUTHORIZED handling
            logger.warn("Web Access Forbidden (no authz): " + e.message)
            sendErrorResponse(request, response, HttpServletResponse.SC_FORBIDDEN, "forbidden", e.message, e, ecfi, webappName, sri)
        } catch (ScreenResourceNotFoundException e) {
            logger.warn("Web Resource Not Found: " + e.message)
            sendErrorResponse(request, response, HttpServletResponse.SC_NOT_FOUND, "not-found", e.message, e, ecfi, webappName, sri)
        } catch (ArtifactTarpitException e) {
            logger.warn("Web Too Many Requests (tarpit): " + e.message)
            if (e.getRetryAfterSeconds()) response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            // NOTE: there is no constant on HttpServletResponse for 429; see RFC 6585 for details
            sendErrorResponse(request, response, 429, "too-many", e.message, e, ecfi, webappName, sri)
        } catch (Throwable t) {
            if (ec.message.hasError()) {
                String errorsString = ec.message.errorsString
                logger.error(errorsString, t)
                if ("true".equals(request.getAttribute("moqui.login.error"))) {
                    sendErrorResponse(request, response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized",
                            errorsString, t, ecfi, webappName, sri)
                } else {
                    sendErrorResponse(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal-error",
                            errorsString, t, ecfi, webappName, sri)
                }
            } else {
                String tString = t.toString()
                if (tString.contains("org.eclipse.jetty.io.EofException")) {
                    logger.error("Internal error processing request: " + tString)
                } else {
                    logger.error("Internal error processing request: " + tString, t)
                }
                sendErrorResponse(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal-error",
                        t.message, t, ecfi, webappName, sri)
            }
        } finally {
            // make sure everything is cleaned up
            ec.destroy()
        }

        /* this is here just for kicks, uncomment to log a list of all artifacts hit/used in the screen render
        StringBuilder hits = new StringBuilder()
        hits.append("Artifacts hit in this request: ")
        for (def aei in ec.artifactExecution.history) hits.append("\n").append(aei)
        logger.info(hits.toString())
         */
    }

    static void sendErrorResponse(HttpServletRequest request, HttpServletResponse response, int errorCode, String errorType,
            String message, Throwable origThrowable, ExecutionContextFactoryImpl ecfi, String moquiWebappName, ScreenRenderImpl sri) {
        String acceptHeader = request.getHeader("Accept")
        if (ecfi == null || (acceptHeader && !acceptHeader.contains("text/html")) || ("rest".equals(sri?.screenUrlInfo?.targetScreen?.screenName))) {
            response.sendError(errorCode, message)
            return
        }
        ExecutionContextImpl ec = ecfi.getEci()
        MNode errorScreenNode = ecfi.getWebappInfo(moquiWebappName)?.getErrorScreenNode(errorType)
        if (errorScreenNode != null) {
            try {
                ec.context.put("errorCode", errorCode)
                ec.context.put("errorType", errorType)
                ec.context.put("errorMessage", message)
                ec.context.put("errorThrowable", origThrowable)
                String screenPathAttr = errorScreenNode.attribute("screen-path")
                // don't do this, causes servlet container to return no content for error status codes: response.setStatus(errorCode)
                ec.screen.makeRender().webappName(moquiWebappName).renderMode("html")
                        .rootScreenFromHost(request.getServerName()).screenPath(Arrays.asList(screenPathAttr.split("/")))
                        .render(request, response)
            } catch (Throwable t) {
                logger.error("Error rendering ${errorType} error screen, sending code ${errorCode} with message: ${message}", t)
                response.sendError(errorCode, message)
            }
        } else {
            response.sendError(errorCode, message)
        }
    }
}
