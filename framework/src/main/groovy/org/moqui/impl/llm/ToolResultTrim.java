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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shrink a tool result by whole values. A list stays a list of whole elements.
 * A map keeps its keys. No mid-string slice of a JSON document.
 */
public final class ToolResultTrim {
    static final int STRING_CAP = 400;
    static final int LIST_CAP = 20;
    static final int SEARCH_LIST_CAP = 12;
    private static final Set<String> PRIORITY = new LinkedHashSet<>();
    private static final Set<String> SEARCH_KEYS = new LinkedHashSet<>();
    static {
        String[] priority = { "error", "instruction", "hint", "select", "selected", "proposedSkillName",
                "proposedSkillId", "proposedSkillStatus", "sim", "simActive", "status", "ok", "messages",
                "serviceName", "text" };
        for (String k : priority) PRIORITY.add(k);
        String[] search = { "documentList", "queryString", "originalQueryString", "documentType", "lookupId",
                "orderHeader", "orderId", "invoice", "invoiceId", "party", "partyId", "product", "productId",
                "productList", "shipment", "shipmentId", "workEffort", "workEffortId", "payment", "paymentId",
                "asset", "assetId", "facility", "facilityId", "returnHeader", "returnId", "acctgTrans",
                "acctgTransId", "container", "containerId", "partyBadge", "partyBadgeId", "messages" };
        for (String k : search) SEARCH_KEYS.add(k);
    }

    private ToolResultTrim() { }

    /**
     * QuickSearch, QuickLookup, and mantle Search root actions return the whole screen context.
     * Keep the search and lookup keys the prompt names, capped, and drop the rest.
     * Returns the original object when none of those keys are present.
     */
    @SuppressWarnings("unchecked")
    public static Object projectSearchActions(Object json) {
        if (!(json instanceof Map)) return json;
        Map<String, Object> src = (Map<String, Object>) json;
        Map<String, Object> kept = new LinkedHashMap<>();
        for (String key : SEARCH_KEYS) {
            if (!src.containsKey(key) || src.get(key) == null) continue;
            Object value = src.get(key);
            if ("documentList".equals(key) || "productList".equals(key))
                value = capList(value, SEARCH_LIST_CAP);
            kept.put(key, shrinkValue(value, STRING_CAP, LIST_CAP));
        }
        if (kept.isEmpty()) return json;
        return kept;
    }

    /**
     * @param maxChars maximum serialized size; values at or under this are returned unchanged
     */
    public static Object limit(Object result, int maxChars) {
        if (result == null || maxChars < 1) return result;
        String raw = json(result);
        if (raw == null || raw.length() <= maxChars) return result;
        if (result instanceof Map) {
            Map<String, Object> copy = shrinkMap((Map<?, ?>) result, STRING_CAP, LIST_CAP);
            copy.put("truncated", Boolean.TRUE);
            copy.put("size", raw.length());
            copyPriority((Map<?, ?>) result, copy);
            fit(copy, maxChars);
            return copy;
        }
        if (result instanceof List) {
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("truncated", Boolean.TRUE);
            wrap.put("size", raw.length());
            wrap.put("items", capList(result, LIST_CAP));
            fit(wrap, maxChars);
            return wrap;
        }
        Map<String, Object> wrap = new LinkedHashMap<>();
        wrap.put("truncated", Boolean.TRUE);
        wrap.put("size", raw.length());
        String text = raw.length() > STRING_CAP ? raw.substring(0, STRING_CAP) + "…" : raw;
        wrap.put("text", text);
        return wrap;
    }

    static boolean hasErrorMessages(Map<?, ?> stored) {
        if (stored == null) return false;
        Object messages = stored.get("messages");
        if (!(messages instanceof Map)) return false;
        Map<?, ?> m = (Map<?, ?>) messages;
        if (nonEmptyList(m.get("errors"))) return true;
        return nonEmptyList(m.get("validationErrors"));
    }

