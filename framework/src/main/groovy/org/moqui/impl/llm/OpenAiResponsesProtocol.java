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

import org.moqui.BaseException;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmProtocol;
import org.moqui.llm.LlmTool;
import org.moqui.llm.LlmToolCall;
import org.moqui.llm.LlmUsage;
import org.moqui.util.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI Responses API ({@code /v1/responses}), also used by xAI. Stateless: {@code store} is forced
 * false and reasoning items are replayed from the conversation. Hosted tools are not sent.
 * Encrypted reasoning is minted by one deployment and rejected by another.
 */
public class OpenAiResponsesProtocol extends HttpLlmProtocol {
    private static final Logger logger = LoggerFactory.getLogger(OpenAiResponsesProtocol.class);
    public static final String DEFAULT_PATH = "/v1/responses";
    static final String INCLUDE_REASONING = "reasoning.encrypted_content";

    public OpenAiResponsesProtocol() { }

    @Override public String getName() { return "openai-responses"; }
    @Override public boolean supportsTools() { return true; }
    @Override public boolean supportsStreaming() { return true; }
    @Override public String defaultPath() { return DEFAULT_PATH; }

    @Override protected Map<String, Object> buildBody(ProtocolRequest request) { return buildRequestBody(request); }

    @Override
    protected ProtocolResult parseResponse(int status, String raw, ProtocolRequest request) {
        return parseBody(status, raw, request.logContent);
    }

    @Override
    protected StreamSession newStreamSession(ProtocolRequest request) {
        StreamAssembler assembler = new StreamAssembler(request);
        return new StreamSession() {
            @Override public void accept(String event, String data, ProtocolStreamListener listener) {
                assembler.accept(event, data, listener);
            }
            @Override public void complete(ProtocolStreamListener listener) {
                if (assembler.terminalResult != null) {
                    listener.onComplete(assembler.finish(200));
                } else if (assembler.hasPartial()) {
                    listener.onFailure(new IOException("LLM stream ended without response.completed"));
                } else {
                    listener.onComplete(assembler.finish(200));
                }
            }
            @Override public ProtocolResult httpError(int status, Throwable t) {
                String errorBody = errorBodyOf(t);
                return parseBody(status, errorBody != null ? errorBody : t.getMessage(), request.logContent);
            }
            @Override public void transportFailure(Throwable t, ProtocolStreamListener listener) {
                // The terminal event arrived and the connection then dropped: the answer is complete
                if (assembler.terminalResult != null) listener.onComplete(assembler.finish(200));
                else listener.onFailure(t);
            }
        };
    }

    public static Map<String, Object> buildRequestBody(ProtocolRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (request.extraBody != null) body.putAll(request.extraBody);
        if (request.model != null && !request.model.isBlank()) body.put("model", request.model);
        String instructions = instructionsOf(request.window);
        if (instructions != null) body.put("instructions", instructions);
        body.put("input", convertInput(request.window));
        if (request.temperature != null) body.put("temperature", request.temperature);
        if (request.maxTokens != null) {
            String param = request.maxTokensParameter != null && !request.maxTokensParameter.isBlank()
                    ? request.maxTokensParameter : "max_output_tokens";
            body.put(param, request.maxTokens);
        }
        body.put("stream", request.stream);
        if (request.tools != null && !request.tools.isEmpty()) {
            List<Map<String, Object>> toolList = convertTools(request.tools);
            if (!toolList.isEmpty()) body.put("tools", toolList);
        }
        body.put("store", Boolean.FALSE);
        List<Object> include = new ArrayList<>();
        include.add(INCLUDE_REASONING);
        body.put("include", include);
        LlmApiConfig.applyToBody(body, request.apiConfig);
        forceStoreAndInclude(body);
        LlmApiConfig.omitTemperatureUnlessEffortNone(body);
        return body;
    }

