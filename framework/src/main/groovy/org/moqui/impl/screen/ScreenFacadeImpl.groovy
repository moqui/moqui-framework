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
import org.moqui.BaseArtifactException
import org.moqui.resource.ResourceReference
import org.moqui.screen.ScreenFacade
import org.moqui.screen.ScreenRender
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.screen.ScreenDefinition.SubscreensItem
import org.moqui.impl.screen.ScreenDefinition.TransitionItem
import org.moqui.screen.ScreenTest
import org.moqui.util.MNode

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache

@CompileStatic
class ScreenFacadeImpl implements ScreenFacade {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenFacadeImpl.class)

    protected final ExecutionContextFactoryImpl ecfi

    protected final Cache<String, ScreenDefinition> screenLocationCache
    protected final Cache<String, ScreenDefinition> screenLocationPermCache
    // used by ScreenUrlInfo
    final Cache<String, ScreenUrlInfo> screenUrlCache
    protected final Cache<String, List<ScreenInfo>> screenInfoCache
    protected final Cache<String, Set<String>> screenInfoRefRevCache
    protected final Cache<String, Template> screenTemplateModeCache
    protected final Map<String, String> mimeTypeByRenderMode = new HashMap<>()
    protected final Map<String, Boolean> alwaysStandaloneByRenderMode = new HashMap<>()
    protected final Map<String, Boolean> skipActionsByRenderMode = new HashMap<>()
    protected final Cache<String, Template> screenTemplateLocationCache
    protected final Cache<String, MNode> widgetTemplateLocationCache
    protected final Cache<String, ArrayList<String>> screenFindPathCache
    protected final Cache<String, MNode> dbFormNodeByIdCache

    protected final Map<String, ScreenWidgetRender> screenWidgetRenderByMode = new HashMap<>()
    protected final ScreenWidgetRender textMacroWidgetRender = new ScreenWidgetRenderFtl()
    protected final Set<String> textOutputRenderModes = new HashSet<>()
    protected final Set<String> allRenderModes = new HashSet<>()

    protected final Map<String, Map<String, String>> themeIconByTextByTheme = new HashMap<>()

    ScreenFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        screenLocationCache = ecfi.cacheFacade.getCache("screen.location", String.class, ScreenDefinition.class)
        screenLocationPermCache = ecfi.cacheFacade.getCache("screen.location.perm", String.class, ScreenDefinition.class)
        screenUrlCache = ecfi.cacheFacade.getCache("screen.url", String.class, ScreenUrlInfo.class)
        screenInfoCache = ecfi.cacheFacade.getCache("screen.info", String.class, List.class)
        screenInfoRefRevCache = ecfi.cacheFacade.getCache("screen.info.ref.rev", String.class, Set.class)
        screenTemplateModeCache = ecfi.cacheFacade.getCache("screen.template.mode", String.class, Template.class)
        screenTemplateLocationCache = ecfi.cacheFacade.getCache("screen.template.location", String.class, Template.class)
        widgetTemplateLocationCache = ecfi.cacheFacade.getCache("widget.template.location", String.class, MNode.class)
        screenFindPathCache = ecfi.cacheFacade.getCache("screen.find.path", String.class, ArrayList.class)
        dbFormNodeByIdCache = ecfi.cacheFacade.getCache("screen.form.db.node", String.class, MNode.class)

        MNode screenFacadeNode = ecfi.getConfXmlRoot().first("screen-facade")
        ArrayList<MNode> stoNodes = screenFacadeNode.children("screen-text-output")
        for (MNode stoNode in stoNodes) textOutputRenderModes.add(stoNode.attribute("type"))

        ArrayList<MNode> outputNodes = new ArrayList<>(stoNodes)
        ArrayList<MNode> soutNodes = screenFacadeNode.children("screen-output")
        if (soutNodes != null && soutNodes.size() > 0) outputNodes.addAll(soutNodes)
        for (MNode outputNode in outputNodes) {
            String type = outputNode.attribute("type")
            allRenderModes.add(type)
            mimeTypeByRenderMode.put(type, outputNode.attribute("mime-type"))
            alwaysStandaloneByRenderMode.put(type, outputNode.attribute("always-standalone") == "true")
            skipActionsByRenderMode.put(type, outputNode.attribute("skip-actions") == "true")
        }
    }

    ExecutionContextFactoryImpl getEcfi() { return ecfi }

    void warmCache() {
        long startTime = System.currentTimeMillis()
        int screenCount = 0
        for (String rootLocation in getAllRootScreenLocations()) {
            logger.info("Warming cache for all screens under ${rootLocation}")
            ScreenDefinition rootSd = getScreenDefinition(rootLocation)
            screenCount++
            screenCount += warmCacheScreen(rootSd)
        }
        logger.info("Warmed screen definition cache for ${screenCount} screens in ${(System.currentTimeMillis() - startTime)/1000} seconds")
    }
    protected int warmCacheScreen(ScreenDefinition sd) {
        int screenCount = 0
        for (SubscreensItem ssi in sd.subscreensByName.values()) {
            try {
                ScreenDefinition subSd = getScreenDefinition(ssi.getLocation())
                screenCount++
                if (subSd) screenCount += warmCacheScreen(subSd)
            } catch (Throwable t) {
                logger.error("Error loading screen at [${ssi.getLocation()}] during cache warming", t)
            }
        }
        return screenCount
    }

    List<String> getAllRootScreenLocations() {
        List<String> allLocations = []
        for (MNode webappNode in ecfi.confXmlRoot.first("webapp-list").children("webapp")) {
            for (MNode rootScreenNode in webappNode.children("root-screen")) {
                String rootLocation = rootScreenNode.attribute("location")
                allLocations.add(rootLocation)
            }
        }
        return allLocations
    }

    boolean isScreen(String location) {
        if (location == null || location.length() == 0) return false
        if (!location.endsWith(".xml")) return false
        if (screenLocationCache.containsKey(location)) return true

        try {
            // we checked the screenLocationCache above, so now do a quick file parse to see if it is a XML file with 'screen' root
            //     element; this is faster and more reliable when a screen is not loaded, screen doesn't have to be fully valid
            //     which is important as with the old approach if there was an error parsing or compiling the screen it was a false
            //     negative and the screen source would be sent in response
            ResourceReference screenRr = ecfi.resourceFacade.getLocationReference(location)
            MNode screenNode = MNode.parseRootOnly(screenRr)
            return screenNode != null && "screen".equals(screenNode.getName())

            // old approach
            // ScreenDefinition checkSd = getScreenDefinition(location)
            // return (checkSd != null)
        } catch (Throwable t) {
            // ignore the error, just checking to see if it is a screen
            if (logger.isInfoEnabled()) logger.info("Error when checking to see if [${location}] is a XML Screen: ${t.toString()}", t)
            return false
        }
    }

    ScreenDefinition getScreenDefinition(String location) {
        if (location == null || location.length() == 0) return null
        ScreenDefinition sd = (ScreenDefinition) screenLocationCache.get(location)
        if (sd != null) return sd

        return makeScreenDefinition(location)
    }

    protected synchronized ScreenDefinition makeScreenDefinition(String location) {
        ScreenDefinition sd = (ScreenDefinition) screenLocationCache.get(location)
        if (sd != null) return sd

        ResourceReference screenRr = ecfi.resourceFacade.getLocationReference(location)

        ScreenDefinition permSd = (ScreenDefinition) screenLocationPermCache.get(location)
        if (permSd != null) {
            // check to see if file has been modified, if we know when it was last modified
            boolean modified = true
            if (screenRr.supportsLastModified()) {
                long rrLastModified = screenRr.getLastModified()
                modified = permSd.screenLoadedTime < rrLastModified
                // see if any screens it depends on (any extends, etc) have been modified
                if (!modified) {
                    for (String dependLocation in permSd.dependsOnScreenLocations) {
                        ScreenDefinition dependSd = getScreenDefinition(dependLocation)
                        if (dependSd.sourceLastModified == null) { modified = true; break; }
                        if (dependSd.sourceLastModified > permSd.screenLoadedTime) {
                            // logger.info("Screen ${location} depends on ${dependLocation}, modified ${dependSd.sourceLastModified} > ${permSd.screenLoadedTime}")
                            modified = true; break;
                        }
                    }
                }
            }

            if (modified) {
                screenLocationPermCache.remove(location)
                logger.info("Reloading modified screen ${location}")
            } else {
                //logger.warn("========= screen expired but hasn't changed so reusing: ${location}")

                // call this just in case a new screen was added, note this does slow things down just a bit, but only in dev (not in production)
                permSd.populateSubscreens()

                screenLocationCache.put(location, permSd)
                return permSd
            }
        }

        MNode screenNode = MNode.parse(screenRr)
        if (screenNode == null) throw new BaseArtifactException("Could not find definition for screen location ${location}")

        sd = new ScreenDefinition(this, screenNode, location)
        // logger.warn("========= loaded screen [${location}] supports LM ${screenRr.supportsLastModified()}, LM: ${screenRr.getLastModified()}")
        if (screenRr.supportsLastModified()) sd.sourceLastModified = screenRr.getLastModified()
        screenLocationCache.put(location, sd)
        if (screenRr.supportsLastModified()) screenLocationPermCache.put(location, sd)
        return sd
    }

    /** NOTE: this is used in ScreenServices.xml for dynamic form stuff (FormResponse, etc) */
    MNode getFormNode(String location) {
        if (!location) return null
        if (location.contains("#")) {
            String screenLocation = location.substring(0, location.indexOf("#"))
            String formName = location.substring(location.indexOf("#")+1)
            if (screenLocation == "moqui.screen.form.DbForm" || screenLocation == "DbForm") {
                return ScreenForm.getDbFormNode(formName, ecfi)
            } else {
                ScreenDefinition esd = getScreenDefinition(screenLocation)
                ScreenForm esf = esd ? esd.getForm(formName) : null
                return esf?.getOrCreateFormNode()
            }
        } else {
            throw new BaseArtifactException("Must use full form location (with #) to get a form node, [${location}] has no hash (#).")
        }
    }

    boolean isRenderModeValid(String renderMode) { return allRenderModes.contains(renderMode) }
    boolean isRenderModeText(String renderMode) { return textOutputRenderModes.contains(renderMode) }
    boolean isRenderModeAlwaysStandalone(String renderMode) { return alwaysStandaloneByRenderMode.get(renderMode) }
    boolean isRenderModeSkipActions(String renderMode) { return skipActionsByRenderMode.get(renderMode) }
    String getMimeTypeByMode(String renderMode) { return (String) mimeTypeByRenderMode.get(renderMode) }

    Template getTemplateByMode(String renderMode) {
        Template template = (Template) screenTemplateModeCache.get(renderMode)
        if (template != null) return template

        template = makeTemplateByMode(renderMode)
        if (template == null) throw new BaseArtifactException("Could not find screen render template for mode [${renderMode}]")
        return template
    }

    protected synchronized Template makeTemplateByMode(String renderMode) {
        Template template = (Template) screenTemplateModeCache.get(renderMode)
        if (template != null) return template

        MNode stoNode = ecfi.getConfXmlRoot().first("screen-facade")
                .first({ MNode it -> it.name == "screen-text-output" && it.attribute("type") == renderMode })
        String templateLocation = stoNode != null ? stoNode.attribute("macro-template-location") : null
        if (!templateLocation) throw new BaseArtifactException("Could not find macro-template-location for render mode (screen-text-output.@type) [${renderMode}]")
        // NOTE: this is a special case where we need something to call #recurse so that all includes can be straight libraries
        String rootTemplate = """<#include "${templateLocation}"/><#visit widgetsNode>"""

        Template newTemplate
        try {
            newTemplate = new Template("moqui.automatic.${renderMode}", new StringReader(rootTemplate),
                    ecfi.resourceFacade.ftlTemplateRenderer.getFtlConfiguration())
        } catch (Exception e) {
            throw new BaseArtifactException("Error while initializing Screen Widgets template at [${templateLocation}]", e)
        }

        screenTemplateModeCache.put(renderMode, newTemplate)
        return newTemplate
    }

    Template getTemplateByLocation(String templateLocation) {
        Template template = (Template) screenTemplateLocationCache.get(templateLocation)
        if (template != null) return template
        return makeTemplateByLocation(templateLocation)
    }

    protected synchronized Template makeTemplateByLocation(String templateLocation) {
        Template template = (Template) screenTemplateLocationCache.get(templateLocation)
        if (template != null) return template

        // NOTE: this is a special case where we need something to call #recurse so that all includes can be straight libraries
        String rootTemplate = """<#include "${templateLocation}"/><#visit widgetsNode>"""


        Template newTemplate
        try {
            // this location needs to look like a filename in the runtime directory, otherwise FTL will look for includes under the directory it looks like instead
            String filename = templateLocation.substring(templateLocation.lastIndexOf("/")+1)
            newTemplate = new Template(filename, new StringReader(rootTemplate),
                    ecfi.resourceFacade.ftlTemplateRenderer.getFtlConfiguration())
        } catch (Exception e) {
            throw new BaseArtifactException("Error while initializing Screen Widgets template at [${templateLocation}]", e)
        }

        screenTemplateLocationCache.put(templateLocation, newTemplate)
        return newTemplate
    }

    MNode getWidgetTemplatesNodeByLocation(String templateLocation) {
        MNode templatesNode = (MNode) widgetTemplateLocationCache.get(templateLocation)
        if (templatesNode != null) return templatesNode
        return makeWidgetTemplatesNodeByLocation(templateLocation)
    }

    protected synchronized MNode makeWidgetTemplatesNodeByLocation(String templateLocation) {
        MNode templatesNode = (MNode) widgetTemplateLocationCache.get(templateLocation)
        if (templatesNode != null) return templatesNode

        templatesNode = MNode.parse(templateLocation, ecfi.resourceFacade.getLocationStream(templateLocation))
        widgetTemplateLocationCache.put(templateLocation, templatesNode)
        return templatesNode
    }

    ScreenWidgetRender getWidgetRenderByMode(String renderMode) {
        // first try the cache
        ScreenWidgetRender swr = (ScreenWidgetRender) screenWidgetRenderByMode.get(renderMode)
        if (swr != null) return swr
        // special case for text output render modes
        if (textOutputRenderModes.contains(renderMode)) return textMacroWidgetRender
        // try making the ScreenWidgerRender object
        swr = makeWidgetRenderByMode(renderMode)
        if (swr == null) throw new BaseArtifactException("Could not find screen widger renderer for mode ${renderMode}")
        return swr
    }
    protected synchronized ScreenWidgetRender makeWidgetRenderByMode(String renderMode) {
        ScreenWidgetRender swr = (ScreenWidgetRender) screenWidgetRenderByMode.get(renderMode)
        if (swr != null) return swr

        MNode stoNode = ecfi.getConfXmlRoot().first("screen-facade")
                .first({ MNode it -> it.name == "screen-output" && it.attribute("type") == renderMode })
        String renderClass = stoNode != null ? stoNode.attribute("widget-render-class") : null
        if (!renderClass) throw new BaseArtifactException("Could not find widget-render-class for render mode (screen-output.@type) ${renderMode}")

        ScreenWidgetRender newSwr
        try {
            Class swrClass = Thread.currentThread().getContextClassLoader().loadClass(renderClass)
            newSwr = (ScreenWidgetRender) swrClass.newInstance()
        } catch (Exception e) {
            throw new BaseArtifactException("Error while initializing Screen Widgets render class [${renderClass}]", e)
        }

        screenWidgetRenderByMode.put(renderMode, newSwr)
        return newSwr
    }

    Map<String, String> getThemeIconByText(String screenThemeId) {
        Map<String, String> themeIconByText = (Map<String, String>) themeIconByTextByTheme.get(screenThemeId)
        if (themeIconByText == null) {
            themeIconByText = new HashMap<>()
            themeIconByTextByTheme.put(screenThemeId, themeIconByText)
        }
        return themeIconByText
    }
    String rootScreenFromHost(String host, String webappName) {
        ExecutionContextFactoryImpl.WebappInfo webappInfo = ecfi.getWebappInfo(webappName)
        MNode webappNode = webappInfo.webappNode
        MNode wildcardHost = (MNode) null
        for (MNode rootScreenNode in webappNode.children("root-screen")) {
            String hostAttr = rootScreenNode.attribute("host")
            if (".*".equals(hostAttr)) {
                // remember wildcard host, default to it if no other matches (just in case put earlier in the list than others)
                wildcardHost = rootScreenNode
            } else if (host.matches(hostAttr)) {
                return rootScreenNode.attribute("location")
            }
        }
        if (wildcardHost != null) return wildcardHost.attribute("location")
        throw new BaseArtifactException("Could not find root screen for host: ${host}")
    }

    /** Called from ArtifactStats screen */
    List<ScreenInfo> getScreenInfoList(String rootLocation, int levels) {
        ScreenInfo rootInfo = new ScreenInfo(getScreenDefinition(rootLocation), null, null, 0)
        List<ScreenInfo> infoList = []
        infoList.add(rootInfo)
        rootInfo.addChildrenToList(infoList, levels)
        return infoList
    }

    class ScreenInfo implements Serializable {
        ScreenDefinition sd
        SubscreensItem ssi
        ScreenInfo parentInfo
        ScreenInfo rootInfo
        Map<String, ScreenInfo> subscreenInfoByName = new TreeMap()
        Map<String, TransitionInfo> transitionInfoByName = new TreeMap()
        int level
        String name
        ArrayList<String> screenPath = new ArrayList<>()

        boolean isNonPlaceholder = false
        int subscreens = 0, allSubscreens = 0, subscreensNonPlaceholder = 0, allSubscreensNonPlaceholder = 0
        int forms = 0, allSubscreensForms = 0
        int trees = 0, allSubscreensTrees = 0
        int sections = 0, allSubscreensSections = 0
        int transitions = 0, allSubscreensTransitions = 0
        int transitionsWithActions = 0, allSubscreensTransitionsWithActions = 0

        ScreenInfo(ScreenDefinition sd, SubscreensItem ssi, ScreenInfo parentInfo, int level) {
            this.sd = sd
            this.ssi = ssi
            this.parentInfo = parentInfo
            this.level = level
            this.name = ssi ? ssi.getName() : sd.getScreenName()
            if (parentInfo != null) this.screenPath.addAll(parentInfo.screenPath)
            this.screenPath.add(name)

            subscreens = sd.subscreensByName.size()

            forms = sd.formByName.size()
            trees = sd.treeByName.size()
            sections = sd.sectionByName.size()
            transitions = sd.transitionByName.size()
            for (TransitionItem ti in sd.transitionByName.values()) if (ti.hasActionsOrSingleService()) transitionsWithActions++
            isNonPlaceholder = forms > 0 || sections > 0 || transitions > 4
            // if (isNonPlaceholder) logger.info("Screen ${name} forms ${forms} sections ${sections} transitions ${transitions}")

            // trickle up totals
            ScreenInfo curParent = parentInfo
            while (curParent != null) {
                curParent.allSubscreens += 1
                if (isNonPlaceholder) curParent.allSubscreensNonPlaceholder += 1
                curParent.allSubscreensForms += forms
                curParent.allSubscreensTrees += trees
                curParent.allSubscreensSections += sections
                curParent.allSubscreensTransitions += transitions
                curParent.allSubscreensTransitionsWithActions += transitionsWithActions
                if (curParent.parentInfo == null) rootInfo = curParent
                curParent = curParent.parentInfo
            }
            if (rootInfo == null) rootInfo = this

            // get info for all subscreens
            for (Map.Entry<String, SubscreensItem> ssEntry in sd.subscreensByName.entrySet()) {
                SubscreensItem curSsi = ssEntry.getValue()
                List<String> childPath = new ArrayList(screenPath)
                childPath.add(curSsi.getName())
                List<ScreenInfo> curInfoList = (List<ScreenInfo>) screenInfoCache.get(screenPathToString(childPath))
                ScreenInfo existingSi = curInfoList ? (ScreenInfo) curInfoList.get(0) : null
                if (existingSi != null) {
                    subscreenInfoByName.put(ssEntry.getKey(), existingSi)
                } else {
                    ScreenDefinition ssSd = getScreenDefinition(curSsi.getLocation())
                    if (ssSd == null) {
                        logger.info("While getting ScreenInfo screen not found for ${curSsi.getName()} at: ${curSsi.getLocation()}")
                        continue
                    }
                    try {
                        ScreenInfo newSi = new ScreenInfo(ssSd, curSsi, this, level+1)
                        subscreenInfoByName.put(ssEntry.getKey(), newSi)
                    } catch (Exception e) {
                        logger.warn("Error loading subscreen ${curSsi.getLocation()}", e)
                    }
                }
            }

            // populate transition references
            for (Map.Entry<String, TransitionItem> tiEntry in sd.transitionByName.entrySet()) {
                transitionInfoByName.put(tiEntry.getKey(), new TransitionInfo(this, tiEntry.getValue()))
            }

            // now that subscreen is initialized save in list for location and path
            List<ScreenInfo> curInfoList = (List<ScreenInfo>) screenInfoCache.get(sd.location)
            if (curInfoList == null) {
                curInfoList = new LinkedList<>()
                screenInfoCache.put(sd.location, curInfoList)
            }
            curInfoList.add(this)
            screenInfoCache.put(screenPathToString(screenPath), [this])
        }

        String getIndentedName() {
            StringBuilder sb = new StringBuilder()
            for (int i = 0; i < level; i++) sb.append("- ")
            sb.append(" ").append(name)
            return sb.toString()
        }

        void addChildrenToList(List<ScreenInfo> infoList, int maxLevel) {
            for (ScreenInfo si in subscreenInfoByName.values()) {
                infoList.add(si)
                if (maxLevel > level) si.addChildrenToList(infoList, maxLevel)
            }
        }
    }

    class TransitionInfo implements Serializable {
        ScreenInfo si
        TransitionItem ti
        Set<String> responseScreenPathSet = new TreeSet()
        List<String> transitionPath

        TransitionInfo(ScreenInfo si, TransitionItem ti) {
            this.si = si
            this.ti = ti
            transitionPath = si.screenPath
            transitionPath.add(ti.getName())

            for (ScreenDefinition.ResponseItem ri in ti.conditionalResponseList) {
                if (ri.urlType && ri.urlType != "transition" && ri.urlType != "screen") continue
                String expandedUrl = ri.url
                if (expandedUrl.contains('${')) expandedUrl = ecfi.getResource().expand(expandedUrl, "")
                ScreenUrlInfo sui = ScreenUrlInfo.getScreenUrlInfo(ecfi.screenFacade, si.rootInfo.sd,
                        si.sd, si.screenPath, expandedUrl, 0)
                if (sui.targetScreen == null) continue
                String targetScreenPath = screenPathToString(sui.getPreTransitionPathNameList())
                responseScreenPathSet.add(targetScreenPath)

                Set<String> refSet = (Set<String>) screenInfoRefRevCache.get(targetScreenPath)
                if (refSet == null) { refSet = new HashSet(); screenInfoRefRevCache.put(targetScreenPath, refSet) }
                refSet.add(screenPathToString(transitionPath))
            }
        }
    }

    @CompileStatic
    static String screenPathToString(List<String> screenPath) {
        StringBuilder sb = new StringBuilder()
        for (String screenName in screenPath) sb.append("/").append(screenName)
        return sb.toString()
    }

    @Override
    ScreenRender makeRender() { return new ScreenRenderImpl(this) }

    @Override
    ScreenTest makeTest() { return new ScreenTestImpl(ecfi) }
}
