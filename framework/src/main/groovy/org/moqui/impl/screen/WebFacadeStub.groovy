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
package org.moqui.impl.screen

import groovy.transform.CompileStatic
import org.moqui.util.ContextStack
import org.moqui.context.ValidationError
import org.moqui.context.WebFacade
import org.moqui.context.MessageFacade.MessageInfo
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.WebFacadeImpl
import org.moqui.impl.service.RestApi
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.AsyncContext
import javax.servlet.DispatcherType
import javax.servlet.Filter
import javax.servlet.FilterRegistration
import javax.servlet.RequestDispatcher
import javax.servlet.Servlet
import javax.servlet.ServletContext
import javax.servlet.ServletException
import javax.servlet.ServletInputStream
import javax.servlet.ServletOutputStream
import javax.servlet.ServletRegistration
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.SessionCookieConfig
import javax.servlet.SessionTrackingMode
import javax.servlet.descriptor.JspConfigDescriptor
import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpSession
import javax.servlet.http.HttpSessionContext
import javax.servlet.http.HttpUpgradeHandler
import javax.servlet.http.Part
import java.security.Principal

/** A test stub for the WebFacade interface, used in ScreenTestImpl */
@CompileStatic
class WebFacadeStub implements WebFacade {
    protected final static Logger logger = LoggerFactory.getLogger(WebFacadeStub.class)

    ExecutionContextFactoryImpl ecfi
    ContextStack parameters = (ContextStack) null
    Map<String, Object> requestParameters = [:]
    Map<String, Object> sessionAttributes = [:]
    String requestMethod = "get"
    boolean skipJsonSerialize = false

    protected HttpSessionStub httpSession
    protected HttpServletRequestStub httpServletRequest
    protected ServletContextStub servletContext
    protected HttpServletResponseStub httpServletResponse

    protected StringWriter responseWriter = new StringWriter()
    protected PrintWriter responsePrintWriter = new PrintWriter(responseWriter)
    protected Object responseJsonObj = null

    WebFacadeStub(ExecutionContextFactoryImpl ecfi, Map<String, Object> requestParameters,
                  Map<String, Object> sessionAttributes, String requestMethod) {
        this.ecfi = ecfi
        if (requestParameters != null) this.requestParameters.putAll(requestParameters)
        if (sessionAttributes != null) this.sessionAttributes = sessionAttributes
        if (requestMethod != null) this.requestMethod = requestMethod

        servletContext = new ServletContextStub(this)
        httpSession = new HttpSessionStub(this)
        httpServletRequest = new HttpServletRequestStub(this)
        httpServletResponse = new HttpServletResponseStub(this)
    }

    String getResponseText() { responseWriter.flush(); return responseWriter.toString() }
    Object getResponseJsonObj() { return responseJsonObj }
    HttpServletResponseStub getHttpServletResponseStub() { return httpServletResponse }

    @Override String getRequestUrl() { return "TestRequestUrl" }

    @Override Map<String, Object> getParameters() {
        // only create when requested, then keep for additional requests
        if (parameters != null) return parameters

        ContextStack cs = new ContextStack()
        cs.push(sessionAttributes)
        cs.push(requestParameters)
        parameters = cs
        return parameters
    }

    @Override HttpServletRequest getRequest() { return httpServletRequest }
    @Override Map<String, Object> getRequestAttributes() { return requestParameters }
    @Override Map<String, Object> getRequestParameters() { return requestParameters }
    @Override Map<String, Object> getSecureRequestParameters() { return requestParameters }

    @Override String getHostName(boolean withPort) { return withPort ? "localhost:443" : "localhost" }

    @Override String getPathInfo() { return httpServletRequest.getPathInfo() }
    @Override ArrayList<String> getPathInfoList() { return WebFacadeImpl.getPathInfoList(request) }
    @Override String getRequestBodyText() { return null }

    @Override HttpServletResponse getResponse() { return httpServletResponse }
    @Override HttpSession getSession() { return httpSession }
    @Override Map<String, Object> getSessionAttributes() { return sessionAttributes }
    @Override String getSessionToken() { return "TestSessionToken" }
    @Override ServletContext getServletContext() { return servletContext }
    @Override Map<String, Object> getApplicationAttributes() { return sessionAttributes }

    @Override String getWebappRootUrl(boolean requireFullUrl, Boolean useEncryption) {
        return useEncryption ? "https://localhost" : "http://localhost"
    }

