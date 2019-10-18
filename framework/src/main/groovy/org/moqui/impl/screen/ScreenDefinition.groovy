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
import org.codehaus.groovy.runtime.InvokerHelper
import org.moqui.BaseArtifactException
import org.moqui.BaseException
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext
import org.moqui.context.ResourceFacade
import org.moqui.impl.context.ContextJavaUtil
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.service.ServiceDefinition
import org.moqui.resource.ResourceReference
import org.moqui.context.WebFacade
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.util.ContextStack
import org.moqui.util.MNode
import org.moqui.util.StringUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.http.HttpServletResponse

@CompileStatic
class ScreenDefinition {
    private final static Logger logger = LoggerFactory.getLogger(ScreenDefinition.class)
    private final static Set<String> scanWidgetNames = new HashSet<String>(
            ['section', 'section-iterate', 'section-include', 'form-single', 'form-list', 'tree', 'subscreens-panel', 'subscreens-menu'])
    private final static Set<String> screenStaticWidgetNames = new HashSet<String>(
            ['subscreens-panel', 'subscreens-menu', 'subscreens-active'])

    @SuppressWarnings("GrFinalVariableAccess") protected final ScreenFacadeImpl sfi
    @SuppressWarnings("GrFinalVariableAccess") protected final MNode screenNode
    @SuppressWarnings("GrFinalVariableAccess") protected final MNode subscreensNode
    @SuppressWarnings("GrFinalVariableAccess") protected final MNode webSettingsNode
    @SuppressWarnings("GrFinalVariableAccess") protected final String location
    @SuppressWarnings("GrFinalVariableAccess") protected final String screenName
    @SuppressWarnings("GrFinalVariableAccess") final long screenLoadedTime
    protected boolean standalone = false
    protected boolean allowExtraPath = false
    protected Set<String> renderModes = null
    protected Set<String> serverStatic = null
    Long sourceLastModified = null

    protected Map<String, ParameterItem> parameterByName = new HashMap<>()
    protected boolean hasRequired = false
    protected Map<String, TransitionItem> transitionByName = new HashMap<>()
    protected Map<String, SubscreensItem> subscreensByName = new HashMap<>()
    protected ArrayList<SubscreensItem> subscreensItemsSorted = null
    protected ArrayList<SubscreensItem> subscreensNoSubPath = null
    protected String defaultSubscreensItem = null

    protected XmlAction alwaysActions = null
    protected XmlAction preActions = null

    protected ScreenSection rootSection = null
    protected Map<String, ScreenSection> sectionByName = new HashMap<>()
    protected Map<String, ScreenForm> formByName = new LinkedHashMap<>()
    protected Map<String, ScreenTree> treeByName = new HashMap<>()
    protected final Set<String> dependsOnScreenLocations = new HashSet<>()
    protected boolean hasTabMenu = false

    protected Map<String, ResourceReference> subContentRefByPath = new HashMap()
    protected Map<String, String> macroTemplateByRenderMode = null

    ScreenDefinition(ScreenFacadeImpl sfi, MNode screenNode, String location) {
        this.sfi = sfi
        this.screenNode = screenNode
        subscreensNode = screenNode.first("subscreens")
        webSettingsNode = screenNode.first("web-settings")
        this.location = location

        ExecutionContextFactoryImpl ecfi = sfi.ecfi

        long startTime = System.currentTimeMillis()
        screenLoadedTime = startTime

        String filename = location.contains("/") ? location.substring(location.lastIndexOf("/")+1) : location
        screenName = filename.contains(".") ? filename.substring(0, filename.indexOf(".")) : filename

        standalone = "true".equals(screenNode.attribute("standalone"))
        allowExtraPath = "true".equals(screenNode.attribute("allow-extra-path"))
        String renderModesStr = screenNode.attribute("render-modes") ?: "all"
        renderModes = new HashSet(Arrays.asList(renderModesStr.split(",")).collect({ it.trim() }))
        String serverStaticStr = screenNode.attribute("server-static")
        if (serverStaticStr) serverStatic = new HashSet(Arrays.asList(serverStaticStr.split(",")).collect({ it.trim() }))

        // parameter
        for (MNode parameterNode in screenNode.children("parameter")) {
            ParameterItem parmItem = new ParameterItem(parameterNode, location, ecfi)
            parameterByName.put(parameterNode.attribute("name"), parmItem)
            if (parmItem.required) hasRequired = true
        }
        // prep always-actions
        if (screenNode.hasChild("always-actions"))
            alwaysActions = new XmlAction(ecfi, screenNode.first("always-actions"), location + ".always_actions")
        // transition
        for (MNode transitionNode in screenNode.children("transition")) {
            TransitionItem ti = new TransitionItem(transitionNode, this)
            transitionByName.put(ti.method == "any" ? ti.name : ti.name + "#" + ti.method, ti)
        }
        // transition-include
        for (MNode transitionInclNode in screenNode.children("transition-include")) {
            ScreenDefinition includeScreen = ecfi.screenFacade.getScreenDefinition(transitionInclNode.attribute("location"))
            if (includeScreen != null) dependsOnScreenLocations.add(includeScreen.location)
            MNode transitionNode = includeScreen?.getTransitionItem(transitionInclNode.attribute("name"), transitionInclNode.attribute("method"))?.transitionNode
            if (transitionNode == null) throw new BaseArtifactException("For transition-include could not find transition ${transitionInclNode.attribute("name")} with method ${transitionInclNode.attribute("method")} in screen at ${transitionInclNode.attribute("location")}")
            TransitionItem ti = new TransitionItem(transitionNode, this)
            transitionByName.put(ti.method == "any" ? ti.name : ti.name + "#" + ti.method, ti)
        }

        // default/automatic transitions
        if (!transitionByName.containsKey("actions")) transitionByName.put("actions", new ActionsTransitionItem(this))
        if (!transitionByName.containsKey("formSelectColumns")) transitionByName.put("formSelectColumns", new FormSelectColumnsTransitionItem(this))
        if (!transitionByName.containsKey("formSaveFind")) transitionByName.put("formSaveFind", new FormSavedFindsTransitionItem(this))
        if (!transitionByName.containsKey("screenDoc")) transitionByName.put("screenDoc", new ScreenDocumentTransitionItem(this))

        // subscreens
        defaultSubscreensItem = subscreensNode?.attribute("default-item")
        populateSubscreens()
        for (SubscreensItem si in getSubscreensItemsSorted()) if (si.noSubPath) {
            if (subscreensNoSubPath == null) subscreensNoSubPath = new ArrayList<>()
            subscreensNoSubPath.add(si)
        }

        // macro-template - go through entire list and set all found, basically we want the last one if there are more than one
        List<MNode> macroTemplateList = screenNode.children("macro-template")
        if (macroTemplateList.size() > 0) {
            macroTemplateByRenderMode = new HashMap<>()
            for (MNode mt in macroTemplateList) macroTemplateByRenderMode.put(mt.attribute('type'), mt.attribute('location'))
        }

        // prep pre-actions
        if (screenNode.hasChild("pre-actions"))
            preActions = new XmlAction(ecfi, screenNode.first("pre-actions"), location + ".pre_actions")

        // get the root section
        rootSection = new ScreenSection(ecfi, screenNode, location + ".screen")

        if (rootSection != null && rootSection.widgets != null) {
            Map<String, ArrayList<MNode>> descMap = rootSection.widgets.widgetsNode.descendants(scanWidgetNames)
            // get all of the other sections by name
            for (MNode sectionNode in descMap.get('section'))
                sectionByName.put(sectionNode.attribute("name"), new ScreenSection(ecfi, sectionNode, "${location}.section\$${sectionNode.attribute("name")}"))
            for (MNode sectionNode in descMap.get('section-iterate'))
                sectionByName.put(sectionNode.attribute("name"), new ScreenSection(ecfi, sectionNode, "${location}.section_iterate\$${sectionNode.attribute("name")}"))
            for (MNode sectionNode in descMap.get('section-include')) pullSectionInclude(sectionNode)

            // get all forms by name
            for (MNode formNode in descMap.get('form-single')) {
                ScreenForm newForm = new ScreenForm(ecfi, this, formNode, "${location}.form_single\$${formNode.attribute("name")}")
                if (newForm.extendsScreenLocation != null) dependsOnScreenLocations.add(newForm.extendsScreenLocation)
                formByName.put(formNode.attribute("name"), newForm)
            }
            for (MNode formNode in descMap.get('form-list')) {
                ScreenForm newForm = new ScreenForm(ecfi, this, formNode, "${location}.form_list\$${formNode.attribute("name")}")
                if (newForm.extendsScreenLocation != null) dependsOnScreenLocations.add(newForm.extendsScreenLocation)
                formByName.put(formNode.attribute("name"), newForm)
            }

            // get all trees by name
            for (MNode treeNode in descMap.get('tree'))
                treeByName.put(treeNode.attribute("name"), new ScreenTree(ecfi, this, treeNode, "${location}.tree\$${treeNode.attribute("name")}"))

            // see if any subscreens-panel or subscreens-menu elements are type=tab (or empty type, defaults to tab)
            for (MNode menuNode in descMap.get("subscreens-panel")) {
                String type = menuNode.attribute("type")
                if (type == null || type.isEmpty() || "tab".equals(type)) { hasTabMenu = true; break }
            }
            if (!hasTabMenu) for (MNode menuNode in descMap.get("subscreens-menu")) {
                String type = menuNode.attribute("type")
                if (type == null || type.isEmpty() || "tab".equals(type)) { hasTabMenu = true; break }
            }

            if (serverStatic == null) {
                // if there are no elements except subscreens-panel, subscreens-active, and subscreens-menu then set serverStatic to all
                boolean otherElements = false
                MNode widgetsNode = rootSection.widgets.widgetsNode
                if (!"widgets".equals(widgetsNode.getName())) widgetsNode = widgetsNode.first("widgets")
                for (MNode child in widgetsNode.getChildren()) {
                    if (!screenStaticWidgetNames.contains(child.getName())) {otherElements = true; break } }
                if (!otherElements) serverStatic = new HashSet<>(['all'])
            }
        }

        if (logger.isTraceEnabled()) logger.trace("Loaded screen at [${location}] in [${(System.currentTimeMillis()-startTime)/1000}] seconds")
    }