    private static boolean nonEmptyList(Object v) {
        return v instanceof List && !((List<?>) v).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static void fit(Map<String, Object> copy, int maxChars) {
        for (int n = 0; n < 40; n++) {
            String now = json(copy);
            if (now == null || now.length() <= maxChars) return;
            String drop = largestDroppable(copy);
            if (drop == null) break;
            copy.remove(drop);
        }
        String now = json(copy);
        if (now != null && now.length() > maxChars) {
            for (Map.Entry<String, Object> e : new ArrayList<>(copy.entrySet())) {
                String key = String.valueOf(e.getKey());
                if ("error".equals(key) || "instruction".equals(key) || "hint".equals(key)) continue;
                if (e.getValue() instanceof String && ((String) e.getValue()).length() > 80)
                    copy.put(e.getKey(), ((String) e.getValue()).substring(0, 80) + "…");
            }
        }
    }

    private static String largestDroppable(Map<String, Object> copy) {
        String drop = null;
        int best = -1;
        for (Map.Entry<String, Object> e : copy.entrySet()) {
            if (PRIORITY.contains(e.getKey()) || "truncated".equals(e.getKey()) || "size".equals(e.getKey()))
                continue;
            String piece = json(e.getValue());
            int len = piece != null ? piece.length() : 0;
            if (len > best) {
                best = len;
                drop = e.getKey();
            }
        }
        return drop;
    }

    private static void copyPriority(Map<?, ?> src, Map<String, Object> dest) {
        for (String key : PRIORITY) {
            if (src.containsKey(key) && src.get(key) != null && !dest.containsKey(key))
                dest.put(key, shrinkValue(src.get(key), STRING_CAP, LIST_CAP));
        }
    }

    private static Map<String, Object> shrinkMap(Map<?, ?> src, int stringCap, int listCap) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : src.entrySet()) {
            if (e.getKey() == null) continue;
            String key = e.getKey().toString();
            Object value = e.getValue();
            if ("headers".equals(key)) continue;
            if ("rows".equals(key) || "documentList".equals(key) || "productList".equals(key))
                value = capList(value, "documentList".equals(key) ? SEARCH_LIST_CAP : listCap);
            else if ("json".equals(key) && value instanceof Map)
                value = shrinkMap((Map<?, ?>) value, stringCap, listCap);
            else
                value = shrinkValue(value, stringCap, listCap);
            if (value != null) out.put(key, value);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object shrinkValue(Object value, int stringCap, int listCap) {
        if (value == null) return null;
        if (value instanceof String) {
            String s = (String) value;
            if (s.length() <= stringCap) return s;
            return s.substring(0, stringCap) + "…";
        }
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map) return shrinkMap((Map<?, ?>) value, stringCap, listCap);
        if (value instanceof List) return capList(value, listCap);
        String s = value.toString();
        if (s.length() > stringCap) s = s.substring(0, stringCap) + "…";
        return s;
    }

    private static Object capList(Object value, int cap) {
        if (!(value instanceof List)) return shrinkValue(value, STRING_CAP, LIST_CAP);
        List<?> src = (List<?>) value;
        int n = Math.min(src.size(), Math.max(1, cap));
        List<Object> out = new ArrayList<>(n + 1);
        for (int i = 0; i < n; i++) out.add(shrinkValue(src.get(i), STRING_CAP, cap));
        if (src.size() > n) {
            Map<String, Object> more = new LinkedHashMap<>();
            more.put("listTruncated", Boolean.TRUE);
            more.put("total", src.size());
            out.add(more);
        }
        return out;
    }

    private static String json(Object value) {
        try {
            return LlmJson.toJson(value);
        } catch (Throwable t) {
            return value != null ? value.toString() : "";
        }
    }

    /**
     * QuickSearch and QuickLookup root actions, by screen name.
     * A segment named Search is not enough: catalog product search uses that name too.
     * Mantle Search.xml is {@link ScreenSearchHints#isMantleSearchActions}.
     */
    public static boolean isSearchActionsPath(List<String> segments) {
        if (segments == null || segments.size() < 2) return false;
        if (!"actions".equals(segments.get(segments.size() - 1))) return false;
        String screen = segments.get(segments.size() - 2);
        if (screen == null) return false;
        String name = screen.toLowerCase(Locale.ROOT);
        return "quicksearch".equals(name) || "quicklookup".equals(name);
    }
}
