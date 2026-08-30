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

import org.moqui.context.ArtifactAuthorizationException;
import org.moqui.context.ArtifactExecutionFacade;
import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.context.ArtifactTarpitException;
import org.moqui.context.ExecutionContext;
import org.moqui.context.LlmFacade;
import org.moqui.context.TransactionFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.moqui.llm.LlmClient;
import org.moqui.llm.LlmConversation;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmResponse;
import org.moqui.llm.LlmTool;
import org.moqui.llm.LlmToolCall;
import org.moqui.llm.LlmToolResult;
import org.moqui.llm.LlmUsage;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Shared gateway logic for LlmServlet and Service REST wrappers.
 * Not a provider-key proxy: keys stay on the profile; tools are fail-closed.
 */
public final class LlmGateway {
    private static final Logger logger = LoggerFactory.getLogger(LlmGateway.class);
    private LlmGateway() { }

    public static final class Route {
        public enum Op { CHAT, RESUME, CANCEL, GET_CONVERSATION, GET_PROFILES }
        public final Op op;
        public final String conversationId;
        public Route(Op op, String conversationId) {
            this.op = op;
            this.conversationId = conversationId;
        }
        public boolean isPost() {
            return op == Op.CHAT || op == Op.RESUME || op == Op.CANCEL;
        }
        public boolean isGet() {
            return op == Op.GET_CONVERSATION || op == Op.GET_PROFILES;
        }
    }

    /**
     * Parse the extra path after the /llm servlet mapping. Requires the v1 prefix.
     * Accepts POST /v1/conversations/{id}/cancel as an alias of /v1/chat/{id}/cancel.
     */
    public static Route parseRoute(String pathInfo) {
        if (pathInfo == null) pathInfo = "";
        while (pathInfo.startsWith("/")) pathInfo = pathInfo.substring(1);
        if (pathInfo.endsWith("/") && pathInfo.length() > 1)
            pathInfo = pathInfo.substring(0, pathInfo.length() - 1);
        if (pathInfo.isEmpty()) return null;
        String[] p = pathInfo.split("/");
        if (p.length < 2 || !"v1".equals(p[0])) return null;
        if ("profiles".equals(p[1]) && p.length == 2) return new Route(Route.Op.GET_PROFILES, null);
        if ("chat".equals(p[1])) {
            if (p.length == 2) return new Route(Route.Op.CHAT, null);
            if (p.length == 4 && "resume".equals(p[3])) return new Route(Route.Op.RESUME, p[2]);
            if (p.length == 4 && "cancel".equals(p[3])) return new Route(Route.Op.CANCEL, p[2]);
            return null;
        }
        if ("conversations".equals(p[1])) {
            if (p.length == 3) return new Route(Route.Op.GET_CONVERSATION, p[2]);
            if (p.length == 4 && "cancel".equals(p[3])) return new Route(Route.Op.CANCEL, p[2]);
            return null;
        }
        return null;
    }

    /**
     * Copy of ScreenRenderImpl session-token skip: GET/HEAD/OPTIONS, webapp require-session-token=false,
     * moqui.request.authenticated (login_key/Basic), token just created, or no session token yet.
     * Returns an error message or null if the POST is allowed.
     */
    public static String csrfError(HttpServletRequest request, String sessionToken, boolean requireSessionToken) {
        if (request == null) return null;
        String method = request.getMethod();
        if (method == null) return null;
        String m = method.toUpperCase();
        if ("GET".equals(m) || "HEAD".equals(m) || "OPTIONS".equals(m)) return null;
        if (!requireSessionToken) return null;
        if ("true".equals(request.getAttribute("moqui.request.authenticated"))) return null;
        if ("true".equals(request.getAttribute("moqui.session.token.created"))) return null;
        if (sessionToken == null || sessionToken.isEmpty()) return null;
        String passed = request.getParameter("moquiSessionToken");
        if (passed == null || passed.isEmpty()) passed = request.getHeader("moquiSessionToken");
        if (passed == null || passed.isEmpty()) passed = request.getHeader("SessionToken");
        if (passed == null || passed.isEmpty()) passed = request.getHeader("X-CSRF-Token");
        if (passed == null || passed.isEmpty())
            return "Session token required (in X-CSRF-Token)";
        if (!sessionToken.equals(passed))
            return "Session token does not match (in X-CSRF-Token)";
        return null;
    }

