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
package org.moqui.impl.context

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

import org.apache.commons.codec.binary.Base64
import org.apache.commons.fileupload.FileItem
import org.apache.commons.fileupload.FileItemFactory
import org.apache.commons.fileupload.disk.DiskFileItemFactory
import org.apache.commons.fileupload.servlet.ServletFileUpload

import org.moqui.context.*
import org.moqui.entity.EntityNotFoundException
import org.moqui.entity.EntityValueNotFoundException
import org.moqui.impl.StupidUtilities
import org.moqui.impl.StupidWebUtilities
import org.moqui.impl.context.ExecutionContextFactoryImpl.WebappInfo
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.screen.ScreenDefinition
import org.moqui.impl.screen.ScreenUrlInfo
import org.moqui.impl.service.RestApi
import org.moqui.impl.service.ServiceJsonRpcDispatcher
import org.moqui.impl.service.ServiceXmlRpcDispatcher
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

import javax.servlet.ServletContext
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpSession
import java.security.SecureRandom

/** This class is a facade to easily get information from and about the web context. */
@CompileStatic
class WebFacadeImpl implements WebFacade {
    protected final static Logger logger = LoggerFactory.getLogger(WebFacadeImpl.class)

    // Not using shared root URL cache because causes issues when requests come to server through different hosts/etc:
    // protected static final Map<String, String> webappRootUrlByParms = new HashMap()

    protected ExecutionContextImpl eci
    protected String webappMoquiName
    protected HttpServletRequest request
    protected HttpServletResponse response

    protected Map<String, Object> savedParameters = null
    protected Map<String, Object> multiPartParameters = null
    protected Map<String, Object> jsonParameters = null
    protected Map<String, Object> declaredPathParameters = null

    protected ContextStack parameters = null
    protected Map<String, Object> requestAttributes = null
    protected Map<String, Object> requestParameters = null
    protected Map<String, Object> sessionAttributes = null
    protected Map<String, Object> applicationAttributes = null

    protected Map<String, Object> errorParameters = null

    protected List<String> savedMessages = null
    protected List<String> savedErrors = null
    protected List<ValidationError> savedValidationErrors = null

    WebFacadeImpl(String webappMoquiName, HttpServletRequest request, HttpServletResponse response,
                  ExecutionContextImpl eci) {
        this.eci = eci
        this.webappMoquiName = webappMoquiName
        this.request = request
        this.response = response

        // NOTE: the Visit is not setup here but rather in the MoquiEventListener (for init and destroy)
        request.setAttribute("ec", eci)

        // get any parameters saved to the session from the last request, and clear that session attribute if there
        savedParameters = (Map) request.session.getAttribute("moqui.saved.parameters")
        if (savedParameters != null) request.session.removeAttribute("moqui.saved.parameters")

        errorParameters = (Map) request.session.getAttribute("moqui.error.parameters")
        if (errorParameters != null) request.session.removeAttribute("moqui.error.parameters")

        // get any messages saved to the session, and clear them from the session
        if (session.getAttribute("moqui.message.messages")) {
            savedMessages = (List<String>) session.getAttribute("moqui.message.messages")
            session.removeAttribute("moqui.message.messages")
        }
        if (session.getAttribute("moqui.message.errors")) {
            savedErrors = (List<String>) session.getAttribute("moqui.message.errors")
            session.removeAttribute("moqui.message.errors")
        }
        if (session.getAttribute("moqui.message.validationErrors")) {
            savedValidationErrors = (List<ValidationError>) session.getAttribute("moqui.message.validationErrors")
            session.removeAttribute("moqui.message.validationErrors")
        }

        // if there is a JSON document submitted consider those as parameters too
        String contentType = request.getHeader("Content-Type")
        if (contentType && (contentType.contains("application/json") || contentType.contains("text/json"))) {
            JsonSlurper slurper = new JsonSlurper()
            Object jsonObj = null
            try {
                jsonObj = slurper.parse(new BufferedReader(new InputStreamReader(request.getInputStream(),
                        request.getCharacterEncoding() ?: "UTF-8")))
            } catch (Throwable t) {
                logger.error("Error parsing HTTP request body JSON: ${t.toString()}", t)
                jsonParameters = [_requestBodyJsonParseError:t.getMessage()] as Map<String, Object>
            }
            if (jsonObj instanceof Map) {
                jsonParameters = (Map<String, Object>) jsonObj
            } else if (jsonObj instanceof List) {
                jsonParameters = [_requestBodyJsonList:jsonObj]
            }
            // logger.warn("=========== Got JSON HTTP request body: ${jsonParameters}")
        }

        // if this is a multi-part request, get the data for it
        if (ServletFileUpload.isMultipartContent(request)) {
            multiPartParameters = new HashMap()
            FileItemFactory factory = makeDiskFileItemFactory(request.session.getServletContext())
            ServletFileUpload upload = new ServletFileUpload(factory)

            List<FileItem> items = (List<FileItem>) upload.parseRequest(request)
            List<FileItem> fileUploadList = []
            multiPartParameters.put("_fileUploadList", fileUploadList)

            for (FileItem item in items) {
                if (item.isFormField()) {
                    multiPartParameters.put(item.getFieldName(), item.getString("UTF-8"))
                } else {
                    // put the FileItem itself in the Map to be used by the application code
                    multiPartParameters.put(item.getFieldName(), item)
                    fileUploadList.add(item)

                    /* Stuff to do with the FileItem:
                      - get info about the uploaded file
                        String fieldName = item.getFieldName()
                        String fileName = item.getName()
                        String contentType = item.getContentType()
                        boolean isInMemory = item.isInMemory()
                        long sizeInBytes = item.getSize()

                      - get the bytes in memory
                        byte[] data = item.get()

                      - write the data to a File
                        File uploadedFile = new File(...)
                        item.write(uploadedFile)

                      - get the bytes in a stream
                        InputStream uploadedStream = item.getInputStream()
                        ...
                        uploadedStream.close()
                     */
                }
            }
        }

        // create the session token if needed (protection against CSRF/XSRF attacks; see ScreenRenderImpl)
        String sessionToken = session.getAttribute("moqui.session.token")
        if (!sessionToken) {
            SecureRandom sr = new SecureRandom()
            byte[] randomBytes = new byte[20]
            sr.nextBytes(randomBytes)
            sessionToken = Base64.encodeBase64URLSafeString(randomBytes)
            session.setAttribute("moqui.session.token", sessionToken)
            request.setAttribute("moqui.session.token.created", "true")
        }
    }

    @Override
    @CompileStatic
    String getSessionToken() { return session.getAttribute("moqui.session.token") }

