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
import org.moqui.context.ExecutionContext;
import org.moqui.context.TransactionFacade;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.llm.LlmConversation;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmProtocol.ProtocolResult;
import org.moqui.llm.LlmToolCall;
import org.moqui.llm.LlmUsage;
import org.moqui.llm.WindowPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/**
 * Durable conversation. In-memory list is always the working copy; EntityFacade writes happen inside
 * persistIsolated (same-thread suspend/begin/commit/resume — not TransactionFacade.runRequireNew).
 */
public class LlmConversationImpl implements LlmConversation {
    private static final Logger logger = LoggerFactory.getLogger(LlmConversationImpl.class);
    static final String STATUS_ACTIVE = "LlmcsActive";
    static final String STATUS_STREAMING = "LlmcsStreaming";
    static final String STATUS_YIELDED = "LlmcsYielded";
    static final String STATUS_COMPLETE = "LlmcsComplete";
    static final String STATUS_FAILED = "LlmcsFailed";
    static final String STATUS_CANCELLED = "LlmcsCancelled";
    static final int PERSIST_TIMEOUT_SECONDS = 30;

    private static final ThreadLocal<Integer> PERSIST_DEPTH = new ThreadLocal<>();

    private final ExecutionContext ec;
    private String conversationId;
    private String profileName;
    private String userId;
    private String visitId;
    private String statusId = STATUS_ACTIVE;
    private String title;
    private String systemText;
    private WindowPolicy windowPolicy = new WindowPolicy();
    private final List<LlmToolCall> pendingToolCalls = new ArrayList<>();
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private Timestamp lastMessageDate;
    private final List<LlmMessage> messages = new ArrayList<>();

    LlmConversationImpl(ExecutionContext ec, String conversationId) {
        this.ec = ec;
        this.conversationId = conversationId;
    }

    /** In-memory conversation for tests and callers without EntityFacade. */
    public static LlmConversationImpl create(ExecutionContext ec, String profileName, Map<String, Object> attributes) {
        LlmConversationImpl conv = new LlmConversationImpl(ec, hasEntity(ec) ? null : newInMemoryId());
        conv.profileName = profileName;
        if (ec != null && ec.getUser() != null) {
            conv.userId = ec.getUser().getUserId();
            conv.visitId = ec.getUser().getVisitId();
        }
        if (attributes != null) conv.attributes.putAll(attributes);
        conv.lastMessageDate = now(ec);
        persistIsolated(ec, () -> {
            if (hasEntity(ec)) {
                EntityValue ev = ec.getEntity().makeValue("moqui.llm.LlmConversation");
                ev.setSequencedIdPrimary();
                conv.conversationId = ev.getString("conversationId");
                conv.writeHeader(ev, true);
            }
        });
        if (conv.conversationId == null) conv.conversationId = newInMemoryId();
        return conv;
    }

    public static LlmConversationImpl load(ExecutionContext ec, String conversationId, boolean checkOwner) {
        if (conversationId == null || conversationId.isBlank())
            throw new LlmException("conversationId is required");
        if (!hasEntity(ec))
            throw new LlmException("Cannot load conversation '" + conversationId + "' without EntityFacade");
        LlmConversationImpl conv = new LlmConversationImpl(ec, conversationId);
        persistIsolated(ec, () -> conv.readFromStore());
        if (conv.statusId == null)
            throw new LlmException("Conversation not found: " + conversationId);
        if (checkOwner) checkCanView(ec, conv.userId);
        return conv;
    }

    /** Owner or ADMIN only (K22). No user on the EC is allowed only for conversations with no owner. */
    static void checkCanView(ExecutionContext ec, String ownerUserId) {
        if (ec == null || ec.getUser() == null) return;
        String current = ec.getUser().getUserId();
        if (current == null || current.isEmpty()) {
            if (ownerUserId == null || ownerUserId.isEmpty()) return;
            throw new ArtifactAuthorizationException("User is not authorized to view LLM conversation");
        }
        if (current.equals(ownerUserId)) return;
        if (ec.getUser().isInGroup("ADMIN")) return;
        throw new ArtifactAuthorizationException("User is not authorized to view LLM conversation");
    }

