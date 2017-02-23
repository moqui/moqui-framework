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
import org.moqui.util.StringUtilities

import javax.servlet.ServletException
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

import org.moqui.context.ExecutionContext
import org.moqui.screen.ScreenRender
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.impl.context.ExecutionContextFactoryImpl

import javax.xml.transform.stream.StreamSource

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class MoquiFopServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiFopServlet.class)

    MoquiFopServlet() {
        super()
    }

    @Override
    void doPost(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    @Override
    void doGet(HttpServletRequest request, HttpServletResponse response) { doScreenRequest(request, response) }

    void doScreenRequest(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ExecutionContextFactoryImpl ecfi =
                (ExecutionContextFactoryImpl) getServletContext().getAttribute("executionContextFactory")
        String moquiWebappName = getServletContext().getInitParameter("moqui-name")

        if (ecfi == null || moquiWebappName == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "System is initializing, try again soon.")
            return
        }

        String pathInfo = request.getPathInfo()
        long startTime = System.currentTimeMillis()

        if (logger.traceEnabled) logger.trace("Start request to [${pathInfo}] at time [${startTime}] in session [${request.session.id}] thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")

        ExecutionContext ec = ecfi.getExecutionContext()
        ec.initWebFacade(moquiWebappName, request, response)
        ec.web.requestAttributes.put("moquiRequestStartTime", startTime)

        String filename = (ec.web.parameters.get("filename") as String) ?: (ec.web.parameters.get("saveFilename") as String)

        String xslFoText = null
        try {
            ScreenRender sr = ec.screen.makeRender().webappName(moquiWebappName).renderMode("xsl-fo")
                    .rootScreenFromHost(request.getServerName()).screenPath(pathInfo.split("/") as List)
            xslFoText = sr.render()

            if (ec.message.hasError()) {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ec.message.errorsString)
                return
            }

            // logger.warn("======== XSL-FO content:\n${xslFoText}")
            if (logger.traceEnabled) logger.trace("XSL-FO content:\n${xslFoText}")

            String contentType = ec.web.requestParameters."contentType" ?: "application/pdf"
            response.setContentType(contentType)

            if (filename) {
                String utfFilename = StringUtilities.encodeAsciiFilename(filename)
                response.addHeader("Content-Disposition", "attachment; filename=\"${filename}\"; filename*=utf-8''${utfFilename}")
            } else {
                response.addHeader("Content-Disposition", "inline")
            }

            // special case disable authz for resource access
            boolean enableAuthz = !ecfi.getExecutionContext().getArtifactExecution().disableAuthz()
            try {
                ec.resource.xslFoTransform(new StreamSource(new StringReader(xslFoText)), null,
                        response.getOutputStream(), contentType)
            } finally {
                if (enableAuthz) ecfi.getExecutionContext().getArtifactExecution().enableAuthz()
            }
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
            logger.error("Error transforming XSL-FO content:\n${xslFoText}", t)
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

        if (logger.infoEnabled) logger.info("Finished XSL-FO request to ${pathInfo}, content type ${response.getContentType()} in ${System.currentTimeMillis()-startTime}ms; session ${request.session.id} thread ${Thread.currentThread().id}:${Thread.currentThread().name}")
    }
}
