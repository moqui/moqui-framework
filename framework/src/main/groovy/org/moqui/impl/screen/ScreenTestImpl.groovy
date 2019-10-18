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
import org.apache.shiro.subject.Subject
import org.moqui.BaseArtifactException
import org.moqui.util.ContextStack
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.screen.ScreenRender
import org.moqui.screen.ScreenTest
import org.moqui.screen.ScreenTest.ScreenTestRender
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ScreenTestImpl implements ScreenTest {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenTestImpl.class)

    protected final ExecutionContextFactoryImpl ecfi
    protected final ScreenFacadeImpl sfi
    // see FtlTemplateRenderer.MoquiTemplateExceptionHandler, others
    final List<String> errorStrings = ["[Template Error", "FTL stack trace", "Could not find subscreen or transition"]

    protected String rootScreenLocation = null
    protected ScreenDefinition rootScreenDef = null
    protected String baseScreenPath = null
    protected List<String> baseScreenPathList = null
    protected ScreenDefinition baseScreenDef = null

    protected String outputType = null
    protected String characterEncoding = null
    protected String macroTemplateLocation = null
    protected String baseLinkUrl = null
    protected String servletContextPath = null
    protected String webappName = null
    protected boolean skipJsonSerialize = false
    protected static final String hostname = "localhost"

    long renderCount = 0, errorCount = 0, totalChars = 0, startTime = System.currentTimeMillis()

    final Map<String, Object> sessionAttributes = [:]

    ScreenTestImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        sfi = ecfi.screenFacade

        // init default webapp, root screen
        webappName('webroot')
    }

    @Override
    ScreenTest rootScreen(String screenLocation) {
        rootScreenLocation = screenLocation
        rootScreenDef = sfi.getScreenDefinition(rootScreenLocation)
        if (rootScreenDef == null) throw new IllegalArgumentException("Root screen not found: ${rootScreenLocation}")
        baseScreenDef = rootScreenDef
        return this
    }
    @Override
    ScreenTest baseScreenPath(String screenPath) {
        if (!rootScreenLocation) throw new BaseArtifactException("No rootScreen specified")
        baseScreenPath = screenPath
        if (baseScreenPath.endsWith("/")) baseScreenPath = baseScreenPath.substring(0, baseScreenPath.length() - 1)
        if (baseScreenPath) {
            baseScreenPathList = ScreenUrlInfo.parseSubScreenPath(rootScreenDef, rootScreenDef, [], baseScreenPath, null, sfi)
            if (baseScreenPathList == null) throw new BaseArtifactException("Error in baseScreenPath, could find not base screen path ${baseScreenPath} under ${rootScreenDef.location}")
            for (String screenName in baseScreenPathList) {
                ScreenDefinition.SubscreensItem ssi = baseScreenDef.getSubscreensItem(screenName)
                if (ssi == null) throw new BaseArtifactException("Error in baseScreenPath, could not find ${screenName} under ${baseScreenDef.location}")
                baseScreenDef = sfi.getScreenDefinition(ssi.location)
                if (baseScreenDef == null) throw new BaseArtifactException("Error in baseScreenPath, could not find screen ${screenName} at ${ssi.location}")
            }
        }
        return this
    }
    @Override ScreenTest renderMode(String outputType) { this.outputType = outputType; return this }
    @Override ScreenTest encoding(String characterEncoding) { this.characterEncoding = characterEncoding; return this }
    @Override ScreenTest macroTemplate(String macroTemplateLocation) { this.macroTemplateLocation = macroTemplateLocation; return this }
    @Override ScreenTest baseLinkUrl(String baseLinkUrl) { this.baseLinkUrl = baseLinkUrl; return this }
    @Override ScreenTest servletContextPath(String scp) { this.servletContextPath = scp; return this }
    @Override ScreenTest skipJsonSerialize(boolean skip) { this.skipJsonSerialize = skip; return this }

    @Override
    ScreenTest webappName(String wan) {
        webappName = wan

        // set a default root screen based on config for "localhost"
        MNode webappNode = ecfi.getWebappNode(webappName)
        for (MNode rootScreenNode in webappNode.children("root-screen")) {
            if (hostname.matches(rootScreenNode.attribute('host'))) {
                String rsLoc = rootScreenNode.attribute('location')
                rootScreen(rsLoc)
                break
            }
        }

        return this
    }

    @Override
    List<String> getNoRequiredParameterPaths(Set<String> screensToSkip) {
        if (!rootScreenLocation) throw new IllegalStateException("No rootScreen specified")

        List<String> noReqParmLocations = baseScreenDef.nestedNoReqParmLocations("", screensToSkip)
        // logger.info("======= rootScreenLocation=${rootScreenLocation}\nbaseScreenPath=${baseScreenPath}\nbaseScreenDef: ${baseScreenDef.location}\nnoReqParmLocations: ${noReqParmLocations}")
        return noReqParmLocations
    }

    @Override
    ScreenTestRender render(String screenPath, Map<String, Object> parameters, String requestMethod) {
        if (!rootScreenLocation) throw new IllegalArgumentException("No rootScreenLocation specified")
        return new ScreenTestRenderImpl(this, screenPath, parameters, requestMethod).render()
    }

    long getRenderCount() { return renderCount }
    long getErrorCount() { return errorCount }
    long getRenderTotalChars() { return totalChars }
    long getStartTime() { return startTime }

    @CompileStatic
    static class ScreenTestRenderImpl implements ScreenTestRender {
        protected final ScreenTestImpl sti
        String screenPath = (String) null
        Map<String, Object> parameters = [:]
        String requestMethod = (String) null

        ScreenRender screenRender = (ScreenRender) null
        String outputString = (String) null
        Object jsonObj = null
        long renderTime = 0
        Map postRenderContext = (Map) null
        protected List<String> errorMessages = []

        ScreenTestRenderImpl(ScreenTestImpl sti, String screenPath, Map<String, Object> parameters, String requestMethod) {
            this.sti = sti
            this.screenPath = screenPath
            if (parameters != null) this.parameters.putAll(parameters)
            this.requestMethod = requestMethod
        }


        ScreenTestRender render() {
            // render in separate thread with an independent ExecutionContext so it doesn't muck up the current one
            ExecutionContextFactoryImpl ecfi = sti.ecfi
            ExecutionContextImpl localEci = ecfi.getEci()
            String username = localEci.userFacade.getUsername()
            Subject loginSubject = localEci.userFacade.getCurrentSubject()
            boolean authzDisabled = localEci.artifactExecutionFacade.getAuthzDisabled()
            ScreenTestRenderImpl stri = this
            Throwable threadThrown = null

            Thread newThread = new Thread("ScreenTestRender") {
                @Override void run() {
                    try {
                        ExecutionContextImpl threadEci = ecfi.getEci()
                        if (loginSubject != null) threadEci.userFacade.internalLoginSubject(loginSubject)
                        else if (username != null && !username.isEmpty()) threadEci.userFacade.internalLoginUser(username)
                        if (authzDisabled) threadEci.artifactExecutionFacade.disableAuthz()
                        // as this is used for server-side transition calls don't do tarpit checks
                        threadEci.artifactExecutionFacade.disableTarpit()
                        renderInternal(threadEci, stri)
                        threadEci.destroy()
                    } catch (Throwable t) {
                        threadThrown = t
                    }
                }
            }
            newThread.start()
            newThread.join()
            if (threadThrown != null) throw threadThrown
            return this
        }
        private static void renderInternal(ExecutionContextImpl eci, ScreenTestRenderImpl stri) {
            ScreenTestImpl sti = stri.sti
            long startTime = System.currentTimeMillis()

            // parse the screenPath
            ArrayList<String> screenPathList = ScreenUrlInfo.parseSubScreenPath(sti.rootScreenDef, sti.baseScreenDef,
                    sti.baseScreenPathList, stri.screenPath, stri.parameters, sti.sfi)
            if (screenPathList == null) throw new BaseArtifactException("Could not find screen path ${stri.screenPath} under base screen ${sti.baseScreenDef.location}")

            // push the context
            ContextStack cs = eci.getContext()
            cs.push()
            // create the WebFacadeStub
            WebFacadeStub wfs = new WebFacadeStub(sti.ecfi, stri.parameters, sti.sessionAttributes, stri.requestMethod)
            // set stub on eci, will also put parameters in the context
            eci.setWebFacade(wfs)
            // make the ScreenRender
            ScreenRender screenRender = sti.sfi.makeRender()
            stri.screenRender = screenRender
            // pass through various settings
            if (sti.rootScreenLocation != null && sti.rootScreenLocation.length() > 0) screenRender.rootScreen(sti.rootScreenLocation)
            if (sti.outputType != null && sti.outputType.length() > 0) screenRender.renderMode(sti.outputType)
            if (sti.characterEncoding != null && sti.characterEncoding.length() > 0) screenRender.encoding(sti.characterEncoding)
            if (sti.macroTemplateLocation != null && sti.macroTemplateLocation.length() > 0) screenRender.macroTemplate(sti.macroTemplateLocation)
            if (sti.baseLinkUrl != null && sti.baseLinkUrl.length() > 0) screenRender.baseLinkUrl(sti.baseLinkUrl)
            if (sti.servletContextPath != null && sti.servletContextPath.length() > 0) screenRender.servletContextPath(sti.servletContextPath)
            screenRender.webappName(sti.webappName)
            if (sti.skipJsonSerialize) wfs.skipJsonSerialize = true

            // set the screenPath
            screenRender.screenPath(screenPathList)

            // do the render
            try {
                screenRender.render(wfs.httpServletRequest, wfs.httpServletResponse)
                // get the response text from the WebFacadeStub
                stri.outputString = wfs.getResponseText()
                stri.jsonObj = wfs.getResponseJsonObj()
            } catch (Throwable t) {
                String errMsg = "Exception in render of ${stri.screenPath}: ${t.toString()}"
                logger.warn(errMsg, t)
                stri.errorMessages.add(errMsg)
                sti.errorCount++
            }
            // calc renderTime
            stri.renderTime = System.currentTimeMillis() - startTime

            // pop the context stack, get rid of var space
            stri.postRenderContext = cs.pop()

            // check, pass through, error messages
            if (eci.message.hasError()) {
                stri.errorMessages.addAll(eci.message.getErrors())
                eci.message.clearErrors()
                StringBuilder sb = new StringBuilder("Error messages from ${stri.screenPath}: ")
                for (String errorMessage in stri.errorMessages) sb.append("\n").append(errorMessage)
                logger.warn(sb.toString())
                sti.errorCount += stri.errorMessages.size()
            }

            // check for error strings in output
            if (stri.outputString != null) for (String errorStr in sti.errorStrings) if (stri.outputString.contains(errorStr)) {
                String errMsg = "Found error [${errorStr}] in output from ${stri.screenPath}"
                stri.errorMessages.add(errMsg)
                sti.errorCount++
                logger.warn(errMsg)
            }

            // update stats
            sti.renderCount++
            if (stri.outputString != null) sti.totalChars += stri.outputString.length()
        }

        @Override ScreenRender getScreenRender() { return screenRender }
        @Override String getOutput() { return outputString }
        @Override Object getJsonObject() { return jsonObj }
        @Override long getRenderTime() { return renderTime }
        @Override Map getPostRenderContext() { return postRenderContext }
        @Override List<String> getErrorMessages() { return errorMessages }

        @Override
        boolean assertContains(String text) {
            if (!outputString) return false
            return outputString.contains(text)
        }
        @Override
        boolean assertNotContains(String text) {
            if (!outputString) return true
            return !outputString.contains(text)
        }
        @Override
        boolean assertRegex(String regex) {
            if (!outputString) return false
            return outputString.matches(regex)
        }
    }
}