    void pullSectionInclude(MNode sectionNode) {
        String location = sectionNode.attribute("location")
        String sectionName = sectionNode.attribute("name")
        if (location.contains('#')) {
            sectionName = location.substring(location.indexOf('#') + 1)
            location = location.substring(0, location.indexOf('#'))
        }

        ScreenDefinition includeScreen = sfi.getEcfi().screenFacade.getScreenDefinition(location)
        ScreenSection includeSection = includeScreen?.getSection(sectionName)
        if (includeSection == null) throw new BaseArtifactException("Could not find section [${sectionNode.attribute("name")} to include at location [${sectionNode.attribute("location")}]")
        sectionByName.put(sectionNode.attribute("name"), includeSection)
        dependsOnScreenLocations.add(location)

        Map<String, ArrayList<MNode>> descMap = includeSection.sectionNode.descendants(
                new HashSet<String>(['section', 'section-iterate', 'section-include', 'form-single', 'form-list', 'tree']))

        // see if the included section contains any SECTIONS, need to reference those here too!
        for (MNode inclRefNode in descMap.get('section'))
            sectionByName.put(inclRefNode.attribute("name"), includeScreen.getSection(inclRefNode.attribute("name")))
        for (MNode inclRefNode in descMap.get('section-iterate'))
            sectionByName.put(inclRefNode.attribute("name"), includeScreen.getSection(inclRefNode.attribute("name")))
        // recurse for section-include
        for (MNode inclRefNode in descMap.get('section-include')) pullSectionInclude(inclRefNode)

        // see if the included section contains any FORMS or TREES, need to reference those here too!
        for (MNode formNode in descMap.get('form-single')) {
            ScreenForm inclForm = includeScreen.getForm(formNode.attribute("name"))
            if (inclForm.extendsScreenLocation != null) dependsOnScreenLocations.add(inclForm.extendsScreenLocation)
            formByName.put(formNode.attribute("name"), inclForm)
        }
        for (MNode formNode in descMap.get('form-list')) {
            ScreenForm inclForm = includeScreen.getForm(formNode.attribute("name"))
            if (inclForm.extendsScreenLocation != null) dependsOnScreenLocations.add(inclForm.extendsScreenLocation)
            formByName.put(formNode.attribute("name"), inclForm)
        }

        for (MNode treeNode in descMap.get('tree'))
            treeByName.put(treeNode.attribute("name"), includeScreen.getTree(treeNode.attribute("name")))
    }