    static void forceStoreAndInclude(Map<String, Object> body) {
        body.put("store", Boolean.FALSE);
        List<Object> include = new ArrayList<>();
        Object existing = body.get("include");
        if (existing instanceof List) {
            for (Object o : (List<?>) existing) if (o != null) include.add(o);
        } else if (existing != null && !existing.toString().isBlank()) {
            include.add(existing.toString());
        }
        boolean has = false;
        for (Object o : include) {
            if (INCLUDE_REASONING.equals(String.valueOf(o))) { has = true; break; }
        }
        if (!has) include.add(INCLUDE_REASONING);
        body.put("include", include);
    }

    static String instructionsOf(List<LlmMessage> window) {
        if (window == null) return null;
        StringBuilder sb = new StringBuilder();
        for (LlmMessage msg : window) {
            if (msg == null || msg.role != LlmMessage.Role.SYSTEM) continue;
            if (msg.content == null || msg.content.isEmpty()) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(msg.content);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    public static List<Map<String, Object>> convertInput(List<LlmMessage> window) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (window == null) return out;
        for (LlmMessage msg : window) {
            if (msg == null || msg.role == null) continue;
            switch (msg.role) {
                case SYSTEM:
                    break;
                case USER:
                    out.add(messageItem("user", msg.content));
                    break;
                case CONTEXT:
                    String source = "";
                    if (msg.metadata != null && msg.metadata.get("source") != null)
                        source = msg.metadata.get("source").toString();
                    out.add(messageItem("user", "<untrusted-context source=\""
                            + OpenAiCompatProtocol.escapeAttr(source) + "\">"
                            + OpenAiCompatProtocol.elideContextMarkers(msg.content) + "</untrusted-context>"));
                    break;
                case ASSISTANT:
                    List<Map<String, Object>> items = itemsOf(msg);
                    if (items != null) {
                        out.addAll(items);
                        break;
                    }
                    if (msg.content != null && !msg.content.isBlank())
                        out.add(messageItem("assistant", msg.content));
                    if (msg.toolCalls != null) {
                        for (LlmToolCall tc : msg.toolCalls) {
                            if (tc == null) continue;
                            Map<String, Object> call = new LinkedHashMap<>();
                            call.put("type", "function_call");
                            call.put("call_id", tc.id);
                            call.put("name", tc.name);
                            call.put("arguments", tc.arguments != null && !tc.arguments.isBlank() ? tc.arguments : "{}");
                            out.add(call);
                        }
                    }
                    break;
                case TOOL:
                    Map<String, Object> output = new LinkedHashMap<>();
                    output.put("type", "function_call_output");
                    output.put("call_id", msg.toolCallId);
                    output.put("output", msg.content != null ? msg.content : "");
                    out.add(output);
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    static Map<String, Object> messageItem(String role, String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "message");
        item.put("role", role);
        item.put("content", text != null ? text : "");
        return item;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> itemsOf(LlmMessage msg) {
        if (msg == null || msg.providerState == null) return null;
        Object raw = msg.providerState.get("items");
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<?>) raw) {
            if (o instanceof Map) out.add((Map<String, Object>) o);
        }
        return out.isEmpty() ? null : out;
    }

    static List<Map<String, Object>> convertTools(List<LlmTool> tools) {
        List<Map<String, Object>> toolList = new ArrayList<>();
        if (tools == null) return toolList;
        for (LlmTool tool : tools) {
            if (tool == null || tool.getName() == null) continue;
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("type", "function");
            tm.put("name", tool.getName());
            if (tool.getDescription() != null) tm.put("description", tool.getDescription());
            Map<String, Object> params = tool.getParametersSchema();
            if (params == null) {
                params = new LinkedHashMap<>();
                params.put("type", "object");
                params.put("properties", new LinkedHashMap<>());
            }
            tm.put("parameters", params);
            toolList.add(tm);
        }
        return toolList;
    }

    public static ProtocolResult parseBody(int httpStatus, String raw, boolean logContent) {
        Map<String, Object> body = null;
        if (raw != null && !raw.isBlank()) {
            try {
                Object parsed = LlmJson.toObject(raw);
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) parsed;
                    body = map;
                }
            } catch (Throwable ignored) {
                // non-JSON still classified from status + raw text
            }
        }
        boolean errorWithoutOutput = body != null && body.get("error") != null && !(body.get("output") instanceof List);
        if (httpStatus < 200 || httpStatus >= 300 || body == null || errorWithoutOutput) {
            ProtocolResult err = LlmRetryClassifier.classify(httpStatus, raw);
            if (!logContent) err.rawJson = null;
            return err;
        }
        ProtocolResult result = fromOutput(httpStatus, body);
        result.rawJson = logContent ? raw : null;
        return result;
    }

