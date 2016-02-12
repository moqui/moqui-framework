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
import org.apache.commons.codec.net.URLCodec
import org.moqui.BaseException
import org.moqui.context.Cache
import org.moqui.context.ExecutionContext
import org.moqui.context.ResourceReference
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.StupidUtilities
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.WebFacadeImpl
import org.moqui.impl.screen.ScreenDefinition.ParameterItem
import org.moqui.impl.screen.ScreenDefinition.TransitionItem
import org.moqui.impl.webapp.ScreenResourceNotFoundException
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ScreenUrlInfo {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenUrlInfo.class)
    protected final static URLCodec urlCodec = new URLCodec()

    // ExecutionContext ec
    ExecutionContextFactoryImpl ecfi
    ScreenFacadeImpl sfi
    ScreenDefinition rootSd
    String plainUrl = null

    ScreenDefinition fromSd = null
    List<String> fromPathList = null
    String fromScreenPath = null

    Map<String, String> pathParameterMap = new HashMap()
    boolean requireEncryption = false
    // boolean hasActions = false
    // boolean disableLink = false
    boolean alwaysUseFullPath = false
    boolean beginTransaction = false

    String menuImage = null
    String menuImageType = null

    /** The full path name list for the URL, including extraPathNameList */
    ArrayList<String> fullPathNameList = null

    /** The minimal path name list for the URL, basically without following the defaults */
    ArrayList<String> minimalPathNameList = null

    /** Everything in the path after the screen or transition, may be used to pass additional info */
    ArrayList<String> extraPathNameList = null

    /** The path for a file resource (template or static), relative to the targetScreen.location */
    List<String> fileResourcePathList = null
    /** If the full path led to a file resource that is verified to exist, the URL goes here; the URL for access on the
     * server, the client will get the resource from the url field as normal */
    ResourceReference fileResourceRef = null
    String fileResourceContentType = null

    /** All screens found in the path list */
    ArrayList<ScreenDefinition> screenPathDefList = new ArrayList<ScreenDefinition>()
    /** The list of screens to render, starting with the root screen OR the last standalone screen if applicable */
    ArrayList<ScreenDefinition> screenRenderDefList = new ArrayList<ScreenDefinition>()
    int renderPathDifference = 0
    boolean lastStandalone = false

    /** The last screen found in the path list */
    ScreenDefinition targetScreen = null
    String targetTransitionActualName = null
    ArrayList<String> preTransitionPathNameList = new ArrayList<String>()

    boolean reusable = true

    protected ScreenUrlInfo() { }

    /** Stub mode for ScreenUrlInfo, represent a plain URL and not a screen URL */
    static ScreenUrlInfo getScreenUrlInfo(ScreenRenderImpl sri, String url) {
        Cache screenUrlCache = sri.getSfi().screenUrlCache
        ScreenUrlInfo cached = (ScreenUrlInfo) screenUrlCache.get(url)
        if (cached != null) return cached

        ScreenUrlInfo newSui = new ScreenUrlInfo(sri, url)
        screenUrlCache.put(url, newSui)
        return newSui
    }

    static ScreenUrlInfo getScreenUrlInfo(ScreenFacadeImpl sfi, ScreenDefinition rootSd, ScreenDefinition fromScreenDef,
                                          List<String> fpnl, String subscreenPath, Boolean lastStandalone) {
        Cache screenUrlCache = sfi.screenUrlCache
        String cacheKey = makeCacheKey(rootSd, fromScreenDef, fpnl, subscreenPath, lastStandalone)
        ScreenUrlInfo cached = (ScreenUrlInfo) screenUrlCache.get(cacheKey)
        if (cached != null) return cached

        ScreenUrlInfo newSui = new ScreenUrlInfo(sfi, rootSd, fromScreenDef, fpnl, subscreenPath, lastStandalone)
        screenUrlCache.put(cacheKey, newSui)
        return newSui
    }

    static ScreenUrlInfo getScreenUrlInfo(ScreenRenderImpl sri, ScreenDefinition fromScreenDef, List<String> fpnl,
                                          String subscreenPath, Boolean lastStandalone) {
        ScreenDefinition rootSd = sri.getRootScreenDef()
        ScreenDefinition fromSd = fromScreenDef
        List<String> fromPathList = fpnl
        if (fromSd == null) fromSd = sri.getActiveScreenDef()
        if (fromPathList == null) fromPathList = sri.getActiveScreenPath()

        Cache screenUrlCache = sri.getSfi().screenUrlCache
        String cacheKey = makeCacheKey(rootSd, fromSd, fromPathList, subscreenPath, lastStandalone)
        ScreenUrlInfo cached = (ScreenUrlInfo) screenUrlCache.get(cacheKey)
        if (cached != null) return cached

        ScreenUrlInfo newSui = new ScreenUrlInfo(sri.getSfi(), rootSd, fromSd, fromPathList, subscreenPath, lastStandalone)
        if (newSui.reusable) screenUrlCache.put(cacheKey, newSui)
        return newSui
    }

    final static char slashChar = (char) '/'
    static String makeCacheKey(ScreenDefinition rootSd, ScreenDefinition fromScreenDef, List<String> fpnl,
                               String subscreenPath, Boolean lastStandalone) {
        StringBuilder sb = new StringBuilder()
        // shouldn't be too many root screens, so the screen name (filename) should be sufficiently unique and much shorter
        sb.append(rootSd.getScreenName()).append(":")
        if (fromScreenDef != null) sb.append(fromScreenDef.getScreenName()).append(":")
        boolean hasSsp = subscreenPath != null && subscreenPath.length() > 0
        boolean skipFpnl = hasSsp && subscreenPath.charAt(0) == slashChar
        // NOTE: we will get more cache hits (less cache redundancy) if we combine with fpnl and use cleanupPathNameList,
        //     but is it worth it? no, let there be redundant cache entries for the same screen path, will be faster
        if (!skipFpnl && fpnl) for (String fpn in fpnl) sb.append('/').append(fpn)
        if (hasSsp) sb.append(subscreenPath)
        if (lastStandalone) sb.append(":LS")

        // logger.warn("======= makeCacheKey subscreenPath=${subscreenPath}, fpnl=${fpnl}\n key=${sb}")
        return sb.toString()
    }

    /** Stub mode for ScreenUrlInfo, represent a plain URL and not a screen URL */
    ScreenUrlInfo(ScreenRenderImpl sri, String url) {
        this.sfi = sri.getSfi()
        this.ecfi = sfi.getEcfi()
        this.rootSd = sri.getRootScreenDef()
        this.plainUrl = url
    }

    ScreenUrlInfo(ScreenFacadeImpl sfi, ScreenDefinition rootSd, ScreenDefinition fromScreenDef,
                  List<String> fpnl, String subscreenPath, Boolean lastStandalone) {
        this.sfi = sfi
        this.ecfi = sfi.getEcfi()
        this.rootSd = rootSd
        fromSd = fromScreenDef
        fromPathList = fpnl
        fromScreenPath = subscreenPath ?: ""
        this.lastStandalone = lastStandalone != null ? lastStandalone : false

        initUrl()
    }

    UrlInstance getInstance(ScreenRenderImpl sri, Boolean expandAliasTransition) {
        return new UrlInstance(this, sri, expandAliasTransition)
    }

    boolean getInCurrentScreenPath(List<String> currentPathNameList) {
        // if currentPathNameList (was from sri.screenUrlInfo) is null it is because this object is not yet set to it, so set this to true as it "is" the current screen path
        if (currentPathNameList == null) return true
        if (minimalPathNameList.size() > currentPathNameList.size()) return false
        for (int i = 0; i < minimalPathNameList.size(); i++) {
            if (minimalPathNameList.get(i) != currentPathNameList.get(i)) return false
        }
        return true
    }

    ScreenDefinition getParentScreen() {
        if (screenRenderDefList.size() > 1) {
            return screenRenderDefList.get(screenRenderDefList.size() - 2)
        } else {
            return null
        }
    }

    boolean isPermitted(ExecutionContext ec) {
        ArtifactExecutionFacadeImpl aefi = (ArtifactExecutionFacadeImpl) ec.getArtifactExecution()
        String username = ec.getUser().getUsername()

        // if a user is permitted to view a certain location once in a render/ec they can safely be always allowed to, so cache it
        // add the username to the key just in case user changes during an EC instance
        String permittedCacheKey = null
        if (fullPathNameList) {
            permittedCacheKey = (username ?: '_anonymous') + fullPathNameList.toString()
            Boolean cachedPermitted = aefi.screenPermittedCache.get(permittedCacheKey)
            if (cachedPermitted != null) return cachedPermitted
        } else {
            // logger.warn("======== Not caching isPermitted, username=${username}, fullPathNameList=${fullPathNameList}")
        }

        Deque<ArtifactExecutionInfoImpl> artifactExecutionInfoStack = new LinkedList<ArtifactExecutionInfoImpl>()

        int index = 1
        for (ScreenDefinition screenDef in screenPathDefList) {
            ArtifactExecutionInfoImpl aeii = new ArtifactExecutionInfoImpl(screenDef.getLocation(), "AT_XML_SCREEN", "AUTHZA_VIEW")

            ArtifactExecutionInfoImpl lastAeii = artifactExecutionInfoStack.peekFirst()

            // logger.warn("TOREMOVE checking screen for user ${username} - ${aeii}")

            boolean isLast = (index == screenPathDefList.size())
            MNode screenNode = screenDef.getScreenNode()

            // if screen is limited to certain tenants, and current tenant is not in the Set, it is not permitted
            if (screenDef.getTenantsAllowed() && !screenDef.getTenantsAllowed().contains(ec.getTenantId())) return false

            String requireAuthentication = screenNode.attribute('require-authentication')
            if (!aefi.isPermitted(username, aeii, lastAeii,
                    isLast ? (!requireAuthentication || requireAuthentication == "true") : false,
                    false, ec.getUser().getNowTimestamp())) {
                // logger.warn("TOREMOVE user ${username} is NOT allowed to view screen at path ${this.fullPathNameList} because of screen at ${screenDef.location}")
                if (permittedCacheKey) aefi.screenPermittedCache.put(permittedCacheKey, false)
                return false
            }

            artifactExecutionInfoStack.addFirst(aeii)
            index++
        }

        // logger.warn("TOREMOVE user ${username} IS allowed to view screen at path ${this.fullPathNameList}")
        if (permittedCacheKey) aefi.screenPermittedCache.put(permittedCacheKey, true)
        return true
    }

    String getBaseUrl(ScreenRenderImpl sri) {
        // support the stub mode for ScreenUrlInfo, representing a plain URL and not a screen URL
        if (plainUrl) return plainUrl

        if (sri == null) return ""
        String baseUrl
        if (sri.baseLinkUrl) {
            baseUrl = sri.baseLinkUrl
            if (baseUrl && baseUrl.charAt(baseUrl.length()-1) == (char) '/') baseUrl = baseUrl.substring(0, baseUrl.length()-1)
        } else {
            if (!sri.webappName) throw new BaseException("No webappName specified, cannot get base URL for screen location ${sri.rootScreenLocation}")
            baseUrl = WebFacadeImpl.getWebappRootUrl(sri.webappName, sri.servletContextPath, true,
                    this.requireEncryption, (ExecutionContextImpl) sri.getEc())
        }
        return baseUrl
    }

    String getUrlWithBase(String baseUrl) {
        StringBuilder urlBuilder = new StringBuilder(baseUrl)
        if (fullPathNameList) {
            int listSize = fullPathNameList.size()
            for (int i = 0; i < listSize; i++) {
                String pathName = fullPathNameList.get(i)
                urlBuilder.append('/').append(pathName)
            }
        }
        return urlBuilder.toString()
    }

    String getMinimalPathUrlWithBase(String baseUrl) {
        StringBuilder urlBuilder = new StringBuilder(baseUrl)
        if (alwaysUseFullPath) {
            // really get the full path instead of minimal
            if (fullPathNameList) {
                int listSize = fullPathNameList.size()
                for (int i = 0; i < listSize; i++) {
                    String pathName = fullPathNameList.get(i)
                    urlBuilder.append('/').append(pathName)
                }
            }
        } else {
            if (minimalPathNameList) {
                int listSize = minimalPathNameList.size()
                for (int i = 0; i < listSize; i++) {
                    String pathName = minimalPathNameList.get(i)
                    urlBuilder.append('/').append(pathName)
                }
            }
        }
        return urlBuilder.toString()
    }

    String getScreenPathUrlWithBase(String baseUrl) {
        StringBuilder urlBuilder = new StringBuilder(baseUrl)
        if (preTransitionPathNameList) for (String pathName in preTransitionPathNameList) urlBuilder.append('/').append(pathName)
        return urlBuilder.toString()
    }

    ArrayList<String> getPreTransitionPathNameList() { return preTransitionPathNameList }
    ArrayList<String> getExtraPathNameList() { return extraPathNameList }

    ScreenUrlInfo addParameter(Object name, Object value) {
        if (!name || value == null) return this
        pathParameterMap.put(name as String, StupidUtilities.toPlainString(value))
        return this
    }
    ScreenUrlInfo addParameters(Map manualParameters) {
        if (!manualParameters) return this
        for (Map.Entry mpEntry in manualParameters.entrySet()) {
            pathParameterMap.put(mpEntry.getKey() as String, StupidUtilities.toPlainString(mpEntry.getValue()))
        }
        return this
    }
    Map getPathParameterMap() { return pathParameterMap }

    void initUrl() {
        // TODO: use this in all calling code (expand url before creating/caching so that we have the full/unique one)
        // support string expansion if there is a "${"
        // if (fromScreenPath.contains('${')) fromScreenPath = ec.getResource().expand(fromScreenPath, "")

        ArrayList<String> subScreenPath = parseSubScreenPath(rootSd, fromSd, fromPathList, fromScreenPath, pathParameterMap, sfi)
        // logger.info("initUrl BEFORE fromPathList=${fromPathList}, fromScreenPath=${fromScreenPath}, subScreenPath=${subScreenPath}")
        if (fromScreenPath.startsWith("//")) {
            // find the screen by name
            fromSd = rootSd
            fromPathList = subScreenPath
            fullPathNameList = subScreenPath
        } else {
            if (this.fromScreenPath.startsWith("/")) {
                this.fromSd = rootSd
                this.fromPathList = new ArrayList<String>()
            }

            fullPathNameList = subScreenPath
        }
        // logger.info("initUrl fromScreenPath=${fromScreenPath}, fromPathList=${fromPathList}, fullPathNameList=${fullPathNameList}")

        // encrypt is the default loop through screens if all are not secure/etc use http setting, otherwise https
        requireEncryption = false
        if (rootSd?.webSettingsNode?.attribute('require-encryption') != "false") requireEncryption = true
        if (rootSd?.screenNode?.attribute('begin-transaction') == "true") beginTransaction = true

        // start the render list with the from/base SD
        screenRenderDefList.add(fromSd)
        screenPathDefList.add(fromSd)

        // loop through path for various things: check validity, see if we can do a transition short-cut and go right
        //     to its response url, etc
        ScreenDefinition lastSd = rootSd
        extraPathNameList = new ArrayList<String>(fullPathNameList)
        for (String pathName in fullPathNameList) {
            String nextLoc = lastSd.getSubscreensItem(pathName)?.location

            if (!nextLoc) {
                // handle case where last one may be a transition name, and not a subscreen name
                if (lastSd.hasTransition(pathName)) {
                    // extra path elements always allowed after transitions for parameters, but we don't want the transition name on it
                    extraPathNameList.remove(0)
                    this.targetTransitionActualName = pathName

                    // break out; a transition means we're at the end
                    break
                }

                // is this a file under the screen?
                ResourceReference existingFileRef = lastSd.getSubContentRef(extraPathNameList)
                if (existingFileRef && existingFileRef.supportsExists() && existingFileRef.exists) {
                    // exclude screen files, don't want to treat them as resources and let them be downloaded
                    if (!sfi.isScreen(existingFileRef.getLocation())) {
                        fileResourceRef = existingFileRef
                        break
                    }
                }

                if (lastSd.screenNode.attribute('allow-extra-path') == "true") {
                    // call it good
                    break
                }

                throw new ScreenResourceNotFoundException(fromSd, fullPathNameList, lastSd, extraPathNameList?.last(), null,
                        new Exception("Screen sub-content not found here"))
            }

            ScreenDefinition nextSd = sfi.getScreenDefinition(nextLoc)
            if (nextSd == null) {
                throw new ScreenResourceNotFoundException(fromSd, fullPathNameList, lastSd, pathName, nextLoc,
                        new Exception("Screen subscreen or transition not found here"))
                // throw new IllegalArgumentException("Could not find screen at location [${nextLoc}], which is subscreen [${pathName}] in relative screen reference [${fromScreenPath}] in screen [${lastSd.location}]")
            }

            if (nextSd.webSettingsNode?.attribute('require-encryption') != "false") this.requireEncryption = true
            if (nextSd.screenNode?.attribute('begin-transaction') == "true") this.beginTransaction = true
            if (nextSd.getSubscreensNode()?.attribute('always-use-full-path') == "true") alwaysUseFullPath = true

            // if standalone, clear out screenRenderDefList before adding this to it
            if (nextSd.isStandalone() || this.lastStandalone) {
                renderPathDifference += screenRenderDefList.size()
                screenRenderDefList.clear()
            }
            screenRenderDefList.add(nextSd)

            screenPathDefList.add(nextSd)
            lastSd = nextSd
            // add this to the list of path names to use for transition redirect
            preTransitionPathNameList.add(pathName)

            // made it all the way to here so this was a screen
            extraPathNameList.remove(0)
        }

        // save the path so far for minimal URLs
        minimalPathNameList = new ArrayList<String>(fullPathNameList)

        // beyond the last screenPathName, see if there are any screen.default-item values (keep following until none found)
        int defaultSubScreenCount = 0
        while (targetTransitionActualName == null && fileResourceRef == null && lastSd.getDefaultSubscreensItem()) {
            if (lastSd.getSubscreensNode()?.attribute('always-use-full-path') == "true") alwaysUseFullPath = true
            // logger.warn("TOREMOVE lastSd ${minimalPathNameList} subscreens: ${lastSd.screenNode?.subscreens}, alwaysUseFullPath=${alwaysUseFullPath}, from ${lastSd.screenNode."subscreens"?."@always-use-full-path"?.getAt(0)}, subscreenName=${subscreenName}")

            // determine the subscreen name
            String subscreenName = null

            // check SubscreensDefault records
            EntityCondition tenantCond = ecfi.entity.conditionFactory.makeCondition(
                    ecfi.entity.conditionFactory.makeCondition("tenantId", EntityCondition.EQUALS, ecfi.eci.tenantId),
                    EntityCondition.OR,
                    ecfi.entity.conditionFactory.makeCondition("tenantId", EntityCondition.EQUALS, null))
            EntityList subscreensDefaultList = ecfi.entity.find("moqui.screen.SubscreensDefault").condition(tenantCond)
                    .condition("screenLocation", lastSd.location).useCache(true).disableAuthz().list()
            for (int i = 0; i < subscreensDefaultList.size(); i++) {
                EntityValue subscreensDefault = subscreensDefaultList.get(i)
                String condStr = (String) subscreensDefault.condition
                if (condStr && !ecfi.getResource().condition(condStr, "SubscreensDefault_condition")) continue
                subscreenName = subscreensDefault.subscreenName
            }

            // if any conditional-default.@condition eval to true, use that conditional-default.@item instead
            List<MNode> condDefaultList = lastSd.getSubscreensNode()?.children("conditional-default")
            if (condDefaultList) for (MNode conditionalDefaultNode in condDefaultList) {
                String condStr = conditionalDefaultNode.attribute('condition')
                if (!condStr) continue
                if (ecfi.getResource().condition(condStr, null)) {
                    subscreenName = conditionalDefaultNode.attribute('item')
                    break
                }
            }

            // whether we got a hit or not there are conditional defaults for this path, so can't reuse this instance
            if (subscreensDefaultList || condDefaultList) reusable = false

            if (!subscreenName) subscreenName = lastSd.getDefaultSubscreensItem()

            String nextLoc = lastSd.getSubscreensItem(subscreenName)?.location
            if (!nextLoc) {
                // handle case where last one may be a transition name, and not a subscreen name
                if (lastSd.hasTransition(subscreenName)) {
                    targetTransitionActualName = subscreenName
                    fullPathNameList.add(subscreenName)
                    break
                }

                // is this a file under the screen?
                ResourceReference existingFileRef = lastSd.getSubContentRef([subscreenName])
                if (existingFileRef && existingFileRef.supportsExists() && existingFileRef.exists) {
                    fileResourceRef = existingFileRef
                    fullPathNameList.add(subscreenName)
                    break
                }

                throw new ScreenResourceNotFoundException(fromSd, fullPathNameList, lastSd, subscreenName, null,
                        new Exception("Screen subscreen or transition not found here"))
                // throw new BaseException("Could not find subscreen or transition [${subscreenName}] in screen [${lastSd.location}]")
            }
            ScreenDefinition nextSd = sfi.getScreenDefinition(nextLoc)
            if (nextSd == null) {
                throw new ScreenResourceNotFoundException(fromSd, fullPathNameList, lastSd, subscreenName, nextLoc,
                        new Exception("Screen subscreen or transition not found here"))
                // throw new BaseException("Could not find screen at location [${nextLoc}], which is default subscreen [${subscreenName}] in screen [${lastSd.location}]")
            }

            if (nextSd.webSettingsNode?.attribute('require-encryption') != "false") this.requireEncryption = true
            if (nextSd.screenNode?.attribute('begin-transaction') == "true") this.beginTransaction = true

            // if standalone, clear out screenRenderDefList before adding this to it
            if (nextSd.isStandalone() || this.lastStandalone) {
                renderPathDifference += screenRenderDefList.size()
                screenRenderDefList.clear()
            }
            screenRenderDefList.add(nextSd)

            screenPathDefList.add(nextSd)
            lastSd = nextSd
            // for use in URL writing and such add the subscreenName we found to the main path name list
            fullPathNameList.add(subscreenName)
            // add this to the list of path names to use for transition redirect, just in case a default is a transition
            preTransitionPathNameList.add(subscreenName)

            defaultSubScreenCount++
        }

        this.targetScreen = lastSd

        // screenRenderDefList now in place, look for menu-image and menu-image-type of last in list
        int renderListSize = screenRenderDefList.size()
        int defaultSubScreenLimit = renderListSize - defaultSubScreenCount - 1
        for (int i = 0; i < renderListSize; i++) {
            // only use explicit path to find icon, don't want default subscreens overriding it
            ScreenDefinition curSd = screenRenderDefList.get(i)
            String curMenuImage = curSd.getScreenNode().attribute("menu-image")
            if (curMenuImage) {
                menuImage = curMenuImage
                menuImageType = curSd.getScreenNode().attribute("menu-image-type") ?: 'url-screen'
            }
            if (i >= defaultSubScreenLimit && menuImage) break
        }
    }

    @Override
    String toString() {
        // return ONLY the url built from the inputs; that is the most basic possible value
        return this.getUrlWithBase(getBaseUrl(null))
    }

    ScreenUrlInfo cloneUrlInfo() {
        ScreenUrlInfo sui = new ScreenUrlInfo()
        this.copyUrlInfoInto(sui)
        return sui
    }

    void copyUrlInfoInto(ScreenUrlInfo sui) {
        sui.sfi = this.sfi
        sui.rootSd = this.rootSd
        sui.fromSd = this.fromSd
        sui.fromPathList = this.fromPathList!=null ? new ArrayList<String>(this.fromPathList) : null
        sui.fromScreenPath = this.fromScreenPath
        sui.pathParameterMap = this.pathParameterMap!=null ? new HashMap(this.pathParameterMap) : null
        sui.requireEncryption = this.requireEncryption
        sui.beginTransaction = this.beginTransaction
        sui.fullPathNameList = this.fullPathNameList!=null ? new ArrayList<String>(this.fullPathNameList) : null
        sui.minimalPathNameList = this.minimalPathNameList!=null ? new ArrayList<String>(this.minimalPathNameList) : null
        sui.fileResourcePathList = this.fileResourcePathList!=null ? new ArrayList<String>(this.fileResourcePathList) : null
        sui.fileResourceRef = this.fileResourceRef
        sui.fileResourceContentType = this.fileResourceContentType
        sui.screenPathDefList = this.screenPathDefList!=null ? new ArrayList<ScreenDefinition>(this.screenPathDefList) : null
        sui.screenRenderDefList = this.screenRenderDefList!=null ? new ArrayList<ScreenDefinition>(this.screenRenderDefList) : null
        sui.renderPathDifference = this.renderPathDifference
        sui.lastStandalone = this.lastStandalone
        sui.targetScreen = this.targetScreen
        sui.targetTransitionActualName = this.targetTransitionActualName
        sui.preTransitionPathNameList = this.preTransitionPathNameList!=null ? new ArrayList<String>(this.preTransitionPathNameList) : null
    }

    static ArrayList<String> parseSubScreenPath(ScreenDefinition rootSd, ScreenDefinition fromSd, List<String> fromPathList,
                                                String screenPath, Map inlineParameters, ScreenFacadeImpl sfi) {
        // if there are any ?... parameters parse them off and remove them from the string
        int indexOfQuestionMark = screenPath.indexOf("?")
        if (indexOfQuestionMark > 0) {
            String pathParmString = screenPath.substring(indexOfQuestionMark + 1)
            if (inlineParameters != null && pathParmString) {
                List<String> nameValuePairs = pathParmString.replaceAll("&amp;", "&").split("&") as List
                for (String nameValuePair in nameValuePairs) {
                    String[] nameValue = nameValuePair.substring(0).split("=")
                    if (nameValue.length == 2) inlineParameters.put(nameValue[0], nameValue[1])
                }
            }
            screenPath = screenPath.substring(0, indexOfQuestionMark)
        }

        if (screenPath.startsWith("//")) {
            // find the screen by name
            String trimmedFromPath = screenPath.substring(2)
            ArrayList<String> originalPathNameList = new ArrayList<String>(trimmedFromPath.split("/") as List)
            originalPathNameList = cleanupPathNameList(originalPathNameList, inlineParameters)

            if (sfi.screenFindPathCache.containsKey(screenPath)) {
                ArrayList<String> cachedPathList = (ArrayList<String>) sfi.screenFindPathCache.get(screenPath)
                if (cachedPathList) {
                    return cachedPathList
                } else {
                    throw new ScreenResourceNotFoundException(fromSd, originalPathNameList, fromSd, screenPath, null,
                            new Exception("Could not find screen, transition or content matching path"))
                }
            } else {
                ArrayList<String> expandedPathNameList = rootSd.findSubscreenPath(originalPathNameList)
                sfi.screenFindPathCache.put(screenPath, expandedPathNameList)
                if (expandedPathNameList) {
                    return expandedPathNameList
                } else {
                    throw new ScreenResourceNotFoundException(fromSd, originalPathNameList, fromSd, screenPath, null,
                            new Exception("Could not find screen, transition or content matching path"))
                }
            }
        } else {
            if (screenPath.startsWith("/")) fromPathList = new ArrayList<String>()

            ArrayList<String> tempPathNameList = new ArrayList<String>()
            tempPathNameList.addAll(fromPathList)
            tempPathNameList.addAll(screenPath.split("/") as List)
            return cleanupPathNameList(tempPathNameList, inlineParameters)
        }
    }

    static ArrayList<String> cleanupPathNameList(ArrayList<String> inputPathNameList, Map inlineParameters) {
        // filter the list: remove empty, remove ".", remove ".." and previous
        ArrayList<String> cleanList = new ArrayList<String>(inputPathNameList.size())
        for (String pathName in inputPathNameList) {
            if (!pathName) continue
            if (pathName == ".") continue
            // .. means go up a level, ie drop the last in the list
            if (pathName == "..") { if (cleanList.size()) cleanList.remove(cleanList.size()-1); continue }
            // if it has a tilde it is a parameter, so skip it but remember it
            if (pathName.startsWith("~")) {
                if (inlineParameters != null) {
                    String[] nameValue = pathName.substring(1).split("=")
                    if (nameValue.length == 2) inlineParameters.put(nameValue[0], nameValue[1])
                }
                continue
            }
            cleanList.add(pathName)
        }
        return cleanList
    }

    @CompileStatic
    static class UrlInstance {
        ScreenUrlInfo sui
        ScreenRenderImpl sri
        ExecutionContext ec
        boolean expandAliasTransition

        /** If a transition is specified, the target transition within the targetScreen */
        TransitionItem curTargetTransition = null

        Map<String, String> otherParameterMap = new HashMap<String, String>()
        Map transitionAliasParameters = null
        Map<String, String> allParameterMap = null

        UrlInstance(ScreenUrlInfo sui, ScreenRenderImpl sri, Boolean expandAliasTransition) {
            this.sui = sui
            this.sri = sri
            ec = sri.getEc()

            this.expandAliasTransition = expandAliasTransition != null ? expandAliasTransition : true
            if (this.expandAliasTransition) expandTransitionAliasUrl()

            // logger.warn("======= Creating UrlInstance ${sui.getFullPathNameList()} - ${sui.targetScreen.getLocation()} - ${sui.getTargetTransitionActualName()}")
        }

        String getRequestMethod() { return ec.web ? ec.web.request.method : "" }
        TransitionItem getTargetTransition() {
            if (curTargetTransition == null && sui.targetScreen != null && sui.targetTransitionActualName)
                curTargetTransition = sui.targetScreen.getTransitionItem(sui.targetTransitionActualName, getRequestMethod())
            return curTargetTransition
        }
        boolean getHasActions() { getTargetTransition() && getTargetTransition().actions }
        boolean getDisableLink() { return (getTargetTransition() && !getTargetTransition().checkCondition(ec)) || !sui.isPermitted(ec) }
        boolean isPermitted() { return sui.isPermitted(ec) }
        boolean getInCurrentScreenPath() {
            List<String> currentPathNameList = new ArrayList<String>(sri.screenUrlInfo.fullPathNameList)
            return sui.getInCurrentScreenPath(currentPathNameList)
        }

        void expandTransitionAliasUrl() {
            TransitionItem ti = getTargetTransition()
            if (ti == null) return

            // Screen Transition as a URL Alias:
            // if fromScreenPath is a transition, and that transition has no condition,
            // service/actions or conditional-response then use the default-response.url instead
            // of the name (if type is screen-path or empty, url-type is url or empty)
            if (ti.condition == null && !ti.hasActionsOrSingleService() && !ti.conditionalResponseList &&
                    ti.defaultResponse && ti.defaultResponse.type == "url" &&
                    ti.defaultResponse.urlType == "screen-path" && ec.web != null) {


                transitionAliasParameters = ti.defaultResponse.expandParameters(sui.getExtraPathNameList(), ec)

                // create a ScreenUrlInfo, then copy its info into this
                String expandedUrl = ti.defaultResponse.url
                if (expandedUrl.contains('${')) expandedUrl = ec.getResource().expand(expandedUrl, "")
                ScreenUrlInfo aliasUrlInfo = getScreenUrlInfo(sri.getSfi(), sui.rootSd, sui.fromSd,
                        sui.preTransitionPathNameList, expandedUrl,
                        (sui.lastStandalone || transitionAliasParameters.lastStandalone == "true"))

                this.sui = aliasUrlInfo
                this.curTargetTransition = null
            }
        }
        Map getTransitionAliasParameters() { return transitionAliasParameters }

        String getUrl() { return sui.getUrlWithBase(sui.getBaseUrl(sri)) }
        String getUrlWithParams() {
            String ps = getParameterString()
            return getUrl() + (ps ? "?" + ps : "")
        }

        String getMinimalPathUrl() { return sui.getMinimalPathUrlWithBase(sui.getBaseUrl(sri)) }
        String getMinimalPathUrlWithParams() {
            String ps = getParameterString()
            return getMinimalPathUrl() + (ps ? "?" + ps : "")
        }

        String getScreenPathUrl() { return sui.getScreenPathUrlWithBase(sui.getBaseUrl(sri)) }

        Map<String, String> getParameterMap() {
            if (allParameterMap != null) return allParameterMap

            allParameterMap = new HashMap<>()
            // get default parameters for the target screen
            if (sui.targetScreen != null) {
                for (ParameterItem pi in sui.targetScreen.getParameterMap().values()) {
                    Object value = pi.getValue(ec)
                    if (value) allParameterMap.put(pi.name, StupidUtilities.toPlainString(value))
                }
            }
            if (targetTransition != null && targetTransition.getParameterMap()) {
                for (ParameterItem pi in targetTransition.getParameterMap().values()) {
                    Object value = pi.getValue(ec)
                    if (value) allParameterMap.put(pi.name, StupidUtilities.toPlainString(value))
                }
            }
            if (targetTransition != null && targetTransition.getSingleServiceName()) {
                String targetServiceName = targetTransition.getSingleServiceName()
                ServiceDefinition sd = ((ServiceFacadeImpl) ec.getService()).getServiceDefinition(targetServiceName)
                if (sd != null) {
                    for (String pn in sd.getInParameterNames()) {
                        Object value = ec.getContext().get(pn)
                        if (!value && ec.getWeb() != null) value = ec.getWeb().getParameters().get(pn)
                        if (value) allParameterMap.put(pn, StupidUtilities.toPlainString(value))
                    }
                } else if (targetServiceName.contains("#")) {
                    // service name but no service def, see if it is an entity op and if so try the pk fields
                    String verb = targetServiceName.substring(0, targetServiceName.indexOf("#"))
                    if (verb == "create" || verb == "update" || verb == "delete" || verb == "store") {
                        String en = targetServiceName.substring(targetServiceName.indexOf("#") + 1)
                        EntityDefinition ed = ((EntityFacadeImpl) ec.getEntity()).getEntityDefinition(en)
                        if (ed != null) {
                            for (String fn in ed.getPkFieldNames()) {
                                Object value = ec.getContext().get(fn)
                                if (!value && ec.getWeb() != null) value = ec.getWeb().getParameters().get(fn)
                                if (value) allParameterMap.put(fn, StupidUtilities.toPlainString(value))
                            }
                        }
                    }
                }
            }
            // add all of the parameters specified inline in the screen path or added after
            if (sui.pathParameterMap) allParameterMap.putAll(sui.pathParameterMap)
            // add transition parameters, for alias transitions
            if (transitionAliasParameters) allParameterMap.putAll(transitionAliasParameters)
            // add all parameters added to the instance after
            allParameterMap.putAll(otherParameterMap)

            // logger.info("TOREMOVE Getting parameterMap [${pm}] for targetScreen [${targetScreen.location}]")
            return allParameterMap
        }

        String getParameterString() {
            StringBuilder ps = new StringBuilder()
            Map<String, String> pm = this.getParameterMap()
            for (Map.Entry<String, String> pme in pm.entrySet()) {
                if (!pme.value) continue
                if (pme.key == "moquiSessionToken") continue
                if (ps.length() > 0) ps.append("&")
                ps.append(pme.key).append("=").append(urlCodec.encode(pme.value))
            }
            return ps.toString()
        }
        String getParameterPathString() {
            StringBuilder ps = new StringBuilder()
            Map<String, String> pm = this.getParameterMap()
            for (Map.Entry<String, String> pme in pm.entrySet()) {
                if (!pme.getValue()) continue
                ps.append("/~")
                ps.append(pme.getKey()).append("=").append(urlCodec.encode(pme.getValue()))
            }
            return ps.toString()
        }

        UrlInstance addParameter(Object name, Object value) {
            if (!name || value == null) return this
            String parmValue = StupidUtilities.toPlainString(value)
            otherParameterMap.put(name as String, parmValue)
            if (allParameterMap != null) allParameterMap.put(name as String, parmValue)
            return this
        }
        UrlInstance addParameters(Map manualParameters) {
            if (!manualParameters) return this
            for (Map.Entry mpEntry in manualParameters.entrySet()) {
                String parmKey = mpEntry.getKey() as String
                String parmValue = StupidUtilities.toPlainString(mpEntry.getValue())
                otherParameterMap.put(parmKey, parmValue)
                if (allParameterMap != null) allParameterMap.put(parmKey, parmValue)
            }
            return this
        }
        Map getOtherParameterMap() { return otherParameterMap }

        UrlInstance passThroughSpecialParameters() {
            copySpecialParameters(ec.context, otherParameterMap)
            return this
        }
        static void copySpecialParameters(Map fromMap, Map toMap) {
            if (!fromMap || !toMap) return
            for (String fieldName in fromMap.keySet()) {
                if (fieldName.startsWith("formDisplayOnly")) toMap.put(fieldName, (String) fromMap.get(fieldName))
            }
            if (fromMap.containsKey("pageNoLimit")) toMap.put("pageNoLimit", (String) fromMap.get("pageNoLimit"))
            if (fromMap.containsKey("lastStandalone")) toMap.put("lastStandalone", (String) fromMap.get("lastStandalone"))
            if (fromMap.containsKey("renderMode")) toMap.put("renderMode", (String) fromMap.get("renderMode"))
        }

        UrlInstance cloneUrlInstance() {
            UrlInstance ui = new UrlInstance(sui, sri, expandAliasTransition)
            ui.curTargetTransition = curTargetTransition
            if (otherParameterMap) ui.otherParameterMap = new HashMap<String, String>(otherParameterMap)
            if (transitionAliasParameters) ui.transitionAliasParameters = new HashMap(transitionAliasParameters)
            return ui
        }

        @Override
        String toString() {
            // return ONLY the url built from the inputs; that is the most basic possible value
            return this.getUrl()
        }
    }
}
