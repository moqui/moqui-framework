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
import org.moqui.context.ArtifactTarpitException;
import org.moqui.context.TransactionFacade;
import org.moqui.entity.EntityValue;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmProtocol.ProtocolRequest;
import org.moqui.llm.LlmProtocol.ProtocolResult;
import org.moqui.llm.LlmProtocol.ProtocolStreamListener;
import org.moqui.llm.LlmResponse;
import org.moqui.llm.LlmStreamListener;
import org.moqui.llm.LlmTool;
import org.moqui.llm.LlmToolCall;
import org.moqui.llm.LlmToolResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.transaction.Status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sequential tool loop. Server tools run first in model order; remaining CLIENT tools yield.
 * Invalid JSON and unknown names become tool error results, not exceptions.
 */
final class LlmAgentLoop {
    private static final Logger logger = LoggerFactory.getLogger(LlmAgentLoop.class);
    static final String CLIENT_UNAVAILABLE = "client tool not available in this context";
    static final String UNKNOWN_TOOL = "unknown tool: ";
    static final String MALFORMED = "malformed arguments: ";
    private static final ThreadLocal<LlmClientImpl> CURRENT_CLIENT = new ThreadLocal<>();

    private final LlmClientImpl client;
    private LlmStreamListener listener;
    private List<ResumeEmit> resumeEmits;

    static LlmClientImpl currentClient() { return CURRENT_CLIENT.get(); }

    LlmAgentLoop(LlmClientImpl client) { this.client = client; }

    LlmResponse run(long start) { return run(start, null); }