    // ExecutionContextImpl getEci() { eci }
    void runFirstHitInVisitActions() {
        WebappInfo wi = eci.ecfi.getWebappInfo(webappMoquiName)
        if (wi.firstHitInVisitActions) wi.firstHitInVisitActions.run(eci)
    }
    void runBeforeRequestActions() {
        WebappInfo wi = eci.ecfi.getWebappInfo(webappMoquiName)
        if (wi.beforeRequestActions) wi.beforeRequestActions.run(eci)
    }
    void runAfterRequestActions() {
        WebappInfo wi = eci.ecfi.getWebappInfo(webappMoquiName)
        if (wi.afterRequestActions) wi.afterRequestActions.run(eci)
    }
    void runAfterLoginActions() {
        WebappInfo wi = eci.ecfi.getWebappInfo(webappMoquiName)
        if (wi.afterLoginActions) wi.afterLoginActions.run(eci)
    }
    void runBeforeLogoutActions() {
        WebappInfo wi = eci.ecfi.getWebappInfo(webappMoquiName)
        if (wi.beforeLogoutActions) wi.beforeLogoutActions.run(eci)
    }

    @CompileStatic
    void saveScreenHistory(ScreenUrlInfo.UrlInstance urlInstanceOrig) {
        ScreenUrlInfo sui = urlInstanceOrig.sui
        ScreenDefinition targetScreen = urlInstanceOrig.sui.targetScreen

        // don't save standalone screens
        if (sui.lastStandalone || targetScreen.isStandalone()) return
        // don't save transition requests, just screens
        if (urlInstanceOrig.getTargetTransition() != null) return

        LinkedList<Map> screenHistoryList = (LinkedList<Map>) session.getAttribute("moqui.screen.history")
        if (screenHistoryList == null) {
            screenHistoryList = new LinkedList<Map>()
            session.setAttribute("moqui.screen.history", screenHistoryList)
        }

        ScreenUrlInfo.UrlInstance urlInstance = urlInstanceOrig.cloneUrlInstance()
        // ignore the page index for history
        urlInstance.getParameterMap().remove("pageIndex")
        // logger.warn("======= parameters: ${urlInstance.getParameterMap()}")
        String urlWithParams = urlInstance.getUrlWithParams()
        String urlNoParams = urlInstance.getUrl()
        // logger.warn("======= urlWithParams: ${urlWithParams}")

        // if is the same as last screen skip it
        Map firstItem = screenHistoryList.size() > 0 ? screenHistoryList.get(0) : null
        if (firstItem != null && firstItem.url == urlWithParams) return

        String targetMenuName = targetScreen.getDefaultMenuName()
        // may need a better way to identify login screens, for now just look for "Login"
        if (targetMenuName == "Login") return


        StringBuilder nameBuilder = new StringBuilder()
        // append parent screen name
        ScreenDefinition parentScreen = sui.getParentScreen()
        if (parentScreen != null) {
            if (parentScreen.getLocation() != sui.rootSd.getLocation())
                nameBuilder.append(parentScreen.getDefaultMenuName()).append(' - ')
        }
        // append target screen name
        if (targetMenuName.contains('${')) {
            nameBuilder.append(eci.getResource().expand(targetMenuName, targetScreen.getLocation()))
        } else {
            nameBuilder.append(targetMenuName)
            // append parameter values
            Map parameters = urlInstance.getParameterMap()
            if (parameters) {
                nameBuilder.append(' (')
                int pCount = 0
                Iterator<Map.Entry<String, String>> entryIter = parameters.entrySet().iterator()
                while (entryIter.hasNext() && pCount < 2) {
                    Map.Entry<String, String> entry = entryIter.next()
                    if (entry.key.contains("_op")) continue
                    if (entry.key.contains("_not")) continue
                    if (entry.key.contains("_ic")) continue
                    if (entry.key.contains("moquiSessionToken")) continue
                    if (!entry.value.trim()) continue
                    nameBuilder.append(entry.value)
                    pCount++
                    if (entryIter.hasNext() && pCount < 2) nameBuilder.append(', ')
                }
                nameBuilder.append(')')
            }
        }

        // remove existing item(s) from list with same URL
        Iterator<Map> screenHistoryIter = screenHistoryList.iterator()
        while (screenHistoryIter.hasNext()) {
            Map screenHistory = screenHistoryIter.next()
            if (screenHistory.url == urlWithParams) screenHistoryIter.remove()
        }

        // add to history list
        screenHistoryList.addFirst([name:nameBuilder.toString(), url:urlWithParams, urlNoParams:urlNoParams,
                image:sui.menuImage, imageType:sui.menuImageType, screenLocation:targetScreen.getLocation()])

        // trim the list if needed; keep 40, whatever uses it may display less
        while (screenHistoryList.size() > 40) screenHistoryList.removeLast()
    }

    @Override
    @CompileStatic
    List<Map> getScreenHistory() { return (LinkedList<Map>) session.getAttribute("moqui.screen.history") }


    @Override
    @CompileStatic
    String getRequestUrl() {
        StringBuilder requestUrl = new StringBuilder()
        requestUrl.append(request.getScheme())
        requestUrl.append("://" + request.getServerName())
        if (request.getServerPort() != 80 && request.getServerPort() != 443) requestUrl.append(":" + request.getServerPort())
        requestUrl.append(request.getRequestURI())
        if (request.getQueryString()) requestUrl.append("?" + request.getQueryString())
        return requestUrl.toString()
    }

    @CompileStatic
    void addDeclaredPathParameter(String name, String value) {
        if (declaredPathParameters == null) declaredPathParameters = new HashMap()
        declaredPathParameters.put(name, value)
    }

    @Override
    @CompileStatic
    Map<String, Object> getParameters() {
        // NOTE: no blocking in these methods because the WebFacadeImpl is created for each thread

        // only create when requested, then keep for additional requests
        if (parameters != null) return parameters

        // Uses the approach of creating a series of this objects wrapping the other non-Map attributes/etc instead of
        // copying everything from the various places into a single combined Map; this should be much faster to create
        // and only slightly slower when running.
        ContextStack cs = new ContextStack()
        cs.push(getRequestParameters())
        cs.push(getApplicationAttributes())
        cs.push(getSessionAttributes())
        cs.push(getRequestAttributes())
        // add an extra Map for anything added so won't go in  request attributes (can put there explicitly if desired)
        cs.push()
        parameters = cs
        return parameters
    }