    @Override Map<String, Object> getErrorParameters() { return null }
    @Override List<MessageInfo> getSavedMessages() { return null }
    @Override List<MessageInfo> getSavedPublicMessages() { return null }
    @Override List<String> getSavedErrors() { return null }
    @Override List<ValidationError> getSavedValidationErrors() { return null }

    @Override List<Map> getScreenHistory() { return (List<Map>) sessionAttributes.get("moqui.screen.history") ?: new ArrayList<Map>() }

    @Override void sendJsonResponse(Object responseObj) {
        if (skipJsonSerialize) {
            responseJsonObj = responseObj
        } else {
            WebFacadeImpl.sendJsonResponseInternal(responseObj, ecfi.getEci(), httpServletRequest, httpServletResponse, requestAttributes)
        }
        /*
        String jsonStr
        if (responseObj instanceof CharSequence) {
            jsonStr = responseObj.toString()
        } else if (responseObj != null) {
            JsonBuilder jb = new JsonBuilder()
            if (responseObj instanceof Map) {
                jb.call((Map) responseObj)
            } else if (responseObj instanceof List) {
                jb.call((List) responseObj)
            } else {
                jb.call((Object) responseObj)
            }
            jsonStr = jb.toPrettyString()
        } else {
            jsonStr = ""
        }
        responseWriter.append(jsonStr)
        logger.info("WebFacadeStub sendJsonResponse ${jsonStr.length()} chars")
        */
    }

    @Override void sendTextResponse(String text) { sendTextResponse(text, "text/plain", null) }
    @Override void sendTextResponse(String text, String contentType, String filename) {
        WebFacadeImpl.sendTextResponseInternal(text, contentType, filename, ecfi.getEci(), httpServletRequest, httpServletResponse, requestAttributes)
        // responseWriter.append(text)
        // logger.info("WebFacadeStub sendTextResponse (${text.length()} chars, content type ${contentType}, filename: ${filename})")
    }

    @Override void sendResourceResponse(String location) {
        WebFacadeImpl.sendResourceResponseInternal(location, false, ecfi.getEci(), httpServletResponse)
        /*
        ResourceReference rr = ecfi.getResource().getLocationReference(location)
        if (rr == null) throw new IllegalArgumentException("Resource not found at: ${location}")
        String rrText = rr.getText()
        responseWriter.append(rrText)
        logger.info("WebFacadeStub sendResourceResponse ${rrText.length()} chars, location: ${location}")
        */
    }
    @Override void sendError(int errorCode, String message, Throwable origThrowable) { response.sendError(errorCode, message) }

    @Override void handleXmlRpcServiceCall() { throw new IllegalArgumentException("WebFacadeStub handleXmlRpcServiceCall not supported") }
    @Override void handleJsonRpcServiceCall() { throw new IllegalArgumentException("WebFacadeStub handleJsonRpcServiceCall not supported") }
    @Override void handleEntityRestCall(List<String> extraPathNameList, boolean masterNameInPath) {
        throw new IllegalArgumentException("WebFacadeStub handleEntityRestCall not supported") }

    @Override
    void handleServiceRestCall(List<String> extraPathNameList) {
        long startTime = System.currentTimeMillis()
        ExecutionContextImpl eci = ecfi.getEci()

        eci.contextStack.push(getParameters())
        RestApi.RestResult restResult = eci.serviceFacade.restApi.run(extraPathNameList, eci)
        eci.contextStack.pop()

        response.addIntHeader('X-Run-Time-ms', (System.currentTimeMillis() - startTime) as int)
        restResult.setHeaders(response)

        sendJsonResponse(restResult.responseObj)
    }

    @Override void handleSystemMessage(List<String> extraPathNameList) {
        throw new IllegalArgumentException("WebFacadeStub handleSystemMessage not supported") }

    static class HttpServletRequestStub implements HttpServletRequest {
        WebFacadeStub wfs

        HttpServletRequestStub(WebFacadeStub wfs) {
            this.wfs = wfs
        }

        String getAuthType() { return null }
        Cookie[] getCookies() { return new Cookie[0] }
        long getDateHeader(String s) { return System.currentTimeMillis() }
        String getHeader(String s) { return null }
        Enumeration getHeaders(String s) { return null }
        Enumeration getHeaderNames() { return null }
        int getIntHeader(String s) { return 0 }

        @Override String getMethod() { return wfs.requestMethod }

        @Override
        String getPathInfo() {
            // TODO
            return null
        }