    public static boolean wantsStream(Map<String, Object> body, String accept) {
        if (isTrue(body != null ? body.get("stream") : null)) return true;
        if (accept == null) return false;
        return accept.toLowerCase().contains("text/event-stream");
    }

    public static Map<String, Object> parseBody(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, Object> map = LlmJson.toMap(text);
            return map != null ? map : new LinkedHashMap<>();
        } catch (Throwable t) {
            throw new LlmException("Invalid JSON request body: " + t.getMessage(), t,
                    LlmFinishReason.ERROR, 400, null, null);
        }
    }

    /**
     * Request tools may only subset {request, write_ui}. write-ui is accepted as write_ui.
     * Unknown names are 400, not silently ignored.
     */
    public static List<String> parseTools(Object tools) {
        List<String> names = new ArrayList<>();
        if (tools == null) return names;
        if (tools instanceof String) {
            for (String part : ((String) tools).split(",")) {
                if (part != null && !part.isBlank()) names.add(part.trim());
            }
        } else if (tools instanceof List) {
            for (Object o : (List<?>) tools) {
                if (o != null && !o.toString().isBlank()) names.add(o.toString().trim());
            }
        } else {
            throw new LlmException("tools must be a list of request/write_ui",
                    null, LlmFinishReason.ERROR, 400, null, null);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : names) {
            String n = "write-ui".equals(raw) ? "write_ui" : raw;
            if (!"request".equals(n) && !"write_ui".equals(n))
                throw new LlmException("tools may only subset {request, write_ui}",
                        null, LlmFinishReason.ERROR, 400, null, null);
            seen.add(n);
        }
        return new ArrayList<>(seen);
    }

    /**
     * Fail-closed: empty allowed-path ⇒ no request tool (K19). Internal LlmTool.request() without
     * prefixes still means any path the user is authorized to hit; the servlet never takes that path.
     */
    public static LlmTool requestToolForServlet(List<LlmFacadeImpl.AllowedPath> allowedPaths) {
        if (allowedPaths == null || allowedPaths.isEmpty()) return null;
        RequestTool rt = new RequestTool();
        for (LlmFacadeImpl.AllowedPath ap : allowedPaths) {
            if (ap != null) rt.addAllowedPath(ap.prefix, ap.methodsCsv);
        }
        return rt;
    }

    public static void attachServletTools(LlmClient client, LlmFacadeImpl.ProfileState profile, List<String> tools) {
        if (client == null || tools == null || tools.isEmpty()) return;
        boolean wantRequest = tools.contains("request");
        boolean wantWriteUi = tools.contains("write_ui");
        if (wantRequest) {
            LlmTool rt = requestToolForServlet(profile != null ? profile.allowedPaths : null);
            if (rt != null) client.tool(rt);
        }
        if (wantWriteUi && profile != null && profile.allowWriteUi) {
            client.tool(LlmTool.writeUi());
            client.allowClientTools(true);
        }
    }

    public static LlmClientImpl prepareClient(ExecutionContext ec, Map<String, Object> body, boolean resume) {
        if (ec == null) throw new LlmException("ExecutionContext is required",
                null, LlmFinishReason.ERROR, 500, null, null);
        if (body == null) body = new LinkedHashMap<>();
        String profileName = str(body.get("profile"));
        if (profileName == null) profileName = "default";
        LlmFacade facade = ec.getLlm();
        LlmClientImpl impl = (LlmClientImpl) facade.getClient(profileName);

        String conversationId = str(body.get("conversationId"));
        if (resume) {
            if (conversationId == null)
                throw new LlmException("conversationId is required to resume",
                        null, LlmFinishReason.ERROR, 400, profileName, null);
            impl.conversation(conversationId);
            String st = impl.conversation.getStatus();
            if (!LlmConversationImpl.STATUS_YIELDED.equals(st))
                throw new LlmException("Conversation is " + st + " (single-flight)",
                        null, LlmFinishReason.ERROR, 409, profileName, conversationId);
        } else if (conversationId != null) {
            impl.conversation(conversationId);
        } else {
            Map<String, Object> attrs = new LinkedHashMap<>();
            String canvasId = str(body.get("canvasId"));
            if (canvasId != null) attrs.put("canvasId", canvasId);
            LlmConversation conv = facade.createConversation(profileName, attrs.isEmpty() ? null : attrs);
            impl.conversation(conv);
        }

        Object inj = body.get("injectContext");
        if (inj instanceof List) {
            for (Object o : (List<?>) inj) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> m = (Map<?, ?>) o;
                String source = str(m.get("source"));
                String content = str(m.get("content"));
                if (content != null) impl.injectContext(source != null ? source : "", content);
            }
        }

        String system = str(body.get("system"));
        if (system != null) impl.system(system);
        String user = str(body.get("user"));
        if (user != null) impl.user(user);

        Object msgs = body.get("messages");
        if (msgs instanceof List) {
            List<LlmMessage> extra = new ArrayList<>();
            for (Object o : (List<?>) msgs) {
                LlmMessage parsed = toMessage(o);
                if (parsed != null) extra.add(parsed);
            }
            if (!extra.isEmpty()) impl.messages(extra);
        }

        attachServletTools(impl, impl.profile, parseTools(body.get("tools")));

        Object temp = body.get("temperature");
        if (temp instanceof Number) impl.temperature(((Number) temp).doubleValue());
        Object maxTok = body.get("maxTokens");
        if (maxTok instanceof Number) impl.maxTokens(((Number) maxTok).intValue());

        if (resume) {
            impl.markResumeFromYielded();
            impl.toolResults(parseToolResults(body.get("toolResults")));
        }
        return impl;
    }

    /**
     * Service REST /s1 begins a screen TX; LlmClient.call() fail-fasts if any JTA TX is in place (K11).
     * Suspend the caller TX for the whole prepare+call, then resume. Do not set allow-tx-over-http.
     */
    public static <T> T withoutCallerTx(ExecutionContext ec, Supplier<T> work) {
        if (work == null) return null;
        if (ec == null || ec.getTransaction() == null) return work.get();
        TransactionFacade tf = ec.getTransaction();
        boolean suspended = false;
        try {
            if (tf.isTransactionInPlace()) suspended = tf.suspend();
            return work.get();
        } finally {
            if (suspended) {
                try { tf.resume(); }
                catch (Throwable t) { logger.error("Error resuming transaction after LLM gateway call", t); }
            }
        }
    }

    public static Map<String, Object> chat(ExecutionContext ec, Map<String, Object> body) {
        return withoutCallerTx(ec, () -> {
            LlmClientImpl client = prepareClient(ec, body, false);
            return responseToMap(client.call());
        });
    }
    public static Map<String, Object> resume(ExecutionContext ec, Map<String, Object> body) {
        return withoutCallerTx(ec, () -> {
            LlmClientImpl client = prepareClient(ec, body, true);
            return responseToMap(client.call());
        });
    }

    /**
     * 200 on Streaming/Yielded (abort RestStream, persist Cancelled). 409 otherwise.
     * Outside the single-flight 409 rule for in-flight turns.
     */
    public static Map<String, Object> cancel(ExecutionContext ec, String conversationId) {
        LlmConversation conv = LlmConversationImpl.load(ec, conversationId, true);
        return cancel(conv);
    }
    public static Map<String, Object> cancel(LlmConversation conv) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", conv.getConversationId());
        String status = conv.getStatus();
        out.put("status", status);
        if (LlmConversationImpl.STATUS_STREAMING.equals(status)
                || LlmConversationImpl.STATUS_YIELDED.equals(status)) {
            conv.cancel();
            out.put("status", conv.getStatus());
            out.put("httpStatus", 200);
            return out;
        }
        out.put("httpStatus", 409);
        out.put("message", "Conversation is " + status + " (cancel requires Streaming or Yielded)");
        return out;
    }

    public static Map<String, Object> getConversationMap(ExecutionContext ec, String conversationId) {
        LlmConversation conv = LlmConversationImpl.load(ec, conversationId, true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", conv.getConversationId());
        out.put("profileName", conv.getProfileName());
        out.put("userId", conv.getUserId());
        out.put("status", conv.getStatus());
        out.put("title", conv.getTitle());
        List<Map<String, Object>> hist = new ArrayList<>();
        for (LlmMessage m : conv.getHistory()) hist.add(messageToMap(m));
        out.put("history", hist);
        out.put("pendingToolCalls", toolCallsToMaps(conv.getPendingClientToolCalls()));
        out.put("attributes", conv.getAttributes());
        return out;
    }

    /** Profiles the current user is authorized to use (AT_LLM VIEW). Names + model, never keys. */
    public static List<Map<String, Object>> listProfiles(ExecutionContext ec) {
        LlmFacade facade = ec.getLlm();
        ArtifactExecutionFacade aefi = ec.getArtifactExecution();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : facade.getProfileNames()) {
            ArtifactExecutionInfo aei = null;
            try {
                if (aefi != null)
                    aei = aefi.push(name, ArtifactExecutionInfo.AT_LLM, ArtifactExecutionInfo.AUTHZA_VIEW, true);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                LlmFacadeImpl.ProfileState ps = facade instanceof LlmFacadeImpl
                        ? ((LlmFacadeImpl) facade).getProfileState(name) : null;
                row.put("model", ps != null ? ps.model : null);
                out.add(row);
            } catch (ArtifactAuthorizationException ignored) {
                // skip profiles the user cannot VIEW
            } catch (ArtifactTarpitException e) {
                throw e;
            } finally {
                if (aefi != null && aei != null) {
                    try { aefi.pop(aei); } catch (Throwable ignored) { }
                }
            }
        }
        return out;
    }

    public static Map<String, Object> responseToMap(LlmResponse r) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (r == null) return out;
        out.put("conversationId", r.conversationId);
        out.put("content", r.content);
        out.put("finishReason", r.finishReason != null ? r.finishReason.name().toLowerCase() : null);
        out.put("yielded", r.yielded);
        out.put("pendingToolCalls", toolCallsToMaps(r.getPendingToolCalls()));
        List<Map<String, Object>> tr = new ArrayList<>();
        if (r.toolResults != null) {
            for (LlmToolResult t : r.toolResults) {
                if (t == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", t.toolCallId);
                row.put("name", t.name);
                row.put("content", t.content);
                tr.add(row);
            }
        }
        out.put("toolResults", tr);
        out.put("usage", usageToMap(r.usage));
        out.put("model", r.model);
        out.put("profile", r.profileName);
        out.put("durationMs", r.durationMs);
        return out;
    }

    public static int jsonStatus(LlmResponse r) {
        if (r != null && r.yielded) return 202;
        return 200;
    }

    public static int httpStatusOf(Throwable t) {
        if (t instanceof ArtifactAuthorizationException) return 403;
        if (t instanceof ArtifactTarpitException) return 429;
        if (t instanceof LlmException) {
            int s = ((LlmException) t).getHttpStatus();
            if (s == 409 || s == 404 || s == 403 || s == 400 || s == 401) return s;
            if (s >= 400 && s < 600) return s;
        }
        return 500;
    }

    public static String toJson(Object value) { return LlmJson.toJson(value); }

    public static String formatSse(String event, Object data) {
        String json = data instanceof String ? (String) data : LlmJson.toJson(data);
        return "event: " + event + "\ndata: " + json + "\n\n";
    }
    public static Map<String, Object> pingData() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", System.currentTimeMillis() / 1000L);
        return m;
    }
    public static Map<String, Object> toolCallToMap(LlmToolCall call) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (call == null) return m;
        m.put("id", call.id);
        m.put("name", call.name);
        Map<String, Object> args = LlmJson.tryToMap(call.arguments);
        m.put("arguments", args != null ? args : call.arguments);
        m.put("execution", executionName(call.execution));
        return m;
    }
    public static Map<String, Object> errorData(Throwable t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", t != null && t.getMessage() != null ? t.getMessage() : "LLM error");
        if (t instanceof LlmException) {
            LlmException le = (LlmException) t;
            m.put("code", le.getReason() != null ? le.getReason().name() : "ERROR");
            m.put("httpStatus", le.getHttpStatus());
        } else {
            m.put("code", "ERROR");
            m.put("httpStatus", 0);
        }
        return m;
    }
    public static Map<String, Object> doneData(LlmResponse r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("finishReason", r != null && r.finishReason != null ? r.finishReason.name().toLowerCase() : "stop");
        m.put("usage", r != null ? usageToMap(r.usage) : null);
        m.put("yielded", r != null && r.yielded);
        return m;
    }
    public static Map<String, Object> yieldData(List<LlmToolCall> pending) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", WriteUiTool.SCHEMA_VERSION);
        m.put("pendingToolCalls", toolCallsToMaps(pending));
        return m;
    }

    static List<LlmToolResult> parseToolResults(Object obj) {
        List<LlmToolResult> out = new ArrayList<>();
        if (!(obj instanceof List)) return out;
        for (Object o : (List<?>) obj) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            String id = str(m.get("toolCallId"));
            if (id == null) id = str(m.get("id"));
            out.add(new LlmToolResult(id, str(m.get("name")), m.get("content")));
        }
        return out;
    }

    static LlmMessage toMessage(Object o) {
        if (!(o instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) o;
        String roleStr = str(m.get("role"));
        LlmMessage.Role role = LlmMessage.Role.USER;
        if (roleStr != null) {
            try { role = LlmMessage.Role.valueOf(roleStr.trim().toUpperCase()); }
            catch (IllegalArgumentException ignored) { role = LlmMessage.Role.USER; }
        }
        LlmMessage msg = new LlmMessage(role, str(m.get("content")));
        msg.name = str(m.get("name"));
        msg.toolCallId = str(m.get("toolCallId"));
        return msg;
    }

    static Map<String, Object> messageToMap(LlmMessage m) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (m == null) return row;
        row.put("messageId", m.messageId);
        row.put("role", m.role != null ? m.role.name().toLowerCase() : null);
        row.put("content", m.content);
        row.put("name", m.name);
        row.put("toolCallId", m.toolCallId);
        row.put("toolCalls", toolCallsToMaps(m.toolCalls));
        row.put("metadata", m.metadata);
        row.put("ordinal", m.ordinal);
        return row;
    }

    static List<Map<String, Object>> toolCallsToMaps(List<LlmToolCall> calls) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (calls == null) return out;
        for (LlmToolCall c : calls) if (c != null) out.add(toolCallToMap(c));
        return out;
    }

    static Map<String, Object> usageToMap(LlmUsage u) {
        if (u == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("promptTokens", u.promptTokens);
        m.put("completionTokens", u.completionTokens);
        m.put("totalTokens", u.totalTokens);
        return m;
    }

    static String executionName(LlmTool.Execution ex) {
        if (ex == LlmTool.Execution.CLIENT) return "client";
        return "server";
    }

    static boolean isTrue(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v == null) return false;
        String s = v.toString();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    static String str(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isEmpty() ? null : s;
    }
}
