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

public class OpenAiCompatProtocol implements LlmProtocol {
    private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatProtocol.class);
    public static final String DEFAULT_PATH = "/v1/chat/completions";

    public OpenAiCompatProtocol() { }

    @Override public String getName() { return "openai-compat"; }
    @Override public boolean supportsTools() { return true; }
    @Override public boolean supportsStreaming() { return false; }

    @Override
    public void chatStream(ProtocolRequest request, ProtocolStreamListener listener) {
        throw new UnsupportedOperationException("OpenAiCompatProtocol.chatStream is not yet implemented");
    }

    @Override
    public ProtocolResult chat(ProtocolRequest request) {
        if (request == null) throw new LlmException("ProtocolRequest is required");
        if (request.endpointUrl == null || request.endpointUrl.isBlank())
            throw new LlmException("LLM endpoint url is required", LlmFinishReason.ERROR, 0, request.profileName);

        Map<String, Object> body = buildRequestBody(request);
        String json = LlmJson.toJson(body);

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
        body.put("stream", false);
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
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("type", "function");
                t.put("function", fn);
                toolList.add(t);
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
}