    @Override
    @CompileStatic
    HttpServletRequest getRequest() { return request }
    @Override
    @CompileStatic
    Map<String, Object> getRequestAttributes() {
        if (requestAttributes != null) return requestAttributes
        requestAttributes = new StupidWebUtilities.RequestAttributeMap(request)
        return requestAttributes
    }
    @Override
    @CompileStatic
    Map<String, Object> getRequestParameters() {
        if (requestParameters != null) return requestParameters

        ContextStack cs = new ContextStack()
        if (savedParameters) cs.push(savedParameters)
        if (multiPartParameters) cs.push(multiPartParameters)
        if (jsonParameters) cs.push(jsonParameters)
        if (declaredPathParameters) cs.push(declaredPathParameters)
        cs.push((Map<String, Object>) request.getParameterMap())
        cs.push(StupidWebUtilities.getPathInfoParameterMap(request.getPathInfo()))

        // NOTE: the CanonicalizeMap cleans up character encodings, and unwraps lists of values with a single entry
        requestParameters = new StupidWebUtilities.CanonicalizeMap(cs)
        return requestParameters
    }
    @Override
    @CompileStatic
    Map<String, Object> getSecureRequestParameters() {
        ContextStack cs = new ContextStack()
        if (savedParameters) cs.push(savedParameters)
        if (multiPartParameters) cs.push(multiPartParameters)
        if (jsonParameters) cs.push(jsonParameters)
        if (!request.getQueryString()) cs.push((Map<String, Object>) request.getParameterMap())

        // NOTE: the CanonicalizeMap cleans up character encodings, and unwraps lists of values with a single entry
        return new StupidWebUtilities.CanonicalizeMap(cs)
    }
    @Override
    @CompileStatic
    String getHostName(boolean withPort) {
        URL requestUrl = new URL(getRequest().getRequestURL().toString())
        String hostName = null
        Integer port = null
        try {
            hostName = requestUrl.getHost()
            port = requestUrl.getPort()
            // logger.info("Got hostName [${hostName}] from getRequestURL [${webFacade.getRequest().getRequestURL()}]")
        } catch (Exception e) {
            /* ignore it, default to getServerName() result */
            logger.trace("Error getting hostName from getRequestURL: ", e)
        }
        if (!hostName) hostName = getRequest().getServerName()
        if (!port || port == -1) port = getRequest().getServerPort()
        if (!port || port == -1) port = getRequest().isSecure() ? 443 : 80

        return withPort ? hostName + ":" + port : hostName
    }


    @Override
    HttpServletResponse getResponse() { return response }

    @Override
    HttpSession getSession() { return request.getSession(true) }
    @Override
    @CompileStatic
    Map<String, Object> getSessionAttributes() {
        if (sessionAttributes) return sessionAttributes
        sessionAttributes = new StupidWebUtilities.SessionAttributeMap(getSession())
        return sessionAttributes
    }

    @Override
    ServletContext getServletContext() { return getSession().getServletContext() }
    @Override
    @CompileStatic
    Map<String, Object> getApplicationAttributes() {
        if (applicationAttributes) return applicationAttributes
        applicationAttributes = new StupidWebUtilities.ServletContextAttributeMap(getSession().getServletContext())
        return applicationAttributes
    }
    @Override
    @CompileStatic
    String getWebappRootUrl(boolean requireFullUrl, Boolean useEncryption) {
        return getWebappRootUrl(this.webappMoquiName, null, requireFullUrl, useEncryption, eci)
    }

    @CompileStatic
    static String getWebappRootUrl(String webappName, String servletContextPath, boolean requireFullUrl, Boolean useEncryption, ExecutionContextImpl eci) {
        WebFacade webFacade = eci.getWeb()
        HttpServletRequest request = webFacade?.getRequest()
        boolean requireEncryption = useEncryption == null && request != null ? request.isSecure() : useEncryption
        boolean needFullUrl = requireFullUrl || request == null ||
                (requireEncryption && !request.isSecure()) || (!requireEncryption && request.isSecure())

        /* Not using shared root URL cache because causes issues when requests come to server through different hosts/etc:
        String cacheKey = webappName + servletContextPath + needFullUrl.toString() + requireEncryption.toString()
        String cachedRootUrl = webappRootUrlByParms.get(cacheKey)
        if (cachedRootUrl != null) return cachedRootUrl

        String urlValue = makeWebappRootUrl(webappName, servletContextPath, eci, webFacade, requireEncryption, needFullUrl)
        webappRootUrlByParms.put(cacheKey, urlValue)
        return urlValue
         */

        // cache the root URLs just within the request, common to generate various URLs in a single request
        String cacheKey = null
        if (request != null) {
            cacheKey = webappName + servletContextPath + needFullUrl.toString() + requireEncryption.toString()
            String cachedRootUrl = request.getAttribute(cacheKey)
            if (cachedRootUrl != null) return cachedRootUrl
        }

        String urlValue = makeWebappRootUrl(webappName, servletContextPath, eci, webFacade, requireEncryption, needFullUrl)
        if (cacheKey) request.setAttribute(cacheKey, urlValue)
        return urlValue
    }
    static String makeWebappRootUrl(String webappName, String servletContextPath, ExecutionContextImpl eci, WebFacade webFacade,
                                    boolean requireEncryption, boolean needFullUrl) {
        HttpServletRequest request = webFacade.getRequest()
        MNode webappNode = eci.ecfi.confXmlRoot.first("webapp-list").first({ MNode it -> it.name == "webapp" && it.attribute("name") == webappName })
        StringBuilder urlBuilder = new StringBuilder()
        // build base from conf
        if (needFullUrl && webappNode) {
            if (request.getScheme() == "https" || (requireEncryption && webappNode.attribute("https-enabled") != "false")) {
                urlBuilder.append("https://")
                if (webappNode.attribute("https-host")) {
                    urlBuilder.append(webappNode.attribute("https-host"))
                } else {
                    if (webFacade != null) {
                        urlBuilder.append(webFacade.getHostName(false))
                    } else {
                        // uh-oh, no web context, default to localhost
                        urlBuilder.append("localhost")
                    }
                }
                String httpsPort = webappNode.attribute("https-port")
                // try the local port; this won't work when switching from http to https, conf required for that
                if (!httpsPort && webFacade != null && request.isSecure())
                    httpsPort = request.getServerPort() as String
                if (httpsPort && httpsPort != "443") urlBuilder.append(":").append(httpsPort)
            } else {
                urlBuilder.append("http://")
                if (webappNode.attribute("http-host")) {
                    urlBuilder.append(webappNode.attribute("http-host"))
                } else {
                    if (webFacade) {
                        urlBuilder.append(webFacade.getHostName(false))
                    } else {
                        // uh-oh, no web context, default to localhost
                        urlBuilder.append("localhost")
                        logger.trace("No webFacade in place, defaulting to localhost for hostName")
                    }
                }
                String httpPort = webappNode.attribute("http-port")
                // try the server port; this won't work when switching from https to http, conf required for that
                if (!httpPort && webFacade != null && !request.isSecure())
                    httpPort = request.getServerPort() as String
                if (httpPort && httpPort != "80") urlBuilder.append(":").append(httpPort)
            }
            urlBuilder.append("/")
        } else {
            // can't get these settings, hopefully a URL from the root will do
            urlBuilder.append("/")
        }

        // add servletContext.contextPath
        if (!servletContextPath && webFacade)
            servletContextPath = webFacade.getServletContext().getContextPath()
        if (servletContextPath) {
            if (servletContextPath.startsWith("/")) servletContextPath = servletContextPath.substring(1)
            urlBuilder.append(servletContextPath)
        }

        // make sure we don't have a trailing slash
        if (urlBuilder.charAt(urlBuilder.length()-1) == (char) '/') urlBuilder.deleteCharAt(urlBuilder.length()-1)

        String urlValue = urlBuilder.toString()
        return urlValue
    }

