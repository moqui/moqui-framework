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
import org.moqui.util.ContextStack;

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
    public static final String PROMPT_SIM = "component://tools/prompt/SimSystem.ftl";
    public static final String PROMPT_SKILL_INJECT = "component://tools/prompt/SkillInject.ftl";
    private LlmGateway() { }

    public static final class Route {
        public enum Op { CHAT, RESUME, CANCEL, GET_CONVERSATION, LIST_CONVERSATIONS, GET_PROFILES, DELETE_CONVERSATION }
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
            return op == Op.GET_CONVERSATION || op == Op.LIST_CONVERSATIONS || op == Op.GET_PROFILES;
        }
        public boolean isDelete() { return op == Op.DELETE_CONVERSATION; }
    }

    /**
     * Parse the extra path after the /llm servlet mapping. Requires the v1 prefix.
     * Accepts POST /v1/conversations/{id}/cancel as an alias of /v1/chat/{id}/cancel.
     */
    public static Route parseRoute(String pathInfo) { return parseRoute(pathInfo, null); }

    public static Route parseRoute(String pathInfo, String method) {
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
            if (p.length == 2) return new Route(Route.Op.LIST_CONVERSATIONS, null);
            if (p.length == 3) {
                if (method != null && "DELETE".equalsIgnoreCase(method))
                    return new Route(Route.Op.DELETE_CONVERSATION, p[2]);
                return new Route(Route.Op.GET_CONVERSATION, p[2]);
            }
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
     * Request tools may only subset {request, write_ui, browse, find_basic, run_service, find_skill, enter_sim, pin}.
     * write-ui is accepted as write_ui. Unknown names are 400, not silently ignored.
     * find_basic is a legal name here; attachServletTools adds the tool only when the profile has an allow list.
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
            throw new LlmException("tools must be a list of request/write_ui/browse/find_basic/run_service/find_skill/enter_sim/pin",
                    null, LlmFinishReason.ERROR, 400, null, null);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : names) {
            String n = "write-ui".equals(raw) ? "write_ui" : raw;
            if ("run-service".equals(n)) n = "run_service";
            if (!"request".equals(n) && !"write_ui".equals(n) && !"browse".equals(n) && !"run_service".equals(n)
                    && !"find_skill".equals(n) && !"enter_sim".equals(n) && !"pin".equals(n)
                    && !FindBasicTool.NAME.equals(n))
                throw new LlmException("tools may only subset {request, write_ui, browse, find_basic, run_service, find_skill, enter_sim, pin}",
                        null, LlmFinishReason.ERROR, 400, null, null);
            seen.add(n);
        }
        return new ArrayList<>(seen);
    }

    /**
     * Fail-closed: empty allowed-path ⇒ no request tool (K19), unless allow-unprefixed-request.
     * Internal LlmTool.request() without prefixes still means any path the user is authorized to hit.
     */
    public static LlmTool requestToolForServlet(List<LlmFacadeImpl.AllowedPath> allowedPaths) {
        return requestToolForServlet(allowedPaths, false);
    }
    public static LlmTool requestToolForServlet(List<LlmFacadeImpl.AllowedPath> allowedPaths,
            boolean allowUnprefixed) {
        if (allowedPaths == null || allowedPaths.isEmpty()) {
            return allowUnprefixed ? new RequestTool() : null;
        }
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
        boolean wantBrowse = tools.contains("browse");
        boolean wantRunService = tools.contains("run_service");
        boolean wantFindSkill = tools.contains("find_skill") || wantBrowse || wantRunService;
        boolean wantEnterSim = tools.contains("enter_sim");
        boolean wantPin = tools.contains("pin") || wantFindSkill;
        boolean wantFindBasic = tools.contains(FindBasicTool.NAME);
        if (wantRequest) {
            boolean unprefixed = profile != null && profile.allowUnprefixedRequest;
            LlmTool rt = requestToolForServlet(profile != null ? profile.allowedPaths : null, unprefixed);
            if (rt != null) client.tool(rt);
        }
        if (wantWriteUi && profile != null && profile.allowWriteUi) {
            WriteUiTool wt = new WriteUiTool();
            wt.setAllowVueSfc(profile.allowVueSfc);
            if (profile.allowUnprefixedRequest && (profile.allowedEntities == null || profile.allowedEntities.isEmpty()))
                wt.setAllowAnyAuthorizedEntity(true);
            client.tool(wt);
            client.allowClientTools(true);
        }
        if (wantBrowse && profile != null && profile.allowBrowse) client.tool(LlmTool.browse());
        if (wantRunService && profile != null && profile.allowRunService) client.tool(LlmTool.runService());
        if (wantFindSkill) client.tool(LlmTool.findSkill());
        if (wantEnterSim && profile != null && profile.allowEnterSim) client.tool(LlmTool.enterSim());
        if (wantPin) client.tool(LlmTool.pin());
        if (wantFindBasic && profile != null && profile.allowedBasicEntities != null
                && !profile.allowedBasicEntities.isEmpty())
            client.tool(new FindBasicTool(profile.allowedBasicEntities));
    }

    public static void requireLlmGateway(ExecutionContext ec) {
        if (ec == null || ec.getUser() == null || !ec.getUser().hasPermission("LlmGateway"))
            throw new LlmException("User does not have permission to use the LLM gateway",
                    null, LlmFinishReason.ERROR, 403, null, null);
    }

    public static LlmClientImpl prepareClient(ExecutionContext ec, Map<String, Object> body, boolean resume) {
        if (ec == null) throw new LlmException("ExecutionContext is required",
                null, LlmFinishReason.ERROR, 500, null, null);
        requireLlmGateway(ec);
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
            // A live turn already claimed this id. Refuse before refreshContext deletes its context rows.
            // A Streaming row with no claim is abandoned; the turn below takes it over.
            if (LlmConversationImpl.STATUS_STREAMING.equals(impl.conversation.getStatus())
                    && LlmConversationImpl.turnInFlight(ec, conversationId)) {
                throw new LlmException("Conversation is LlmcsStreaming (single-flight)",
                        null, LlmFinishReason.ERROR, 409, profileName, conversationId);
            }
        } else {
            Map<String, Object> attrs = new LinkedHashMap<>();
            String canvasId = str(body.get("canvasId"));
            if (canvasId != null) attrs.put("canvasId", canvasId);
            String purpose = str(body.get("purpose"));
            if (purpose != null) attrs.put("purpose", purpose);
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

        applyForceSkillUse(impl, body);
        applyWriteMode(impl, body);
        applySystem(impl, body);
        appendForceSkillUseSystem(impl);
        String session = SessionFacts.text(impl.ec);
        String modeNote = writeModeNote(currentWriteMode(impl));
        if (modeNote != null && !modeNote.isBlank()) {
            if (session == null || session.isBlank()) session = modeNote;
            else session = session + "\n" + modeNote;
        }
        refreshContext(impl, "session", session);
        refreshContext(impl, "pins", PinTool.text(impl));
        refreshContext(impl, "skill-widgets", SkillIndex.activeWidgetText(impl.ec, impl.activeSkillName));
        String user = str(body.get("user"));
        if (user != null) {
            impl.user(user);
            injectSkills(impl, user);
        }

        Object msgs = body.get("messages");
        if (msgs instanceof List) {
            List<LlmMessage> extra = new ArrayList<>();
            for (Object o : (List<?>) msgs) {
                LlmMessage parsed = toMessage(o);
                if (parsed != null && parsed.role == LlmMessage.Role.USER) extra.add(parsed);
            }
            if (!extra.isEmpty()) impl.messages(extra);
        }

        attachServletTools(impl, impl.profile, parseTools(body.get("tools")));


        Object temp = body.get("temperature");
        if (temp instanceof Number) impl.temperature(((Number) temp).doubleValue());
        Object maxTok = body.get("maxTokens");
        if (maxTok instanceof Number) impl.maxTokens(((Number) maxTok).intValue());
        Object extraBody = body.get("extraBody");
        if (extraBody instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> extraMap = (Map<String, Object>) extraBody;
            impl.extraBody(extraMap);
        }

        if (resume) {
            impl.markResumeFromYielded();
            impl.toolResults(parseToolResults(body.get("toolResults")));
        }
        return impl;
    }

    static void applyForceSkillUse(LlmClientImpl impl, Map<String, Object> body) {
        if (impl == null) return;
        if (body != null && body.containsKey("forceSkillUse")) {
            impl.forceSkillUse = isTrue(body.get("forceSkillUse"));
            if (impl.conversation != null)
                impl.conversation.setAttribute("forceSkillUse", impl.forceSkillUse ? "true" : "false");
        } else if (impl.conversation != null) {
            impl.forceSkillUse = isTrue(impl.conversation.getAttributes().get("forceSkillUse"));
        }
        if ((impl.activeSkillName == null || impl.activeSkillName.isBlank()) && impl.conversation != null) {
            Object v = impl.conversation.getAttributes().get("activeSkillName");
            if (v != null && !v.toString().isBlank()) impl.activeSkillName = v.toString();
        }
        String bodySkill = body != null ? str(body.get("activeSkillName")) : null;
        if (bodySkill != null) {
            SkillIndex.SkillDoc doc = SkillIndex.getByName(impl.ec, bodySkill);
            if (doc != null) SkillUseGate.activate(impl, doc.name);
        }
    }

    static void applyWriteMode(LlmClientImpl impl, Map<String, Object> body) {
        if (impl == null || impl.conversation == null || body == null || !body.containsKey("writeMode")) return;
        String mode = canonicalWriteMode(body.get("writeMode"));
        if (mode != null) impl.conversation.setAttribute("writeMode", mode);
    }
    static String currentWriteMode(LlmClientImpl impl) {
        if (impl == null || impl.conversation == null) return null;
        return canonicalWriteMode(impl.conversation.getAttributes().get("writeMode"));
    }
    static String canonicalWriteMode(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        if ("script".equalsIgnoreCase(s)) return "script";
        if ("agent".equalsIgnoreCase(s)) return "agent";
        return null;
    }
    /** One session-context block naming the Assist write mode that is active for this turn. */
    static String writeModeNote(String mode) {
        if ("script".equals(mode)) {
            return "writeMode=script\n"
                    + "Script mode is active. Put the POST on the canvas: kind=openui Button "
                    + "@Run(Mutation(\"request\", {method, path, body})), or kind=form actions with method and path. "
                    + "A form with only submitLabel returns the values after the click; then request the write. Prefer the Mutation.";
        }
        if ("agent".equals(mode)) {
            return "writeMode=agent\n"
                    + "Agent mode is active. A submitLabel form is enough. After submitted:true, request or run_service the write. "
                    + "For risk=confirm, wait for the click.";
        }
        return "";
    }

    static void appendForceSkillUseSystem(LlmClientImpl impl) {
        if (impl == null || !impl.forceSkillUse) return;
        String extra = SkillUseGate.SYSTEM_ADDENDUM;
        if (impl.systemContent == null || impl.systemContent.isBlank()) impl.system(extra);
        else if (!impl.systemContent.contains("Force Skill Use is on"))
            impl.system(impl.systemContent + "\n\n" + extra);
    }

    /**
     * Profile system-location wins. If allow-client-system is false, body.system is ignored.
     */
    static void applySystem(LlmClientImpl impl, Map<String, Object> body) {
        LlmFacadeImpl.ProfileState profile = impl != null ? impl.profile : null;
        if (profile != null && profile.systemLocation != null && !profile.systemLocation.isBlank()) {
            Map<String, Object> promptCtx = new LinkedHashMap<>();
            promptCtx.put("allowVueSfc", profile.allowVueSfc);
            try {
                String hints = ScreenSearchHints.text(impl.ec);
                if (hints != null && !hints.isBlank()) promptCtx.put("searchHints", hints);
            } catch (Throwable t) {
                logger.warn("Search screen hints failed: {}", t.getMessage());
            }
            String text = renderPrompt(impl.ec, profile.systemLocation, promptCtx);
            if (text != null && !text.isBlank()) impl.system(text);
            return;
        }
        boolean allowClient = profile == null || profile.allowClientSystem;
        if (!allowClient) return;
        String system = str(body != null ? body.get("system") : null);
        if (system != null) impl.system(system);
    }

    /** Replace a named context block so a resumed turn does not stack copies. */
    static void refreshContext(LlmClientImpl impl, String source, String content) {
        if (impl == null || source == null) return;
        try {
            if (impl.conversation != null) impl.conversation.removeContextBySource(source);
            if (content != null && !content.isBlank()) impl.injectContext(source, content);
        } catch (Throwable t) {
            logger.warn("Context {} failed: {}", source, t.getMessage());
        }
    }

    static void injectSkills(LlmClientImpl impl, String userText) {
        if (impl == null || userText == null || userText.isBlank()) return;
        try {
            impl.injectContext("skills", SkillIndex.formatInjectForQuery(impl.ec, userText));
        } catch (Throwable t) {
            logger.warn("Skill inject failed: {}", t.getMessage());
        }
    }

    /**
     * Render an LLM prompt template via ResourceFacade (FTL compile cache).
     * extraContext is pushed for the render only.
     */
    static String renderPrompt(ExecutionContext ec, String location, Map<String, Object> extraContext) {
        if (location == null || location.isBlank() || ec == null || ec.getResource() == null) return null;
        ContextStack cs = ec.getContext();
        boolean pushed = false;
        try {
            if (cs != null) {
                cs.push();
                pushed = true;
                if (extraContext != null && !extraContext.isEmpty()) cs.putAll(extraContext);
            }
            String text = ec.getResource().template(location, "");
            return text != null ? text.trim() : null;
        } catch (Throwable t) {
            logger.warn("Could not render LLM prompt {}: {}", location, t.getMessage());
            return null;
        } finally {
            if (pushed) cs.pop();
        }
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
        requireLlmGateway(ec);
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
        requireLlmGateway(ec);
        LlmConversationImpl conv = LlmConversationImpl.load(ec, conversationId, true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", conv.getConversationId());
        out.put("profileName", conv.getProfileName());
        out.put("userId", conv.getUserId());
        out.put("status", conv.getStatus());
        out.put("title", conv.getTitle());
        out.put("summary", conv.getSummary());
        out.put("createdDate", millis(conv.getCreatedDate()));
        out.put("lastMessageDate", millis(conv.getLastMessageDate()));
        out.put("hasCanvas", conv.hasCanvas());
        out.put("canvas", conv.getCanvasMap());
        List<Map<String, Object>> hist = new ArrayList<>();
        for (LlmMessage m : conv.getHistory()) hist.add(messageToMap(m));
        out.put("history", hist);
        out.put("pendingToolCalls", toolCallsToMaps(conv.getPendingClientToolCalls()));
        out.put("attributes", conv.getAttributes());
        return out;
    }

    public static final int CONVERSATION_PAGE_SIZE = 20;

    /**
     * Owner's conversations, newest first. ADMIN may list all.
     * purpose matches the purpose column. search matches searchText (text-fts), summary, and title.
     */
    public static Map<String, Object> listConversations(ExecutionContext ec, String profile, String purpose,
            String search, int pageIndex) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        out.put("conversations", rows);
        out.put("pageSize", CONVERSATION_PAGE_SIZE);
        int page = Math.max(pageIndex, 0);
        out.put("pageIndex", page);
        out.put("totalCount", 0L);
        requireLlmGateway(ec);
        if (ec == null || ec.getEntity() == null || ec.getUser() == null) return out;
        String userId = ec.getUser().getUserId();
        boolean admin = ec.getUser().isInGroup("ADMIN");
        if (!admin && (userId == null || userId.isBlank())) {
            out.put("pageIndex", 0);
            return out;
        }
        boolean authzWasDisabled = ec.getArtifactExecution().disableAuthz();
        try {
            backfillConversationList(ec, userId, admin, profile);
            org.moqui.entity.EntityFind find = conversationFind(ec, userId, admin, profile, purpose, search);
            long total = find.count();
            int lastPage = total <= 0 ? 0 : (int) ((total - 1) / CONVERSATION_PAGE_SIZE);
            if (page > lastPage) page = lastPage;
            out.put("pageIndex", page);
            out.put("totalCount", total);
            org.moqui.entity.EntityList list = find.orderBy("-lastMessageDate,-createdDate")
                    .offset(page, CONVERSATION_PAGE_SIZE).limit(CONVERSATION_PAGE_SIZE)
                    .selectField("conversationId").selectField("profileName").selectField("userId")
                    .selectField("statusId").selectField("title").selectField("summary")
                    .selectField("createdDate").selectField("lastMessageDate").selectField("messageCount")
                    .selectField("hasCanvas").list();
            if (list == null) return out;
            for (org.moqui.entity.EntityValue ev : list) {
                if (ev == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("conversationId", ev.getString("conversationId"));
                row.put("profileName", ev.getString("profileName"));
                row.put("userId", ev.getString("userId"));
                row.put("status", ev.getString("statusId"));
                row.put("title", ev.getString("title"));
                row.put("summary", ev.getString("summary"));
                row.put("createdDate", millis(ev.getTimestamp("createdDate")));
                row.put("lastMessageDate", millis(ev.getTimestamp("lastMessageDate")));
                row.put("messageCount", ev.get("messageCount"));
                row.put("hasCanvas", "Y".equals(ev.getString("hasCanvas")));
                rows.add(row);
            }
        } finally {
            if (!authzWasDisabled) ec.getArtifactExecution().enableAuthz();
        }
        return out;
    }

    /** 409 while Streaming. Otherwise delete the conversation and its messages, call logs, and skill uses. */
    public static Map<String, Object> deleteConversation(ExecutionContext ec, String conversationId) {
        requireLlmGateway(ec);
        LlmConversationImpl conv = LlmConversationImpl.load(ec, conversationId, true);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("conversationId", conv.getConversationId());
        if (LlmConversationImpl.STATUS_STREAMING.equals(conv.getStatus())) {
            out.put("httpStatus", 409);
            out.put("deleted", Boolean.FALSE);
            out.put("message", "Conversation is streaming");
            return out;
        }
        conv.deleteStored();
        out.put("httpStatus", 200);
        out.put("deleted", Boolean.TRUE);
        return out;
    }

    private static org.moqui.entity.EntityFind conversationFind(ExecutionContext ec, String userId, boolean admin,
            String profile, String purpose, String search) {
        org.moqui.entity.EntityFind find = ec.getEntity().find("moqui.llm.LlmConversation");
        if (!admin && userId != null) find.condition("userId", userId);
        if (profile != null && !profile.isBlank()) find.condition("profileName", profile.trim());
        if (purpose != null && !purpose.isBlank()) find.condition("purpose", purpose.trim());
        String term = searchTerm(search);
        if (term != null) {
            String like = "%" + term + "%";
            org.moqui.entity.EntityConditionFactory cf = ec.getEntity().getConditionFactory();
            List<org.moqui.entity.EntityCondition> ors = new ArrayList<>();
            // text-fts drops tokens shorter than 3 characters, so a short term matches summary and title only.
            if (term.length() >= 3)
                ors.add(cf.makeCondition("searchText", org.moqui.entity.EntityCondition.LIKE, like));
            ors.add(cf.makeCondition("summary", org.moqui.entity.EntityCondition.LIKE, like));
            ors.add(cf.makeCondition("title", org.moqui.entity.EntityCondition.LIKE, like));
            find.condition(cf.makeCondition(ors, org.moqui.entity.EntityCondition.OR));
        }
        return find;
    }

    /** Legacy rows: fill purpose, createdDate, summary, and searchText, and move lastWriteUi into canvasJson. */
    private static void backfillConversationList(ExecutionContext ec, String userId, boolean admin, String profile) {
        if (ec == null || ec.getEntity() == null) return;
        // One transaction so the header updates enlist and their record locks are cleared on commit.
        LlmConversationImpl.persistIsolated(ec, () -> {
        org.moqui.entity.EntityFind find = ec.getEntity().find("moqui.llm.LlmConversation")
                .condition("summary", org.moqui.entity.EntityCondition.IS_NULL, null).limit(100);
        if (!admin && userId != null) find.condition("userId", userId);
        if (profile != null && !profile.isBlank()) find.condition("profileName", profile.trim());
        org.moqui.entity.EntityList list = find.list();
        if (list == null) return;
        for (org.moqui.entity.EntityValue ev : list) {
            if (ev == null) continue;
            boolean changed = false;
            Map<String, Object> attrs = LlmJson.tryToMap(ev.getString("attributesJson"));
            String canvas = ev.getString("canvasJson");
            if ((canvas == null || canvas.isBlank()) && attrs != null
                    && attrs.get(WriteUiTool.ATTR_LAST_WRITE_UI) instanceof Map) {
                ev.set("canvasJson", LlmJson.toJson(attrs.get(WriteUiTool.ATTR_LAST_WRITE_UI)));
                ev.set("hasCanvas", "Y");
                attrs.remove(WriteUiTool.ATTR_LAST_WRITE_UI);
                ev.set("attributesJson", attrs.isEmpty() ? null : LlmJson.toJson(attrs));
                changed = true;
            }
            if (ev.getString("purpose") == null && attrs != null && attrs.get("purpose") != null
                    && !attrs.get("purpose").toString().isBlank()) {
                ev.set("purpose", attrs.get("purpose").toString().trim());
                changed = true;
            }
            String id = ev.getString("conversationId");
            if (ev.getTimestamp("createdDate") == null) {
                java.sql.Timestamp started = earliestMessageDate(ec, id);
                if (started == null) started = ev.getTimestamp("lastMessageDate");
                if (started != null) {
                    ev.set("createdDate", started);
                    changed = true;
                }
            }
            org.moqui.entity.EntityValue userMsg = firstUserMessage(ec, id);
            String text = userMsg != null ? userMsg.getString("content") : null;
            String summary;
            if (text != null && !text.isBlank()) summary = ConversationSummary.truncate(text);
            else if (ev.getString("title") != null && !ev.getString("title").isBlank())
                summary = ConversationSummary.truncate(ev.getString("title"));
            else summary = "New conversation";
            ev.set("summary", summary);
            ev.set("searchText", text != null && !text.isBlank()
                    ? ConversationSummary.cap(text, ConversationSummary.SEARCH_MAX) : summary);
            changed = true;
            if (changed) ev.update();
        }
        });
    }

    private static java.sql.Timestamp earliestMessageDate(ExecutionContext ec, String conversationId) {
        if (conversationId == null) return null;
        org.moqui.entity.EntityList list = ec.getEntity().find("moqui.llm.LlmMessage")
                .condition("conversationId", conversationId).orderBy("sentDate").limit(1).list();
        if (list == null || list.isEmpty()) return null;
        return list.get(0).getTimestamp("sentDate");
    }

    private static org.moqui.entity.EntityValue firstUserMessage(ExecutionContext ec, String conversationId) {
        if (conversationId == null) return null;
        org.moqui.entity.EntityList list = ec.getEntity().find("moqui.llm.LlmMessage")
                .condition("conversationId", conversationId).condition("role", "USER")
                .orderBy("ordinal").limit(1).list();
        if (list == null || list.isEmpty()) return null;
        return list.get(0);
    }

    private static String searchTerm(String search) {
        if (search == null) return null;
        String cleaned = search.replace('%', ' ').replace('_', ' ').trim();
        if (cleaned.isEmpty()) return null;
        return cleaned;
    }

    private static Long millis(java.sql.Timestamp ts) {
        return ts == null ? null : ts.getTime();
    }

    /** Profiles the current user is authorized to use (AT_LLM VIEW). Names + model, never keys. */
    public static List<Map<String, Object>> listProfiles(ExecutionContext ec) {
        requireLlmGateway(ec);
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
                if (ps != null) {
                    row.put("allowWriteUi", ps.allowWriteUi);
                    row.put("allowBrowse", ps.allowBrowse);
                    row.put("allowRunService", ps.allowRunService);
                    row.put("allowUnprefixedRequest", ps.allowUnprefixedRequest);
                    row.put("allowEnterSim", ps.allowEnterSim);
                    row.put("allowClientSystem", ps.allowClientSystem);
                    row.put("allowVueSfc", ps.allowVueSfc);
                }
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
        if (Boolean.TRUE.equals(call.confirm)) m.put("confirm", Boolean.TRUE);
        if (call.risk != null && !call.risk.isBlank()) m.put("risk", call.risk);
        m.put("summary", LlmTrace.summarizeCall(call.name, args != null ? args : call.arguments));
        return m;
    }
    public static Map<String, Object> toolResultToMap(LlmToolCall call, Object result, LlmTool.Execution execution) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", call != null ? call.id : null);
        m.put("name", call != null ? call.name : null);
        m.put("execution", executionName(execution != null ? execution : (call != null ? call.execution : null)));
        m.put("content", result);
        m.put("summary", LlmTrace.summarizeResult(call != null ? call.name : null, result));
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
        m.put("durationMs", r != null ? r.durationMs : 0L);
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
