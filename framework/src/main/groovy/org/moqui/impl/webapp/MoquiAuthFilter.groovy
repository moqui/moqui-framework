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
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.UserFacadeImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.FilterConfig
import javax.servlet.ServletContext
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/** Check authentication and permission for servlets other than MoquiServlet, MoquiFopServlet.
 * Specify permission to check in 'permission' init-param. */
@CompileStatic
class MoquiAuthFilter implements Filter {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiAuthFilter.class)

    protected FilterConfig filterConfig = null
    protected String permission = null

    MoquiAuthFilter() { super() }

    @Override
    void init(FilterConfig filterConfig) throws ServletException {
        this.filterConfig = filterConfig
        permission = filterConfig.getInitParameter("permission")
    }

    @Override
    void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain) throws IOException, ServletException {
        if (!(req instanceof HttpServletRequest) || !(resp instanceof HttpServletResponse)) { chain.doFilter(req, resp); return }
        HttpServletRequest request = (HttpServletRequest) req
        HttpServletResponse response = (HttpServletResponse) resp
        // HttpSession session = request.getSession()
        ServletContext servletContext = req.getServletContext()

        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) servletContext.getAttribute("executionContextFactory")
        // check for and cleanly handle when executionContextFactory is not in place in ServletContext attr
        if (ecfi == null) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "System is initializing, try again soon.")
            return
        }
        ExecutionContextImpl activeEc = ecfi.activeContext.get()
        if (activeEc != null) {
            logger.warn("In MoquiAuthFilter.doFilter there is already an ExecutionContext for user ${activeEc.user.username} (from ${activeEc.forThreadId}:${activeEc.forThreadName}) in this thread (${Thread.currentThread().id}:${Thread.currentThread().name}), destroying")
            activeEc.destroy()
        }

        ExecutionContextImpl ec = ecfi.getEci()
        try {
            UserFacadeImpl ufi = ec.userFacade
            ufi.initFromHttpRequest(request, response)

            if (!ufi.username) {
                String message = ec.messageFacade.getErrorsString()
                if (!message) message = "Authentication required"
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, message)
                return
            }

            if (permission && !ufi.hasPermission(permission)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "User ${ufi.username} does not have permission ${permission}")
                return
            }

            chain.doFilter(req, resp)
        } finally {
            ec.destroy()
        }
    }

    @Override
    void destroy() {  }
}
