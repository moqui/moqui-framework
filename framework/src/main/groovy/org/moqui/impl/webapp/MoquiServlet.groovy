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
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.screen.ScreenRenderImpl
import org.moqui.screen.ScreenRender
import org.moqui.util.MNode

import javax.servlet.ServletConfig
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.ServletException

import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class MoquiServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiServlet.class)
    protected ExecutionContextFactoryImpl ecfi = null
    protected String moquiWebappName = null

    MoquiServlet() { super(); }

    @Override
    void init(ServletConfig config) throws ServletException {
        super.init(config)

        ecfi = (ExecutionContextFactoryImpl) config.getServletContext().getAttribute("executionContextFactory")
        moquiWebappName = config.getServletContext().getInitParameter("moqui-name")
        logger.info("${config.getServletName()} initialized for webapp ${moquiWebappName}")
    }

    @Override
    void service(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    void doScreenRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!request.characterEncoding) request.setCharacterEncoding("UTF-8")

        if (ecfi == null || moquiWebappName == null) init(getServletConfig())

        long startTime = System.currentTimeMillis()
        String pathInfo = request.getPathInfo()

        if (logger.traceEnabled) logger.trace("Start request to [${pathInfo}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")

        ExecutionContextImpl ec = ecfi.getEci()

        /** NOTE to set render settings manually do something like this, but it is not necessary to set these things
         * for a web page render because if we call render(request, response) it can figure all of this out as defaults
         *
         * ScreenRender render = ec.screen.makeRender().webappName(moquiWebappName).renderMode("html")
         *         .rootScreenFromHost(request.getServerName()).screenPath(pathInfo.split("/") as List)
         */

        try {
            ec.initWebFacade(moquiWebappName, request, response)
            ec.web.requestAttributes.put("moquiRequestStartTime", startTime)

            ScreenRenderImpl sri = (ScreenRenderImpl) ec.screenFacade.makeRender()
            sri.render(request, response)
        } catch (AuthenticationRequiredException e) {
            logger.warn("Web Unauthorized (no authc): " + e.message)
            sendErrorResponse(request, response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", e.message, e)
        } catch (ArtifactAuthorizationException e) {
            // SC_UNAUTHORIZED 401 used when authc/login fails, use SC_FORBIDDEN 403 for authz failures
            // See ScreenRenderImpl.checkWebappSettings for authc and SC_UNAUTHORIZED handling
            logger.warn("Web Access Forbidden (no authz): " + e.message)
            sendErrorResponse(request, response, HttpServletResponse.SC_FORBIDDEN, "forbidden", e.message, e)
        } catch (ScreenResourceNotFoundException e) {
            logger.warn("Web Resource Not Found: " + e.message)
            sendErrorResponse(request, response, HttpServletResponse.SC_NOT_FOUND, "not-found", e.message, e)
        } catch (ArtifactTarpitException e) {
            logger.warn("Web Too Many Requests (tarpit): " + e.message)
            if (e.getRetryAfterSeconds()) response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            // NOTE: there is no constant on HttpServletResponse for 429; see RFC 6585 for details
            sendErrorResponse(request, response, 429, "too-many", e.message, e)
        } catch (Throwable t) {
            if (ec.message.hasError()) {
                String errorsString = ec.message.errorsString
                logger.error(errorsString, t)
                sendErrorResponse(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal-error", errorsString, t)
            } else {
                logger.error("Internal error processing request: " + t.message, t)
                sendErrorResponse(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal-error", t.message, t)
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

    void sendErrorResponse(HttpServletRequest request, HttpServletResponse response, int errorCode, String errorType,
                           String message, Throwable origThrowable) {
        if (ecfi == null) {
            response.sendError(errorCode, message)
            return
        }
        ExecutionContext ec = ecfi.getExecutionContext()
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
