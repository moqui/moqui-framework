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
package org.moqui.impl.llm;

import org.moqui.context.ExecutionContext;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.impl.screen.ScreenDefinition;
import org.moqui.impl.screen.ScreenDefinition.SubscreensItem;
import org.moqui.impl.screen.ScreenFacadeImpl;
import org.moqui.impl.screen.ScreenUrlInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Standing prompt text for QuickSearch, QuickLookup, and mantle Search mounts this user can view.
 * Paths come from the screen tree. No extra authz grant and no fallback service.
 * Catalog product search is a different screen and is not included.
 */
public final class ScreenSearchHints {
    private static final Logger logger = LoggerFactory.getLogger(ScreenSearchHints.class);
    static final String QUICK_SEARCH = "component://SimpleScreens/screen/SimpleScreens/QuickSearch.xml";
    static final String QUICK_LOOKUP = "component://SimpleScreens/screen/SimpleScreens/QuickLookup.xml";
    /** Mantle document search. Not {@code Catalog/Search.xml}. */
    static final String MANTLE_SEARCH = "component://SimpleScreens/screen/SimpleScreens/Search.xml";
    private static final int MAX_DEPTH = 8;
    private static final int MAX_MOUNTS = 12;

    private ScreenSearchHints() { }

    public static String text(ExecutionContext ec) {
        return render(find(ec));
    }

    public static String render(List<Mount> mounts) {
        if (mounts == null || mounts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("GET these read-only actions paths with request (use /apps, not /qapps). ");
        sb.append("They do not need a sim and they do not wait for confirm. ");
        sb.append("Link the user to the same path under /qapps, without /actions.\n");
        sb.append("One hit: use that id. Many hits: a table the user picks. No hit: then a create.\n");
        boolean lookupMaps = false;
        for (Mount m : mounts) {
            if (m.lookupMaps) lookupMaps = true;
            if (m.lookup) {
                sb.append("- Lookup by id: GET ").append(m.actionsPath).append(" with query lookupId.\n");
            } else {
                sb.append("- Search: GET ").append(m.actionsPath)
                        .append(" with query queryString. Optional documentType is a dataDocumentId from that screen.\n");
            }
        }
        sb.append("The JSON is screen context. Read documentList for search hits (id, type, title fields on each hit). ");
        if (lookupMaps) {
            sb.append("Read lookup maps when present: orderHeader/orderId, invoice/invoiceId, party/partyId, ");
            sb.append("product/productId/productList, shipment/shipmentId, workEffort/workEffortId, payment/paymentId, ");
            sb.append("asset/assetId, facility/facilityId, returnHeader/returnId, acctgTrans/acctgTransId, ");
            sb.append("container, partyBadge. ");
        }
        sb.append("Detail links are not in that JSON. browse the same app for the detail screen.\n");
        return sb.toString();
    }

    /**
     * True when this root actions path is mantle Search.xml.
     * The path segment is often {@code Search}, which catalog product search also uses, so the screen
     * location is resolved and the name alone is not enough.
     */
    public static boolean isMantleSearchActions(ExecutionContext ec, List<String> segments) {
        if (!(ec instanceof ExecutionContextImpl)) return false;
        if (segments == null || segments.size() < 2) return false;
        if (!"actions".equals(segments.get(segments.size() - 1))) return false;
        String screen = segments.get(segments.size() - 2);
        if (screen == null || !"search".equals(screen.toLowerCase(Locale.ROOT))) return false;
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < segments.size() - 1; i++) path.append('/').append(segments.get(i));
        return MANTLE_SEARCH.equals(locationOf((ExecutionContextImpl) ec, path.toString()));
    }

    static List<Mount> find(ExecutionContext ec) {
        List<Mount> out = new ArrayList<>();
        if (!(ec instanceof ExecutionContextImpl)) return out;
        ExecutionContextImpl eci = (ExecutionContextImpl) ec;
        ScreenFacadeImpl sfi = eci.screenFacade;
        if (sfi == null) return out;
        ScreenDefinition root = webroot(eci);
        ScreenDefinition apps = BrowseTool.getAppsScreen(eci);
        if (root == null || apps == null) return out;
        walk(eci, sfi, root, apps, "/apps", 1, new LinkedHashSet<>(), out);
        return out;
    }