    /**
     * Same-thread require-new TX (ServiceCallSync.requireNewTransaction pattern).
     * Reentrant: nested calls run in the already-isolated TX. Do not use runRequireNew (that starts a thread).
     */
    public static void persistIsolated(ExecutionContext ec, Runnable work) {
        if (work == null) return;
        Integer depth = PERSIST_DEPTH.get();
        if (depth != null && depth > 0) {
            work.run();
            return;
        }
        if (ec == null || ec.getTransaction() == null) {
            work.run();
            return;
        }
        PERSIST_DEPTH.set(1);
        ArtifactExecutionFacade aefi = ec.getArtifactExecution();
        boolean alreadyDisabled = aefi != null && aefi.disableAuthz();
        TransactionFacade tf = ec.getTransaction();
        boolean suspended = false;
        try {
            if (tf.isTransactionInPlace()) suspended = tf.suspend();
            boolean began = tf.begin(PERSIST_TIMEOUT_SECONDS);
            try {
                work.run();
            } catch (Throwable t) {
                tf.rollback(began, "Error persisting LLM data", t);
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                throw new LlmException("Error persisting LLM data", t);
            } finally {
                if (tf.isTransactionInPlace()) tf.commit(began);
            }
        } finally {
            if (suspended) {
                try { tf.resume(); }
                catch (Throwable t) { logger.error("Error resuming transaction after LLM persist", t); }
            }
            if (aefi != null && !alreadyDisabled) aefi.enableAuthz();
            PERSIST_DEPTH.remove();
        }
    }

