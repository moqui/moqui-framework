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

import org.moqui.context.ArtifactExecutionFacade;
import org.moqui.context.ExecutionContext;
import org.moqui.context.TransactionFacade;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.llm.tools.WriteUiTool;
import org.moqui.llm.LlmConversation;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmFinishReason;
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
    /** Status id as a person reads it: "Streaming", not "LlmcsStreaming". For messages that reach users and models. */
    static String statusText(String statusId) {
        return statusId != null && statusId.startsWith("Llmcs") ? statusId.substring(5) : statusId;
    }

    static final String STATUS_ACTIVE = "LlmcsActive";
    static final String STATUS_STREAMING = "LlmcsStreaming";
    static final String STATUS_YIELDED = "LlmcsYielded";
    static final String STATUS_COMPLETE = "LlmcsComplete";
    static final String STATUS_FAILED = "LlmcsFailed";
    static final String STATUS_CANCELLED = "LlmcsCancelled";
    static final int PERSIST_TIMEOUT_SECONDS = 30;

    private static final ThreadLocal<Integer> PERSIST_DEPTH = new ThreadLocal<>();
    /** Set while persistIsolated holds authz disabled that the caller had enabled. */
    private static final ThreadLocal<Boolean> PERSIST_OWNS_AUTHZ = new ThreadLocal<>();

    /**
     * Run a tool with the caller's own authz setting. persistIsolated disables authz for LLM
     * bookkeeping writes, which must not extend to a tool the user asked to execute.
     */
    static <T> T withCallerAuthz(ExecutionContext ec, java.util.function.Supplier<T> work) {
        ArtifactExecutionFacade aefi = ec != null ? ec.getArtifactExecution() : null;
        boolean restore = aefi != null && Boolean.TRUE.equals(PERSIST_OWNS_AUTHZ.get());
        if (restore) aefi.enableAuthz();
        try {
            return work.get();
        } finally {
            if (restore) aefi.disableAuthz();
        }
    }

    private final ExecutionContext ec;
    private String conversationId;
    private String profileName;
    private String userId;
    private String visitId;
    private String statusId = STATUS_ACTIVE;
    private String title;
    private Timestamp createdDate;
    private String purpose;
    private String summary;
    private String searchText;
    private String hasCanvas;
    private String summaryFromLlm;
    private String canvasJson;
    private String systemText;
    private WindowPolicy windowPolicy = new WindowPolicy();
    private final List<LlmToolCall> pendingToolCalls = new ArrayList<>();
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private Timestamp lastMessageDate;
    /** Token from {@link LlmFacadeImpl#claimTurn}. Zero means this instance does not hold the claim. */
    private long turnToken = 0L;
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
        if (attributes != null) {
            conv.attributes.putAll(attributes);
            Object purpose = attributes.get("purpose");
            if (purpose != null && !purpose.toString().isBlank()) conv.purpose = purpose.toString().trim();
        }
        conv.lastMessageDate = now(ec);
        conv.createdDate = conv.lastMessageDate;
        persistIsolated(ec, () -> {
            if (hasEntity(ec)) {
                EntityValue ev = ec.getEntity().makeValue("moqui.llm.LlmConversation");
                ev.setSequencedIdPrimary();
                conv.conversationId = ev.getString("conversationId");
                conv.writeHeader(ev, true);
            }
        }, conv);
        if (conv.conversationId == null) conv.conversationId = newInMemoryId();
        return conv;
    }

    public static LlmConversationImpl load(ExecutionContext ec, String conversationId, boolean checkOwner) {
        if (conversationId == null || conversationId.isBlank())
            throw new LlmException("conversationId is required");
        if (!hasEntity(ec))
            throw new LlmException("Cannot load conversation '" + conversationId + "' without EntityFacade");
        LlmConversationImpl conv = new LlmConversationImpl(ec, conversationId);
        persistIsolated(ec, () -> conv.readFromStore(), conv);
        if (conv.statusId == null)
            throw new LlmException("Conversation not found: " + conversationId,
                    null, LlmFinishReason.ERROR, 404, null, conversationId);
        if (checkOwner) checkCanView(ec, conv.userId);
        return conv;
    }

    /** Owner or ADMIN only (K22). No user on the EC is allowed only for conversations with no owner. */
    public static void checkCanView(ExecutionContext ec, String ownerUserId) {
        if (ec == null || ec.getUser() == null) return;
        String current = ec.getUser().getUserId();
        if (current == null || current.isEmpty()) {
            if (ownerUserId == null || ownerUserId.isEmpty()) return;
            throw new LlmException("User is not authorized to view LLM conversation",
                    null, LlmFinishReason.ERROR, 403, null, null);
        }
        if (current.equals(ownerUserId)) return;
        if (ec.getUser().isInGroup("ADMIN")) return;
        throw new LlmException("User is not authorized to view LLM conversation",
                null, LlmFinishReason.ERROR, 403, null, null);
    }

    /**
     * Same-thread require-new TX (ServiceCallSync.requireNewTransaction pattern).
     * Reentrant: nested calls run in the already-isolated TX. Do not use runRequireNew (that starts a thread).
     * On any failure before a successful commit, {@code conv} memory is restored so rollback cannot leave
     * in-memory status ahead of the DB (K11).
     */
    public static void persistIsolated(ExecutionContext ec, Runnable work) {
        persistIsolated(ec, work, null);
    }
    public void persistIsolated(Runnable work) {
        persistIsolated(ec, work, this);
    }
    static void persistIsolated(ExecutionContext ec, Runnable work, LlmConversationImpl conv) {
        if (work == null) return;
        Integer depth = PERSIST_DEPTH.get();
        if (depth != null && depth > 0) {
            work.run();
            return;
        }
        Snapshot snap = conv != null ? conv.snapshot() : null;
        if (ec == null || ec.getTransaction() == null) {
            try {
                work.run();
            } catch (Throwable t) {
                if (snap != null) conv.restore(snap);
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                throw new LlmException("Error persisting LLM data", t);
            }
            return;
        }
        PERSIST_DEPTH.set(1);
        ArtifactExecutionFacade aefi = ec.getArtifactExecution();
        boolean alreadyDisabled = aefi != null && aefi.disableAuthz();
        PERSIST_OWNS_AUTHZ.set(!alreadyDisabled);
        TransactionFacade tf = ec.getTransaction();
        boolean suspended = false;
        try {
            if (tf.isTransactionInPlace()) suspended = tf.suspend();
            boolean began = tf.begin(PERSIST_TIMEOUT_SECONDS);
            try {
                work.run();
                if (tf.isTransactionInPlace()) tf.commit(began);
            } catch (Throwable t) {
                if (snap != null) conv.restore(snap);
                try { tf.rollback(began, "Error persisting LLM data", t); }
                catch (Throwable rb) { logger.error("Error rolling back LLM persist", rb); }
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                throw new LlmException("Error persisting LLM data", t);
            }
        } finally {
            if (suspended) {
                try { tf.resume(); }
                catch (Throwable t) { logger.error("Error resuming transaction after LLM persist", t); }
            }
            if (aefi != null && !alreadyDisabled) aefi.enableAuthz();
            PERSIST_DEPTH.remove();
            PERSIST_OWNS_AUTHZ.remove();
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
    public String getSummary() { return summary; }
    public boolean isSummaryFromLlm() { return "Y".equals(summaryFromLlm); }
    public Timestamp getCreatedDate() { return createdDate; }
    public Timestamp getLastMessageDate() { return lastMessageDate; }
    public String getCanvasJson() { return canvasJson; }
    public boolean hasCanvas() { return "Y".equals(hasCanvas); }

    /** Parsed canvas, or null. A new map each call. */
    public Map<String, Object> getCanvasMap() {
        if (canvasJson == null || canvasJson.isBlank()) return null;
        return LlmJson.tryToMap(canvasJson);
    }

    /** In memory only. The yield commit writes the header. */
    public void setCanvasMap(Map<String, Object> canvas) {
        if (canvas == null || canvas.isEmpty()) {
            canvasJson = null;
            hasCanvas = "N";
            return;
        }
        canvasJson = LlmJson.toJson(canvas);
        hasCanvas = "Y";
    }

    void assignSummary(String summary, String searchText, boolean fromLlm) {
        this.summary = summary;
        this.searchText = searchText;
        if (fromLlm) this.summaryFromLlm = "Y";
        else if (this.summaryFromLlm == null) this.summaryFromLlm = "N";
    }

    void markSummaryAttempted() { this.summaryFromLlm = "Y"; }

    String firstUserContent() {
        for (LlmMessage m : messages) {
            if (m != null && m.role == LlmMessage.Role.USER && m.content != null && !m.content.isBlank())
                return m.content;
        }
        return null;
    }

    /** Point the latest assistant tool calls at the enriched yield payload and write that row. */
    void syncYieldedToolCalls(List<LlmToolCall> pending) {
        if (pending == null || pending.isEmpty()) return;
        Map<String, LlmToolCall> byId = new LinkedHashMap<>();
        for (LlmToolCall call : pending) {
            if (call != null && call.id != null) byId.put(call.id, call);
        }
        if (byId.isEmpty()) return;
        for (int i = messages.size() - 1; i >= 0; i--) {
            LlmMessage message = messages.get(i);
            if (message == null || message.role != LlmMessage.Role.ASSISTANT || message.toolCalls == null) continue;
            boolean changed = false;
            List<LlmToolCall> next = new ArrayList<>();
            for (LlmToolCall call : message.toolCalls) {
                LlmToolCall repl = call != null ? byId.get(call.id) : null;
                if (repl != null) {
                    next.add(repl.copy());
                    changed = true;
                } else {
                    next.add(call);
                }
            }
            if (changed) {
                message.toolCalls = next;
                writeMessage(message, false);
            }
            return;
        }
    }

    void deleteStored() {
        persistIsolated(() -> {
            if (!hasEntity(ec) || conversationId == null) return;
            String id = conversationId;
            ec.getEntity().find("moqui.llm.LlmSkillUse").condition("conversationId", id).deleteAll();
            ec.getEntity().find("moqui.llm.LlmCallLog").condition("conversationId", id).deleteAll();
            ec.getEntity().find("moqui.llm.LlmMessage").condition("conversationId", id).deleteAll();
            EntityValue ev = ec.getEntity().find("moqui.llm.LlmConversation")
                    .condition("conversationId", id).one();
            if (ev != null) ev.delete();
            messages.clear();
            pendingToolCalls.clear();
            statusId = null;
        });
    }

    @Override
    public void setTitle(String title) {
        persistIsolated(() -> {
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
        return WindowBuilder.build(messages, p != null ? p : windowPolicy, conversationId);
    }

    @Override
    public LlmConversation append(LlmMessage message) {
        if (message == null) return this;
        persistIsolated(() -> appendInternal(message.copy()));
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
        persistIsolated(() -> replaceSystemInternal(content));
        return this;
    }

    @Override
    public LlmConversation injectContext(String source, String content) {
        LlmMessage ctx = LlmMessage.context(source, content);
        persistIsolated(() -> appendInternal(ctx));
        return this;
    }

    @Override
    public LlmConversation removeContextBySource(String source) {
        persistIsolated(() -> {
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
        persistIsolated(() -> {
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
        persistIsolated(() -> {
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
                    lastMessageDate = copy.sentDate;
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
        persistIsolated(() -> {
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
        persistIsolated(() -> {
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
        persistIsolated(() -> {
            if (name == null) return;
            if (value == null) attributes.remove(name);
            else attributes.put(name, value);
            updateHeader();
        });
    }

    @Override
    public void cancel() {
        persistIsolated(() -> {
            if (STATUS_COMPLETE.equals(statusId) || STATUS_FAILED.equals(statusId)
                    || STATUS_CANCELLED.equals(statusId)) return;
            statusId = STATUS_CANCELLED;
            pendingToolCalls.clear();
            updateHeader();
        });
        abortInFlight();
    }

    private void abortInFlight() {
        if (ec == null || conversationId == null) return;
        try {
            if (ec.getLlm() instanceof LlmFacadeImpl)
                ((LlmFacadeImpl) ec.getLlm()).abortInFlight(conversationId);
        } catch (Throwable t) {
            logger.warn("Error aborting in-flight LLM stream for conversation " + conversationId, t);
        }
    }
    private void clearCancelledFlag() {
        if (ec == null || conversationId == null) return;
        try {
            if (ec.getLlm() instanceof LlmFacadeImpl)
                ((LlmFacadeImpl) ec.getLlm()).clearCancelled(conversationId);
        } catch (Throwable ignored) { }
    }

    @Override
    public void persist() {
        persistIsolated(() -> {
            updateHeader();
            for (LlmMessage m : messages) writeMessage(m, m.messageId == null);
        });
    }

    /**
     * FOR UPDATE: refuse to leave Cancelled so /cancel wins over persistSuccess/yield/complete.
     */
    void setStatusInternal(String status) {
        if (hasEntity(ec) && conversationId != null) {
            EntityValue ev = ec.getEntity().find("moqui.llm.LlmConversation")
                    .condition("conversationId", conversationId).forUpdate(true).useCache(false).one();
            String dbStatus = ev != null ? ev.getString("statusId") : statusId;
            if (STATUS_CANCELLED.equals(dbStatus) && !STATUS_CANCELLED.equals(status)) {
                statusId = STATUS_CANCELLED;
                return;
            }
            statusId = status;
            if (ev != null) writeHeader(ev, false);
            else updateHeader();
        } else {
            if (STATUS_CANCELLED.equals(statusId) && !STATUS_CANCELLED.equals(status)) return;
            this.statusId = status;
            updateHeader();
        }
    }

    /**
     * Single-flight inside the isolated TX: re-read (FOR UPDATE). Yielded is 409 on a new turn;
     * resume (Yielded to Streaming) is allowed when {@code resumeFromYielded} is true.
     * Streaming is 409 only while this JVM still has the turn claimed. A Streaming row with no
     * claim is a turn that already ended, and this call continues it.
     */
    void beginTurnStreaming() { beginTurnStreaming(false); }
    void beginTurnStreaming(boolean resumeFromYielded) {
        clearCancelledFlag();
        if (hasEntity(ec) && conversationId != null) {
            EntityValue ev = ec.getEntity().find("moqui.llm.LlmConversation")
                    .condition("conversationId", conversationId).forUpdate(true).useCache(false).one();
            if (ev == null) throw new LlmException("Conversation not found: " + conversationId);
            String dbStatus = ev.getString("statusId");
            if (STATUS_YIELDED.equals(dbStatus) && !resumeFromYielded) {
                throw new LlmException("Conversation is " + statusText(dbStatus) + " (single-flight)",
                        null, LlmFinishReason.ERROR, 409, profileName, conversationId);
            }
            // Streaming with a live claim is the real single-flight. A Streaming row and no claim
            // means the turn that set it already left this JVM (request ended, or the process restarted).
            if (STATUS_STREAMING.equals(dbStatus) && turnClaimed()) {
                throw new LlmException("Conversation is " + statusText(dbStatus) + " (single-flight)",
                        null, LlmFinishReason.ERROR, 409, profileName, conversationId);
            }
            // Another node's live lease is the same single-flight, across the cluster
            if (STATUS_STREAMING.equals(dbStatus) && otherNodeHoldsLease(ec, ev)) {
                throw new LlmException("Conversation is " + statusText(dbStatus) + " on another node (single-flight)",
                        null, LlmFinishReason.ERROR, 409, profileName, conversationId);
            }
            if (STATUS_STREAMING.equals(dbStatus))
                logger.warn("Conversation " + conversationId
                        + " was LlmcsStreaming with no live turn or lease; continuing");
            claimTurn();
            statusId = STATUS_STREAMING;
            // The one write that is allowed to leave Cancelled (a new turn after a cancel) and that takes the lease
            writeHeader(ev, false, true);
        } else {
            if (STATUS_STREAMING.equals(statusId)
                    || (STATUS_YIELDED.equals(statusId) && !resumeFromYielded)) {
                throw new LlmException("Conversation is " + statusText(statusId) + " (single-flight)",
                        null, LlmFinishReason.ERROR, 409, profileName, conversationId);
            }
            statusId = STATUS_STREAMING;
        }
    }

    /** Package-visible for the gateway's early 409, before it rewrites context messages. */
    static boolean turnInFlight(ExecutionContext ec, String conversationId) {
        if (ec == null || conversationId == null || !(ec.getLlm() instanceof LlmFacadeImpl)) return false;
        if (((LlmFacadeImpl) ec.getLlm()).turnClaimed(conversationId)) return true;
        if (!hasEntity(ec)) return false;
        EntityValue ev = ec.getEntity().find("moqui.llm.LlmConversation")
                .condition("conversationId", conversationId).useCache(false).one();
        return ev != null && STATUS_STREAMING.equals(ev.getString("statusId")) && otherNodeHoldsLease(ec, ev);
    }

    /** True when a different node owns this row's turn and its lease has not run out. */
    static boolean otherNodeHoldsLease(ExecutionContext ec, EntityValue ev) {
        if (ec == null || ev == null || !(ec.getLlm() instanceof LlmFacadeImpl)) return false;
        String owner = ev.getString("turnOwner");
        Timestamp thru = ev.getTimestamp("turnLeaseThru");
        if (owner == null || owner.isEmpty() || thru == null) return false;
        if (owner.equals(((LlmFacadeImpl) ec.getLlm()).getNodeId())) return false;
        return thru.getTime() > System.currentTimeMillis();
    }
    private boolean turnClaimed() {
        // No facade means we cannot tell a live turn from a leftover row, so keep the strict 409.
        if (ec == null || !(ec.getLlm() instanceof LlmFacadeImpl)) return true;
        return ((LlmFacadeImpl) ec.getLlm()).turnClaimed(conversationId);
    }
    private void claimTurn() {
        if (ec == null || !(ec.getLlm() instanceof LlmFacadeImpl)) return;
        long token = ((LlmFacadeImpl) ec.getLlm()).claimTurn(conversationId);
        if (token > 0L) turnToken = token;
    }
    void releaseTurnClaim() {
        long token = turnToken;
        turnToken = 0L;
        if (token <= 0L || ec == null || !(ec.getLlm() instanceof LlmFacadeImpl)) return;
        ((LlmFacadeImpl) ec.getLlm()).releaseTurn(conversationId, token);
    }

    void setPendingToolCallsInternal(List<LlmToolCall> calls) {
        pendingToolCalls.clear();
        if (calls != null) pendingToolCalls.addAll(calls);
        updateHeader();
    }

    /**
     * True when the stored row says Cancelled. A cancel handled by another node only reaches this node
     * through the database, because the in-memory cancel set is per JVM.
     */
    boolean isCancelledInStore() {
        if (!hasEntity(ec) || conversationId == null) return false;
        boolean[] cancelled = new boolean[1];
        persistIsolated(() -> {
            EntityValue ev = ec.getEntity().find("moqui.llm.LlmConversation")
                    .condition("conversationId", conversationId).useCache(false).one();
            cancelled[0] = ev != null && STATUS_CANCELLED.equals(ev.getString("statusId"));
        });
        return cancelled[0];
    }

    /** Re-read DB (source of truth) and only then Failed/Cancelled if still Streaming. */
    void repairTerminalIfStreaming(boolean cancelled) {
        persistIsolated(() -> {
            String dbStatus = statusId;
            EntityValue ev = null;
            if (hasEntity(ec) && conversationId != null) {
                ev = ec.getEntity().find("moqui.llm.LlmConversation")
                        .condition("conversationId", conversationId).forUpdate(true).useCache(false).one();
                if (ev != null) dbStatus = ev.getString("statusId");
            }
            if (STATUS_STREAMING.equals(dbStatus)) {
                statusId = cancelled ? STATUS_CANCELLED : STATUS_FAILED;
                pendingToolCalls.clear();
                if (ev != null) writeHeader(ev, false);
                else updateHeader();
            } else if (ev != null && dbStatus != null) {
                statusId = dbStatus;
            }
        });
    }

    void replaceSystemInternal(String content) {
        LlmMessage existing = findSystem();
        if (existing != null) {
            existing.content = content;
            existing.sentDate = now(ec);
            systemText = content;
            lastMessageDate = existing.sentDate;
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
                lastMessageDate = existing.sentDate;
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
        if (message.role == LlmMessage.Role.USER) ConversationSummary.noteFirstUser(this, message.content);
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
        java.sql.Timestamp callStart = now(ec);
        ev.set("startDate", callStart);
        ev.set("startDay", callStart != null ? new java.sql.Date(callStart.getTime()) : null);
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
        if (hasEntity(ec) && conversationId != null) {
            EntityList el = ec.getEntity().find("moqui.llm.LlmMessage")
                    .condition("conversationId", conversationId)
                    .orderBy("-ordinal").limit(1).useCache(false).list();
            if (el != null && el.size() > 0) {
                Long o = el.getFirst().getLong("ordinal");
                if (o != null && o.intValue() + 1 > next) next = o.intValue() + 1;
            }
        }
        return next;
    }

    private Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.conversationId = conversationId;
        s.profileName = profileName;
        s.userId = userId;
        s.visitId = visitId;
        s.statusId = statusId;
        s.title = title;
        s.createdDate = createdDate;
        s.purpose = purpose;
        s.summary = summary;
        s.searchText = searchText;
        s.hasCanvas = hasCanvas;
        s.summaryFromLlm = summaryFromLlm;
        s.canvasJson = canvasJson;
        s.systemText = systemText;
        s.windowPolicy = windowPolicy != null ? windowPolicy.copy() : new WindowPolicy();
        s.pendingToolCalls.addAll(pendingToolCalls);
        s.attributes.putAll(attributes);
        s.lastMessageDate = lastMessageDate;
        for (LlmMessage m : messages) s.messages.add(m != null ? m.copy() : null);
        return s;
    }

    private void restore(Snapshot s) {
        conversationId = s.conversationId;
        profileName = s.profileName;
        userId = s.userId;
        visitId = s.visitId;
        statusId = s.statusId;
        title = s.title;
        createdDate = s.createdDate;
        purpose = s.purpose;
        summary = s.summary;
        searchText = s.searchText;
        hasCanvas = s.hasCanvas;
        summaryFromLlm = s.summaryFromLlm;
        canvasJson = s.canvasJson;
        systemText = s.systemText;
        windowPolicy = s.windowPolicy != null ? s.windowPolicy : new WindowPolicy();
        pendingToolCalls.clear();
        pendingToolCalls.addAll(s.pendingToolCalls);
        attributes.clear();
        attributes.putAll(s.attributes);
        lastMessageDate = s.lastMessageDate;
        messages.clear();
        messages.addAll(s.messages);
    }

    private static final class Snapshot {
        String conversationId, profileName, userId, visitId, statusId, title, systemText;
        String purpose, summary, searchText, hasCanvas, summaryFromLlm, canvasJson;
        Timestamp createdDate;
        WindowPolicy windowPolicy;
        final List<LlmToolCall> pendingToolCalls = new ArrayList<>();
        final Map<String, Object> attributes = new LinkedHashMap<>();
        Timestamp lastMessageDate;
        final List<LlmMessage> messages = new ArrayList<>();
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
        createdDate = header.getTimestamp("createdDate");
        purpose = header.getString("purpose");
        summary = header.getString("summary");
        searchText = header.getString("searchText");
        hasCanvas = header.getString("hasCanvas");
        summaryFromLlm = header.getString("summaryFromLlm");
        canvasJson = header.getString("canvasJson");
        systemText = header.getString("systemText");
        windowPolicy = WindowPolicy.fromMap(LlmJson.toMap(header.getString("windowPolicyJson")));
        pendingToolCalls.clear();
        pendingToolCalls.addAll(ConversationStore.parseToolCalls(header.getString("pendingToolCallsJson")));
        attributes.clear();
        Map<String, Object> attrs = LlmJson.toMap(header.getString("attributesJson"));
        if (attrs != null) attributes.putAll(attrs);
        boolean migratedCanvas = false;
        if ((canvasJson == null || canvasJson.isBlank()) && attrs != null
                && attrs.get(WriteUiTool.ATTR_LAST_WRITE_UI) instanceof Map) {
            canvasJson = LlmJson.toJson(attrs.get(WriteUiTool.ATTR_LAST_WRITE_UI));
            hasCanvas = "Y";
            attributes.remove(WriteUiTool.ATTR_LAST_WRITE_UI);
            migratedCanvas = true;
        }
        lastMessageDate = header.getTimestamp("lastMessageDate");
        messages.clear();
        EntityList list = ec.getEntity().find("moqui.llm.LlmMessage")
                .condition("conversationId", conversationId).orderBy("ordinal").list();
        int size = list.size();
        for (int i = 0; i < size; i++) messages.add(ConversationStore.fromEntity(list.get(i)));
        if (migratedCanvas) updateHeader();
    }

    private void writeHeader(EntityValue ev, boolean create) { writeHeader(ev, create, false); }

    /**
     * @param takeTurn true only from beginTurnStreaming. It may move a Cancelled row back to Streaming and records this
     * node as the turn owner with a fresh lease. Every other write leaves a Cancelled row Cancelled, because /cancel can
     * arrive while a turn holds an older in-memory copy that still says Streaming.
     */
    private void writeHeader(EntityValue ev, boolean create, boolean takeTurn) {
        if (!create && !takeTurn && STATUS_CANCELLED.equals(ev.getString("statusId")) && !STATUS_CANCELLED.equals(statusId))
            statusId = STATUS_CANCELLED;
        ev.set("profileName", profileName);
        ev.set("userId", userId);
        ev.set("visitId", visitId);
        ev.set("statusId", statusId != null ? statusId : STATUS_ACTIVE);
        ev.set("title", title);
        ev.set("createdDate", createdDate);
        ev.set("purpose", purpose);
        ev.set("summary", summary);
        ev.set("searchText", searchText);
        ev.set("hasCanvas", hasCanvas);
        ev.set("summaryFromLlm", summaryFromLlm);
        ev.set("canvasJson", canvasJson);
        ev.set("systemText", systemText);
        ev.set("windowPolicyJson", LlmJson.toJson(windowPolicy.toMap()));
        ev.set("pendingToolCallsJson", pendingToolCalls.isEmpty() ? null : LlmJson.toJson(pendingToolCalls));
        ev.set("attributesJson", attributes.isEmpty() ? null : LlmJson.toJson(attributes));
        ev.set("lastMessageDate", lastMessageDate);
        ev.set("messageCount", visibleMessageCount(messages));
        if (takeTurn && ec != null && ec.getLlm() instanceof LlmFacadeImpl) {
            ev.set("turnOwner", ((LlmFacadeImpl) ec.getLlm()).getNodeId());
            ev.set("turnLeaseThru", new Timestamp(System.currentTimeMillis() + LlmFacadeImpl.LEASE_SECONDS * 1000L));
        } else if (!STATUS_STREAMING.equals(statusId)) {
            ev.set("turnOwner", null);
            ev.set("turnLeaseThru", null);
        }
        if (create) ev.create();
        else ev.update();
    }

    /** Rows the chat shows. System and context stay in the transcript but are not counted in the list. */
    private static int visibleMessageCount(List<LlmMessage> messages) {
        int count = 0;
        if (messages == null) return 0;
        for (LlmMessage message : messages) {
            if (message == null || message.role == null) continue;
            if (message.role == LlmMessage.Role.SYSTEM || message.role == LlmMessage.Role.CONTEXT) continue;
            count++;
        }
        return count;
    }

    void updateHeader() {
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
            ConversationStore.fillMessage(ec, ev, m);
            ev.create();
        } else {
            ev = ec.getEntity().find("moqui.llm.LlmMessage").condition("messageId", m.messageId).one();
            if (ev == null) {
                ev = ec.getEntity().makeValue("moqui.llm.LlmMessage");
                ev.set("messageId", m.messageId);
                ev.set("conversationId", conversationId);
                ConversationStore.fillMessage(ec, ev, m);
                ev.create();
            } else {
                ConversationStore.fillMessage(ec, ev, m);
                ev.update();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean sourceEquals(LlmMessage m, String source) {
        if (m.metadata == null) return source == null;
        Object s = m.metadata.get("source");
        if (source == null) return s == null;
        return source.equals(s);
    }
    private static boolean hasEntity(ExecutionContext ec) {
        return ec != null;
    }
    static Timestamp now(ExecutionContext ec) {
        if (ec != null && ec.getUser() != null) return ec.getUser().getNowTimestamp();
        return new Timestamp(System.currentTimeMillis());
    }
    private static String newInMemoryId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
