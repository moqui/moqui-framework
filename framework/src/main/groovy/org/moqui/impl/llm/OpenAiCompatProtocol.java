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

public class OpenAiCompatProtocol implements LlmProtocol {
    private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatProtocol.class);
    public static final String DEFAULT_PATH = "/v1/chat/completions";

    public OpenAiCompatProtocol() { }

    @Override public String getName() { return "openai-compat"; }
    @Override public boolean supportsTools() { return true; }
    @Override public boolean supportsStreaming() { return true; }

    @Override
    public void chatStream(ProtocolRequest request, ProtocolStreamListener listener) {
        if (listener == null) throw new IllegalArgumentException("ProtocolStreamListener is required");
        if (request == null) throw new LlmException("ProtocolRequest is required");
        if (request.endpointUrl == null || request.endpointUrl.isBlank())
            throw new LlmException("LLM endpoint url is required", LlmFinishReason.ERROR, 0, request.profileName);

        request.stream = true;
        String json = LlmJson.toJson(buildRequestBody(request));

        RestClient restClient = newChatRestClient(request, json);
        restClient.acceptContentType("text/event-stream");
        // streamInternal rethrows TimeoutException, so header-timeout retry is Layer A here.
        if (request.timeoutRetry) restClient.timeoutRetry(true);

        StreamAssembler assembler = new StreamAssembler(request);
        AtomicBoolean finished = new AtomicBoolean(false);
        restClient.streamSse(new RestClient.SseConsumer() {
            @Override public boolean onEvent(String event, String data, String id) {
                assembler.accept(data, listener);
                return true;
            }
            @Override public void onComplete() {
                if (!finished.compareAndSet(false, true)) return;
                // Clean EOF or [DONE] with tokens but no finish_reason is a truncated stream, not STOP.
                if (assembler.hasUnfinishedOutput()) {
                    listener.onFailure(new java.io.IOException("LLM stream ended without finish_reason"));
                } else {
                    listener.onComplete(assembler.toResult(200, null));
                }
            }
            @Override public void onFailure(Throwable t) {
                if (!finished.compareAndSet(false, true)) return;
                int status = httpStatusOf(t);
                if (status >= 400) {
                    // Finished HTTP error (not a mid-body drop). Classify body the same as chat().
                    listener.onComplete(assembler.toResult(status, t));
                } else {
                    listener.onFailure(t);
                }
            }
        });
    }

    @Override
    public ProtocolResult chat(ProtocolRequest request) {
        if (request == null) throw new LlmException("ProtocolRequest is required");
        if (request.endpointUrl == null || request.endpointUrl.isBlank())
            throw new LlmException("LLM endpoint url is required", LlmFinishReason.ERROR, 0, request.profileName);

        String json = LlmJson.toJson(buildRequestBody(request));
        RestClient restClient = newChatRestClient(request, json);

        RestClient.RestResponse response;
        try {
            response = restClient.call();
        } catch (BaseException e) {
            ProtocolResult err = new ProtocolResult(LlmFinishReason.ERROR);
            err.errorMessage = e.getMessage();
            // RestClient.callInternal wraps TimeoutException in BaseException, so
            // RestClient.timeoutRetry never runs. Layer B owns timeout retry until that
            // propagates. Do not also call timeoutRetry() here (would double-retry).
            err.retryable = request.timeoutRetry && isTimeout(e);
            if (logger.isDebugEnabled()) logger.debug("LLM HTTP call failed for profile " + request.profileName, e);
            return err;
        }

        int status = response.getStatusCode();
        String raw = response.text();
        ProtocolResult result = LlmRetryClassifier.classify(status, raw);
        if (!request.logContent) result.rawJson = null;
        else result.rawJson = raw;
        if (result.model == null) result.model = request.model;
        return result;
    }

    static RestClient newChatRestClient(ProtocolRequest request, String json) {
        RestClient restClient = new RestClient()
                .method(RestClient.POST)
                .uri(request.endpointUrl)
                .contentType("application/json")
                .timeout(request.timeoutSeconds != null && request.timeoutSeconds > 0 ? request.timeoutSeconds : 120)
                .retry(request.retryInitialSeconds > 0 ? request.retryInitialSeconds : 2.0f,
                        request.retryMax >= 0 ? request.retryMax : 5)
                .redactHeaders(redactHeaderNames(request))
                .text(json);
        if (request.requestFactory != null) restClient.withRequestFactory(request.requestFactory);
        applyAuthAndHeaders(restClient, request);
        return restClient;
    }

    static void applyAuthAndHeaders(RestClient restClient, ProtocolRequest request) {
        for (Map.Entry<String, String> e : authHeaders(request).entrySet()) {
            restClient.addHeader(e.getKey(), e.getValue());
        }
        if (request.extraHeaders != null) {
            for (Map.Entry<String, String> e : request.extraHeaders.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null) continue;
                restClient.addHeader(e.getKey(), e.getValue());
            }
        }
    }

    /** Auth header only; empty when api-key is blank (no Authorization: Bearer ). */
    public static Map<String, String> authHeaders(ProtocolRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (request == null) return headers;
        String apiKey = request.apiKey;
        if (apiKey == null || apiKey.isBlank()) return headers;
        String headerName = request.authHeaderName != null && !request.authHeaderName.isBlank()
                ? request.authHeaderName : "Authorization";
        String headerValue = request.authHeaderValue;
        if (headerValue == null || headerValue.isBlank()) headerValue = "Bearer " + apiKey;
        headers.put(headerName, headerValue);
        return headers;
    }

    public static String[] redactHeaderNames(ProtocolRequest request) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.add("Authorization");
        names.add("api-key");
        names.add("x-api-key");
        if (request != null && request.authHeaderName != null && !request.authHeaderName.isBlank())
            names.add(request.authHeaderName);
        return names.toArray(new String[0]);
    }

    public static Map<String, Object> buildRequestBody(ProtocolRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (request.extraBody != null) body.putAll(request.extraBody);
        if (request.model != null && !request.model.isBlank()) body.put("model", request.model);
        body.put("messages", convertMessages(request.window));
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
        return body;
    }

    public static List<Map<String, Object>> convertMessages(List<LlmMessage> window) {
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
                            fn.put("arguments", tc.arguments != null ? tc.arguments : "{}");
                            tcm.put("function", fn);
                            tcs.add(tcm);
                        }
                        m.put("tool_calls", tcs);
                    }
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
                            + (msg.content != null ? msg.content : "") + "</untrusted-context>");
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
     * Origin vs full-endpoint URL composition.
     * If url contains '?' or a path other than empty/'/', treat as full endpoint (ignore path).
     * Otherwise origin: strip trailing slash, append path (default /v1/chat/completions), append extra query.
     */
    public static String composeEndpointUrl(String url, String path, Map<String, String> extraQuery) {
        if (url == null || url.isBlank()) throw new LlmException("LLM profile url is required");
        String trimmed = url.trim();
        String pathToUse = (path == null || path.isBlank()) ? DEFAULT_PATH : path.trim();
        if (!pathToUse.startsWith("/")) pathToUse = "/" + pathToUse;

        int qIdx = trimmed.indexOf('?');
        String noQuery = qIdx >= 0 ? trimmed.substring(0, qIdx) : trimmed;
        String existingQuery = qIdx >= 0 ? trimmed.substring(qIdx + 1) : "";

        URI uri;
        try {
            uri = URI.create(noQuery);
        } catch (Exception e) {
            throw new LlmException("Unparseable LLM profile url: " + url, e);
        }
        String uriPath = uri.getRawPath();
        boolean fullEndpoint = qIdx >= 0 || (uriPath != null && !uriPath.isEmpty() && !"/".equals(uriPath));

        if (fullEndpoint) {
            if (extraQuery == null || extraQuery.isEmpty()) return trimmed;
            Set<String> existingNames = queryNames(existingQuery);
            StringBuilder add = new StringBuilder();
            for (Map.Entry<String, String> e : extraQuery.entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty()) continue;
                if (existingNames.contains(e.getKey())) continue;
                add.append(add.length() == 0 && qIdx < 0 ? '?' : '&');
                add.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue() != null ? e.getValue() : ""));
            }
            return add.length() == 0 ? trimmed : trimmed + add;
        }

        String origin = noQuery.endsWith("/") ? noQuery.substring(0, noQuery.length() - 1) : noQuery;
        StringBuilder result = new StringBuilder(origin).append(pathToUse);
        if (extraQuery != null && !extraQuery.isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, String> e : extraQuery.entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty()) continue;
                result.append(first ? '?' : '&');
                first = false;
                result.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue() != null ? e.getValue() : ""));
            }
        }
        return result.toString();
    }

    static Set<String> queryNames(String query) {
        Set<String> names = new LinkedHashSet<>();
        if (query == null || query.isEmpty()) return names;
        String[] parts = query.split("&");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String name = eq >= 0 ? part.substring(0, eq) : part;
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    static String escapeAttr(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }

    static boolean isTimeout(Throwable t) {
        while (t != null) {
            if (t instanceof java.util.concurrent.TimeoutException) return true;
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase().contains("timeout")) return true;
            t = t.getCause();
        }
        return false;
    }

    static int httpStatusOf(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof RestClient.HttpErrorException) {
                int status = ((RestClient.HttpErrorException) cur).getStatusCode();
                if (status > 0) return status;
            }
            if (cur instanceof HttpResponseException) {
                Response resp = ((HttpResponseException) cur).getResponse();
                if (resp != null && resp.getStatus() > 0) return resp.getStatus();
            }
            cur = cur.getCause();
        }
        return 0;
    }

    static String errorBodyOf(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof RestClient.HttpErrorException) {
                return ((RestClient.HttpErrorException) cur).getResponseText();
            }
            cur = cur.getCause();
        }
        return null;
    }

    /** Concatenate content and tool_call arguments by index; usage may arrive after finish_reason. */
    static final class StreamAssembler {
        private final String requestModel;
        private final boolean logContent;
        private final StringBuilder content = new StringBuilder();
        private final TreeMap<Integer, ToolCallAcc> toolCalls = new TreeMap<>();
        private final Map<String, Integer> toolCallIdToIndex = new LinkedHashMap<>();
        private String finishReason;
        private String model;
        private Map<String, Object> usageMap;
        private Map<String, Object> error;
        private final StringBuilder raw = new StringBuilder();

        StreamAssembler(ProtocolRequest request) {
            this.requestModel = request != null ? request.model : null;
            this.logContent = request != null && request.logContent;
        }

        void accept(String data, ProtocolStreamListener listener) {
            if (data == null || data.isBlank()) return;
            if (logContent) {
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

            Object tcs = delta.get("tool_calls");
            if (tcs instanceof List) {
                for (Object item : (List<?>) tcs) {
                    accumulateToolCall(LlmRetryClassifier.asMap(item));
                }
            }
        }

        boolean hasUnfinishedOutput() {
            return finishReason == null && error == null && (content.length() > 0 || !toolCalls.isEmpty());
        }

        private void accumulateToolCall(Map<?, ?> tc) {
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
            Object argsObj = fn != null ? fn.get("arguments") : tc.get("arguments");
            if (argsObj instanceof CharSequence) {
                acc.arguments.append(argsObj);
            } else if (argsObj != null && acc.arguments.length() == 0) {
                acc.arguments.append(LlmJson.toJson(argsObj));
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
            if (!toolCalls.isEmpty()) message.put("tool_calls", toolCallsAsOpenAi());
            choice.put("message", message);
            choices.add(choice);
            body.put("choices", choices);

            String rawJson = logContent && raw.length() > 0 ? raw.toString() : (t != null ? t.getMessage() : null);
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
                out.add(tc);
            }
            return out;
        }
    }

    static final class ToolCallAcc {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }
}