    static ProtocolResult fromOutput(int httpStatus, Map<String, Object> body) {
        ProtocolResult result = new ProtocolResult();
        result.httpStatus = httpStatus;
        result.model = LlmRetryClassifier.str(body.get("model"));
        result.usage = usageOf(body);

        Map<?, ?> error = LlmRetryClassifier.asMap(body.get("error"));
        String errorCode = error != null ? LlmRetryClassifier.str(error.get("code")) : null;
        String errorType = error != null ? LlmRetryClassifier.str(error.get("type")) : null;
        String errorMessage = error != null ? LlmRetryClassifier.str(error.get("message")) : null;
        result.providerErrorCode = errorCode != null ? errorCode : errorType;
        result.errorMessage = errorMessage;

        String status = LlmRetryClassifier.str(body.get("status"));
        Map<?, ?> incomplete = LlmRetryClassifier.asMap(body.get("incomplete_details"));
        String incompleteReason = incomplete != null ? LlmRetryClassifier.str(incomplete.get("reason")) : null;

        StringBuilder text = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        List<LlmToolCall> calls = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        Object output = body.get("output");
        if (output instanceof List) {
            for (Object o : (List<?>) output) {
                Map<?, ?> item = LlmRetryClassifier.asMap(o);
                if (item == null) continue;
                String type = LlmRetryClassifier.str(item.get("type"));
                Map<String, Object> copy = copyItem(item);
                if ("reasoning".equals(type)) {
                    appendSummary(reasoning, item.get("summary"));
                    if (copy.get("encrypted_content") != null) copy.put("summary", new ArrayList<>());
                    items.add(copy);
                } else if ("function_call".equals(type)) {
                    String callId = LlmRetryClassifier.str(item.get("call_id"));
                    if (callId == null || callId.isBlank()) callId = LlmRetryClassifier.str(item.get("id"));
                    String name = LlmRetryClassifier.str(item.get("name"));
                    calls.add(new LlmToolCall(callId, name, argumentsOf(item.get("arguments"))));
                    items.add(copy);
                } else if ("message".equals(type)) {
                    appendMessageText(text, item.get("content"));
                    items.add(copy);
                } else {
                    items.add(copy);
                }
            }
        }
        result.content = text.length() == 0 ? null : text.toString();
        result.reasoning = reasoning.length() == 0 ? null : reasoning.toString();
        result.toolCalls = calls.isEmpty() ? null : calls;
        if (!items.isEmpty()) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("items", items);
            result.providerState = state;
        }