        @Override
        String getPathTranslated() {
            // TODO
            return null
        }

        @Override
        String getContextPath() {
            // TODO
            return null
        }

        String getQueryString() { return null }
        String getRemoteUser() { return null }
        boolean isUserInRole(String s) { return false }
        Principal getUserPrincipal() { return null }
        String getRequestedSessionId() { return null }

        @Override
        String getRequestURI() {
            // TODO
            return null
        }

        @Override
        StringBuffer getRequestURL() {
            // TODO
            return null
        }

        @Override String getServletPath() { return "" }
        @Override HttpSession getSession(boolean b) { return wfs.httpSession }
        @Override HttpSession getSession() { return wfs.httpSession }

        @Override boolean isRequestedSessionIdValid() { return true }
        @Override boolean isRequestedSessionIdFromCookie() { return false }
        @Override boolean isRequestedSessionIdFromURL() { return false }
        @Override boolean isRequestedSessionIdFromUrl() { return false }

        @Override Object getAttribute(String s) { return wfs.requestParameters.get(s) }
        @Override Enumeration getAttributeNames() { return wfs.requestParameters.keySet() as Enumeration }

        @Override String getCharacterEncoding() { return "UTF-8" }
        @Override void setCharacterEncoding(String s) throws UnsupportedEncodingException { }
        @Override int getContentLength() { return 0 }
        @Override String getContentType() { return null }
        @Override ServletInputStream getInputStream() throws IOException { return null }

        @Override String getParameter(String s) { return wfs.requestParameters.get(s) as String }
        @Override Enumeration getParameterNames() {
            return new Enumeration() {
                Iterator i = wfs.requestParameters.keySet().iterator()
                boolean hasMoreElements() { return i.hasNext() }
                Object nextElement() { return i.next() }
            }
        }
        @Override String[] getParameterValues(String s) {
            Object valObj = wfs.requestParameters.get(s)
            if (valObj != null) {
                String[] retVal = new String[1]
                retVal[0] = valObj as String
                return retVal
            } else {
                return null
            }
        }
        @Override Map getParameterMap() { return wfs.requestParameters }

        @Override String getProtocol() { return "HTTP/1.1" }
        @Override String getScheme() { return "https" }
        @Override String getServerName() { return "localhost" }
        @Override int getServerPort() { return 443 }

        @Override BufferedReader getReader() throws IOException { return null }
        @Override String getRemoteAddr() { return "TestRemoteAddr" }
        @Override String getRemoteHost() { return "TestRemoteHost" }

        @Override void setAttribute(String s, Object o) { wfs.requestParameters.put(s, o) }
        @Override void removeAttribute(String s) { wfs.requestParameters.remove(s) }

        @Override Locale getLocale() { return Locale.ENGLISH }
        @Override Enumeration getLocales() { return null }

        @Override boolean isSecure() { return true }

        @Override RequestDispatcher getRequestDispatcher(String s) { return null }

        @Override String getRealPath(String s) { return null }

        @Override int getRemotePort() { return 0 }
        @Override String getLocalName() { return "TestLocalName" }
        @Override String getLocalAddr() { return "TestLocalAddr" }
        @Override int getLocalPort() { return 443 }

        // ========== New methods for Servlet 3.1 ==========
        @Override String changeSessionId() { throw new UnsupportedOperationException() }
        @Override boolean authenticate(HttpServletResponse response) throws IOException, ServletException { throw new UnsupportedOperationException() }
        @Override void login(String username, String password) throws ServletException { throw new UnsupportedOperationException() }
        @Override void logout() throws ServletException { throw new UnsupportedOperationException() }
        @Override Collection<Part> getParts() throws IOException, ServletException { throw new UnsupportedOperationException() }
        @Override Part getPart(String name) throws IOException, ServletException { throw new UnsupportedOperationException() }
        @Override def <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException { return null }
        @Override long getContentLengthLong() { return 0 }
        @Override ServletContext getServletContext() { return wfs.servletContext }
        @Override AsyncContext startAsync() throws IllegalStateException { throw new UnsupportedOperationException("startAsync not supported") }
        @Override AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException { return null }
        @Override boolean isAsyncStarted() { return false }
        @Override boolean isAsyncSupported() { return false }
        @Override AsyncContext getAsyncContext() { throw new UnsupportedOperationException("getAsyncContext not supported") }
        @Override DispatcherType getDispatcherType() { throw new UnsupportedOperationException("getDispatcherType not supported") }
    }

