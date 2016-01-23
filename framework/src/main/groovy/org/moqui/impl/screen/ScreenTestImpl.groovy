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
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.moqui.context.ContextStack
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.screen.ScreenRender
import org.moqui.screen.ScreenTest
import org.moqui.screen.ScreenTest.ScreenTestRender
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ScreenTestImpl implements ScreenTest {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenTestImpl.class)

    protected final ExecutionContextFactoryImpl ecfi
    protected final ScreenFacadeImpl sfi
    final List<String> errorStrings = ["FTL stack trace", "Could not find subscreen or transition"]

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
        if (!rootScreenLocation) throw new IllegalStateException("No rootScreen specified")
        baseScreenPath = screenPath
        if (baseScreenPath.endsWith("/")) baseScreenPath = baseScreenPath.substring(0, baseScreenPath.length() - 1)
        if (baseScreenPath) {
            baseScreenPathList = ScreenUrlInfo.parseSubScreenPath(rootScreenDef, rootScreenDef, [], baseScreenPath, null, sfi)
            for (String screenName in baseScreenPathList) {
                ScreenDefinition.SubscreensItem ssi = baseScreenDef.getSubscreensItem(screenName)
                if (ssi == null) throw new IllegalArgumentException("Error in baseScreenPath, could not find ${screenName} under ${baseScreenDef.location}")
                baseScreenDef = sfi.getScreenDefinition(ssi.location)
                if (baseScreenDef == null) throw new IllegalArgumentException("Error in baseScreenPath, could not find screen ${screenName} at ${ssi.location}")
            }
        }
        return this
    }
    @Override
    ScreenTest renderMode(String outputType) { this.outputType = outputType; return this }
    @Override
    ScreenTest encoding(String characterEncoding) { this.characterEncoding = characterEncoding; return this }
    @Override
    ScreenTest macroTemplate(String macroTemplateLocation) { this.macroTemplateLocation = macroTemplateLocation; return this }
    @Override
    ScreenTest baseLinkUrl(String baseLinkUrl) { this.baseLinkUrl = baseLinkUrl; return this }
    @Override
    ScreenTest servletContextPath(String scp) { this.servletContextPath = scp; return this }

    @Override
    @TypeChecked(TypeCheckingMode.SKIP)
    ScreenTest webappName(String wan) {
        webappName = wan

        // set a default root screen based on config for "localhost"
        Node webappNode = (Node) ecfi.confXmlRoot."webapp-list"[0]."webapp".find({ it.@name == webappName })
        for (Object rootScreenObj in (NodeList) webappNode.get("root-screen")) {
            Node rootScreenNode = (Node) rootScreenObj
            if (hostname.matches((String) rootScreenNode.attribute('host'))) {
                String rsLoc = (String) rootScreenNode.attribute('location')
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
        String screenPath = null
        Map<String, Object> parameters = [:]
        String requestMethod = null

        protected ScreenRender screenRender = null
        protected String outputString = null
        protected long renderTime = 0
        protected Map postRenderContext = null
        protected List<String> errorMessages = []

        ScreenTestRenderImpl(ScreenTestImpl sti, String screenPath, Map<String, Object> parameters, String requestMethod) {
            this.sti = sti
            this.screenPath = screenPath
            if (parameters) this.parameters.putAll(parameters)
            this.requestMethod = requestMethod
        }

        ScreenTestRender render() {
            ExecutionContextImpl eci = sti.ecfi.getEci()
            long startTime = System.currentTimeMillis()

            // parse the screenPath
            ArrayList<String> screenPathList = ScreenUrlInfo.parseSubScreenPath(sti.rootScreenDef, sti.baseScreenDef,
                    sti.baseScreenPathList, screenPath, parameters, sti.sfi)

            // push the context
            ContextStack cs = eci.getContext()
            cs.push()
            // create the WebFacadeStub
            WebFacadeStub wfs = new WebFacadeStub(sti.ecfi, parameters, sti.sessionAttributes, requestMethod)
            // set stub on eci, will also put parameters in the context
            eci.setWebFacade(wfs)
            // make the ScreenRender
            screenRender = sti.sfi.makeRender()
            // pass through various settings
            if (sti.rootScreenLocation) screenRender.rootScreen(sti.rootScreenLocation)
            if (sti.outputType) screenRender.renderMode(sti.outputType)
            if (sti.characterEncoding) screenRender.encoding(sti.characterEncoding)
            if (sti.macroTemplateLocation) screenRender.macroTemplate(sti.macroTemplateLocation)
            if (sti.baseLinkUrl) screenRender.baseLinkUrl(sti.baseLinkUrl)
            if (sti.servletContextPath) screenRender.servletContextPath(sti.servletContextPath)
            screenRender.webappName(sti.webappName)

            // set the screenPath
            screenRender.screenPath(screenPathList)

            // do the render
            try {
                screenRender.render(wfs.httpServletRequest, wfs.httpServletResponse)
                // get the response text from the WebFacadeStub
                outputString = wfs.getResponseText()
            } catch (Throwable t) {
                String errMsg = "Exception in render of ${screenPath}: ${t.toString()}"
                logger.warn(errMsg, t)
                errorMessages.add(errMsg)
                sti.errorCount++
            }
            // calc renderTime
            renderTime = System.currentTimeMillis() - startTime

            // pop the context stack, get rid of var space
            postRenderContext = cs.pop()

            // check, pass through, error messages
            if (eci.message.hasError()) {
                errorMessages.addAll(eci.message.getErrors())
                eci.message.clearErrors()
                StringBuilder sb = new StringBuilder("Error messages from ${screenPath}: ")
                for (String errorMessage in errorMessages) sb.append("\n").append(errorMessage)
                logger.warn(sb.toString())
                sti.errorCount += errorMessages.size()
            }

            // check for error strings in output
            if (outputString) for (String errorStr in sti.errorStrings) if (outputString.contains(errorStr)) {
                String errMsg = "Found error [${errorStr}] in output from ${screenPath}"
                errorMessages.add(errMsg)
                sti.errorCount++
                logger.warn(errMsg)
            }

            // update stats
            sti.renderCount++
            if (outputString) sti.totalChars += outputString.length()

            return this
        }

        @Override
        ScreenRender getScreenRender() { return screenRender }
        @Override
        String getOutput() { return outputString }
        @Override
        long getRenderTime() { return renderTime }
        @Override
        Map getPostRenderContext() { return postRenderContext }
        @Override
        List<String> getErrorMessages() { return errorMessages }

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