        boolean hasCalls = result.toolCalls != null && !result.toolCalls.isEmpty();
        if (LlmRetryClassifier.isContentFilter(errorCode, errorType, errorMessage)
                || (incompleteReason != null && incompleteReason.toLowerCase().contains("content_filter"))) {
            result.finishReason = LlmFinishReason.CONTENT_FILTER;
            return result;
        }
        if ("failed".equalsIgnoreCase(status) || "cancelled".equalsIgnoreCase(status) || error != null) {
            result.finishReason = LlmFinishReason.ERROR;
            result.retryable = LlmRetryClassifier.isRateLimit(errorCode, errorType, errorMessage);
            return result;
        }
        if ("incomplete".equalsIgnoreCase(status)) {
            result.finishReason = LlmFinishReason.LENGTH;
            return result;
        }
        if (hasCalls) {
            result.finishReason = LlmFinishReason.TOOL_CALLS;
            return result;
        }
        if (result.content != null && !result.content.isBlank()) {
            result.finishReason = LlmFinishReason.STOP;
            return result;
        }
        result.finishReason = LlmFinishReason.EMPTY;
        return result;
    }

    static Map<String, Object> copyItem(Map<?, ?> item) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : item.entrySet()) {
            if (e.getKey() != null) copy.put(e.getKey().toString(), e.getValue());
        }
        return copy;
    }

    static String argumentsOf(Object args) {
        if (args == null) return "{}";
        if (args instanceof CharSequence) return args.toString();
        return LlmJson.toJson(args);
    }

    static void appendMessageText(StringBuilder text, Object content) {
        if (content instanceof CharSequence) {
            text.append(content);
            return;
        }
        if (!(content instanceof List)) return;
        for (Object part : (List<?>) content) {
            if (part instanceof CharSequence) {
                text.append(part);
            } else if (part instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) part;
                String type = LlmRetryClassifier.str(map.get("type"));
                if (type != null && !"output_text".equals(type) && !"text".equals(type)) continue;
                Object t = map.get("text");
                if (t != null) text.append(t.toString());
            }
        }
    }

    static void appendSummary(StringBuilder reasoning, Object summary) {
        if (summary instanceof CharSequence) {
            reasoning.append(summary);
            return;
        }
        if (!(summary instanceof List)) return;
        for (Object part : (List<?>) summary) {
            if (part instanceof CharSequence) reasoning.append(part);
            else if (part instanceof Map) {
                Object text = ((Map<?, ?>) part).get("text");
                if (text != null) reasoning.append(text.toString());
            }
        }
    }

    static LlmUsage usageOf(Map<String, Object> body) {
        Map<?, ?> usage = LlmRetryClassifier.asMap(body.get("usage"));
        if (usage == null) return null;
        Integer prompt = LlmRetryClassifier.toInt(usage.get("input_tokens"));
        if (prompt == null) prompt = LlmRetryClassifier.toInt(usage.get("prompt_tokens"));
        Integer completion = LlmRetryClassifier.toInt(usage.get("output_tokens"));
        if (completion == null) completion = LlmRetryClassifier.toInt(usage.get("completion_tokens"));
        Integer total = LlmRetryClassifier.toInt(usage.get("total_tokens"));
        if (prompt == null && completion == null && total == null) return null;
        return new LlmUsage(prompt, completion, total);
    }

    static final class StreamAssembler {
        private final ProtocolRequest request;
        private final StringBuilder content = new StringBuilder();
        private final TreeMap<Integer, ToolAcc> tools = new TreeMap<>();
        private final Map<String, Integer> idToIndex = new LinkedHashMap<>();
        private final StringBuilder raw = new StringBuilder();
        private ProtocolResult terminalResult;

        StreamAssembler(ProtocolRequest request) { this.request = request; }

        void accept(String event, String data, ProtocolStreamListener listener) {
            if (data == null || data.isBlank() || "[DONE]".equals(data.trim())) return;
            if (request != null && request.logContent) {
                if (raw.length() > 0) raw.append('\n');
                raw.append(data);
            }
            Map<String, Object> chunk;
            try {
                chunk = LlmJson.toMap(data);
            } catch (Throwable t) {
                if (logger.isDebugEnabled()) logger.debug("Skipping non-JSON SSE chunk: " + t.getMessage());
                return;
            }
            if (chunk == null) return;
            String type = event != null && !event.isBlank() ? event : LlmRetryClassifier.str(chunk.get("type"));
            if ("response.output_text.delta".equals(type)) {
                String delta = LlmRetryClassifier.str(chunk.get("delta"));
                if (delta != null && !delta.isEmpty()) {
                    content.append(delta);
                    if (listener != null) listener.onDelta(delta);
                }
                return;
            }
            if ("response.output_item.added".equals(type) || "response.output_item.done".equals(type)) {
                noteItem(chunk);
                return;
            }
            if ("response.function_call_arguments.delta".equals(type)) {
                appendArgs(chunk, listener);
                return;
            }
            if ("response.completed".equals(type) || "response.incomplete".equals(type) || "response.failed".equals(type)) {
                Map<?, ?> response = LlmRetryClassifier.asMap(chunk.get("response"));
                if (response == null && (chunk.get("output") instanceof List || chunk.get("status") != null)) {
                    response = chunk;
                }
                if (response != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> asBody = (Map<String, Object>) response;
                    terminalResult = fromOutput(200, asBody);
                }
                return;
            }
            if ("error".equals(type)) {
                Map<String, Object> errBody = chunk.get("error") != null ? chunk : wrapError(chunk);
                terminalResult = LlmRetryClassifier.classify(200, errBody, null);
            }
        }

        boolean hasPartial() {
            if (content.length() > 0) return true;
            for (ToolAcc acc : tools.values()) {
                if (acc.arguments.length() > 0 || (acc.name != null && !acc.name.isBlank())) return true;
            }
            return false;
        }

        ProtocolResult finish(int httpStatus) {
            ProtocolResult r = terminalResult != null ? terminalResult : new ProtocolResult(LlmFinishReason.EMPTY);
            r.httpStatus = httpStatus;
            if (r.model == null && request != null) r.model = request.model;
            if (request == null || !request.logContent) r.rawJson = null;
            else if (raw.length() > 0) r.rawJson = raw.toString();
            return r;
        }

        private void noteItem(Map<String, Object> chunk) {
            Map<?, ?> item = LlmRetryClassifier.asMap(chunk.get("item"));
            if (item == null || !"function_call".equals(LlmRetryClassifier.str(item.get("type")))) return;
            ToolAcc acc = accFor(LlmRetryClassifier.toInt(chunk.get("output_index")), LlmRetryClassifier.str(item.get("id")));
            String name = LlmRetryClassifier.str(item.get("name"));
            if (name != null && !name.isBlank()) acc.name = name;
            String callId = LlmRetryClassifier.str(item.get("call_id"));
            if (callId != null && !callId.isBlank()) acc.callId = callId;
        }

        private void appendArgs(Map<String, Object> chunk, ProtocolStreamListener listener) {
            ToolAcc acc = accFor(LlmRetryClassifier.toInt(chunk.get("output_index")), LlmRetryClassifier.str(chunk.get("item_id")));
            Object delta = chunk.get("delta");
            if (delta != null) acc.arguments.append(delta.toString());
            if (listener != null && streamsPartialArguments(request, acc.name) && acc.arguments.length() - acc.lastDeltaLen >= 48) {
                acc.lastDeltaLen = acc.arguments.length();
                listener.onToolCallDelta(acc.name, acc.arguments.toString());
            }
        }

        private ToolAcc accFor(Integer idx, String itemId) {
            Integer key = idx;
            if (key == null && itemId != null && idToIndex.containsKey(itemId)) key = idToIndex.get(itemId);
            if (key == null && itemId != null) key = tools.isEmpty() ? 0 : tools.lastKey() + 1;
            if (key == null) key = tools.isEmpty() ? 0 : tools.lastKey();
            ToolAcc acc = tools.get(key);
            if (acc == null) {
                acc = new ToolAcc();
                tools.put(key, acc);
            }
            if (itemId != null && !itemId.isBlank()) idToIndex.put(itemId, key);
            return acc;
        }

        private static Map<String, Object> wrapError(Map<String, Object> chunk) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", chunk);
            return body;
        }
    }

    static final class ToolAcc {
        String name;
        String callId;
        final StringBuilder arguments = new StringBuilder();
        int lastDeltaLen = 0;
    }
}
