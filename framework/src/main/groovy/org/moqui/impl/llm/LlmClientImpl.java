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

import org.moqui.context.ExecutionContext;
import org.moqui.llm.LlmClient;
import org.moqui.llm.LlmConversation;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmProtocol;
import org.moqui.llm.LlmProtocol.ProtocolRequest;
import org.moqui.llm.LlmProtocol.ProtocolResult;
import org.moqui.llm.LlmResponse;
import org.moqui.llm.LlmStreamListener;
import org.moqui.llm.LlmTool;
import org.moqui.llm.LlmToolResult;
import org.moqui.llm.WindowPolicy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public class LlmClientImpl implements LlmClient {
    private final ExecutionContext ec;
    private final LlmFacadeImpl.ProfileState profile;
    private final BooleanSupplier transactionInPlace;

    private String systemContent = null;
    private final List<LlmMessage> extraMessages = new ArrayList<>();
    private final List<LlmMessage> userMessages = new ArrayList<>();
    private String modelOverride = null;
    private Double temperature = null;
    private Integer maxTokens = null;
    private Integer timeoutSeconds = null;
    private Map<String, Object> extraBody = null;

    public LlmClientImpl(ExecutionContext ec, LlmFacadeImpl.ProfileState profile) {
        this(ec, profile, null);
    }
    /** @param transactionInPlace test hook; null uses ec.transaction.isTransactionInPlace() */
    public LlmClientImpl(ExecutionContext ec, LlmFacadeImpl.ProfileState profile, BooleanSupplier transactionInPlace) {
        if (profile == null) throw new LlmException("LLM profile is required");
        this.ec = ec;
        this.profile = profile;
        this.transactionInPlace = transactionInPlace;
        this.temperature = profile.temperature;
        this.maxTokens = profile.maxTokens;
        this.timeoutSeconds = profile.timeoutSeconds;
    }

    @Override public String getProfileName() { return profile.name; }

    @Override public LlmClient conversation(String conversationId) { throw uoe("conversation"); }
    @Override public LlmClient conversation(LlmConversation conversation) { throw uoe("conversation"); }
    @Override public LlmClient newConversation() { throw uoe("newConversation"); }
    @Override public LlmClient injectContext(String source, String content) { throw uoe("injectContext"); }
    @Override public LlmClient tool(LlmTool tool) { throw uoe("tool"); }
    @Override public LlmClient tools(List<LlmTool> tools) { throw uoe("tools"); }
    @Override public LlmClient toolResults(List<LlmToolResult> results) { throw uoe("toolResults"); }
    @Override public LlmClient allowClientTools(boolean allow) { throw uoe("allowClientTools"); }
    @Override public LlmClient allowedEntity(String entityName) { throw uoe("allowedEntity"); }
    @Override public LlmClient allowedPath(String prefix, String methodsCsv) { throw uoe("allowedPath"); }
    @Override public LlmClient maxIterations(int n) { throw uoe("maxIterations"); }
    @Override public LlmClient windowPolicy(WindowPolicy policy) { throw uoe("windowPolicy"); }
    @Override public LlmResponse stream(LlmStreamListener listener) { throw uoe("stream"); }

    @Override
    public LlmClient system(String content) {
        this.systemContent = content;
        return this;
    }
    @Override
    public LlmClient user(String content) {
        userMessages.add(LlmMessage.user(content));
        return this;
    }
    @Override
    public LlmClient messages(List<LlmMessage> extra) {
        if (extra != null) {
            for (LlmMessage m : extra) {
                if (m != null) extraMessages.add(m);
            }
        }
        return this;
    }
    @Override
    public LlmClient model(String model) {
        this.modelOverride = model;
        return this;
    }
    @Override
    public LlmClient temperature(double t) {
        this.temperature = t;
        return this;
    }
    @Override
    public LlmClient maxTokens(int n) {
        this.maxTokens = n;
        return this;
    }
    @Override
    public LlmClient timeout(int seconds) {
        this.timeoutSeconds = seconds;
        return this;
    }
    @Override
    public LlmClient extraBody(Map<String, Object> body) {
        if (body == null) this.extraBody = null;
        else this.extraBody = new LinkedHashMap<>(body);
        return this;
    }

    @Override
    public LlmResponse call() {
        // Fail-fast before any HTTP.
        if (isTransactionInPlace() && !profile.allowTxOverHttp) {
            throw new LlmException(
                    "Cannot call LLM while a JTA transaction is active (default TX timeout 60s vs LLM timeout "
                            + (timeoutSeconds != null ? timeoutSeconds : profile.timeoutSeconds)
                            + "s). Commit first, or set allow-tx-over-http=true.",
                    LlmFinishReason.ERROR, 0, profile.name);
        }

        String model = resolveModel();
        if (model == null || model.isBlank()) {
            throw new LlmException("profile '" + profile.name + "' has no model",
                    LlmFinishReason.ERROR, 0, profile.name);
        }

        List<LlmMessage> window = buildWindow();
        int emptyAttempts = 0;
        int errorAttempts = 0;
        float waitSeconds = profile.retryInitialSeconds > 0 ? profile.retryInitialSeconds : 0;
        long start = System.currentTimeMillis();

        while (true) {
            ProtocolRequest req = buildRequest(model, window);
            ProtocolResult result;
            try {
                result = profile.protocol.chat(req);
            } catch (LlmException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new LlmException("LLM protocol call failed: " + e.getMessage(), e,
                        LlmFinishReason.ERROR, 0, profile.name);
            }
            if (result == null) {
                throw new LlmException("LLM protocol returned no result", LlmFinishReason.ERROR, 0, profile.name);
            }
            LlmFinishReason fr = result.finishReason != null ? result.finishReason : LlmFinishReason.ERROR;

            if (fr == LlmFinishReason.STOP || fr == LlmFinishReason.LENGTH || fr == LlmFinishReason.TOOL_CALLS) {
                return toResponse(result, fr, start);
            }
            if (fr == LlmFinishReason.CONTENT_FILTER) {
                throw new LlmException(nvl(result.errorMessage, "LLM content filter"),
                        LlmFinishReason.CONTENT_FILTER, result.httpStatus, profile.name);
            }
            if (fr == LlmFinishReason.CONTEXT_OVERFLOW) {
                throw new LlmException(nvl(result.errorMessage, "LLM context length exceeded"),
                        LlmFinishReason.CONTEXT_OVERFLOW, result.httpStatus, profile.name);
            }
            if (fr == LlmFinishReason.EMPTY) {
                if (emptyAttempts < profile.emptyRetries) {
                    emptyAttempts++;
                    sleepBackoff(waitSeconds);
                    waitSeconds = nextWait(waitSeconds);
                    continue;
                }
                throw new LlmException("LLM returned empty content after " + profile.emptyRetries + " retries",
                        LlmFinishReason.EMPTY, result.httpStatus, profile.name);
            }

            // ERROR
            boolean retryable = result.retryable;
            if (retryable && errorAttempts < profile.retryMax) {
                errorAttempts++;
                sleepBackoff(waitSeconds);
                waitSeconds = nextWait(waitSeconds);
                continue;
            }
            throw new LlmException(nvl(result.errorMessage, "LLM call failed"),
                    LlmFinishReason.ERROR, result.httpStatus, profile.name);
        }
    }

    private boolean isTransactionInPlace() {
        if (transactionInPlace != null) return transactionInPlace.getAsBoolean();
        if (ec == null || ec.getTransaction() == null) return false;
        return ec.getTransaction().isTransactionInPlace();
    }

    private String resolveModel() {
        if (modelOverride != null && !modelOverride.isBlank()) return modelOverride;
        if (profile.model != null && !profile.model.isBlank()) return profile.model;
        if (extraBody != null && extraBody.get("model") != null) {
            String m = extraBody.get("model").toString();
            if (m != null && !m.isBlank()) return m;
        }
        return null;
    }

    private List<LlmMessage> buildWindow() {
        List<LlmMessage> window = new ArrayList<>();
        if (systemContent != null) window.add(LlmMessage.system(systemContent));
        window.addAll(extraMessages);
        window.addAll(userMessages);
        return window;
    }

    private ProtocolRequest buildRequest(String model, List<LlmMessage> window) {
        ProtocolRequest req = new ProtocolRequest();
        req.profileName = profile.name;
        req.endpointUrl = profile.endpointUrl;
        req.apiKey = profile.apiKey;
        req.authHeaderName = profile.authHeaderName;
        req.authHeaderValue = profile.authHeaderValue;
        req.extraHeaders = profile.extraHeaders;
        req.extraQuery = profile.extraQuery;
        req.model = model;
        req.window = window;
        req.temperature = temperature;
        req.maxTokens = maxTokens;
        req.maxTokensParameter = profile.maxTokensParameter;
        req.timeoutSeconds = timeoutSeconds != null ? timeoutSeconds : profile.timeoutSeconds;
        req.stream = false;
        req.extraBody = extraBody;
        req.requestFactory = profile.requestFactory;
        req.retryInitialSeconds = profile.retryInitialSeconds;
        req.retryMax = profile.retryMax;
        req.timeoutRetry = profile.timeoutRetry;
        req.logContent = profile.logContent;
        return req;
    }

    private LlmResponse toResponse(ProtocolResult result, LlmFinishReason fr, long start) {
        LlmResponse r = new LlmResponse();
        r.content = result.content;
        r.finishReason = fr;
        r.toolCalls = result.toolCalls;
        r.usage = result.usage;
        r.model = result.model != null ? result.model : resolveModel();
        r.profileName = profile.name;
        r.httpStatus = result.httpStatus;
        r.errorMessage = result.errorMessage;
        r.providerErrorCode = result.providerErrorCode;
        r.durationMs = System.currentTimeMillis() - start;
        r.rawJson = profile.logContent ? result.rawJson : null;
        r.yielded = false;
        return r;
    }

    private void sleepBackoff(float waitSeconds) {
        if (waitSeconds <= 0) return;
        try {
            Thread.sleep(Math.round(waitSeconds * 1000.0f));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM retry sleep interrupted", e, LlmFinishReason.ERROR, 0, profile.name);
        }
    }

    private float nextWait(float waitSeconds) {
        float initial = profile.retryInitialSeconds > 0 ? profile.retryInitialSeconds : 2.0f;
        if (waitSeconds <= 0) return initial;
        return waitSeconds * initial;
    }

    private static String nvl(String v, String def) { return v == null || v.isBlank() ? def : v; }

    private static UnsupportedOperationException uoe(String method) {
        return new UnsupportedOperationException("LlmClient." + method + " is not yet implemented");
    }
}