    void populateSubscreens() {
        // start with file/directory structure
        String cleanLocationBase = location.substring(0, location.lastIndexOf("."))
        ResourceReference locationRef = sfi.ecfi.resourceFacade.getLocationReference(location)
        if (logger.traceEnabled) logger.trace("Finding subscreens for screen at [${locationRef}]")
        if (locationRef.supportsAll()) {
            String subscreensDirStr = locationRef.location
            subscreensDirStr = subscreensDirStr.substring(0, subscreensDirStr.lastIndexOf("."))

            ResourceReference subscreensDirRef = sfi.ecfi.resourceFacade.getLocationReference(subscreensDirStr)
            if (subscreensDirRef.exists && subscreensDirRef.isDirectory()) {
                if (logger.traceEnabled) logger.trace("Looking for subscreens in directory [${subscreensDirRef}]")
                for (ResourceReference subscreenRef in subscreensDirRef.directoryEntries) {
                    if (!subscreenRef.isFile() || !subscreenRef.location.endsWith(".xml")) continue
                    MNode subscreenRoot = MNode.parse(subscreenRef)
                    if (subscreenRoot.name == "screen") {
                        String ssName = subscreenRef.getFileName()
                        ssName = ssName.substring(0, ssName.lastIndexOf("."))
                        String cleanLocation = cleanLocationBase + "/" + subscreenRef.getFileName()
                        SubscreensItem si = new SubscreensItem(ssName, cleanLocation, subscreenRoot, this)
                        subscreensByName.put(si.name, si)
                        if (logger.traceEnabled) logger.trace("Added file subscreen [${si.name}] at [${si.location}] to screen [${locationRef}]")
                    }
                }
            }
        } else {
            logger.info("Not getting subscreens by file/directory structure for screen [${location}] because it is not a location that supports directories")
        }

        // override dir structure with subscreens.subscreens-item elements
        if (screenNode.hasChild("subscreens")) for (MNode subscreensItem in screenNode.first("subscreens").children("subscreens-item")) {
            SubscreensItem si = new SubscreensItem(subscreensItem, this)
            subscreensByName.put(si.name, si)
            if (logger.traceEnabled) logger.trace("Added Screen XML defined subscreen [${si.name}] at [${si.location}] to screen [${locationRef}]")
        }

        // override dir structure and screen.subscreens.subscreens-item elements with Moqui Conf XML screen-facade.screen.subscreens-item elements
        MNode screenFacadeNode = sfi.ecfi.confXmlRoot.first("screen-facade")
        MNode confScreenNode = screenFacadeNode.first("screen", "location", location)
        if (confScreenNode != null) {
            for (MNode subscreensItem in confScreenNode.children("subscreens-item")) {
                SubscreensItem si = new SubscreensItem(subscreensItem, this)
                subscreensByName.put(si.name, si)
                if (logger.traceEnabled) logger.trace("Added Moqui Conf XML defined subscreen [${si.name}] at [${si.location}] to screen [${locationRef}]")
            }
            if (confScreenNode.attribute("default-subscreen"))
                defaultSubscreensItem = confScreenNode.attribute("default-subscreen")
        }

        // override dir structure and subscreens-item elements with moqui.screen.SubscreensItem entity
        EntityFind subscreensItemFind = sfi.ecfi.entityFacade.find("moqui.screen.SubscreensItem")
                .condition([screenLocation:location] as Map<String, Object>)
        // NOTE: this filter should NOT be done here, causes subscreen items to be filtered by first user that renders the screen, not by current user!
        // subscreensItemFind.condition("userGroupId", EntityCondition.IN, sfi.ecfi.executionContext.user.userGroupIdSet)
        EntityList subscreensItemList = subscreensItemFind.useCache(true).disableAuthz().list()
        for (EntityValue subscreensItem in subscreensItemList) {
            SubscreensItem si = new SubscreensItem(subscreensItem, this)
            subscreensByName.put(si.name, si)
            if ("Y".equals(subscreensItem.makeDefault)) defaultSubscreensItem = si.name
            if (logger.traceEnabled) logger.trace("Added database subscreen [${si.name}] at [${si.location}] to screen [${locationRef}]")
        }
    }

    MNode getScreenNode() { return screenNode }
    MNode getSubscreensNode() { return subscreensNode }
    MNode getWebSettingsNode() { return webSettingsNode }
    String getLocation() { return location }

    String getDefaultSubscreensItem() { return defaultSubscreensItem }
    ArrayList<SubscreensItem> getSubscreensNoSubPath() { return subscreensNoSubPath }

    String getScreenName() { return screenName }
    boolean isStandalone() { return standalone }
    boolean isServerStatic(String renderMode) { return serverStatic != null && (serverStatic.contains('all') || serverStatic.contains(renderMode)) }

    String getDefaultMenuName() { return getPrettyMenuName(screenNode.attribute("default-menu-title"), location, sfi.ecfi) }
    static String getPrettyMenuName(String menuName, String location, ExecutionContextFactoryImpl ecfi) {
        if (menuName == null || menuName.isEmpty()) {
            String filename = location.substring(location.lastIndexOf("/")+1, location.length()-4)
            StringBuilder prettyName = new StringBuilder()
            for (String part in filename.split("(?=[A-Z])")) {
                if (prettyName) prettyName.append(" ")
                prettyName.append(part)
            }
            char firstChar = prettyName.charAt(0)
            if (Character.isLowerCase(firstChar)) prettyName.setCharAt(0, Character.toUpperCase(firstChar))
            menuName = prettyName.toString()
        }

        return ecfi.getEci().l10nFacade.localize(menuName)
    }

    /** Get macro template location specific to screen from marco-template elements */
    String getMacroTemplateLocation(String renderMode) {
        if (macroTemplateByRenderMode == null) return null
        return (String) macroTemplateByRenderMode.get(renderMode)
    }

    Map<String, ParameterItem> getParameterMap() { return parameterByName }
    boolean hasRequiredParameters() { return hasRequired }
    boolean hasTabMenu() { return hasTabMenu }

    XmlAction getPreActions() { return preActions }
    XmlAction getAlwaysActions() { return alwaysActions }

    boolean hasTransition(String name) {
        for (TransitionItem curTi in transitionByName.values()) if (curTi.name == name) return true
        return false
    }

    TransitionItem getTransitionItem(String name, String method) {
        method = method != null ? method.toLowerCase() : ""
        TransitionItem ti = (TransitionItem) transitionByName.get(name.concat("#").concat(method))
        // if no ti, try by name only which will catch transitions with "any" or empty method
        if (ti == null) ti = (TransitionItem) transitionByName.get(name)
        // still none? try each one to see if it matches as a regular expression (first one to match wins)
        if (ti == null) for (TransitionItem curTi in transitionByName.values()) {
            if (method != null && !method.isEmpty() && ("any".equals(curTi.method) || method.equals(curTi.method))) {
                if (name.equals(curTi.name)) { ti = curTi; break }
                if (name.matches(curTi.name)) { ti = curTi; break }
            }
            // logger.info("In getTransitionItem() transition with name [${curTi.name}] method [${curTi.method}] did not match name [${name}] method [${method}]")
        }
        return ti
    }

    Collection<TransitionItem> getAllTransitions() { return transitionByName.values() }

    SubscreensItem getSubscreensItem(String name) { return (SubscreensItem) subscreensByName.get(name) }

