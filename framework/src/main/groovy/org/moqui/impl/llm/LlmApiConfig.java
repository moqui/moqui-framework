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

import org.moqui.llm.LlmException;
import org.moqui.util.MNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Profile api-config: a base JSON object merged into every request, then the conversation's
 * low, medium, or high object merged on top. Nested objects combine. Scalars and arrays replace.
 * A JSON null removes that key.
 */
public final class LlmApiConfig {
    public static final String ATTR = "reasoning";

    private static final LlmApiConfig NONE = new LlmApiConfig(false, null, null, null, null);

    public final boolean present;
    private final Map<String, Object> base;
    private final Map<String, Object> low;
    private final Map<String, Object> medium;
    private final Map<String, Object> high;

    private LlmApiConfig(boolean present, Map<String, Object> base, Map<String, Object> low,
            Map<String, Object> medium, Map<String, Object> high) {
        this.present = present;
        this.base = base;
        this.low = low;
        this.medium = medium;
        this.high = high;
    }

    public static LlmApiConfig none() { return NONE; }

    /** low, medium, or high. Null when the value is missing or not one of those three. */
    public static String canonical(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if ("low".equals(s) || "medium".equals(s) || "high".equals(s)) return s;
        return null;
    }

    public static LlmApiConfig fromNode(MNode profile, String profileName) {
        if (profile == null) return NONE;
        MNode api = profile.first("api-config");
        if (api == null) return NONE;
        return new LlmApiConfig(true,
                read(api, "base", profileName),
                read(api, "low", profileName),
                read(api, "medium", profileName),
                read(api, "high", profileName));
    }

    /** Base merged with the level object. Null when this profile has no api-config, or both sides are empty. */
    public Map<String, Object> forLevel(String level) {
        if (!present) return null;
        Map<String, Object> over;
        if ("high".equals(level)) over = high;
        else if ("medium".equals(level)) over = medium;
        else over = low;
        return merge(base, over);
    }

    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> over) {
        if ((base == null || base.isEmpty()) && (over == null || over.isEmpty())) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        deepMerge(out, base);
        deepMerge(out, over);
        return out.isEmpty() ? null : out;
    }

    /**
     * Deep-merge overlay onto the request body. messages, input, instructions, tools, and stream stay as the
     * protocol wrote them. An overlay cannot add those keys when the protocol left them out.
     */
    public static void applyToBody(Map<String, Object> body, Map<String, Object> overlay) {
        if (body == null || overlay == null || overlay.isEmpty()) return;
        Object messages = body.get("messages");
        boolean hadMessages = body.containsKey("messages");
        Object input = body.get("input");
        boolean hadInput = body.containsKey("input");
        Object instructions = body.get("instructions");
        boolean hadInstructions = body.containsKey("instructions");
        boolean hadTools = body.containsKey("tools");
        Object tools = body.get("tools");
        Object stream = body.get("stream");
        boolean hadStream = body.containsKey("stream");
        deepMerge(body, overlay);
        if (hadMessages) body.put("messages", messages);
        else body.remove("messages");
        if (hadInput) body.put("input", input);
        else body.remove("input");
        if (hadInstructions) body.put("instructions", instructions);
        else body.remove("instructions");
        if (hadStream) body.put("stream", stream);
        else body.remove("stream");
        if (hadTools) body.put("tools", tools);
        else body.remove("tools");
    }

    /**
     * Drop temperature when {@code reasoning.effort} is set to anything other than {@code none}.
     * Chat Completions still sends temperature alongside {@code reasoning_effort} (Qwen and others accept both).
     */
    public static void omitTemperatureForReasoningObject(Map<String, Object> body) {
        if (body == null || !body.containsKey("temperature")) return;
        Object reasoning = body.get("reasoning");
        if (!(reasoning instanceof Map)) return;
        if (effortBlocksTemperature(((Map<?, ?>) reasoning).get("effort"))) body.remove("temperature");
    }

    /**
     * Responses: GPT-5 and Grok reject temperature unless effort is {@code none}.
     * Checks both {@code reasoning.effort} and {@code reasoning_effort}.
     */
    public static void omitTemperatureUnlessEffortNone(Map<String, Object> body) {
        if (body == null || !body.containsKey("temperature")) return;
        if (effortBlocksTemperature(body.get("reasoning_effort"))) {
            body.remove("temperature");
            return;
        }
        omitTemperatureForReasoningObject(body);
    }

    static boolean effortBlocksTemperature(Object effort) {
        if (effort == null) return false;
        String s = effort.toString().trim();
        return !s.isEmpty() && !"none".equalsIgnoreCase(s);
    }

    @SuppressWarnings("unchecked")
    static void deepMerge(Map<String, Object> dest, Map<String, Object> src) {
        if (src == null || dest == null) return;
        for (Map.Entry<String, Object> e : src.entrySet()) {
            String key = e.getKey();
            if (key == null) continue;
            Object val = e.getValue();
            if (val == null) {
                dest.remove(key);
                continue;
            }
            if (val instanceof Map) {
                Object existing = dest.get(key);
                Map<String, Object> nested = new LinkedHashMap<>();
                if (existing instanceof Map) deepMerge(nested, (Map<String, Object>) existing);
                deepMerge(nested, (Map<String, Object>) val);
                dest.put(key, nested);
            } else if (val instanceof List) {
                dest.put(key, new ArrayList<>((List<?>) val));
            } else {
                dest.put(key, val);
            }
        }
    }

    private static Map<String, Object> read(MNode api, String childName, String profileName) {
        MNode child = api.first(childName);
        if (child == null) return null;
        String text = child.getText();
        if (text == null || text.isBlank()) return null;
        try {
            Map<String, Object> map = LlmJson.toMap(text.trim());
            if (map == null) return null;
            return copyMap(map);
        } catch (RuntimeException e) {
            throw new LlmException("Invalid api-config." + childName + " JSON on profile " + profileName
                    + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMap(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : src.entrySet()) {
            Object val = e.getValue();
            if (val instanceof Map) out.put(e.getKey(), copyMap((Map<String, Object>) val));
            else if (val instanceof List) out.put(e.getKey(), new ArrayList<>((List<?>) val));
            else out.put(e.getKey(), val);
        }
        return out;
    }
}
