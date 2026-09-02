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
package org.moqui.llm;

import org.moqui.util.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public interface LlmProtocol {
    String getName();

    /** Blocking chat. Must not run the agent loop. Must not persist. */
    ProtocolResult chat(ProtocolRequest request);

    /** Streaming chat. Implementations that do not support streaming throw UOE. */
    void chatStream(ProtocolRequest request, ProtocolStreamListener listener);

    boolean supportsTools();
    boolean supportsStreaming();

    final class ProtocolRequest {
        public String profileName;
        public String endpointUrl;
        public String apiKey;
        public String authHeaderName;
        public String authHeaderValue;
        public Map<String, String> extraHeaders;
        public Map<String, String> extraQuery;
        public String model;
        public List<LlmMessage> window;
        public List<LlmTool> tools;
        public Double temperature;
        public Integer maxTokens;
        public String maxTokensParameter;
        public Integer timeoutSeconds;
        public boolean stream;
        public Map<String, Object> extraBody;
        public RestClient.RequestFactory requestFactory;
        public float retryInitialSeconds = 2.0f;
        public int retryMax = 5;
        public boolean timeoutRetry = true;
        public boolean logContent;
        /** Set by chatStream so /cancel and client disconnect can RestStream.close(). */
        public java.util.function.Consumer<RestClient.RestStream> onStreamOpen;
    }

    final class ProtocolResult {
        public String content;
        /** Provider thinking / reasoning_content when present (logged, not persisted). */
        public String reasoning;
        public LlmFinishReason finishReason;
        public String providerErrorCode;
        public List<LlmToolCall> toolCalls;
        public LlmUsage usage;
        public String model;
        public int httpStatus;
        public String errorMessage;
        public String rawJson;
        /** Layer B: retry this result (5xx or HTTP 200 rate-limit JSON). */
        public boolean retryable;

        public ProtocolResult() { }
        public ProtocolResult(LlmFinishReason finishReason) { this.finishReason = finishReason; }

        public List<LlmToolCall> getToolCalls() {
            return toolCalls != null ? toolCalls : new ArrayList<>();
        }
    }

    interface ProtocolStreamListener {
        void onDelta(String textDelta);
        void onComplete(ProtocolResult result);
        void onFailure(Throwable t);
    }
}