    ArrayList<String> findSubscreenPath(ArrayList<String> remainingPathNameList) {
        if (!remainingPathNameList) return null
        String curName = remainingPathNameList.get(0)
        SubscreensItem curSsi = getSubscreensItem(curName)
        if (curSsi != null) {
            if (remainingPathNameList.size() > 1) {
                ArrayList<String> subPathNameList = new ArrayList<>(remainingPathNameList)
                subPathNameList.remove(0)
                try {
                    ScreenDefinition subSd = sfi.getScreenDefinition(curSsi.getLocation())
                    ArrayList<String> subPath = subSd.findSubscreenPath(subPathNameList)
                    if (!subPath) return null
                    subPath.add(0, curName)
                    return subPath
                } catch (Exception e) {
                    logger.error("Error finding subscreens under screen at ${curSsi.getLocation()}", BaseException.filterStackTrace(e))
                    return null
                }
            } else {
                return remainingPathNameList
            }
        }

        // if this is a transition right under this screen use it before searching subscreens
        if (hasTransition(curName)) return remainingPathNameList

        // breadth first by looking at subscreens of each subscreen on a first pass
        for (Map.Entry<String, SubscreensItem> entry in subscreensByName.entrySet()) {
            ScreenDefinition subSd = null
            try {
                subSd = sfi.getScreenDefinition(entry.getValue().getLocation())
            } catch (Exception e) {
                logger.error("Error finding subscreens under screen ${entry.key} at ${entry.getValue().getLocation()}", BaseException.filterStackTrace(e))
            }
            if (subSd == null) {
                if (logger.isTraceEnabled()) logger.trace("Screen ${entry.getKey()} at ${entry.getValue().getLocation()} not found, subscreen of [${this.getLocation()}]")
                continue
            }
            SubscreensItem subSsi = subSd.getSubscreensItem(curName)
            if (subSsi != null) {
                if (remainingPathNameList.size() > 1) {
                    // if there are still more path elements, recurse to find them
                    ArrayList<String> subPathNameList = new ArrayList<>(remainingPathNameList)
                    subPathNameList.remove(0)
                    ScreenDefinition subSubSd = sfi.getScreenDefinition(subSsi.getLocation())
                    ArrayList<String> subPath = subSubSd.findSubscreenPath(subPathNameList)
                    // found a partial match, not the full thing, no match so give up
                    if (!subPath) return null
                    // we've found it two deep, add both names, sub name first
                    subPath.add(0, curName)
                    subPath.add(0, entry.getKey())
                    return subPath
                } else {
                    return new ArrayList<String>([entry.getKey(), curName])
                }
            }
        }
        // not immediate child or grandchild subscreen, start recursion
        for (Map.Entry<String, SubscreensItem> entry in subscreensByName.entrySet()) {
            ScreenDefinition subSd = null
            try {
                subSd = sfi.getScreenDefinition(entry.getValue().getLocation())
            } catch (Exception e) {
                logger.error("Error finding subscreens under screen ${entry.key} at ${entry.getValue().getLocation()}", BaseException.filterStackTrace(e))
            }
            if (subSd == null) {
                if (logger.isTraceEnabled()) logger.trace("Screen ${entry.getKey()} at ${entry.getValue().getLocation()} not found, subscreen of [${this.getLocation()}]")
                continue
            }
            List<String> subPath = subSd.findSubscreenPath(remainingPathNameList)
            if (subPath) {
                subPath.add(0, entry.getKey())
                return subPath
            }
        }

        // is this a resource (file) under the screen?
        ResourceReference existingFileRef = getSubContentRef(remainingPathNameList)
        if (existingFileRef && existingFileRef.supportsExists() && existingFileRef.exists) {
            return remainingPathNameList
        }

        /* Used mainly for transition responses where the final path element is a screen, transition, or resource with
            no extra path elements; allowing extra path elements causes problems only solvable by first searching without
            allowing extra path elements, then searching the full tree for all possible paths that include extra elements
            and choosing the maximal match (highest number of original sparse path elements matching actual screens)
        if (allowExtraPath) { return remainingPathNameList }
        */

        // nothing found, return null by default
        return null
    }

    List<String> nestedNoReqParmLocations(String currentPath, Set<String> screensToSkip) {
        if (!screensToSkip) screensToSkip = new HashSet<String>()
        List<String> locList = []
        List<SubscreensItem> ssiList = getSubscreensItemsSorted()
        for (SubscreensItem ssi in ssiList) {
            if (screensToSkip.contains(ssi.name)) continue
            try {
                ScreenDefinition subSd = sfi.getScreenDefinition(ssi.location)
                if (!subSd.hasRequiredParameters()) {
                    String subPath = (currentPath ? currentPath + "/" : '') + ssi.name
                    // don't add current if it a has a default subscreen item
                    if (!subSd.getDefaultSubscreensItem()) locList.add(subPath)
                    locList.addAll(subSd.nestedNoReqParmLocations(subPath, screensToSkip))
                }
            } catch (Exception e) {
                logger.error("Error finding no parameter screens under ${this.location} for subscreen location ${ssi.location}", e)
            }
        }
        return locList
    }

    ArrayList<SubscreensItem> getSubscreensItemsSorted() {
        if (subscreensItemsSorted != null) return subscreensItemsSorted
        ArrayList<SubscreensItem> newList = new ArrayList(subscreensByName.size())
        if (subscreensByName.size() == 0) return newList
        newList.addAll(subscreensByName.values())
        Collections.sort(newList, new SubscreensItemComparator())
        return subscreensItemsSorted = newList
    }

    ArrayList<SubscreensItem> getMenuSubscreensItems() {
        ArrayList<SubscreensItem> allItems = getSubscreensItemsSorted()
        int allItemSize = allItems.size()
        ArrayList<SubscreensItem> filteredList = new ArrayList(allItemSize)

        for (int i = 0; i < allItemSize; i++) {
            SubscreensItem si = (SubscreensItem) allItems.get(i)
            // check the menu include flag
            if (!si.menuInclude) continue
            // valid in current context? (user group, etc)
            if (!si.isValidInCurrentContext()) continue
            // made it through the checks? add it in...
            filteredList.add(si)
        }

        return filteredList
    }

    ScreenSection getRootSection() { return rootSection }
    void render(ScreenRenderImpl sri, boolean isTargetScreen) {
        // NOTE: don't require authz if the screen doesn't require auth
        String requireAuthentication = screenNode.attribute("require-authentication")
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(location,
                ArtifactExecutionInfo.AT_XML_SCREEN, ArtifactExecutionInfo.AUTHZA_VIEW, sri.outputContentType)
        if ("false".equals(screenNode.attribute("track-artifact-hit"))) aei.setTrackArtifactHit(false)
        sri.ec.artifactExecutionFacade.pushInternal(aei, isTargetScreen ?
                (requireAuthentication == null || requireAuthentication.length() == 0 || "true".equals(requireAuthentication)) : false, true)

        boolean loggedInAnonymous = false
        if ("anonymous-all".equals(requireAuthentication)) {
            sri.ec.artifactExecutionFacade.setAnonymousAuthorizedAll()
            loggedInAnonymous = sri.ec.userFacade.loginAnonymousIfNoUser()
        } else if ("anonymous-view".equals(requireAuthentication)) {
            sri.ec.artifactExecutionFacade.setAnonymousAuthorizedView()
            loggedInAnonymous = sri.ec.userFacade.loginAnonymousIfNoUser()
        }

        // logger.info("Rendering screen ${location}, screenNode: \n${screenNode}")

        try {
            rootSection.render(sri)
        } finally {
            sri.ec.artifactExecutionFacade.pop(aei)
            if (loggedInAnonymous) sri.ec.userFacade.logoutAnonymousOnly()
        }
    }

    ScreenSection getSection(String sectionName) {
        ScreenSection ss = sectionByName.get(sectionName)
        if (ss == null) throw new BaseArtifactException("Could not find section ${sectionName} in screen ${getLocation()}")
        return ss
    }
    ScreenForm getForm(String formName) {
        ScreenForm sf = formByName.get(formName)
        if (sf == null) throw new BaseArtifactException("Could not find form ${formName} in screen ${getLocation()}")
        return sf
    }
    ArrayList<ScreenForm> getAllForms() { return new ArrayList<>(formByName.values()) }
    ScreenTree getTree(String treeName) {
        ScreenTree st = treeByName.get(treeName)
        if (st == null) throw new BaseArtifactException("Could not find tree ${treeName} in screen ${getLocation()}")
        return st
    }