    LlmResponse run(long start, LlmStreamListener listener) {
        this.listener = listener;
        int maxIter = client.maxIterationsEffective();
        boolean resume = client.hasResumeResults() || client.resumeFromYielded;
        List<LlmMessage> working = null;

        if (client.conversation != null) {
            client.conversation.persistIsolated(() -> {
                client.conversation.beginTurnStreaming(resume);
                if (client.systemContent != null) client.conversation.replaceSystemInternal(client.systemContent);
                applyResumeResults(client.conversation);
                for (LlmMessage u : client.userMessages) client.conversation.appendInternal(u.copy());
            });
            client.markStreamingPersisted();
            if (listener != null) listener.onConversation(client.conversation.getConversationId());
            flushResumeEmits();
        } else {
            working = client.buildWindow();
            applyResumeResults(working);
            flushResumeEmits();
        }

        List<LlmToolResult> roundResults = new ArrayList<>();
        int emptyAttempts = 0;
        int errorAttempts = 0;
        float waitSeconds = client.profile.retryInitialSeconds > 0 ? client.profile.retryInitialSeconds : 0;
        int iteration = 0;

        while (iteration < maxIter) {
            client.throwIfCancelled();
            List<LlmMessage> window = client.conversation != null ? client.buildWindow() : working;
            ProtocolRequest req = client.buildRequest(client.resolveModel(), window);
            req.tools = client.tools;

            ProtocolResult result;
            try {
                result = invokeProtocol(req);
                client.throwIfCancelled();
            } catch (ArtifactAuthorizationException | ArtifactTarpitException e) {
                throw e;
            } catch (LlmException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new LlmException("LLM protocol call failed: " + e.getMessage(), e,
                        LlmFinishReason.ERROR, 0, client.profile.name, client.convId());
            }
            if (result == null) {
                throw new LlmException("LLM protocol returned no result",
                        null, LlmFinishReason.ERROR, 0, client.profile.name, client.convId());
            }
            LlmFinishReason fr = result.finishReason != null ? result.finishReason : LlmFinishReason.ERROR;

            if (fr == LlmFinishReason.CONTENT_FILTER) {
                throw new LlmException(nvl(result.errorMessage, "LLM content filter"),
                        null, LlmFinishReason.CONTENT_FILTER, result.httpStatus, client.profile.name, client.convId());
            }
            if (fr == LlmFinishReason.CONTEXT_OVERFLOW) {
                throw new LlmException(nvl(result.errorMessage, "LLM context length exceeded"),
                        null, LlmFinishReason.CONTEXT_OVERFLOW, result.httpStatus, client.profile.name, client.convId());
            }
            if (fr == LlmFinishReason.EMPTY) {
                if (emptyAttempts < client.profile.emptyRetries) {
                    emptyAttempts++;
                    client.sleepBackoff(waitSeconds);
                    waitSeconds = client.nextWait(waitSeconds);
                    continue;
                }
                throw new LlmException("LLM returned empty content after " + client.profile.emptyRetries + " retries",
                        null, LlmFinishReason.EMPTY, result.httpStatus, client.profile.name, client.convId());
            }
            if (fr != LlmFinishReason.STOP && fr != LlmFinishReason.LENGTH && fr != LlmFinishReason.TOOL_CALLS) {
                boolean retryable = result.retryable;
                if (retryable && errorAttempts < client.profile.retryMax) {
                    errorAttempts++;
                    client.sleepBackoff(waitSeconds);
                    waitSeconds = client.nextWait(waitSeconds);
                    continue;
                }
                throw new LlmException(nvl(result.errorMessage, "LLM call failed"),
                        null, LlmFinishReason.ERROR, result.httpStatus, client.profile.name, client.convId());
            }

            iteration++;
            emptyAttempts = 0;
            List<LlmToolCall> calls = result.getToolCalls();
            boolean hasCalls = calls != null && !calls.isEmpty();

            LlmMessage asst = LlmMessage.assistant(result.content);
            asst.toolCalls = hasCalls ? new ArrayList<>(calls) : null;
            if (client.conversation != null) {
                final ProtocolResult logResult = result;
                final int logIter = iteration;
                client.conversation.persistIsolated(() -> {
                    client.conversation.appendInternal(asst);
                    client.conversation.writeCallLog(client.profile.name,
                            client.profile.protocol != null ? client.profile.protocol.getName() : null,
                            result.model != null ? result.model : client.resolveModel(), client.profile.logContent,
                            window, logResult, System.currentTimeMillis() - start, logIter, false);
                });
            } else if (working != null) {
                working.add(asst);
            }

            if (!hasCalls || fr == LlmFinishReason.STOP || fr == LlmFinishReason.LENGTH) {
                completeConversation();
                client.throwIfCancelled();
                LlmResponse r = client.toResponse(result, fr, start);
                r.toolResults = roundResults;
                r.yielded = false;
                if (listener != null) listener.onComplete(r);
                return r;
            }

            List<LlmToolCall> serverCalls = new ArrayList<>();
            List<LlmToolCall> clientCalls = new ArrayList<>();
            for (LlmToolCall call : calls) {
                if (call == null) continue;
                // Gated client tools (write_ui) must not yield; treat as a server-style refusal result.
                if (!SkillUseGate.allowed(client, call.name)) {
                    serverCalls.add(call);
                    continue;
                }
                LlmTool tool = client.findTool(call.name);
                if (tool != null && tool.getExecution() == LlmTool.Execution.CLIENT) clientCalls.add(call);
                else serverCalls.add(call);
            }

            if (!serverCalls.isEmpty() && listener != null) listener.onPing();
            for (int serverIndex = 0; serverIndex < serverCalls.size(); serverIndex++) {
                LlmToolCall call = serverCalls.get(serverIndex);
                client.throwIfCancelled();
                SkillRiskGate.Decision decision = SkillRiskGate.decide(client, call);
                if (decision.action == SkillRiskGate.Decision.YIELD) {
                    for (int rest = serverIndex + 1; rest < serverCalls.size(); rest++)
                        recordServerResult(working, roundResults, serverCalls.get(rest), SkillRiskGate.deferred(serverCalls.get(rest)));
                    for (LlmToolCall later : clientCalls)
                        recordServerResult(working, roundResults, later, SkillRiskGate.deferred(later));
                    LlmToolCall pending = call.copy();
                    pending.confirm = Boolean.TRUE;
                    pending.risk = decision.risk;
                    pending.execution = LlmTool.Execution.SERVER;
                    LlmTrace.logToolCall(pending.name, pending.arguments);
                    if (listener != null) listener.onToolCall(pending, LlmTool.Execution.SERVER);
                    return yieldPending(result, start, roundResults, pending);
                }
                LlmTrace.logToolCall(call.name, call.arguments);
                if (listener != null) listener.onToolCall(call, LlmTool.Execution.SERVER);
                Object executed = decision.action == SkillRiskGate.Decision.REFUSE
                        ? decision.refusal : executeOne(call, false);
                recordServerResult(working, roundResults, call, executed);
            }

            if (!clientCalls.isEmpty()) {
                if (!client.allowClientTools) {
                    for (LlmToolCall call : clientCalls) {
                        Map<String, Object> err = errorMap(CLIENT_UNAVAILABLE);
                        LlmTrace.logToolCall(call.name, call.arguments);
                        LlmTrace.logToolResult(call.name, err);
                        if (listener != null) {
                            listener.onToolCall(call, LlmTool.Execution.CLIENT);
                            listener.onToolResult(call, err, LlmTool.Execution.CLIENT);
                        }
                        roundResults.add(new LlmToolResult(call.id, call.name, err));
                        appendTool(working, call.id, call.name, err);
                    }
                    continue;
                }
                List<LlmToolCall> pending = new ArrayList<>();
                for (LlmToolCall call : clientCalls) {
                    LlmToolCall copy = call.copy();
                    copy.execution = LlmTool.Execution.CLIENT;
                    LlmTool tool = client.findTool(call.name);
                    Map<String, Object> args = LlmJson.tryToMap(call.arguments);
                    if (args == null) {
                        Map<String, Object> err = errorMap(MALFORMED + call.arguments);
                        LlmTrace.logToolCall(call.name, call.arguments);
                        LlmTrace.logToolResult(call.name, err);
                        if (listener != null) {
                            listener.onToolCall(copy, LlmTool.Execution.CLIENT);
                            listener.onToolResult(copy, err, LlmTool.Execution.CLIENT);
                        }
                        roundResults.add(new LlmToolResult(call.id, call.name, err));
                        appendTool(working, call.id, call.name, err);
                        continue;
                    }
                    if (tool != null) {
                        Map<String, Object> enriched = tool.enrichForClient(args, client.ec);
                        if (enriched != null && WriteUiTool.NAME.equals(call.name))
                            enriched = WriteUiTool.applyWriteThrough(enriched, client.conversation);
                        if (enriched != null && WriteUiTool.NAME.equals(call.name)) {
                            List<String> unbound = WriteUiTool.unboundActionFields(enriched);
                            if (!unbound.isEmpty()) {
                                Map<String, Object> err = new LinkedHashMap<>();
                                err.put("error", "unbound_fields");
                                err.put("instruction", "These bodyFromFields names are not fields, so the click does not send them: "
                                        + unbound + ". Add each as a field with defaultValue. A display field is not a parameter.");
                                err.put("fields", unbound);
                                LlmTrace.logToolCall(call.name, call.arguments);
                                LlmTrace.logToolResult(call.name, err);
                                if (listener != null) {
                                    listener.onToolCall(copy, LlmTool.Execution.CLIENT);
                                    listener.onToolResult(copy, err, LlmTool.Execution.CLIENT);
                                }
                                roundResults.add(new LlmToolResult(call.id, call.name, err));
                                appendTool(working, call.id, call.name, err);
                                continue;
                            }
                        }
                        if (enriched != null) copy.arguments = LlmJson.toJson(enriched);
                    }
                    pending.add(copy);
                }
                if (pending.isEmpty()) continue;
                for (LlmToolCall copy : pending) {
                    LlmTrace.logToolCall(copy.name, copy.arguments);
                    if (listener != null) listener.onToolCall(copy, LlmTool.Execution.CLIENT);
                }
                return yieldPending(result, start, roundResults, pending);
            }
        }

        if (client.conversation != null) {
            client.conversation.persistIsolated(() -> {
                client.conversation.setPendingToolCallsInternal(null);
                client.conversation.setStatusInternal(LlmConversationImpl.STATUS_ACTIVE);
            });
        }
        throw new LlmException("LLM agent loop exceeded maxIterations=" + maxIter,
                null, LlmFinishReason.MAX_ITERATIONS, 0, client.profile.name, client.convId());
    }