    static class HttpSessionStub implements HttpSession {
        WebFacadeStub wfs
        HttpSessionStub(WebFacadeStub wfs) { this.wfs = wfs }

        long getCreationTime() { return System.currentTimeMillis() }
        String getId() { return "TestSessionId" }
        long getLastAccessedTime() { return System.currentTimeMillis() }
        ServletContext getServletContext() { return wfs.servletContext }
        void setMaxInactiveInterval(int i) { }
        int getMaxInactiveInterval() { return 0 }
        HttpSessionContext getSessionContext() { return null }

        @Override Object getAttribute(String s) { return wfs.sessionAttributes.get(s) }
        @Override Object getValue(String s) { return wfs.sessionAttributes.get(s) }
        @Override Enumeration getAttributeNames() {
            return new Enumeration() {
                Iterator i = wfs.sessionAttributes.keySet().iterator()
                boolean hasMoreElements() { return i.hasNext() }
                Object nextElement() { return i.next() }
            }
        }
        @Override String[] getValueNames() { return null }
        @Override void setAttribute(String s, Object o) { wfs.sessionAttributes.put(s, o) }
        @Override void putValue(String s, Object o) { wfs.sessionAttributes.put(s, o) }
        @Override void removeAttribute(String s) { wfs.sessionAttributes.remove(s) }
        @Override void removeValue(String s) { wfs.sessionAttributes.remove(s) }

        void invalidate() { }
        boolean isNew() { return false }
    }

    static class ServletContextStub implements ServletContext {
        WebFacadeStub wfs
        ServletContextStub(WebFacadeStub wfs) { this.wfs = wfs }

        ServletContext getContext(String s) { return this }
        String getContextPath() { return "" }
        int getMajorVersion() { return 3 }
        int getMinorVersion() { return 0 }
        String getMimeType(String s) { return null }
        Set getResourcePaths(String s) { return new HashSet() }
        URL getResource(String s) throws MalformedURLException { return null }
        InputStream getResourceAsStream(String s) { return null }
        RequestDispatcher getRequestDispatcher(String s) { return null }
        RequestDispatcher getNamedDispatcher(String s) { return null }
        Servlet getServlet(String s) throws ServletException { return null }
        Enumeration getServlets() { return null }
        Enumeration getServletNames() { return null }
        void log(String s) { }
        void log(Exception e, String s) { }
        void log(String s, Throwable throwable) { }
        String getRealPath(String s) { return null }
        String getServerInfo() { return "Web Facade Stub/1.0" }

        @Override String getInitParameter(String s) { return s == "moqui-name" ? "webroot" : null }
        @Override Enumeration getInitParameterNames() {
            return new Enumeration() {
                Iterator i = ['moqui-name'].iterator()
                boolean hasMoreElements() { return i.hasNext() }
                Object nextElement() { return i.next() }
            }
        }
        @Override Object getAttribute(String s) { return wfs.sessionAttributes.get(s) }
        @Override Enumeration getAttributeNames() {
            return new Enumeration() {
                Iterator i = wfs.sessionAttributes.keySet().iterator()
                boolean hasMoreElements() { return i.hasNext() }
                Object nextElement() { return i.next() }
            }
        }
        @Override void setAttribute(String s, Object o) { wfs.sessionAttributes.put(s, o) }
        @Override void removeAttribute(String s) { wfs.sessionAttributes.remove(s) }
        @Override String getServletContextName() { return "Moqui Root Webapp" }