    ResourceReference getSubContentRef(List<String> pathNameList) {
        StringBuilder pathNameBldr = new StringBuilder()
        // add the path elements that remain
        for (String rp in pathNameList) pathNameBldr.append("/").append(rp)
        String pathName = pathNameBldr.toString()

        ResourceReference contentRef = subContentRefByPath.get(pathName)
        if (contentRef != null) return contentRef

        ResourceReference lastScreenRef = sfi.ecfi.resourceFacade.getLocationReference(location)
        if (lastScreenRef.supportsAll()) {
            // NOTE: this caches internally so consider getting rid of subContentRefByPath
            contentRef = lastScreenRef.findChildFile(pathName)
        } else {
            logger.info("Not looking for sub-content [${pathName}] under screen [${location}] because screen location does not support exists, isFile, etc")
        }

        if (contentRef != null) subContentRefByPath.put(pathName, contentRef)
        return contentRef
    }

    List<Map<String, Object>> getScreenDocumentInfoList() {
        String localeString = sfi.ecfi.getEci().userFacade.getLocale().toString()
        int localeUnderscoreIndex = localeString.indexOf('_')
        String langString = null
        // look for locale match, lang only match, or null
        if (localeUnderscoreIndex > 0) langString = localeString.substring(0, localeUnderscoreIndex)

        // do very simple cached query for all, then filter in iterator by locale
        EntityList list = sfi.ecfi.entityFacade.find("moqui.screen.ScreenDocument").condition("screenLocation", location)
                .orderBy("docIndex").useCache(true).disableAuthz().list()
        int listSize = list.size()

        List<Map<String, Object>> outList = new ArrayList<>(listSize)
        for (int i = 0; i < listSize; i++) {
            EntityValue screenDoc = (EntityValue) list.get(i)
            String docLocale = screenDoc.getNoCheckSimple("locale")
            if (docLocale != null && (!localeString.equals(docLocale) || (langString != null && !langString.equals(docLocale)))) continue
            String title = screenDoc.getNoCheckSimple("docTitle")
            if (title == null) {
                String loc = screenDoc.getNoCheckSimple("docLocation")
                int fnStart = loc.lastIndexOf("/") + 1
                if (fnStart == -1) fnStart = 0
                int fnEnd = loc.indexOf(".", fnStart)
                if (fnEnd == -1) fnEnd = loc.length()
                title = loc.substring(fnStart, fnEnd)
            }
            outList.add([title:title, index:(Long) screenDoc.getNoCheckSimple("docIndex")] as Map<String, Object>)
        }
        return outList
    }

    @Override
    String toString() { return location }

    @CompileStatic
    static class ParameterItem {
        protected String name
        protected Class fromFieldGroovy = null
        protected String valueString = null
        protected Class valueGroovy = null
        protected boolean required = false

        ParameterItem(MNode parameterNode, String location, ExecutionContextFactoryImpl ecfi) {
            this.name = parameterNode.attribute("name")
            if (parameterNode.attribute("required") == "true") required = true

            if (parameterNode.attribute("from")) fromFieldGroovy = ecfi.getGroovyClassLoader().parseClass(
                    parameterNode.attribute("from"), StringUtilities.cleanStringForJavaName("${location}.parameter_${name}.from_field"))

            valueString = parameterNode.attribute("value")
            if (valueString != null && valueString.length() == 0) valueString = null
            if (valueString != null && valueString.contains('${')) {
                valueGroovy = ecfi.getGroovyClassLoader().parseClass(('"""' + parameterNode.attribute("value") + '"""'),
                        StringUtilities.cleanStringForJavaName("${location}.parameter_${name}.value"))
            }
        }
        String getName() { return name }
        Object getValue(ExecutionContext ec) {
            Object value = null
            if (fromFieldGroovy != null) { value = InvokerHelper.createScript(fromFieldGroovy, ec.contextBinding).run() }
            if (value == null) {
                if (valueGroovy != null) { value = InvokerHelper.createScript(valueGroovy, ec.contextBinding).run() }
                else { value = valueString }
            }
            if (value == null) value = ec.context.getByString(name)
            if (value == null && ec.web != null) value = ec.web.parameters.get(name)
            return value
        }
    }

    @CompileStatic
    static class TransitionItem {
        protected ScreenDefinition parentScreen
        protected MNode transitionNode

        protected String name
        protected String method
        protected String location
        protected XmlAction condition = null
        protected XmlAction actions = null
        protected XmlAction serviceActions = null
        protected String singleServiceName = null

        protected Map<String, ParameterItem> parameterByName = new HashMap()
        protected List<String> pathParameterList = null

        protected List<ResponseItem> conditionalResponseList = new ArrayList<ResponseItem>()
        protected ResponseItem defaultResponse = null
        protected ResponseItem errorResponse = null

        protected boolean beginTransaction = true
        protected boolean readOnly = false
        protected boolean requireSessionToken = true

        protected TransitionItem(ScreenDefinition parentScreen) { this.parentScreen = parentScreen }

        TransitionItem(MNode transitionNode, ScreenDefinition parentScreen) {
            this.parentScreen = parentScreen
            this.transitionNode = transitionNode
            name = transitionNode.attribute("name")
            method = transitionNode.attribute("method") ?: "any"
            location = "${parentScreen.location}.transition\$${StringUtilities.cleanStringForJavaName(name)}"
            beginTransaction = transitionNode.attribute("begin-transaction") != "false"
            requireSessionToken = transitionNode.attribute("require-session-token") != "false"

            ExecutionContextFactoryImpl ecfi = parentScreen.sfi.ecfi
            // parameter
            for (MNode parameterNode in transitionNode.children("parameter"))
                parameterByName.put(parameterNode.attribute("name"), new ParameterItem(parameterNode, location, ecfi))
            // path-parameter
            if (transitionNode.hasChild("path-parameter")) {
                pathParameterList = new ArrayList()
                for (MNode pathParameterNode in transitionNode.children("path-parameter"))
                    pathParameterList.add(pathParameterNode.attribute("name"))
            }

            // condition
            if (transitionNode.first("condition")?.first() != null) {
                // the script is effectively the first child of the condition element
                condition = new XmlAction(parentScreen.sfi.ecfi, transitionNode.first("condition").first(), location + ".condition")
            }
            // allow both call-service and actions
            if (transitionNode.hasChild("actions")) {
                actions = new XmlAction(parentScreen.sfi.ecfi, transitionNode.first("actions"), location + ".actions")
            }
            if (transitionNode.hasChild("service-call")) {
                MNode callServiceNode = transitionNode.first("service-call")
                if (!callServiceNode.attribute("in-map")) callServiceNode.attributes.put("in-map", "true")
                if (!callServiceNode.attribute("out-map")) callServiceNode.attributes.put("out-map", "context")
                if (!callServiceNode.attribute("multi") && !"true".equals(callServiceNode.attribute("async")))
                    callServiceNode.attributes.put("multi", "parameter")
                serviceActions = new XmlAction(parentScreen.sfi.ecfi, callServiceNode, location + ".service_call")
                singleServiceName = callServiceNode.attribute("name")
            }

            readOnly = (actions == null && serviceActions == null) || transitionNode.attribute("read-only") == "true"

            // conditional-response*
            for (MNode condResponseNode in transitionNode.children("conditional-response"))
                conditionalResponseList.add(new ResponseItem(condResponseNode, this, parentScreen))
            // default-response
            defaultResponse = new ResponseItem(transitionNode.first("default-response"), this, parentScreen)
            // error-response
            if (transitionNode.hasChild("error-response"))
                errorResponse = new ResponseItem(transitionNode.first("error-response"), this, parentScreen)
        }