    static boolean isCancelThrowable(Throwable t) {
        while (t != null) {
            if (t instanceof InterruptedException || t instanceof CancellationException) return true;
            t = t.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }

    @Override public String getConversationId() { return conversationId; }
    @Override public String getProfileName() { return profileName; }
    @Override public String getUserId() { return userId; }
    @Override public String getStatus() { return statusId; }
    @Override public String getTitle() { return title; }

    @Override
    public void setTitle(String title) {
        persistIsolated(ec, () -> {
            this.title = title;
            updateHeader();
        });
    }

    @Override
    public List<LlmMessage> getHistory() { return new ArrayList<>(messages); }

    @Override
    public List<LlmMessage> buildWindow() { return buildWindow(windowPolicy); }

    @Override
    public List<LlmMessage> buildWindow(WindowPolicy p) {
        WindowPolicy policy = p != null ? p : windowPolicy;
        if (policy == null) policy = new WindowPolicy();
        LlmMessage system = null;
        List<LlmMessage> context = new ArrayList<>();
        List<LlmMessage> rest = new ArrayList<>();
        for (LlmMessage m : messages) {
            if (m == null || m.role == null) continue;
            if (m.role == LlmMessage.Role.SYSTEM) {
                if (system == null) system = m;
            } else if (m.role == LlmMessage.Role.CONTEXT) {
                if (policy.includeContext) context.add(m);
            } else {
                rest.add(m);
            }
        }
        rest = trimRest(rest, policy, charsOf(system) + charsOfAll(context));
        List<LlmMessage> window = new ArrayList<>();
        if (policy.keepSystemFirst && system != null) window.add(system);
        window.addAll(context);
        window.addAll(rest);
        return window;
    }

    @Override
    public LlmConversation append(LlmMessage message) {
        if (message == null) return this;
        persistIsolated(ec, () -> appendInternal(message.copy()));
        return this;
    }
    @Override
    public LlmConversation appendUser(String content) {
        return append(LlmMessage.user(content));
    }
    @Override
    public LlmConversation appendAssistant(String content) {
        return append(LlmMessage.assistant(content));
    }
    @Override
    public LlmConversation appendToolResult(String toolCallId, String name, Object content) {
        String text;
        if (content == null) text = "";
        else if (content instanceof String) text = (String) content;
        else text = LlmJson.toJson(content);
        return append(LlmMessage.tool(toolCallId, name, text));
    }

    @Override
    public LlmConversation replaceSystem(String content) {
        persistIsolated(ec, () -> replaceSystemInternal(content));
        return this;
    }

    @Override
    public LlmConversation injectContext(String source, String content) {
        LlmMessage ctx = LlmMessage.context(source, content);
        persistIsolated(ec, () -> appendInternal(ctx));
        return this;
    }

    @Override
    public LlmConversation removeContextBySource(String source) {
        persistIsolated(ec, () -> {
            List<LlmMessage> removed = new ArrayList<>();
            for (LlmMessage m : messages) {
                if (m.role == LlmMessage.Role.CONTEXT && sourceEquals(m, source)) removed.add(m);
            }
            for (LlmMessage m : removed) removeInternal(m);
        });
        return this;
    }

    @Override
    public LlmConversation clearContext() {
        persistIsolated(ec, () -> {
            List<LlmMessage> removed = new ArrayList<>();
            for (LlmMessage m : messages) {
                if (m.role == LlmMessage.Role.CONTEXT) removed.add(m);
            }
            for (LlmMessage m : removed) removeInternal(m);
        });
        return this;
    }

    @Override
    public LlmConversation replaceMessage(String messageId, LlmMessage replacement) {
        if (messageId == null || replacement == null) return this;
        persistIsolated(ec, () -> {
            for (int i = 0; i < messages.size(); i++) {
                LlmMessage cur = messages.get(i);
                if (messageId.equals(cur.messageId)) {
                    LlmMessage copy = replacement.copy();
                    copy.messageId = cur.messageId;
                    copy.ordinal = cur.ordinal;
                    if (copy.sentDate == null) copy.sentDate = cur.sentDate != null ? cur.sentDate : now(ec);
                    if (copy.role == null) copy.role = cur.role;
                    messages.set(i, copy);
                    if (copy.role == LlmMessage.Role.SYSTEM) systemText = copy.content;
                    writeMessage(copy, false);
                    updateHeader();
                    return;
                }
            }
            throw new LlmException("Message not found: " + messageId);
        });
        return this;
    }

    @Override
    public LlmConversation removeMessage(String messageId) {
        if (messageId == null) return this;
        persistIsolated(ec, () -> {
            LlmMessage found = null;
            for (LlmMessage m : messages) {
                if (messageId.equals(m.messageId)) { found = m; break; }
            }
            if (found != null) removeInternal(found);
        });
        return this;
    }

    @Override
    public LlmConversation setWindowPolicy(WindowPolicy policy) {
        persistIsolated(ec, () -> {
            this.windowPolicy = policy != null ? policy : new WindowPolicy();
            updateHeader();
        });
        return this;
    }
    @Override
    public WindowPolicy getWindowPolicy() { return windowPolicy != null ? windowPolicy.copy() : new WindowPolicy(); }

    @Override
    public List<LlmToolCall> getPendingClientToolCalls() { return new ArrayList<>(pendingToolCalls); }

    @Override
    public Map<String, Object> getAttributes() { return new LinkedHashMap<>(attributes); }

    @Override
    public void setAttribute(String name, Object value) {
        persistIsolated(ec, () -> {
            if (name == null) return;
            if (value == null) attributes.remove(name);
            else attributes.put(name, value);
            updateHeader();
        });
    }

    @Override
    public void cancel() {
        persistIsolated(ec, () -> {
            if (STATUS_COMPLETE.equals(statusId) || STATUS_FAILED.equals(statusId)
                    || STATUS_CANCELLED.equals(statusId)) return;
            statusId = STATUS_CANCELLED;
            pendingToolCalls.clear();
            updateHeader();
        });
    }

    @Override
    public void persist() {
        persistIsolated(ec, () -> {
            updateHeader();
            for (LlmMessage m : messages) writeMessage(m, m.messageId == null);
        });
    }

    void setStatusInternal(String status) {
        this.statusId = status;
        updateHeader();
    }

    void replaceSystemInternal(String content) {
        LlmMessage existing = findSystem();
        if (existing != null) {
            existing.content = content;
            existing.sentDate = now(ec);
            systemText = content;
            writeMessage(existing, false);
            updateHeader();
        } else {
            LlmMessage sys = LlmMessage.system(content);
            appendInternal(sys);
        }
    }

    void appendInternal(LlmMessage message) {
        if (message.role == LlmMessage.Role.SYSTEM) {
            LlmMessage existing = findSystem();
            if (existing != null) {
                existing.content = message.content;
                existing.name = message.name;
                existing.metadata = message.metadata;
                existing.sentDate = now(ec);
                systemText = message.content;
                writeMessage(existing, false);
                updateHeader();
                return;
            }
        }
        message.ordinal = nextOrdinal();
        message.sentDate = message.sentDate != null ? message.sentDate : now(ec);
        if (message.messageId == null && !hasEntity(ec)) message.messageId = newInMemoryId();
        messages.add(message);
        if (message.role == LlmMessage.Role.SYSTEM) systemText = message.content;
        lastMessageDate = message.sentDate;
        writeMessage(message, true);
        updateHeader();
    }

    void writeCallLog(String profileName, String protocolName, String model, boolean logContent,
            List<LlmMessage> window, ProtocolResult result, long durationMs, int iteration, boolean wasError) {
        if (!hasEntity(ec)) return;
        EntityValue ev = ec.getEntity().makeValue("moqui.llm.LlmCallLog");
        ev.setSequencedIdPrimary();
        ev.set("conversationId", conversationId);
        ev.set("profileName", profileName);
        ev.set("model", model);
        ev.set("protocolName", protocolName);
        ev.set("userId", userId);
        ev.set("visitId", visitId);
        if (result != null) {
            ev.set("httpStatus", result.httpStatus);
            ev.set("finishReason", result.finishReason != null ? result.finishReason.name() : null);
            if (result.usage != null) {
                LlmUsage u = result.usage;
                ev.set("promptTokens", u.promptTokens);
                ev.set("completionTokens", u.completionTokens);
                ev.set("totalTokens", u.totalTokens);
            }
            ev.set("errorMessage", result.errorMessage);
            if (logContent) ev.set("responseJson", result.rawJson);
        }
        ev.set("durationMs", durationMs);
        ev.set("iteration", iteration);
        ev.set("wasError", wasError ? "Y" : "N");
        ev.set("startDate", now(ec));
        if (logContent && window != null) {
            List<Map<String, Object>> win = new ArrayList<>();
            for (LlmMessage m : window) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("role", m.role != null ? m.role.name() : null);
                row.put("content", m.content);
                row.put("name", m.name);
                row.put("toolCallId", m.toolCallId);
                win.add(row);
            }
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("model", model);
            req.put("messages", win);
            ev.set("requestJson", LlmJson.toJson(req));
        }
        ev.create();
    }

