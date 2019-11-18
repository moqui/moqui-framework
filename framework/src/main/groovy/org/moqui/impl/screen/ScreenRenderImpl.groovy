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

import freemarker.template.Template
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.apache.http.HttpResponse
import org.moqui.BaseArtifactException
import org.moqui.BaseException
import org.moqui.context.*
import org.moqui.context.MessageFacade.MessageInfo
import org.moqui.entity.EntityCondition.ComparisonOperator
import org.moqui.entity.EntityException
import org.moqui.entity.EntityList
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.screen.ScreenTest
import org.moqui.util.WebUtilities
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.ContextJavaUtil
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.ResourceFacadeImpl
import org.moqui.impl.context.WebFacadeImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityValueBase
import org.moqui.impl.screen.ScreenDefinition.ResponseItem
import org.moqui.impl.screen.ScreenDefinition.SubscreensItem
import org.moqui.impl.screen.ScreenForm.FormInstance
import org.moqui.impl.screen.ScreenUrlInfo.UrlInstance
import org.moqui.screen.ScreenRender
import org.moqui.util.ContextStack
import org.moqui.util.MNode
import org.moqui.resource.ResourceReference
import org.moqui.util.ObjectUtilities
import org.moqui.util.StringUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@CompileStatic
class ScreenRenderImpl implements ScreenRender {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenRenderImpl.class)
    protected final static boolean isTraceEnabled = logger.isTraceEnabled()

    public final ScreenFacadeImpl sfi
    public final ExecutionContextImpl ec
    protected boolean rendering = false

    protected String rootScreenLocation = (String) null
    protected ScreenDefinition rootScreenDef = (ScreenDefinition) null
    protected ScreenDefinition overrideActiveScreenDef = (ScreenDefinition) null

    protected ArrayList<String> originalScreenPathNameList = new ArrayList<String>()
    protected ScreenUrlInfo screenUrlInfo = (ScreenUrlInfo) null
    protected UrlInstance screenUrlInstance = (UrlInstance) null
    protected Map<String, ScreenUrlInfo> subscreenUrlInfos = new HashMap()
    protected int screenPathIndex = 0
    protected Set<String> stopRenderScreenLocations = new HashSet()

    protected String baseLinkUrl = (String) null
    protected String servletContextPath = (String) null
    protected String webappName = (String) null

    protected String renderMode = (String) null
    protected String characterEncoding = "UTF-8"
    /** For HttpServletRequest/Response renders this will be set on the response either as this default or a value
     * determined during render, especially for screen sub-content based on the extension of the filename. */
    protected String outputContentType = (String) null

    protected String macroTemplateLocation = (String) null
    protected Boolean boundaryComments = (Boolean) null

    protected HttpServletRequest request = (HttpServletRequest) null
    protected HttpServletResponse response = (HttpServletResponse) null
    protected Writer internalWriter = (Writer) null
    protected Writer afterScreenWriter = (Writer) null
    protected Writer scriptWriter = (Writer) null
    protected OutputStream internalOutputStream = (OutputStream) null

    protected boolean dontDoRender = false
    protected boolean saveHistory = false

    protected Map<String, FormInstance> screenFormCache = new HashMap<>()
    protected String curThemeId = (String) null
    protected Map<String, ArrayList<String>> curThemeValuesByType = new HashMap<>()

    ScreenRenderImpl(ScreenFacadeImpl sfi) {
        this.sfi = sfi
        ec = sfi.ecfi.getEci()
    }

    Writer getWriter() {
        if (internalWriter != null) return internalWriter
        if (internalOutputStream != null) {
            if (characterEncoding == null || characterEncoding.length() == 0) characterEncoding = "UTF-8"
            internalWriter = new OutputStreamWriter(internalOutputStream, characterEncoding)
            return internalWriter
        }
        if (response != null) {
            internalWriter = response.getWriter()
            return internalWriter
        }
        throw new BaseArtifactException("Could not render screen, no writer available")
    }

    OutputStream getOutputStream() {
        if (internalOutputStream != null) return internalOutputStream
        if (response != null) {
            internalOutputStream = response.getOutputStream()
            return internalOutputStream
        }
        throw new BaseArtifactException("Could not render screen, no output stream available")
    }

    ScreenUrlInfo getScreenUrlInfo() { return screenUrlInfo }
    UrlInstance getScreenUrlInstance() { return screenUrlInstance }

    @Override ScreenRender rootScreen(String rsLocation) { rootScreenLocation = rsLocation; return this }
    ScreenRender rootScreenFromHost(String host) { return rootScreen(sfi.rootScreenFromHost(host, webappName)) }
    @Override ScreenRender screenPath(List<String> screenNameList) { originalScreenPathNameList.addAll(screenNameList); return this }
    @Override ScreenRender renderMode(String renderMode) { this.renderMode = renderMode; return this }
    String getRenderMode() { return renderMode }

    @Override ScreenRender encoding(String characterEncoding) { this.characterEncoding = characterEncoding;  return this }
    @Override ScreenRender macroTemplate(String mtl) { this.macroTemplateLocation = mtl; return this }
    @Override ScreenRender baseLinkUrl(String blu) { this.baseLinkUrl = blu; return this }
    @Override ScreenRender servletContextPath(String scp) { this.servletContextPath = scp; return this }
    @Override ScreenRender webappName(String wan) { this.webappName = wan; return this }
    @Override ScreenRender saveHistory(boolean sh) { this.saveHistory = sh; return this }

    @Override
    void render(HttpServletRequest request, HttpServletResponse response) {
        if (rendering) throw new IllegalStateException("This screen render has already been used")
        rendering = true
        this.request = request
        this.response = response
        // NOTE: don't get the writer at this point, we don't yet know if we're writing text or binary
        if (webappName == null || webappName.length() == 0) webappName = request.servletContext.getInitParameter("moqui-name")
        if (webappName != null && webappName.length() > 0 && (rootScreenLocation == null || rootScreenLocation.length() == 0))
            rootScreenFromHost(request.getServerName())
        if (originalScreenPathNameList == null || originalScreenPathNameList.size() == 0) {
            ArrayList<String> pathList = ec.web.getPathInfoList()
            screenPath(pathList)
        }
        if (servletContextPath == null || servletContextPath.isEmpty())
            servletContextPath = request.getServletContext()?.getContextPath()

        // now render
        internalRender()
    }

    @Override
    void render(Writer writer) {
        if (rendering) throw new IllegalStateException("This screen render has already been used")
        rendering = true
        internalWriter = writer
        internalRender()
    }

    @Override
    void render(OutputStream os) {
        if (rendering) throw new IllegalStateException("This screen render has already been used")
        rendering = true
        internalOutputStream = os
        internalRender()
    }

    @Override
    String render() {
        if (rendering) throw new IllegalStateException("This screen render has already been used")
        rendering = true
        internalWriter = new StringWriter()
        internalRender()
        return internalWriter.toString()
    }

    /** this should be called as part of a always-actions or pre-actions block to stop rendering before it starts */
    void sendRedirectAndStopRender(String redirectUrl) {
        if (response != null) {
            if (servletContextPath != null && !servletContextPath.isEmpty() && redirectUrl.startsWith("/"))
                redirectUrl = servletContextPath + redirectUrl
            if ("vuet".equals(renderMode)) {
                if (logger.isInfoEnabled()) logger.info("Redirecting (vuet) to ${redirectUrl} instead of rendering ${this.getScreenUrlInfo().getFullPathNameList()}")
                response.addHeader("X-Redirect-To", redirectUrl)
                // use code 205 (Reset Content) for client router handled redirect
                response.setStatus(HttpServletResponse.SC_RESET_CONTENT)
            } else {
                if (logger.isInfoEnabled()) logger.info("Redirecting to ${redirectUrl} instead of rendering ${this.getScreenUrlInfo().getFullPathNameList()}")
                response.sendRedirect(redirectUrl)
            }
            dontDoRender = true
        }
    }
    boolean sendJsonRedirect(UrlInstance fullUrl, Long renderStartTime) {
        if ("json".equals(screenUrlInfo.targetTransitionExtension) || request?.getHeader("Accept")?.contains("application/json")) {
            String pathWithParams = fullUrl.getPathWithParams()
            Map<String, Object> responseMap = getBasicResponseMap()
            // add screen path, parameters from fullUrl
            responseMap.put("screenPathList", fullUrl.sui.fullPathNameList)
            responseMap.put("screenParameters", fullUrl.getParameterMap())
            responseMap.put("screenUrl", pathWithParams)
            // send it
            ec.web.sendJsonResponse(responseMap)
            if (logger.isInfoEnabled()) logger.info("Transition ${screenUrlInfo.getFullPathNameList().join("/")}${renderStartTime != null ? ' in ' + (System.currentTimeMillis() - renderStartTime) + 'ms' : ''}, JSON redirect to: ${pathWithParams}")
            return true
        } else {
            return false
        }
    }
    boolean sendJsonRedirect(String plainUrl) {
        if ("json".equals(screenUrlInfo.targetTransitionExtension) || request?.getHeader("Accept")?.contains("application/json")) {
            Map<String, Object> responseMap = getBasicResponseMap()
            // the plain URL, send as redirect URL
            responseMap.put("redirectUrl", plainUrl)
            // send it
            ec.web.sendJsonResponse(responseMap)
            return true
        } else {
            return false
        }
    }
    Map<String, Object> getBasicResponseMap() {
        Map<String, Object> responseMap = new HashMap<>()
        // add saveMessagesToSession, saveRequestParametersToSession/saveErrorParametersToSession data
        // add all plain object data from session?
        List<MessageInfo> messageInfos = ec.message.getMessageInfos()
        int messageInfosSize = messageInfos.size()
        if (messageInfosSize > 0) {
            List<Map> miMapList = new ArrayList<>(messageInfosSize)
            for (int i = 0; i < messageInfosSize; i++) {
                MessageInfo messageInfo = (MessageInfo) messageInfos.get(i)
                miMapList.add([message:messageInfo.message, type:messageInfo.typeString])
            }
            responseMap.put("messageInfos", miMapList)
        }
        if (ec.message.getErrors().size() > 0) responseMap.put("errors", ec.message.errors)
        if (ec.message.getValidationErrors().size() > 0) {
            List<ValidationError> valErrorList = ec.message.getValidationErrors()
            int valErrorListSize = valErrorList.size()
            ArrayList<Map> valErrMapList = new ArrayList<>(valErrorListSize)
            for (int i = 0; i < valErrorListSize; i++) valErrMapList.add(valErrorList.get(i).getMap())
            responseMap.put("validationErrors", valErrMapList)
        }

        Map parms = new HashMap()
        if (ec.web.requestParameters != null) parms.putAll(ec.web.requestParameters)
        if (ec.web.requestAttributes != null) parms.putAll(ec.web.requestAttributes)
        responseMap.put("currentParameters", ContextJavaUtil.unwrapMap(parms))

        return responseMap
    }

    protected void internalRender() {
        // make sure this (sri) is in the context before running actions or rendering screens
        ec.context.put("sri", this)

        long renderStartTime = System.currentTimeMillis()

        rootScreenDef = sfi.getScreenDefinition(rootScreenLocation)
        if (rootScreenDef == null) throw new BaseArtifactException("Could not find root screen at location ${rootScreenLocation}")

        if (logger.traceEnabled) logger.trace("Rendering screen ${rootScreenLocation} with path list ${originalScreenPathNameList}")
        // logger.info("Rendering screen [${rootScreenLocation}] with path list [${originalScreenPathNameList}]")

        WebFacade web = ec.getWeb()
        String lastStandalone = web != null ? web.requestParameters.lastStandalone : null
        screenUrlInfo = ScreenUrlInfo.getScreenUrlInfo(this, rootScreenDef, originalScreenPathNameList, null,
                ScreenUrlInfo.parseLastStandalone(lastStandalone, 0))

        // if the target of the url doesn't exist throw exception
        screenUrlInfo.checkExists()
        screenUrlInstance = screenUrlInfo.getInstance(this, false)

        if (web != null) {
            // clear out the parameters used for special screen URL config
            if (lastStandalone != null && lastStandalone.length() > 0) web.requestParameters.lastStandalone = ""

            // if screenUrlInfo has any parameters add them to the request (probably came from a transition acting as an alias)
            Map<String, String> suiParameterMap = screenUrlInstance.getTransitionAliasParameters()
            if (suiParameterMap != null) web.requestParameters.putAll(suiParameterMap)

            // add URL parameters, if there were any in the URL (in path info or after ?)
            screenUrlInstance.addParameters(web.requestParameters)
        }

        // check webapp settings for each screen in the path
        ArrayList<ScreenDefinition> screenPathDefList = screenUrlInfo.screenPathDefList
        int screenPathDefListSize = screenPathDefList.size()
        for (int i = screenUrlInfo.renderPathDifference; i < screenPathDefListSize; i++) {
            ScreenDefinition sd = (ScreenDefinition) screenPathDefList.get(i)
            if (!checkWebappSettings(sd)) return
        }

        // check this here after the ScreenUrlInfo (with transition alias, etc) has already been handled
        String localRenderMode = web != null ? web.requestParameters.renderMode : null
        if ((renderMode == null || renderMode.length() == 0) && localRenderMode != null && localRenderMode.length() > 0)
            renderMode = localRenderMode
        // if no renderMode get from target screen extension in URL
        if ((renderMode == null || renderMode.length() == 0) && screenUrlInfo.targetScreenRenderMode != null)
            renderMode = screenUrlInfo.targetScreenRenderMode
        // if no outputContentType but there is a renderMode get outputContentType based on renderMode
        if ((outputContentType == null || outputContentType.length() == 0) && renderMode != null && renderMode.length() > 0) {
            String mimeType = sfi.getMimeTypeByMode(renderMode)
            if (mimeType != null && mimeType.length() > 0) outputContentType = mimeType
        }

        // if these aren't set yet then set to basic defaults
        if (renderMode == null || renderMode.length() == 0) renderMode = "html"
        if (characterEncoding == null || characterEncoding.length() == 0) characterEncoding = "UTF-8"
        if (outputContentType == null || outputContentType.length() == 0) outputContentType = "text/html"


        // before we render, set the character encoding (set the content type later, after we see if there is sub-content with a different type)
        if (response != null) response.setCharacterEncoding(characterEncoding)

        // if there is a transition run that INSTEAD of the screen to render
        ScreenDefinition.TransitionItem targetTransition = screenUrlInstance.getTargetTransition()
        // logger.warn("============ Rendering screen ${screenUrlInfo.getTargetScreen().getLocation()} transition ${screenUrlInfo.getTargetTransitionActualName()} has transition ${targetTransition != null}")
        if (targetTransition != null) {
            // if this transition has actions and request was not secure or any parameters were not in the body
            // return an error, helps prevent CSRF/XSRF attacks
            if (request != null && targetTransition.hasActionsOrSingleService()) {
                ExecutionContextFactoryImpl.WebappInfo webappInfo = ec.ecfi.getWebappInfo(webappName)
                String queryString = request.getQueryString()

                // NOTE: We decode path parameter ourselves, so use getRequestURI instead of getPathInfo
                Map<String, Object> pathInfoParameterMap = WebUtilities.getPathInfoParameterMap(request.getRequestURI())
                if (!targetTransition.isReadOnly() && (
                        (!request.isSecure() && webappInfo != null && webappInfo.httpsEnabled) ||
                        (queryString != null && queryString.length() > 0) ||
                        (pathInfoParameterMap != null && pathInfoParameterMap.size() > 0))) {
                    throw new BaseArtifactException(
                        """Cannot run screen transition with actions from non-secure request or with URL
                        parameters for security reasons (they are not encrypted and need to be for data
                        protection and source validation). Change the link this came from to be a
                        form with hidden input fields instead, or declare the transition as read-only.""")
                }
                // require a moquiSessionToken parameter for all but get
                if (request.getMethod().toLowerCase() != "get" && webappInfo != null && webappInfo.requireSessionToken &&
                        targetTransition.getRequireSessionToken() &&
                        !"true".equals(request.getAttribute("moqui.session.token.created")) &&
                        !"true".equals(request.getAttribute("moqui.request.authenticated"))) {
                    String passedToken = (String) ec.web.getParameters().get("moquiSessionToken")
                    if (!passedToken) passedToken = request.getHeader("moquiSessionToken") ?:
                            request.getHeader("SessionToken") ?: request.getHeader("X-CSRF-Token")

                    String curToken = ec.web.getSessionToken()
                    if (curToken != null && curToken.length() > 0) {
                        if (passedToken == null || passedToken.length() == 0) {
                            throw new AuthenticationRequiredException("Session token required (in moquiSessionToken) for URL ${screenUrlInstance.url}")
                        } else if (!curToken.equals(passedToken)) {
                            throw new AuthenticationRequiredException("Session token does not match (in moquiSessionToken) for URL ${screenUrlInstance.url}")
                        }
                    }
                }
            }

            long startTimeNanos = System.nanoTime()

            TransactionFacade transactionFacade = sfi.getEcfi().transactionFacade
            boolean beginTransaction = targetTransition.getBeginTransaction()
            boolean beganTransaction = beginTransaction ? transactionFacade.begin(null) : false
            ResponseItem ri = null
            try {
                boolean runPreActions = targetTransition instanceof ScreenDefinition.ActionsTransitionItem
                screenPathIndex = 0
                ri = recursiveRunTransition(runPreActions)
                screenPathIndex = 0
            } catch (Throwable t) {
                transactionFacade.rollback(beganTransaction, "Error running transition in [${screenUrlInstance.url}]", t)
                throw t
            } finally {
                try {
                    if (transactionFacade.isTransactionInPlace()) {
                        if (ec.getMessage().hasError()) {
                            transactionFacade.rollback(beganTransaction, ec.getMessage().getErrorsString(), null)
                        } else {
                            transactionFacade.commit(beganTransaction)
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error ending screen transition transaction", e)
                }

                if (!"false".equals(screenUrlInfo.targetScreen.screenNode.attribute("track-artifact-hit"))) {
                    String riType = ri != null ? ri.type : null
                    sfi.ecfi.countArtifactHit(ArtifactExecutionInfo.AT_XML_SCREEN_TRANS, riType != null ? riType : "",
                            targetTransition.parentScreen.getLocation() + "#" + targetTransition.name,
                            (web != null ? web.requestParameters : null), renderStartTime,
                            (System.nanoTime() - startTimeNanos)/1000000.0D, null)
                }
            }

            if (ri == null) throw new BaseArtifactException("No response found for transition [${screenUrlInstance.targetTransition.name}] on screen ${screenUrlInfo.targetScreen.location}")

            WebFacadeImpl wfi = (WebFacadeImpl) null
            if (web != null && web instanceof WebFacadeImpl) wfi = (WebFacadeImpl) web

            if (ri.saveCurrentScreen && wfi != null) {
                StringBuilder screenPath = new StringBuilder()
                for (String pn in screenUrlInfo.fullPathNameList) screenPath.append("/").append(pn)
                ((WebFacadeImpl) web).saveScreenLastInfo(screenPath.toString(), null)
            }

            if ("none".equals(ri.type)) {
                if (logger.isTraceEnabled()) logger.trace("Transition ${screenUrlInfo.getFullPathNameList().join("/")} in ${System.currentTimeMillis() - renderStartTime}ms, type none response")
                return
            }

            String url = ri.url != null ? ri.url : ""
            String urlType = ri.urlType != null && ri.urlType.length() > 0 ? ri.urlType : "screen-path"
            boolean isScreenLast = "screen-last".equals(ri.type)

            if (wfi != null) {
                // handle screen-last, etc
                if (isScreenLast || "screen-last-noparam".equals(ri.type)) {
                    String savedUrl =  wfi.getRemoveScreenLastPath()
                    urlType = "screen-path"
                    if (savedUrl != null && savedUrl.length() > 0) {
                        url = savedUrl
                        wfi.removeScreenLastParameters(isScreenLast)
                        // logger.warn("going to screen-last from screen last path ${url}")
                    } else {
                        // try screen history when no last was saved
                        List<Map> historyList = wfi.getScreenHistory()
                        Map historyMap = historyList != null && historyList.size() > 0 ? historyList.first() : (Map) null
                        if (historyMap != null) {
                            url = isScreenLast ? historyMap.pathWithParams : historyMap.path
                            // logger.warn("going to screen-last from screen history ${url}")
                        } else {
                            // if no saved URL, just go to root/default; avoid getting stuck on Login screen, etc
                            url = "/"
                            // logger.warn("going to screen-last no last path or history to going to root")
                        }
                    }
                }

                // save messages in session before redirecting so they can be displayed on the next screen
                wfi.saveMessagesToSession()
                if (ri.saveParameters) wfi.saveRequestParametersToSession()
                if (ec.message.hasError()) wfi.saveErrorParametersToSession()
            }

            // either send a redirect for the response, if possible, or just render the response now
            if (this.response != null) {
                if ("plain".equals(urlType)) {
                    StringBuilder ps = new StringBuilder()
                    Map<String, String> pm = (Map<String, String>) ri.expandParameters(screenUrlInfo.getExtraPathNameList(), ec)
                    if (pm != null && pm.size() > 0) {
                        for (Map.Entry<String, String> pme in pm.entrySet()) {
                            if (!pme.value) continue
                            if (ps.length() > 0) ps.append("&")
                            ps.append(URLEncoder.encode(pme.key, "UTF-8")).append("=").append(URLEncoder.encode(pme.value, "UTF-8"))
                        }
                    }
                    String fullUrl = url
                    if (ps.length() > 0) {
                        if (url.contains("?")) fullUrl += "&" else fullUrl += "?"
                        fullUrl += ps.toString()
                    }
                    // NOTE: even if transition extension is json still send redirect when we just have a plain url
                    if (logger.isInfoEnabled()) logger.info("Transition ${screenUrlInfo.getFullPathNameList().join("/")} in ${System.currentTimeMillis() - renderStartTime}ms, redirecting to plain URL: ${fullUrl}")
                    if (!sendJsonRedirect(fullUrl)) {
                        response.sendRedirect(fullUrl)
                    }
                } else {
                    // default is screen-path
                    UrlInstance fullUrl = buildUrl(rootScreenDef, screenUrlInfo.preTransitionPathNameList, url)
                    // copy through pageIndex if passed so in form-list with multiple pages we stay on same page
                    if (web.requestParameters.containsKey("pageIndex")) fullUrl.addParameter("pageIndex", (String) web.parameters.get("pageIndex"))
                    // copy through orderByField if passed so in form-list with multiple pages we retain the sort order
                    if (web.requestParameters.containsKey("orderByField")) fullUrl.addParameter("orderByField", (String) web.parameters.get("orderByField"))
                    fullUrl.addParameters(ri.expandParameters(screenUrlInfo.getExtraPathNameList(), ec))
                    // if this was a screen-last and the screen has declared parameters include them in the URL
                    Map savedParameters = wfi?.getSavedParameters()
                    UrlInstance.copySpecialParameters(savedParameters, fullUrl.getOtherParameterMap())
                    // screen parameters
                    Map<String, ScreenDefinition.ParameterItem> parameterItemMap = fullUrl.sui.pathParameterItems
                    if (isScreenLast && savedParameters != null && savedParameters.size() > 0) {
                        if (parameterItemMap != null && parameterItemMap.size() > 0) {
                            for (String parmName in parameterItemMap.keySet()) {
                                if (savedParameters.get(parmName)) fullUrl.addParameter(parmName, savedParameters.get(parmName))
                            }
                        } else {
                            fullUrl.addParameters(savedParameters)
                        }
                    }
                    // transition parameters
                    Map<String, ScreenDefinition.ParameterItem> transParameterItemMap = fullUrl.getTargetTransition()?.getParameterMap()
                    if (isScreenLast && savedParameters != null && savedParameters.size() > 0 &&
                            transParameterItemMap != null && transParameterItemMap.size() > 0) {
                        for (String parmName in transParameterItemMap.keySet()) {
                            if (savedParameters.get(parmName))
                                fullUrl.addParameter(parmName, savedParameters.get(parmName))
                        }
                    }

                    if (!sendJsonRedirect(fullUrl, renderStartTime)) {
                        String fullUrlString = fullUrl.getUrlWithParams(screenUrlInfo.targetTransitionExtension)
                        if (logger.isInfoEnabled()) logger.info("Transition ${screenUrlInfo.getFullPathNameList().join("/")} in ${System.currentTimeMillis() - renderStartTime}ms, redirecting to screen path URL: ${fullUrlString}")
                        response.sendRedirect(fullUrlString)
                    }
                }
            } else {
                ArrayList<String> pathElements = new ArrayList<>(Arrays.asList(url.split("/")))
                if (url.startsWith("/")) {
                    this.originalScreenPathNameList = pathElements
                } else {
                    this.originalScreenPathNameList = new ArrayList<>(screenUrlInfo.preTransitionPathNameList)
                    this.originalScreenPathNameList.addAll(pathElements)
                }
                // reset screenUrlInfo and call this again to start over with the new target
                screenUrlInfo = (ScreenUrlInfo) null
                internalRender()
            }
        } else if (screenUrlInfo.fileResourceRef != null) {
            ResourceReference fileResourceRef = screenUrlInfo.fileResourceRef

            long resourceStartTime = System.currentTimeMillis()
            long startTimeNanos = System.nanoTime()

            TemplateRenderer tr = sfi.ecfi.resourceFacade.getTemplateRendererByLocation(fileResourceRef.location)

            // use the fileName to determine the content/mime type
            String fileName = fileResourceRef.fileName
            // strip template extension(s) to avoid problems with trying to find content types based on them
            String fileContentType = sfi.ecfi.resourceFacade.getContentType(tr != null ? tr.stripTemplateExtension(fileName) : fileName)

            boolean isBinary = tr == null && ResourceReference.isBinaryContentType(fileContentType)
            // if (isTraceEnabled) logger.trace("Content type for screen sub-content filename [${fileName}] is [${fileContentType}], default [${this.outputContentType}], is binary? ${isBinary}")

            ExecutionContextFactoryImpl.WebappInfo webappInfo = ec.ecfi.getWebappInfo(webappName)
            if (isBinary) {
                if (response != null) {
                    this.outputContentType = fileContentType
                    response.setContentType(this.outputContentType)
                    // static binary, tell the browser to cache it
                    if (webappInfo != null) {
                        webappInfo.addHeaders("screen-resource-binary", response)
                    } else {
                        response.addHeader("Cache-Control", "max-age=86400, must-revalidate, public")
                    }

                    InputStream is
                    try {
                        is = fileResourceRef.openStream()
                        OutputStream os = response.outputStream
                        int totalLen = ObjectUtilities.copyStream(is, os)

                        if (screenUrlInfo.targetScreen.screenNode.attribute("track-artifact-hit") != "false") {
                            sfi.ecfi.countArtifactHit(ArtifactExecutionInfo.AT_XML_SCREEN_CONTENT, fileContentType,
                                    fileResourceRef.location, (web != null ? web.requestParameters : null),
                                    resourceStartTime, (System.nanoTime() - startTimeNanos)/1000000.0D, (long) totalLen)
                        }
                        if (isTraceEnabled) logger.trace("Sent binary response of length ${totalLen} from file ${fileResourceRef.location} for request to ${screenUrlInstance.url}")
                    } finally {
                        if (is != null) is.close()
                    }
                } else {
                    throw new BaseArtifactException("Tried to get binary content at ${screenUrlInfo.fileResourcePathList} under screen ${screenUrlInfo.targetScreen.location}, but there is no HTTP response available")
                }
            } else if (!"true".equals(screenUrlInfo.targetScreen.screenNode.attribute("include-child-content"))) {
                // not a binary object (hopefully), read it and write it to the writer
                if (fileContentType != null && fileContentType.length() > 0) this.outputContentType = fileContentType
                if (response != null) {
                    response.setContentType(this.outputContentType)
                    response.setCharacterEncoding(this.characterEncoding)
                }

                if (tr != null) {
                    // if requires a render, don't cache and make it private
                    if (response != null) {
                        if (webappInfo != null) {
                            webappInfo.addHeaders("screen-resource-template", response)
                        } else {
                            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate, private")
                        }
                    }
                    tr.render(fileResourceRef.location, writer)
                } else {
                    // static text, tell the browser to cache it
                    if (response != null) {
                        if (webappInfo != null) {
                            webappInfo.addHeaders("screen-resource-text", response)
                        } else {
                            response.addHeader("Cache-Control", "max-age=86400, must-revalidate, public")
                        }
                    }
                    // no renderer found, just grab the text (cached) and throw it to the writer
                    String text = sfi.ecfi.resourceFacade.getLocationText(fileResourceRef.location, true)
                    if (text != null && text.length() > 0) {
                        // NOTE: String.length not correct for byte length
                        String charset = response?.getCharacterEncoding() ?: "UTF-8"

                        // getBytes() is pretty slow, seems to be only way to get accurate length, perhaps better without it (definitely faster)
                        // int length = text.getBytes(charset).length
                        // if (response != null) response.setContentLength(length)

                        if (isTraceEnabled) logger.trace("Sending text response with ${charset} encoding from file ${fileResourceRef.location} for request to ${screenUrlInstance.url}")

                        writer.write(text)
                        if (!"false".equals(screenUrlInfo.targetScreen.screenNode.attribute("track-artifact-hit"))) {
                            sfi.ecfi.countArtifactHit(ArtifactExecutionInfo.AT_XML_SCREEN_CONTENT, fileContentType,
                                    fileResourceRef.location, (web != null ? web.requestParameters : null),
                                    resourceStartTime, (System.nanoTime() - startTimeNanos)/1000000.0D, (long) text.length())
                        }
                    } else {
                        logger.warn("Not sending text response from file [${fileResourceRef.location}] for request to [${screenUrlInstance.url}] because no text was found in the file.")
                    }
                }
            } else {
                // render the root screen as normal, and when that is to the targetScreen include the content
                doActualRender()
            }
        } else {
            doActualRender()
            if (response != null && logger.isInfoEnabled()) {
                Map<String, Object> reqParms = web?.getRequestParameters()
                logger.info("${screenUrlInfo.getFullPathNameList().join("/")} ${reqParms != null && reqParms.size() > 0 ? reqParms : '[]'} in ${(System.currentTimeMillis()-renderStartTime)}ms (${response.getContentType()}) session ${request.session.id}")
            }
        }
    }

    protected ResponseItem recursiveRunTransition(boolean runPreActions) {
        ScreenDefinition sd = getActiveScreenDef()
        // for these authz is not required, as long as something authorizes on the way to the transition, or
        // the transition itself, it's fine
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(sd.location,
                ArtifactExecutionInfo.AT_XML_SCREEN, ArtifactExecutionInfo.AUTHZA_VIEW, null)
        ec.artifactExecutionFacade.pushInternal(aei, false, false)

        boolean loggedInAnonymous = false
        ResponseItem ri = (ResponseItem) null

        try {
            MNode screenNode = sd.getScreenNode()
            String requireAuthentication = screenNode.attribute("require-authentication")
            if ("anonymous-all".equals(requireAuthentication)) {
                ec.artifactExecutionFacade.setAnonymousAuthorizedAll()
                loggedInAnonymous = ec.userFacade.loginAnonymousIfNoUser()
            } else if ("anonymous-view".equals(requireAuthentication)) {
                ec.artifactExecutionFacade.setAnonymousAuthorizedView()
                loggedInAnonymous = ec.userFacade.loginAnonymousIfNoUser()
            }

            if (sd.alwaysActions != null) sd.alwaysActions.run(ec)
            if (runPreActions && sd.preActions != null) sd.preActions.run(ec)

            if (getActiveScreenHasNext()) {
                screenPathIndex++
                try { ri = recursiveRunTransition(runPreActions) }
                finally { screenPathIndex-- }
            } else {
                // run the transition
                ri = screenUrlInstance.targetTransition.run(this)
            }
        } finally {
            ec.artifactExecutionFacade.pop(aei)
            if (loggedInAnonymous) ec.userFacade.logoutAnonymousOnly()
        }

        return ri
    }
    protected void recursiveRunActions(boolean runAlwaysActions, boolean runPreActions) {
        ScreenDefinition sd = getActiveScreenDef()
        boolean activeScreenHasNext = getActiveScreenHasNext()
        // check authz first, including anonymous-* handling so that permissions and auth are in place
        // NOTE: don't require authz if the screen doesn't require auth
        MNode screenNode = sd.getScreenNode()
        String requireAuthentication = screenNode.attribute("require-authentication")
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(sd.location,
                ArtifactExecutionInfo.AT_XML_SCREEN, ArtifactExecutionInfo.AUTHZA_VIEW, outputContentType).setTrackArtifactHit(false)
        ec.artifactExecutionFacade.pushInternal(aei, !activeScreenHasNext ? (!requireAuthentication || requireAuthentication == "true") : false, false)

        boolean loggedInAnonymous = false
        try {
            if (requireAuthentication == "anonymous-all") {
                ec.artifactExecutionFacade.setAnonymousAuthorizedAll()
                loggedInAnonymous = ec.userFacade.loginAnonymousIfNoUser()
            } else if (requireAuthentication == "anonymous-view") {
                ec.artifactExecutionFacade.setAnonymousAuthorizedView()
                loggedInAnonymous = ec.userFacade.loginAnonymousIfNoUser()
            }

            if (runAlwaysActions && sd.alwaysActions != null) sd.alwaysActions.run(ec)
            if (runPreActions && sd.preActions != null) sd.preActions.run(ec)

            if (activeScreenHasNext) {
                screenPathIndex++
                try { recursiveRunActions(runAlwaysActions, runPreActions) }
                finally { screenPathIndex-- }
            }
        } finally {
            // all done so pop the artifact info; don't bother making sure this is done on errors/etc like in a finally clause because if there is an error this will help us know how we got there
            ec.artifactExecutionFacade.pop(aei)
            if (loggedInAnonymous) ec.userFacade.logoutAnonymousOnly()
        }
    }

    void doActualRender() {
        ArrayList<ScreenDefinition> screenPathDefList = screenUrlInfo.screenPathDefList
        int screenPathDefListSize = screenPathDefList.size()
        ExecutionContextFactoryImpl.WebappInfo webappInfo = ec.ecfi.getWebappInfo(webappName)

        boolean isServerStatic = screenUrlInfo.targetScreen.isServerStatic(renderMode)
        // TODO: consider server caching of rendered screen, this is the place to do it

        boolean beganTransaction = screenUrlInfo.beginTransaction ? sfi.ecfi.transactionFacade.begin(screenUrlInfo.transactionTimeout) : false
        try {
            // run always-actions for all screens in path
            boolean hasAlwaysActions = false
            for (int i = 0; i < screenPathDefListSize; i++) {
                ScreenDefinition sd = (ScreenDefinition) screenPathDefList.get(i)
                if (sd.alwaysActions != null) { hasAlwaysActions = true; break }
            }
            if (hasAlwaysActions) {
                screenPathIndex = 0
                recursiveRunActions(true, false)
                screenPathIndex = 0
            }

            if (response != null) {
                response.setContentType(this.outputContentType)
                response.setCharacterEncoding(this.characterEncoding)
                if (isServerStatic) {
                    if (webappInfo != null) {
                        webappInfo.addHeaders("screen-server-static", response)
                    } else {
                        response.addHeader("Cache-Control", "max-age=86400, must-revalidate, public")
                    }
                } else {
                    if (webappInfo != null) {
                        webappInfo.addHeaders("screen-render", response)
                    } else {
                        // if requires a render, don't cache and make it private
                        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate, private")
                        // add Content-Security-Policy by default to not allow use in iframe or allow form actions on different host
                        // see https://content-security-policy.com/
                        // TODO make this configurable for different screen paths? maybe a screen.web-settings attribute to exclude or add to?
                        response.addHeader("Content-Security-Policy", "frame-ancestors 'none'; form-action 'self';")
                        response.addHeader("X-Frame-Options", "deny")
                    }
                }
                // if the request is secure add HSTS Strict-Transport-Security header with one leap year age (in seconds)
                if (request.isSecure()) {
                    if (webappInfo != null) {
                        webappInfo.addHeaders("screen-secure", response)
                    } else {
                        response.addHeader("Strict-Transport-Security", "max-age=31536000")
                    }
                }

                String filename = ec.context.saveFilename as String
                if (filename) {
                    String utfFilename = StringUtilities.encodeAsciiFilename(filename)
                    response.addHeader("Content-Disposition", "attachment; filename=\"${filename}\"; filename*=utf-8''${utfFilename}")
                }
            }

            // for inherited permissions to work, walk the screen list before the screens to render and artifact push
            // them, then pop after
            ArrayList<ArtifactExecutionInfo> aeiList = null
            if (screenUrlInfo.renderPathDifference > 0) {
                aeiList = new ArrayList<ArtifactExecutionInfo>(screenUrlInfo.renderPathDifference)
                for (int i = 0; i < screenUrlInfo.renderPathDifference; i++) {
                    ScreenDefinition permSd = screenPathDefList.get(i)

                    // check the subscreens item for this screen (valid in context)
                    if (i > 0) {
                        String curPathName = screenUrlInfo.fullPathNameList.get(i - 1) // one lower in path as it doesn't have root screen
                        ScreenDefinition parentScreen = screenPathDefList.get(i - 1)
                        SubscreensItem ssi = parentScreen.getSubscreensItem(curPathName)
                        if (ssi == null) {
                            logger.warn("Couldn't find SubscreenItem: parent ${parentScreen.getScreenName()}, curPathName ${curPathName}, current ${permSd.getScreenName()}\npath list: ${screenUrlInfo.fullPathNameList}\nscreen list: ${screenUrlInfo.screenPathDefList}")
                        } else {
                            if (!ssi.isValidInCurrentContext())
                                throw new ArtifactAuthorizationException("The screen ${permSd.getScreenName()} is not available")
                        }
                    }

                    ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(permSd.location,
                            ArtifactExecutionInfo.AT_XML_SCREEN, ArtifactExecutionInfo.AUTHZA_VIEW, outputContentType)
                    ec.artifactExecutionFacade.pushInternal(aei, false, false)
                    aeiList.add(aei)
                }
            }

            try {
                int preActionStartIndex
                if (screenUrlInfo.targetScreenRenderMode != null && sfi.isRenderModeAlwaysStandalone(screenUrlInfo.targetScreenRenderMode) &&
                        screenPathDefListSize > 2) {
                    // special case for render modes that are always standalone: run pre-actions for all screens in path except first 2 (generally webroot, apps)
                    preActionStartIndex = 2
                } else {
                    // run pre-actions for just the screens that will be rendered
                    preActionStartIndex = screenUrlInfo.renderPathDifference
                }
                boolean hasPreActions = false
                for (int i = preActionStartIndex; i < screenPathDefListSize; i++) {
                    ScreenDefinition sd = (ScreenDefinition) screenPathDefList.get(i)
                    if (sd.preActions != null) { hasPreActions = true; break }
                }
                if (hasPreActions) {
                    screenPathIndex = preActionStartIndex
                    recursiveRunActions(false, true)
                    screenPathIndex = 0
                }

                // if dontDoRender then quit now; this should be set during always-actions or pre-actions
                if (dontDoRender) { return }

                // we've run always and pre actions, it's now or never for required parameters so check them
                if (!sfi.isRenderModeSkipActions(renderMode)) {
                    for (int i = screenUrlInfo.renderPathDifference; i < screenPathDefListSize; i++) {
                        ScreenDefinition sd = (ScreenDefinition) screenPathDefList.get(i)
                        for (ScreenDefinition.ParameterItem pi in sd.getParameterMap().values()) {
                            if (!pi.required) continue
                            Object parmValue = ec.context.getByString(pi.name)
                            if (ObjectUtilities.isEmpty(parmValue)) {
                                ec.message.addError(ec.resource.expand("Required parameter missing (${pi.name})","",[pi:pi]))
                                logger.warn("Tried to render screen [${sd.getLocation()}] without required parameter [${pi.name}], error message added and adding to stop list to not render")
                                stopRenderScreenLocations.add(sd.getLocation())
                            }
                        }
                    }
                }

                // start rendering at the root section of the first screen to render
                screenPathIndex = screenUrlInfo.renderPathDifference
                ScreenDefinition renderStartDef = getActiveScreenDef()
                // if there is no next screen to render then it is the target screen, otherwise it's not
                renderStartDef.render(this, !getActiveScreenHasNext())

                // if these aren't already cleared it out means they haven't been included in the output, so add them here
                if (afterScreenWriter != null) internalWriter.write(afterScreenWriter.toString())
                if (scriptWriter != null) {
                    internalWriter.write("\n<script>\n")
                    internalWriter.write(scriptWriter.toString())
                    internalWriter.write("\n</script>\n")
                }
            } finally {
                // pop all screens, then good to go
                if (aeiList) for (int i = (aeiList.size() - 1); i >= 0; i--) ec.artifactExecution.pop(aeiList.get(i))
            }

            // save the screen history
            if (saveHistory && screenUrlInfo.targetExists) {
                WebFacade webFacade = ec.getWeb()
                if (webFacade != null && webFacade instanceof WebFacadeImpl) ((WebFacadeImpl) webFacade).saveScreenHistory(screenUrlInstance)
            }
        } catch (ArtifactAuthorizationException e) {
            throw e
        } catch (ArtifactTarpitException e) {
            throw e
        } catch (Throwable t) {
            String errMsg = "Error rendering screen [${getActiveScreenDef().location}]"
            sfi.ecfi.transactionFacade.rollback(beganTransaction, errMsg, t)
            throw new RuntimeException(errMsg, t)
        } finally {
            // if we began a tx commit it
            if (beganTransaction && sfi.ecfi.transactionFacade.isTransactionInPlace()) sfi.ecfi.transactionFacade.commit()
        }
    }

    boolean checkWebappSettings(ScreenDefinition currentSd) {
        if (request == null) return true

        MNode webSettingsNode = currentSd.webSettingsNode
        if (webSettingsNode != null && "false".equals(webSettingsNode.attribute("allow-web-request")))
            throw new BaseArtifactException("The screen [${currentSd.location}] cannot be used in a web request (allow-web-request=false).")

        String mimeType = webSettingsNode != null ? webSettingsNode.attribute("mime-type") : null
        if (mimeType != null && mimeType.length() > 0) this.outputContentType = mimeType
        String characterEncoding = webSettingsNode != null ? webSettingsNode.attribute("character-encoding") : null
        if (characterEncoding != null && characterEncoding.length() > 0) this.characterEncoding = characterEncoding

        // if screen requires auth and there is not active user redirect to login screen, save this request
        // if (isTraceEnabled) logger.trace("Checking screen [${currentSd.location}] for require-authentication, current user is [${ec.user.userId}]")

        WebFacadeImpl wfi = ec.getWebImpl()
        String requireAuthentication = currentSd.screenNode?.attribute("require-authentication")
        String userId = ec.getUser().getUserId()
        if ((requireAuthentication == null || requireAuthentication.length() == 0 || requireAuthentication == "true")
                && (userId == null || userId.length() == 0) && !ec.userFacade.getLoggedInAnonymous()) {
            if (logger.isInfoEnabled()) logger.info("Screen at location ${currentSd.location}, which is part of ${screenUrlInfo.fullPathNameList} under screen ${screenUrlInfo.fromSd.location} requires authentication but no user is currently logged in.")
            // save the request as a save-last to use after login
            if (wfi != null && screenUrlInfo.fileResourceRef == null) {
                StringBuilder screenPath = new StringBuilder()
                for (String pn in originalScreenPathNameList) if (pn) screenPath.append("/").append(pn)
                // logger.warn("saving screen last: ${screenPath.toString()}")
                wfi.saveScreenLastInfo(screenPath.toString(), null)
                // save messages in session before redirecting so they can be displayed on the next screen
                wfi.saveMessagesToSession()
            }

            // find the last login path from screens in path (whether rendered or not)
            String loginPath = "/Login"
            for (ScreenDefinition sd in screenUrlInfo.screenPathDefList) {
                String loginPathAttr = (String) sd.screenNode.attribute("login-path")
                if (loginPathAttr) loginPath = loginPathAttr
            }

            if (screenUrlInfo.lastStandalone != 0 || screenUrlInstance.getTargetTransition() != null) {
                // just send a 401 response, should always be for data submit, content rendering, JS AJAX requests, etc
                if (wfi != null) wfi.sendError(401, null, null)
                else if (response != null) response.sendError(401, "Authentication required")
                return false

                /* TODO: remove all of this, we don't need it
                ArrayList<String> pathElements = new ArrayList<>()
                if (!loginPath.startsWith("/")) {
                    pathElements.addAll(screenUrlInfo.preTransitionPathNameList)
                    pathElements.addAll(Arrays.asList(loginPath.split("/")))
                } else {
                    pathElements.addAll(Arrays.asList(loginPath.substring(1).split("/")))
                }

                // BEGIN what used to be only for requests for a json response
                Map<String, Object> responseMap = new HashMap<>()
                if (ec.message.getMessages().size() > 0) responseMap.put("messages", ec.message.messages)
                if (ec.message.getErrors().size() > 0) responseMap.put("errors", ec.message.errors)
                if (ec.message.getValidationErrors().size() > 0) {
                    List<ValidationError> valErrorList = ec.message.getValidationErrors()
                    int valErrorListSize = valErrorList.size()
                    ArrayList<Map> valErrMapList = new ArrayList<>(valErrorListSize)
                    for (int i = 0; i < valErrorListSize; i++) valErrMapList.add(valErrorList.get(i).getMap())
                    responseMap.put("validationErrors", valErrMapList)
                }

                Map parms = new HashMap()
                if (ec.web.requestParameters != null) parms.putAll(ec.web.requestParameters)
                // if (ec.web.requestAttributes != null) parms.putAll(ec.web.requestAttributes)
                responseMap.put("currentParameters", ContextJavaUtil.unwrapMap(parms))

                responseMap.put("redirectUrl", '/' + pathElements.join('/'))
                // logger.warn("Sending JSON no authc response: ${responseMap}")
                ec.web.sendJsonResponse(responseMap)

                // END what used to be only for requests for a json response
                */

                /* better to always send a JSON response as above instead of sometimes sending the Login screen, other that status response usually ignored anyway
                if ("json".equals(screenUrlInfo.targetTransitionExtension) || request?.getHeader("Accept")?.contains("application/json")) {
                } else {
                    // respond with 401 and the login screen instead of a redirect; JS client libraries handle this much better
                    this.originalScreenPathNameList = pathElements
                    // reset screenUrlInfo and call this again to start over with the new target
                    screenUrlInfo = null
                    internalRender()
                }

                return false
                */
            } else {
                // now prepare and send the redirect
                ScreenUrlInfo suInfo = ScreenUrlInfo.getScreenUrlInfo(this, rootScreenDef, new ArrayList<String>(), loginPath, 0)
                UrlInstance urlInstance = suInfo.getInstance(this, false)
                response.sendRedirect(urlInstance.url)
                return false
            }
        }

        // if request not secure and screens requires secure redirect to https
        ExecutionContextFactoryImpl.WebappInfo webappInfo = ec.ecfi.getWebappInfo(webappName)
        if (!request.isSecure() && (webSettingsNode == null || webSettingsNode.attribute("require-encryption") != "false") &&
                webappInfo != null && webappInfo.httpsEnabled) {
            if (logger.isInfoEnabled()) logger.info("Screen at location ${currentSd.location}, which is part of ${screenUrlInfo.fullPathNameList} under screen ${screenUrlInfo.fromSd.location} requires an encrypted/secure connection but the request is not secure, sending redirect to secure.")
            // save messages in session before redirecting so they can be displayed on the next screen
            if (wfi != null) wfi.saveMessagesToSession()
            // redirect to the same URL this came to
            response.sendRedirect(screenUrlInstance.getUrlWithParams())
            return false
        }

        return true
    }

    boolean doBoundaryComments() {
        if (screenPathIndex == 0) return false
        if (boundaryComments != null) return boundaryComments.booleanValue()
        boundaryComments = "true".equals(sfi.ecfi.confXmlRoot.first("screen-facade").attribute("boundary-comments"))
        return boundaryComments
    }

    ScreenDefinition getRootScreenDef() { return rootScreenDef }
    ScreenDefinition getActiveScreenDef() {
        if (overrideActiveScreenDef != null) return overrideActiveScreenDef
        // no -1 here because the list includes the root screen
        return (ScreenDefinition) screenUrlInfo.screenPathDefList.get(screenPathIndex)
    }
    ScreenDefinition getNextScreenDef() {
        if (!getActiveScreenHasNext()) return null
        return (ScreenDefinition) screenUrlInfo.screenPathDefList.get(screenPathIndex + 1)
    }
    String getActiveScreenPathName() {
        if (screenPathIndex == 0) return ""
        // subtract 1 because path name list doesn't include root screen
        return screenUrlInfo.fullPathNameList.get(screenPathIndex - 1)
    }
    String getNextScreenPathName() {
        // would subtract 1 because path name list doesn't include root screen, but we want next so use current screenPathIndex
        return screenUrlInfo.fullPathNameList.get(screenPathIndex)
    }
    boolean getActiveScreenHasNext() { return (screenPathIndex + 1) < screenUrlInfo.screenPathDefList.size() }

    ArrayList<String> getActiveScreenPath() {
        // handle case where root screen is first/zero in list versus a standalone screen
        if (screenPathIndex == 0) return new ArrayList<String>()
        ArrayList<String> activePath = new ArrayList<>(screenUrlInfo.fullPathNameList[0..screenPathIndex-1])
        // logger.info("===== activePath=${activePath}, rpd=${screenUrlInfo.renderPathDifference}, spi=${screenPathIndex}, fpi=${fullPathIndex}\nroot: ${screenUrlInfo.rootSd.location}\ntarget: ${screenUrlInfo.targetScreen.location}\nfrom: ${screenUrlInfo.fromSd.location}\nfrom path: ${screenUrlInfo.fromPathList}")
        return activePath
    }

    String renderSubscreen() {
        // first see if there is another screen def in the list
        if (!getActiveScreenHasNext()) {
            if (screenUrlInfo.fileResourceRef != null) {
                // NOTE: don't set this.outputContentType, when including in a screen the screen determines the type
                sfi.ecfi.resourceFacade.template(screenUrlInfo.fileResourceRef.location, writer)
                return ""
            } else {
                return "Tried to render subscreen in screen [${getActiveScreenDef()?.location}] but there is no subscreens.@default-item, and no more valid subscreen names in the screen path [${screenUrlInfo.fullPathNameList}]"
            }
        }

        ScreenDefinition screenDef = getNextScreenDef()
        // check the subscreens item for this screen (valid in context)
        if (screenPathIndex > 0) {
            String curPathName = getNextScreenPathName()
            ScreenDefinition parentScreen = getActiveScreenDef()
            SubscreensItem ssi = parentScreen.getSubscreensItem(curPathName)
            if (ssi == null) {
                logger.warn("Couldn't find SubscreenItem (render): parent ${parentScreen.getScreenName()}, curPathName ${curPathName}, current ${screenDef.getScreenName()}\npath list: ${screenUrlInfo.fullPathNameList}\nscreen list: ${screenUrlInfo.screenPathDefList}")
            } else {
                if (!ssi.isValidInCurrentContext())
                    throw new ArtifactAuthorizationException("The screen ${screenDef.getScreenName()} is not available")
            }
        }

        screenPathIndex++
        try {
            if (!stopRenderScreenLocations.contains(screenDef.getLocation())) {
                writer.flush()
                screenDef.render(this, !getActiveScreenHasNext())
                writer.flush()
            }
        } catch (Throwable t) {
            logger.error("Error rendering screen [${screenDef.location}]", t)
            return "Error rendering screen [${screenDef.location}]: ${t.toString()}"
        } finally {
            screenPathIndex--
        }
        // NOTE: this returns a String so that it can be used in an FTL interpolation, but it always writes to the writer
        return ""
    }

    Template getTemplate() {
        if (macroTemplateLocation != null) {
            return sfi.getTemplateByLocation(macroTemplateLocation)
        } else {
            String overrideTemplateLocation = (String) null
            // go through entire screenPathDefList so that parent screen can override template even if it isn't rendered to decorate subscreen
            ArrayList<ScreenDefinition> screenPathDefList = screenUrlInfo.screenPathDefList
            int screenPathDefListSize = screenPathDefList.size()
            for (int i = 0; i < screenPathDefListSize; i++) {
                ScreenDefinition sd = (ScreenDefinition) screenPathDefList.get(i)
                String curLocation = sd.getMacroTemplateLocation(renderMode)
                if (curLocation != null && curLocation.length() > 0) overrideTemplateLocation = curLocation
            }
            return overrideTemplateLocation != null ? sfi.getTemplateByLocation(overrideTemplateLocation) : sfi.getTemplateByMode(renderMode)
        }
    }
    ScreenWidgetRender getScreenWidgetRender() {
        ScreenWidgetRender swr = sfi.getWidgetRenderByMode(renderMode)
        if (swr == null) throw new BaseArtifactException("Could not find ScreenWidgerRender implementation for render mode ${renderMode}")
        return swr
    }

    String renderSection(String sectionName) {
        ScreenDefinition sd = getActiveScreenDef()
        try {
            ScreenSection section = sd.getSection(sectionName)
            if (section == null) throw new BaseArtifactException("No section with name [${sectionName}] in screen [${sd.location}]")
            writer.flush()
            section.render(this)
            writer.flush()
        } catch (Throwable t) {
            BaseException.filterStackTrace(t)
            logger.error("Error rendering section [${sectionName}] in screen [${sd.location}]: " + t.toString(), t)
            return "Error rendering section [${sectionName}] in screen [${sd.location}]: ${t.toString()}"
        }
        // NOTE: this returns a String so that it can be used in an FTL interpolation, but it always writes to the writer
        return ""
    }

    MNode getFormNode(String formName) {
        FormInstance fi = getFormInstance(formName)
        if (fi == null) return null
        return fi.getFormNode()
    }
    FormInstance getFormInstance(String formName) {
        ScreenDefinition sd = getActiveScreenDef()
        String nodeCacheKey = sd.getLocation() + "#" + formName
        // NOTE: this is cached in the context of the renderer for multiple accesses; because of form overrides may not
        // be valid outside the scope of a single screen render
        FormInstance formNode = screenFormCache.get(nodeCacheKey)
        if (formNode == null) {
            ScreenForm form = sd.getForm(formName)
            if (!form) throw new BaseArtifactException("No form with name [${formName}] in screen [${sd.location}]")
            formNode = form.getFormInstance()
            screenFormCache.put(nodeCacheKey, formNode)
        }
        return formNode
    }

    String renderIncludeScreen(String location, String shareScopeStr) {
        boolean shareScope = shareScopeStr == "true"

        ContextStack cs = (ContextStack) ec.context
        ScreenDefinition oldOverrideActiveScreenDef = overrideActiveScreenDef
        try {
            if (!shareScope) cs.push()
            writer.flush()

            ScreenDefinition screenDef = sfi.getScreenDefinition(location)
            if (!screenDef) throw new BaseArtifactException("Could not find screen at location [${location}]")
            overrideActiveScreenDef = screenDef
            screenDef.render(this, false)

            // this way is more literal, but has issues with relative paths and such:
            // sfi.makeRender().rootScreen(location).renderMode(renderMode).encoding(characterEncoding)
            //         .macroTemplate(macroTemplateLocation).render(writer)

            writer.flush()
        } catch (Throwable t) {
            logger.error("Error rendering screen [${location}]", t)
            return "Error rendering screen [${location}]: ${t.toString()}"
        } finally {
            overrideActiveScreenDef = oldOverrideActiveScreenDef
            if (!shareScope) cs.pop()
        }

        // NOTE: this returns a String so that it can be used in an FTL interpolation, but it always writes to the writer
        return ""
    }

    String renderText(String location, String isTemplateStr) {
        boolean isTemplate = !"false".equals(isTemplateStr)

        if (location == null || location.length() == 0 || "null".equals(location)) {
            logger.warn("Not rendering text in screen [${getActiveScreenDef().location}], location was empty")
            return ""
        }
        if (isTemplate) {
            writer.flush()
            // NOTE: run templates with their own variable space so we can add sri, and avoid getting anything added from within
            ContextStack cs = (ContextStack) ec.context
            cs.push()
            cs.put("sri", this)
            sfi.ecfi.resourceFacade.template(location, writer)
            cs.pop()
            writer.flush()
            // NOTE: this returns a String so that it can be used in an FTL interpolation, but it always writes to the writer
            return ""
        } else {
            return sfi.ecfi.resourceFacade.getLocationText(location, true) ?: ""
        }
    }

    String appendToAfterScreenWriter(String text) {
        if (afterScreenWriter == null) afterScreenWriter = new StringWriter()
        afterScreenWriter.append(text)
        // NOTE: this returns a String so that it can be used in an FTL interpolation, but it always writes to the writer
        return ""
    }
    String getAfterScreenWriterText() {
        String outText = afterScreenWriter == null ? "" : afterScreenWriter.toString()
        afterScreenWriter = null
        return outText
    }
    String appendToScriptWriter(String text) {
        if (scriptWriter == null) scriptWriter = new StringWriter()
        scriptWriter.append(text)
        // NOTE: this returns a String so that it can be used in an FTL interpolation, but it always writes to the writer
        return ""
    }
    String getScriptWriterText() {
        String outText = scriptWriter == null ? "" : scriptWriter.toString()
        scriptWriter = null
        return outText
    }

    ScreenUrlInfo buildUrlInfo(String subscreenPathOrig) {
        String subscreenPath = subscreenPathOrig?.contains("\${") ? ec.resource.expand(subscreenPathOrig, "") : subscreenPathOrig

        List<String> pathList = getActiveScreenPath()
        StringBuilder keyBuilder = new StringBuilder()
        for (String pathElem in pathList) keyBuilder.append(pathElem).append("/")
        String key = keyBuilder.append(subscreenPath).toString()

        ScreenUrlInfo csui = subscreenUrlInfos.get(key)
        if (csui != null) {
            // logger.warn("========== found cached ScreenUrlInfo ${key}")
            return csui
        }  else {
            // logger.warn("========== DID NOT find cached ScreenUrlInfo ${key}")
        }

        ScreenUrlInfo sui = ScreenUrlInfo.getScreenUrlInfo(this, null, null, subscreenPath, 0)
        subscreenUrlInfos.put(key, sui)
        return sui
    }

    UrlInstance buildUrl(String subscreenPath) {
        return buildUrlInfo(subscreenPath).getInstance(this, null)
    }
    UrlInstance buildUrl(ScreenDefinition fromSd, ArrayList<String> fromPathList, String subscreenPathOrig) {
        String subscreenPath = subscreenPathOrig?.contains("\${") ? ec.resource.expand(subscreenPathOrig, "") : subscreenPathOrig
        ScreenUrlInfo ui = ScreenUrlInfo.getScreenUrlInfo(this, fromSd, fromPathList, subscreenPath, 0)
        return ui.getInstance(this, null)
    }
    UrlInstance buildUrlFromTarget(String subscreenPathOrig) {
        String subscreenPath = subscreenPathOrig?.contains("\${") ? ec.resource.expand(subscreenPathOrig, "") : subscreenPathOrig
        ScreenUrlInfo ui = ScreenUrlInfo.getScreenUrlInfo(this, screenUrlInfo.targetScreen, screenUrlInfo.preTransitionPathNameList, subscreenPath, 0)
        return ui.getInstance(this, null)
    }

    UrlInstance makeUrlByType(String origUrl, String urlType, MNode parameterParentNode, String expandTransitionUrlString) {
        Boolean expandTransitionUrl = expandTransitionUrlString != null ? "true".equals(expandTransitionUrlString) : null
        /* TODO handle urlType=content: A content location (without the content://). URL will be one that can access that content. */
        ScreenUrlInfo suInfo
        String urlTypeExpanded = ec.resource.expand(urlType, "")
        switch (urlTypeExpanded) {
            // for transition we want a URL relative to the current screen, so just pass that to buildUrl
            case "transition": suInfo = buildUrlInfo(origUrl); break
            case "screen": suInfo = buildUrlInfo(origUrl); break
            case "content": throw new BaseArtifactException("The url-type of content is not yet supported"); break
            case "plain":
            default:
                String url = ec.resource.expand(origUrl, "")
                suInfo = ScreenUrlInfo.getScreenUrlInfo(this, url)
                break
        }

        UrlInstance urli = suInfo.getInstance(this, expandTransitionUrl)

        if (parameterParentNode != null) {
            String parameterMapStr = (String) parameterParentNode.attribute("parameter-map")
            if (parameterMapStr != null && !parameterMapStr.isEmpty()) {
                Map ctxParameterMap = (Map) ec.resource.expression(parameterMapStr, "")
                if (ctxParameterMap) urli.addParameters(ctxParameterMap)
            }
            ArrayList<MNode> parameterNodes = parameterParentNode.children("parameter")
            int parameterNodesSize = parameterNodes.size()
            for (int i = 0; i < parameterNodesSize; i++) {
                MNode parameterNode = (MNode) parameterNodes.get(i)
                String name = parameterNode.attribute("name")
                String from = parameterNode.attribute("from")
                if (from == null || from.isEmpty()) from = name
                urli.addParameter(name, getContextValue(from, parameterNode.attribute("value")))
            }
        }

        return urli
    }

    Object getContextValue(String from, String value) {
        if (value) {
            return ec.resource.expand(value, getActiveScreenDef().location, (Map) ec.contextStack.get("_formMap"))
        } else if (from) {
            return ec.resource.expression(from, getActiveScreenDef().location, (Map) ec.contextStack.get("_formMap"))
        } else {
            return ""
        }
    }
    String setInContext(MNode setNode) {
        ((ResourceFacadeImpl) ec.resource).setInContext(setNode.attribute("field"),
                setNode.attribute("from"), setNode.attribute("value"),
                setNode.attribute("default-value"), setNode.attribute("type"),
                setNode.attribute("set-if-empty"))
        return ""
    }
    String pushContext() { ec.contextStack.push(); return "" }
    String popContext() { ec.contextStack.pop(); return "" }

    /** Call this at the beginning of a form-single or for form-list.@first-row-map and @last-row-map. Always call popContext() at the end of the form! */
    String pushSingleFormMapContext(String mapExpr) {
        ContextStack cs = ec.contextStack
        Map valueMap = null
        if (mapExpr != null && !mapExpr.isEmpty()) valueMap = (Map) ec.resourceFacade.expression(mapExpr, null)
        if (valueMap instanceof EntityValue) valueMap = ((EntityValue) valueMap).getMap()
        if (valueMap == null) valueMap = new HashMap()

        cs.push()
        cs.putAll(valueMap)
        cs.put("_formMap", valueMap)

        return ""
    }
    String startFormListRow(ScreenForm.FormListRenderInfo listRenderInfo, Object listEntry, int index, boolean hasNext) {
        ContextStack cs = ec.contextStack
        cs.push()

        if (listEntry instanceof Map) {
            Map valueMap = (Map) listEntry
            if (valueMap instanceof EntityValue) valueMap = ((EntityValue) valueMap).getMap()
            cs.putAll(valueMap)
            cs.put("_formMap", valueMap)
        } else {
            throw new BaseArtifactException("Found form-list ${listRenderInfo.getFormNode().attribute('name')} list entry that is not a Map, is a ${listEntry.class.name} which should never happen after running list through list pre-processor")
        }
        // NOTE: this returns an empty String so that it can be used in an FTL interpolation, but nothing is written
        return ""
    }
    String endFormListRow() {
        ec.contextStack.pop()
        // NOTE: this returns an empty String so that it can be used in an FTL interpolation, but nothing is written
        return ""
    }
    String startFormListSubRow(ScreenForm.FormListRenderInfo listRenderInfo, Object subListEntry, int index, boolean hasNext) {
        ContextStack cs = ec.contextStack
        cs.push()
        MNode formNode = listRenderInfo.formNode
        if (subListEntry instanceof Map) {
            Map valueMap = (Map) subListEntry
            if (valueMap instanceof EntityValue) valueMap = ((EntityValue) valueMap).getMap()
            cs.putAll(valueMap)
            cs.put("_formMap", valueMap)
        } else {
            throw new BaseArtifactException("Found form-list ${listRenderInfo.getFormNode().attribute('name')} sub-list entry that is not a Map, is a ${subListEntry.class.name} which should never happen after running list through list pre-processor")
        }
        String listStr = formNode.attribute('list')
        cs.put(listStr + "_sub_index", index)
        cs.put(listStr + "_sub_has_next", hasNext)
        cs.put(listStr + "_sub_entry", subListEntry)
        // NOTE: this returns an empty String so that it can be used in an FTL interpolation, but nothing is written
        return ""
    }
    String endFormListSubRow() {
        ec.contextStack.pop()
        // NOTE: this returns an empty String so that it can be used in an FTL interpolation, but nothing is written
        return ""
    }
    static String safeCloseList(Object listObject) {
        if (listObject instanceof EntityListIterator) ((EntityListIterator) listObject).close()
        // NOTE: this returns an empty String so that it can be used in an FTL interpolation, but nothing is written
        return ""
    }

    String getFieldValueString(MNode widgetNode) {
        MNode fieldNodeWrapper = widgetNode.parent.parent
        String defaultValue = widgetNode.attribute("default-value")
        if (defaultValue == null) defaultValue = ""
        String format = widgetNode.attribute("format")
        if ("text".equals(renderMode) || "csv".equals(renderMode)) {
            String textFormat = widgetNode.attribute("text-format")
            if (textFormat != null && !textFormat.isEmpty()) format = textFormat
        }

        Object obj = getFieldValue(fieldNodeWrapper, defaultValue)
        if (obj == null) return ""
        if (obj instanceof String) return (String) obj
        String strValue = ec.l10nFacade.format(obj, format)
        return strValue
    }
    String getFieldValueString(MNode fieldNodeWrapper, String defaultValue, String format) {
        Object obj = getFieldValue(fieldNodeWrapper, defaultValue)
        if (obj == null) return ""
        if (obj instanceof String) return (String) obj
        String strValue = ec.l10nFacade.format(obj, format)
        return strValue
    }
    String getFieldValuePlainString(MNode fieldNodeWrapper, String defaultValue) {
        // NOTE: defaultValue is handled below so that for a plain string it is not run through expand
        Object obj = getFieldValue(fieldNodeWrapper, "")
        if (ObjectUtilities.isEmpty(obj) && defaultValue != null && defaultValue.length() > 0)
            return ec.resourceFacade.expandNoL10n(defaultValue, "")
        return ObjectUtilities.toPlainString(obj)
    }
    String getNamedValuePlain(String fieldName, MNode formNode) {
        Object value = null
        if ("form-single".equals(formNode.name)) {
            String mapAttr = formNode.attribute("map")
            String mapName = mapAttr != null && mapAttr.length() > 0 ? mapAttr : "fieldValues"
            Map valueMap = (Map) ec.resource.expression(mapName, "")

            if (valueMap != null) {
                try {
                    if (valueMap instanceof EntityValueBase) {
                        // if it is an EntityValueImpl, only get if the fieldName is a value
                        EntityValueBase evb = (EntityValueBase) valueMap
                        if (evb.getEntityDefinition().isField(fieldName)) value = evb.get(fieldName)
                    } else {
                        value = valueMap.get(fieldName)
                    }
                } catch (EntityException e) {
                    // do nothing, not necessarily an entity field
                    if (isTraceEnabled) logger.trace("Ignoring entity exception for non-field: ${e.toString()}")
                }
            }
        }
        if (value == null) value = ec.contextStack.getByString(fieldName)
        return ObjectUtilities.toPlainString(value)
    }

    Object getFieldValue(MNode fieldNode, String defaultValue) {
        String fieldName = fieldNode.attribute("name")
        Object value = null

        MNode formNode = fieldNode.parent
        if ("form-single".equals(formNode.name)) {
            // if this is an error situation try error parameters first
            Map<String, Object> errorParameters = ec.getWeb()?.getErrorParameters()
            if (errorParameters != null && (errorParameters.moquiFormName == fieldNode.parent.attribute("name"))) {
                value = errorParameters.get(fieldName)
                if (!ObjectUtilities.isEmpty(value)) return value
            }

            // NOTE: field.@from attribute is handled for form-list in pre-processing done by AggregationUtil
            String fromAttr = fieldNode.attribute("from")
            if (fromAttr == null || fromAttr.isEmpty()) fromAttr = fieldNode.attribute("entry-name")
            if (fromAttr != null && fromAttr.length() > 0) return ec.resourceFacade.expression(fromAttr, null)

            String mapAttr = formNode.attribute("map")
            String mapName = mapAttr != null && mapAttr.length() > 0 ? mapAttr : "fieldValues"
            Map valueMap = (Map) ec.resource.expression(mapName, "")

            if (valueMap != null) {
                try {
                    if (valueMap instanceof EntityValueBase) {
                        // if it is an EntityValueImpl, only get if the fieldName is a value
                        EntityValueBase evb = (EntityValueBase) valueMap
                        if (evb.getEntityDefinition().isField(fieldName)) value = evb.get(fieldName)
                    } else {
                        value = valueMap.get(fieldName)
                    }
                } catch (EntityException e) {
                    // do nothing, not necessarily an entity field
                    if (isTraceEnabled) logger.trace("Ignoring entity exception for non-field: ${e.toString()}")
                }
            }
        }

        // the value == null check here isn't necessary but is the most common case so
        if (value == null || ObjectUtilities.isEmpty(value)) {
            value = ec.contextStack.getByString(fieldName)
            if (!ObjectUtilities.isEmpty(value)) return value
        } else {
            return value
        }

        String defaultStr = ec.getResource().expand(defaultValue, null)
        if (defaultStr != null && defaultStr.length() > 0) return defaultStr
        return value
    }
    String getFieldValueClass(MNode fieldNodeWrapper) {
        Object fieldValue = getFieldValue(fieldNodeWrapper, null)
        return fieldValue != null ? fieldValue.getClass().getSimpleName() : "String"
    }

    String getFieldEntityValue(MNode widgetNode) {
        MNode fieldNode = widgetNode.parent.parent
        Object fieldValue = getFieldValue(fieldNode, "")
        if (fieldValue == null) return getDefaultText(widgetNode)
        String entityName = widgetNode.attribute("entity-name")
        EntityDefinition ed = sfi.ecfi.entityFacade.getEntityDefinition(entityName)

        // find the entity value
        String keyFieldName = widgetNode.attribute("key-field-name")
        if (keyFieldName == null || keyFieldName.isEmpty()) keyFieldName = widgetNode.attribute("entity-key-name")
        if (keyFieldName == null || keyFieldName.isEmpty()) keyFieldName = ed.getPkFieldNames().get(0)
        String useCache = widgetNode.attribute("use-cache") ?: widgetNode.attribute("entity-use-cache") ?: "true"
        EntityValue ev = ec.entity.find(entityName).condition(keyFieldName, fieldValue)
                .useCache(useCache == "true").one()
        if (ev == null) return getDefaultText(widgetNode)

        String value = ""
        String text = (String) widgetNode.attribute("text")
        if (text != null && text.length() > 0) {
            // push onto the context and then expand the text
            ec.context.push(ev.getMap())
            try {
                value = ec.resource.expand(text, null)
            } finally {
                ec.context.pop()
            }
        } else {
            // get the value of the default description field for the entity
            String defaultDescriptionField = ed.getDefaultDescriptionField()
            if (defaultDescriptionField) value = ev.get(defaultDescriptionField)
        }
        return value
    }
    protected String getDefaultText(MNode widgetNode) {
        String defaultText = widgetNode.attribute("default-text")
        if (defaultText != null && defaultText.length() > 0) {
            return ec.resource.expand(defaultText, null)
        } else {
            return ""
        }
    }

    LinkedHashMap<String, String> getFieldOptions(MNode widgetNode) {
        LinkedHashMap<String, String> optsMap = ScreenForm.getFieldOptions(widgetNode, ec)
        if (optsMap.size() == 0 && widgetNode.hasChild("dynamic-options")) {
            MNode childNode = widgetNode.first("dynamic-options")
            if (!"true".equals(childNode.attribute("server-search"))) {
                // a bit of a hack, use ScreenTest to call the transition server-side as if it were a web request
                String transition = childNode.attribute("transition")
                String labelField = childNode.attribute("label-field") ?: "label"
                String valueField = childNode.attribute("value-field") ?: "value"

                Map<String, Object> parameters = new HashMap<>()
                boolean hasAllDepends = addNodeParameters(childNode, parameters)
                // logger.warn("getFieldOptions parameters ${parameters}")

                if (hasAllDepends) {
                    UrlInstance transUrl = buildUrl(transition)
                    ScreenTest screenTest = ec.screen.makeTest().rootScreen(rootScreenLocation).skipJsonSerialize(true)
                    ScreenTest.ScreenTestRender str = screenTest.render(transUrl.getPathWithParams(), parameters, null)

                    Object jsonObj = str.getJsonObject()
                    List optsList = null
                    if (jsonObj instanceof List) {
                        optsList = (List) jsonObj
                    } else if (jsonObj instanceof Map) {
                        Map jsonMap = (Map) jsonObj
                        Object optionsObj = jsonMap.get("options")
                        if (optionsObj instanceof List) optsList = (List) optionsObj
                    }
                    if (optsList != null) for (Object entryObj in optsList) {
                        if (entryObj instanceof Map) {
                            Map entryMap = (Map) entryObj
                            String valueObj = entryMap.get(valueField)
                            String labelObj = entryMap.get(labelField)
                            if (valueObj && labelObj) optsMap.put(valueObj, labelObj)
                        }
                    }

                    /* old approach before skipJsonSerialize
                    String output = str.getOutput()

                    try {
                        Object jsonObj = new JsonSlurper().parseText(output)
                        List optsList = null
                        if (jsonObj instanceof List) {
                            optsList = (List) jsonObj
                        } else if (jsonObj instanceof Map) {
                            Map jsonMap = (Map) jsonObj
                            Object optionsObj = jsonMap.get("options")
                            if (optionsObj instanceof List) optsList = (List) optionsObj
                        }
                        if (optsList != null) for (Object entryObj in optsList) {
                            if (entryObj instanceof Map) {
                                Map entryMap = (Map) entryObj
                                String valueObj = entryMap.get(valueField)
                                String labelObj = entryMap.get(labelField)
                                if (valueObj && labelObj) optsMap.put(valueObj, labelObj)
                            }
                        }
                    } catch (Throwable t) {
                        logger.warn("Error getting field options from transition", t)
                    }
                    */
                }
            }
        }
        return optsMap
    }

    /** This is messy, does a server-side/internal 'test' render so we can get the label/description for the current value
     * from the transition written for client access. */
    String getFieldTransitionValue(String transition, MNode parameterParentNode, String term, String labelField, boolean alwaysGet) {
        if (!alwaysGet && (term == null || term.isEmpty())) return null
        if (!labelField) labelField = "label"

        Map<String, Object> parameters = new HashMap<>()
        parameters.put("term", term)
        boolean hasAllDepends = addNodeParameters(parameterParentNode, parameters)
        // logger.warn("getFieldTransitionValue parameters ${parameters}")
        // logger.warn("getFieldTransitionValue context ${ec.context.keySet()}")
        if (!hasAllDepends) return null

        UrlInstance transUrl = buildUrl(transition)
        ScreenTest screenTest = sfi.makeTest().rootScreen(rootScreenLocation)
        ScreenTest.ScreenTestRender str = screenTest.render(transUrl.getPathWithParams(), parameters, null)
        String output = str.getOutput()

        String transValue = null
        Object jsonObj = null
        try {
            jsonObj = new JsonSlurper().parseText(output)
            if (jsonObj instanceof List && ((List) jsonObj).size() > 0) {
                Object firstObj = ((List) jsonObj).get(0)
                if (firstObj instanceof Map) {
                    transValue = ((Map) firstObj).get(labelField)
                } else {
                    transValue = firstObj.toString()
                }
            } else if (jsonObj instanceof Map) {
                Map jsonMap = (Map) jsonObj
                Object optionsObj = jsonMap.get("options")
                if (optionsObj instanceof List && ((List) optionsObj).size() > 0) {
                    Object firstObj = ((List) optionsObj).get(0)
                    if (firstObj instanceof Map) {
                        transValue = ((Map) firstObj).get(labelField)
                    } else {
                        transValue = firstObj.toString()
                    }
                } else {
                    transValue = jsonMap.get(labelField)
                }
            } else if (jsonObj != null) {
                transValue = jsonObj.toString()
            }
        } catch (Throwable t) {
            // this happens all the time for non-JSON text response: logger.warn("Error getting field label from transition", t)
            transValue = output
        }

        // logger.warn("term ${term} output ${output} transValue ${transValue}")
        return transValue
    }

    Map<String, String> getFormHiddenParameters(MNode formNode) {
        Map<String, String> parmMap = new LinkedHashMap<>()
        if (formNode == null) return parmMap
        MNode hiddenParametersNode = formNode.first("hidden-parameters")
        if (hiddenParametersNode == null) return parmMap

        Map<String, Object> objMap = new LinkedHashMap<>()
        addNodeParameters(hiddenParametersNode, objMap)
        for (Map.Entry<String, Object> entry in objMap.entrySet()) {
            Object valObj = entry.getValue()
            String valStr = ObjectUtilities.toPlainString(valObj)
            if (valStr != null && !valStr.isEmpty()) parmMap.put(entry.getKey(), valStr)
        }

        return parmMap
    }

    boolean addNodeParameters(MNode parameterParentNode, Map<String, Object> parameters) {
        if (parameterParentNode == null) return true
        // get specified parameters
        String parameterMapStr = (String) parameterParentNode.attribute("parameter-map")
        if (parameterMapStr != null && !parameterMapStr.isEmpty()) {
            Map ctxParameterMap = (Map) ec.resource.expression(parameterMapStr, "")
            if (ctxParameterMap != null) parameters.putAll(ctxParameterMap)
        }
        ArrayList<MNode> parameterNodes = parameterParentNode.children("parameter")
        int parameterNodesSize = parameterNodes.size()
        for (int i = 0; i < parameterNodesSize; i++) {
            MNode parameterNode = (MNode) parameterNodes.get(i)
            String name = parameterNode.attribute("name")
            String from = parameterNode.attribute("from")
            if (from == null || from.isEmpty()) from = name
            parameters.put(name, getContextValue(from, parameterNode.attribute("value")))
        }

        // get current values for depends-on fields
        boolean dependsOptional = "true".equals(parameterParentNode.attribute("depends-optional"))
        boolean hasAllDepends = true
        ArrayList<MNode> doNodeList = parameterParentNode.children("depends-on")
        for (int i = 0; i < doNodeList.size(); i++) {
            MNode doNode = (MNode) doNodeList.get(i)
            String doField = doNode.attribute("field")
            String doParameter = doNode.attribute("parameter") ?: doField
            Object contextVal = ec.context.get(doField)
            if (ObjectUtilities.isEmpty(contextVal) && ec.contextStack.get("_formMap") != null)
                contextVal = ((Map) ec.contextStack.get("_formMap")).get(doField)
            if (ObjectUtilities.isEmpty(contextVal)) {
                hasAllDepends = false
            } else {
                parameters.put(doParameter, contextVal)
            }
        }

        return hasAllDepends || dependsOptional
    }

    boolean isInCurrentScreenPath(List<String> pathNameList) {
        if (pathNameList.size() > screenUrlInfo.fullPathNameList.size()) return false
        for (int i = 0; i < pathNameList.size(); i++) {
            if (pathNameList.get(i) != screenUrlInfo.fullPathNameList.get(i)) return false
        }
        return true
    }
    boolean isActiveInCurrentMenu() {
        List<String> currentScreenPath = screenUrlInfo ? new ArrayList(screenUrlInfo.fullPathNameList) : null
        for (SubscreensItem ssi in getActiveScreenDef().subscreensByName.values()) {
            if (!ssi.menuInclude) continue
            ScreenUrlInfo urlInfo = buildUrlInfo(ssi.name)
            if (urlInfo.getInCurrentScreenPath(currentScreenPath)) return true
        }
        return false
    }
    boolean isAnchorLink(MNode linkNode, UrlInstance urlInstance) {
        String linkType = linkNode.attribute("link-type")
        String urlType = linkNode.attribute("url-type")
        return ("anchor".equals(linkType) || "anchor-button".equals(linkType)) || ((!linkType || "auto".equals(linkType)) &&
                ((urlType && !urlType.equals("transition")) || (urlInstance.isReadOnly())))
    }

    UrlInstance getCurrentScreenUrl() { return screenUrlInstance }
    URI getBaseLinkUri() {
        String urlString = baseLinkUrl ?: screenUrlInstance.getScreenPathUrl()
        // logger.warn("=================== urlString=${urlString}, baseLinkUrl=${baseLinkUrl}")
        URL blu = new URL(urlString)
        // NOTE: not including user info, query, or fragment... should consider them?
        // NOTE: using the multi-argument constructor so it will encode stuff
        URI baseUri = new URI(blu.getProtocol(), null, blu.getHost(), blu.getPort(), blu.getPath(), null, null)
        return baseUri
    }

    String getCurrentThemeId() {
        if (curThemeId != null) return curThemeId

        String stteId = null
        // loop through only screens to render and look for @screen-theme-type-enum-id, use last one found
        ArrayList<ScreenDefinition> screenPathDefList = screenUrlInfo.screenPathDefList
        int screenPathDefListSize = screenPathDefList.size()
        for (int i = screenUrlInfo.renderPathDifference; i < screenPathDefListSize; i++) {
            ScreenDefinition sd = (ScreenDefinition) screenPathDefList.get(i)
            String stteiStr = sd.screenNode.attribute("screen-theme-type-enum-id")
            if (stteiStr != null && stteiStr.length() > 0) stteId = stteiStr
        }
        // if no setting default to STT_INTERNAL
        if (stteId == null) stteId = "STT_INTERNAL"

        EntityFacadeImpl entityFacade = sfi.ecfi.entityFacade
        // see if there is a user setting for the theme
        String themeId = entityFacade.fastFindOne("moqui.security.UserScreenTheme", true, true, ec.userFacade.userId, stteId)?.screenThemeId
        // use the Enumeration.enumCode from the type to find the theme type's default screenThemeId
        if (themeId == null || themeId.length() == 0) {
            EntityValue themeTypeEnum = entityFacade.fastFindOne("moqui.basic.Enumeration", true, true, stteId)
            if (themeTypeEnum?.enumCode) themeId = themeTypeEnum.enumCode
        }
        // theme with "DEFAULT" in the ID
        if (themeId == null || themeId.length() == 0) {
            EntityValue stv = entityFacade.find("moqui.screen.ScreenTheme")
                    .condition("screenThemeTypeEnumId", stteId)
                    .condition("screenThemeId", ComparisonOperator.LIKE, "%DEFAULT%").disableAuthz().one()
            if (stv) themeId = stv.screenThemeId
        }

        curThemeId = themeId ?: ""
        return themeId
    }

    ArrayList<String> getThemeValues(String resourceTypeEnumId) {
        ArrayList<String> cachedList = (ArrayList<String>) curThemeValuesByType.get(resourceTypeEnumId)
        if (cachedList != null) return cachedList

        EntityList strList = sfi.ecfi.entityFacade.find("moqui.screen.ScreenThemeResource")
                .condition("screenThemeId", getCurrentThemeId()).condition("resourceTypeEnumId", resourceTypeEnumId)
                .orderBy("sequenceNum").useCache(true).disableAuthz().list()
        int strListSize = strList.size()
        ArrayList<String> values = new ArrayList<>(strListSize)
        for (int i = 0; i < strListSize; i++) {
            EntityValue str = (EntityValue) strList.get(i)
            String resourceValue = (String) str.getNoCheckSimple("resourceValue")
            if (resourceValue != null && !resourceValue.isEmpty()) values.add(resourceValue)
        }

        curThemeValuesByType.put(resourceTypeEnumId, values)
        return values
    }
    // NOTE: this is called a LOT during screen renders, for links/buttons/etc
    String getThemeIconClass(String text) {
        String screenThemeId = getCurrentThemeId()
        Map<String, String> curThemeIconByText = sfi.getThemeIconByText(screenThemeId)
        if (curThemeIconByText.containsKey(text)) return curThemeIconByText.get(text)

        EntityList stiList = sfi.ecfi.entityFacade.find("moqui.screen.ScreenThemeIcon")
                .condition("screenThemeId", screenThemeId).useCache(true).disableAuthz().list()
        int stiListSize = stiList.size()
        String iconClass = (String) null
        for (int i = 0; i < stiListSize; i++) {
            EntityValue sti = (EntityValue) stiList.get(i)
            if (text.matches(sti.getString("textPattern"))) {
                iconClass = sti.getString("iconClass")
                break
            }
        }

        curThemeIconByText.put(text, iconClass)
        return iconClass
    }

    List<Map> getMenuData(ArrayList<String> pathNameList) {
        if (!ec.user.userId) { ec.web.sendError(401, "Authentication required", null); return null }
        ScreenUrlInfo fullUrlInfo = ScreenUrlInfo.getScreenUrlInfo(this, rootScreenDef, pathNameList, null, 0)
        if (!fullUrlInfo.targetExists) { ec.web.sendError(404, "Screen not found for path ${pathNameList}", null); return null }
        UrlInstance fullUrlInstance = fullUrlInfo.getInstance(this, null)
        if (!fullUrlInstance.isPermitted()) { ec.web.sendError(403, "View not permitted for path ${pathNameList}", null); return null }

        ArrayList<String> fullPathList = fullUrlInfo.fullPathNameList
        int fullPathSize = fullPathList.size()
        ArrayList<String> extraPathList = fullUrlInfo.extraPathNameList
        int extraPathSize = extraPathList != null ? extraPathList.size() : 0
        if (extraPathSize > 0) {
            fullPathSize -= extraPathSize
            fullPathList = new ArrayList<String>(fullPathList.subList(0, fullPathSize))
        }

        StringBuilder currentPath = new StringBuilder()
        List<Map> menuDataList = new LinkedList<>()
        ScreenDefinition curScreen = rootScreenDef

        // to support menu titles with values set in pre-actions: run pre-actions for all screens in path except first 2 (generally webroot, apps)
        ec.artifactExecutionFacade.setAnonymousAuthorizedView()
        ec.userFacade.loginAnonymousIfNoUser()
        ArrayList<ScreenDefinition> preActionSds = new ArrayList<>(fullUrlInfo.screenPathDefList.subList(2, fullUrlInfo.screenPathDefList.size()))
        int preActionSdSize = preActionSds.size()
        for (int i = 0; i < preActionSdSize; i++) {
            ScreenDefinition sd = (ScreenDefinition) preActionSds.get(i)
            if (sd.preActions != null) {
                try { sd.preActions.run(ec) }
                catch (Throwable t) { logger.warn("Error running pre-actions in ${sd.getLocation()} while getting menu data: " + t.toString()) }
            }
        }

        for (int i = 0; i < (fullPathSize - 1); i++) {
            String pathItem = (String) fullPathList.get(i)
            String nextItem = (String) fullPathList.get(i+1)
            currentPath.append('/').append(StringUtilities.urlEncodeIfNeeded(pathItem))

            SubscreensItem curSsi = curScreen.getSubscreensItem(pathItem)
            // already checked for exists above, path may have extra path elements beyond the screen so allow it
            if (curSsi == null) break
            curScreen = ec.screenFacade.getScreenDefinition(curSsi.location)

            List<Map> subscreensList = new LinkedList<>()
            ArrayList<SubscreensItem> menuItems = curScreen.getMenuSubscreensItems()
            int menuItemsSize = menuItems.size()
            for (int j = 0; j < menuItemsSize; j++) {
                SubscreensItem subscreensItem = (SubscreensItem) menuItems.get(j)
                String screenPath = new StringBuilder(currentPath).append('/').append(StringUtilities.urlEncodeIfNeeded(subscreensItem.name)).toString()
                UrlInstance screenUrlInstance = buildUrl(screenPath)
                ScreenUrlInfo sui = screenUrlInstance.sui
                if (!screenUrlInstance.isPermitted()) continue
                // build this subscreen's pathWithParams
                String pathWithParams = "/" + sui.preTransitionPathNameList.join("/")
                Map<String, String> parmMap = screenUrlInstance.getParameterMap()
                // check for missing required parameters
                boolean parmMissing = false
                for (ScreenDefinition.ParameterItem pi in sui.pathParameterItems.values()) {
                    if (!pi.required) continue
                    String parmValue = parmMap.get(pi.name)
                    if (parmValue == null || parmValue.isEmpty()) { parmMissing = true; break }
                }
                // if there is a parameter missing skip the subscreen
                if (parmMissing) continue
                String parmString = screenUrlInstance.getParameterString()
                if (!parmString.isEmpty()) pathWithParams += ('?' + parmString)

                String image = sui.menuImage
                String imageType = sui.menuImageType
                if (image != null && image.length() > 0 && (imageType == null || imageType.length() == 0 || "url-screen".equals(imageType)))
                    image = buildUrl(image).path

                boolean active = (nextItem == subscreensItem.name)
                Map itemMap = [name:subscreensItem.name, title:ec.resource.expand(subscreensItem.menuTitle, ""),
                               path:screenPath, pathWithParams:pathWithParams, image:image]
                if ("icon".equals(imageType)) itemMap.imageType = "icon"
                if (active) itemMap.active = true
                if (screenUrlInstance.disableLink) itemMap.disableLink = true
                subscreensList.add(itemMap)
                // not needed: screenStatic:sui.targetScreen.isServerStatic(renderMode)
            }

            String curScreenPath = currentPath.toString()
            UrlInstance curUrlInstance = buildUrl(curScreenPath)
            String curPathWithParams = curScreenPath
            String curParmString = curUrlInstance.getParameterString()
            if (!curParmString.isEmpty()) curPathWithParams = curPathWithParams + '?' + curParmString
            menuDataList.add([name:pathItem, title:curScreen.getDefaultMenuName(), subscreens:subscreensList, path:curScreenPath,
                    pathWithParams:curPathWithParams, hasTabMenu:curScreen.hasTabMenu(), renderModes:curScreen.renderModes])
            // not needed: screenStatic:curScreen.isServerStatic(renderMode)
        }

        String lastPathItem = (String) fullPathList.get(fullPathSize - 1)
        fullUrlInstance.addParameters(ec.web.getRequestParameters())
        currentPath.append('/').append(StringUtilities.urlEncodeIfNeeded(lastPathItem))
        String lastPath = currentPath.toString()
        String paramString = fullUrlInstance.getParameterString()
        if (paramString.length() > 0) currentPath.append('?').append(paramString)

        String lastImage = fullUrlInfo.menuImage
        String lastImageType = fullUrlInfo.menuImageType
        if (lastImage != null && lastImage.length() > 0 && (lastImageType == null || lastImageType.length() == 0 || "url-screen".equals(lastImageType)))
            lastImage = buildUrl(lastImage).url
        String lastTitle = fullUrlInfo.targetScreen.getDefaultMenuName()
        if (lastTitle.contains('${')) lastTitle = ec.resourceFacade.expand(lastTitle, "")
        List<Map<String, Object>> screenDocList = fullUrlInfo.targetScreen.getScreenDocumentInfoList()

        if (extraPathList != null) {
            int extraPathListSize = extraPathList.size()
            for (int i = 0; i < extraPathListSize; i++) extraPathList.set(i, StringUtilities.urlEncodeIfNeeded((String) extraPathList.get(i)))
        }
        Map lastMap = [name:lastPathItem, title:lastTitle, path:lastPath, pathWithParams:currentPath.toString(), image:lastImage,
                extraPathList:extraPathList, screenDocList:screenDocList, renderModes:fullUrlInfo.targetScreen.renderModes]
        if ("icon".equals(lastImageType)) lastMap.imageType = "icon"
        menuDataList.add(lastMap)
        // not needed: screenStatic:fullUrlInfo.targetScreen.isServerStatic(renderMode)

        // for (Map info in menuDataList) logger.warn("menu data item: ${info}")
        return menuDataList
    }
}