        String getName() { return name }
        String getMethod() { return method }
        String getSingleServiceName() { return singleServiceName }
        List<String> getPathParameterList() { return pathParameterList }
        Map<String, ParameterItem> getParameterMap() { return parameterByName }
        boolean hasActionsOrSingleService() { return actions != null || serviceActions != null}
        boolean getBeginTransaction() { return beginTransaction }
        boolean isReadOnly() { return readOnly }
        boolean getRequireSessionToken() { return requireSessionToken }

        boolean checkCondition(ExecutionContextImpl ec) { return condition ? condition.checkCondition(ec) : true }

        void setAllParameters(List<String> extraPathNameList, ExecutionContextImpl ec) {
            // get the path parameters
            if (extraPathNameList && getPathParameterList()) {
                List<String> pathParameterList = getPathParameterList()
                int i = 0
                for (String extraPathName in extraPathNameList) {
                    if (pathParameterList.size() > i) {
                        // logger.warn("extraPathName ${extraPathName} i ${i} name ${pathParameterList.get(i)}")
                        if (ec.webImpl != null) ec.webImpl.addDeclaredPathParameter(pathParameterList.get(i), extraPathName)
                        ec.getContext().put(pathParameterList.get(i), extraPathName)
                        i++
                    } else {
                        break
                    }
                }
            }

            // put parameters in the context
            if (ec.getWeb() != null) {
                // screen parameters
                for (ParameterItem pi in parentScreen.getParameterMap().values()) {
                    Object value = pi.getValue(ec)
                    if (value != null) ec.contextStack.put(pi.getName(), value)
                }
                // transition parameters
                for (ParameterItem pi in parameterByName.values()) {
                    Object value = pi.getValue(ec)
                    if (value != null) ec.contextStack.put(pi.getName(), value)
                }
            }
        }

        ResponseItem run(ScreenRenderImpl sri) {
            ExecutionContextImpl ec = sri.ec

            // NOTE: if parent screen of transition does not require auth, don't require authz
            // NOTE: use the View authz action to leave it open, ie require minimal authz; restrictions are often more
            //    in the services/etc if/when needed, or specific transitions can have authz settings
            String requireAuthentication = (String) parentScreen.screenNode.attribute('require-authentication')
            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl("${parentScreen.location}/${name}",
                    ArtifactExecutionInfo.AT_XML_SCREEN_TRANS, ArtifactExecutionInfo.AUTHZA_VIEW, sri.outputContentType)
            ec.artifactExecutionFacade.pushInternal(aei, (!requireAuthentication || "true".equals(requireAuthentication)), true)

            boolean loggedInAnonymous = false
            if (requireAuthentication == "anonymous-all") {
                ec.artifactExecutionFacade.setAnonymousAuthorizedAll()
                loggedInAnonymous = ec.userFacade.loginAnonymousIfNoUser()
            } else if (requireAuthentication == "anonymous-view") {
                ec.artifactExecutionFacade.setAnonymousAuthorizedView()
                loggedInAnonymous = ec.userFacade.loginAnonymousIfNoUser()
            }

            try {
                ScreenUrlInfo screenUrlInfo = sri.getScreenUrlInfo()
                ScreenUrlInfo.UrlInstance screenUrlInstance = sri.getScreenUrlInstance()
                setAllParameters(screenUrlInfo.getExtraPathNameList(), ec)
                // for alias transitions rendered in-request put the parameters in the context
                if (screenUrlInstance.getTransitionAliasParameters()) ec.contextStack.putAll(screenUrlInstance.getTransitionAliasParameters())


                if (!checkCondition(ec)) {
                    sri.ec.message.addError(ec.resource.expand('Condition failed for transition [${location}], not running actions or redirecting','',[location:location]))
                    if (errorResponse) return errorResponse
                    return defaultResponse
                }

                // don't push a map on the context, let the transition actions set things that will remain: sri.ec.context.push()
                ec.contextStack.put("sri", sri)
                // logger.warn("Running transition ${name} context: ${ec.contextStack.toString()}")
                if (serviceActions != null) {
                    // if this is an implicit entity auto service filter input for HTML like done in defined service calls by default;
                    //     to get around define a service with a parameter that allows safe or any HTML instead of using implicit entity auto directly
                    if (ec.serviceFacade.isEntityAutoPattern(singleServiceName)) {
                        String entityName = ServiceDefinition.getNounFromName(singleServiceName)
                        EntityDefinition ed = ec.entityFacade.getEntityDefinition(entityName)
                        if (ed != null) {
                            ArrayList<String> fieldNameList = ed.getAllFieldNames()
                            int fieldNameListSize = fieldNameList.size()
                            for (int i = 0; i < fieldNameListSize; i++) {
                                String fieldName = (String) fieldNameList.get(i)
                                Object fieldValue = ec.contextStack.getByString(fieldName)
                                if (fieldValue instanceof CharSequence) {
                                    String fieldString = fieldValue.toString()
                                    if (fieldString.contains("<")) {
                                        ec.messageFacade.addValidationError(null, fieldName, singleServiceName,
                                                ec.getL10n().localize("HTML not allowed including less-than (<), greater-than (>), etc symbols"), null)
                                    }
                                }
                            }
                        }
                    }
                    if (!ec.messageFacade.hasError()) {
                        serviceActions.run(ec)
                    }
                }
                // run actions if any defined, even if service-call also used
                // NOTE: prior code also required !ec.messageFacade.hasError() which doesn't allow actions to handle errors
                if (actions != null) {
                    actions.run(ec)
                }

                ResponseItem ri = null
                // if there is an error-response and there are errors, we have a winner
                if (ec.messageFacade.hasError() && errorResponse) ri = errorResponse

                // check all conditional-response, if condition then return that response
                if (ri == null) for (ResponseItem condResp in conditionalResponseList) {
                    if (condResp.checkCondition(ec)) ri = condResp
                }
                // no errors, no conditionals, return default
                if (ri == null) ri = defaultResponse

                return ri
            } finally {
                // don't pop the context until after evaluating conditions so that data set in the actions can be used
                // don't pop the context at all, see note above about push: sri.ec.context.pop()

                // all done so pop the artifact info; don't bother making sure this is done on errors/etc like in a finally
                // clause because if there is an error this will help us know how we got there
                ec.artifactExecutionFacade.pop(aei)
                if (loggedInAnonymous) ec.userFacade.logoutAnonymousOnly()
            }
        }
    }

    static class ActionsTransitionItem extends TransitionItem {
        ActionsTransitionItem(ScreenDefinition parentScreen) {
            super(parentScreen)
            name = "actions"; method = "any"; location = "${parentScreen.location}.transition\$${name}"
            transitionNode = null; beginTransaction = true; readOnly = true; requireSessionToken = false
            defaultResponse = new ResponseItem(new MNode("default-response", [type:"none"]), this, parentScreen)
        }

