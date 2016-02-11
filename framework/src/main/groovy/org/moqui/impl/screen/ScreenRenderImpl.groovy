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
import org.moqui.entity.EntityList
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.moqui.impl.StupidUtilities
import org.moqui.impl.StupidWebUtilities
import org.moqui.impl.context.ArtifactExecutionInfoImpl
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
    protected final static URLCodec urlCodec = new URLCodec()

    protected final ScreenFacadeImpl sfi
    protected ExecutionContext localEc = null
    protected boolean rendering = false

    protected String rootScreenLocation = null
    protected ScreenDefinition rootScreenDef = null
    protected ScreenDefinition overrideActiveScreenDef = null

    protected List<String> originalScreenPathNameList = new ArrayList<String>()
    protected ScreenUrlInfo screenUrlInfo = null
    protected UrlInstance screenUrlInstance = null
    protected Map<String, ScreenUrlInfo> subscreenUrlInfos = new HashMap()
    protected int screenPathIndex = 0
    protected Set<String> stopRenderScreenLocations = new HashSet()

    protected String baseLinkUrl = null
    protected String servletContextPath = null
    protected String webappName = null

    protected String renderMode = null
    protected String characterEncoding = "UTF-8"
    /** For HttpServletRequest/Response renders this will be set on the response either as this default or a value
     * determined during render, especially for screen sub-content based on the extension of the filename. */
    protected String outputContentType = null

    protected String macroTemplateLocation = null
    protected Boolean boundaryComments = null

    protected HttpServletRequest request = null
    protected HttpServletResponse response = null
    protected Writer internalWriter = null
    protected Writer afterScreenWriter = null
    protected Writer scriptWriter= null

    protected boolean dontDoRender = false

    protected Map<String, MNode> screenFormNodeCache = new HashMap()

    ScreenRenderImpl(ScreenFacadeImpl sfi) {
        this.sfi = sfi
        this.localEc = sfi.ecfi.getExecutionContext()
    }

    Writer getWriter() {
        if (internalWriter != null) return internalWriter
        if (response != null) {
            internalWriter = response.getWriter()
            return internalWriter
        }
        throw new BaseException("Could not render screen, no writer available")
    }

    ExecutionContext getEc() { return localEc }
    ScreenFacadeImpl getSfi() { return sfi }
    ScreenUrlInfo getScreenUrlInfo() { return screenUrlInfo }
    UrlInstance getScreenUrlInstance() { return screenUrlInstance }

    @Override
    ScreenRender rootScreen(String rootScreenLocation) { this.rootScreenLocation = rootScreenLocation; return this }

    ScreenRender rootScreenFromHost(String host) {
        for (MNode rootScreenNode in getWebappNode().children("root-screen")) {
            if (host.matches((String) rootScreenNode.attribute('host')))
                return this.rootScreen((String) rootScreenNode.attribute('location'))
        }
        throw new BaseException("Could not find root screen for host [${host}]")
    }

    @Override
    ScreenRender screenPath(List<String> screenNameList) { this.originalScreenPathNameList.addAll(screenNameList); return this }

    @Override
    ScreenRender renderMode(String renderMode) { this.renderMode = renderMode; return this }

    String getRenderMode() { return this.renderMode }

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
        if (!webappName) webappName(request.session.servletContext.getInitParameter("moqui-name"))
        if (webappName && !rootScreenLocation) rootScreenFromHost(request.getServerName())
        if (!originalScreenPathNameList) screenPath(request.getPathInfo().split("/") as List)
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
            logger.info("Redirecting to [${redirectUrl}] instead of rendering [${this.getScreenUrlInfo().getFullPathNameList()}]")
        }
    }

    protected ResponseItem recursiveRunTransition(Iterator<ScreenDefinition> sdIterator) {
        ScreenDefinition sd = sdIterator.next()
        // for these authz is not required, as long as something authorizes on the way to the transition, or
        // the transition itself, it's fine
        ArtifactExecutionInfo aei = new ArtifactExecutionInfoImpl(sd.location, "AT_XML_SCREEN", "AUTHZA_VIEW")
        ec.getArtifactExecution().push(aei, false)

        boolean loggedInAnonymous = false
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

        ResponseItem ri = null
        if (sdIterator.hasNext()) {
            screenPathIndex++
            try {
                ri = recursiveRunTransition(sdIterator)
            } finally {
                screenPathIndex--
            }
        } else {
            // run the transition
            ri = screenUrlInstance.targetTransition.run(this)
        }

        ec.getArtifactExecution().pop(aei)
        if (loggedInAnonymous) ((UserFacadeImpl) ec.getUser()).logoutAnonymousOnly()

        return ri
    }

    protected void internalRender() {
        rootScreenDef = sfi.getScreenDefinition(rootScreenLocation)
        if (rootScreenDef == null) throw new BaseException("Could not find root screen at location [${rootScreenLocation}]")

        if (logger.traceEnabled) logger.trace("Rendering screen [${rootScreenLocation}] with path list [${originalScreenPathNameList}]")
        // logger.info("Rendering screen [${rootScreenLocation}] with path list [${originalScreenPathNameList}]")

        screenUrlInfo = ScreenUrlInfo.getScreenUrlInfo(this, rootScreenDef, originalScreenPathNameList, null,
                (ec.getWeb() != null && ec.getWeb().requestParameters.lastStandalone == "true"))
        screenUrlInstance = screenUrlInfo.getInstance(this, false)
        if (ec.getWeb()) {
            // clear out the parameters used for special screen URL config
            if (ec.getWeb().requestParameters.lastStandalone) ec.getWeb().requestParameters.lastStandalone = ""

            // if screenUrlInfo has any parameters add them to the request (probably came from a transition acting as an alias)
            Map<String, String> suiParameterMap = screenUrlInstance.getTransitionAliasParameters()
            if (suiParameterMap) ec.getWeb().requestParameters.putAll(suiParameterMap)

            // add URL parameters, if there were any in the URL (in path info or after ?)
            screenUrlInstance.addParameters(ec.getWeb().requestParameters)
        }

        // check webapp settings for each screen in the path
        for (ScreenDefinition checkSd in screenUrlInfo.screenRenderDefList) {
            if (!checkWebappSettings(checkSd)) return
        }

        // check this here after the ScreenUrlInfo (with transition alias, etc) has already been handled
        if (ec.getWeb() != null && ec.getWeb().requestParameters.renderMode) {
            // we know this is a web request, set defaults if missing
            renderMode = ec.getWeb().requestParameters.renderMode
            String mimeType = sfi.getMimeTypeByMode(renderMode)
            if (mimeType) outputContentType = mimeType
        }

        // if these aren't set in any screen (in the checkWebappSettings method), set them here
        if (!renderMode) renderMode = "html"
        if (!characterEncoding) characterEncoding = "UTF-8"
        if (!outputContentType) outputContentType = "text/html"


        // before we render, set the character encoding (set the content type later, after we see if there is sub-content with a different type)
        if (this.response != null) response.setCharacterEncoding(this.characterEncoding)

        // if there is a transition run that INSTEAD of the screen to render
        ScreenDefinition.TransitionItem targetTransition = screenUrlInstance.getTargetTransition()
        // logger.warn("============ Rendering screen ${screenUrlInfo.getTargetScreen().getLocation()} transition ${screenUrlInfo.getTargetTransitionActualName()} has transition ${targetTransition != null}")
        if (targetTransition != null) {
            // if this transition has actions and request was not secure or any parameters were not in the body
            // return an error, helps prevent CSRF/XSRF attacks
            if (request != null && targetTransition.hasActionsOrSingleService()) {
                if (!targetTransition.isReadOnly() && (
                        (!request.isSecure() && getWebappNode().attribute('https-enabled') != "false") ||
                        request.getQueryString() ||
                        StupidWebUtilities.getPathInfoParameterMap(request.getPathInfo()))) {
                    throw new IllegalArgumentException(
                        """Cannot run screen transition with actions from non-secure request or with URL
                        parameters for security reasons (they are not encrypted and need to be for data
                        protection and source validation). Change the link this came from to be a
                        form with hidden input fields instead, or declare the transition as read-only.""")
                }
                // require a moquiSessionToken parameter for all but get
                if (request.getMethod().toLowerCase() != "get" &&
                        getWebappNode().attribute("require-session-token") != "false" &&
                        targetTransition.getRequireSessionToken() &&
                        request.getAttribute("moqui.session.token.created") != "true" &&
                        request.getAttribute("moqui.request.authenticated") != "true") {
                    String passedToken = ec.web.getParameters().get("moquiSessionToken")
                    String curToken = ec.web.getSessionToken()
                    if (curToken) {
                        if (!passedToken) {
                            throw new IllegalArgumentException("Session token required (in moquiSessionToken) for URL ${screenUrlInstance.url}")
                        } else if (curToken != passedToken) {
                            throw new IllegalArgumentException("Session token does not match (in moquiSessionToken) for URL ${screenUrlInstance.url}")
                        }
                    }
                }
            }

            long transitionStartTime = System.currentTimeMillis()
            long startTimeNanos = System.nanoTime()

            boolean beginTransaction = targetTransition.getBeginTransaction()
            boolean beganTransaction = beginTransaction ? sfi.getEcfi().getTransactionFacade().begin(null) : false
            ResponseItem ri = null
            try {
                ri = recursiveRunTransition(screenUrlInfo.screenPathDefList.iterator())
            } catch (Throwable t) {
                sfi.ecfi.transactionFacade.rollback(beganTransaction, "Error running transition in [${screenUrlInstance.url}]", t)
                throw t
            } finally {
                try {
                    if (sfi.ecfi.transactionFacade.isTransactionInPlace()) {
                        if (ec.getMessage().hasError()) {
                            sfi.ecfi.transactionFacade.rollback(beganTransaction, ec.getMessage().getErrorsString(), null)
                        } else {
                            sfi.ecfi.transactionFacade.commit(beganTransaction)
                        }
                    }
                } catch (Exception e) {
                    logger.error("Error ending screen transition transaction", e)
                }

                if (screenUrlInfo.targetScreen.screenNode.attribute('track-artifact-hit') != "false") {
                    sfi.ecfi.countArtifactHit("transition", ri?.type ?: "",
                            targetTransition.parentScreen.getLocation() + "#" + targetTransition.name,
                            (ec.getWeb() ? ec.getWeb().requestParameters : null), transitionStartTime,
                            (System.nanoTime() - startTimeNanos)/1E6, null)
                }
            }

            if (ri == null) throw new IllegalArgumentException("No response found for transition [${screenUrlInstance.targetTransition.name}] on screen ${screenUrlInfo.targetScreen.location}")

            if (ri.saveCurrentScreen && ec.getWeb() != null) {
                StringBuilder screenPath = new StringBuilder()
                for (String pn in screenUrlInfo.fullPathNameList) screenPath.append("/").append(pn)
                ((WebFacadeImpl) ec.getWeb()).saveScreenLastInfo(screenPath.toString(), null)
            }

            if (ri.type == "none") {
                logger.info("Finished transition ${getScreenUrlInfo().getFullPathNameList()} in ${(System.currentTimeMillis() - transitionStartTime)/1000} seconds.")
                return
            }

            String url = ri.url ?: ""
            String urlType = ri.urlType ?: "screen-path"

            // handle screen-last, etc
            WebFacadeImpl wfi = null
            if (ec.getWeb() != null && ec.getWeb() instanceof WebFacadeImpl) wfi = (WebFacadeImpl) ec.getWeb()
            if (wfi != null) {
                if (ri.type == "screen-last" || ri.type == "screen-last-noparam") {
                    String savedUrl =  wfi.getRemoveScreenLastPath()
                    urlType = "screen-path"
                    if (savedUrl) {
                        url = savedUrl
                        wfi.removeScreenLastParameters(ri.type == "screen-last")
                    } else {
                        // try screen history when no last was saved
                        List historyList = wfi.getScreenHistory()
                        Map historyMap = historyList ? historyList.first() : null
                        if (historyMap) {
                            url = ri.type == "screen-last" ? historyMap.url : historyMap.urlNoParams
                            urlType = "plain"
                        } else {
                            // if no saved URL, just go to root/default; avoid getting stuck on Login screen, etc
                            url = savedUrl ?: "/"
                        }
                    }
                }
            }

            // either send a redirect for the response, if possible, or just render the response now
            if (this.response != null) {
                // save messages in session before redirecting so they can be displayed on the next screen
                if (wfi != null) {
                    wfi.saveMessagesToSession()
                    if (ri.saveParameters) wfi.saveRequestParametersToSession()
                    if (ec.message.hasError()) wfi.saveErrorParametersToSession()
                }

                if (urlType == "plain") {
                    StringBuilder ps = new StringBuilder()
                    Map<String, String> pm = (Map<String, String>) ri.expandParameters(screenUrlInfo.getExtraPathNameList(), ec)
                    if (pm) {
                        for (Map.Entry<String, String> pme in pm.entrySet()) {
                            if (!pme.value) continue
                            if (ps.length() > 0) ps.append("&")
                            ps.append(pme.key).append("=").append(urlCodec.encode(pme.value))
                        }
                    }
                    String fullUrl = url
                    if (ps) {
                        if (url.contains("?")) fullUrl += "&" else fullUrl += "?"
                        fullUrl += ps.toString()
                    }
                    response.sendRedirect(fullUrl)
                } else {
                    // default is screen-path
                    UrlInstance fullUrl = buildUrl(rootScreenDef, screenUrlInfo.preTransitionPathNameList, url)
                    fullUrl.addParameters(ri.expandParameters(screenUrlInfo.getExtraPathNameList(), ec))
                    // if this was a screen-last and the screen has declared parameters include them in the URL
                    Map savedParameters = wfi?.getSavedParameters()
                    UrlInstance.copySpecialParameters(savedParameters, fullUrl.getOtherParameterMap())
                    // screen parameters
                    if (ri.type == "screen-last" && savedParameters && fullUrl.sui.getTargetScreen()?.getParameterMap()) {
                        for (String parmName in fullUrl.sui.getTargetScreen().getParameterMap().keySet()) {
                            if (savedParameters.get(parmName))
                                fullUrl.addParameter(parmName, savedParameters.get(parmName))
                        }
                    }
                    // transition parameters
                    if (ri.type == "screen-last" && savedParameters && fullUrl.getTargetTransition()?.getParameterMap()) {
                        for (String parmName in fullUrl.getTargetTransition().getParameterMap().keySet()) {
                            if (savedParameters.get(parmName))
                                fullUrl.addParameter(parmName, savedParameters.get(parmName))
                        }
                    }
                    String fullUrlString = fullUrl.getUrlWithParams()
                    logger.info("Finished transition [${getScreenUrlInfo().getFullPathNameList()}] in [${(System.currentTimeMillis() - transitionStartTime)/1000}] seconds , redirecting to [${fullUrlString}]")
                    response.sendRedirect(fullUrlString)
                }
            } else {
                List<String> pathElements = url.split("/") as List
                if (url.startsWith("/")) {
                    this.originalScreenPathNameList = pathElements
                } else {
                    this.originalScreenPathNameList = screenUrlInfo.preTransitionPathNameList
                    this.originalScreenPathNameList.addAll(pathElements)
                }
                // reset screenUrlInfo and call this again to start over with the new target
                screenUrlInfo = null
                internalRender()
            }
        } else if (screenUrlInfo.fileResourceRef != null) {
            long resourceStartTime = System.currentTimeMillis()
            long startTimeNanos = System.nanoTime()

            TemplateRenderer tr = sfi.ecfi.resourceFacade.getTemplateRendererByLocation(screenUrlInfo.fileResourceRef.location)

            // use the fileName to determine the content/mime type
            String fileName = screenUrlInfo.fileResourceRef.fileName
            // strip template extension(s) to avoid problems with trying to find content types based on them
            String fileContentType = sfi.ecfi.resourceFacade.getContentType(tr != null ? tr.stripTemplateExtension(fileName) : fileName)

            boolean isBinary = sfi.ecfi.resourceFacade.isBinaryContentType(fileContentType)
            // if (logger.traceEnabled) logger.trace("Content type for screen sub-content filename [${fileName}] is [${fileContentType}], default [${this.outputContentType}], is binary? ${isBinary}")

            if (isBinary) {
                if (response) {
                    this.outputContentType = fileContentType
                    response.setContentType(this.outputContentType)
                    // static binary, tell the browser to cache it
                    // NOTE: make this configurable?
                    response.addHeader("Cache-Control", "max-age=3600, must-revalidate, public")

                    InputStream is
                    try {
                        is = screenUrlInfo.fileResourceRef.openStream()
                        OutputStream os = response.outputStream
                        int totalLen = StupidUtilities.copyStream(is, os)

                        if (screenUrlInfo.targetScreen.screenNode.attribute('track-artifact-hit') != "false") {
                            sfi.ecfi.countArtifactHit("screen-content", fileContentType, screenUrlInfo.fileResourceRef.location,
                                    (ec.getWeb() != null ? ec.getWeb().requestParameters : null), resourceStartTime,
                                    (System.nanoTime() - startTimeNanos)/1E6, (long) totalLen)
                        }
                        if (logger.traceEnabled) logger.trace("Sent binary response of length [${totalLen}] with from file [${screenUrlInfo.fileResourceRef.location}] for request to [${screenUrlInstance.url}]")
                        return
                    } finally {
                        if (is != null) is.close()
                    }
                } else {
                    throw new IllegalArgumentException("Tried to get binary content at [${screenUrlInfo.fileResourcePathList}] under screen [${screenUrlInfo.targetScreen.location}], but there is no HTTP response available")
                }
            }

            // not binary, render as text
            if (screenUrlInfo.targetScreen.screenNode.attribute('include-child-content') != "true") {
                // not a binary object (hopefully), read it and write it to the writer
                if (fileContentType) this.outputContentType = fileContentType
                if (response != null) {
                    response.setContentType(this.outputContentType)
                    response.setCharacterEncoding(this.characterEncoding)
                }

                if (tr != null) {
                    // if requires a render, don't cache and make it private
                    if (response != null) response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate, private")
                    tr.render(screenUrlInfo.fileResourceRef.location, writer)
                } else {
                    // static text, tell the browser to cache it
                    // TODO: make this configurable?
                    if (response != null) response.addHeader("Cache-Control", "max-age=3600, must-revalidate, public")
                    // no renderer found, just grab the text (cached) and throw it to the writer
                    String text = sfi.ecfi.resourceFacade.getLocationText(screenUrlInfo.fileResourceRef.location, true)
                    if (text) {
                        // NOTE: String.length not correct for byte length
                        String charset = response?.getCharacterEncoding() ?: "UTF-8"
                        int length = text.getBytes(charset).length
                        if (response != null) response.setContentLength(length)

                        if (logger.traceEnabled) logger.trace("Sending text response of length [${length}] with [${charset}] encoding from file [${screenUrlInfo.fileResourceRef.location}] for request to [${screenUrlInstance.url}]")

                        writer.write(text)

                        if (screenUrlInfo.targetScreen.screenNode.attribute('track-artifact-hit') != "false") {
                            sfi.ecfi.countArtifactHit("screen-content", fileContentType, screenUrlInfo.fileResourceRef.location,
                                    (ec.getWeb() != null ? ec.getWeb().requestParameters : null), resourceStartTime,
                                    (System.nanoTime() - startTimeNanos)/1E6, (long) length)
                        }
                    } else {
                        logger.warn("Not sending text response from file [${screenUrlInfo.fileResourceRef.location}] for request to [${screenUrlInstance.url}] because no text was found in the file.")
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
        ArtifactExecutionInfo aei = new ArtifactExecutionInfoImpl(sd.location, "AT_XML_SCREEN", "AUTHZA_VIEW")
        ec.artifactExecution.push(aei, !screenDefIterator.hasNext() ? (!requireAuthentication || requireAuthentication == "true") : false)

        if (sd.getTenantsAllowed() && !sd.getTenantsAllowed().contains(ec.getTenantId()))
            throw new ArtifactAuthorizationException("The screen ${sd.getScreenName()} is not available to tenant [${ec.getTenantId()}]")

        boolean loggedInAnonymous = false
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

        // all done so pop the artifact info; don't bother making sure this is done on errors/etc like in a finally clause because if there is an error this will help us know how we got there
        ec.artifactExecution.pop(aei)
        if (loggedInAnonymous) ((UserFacadeImpl) ec.getUser()).logoutAnonymousOnly()
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
                        throw new ArtifactAuthorizationException("The screen ${permSd.getScreenName()} is not available to tenant [${ec.getTenantId()}]")
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

                    ArtifactExecutionInfo aei = new ArtifactExecutionInfoImpl(permSd.location, "AT_XML_SCREEN", "AUTHZA_VIEW")
                    ec.artifactExecution.push(aei, false)
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
            for (ScreenDefinition sd in screenUrlInfo.screenRenderDefList) {
                for (ScreenDefinition.ParameterItem pi in sd.getParameterMap().values()) {
                    if (pi.required && ec.context.get(pi.name) == null) {
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
        if (!request) return true

        if (currentSd.webSettingsNode?.attribute('allow-web-request') == "false")
            throw new IllegalArgumentException("The screen [${currentSd.location}] cannot be used in a web request (allow-web-request=false).")

        String mimeType = (String) currentSd.webSettingsNode?.attribute('mime-type')
        if (mimeType) this.outputContentType = mimeType
        String characterEncoding = (String) currentSd.webSettingsNode?.attribute('character-encoding')
        if (characterEncoding) this.characterEncoding = characterEncoding

        // if screen requires auth and there is not active user redirect to login screen, save this request
        if (logger.traceEnabled) logger.trace("Checking screen [${currentSd.location}] for require-authentication, current user is [${ec.user.userId}]")

        WebFacadeImpl wfi = null
        if (ec.getWeb() != null && ec.getWeb() instanceof WebFacadeImpl) wfi = (WebFacadeImpl) ec.getWeb()
        String requireAuthentication = (String) currentSd.screenNode?.attribute('require-authentication')
        if ((!requireAuthentication || requireAuthentication == "true")
                && !ec.getUser().getUserId() && !((UserFacadeImpl) ec.getUser()).getLoggedInAnonymous()) {
            logger.info("Screen at location [${currentSd.location}], which is part of [${screenUrlInfo.fullPathNameList}] under screen [${screenUrlInfo.fromSd.location}] requires authentication but no user is currently logged in.")
            // save the request as a save-last to use after login
            if (wfi != null && screenUrlInfo.fileResourceRef == null) {
                StringBuilder screenPath = new StringBuilder()
                for (String pn in screenUrlInfo.fullPathNameList) screenPath.append("/").append(pn)
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
                List<String> pathElements = loginPath.split("/") as List
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
                ScreenUrlInfo suInfo = ScreenUrlInfo.getScreenUrlInfo(this, rootScreenDef, [], loginPath, false)
                UrlInstance urlInstance = suInfo.getInstance(this, false)
                response.sendRedirect(urlInstance.url)
                return false
            }
        }

        // if request not secure and screens requires secure redirect to https
        if (currentSd.webSettingsNode?.attribute('require-encryption') != "false" &&
                getWebappNode().attribute('https-enabled') != "false" && !request.isSecure()) {
            logger.info("Screen at location [${currentSd.location}], which is part of [${screenUrlInfo.fullPathNameList}] under screen [${screenUrlInfo.fromSd.location}] requires an encrypted/secure connection but the request is not secure, sending redirect to secure.")
            // save messages in session before redirecting so they can be displayed on the next screen
            if (wfi != null) wfi.saveMessagesToSession()
            // redirect to the same URL this came to
            response.sendRedirect(screenUrlInstance.getUrlWithParams())
            return false
        }

        return true
    }

    MNode getWebappNode() {
        if (webappName == null || webappName.length() == 0) return null
        return sfi.ecfi.confXmlRoot.first("webapp-list").first({ MNode it -> it.name == "webapp" && it.attribute("name") == webappName })
    }

    boolean doBoundaryComments() {
        if (screenPathIndex == 0) return false
        if (boundaryComments != null) return boundaryComments
        boundaryComments = sfi.ecfi.confXmlRoot.first("screen-facade").attribute("boundary-comments") == "true"
        return boundaryComments
    }

    ScreenDefinition getRootScreenDef() { return rootScreenDef }
    ScreenDefinition getActiveScreenDef() { return overrideActiveScreenDef ?: screenUrlInfo.screenRenderDefList.get(screenPathIndex) }

    List<String> getActiveScreenPath() {
        // handle case where root screen is first/zero in list versus a standalone screen
        int fullPathIndex = screenUrlInfo.renderPathDifference + screenPathIndex
        if (fullPathIndex == 0) return []
        List<String> activePath = screenUrlInfo.fullPathNameList[0..fullPathIndex-1]
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
            throw new ArtifactAuthorizationException("The screen ${screenDef.getScreenName()} is not available to tenant [${ec.getTenantId()}]")
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
                // go through entire list and set all found, basically we want the last one if there are more than one
                MNode mt = sd.screenNode.first({ MNode it -> it.name == "macro-template" && it.attribute('type') == renderMode })
                if (mt != null) overrideTemplateLocation = mt.attribute('location')
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
        ((ContextStack) ec.context).push()
        form.runFormListRowActions(this, listEntry, index, hasNext)
        // NOTE: this returns an empty String so that it can be used in an FTL interpolation, but nothing is written
        return ""
    }
    String endFormListRow() {
        ((ContextStack) ec.context).pop()
        // NOTE: this returns an empty String so that it can be used in an FTL interpolation, but nothing is written
        return ""
    }
    String safeCloseList(Object listObject) {
        if (listObject instanceof EntityListIterator) ((EntityListIterator) listObject).close()
        // NOTE: this returns an empty String so that it can be used in an FTL interpolation, but nothing is written
        return ""
    }
    MNode getFormNode(String formName) {
        ScreenDefinition sd = getActiveScreenDef()
        String nodeCacheKey = sd.getLocation() + "#" + formName
        // NOTE: this is cached in the context of the renderer for multiple accesses; because of form overrides may not
        // be valid outside the scope of a single screen render
        MNode formNode = screenFormNodeCache.get(nodeCacheKey)
        if (formNode == null) {
            ScreenForm form = sd.getForm(formName)
            if (!form) throw new IllegalArgumentException("No form with name [${formName}] in screen [${sd.location}]")
            formNode = form.getFormNode()
            screenFormNodeCache.put(nodeCacheKey, formNode)
        }
        return formNode
    }
    FtlNodeWrapper getFtlFormNode(String formName) { return FtlNodeWrapper.wrapNode(getFormNode(formName)) }
    List<FtlNodeWrapper> getFtlFormFieldLayoutNonReferencedFieldList(String formName) {
        ScreenDefinition sd = getActiveScreenDef()
        List<MNode> fieldNodeList = sd.getForm(formName).getFieldLayoutNonReferencedFieldList()
        List<FtlNodeWrapper> fieldFtlNodeList = []
        for (MNode fieldNode in fieldNodeList) fieldFtlNodeList.add(FtlNodeWrapper.wrapNode(fieldNode))
        return fieldFtlNodeList
    }
    List<FtlNodeWrapper> getFtlFormListColumnNonReferencedHiddenFieldList(String formName) {
        ScreenDefinition sd = getActiveScreenDef()
        List<MNode> fieldNodeList = sd.getForm(formName).getColumnNonReferencedHiddenFieldList()
        List<FtlNodeWrapper> fieldFtlNodeList = []
        for (MNode fieldNode in fieldNodeList) fieldFtlNodeList.add(FtlNodeWrapper.wrapNode(fieldNode))
        return fieldFtlNodeList
    }


    boolean isFormUpload(String formName) {
        MNode cachedFormNode = this.getFormNode(formName)
        return getActiveScreenDef().getForm(formName).isUpload(cachedFormNode)
    }
    boolean isFormHeaderForm(String formName) {
        MNode cachedFormNode = this.getFormNode(formName)
        return getActiveScreenDef().getForm(formName).isFormHeaderForm(cachedFormNode)
    }

    String getFormFieldValidationClasses(String formName, String fieldName) {
        ScreenForm form = getActiveScreenDef().getForm(formName)
        MNode cachedFormNode = getFormNode(formName)
        MNode validateNode = form.getFieldValidateNode(fieldName, cachedFormNode)
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
        MNode cachedFormNode = getFormNode(formName)
        MNode validateNode = form.getFieldValidateNode(fieldName, cachedFormNode)
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
        boolean isTemplate = (isTemplateStr != "false")

        if (!location || location == "null") {
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

    UrlInstance buildUrl(ScreenDefinition fromSd, List<String> fromPathList, String subscreenPathOrig) {
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
        Map valueMap = (Map) cs.get(mapName)

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
        if (!obj && defaultValue) return ec.getResource().expand(defaultValue, "")
        return StupidUtilities.toPlainString(obj)
        // NOTE: this approach causes problems with currency fields, but kills the string expand for default-value... a better approach?
        //return obj ? obj.toString() : (defaultValue ? ec.getResource().expand(defaultValue, null) : "")
    }

    Object getFieldValue(FtlNodeWrapper fieldNodeWrapper, String defaultValue) {
        MNode fieldNode = fieldNodeWrapper.getMNode()
        String entryName = fieldNode.attribute('entry-name')
        if (entryName) return ec.getResource().expression(entryName, null)
        String fieldName = fieldNode.attribute('name')
        String mapName = fieldNode.parent.attribute('map') ?: "fieldValues"
        Object value = null
        // if this is an error situation try parameters first, otherwise try parameters last
        Map<String, Object> errorParameters = ec.getWeb()?.getErrorParameters()
        if (errorParameters != null && (errorParameters.moquiFormName == fieldNode.parent.attribute('name')))
            value = errorParameters.get(fieldName)
        Map valueMap = (Map) ec.resource.expression(mapName, "")
        if (StupidUtilities.isEmpty(value)) {
            MNode formNode = fieldNode.parent
            if (valueMap && formNode.name == "form-single") {
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
                    if (logger.isTraceEnabled()) logger.trace("Ignoring entity exception for non-field: ${e.toString()}")
                }
            } else if (formNode.name == "form-list" && formNode.attribute('list-entry')) {
                // use some Groovy goodness to get an object property, only do if this is NOT a Map (that is handled by
                //     putting all Map entries in the context for each row)
                Object entryObj = ec.getContext().get(formNode.attribute('list-entry'))
                if (entryObj != null && !(entryObj instanceof Map)) {
                    try {
                        value = entryObj.getAt(fieldName)
                    } catch (MissingPropertyException e) {
                        // ignore exception, we know this may not be a real property of the object
                    }
                }
            }
        }
        if (StupidUtilities.isEmpty(value)) value = ec.getContext().get(fieldName)
        // this isn't needed since the parameters are copied to the context: if (!isError && isWebAndSameForm && !value) value = ec.getWeb().parameters.get(fieldName)

        if (!StupidUtilities.isEmpty(value)) return value

        String defaultStr = ec.getResource().expand(defaultValue, null)
        if (defaultStr) return defaultStr
        return value
    }
    String getFieldValueClass(FtlNodeWrapper fieldNodeWrapper) {
        Object fieldValue = null

        MNode fieldNode = fieldNodeWrapper.getMNode()
        String entryName = fieldNode.attribute('entry-name')
        if (entryName) fieldValue = ec.getResource().expression(entryName, null)
        if (fieldValue == null) {
            String fieldName = fieldNode.attribute('name')
            String mapName = fieldNode.parent.attribute('map') ?: "fieldValues"
            if (ec.getContext().get(mapName) && fieldNode.parent.name == "form-single") {
                try {
                    Map valueMap = (Map) ec.getContext().get(mapName)
                    if (valueMap instanceof EntityValueImpl) {
                        // if it is an EntityValueImpl, only get if the fieldName is a value
                        EntityValueImpl evi = (EntityValueImpl) valueMap
                        if (evi.getEntityDefinition().isField(fieldName)) fieldValue = evi.get(fieldName)
                    } else {
                        fieldValue = valueMap.get(fieldName)
                    }
                } catch (EntityException e) {
                    // do nothing, not necessarily an entity field
                    if (logger.isTraceEnabled()) logger.trace("Ignoring entity exception for non-field: ${e.toString()}")
                }
            }
            if (!fieldValue) fieldValue = ec.getContext().get(fieldName)
        }

        return fieldValue != null ? fieldValue.getClass().getSimpleName() : "String"
    }

    String getFieldEntityValue(FtlNodeWrapper widgetNodeWrapper) {
        FtlNodeWrapper fieldNodeWrapper = (FtlNodeWrapper) widgetNodeWrapper.parentNode.parentNode
        Object fieldValue = getFieldValue(fieldNodeWrapper, "")
        if (!fieldValue) return ""
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
        if (text) {
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
        String stteId = null
        // loop through entire screenRenderDefList and look for @screen-theme-type-enum-id, use last one found
        if (screenUrlInfo.screenRenderDefList) for (ScreenDefinition sd in screenUrlInfo.screenRenderDefList) {
            String stteiStr = (String) sd.screenNode?.attribute('screen-theme-type-enum-id')
            if (stteiStr) stteId = stteiStr
        }
        // if no setting default to STT_INTERNAL
        if (!stteId) stteId = "STT_INTERNAL"

        // see if there is a user setting for the theme
        String themeId = sfi.ecfi.entityFacade.find("moqui.security.UserScreenTheme")
                .condition([userId:ec.user.userId, screenThemeTypeEnumId:stteId] as Map<String, Object>)
                .useCache(true).disableAuthz().one()?.screenThemeId
        // use the Enumeration.enumCode from the type to find the theme type's default screenThemeId
        if (!themeId) {
            boolean alreadyDisabled = ec.getArtifactExecution().disableAuthz()
            try {
                EntityValue themeTypeEnum = sfi.ecfi.entityFacade.find("moqui.basic.Enumeration")
                        .condition("enumId", stteId).useCache(true).disableAuthz().one()
                if (themeTypeEnum?.enumCode) themeId = themeTypeEnum.enumCode
            } finally {
                if (!alreadyDisabled) ec.getArtifactExecution().enableAuthz()
            }
        }
        // theme with "DEFAULT" in the ID
        if (!themeId) {
            EntityValue stv = sfi.ecfi.entityFacade.find("moqui.screen.ScreenTheme")
                    .condition("screenThemeTypeEnumId", stteId)
                    .condition("screenThemeId", ComparisonOperator.LIKE, "%DEFAULT%").one()
            if (stv) themeId = stv.screenThemeId
        }
        return themeId
    }

    List<String> getThemeValues(String resourceTypeEnumId) {
        EntityList strList = sfi.ecfi.entityFacade.find("moqui.screen.ScreenThemeResource")
                .condition([screenThemeId:getCurrentThemeId(), resourceTypeEnumId:resourceTypeEnumId] as Map<String, Object>)
                .orderBy("sequenceNum").useCache(true).disableAuthz().list()
        List<String> values = new LinkedList()
        for (EntityValue str in strList) values.add(str.resourceValue as String)
        return values
    }

    String getThemeIconClass(String text) {
        EntityList stiList = sfi.ecfi.entityFacade.find("moqui.screen.ScreenThemeIcon").disableAuthz()
                .condition([screenThemeId:getCurrentThemeId()] as Map<String, Object>).useCache(true).list()
        for (EntityValue sti in stiList) {
            if (text.matches((String) sti.textPattern)) {
                return (String) sti.iconClass
            }
        }
        return null
    }
}