    private ProtocolResult invokeProtocol(ProtocolRequest req) {
        if (listener != null) req.stream = true;
        LlmTrace.logRequest(client, req);
        long t0 = System.currentTimeMillis();
        ProtocolResult result = null;
        try {
            if (listener == null) {
                result = client.profile.protocol.chat(req);
                return result;
            }
            ProtocolResult[] box = new ProtocolResult[1];
            Throwable[] fail = new Throwable[1];
            req.onStreamOpen = client::registerInFlight;
            try {
                client.profile.protocol.chatStream(req, new ProtocolStreamListener() {
                    @Override public void onDelta(String textDelta) {
                        if (textDelta != null && !textDelta.isEmpty()) listener.onDelta(textDelta);
                    }
                    @Override public void onToolCallDelta(String name, String argumentsSoFar) {
                        listener.onToolCallDelta(name, argumentsSoFar);
                    }
                    @Override public void onComplete(ProtocolResult r) { box[0] = r; }
                    @Override public void onFailure(Throwable t) { fail[0] = t; }
                });
            } finally {
                client.unregisterInFlight();
            }
            if (fail[0] != null) {
                throw new LlmException("LLM stream failed: " + fail[0].getMessage(), fail[0],
                        LlmFinishReason.ERROR, 0, client.profile.name, client.convId());
            }
            result = box[0];
            return result;
        } finally {
            LlmTrace.logResponse(client, result, System.currentTimeMillis() - t0);
        }
    }