    String getRequestDetails() {
        StringBuilder sb = new StringBuilder()
        sb.append("Request: ").append(request.getMethod()).append(" ").append(request.getRequestURL()).append("\n")
        sb.append("Scheme: ").append(request.getScheme()).append(", Secure? ").append(request.isSecure()).append("\n")
        sb.append("Remote: ").append(request.getRemoteAddr()).append(" - ").append(request.getRemoteHost()).append("\n")
        for (String hn in request.getHeaderNames()) {
            sb.append("Header: ").append(hn).append(" = ")
            for (String hv in request.getHeaders(hn)) sb.append("[").append(hv).append("] ")
            sb.append("\n")
        }
        for (String pn in request.getParameterNames()) sb.append("Parameter: ").append(pn).append(" = ").append(request.getParameterValues(pn)).append("\n")
        return sb.toString()
    }

    @Override
    @CompileStatic
    Map<String, Object> getErrorParameters() { return errorParameters }
    @Override
    @CompileStatic
    List<String> getSavedMessages() { return savedMessages }
    @Override
    @CompileStatic
    List<String> getSavedErrors() { return savedErrors }
    @Override
    @CompileStatic
    List<ValidationError> getSavedValidationErrors() { return savedValidationErrors }


    @Override
    @CompileStatic
    void sendJsonResponse(Object responseObj) {
        sendJsonResponseInternal(responseObj, eci, request, response, requestAttributes)
    }
    @CompileStatic
    static void sendJsonResponseInternal(Object responseObj, ExecutionContextImpl eci, HttpServletRequest request,
                                         HttpServletResponse response, Map<String, Object> requestAttributes) {
        String jsonStr
        if (responseObj instanceof CharSequence) {
            jsonStr = responseObj.toString()
        } else {
            if (eci.message.messages) {
                if (responseObj == null) {
                    responseObj = [messages:eci.message.getMessagesString()] as Map<String, Object>
                } else if (responseObj instanceof Map && !responseObj.containsKey("messages")) {
                    Map responseMap = new HashMap()
                    responseMap.putAll(responseObj)
                    responseMap.put("messages", eci.message.getMessagesString())
                    responseObj = responseMap
                }
            }

            if (eci.getMessage().hasError()) {
                JsonBuilder jb = new JsonBuilder()
                // if the responseObj is a Map add all of it's data
                if (responseObj instanceof Map) {
                    // only add an errors if it is not a jsonrpc response (JSON RPC has it's own error handling)
                    if (!responseObj.containsKey("jsonrpc")) {
                        Map responseMap = new HashMap()
                        responseMap.putAll(responseObj)
                        responseMap.put("errors", eci.message.errorsString)
                        responseObj = responseMap
                    }
                    jb.call(responseObj)
                } else if (responseObj != null) {
                    logger.error("Error found when sending JSON string but JSON object is not a Map so not sending: ${eci.message.errorsString}")
                    jb.call(responseObj)
                }

                jsonStr = jb.toString()
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            } else if (responseObj != null) {
                // logger.warn("========== Sending JSON for object: ${responseObj}")
                JsonBuilder jb = new JsonBuilder()
                if (responseObj instanceof Map) {
                    jb.call((Map) responseObj)
                } else if (responseObj instanceof List) {
                    jb.call((List) responseObj)
                } else {
                    jb.call((Object) responseObj)
                }
                jsonStr = jb.toPrettyString()
                response.setStatus(HttpServletResponse.SC_OK)
            } else {
                jsonStr = ""
                response.setStatus(HttpServletResponse.SC_OK)
            }
        }

        if (!jsonStr) return

        // logger.warn("========== Sending JSON string: ${jsonStr}")
        response.setContentType("application/json")
        // NOTE: String.length not correct for byte length
        String charset = response.getCharacterEncoding() ?: "UTF-8"
        int length = jsonStr.getBytes(charset).length
        response.setContentLength(length)

        try {
            response.writer.write(jsonStr)
            response.writer.flush()
            if (logger.infoEnabled) {
                Long startTime = (Long) requestAttributes.get("moquiRequestStartTime")
                String timeMsg = ""
                if (startTime) timeMsg = "in [${(System.currentTimeMillis()-startTime)/1000}] seconds"
                logger.info("Sent JSON response of length [${length}] with [${charset}] encoding ${timeMsg} for ${request.getMethod()} request to ${request.getPathInfo()}")
            }
        } catch (IOException e) {
            logger.error("Error sending JSON string response", e)
        }
    }

    @Override
    @CompileStatic
    void sendTextResponse(String text) {
        sendTextResponseInternal(text, "text/plain", null, eci, request, response, requestAttributes)
    }
    @Override
    @CompileStatic
    void sendTextResponse(String text, String contentType, String filename) {
        sendTextResponseInternal(text, contentType, filename, eci, request, response, requestAttributes)
    }
    @CompileStatic
    static void sendTextResponseInternal(String text, String contentType, String filename, ExecutionContextImpl eci,
                                         HttpServletRequest request, HttpServletResponse response,
                                         Map<String, Object> requestAttributes) {
        if (!contentType) contentType = "text/plain"
        String responseText
        if (eci.getMessage().hasError()) {
            responseText = eci.message.errorsString
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
        } else {
            responseText = text
            response.setStatus(HttpServletResponse.SC_OK)
        }

        response.setContentType(contentType)
        // NOTE: String.length not correct for byte length
        String charset = response.getCharacterEncoding() ?: "UTF-8"
        int length = responseText ? responseText.getBytes(charset).length : 0I
        response.setContentLength(length)

        if (!filename) {
            response.addHeader("Content-Disposition", "inline")
        } else {
            response.addHeader("Content-Disposition", "attachment; filename=\"${filename}\"; filename*=utf-8''${StupidUtilities.encodeAsciiFilename(filename)}")
        }

        try {
            if (responseText) response.writer.write(responseText)
            response.writer.flush()
            if (logger.infoEnabled) {
                Long startTime = (Long) requestAttributes.get("moquiRequestStartTime")
                String timeMsg = ""
                if (startTime) timeMsg = "in [${(System.currentTimeMillis()-startTime)/1000}] seconds"
                logger.info("Sent text (${contentType}) response of length [${length}] with [${charset}] encoding ${timeMsg} for ${request.getMethod()} request to ${request.getPathInfo()}")
            }
        } catch (IOException e) {
            logger.error("Error sending text response", e)
        }
    }