        // NOTE: runs pre-actions too, see sri.recursiveRunTransition() call in sri.internalRender()
        ResponseItem run(ScreenRenderImpl sri) {
            ExecutionContextImpl ec = sri.ec
            ContextStack context = ec.contextStack
            context.put("sri", sri)
            WebFacade wf = ec.getWeb()
            if (wf == null) throw new BaseArtifactException("Cannot run actions transition outside of a web request")

            ArrayList<String> extraPathList = sri.screenUrlInfo.extraPathNameList
            if (extraPathList != null && extraPathList.size() > 0) {
                String partName = (String) extraPathList.get(0)
                // is it a form or tree?
                ScreenForm form = parentScreen.formByName.get(partName)
                if (form != null) {
                    if (!form.hasDataPrep()) throw new BaseArtifactException("Found form ${partName} in screen ${parentScreen.getScreenName()} but it does not have its own data preparation")
                    ScreenForm.FormInstance formInstance = form.getFormInstance()
                    if (formInstance.isList()) {
                        ScreenForm.FormListRenderInfo renderInfo = formInstance.makeFormListRenderInfo()
                        Object listObj = renderInfo.getListObject(true)

                        HttpServletResponse response = wf.response
                        String listName = formInstance.formNode.attribute("list")
                        if (context.get(listName.concat("Count")) != null) {
                            response.addIntHeader('X-Total-Count', context.get(listName.concat("Count")) as int)
                            response.addIntHeader('X-Page-Index', context.get(listName.concat("PageIndex")) as int)
                            response.addIntHeader('X-Page-Size', context.get(listName.concat("PageSize")) as int)
                            response.addIntHeader('X-Page-Max-Index', context.get(listName.concat("PageMaxIndex")) as int)
                            response.addIntHeader('X-Page-Range-Low', context.get(listName.concat("PageRangeLow")) as int)
                            response.addIntHeader('X-Page-Range-High', context.get(listName.concat("PageRangeHigh")) as int)
                        }

                        wf.sendJsonResponse(listObj)
                    }
                    // TODO: else support form-single data prep once something is added
                } else {
                    ScreenTree tree = parentScreen.treeByName.get(partName)
                    if (tree != null) {
                        tree.sendSubNodeJson()
                    } else {
                        throw new BaseArtifactException("Could not find form or tree named ${partName} in screen ${parentScreen.getScreenName()} so cannot run its actions")
                    }
                }
            } else {
                // run actions (if there are any)
                XmlAction actions = parentScreen.rootSection.actions
                if (actions != null) {
                    actions.run(ec)
                    // use entire ec.context to get values from always-actions and pre-actions
                    wf.sendJsonResponse(ContextJavaUtil.unwrapMap(context))
                } else {
                    wf.sendJsonResponse(new HashMap())
                }
            }

            return defaultResponse
        }
    }

    /** Special automatic transition to save results of Select Columns form for form-list with select-columns=true */
    static class FormSelectColumnsTransitionItem extends TransitionItem {
        FormSelectColumnsTransitionItem(ScreenDefinition parentScreen) {
            super(parentScreen)
            name = "formSelectColumns"; method = "any"; location = "${parentScreen.location}.transition\$${name}"
            transitionNode = null; beginTransaction = true; readOnly = false; requireSessionToken = false
            defaultResponse = new ResponseItem(new MNode("default-response", [type:"none"]), this, parentScreen)
        }

        ResponseItem run(ScreenRenderImpl sri) {
            ScreenForm.saveFormConfig(sri.ec)
            ScreenUrlInfo.UrlInstance redirectUrl = sri.buildUrl(sri.rootScreenDef, sri.screenUrlInfo.preTransitionPathNameList, ".")
            redirectUrl.addParameters(sri.getCurrentScreenUrl().getParameterMap()).removeParameter("columnsTree")
                    .removeParameter("formLocation").removeParameter("ResetColumns").removeParameter("SaveColumns")

            if (!sri.sendJsonRedirect(redirectUrl, null)) sri.response.sendRedirect(redirectUrl.getUrlWithParams())
            return defaultResponse
        }
    }
    /** Special automatic transition to manage Saved Finds for form-list with saved-finds=true */
    static class FormSavedFindsTransitionItem extends TransitionItem {
        protected ResponseItem noneResponse = null

        FormSavedFindsTransitionItem(ScreenDefinition parentScreen) {
            super(parentScreen)
            name = "formSaveFind"; method = "any"; location = "${parentScreen.location}.transition\$${name}"
            transitionNode = null; beginTransaction = true; readOnly = false; requireSessionToken = false
            defaultResponse = new ResponseItem(new MNode("default-response", [url:"."]), this, parentScreen)
            noneResponse = new ResponseItem(new MNode("default-response", [type:"none"]), this, parentScreen)
        }

        ResponseItem run(ScreenRenderImpl sri) {
            String formListFindId = ScreenForm.processFormSavedFind(sri.ec)

            if (formListFindId == null || sri.response == null) return defaultResponse

            ScreenUrlInfo curUrlInfo = sri.getScreenUrlInfo()
            ArrayList<String> curFpnl = new ArrayList<>(curUrlInfo.fullPathNameList)
            // remove last path element, is transition name and we just want the screen this is from
            curFpnl.remove(curFpnl.size() - 1)

            ScreenUrlInfo fwdUrlInfo = ScreenUrlInfo.getScreenUrlInfo(sri, null, curFpnl, null, 0)
            ScreenUrlInfo.UrlInstance fwdInstance = fwdUrlInfo.getInstance(sri, null)

            Map<String, Object> flfInfo = ScreenForm.getFormListFindInfo(formListFindId, sri.ec, null)
            fwdInstance.addParameters((Map<String, String>) flfInfo.findParameters)

            if (!sri.sendJsonRedirect(fwdInstance, null)) sri.response.sendRedirect(fwdInstance.getUrlWithParams())
            return noneResponse
        }
    }

    /** Special automatic transition to get content of a ScreenDocument by docIndex */
    static class ScreenDocumentTransitionItem extends TransitionItem {
        ScreenDocumentTransitionItem(ScreenDefinition parentScreen) {
            super(parentScreen)
            name = "screenDoc"; method = "any"; location = "${parentScreen.location}.transition\$${name}"
            transitionNode = null; beginTransaction = false; readOnly = true; requireSessionToken = false
            defaultResponse = new ResponseItem(new MNode("default-response", [type:"none"]), this, parentScreen)
        }

        ResponseItem run(ScreenRenderImpl sri) {
            ExecutionContextImpl eci = sri.ec
            String docIndexString = eci.contextStack.getByString("docIndex")
            if (docIndexString == null || docIndexString.isEmpty()) {
                eci.web.sendError(HttpServletResponse.SC_NOT_FOUND, "No docIndex specified", null)
                return defaultResponse
            }
            Long docIndex = docIndexString as Long
            EntityValue screenDocument = eci.entityFacade.find("moqui.screen.ScreenDocument")
                    .condition("screenLocation", parentScreen.location).condition("docIndex", docIndex)
                    .useCache(true).disableAuthz().one()
            if (screenDocument == null) {
                eci.web.sendError(HttpServletResponse.SC_NOT_FOUND, "No document found for index ${docIndex}", null)
                return defaultResponse
            }

            String location = screenDocument.getNoCheckSimple("docLocation")
            eci.resourceFacade.template(location, sri.response.getWriter())

            return defaultResponse
        }
    }