    private void completeConversation() {
        if (client.conversation == null) return;
        client.conversation.persistIsolated(() -> {
            client.conversation.setPendingToolCallsInternal(null);
            client.conversation.setStatusInternal(LlmConversationImpl.STATUS_COMPLETE);
        });
    }

    private void appendTool(List<LlmMessage> working, String toolCallId, String name, Object content) {
        String text = content instanceof String ? (String) content : LlmJson.toJson(content);
        if (client.conversation != null) {
            client.conversation.appendToolResult(toolCallId, name, content);
        } else if (working != null) {
            working.add(LlmMessage.tool(toolCallId, name, text));
        }
    }

    private void recordServerResult(List<LlmMessage> working, List<LlmToolResult> roundResults,
            LlmToolCall call, Object executed) {
        client.throwIfCancelled();
        Object stored = client.truncateResult(executed);
        roundResults.add(new LlmToolResult(call.id, call.name, stored));
        appendTool(working, call.id, call.name, stored);
        noteSkillLifecycle(call.name, call.arguments, stored);
        LlmTrace.logToolResult(call.name, stored);
        if (listener != null) listener.onToolResult(call, stored, LlmTool.Execution.SERVER);
    }

    private LlmResponse yieldPending(ProtocolResult result, long start, List<LlmToolResult> roundResults,
            Object pendingCalls) {
        List<LlmToolCall> pending = new ArrayList<>();
        if (pendingCalls instanceof LlmToolCall) pending.add((LlmToolCall) pendingCalls);
        else if (pendingCalls instanceof List) {
            for (Object item : (List<?>) pendingCalls) {
                if (item instanceof LlmToolCall) pending.add((LlmToolCall) item);
            }
        }
        if (client.conversation != null) {
            client.conversation.persistIsolated(() -> {
                client.conversation.setPendingToolCallsInternal(pending);
                client.conversation.setStatusInternal(LlmConversationImpl.STATUS_YIELDED);
            });
            client.throwIfCancelled();
        }
        LlmResponse r = client.toResponse(result, LlmFinishReason.TOOL_CALLS, start);
        r.yielded = true;
        r.httpStatus = 202;
        r.pendingToolCalls = pending;
        r.toolResults = roundResults;
        if (listener != null) {
            listener.onYield(pending);
            listener.onComplete(r);
        }
        return r;
    }

