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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import groovy.transform.CompileStatic

import org.apache.commons.fileupload.FileItem
import org.apache.commons.fileupload.FileItemFactory
import org.apache.commons.fileupload.disk.DiskFileItemFactory
import org.apache.commons.fileupload.servlet.ServletFileUpload
import org.moqui.context.*
import org.moqui.entity.EntityNotFoundException
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityValueNotFoundException
import org.moqui.util.WebUtilities
import org.moqui.impl.context.ExecutionContextFactoryImpl.WebappInfo
import org.moqui.impl.screen.ScreenDefinition
import org.moqui.impl.screen.ScreenUrlInfo
import org.moqui.impl.service.RestApi
import org.moqui.impl.service.ServiceJsonRpcDispatcher
import org.moqui.impl.service.ServiceXmlRpcDispatcher
import org.moqui.util.ContextStack
import org.moqui.resource.ResourceReference
import org.moqui.util.ObjectUtilities
import org.moqui.util.StringUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.ServletContext
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpSession

/** This class is a facade to easily get information from and about the web context. */
@CompileStatic
class WebFacadeImpl implements WebFacade {
    protected final static Logger logger = LoggerFactory.getLogger(WebFacadeImpl.class)

    protected final static ObjectMapper jacksonMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true)
    static {
        // Jackson custom serializers, etc
        SimpleModule module = new SimpleModule()
        module.addSerializer(GString, new GStringJsonSerializer())
        jacksonMapper.registerModule(module)
    }

    // Not using shared root URL cache because causes issues when requests come to server through different hosts/etc:
    // protected static final Map<String, String> webappRootUrlByParms = new HashMap()

    protected ExecutionContextImpl eci
    protected String webappMoquiName
    protected HttpServletRequest request
    protected HttpServletResponse response

    protected Map<String, Object> savedParameters = (Map<String, Object>) null
    protected Map<String, Object> multiPartParameters = (Map<String, Object>) null
    protected Map<String, Object> jsonParameters = (Map<String, Object>) null
    protected Map<String, Object> declaredPathParameters = (Map<String, Object>) null

    protected ContextStack parameters = (ContextStack) null
    protected Map<String, Object> requestAttributes = (Map<String, Object>) null
    protected Map<String, Object> requestParameters = (Map<String, Object>) null
    protected Map<String, Object> sessionAttributes = (Map<String, Object>) null
    protected Map<String, Object> applicationAttributes = (Map<String, Object>) null

    protected Map<String, Object> errorParameters = (Map<String, Object>) null

    protected List<String> savedMessages = (List<String>) null
    protected List<String> savedErrors = (List<String>) null
    protected List<ValidationError> savedValidationErrors = (List<ValidationError>) null

    WebFacadeImpl(String webappMoquiName, HttpServletRequest request, HttpServletResponse response,
                  ExecutionContextImpl eci) {
        this.eci = eci
        this.webappMoquiName = webappMoquiName
        this.request = request
        this.response = response

        // NOTE: the Visit is not setup here but rather in the MoquiEventListener (for init and destroy)
        request.setAttribute("ec", eci)

        // get any parameters saved to the session from the last request, and clear that session attribute if there
        savedParameters = (Map<String, Object>) request.session.getAttribute("moqui.saved.parameters")
        if (savedParameters != null) request.session.removeAttribute("moqui.saved.parameters")

        errorParameters = (Map<String, Object>) request.session.getAttribute("moqui.error.parameters")
        if (errorParameters != null) request.session.removeAttribute("moqui.error.parameters")

        // get any messages saved to the session, and clear them from the session
        if (session.getAttribute("moqui.message.messages") != null) {
            savedMessages = (List<String>) session.getAttribute("moqui.message.messages")
            session.removeAttribute("moqui.message.messages")
        }
        if (session.getAttribute("moqui.message.errors") != null) {
            savedErrors = (List<String>) session.getAttribute("moqui.message.errors")
            session.removeAttribute("moqui.message.errors")
        }
        if (session.getAttribute("moqui.message.validationErrors") != null) {
            savedValidationErrors = (List<ValidationError>) session.getAttribute("moqui.message.validationErrors")
            session.removeAttribute("moqui.message.validationErrors")
        }

        // if there is a JSON document submitted consider those as parameters too
        String contentType = request.getHeader("Content-Type")
        if (contentType != null && contentType.length() > 0 && (contentType.contains("application/json") || contentType.contains("text/json"))) {
            // read the body first to make sure it isn't empty, better support clients that pass a Content-Type but no content (even though they shouldn't)
            StringBuilder bodyBuilder = new StringBuilder()
            BufferedReader reader = request.getReader()
            if (reader != null) {
                String curLine
                while ((curLine = reader.readLine()) != null) bodyBuilder.append(curLine)
            }
            if (bodyBuilder.length() > 0) {
                try {
                    JsonNode jsonNode = jacksonMapper.readTree(bodyBuilder.toString())
                    if (jsonNode.isObject()) {
                        jsonParameters = jacksonMapper.treeToValue(jsonNode, Map.class)
                    } else if (jsonNode.isArray()) {
                        jsonParameters = [_requestBodyJsonList:jacksonMapper.treeToValue(jsonNode, List.class)] as Map<String, Object>
                    }
                } catch (Throwable t) {
                    logger.error("Error parsing HTTP request body JSON: ${t.toString()}", t)
                    jsonParameters = [_requestBodyJsonParseError:t.getMessage()] as Map<String, Object>
                }
                logger.warn("=========== Got JSON HTTP request body: ${jsonParameters}")
            }
        } else if (ServletFileUpload.isMultipartContent(request)) {
            // if this is a multi-part request, get the data for it
            multiPartParameters = new HashMap()
            FileItemFactory factory = makeDiskFileItemFactory()
            ServletFileUpload upload = new ServletFileUpload(factory)

            List<FileItem> items = (List<FileItem>) upload.parseRequest(request)
            List<FileItem> fileUploadList = []
            multiPartParameters.put("_fileUploadList", fileUploadList)

            for (FileItem item in items) {
                if (item.isFormField()) {
                    addValueToMultipartParameterMap(item.getFieldName(), item.getString("UTF-8"))
                } else {
                    // put the FileItem itself in the Map to be used by the application code
                    addValueToMultipartParameterMap(item.getFieldName(), item)
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
        if (sessionToken == null || sessionToken.length() == 0) {
            sessionToken = StringUtilities.getRandomString(20)
            session.setAttribute("moqui.session.token", sessionToken)
            request.setAttribute("moqui.session.token.created", "true")
        }
    }

    /** Apache Commons FileUpload does not support string array so when using multiple select and there's a duplicate
     * fieldName convert value to an array list when fieldName is already in multipart parameters. */
    private void addValueToMultipartParameterMap(String key, Object value) {
        // change &nbsp; (\u00a0) to null, used as a placeholder when empty string doesn't work
        if ("\u00a0".equals(value)) value = null
        Object previousValue = multiPartParameters.put(key, value)
        if (previousValue != null) {
            List<Object> valueList = new ArrayList<>()
            valueList.add(value)
            multiPartParameters.put(key, valueList)
            if(previousValue instanceof Collection) {
                valueList.addAll((Collection) previousValue)
            } else {
                valueList.add(previousValue)
            }
        }
    }

    @Override
    String getSessionToken() { return session.getAttribute("moqui.session.token") }

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

    void saveScreenHistory(ScreenUrlInfo.UrlInstance urlInstanceOrig) {
        ScreenUrlInfo sui = urlInstanceOrig.sui
        ScreenDefinition targetScreen = urlInstanceOrig.sui.targetScreen

        // don't save standalone screens
        if (sui.lastStandalone || targetScreen.isStandalone()) return
        // don't save transition requests, just screens
        if (urlInstanceOrig.getTargetTransition() != null) return
        // if history=false on the screen don't save
        if ("false".equals(targetScreen.screenNode.attribute("history"))) return

        List<Map> screenHistoryList = (List<Map>) session.getAttribute("moqui.screen.history")
        if (screenHistoryList == null) {
            screenHistoryList = Collections.<Map>synchronizedList(new LinkedList<Map>())
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
            StringBuilder paramBuilder = new StringBuilder()
            if (parameters) {
                int pCount = 0
                Iterator<Map.Entry<String, String>> entryIter = parameters.entrySet().iterator()
                while (entryIter.hasNext() && pCount < 2) {
                    Map.Entry<String, String> entry = entryIter.next()
                    if (entry.key.contains("_op")) continue
                    if (entry.key.contains("_not")) continue
                    if (entry.key.contains("_ic")) continue
                    if ("moquiSessionToken".equals(entry.key)) continue
                    if (entry.value.trim().length() == 0) continue

                    // injection issue with name field: userId=%3Cscript%3Ealert(%27Test%20Crack!%27)%3C/script%3E
                    String parmValue = entry.value
                    if (parmValue) parmValue = URLEncoder.encode(parmValue, "UTF-8")
                    paramBuilder.append(parmValue)

                    pCount++
                    if (entryIter.hasNext() && pCount < 2) paramBuilder.append(', ')
                }
            }
            if (paramBuilder.length() > 0) nameBuilder.append(' (').append(paramBuilder.toString()).append(')')
        }

        synchronized (screenHistoryList) {
            // remove existing item(s) from list with same URL
            Iterator<Map> screenHistoryIter = screenHistoryList.iterator()
            while (screenHistoryIter.hasNext()) {
                Map screenHistory = screenHistoryIter.next()
                if (screenHistory.url == urlWithParams) screenHistoryIter.remove()
            }
            // add to history list
            screenHistoryList.add(0, [name:nameBuilder.toString(), url:urlWithParams, urlNoParams:urlNoParams,
                    image:sui.menuImage, imageType:sui.menuImageType, screenLocation:targetScreen.getLocation()])
            // trim the list if needed; keep 40, whatever uses it may display less
            while (screenHistoryList.size() > 40) screenHistoryList.remove(40)
        }
    }

    @Override
    List<Map> getScreenHistory() {
        List<Map> histList = (List<Map>) session.getAttribute("moqui.screen.history")
        if (histList == null) histList = Collections.<Map>synchronizedList(new LinkedList<Map>())
        return histList
    }

    @Override
    String getRequestUrl() {
        StringBuilder requestUrl = new StringBuilder()
        requestUrl.append(request.getScheme())
        requestUrl.append("://" + request.getServerName())
        if (request.getServerPort() != 80 && request.getServerPort() != 443) requestUrl.append(":" + request.getServerPort())
        requestUrl.append(request.getRequestURI())
        if (request.getQueryString()) requestUrl.append("?" + request.getQueryString())
        return requestUrl.toString()
    }

    void addDeclaredPathParameter(String name, String value) {
        if (declaredPathParameters == null) declaredPathParameters = new HashMap()
        declaredPathParameters.put(name, value)
    }

    @Override
    Map<String, Object> getParameters() {
        // NOTE: no blocking in these methods because the WebFacadeImpl is created for each thread

        // only create when requested, then keep for additional requests
        if (parameters != null) return parameters

        // Uses the approach of creating a series of this objects wrapping the other non-Map attributes/etc instead of
        // copying everything from the various places into a single combined Map; this should be much faster to create
        // and only slightly slower when running.
        ContextStack cs = new ContextStack(false)
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
    HttpServletRequest getRequest() { return request }
    @Override
    Map<String, Object> getRequestAttributes() {
        if (requestAttributes != null) return requestAttributes
        requestAttributes = new WebUtilities.AttributeContainerMap(new WebUtilities.ServletRequestContainer(request))
        return requestAttributes
    }
    @Override
    Map<String, Object> getRequestParameters() {
        if (requestParameters != null) return requestParameters

        ContextStack cs = new ContextStack(false)
        if (savedParameters != null) cs.push(savedParameters)
        if (multiPartParameters != null) cs.push(multiPartParameters)
        if (jsonParameters != null) cs.push(jsonParameters)
        if (declaredPathParameters != null) cs.push(new WebUtilities.CanonicalizeMap(declaredPathParameters))

        // no longer uses CanonicalizeMap, search Map for String[] of size 1 and change to String
        Map<String, Object> reqParmMap = WebUtilities.simplifyRequestParameters(request, false)
        if (reqParmMap.size() > 0) cs.push(reqParmMap)

        // NOTE: We decode path parameter ourselves, so use getRequestURI instead of getPathInfo
        Map<String, Object> pathInfoParameterMap = WebUtilities.getPathInfoParameterMap(request.getRequestURI())
        if (pathInfoParameterMap != null && pathInfoParameterMap.size() > 0) cs.push(pathInfoParameterMap)
        // NOTE: the CanonicalizeMap cleans up character encodings, and unwraps lists of values with a single entry

        // do one last push so writes don't modify whatever was at the top of the stack
        cs.push()
        requestParameters = cs
        return requestParameters
    }
    @Override
    Map<String, Object> getSecureRequestParameters() {
        ContextStack cs = new ContextStack(false)
        if (savedParameters) cs.push(savedParameters)
        if (multiPartParameters) cs.push(multiPartParameters)
        if (jsonParameters) cs.push(jsonParameters)

        Map<String, Object> reqParmMap = WebUtilities.simplifyRequestParameters(request, true)
        if (reqParmMap.size() > 0) cs.push(reqParmMap)

        return cs
    }

    @Override
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
    Map<String, Object> getSessionAttributes() {
        if (sessionAttributes != null) return sessionAttributes
        sessionAttributes = new WebUtilities.AttributeContainerMap(new WebUtilities.HttpSessionContainer(getSession()))
        return sessionAttributes
    }

    @Override
    ServletContext getServletContext() { return getSession().getServletContext() }
    @Override
    Map<String, Object> getApplicationAttributes() {
        if (applicationAttributes != null) return applicationAttributes
        applicationAttributes = new WebUtilities.AttributeContainerMap(new WebUtilities.ServletContextContainer(getServletContext()))
        return applicationAttributes
    }

    String getWebappMoquiName() { return webappMoquiName }
    @Override
    String getWebappRootUrl(boolean requireFullUrl, Boolean useEncryption) {
        return getWebappRootUrl(this.webappMoquiName, null, requireFullUrl, useEncryption, eci)
    }

    static String getWebappRootUrl(String webappName, String servletContextPath, boolean requireFullUrl, Boolean useEncryption, ExecutionContextImpl eci) {
        WebFacade webFacade = eci.getWeb()
        HttpServletRequest request = webFacade?.getRequest()
        boolean requireEncryption = useEncryption == null && request != null ? request.isSecure() : (useEncryption != null ? useEncryption.booleanValue() : false)
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
        String cacheKey = (String) null
        if (request != null) {
            StringBuilder keyBuilder = new StringBuilder(200)
            keyBuilder.append(webappName).append(servletContextPath)
            if (needFullUrl) keyBuilder.append("T") else keyBuilder.append("F")
            if (requireEncryption) keyBuilder.append("T") else keyBuilder.append("F")
            cacheKey = keyBuilder.toString()

            String cachedRootUrl = request.getAttribute(cacheKey)
            if (cachedRootUrl != null) return cachedRootUrl
        }

        String urlValue = makeWebappRootUrl(webappName, servletContextPath, eci, webFacade, requireEncryption, needFullUrl)
        if (cacheKey != null) request.setAttribute(cacheKey, urlValue)
        return urlValue
    }
    static String makeWebappHost(String webappName, ExecutionContextImpl eci, WebFacade webFacade, boolean requireEncryption) {
        WebappInfo webappInfo = eci.ecfi.getWebappInfo(webappName)
        // can't get these settings, hopefully a URL from the root will do
        if (webappInfo == null) return ""

        StringBuilder urlBuilder = new StringBuilder()
        HttpServletRequest request = webFacade?.getRequest()
        if ("https".equals(request?.getScheme()) || (requireEncryption && webappInfo.httpsEnabled)) {
            urlBuilder.append("https://")
            if (webappInfo.httpsHost != null) {
                urlBuilder.append(webappInfo.httpsHost)
            } else {
                if (webFacade != null) {
                    urlBuilder.append(webFacade.getHostName(false))
                } else {
                    // uh-oh, no web context, default to localhost
                    urlBuilder.append("localhost")
                }
            }
            String httpsPort = webappInfo.httpsPort
            // try the local port; this won't work when switching from http to https, conf required for that
            if (httpsPort == null && request != null && request.isSecure()) httpsPort = request.getServerPort() as String
            if (httpsPort != null && !httpsPort.isEmpty() && !"443".equals(httpsPort)) urlBuilder.append(":").append(httpsPort)
        } else {
            urlBuilder.append("http://")
            if (webappInfo.httpHost != null) {
                urlBuilder.append(webappInfo.httpHost)
            } else {
                if (webFacade != null) {
                    urlBuilder.append(webFacade.getHostName(false))
                } else {
                    // uh-oh, no web context, default to localhost
                    urlBuilder.append("localhost")
                    logger.trace("No webapp http-host and no webFacade in place, defaulting to localhost for hostName")
                }
            }
            String httpPort = webappInfo.httpPort
            // try the server port; this won't work when switching from https to http, conf required for that
            if (!httpPort && request != null && !request.isSecure()) httpPort = request.getServerPort() as String
            if (httpPort != null && !httpPort.isEmpty() && !"80".equals(httpPort)) urlBuilder.append(":").append(httpPort)
        }
        return urlBuilder.toString()
    }
    static String makeWebappRootUrl(String webappName, String servletContextPath, ExecutionContextImpl eci, WebFacade webFacade,
                                    boolean requireEncryption, boolean needFullUrl) {
        StringBuilder urlBuilder = new StringBuilder()
        // build base from conf
        if (needFullUrl) urlBuilder.append(makeWebappHost(webappName, eci, webFacade, requireEncryption))
        urlBuilder.append("/")

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

    @Override Map<String, Object> getErrorParameters() { return errorParameters }
    @Override List<String> getSavedMessages() { return savedMessages }
    @Override List<String> getSavedErrors() { return savedErrors }
    @Override List<ValidationError> getSavedValidationErrors() { return savedValidationErrors }

    @Override
    void sendJsonResponse(Object responseObj) { sendJsonResponseInternal(responseObj, eci, request, response, requestAttributes) }
    static void sendJsonResponseInternal(Object responseObj, ExecutionContextImpl eci, HttpServletRequest request,
                                         HttpServletResponse response, Map<String, Object> requestAttributes) {
        String jsonStr = null
        if (responseObj instanceof CharSequence) {
            jsonStr = responseObj.toString()
            responseObj = null
        } else {
            Map responseMap = responseObj instanceof Map ? (Map) responseObj : null

            if (eci.message.messages) {
                if (responseObj == null) {
                    responseObj = [messages:eci.message.getMessagesString()] as Map<String, Object>
                } else if (responseMap != null && !responseMap.containsKey("messages")) {
                    responseMap = new HashMap(responseMap)
                    responseMap.put("messages", eci.message.getMessagesString())
                    responseObj = responseMap
                }
            }

            if (eci.getMessage().hasError()) {
                // if the responseObj is a Map add all of it's data
                // only add an errors if it is not a jsonrpc response (JSON RPC has it's own error handling)
                if (responseMap != null && !responseMap.containsKey("errors") && !responseMap.containsKey("jsonrpc")) {
                    responseMap = new HashMap(responseMap)
                    responseMap.put("errors", eci.message.errorsString)
                    responseObj = responseMap
                } else if (responseObj != null && !(responseObj instanceof Map)) {
                    logger.error("Error found when sending JSON string, JSON object is not a Map so not adding errors to return: ${eci.message.errorsString}")
                }
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR)
            } else {
                response.setStatus(HttpServletResponse.SC_OK)
            }
        }

        // logger.warn("========== Sending JSON for object: ${responseObj}")
        if (responseObj != null) jsonStr = jacksonMapper.writeValueAsString(responseObj)

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
            if (logger.isTraceEnabled()) {
                Long startTime = (Long) requestAttributes.get("moquiRequestStartTime")
                String timeMsg = ""
                if (startTime) timeMsg = "in ${(System.currentTimeMillis()-startTime)}ms"
                logger.trace("Sent JSON response ${length} bytes ${charset} encoding ${timeMsg} for ${request.getMethod()} to ${request.getPathInfo()}")
            }
        } catch (IOException e) {
            logger.error("Error sending JSON string response", e)
        }
    }

    void sendJsonError(int statusCode, String errorMessages) {
        // NOTE: uses same field name as sendJsonResponseInternal
        String jsonStr = jacksonMapper.writeValueAsString([errorCode:statusCode, errors:errorMessages])
        response.setContentType("application/json")
        // NOTE: String.length not correct for byte length
        String charset = response.getCharacterEncoding() ?: "UTF-8"
        int length = jsonStr.getBytes(charset).length
        response.setContentLength(length)
        response.setStatus(statusCode)
        response.writer.write(jsonStr)
        response.writer.flush()
    }


    @Override
    void sendTextResponse(String text) {
        sendTextResponseInternal(text, "text/plain", null, eci, request, response, requestAttributes)
    }
    @Override
    void sendTextResponse(String text, String contentType, String filename) {
        sendTextResponseInternal(text, contentType, filename, eci, request, response, requestAttributes)
    }
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
        int length = responseText != null ? responseText.getBytes(charset).length : 0I
        response.setContentLength(length)

        if (!filename) {
            response.addHeader("Content-Disposition", "inline")
        } else {
            response.addHeader("Content-Disposition", "attachment; filename=\"${filename}\"; filename*=utf-8''${StringUtilities.encodeAsciiFilename(filename)}")
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

    @Override void sendResourceResponse(String location) { sendResourceResponseInternal(location, false, eci, response) }
    void sendResourceResponse(String location, boolean inline) { sendResourceResponseInternal(location, inline, eci, response) }
    static void sendResourceResponseInternal(String location, boolean inline, ExecutionContextImpl eci, HttpServletResponse response) {
        ResourceReference rr = eci.resource.getLocationReference(location)
        if (rr == null || (rr.supportsExists() && !rr.getExists())) {
            logger.warn("Sending not found response, resource not found at: ${location}")
            response.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }
        String contentType = rr.getContentType()
        if (contentType) response.setContentType(contentType)
        if (inline) {
            response.addHeader("Content-Disposition", "inline")
            response.addHeader("Cache-Control", "max-age=3600, must-revalidate, public")
        } else {
            response.addHeader("Content-Disposition", "attachment; filename=\"${rr.getFileName()}\"; filename*=utf-8''${StringUtilities.encodeAsciiFilename(rr.getFileName())}")
        }
        if (contentType == null || contentType.isEmpty() || ResourceReference.isBinaryContentType(contentType)) {
            InputStream is = rr.openStream()
            if (is == null) {
                logger.warn("Sending not found response, openStream returned null for location: ${location}")
                response.sendError(HttpServletResponse.SC_NOT_FOUND)
                return
            }

            try {
                OutputStream os = response.outputStream
                try {
                    int totalLen = ObjectUtilities.copyStream(is, os)
                    logger.info("Streamed ${totalLen} bytes from location ${location}")
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

    @Override void handleXmlRpcServiceCall() { new ServiceXmlRpcDispatcher(eci).dispatch(request, response) }
    @Override void handleJsonRpcServiceCall() { new ServiceJsonRpcDispatcher(eci).dispatch() }

    @Override
    void handleEntityRestCall(List<String> extraPathNameList, boolean masterNameInPath) {
        ContextStack parmStack = (ContextStack) getParameters()

        // check for parsing error, send a 400 response
        if (parmStack._requestBodyJsonParseError) {
            sendJsonError(HttpServletResponse.SC_BAD_REQUEST, (String) parmStack._requestBodyJsonParseError)
            return
        }

        // make sure a user is logged in, screen/etc that calls will generally be configured to not require auth
        if (!eci.getUser().getUsername()) {
            // if there was a login error there will be a MessageFacade error message
            String errorMessage = eci.message.errorsString
            if (!errorMessage) errorMessage = "Authentication required for entity REST operations"
            sendJsonError(HttpServletResponse.SC_UNAUTHORIZED, errorMessage)
            return
        }

        String method = request.getMethod()
        if ("post".equalsIgnoreCase(method)) {
            String ovdMethod = request.getHeader("X-HTTP-Method-Override")
            if (ovdMethod != null && !ovdMethod.isEmpty()) method = ovdMethod.toLowerCase()
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
                        sendJsonError(HttpServletResponse.SC_BAD_REQUEST, errMsg)
                        return
                    }
                    // logger.warn("========== REST ${method} ${request.getPathInfo()} ${extraPathNameList}; body list object: ${bodyListObj}")
                    parmStack.push()
                    parmStack.putAll((Map) bodyListObj)
                    Object responseObj = eci.entityFacade.rest(method, extraPathNameList, parmStack, masterNameInPath)
                    responseList.add(responseObj ?: [:])
                    parmStack.pop()
                }
                response.addIntHeader('X-Run-Time-ms', (System.currentTimeMillis() - startTime) as int)
                sendJsonResponse(responseList)
            } else {
                Object responseObj = eci.entityFacade.rest(method, extraPathNameList, parmStack, masterNameInPath)
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
            logger.warn("REST Access Forbidden (403 no authz): " + e.message)
            sendJsonError(HttpServletResponse.SC_FORBIDDEN, e.message)
        } catch (ArtifactTarpitException e) {
            logger.warn("REST Too Many Requests (429 tarpit): " + e.message)
            if (e.getRetryAfterSeconds()) response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            // NOTE: there is no constant on HttpServletResponse for 429; see RFC 6585 for details
            sendJsonError(429, e.message)
        } catch (EntityNotFoundException e) {
            logger.warn((String) "REST Entity Not Found (404): " + e.getMessage(), e)
            // send 404 Not Found for entities that don't exist (along with records that don't exist)
            sendJsonError(HttpServletResponse.SC_NOT_FOUND, e.message)
        } catch (EntityValueNotFoundException e) {
            logger.warn("REST Entity Value Not Found (404): " + e.getMessage())
            // record doesn't exist, send 404 Not Found
            sendJsonError(HttpServletResponse.SC_NOT_FOUND, e.message)
        } catch (Throwable t) {
            String errorMessage = t.toString()
            if (eci.message.hasError()) {
                String errorsString = eci.message.errorsString
                logger.error(errorsString, t)
                errorMessage = errorMessage + ' ' + errorsString
            }
            logger.warn((String) "General error in entity REST: " + t.toString(), t)
            sendJsonError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorMessage)
        }
    }

    @Override
    void handleServiceRestCall(List<String> extraPathNameList) {
        ContextStack parmStack = (ContextStack) getParameters()

        // check for login, etc error messages
        if (eci.message.hasError()) {
            String errorsString = eci.message.errorsString
            if ("true".equals(request.getAttribute("moqui.login.error"))) {
                logger.warn((String) "Login error in Service REST API: " + errorsString)
                sendJsonError(HttpServletResponse.SC_UNAUTHORIZED, errorsString)
            } else {
                logger.warn((String) "General error in Service REST API: " + errorsString)
                sendJsonError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorsString)
            }
            return
        }

        // check for parsing error, send a 400 response
        if (parmStack._requestBodyJsonParseError) {
            sendJsonError(HttpServletResponse.SC_BAD_REQUEST, (String) parmStack._requestBodyJsonParseError)
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
                        sendJsonError(HttpServletResponse.SC_BAD_REQUEST, errMsg)
                        return
                    }
                    // logger.warn("========== REST ${request.getMethod()} ${request.getPathInfo()} ${extraPathNameList}; body list object: ${bodyListObj}")
                    parmStack.push()
                    parmStack.putAll((Map) bodyListObj)
                    eci.contextStack.push(parmStack)

                    RestApi.RestResult restResult = eci.serviceFacade.restApi.run(extraPathNameList, eci)
                    responseList.add(restResult.responseObj ?: [:])

                    eci.contextStack.pop()
                    parmStack.pop()
                }
                response.addIntHeader('X-Run-Time-ms', (System.currentTimeMillis() - startTime) as int)

                if (eci.message.hasError()) {
                    // if error return that
                    String errorsString = eci.message.errorsString
                    logger.warn((String) "General error in Service REST API: " + errorsString)
                    sendJsonError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorsString)
                } else {
                    // otherwise send response
                    sendJsonResponse(responseList)
                }
            } else {
                eci.contextStack.push(parmStack)
                RestApi.RestResult restResult = eci.serviceFacade.restApi.run(extraPathNameList, eci)
                eci.contextStack.pop()
                response.addIntHeader('X-Run-Time-ms', (System.currentTimeMillis() - startTime) as int)
                restResult.setHeaders(response)

                if (eci.message.hasError()) {
                    // if error return that
                    String errorsString = eci.message.errorsString
                    logger.warn((String) "Error message from Service REST API (400): " + errorsString)
                    sendJsonError(HttpServletResponse.SC_BAD_REQUEST, errorsString)
                } else {
                    // NOTE: This will always respond with 200 OK, consider using 201 Created (for successful POST, create PUT)
                    //     and 204 No Content (for DELETE and other when no content is returned)
                    sendJsonResponse(restResult.responseObj)
                }
            }
        } catch (AuthenticationRequiredException e) {
            logger.warn("REST Unauthorized (401 no authc): " + e.message)
            sendJsonError(HttpServletResponse.SC_UNAUTHORIZED, e.message)
        } catch (ArtifactAuthorizationException e) {
            // SC_UNAUTHORIZED 401 used when authc/login fails, use SC_FORBIDDEN 403 for authz failures
            logger.warn("REST Access Forbidden (403 no authz): " + e.message)
            sendJsonError(HttpServletResponse.SC_FORBIDDEN, e.message)
        } catch (ArtifactTarpitException e) {
            logger.warn("REST Too Many Requests (429 tarpit): " + e.message)
            if (e.getRetryAfterSeconds()) response.addIntHeader("Retry-After", e.getRetryAfterSeconds())
            // NOTE: there is no constant on HttpServletResponse for 429; see RFC 6585 for details
            sendJsonError(429, e.message)
        } catch (RestApi.ResourceNotFoundException e) {
            logger.warn((String) "REST Resource Not Found (404): " + e.getMessage())
            // send 404 Not Found for resources/paths that don't exist (along with records that don't exist)
            sendJsonError(HttpServletResponse.SC_NOT_FOUND, e.message)
        } catch (RestApi.MethodNotSupportedException e) {
            logger.warn((String) "REST Method Not Supported (405): " + e.getMessage())
            sendJsonError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, e.message)
        } catch (EntityValueNotFoundException e) {
            logger.warn("REST Entity Value Not Found (404): " + e.getMessage())
            // record doesn't exist, send 404 Not Found
            sendJsonError(HttpServletResponse.SC_NOT_FOUND, e.message)
        } catch (Throwable t) {
            String errorMessage = t.toString()
            if (eci.message.hasError()) {
                String errorsString = eci.message.errorsString
                logger.error(errorsString, t)
                errorMessage = errorMessage + ' ' + errorsString
            }
            logger.warn((String) "Error thrown in Service REST API (500): " + t.toString(), t)
            sendJsonError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorMessage)
        }
    }

    void saveScreenLastInfo(String screenPath, Map parameters) {
        session.setAttribute("moqui.screen.last.path", screenPath ?: request.getPathInfo())
        parameters = parameters ?: new HashMap(getRequestParameters())
        WebUtilities.testSerialization("moqui.screen.last.parameters", parameters)
        session.setAttribute("moqui.screen.last.parameters", parameters)
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
        List<String> messages = eci.messageFacade.getMessages()
        if (messages != null && messages.size() > 0) session.setAttribute("moqui.message.messages", messages)
        List<String> errors = eci.messageFacade.getErrors()
        if (errors != null && errors.size() > 0) session.setAttribute("moqui.message.errors", errors)
        List<ValidationError> validationErrors = eci.messageFacade.validationErrors
        WebUtilities.testSerialization("moqui.message.validationErrors", validationErrors)
        if (validationErrors != null && validationErrors.size() > 0) session.setAttribute("moqui.message.validationErrors", validationErrors)
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
        WebUtilities.testSerialization("moqui.saved.parameters", parms)
        session.setAttribute("moqui.saved.parameters", parms)
    }

    /** Save request parameters and attributes to a Map in the moqui.error.parameters session attribute */
    void saveErrorParametersToSession() {
        Map parms = new HashMap()
        if (requestParameters) parms.putAll(requestParameters)
        if (requestAttributes) parms.putAll(requestAttributes)
        WebUtilities.testSerialization("moqui.saved.parameters", parms)
        session.setAttribute("moqui.error.parameters", parms)
    }

    static final byte[] trackingPng = [(byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0x00,0x00,0x00,0x0D,0x49,0x48,0x44,0x52,0x00,
            0x00,0x00,0x01,0x00,0x00,0x00,0x01,0x08,0x06,0x00,0x00,0x00,0x1F,0x15,(byte)0xC4,(byte)0x89,0x00,0x00,0x00,0x0B,0x49,
            0x44,0x41,0x54,0x78,(byte)0xDA,0x63,0x60,0x00,0x02,0x00,0x00,0x05,0x00,0x01,(byte)0xE9,(byte)0xFA,(byte)0xDC,(byte)0xD8,
            0x00,0x00,0x00,0x00,0x49,0x45,0x4E,0x44,(byte)0xAE,0x42,0x60,(byte)0x82]
    void viewEmailMessage() {
        // first send the empty image
        response.setContentType('image/png')
        response.addHeader("Content-Disposition", "inline")
        OutputStream os = response.outputStream
        try { os.write(trackingPng) } finally { os.close() }
        // mark the message viewed
        try {
            String emailMessageId = eci.contextStack.get("emailMessageId")
            if (emailMessageId != null && !emailMessageId.isEmpty()) {
                int dotIndex = emailMessageId.indexOf(".")
                if (dotIndex > 0) emailMessageId = emailMessageId.substring(0, dotIndex)
                EntityValue emailMessage = eci.entity.find("moqui.basic.email.EmailMessage").condition("emailMessageId", emailMessageId)
                        .disableAuthz().one()
                if (emailMessage == null) {
                    logger.warn("Tried to mark EmailMessage ${emailMessageId} viewed but not found")
                } else if (!"ES_VIEWED".equals(emailMessage.statusId)) {
                    eci.service.sync().name("update#moqui.basic.email.EmailMessage").parameter("emailMessageId", emailMessageId)
                            .parameter("statusId", "ES_VIEWED").parameter("receivedDate", eci.user.nowTimestamp).disableAuthz().call()
                }
            }
        } catch (Throwable t) {
            logger.error("Error marking EmailMessage viewed", t)
        }
    }

    protected DiskFileItemFactory makeDiskFileItemFactory() {
        // NOTE: consider keeping this factory somewhere to be more efficient, if it even makes a difference...
        File repository = new File(eci.ecfi.runtimePath + "/tmp")
        if (!repository.exists()) repository.mkdir()

        DiskFileItemFactory factory = new DiskFileItemFactory(DiskFileItemFactory.DEFAULT_SIZE_THRESHOLD, repository)

        // TODO: this was causing files to get deleted before the upload was streamed... need to figure out something else
        //FileCleaningTracker fileCleaningTracker = FileCleanerCleanup.getFileCleaningTracker(request.getServletContext())
        //factory.setFileCleaningTracker(fileCleaningTracker)
        return factory
    }

    static class GStringJsonSerializer extends StdSerializer<GString> {
        GStringJsonSerializer() { super(GString) }
        @Override void serialize(GString value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException, JsonProcessingException { if (value != (Object) null) gen.writeString(value.toString()) }
    }
}
