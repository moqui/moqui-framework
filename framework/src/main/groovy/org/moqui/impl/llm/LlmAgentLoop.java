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
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmProtocol.ProtocolRequest;
import org.moqui.llm.LlmProtocol.ProtocolResult;
import org.moqui.llm.LlmResponse;
import org.moqui.llm.LlmTool;
import org.moqui.llm.LlmToolCall;
import org.moqui.llm.LlmToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sequential tool loop. Server tools run first in model order; remaining CLIENT tools yield.
 * Invalid JSON and unknown names become tool error results, not exceptions.
 */
final class LlmAgentLoop {
    static final String CLIENT_UNAVAILABLE = "client tool not available in this context";
    static final String UNKNOWN_TOOL = "unknown tool: ";
    static final String MALFORMED = "malformed arguments: ";

    private final LlmClientImpl client;

    LlmAgentLoop(LlmClientImpl client) { this.client = client; }

    LlmResponse run(long start) {
        int maxIter = client.maxIterationsEffective();
        boolean resume = client.hasResumeResults();
        List<LlmMessage> working = null;

        if (client.conversation != null) {
            client.conversation.persistIsolated(() -> {
                client.conversation.beginTurnStreaming(resume);
                if (client.systemContent != null) client.conversation.replaceSystemInternal(client.systemContent);
                applyResumeResults(client.conversation);
                for (LlmMessage u : client.userMessages) client.conversation.appendInternal(u.copy());
            });
            client.markStreamingPersisted();
        } else {
            working = client.buildWindow();
            applyResumeResults(working);
        }

        List<LlmToolResult> roundResults = new ArrayList<>();
        int emptyAttempts = 0;
        int errorAttempts = 0;
        float waitSeconds = client.profile.retryInitialSeconds > 0 ? client.profile.retryInitialSeconds : 0;
        int iteration = 0;

        while (iteration < maxIter) {
            List<LlmMessage> window = client.conversation != null ? client.buildWindow() : working;
            ProtocolRequest req = client.buildRequest(client.resolveModel(), window);
            req.tools = client.tools;

            ProtocolResult result;
            try {
                result = client.profile.protocol.chat(req);
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
                LlmResponse r = client.toResponse(result, fr, start);
                r.toolResults = roundResults;
                r.yielded = false;
                return r;
            }

            List<LlmToolCall> serverCalls = new ArrayList<>();
            List<LlmToolCall> clientCalls = new ArrayList<>();
            for (LlmToolCall call : calls) {
                if (call == null) continue;
                LlmTool tool = client.findTool(call.name);
                if (tool != null && tool.getExecution() == LlmTool.Execution.CLIENT) clientCalls.add(call);
                else serverCalls.add(call);
            }

            for (LlmToolCall call : serverCalls) {
                Object executed = executeOne(call);
                Object stored = client.truncateResult(executed);
                roundResults.add(new LlmToolResult(call.id, call.name, stored));
                appendTool(working, call.id, call.name, stored);
            }

            if (!clientCalls.isEmpty()) {
                if (!client.allowClientTools) {
                    for (LlmToolCall call : clientCalls) {
                        Map<String, Object> err = errorMap(CLIENT_UNAVAILABLE);
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
                        roundResults.add(new LlmToolResult(call.id, call.name, err));
                        appendTool(working, call.id, call.name, err);
                        continue;
                    }
                    if (tool != null) {
                        Map<String, Object> enriched = tool.enrichForClient(args, client.ec);
                        if (enriched != null) copy.arguments = LlmJson.toJson(enriched);
                    }
                    pending.add(copy);
                }
                if (pending.isEmpty()) continue;
                if (client.conversation != null) {
                    client.conversation.persistIsolated(() -> {
                        client.conversation.setPendingToolCallsInternal(pending);
                        client.conversation.setStatusInternal(LlmConversationImpl.STATUS_YIELDED);
                    });
                }
                LlmResponse r = client.toResponse(result, LlmFinishReason.TOOL_CALLS, start);
                r.yielded = true;
                r.httpStatus = 202;
                r.pendingToolCalls = pending;
                r.toolResults = roundResults;
                return r;
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

    private Object executeOne(LlmToolCall call) {
        LlmTool tool = client.findTool(call.name);
        if (tool == null) return errorMap(UNKNOWN_TOOL + call.name);
        Map<String, Object> args = LlmJson.tryToMap(call.arguments);
        if (args == null) return errorMap(MALFORMED + call.arguments);
        try {
            return tool.execute(args, client.ec);
        } catch (Throwable t) {
            return errorMap(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
    }

    private void applyResumeResults(LlmConversationImpl conv) {
        if (!client.hasResumeResults()) {
            conv.setPendingToolCallsInternal(null);
            return;
        }
        List<LlmToolCall> pending = new ArrayList<>(conv.getPendingClientToolCalls());
        List<LlmToolResult> results = client.resumeToolResults;
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (LlmToolResult tr : results) {
            if (tr == null) continue;
            String id = tr.toolCallId;
            conv.appendInternal(LlmMessage.tool(id, tr.name, contentText(tr.content)));
            if (id != null) seen.add(id);
        }
        for (LlmToolCall pendingCall : pending) {
            if (pendingCall == null || pendingCall.id == null || seen.contains(pendingCall.id)) continue;
            conv.appendInternal(LlmMessage.tool(pendingCall.id, pendingCall.name,
                    LlmJson.toJson(errorMap("client tool result missing"))));
        }
        conv.setPendingToolCallsInternal(null);
    }

    private void applyResumeResults(List<LlmMessage> working) {
        if (!client.hasResumeResults() || working == null) return;
        for (LlmToolResult tr : client.resumeToolResults) {
            if (tr == null) continue;
            working.add(LlmMessage.tool(tr.toolCallId, tr.name, contentText(tr.content)));
        }
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