    Object executeOne(LlmToolCall call, boolean confirmed) {
        if (!SkillUseGate.allowed(client, call.name)) return SkillUseGate.refusal();
        if (!confirmed) {
            SkillRiskGate.Decision decision = SkillRiskGate.decide(client, call);
            if (decision.action == SkillRiskGate.Decision.REFUSE) return decision.refusal;
            if (decision.action == SkillRiskGate.Decision.YIELD)
                return SkillRiskGate.notConfirmed();
        }
        LlmTool tool = client.findTool(call.name);
        if (tool == null) return errorMap(UNKNOWN_TOOL + call.name);
        Map<String, Object> args = LlmJson.tryToMap(call.arguments);
        if (args == null) return errorMap(MALFORMED + call.arguments);
        LlmClientImpl prev = CURRENT_CLIENT.get();
        CURRENT_CLIENT.set(client);
        TransactionFacade tf = client.ec != null ? client.ec.getTransaction() : null;
        boolean suspended = false;
        boolean failed = false;
        try {
            // The conversation transaction must not join the tool. A service that did not begin
            // the current transaction only setRollbackOnly, which then blocks saving the tool result.
            if (tf != null && tf.isTransactionInPlace()) suspended = tf.suspend();
            try {
                return tool.execute(args, client.ec);
            } catch (Throwable t) {
                failed = true;
                return errorMap(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            } finally {
                closeToolTransaction(tf, failed);
            }
        } catch (Throwable t) {
            return errorMap(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        } finally {
            if (suspended && tf != null) {
                try { tf.resume(); }
                catch (Throwable t) { logger.error("Error resuming transaction after tool", t); }
            }
            if (prev != null) CURRENT_CLIENT.set(prev);
            else CURRENT_CLIENT.remove();
        }
    }

    /** Close a transaction the tool left open. A failure rolls it back. A success commits it. */
    static void closeToolTransaction(TransactionFacade tf, boolean failed) {
        if (tf == null) return;
        try {
            if (!tf.isTransactionInPlace()) return;
            int status = tf.getStatus();
            if (status == Status.STATUS_MARKED_ROLLBACK || failed) {
                tf.rollback("tool transaction", null);
            } else if (status == Status.STATUS_ACTIVE) {
                tf.commit();
            }
        } catch (Throwable t) {
            logger.error("Error closing tool transaction", t);
            try {
                if (tf.isTransactionInPlace()) tf.rollback("tool transaction close failed", t);
            } catch (Throwable ignored) { }
        }
    }

    private void applyResumeResults(LlmConversationImpl conv) {
        if (!client.hasResumeResults() && !client.resumeFromYielded) {
            conv.setPendingToolCallsInternal(null);
            return;
        }
        List<LlmToolCall> pending = new ArrayList<>(conv.getPendingClientToolCalls());
        List<LlmToolResult> results = client.resumeToolResults;
        java.util.Set<String> pendingIds = new java.util.LinkedHashSet<>();
        for (LlmToolCall pendingCall : pending) {
            if (pendingCall != null && pendingCall.id != null) pendingIds.add(pendingCall.id);
        }
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (LlmToolResult tr : results) {
            if (tr == null) continue;
            String id = tr.toolCallId;
            if (id == null || !pendingIds.contains(id)) continue;
            LlmToolCall pendingCall = pendingCallById(pending, id);
            Object content = resumeContent(pendingCall, tr.content);
            conv.appendInternal(LlmMessage.tool(id, tr.name, contentText(content)));
            seen.add(id);
            noteResumeEmit(id, tr.name, content);
        }
        for (LlmToolCall pendingCall : pending) {
            if (pendingCall == null || pendingCall.id == null || seen.contains(pendingCall.id)) continue;
            Object missing = Boolean.TRUE.equals(pendingCall.confirm)
                    ? SkillRiskGate.notConfirmed() : errorMap("client tool result missing");
            conv.appendInternal(LlmMessage.tool(pendingCall.id, pendingCall.name, contentText(missing)));
            noteResumeEmit(pendingCall.id, pendingCall.name, missing);
        }
        conv.setPendingToolCallsInternal(null);
    }

    private void applyResumeResults(List<LlmMessage> working) {
        if (!client.hasResumeResults() || working == null) return;
        for (LlmToolResult tr : client.resumeToolResults) {
            if (tr == null) continue;
            Object content = resumeContent(null, tr.content);
            working.add(LlmMessage.tool(tr.toolCallId, tr.name, contentText(content)));
            noteResumeEmit(tr.toolCallId, tr.name, content);
        }
    }

    private Object resumeContent(LlmToolCall pendingCall, Object content) {
        if (pendingCall == null || !Boolean.TRUE.equals(pendingCall.confirm)) return content;
        if (!SkillRiskGate.confirmed(content)) return SkillRiskGate.notConfirmed();
        Object executed = executeOne(pendingCall, true);
        noteSkillLifecycle(pendingCall.name, pendingCall.arguments, executed);
        return client.truncateResult(executed);
    }

    private static LlmToolCall pendingCallById(List<LlmToolCall> pending, String id) {
        for (LlmToolCall call : pending) {
            if (call != null && id.equals(call.id)) return call;
        }
        return null;
    }

    private void noteResumeEmit(String id, String name, Object content) {
        LlmToolCall call = new LlmToolCall(id, name, null);
        call.execution = LlmTool.Execution.CLIENT;
        if (resumeEmits == null) resumeEmits = new ArrayList<>();
        resumeEmits.add(new ResumeEmit(call, content));
    }

    private void flushResumeEmits() {
        if (resumeEmits == null) return;
        for (ResumeEmit e : resumeEmits) {
            if (e == null || e.call == null) continue;
            LlmTrace.logToolResult(e.call.name, e.content);
            if (listener != null) listener.onToolResult(e.call, e.content, LlmTool.Execution.CLIENT);
        }
        resumeEmits = null;
    }

    private static final class ResumeEmit {
        final LlmToolCall call;
        final Object content;
        ResumeEmit(LlmToolCall call, Object content) {
            this.call = call;
            this.content = content;
        }
    }

    private void noteSkillLifecycle(String toolName, String argumentsJson, Object stored) {
        if (!(stored instanceof Map)) return;
        Map<?, ?> m = (Map<?, ?>) stored;
        if ("enter_sim".equals(toolName)) {
            Object n = m.get("proposedSkillName");
            if (n != null && !n.toString().isBlank()) rememberProposed(n.toString());
        }
        if ("write_ui".equals(toolName)) {
            if (isWorldWriteSuccess(toolName, argumentsJson, m)) maybeAdmit();
            return;
        }
        if (!executedWrite(toolName, argumentsJson, m)) return;
        boolean sim = client.ec instanceof ExecutionContextImpl && ((ExecutionContextImpl) client.ec).simSession;
        boolean callOk = callSucceeded(toolName, m);
        String skill = client.activeSkillName;
        if (sim) {
            if (!callOk && skill != null && !skill.isBlank())
                SkillIndex.recordOutcomeInTx(client.ec, skill, "sim", "fail",
                        failureNotes(toolName, m), client.convId());
            return;
        }
        if (callOk && !ToolResultTrim.hasErrorMessages(m)) {
            if (skill != null && !skill.isBlank() && !skill.equals(proposedName()))
                SkillIndex.recordOutcomeInTx(client.ec, skill, "world", "pass", null, client.convId());
            else maybeAdmit();
            return;
        }
        if (skill == null || skill.isBlank()) return;
        SkillIndex.recordOutcomeInTx(client.ec, skill, "world", "fail",
                failureNotes(toolName, m), client.convId());
    }

    private String proposedName() {
        String proposed = client.pendingProposedSkillName;
        if ((proposed == null || proposed.isBlank()) && client.conversation != null) {
            Object v = client.conversation.getAttributes().get("proposedSkillName");
            if (v != null) proposed = v.toString();
        }
        return proposed;
    }

    private static boolean executedWrite(String toolName, String argumentsJson, Map<?, ?> stored) {
        if (stored == null) return false;
        Object err = stored.get("error");
        if (err != null) {
            String code = err.toString();
            if (SkillRiskGate.NOT_CONFIRMED.equals(code) || SkillRiskGate.DEFERRED.equals(code)
                    || SkillUseGate.ERROR.equals(code)) return false;
        }
        if ("run_service".equals(toolName)) return true;
        if (!"request".equals(toolName)) return false;
        Map<String, Object> args = LlmJson.tryToMap(argumentsJson);
        String method = args != null && args.get("method") != null ? args.get("method").toString() : null;
        if (method == null) return false;
        String mu = method.trim().toUpperCase(java.util.Locale.ROOT);
        return !mu.isEmpty() && !"GET".equals(mu) && !"HEAD".equals(mu);
    }

    static String failureNotes(String toolName, Map<?, ?> stored) {
        StringBuilder sb = new StringBuilder();
        if (toolName != null) sb.append(toolName);
        if (stored.get("status") != null) sb.append(" status=").append(stored.get("status"));
        if (stored.get("serviceName") != null) sb.append(' ').append(stored.get("serviceName"));
        appendNote(sb, stored.get("error"));
        appendNote(sb, stored.get("text"));
        Object messages = stored.get("messages");
        if (messages instanceof Map) {
            Map<?, ?> box = (Map<?, ?>) messages;
            Object errors = box.get("errors");
            if (errors instanceof List) {
                for (Object line : (List<?>) errors) appendNote(sb, line);
            }
            Object validation = box.get("validationErrors");
            if (validation instanceof List) {
                for (Object row : (List<?>) validation) {
                    if (!(row instanceof Map)) continue;
                    Map<?, ?> ve = (Map<?, ?>) row;
                    appendNote(sb, "field " + ve.get("field") + ": " + ve.get("message"));
                }
            }
        }
        String text = sb.toString().trim();
        return text.length() > 800 ? text.substring(0, 800) : text;
    }

    private static void appendNote(StringBuilder sb, Object value) {
        if (value == null) return;
        String text = value.toString().trim();
        if (text.isEmpty()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(text);
    }

    private void rememberProposed(String skillName) {
        client.pendingProposedSkillName = skillName;
        if (client.conversation != null) client.conversation.setAttribute("proposedSkillName", skillName);
    }

    private void maybeAdmit() {
        String name = client.pendingProposedSkillName;
        if ((name == null || name.isBlank()) && client.conversation != null) {
            Object v = client.conversation.getAttributes().get("proposedSkillName");
            if (v != null) name = v.toString();
        }
        if (name == null || name.isBlank()) return;
        String active = client.activeSkillName;
        if (active == null || active.isBlank() || !active.equals(name)) return;
        EntityValue admitted = SkillIndex.admitWorldPassInTx(client.ec, name);
        if (admitted == null) return;
        client.pendingProposedSkillName = null;
        if (client.conversation != null) client.conversation.setAttribute("proposedSkillName", null);
    }

    /** True when the tool returned a success payload, including inside sim. Error messages count as failure. */
    private static boolean callSucceeded(String toolName, Map<?, ?> stored) {
        if (stored == null || stored.get("error") != null) return false;
        if (ToolResultTrim.hasErrorMessages(stored)) return false;
        if ("run_service".equals(toolName)) return Boolean.TRUE.equals(stored.get("ok"));
        if ("request".equals(toolName)) {
            Object status = stored.get("status");
            int s = status instanceof Number ? ((Number) status).intValue() : 0;
            return s >= 200 && s < 300;
        }
        return false;
    }

    private boolean isWorldWriteSuccess(String toolName, String argumentsJson, Map<?, ?> stored) {
        if (client.ec instanceof ExecutionContextImpl && ((ExecutionContextImpl) client.ec).simSession)
            return false;
        if (!callSucceeded(toolName, stored)) return false;
        if (stored.get("error") != null) return false;
        if ("run_service".equals(toolName)) return Boolean.TRUE.equals(stored.get("ok"));
        if ("request".equals(toolName)) {
            Map<String, Object> args = LlmJson.tryToMap(argumentsJson);
            String method = args != null && args.get("method") != null ? args.get("method").toString() : null;
            if (method != null) {
                String m = method.trim().toUpperCase();
                if ("GET".equals(m) || "HEAD".equals(m)) return false;
            }
            Object status = stored.get("status");
            int s = status instanceof Number ? ((Number) status).intValue() : 0;
            return s >= 200 && s < 300;
        }
        if ("write_ui".equals(toolName)) {
            if (!Boolean.TRUE.equals(stored.get("submitted"))) return false;
            Object ar = stored.get("actionResults");
            if (!(ar instanceof List) || ((List<?>) ar).isEmpty()) return false;
            for (Object row : (List<?>) ar) {
                if (!(row instanceof Map)) continue;
                Object st = ((Map<?, ?>) row).get("status");
                int s = st instanceof Number ? ((Number) st).intValue() : 0;
                if (s >= 400) return false;
                if (Boolean.TRUE.equals(((Map<?, ?>) row).get("error"))
                        || ((Map<?, ?>) row).get("error") instanceof String) return false;
            }
            return true;
        }
        return false;
    }

    private static String contentText(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        return LlmJson.toJson(content);
    }

    static Map<String, Object> errorMap(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static String nvl(String v, String def) { return v == null || v.isBlank() ? def : v; }
}