    boolean isStreamingOrYielded() {
        return STATUS_STREAMING.equals(statusId) || STATUS_YIELDED.equals(statusId);
    }

    private void removeInternal(LlmMessage found) {
        messages.remove(found);
        if (found.role == LlmMessage.Role.SYSTEM) systemText = findSystem() != null ? findSystem().content : null;
        if (hasEntity(ec) && found.messageId != null) {
            EntityValue ev = ec.getEntity().find("moqui.llm.LlmMessage").condition("messageId", found.messageId).one();
            if (ev != null) ev.delete();
        }
        updateHeader();
    }

    private LlmMessage findSystem() {
        for (LlmMessage m : messages) {
            if (m.role == LlmMessage.Role.SYSTEM) return m;
        }
        return null;
    }

    private int nextOrdinal() {
        int next = 0;
        for (LlmMessage m : messages) if (m.ordinal >= next) next = m.ordinal + 1;
        return next;
    }

    private void readFromStore() {
        EntityValue header = ec.getEntity().find("moqui.llm.LlmConversation")
                .condition("conversationId", conversationId).one();
        if (header == null) {
            statusId = null;
            return;
        }
        profileName = header.getString("profileName");
        userId = header.getString("userId");
        visitId = header.getString("visitId");
        statusId = header.getString("statusId");
        title = header.getString("title");
        systemText = header.getString("systemText");
        windowPolicy = WindowPolicy.fromMap(LlmJson.toMap(header.getString("windowPolicyJson")));
        pendingToolCalls.clear();
        pendingToolCalls.addAll(parseToolCalls(header.getString("pendingToolCallsJson")));
        attributes.clear();
        Map<String, Object> attrs = LlmJson.toMap(header.getString("attributesJson"));
        if (attrs != null) attributes.putAll(attrs);
        lastMessageDate = header.getTimestamp("lastMessageDate");
        messages.clear();
        EntityList list = ec.getEntity().find("moqui.llm.LlmMessage")
                .condition("conversationId", conversationId).orderBy("ordinal").list();
        int size = list.size();
        for (int i = 0; i < size; i++) messages.add(fromEntity(list.get(i)));
    }