    @Override
    @CompileStatic
    void sendResourceResponse(String location) {
        sendResourceResponseInternal(location, false, eci, response, requestAttributes)
    }
    @CompileStatic
    void sendResourceResponse(String location, boolean inline) {
        sendResourceResponseInternal(location, inline, eci, response, requestAttributes)
    }
    @CompileStatic
    static void sendResourceResponseInternal(String location, boolean inline, ExecutionContextImpl eci,
                                             HttpServletResponse response, Map<String, Object> requestAttributes) {
        ResourceReference rr = eci.resource.getLocationReference(location)
        if (rr == null) throw new IllegalArgumentException("Resource not found at: ${location}")
        response.setContentType(rr.contentType)
        if (inline) {
            response.addHeader("Content-Disposition", "inline")
        } else {
            response.addHeader("Content-Disposition", "attachment; filename=\"${rr.getFileName()}\"; filename*=utf-8''${StupidUtilities.encodeAsciiFilename(rr.getFileName())}")
        }
        String contentType = rr.getContentType()
        if (!contentType || ResourceFacadeImpl.isBinaryContentType(contentType)) {
            InputStream is = rr.openStream()
            try {
                OutputStream os = response.outputStream
                try {
                    int totalLen = StupidUtilities.copyStream(is, os)
                    logger.info("Streamed ${totalLen} bytes from contentLocation ${location}")
                } finally {
                    os.close()
                }
            } finally {
                is.close()
            }
        } else {
            String rrText = rr.getText()
            if (rrText) response.writer.append(rrText)
            response.writer.flush()
        }
    }

    @Override
    @CompileStatic
    void handleXmlRpcServiceCall() { new ServiceXmlRpcDispatcher(eci).dispatch(request, response) }

    @Override
    @CompileStatic
    void handleJsonRpcServiceCall() { new ServiceJsonRpcDispatcher(eci).dispatch(request, response) }

