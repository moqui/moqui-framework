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

import org.eclipse.jetty.client.HttpResponseException;
import org.eclipse.jetty.client.Response;
import org.moqui.BaseException;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmProtocol;
import org.moqui.llm.LlmTool;
import org.moqui.llm.LlmToolCall;
import org.moqui.util.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class OpenAiCompatProtocol extends HttpLlmProtocol {
    private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatProtocol.class);
    public static final String DEFAULT_PATH = "/v1/chat/completions";

    public OpenAiCompatProtocol() { }

    @Override public String getName() { return "openai-compat"; }
    @Override public boolean supportsTools() { return true; }
    @Override public boolean supportsStreaming() { return true; }

    @Override protected Map<String, Object> buildBody(ProtocolRequest request) { return buildRequestBody(request); }

    @Override
    protected ProtocolResult parseResponse(int status, String raw, ProtocolRequest request) {
        ProtocolResult result = LlmRetryClassifier.classify(status, raw);
        result.rawJson = request.logContent ? raw : null;
        return result;
    }

    @Override
    protected StreamSession newStreamSession(ProtocolRequest request) {
        StreamAssembler assembler = new StreamAssembler(request);
        return new StreamSession() {
            @Override public void accept(String event, String data, ProtocolStreamListener listener) {
                assembler.accept(data, listener);
            }
            @Override public void complete(ProtocolStreamListener listener) {
                // Clean EOF or [DONE] with tokens but no finish_reason is a truncated stream, not STOP.
                if (assembler.hasUnfinishedOutput()) listener.onFailure(new java.io.IOException("LLM stream ended without finish_reason"));
                else listener.onComplete(assembler.toResult(200, null));
            }
            @Override public ProtocolResult httpError(int status, Throwable t) { return assembler.toResult(status, t); }
        };
    }

    public static Map<String, Object> buildRequestBody(ProtocolRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (request.extraBody != null) body.putAll(request.extraBody);
        if (request.model != null && !request.model.isBlank()) body.put("model", request.model);
        body.put("messages", convertMessages(request.window, request));
        if (request.temperature != null) body.put("temperature", request.temperature);
        if (request.maxTokens != null) {
            String param = request.maxTokensParameter != null && !request.maxTokensParameter.isBlank()
                    ? request.maxTokensParameter : "max_tokens";
            body.put(param, request.maxTokens);
        }
        boolean stream = request.stream;
        body.put("stream", stream);
        if (stream) {
            Object existing = body.get("stream_options");
            Map<String, Object> opts;
            if (existing instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> existingMap = (Map<String, Object>) existing;
                opts = new LinkedHashMap<>(existingMap);
            } else {
                opts = new LinkedHashMap<>();
            }
            if (!opts.containsKey("include_usage")) opts.put("include_usage", true);
            body.put("stream_options", opts);
        }
        if (request.tools != null && !request.tools.isEmpty()) {
            List<Map<String, Object>> toolList = new ArrayList<>();
            for (LlmTool tool : request.tools) {
                if (tool == null || tool.getName() == null) continue;
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", tool.getName());
                if (tool.getDescription() != null) fn.put("description", tool.getDescription());
                Map<String, Object> params = tool.getParametersSchema();
                if (params == null) {
                    params = new LinkedHashMap<>();
                    params.put("type", "object");
                    params.put("properties", new LinkedHashMap<>());
                }
                fn.put("parameters", params);
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("type", "function");
                tm.put("function", fn);
                toolList.add(tm);
            }
            if (!toolList.isEmpty()) body.put("tools", toolList);
        }
        LlmApiConfig.applyToBody(body, request.apiConfig);
        // reasoning.effort (Responses shape) rejects temperature. reasoning_effort stays, so Qwen can send both.
        LlmApiConfig.omitTemperatureForReasoningObject(body);
        return body;
    }

    public static List<Map<String, Object>> convertMessages(List<LlmMessage> window) {
        return convertMessages(window, null);
    }

    public static List<Map<String, Object>> convertMessages(List<LlmMessage> window, ProtocolRequest request) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (window == null) return out;
        for (LlmMessage msg : window) {
            if (msg == null || msg.role == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            switch (msg.role) {
                case SYSTEM:
                    m.put("role", "system");
                    m.put("content", msg.content);
                    break;
                case USER:
                    m.put("role", "user");
                    m.put("content", msg.content);
                    break;
                case ASSISTANT:
                    m.put("role", "assistant");
                    m.put("content", msg.content);
                    if (msg.toolCalls != null && !msg.toolCalls.isEmpty()) {
                        List<Map<String, Object>> tcs = new ArrayList<>(msg.toolCalls.size());
                        for (LlmToolCall tc : msg.toolCalls) {
                            if (tc == null) continue;
                            Map<String, Object> tcm = new LinkedHashMap<>();
                            tcm.put("id", tc.id);
                            tcm.put("type", "function");
                            Map<String, Object> fn = new LinkedHashMap<>();
                            fn.put("name", tc.name);
                            fn.put("arguments", tc.arguments != null && !tc.arguments.isBlank() ? tc.arguments : "{}");
                            tcm.put("function", fn);
                            if (tc.providerFields != null) {
                                for (Map.Entry<String, Object> extra : tc.providerFields.entrySet()) {
                                    if (extra.getKey() == null || extra.getValue() == null) continue;
                                    String k = extra.getKey();
                                    if ("id".equals(k) || "type".equals(k) || "function".equals(k)) continue;
                                    tcm.put(k, extra.getValue());
                                }
                            }
                            tcs.add(tcm);
                        }
                        m.put("tool_calls", tcs);
                    }
                    applyAssistantReplay(m, msg, request);
                    break;
                case TOOL:
                    m.put("role", "tool");
                    m.put("tool_call_id", msg.toolCallId);
                    m.put("content", msg.content);
                    if (msg.name != null) m.put("name", msg.name);
                    break;
                case CONTEXT:
                    m.put("role", "user");
                    String source = "";
                    if (msg.metadata != null && msg.metadata.get("source") != null)
                        source = msg.metadata.get("source").toString();
                    m.put("content", "<untrusted-context source=\"" + escapeAttr(source) + "\">"
                            + elideContextMarkers(msg.content) + "</untrusted-context>");
                    break;
                default:
                    continue;
            }
            if (msg.name != null && msg.role != LlmMessage.Role.TOOL && !m.containsKey("name"))
                m.put("name", msg.name);
            out.add(m);
        }
        return out;
    }

    /**
     * Echo reasoning_content and message extra_content. DeepSeek thinking mode 400s on the next tool
     * turn when the key is missing, including turns that returned no reasoning text.
     */
    static void applyAssistantReplay(Map<String, Object> m, LlmMessage msg, ProtocolRequest request) {
        Map<String, Object> state = msg != null ? msg.providerState : null;
        boolean hasReasoning = state != null && state.containsKey("reasoningContent");
        if (hasReasoning) {
            Object rc = state.get("reasoningContent");
            m.put("reasoning_content", rc == null ? "" : rc.toString());
        } else if (needsEmptyReasoning(m, request)) {
            m.put("reasoning_content", "");
        }
        Object extras = state != null ? state.get("messageExtras") : null;
        if (extras instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) extras).entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                String k = e.getKey().toString();
                if ("role".equals(k) || "content".equals(k) || "tool_calls".equals(k) || "reasoning_content".equals(k))
                    continue;
                m.put(k, e.getValue());
            }
        }
    }

    static boolean needsEmptyReasoning(Map<String, Object> message, ProtocolRequest request) {
        if (request == null || request.tools == null || request.tools.isEmpty()) return false;
        if (message == null || !(message.get("tool_calls") instanceof List) || ((List<?>) message.get("tool_calls")).isEmpty())
            return false;
        return thinkingEnabled(request.apiConfig);
    }

    static boolean thinkingEnabled(Map<String, Object> apiConfig) {
        if (apiConfig == null) return false;
        Object thinking = apiConfig.get("thinking");
        if (!(thinking instanceof Map)) return false;
        Object type = ((Map<?, ?>) thinking).get("type");
        return type != null && "enabled".equalsIgnoreCase(type.toString().trim());
    }

    static final String CONTEXT_ELIDED = "[elided-context-marker]";

    /** Replace wrapper markers inside a context body so the payload cannot close the wrapper early. */
    static String elideContextMarkers(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace("</untrusted-context>", CONTEXT_ELIDED)
                .replace("<untrusted-context", CONTEXT_ELIDED);
    }

    static String escapeAttr(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    /** Concatenate content and tool_call arguments by index; usage may arrive after finish_reason. */
    static final class StreamAssembler {
        private final ProtocolRequest request;
        private final String requestModel;
        private final boolean logContent;
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private boolean sawReasoningKey;
        private Object messageExtra;
        private final TreeMap<Integer, ToolCallAcc> toolCalls = new TreeMap<>();
        private final Map<String, Integer> toolCallIdToIndex = new LinkedHashMap<>();
        private String finishReason;
        private String model;
        private Map<String, Object> usageMap;
        private Map<String, Object> error;

        StreamAssembler(ProtocolRequest request) {
            this.request = request;
            this.requestModel = request != null ? request.model : null;
            this.logContent = request != null && request.logContent;
        }

        void accept(String data, ProtocolStreamListener listener) {
            if (data == null || data.isBlank()) return;
            Map<String, Object> chunk;
            try {
                chunk = LlmJson.toMap(data);
            } catch (Throwable t) {
                if (logger.isDebugEnabled()) logger.debug("Skipping non-JSON SSE chunk: " + t.getMessage());
                return;
            }
            if (chunk == null) return;

            String chunkModel = LlmRetryClassifier.str(chunk.get("model"));
            if (chunkModel != null && !chunkModel.isBlank()) model = chunkModel;

            Map<?, ?> err = LlmRetryClassifier.asMap(chunk.get("error"));
            if (err != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> errMap = (Map<String, Object>) err;
                error = errMap;
            }

            Map<?, ?> usage = LlmRetryClassifier.asMap(chunk.get("usage"));
            if (usage != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> um = (Map<String, Object>) usage;
                usageMap = um;
            }

            List<?> choices = chunk.get("choices") instanceof List ? (List<?>) chunk.get("choices") : null;
            if (choices == null || choices.isEmpty()) return;
            Map<?, ?> choice = LlmRetryClassifier.asMap(choices.get(0));
            if (choice == null) return;

            String fr = LlmRetryClassifier.str(choice.get("finish_reason"));
            if (fr == null) fr = LlmRetryClassifier.str(choice.get("finishReason"));
            if (fr != null && !fr.isBlank()) finishReason = fr;

            Map<?, ?> delta = LlmRetryClassifier.asMap(choice.get("delta"));
            if (delta == null) return;

            Object contentDelta = delta.get("content");
            if (contentDelta instanceof CharSequence) {
                String s = contentDelta.toString();
                if (!s.isEmpty()) {
                    content.append(s);
                    if (listener != null) listener.onDelta(s);
                }
            }
            if (delta.containsKey("reasoning_content")) {
                sawReasoningKey = true;
                Object raw = delta.get("reasoning_content");
                if (raw != null) reasoning.append(raw.toString());
            } else {
                String think = LlmRetryClassifier.reasoningOf(delta);
                if (think != null && !think.isEmpty()) reasoning.append(think);
            }
            if (delta.get("extra_content") != null) messageExtra = delta.get("extra_content");

            Object tcs = delta.get("tool_calls");
            if (tcs instanceof List) {
                for (Object item : (List<?>) tcs) {
                    accumulateToolCall(LlmRetryClassifier.asMap(item), listener);
                }
            }
        }

        boolean hasUnfinishedOutput() {
            return finishReason == null && error == null && (content.length() > 0 || !toolCalls.isEmpty());
        }

        private void accumulateToolCall(Map<?, ?> tc, ProtocolStreamListener listener) {
            if (tc == null) return;
            String id = LlmRetryClassifier.str(tc.get("id"));
            boolean hasId = id != null && !id.isBlank();
            Integer idx = null;
            if (tc.get("index") != null) {
                Integer parsed = LlmRetryClassifier.toInt(tc.get("index"));
                if (parsed != null) idx = parsed;
            }
            if (idx == null && hasId && toolCallIdToIndex.containsKey(id)) {
                idx = toolCallIdToIndex.get(id);
            } else if (idx == null && hasId) {
                idx = toolCalls.isEmpty() ? 0 : toolCalls.lastKey() + 1;
            } else if (idx == null) {
                idx = toolCalls.isEmpty() ? 0 : toolCalls.lastKey();
            }

            ToolCallAcc acc = toolCalls.get(idx);
            // Some servers reuse index 0 for every parallel call and tell them apart only by id
            if (acc != null && hasId && acc.id != null && !acc.id.equals(id)) {
                idx = toolCalls.lastKey() + 1;
                acc = null;
            }
            if (acc == null) {
                acc = new ToolCallAcc();
                toolCalls.put(idx, acc);
            }
            if (id != null && !id.isBlank()) {
                acc.id = id;
                toolCallIdToIndex.put(id, idx);
            }
            Map<?, ?> fn = LlmRetryClassifier.asMap(tc.get("function"));
            String name = fn != null ? LlmRetryClassifier.str(fn.get("name")) : LlmRetryClassifier.str(tc.get("name"));
            if (name != null && !name.isBlank()) acc.name = name;
            Map<String, Object> extras = LlmRetryClassifier.providerFieldsOf(tc);
            if (extras != null) {
                if (acc.extras == null) acc.extras = new LinkedHashMap<>();
                acc.extras.putAll(extras);
            }
            Object argsObj = fn != null ? fn.get("arguments") : tc.get("arguments");
            if (argsObj instanceof CharSequence) {
                acc.arguments.append(argsObj);
            } else if (argsObj != null && acc.arguments.length() == 0) {
                acc.arguments.append(LlmJson.toJson(argsObj));
            }
            if (listener != null && streamsPartialArguments(request, acc.name) && acc.arguments.length() - acc.lastDeltaLen >= 48) {
                acc.lastDeltaLen = acc.arguments.length();
                listener.onToolCallDelta(acc.name, acc.arguments.toString());
            }
        }

        ProtocolResult toResult(int httpStatus, Throwable t) {
            String errorBody = errorBodyOf(t);
            if (error == null && content.length() == 0 && toolCalls.isEmpty() && t != null) {
                String raw = (errorBody != null && !errorBody.isBlank()) ? errorBody : t.getMessage();
                ProtocolResult r = LlmRetryClassifier.classify(httpStatus, raw);
                if (r.model == null) r.model = model != null ? model : requestModel;
                if (r.errorMessage == null || r.errorMessage.isBlank()) r.errorMessage = t.getMessage();
                if (!logContent) r.rawJson = null;
                return r;
            }

            Map<String, Object> body = new LinkedHashMap<>();
            if (model != null) body.put("model", model);
            else if (requestModel != null) body.put("model", requestModel);
            if (error != null) body.put("error", error);
            if (usageMap != null) body.put("usage", usageMap);

            List<Map<String, Object>> choices = new ArrayList<>();
            Map<String, Object> choice = new LinkedHashMap<>();
            if (finishReason != null) choice.put("finish_reason", finishReason);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("content", content.length() == 0 ? null : content.toString());
            if (sawReasoningKey) message.put("reasoning_content", reasoning.toString());
            else if (reasoning.length() > 0) message.put("reasoning_content", reasoning.toString());
            if (messageExtra != null) message.put("extra_content", messageExtra);
            if (!toolCalls.isEmpty()) message.put("tool_calls", toolCallsAsOpenAi());
            choice.put("message", message);
            choices.add(choice);
            body.put("choices", choices);

            String rawJson = logContent ? LlmJson.toJson(body) : (t != null ? t.getMessage() : null);
            ProtocolResult r = LlmRetryClassifier.classify(httpStatus, body, rawJson);
            if (r.model == null) r.model = model != null ? model : requestModel;
            if (!logContent) r.rawJson = null;
            return r;
        }

        private List<Map<String, Object>> toolCallsAsOpenAi() {
            List<Map<String, Object>> out = new ArrayList<>(toolCalls.size());
            for (ToolCallAcc acc : toolCalls.values()) {
                Map<String, Object> tc = new LinkedHashMap<>();
                tc.put("id", acc.id);
                tc.put("type", "function");
                Map<String, Object> fn = new LinkedHashMap<>();
                fn.put("name", acc.name);
                fn.put("arguments", acc.arguments.toString());
                tc.put("function", fn);
                if (acc.extras != null) tc.putAll(acc.extras);
                out.add(tc);
            }
            return out;
        }
    }

    static final class ToolCallAcc {
        String id;
        String name;
        Map<String, Object> extras;
        final StringBuilder arguments = new StringBuilder();
        int lastDeltaLen = 0;
    }
}