    private void writeHeader(EntityValue ev, boolean create) {
        ev.set("profileName", profileName);
        ev.set("userId", userId);
        ev.set("visitId", visitId);
        ev.set("statusId", statusId != null ? statusId : STATUS_ACTIVE);
        ev.set("title", title);
        ev.set("systemText", systemText);
        ev.set("windowPolicyJson", LlmJson.toJson(windowPolicy.toMap()));
        ev.set("pendingToolCallsJson", pendingToolCalls.isEmpty() ? null : LlmJson.toJson(pendingToolCalls));
        ev.set("attributesJson", attributes.isEmpty() ? null : LlmJson.toJson(attributes));
        ev.set("lastMessageDate", lastMessageDate);
        ev.set("messageCount", messages.size());
        if (create) ev.create();
        else ev.update();
    }

    private void updateHeader() {
        if (!hasEntity(ec) || conversationId == null) return;
        EntityValue ev = ec.getEntity().find("moqui.llm.LlmConversation")
                .condition("conversationId", conversationId).one();
        if (ev == null) {
            ev = ec.getEntity().makeValue("moqui.llm.LlmConversation");
            ev.set("conversationId", conversationId);
            writeHeader(ev, true);
        } else {
            writeHeader(ev, false);
        }
    }

    private void writeMessage(LlmMessage m, boolean create) {
        if (!hasEntity(ec)) return;
        EntityValue ev;
        if (create || m.messageId == null) {
            ev = ec.getEntity().makeValue("moqui.llm.LlmMessage");
            ev.setSequencedIdPrimary();
            m.messageId = ev.getString("messageId");
            ev.set("conversationId", conversationId);
            fillMessage(ev, m);
            ev.create();
        } else {
            ev = ec.getEntity().find("moqui.llm.LlmMessage").condition("messageId", m.messageId).one();
            if (ev == null) {
                ev = ec.getEntity().makeValue("moqui.llm.LlmMessage");
                ev.set("messageId", m.messageId);
                ev.set("conversationId", conversationId);
                fillMessage(ev, m);
                ev.create();
            } else {
                fillMessage(ev, m);
                ev.update();
            }
        }
    }

    private void fillMessage(EntityValue ev, LlmMessage m) {
        ev.set("ordinal", m.ordinal);
        ev.set("role", m.role != null ? m.role.name() : LlmMessage.Role.USER.name());
        ev.set("content", m.content);
        ev.set("name", m.name);
        ev.set("toolCallId", m.toolCallId);
        ev.set("toolCallsJson", m.toolCalls == null || m.toolCalls.isEmpty() ? null : LlmJson.toJson(m.toolCalls));
        ev.set("metadataJson", m.metadata == null || m.metadata.isEmpty() ? null : LlmJson.toJson(m.metadata));
        ev.set("tokenEstimate", tokenEstimate(m.content));
        ev.set("sentDate", m.sentDate != null ? m.sentDate : now(ec));
    }

