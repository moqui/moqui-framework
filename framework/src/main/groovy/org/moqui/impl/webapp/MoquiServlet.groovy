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
import org.moqui.context.NotificationMessage
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

        // "Connection:Upgrade or " "Upgrade".equals(request.getHeader("Connection")) ||
        if ("websocket".equals(request.getHeader("Upgrade"))) {
            logger.warn("Got request for Upgrade:websocket which should have been handled by servlet container, returning error")
            response.sendError(HttpServletResponse.SC_NOT_IMPLEMENTED)
            return
        }

        // handle CORS actual and preflight request headers
        ExecutionContextFactoryImpl.WebappInfo webappInfo = ecfi.getWebappInfo(webappName)
        String originHeader = request.getHeader("Origin")
        if (originHeader != null && !originHeader.isEmpty() && webappInfo != null &&
                !"false".equals(webappInfo.webappNode.attribute("handle-cors"))) {

            // generate Access-Control-Allow-Origin based on Origin, if allowed
            Set<String> allowOriginSet = webappInfo.allowOriginSet
            int originSepIdx = originHeader.indexOf("://")
            String originDomain = originSepIdx > 0 ? originHeader.substring(originSepIdx + 3) : originHeader
            int originDomColonIdx = originDomain.indexOf(":")
            if (originDomColonIdx > 0) originDomain = originDomain.substring(0, originDomColonIdx)
            // if * allowed or Origin domain matches request domain always allow (same origin)
            String serverName = request.getServerName()
            URL requestUrl = new URL(request.getRequestURL().toString())
            String hostName = requestUrl.getHost()
            if (allowOriginSet.contains("*") || originDomain == serverName || originDomain == hostName) {
                response.setHeader("Access-Control-Allow-Origin", originHeader)
            } else {
                if (allowOriginSet.contains(originHeader) || allowOriginSet.contains(originDomain)) {
                    response.setHeader("Access-Control-Allow-Origin", originHeader)
                } else {
                    logger.warn("Returning 401, Origin ${originHeader} not allowed for configuration ${allowOriginSet} or server name ${serverName} or request host ${hostName}")
                    // Origin not allowed, send 401 response
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Origin not allowed")
                    return
                }
            }

            String acRequestMethod = request.getHeader("Access-Control-Request-Method")
            if ("OPTIONS".equals(request.getMethod()) && acRequestMethod != null && !acRequestMethod.isEmpty()) {
                // String acRequestHeaders = request.getHeader("Access-Control-Request-Headers")
                webappInfo.addHeaders("cors-preflight", response)
                response.setStatus(HttpServletResponse.SC_OK)
                return
            } else {
                webappInfo.addHeaders("cors-actual", response)
            }
        }

        if (!request.characterEncoding) request.setCharacterEncoding("UTF-8")
        long startTime = System.currentTimeMillis()

        if (logger.traceEnabled) logger.trace("Start request to [${request.getPathInfo()}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")
        // logger.warn("Start request to [${pathInfo}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]", new Exception("Start request"))

        if (MDC.get("moqui_userId") != null) logger.warn("In MoquiServlet.service there is already a userId in thread (${Thread.currentThread().id}:${Thread.currentThread().name}), removing")
        MDC.remove("moqui_userId")
        MDC.remove("moqui_visitorId")

        // make sure no transaction is active in thread
        if (ecfi.transactionFacade.isTransactionInPlace()) {
            logger.warn("In MoquiServlet.service there is already a transaction for thread [${Thread.currentThread().id}:${Thread.currentThread().name}], closing")
            try {
                ecfi.transactionFacade.destroyAllInThread()
            } catch (Throwable t) {
                logger.error("Error destroying transaction already in place in MoquiServlet.service", t)
            }
        }

        // check for active ExecutionContext
        ExecutionContextImpl activeEc = ecfi.activeContext.get()
        if (activeEc != null) {
            logger.warn("In MoquiServlet.service there is already an ExecutionContext for user ${activeEc.user.username} (from ${activeEc.forThreadId}:${activeEc.forThreadName}) in this thread (${Thread.currentThread().id}:${Thread.currentThread().name}), destroying")
            try {
                activeEc.destroy()
            } catch (Throwable t) {
                logger.error("Error destroying ExecutionContext already in place in MoquiServlet.service", t)
            }
        }
        // get a new ExecutionContext
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
            sendErrorResponse(request, response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", null, e, ecfi, webappName, sri)
        } catch (ArtifactAuthorizationException e) {
            // SC_UNAUTHORIZED 401 used when authc/login fails, use SC_FORBIDDEN 403 for authz failures
            // See ScreenRenderImpl.checkWebappSettings for authc and SC_UNAUTHORIZED handling
            logger.warn("Web Access Forbidden (no authz): " + e.message)
            sendErrorResponse(request, response, HttpServletResponse.SC_FORBIDDEN, "forbidden", null, e, ecfi, webappName, sri)
        } catch (ScreenResourceNotFoundException e) {
            logger.warn("Web Resource Not Found: " + e.message)
            sendErrorResponse(request, response, HttpServletResponse.SC_NOT_FOUND, "not-found", null, e, ecfi, webappName, sri)
        } catch (ArtifactTarpitException e) {
            logger.warn("Web Too Many Requests (tarpit): " + e.message)
            if (e.getRetryAfterSeconds()) response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            // NOTE: there is no constant on HttpServletResponse for 429; see RFC 6585 for details
            sendErrorResponse(request, response, 429, "too-many", null, e, ecfi, webappName, sri)
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
                if (isBrokenPipe(t)) {
                    logger.error("Internal error processing request: " + tString)
                } else {
                    logger.error("Internal error processing request: " + tString, t)
                }
                sendErrorResponse(request, response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal-error",
                        null, t, ecfi, webappName, sri)
            }
        } finally {
            /* this is here just for kicks, uncomment to log a list of all artifacts hit/used in the screen render
            StringBuilder hits = new StringBuilder()
            hits.append("Artifacts hit in this request: ")
            for (def aei in ec.artifactExecution.history) hits.append("\n").append(aei)
            logger.info(hits.toString())
            */

            // make sure everything is cleaned up
            ec.destroy()
        }

        /* definitely don't want this normally, but uncomment to help debug session attribute issues:
        logger.warn("Thread ClassLoader ${Thread.currentThread().getContextClassLoader()?.getClass()?.getName()}")
        for (String name in ec.web.session.getAttributeNames()) {
            Object value = ec.web.session.getAttribute(name)
            logger.warn("Session attr " + name + "(" + (value != null ? value.getClass().getName() : "") + ":" + (value != null && value.getClass().getClassLoader() != null ? value.getClass().getClassLoader().getClass().getName() : "") + ")" + " value: " + value)
        }
        */
    }

    static void sendErrorResponse(HttpServletRequest request, HttpServletResponse response, int errorCode, String errorType,
            String message, Throwable origThrowable, ExecutionContextFactoryImpl ecfi, String moquiWebappName, ScreenRenderImpl sri) {

        if (message == null && origThrowable != null) {
            List<String> msgList = new ArrayList<>(10)
            Throwable curt = origThrowable
            while (curt != null) {
                msgList.add(curt.message)
                curt = curt.getCause()
            }
            int msgListSize = msgList.size()
            if (msgListSize > 4) msgList = (List<String>) msgList.subList(msgListSize - 4, msgListSize)
            message = msgList.join(" ")
        }

        if (ecfi != null && errorCode == HttpServletResponse.SC_INTERNAL_SERVER_ERROR && !isBrokenPipe(origThrowable)) {
            ExecutionContextImpl ec = ecfi.getEci()
            ec.makeNotificationMessage().topic("WebServletError").type(NotificationMessage.danger)
                    .title('''Web Error ${errorCode?:''} (${username?:'no user'}) ${path?:''} ${message?:'N/A'}''')
                    .message([errorCode:errorCode, errorType:errorType, message:message, exception:origThrowable?.toString(),
                        path:ec.web?.getPathInfo(), parameters:ec.web?.getRequestParameters(), username:ec.user.username] as Map<String, Object>)
                    .send()
        }

        if (ecfi == null) {
            response.sendError(errorCode, message)
            return
        }
        ExecutionContextImpl ec = ecfi.getEci()
        String acceptHeader = request.getHeader("Accept")
        boolean acceptHtml = acceptHeader != null && acceptHeader.contains("text/html")
        MNode errorScreenNode = acceptHtml ? ecfi.getWebappInfo(moquiWebappName)?.getErrorScreenNode(errorType) : null
        if (errorScreenNode != null) {
            try {
                ec.context.put("errorCode", errorCode)
                ec.context.put("errorType", errorType)
                ec.context.put("errorMessage", message)
                ec.context.put("errorThrowable", origThrowable)
                String screenPathAttr = errorScreenNode.attribute("screen-path")
                // NOTE 20180228: this seems to be working fine now and Jetty (at least) is returning the 404/etc responses with the custom HTML body unlike before
                response.setStatus(errorCode)
                ec.screen.makeRender().webappName(moquiWebappName).renderMode("html")
                        .rootScreenFromHost(request.getServerName()).screenPath(Arrays.asList(screenPathAttr.split("/")))
                        .render(request, response)
            } catch (Throwable t) {
                logger.error("Error rendering ${errorType} error screen, sending code ${errorCode} with message: ${message}", t)
                response.sendError(errorCode, message)
            }
        } else {
            if (ec.web != null) {
                ec.web.sendError(errorCode, message, origThrowable)
            } else {
                response.sendError(errorCode, message)
            }
        }
    }

    static boolean isBrokenPipe(Throwable throwable) {
        Throwable curt = throwable
        while (curt != null) {
            // could constrain more looking for "Broken pipe" message
            // works for Jetty, may have different exception patterns on other servlet containers
            if (curt instanceof IOException) return true
            curt = curt.getCause()
        }
        return false
    }
}
