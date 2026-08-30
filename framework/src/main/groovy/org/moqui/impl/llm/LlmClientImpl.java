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
import org.moqui.llm.LlmProtocol.ProtocolStreamListener;
import org.moqui.llm.LlmResponse;
import org.moqui.llm.LlmStreamListener;
import org.moqui.llm.LlmTool;
import org.moqui.llm.LlmToolCall;
import org.moqui.llm.LlmToolResult;
import org.moqui.llm.WindowPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

public class LlmClientImpl implements LlmClient {
    private static final Logger logger = LoggerFactory.getLogger(LlmClientImpl.class);
    static final int DEFAULT_MAX_ITERATIONS = 8;
    static final int DEFAULT_TOOL_RESULT_MAX_CHARS = 8000;

    final ExecutionContext ec;
    final LlmFacadeImpl.ProfileState profile;
    private final BooleanSupplier transactionInPlace;

    String systemContent = null;
    final List<LlmMessage> extraMessages = new ArrayList<>();
    final List<LlmMessage> userMessages = new ArrayList<>();
    private String modelOverride = null;
    private Double temperature = null;
    private Integer maxTokens = null;
    private Integer timeoutSeconds = null;
    private Map<String, Object> extraBody = null;
    LlmConversationImpl conversation = null;
    private WindowPolicy windowPolicy = null;
    final List<LlmTool> tools = new ArrayList<>();
    final List<LlmToolResult> resumeToolResults = new ArrayList<>();
    boolean allowClientTools = false;
    private final Set<String> allowedEntities = new LinkedHashSet<>();
    private final List<LlmFacadeImpl.AllowedPath> allowedPaths = new ArrayList<>();
    private Integer maxIterations = null;
    private int toolResultMaxChars = DEFAULT_TOOL_RESULT_MAX_CHARS;
    private boolean streamingWasPersisted = false;

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
        if (profile.allowedEntities != null) this.allowedEntities.addAll(profile.allowedEntities);
        if (profile.confNode != null) {
            this.maxIterations = LlmFacadeImpl.parseInteger(profile.confNode.attribute("max-iterations"));
            int tr = LlmFacadeImpl.parseInt(profile.confNode.attribute("tool-result-max-chars"), DEFAULT_TOOL_RESULT_MAX_CHARS);
            if (tr > 0) this.toolResultMaxChars = tr;
        }
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
    @Override
    public LlmClient tool(LlmTool tool) {
        if (tool != null) {
            applyAllowLists(tool);
            tools.add(tool);
        }
        return this;
    }
    @Override
    public LlmClient tools(List<LlmTool> more) {
        if (more != null) for (LlmTool t : more) tool(t);
        return this;
    }
    @Override
    public LlmClient toolResults(List<LlmToolResult> results) {
        if (results != null) {
            for (LlmToolResult r : results) if (r != null) resumeToolResults.add(r);
        }
        return this;
    }
    @Override
    public LlmClient allowClientTools(boolean allow) {
        this.allowClientTools = allow;
        return this;
    }
    @Override
    public LlmClient allowedEntity(String entityName) {
        if (entityName != null && !entityName.isBlank()) {
            allowedEntities.add(entityName);
            for (LlmTool t : tools) {
                if (t instanceof WriteUiTool) ((WriteUiTool) t).addAllowedEntity(entityName);
            }
        }
        return this;
    }
    @Override
    public LlmClient allowedPath(String prefix, String methodsCsv) {
        if (prefix != null && !prefix.isBlank()) {
            LlmFacadeImpl.AllowedPath ap = new LlmFacadeImpl.AllowedPath(prefix, methodsCsv);
            allowedPaths.add(ap);
            for (LlmTool t : tools) {
                if (t instanceof RequestTool) ((RequestTool) t).addAllowedPath(prefix, methodsCsv);
            }
        }
        return this;
    }
    @Override
    public LlmClient maxIterations(int n) {
        if (n < 1) throw new LlmException("maxIterations must be >= 1");
        this.maxIterations = n;
        return this;
    }
    @Override
    public LlmClient windowPolicy(WindowPolicy policy) {
        this.windowPolicy = policy != null ? policy : new WindowPolicy();
        applyWindowPolicyToConversation();
        return this;
    }

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
    public LlmResponse stream(LlmStreamListener listener) {
        if (listener == null) throw new IllegalArgumentException("LlmStreamListener is required");
        failFastIfTransaction();

        String model = requireModel();
        List<LlmMessage> window = buildWindow();
        ProtocolRequest req = buildRequest(model, window);
        req.stream = true;
        long start = System.currentTimeMillis();

        ProtocolResult[] resultBox = new ProtocolResult[1];
        Throwable[] failBox = new Throwable[1];
        try {
            profile.protocol.chatStream(req, new ProtocolStreamListener() {
                @Override public void onDelta(String textDelta) {
                    if (textDelta != null && !textDelta.isEmpty()) listener.onDelta(textDelta);
                }
                @Override public void onComplete(ProtocolResult result) { resultBox[0] = result; }
                @Override public void onFailure(Throwable t) { failBox[0] = t; }
            });
        } catch (LlmException e) {
            listener.onError(e);
            throw e;
        } catch (RuntimeException e) {
            listener.onFailure(e);
            throw new LlmException("LLM protocol stream failed: " + e.getMessage(), e,
                    LlmFinishReason.ERROR, 0, profile.name);
        }

        if (failBox[0] != null) {
            listener.onFailure(failBox[0]);
            throw new LlmException("LLM stream failed: " + failBox[0].getMessage(), failBox[0],
                    LlmFinishReason.ERROR, 0, profile.name);
        }
        ProtocolResult result = resultBox[0];
        if (result == null) {
            LlmException le = new LlmException("LLM protocol returned no stream result",
                    LlmFinishReason.ERROR, 0, profile.name);
            listener.onError(le);
            throw le;
        }
        return finishStreamResult(listener, result, start);
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
        streamingWasPersisted = false;
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
            if (!tools.isEmpty() || !resumeToolResults.isEmpty()) {
                return new LlmAgentLoop(this).run(start);
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
            // MAX_ITERATIONS already wrote Active so the caller can continue; do not overwrite with Failed.
            if (streamingWasPersisted && conversation != null && !isMaxIterations(t)) {
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
        if (isMaxIterations(t)) return;
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

    private LlmResponse finishStreamResult(LlmStreamListener listener, ProtocolResult result, long start) {
        LlmFinishReason fr = result.finishReason != null ? result.finishReason : LlmFinishReason.ERROR;
        if (fr == LlmFinishReason.STOP || fr == LlmFinishReason.LENGTH || fr == LlmFinishReason.TOOL_CALLS) {
            LlmResponse r = toResponse(result, fr, start);
            if (r.toolCalls != null) {
                for (LlmToolCall tc : r.toolCalls) {
                    if (tc != null) listener.onToolCall(tc, LlmTool.Execution.SERVER);
                }
            }
            listener.onComplete(r);
            return r;
        }
        LlmException le;
        if (fr == LlmFinishReason.CONTENT_FILTER) {
            le = new LlmException(nvl(result.errorMessage, "LLM content filter"),
                    LlmFinishReason.CONTENT_FILTER, result.httpStatus, profile.name);
        } else if (fr == LlmFinishReason.CONTEXT_OVERFLOW) {
            le = new LlmException(nvl(result.errorMessage, "LLM context length exceeded"),
                    LlmFinishReason.CONTEXT_OVERFLOW, result.httpStatus, profile.name);
        } else if (fr == LlmFinishReason.EMPTY) {
            le = new LlmException("LLM returned empty content", LlmFinishReason.EMPTY, result.httpStatus, profile.name);
        } else {
            le = new LlmException(nvl(result.errorMessage, "LLM call failed"),
                    LlmFinishReason.ERROR, result.httpStatus, profile.name);
        }
        listener.onError(le);
        throw le;
    }

    private void failFastIfTransaction() {
        if (isTransactionInPlace() && !profile.allowTxOverHttp) {
            throw new LlmException(
                    "Cannot call LLM while a JTA transaction is active (default TX timeout 60s vs LLM timeout "
                            + (timeoutSeconds != null ? timeoutSeconds : profile.timeoutSeconds)
                            + "s). Commit first, or set allow-tx-over-http=true.",
                    LlmFinishReason.ERROR, 0, profile.name);
        }
    }

    private boolean isTransactionInPlace() {
        if (transactionInPlace != null) return transactionInPlace.getAsBoolean();
        if (ec == null || ec.getTransaction() == null) return false;
        return ec.getTransaction().isTransactionInPlace();
    }

    private String requireModel() {
        String model = resolveModel();
        if (model == null || model.isBlank()) {
            throw new LlmException("profile '" + profile.name + "' has no model",
                    LlmFinishReason.ERROR, 0, profile.name);
        }
        return model;
    }

    String resolveModel() {
        if (modelOverride != null && !modelOverride.isBlank()) return modelOverride;
        if (profile.model != null && !profile.model.isBlank()) return profile.model;
        if (extraBody != null && extraBody.get("model") != null) {
            String m = extraBody.get("model").toString();
            if (m != null && !m.isBlank()) return m;
        }
        return null;
    }

    List<LlmMessage> buildWindow() {
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

    ProtocolRequest buildRequest(String model, List<LlmMessage> window) {
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
        if (!tools.isEmpty()) req.tools = tools;
        return req;
    }

    LlmResponse toResponse(ProtocolResult result, LlmFinishReason fr, long start) {
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

    String convId() { return conversation != null ? conversation.getConversationId() : null; }

    void sleepBackoff(float waitSeconds) {
        if (waitSeconds <= 0) return;
        try {
            Thread.sleep(Math.round(waitSeconds * 1000.0f));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("LLM retry sleep interrupted", e, LlmFinishReason.ERROR, 0, profile.name, convId());
        }
    }

    float nextWait(float waitSeconds) {
        float initial = profile.retryInitialSeconds > 0 ? profile.retryInitialSeconds : 2.0f;
        if (waitSeconds <= 0) return initial;
        return waitSeconds * initial;
    }

    private static String nvl(String v, String def) { return v == null || v.isBlank() ? def : v; }

    static boolean isMaxIterations(Throwable t) {
        return t instanceof LlmException && ((LlmException) t).getReason() == LlmFinishReason.MAX_ITERATIONS;
    }

    private static UnsupportedOperationException uoe(String method) {
        return new UnsupportedOperationException("LlmClient." + method + " is not yet implemented");
    }

    void markStreamingPersisted() { this.streamingWasPersisted = true; }

    boolean hasResumeResults() { return !resumeToolResults.isEmpty(); }

    int maxIterationsEffective() {
        if (maxIterations != null && maxIterations > 0) return maxIterations;
        return DEFAULT_MAX_ITERATIONS;
    }

    LlmTool findTool(String name) {
        if (name == null) return null;
        for (LlmTool t : tools) {
            if (t != null && name.equals(t.getName())) return t;
        }
        return null;
    }

    Object truncateResult(Object result) {
        if (result == null) return null;
        String json = result instanceof String ? (String) result : LlmJson.toJson(result);
        if (json == null || json.length() <= toolResultMaxChars) return result;
        Map<String, Object> truncated = new LinkedHashMap<>();
        truncated.put("truncated", true);
        truncated.put("preview", json.substring(0, toolResultMaxChars));
        truncated.put("size", json.length());
        if (result instanceof Map) {
            Object status = ((Map<?, ?>) result).get("status");
            if (status != null) truncated.put("status", status);
        }
        return truncated;
    }

    private void applyAllowLists(LlmTool tool) {
        if (tool instanceof RequestTool) {
            RequestTool rt = (RequestTool) tool;
            for (LlmFacadeImpl.AllowedPath ap : allowedPaths) rt.addAllowedPath(ap.prefix, ap.methodsCsv);
        } else if (tool instanceof WriteUiTool) {
            WriteUiTool wt = (WriteUiTool) tool;
            for (String ent : allowedEntities) wt.addAllowedEntity(ent);
        }
    }
}