    @Override
    @CompileStatic
    void handleEntityRestCall(List<String> extraPathNameList, boolean masterNameInPath) {
        ContextStack parmStack = (ContextStack) getParameters()

        // check for parsing error, send a 400 response
        if (parmStack._requestBodyJsonParseError) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, (String) parmStack._requestBodyJsonParseError)
            return
        }

        // make sure a user is logged in, screen/etc that calls will generally be configured to not require auth
        if (!eci.getUser().getUsername()) {
            // if there was a login error there will be a MessageFacade error message
            String errorMessage = eci.message.errorsString
            if (!errorMessage) errorMessage = "Authentication required for entity REST operations"
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, errorMessage)
            return
        }

        try {
            // logger.warn("====== parameters: ${parmStack.toString()}")
            long startTime = System.currentTimeMillis()
            // if _requestBodyJsonList do multiple calls
            if (parmStack._requestBodyJsonList) {
                // TODO: Consider putting all of this in a transaction for non-find operations (currently each is run in
                // TODO:     a separate transaction); or handle errors per-row instead of blowing up the whole request
                List responseList = []
                for (Object bodyListObj in parmStack._requestBodyJsonList) {
                    if (!(bodyListObj instanceof Map)) {
                        String errMsg = "If request body JSON is a list/array it must contain only object/map values, found non-map entry of type ${bodyListObj.getClass().getName()} with value: ${bodyListObj}"
                        logger.warn(errMsg)
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST, errMsg)
                        return
                    }
                    // logger.warn("========== REST ${request.getMethod()} ${request.getPathInfo()} ${extraPathNameList}; body list object: ${bodyListObj}")
                    parmStack.push()
                    parmStack.putAll((Map) bodyListObj)
                    Object responseObj = eci.getEntity().rest(request.getMethod(), extraPathNameList, parmStack, masterNameInPath)
                    responseList.add(responseObj ?: [:])
                    parmStack.pop()
                }
                response.addIntHeader('X-Run-Time-ms', (System.currentTimeMillis() - startTime) as int)
                sendJsonResponse(responseList)
            } else {
                Object responseObj = eci.getEntity().rest(request.getMethod(), extraPathNameList, parmStack, masterNameInPath)
                response.addIntHeader('X-Run-Time-ms', (System.currentTimeMillis() - startTime) as int)

                if (parmStack.xTotalCount != null) response.addIntHeader('X-Total-Count', parmStack.xTotalCount as int)
                if (parmStack.xPageIndex != null) response.addIntHeader('X-Page-Index', parmStack.xPageIndex as int)
                if (parmStack.xPageSize != null) response.addIntHeader('X-Page-Size', parmStack.xPageSize as int)
                if (parmStack.xPageMaxIndex != null) response.addIntHeader('X-Page-Max-Index', parmStack.xPageMaxIndex as int)
                if (parmStack.xPageRangeLow != null) response.addIntHeader('X-Page-Range-Low', parmStack.xPageRangeLow as int)
                if (parmStack.xPageRangeHigh != null) response.addIntHeader('X-Page-Range-High', parmStack.xPageRangeHigh as int)

                // NOTE: This will always respond with 200 OK, consider using 201 Created (for successful POST, create PUT)
                //     and 204 No Content (for DELETE and other when no content is returned)
                sendJsonResponse(responseObj)
            }
        } catch (ArtifactAuthorizationException e) {
            // SC_UNAUTHORIZED 401 used when authc/login fails, use SC_FORBIDDEN 403 for authz failures
            logger.warn("REST Access Forbidden (no authz): " + e.message)
            response.sendError(HttpServletResponse.SC_FORBIDDEN, e.message)
        } catch (ArtifactTarpitException e) {
            logger.warn("REST Too Many Requests (tarpit): " + e.message)
            if (e.getRetryAfterSeconds()) response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            // NOTE: there is no constant on HttpServletResponse for 429; see RFC 6585 for details
            response.sendError(429, e.message)
        } catch (EntityNotFoundException e) {
            logger.warn((String) "REST Entity Not Found: " + e.getMessage(), e)
            // send bad request (400), reserve 404 Not Found for records that don't exist
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.message)
        } catch (EntityValueNotFoundException e) {
            logger.warn("REST Entity Value Not Found: " + e.getMessage())
            // record doesn't exist, send 404 Not Found
            response.sendError(HttpServletResponse.SC_NOT_FOUND, e.message)
        } catch (Throwable t) {
            String errorMessage = t.toString()
            if (eci.message.hasError()) {
                String errorsString = eci.message.errorsString
                logger.error(errorsString, t)
                errorMessage = errorMessage + ' ' + errorsString
            }
            logger.warn((String) "General error in entity REST: " + t.toString(), t)
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorMessage)
        }
    }

    @CompileStatic
    void handleEntityRestSchema(List<String> extraPathNameList, String schemaUri, String linkPrefix,
                                String schemaLinkPrefix, boolean getMaster) {
        // make sure a user is logged in, screen/etc that calls will generally be configured to not require auth
        if (!eci.getUser().getUsername()) {
            // if there was a login error there will be a MessageFacade error message
            String errorMessage = eci.message.errorsString
            if (!errorMessage) errorMessage = "Authentication required for entity REST schema"
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, errorMessage)
            return
        }

        EntityFacadeImpl efi = eci.getEcfi().getEntityFacade()

        if (extraPathNameList.size() == 0) {
            List allRefList = []
            Map definitionsMap = [:]
            definitionsMap.put('paginationParameters', EntityDefinition.jsonPaginationParameters)
            Map rootMap = ['$schema':'http://json-schema.org/draft-04/hyper-schema#', title:'Moqui Entity REST API',
                    anyOf:allRefList, definitions:definitionsMap]
            if (schemaUri) rootMap.put('id', schemaUri)

            Set<String> entityNameSet
            if (getMaster) {
                // if getMaster and no entity name in path, just get entities with master definitions
                entityNameSet = efi.getAllEntityNamesWithMaster()
            } else {
                entityNameSet = efi.getAllNonViewEntityNames()
            }
            for (String entityName in entityNameSet) {
                EntityDefinition ed = efi.getEntityDefinition(entityName)
                String refName = ed.getShortAlias() ?: ed.getFullEntityName()
                if (getMaster) {
                    Map<String, EntityDefinition.MasterDefinition> masterDefMap = ed.getMasterDefinitionMap()
                    Map entityPathMap = [:]
                    for (String masterName in masterDefMap.keySet()) {
                        allRefList.add(['$ref':"#/definitions/${refName}/${masterName}"])

                        Map schema = ed.getJsonSchema(false, false, definitionsMap, schemaUri, linkPrefix, schemaLinkPrefix, false, masterName, null)
                        entityPathMap.put(masterName, schema)
                    }
                    definitionsMap.put(refName, entityPathMap)
                } else {
                    allRefList.add(['$ref':"#/definitions/${refName}"])

                    Map schema = ed.getJsonSchema(false, false, null, schemaUri, linkPrefix, schemaLinkPrefix, true, null, null)
                    definitionsMap.put(refName, schema)
                }
            }

            JsonBuilder jb = new JsonBuilder()
            jb.call(rootMap)
            String jsonStr = jb.toPrettyString()

            sendTextResponse(jsonStr, "application/schema+json", "MoquiEntities.schema.json")
        } else {
            String entityName = extraPathNameList.get(0)
            if (entityName.endsWith(".json")) entityName = entityName.substring(0, entityName.length() - 5)

            String masterName = null
            if (extraPathNameList.size() > 1) {
                masterName = extraPathNameList.get(1)
                if (masterName.endsWith(".json")) masterName = masterName.substring(0, masterName.length() - 5)
            }
            if (getMaster && !masterName) masterName = "default"

            try {
                EntityDefinition ed = efi.getEntityDefinition(entityName)
                if (ed == null) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No entity found with name or alias [${entityName}]")
                    return
                }

                Map schema = ed.getJsonSchema(false, true, null, schemaUri, linkPrefix, schemaLinkPrefix, !getMaster, masterName, null)
                // TODO: support array wrapper (different URL? suffix?) with [type:'array', items:schema]

                // sendJsonResponse(schema)
                JsonBuilder jb = new JsonBuilder()
                jb.call(schema)
                String jsonStr = jb.toPrettyString()

                sendTextResponse(jsonStr, "application/schema+json", "${entityName}.schema.json")
            } catch (EntityNotFoundException e) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No entity found with name or alias [${entityName}]")
            }
        }
    }

    @CompileStatic
    void handleEntityRestRaml(List<String> extraPathNameList, String linkPrefix, String schemaLinkPrefix, boolean getMaster) {
        // make sure a user is logged in, screen/etc that calls will generally be configured to not require auth
        if (!eci.getUser().getUsername()) {
            // if there was a login error there will be a MessageFacade error message
            String errorMessage = eci.message.errorsString
            if (!errorMessage) errorMessage = "Authentication required for entity REST schema"
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, errorMessage)
            return
        }

        EntityFacadeImpl efi = eci.getEcfi().getEntityFacade()

        List<Map> schemasList = []
        Map<String, Object> rootMap = [title:'Moqui Entity REST API', version:'v1', baseUri:linkPrefix,
                                       mediaType:'application/json', schemas:schemasList] as Map<String, Object>
        rootMap.put('traits', [[paged:[queryParameters:EntityDefinition.ramlPaginationParameters]]])

        Set<String> entityNameSet
        String masterName = null
        if (extraPathNameList.size() > 0) {
            String entityName = extraPathNameList.get(0)
            if (entityName.endsWith(".raml")) entityName = entityName.substring(0, entityName.length() - 5)

            if (extraPathNameList.size() > 1) {
                masterName = extraPathNameList.get(1)
                if (masterName.endsWith(".raml")) masterName = masterName.substring(0, masterName.length() - 5)
            }

            entityNameSet = new TreeSet<String>()
            entityNameSet.add(entityName)
        } else if (getMaster) {
            // if getMaster and no entity name in path, just get entities with master definitions
            entityNameSet = efi.getAllEntityNamesWithMaster()
        } else {
            entityNameSet = efi.getAllNonViewEntityNames()
        }
        for (String entityName in entityNameSet) {
            EntityDefinition ed = efi.getEntityDefinition(entityName)
            String refName = ed.getShortAlias() ?: ed.getFullEntityName()
            if (getMaster) {
                Set<String> masterNameSet = new LinkedHashSet<String>()
                if (masterName) {
                    masterNameSet.add(masterName)
                } else {
                    Map<String, EntityDefinition.MasterDefinition> masterDefMap = ed.getMasterDefinitionMap()
                    masterNameSet.addAll(masterDefMap.keySet())
                }
                Map entityPathMap = [:]
                for (String curMasterName in masterNameSet) {
                    schemasList.add([("${refName}/${curMasterName}".toString()):"!include ${schemaLinkPrefix}/${refName}/${curMasterName}.json".toString()])

                    Map ramlApi = ed.getRamlApi(masterName)
                    entityPathMap.put("/" + curMasterName, ramlApi)
                }
                rootMap.put("/" + refName, entityPathMap)
            } else {
                schemasList.add([(refName):"!include ${schemaLinkPrefix}/${refName}.json".toString()])

                Map ramlApi = ed.getRamlApi(null)
                rootMap.put('/' + refName, ramlApi)
            }
        }

        DumperOptions options = new DumperOptions()
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
        // default: options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN)
        options.setPrettyFlow(true)
        Yaml yaml = new Yaml(options)
        String yamlString = yaml.dump(rootMap)
        // add beginning line "#%RAML 0.8", more efficient way to do this?
        yamlString = "#%RAML 0.8\n" + yamlString

        sendTextResponse(yamlString, "application/raml+yaml", "MoquiEntities.raml")
    }

    @CompileStatic
    void handleEntityRestSwagger(List<String> extraPathNameList, String hostName, String basePath, boolean getMaster) {
        if (extraPathNameList.size() == 0) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No entity name specified in path (for all entities use 'all')")
            return
        }

        EntityFacadeImpl efi = eci.getEcfi().getEntityFacade()

        String entityName = extraPathNameList.get(0)
        String outputType = "application/json"
        if (entityName.endsWith(".yaml")) outputType = "application/yaml"
        if (entityName.endsWith(".json") || entityName.endsWith(".yaml"))
            entityName = entityName.substring(0, entityName.length() - 5)
        if (entityName == 'all') entityName = null

        String masterName = null
        if (extraPathNameList.size() > 1) {
            masterName = extraPathNameList.get(1)
            if (masterName.endsWith(".json") || masterName.endsWith(".yaml"))
                masterName = masterName.substring(0, masterName.length() - 5)
        }

        String filename = entityName ?: "Entities"
        if (masterName) filename = filename + "." + masterName


        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT, PATCH, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, api_key, Authorization")

        Map definitionsMap = new TreeMap()
        Map<String, Object> swaggerMap = [swagger:'2.0',
            info:[title:("${filename} REST API"), version:'1.6.2'], host:hostName, basePath:basePath,
            schemes:['http', 'https'], consumes:['application/json', 'multipart/form-data'], produces:['application/json'],
            securityDefinitions:[basicAuth:[type:'basic', description:'HTTP Basic Authentication'],
                api_key:[type:"apiKey", name:"api_key", in:"header", description:'HTTP Header api_key, also supports tenant_id header']],
            paths:[:], definitions:definitionsMap
        ]

        Set<String> entityNameSet
        if (entityName) {
            entityNameSet = new TreeSet<String>()
            entityNameSet.add(entityName)
        } else if (getMaster) {
            // if getMaster and no entity name in path, just get entities with master definitions
            entityNameSet = efi.getAllEntityNamesWithMaster()
        } else {
            entityNameSet = efi.getAllNonViewEntityNames()
        }

        for (String curEntityName in entityNameSet) {
            EntityDefinition ed = efi.getEntityDefinition(curEntityName)
            if (getMaster) {
                Set<String> masterNameSet = new LinkedHashSet<String>()
                if (masterName) {
                    masterNameSet.add(masterName)
                } else {
                    Map<String, EntityDefinition.MasterDefinition> masterDefMap = ed.getMasterDefinitionMap()
                    masterNameSet.addAll(masterDefMap.keySet())
                }
                for (String curMasterName in masterNameSet) {
                    ed.addToSwaggerMap(swaggerMap, curMasterName)
                }
            } else {
                ed.addToSwaggerMap(swaggerMap, null)
            }
        }

        if (outputType == "application/json") {
            JsonBuilder jb = new JsonBuilder()
            jb.call(swaggerMap)
            String jsonStr = jb.toPrettyString()
            sendTextResponse(jsonStr, "application/json", "${filename}.swagger.json")
        } else if (outputType == "application/yaml") {
            DumperOptions options = new DumperOptions()
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
            // default: options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN)
            options.setPrettyFlow(true)
            Yaml yaml = new Yaml(options)
            String yamlString = yaml.dump(swaggerMap)

            sendTextResponse(yamlString, "application/yaml", "${filename}.swagger.yaml")
        } else {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Output type ${outputType} not supported")
        }
    }

    @Override
    @CompileStatic
    void handleServiceRestCall(List<String> extraPathNameList) {
        ContextStack parmStack = (ContextStack) getParameters()

        // check for login, etc error messages
        if (eci.message.hasError()) {
            String errorsString = eci.message.errorsString
            logger.warn((String) "General error in Service REST API: " + errorsString)
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorsString)
        }

        // check for parsing error, send a 400 response
        if (parmStack._requestBodyJsonParseError) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, (String) parmStack._requestBodyJsonParseError)
            return
        }

        try {
            long startTime = System.currentTimeMillis()
            // if _requestBodyJsonList do multiple calls
            if (parmStack._requestBodyJsonList) {
                // TODO: Consider putting all of this in a transaction for non-find operations (currently each is run in
                // TODO:     a separate transaction); or handle errors per-row instead of blowing up the whole request
                List responseList = []
                for (Object bodyListObj in parmStack._requestBodyJsonList) {
                    if (!(bodyListObj instanceof Map)) {
                        String errMsg = "If request body JSON is a list/array it must contain only object/map values, found non-map entry of type ${bodyListObj.getClass().getName()} with value: ${bodyListObj}"
                        logger.warn(errMsg)
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST, errMsg)
                        return
                    }
                    // logger.warn("========== REST ${request.getMethod()} ${request.getPathInfo()} ${extraPathNameList}; body list object: ${bodyListObj}")
                    parmStack.push()
                    parmStack.putAll((Map) bodyListObj)
                    eci.context.push(parmStack)

                    RestApi.RestResult restResult = eci.getEcfi().getServiceFacade().getRestApi().run(extraPathNameList, eci)
                    responseList.add(restResult.responseObj ?: [:])

                    eci.context.pop()
                    parmStack.pop()
                }
                response.addIntHeader('X-Run-Time-ms', (System.currentTimeMillis() - startTime) as int)

                if (eci.message.hasError()) {
                    // if error return that
                    String errorsString = eci.message.errorsString
                    logger.warn((String) "General error in Service REST API: " + errorsString)
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorsString)
                } else {
                    // otherwise send response
                    sendJsonResponse(responseList)
                }
            } else {
                eci.context.push(parmStack)
                RestApi.RestResult restResult = eci.getEcfi().getServiceFacade().getRestApi().run(extraPathNameList, eci)
                eci.context.pop()
                response.addIntHeader('X-Run-Time-ms', (System.currentTimeMillis() - startTime) as int)
                restResult.setHeaders(response)

                if (eci.message.hasError()) {
                    // if error return that
                    String errorsString = eci.message.errorsString
                    logger.warn((String) "General error in Service REST API: " + errorsString)
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorsString)
                } else {
                    // NOTE: This will always respond with 200 OK, consider using 201 Created (for successful POST, create PUT)
                    //     and 204 No Content (for DELETE and other when no content is returned)
                    sendJsonResponse(restResult.responseObj)
                }
            }
        } catch (AuthenticationRequiredException e) {
            logger.warn("REST Unauthorized (no authc): " + e.message)
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, e.message)
        } catch (ArtifactAuthorizationException e) {
            // SC_UNAUTHORIZED 401 used when authc/login fails, use SC_FORBIDDEN 403 for authz failures
            logger.warn("REST Access Forbidden (no authz): " + e.message)
            response.sendError(HttpServletResponse.SC_FORBIDDEN, e.message)
        } catch (ArtifactTarpitException e) {
            logger.warn("REST Too Many Requests (tarpit): " + e.message)
            if (e.getRetryAfterSeconds()) response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            // NOTE: there is no constant on HttpServletResponse for 429; see RFC 6585 for details
            response.sendError(429, e.message)
        } catch (RestApi.ResourceNotFoundException e) {
            logger.warn((String) "REST Resource Not Found: " + e.getMessage(), e)
            // send bad request (400), reserve 404 Not Found for records that don't exist
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, e.message)
        } catch (RestApi.MethodNotSupportedException e) {
            logger.warn((String) "REST Method Not Supported: " + e.getMessage(), e)
            response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, e.message)
        } catch (EntityValueNotFoundException e) {
            logger.warn("REST Entity Value Not Found: " + e.getMessage())
            // record doesn't exist, send 404 Not Found
            response.sendError(HttpServletResponse.SC_NOT_FOUND, e.message)
        } catch (Throwable t) {
            String errorMessage = t.toString()
            if (eci.message.hasError()) {
                String errorsString = eci.message.errorsString
                logger.error(errorsString, t)
                errorMessage = errorMessage + ' ' + errorsString
            }
            logger.warn((String) "General error in Service REST API: " + t.toString(), t)
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorMessage)
        }
    }

    @CompileStatic
    void handleServiceRestSwagger(List<String> extraPathNameList, String hostName, String basePath) {
        if (extraPathNameList.size() == 0) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No root resource name specified in path")
            return
        }

        String outputType = "application/json"
        List<String> rootPathList = []
        StringBuilder filenameBase = new StringBuilder()
        for (String pathName in extraPathNameList) {
            if (pathName.endsWith(".yaml")) outputType = "application/yaml"
            if (pathName.endsWith(".json") || pathName.endsWith(".yaml"))
                pathName = pathName.substring(0, pathName.length() - 5)
            rootPathList.add(pathName)
            filenameBase.append(pathName).append('.')
        }

        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT, PATCH, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, api_key, Authorization")

        Map swaggerMap = eci.ecfi.serviceFacade.restApi.getSwaggerMap(rootPathList, hostName, basePath)
        if (outputType == "application/json") {
            JsonBuilder jb = new JsonBuilder()
            jb.call(swaggerMap)
            String jsonStr = jb.toPrettyString()
            sendTextResponse(jsonStr, "application/json", "${filenameBase}swagger.json")
        } else if (outputType == "application/yaml") {
            DumperOptions options = new DumperOptions()
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
            // default: options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN)
            options.setPrettyFlow(true)
            Yaml yaml = new Yaml(options)
            String yamlString = yaml.dump(swaggerMap)

            sendTextResponse(yamlString, "application/yaml", "${filenameBase}swagger.yaml")
        } else {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Output type ${outputType} not supported")
        }
    }

    @CompileStatic
    void handleServiceRestRaml(List<String> extraPathNameList, String linkPrefix) {
        if (extraPathNameList.size() == 0) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "No root resource name specified in path")
            return
        }
        String rootResourceName = extraPathNameList.get(0)
        if (rootResourceName.endsWith(".raml")) rootResourceName = rootResourceName.substring(0, rootResourceName.length() - 5)

        Map swaggerMap = eci.ecfi.serviceFacade.restApi.getRamlMap(rootResourceName, linkPrefix)
        DumperOptions options = new DumperOptions()
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
        // default: options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN)
        options.setPrettyFlow(true)
        Yaml yaml = new Yaml(options)
        String yamlString = yaml.dump(swaggerMap)
        // add beginning line "#%RAML 1.0", more efficient way to do this?
        yamlString = "#%RAML 1.0\n" + yamlString

        sendTextResponse(yamlString, "application/raml+yaml", "${rootResourceName}.raml")
    }

    void saveScreenLastInfo(String screenPath, Map parameters) {
        session.setAttribute("moqui.screen.last.path", screenPath ?: request.getPathInfo())
        session.setAttribute("moqui.screen.last.parameters", parameters ?: new HashMap(getRequestParameters()))
    }

    String getRemoveScreenLastPath() {
        String path = session.getAttribute("moqui.screen.last.path")
        session.removeAttribute("moqui.screen.last.path")
        return path
    }
    Map getSavedParameters() { return (Map) session.getAttribute("moqui.saved.parameters") }
    void removeScreenLastParameters(boolean moveToSaved) {
        if (moveToSaved) session.setAttribute("moqui.saved.parameters", session.getAttribute("moqui.screen.last.parameters"))
        session.removeAttribute("moqui.screen.last.parameters")
    }

    void saveMessagesToSession() {
        if (eci.message.messages) session.setAttribute("moqui.message.messages", eci.message.messages)
        if (eci.message.errors) session.setAttribute("moqui.message.errors", eci.message.errors)
        if (eci.message.validationErrors) session.setAttribute("moqui.message.validationErrors", eci.message.validationErrors)
    }

    /** Save passed parameters Map to a Map in the moqui.saved.parameters session attribute */
    void saveParametersToSession(Map parameters) {
        Map parms = new HashMap()
        Map currentSavedParameters = (Map) request.session.getAttribute("moqui.saved.parameters")
        if (currentSavedParameters) parms.putAll(currentSavedParameters)
        if (parameters) parms.putAll(parameters)
        session.setAttribute("moqui.saved.parameters", parms)
    }
    /** Save request parameters and attributes to a Map in the moqui.saved.parameters session attribute */
    void saveRequestParametersToSession() {
        Map parms = new HashMap()
        Map currentSavedParameters = (Map) request.session.getAttribute("moqui.saved.parameters")
        if (currentSavedParameters) parms.putAll(currentSavedParameters)
        if (requestParameters) parms.putAll(requestParameters)
        if (requestAttributes) parms.putAll(requestAttributes)
        session.setAttribute("moqui.saved.parameters", parms)
    }

    /** Save request parameters and attributes to a Map in the moqui.error.parameters session attribute */
    void saveErrorParametersToSession() {
        Map parms = new HashMap()
        if (requestParameters) parms.putAll(requestParameters)
        if (requestAttributes) parms.putAll(requestAttributes)
        session.setAttribute("moqui.error.parameters", parms)
    }

    static DiskFileItemFactory makeDiskFileItemFactory(ServletContext context) {
        // NOTE: consider keeping this factory somewhere to be more efficient, if it even makes a difference...
        File repository = new File(System.getProperty("moqui.runtime") + "/tmp")
        if (!repository.exists()) repository.mkdir()

        DiskFileItemFactory factory = new DiskFileItemFactory(DiskFileItemFactory.DEFAULT_SIZE_THRESHOLD, repository)

        // TODO: this was causing files to get deleted before the upload was streamed... need to figure out something else
        //FileCleaningTracker fileCleaningTracker = FileCleanerCleanup.getFileCleaningTracker(context)
        //factory.setFileCleaningTracker(fileCleaningTracker)
        return factory
    }
}
