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
package org.moqui.impl.llm.tools;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;
import org.moqui.impl.llm.LlmConversationImpl;
import org.moqui.llm.LlmConversation;
import org.moqui.llm.LlmTool;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cleans what the model put in a canvas before it is stored or shown: strips control characters, keeps only known keys,
 * checks action paths, and removes remote loads (link tags, script and style sources) from a generated component.
 * Pure functions over maps and strings.
 */
final class CanvasSanitizer {
    private CanvasSanitizer() { }

    static final Pattern LINK_TAG = Pattern.compile("(?is)<link\\b[^>]*(?:/>|>)(?:\\s*</link>)?");
    static final Pattern SCRIPT_SRC = Pattern.compile(
            "(?is)(<script\\b[^>]*?)\\ssrc\\s*=\\s*(?:'[^']*'|\"[^\"]*\"|[^\\s>]+)");
    static final Pattern STYLE_SRC = Pattern.compile(
            "(?is)(<style\\b[^>]*?)\\ssrc\\s*=\\s*(?:'[^']*'|\"[^\"]*\"|[^\\s>]+)");

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cleanColumns(Object obj) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> col = CanvasSanitizer.keepKeys(CanvasSanitizer.deepCopy((Map<String, Object>) item), WriteUiTool.COLUMN_KEYS);
            CanvasSanitizer.sanitizeString(col, "name");
            CanvasSanitizer.sanitizeString(col, "label");
            String widget = WriteUiTool.str(col.get("widget"));
            if (widget != null && !WriteUiTool.WIDGETS.contains(widget)) col.put("widget", "display");
            if (WriteUiTool.str(col.get("name")) != null) out.add(col);
        }
        return out;
    }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cleanRows(Object obj) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> row = CanvasSanitizer.deepCopy((Map<String, Object>) item);
            for (Map.Entry<String, Object> e : new ArrayList<>(row.entrySet())) {
                if (e.getValue() instanceof CharSequence)
                    row.put(e.getKey(), CanvasSanitizer.clean(e.getValue().toString()));
            }
            out.add(row);
        }
        return out;
    }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cleanActions(Object obj) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> act = CanvasSanitizer.keepKeys(CanvasSanitizer.deepCopy((Map<String, Object>) item), WriteUiTool.ACTION_KEYS);
            CanvasSanitizer.sanitizeString(act, "id");
            CanvasSanitizer.sanitizeString(act, "label");
            CanvasSanitizer.sanitizeString(act, "method");
            CanvasSanitizer.sanitizeString(act, "path");
            String path = WriteUiTool.str(act.get("path"));
            if (path != null && RequestTool.validatePath(path) != null) continue;
            String method = WriteUiTool.str(act.get("method"));
            if (method != null) act.put("method", method.trim().toUpperCase(Locale.ROOT));
            act.put("bodyFromFields", CanvasSanitizer.cleanStringList(act.get("bodyFromFields")));
            act.put("queryFromFields", CanvasSanitizer.cleanStringList(act.get("queryFromFields")));
            act.put("bind", CanvasSanitizer.cleanStringList(act.get("bind")));
            act.put("dependsOn", CanvasSanitizer.cleanStringList(act.get("dependsOn")));
            if (WriteUiTool.str(act.get("id")) != null || path != null) out.add(act);
        }
        return out;
    }

    static List<String> cleanStringList(Object obj) {
        List<String> out = new ArrayList<>();
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) {
            if (item == null) continue;
            String s = CanvasSanitizer.clean(item.toString());
            if (s != null && !s.isBlank()) out.add(s);
        }
        return out;
    }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> asMapList(Object obj) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) {
            if (item instanceof Map) out.add((Map<String, Object>) item);
        }
        return out;
    }
    static List<String> asStringList(Object obj) {
        List<String> out = new ArrayList<>();
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) if (item != null) out.add(item.toString());
        return out;
    }

    static String clean(String s) {
        if (s == null) return null;
        // Strip tags, then unescape so Vue text interpolation does not show &amp;
        return Parser.unescapeEntities(Jsoup.clean(s, Safelist.none()), false);
    }
    static void sanitizeString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof CharSequence) map.put(key, CanvasSanitizer.clean(v.toString()));
    }
    static Map<String, Object> keepKeys(Map<String, Object> in, Set<String> keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in == null) return out;
        for (String k : keys) {
            if (in.containsKey(k)) out.put(k, in.get(k));
        }
        return out;
    }
    static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> deepCopy(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in == null) return out;
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map) out.put(e.getKey(), CanvasSanitizer.deepCopy((Map<String, Object>) v));
            else if (v instanceof List) {
                List<Object> copy = new ArrayList<>();
                for (Object item : (List<?>) v) {
                    if (item instanceof Map) copy.add(CanvasSanitizer.deepCopy((Map<String, Object>) item));
                    else copy.add(item);
                }
                out.put(e.getKey(), copy);
            } else out.put(e.getKey(), v);
        }
        return out;
    }
    static String stripFences(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
            t = t.trim();
        }
        return t;
    }
    static String stripRemoteLoads(String sfc) {
        if (sfc == null) return null;
        String s = LINK_TAG.matcher(sfc).replaceAll("");
        s = SCRIPT_SRC.matcher(s).replaceAll("$1");
        s = STYLE_SRC.matcher(s).replaceAll("$1");
        return s;
    }
}
