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
import groovy.transform.CompileStatic
import org.apache.commons.codec.net.URLCodec
import org.apache.commons.collections.map.ListOrderedMap

import org.moqui.BaseException
import org.moqui.context.*
import org.moqui.entity.EntityCondition.ComparisonOperator
import org.moqui.entity.EntityException
import org.moqui.entity.EntityFacade
import org.moqui.entity.EntityList
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.moqui.impl.StupidJavaUtilities
import org.moqui.impl.StupidUtilities
import org.moqui.impl.StupidWebUtilities
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.ResourceFacadeImpl
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.context.WebFacadeImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityValueBase
import org.moqui.impl.entity.EntityValueImpl
import org.moqui.impl.screen.ScreenDefinition.ResponseItem
import org.moqui.impl.screen.ScreenDefinition.SubscreensItem
import org.moqui.impl.screen.ScreenUrlInfo.UrlInstance
import org.moqui.impl.util.FtlNodeWrapper
import org.moqui.screen.ScreenRender
import org.moqui.util.MNode

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@CompileStatic
class ScreenRenderImpl implements ScreenRender {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenRenderImpl.class)
    protected final static boolean isTraceEnabled = logger.isTraceEnabled()
    protected final static URLCodec urlCodec = new URLCodec()

    protected final ScreenFacadeImpl sfi
    protected ExecutionContextImpl localEc
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

    protected boolean dontDoRender = false

    protected Map<String, FtlNodeWrapper> screenFormNodeCache = new HashMap<>()
    protected String curThemeId = (String) null
    protected Map<String, ArrayList<String>> curThemeValuesByType = new HashMap<>()

    ScreenRenderImpl(ScreenFacadeImpl sfi) {
        this.sfi = sfi
        localEc = sfi.ecfi.getEci()
    }

    Writer getWriter() {
        if (internalWriter != null) return internalWriter
        if (response != null) {
            internalWriter = response.getWriter()
            return internalWriter
        }
        throw new BaseException("Could not render screen, no writer available")
    }

    ExecutionContextImpl getEc() { return localEc }
    ScreenFacadeImpl getSfi() { return sfi }
    ScreenUrlInfo getScreenUrlInfo() { return screenUrlInfo }
    UrlInstance getScreenUrlInstance() { return screenUrlInstance }

    @Override
    ScreenRender rootScreen(String rsLocation) { rootScreenLocation = rsLocation; return this }

    ScreenRender rootScreenFromHost(String host) {
        MNode webappNode = sfi.getWebappNode(webappName)
        MNode wildcardHost = (MNode) null
        for (MNode rootScreenNode in webappNode.children("root-screen")) {
            String hostAttr = rootScreenNode.attribute("host")
            if (".*".equals(hostAttr)) {
                // remember wildcard host, default to it if no other matches (just in case put earlier in the list than others)
                wildcardHost = rootScreenNode
            } else if (host.matches(hostAttr)) {
                return rootScreen(rootScreenNode.attribute("location"))
            }
        }
        if (wildcardHost != null) return rootScreen(wildcardHost.attribute("location"))
        throw new BaseException("Could not find root screen for host: ${host}")
    }

    @Override
    ScreenRender screenPath(List<String> screenNameList) { originalScreenPathNameList.addAll(screenNameList); return this }

    @Override
    ScreenRender renderMode(String renderMode) { this.renderMode = renderMode; return this }

    String getRenderMode() { return renderMode }

    @Override
    ScreenRender encoding(String characterEncoding) { this.characterEncoding = characterEncoding;  return this }

    @Override
    ScreenRender macroTemplate(String mtl) { this.macroTemplateLocation = mtl; return this }

    @Override
    ScreenRender baseLinkUrl(String blu) { this.baseLinkUrl = blu; return this }

    @Override
    ScreenRender servletContextPath(String scp) { this.servletContextPath = scp; return this }

    @Override
    ScreenRender webappName(String wan) { this.webappName = wan; return this }

    @Override
    void render(HttpServletRequest request, HttpServletResponse response) {
        if (rendering) throw new IllegalStateException("This screen render has already been used")
        rendering = true
        this.request = request
        this.response = response
        // NOTE: don't get the writer at this point, we don't yet know if we're writing text or binary
        if (webappName == null || webappName.length() == 0)
            webappName(request.session.servletContext.getInitParameter("moqui-name"))
        if (webappName != null && webappName.length() > 0 && (rootScreenLocation == null || rootScreenLocation.length() == 0))
            rootScreenFromHost(request.getServerName())
        if (originalScreenPathNameList == null || originalScreenPathNameList.size() == 0) {
            String pathInfo = request.getPathInfo()
            if (pathInfo != null) screenPath(pathInfo.split("/") as List)
        }
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
            response.sendRedirect(redirectUrl)
            dontDoRender = true
            logger.info("Redirecting to ${redirectUrl} instead of rendering ${this.getScreenUrlInfo().getFullPathNameList()}")
        }
    }

    protected ResponseItem recursiveRunTransition(Iterator<ScreenDefinition> sdIterator, boolean runPreActions) {
        ScreenDefinition sd = sdIterator.next()
        // for these authz is not required, as long as something authorizes on the way to the transition, or
        // the transition itself, it's fine
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(sd.location, "AT_XML_SCREEN", "AUTHZA_VIEW")
        ec.getArtifactExecutionImpl().pushInternal(aei, false)

        boolean loggedInAnonymous = false
        ResponseItem ri = (ResponseItem) null

        try {
            MNode screenNode = sd.getScreenNode()
            String requireAuthentication = screenNode.attribute('require-authentication')
            if (requireAuthentication == "anonymous-all") {
                ec.artifactExecution.setAnonymousAuthorizedAll()
                loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
            } else if (requireAuthentication == "anonymous-view") {
                ec.artifactExecution.setAnonymousAuthorizedView()
                loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
            }

            if (sd.alwaysActions != null) sd.alwaysActions.run(ec)
            if (runPreActions && sd.preActions != null) sd.preActions.run(ec)

            if (sdIterator.hasNext()) {
                screenPathIndex++
                try {
                    ri = recursiveRunTransition(sdIterator, runPreActions)
                } finally {
                    screenPathIndex--
                }
            } else {
                // run the transition
                ri = screenUrlInstance.targetTransition.run(this)
            }
        } finally {
            ec.getArtifactExecution().pop(aei)
            if (loggedInAnonymous) ((UserFacadeImpl) ec.getUser()).logoutAnonymousOnly()
        }

        return ri
    }

    protected void internalRender() {
        rootScreenDef = sfi.getScreenDefinition(rootScreenLocation)
        if (rootScreenDef == null) throw new BaseException("Could not find root screen at location [${rootScreenLocation}]")

        if (logger.traceEnabled) logger.trace("Rendering screen [${rootScreenLocation}] with path list [${originalScreenPathNameList}]")
        // logger.info("Rendering screen [${rootScreenLocation}] with path list [${originalScreenPathNameList}]")

        WebFacade web = ec.getWeb()
        String lastStandalone = web != null ? web.requestParameters.lastStandalone : null
        screenUrlInfo = ScreenUrlInfo.getScreenUrlInfo(this, rootScreenDef, originalScreenPathNameList, null,
                "true".equals(lastStandalone))
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
        for (ScreenDefinition checkSd in screenUrlInfo.screenRenderDefList) {
            if (!checkWebappSettings(checkSd)) return
        }

        // check this here after the ScreenUrlInfo (with transition alias, etc) has already been handled
        String localRenderMode = web != null ? web.requestParameters.renderMode : null
        if (localRenderMode != null && localRenderMode.length() > 0) renderMode = localRenderMode
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
                MNode webappNode = sfi.getWebappNode(webappName)
                String queryString = request.getQueryString()
                Map<String, Object> pathInfoParameterMap = StupidWebUtilities.getPathInfoParameterMap(request.getPathInfo())
                if (!targetTransition.isReadOnly() && (
                        (!request.isSecure() && !"false".equals(webappNode.attribute('https-enabled'))) ||
                        (queryString != null && queryString.length() > 0) ||
                        (pathInfoParameterMap != null && pathInfoParameterMap.size() > 0))) {
                    throw new IllegalArgumentException(
                        """Cannot run screen transition with actions from non-secure request or with URL
                        parameters for security reasons (they are not encrypted and need to be for data
                        protection and source validation). Change the link this came from to be a
                        form with hidden input fields instead, or declare the transition as read-only.""")
                }
                // require a moquiSessionToken parameter for all but get
                if (request.getMethod().toLowerCase() != "get" &&
                        webappNode.attribute("require-session-token") != "false" &&
                        targetTransition.getRequireSessionToken() &&
                        request.getAttribute("moqui.session.token.created") != "true" &&
                        request.getAttribute("moqui.request.authenticated") != "true") {
                    String passedToken = (String) ec.web.getParameters().get("moquiSessionToken")
                    String curToken = ec.web.getSessionToken()
                    if (curToken != null && curToken.length() > 0) {
                        if (passedToken == null || passedToken.length() == 0) {
                            throw new IllegalArgumentException("Session token required (in moquiSessionToken) for URL ${screenUrlInstance.url}")
                        } else if (!curToken.equals(passedToken)) {
                            throw new IllegalArgumentException("Session token does not match (in moquiSessionToken) for URL ${screenUrlInstance.url}")
                        }
                    }
                }
            }

            long transitionStartTime = System.currentTimeMillis()
            long startTimeNanos = System.nanoTime()

            TransactionFacade transactionFacade = sfi.getEcfi().getTransactionFacade()
            boolean beginTransaction = targetTransition.getBeginTransaction()
            boolean beganTransaction = beginTransaction ? transactionFacade.begin(null) : false
            ResponseItem ri = null
            try {
                boolean runPreActions = targetTransition instanceof ScreenDefinition.ActionsTransitionItem
                ri = recursiveRunTransition(screenUrlInfo.screenPathDefList.iterator(), runPreActions)
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

                if (!"false".equals(screenUrlInfo.targetScreen.screenNode.attribute('track-artifact-hit'))) {
                    String riType = ri != null ? ri.type : null
                    sfi.ecfi.countArtifactHit("transition", riType != null ? riType : "",
                            targetTransition.parentScreen.getLocation() + "#" + targetTransition.name,
                            (web != null ? web.requestParameters : null), transitionStartTime,
                            (System.nanoTime() - startTimeNanos)/1E6, null)
                }
            }

            if (ri == null) throw new IllegalArgumentException("No response found for transition [${screenUrlInstance.targetTransition.name}] on screen ${screenUrlInfo.targetScreen.location}")

            WebFacadeImpl wfi = (WebFacadeImpl) null
            if (web != null && web instanceof WebFacadeImpl) wfi = (WebFacadeImpl) web

            if (ri.saveCurrentScreen && wfi != null) {
                StringBuilder screenPath = new StringBuilder()
                for (String pn in screenUrlInfo.fullPathNameList) screenPath.append("/").append(pn)
                ((WebFacadeImpl) web).saveScreenLastInfo(screenPath.toString(), null)
            }

            if ("none".equals(ri.type)) {
                logger.info("Transition ${getScreenUrlInfo().getFullPathNameList().join("/")} in ${System.currentTimeMillis() - transitionStartTime}ms, type none response")
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
                    } else {
                        // try screen history when no last was saved
                        List<Map> historyList = wfi.getScreenHistory()
                        Map historyMap = historyList != null && historyList.size() > 0 ? historyList.first() : (Map) null
                        if (historyMap != null) {
                            url = isScreenLast ? historyMap.url : historyMap.urlNoParams
                            urlType = "plain"
                        } else {
                            // if no saved URL, just go to root/default; avoid getting stuck on Login screen, etc
                            url = savedUrl ?: "/"
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
                            ps.append(pme.key).append("=").append(urlCodec.encode(pme.value))
                        }
                    }
                    String fullUrl = url
                    if (ps.length() > 0) {
                        if (url.contains("?")) fullUrl += "&" else fullUrl += "?"
                        fullUrl += ps.toString()
                    }
                    // NOTE: even if transition extension is json still send redirect when we just have a plain url
                    logger.info("Transition ${getScreenUrlInfo().getFullPathNameList().join("/")} in ${System.currentTimeMillis() - transitionStartTime}ms, redirecting to plain URL: ${fullUrl}")
                    response.sendRedirect(fullUrl)
                } else {
                    // default is screen-path
                    UrlInstance fullUrl = buildUrl(rootScreenDef, screenUrlInfo.preTransitionPathNameList, url)
                    fullUrl.addParameters(ri.expandParameters(screenUrlInfo.getExtraPathNameList(), ec))
                    // if this was a screen-last and the screen has declared parameters include them in the URL
                    Map savedParameters = wfi?.getSavedParameters()
                    UrlInstance.copySpecialParameters(savedParameters, fullUrl.getOtherParameterMap())
                    // screen parameters
                    Map<String, ScreenDefinition.ParameterItem> parameterItemMap = fullUrl.sui.getTargetScreen()?.getParameterMap()
                    if (isScreenLast && savedParameters != null && savedParameters.size() > 0 &&
                            parameterItemMap != null && parameterItemMap.size() > 0) {
                        for (String parmName in parameterItemMap.keySet()) {
                            if (savedParameters.get(parmName))
                                fullUrl.addParameter(parmName, savedParameters.get(parmName))
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

                    if ("json".equals(screenUrlInfo.targetTransitionExtension)) {
                        Map<String, Object> responseMap = new HashMap<>()
                        // add saveMessagesToSession, saveRequestParametersToSession/saveErrorParametersToSession data
                        // add all plain object data from session?
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
                        if (web.requestParameters != null) parms.putAll(web.requestParameters)
                        if (web.requestAttributes != null) parms.putAll(web.requestAttributes)
                        responseMap.put("currentParameters", ScreenDefinition.unwrapMap(parms))

                        // add screen path, parameters from fullUrl
                        responseMap.put("screenPathList", fullUrl.sui.fullPathNameList)
                        responseMap.put("screenParameters", fullUrl.getParameterMap())

                        web.sendJsonResponse(responseMap)
                    } else {
                        String fullUrlString = fullUrl.getMinimalPathUrlWithParams()
                        logger.info("Transition ${getScreenUrlInfo().getFullPathNameList().join("/")} in ${System.currentTimeMillis() - transitionStartTime}ms, redirecting to screen path URL: ${fullUrlString}")
                        response.sendRedirect(fullUrlString)
                    }
                }
            } else {
                ArrayList<String> pathElements = new ArrayList<>(Arrays.asList(url.split("/")))
                if (url.startsWith("/")) {
                    this.originalScreenPathNameList = pathElements
                } else {
                    this.originalScreenPathNameList = screenUrlInfo.preTransitionPathNameList
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

            boolean isBinary = tr == null && sfi.ecfi.resourceFacade.isBinaryContentType(fileContentType)
            // if (isTraceEnabled) logger.trace("Content type for screen sub-content filename [${fileName}] is [${fileContentType}], default [${this.outputContentType}], is binary? ${isBinary}")

            if (isBinary) {
                if (response != null) {
                    this.outputContentType = fileContentType
                    response.setContentType(this.outputContentType)
                    // static binary, tell the browser to cache it
                    // NOTE: make this configurable?
                    response.addHeader("Cache-Control", "max-age=3600, must-revalidate, public")

                    InputStream is
                    try {
                        is = fileResourceRef.openStream()
                        OutputStream os = response.outputStream
                        int totalLen = StupidUtilities.copyStream(is, os)

                        if (screenUrlInfo.targetScreen.screenNode.attribute('track-artifact-hit') != "false") {
                            sfi.ecfi.countArtifactHit("screen-content", fileContentType, fileResourceRef.location,
                                    (web != null ? web.requestParameters : null), resourceStartTime,
                                    (System.nanoTime() - startTimeNanos)/1E6, (long) totalLen)
                        }
                        if (isTraceEnabled) logger.trace("Sent binary response of length ${totalLen} from file ${fileResourceRef.location} for request to ${screenUrlInstance.url}")
                    } finally {
                        if (is != null) is.close()
                    }
                } else {
                    throw new IllegalArgumentException("Tried to get binary content at [${screenUrlInfo.fileResourcePathList}] under screen [${screenUrlInfo.targetScreen.location}], but there is no HTTP response available")
                }
            } else if (!"true".equals(screenUrlInfo.targetScreen.screenNode.attribute('include-child-content'))) {
                // not a binary object (hopefully), read it and write it to the writer
                if (fileContentType != null && fileContentType.length() > 0) this.outputContentType = fileContentType
                if (response != null) {
                    response.setContentType(this.outputContentType)
                    response.setCharacterEncoding(this.characterEncoding)
                }

                if (tr != null) {
                    // if requires a render, don't cache and make it private
                    if (response != null) response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate, private")
                    tr.render(fileResourceRef.location, writer)
                } else {
                    // static text, tell the browser to cache it
                    // TODO: make this configurable?
                    if (response != null) response.addHeader("Cache-Control", "max-age=3600, must-revalidate, public")
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
                        if (!"false".equals(screenUrlInfo.targetScreen.screenNode.attribute('track-artifact-hit'))) {
                            sfi.ecfi.countArtifactHit("screen-content", fileContentType, fileResourceRef.location,
                                    (web != null ? web.requestParameters : null), resourceStartTime,
                                    (System.nanoTime() - startTimeNanos)/1E6, (long) text.length())
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
        }
    }

    protected void recursiveRunActions(Iterator<ScreenDefinition> screenDefIterator, boolean runAlwaysActions, boolean runPreActions) {
        ScreenDefinition sd = screenDefIterator.next()
        // check authz first, including anonymous-* handling so that permissions and auth are in place
        // NOTE: don't require authz if the screen doesn't require auth
        MNode screenNode = sd.getScreenNode()
        String requireAuthentication = screenNode.attribute('require-authentication')
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(sd.location, "AT_XML_SCREEN", "AUTHZA_VIEW")
        ec.artifactExecutionImpl.pushInternal(aei, !screenDefIterator.hasNext() ? (!requireAuthentication || requireAuthentication == "true") : false)

        boolean loggedInAnonymous = false
        try {
            if (sd.getTenantsAllowed() && !sd.getTenantsAllowed().contains(ec.getTenantId()))
                throw new ArtifactAuthorizationException("The screen ${sd.getScreenName()} is not available to tenant ${ec.getTenantId()}")

            if (requireAuthentication == "anonymous-all") {
                ec.artifactExecution.setAnonymousAuthorizedAll()
                loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
            } else if (requireAuthentication == "anonymous-view") {
                ec.artifactExecution.setAnonymousAuthorizedView()
                loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
            }

            if (runAlwaysActions && sd.alwaysActions != null) sd.alwaysActions.run(ec)
            if (runPreActions && sd.preActions != null) sd.preActions.run(ec)

            if (screenDefIterator.hasNext()) recursiveRunActions(screenDefIterator, runAlwaysActions, runPreActions)
        } finally {
            // all done so pop the artifact info; don't bother making sure this is done on errors/etc like in a finally clause because if there is an error this will help us know how we got there
            ec.artifactExecution.pop(aei)
            if (loggedInAnonymous) ((UserFacadeImpl) ec.getUser()).logoutAnonymousOnly()
        }
    }

    void doActualRender() {
        long screenStartTime = System.currentTimeMillis()
        long startTimeNanos = System.nanoTime()
        boolean beganTransaction = screenUrlInfo.beginTransaction ? sfi.ecfi.transactionFacade.begin(null) : false
        try {
            // make sure this (sri) is in the context before running actions
            ec.context.put("sri", this)

            // run always-actions for all screens in path
            boolean hasAlwaysActions = false
            for (ScreenDefinition sd in screenUrlInfo.screenPathDefList) if (sd.alwaysActions != null) {
                hasAlwaysActions = true; break
            }
            if (hasAlwaysActions) {
                Iterator<ScreenDefinition> screenDefIterator = screenUrlInfo.screenPathDefList.iterator()
                recursiveRunActions(screenDefIterator, true, false)
            }

            if (response != null) {
                response.setContentType(this.outputContentType)
                response.setCharacterEncoding(this.characterEncoding)
                // if requires a render, don't cache and make it private
                response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate, private")
            }

            // for inherited permissions to work, walk the screen list before the screenRenderDefList and artifact push
            // them, then pop after
            ArrayList<ArtifactExecutionInfo> aeiList = null
            if (screenUrlInfo.renderPathDifference > 0) {
                aeiList = new ArrayList<ArtifactExecutionInfo>(screenUrlInfo.renderPathDifference)
                for (int i = 0; i < screenUrlInfo.renderPathDifference; i++) {
                    ScreenDefinition permSd = screenUrlInfo.screenPathDefList.get(i)

                    if (permSd.getTenantsAllowed() && !permSd.getTenantsAllowed().contains(ec.getTenantId()))
                        throw new ArtifactAuthorizationException("The screen ${permSd.getScreenName()} is not available to tenant ${ec.getTenantId()}")
                    // check the subscreens item for this screen (valid in context)
                    if (i > 0) {
                        String curPathName = screenUrlInfo.fullPathNameList.get(i - 1) // one lower in path as it doesn't have root screen
                        ScreenDefinition parentScreen = screenUrlInfo.screenPathDefList.get(i - 1)
                        SubscreensItem ssi = parentScreen.getSubscreensItem(curPathName)
                        if (ssi == null) {
                            logger.warn("Couldn't find SubscreenItem: parent ${parentScreen.getScreenName()}, curPathName ${curPathName}, current ${permSd.getScreenName()}\npath list: ${screenUrlInfo.fullPathNameList}\nscreen list: ${screenUrlInfo.screenPathDefList}")
                        } else {
                            if (!ssi.isValidInCurrentContext())
                                throw new ArtifactAuthorizationException("The screen ${permSd.getScreenName()} is not available")
                        }
                    }

                    ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(permSd.location, "AT_XML_SCREEN", "AUTHZA_VIEW")
                    ec.artifactExecutionImpl.pushInternal(aei, false)
                    aeiList.add(aei)
                }
            }

            // run pre-actions for just the screens that will be rendered
            boolean hasPreActions = false
            for (ScreenDefinition sd in screenUrlInfo.screenRenderDefList) if (sd.preActions != null) {
                hasPreActions = true; break
            }
            if (hasPreActions) {
                Iterator<ScreenDefinition> screenDefIterator = screenUrlInfo.screenRenderDefList.iterator()
                recursiveRunActions(screenDefIterator, false, true)
            }

            // if dontDoRender then quit now; this should be set during always-actions or pre-actions
            if (dontDoRender) {
                // pop all screens, then good to go
                if (aeiList) for (int i = (aeiList.size() - 1); i >= 0; i--) ec.artifactExecution.pop(aeiList.get(i))
                return
            }

            // we've run always and pre actions, it's now or never for required parameters so check them

            if (!sfi.isRenderModeSkipActions(renderMode)) for (ScreenDefinition sd in screenUrlInfo.screenRenderDefList) {
                for (ScreenDefinition.ParameterItem pi in sd.getParameterMap().values()) {
                    if (pi.required && ec.context.getByString(pi.name) == null) {
                        ec.message.addError("Required parameter missing (${pi.name})")
                        logger.warn("Tried to render screen [${sd.getLocation()}] without required parameter [${pi.name}], error message added and adding to stop list to not render")
                        stopRenderScreenLocations.add(sd.getLocation())
                    }
                }
            }

            // start rendering at the root section of the root screen
            ScreenDefinition renderStartDef = screenUrlInfo.screenRenderDefList.get(0)
            // if screenRenderDefList.size == 1 then it is the target screen, otherwise it's not
            renderStartDef.render(this, screenUrlInfo.screenRenderDefList.size() == 1)

            // if these aren't already cleared it out means they haven't been included in the output, so add them here
            if (afterScreenWriter) internalWriter.write(afterScreenWriter.toString())
            if (scriptWriter) {
                internalWriter.write("\n<script>\n")
                internalWriter.write(scriptWriter.toString())
                internalWriter.write("\n</script>\n")
            }

            if (aeiList) for (int i = (aeiList.size() - 1); i >= 0; i--) ec.artifactExecution.pop(aeiList.get(i))
        } catch (ArtifactAuthorizationException e) {
            throw e
        } catch (ArtifactTarpitException e) {
            throw e
        } catch (Throwable t) {
            String errMsg = "Error rendering screen [${getActiveScreenDef().location}]"
            sfi.ecfi.transactionFacade.rollback(beganTransaction, errMsg, t)
            throw new RuntimeException(errMsg, t)
        } finally {
            WebFacade webFacade = ec.getWeb()
            // if we began a tx commit it
            if (beganTransaction && sfi.ecfi.transactionFacade.isTransactionInPlace()) sfi.ecfi.transactionFacade.commit()
            // track the screen artifact hit
            if (screenUrlInfo.targetScreen.screenNode.attribute('track-artifact-hit') != "false") {
                sfi.ecfi.countArtifactHit("screen", this.outputContentType, screenUrlInfo.screenRenderDefList.last().getLocation(),
                        (webFacade != null ? webFacade.requestParameters : null), screenStartTime,
                        (System.nanoTime() - startTimeNanos)/1E6, null)
            }
            // save the screen history
            if (webFacade != null && webFacade instanceof WebFacadeImpl) {
                ((WebFacadeImpl) webFacade).saveScreenHistory(screenUrlInstance)
            }
        }
    }

    boolean checkWebappSettings(ScreenDefinition currentSd) {
        if (request == null) return true

        MNode webSettingsNode = currentSd.webSettingsNode
        if (webSettingsNode != null && "false".equals(webSettingsNode.attribute('allow-web-request')))
            throw new IllegalArgumentException("The screen [${currentSd.location}] cannot be used in a web request (allow-web-request=false).")

        String mimeType = webSettingsNode != null ? webSettingsNode.attribute('mime-type') : null
        if (mimeType != null && mimeType.length() > 0) this.outputContentType = mimeType
        String characterEncoding = webSettingsNode != null ? webSettingsNode.attribute('character-encoding') : null
        if (characterEncoding != null && characterEncoding.length() > 0) this.characterEncoding = characterEncoding

        // if screen requires auth and there is not active user redirect to login screen, save this request
        // if (isTraceEnabled) logger.trace("Checking screen [${currentSd.location}] for require-authentication, current user is [${ec.user.userId}]")

        WebFacadeImpl wfi = ec.getWebImpl()
        String requireAuthentication = currentSd.screenNode?.attribute('require-authentication')
        String userId = ec.getUser().getUserId()
        if ((requireAuthentication == null || requireAuthentication.length() == 0 || requireAuthentication == "true")
                && (userId == null || userId.length() == 0) && !ec.getUserFacade().getLoggedInAnonymous()) {
            logger.info("Screen at location [${currentSd.location}], which is part of [${screenUrlInfo.fullPathNameList}] under screen [${screenUrlInfo.fromSd.location}] requires authentication but no user is currently logged in.")
            // save the request as a save-last to use after login
            if (wfi != null && screenUrlInfo.fileResourceRef == null) {
                StringBuilder screenPath = new StringBuilder()
                for (String pn in originalScreenPathNameList) screenPath.append("/").append(pn)
                wfi.saveScreenLastInfo(screenPath.toString(), null)
                // save messages in session before redirecting so they can be displayed on the next screen
                wfi.saveMessagesToSession()
            }

            // find the last login path from screens in path (whether rendered or not)
            String loginPath = "/Login"
            for (ScreenDefinition sd in screenUrlInfo.screenPathDefList) {
                String loginPathAttr = (String) sd.screenNode.attribute('login-path')
                if (loginPathAttr) loginPath = loginPathAttr
            }

            if (screenUrlInfo.isLastStandalone() || screenUrlInstance.getTargetTransition() != null) {
                // respond with 401 and the login screen instead of a redirect; JS client libraries handle this much better
                ArrayList<String> pathElements = new ArrayList(Arrays.asList(loginPath.split("/")))
                if (loginPath.startsWith("/")) {
                    this.originalScreenPathNameList = pathElements
                } else {
                    this.originalScreenPathNameList = screenUrlInfo.preTransitionPathNameList
                    this.originalScreenPathNameList.addAll(pathElements)
                }
                // reset screenUrlInfo and call this again to start over with the new target
                screenUrlInfo = null
                internalRender()
                if (response != null) response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)
                return false
            } else {
                // now prepare and send the redirect
                ScreenUrlInfo suInfo = ScreenUrlInfo.getScreenUrlInfo(this, rootScreenDef, new ArrayList<String>(), loginPath, false)
                UrlInstance urlInstance = suInfo.getInstance(this, false)
                response.sendRedirect(urlInstance.url)
                return false
            }
        }

        // if request not secure and screens requires secure redirect to https
        MNode webappNode = sfi.getWebappNode(webappName)
        if (!request.isSecure() && (webSettingsNode == null || webSettingsNode.attribute('require-encryption') != "false") &&
                webappNode != null && webappNode.attribute('https-enabled') != "false") {
            logger.info("Screen at location [${currentSd.location}], which is part of [${screenUrlInfo.fullPathNameList}] under screen [${screenUrlInfo.fromSd.location}] requires an encrypted/secure connection but the request is not secure, sending redirect to secure.")
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
        if (boundaryComments != null) return boundaryComments
        boundaryComments = sfi.ecfi.confXmlRoot.first("screen-facade").attribute("boundary-comments") == "true"
        return boundaryComments
    }

    ScreenDefinition getRootScreenDef() { return rootScreenDef }
    ScreenDefinition getActiveScreenDef() {
        if (overrideActiveScreenDef != null) return overrideActiveScreenDef
        return (ScreenDefinition) screenUrlInfo.screenRenderDefList.get(screenPathIndex)
    }
    String getActiveScreenPathName() {
        int fullPathIndex = screenUrlInfo.renderPathDifference + screenPathIndex - 1
        return screenUrlInfo.fullPathNameList.get(fullPathIndex)
    }

    ArrayList<String> getActiveScreenPath() {
        // handle case where root screen is first/zero in list versus a standalone screen
        int fullPathIndex = screenUrlInfo.renderPathDifference + screenPathIndex
        if (fullPathIndex == 0) return new ArrayList<String>()
        ArrayList<String> activePath = new ArrayList<>(screenUrlInfo.fullPathNameList[0..fullPathIndex-1])
        // logger.info("===== activePath=${activePath}, rpd=${screenUrlInfo.renderPathDifference}, spi=${screenPathIndex}, fpi=${fullPathIndex}\nroot: ${screenUrlInfo.rootSd.location}\ntarget: ${screenUrlInfo.targetScreen.location}\nfrom: ${screenUrlInfo.fromSd.location}\nfrom path: ${screenUrlInfo.fromPathList}")
        return activePath
    }

    String renderSubscreen() {
        // first see if there is another screen def in the list
        if ((screenPathIndex+1) >= screenUrlInfo.screenRenderDefList.size()) {
            if (screenUrlInfo.fileResourceRef) {
                // NOTE: don't set this.outputContentType, when including in a screen the screen determines the type
                sfi.ecfi.resourceFacade.renderTemplateInCurrentContext(screenUrlInfo.fileResourceRef.location, writer)
                return ""
            } else {
                return "Tried to render subscreen in screen [${getActiveScreenDef()?.location}] but there is no subscreens.@default-item, and no more valid subscreen names in the screen path [${screenUrlInfo.fullPathNameList}]"
            }
        }

        ScreenDefinition screenDef = screenUrlInfo.screenRenderDefList.get(screenPathIndex + 1)
        if (screenDef.getTenantsAllowed() && !screenDef.getTenantsAllowed().contains(ec.getTenantId()))
            throw new ArtifactAuthorizationException("The screen ${screenDef.getScreenName()} is not available to tenant ${ec.getTenantId()}")
        // check the subscreens item for this screen (valid in context)
        int i = screenPathIndex + screenUrlInfo.renderPathDifference
        if (i > 0) {
            String curPathName = screenUrlInfo.fullPathNameList.get(i) // current one lower in path as it doesn't have root screen
            ScreenDefinition parentScreen = screenUrlInfo.screenPathDefList.get(i)
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
                screenDef.render(this, (screenUrlInfo.screenRenderDefList.size() - 1) == screenPathIndex)
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
        if (macroTemplateLocation) {
            return sfi.getTemplateByLocation(macroTemplateLocation)
        } else {
            String overrideTemplateLocation = null
            // go through the screenPathDefList instead screenRenderDefList so that parent screen can override template
            //     even if it isn't rendered to decorate subscreen
            for (ScreenDefinition sd in screenUrlInfo.screenPathDefList) {
                String curLocation = sd.getMacroTemplateLocation(renderMode)
                if (curLocation != null && curLocation.length() > 0) overrideTemplateLocation = curLocation
            }

            if (overrideTemplateLocation) {
                return sfi.getTemplateByLocation(overrideTemplateLocation)
            } else {
                return sfi.getTemplateByMode(renderMode)
            }
        }
    }

    String renderSection(String sectionName) {
        ScreenDefinition sd = getActiveScreenDef()
        try {
            ScreenSection section = sd.getSection(sectionName)
            if (!section) throw new IllegalArgumentException("No section with name [${sectionName}] in screen [${sd.location}]")
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

    String startFormListRow(String formName, Object listEntry, int index, boolean hasNext) {
        ScreenDefinition sd = getActiveScreenDef()
        ScreenForm form = sd.getForm(formName)
        if (form == null) throw new IllegalArgumentException("No form with name [${formName}] in screen [${sd.location}]")
        FtlNodeWrapper formNode = getFtlFormNode(formName)
        ec.context.push()
        form.runFormListRowActions(this, listEntry, index, hasNext, formNode)
        // NOTE: this returns an empty String so that it can be used in an FTL interpolation, but nothing is written
        return ""
    }
    String endFormListRow() {
        ec.context.pop()
        // NOTE: this returns an empty String so that it can be used in an FTL interpolation, but nothing is written
        return ""
    }
    static String safeCloseList(Object listObject) {
        if (listObject instanceof EntityListIterator) ((EntityListIterator) listObject).close()
        // NOTE: this returns an empty String so that it can be used in an FTL interpolation, but nothing is written
        return ""
    }
    FtlNodeWrapper getFtlFormNode(String formName) {
        ScreenDefinition sd = getActiveScreenDef()
        String nodeCacheKey = sd.getLocation() + "#" + formName
        // NOTE: this is cached in the context of the renderer for multiple accesses; because of form overrides may not
        // be valid outside the scope of a single screen render
        FtlNodeWrapper formNode = screenFormNodeCache.get(nodeCacheKey)
        if (formNode == null) {
            ScreenForm form = sd.getForm(formName)
            if (!form) throw new IllegalArgumentException("No form with name [${formName}] in screen [${sd.location}]")
            formNode = form.getFtlFormNode()
            screenFormNodeCache.put(nodeCacheKey, formNode)
        }
        return formNode
    }
    List<FtlNodeWrapper> getFtlFormFieldLayoutNonReferencedFieldList(String formName) {
        ScreenDefinition sd = getActiveScreenDef()
        return sd.getForm(formName).getFieldLayoutNonReferencedFieldList()
    }
    List<FtlNodeWrapper> getFtlFormListColumnNonReferencedHiddenFieldList(String formName) {
        ScreenDefinition sd = getActiveScreenDef()
        return sd.getForm(formName).getColumnNonReferencedHiddenFieldList()
    }


    boolean isFormUpload(String formName) {
        return getActiveScreenDef().getForm(formName).isUpload(getFtlFormNode(formName))
    }
    boolean isFormHeaderForm(String formName) {
        return getActiveScreenDef().getForm(formName).isFormHeaderForm(getFtlFormNode(formName))
    }

    String getFormFieldValidationClasses(String formName, String fieldName) {
        ScreenForm form = getActiveScreenDef().getForm(formName)
        MNode validateNode = form.getFieldValidateNode(fieldName, getFtlFormNode(formName))
        if (validateNode == null) return ""

        Set<String> vcs = new HashSet()
        if (validateNode.name == "parameter") {
            MNode parameterNode = validateNode
            if (parameterNode.attribute('required') == "true") vcs.add("required")
            if (parameterNode.hasChild("number-integer")) vcs.add("number")
            if (parameterNode.hasChild("number-decimal")) vcs.add("number")
            if (parameterNode.hasChild("text-email")) vcs.add("email")
            if (parameterNode.hasChild("text-url")) vcs.add("url")
            if (parameterNode.hasChild("text-digits")) vcs.add("digits")
            if (parameterNode.hasChild("credit-card")) vcs.add("creditcard")

            String type = parameterNode.attribute('type')
            if (type && (type.endsWith("BigDecimal") || type.endsWith("BigInteger") || type.endsWith("Long") ||
                    type.endsWith("Integer") || type.endsWith("Double") || type.endsWith("Float") ||
                    type.endsWith("Number"))) vcs.add("number")
        } else if (validateNode.name == "field") {
            MNode fieldNode = validateNode
            String type = fieldNode.attribute('type')
            if (type && (type.startsWith("number-") || type.startsWith("currency-"))) vcs.add("number")
            // bad idea, for create forms with optional PK messes it up: if (fieldNode."@is-pk" == "true") vcs.add("required")
        }

        StringBuilder sb = new StringBuilder()
        for (String vc in vcs) { if (sb) sb.append(" "); sb.append(vc); }
        return sb.toString()
    }

    Map getFormFieldValidationRegexpInfo(String formName, String fieldName) {
        ScreenForm form = getActiveScreenDef().getForm(formName)
        MNode validateNode = form.getFieldValidateNode(fieldName, getFtlFormNode(formName))
        if (validateNode?.hasChild("matches")) {
            MNode matchesNode = validateNode.first("matches")
            return [regexp:matchesNode.attribute('regexp'), message:matchesNode.attribute('message')]
        }
        return null
    }

    String renderIncludeScreen(String location, String shareScopeStr) {
        boolean shareScope = shareScopeStr == "true"

        ContextStack cs = (ContextStack) ec.context
        ScreenDefinition oldOverrideActiveScreenDef = overrideActiveScreenDef
        try {
            if (!shareScope) cs.push()
            writer.flush()

            ScreenDefinition screenDef = sfi.getScreenDefinition(location)
            if (!screenDef) throw new BaseException("Could not find screen at location [${location}]")
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
            sfi.ecfi.resourceFacade.renderTemplateInCurrentContext(location, writer)
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

        ScreenUrlInfo sui = ScreenUrlInfo.getScreenUrlInfo(this, null, null, subscreenPath, null)
        subscreenUrlInfos.put(key, sui)
        return sui
    }

    UrlInstance buildUrl(String subscreenPath) {
        return buildUrlInfo(subscreenPath).getInstance(this, null)
    }

    UrlInstance buildUrl(ScreenDefinition fromSd, ArrayList<String> fromPathList, String subscreenPathOrig) {
        String subscreenPath = subscreenPathOrig?.contains("\${") ? ec.resource.expand(subscreenPathOrig, "") : subscreenPathOrig
        ScreenUrlInfo ui = ScreenUrlInfo.getScreenUrlInfo(this, fromSd, fromPathList, subscreenPath, null)
        return ui.getInstance(this, null)
    }

    UrlInstance makeUrlByType(String origUrl, String urlType, FtlNodeWrapper parameterParentNodeWrapper,
                                String expandTransitionUrlString) {
        return makeUrlByTypeGroovyNode(origUrl, urlType, parameterParentNodeWrapper?.getMNode(), expandTransitionUrlString)
    }
    UrlInstance makeUrlByTypeGroovyNode(String origUrl, String urlType, MNode parameterParentNode,
                                String expandTransitionUrlString) {
        Boolean expandTransitionUrl = expandTransitionUrlString != null ? expandTransitionUrlString == "true" : null
        /* TODO handle urlType=content
            A content location (without the content://). URL will be one that can access that content.
         */
        ScreenUrlInfo suInfo
        String urlTypeExpanded = ec.resource.expand(urlType, "")
        switch (urlTypeExpanded) {
            // for transition we want a URL relative to the current screen, so just pass that to buildUrl
            case "transition": suInfo = buildUrlInfo(origUrl); break;
            case "screen": suInfo = buildUrlInfo(origUrl); break;
            case "content": throw new IllegalArgumentException("The url-type of content is not yet supported"); break;
            case "plain":
            default:
                String url = ec.resource.expand(origUrl, "")
                suInfo = ScreenUrlInfo.getScreenUrlInfo(this, url)
                break
        }

        UrlInstance urli = suInfo.getInstance(this, expandTransitionUrl)

        if (parameterParentNode != null) {
            String parameterMapStr = (String) parameterParentNode.attribute('parameter-map')
            if (parameterMapStr) {
                def ctxParameterMap = ec.resource.expression(parameterMapStr, "")
                if (ctxParameterMap) urli.addParameters((Map) ctxParameterMap)
            }
            for (MNode parameterNode in parameterParentNode.children("parameter")) {
                String name = parameterNode.attribute('name')
                urli.addParameter(name, getContextValue(
                        parameterNode.attribute('from') ?: name, parameterNode.attribute('value')))
            }
        }

        return urli
    }

    Object getContextValue(String from, String value) {
        if (value) {
            return ec.resource.expand(value, getActiveScreenDef().location)
        } else if (from) {
            return ec.resource.expression(from, getActiveScreenDef().location)
        } else {
            return ""
        }
    }
    String setInContext(FtlNodeWrapper setNodeWrapper) {
        MNode setNode = setNodeWrapper.getMNode()
        ((ResourceFacadeImpl) ec.resource).setInContext(setNode.attribute('field'),
                setNode.attribute('from'), setNode.attribute('value'),
                setNode.attribute('default-value'), setNode.attribute('type'),
                setNode.attribute('set-if-empty'))
        return ""
    }
    String pushContext() { ec.getContext().push(); return "" }
    String popContext() { ec.getContext().pop(); return "" }

    /** Call this at the beginning of a form-single. Always call popContext() at the end of the form! */
    String pushSingleFormMapContext(FtlNodeWrapper formNodeWrapper) {
        ContextStack cs = ec.getContext()
        MNode formNode = formNodeWrapper.getMNode()
        String mapName = formNode.attribute('map') ?: "fieldValues"
        Map valueMap = (Map) cs.getByString(mapName)

        cs.push()
        if (valueMap) cs.putAll(valueMap)
        cs.put("_formMap", valueMap)
        cs.put(mapName, valueMap)

        return ""
    }
    String getFieldValueString(FtlNodeWrapper fieldNodeWrapper, String defaultValue, String format) {
        Object obj = getFieldValue(fieldNodeWrapper, defaultValue)
        String strValue = ec.l10n.format(obj, format)
        return strValue
    }
    String getFieldValuePlainString(FtlNodeWrapper fieldNodeWrapper, String defaultValue) {
        // NOTE: defaultValue is handled below so that for a plain string it is not run through expand
        Object obj = getFieldValue(fieldNodeWrapper, "")
        if (StupidJavaUtilities.isEmpty(obj) && defaultValue != null && defaultValue.length() > 0)
            return ec.getResource().expand(defaultValue, "")
        return StupidJavaUtilities.toPlainString(obj)
        // NOTE: this approach causes problems with currency fields, but kills the string expand for default-value... a better approach?
        //return obj ? obj.toString() : (defaultValue ? ec.getResource().expand(defaultValue, null) : "")
    }

    Object getFieldValue(FtlNodeWrapper fieldNodeWrapper, String defaultValue) {
        MNode fieldNode = fieldNodeWrapper.getMNode()
        String entryName = fieldNode.attribute('entry-name')
        if (entryName != null && entryName.length() > 0) return ec.getResource().expression(entryName, null)
        String fieldName = fieldNode.attribute('name')
        String mapAttr = fieldNode.parent.attribute('map')
        String mapName = mapAttr != null && mapAttr.length() > 0 ? mapAttr : "fieldValues"
        Object value = null
        // if this is an error situation try parameters first, otherwise try parameters last
        Map<String, Object> errorParameters = ec.getWeb()?.getErrorParameters()
        if (errorParameters != null && (errorParameters.moquiFormName == fieldNode.parent.attribute('name')))
            value = errorParameters.get(fieldName)
        Map valueMap = (Map) ec.resource.expression(mapName, "")
        if (StupidJavaUtilities.isEmpty(value)) {
            MNode formNode = fieldNode.parent
            boolean isFormSingle = "form-single".equals(formNode.name)
            boolean isFormList = !isFormSingle && "form-list".equals(formNode.name)
            if (valueMap != null && isFormSingle) {
                try {
                    if (valueMap instanceof EntityValueBase) {
                        // if it is an EntityValueImpl, only get if the fieldName is a value
                        EntityValueBase evb = (EntityValueImpl) valueMap
                        if (evb.getEntityDefinition().isField(fieldName)) value = evb.get(fieldName)
                    } else {
                        value = valueMap.get(fieldName)
                    }
                } catch (EntityException e) {
                    // do nothing, not necessarily an entity field
                    if (isTraceEnabled) logger.trace("Ignoring entity exception for non-field: ${e.toString()}")
                }
            } else if (isFormList) {
                String listEntryAttr = formNode.attribute('list-entry')
                if (listEntryAttr != null && listEntryAttr.length() > 0) {
                    // use some Groovy goodness to get an object property, only do if this is NOT a Map (that is handled by
                    //     putting all Map entries in the context for each row)
                    Object entryObj = ec.getContext().getByString(listEntryAttr)
                    if (entryObj != null && !(entryObj instanceof Map)) {
                        try {
                            value = entryObj.getAt(fieldName)
                        } catch (MissingPropertyException e) {
                            // ignore exception, we know this may not be a real property of the object
                            if (isTraceEnabled) logger.trace("Field ${fieldName} is not a property of list-entry ${listEntryAttr} in form ${formNode.attribute('name')}: ${e.toString()}")
                        }
                    }
                }
            }
        }
        if (StupidJavaUtilities.isEmpty(value)) value = ec.getContext().getByString(fieldName)
        // this isn't needed since the parameters are copied to the context: if (!isError && isWebAndSameForm && !value) value = ec.getWeb().parameters.get(fieldName)

        if (!StupidJavaUtilities.isEmpty(value)) return value

        String defaultStr = ec.getResource().expand(defaultValue, null)
        if (defaultStr != null && defaultStr.length() > 0) return defaultStr
        return value
    }
    String getFieldValueClass(FtlNodeWrapper fieldNodeWrapper) {
        Object fieldValue = null

        MNode fieldNode = fieldNodeWrapper.getMNode()
        String entryName = fieldNode.attribute('entry-name')
        if (entryName != null && entryName.length() > 0) fieldValue = ec.getResource().expression(entryName, null)
        if (fieldValue == null) {
            String fieldName = fieldNode.attribute('name')
            String mapName = fieldNode.parent.attribute('map') ?: "fieldValues"
            if (ec.getContext().getByString(mapName) != null && fieldNode.parent.name == "form-single") {
                try {
                    Map valueMap = (Map) ec.getContext().getByString(mapName)
                    if (valueMap instanceof EntityValueImpl) {
                        // if it is an EntityValueImpl, only get if the fieldName is a value
                        EntityValueImpl evi = (EntityValueImpl) valueMap
                        if (evi.getEntityDefinition().isField(fieldName)) fieldValue = evi.get(fieldName)
                    } else {
                        fieldValue = valueMap.get(fieldName)
                    }
                } catch (EntityException e) {
                    // do nothing, not necessarily an entity field
                    if (isTraceEnabled) logger.trace("Ignoring entity exception for non-field: ${e.toString()}")
                }
            }
            if (StupidJavaUtilities.isEmpty(fieldValue)) fieldValue = ec.getContext().getByString(fieldName)
        }

        return fieldValue != null ? fieldValue.getClass().getSimpleName() : "String"
    }

    String getFieldEntityValue(FtlNodeWrapper widgetNodeWrapper) {
        FtlNodeWrapper fieldNodeWrapper = (FtlNodeWrapper) widgetNodeWrapper.parentNode.parentNode
        Object fieldValue = getFieldValue(fieldNodeWrapper, "")
        if (fieldValue == null) return ""
        MNode widgetNode = widgetNodeWrapper.getMNode()
        String entityName = widgetNode.attribute('entity-name')
        EntityDefinition ed = sfi.ecfi.entityFacade.getEntityDefinition(entityName)

        // find the entity value
        String keyFieldName = (String) widgetNode.attribute('key-field-name')
        if (!keyFieldName) keyFieldName = (String) widgetNode.attribute('entity-key-name')
        if (!keyFieldName) keyFieldName = ed.getPkFieldNames().get(0)
        String useCache = widgetNode.attribute('use-cache') ?: widgetNode.attribute('entity-use-cache') ?: 'true'
        EntityValue ev = ec.entity.find(entityName).condition(keyFieldName, fieldValue)
                .useCache(useCache == "true").one()
        if (ev == null) return ""

        String value = ""
        String text = (String) widgetNode.attribute('text')
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

    ListOrderedMap getFieldOptions(FtlNodeWrapper widgetNodeWrapper) {
        return ScreenForm.getFieldOptions(widgetNodeWrapper.getMNode(), ec)
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
        // loop through entire screenRenderDefList and look for @screen-theme-type-enum-id, use last one found
        ArrayList<ScreenDefinition> screenRenderDefList = screenUrlInfo.screenRenderDefList
        if (screenRenderDefList != null) {
            int screenRenderDefListSize = screenRenderDefList.size()
            for (int i = 0; i < screenRenderDefListSize; i++) {
                ScreenDefinition sd = (ScreenDefinition) screenRenderDefList.get(i)
                String stteiStr = sd.screenNode.attribute('screen-theme-type-enum-id')
                if (stteiStr != null && stteiStr.length() > 0) stteId = stteiStr
            }
        }
        // if no setting default to STT_INTERNAL
        if (stteId == null) stteId = "STT_INTERNAL"

        EntityFacade entityFacade = sfi.ecfi.getEntityFacade(localEc.tenantId)
        // see if there is a user setting for the theme
        String themeId = entityFacade.find("moqui.security.UserScreenTheme")
                .condition("userId", localEc.user.userId).condition("screenThemeTypeEnumId", stteId)
                .useCache(true).disableAuthz().one()?.screenThemeId
        // use the Enumeration.enumCode from the type to find the theme type's default screenThemeId
        if (themeId == null || themeId.length() == 0) {
            EntityValue themeTypeEnum = entityFacade.find("moqui.basic.Enumeration")
                    .condition("enumId", stteId).useCache(true).disableAuthz().one()
            if (themeTypeEnum?.enumCode) themeId = themeTypeEnum.enumCode
        }
        // theme with "DEFAULT" in the ID
        if (themeId == null || themeId.length() == 0) {
            EntityValue stv = sfi.ecfi.entityFacade.find("moqui.screen.ScreenTheme")
                    .condition("screenThemeTypeEnumId", stteId)
                    .condition("screenThemeId", ComparisonOperator.LIKE, "%DEFAULT%").one()
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
            values.add(str.getString("resourceValue"))
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
}
