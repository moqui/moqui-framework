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
import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.context.ArtifactTarpitException;
import org.moqui.context.ExecutionContext;
import org.moqui.llm.LlmClient;
import org.moqui.llm.LlmConversation;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmProtocol.ProtocolRequest;
import org.moqui.llm.LlmProtocol.ProtocolResult;
import org.moqui.llm.LlmResponse;
import org.moqui.llm.LlmStreamListener;
import org.moqui.llm.LlmTool;
import org.moqui.llm.LlmToolResult;
import org.moqui.llm.WindowPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

public class LlmClientImpl implements LlmClient {
    private static final Logger logger = LoggerFactory.getLogger(LlmClientImpl.class);
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
    private LlmConversationImpl conversation = null;
    private WindowPolicy windowPolicy = null;

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

    @Override
    public LlmClient conversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank())
            throw new LlmException("conversationId is required");
        this.conversation = LlmConversationImpl.load(ec, conversationId, true);
        applyWindowPolicyToConversation();
        return this;
    }
    @Override
    public LlmClient conversation(LlmConversation conversation) {
        if (conversation == null) {
            this.conversation = null;
            return this;
        }
        if (conversation instanceof LlmConversationImpl) {
            this.conversation = (LlmConversationImpl) conversation;
        } else {
            this.conversation = LlmConversationImpl.load(ec, conversation.getConversationId(), true);
        }
        applyWindowPolicyToConversation();
        return this;
    }
    @Override
    public LlmClient newConversation() {
        this.conversation = LlmConversationImpl.create(ec, profile.name, null);
        applyWindowPolicyToConversation();
        return this;
    }
    @Override
    public LlmClient injectContext(String source, String content) {
        if (conversation != null) conversation.injectContext(source, content);
        else extraMessages.add(LlmMessage.context(source, content));
        return this;
    }
    @Override public LlmClient tool(LlmTool tool) { throw uoe("tool"); }
    @Override public LlmClient tools(List<LlmTool> tools) { throw uoe("tools"); }
    @Override public LlmClient toolResults(List<LlmToolResult> results) { throw uoe("toolResults"); }
    @Override public LlmClient allowClientTools(boolean allow) { throw uoe("allowClientTools"); }
    @Override public LlmClient allowedEntity(String entityName) { throw uoe("allowedEntity"); }
    @Override public LlmClient allowedPath(String prefix, String methodsCsv) { throw uoe("allowedPath"); }
    @Override public LlmClient maxIterations(int n) { throw uoe("maxIterations"); }
    @Override
    public LlmClient windowPolicy(WindowPolicy policy) {
        this.windowPolicy = policy != null ? policy : new WindowPolicy();
        applyWindowPolicyToConversation();
        return this;
    }
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
        // Fail-fast BEFORE any status=Streaming write. persistIsolated resumes the caller TX, so a
        // check after Streaming would still see a ServiceJob TX and wedge the conversation (K11).
        if (isTransactionInPlace() && !profile.allowTxOverHttp) {
            throw new LlmException(
                    "Cannot call LLM while a JTA transaction is active (default TX timeout 60s vs LLM timeout "
                            + (timeoutSeconds != null ? timeoutSeconds : profile.timeoutSeconds)
                            + "s). Commit first, or set allow-tx-over-http=true.",
                    null, LlmFinishReason.ERROR, 0, profile.name, convId());
        }

        String model = resolveModel();
        if (model == null || model.isBlank()) {
            throw new LlmException("profile '" + profile.name + "' has no model",
                    null, LlmFinishReason.ERROR, 0, profile.name, convId());
        }

        ArtifactExecutionFacade aefi = ec != null ? ec.getArtifactExecution() : null;
        ArtifactExecutionInfo aei = null;
        // True only after Streaming is committed (or in-memory persistIsolated returned).
        boolean streamingWasPersisted = false;
        boolean cancelled = false;
        int emptyAttempts = 0;
        int errorAttempts = 0;
        float waitSeconds = profile.retryInitialSeconds > 0 ? profile.retryInitialSeconds : 0;
        long start = System.currentTimeMillis();
        ProtocolResult lastResult = null;
        try {
            // Authz/tarpit before any Streaming write so a 403/429 cannot wedge the row.
            if (aefi != null) {
                aei = aefi.push(profile.name, ArtifactExecutionInfo.AT_LLM,
                        ArtifactExecutionInfo.AUTHZA_VIEW, true);
            }
            if (conversation != null) {
                conversation.persistIsolated(() -> {
                    conversation.beginTurnStreaming();
                    if (systemContent != null) conversation.replaceSystemInternal(systemContent);
                    for (LlmMessage u : userMessages) conversation.appendInternal(u.copy());
                });
                streamingWasPersisted = true;
            }
            while (true) {
                List<LlmMessage> window = buildWindow();
                ProtocolRequest req = buildRequest(model, window);
                ProtocolResult result;
                try {
                    result = profile.protocol.chat(req);
                } catch (ArtifactAuthorizationException | ArtifactTarpitException e) {
                    throw e;
                } catch (LlmException e) {
                    throw e;
                } catch (RuntimeException e) {
                    throw new LlmException("LLM protocol call failed: " + e.getMessage(), e,
                            LlmFinishReason.ERROR, 0, profile.name, convId());
                }
                if (result == null) {
                    throw new LlmException("LLM protocol returned no result",
                            null, LlmFinishReason.ERROR, 0, profile.name, convId());
                }
                lastResult = result;
                LlmFinishReason fr = result.finishReason != null ? result.finishReason : LlmFinishReason.ERROR;

                if (fr == LlmFinishReason.STOP || fr == LlmFinishReason.LENGTH || fr == LlmFinishReason.TOOL_CALLS) {
                    persistSuccess(window, result, fr, start);
                    return toResponse(result, fr, start);
                }
                if (fr == LlmFinishReason.CONTENT_FILTER) {
                    throw new LlmException(nvl(result.errorMessage, "LLM content filter"),
                            null, LlmFinishReason.CONTENT_FILTER, result.httpStatus, profile.name, convId());
                }
                if (fr == LlmFinishReason.CONTEXT_OVERFLOW) {
                    throw new LlmException(nvl(result.errorMessage, "LLM context length exceeded"),
                            null, LlmFinishReason.CONTEXT_OVERFLOW, result.httpStatus, profile.name, convId());
                }
                if (fr == LlmFinishReason.EMPTY) {
                    if (emptyAttempts < profile.emptyRetries) {
                        emptyAttempts++;
                        sleepBackoff(waitSeconds);
                        waitSeconds = nextWait(waitSeconds);
                        continue;
                    }
                    throw new LlmException("LLM returned empty content after " + profile.emptyRetries + " retries",
                            null, LlmFinishReason.EMPTY, result.httpStatus, profile.name, convId());
                }

                boolean retryable = result.retryable;
                if (retryable && errorAttempts < profile.retryMax) {
                    errorAttempts++;
                    sleepBackoff(waitSeconds);
                    waitSeconds = nextWait(waitSeconds);
                    continue;
                }
                throw new LlmException(nvl(result.errorMessage, "LLM call failed"),
                        null, LlmFinishReason.ERROR, result.httpStatus, profile.name, convId());
            }
        } catch (Throwable t) {
            cancelled = LlmConversationImpl.isCancelThrowable(t);
            if (streamingWasPersisted && conversation != null) {
                try { persistFailure(lastResult, start, t); }
                catch (Throwable persistErr) {
                    logger.error("Error persisting LLM Failed/Cancelled for conversation " + convId(), persistErr);
                }
            }
            if (t instanceof ArtifactAuthorizationException) throw (ArtifactAuthorizationException) t;
            if (t instanceof ArtifactTarpitException) throw (ArtifactTarpitException) t;
            if (t instanceof LlmException) throw (LlmException) t;
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new LlmException("LLM call failed: " + t.getMessage(), t,
                    LlmFinishReason.ERROR, 0, profile.name, convId());
        } finally {
            // DB is source of truth: if this turn persisted Streaming and did not Complete, re-read
            // the header and Failed/Cancelled if still Streaming (memory may already have been mutated).
            if (streamingWasPersisted && conversation != null
                    && !LlmConversationImpl.STATUS_COMPLETE.equals(conversation.getStatus())) {
                try { conversation.repairTerminalIfStreaming(cancelled); }
                catch (Throwable persistErr) {
                    logger.error("Error repairing LLM Streaming status for conversation " + convId(), persistErr);
                }
            }
            if (aefi != null && aei != null) aefi.pop(aei);
        }
    }

    private void persistSuccess(List<LlmMessage> window, ProtocolResult result, LlmFinishReason fr, long start) {
        if (conversation == null) return;
        conversation.persistIsolated(() -> {
            LlmMessage asst = LlmMessage.assistant(result.content);
            asst.toolCalls = result.toolCalls;
            conversation.appendInternal(asst);
            conversation.writeCallLog(profile.name, profile.protocol != null ? profile.protocol.getName() : null,
                    result.model != null ? result.model : resolveModel(), profile.logContent,
                    window, result, System.currentTimeMillis() - start, 1, false);
            conversation.setStatusInternal(LlmConversationImpl.STATUS_COMPLETE);
        });
    }

    private void persistFailure(ProtocolResult result, long start, Throwable t) {
        if (conversation == null) return;
        boolean cancelled = LlmConversationImpl.isCancelThrowable(t);
        String status = cancelled ? LlmConversationImpl.STATUS_CANCELLED : LlmConversationImpl.STATUS_FAILED;
        conversation.persistIsolated(() -> {
            if (result != null) {
                conversation.writeCallLog(profile.name, profile.protocol != null ? profile.protocol.getName() : null,
                        result.model != null ? result.model : resolveModel(), profile.logContent,
                        null, result, System.currentTimeMillis() - start, 1, true);
            }
            conversation.setStatusInternal(status);
        });
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
        if (conversation != null) {
            WindowPolicy policy = windowPolicy != null ? windowPolicy : conversation.getWindowPolicy();
            List<LlmMessage> window = conversation.buildWindow(policy);
            for (LlmMessage extra : extraMessages) {
                if (extra == null || extra.role == LlmMessage.Role.SYSTEM) continue;
                window.add(extra);
            }
            return window;
        }
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
        r.conversationId = convId();
        r.httpStatus = result.httpStatus;
        r.errorMessage = result.errorMessage;
        r.providerErrorCode = result.providerErrorCode;
        r.durationMs = System.currentTimeMillis() - start;
        r.rawJson = profile.logContent ? result.rawJson : null;
        r.yielded = false;
        return r;
    }

    private void applyWindowPolicyToConversation() {
        if (conversation != null && windowPolicy != null) conversation.setWindowPolicy(windowPolicy);
    }

    private String convId() { return conversation != null ? conversation.getConversationId() : null; }

    private void sleepBackoff(float waitSeconds) {
        if (waitSeconds <= 0) return;
        try {
            Thread.sleep(Math.round(waitSeconds * 1000.0f));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM retry sleep interrupted", e, LlmFinishReason.ERROR, 0, profile.name, convId());
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