    private static LlmMessage fromEntity(EntityValue ev) {
        LlmMessage m = new LlmMessage();
        m.messageId = ev.getString("messageId");
        Long ord = ev.getLong("ordinal");
        m.ordinal = ord != null ? ord.intValue() : 0;
        String role = ev.getString("role");
        try {
            m.role = role != null ? LlmMessage.Role.valueOf(role) : LlmMessage.Role.USER;
        } catch (IllegalArgumentException e) {
            m.role = LlmMessage.Role.USER;
        }
        m.content = ev.getString("content");
        m.name = ev.getString("name");
        m.toolCallId = ev.getString("toolCallId");
        m.toolCalls = parseToolCalls(ev.getString("toolCallsJson"));
        m.metadata = LlmJson.toMap(ev.getString("metadataJson"));
        m.sentDate = ev.getTimestamp("sentDate");
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<LlmToolCall> parseToolCalls(String json) {
        List<LlmToolCall> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        Object obj = LlmJson.toObject(json);
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) {
            if (item instanceof LlmToolCall) {
                out.add((LlmToolCall) item);
            } else if (item instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) item;
                LlmToolCall tc = new LlmToolCall();
                Object id = map.get("id");
                Object name = map.get("name");
                Object args = map.get("arguments");
                tc.id = id != null ? id.toString() : null;
                tc.name = name != null ? name.toString() : null;
                tc.arguments = args != null ? args.toString() : null;
                out.add(tc);
            }
        }
        return out;
    }

    private static List<LlmMessage> trimRest(List<LlmMessage> rest, WindowPolicy policy, int reservedChars) {
        if (rest.isEmpty()) return rest;
        List<LlmMessage> kept = new ArrayList<>(rest);
        while (!kept.isEmpty()) {
            boolean overMessages = policy.maxMessages > 0 && kept.size() > policy.maxMessages;
            boolean overChars = policy.maxChars > 0 && (reservedChars + charsOfAll(kept)) > policy.maxChars;
            if (!overMessages && !overChars) break;
            int drop = 1;
            if (policy.keepToolPairs) drop = pairLength(kept, 0);
            if (drop < 1) drop = 1;
            if (drop > kept.size()) drop = kept.size();
            kept.subList(0, drop).clear();
        }
        return kept;
    }

    private static int pairLength(List<LlmMessage> msgs, int i) {
        LlmMessage first = msgs.get(i);
        if (first.role == LlmMessage.Role.ASSISTANT && first.toolCalls != null && !first.toolCalls.isEmpty()) {
            Set<String> ids = new LinkedHashSet<>();
            for (LlmToolCall tc : first.toolCalls) if (tc != null && tc.id != null) ids.add(tc.id);
            int n = 1;
            for (int j = i + 1; j < msgs.size(); j++) {
                LlmMessage t = msgs.get(j);
                if (t.role == LlmMessage.Role.TOOL && (t.toolCallId == null || ids.contains(t.toolCallId))) n++;
                else break;
            }
            return n;
        }
        return 1;
    }

    private static int charsOf(LlmMessage m) {
        if (m == null || m.content == null) return 0;
        return m.content.length();
    }
    private static int charsOfAll(List<LlmMessage> list) {
        int n = 0;
        for (LlmMessage m : list) n += charsOf(m);
        return n;
    }
    private static int tokenEstimate(String content) {
        if (content == null || content.isEmpty()) return 0;
        return Math.max(1, content.length() / 4);
    }
    private static boolean sourceEquals(LlmMessage m, String source) {
        if (m.metadata == null) return source == null;
        Object s = m.metadata.get("source");
        if (source == null) return s == null;
        return source.equals(s);
    }
    private static boolean hasEntity(ExecutionContext ec) {
        return ec != null;
    }
    private static Timestamp now(ExecutionContext ec) {
        if (ec != null && ec.getUser() != null) return ec.getUser().getNowTimestamp();
        return new Timestamp(System.currentTimeMillis());
    }
    private static String newInMemoryId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