        // ========== New methods for Servlet 3.1 ==========
        @Override int getEffectiveMajorVersion() { return 3 }
        @Override int getEffectiveMinorVersion() { return 1 }
        @Override boolean setInitParameter(String name, String value) { return false }
        @Override ServletRegistration.Dynamic addServlet(String servletName, String className) { throw new UnsupportedOperationException() }
        @Override ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) { throw new UnsupportedOperationException() }
        @Override ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) { throw new UnsupportedOperationException() }
        @Override def <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException { throw new UnsupportedOperationException() }
        @Override ServletRegistration getServletRegistration(String servletName) { throw new UnsupportedOperationException() }
        @Override Map<String, ? extends ServletRegistration> getServletRegistrations() { throw new UnsupportedOperationException() }
        @Override FilterRegistration.Dynamic addFilter(String filterName, String className) { throw new UnsupportedOperationException() }
        @Override FilterRegistration.Dynamic addFilter(String filterName, Filter filter) { throw new UnsupportedOperationException() }
        @Override FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) { throw new UnsupportedOperationException() }
        @Override def <T extends Filter> T createFilter(Class<T> clazz) throws ServletException { throw new UnsupportedOperationException() }
        @Override FilterRegistration getFilterRegistration(String filterName) { throw new UnsupportedOperationException() }
        @Override Map<String, ? extends FilterRegistration> getFilterRegistrations() { throw new UnsupportedOperationException() }
        @Override SessionCookieConfig getSessionCookieConfig() { throw new UnsupportedOperationException() }
        @Override void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) { throw new UnsupportedOperationException() }
        @Override Set<SessionTrackingMode> getDefaultSessionTrackingModes() { throw new UnsupportedOperationException() }
        @Override Set<SessionTrackingMode> getEffectiveSessionTrackingModes() { throw new UnsupportedOperationException() }
        @Override void addListener(String className) { throw new UnsupportedOperationException() }
        @Override def <T extends EventListener> void addListener(T t) { throw new UnsupportedOperationException() }
        @Override void addListener(Class<? extends EventListener> listenerClass) { throw new UnsupportedOperationException() }
        @Override def <T extends EventListener> T createListener(Class<T> clazz) throws ServletException { throw new UnsupportedOperationException() }
        @Override JspConfigDescriptor getJspConfigDescriptor() { throw new UnsupportedOperationException() }
        @Override ClassLoader getClassLoader() { throw new UnsupportedOperationException() }
        @Override void declareRoles(String... roleNames) { throw new UnsupportedOperationException() }
        @Override String getVirtualServerName() { throw new UnsupportedOperationException() }
    }

    static class HttpServletResponseStub implements HttpServletResponse {
        WebFacadeStub wfs

        String characterEncoding = null
        int contentLength = 0
        String contentType = null
        Locale locale = Locale.default
        int status = SC_OK
        Map<String, Object> headers = [:]

        HttpServletResponseStub(WebFacadeStub wfs) { this.wfs = wfs }

        @Override void addCookie(Cookie cookie) { }

        @Override boolean containsHeader(String s) { return headers.containsKey(s) }

        @Override String encodeURL(String s) { return null }
        @Override String encodeRedirectURL(String s) { return null }
        @Override String encodeUrl(String s) { return null }
        @Override String encodeRedirectUrl(String s) { return null }

        @Override void sendError(int i, String s) throws IOException {
            status = i
            if (s != null) wfs.responseWriter.append(s)
        }
        @Override void sendError(int i) throws IOException { status = i }
        @Override void sendRedirect(String s) throws IOException { logger.info("HttpServletResponseStub sendRedirect to: ${s}") }

        @Override void setDateHeader(String s, long l) { headers.put(s, l) }
        @Override void addDateHeader(String s, long l) { headers.put(s, l) }
        @Override void setHeader(String s, String s1) { headers.put(s, s1) }
        @Override void addHeader(String s, String s1) { headers.put(s, s1) }
        @Override void setIntHeader(String s, int i) { headers.put(s, i) }
        @Override void addIntHeader(String s, int i) { headers.put(s, i) }

        @Override void setStatus(int i, String s) { status = i; wfs.responseWriter.append(s) }

        @Override String getCharacterEncoding() { return characterEncoding }
        @Override String getContentType() { return contentType }

        @Override ServletOutputStream getOutputStream() throws IOException {
            throw new UnsupportedOperationException("Using WebFacadeStub getOutputStream is not supported") }
        @Override PrintWriter getWriter() throws IOException { return wfs.responsePrintWriter }

        @Override void setBufferSize(int i) { }
        @Override int getBufferSize() { return wfs.responseWriter.getBuffer().length() }

        @Override void flushBuffer() throws IOException { wfs.responseWriter.flush() }
        @Override void resetBuffer() { wfs.responseWriter = new StringWriter() }
        @Override boolean isCommitted() { return false }
        @Override void reset() { resetBuffer(); status = SC_OK; headers.clear() }
        @Override void setLocale(Locale locale) { this.locale = locale }
        @Override Locale getLocale() { return locale }

        // ========== New methods for Servlet 3.1 ==========
        @Override String getHeader(String name) { return headers.get(name) as String }
        @Override Collection<String> getHeaders(String name) { return [headers.get(name) as String] }
        @Override Collection<String> getHeaderNames() { return headers.keySet() }
        @Override void setContentLengthLong(long len) { }
    }
}
