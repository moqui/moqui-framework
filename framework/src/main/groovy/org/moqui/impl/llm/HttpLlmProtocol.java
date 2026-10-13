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
import org.moqui.llm.LlmProtocol;
import org.moqui.llm.LlmTool;
import org.moqui.util.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the HTTP JSON protocols (Chat Completions, Responses) share: building the request client with auth and headers,
 * the blocking call with its error handling, the streaming call and its one-shot completion guard, and the
 * helpers for endpoint URLs and error inspection. A subclass supplies the request body, how a response body becomes a
 * result, and how stream events accumulate.
 */
public abstract class HttpLlmProtocol implements LlmProtocol {
    private static final Logger logger = LoggerFactory.getLogger(HttpLlmProtocol.class);
    /** Used by {@link #composeEndpointUrl} when a profile gives an origin and no path. */
    public static final String DEFAULT_CHAT_PATH = "/v1/chat/completions";

    /** The JSON body to POST for this request. */
    protected abstract Map<String, Object> buildBody(ProtocolRequest request);

    /** Turn a finished HTTP response into a result, including provider error bodies. */
    protected abstract ProtocolResult parseResponse(int httpStatus, String raw, ProtocolRequest request);

    /** One streaming call's accumulation state. */
    protected abstract StreamSession newStreamSession(ProtocolRequest request);

    /** Accumulates one stream's events and decides how it ends. Called from the HTTP client's thread. */
    protected interface StreamSession {
        /** One SSE event. Streams that carry the type in the data pass a null or blank event. */
        void accept(String event, String data, ProtocolStreamListener listener);
        /** The stream ended cleanly: deliver the result, or a failure if it was cut short. */
        void complete(ProtocolStreamListener listener);
        /** The server answered with an HTTP error status. The result carries the classified provider error. */
        ProtocolResult httpError(int status, Throwable t);
        /** A transport failure with no HTTP error status. A stream that already finished is still a success. */
        default void transportFailure(Throwable t, ProtocolStreamListener listener) { listener.onFailure(t); }
    }

    @Override
    public final void chatStream(ProtocolRequest request, ProtocolStreamListener listener) {
        if (listener == null) throw new IllegalArgumentException("ProtocolStreamListener is required");
        requireEndpoint(request);

        request.stream = true;
        String json = LlmJson.toJson(buildBody(request));
        RestClient restClient = newChatRestClient(request, json);
        restClient.acceptContentType("text/event-stream");
        // streamInternal rethrows TimeoutException, so header-timeout retry is Layer A here.
        if (request.timeoutRetry) restClient.timeoutRetry(true);

        StreamSession session = newStreamSession(request);
        AtomicBoolean finished = new AtomicBoolean(false);
        restClient.streamSse(new RestClient.SseConsumer() {
            @Override public boolean onEvent(String event, String data, String id) {
                session.accept(event, data, listener);
                return true;
            }
            @Override public void onComplete() {
                if (!finished.compareAndSet(false, true)) return;
                session.complete(listener);
            }
            @Override public void onFailure(Throwable t) {
                if (!finished.compareAndSet(false, true)) return;
                int status = httpStatusOf(t);
                if (status >= 400) {
                    // A finished HTTP error, not a mid-body drop. Classify the body the same as chat().
                    ProtocolResult r = session.httpError(status, t);
                    if (r.model == null) r.model = request.model;
                    listener.onComplete(r);
                } else {
                    session.transportFailure(t, listener);
                }
            }
        }, request.onStreamOpen);
    }

    @Override
    public final ProtocolResult chat(ProtocolRequest request) {
        requireEndpoint(request);

        String json = LlmJson.toJson(buildBody(request));
        RestClient restClient = newChatRestClient(request, json);

        RestClient.RestResponse response;
        try {
            response = restClient.call();
        } catch (BaseException e) {
            ProtocolResult err = new ProtocolResult(LlmFinishReason.ERROR);
            err.errorMessage = e.getMessage();
            // RestClient.callInternal wraps TimeoutException in BaseException, so RestClient.timeoutRetry never
            // runs. Layer B owns timeout retry until that propagates. Do not also call timeoutRetry() here,
            // which would retry twice.
            err.retryable = request.timeoutRetry && isTimeout(e);
            if (logger.isDebugEnabled()) logger.debug("LLM HTTP call failed for profile " + request.profileName, e);
            return err;
        }

        ProtocolResult result = parseResponse(response.getStatusCode(), response.text(), request);
        if (result.model == null) result.model = request.model;
        return result;
    }

    private static void requireEndpoint(ProtocolRequest request) {
        if (request == null) throw new LlmException("ProtocolRequest is required");
        if (request.endpointUrl == null || request.endpointUrl.isBlank())
            throw new LlmException("LLM endpoint url is required", LlmFinishReason.ERROR, 0, request.profileName);
    }

    /** True when the profile asked for partial arguments of this tool to be streamed to the client as they arrive. */
    protected static boolean streamsPartialArguments(ProtocolRequest request, String toolName) {
        if (request == null || request.tools == null || toolName == null) return false;
        for (LlmTool t : request.tools) {
            if (t != null && toolName.equals(t.getName())) return t.streamsPartialArguments();
        }
        return false;
    }

    /**
     * Origin vs full-endpoint URL composition.
     * If url contains '?' or a path other than empty/'/', treat as full endpoint (ignore path).
     * Otherwise origin: strip trailing slash, append path (default /v1/chat/completions), append extra query.
     */
    public static String composeEndpointUrl(String url, String path, Map<String, String> extraQuery) {
        if (url == null || url.isBlank()) throw new LlmException("LLM profile url is required");
        String trimmed = url.trim();
        String pathToUse = (path == null || path.isBlank()) ? DEFAULT_CHAT_PATH : path.trim();
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


    static RestClient newChatRestClient(ProtocolRequest request, String json) {
        RestClient restClient = new RestClient()
                .method(RestClient.POST)
                .uri(request.endpointUrl)
                .contentType("application/json")
                .timeout(request.timeoutSeconds != null && request.timeoutSeconds > 0 ? request.timeoutSeconds : 120)
                .retry(request.retryInitialSeconds > 0 ? request.retryInitialSeconds : 2.0f,
                        request.retryMax >= 0 ? request.retryMax : 5)
                .redactHeaders(redactHeaderNames(request))
                .allowInSim(true)
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

}
