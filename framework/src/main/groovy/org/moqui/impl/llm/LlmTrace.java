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

import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmProtocol.ProtocolRequest;
import org.moqui.llm.LlmProtocol.ProtocolResult;
import org.moqui.llm.LlmTool;
import org.moqui.llm.LlmToolCall;
import org.moqui.llm.LlmUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Compact, redacted one-line traces for Assist chat summaries and INFO logs.
 * Independent of profile {@code log-content} (that flag still controls LlmCallLog JSON).
 *
 * Full unredacted dumps: the two {@code TOREMOVE} logger.info lines in
 * {@link #logRequest} and {@link #logResponse}. Comment those two lines to silence;
 * uncomment to debug prompts. Dump helpers stay so those two lines are all that changes.
 */
public final class LlmTrace {
    private static final Logger logger = LoggerFactory.getLogger(LlmTrace.class);
    /** Grep key for temporary full-body dumps. */
    public static final String TOREMOVE = "TOREMOVE";
    public static final int PREVIEW_CHARS = 60;
    static final int VALUE_MAX = 80;
    static final int SUMMARY_MAX = 240;
    static final int MAX_GENERIC_KEYS = 6;
    private static final Pattern SENSITIVE = Pattern.compile(
            "password|secret|api[-_]?key|authorization|ssn|creditcard", Pattern.CASE_INSENSITIVE);

    private LlmTrace() { }

    public static void logRequest(LlmClientImpl client, ProtocolRequest req) {
        if (!logger.isInfoEnabled()) return;
        String profile = client != null ? client.getProfileName() : null;
        String convId = client != null ? client.convId() : null;
        boolean sim = isSim(client);
        logger.info(formatRequest(profile, convId, sim,
                req != null ? req.model : null,
                req != null && req.stream,
                req != null ? req.window : null));
        // TOREMOVE full dump (unredacted). Comment this line to silence; uncomment to debug prompts.
        logger.info("{}\n{}", formatDumpRequestLine(profile, convId, sim, req), formatDumpRequest(req));
    }

    public static void logResponse(LlmClientImpl client, ProtocolResult result, long durationMs) {
        if (!logger.isInfoEnabled()) return;
        String profile = client != null ? client.getProfileName() : null;
        String convId = client != null ? client.convId() : null;
        boolean sim = isSim(client);
        logger.info(formatResponse(profile, convId, sim, durationMs, result));
        // TOREMOVE full dump (unredacted). Comment this line to silence; uncomment to debug prompts.
        logger.info("{}\n{}", formatDumpResponseLine(profile, convId, sim, result), formatDumpResponse(result));
    }

    public static void logToolCall(String name, Object arguments) {
        if (!logger.isInfoEnabled()) return;
        String sum = summarizeCall(name, arguments);
        if (sum == null || sum.isEmpty()) logger.info("LLM tool call {}", nvl(name, "?"));
        else logger.info("LLM tool call {} {}", nvl(name, "?"), sum);
    }

    public static void logToolResult(String name, Object content) {
        if (!logger.isInfoEnabled()) return;
        String sum = summarizeResult(name, content);
        if (sum == null || sum.isEmpty()) logger.info("LLM tool result {}", nvl(name, "?"));
        else logger.info("LLM tool result {} {}", nvl(name, "?"), sum);
    }

    public static void logSimEnter(String convId, String goal, int maxIter, boolean overlayStart) {
        if (!logger.isInfoEnabled()) return;
        logger.info(formatSimEnter(convId, goal, maxIter, overlayStart));
    }

    public static void logSimExit(String convId, long durationMs, String proposed, String error) {
        if (!logger.isInfoEnabled()) return;
        logger.info(formatSimExit(convId, durationMs, proposed, error));
    }

    public static String formatRequest(String profile, String convId, boolean sim, String model,
            boolean stream, List<LlmMessage> window) {
        String prompt = concatenateContents(window);
        Preview prev = preview(prompt, PREVIEW_CHARS);
        StringBuilder sb = new StringBuilder("LLM request");
        appendField(sb, "profile", profile);
        appendField(sb, "conv", convId);
        sb.append(" sim=").append(sim);
        appendField(sb, "model", model);
        sb.append(" stream=").append(stream);
        sb.append(" messages=").append(window != null ? window.size() : 0);
        sb.append(" promptChars=").append(prev.chars);
        appendPreview(sb, prev);
        return sb.toString();
    }

    public static String formatResponse(String profile, String convId, boolean sim, long durationMs,
            ProtocolResult result) {
        String text = responseText(result);
        Preview prev = preview(text, PREVIEW_CHARS);
        StringBuilder sb = new StringBuilder("LLM response");
        appendField(sb, "profile", profile);
        appendField(sb, "conv", convId);
        sb.append(" sim=").append(sim);
        sb.append(" ms=").append(Math.max(0, durationMs));
        sb.append(" tokens=").append(formatTokens(result != null ? result.usage : null));
        String finish = "?";
        int http = 0;
        if (result != null) {
            if (result.finishReason != null) finish = result.finishReason.name().toLowerCase(Locale.ROOT);
            else if (result.errorMessage != null && !result.errorMessage.isBlank()) finish = "error";
            http = result.httpStatus;
        }
        sb.append(" finish=").append(finish);
        sb.append(" http=").append(http);
        sb.append(" chars=").append(prev.chars);
        appendPreview(sb, prev);
        return sb.toString();
    }

    public static String formatDumpRequestLine(String profile, String convId, boolean sim, ProtocolRequest req) {
        StringBuilder sb = new StringBuilder(TOREMOVE).append(" LLM request");
        appendField(sb, "profile", profile);
        appendField(sb, "conv", convId);
        sb.append(" sim=").append(sim);
        int n = req != null && req.window != null ? req.window.size() : 0;
        sb.append(" messages=").append(n);
        String tools = toolNames(req);
        if (!tools.isEmpty()) sb.append(" tools=").append(tools);
        return sb.toString();
    }

    public static String formatDumpResponseLine(String profile, String convId, boolean sim, ProtocolResult result) {
        StringBuilder sb = new StringBuilder(TOREMOVE).append(" LLM response");
        appendField(sb, "profile", profile);
        appendField(sb, "conv", convId);
        sb.append(" sim=").append(sim);
        String finish = "?";
        if (result != null && result.finishReason != null)
            finish = result.finishReason.name().toLowerCase(Locale.ROOT);
        else if (result != null && result.errorMessage != null && !result.errorMessage.isBlank())
            finish = "error";
        sb.append(" finish=").append(finish);
        return sb.toString();
    }

    /** Unredacted conversation window for TOREMOVE dumps. */
    public static String formatDumpRequest(ProtocolRequest req) {
        if (req == null || req.window == null || req.window.isEmpty()) return "(empty window)";
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : req.window) {
            if (m == null) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("=== ");
            sb.append(m.role != null ? m.role.name().toLowerCase(Locale.ROOT) : "?");
            if (m.name != null && !m.name.isBlank()) sb.append(' ').append(m.name);
            if (m.toolCallId != null && !m.toolCallId.isBlank())
                sb.append(" toolCallId=").append(m.toolCallId);
            sb.append(" ===");
            if (m.content != null && !m.content.isEmpty()) sb.append('\n').append(m.content);
            if (m.toolCalls != null) {
                for (LlmToolCall tc : m.toolCalls) {
                    if (tc == null) continue;
                    sb.append("\n--- tool_call");
                    if (tc.id != null) sb.append(" id=").append(tc.id);
                    if (tc.name != null) sb.append(" name=").append(tc.name);
                    sb.append(" ---\n");
                    sb.append(tc.arguments != null ? tc.arguments : "{}");
                }
            }
        }
        return sb.length() == 0 ? "(empty window)" : sb.toString();
    }

    /** Unredacted response including thinking when captured. */
    public static String formatDumpResponse(ProtocolResult result) {
        if (result == null) return "(null result)";
        StringBuilder sb = new StringBuilder();
        if (result.reasoning != null && !result.reasoning.isBlank()) {
            sb.append("=== thinking ===\n").append(result.reasoning);
        }
        if (result.content != null && !result.content.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("=== content ===\n").append(result.content);
        }
        if (result.toolCalls != null) {
            for (LlmToolCall tc : result.toolCalls) {
                if (tc == null) continue;
                if (sb.length() > 0) sb.append("\n\n");
                sb.append("=== tool_call");
                if (tc.name != null) sb.append(' ').append(tc.name);
                if (tc.id != null) sb.append(" id=").append(tc.id);
                sb.append(" ===\n");
                sb.append(tc.arguments != null ? tc.arguments : "{}");
            }
        }
        if (result.errorMessage != null && !result.errorMessage.isBlank()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("=== error ===\n").append(result.errorMessage);
        }
        return sb.length() == 0 ? "(empty)" : sb.toString();
    }

    static String toolNames(ProtocolRequest req) {
        if (req == null || req.tools == null || req.tools.isEmpty()) return "";
        List<String> names = new ArrayList<>();
        for (LlmTool t : req.tools) {
            if (t != null && t.getName() != null && !t.getName().isBlank()) names.add(t.getName());
        }
        return String.join(",", names);
    }

    public static String formatSimEnter(String convId, String goal, int maxIter, boolean overlayStart) {
        StringBuilder sb = new StringBuilder("LLM sim enter");
        appendField(sb, "conv", convId);
        String g = collapseWs(goal);
        if (g != null && !g.isEmpty()) sb.append(" goal=").append(quote(cap(g, VALUE_MAX)));
        sb.append(" maxIter=").append(maxIter);
        sb.append(" overlay=").append(overlayStart ? "start" : "reuse");
        return sb.toString();
    }

    public static String formatSimExit(String convId, long durationMs, String proposed, String error) {
        StringBuilder sb = new StringBuilder("LLM sim exit");
        appendField(sb, "conv", convId);
        sb.append(" ms=").append(Math.max(0, durationMs));
        if (proposed != null && !proposed.isBlank()) appendField(sb, "proposed", proposed.trim());
        if (error != null && !error.isBlank()) sb.append(" error=").append(quote(cap(collapseWs(error), VALUE_MAX)));
        return sb.toString();
    }

    public static String summarizeCall(String name, Object arguments) {
        Map<String, Object> args = asMap(arguments);
        if (args == null) args = Collections.emptyMap();
        List<String> parts = new ArrayList<>();
        if ("browse".equals(name)) {
            String path = str(args.get("path"));
            if (path == null || path.isBlank()) path = "/";
            parts.add("path=" + path.trim());
            String match = str(args.get("match"));
            if (match != null && !match.isBlank()) parts.add("match=" + cap(collapseWs(match), VALUE_MAX));
            int depth = intVal(args.get("depth"), 1);
            if (depth != 1) parts.add("depth=" + depth);
            if (boolVal(args.get("detail"))) parts.add("detail=true");
        } else if ("request".equals(name)) {
            String method = str(args.get("method"));
            String path = str(args.get("path"));
            parts.add((method != null && !method.isBlank() ? method.trim().toUpperCase(Locale.ROOT) : "?")
                    + " " + (path != null && !path.isBlank() ? path.trim() : "?"));
            appendKeyNames(parts, "queryKeys", args.get("query"));
            appendKeyNames(parts, "bodyKeys", args.get("body"));
        } else if ("run_service".equals(name)) {
            String svc = str(args.get("serviceName"));
            if (svc != null && !svc.isBlank()) parts.add(svc.trim());
            appendKv(parts, asMap(args.get("parameters")), MAX_GENERIC_KEYS);
        } else if ("find_skill".equals(name)) {
            String q = str(args.get("query"));
            if (q != null && !q.isBlank()) parts.add("query=" + quote(cap(collapseWs(q), VALUE_MAX)));
            int limit = intVal(args.get("limit"), -1);
            if (limit >= 0) parts.add("limit=" + limit);
            String sel = str(args.get("select"));
            if (sel != null && !sel.isBlank()) parts.add("select=" + quote(cap(collapseWs(sel), VALUE_MAX)));
        } else if ("enter_sim".equals(name)) {
            String goal = str(args.get("goal"));
            if (goal != null && !goal.isBlank()) parts.add("goal=" + quote(cap(collapseWs(goal), VALUE_MAX)));
            if (args.get("max_iterations") != null) parts.add("maxIter=" + intVal(args.get("max_iterations"), 0));
        } else if ("write_ui".equals(name)) {
            String kind = str(args.get("kind"));
            if (kind == null || kind.isBlank()) kind = "form";
            parts.add("kind=" + kind.trim());
            String title = str(args.get("title"));
            if (title != null && !title.isBlank()) parts.add("title=" + quote(cap(collapseWs(title), VALUE_MAX)));
            int fields = listSize(args.get("fields"));
            if (fields > 0) parts.add("fields=" + fields);
            int actions = listSize(args.get("actions"));
            if (actions > 0) parts.add("actions=" + actions);
        } else {
            appendKv(parts, args, MAX_GENERIC_KEYS);
        }
        return cap(String.join(" ", parts), SUMMARY_MAX);
    }

    public static String summarizeResult(String name, Object content) {
        Map<String, Object> m = asMap(content);
        List<String> parts = new ArrayList<>();
        if (m != null) {
            if ("browse".equals(name)) {
                addError(parts, m);
                String path = str(m.get("path"));
                if (path != null && !path.isBlank()) parts.add("path=" + path.trim());
                Object children = m.get("children");
                if (children instanceof List) parts.add(((List<?>) children).size() + " children");
            } else if ("request".equals(name)) {
                if (m.get("status") != null) parts.add("status=" + m.get("status"));
                addError(parts, m);
            } else if ("run_service".equals(name)) {
                if (Boolean.TRUE.equals(m.get("ok"))) parts.add("ok");
                addError(parts, m);
                String svc = str(m.get("serviceName"));
                if (svc != null && !svc.isBlank() && parts.isEmpty()) parts.add(svc.trim());
            } else if ("find_skill".equals(name)) {
                Object skills = m.get("skills");
                if (skills instanceof List) {
                    List<?> list = (List<?>) skills;
                    if (list.isEmpty()) parts.add("none");
                    else {
                        List<String> names = new ArrayList<>();
                        for (Object row : list) {
                            if (names.size() >= 4) break;
                            Map<String, Object> sm = asMap(row);
                            String n = sm != null ? str(sm.get("name")) : null;
                            if (n != null && !n.isBlank()) names.add(n.trim());
                        }
                        if (!names.isEmpty()) parts.add(String.join(",", names));
                        else parts.add(list.size() + " skills");
                    }
                }
                Map<String, Object> selected = asMap(m.get("selected"));
                if (selected != null) {
                    String sn = str(selected.get("name"));
                    if (sn != null && !sn.isBlank()) parts.add("selected=" + sn.trim());
                }
                addError(parts, m);
            } else if ("enter_sim".equals(name)) {
                String proposed = str(m.get("proposedSkillName"));
                if (proposed != null && !proposed.isBlank()) parts.add("proposed=" + proposed.trim());
                String sid = str(m.get("proposedSkillId"));
                if (sid != null && !sid.isBlank()) parts.add("skillId=" + sid.trim());
                if (Boolean.FALSE.equals(m.get("selected"))) parts.add("notSelected");
                addError(parts, m);
            } else if ("write_ui".equals(name)) {
                if (m.containsKey("submitted"))
                    parts.add(Boolean.TRUE.equals(m.get("submitted")) ? "submitted" : "not submitted");
                Object button = m.get("button");
                if (button != null && !button.toString().isBlank()) parts.add("button=" + button);
                addError(parts, m);
            } else {
                addError(parts, m);
                if (Boolean.TRUE.equals(m.get("ok"))) parts.add("ok");
                if (m.get("status") != null) parts.add("status=" + m.get("status"));
            }
            if (Boolean.TRUE.equals(m.get("truncated"))) {
                parts.add("truncated");
                if (m.get("size") != null) parts.add("size=" + m.get("size"));
            }
        } else if (content instanceof String) {
            String s = collapseWs((String) content);
            if (!s.isEmpty()) parts.add(quote(cap(s, VALUE_MAX)));
        } else if (content != null) {
            parts.add(cap(collapseWs(String.valueOf(content)), VALUE_MAX));
        }
        if (parts.isEmpty() && content != null) parts.add("ok");
        return cap(String.join(" ", parts), SUMMARY_MAX);
    }

    public static Preview preview(String text, int n) {
        String flat = collapseWs(text);
        int len = flat.length();
        if (n < 1) n = PREVIEW_CHARS;
        if (len <= n) return new Preview(len, flat, null);
        return new Preview(len, flat.substring(0, n), flat.substring(len - n));
    }

    public static final class Preview {
        public final int chars;
        public final String head;
        public final String tail;
        Preview(int chars, String head, String tail) {
            this.chars = chars;
            this.head = head;
            this.tail = tail;
        }
    }

    static boolean isSensitiveKey(String key) {
        if (key == null || key.isEmpty()) return false;
        if (ServiceCallTool.isSkipKey(key)) return true;
        String lower = key.toLowerCase(Locale.ROOT);
        if ("token".equals(lower)) return true;
        return SENSITIVE.matcher(lower).find();
    }

    static boolean isSim(LlmClientImpl client) {
        if (client == null || client.ec == null) return false;
        return client.ec instanceof ExecutionContextImpl && ((ExecutionContextImpl) client.ec).simSession;
    }

    static String concatenateContents(List<LlmMessage> window) {
        if (window == null || window.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : window) {
            if (m == null || m.content == null || m.content.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(m.content);
        }
        return sb.toString();
    }

    static String responseText(ProtocolResult result) {
        if (result == null) return "";
        if (result.content != null && !result.content.isBlank()) return result.content;
        if (result.toolCalls != null && !result.toolCalls.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (LlmToolCall tc : result.toolCalls) {
                if (tc == null) continue;
                if (sb.length() > 0) sb.append("; ");
                sb.append(tc.name != null ? tc.name : "?");
                String sum = summarizeCall(tc.name, tc.arguments);
                if (sum != null && !sum.isEmpty()) sb.append(' ').append(sum);
            }
            return sb.toString();
        }
        if (result.errorMessage != null) return result.errorMessage;
        return "";
    }

    static String formatTokens(LlmUsage u) {
        if (u == null) return "?";
        return "p" + tok(u.promptTokens) + "/c" + tok(u.completionTokens) + "/t" + tok(u.totalTokens);
    }

    static String tok(Integer n) { return n != null ? n.toString() : "?"; }

    static String collapseWs(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    static String cap(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }

    static String quote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static void appendPreview(StringBuilder sb, Preview prev) {
        if (prev == null) return;
        sb.append(" head=").append(quote(prev.head != null ? prev.head : ""));
        if (prev.tail != null) sb.append(" tail=").append(quote(prev.tail));
    }

    static void appendField(StringBuilder sb, String key, String value) {
        sb.append(' ').append(key).append('=');
        sb.append(value != null && !value.isBlank() ? value.trim() : "-");
    }

    static void appendKv(List<String> parts, Map<String, Object> map, int maxKeys) {
        if (map == null || map.isEmpty() || parts == null) return;
        int n = 0;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String k = e.getKey();
            if (k == null || isOmittedCallKey(k) || ServiceCallTool.isSkipKey(k)) continue;
            if (n >= maxKeys) {
                parts.add("...");
                break;
            }
            parts.add(k + "=" + formatValue(k, e.getValue()));
            n++;
        }
    }

    static void appendKeyNames(List<String> parts, String label, Object obj) {
        Map<String, Object> map = asMap(obj);
        if (map == null || map.isEmpty()) return;
        List<String> keys = new ArrayList<>();
        for (String k : map.keySet()) {
            if (k == null || k.isBlank() || isSensitiveKey(k)) continue;
            keys.add(k);
            if (keys.size() >= MAX_GENERIC_KEYS) break;
        }
        if (!keys.isEmpty()) parts.add(label + "=" + String.join(",", keys));
    }

    static boolean isOmittedCallKey(String key) {
        if (key == null) return true;
        String lower = key.toLowerCase(Locale.ROOT);
        return "sfc".equals(lower) || "template".equals(lower) || "script".equals(lower) || "style".equals(lower);
    }

    static String formatValue(String key, Object v) {
        if (isSensitiveKey(key)) return "***";
        if (v == null) return "";
        if (v instanceof Map) return "{...}";
        if (v instanceof List) return "[" + ((List<?>) v).size() + "]";
        String s = collapseWs(String.valueOf(v));
        s = cap(s, VALUE_MAX);
        if (s.indexOf(' ') >= 0 || s.indexOf('=') >= 0) return quote(s);
        return s;
    }

    static void addError(List<String> parts, Map<String, Object> m) {
        Object err = m.get("error");
        if (err == null) return;
        String s = collapseWs(String.valueOf(err));
        if (!s.isEmpty()) parts.add("error=" + quote(cap(s, VALUE_MAX)));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        if (o instanceof CharSequence) return LlmJson.tryToMap(o.toString());
        return null;
    }

    static String str(Object o) { return o == null ? null : o.toString(); }

    static String nvl(String v, String def) { return v == null || v.isBlank() ? def : v; }

    static int intVal(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o == null) return def;
        try { return Integer.parseInt(o.toString().trim()); }
        catch (Exception e) { return def; }
    }

    static boolean boolVal(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o == null) return false;
        String s = o.toString().trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    static int listSize(Object o) {
        return o instanceof List ? ((List<?>) o).size() : 0;
    }
}
