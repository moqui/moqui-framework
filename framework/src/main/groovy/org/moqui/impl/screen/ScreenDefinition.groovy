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

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.codehaus.groovy.runtime.InvokerHelper
import org.moqui.BaseException
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.util.ContextStack
import org.moqui.context.ExecutionContext
import org.moqui.context.WebFacade
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.actions.XmlAction
import org.moqui.context.ResourceReference
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.StupidUtilities
import org.moqui.entity.EntityFind
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.context.WebFacadeImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ScreenDefinition {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenDefinition.class)

    protected final ScreenFacadeImpl sfi
    protected final MNode screenNode
    protected final MNode subscreensNode
    protected final MNode webSettingsNode
    protected final String location
    protected final String screenName
    protected boolean standalone = false
    Long sourceLastModified = null

    protected Map<String, ParameterItem> parameterByName = new HashMap()
    protected Map<String, TransitionItem> transitionByName = new HashMap()
    protected Map<String, SubscreensItem> subscreensByName = new HashMap()
    protected List<SubscreensItem> subscreensItemsSorted = null
    protected Set<String> tenantsAllowed = null

    protected XmlAction alwaysActions = null
    protected XmlAction preActions = null

    protected ScreenSection rootSection = null
    protected Map<String, ScreenSection> sectionByName = new HashMap()
    protected Map<String, ScreenForm> formByName = new HashMap()
    protected Map<String, ScreenTree> treeByName = new HashMap()

    protected Map<String, ResourceReference> subContentRefByPath = new HashMap()
    protected Map<String, String> macroTemplateByRenderMode = null

    ScreenDefinition(ScreenFacadeImpl sfi, MNode screenNode, String location) {
        this.sfi = sfi
        this.screenNode = screenNode
        subscreensNode = screenNode.first("subscreens")
        webSettingsNode = screenNode.first("web-settings")
        this.location = location

        long startTime = System.currentTimeMillis()

        String filename = location.contains("/") ? location.substring(location.lastIndexOf("/")+1) : location
        screenName = filename.contains(".") ? filename.substring(0, filename.indexOf(".")) : filename

        standalone = screenNode.attribute('standalone') == "true"

        // parameter
        for (MNode parameterNode in screenNode.children("parameter"))
            parameterByName.put(parameterNode.attribute("name"), new ParameterItem(parameterNode, location))
        // prep always-actions
        if (screenNode.hasChild("always-actions"))
            alwaysActions = new XmlAction(sfi.ecfi, screenNode.first("always-actions"), location + ".always_actions")
        // transition
        for (MNode transitionNode in screenNode.children("transition")) {
            TransitionItem ti = new TransitionItem(transitionNode, this)
            transitionByName.put(ti.method == "any" ? ti.name : ti.name + "#" + ti.method, ti)
        }
        // transition-include
        for (MNode transitionInclNode in screenNode.children("transition-include")) {
            ScreenDefinition includeScreen = sfi.getEcfi().getScreenFacade().getScreenDefinition(transitionInclNode.attribute("location"))
            MNode transitionNode = includeScreen?.getTransitionItem(transitionInclNode.attribute("name"), transitionInclNode.attribute("method"))?.transitionNode
            if (transitionNode == null) throw new IllegalArgumentException("For transition-include could not find transition [${transitionInclNode.attribute("name")}] with method [${transitionInclNode.attribute("method")}] in screen at [${transitionInclNode.attribute("location")}]")
            TransitionItem ti = new TransitionItem(transitionNode, this)
            transitionByName.put(ti.method == "any" ? ti.name : ti.name + "#" + ti.method, ti)
        }
        // actions transition, for all screens
        if (!transitionByName.containsKey("actions")) transitionByName.put("actions", new ActionsTransitionItem(this))
        // formSelectColumns transition, for all screens
        if (!transitionByName.containsKey("formSelectColumns")) transitionByName.put("formSelectColumns", new FormSelectColumnsTransitionItem(this))

        // subscreens
        populateSubscreens()

        // tenants-allowed
        if (screenNode.attribute("tenants-allowed")) {
            tenantsAllowed = new HashSet(Arrays.asList((screenNode.attribute("tenants-allowed")).split(",")))
        }
        // macro-template - go through entire list and set all found, basically we want the last one if there are more than one
        List<MNode> macroTemplateList = screenNode.children("macro-template")
        if (macroTemplateList.size() > 0) {
            macroTemplateByRenderMode = new HashMap<>()
            for (MNode mt in macroTemplateList) macroTemplateByRenderMode.put(mt.attribute('type'), mt.attribute('location'))
        }

        // prep pre-actions
        if (screenNode.hasChild("pre-actions"))
            preActions = new XmlAction(sfi.ecfi, screenNode.first("pre-actions"), location + ".pre_actions")

        // get the root section
        rootSection = new ScreenSection(sfi.ecfi, screenNode, location + ".screen")

        if (rootSection && rootSection.widgets) {
            Map<String, ArrayList<MNode>> descMap = rootSection.widgets.widgetsNode.descendants(
                    new HashSet<String>(['section', 'section-iterate', 'section-include', 'form-single', 'form-list', 'tree']))
            // get all of the other sections by name
            for (MNode sectionNode in descMap.get('section'))
                sectionByName.put(sectionNode.attribute("name"), new ScreenSection(sfi.ecfi, sectionNode, "${location}.${sectionNode.name.replace('-','_')}_${sectionNode.attribute("name").replace('-','_')}"))
            for (MNode sectionNode in descMap.get('section-iterate'))
                sectionByName.put(sectionNode.attribute("name"), new ScreenSection(sfi.ecfi, sectionNode, "${location}.${sectionNode.name.replace('-','_')}_${sectionNode.attribute("name").replace('-','_')}"))
            for (MNode sectionNode in descMap.get('section-include'))
                pullSectionInclude(sectionNode)

            // get all forms by name
            for (MNode formNode in descMap.get('form-single'))
                formByName.put(formNode.attribute("name"), new ScreenForm(sfi.ecfi, this, formNode, "${location}.${formNode.name.replace('-','_')}_${formNode.attribute("name").replace('-','_')}"))
            for (MNode formNode in descMap.get('form-list'))
                formByName.put(formNode.attribute("name"), new ScreenForm(sfi.ecfi, this, formNode, "${location}.${formNode.name.replace('-','_')}_${formNode.attribute("name").replace('-','_')}"))

            // get all trees by name
            for (MNode treeNode in descMap.get('tree')) {
                treeByName.put(treeNode.attribute("name"), new ScreenTree(sfi.ecfi, this, treeNode, "${location}.${treeNode.name.replace('-','_')}_${treeNode.attribute("name").replace('-','_')}"))
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

        ScreenDefinition includeScreen = sfi.getEcfi().getScreenFacade().getScreenDefinition(location)
        ScreenSection includeSection = includeScreen?.getSection(sectionName)
        if (includeSection == null) throw new IllegalArgumentException("Could not find section [${sectionNode.attribute("name")} to include at location [${sectionNode.attribute("location")}]")
        sectionByName.put(sectionNode.attribute("name"), includeSection)

        Map<String, ArrayList<MNode>> descMap = includeSection.sectionNode.descendants(
                new HashSet<String>(['section', 'section-iterate', 'section-include', 'form-single', 'form-list', 'tree']))

        // see if the included section contains any SECTIONS, need to reference those here too!
        for (MNode inclRefNode in descMap.get('section'))
            sectionByName.put(inclRefNode.attribute("name"), includeScreen.getSection(inclRefNode.attribute("name")))
        for (MNode inclRefNode in descMap.get('section-iterate'))
            sectionByName.put(inclRefNode.attribute("name"), includeScreen.getSection(inclRefNode.attribute("name")))
        // recurse for section-include
        for (MNode inclRefNode in descMap.get('section-include'))
            pullSectionInclude(inclRefNode)

        // see if the included section contains any FORMS or TREES, need to reference those here too!
        for (MNode formNode in descMap.get('form-single'))
            formByName.put(formNode.attribute("name"), includeScreen.getForm(formNode.attribute("name")))
        for (MNode formNode in descMap.get('form-list'))
            formByName.put(formNode.attribute("name"), includeScreen.getForm(formNode.attribute("name")))
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
            logger.warn("Not getting subscreens by file/directory structure for screen [${location}] because it is not a location that supports directories")
        }

        // override dir structure with subscreens.subscreens-item elements
        if (screenNode.hasChild("subscreens")) for (MNode subscreensItem in screenNode.first("subscreens").children("subscreens-item")) {
            SubscreensItem si = new SubscreensItem(subscreensItem, this)
            subscreensByName.put(si.name, si)
            if (logger.traceEnabled) logger.trace("Added XML defined subscreen [${si.name}] at [${si.location}] to screen [${locationRef}]")
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
            if (logger.traceEnabled) logger.trace("Added database subscreen [${si.name}] at [${si.location}] to screen [${locationRef}]")
        }
    }

    MNode getScreenNode() { return screenNode }
    MNode getSubscreensNode() { return subscreensNode }
    String getDefaultSubscreensItem() { return subscreensNode?.attribute('default-item') }
    MNode getWebSettingsNode() { return webSettingsNode }
    String getLocation() { return location }
    Set<String> getTenantsAllowed() { return tenantsAllowed }

    String getScreenName() { return screenName }
    boolean isStandalone() { return standalone }

    String getDefaultMenuName() {
        String menuName = screenNode.attribute("default-menu-title")
        if (!menuName) {
            String filename = location.substring(location.lastIndexOf("/")+1, location.length()-4)
            StringBuilder prettyName = new StringBuilder()
            for (String part in filename.split("(?=[A-Z])")) {
                if (prettyName) prettyName.append(" ")
                prettyName.append(part)
            }
            Character firstChar = prettyName.charAt(0) as Character
            if (firstChar.isLowerCase()) prettyName.setCharAt(0, firstChar.toUpperCase())
            menuName = prettyName.toString()
        }

        return sfi.getEcfi().getExecutionContext().getL10n().localize(menuName)
    }

    /** Get macro template location specific to screen from marco-template elements */
    String getMacroTemplateLocation(String renderMode) {
        if (macroTemplateByRenderMode == null) return null
        return macroTemplateByRenderMode.get(renderMode)
    }

    Map<String, ParameterItem> getParameterMap() { return parameterByName }
    boolean hasRequiredParameters() {
        boolean hasRequired = false
        for (ParameterItem pi in parameterByName.values()) if (pi.required) { hasRequired = true; break }
        return hasRequired
    }

    boolean hasTransition(String name) {
        for (TransitionItem curTi in transitionByName.values()) if (curTi.name == name) return true
        return false
    }

    TransitionItem getTransitionItem(String name, String method) {
        method = method ? method.toLowerCase() : ""
        TransitionItem ti = (TransitionItem) transitionByName.get(name + "#" + method)
        // if no ti, try by name only which will catch transitions with "any" or empty method
        if (ti == null) ti = (TransitionItem) transitionByName.get(name)
        // still none? try each one to see if it matches as a regular expression (first one to match wins)
        if (ti == null) for (TransitionItem curTi in transitionByName.values()) {
            if (method && curTi.method && (curTi.method == "any" || curTi.method == method)) {
                if (name == curTi.name) { ti = curTi; break }
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
                ScreenDefinition subSd = sfi.getScreenDefinition(curSsi.getLocation())
                ArrayList<String> subPath = subSd.findSubscreenPath(subPathNameList)
                if (!subPath) return null
                subPath.add(0, curName)
                return subPath
            } else {
                return remainingPathNameList
            }
        }

        // if this is a transition right under this screen use it before searching subscreens
        if (hasTransition(curName)) return remainingPathNameList

        // breadth first by looking at subscreens of each subscreen on a first pass
        for (Map.Entry<String, SubscreensItem> entry in subscreensByName.entrySet()) {
            ScreenDefinition subSd = sfi.getScreenDefinition(entry.getValue().getLocation())
            if (subSd == null) {
                if (logger.isTraceEnabled()) logger.trace("Screen [${entry.getKey()}] at location [${entry.getValue().getLocation()}] not found, subscreen of [${this.getLocation()}]")
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
            ScreenDefinition subSd = sfi.getScreenDefinition(entry.getValue().getLocation())
            if (subSd == null) {
                if (logger.isTraceEnabled()) logger.trace("Screen [${entry.getKey()}] at location [${entry.getValue().getLocation()}] not found, subscreen of [${this.getLocation()}]")
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
        if (screenNode."@allow-extra-path" == "true") {
            // call it good
            return remainingPathNameList
        }
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
            ScreenDefinition subSd = sfi.getScreenDefinition(ssi.location)
            if (!subSd.hasRequiredParameters()) {
                String subPath = (currentPath ? currentPath + "/" : '') + ssi.name
                // don't add current if a has a default subscreen item
                if (!subSd.getDefaultSubscreensItem()) locList.add(subPath)
                locList.addAll(subSd.nestedNoReqParmLocations(subPath, screensToSkip))
            }
        }
        return locList
    }

    List<SubscreensItem> getSubscreensItemsSorted() {
        if (subscreensItemsSorted != null) return subscreensItemsSorted
        List<SubscreensItem> newList = new ArrayList(subscreensByName.size())
        if (subscreensByName.size() == 0) return newList
        newList.addAll(subscreensByName.values())
        Collections.sort(newList, new SubscreensItemComparator())
        return subscreensItemsSorted = newList
    }

    List<SubscreensItem> getMenuSubscreensItems() {
        List<SubscreensItem> allItems = getSubscreensItemsSorted()
        List<SubscreensItem> filteredList = new ArrayList(allItems.size())

        for (SubscreensItem si in allItems) {
            // check the menu include flag
            if (!si.menuInclude) continue
            // valid in current context? (user group, tenant, etc)
            if (!si.isValidInCurrentContext()) continue
            // made it through the checks? add it in...
            filteredList.add(si)
        }

        return filteredList
    }

    ScreenSection getRootSection() { return rootSection }
    void render(ScreenRenderImpl sri, boolean isTargetScreen) {
        // NOTE: don't require authz if the screen doesn't require auth
        String requireAuthentication = screenNode.attribute('require-authentication')
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(location,
                ArtifactExecutionInfo.AT_XML_SCREEN, ArtifactExecutionInfo.AUTHZA_VIEW)
        sri.ec.artifactExecutionImpl.pushInternal(aei, isTargetScreen ? (!requireAuthentication || requireAuthentication == "true") : false)

        boolean loggedInAnonymous = false
        if (requireAuthentication == "anonymous-all") {
            sri.ec.artifactExecution.setAnonymousAuthorizedAll()
            loggedInAnonymous = sri.ec.getUser().loginAnonymousIfNoUser()
        } else if (requireAuthentication == "anonymous-view") {
            sri.ec.artifactExecution.setAnonymousAuthorizedView()
            loggedInAnonymous = sri.ec.getUser().loginAnonymousIfNoUser()
        }

        // logger.info("Rendering screen ${location}, screenNode: \n${screenNode}")

        try {
            rootSection.render(sri)
        } finally {
            sri.ec.artifactExecution.pop(aei)
            if (loggedInAnonymous) ((UserFacadeImpl) sri.ec.getUser()).logoutAnonymousOnly()
        }
    }

    ScreenSection getSection(String sectionName) {
        ScreenSection ss = sectionByName.get(sectionName)
        if (ss == null) throw new IllegalArgumentException("Could not find form [${sectionName}] in screen: ${getLocation()}")
        return ss
    }
    ScreenForm getForm(String formName) {
        ScreenForm sf = formByName.get(formName)
        if (sf == null) throw new IllegalArgumentException("Could not find form [${formName}] in screen: ${getLocation()}")
        return sf
    }
    ScreenTree getTree(String treeName) {
        ScreenTree st = treeByName.get(treeName)
        if (st == null) throw new IllegalArgumentException("Could not find tree [${treeName}] in screen: ${getLocation()}")
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
            logger.warn("Not looking for sub-content [${pathName}] under screen [${location}] because screen location does not support exists, isFile, etc")
        }

        if (contentRef != null) subContentRefByPath.put(pathName, contentRef)
        return contentRef
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

        ParameterItem(MNode parameterNode, String location) {
            this.name = parameterNode.attribute("name")
            if (parameterNode.attribute("required") == "true") required = true

            if (parameterNode.attribute("from")) fromFieldGroovy = new GroovyClassLoader().parseClass(
                    parameterNode.attribute("from"), StupidUtilities.cleanStringForJavaName("${location}.parameter_${name}.from_field"))

            valueString = parameterNode.attribute("value")
            if (valueString != null && valueString.length() == 0) valueString = null
            if (valueString != null && valueString.contains('${')) {
                valueGroovy = new GroovyClassLoader().parseClass(('"""' + parameterNode.attribute("value") + '"""'),
                        StupidUtilities.cleanStringForJavaName("${location}.parameter_${name}.value"))
            }
        }
        String getName() { return name }
        Object getValue(ExecutionContext ec) {
            Object value = null
            if (fromFieldGroovy != null) {
                value = InvokerHelper.createScript(fromFieldGroovy, ec.contextBinding).run()
            }
            if (value == null) {
                if (valueGroovy != null) {
                    value = InvokerHelper.createScript(valueGroovy, ec.contextBinding).run()
                } else {
                    value = valueString
                }
            }
            if (value == null) value = ec.context.getByString(name)
            if (value == null && ec.web) value = ec.web.parameters.get(name)
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
        protected String singleServiceName = null

        protected Map<String, ParameterItem> parameterByName = new HashMap()
        protected List<String> pathParameterList = null

        protected List<ResponseItem> conditionalResponseList = new ArrayList<ResponseItem>()
        protected ResponseItem defaultResponse = null
        protected ResponseItem errorResponse = null

        protected boolean beginTransaction = true
        protected boolean readOnly = false
        protected boolean requireSessionToken = true

        protected TransitionItem(ScreenDefinition parentScreen) {
            this.parentScreen = parentScreen
        }

        TransitionItem(MNode transitionNode, ScreenDefinition parentScreen) {
            this.parentScreen = parentScreen
            this.transitionNode = transitionNode
            name = transitionNode.attribute("name")
            method = transitionNode.attribute("method") ?: "any"
            location = "${parentScreen.location}.transition_${StupidUtilities.cleanStringForJavaName(name)}"
            beginTransaction = transitionNode.attribute("begin-transaction") != "false"
            readOnly = transitionNode.attribute("read-only") == "true"
            requireSessionToken = transitionNode.attribute("require-session-token") != "false"

            // parameter
            for (MNode parameterNode in transitionNode.children("parameter"))
                parameterByName.put(parameterNode.attribute("name"), new ParameterItem(parameterNode, location))
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
            // service OR actions
            if (transitionNode.hasChild("service-call")) {
                MNode callServiceNode = transitionNode.first("service-call")
                if (!callServiceNode.attribute("in-map")) callServiceNode.attributes.put("in-map", "true")
                if (!callServiceNode.attribute("out-map")) callServiceNode.attributes.put("out-map", "context")
                if (!callServiceNode.attribute("multi")) callServiceNode.attributes.put("multi", "parameter")
                actions = new XmlAction(parentScreen.sfi.ecfi, callServiceNode, location + ".service_call")
                singleServiceName = callServiceNode.attribute("name")
            } else if (transitionNode.hasChild("actions")) {
                actions = new XmlAction(parentScreen.sfi.ecfi, transitionNode.first("actions"), location + ".actions")
            }

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
        boolean hasActionsOrSingleService() { return actions != null }
        boolean getBeginTransaction() { return beginTransaction }
        boolean isReadOnly() { return readOnly }
        boolean getRequireSessionToken() { return requireSessionToken }

        boolean checkCondition(ExecutionContextImpl ec) { return condition ? condition.checkCondition(ec) : true }

        void setAllParameters(List<String> extraPathNameList, ExecutionContext ec) {
            // get the path parameters
            if (extraPathNameList && getPathParameterList()) {
                List<String> pathParameterList = getPathParameterList()
                int i = 0
                for (String extraPathName in extraPathNameList) {
                    if (pathParameterList.size() > i) {
                        if (ec.getWeb()) ((WebFacadeImpl) ec.getWeb()).addDeclaredPathParameter(pathParameterList.get(i), extraPathName)
                        ec.getContext().put(pathParameterList.get(i), extraPathName)
                        i++
                    } else {
                        break
                    }
                }
            }

            // put parameters in the context
            if (ec.getWeb()) {
                // screen parameters
                for (ParameterItem pi in parentScreen.getParameterMap().values()) {
                    Object value = pi.getValue(ec)
                    if (value != null) ec.getContext().put(pi.getName(), value)
                }
                // transition parameters
                for (ParameterItem pi in parameterByName.values()) {
                    Object value = pi.getValue(ec)
                    if (value != null) ec.getContext().put(pi.getName(), value)
                }
            }
        }

        ResponseItem run(ScreenRenderImpl sri) {
            ExecutionContextImpl ec = sri.getEc()

            // NOTE: if parent screen of transition does not require auth, don't require authz
            // NOTE: use the View authz action to leave it open, ie require minimal authz; restrictions are often more
            //    in the services/etc if/when needed, or specific transitions can have authz settings
            String requireAuthentication = (String) parentScreen.screenNode.attribute('require-authentication')
            ArtifactExecutionInfo aei = new ArtifactExecutionInfoImpl("${parentScreen.location}/${name}",
                    ArtifactExecutionInfo.AT_XML_SCREEN_TRANS, ArtifactExecutionInfo.AUTHZA_VIEW)
            ec.getArtifactExecutionImpl().pushInternal(aei, (!requireAuthentication || requireAuthentication == "true"))

            boolean loggedInAnonymous = false
            if (requireAuthentication == "anonymous-all") {
                ec.artifactExecution.setAnonymousAuthorizedAll()
                loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
            } else if (requireAuthentication == "anonymous-view") {
                ec.artifactExecution.setAnonymousAuthorizedView()
                loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
            }

            try {
                ScreenUrlInfo screenUrlInfo = sri.getScreenUrlInfo()
                ScreenUrlInfo.UrlInstance screenUrlInstance = sri.getScreenUrlInstance()
                setAllParameters(screenUrlInfo.getExtraPathNameList(), ec)
                // for alias transitions rendered in-request put the parameters in the context
                if (screenUrlInstance.getTransitionAliasParameters()) ec.getContext().putAll(screenUrlInstance.getTransitionAliasParameters())


                if (!checkCondition(ec)) {
                    sri.ec.message.addError(ec.resource.expand('Condition failed for transition [${location}], not running actions or redirecting','',[location:location]))
                    if (errorResponse) return errorResponse
                    return defaultResponse
                }

                // don't push a map on the context, let the transition actions set things that will remain: sri.ec.context.push()
                ec.getContext().put("sri", sri)
                if (actions != null) actions.run(ec)

                ResponseItem ri = null
                // if there is an error-response and there are errors, we have a winner
                if (ec.getMessage().hasError() && errorResponse) ri = errorResponse

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
                ec.getArtifactExecution().pop(aei)
                if (loggedInAnonymous) ((UserFacadeImpl) ec.getUser()).logoutAnonymousOnly()
            }
        }
    }

    @CompileStatic
    static class ActionsTransitionItem extends TransitionItem {
        ActionsTransitionItem(ScreenDefinition parentScreen) {
            super(parentScreen)
            transitionNode = null
            name = "actions"
            method = "any"
            location = "${parentScreen.location}.transition_${name}"
            beginTransaction = true
            readOnly = true
            requireSessionToken = false

            defaultResponse = new ResponseItem(new MNode("default-response", [type:"none"]), this, parentScreen)
        }

        // NOTE: runs pre-actions too, see sri.recursiveRunTransition() call in sri.internalRender()
        ResponseItem run(ScreenRenderImpl sri) {
            ExecutionContextImpl ec = sri.getEc()
            WebFacade wf = ec.getWeb()
            if (wf == null) throw new BaseException("Cannot run actions transition outside of a web request")

            // run actions (if there are any)
            XmlAction actions = parentScreen.rootSection.actions
            if (actions != null) {
                ec.context.put("sri", sri)
                actions.run(ec)
                // use entire ec.context to get values from always-actions and pre-actions
                wf.sendJsonResponse(unwrapMap(ec.context))
            } else {
                wf.sendJsonResponse(new HashMap())
            }

            return defaultResponse
        }
    }

    // the Groovy JsonBuilder doesn't handle various Moqui objects very well, ends up trying to access all
    // properties and results in infinite recursion, so need to unwrap and exclude some
    static Map<String, Object> unwrapMap(Map<String, Object> sourceMap) {
        Map<String, Object> targetMap = new HashMap<>()
        for (Map.Entry<String, Object> entry in sourceMap) {
            String key = entry.getKey()
            Object value = entry.getValue()
            if (value == null) continue
            // logger.warn("======== actionsResult - ${entry.key} (${entry.value?.getClass()?.getName()}): ${entry.value}")
            Object unwrapped = unwrap(key, value)
            if (unwrapped != null) targetMap.put(key, unwrapped)
        }
        return targetMap
    }
    static Object unwrap(String key, Object value) {
        if (value == null) return null
        if (value instanceof CharSequence || value instanceof Number || value instanceof Date) {
            return value
        } else if (value instanceof EntityFind || value instanceof ExecutionContextImpl ||
                value instanceof ScreenRenderImpl || value instanceof ContextStack) {
            // intentionally skip, commonly left in context by entity-find XML action
            return null
        } else if (value instanceof EntityValue) {
            EntityValue ev = value as EntityValue
            return ev.getPlainValueMap(0)
        } else if (value instanceof EntityList) {
            EntityList el = value as EntityList
            ArrayList<Map> newList = new ArrayList<>()
            int elSize = el.size()
            for (int i = 0; i < elSize; i++) {
                EntityValue ev = (EntityValue) el.get(i)
                newList.add(ev.getPlainValueMap(0))
            }
            return newList
        } else if (value instanceof Collection) {
            Collection valCol = value as Collection
            ArrayList newList = new ArrayList(valCol.size())
            for (Object entry in valCol) newList.add(unwrap(key, entry))
            return newList
        } else if (value instanceof Map) {
            Map valMap = value as Map
            Map newMap = new HashMap(valMap.size())
            for (Map.Entry entry in valMap.entrySet()) newMap.put(entry.getKey(), unwrap(key, entry.getValue()))
            return newMap
        } else {
            logger.info("In screen actions skipping value from actions block that is not supported; key=${key}, type=${value.class.name}, value=${value}")
            return null
        }
    }

    /** Special automatic transition to save results of Select Columns form for form-list with select-columns=true */
    static class FormSelectColumnsTransitionItem extends TransitionItem {
        FormSelectColumnsTransitionItem(ScreenDefinition parentScreen) {
            super(parentScreen)
            transitionNode = null
            name = "formSelectColumns"
            method = "any"
            location = "${parentScreen.location}.transition_${name}"
            beginTransaction = true
            readOnly = true
            requireSessionToken = false

            defaultResponse = new ResponseItem(new MNode("default-response", [url:"."]), this, parentScreen)
        }

        ResponseItem run(ScreenRenderImpl sri) {
            ExecutionContextImpl ec = sri.getEc()

            String userId = ec.user.userId
            String formLocation = ec.context.get("formLocation")

            // see if there is an existing FormConfig record
            String formConfigId = ec.context.get("formConfigId")
            if (!formConfigId) {
                EntityValue fcu = ec.entity.find("moqui.screen.form.FormConfigUser")
                        .condition("userId", userId).condition("formLocation", formLocation).useCache(false).one()
                formConfigId = fcu != null ? fcu.formConfigId : null
            }
            String userCurrentFormConfigId = formConfigId

            // if FormConfig associated with this user but no other users or groups delete its FormConfigField
            //     records and remember its ID for create FormConfigField
            if (formConfigId) {
                long userCount = ec.entity.find("moqui.screen.form.FormConfigUser")
                        .condition("formConfigId", formConfigId).useCache(false).count()
                if (userCount > 1) {
                    formConfigId = null
                } else {
                    long groupCount = ec.entity.find("moqui.screen.form.FormConfigUserGroup")
                            .condition("formConfigId", formConfigId).useCache(false).count()
                    if (groupCount > 0) formConfigId = null
                }
            }

            // clear out existing records
            if (formConfigId) {
                ec.entity.find("moqui.screen.form.FormConfigField").condition("formConfigId", formConfigId).deleteAll()
            }

            // are we resetting columns?
            if (ec.context.get("ResetColumns")) {
                if (formConfigId) {
                    // no other users on this form, and now being reset, so delete FormConfig
                    ec.entity.find("moqui.screen.form.FormConfigUser").condition("formConfigId", formConfigId).deleteAll()
                    ec.entity.find("moqui.screen.form.FormConfig").condition("formConfigId", formConfigId).deleteAll()
                } else if (userCurrentFormConfigId) {
                    // there is a FormConfig but other users are using it, so just remove this user
                    ec.entity.find("moqui.screen.form.FormConfigUser").condition("formConfigId", userCurrentFormConfigId)
                            .condition("userId", userId).deleteAll()
                }
                // to reset columns don't save new ones, just return after clearing out existing records
                return defaultResponse
            }

            // if there is no FormConfig or found record is associated with other users or groups
            //     create a new FormConfig record to use
            if (!formConfigId) {
                Map createResult = ec.service.sync().name("create#moqui.screen.form.FormConfig")
                        .parameters([userId:userId, formLocation:formLocation, description:"For user ${userId}"]).call()
                formConfigId = createResult.formConfigId
                ec.service.sync().name("create#moqui.screen.form.FormConfigUser")
                        .parameters([formConfigId:formConfigId, userId:userId, formLocation:formLocation]).call()
            }

            // save changes to DB
            String columnsTreeStr = ec.context.get("columnsTree") as String
            // logger.info("columnsTreeStr: ${columnsTreeStr}")
            // if columnsTree empty there were no changes
            if (!columnsTreeStr) return defaultResponse
            JsonSlurper slurper = new JsonSlurper()
            List<Map> columnsTree = (List<Map>) slurper.parseText(columnsTreeStr)

            StupidUtilities.orderMapList(columnsTree, ['order'])
            int columnIndex = 0
            for (Map columnMap in columnsTree) {
                if (columnMap.get("id") == "hidden") continue
                List<Map> children = (List<Map>) columnMap.get("children")
                StupidUtilities.orderMapList(children, ['order'])
                int columnSequence = 0
                for (Map fieldMap in children) {
                    String fieldName = (String) fieldMap.get("id")
                    // logger.info("Adding field ${fieldName} to column ${columnIndex} at sequence ${columnSequence}")
                    ec.service.sync().name("create#moqui.screen.form.FormConfigField")
                            .parameters([formConfigId:formConfigId, fieldName:fieldName,
                                         positionIndex:columnIndex, positionSequence:columnSequence]).call()
                    columnSequence++
                }
                columnIndex++
            }
            return defaultResponse
        }
    }


    @CompileStatic
    static class ResponseItem {
        protected TransitionItem transitionItem
        protected ScreenDefinition parentScreen
        protected XmlAction condition = null
        protected Map<String, ParameterItem> parameterMap = new HashMap()

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

            type = responseNode.attribute("type") ?: "url"
            url = responseNode.attribute("url")
            urlType = responseNode.attribute("url-type") ?: "screen-path"
            if (responseNode.attribute("parameter-map")) parameterMapNameGroovy = new GroovyClassLoader().parseClass(
                    responseNode.attribute("parameter-map"), "${location}.parameter_map")
            // deferred for future version: saveLastScreen = responseNode."@save-last-screen" == "true"
            saveCurrentScreen = responseNode.attribute("save-current-screen") == "true"
            saveParameters = responseNode.attribute("save-parameters") == "true"

            for (MNode parameterNode in responseNode.children("parameter"))
                parameterMap.put(parameterNode.attribute("name"), new ParameterItem(parameterNode, location))
        }

        boolean checkCondition(ExecutionContextImpl ec) { return condition ? condition.checkCondition(ec) : true }

        String getType() { return type }
        String getUrl() { return parentScreen.sfi.ecfi.resourceFacade.expand(url, "") }
        String getUrlType() { return urlType }
        boolean getSaveCurrentScreen() { return saveCurrentScreen }
        boolean getSaveParameters() { return saveParameters }

        Map expandParameters(List<String> extraPathNameList, ExecutionContext ec) {
            transitionItem.setAllParameters(extraPathNameList, ec)

            Map ep = new HashMap()
            for (ParameterItem pi in parameterMap.values()) ep.put(pi.getName(), pi.getValue(ec))
            if (parameterMapNameGroovy != null) {
                Object pm = InvokerHelper.createScript(parameterMapNameGroovy, ec.getContextBinding()).run()
                if (pm && pm instanceof Map) ep.putAll(pm)
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
        protected Class disableWhenGroovy = null
        protected String userGroupId = null
        protected Set<String> tenantsAllowed = null

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
            menuInclude = (!subscreensItem.attribute("menu-include") || subscreensItem.attribute("menu-include") == "true")

            if (subscreensItem.attribute("disable-when")) disableWhenGroovy = new GroovyClassLoader().parseClass(
                    subscreensItem.attribute("disable-when"), "${parentScreen.location}.subscreens_item_${name}.disable_when")
            if (subscreensItem.attribute("tenants-allowed")) {
                String tenantsAllowedStr = subscreensItem.attribute("tenants-allowed")
                tenantsAllowed = new TreeSet(tenantsAllowedStr.split(',') as List)
            }
        }

        SubscreensItem(EntityValue subscreensItem, ScreenDefinition parentScreen) {
            this.parentScreen = parentScreen
            name = subscreensItem.subscreenName
            location = subscreensItem.subscreenLocation
            menuTitle = subscreensItem.menuTitle ?: getDefaultTitle()
            menuIndex = subscreensItem.menuIndex ? subscreensItem.menuIndex as Integer : null
            menuInclude = (subscreensItem.menuInclude == "Y")
            userGroupId = subscreensItem.userGroupId
            if (subscreensItem.tenantsAllowed) {
                String tenantsAllowedStr = subscreensItem.tenantsAllowed
                tenantsAllowed = new TreeSet(tenantsAllowedStr.split(',') as List)
            }
        }

        String getDefaultTitle() {
            ScreenDefinition sd = parentScreen.sfi.getScreenDefinition(location)
            if (sd != null) {
                return sd.getDefaultMenuName()
            } else {
                return location.substring(location.lastIndexOf("/")+1, location.length()-4)
            }
        }

        String getName() { return name }
        String getLocation() { return location }
        String getMenuTitle() { return menuTitle }
        Integer getMenuIndex() { return menuIndex }
        boolean getMenuInclude() { return menuInclude }
        boolean getDisable(ExecutionContext ec) {
            if (!disableWhenGroovy) return false
            return InvokerHelper.createScript(disableWhenGroovy, ec.contextBinding).run() as boolean
        }
        String getUserGroupId() { return userGroupId }
        boolean isValidInCurrentContext() {
            ExecutionContextImpl eci = parentScreen.sfi.getEcfi().getEci()
            // if the subscreens item is limited to a UserGroup make sure user is in that group
            if (userGroupId && !(userGroupId in eci.getUser().getUserGroupIdSet())) return false
            // if limited to tenants make sure active tenant is one of them
            if (tenantsAllowed != null && !(tenantsAllowed.contains(eci.getTenantId()))) return false

            return true
        }
    }

    @CompileStatic
    static class SubscreensItemComparator implements Comparator<SubscreensItem> {
        public SubscreensItemComparator() { }
        @Override
        public int compare(SubscreensItem ssi1, SubscreensItem ssi2) {
            // order by index, null index first
            if (ssi1.menuIndex == null && ssi2.menuIndex != null) return -1
            if (ssi1.menuIndex != null && ssi2.menuIndex == null) return 1
            if (ssi1.menuIndex != null && ssi2.menuIndex != null) {
                int indexComp = ssi1.menuIndex.compareTo(ssi2.menuIndex)
                if (indexComp != 0) return indexComp
            }
            // if index is the same or both null, order by title
            return ssi1.menuTitle.compareTo(ssi2.menuTitle)
        }
    }
}
