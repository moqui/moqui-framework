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

    MoquiServlet() { super(); }

    @Override
    void doPost(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    @Override
    void doGet(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    @Override
    void doPut(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    @Override
    void doDelete(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    void doScreenRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ExecutionContextFactory executionContextFactory =
                (ExecutionContextFactory) getServletContext().getAttribute("executionContextFactory")
        String moquiWebappName = getServletContext().getInitParameter("moqui-name")

        String pathInfo = request.getPathInfo()
        long startTime = System.currentTimeMillis()

        if (logger.traceEnabled) logger.trace("Start request to [${pathInfo}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")

        ExecutionContext ec = executionContextFactory.getExecutionContext()
        if (!request.characterEncoding) request.setCharacterEncoding("UTF-8")
        ec.initWebFacade(moquiWebappName, request, response)
        ec.web.requestAttributes.put("moquiRequestStartTime", startTime)

        /** NOTE to set render settings manually do something like this, but it is not necessary to set these things
         * for a web page render because if we call render(request, response) it can figure all of this out as defaults
         *
         * ScreenRender render = ec.screen.makeRender().webappName(webappMoquiName).renderMode("html")
         *         .rootScreenFromHost(request.getServerName()).screenPath(pathInfo.split("/") as List)
         */

        try {
            ec.screen.makeRender().render(request, response)
        } catch (AuthenticationRequiredException e) {
            logger.warn("Web Unauthorized (no authc): " + e.message)
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.message)
        } catch (ArtifactAuthorizationException e) {
            // SC_UNAUTHORIZED 401 used when authc/login fails, use SC_FORBIDDEN 403 for authz failures
            // See ScreenRenderImpl.checkWebappSettings for authc and SC_UNAUTHORIZED handling
            logger.warn((String) "Web Access Forbidden (no authz): " + e.message)
            response.sendError(HttpServletResponse.SC_FORBIDDEN, e.message)
        } catch (ArtifactTarpitException e) {
            logger.warn((String) "Web Too Many Requests (tarpit): " + e.message)
            if (e.getRetryAfterSeconds()) response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            // NOTE: there is no constant on HttpServletResponse for 429; see RFC 6585 for details
            response.sendError(429, e.message)
        } catch (ScreenResourceNotFoundException e) {
            logger.warn((String) "Web Resource Not Found: " + e.message)
            response.sendError(HttpServletResponse.SC_NOT_FOUND, e.message)
        } catch (Throwable t) {
            if (ec.message.hasError()) {
                String errorsString = ec.message.errorsString
                logger.error(errorsString, t)
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorsString)
            } else {
                throw t
            }
        } finally {
            // make sure everything is cleaned up
            ec.destroy()
        }

        if (logger.isInfoEnabled() || logger.isTraceEnabled()) {
            String contentType = response.getContentType()
            String logMsg = "Finished request to ${pathInfo} of content type ${response.getContentType()} in ${(System.currentTimeMillis()-startTime)/1000} seconds in session ${request.session.id} thread ${Thread.currentThread().id}:${Thread.currentThread().name}"
            if (logger.isInfoEnabled() && contentType && contentType.contains("text/html")) logger.info(logMsg)
            if (logger.isTraceEnabled()) logger.trace(logMsg)
        }

        /* this is here just for kicks, uncomment to log a list of all artifacts hit/used in the screen render
        StringBuilder hits = new StringBuilder()
        hits.append("Artifacts hit in this request: ")
        for (def aei in ec.artifactExecution.history) hits.append("\n").append(aei)
        logger.info(hits.toString())
         */
    }
}