    private static void walk(ExecutionContextImpl eci, ScreenFacadeImpl sfi, ScreenDefinition root,
            ScreenDefinition sd, String path, int depth, Set<String> seen, List<Mount> out) {
        if (sd == null || depth > MAX_DEPTH || out.size() >= MAX_MOUNTS) return;
        List<SubscreensItem> items;
        try {
            items = sd.getSubscreensItemsSorted();
        } catch (Throwable t) {
            logger.debug("subscreens under {}: {}", path, t.getMessage());
            return;
        }
        if (items == null) return;
        for (SubscreensItem si : items) {
            if (out.size() >= MAX_MOUNTS) return;
            if (si == null || si.getName() == null || si.getLocation() == null) continue;
            try {
                if (!si.isValidInCurrentContext()) continue;
            } catch (Throwable t) {
                continue;
            }
            String childPath = path + "/" + si.getName();
            String seenKey = childPath + " " + si.getLocation();
            if (!seen.add(seenKey)) continue;
            boolean lookup = QUICK_LOOKUP.equals(si.getLocation());
            boolean quickSearch = QUICK_SEARCH.equals(si.getLocation());
            boolean mantleSearch = MANTLE_SEARCH.equals(si.getLocation());
            if ((lookup || quickSearch || mantleSearch) && permitted(eci, sfi, root, childPath, si.getLocation())) {
                out.add(new Mount(lookup, lookup || quickSearch, childPath, childPath + "/actions"));
            }
            if (depth >= MAX_DEPTH) continue;
            ScreenDefinition child;
            try {
                child = sfi.getScreenDefinition(si.getLocation());
            } catch (Throwable t) {
                continue;
            }
            if (child != null && child != sd) walk(eci, sfi, root, child, childPath, depth + 1, seen, out);
        }
    }

    private static boolean permitted(ExecutionContextImpl eci, ScreenFacadeImpl sfi, ScreenDefinition root,
            String path, String location) {
        try {
            ScreenUrlInfo sui = ScreenUrlInfo.getScreenUrlInfo(sfi, root, root, new ArrayList<>(), path, 0);
            ScreenDefinition target = sui.getTargetScreen();
            if (target == null || target.getLocation() == null) return false;
            if (!location.equals(target.getLocation())) return false;
            return sui.isPermitted(eci, null);
        } catch (Throwable t) {
            logger.debug("search screen authz {} : {}", path, t.getMessage());
            return false;
        }
    }

    private static String locationOf(ExecutionContextImpl eci, String path) {
        ScreenFacadeImpl sfi = eci.screenFacade;
        if (sfi == null || path == null) return null;
        ScreenDefinition root = webroot(eci);
        if (root == null) return null;
        try {
            ScreenUrlInfo sui = ScreenUrlInfo.getScreenUrlInfo(sfi, root, root, new ArrayList<>(), path, 0);
            ScreenDefinition target = sui.getTargetScreen();
            if (target == null) return null;
            return target.getLocation();
        } catch (Throwable t) {
            logger.debug("search screen resolve {} : {}", path, t.getMessage());
            return null;
        }
    }

    private static ScreenDefinition webroot(ExecutionContextImpl eci) {
        ScreenFacadeImpl sfi = eci.screenFacade;
        List<String> roots = sfi.getAllRootScreenLocations();
        if (roots == null) return null;
        for (String loc : roots) {
            ScreenDefinition root = sfi.getScreenDefinition(loc);
            if (root != null && root.getSubscreensItem("apps") != null) return root;
        }
        return null;
    }

    public static final class Mount {
        /** QuickLookup: the prompt line is lookupId. QuickSearch and mantle Search use queryString. */
        final boolean lookup;
        /** QuickSearch and QuickLookup return id maps. Mantle Search returns documentList only. */
        final boolean lookupMaps;
        final String screenPath;
        final String actionsPath;
        public Mount(boolean lookup, boolean lookupMaps, String screenPath, String actionsPath) {
            this.lookup = lookup;
            this.lookupMaps = lookupMaps;
            this.screenPath = screenPath;
            this.actionsPath = actionsPath;
        }
    }
}