    @CompileStatic
    static class ResponseItem {
        protected TransitionItem transitionItem
        protected ScreenDefinition parentScreen
        protected XmlAction condition = null
        protected Map<String, ParameterItem> parameterMap = new HashMap<>()

        protected String type
        protected String url
        protected String urlType
        protected Class parameterMapNameGroovy = null
        protected boolean saveCurrentScreen
        protected boolean saveParameters

        ResponseItem(MNode responseNode, TransitionItem ti, ScreenDefinition parentScreen) {
            this.transitionItem = ti
            this.parentScreen = parentScreen
            String location = "${parentScreen.location}.transition_${ti.name}.${responseNode.name.replace("-","_")}"
            if (responseNode.first("condition")?.first() != null) {
                // the script is effectively the first child of the condition element
                condition = new XmlAction(parentScreen.sfi.ecfi, responseNode.first("condition").first(),
                        location + ".condition")
            }

            ExecutionContextFactoryImpl ecfi = parentScreen.sfi.ecfi
            type = responseNode.attribute("type") ?: "url"
            url = responseNode.attribute("url")
            urlType = responseNode.attribute("url-type") ?: "screen-path"
            if (responseNode.attribute("parameter-map")) parameterMapNameGroovy = ecfi.getGroovyClassLoader()
                    .parseClass(responseNode.attribute("parameter-map"), "${location}.parameter_map")
            // deferred for future version: saveLastScreen = responseNode."@save-last-screen" == "true"
            saveCurrentScreen = responseNode.attribute("save-current-screen") == "true"
            saveParameters = responseNode.attribute("save-parameters") == "true"

            for (MNode parameterNode in responseNode.children("parameter"))
                parameterMap.put(parameterNode.attribute("name"), new ParameterItem(parameterNode, location, ecfi))
        }

        boolean checkCondition(ExecutionContextImpl ec) { return condition ? condition.checkCondition(ec) : true }

        String getType() { return type }
        String getUrl() { return parentScreen.sfi.ecfi.resourceFacade.expandNoL10n(url, "") }
        String getUrlType() { return urlType }
        boolean getSaveCurrentScreen() { return saveCurrentScreen }
        boolean getSaveParameters() { return saveParameters }

        Map expandParameters(List<String> extraPathNameList, ExecutionContextImpl ec) {
            transitionItem.setAllParameters(extraPathNameList, ec)

            Map ep = new HashMap()
            for (ParameterItem pi in parameterMap.values()) ep.put(pi.getName(), pi.getValue(ec))
            if (parameterMapNameGroovy != null) {
                Object pm = InvokerHelper.createScript(parameterMapNameGroovy, ec.getContextBinding()).run()
                if (pm && pm instanceof Map) ep.putAll((Map) pm)
            }
            // logger.warn("========== Expanded response map to url [${url}] to: ${ep}; parameterMap=${parameterMap}; parameterMapNameGroovy=[${parameterMapNameGroovy}]")
            return ep
        }
    }

    @CompileStatic
    static class SubscreensItem {
        protected ScreenDefinition parentScreen
        protected String name
        protected String location
        protected String menuTitle
        protected Integer menuIndex
        protected boolean menuInclude
        protected boolean noSubPath = false
        protected Class disableWhenGroovy = null
        protected String userGroupId = null

        SubscreensItem(String name, String location, MNode screen, ScreenDefinition parentScreen) {
            this.parentScreen = parentScreen
            this.name = name
            this.location = location
            menuTitle = screen.attribute("default-menu-title") ?: getDefaultTitle()
            menuIndex = screen.attribute("default-menu-index") ? (screen.attribute("default-menu-index") as Integer) : null
            menuInclude = (!screen.attribute("default-menu-include") || screen.attribute("default-menu-include") == "true")
        }

        SubscreensItem(MNode subscreensItem, ScreenDefinition parentScreen) {
            this.parentScreen = parentScreen
            name = subscreensItem.attribute("name")
            location = subscreensItem.attribute("location")
            menuTitle = subscreensItem.attribute("menu-title") ?: getDefaultTitle()
            menuIndex = subscreensItem.attribute("menu-index") ? (subscreensItem.attribute("menu-index") as Integer) : null
            menuInclude = !subscreensItem.attribute("menu-include") || subscreensItem.attribute("menu-include") == "true"
            noSubPath = subscreensItem.attribute("no-sub-path") == "true"

            if (subscreensItem.attribute("disable-when")) disableWhenGroovy = parentScreen.sfi.ecfi.getGroovyClassLoader()
                    .parseClass(subscreensItem.attribute("disable-when"), "${parentScreen.location}.subscreens_item_${name}.disable_when")
        }

        SubscreensItem(EntityValue subscreensItem, ScreenDefinition parentScreen) {
            this.parentScreen = parentScreen
            name = subscreensItem.subscreenName
            location = subscreensItem.subscreenLocation
            menuTitle = subscreensItem.menuTitle ?: getDefaultTitle()
            menuIndex = subscreensItem.menuIndex ? subscreensItem.menuIndex as Integer : null
            menuInclude = subscreensItem.menuInclude == "Y"
            noSubPath = subscreensItem.noSubPath == "Y"
            userGroupId = subscreensItem.userGroupId
        }

        String getDefaultTitle() {
            ExecutionContextFactoryImpl ecfi = parentScreen.sfi.ecfi
            ResourceReference screenRr = ecfi.resourceFacade.getLocationReference(location)
            MNode screenNode = MNode.parseRootOnly(screenRr)
            return getPrettyMenuName(screenNode?.attribute("default-menu-title"), location, ecfi)
        }

        String getName() { return name }
        String getLocation() { return location }
        String getMenuTitle() { return menuTitle }
        Integer getMenuIndex() { return menuIndex }
        boolean getMenuInclude() { return menuInclude }
        boolean getDisable(ExecutionContext ec) {
            if (disableWhenGroovy == null) return false
            return InvokerHelper.createScript(disableWhenGroovy, ec.contextBinding).run() as boolean
        }
        String getUserGroupId() { return userGroupId }
        boolean isValidInCurrentContext() {
            ExecutionContextImpl eci = parentScreen.sfi.getEcfi().getEci()
            // if the subscreens item is limited to a UserGroup make sure user is in that group
            if (userGroupId && !(userGroupId in eci.getUser().getUserGroupIdSet())) return false

            return true
        }
    }

    @CompileStatic
    static class SubscreensItemComparator implements Comparator<SubscreensItem> {
        SubscreensItemComparator() { }
        @Override
        int compare(SubscreensItem ssi1, SubscreensItem ssi2) {
            // order by index, null index first
            if (ssi1.menuIndex == null && ssi2.menuIndex != null) return -1
            if (ssi1.menuIndex != null && ssi2.menuIndex == null) return 1
            if (ssi1.menuIndex != null && ssi2.menuIndex != null) {
                int indexComp = ssi1.menuIndex.compareTo(ssi2.menuIndex)
                if (indexComp != 0) return indexComp
            }
            // if index is the same or both null, order by localized title
            ResourceFacade rf = ssi1.parentScreen.sfi.ecfi.resourceFacade
            return rf.expand(ssi1.menuTitle,'',null,true).toUpperCase().compareTo(
                   rf.expand(ssi2.menuTitle,'',null,true).toUpperCase())
        }
    }
}
