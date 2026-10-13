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
package org.moqui.impl.llm.tools

import groovy.transform.CompileStatic
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.screen.ScreenDefinition
import org.moqui.impl.screen.ScreenDefinition.SubscreensItem
import org.moqui.impl.screen.ScreenDefinition.TransitionItem
import org.moqui.impl.screen.ScreenFacadeImpl
import org.moqui.impl.screen.ScreenUrlInfo
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Resolves catalog paths (/qapps/a/b, /apps/a/b) to the screen tree and answers the permission questions
 * that browse, screen_use and request ask about a screen or transition. Never disables authz.
 */
@CompileStatic
class ScreenCatalog {
    private static final Logger logger = LoggerFactory.getLogger(ScreenCatalog.class)

    static ScreenDefinition getAppsScreen(ExecutionContextImpl eci) {
        ScreenFacadeImpl sfi = eci.screenFacade
        List<String> roots = sfi.getAllRootScreenLocations()
        for (String loc : roots) {
            ScreenDefinition root = sfi.getScreenDefinition(loc)
            if (root == null) continue
            SubscreensItem apps = root.getSubscreensItem("apps")
            if (apps == null) continue
            ScreenDefinition appsSd = sfi.getScreenDefinition(apps.location)
            if (appsSd != null) return appsSd
        }
        return null
    }


    static ScreenDefinition webrootWithApps(ExecutionContextImpl eci) {
        if (eci == null) return null
        ScreenFacadeImpl sfi = eci.screenFacade
        List<String> roots = sfi.getAllRootScreenLocations()
        for (String loc : roots) {
            ScreenDefinition root = sfi.getScreenDefinition(loc)
            if (root != null && root.getSubscreensItem("apps") != null) return root
        }
        return null
    }
    /** Catalog path /qapps/a/b or /apps/a/b becomes the webroot path list [apps, a, b]. */
    static ArrayList<String> appsPathList(String catalogPath) {
        ArrayList<String> out = new ArrayList<>()
        List<String> segs = BrowseTool.splitPath(catalogPath == null ? "" : catalogPath)
        if (segs.isEmpty()) { out.add("apps"); return out }
        String root = segs.get(0)
        if (!"qapps".equals(root) && !"apps".equals(root) && !"vapps".equals(root)) return null
        out.add("apps")
        for (int i = 1; i < segs.size(); i++) out.add(segs.get(i))
        return out
    }
    static ScreenUrlInfo screenUrl(ExecutionContextImpl eci, String catalogPath) {
        ArrayList<String> path = appsPathList(catalogPath)
        if (path == null || eci == null) return null
        ScreenDefinition root = webrootWithApps(eci)
        if (root == null) return null
        try {
            return ScreenUrlInfo.getScreenUrlInfo(eci.screenFacade, root, root, path, null, 0)
        } catch (Throwable t) {
            logger.debug("browse screen url failed for ${catalogPath}: ${t.message}")
            return null
        }
    }
    static boolean screenViewPermitted(ExecutionContextImpl eci, String catalogPath) {
        ScreenUrlInfo sui = screenUrl(eci, catalogPath)
        if (sui == null || !sui.targetExists) return false
        try {
            return sui.isPermitted(eci, (TransitionItem) null)
        } catch (Throwable t) {
            logger.debug("browse screen authz failed for ${catalogPath}: ${t.message}")
            return false
        }
    }
    static boolean transitionPermitted(ExecutionContextImpl eci, String screenCatalogPath, TransitionItem ti) {
        if (ti == null || eci == null) return false
        ScreenUrlInfo sui = screenUrl(eci, screenCatalogPath)
        if (sui == null || !sui.targetExists) return false
        try {
            return sui.isPermitted(eci, ti)
        } catch (Throwable t) {
            logger.debug("browse transition authz failed for ${screenCatalogPath}/${ti.name}: ${t.message}")
            return false
        }
    }
}
