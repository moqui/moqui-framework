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

import org.moqui.impl.llm.LlmClientImpl
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.LlmRetryClassifier
import org.moqui.impl.llm.OpenAiCompatProtocol
import org.moqui.llm.LlmClient
import org.moqui.llm.LlmException
import org.moqui.llm.LlmFinishReason
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmProtocol
import org.moqui.llm.LlmProtocol.ProtocolRequest
import org.moqui.llm.LlmResponse
import org.moqui.llm.LlmStreamListener
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmToolCall
import org.moqui.llm.test.FakeLlmProtocol
import org.moqui.util.MNode
import org.moqui.util.RestClient
import spock.lang.IgnoreIf
import spock.lang.Specification

class LlmClientTests extends Specification {

    private static LlmClientImpl client(FakeLlmProtocol proto, String model = "test-model",
            boolean allowTx = false, Closure<Boolean> tx = { false }) {
        def profile = LlmFacadeImpl.ProfileState.forTest("default", proto, model, allowTx, 2, 0f, 5)
        return new LlmClientImpl(null, profile, tx)
    }

    // ========== URL composition ==========

    def "origin url appends default chat completions path"() {
        expect:
        OpenAiCompatProtocol.composeEndpointUrl("https://api.openai.com", null, null) ==
                "https://api.openai.com/v1/chat/completions"
        OpenAiCompatProtocol.composeEndpointUrl("https://api.openai.com/", null, null) ==
                "https://api.openai.com/v1/chat/completions"
        OpenAiCompatProtocol.composeEndpointUrl("http://127.0.0.1:11434", "/v1/chat/completions", null) ==
                "http://127.0.0.1:11434/v1/chat/completions"
    }

    def "origin url appends custom path and extra query"() {
        when:
        String url = OpenAiCompatProtocol.composeEndpointUrl("https://myres.openai.azure.com",
                "/openai/deployments/gpt4o/chat/completions", [ "api-version": "2024-10-21" ])
        then:
        url == "https://myres.openai.azure.com/openai/deployments/gpt4o/chat/completions?api-version=2024-10-21"
    }

    def "full endpoint url does not append default path"() {
        expect:
        OpenAiCompatProtocol.composeEndpointUrl(
                "https://myres.openai.azure.com/openai/deployments/gpt4o/chat/completions",
                "/v1/chat/completions", null) ==
                "https://myres.openai.azure.com/openai/deployments/gpt4o/chat/completions"
    }

    def "full endpoint with query keeps query and appends missing extra query only"() {
        when:
        String url = OpenAiCompatProtocol.composeEndpointUrl(
                "https://myres.openai.azure.com/openai/deployments/gpt4o/chat/completions?api-version=2024-10-21",
                "/v1/chat/completions", [ "api-version": "ignore-me", "foo": "bar" ])
        then:
        url == "https://myres.openai.azure.com/openai/deployments/gpt4o/chat/completions?api-version=2024-10-21&foo=bar"
    }

    // ========== Auth header pattern (raw ${api-key}, not SystemBinding) ==========

    def "Azure auth-header-pattern is the raw api-key with no Bearer"() {
        expect:
        LlmFacadeImpl.resolveAuthHeaderValue('${api-key}', 'sk-azure') == 'sk-azure'
    }

    def "omitted auth-header-pattern is Bearer plus key"() {
        expect:
        LlmFacadeImpl.resolveAuthHeaderValue(null, 'sk-oa') == 'Bearer sk-oa'
        LlmFacadeImpl.resolveAuthHeaderValue('', 'sk-oa') == 'Bearer sk-oa'
    }

    def "explicit Bearer api-key pattern keeps Bearer prefix"() {
        expect:
        LlmFacadeImpl.resolveAuthHeaderValue('Bearer ${api-key}', 'sk-oa') == 'Bearer sk-oa'
    }

    def "blank api-key omits auth header value"() {
        expect:
        LlmFacadeImpl.resolveAuthHeaderValue('Bearer ${api-key}', '') == null
        LlmFacadeImpl.resolveAuthHeaderValue('${api-key}', '  ') == null
        LlmFacadeImpl.resolveAuthHeaderValue(null, null) == null
    }

    def "raw auth-header-pattern is used even after setSystemExpandAttributes"() {
        given:
        MNode azure = new MNode("profile", ["auth-header-name": "api-key", "auth-header-pattern": '${api-key}'])
        azure.setSystemExpandAttributes(true)
        MNode openai = new MNode("profile", ["auth-header-pattern": 'Bearer ${api-key}'])
        openai.setSystemExpandAttributes(true)
        MNode omitted = new MNode("profile", ["name": "default"])
        omitted.setSystemExpandAttributes(true)
        when:
        String azureVal = LlmFacadeImpl.resolveAuthHeaderValue(azure.getAttributes().get("auth-header-pattern"), "sk-azure")
        String openaiVal = LlmFacadeImpl.resolveAuthHeaderValue(openai.getAttributes().get("auth-header-pattern"), "sk-oa")
        String omittedVal = LlmFacadeImpl.resolveAuthHeaderValue(omitted.getAttributes().get("auth-header-pattern"), "sk-oa")
        then:
        azureVal == "sk-azure"
        openaiVal == "Bearer sk-oa"
        omittedVal == "Bearer sk-oa"
    }

    def "applyAuthAndHeaders: Azure api-key has no Bearer; blank key adds no header"() {
        when:
        ProtocolRequest azure = new ProtocolRequest()
        azure.apiKey = "sk-azure"
        azure.authHeaderName = "api-key"
        azure.authHeaderValue = LlmFacadeImpl.resolveAuthHeaderValue('${api-key}', "sk-azure")
        Map azureHdrs = OpenAiCompatProtocol.authHeaders(azure)

        ProtocolRequest omitted = new ProtocolRequest()
        omitted.apiKey = "sk-oa"
        omitted.authHeaderName = "Authorization"
        omitted.authHeaderValue = LlmFacadeImpl.resolveAuthHeaderValue(null, "sk-oa")
        Map omittedHdrs = OpenAiCompatProtocol.authHeaders(omitted)

        ProtocolRequest explicit = new ProtocolRequest()
        explicit.apiKey = "sk-oa"
        explicit.authHeaderName = "Authorization"
        explicit.authHeaderValue = LlmFacadeImpl.resolveAuthHeaderValue('Bearer ${api-key}', "sk-oa")
        Map explicitHdrs = OpenAiCompatProtocol.authHeaders(explicit)

        ProtocolRequest blank = new ProtocolRequest()
        blank.apiKey = ""
        blank.authHeaderName = "Authorization"
        blank.authHeaderValue = LlmFacadeImpl.resolveAuthHeaderValue('Bearer ${api-key}', "")
        Map blankHdrs = OpenAiCompatProtocol.authHeaders(blank)

        then:
        azureHdrs.size() == 1
        azureHdrs["api-key"] == "sk-azure"
        !azureHdrs["api-key"].contains("Bearer")
        omittedHdrs["Authorization"] == "Bearer sk-oa"
        explicitHdrs["Authorization"] == "Bearer sk-oa"
        blankHdrs.isEmpty()
    }

    def "redactHeaders includes custom auth-header-name"() {
        given:
        ProtocolRequest req = new ProtocolRequest()
        req.authHeaderName = "X-GOOG-API-KEY"
        when:
        String[] names = OpenAiCompatProtocol.redactHeaderNames(req)
        then:
        "Authorization" in names
        "api-key" in names
        "x-api-key" in names
        "X-GOOG-API-KEY" in names
    }

    // ========== Layer B classification order ==========

    def "context overflow is classified before empty"() {
        when:
        def r = LlmRetryClassifier.classify(400,
                '{"error":{"code":"context_length_exceeded","message":"This model\'s maximum context length is 8k tokens"}}')
        then:
        r.finishReason == LlmFinishReason.CONTEXT_OVERFLOW
        !r.retryable
    }

    def "HTTP 413 with too many tokens message is context overflow"() {
        when:
        def r = LlmRetryClassifier.classify(413, '{"message":"too many tokens in the request"}')
        then:
        r.finishReason == LlmFinishReason.CONTEXT_OVERFLOW
    }

    def "content_filter on HTTP 400 is not empty-retried even with empty choices"() {
        when:
        def r = LlmRetryClassifier.classify(400,
                '{"error":{"code":"content_filter","message":"blocked"},"choices":[]}')
        then:
        r.finishReason == LlmFinishReason.CONTENT_FILTER
    }

    def "invalid_prompt is content filter"() {
        when:
        def r = LlmRetryClassifier.classify(400, '{"error":{"code":"invalid_prompt","message":"nope"}}')
        then:
        r.finishReason == LlmFinishReason.CONTENT_FILTER
    }

    def "finish_reason content_filter with blank content is not EMPTY"() {
        when:
        def r = LlmRetryClassifier.classify(200,
                '{"choices":[{"finish_reason":"content_filter","message":{"content":""}}]}')
        then:
        r.finishReason == LlmFinishReason.CONTENT_FILTER
    }

    def "finish_reason length with empty content is LENGTH not EMPTY"() {
        when:
        def r = LlmRetryClassifier.classify(200,
                '{"choices":[{"finish_reason":"length","message":{"content":""}}]}')
        then:
        r.finishReason == LlmFinishReason.LENGTH
        r.content == ""
    }

    def "finish_reason length keeps partial content"() {
        when:
        def r = LlmRetryClassifier.classify(200,
                '{"choices":[{"finish_reason":"length","message":{"content":"Hello "}}]}')
        then:
        r.finishReason == LlmFinishReason.LENGTH
        r.content == "Hello "
    }

    def "finish_reason tool_calls is TOOL_CALLS"() {
        when:
        def r = LlmRetryClassifier.classify(200,
                '{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"request","arguments":"{}"}}]}}]}')
        then:
        r.finishReason == LlmFinishReason.TOOL_CALLS
        r.toolCalls.size() == 1
        r.toolCalls[0].name == "request"
    }

    def "finish_reason stop with content is STOP"() {
        when:
        def r = LlmRetryClassifier.classify(200,
                '{"choices":[{"finish_reason":"stop","message":{"content":"hello"}}],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}')
        then:
        r.finishReason == LlmFinishReason.STOP
        r.content == "hello"
        r.usage.totalTokens == 4
    }

    def "empty choices is EMPTY"() {
        when:
        def r = LlmRetryClassifier.classify(200, '{"choices":[]}')
        then:
        r.finishReason == LlmFinishReason.EMPTY
    }

    def "HTTP 200 JSON rate-limit is retryable ERROR not EMPTY"() {
        when:
        def r = LlmRetryClassifier.classify(200,
                '{"error":{"type":"rate_limit_exceeded","message":"Rate limit reached"}}')
        then:
        r.finishReason == LlmFinishReason.ERROR
        r.retryable
    }

    def "HTTP 500 is retryable ERROR"() {
        when:
        def r = LlmRetryClassifier.classify(500, '{"error":{"message":"boom"}}')
        then:
        r.finishReason == LlmFinishReason.ERROR
        r.retryable
    }

    def "HTTP 401 is not retryable"() {
        when:
        def r = LlmRetryClassifier.classify(401, '{"error":{"message":"invalid api key"}}')
        then:
        r.finishReason == LlmFinishReason.ERROR
        !r.retryable
    }

    // ========== message conversion ==========

    def "CONTEXT role is emitted as untrusted user wrapper"() {
        when:
        def converted = OpenAiCompatProtocol.convertMessages([
                LlmMessage.system("sys"),
                LlmMessage.context("entity:Party:1", "Acme Corp"),
                LlmMessage.user("hi")
        ])
        then:
        converted.size() == 3
        converted[0].role == "system"
        converted[1].role == "user"
        converted[1].content == '<untrusted-context source="entity:Party:1">Acme Corp</untrusted-context>'
        converted[2].role == "user"
        converted[2].content == "hi"
    }

    def "max tokens omitted when unset and uses max_completion_tokens when configured"() {
        when:
        ProtocolRequest req = new ProtocolRequest()
        req.model = "m"
        req.window = [LlmMessage.user("x")]
        Map body = OpenAiCompatProtocol.buildRequestBody(req)
        then:
        !body.containsKey("max_tokens")
        !body.containsKey("max_completion_tokens")
        body.stream == false

        when:
        req.maxTokens = 64
        req.maxTokensParameter = "max_completion_tokens"
        Map body2 = OpenAiCompatProtocol.buildRequestBody(req)
        then:
        body2.max_completion_tokens == 64
        !body2.containsKey("max_tokens")
    }

    def "stream=true body includes stream_options.include_usage"() {
        when:
        ProtocolRequest req = new ProtocolRequest()
        req.model = "m"
        req.window = [LlmMessage.user("x")]
        req.stream = true
        Map body = OpenAiCompatProtocol.buildRequestBody(req)
        then:
        body.stream == true
        body.stream_options instanceof Map
        body.stream_options.include_usage == true
    }

    // ========== LlmClient.call with Fake protocol ==========

    def "call returns STOP content from FakeLlmProtocol"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [FakeLlmProtocol.stop("classified")]
        when:
        LlmResponse r = client(proto).system("classify").user("ticket text").call()
        then:
        r.content == "classified"
        r.finishReason == LlmFinishReason.STOP
        proto.chatCount == 1
        proto.lastRequest.model == "test-model"
        proto.lastRequest.authHeaderValue == null
        proto.lastRequest.window.size() == 2
        proto.lastRequest.window[0].role == LlmMessage.Role.SYSTEM
        proto.lastRequest.window[1].role == LlmMessage.Role.USER
    }

    def "call throws if neither profile nor builder set a model"() {
        given:
        def proto = new FakeLlmProtocol(failIfInvoked: true)
        when:
        client(proto, "").user("hi").call()
        then:
        LlmException e = thrown()
        e.message.contains("has no model")
        proto.chatCount == 0
    }

    def "builder model overrides empty profile model"() {
        given:
        def proto = new FakeLlmProtocol()
        when:
        LlmResponse r = client(proto, "").model("gpt-test").user("hi").call()
        then:
        r.finishReason == LlmFinishReason.STOP
        proto.lastRequest.model == "gpt-test"
    }

    def "call with open TX throws before any protocol HTTP"() {
        given:
        def proto = new FakeLlmProtocol(failIfInvoked: true)
        when:
        client(proto, "test-model", false, { true }).user("hi").call()
        then:
        LlmException e = thrown()
        e.message.toLowerCase().contains("transaction")
        proto.chatCount == 0
    }

    def "call with open TX allowed when allow-tx-over-http"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [FakeLlmProtocol.stop("ok")]
        when:
        LlmResponse r = client(proto, "test-model", true, { true }).user("hi").call()
        then:
        r.content == "ok"
        proto.chatCount == 1
    }

    def "EMPTY is retried up to empty-retries then throws without treating filter as empty"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [FakeLlmProtocol.empty(), FakeLlmProtocol.empty(), FakeLlmProtocol.empty()]
        when:
        client(proto).user("hi").call()
        then:
        LlmException e = thrown()
        e.reason == LlmFinishReason.EMPTY
        proto.chatCount == 3
    }

    def "EMPTY then STOP succeeds after retry"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [FakeLlmProtocol.empty(), FakeLlmProtocol.stop("later")]
        when:
        LlmResponse r = client(proto).user("hi").call()
        then:
        r.content == "later"
        proto.chatCount == 2
    }

    def "CONTENT_FILTER from protocol is not retried"() {
        given:
        def proto = new FakeLlmProtocol()
        def filtered = new LlmProtocol.ProtocolResult(LlmFinishReason.CONTENT_FILTER)
        filtered.httpStatus = 400
        proto.results = [filtered]
        when:
        client(proto).user("hi").call()
        then:
        LlmException e = thrown()
        e.reason == LlmFinishReason.CONTENT_FILTER
        proto.chatCount == 1
    }

    def "LENGTH returns partial content and does not retry"() {
        given:
        def proto = new FakeLlmProtocol()
        def length = new LlmProtocol.ProtocolResult(LlmFinishReason.LENGTH)
        length.content = "partial"
        length.httpStatus = 200
        proto.results = [length]
        when:
        LlmResponse r = client(proto).user("hi").call()
        then:
        r.finishReason == LlmFinishReason.LENGTH
        r.content == "partial"
        proto.chatCount == 1
    }

    def "unimplemented methods throw UnsupportedOperationException"() {
        given:
        LlmClient c = client(new FakeLlmProtocol())
        when: c.conversation("x")
        then: thrown(UnsupportedOperationException)
        when: c.newConversation()
        then: thrown(UnsupportedOperationException)
        when: c.injectContext("s", "c")
        then: thrown(UnsupportedOperationException)
        when: c.tool(null)
        then: thrown(UnsupportedOperationException)
        when: c.tools(null)
        then: thrown(UnsupportedOperationException)
        when: c.toolResults(null)
        then: thrown(UnsupportedOperationException)
        when: c.allowClientTools(true)
        then: thrown(UnsupportedOperationException)
        when: c.allowedEntity("x")
        then: thrown(UnsupportedOperationException)
        when: c.allowedPath("/rest/", "GET")
        then: thrown(UnsupportedOperationException)
        when: c.maxIterations(3)
        then: thrown(UnsupportedOperationException)
        when: c.windowPolicy(null)
        then: thrown(UnsupportedOperationException)
    }

    def "stream with null listener throws before protocol"() {
        given:
        def proto = new FakeLlmProtocol(failIfInvoked: true)
        when:
        client(proto).user("hi").stream(null)
        then:
        thrown(IllegalArgumentException)
        proto.chatStreamCount == 0
    }

    def "stream returns STOP content and forwards deltas from FakeLlmProtocol"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [FakeLlmProtocol.stop("Hello")]
        proto.streamDeltas = ["Hel", "lo"]
        def listener = new CollectingListener()
        when:
        LlmResponse r = client(proto).system("sys").user("hi").stream(listener)
        then:
        r.content == "Hello"
        r.finishReason == LlmFinishReason.STOP
        listener.deltas == ["Hel", "lo"]
        listener.complete?.content == "Hello"
        listener.failure == null
        proto.chatStreamCount == 1
        proto.lastRequest.stream
        proto.lastRequest.model == "test-model"
    }

    def "stream fail-fast on open TX before any protocol HTTP"() {
        given:
        def proto = new FakeLlmProtocol(failIfInvoked: true)
        when:
        client(proto, "test-model", false, { true }).user("hi").stream(new CollectingListener())
        then:
        LlmException e = thrown()
        e.message.toLowerCase().contains("transaction")
        proto.chatStreamCount == 0
        proto.chatCount == 0
    }

    def "stream does not retry on drop"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.streamDeltas = ["Hel"]
        proto.streamFailure = new IOException("connection reset")
        proto.results = [FakeLlmProtocol.stop("Hello"), FakeLlmProtocol.stop("retry-would-be-wrong")]
        def listener = new CollectingListener()
        when:
        client(proto).user("hi").stream(listener)
        then:
        LlmException e = thrown()
        e.reason == LlmFinishReason.ERROR
        listener.deltas == ["Hel"]
        listener.failure instanceof IOException
        listener.complete == null
        proto.chatStreamCount == 1
    }

    def "stream assembles tool_calls onto the complete response and onToolCall"() {
        given:
        def proto = new FakeLlmProtocol()
        def tcResult = new LlmProtocol.ProtocolResult(LlmFinishReason.TOOL_CALLS)
        tcResult.httpStatus = 200
        tcResult.toolCalls = [new LlmToolCall("call_1", "request", '{"method":"GET"}')]
        proto.results = [tcResult]
        def listener = new CollectingListener()
        when:
        LlmResponse r = client(proto).user("hi").stream(listener)
        then:
        r.finishReason == LlmFinishReason.TOOL_CALLS
        r.toolCalls.size() == 1
        r.toolCalls[0].id == "call_1"
        r.toolCalls[0].arguments == '{"method":"GET"}'
        listener.toolCalls.size() == 1
        listener.toolCalls[0].name == "request"
        listener.complete?.finishReason == LlmFinishReason.TOOL_CALLS
        proto.chatStreamCount == 1
    }

    static class CollectingListener implements LlmStreamListener {
        List<String> deltas = []
        List<LlmToolCall> toolCalls = []
        LlmResponse complete
        Throwable failure
        LlmException error
        @Override void onDelta(String textDelta) { deltas.add(textDelta) }
        @Override void onToolCall(LlmToolCall call, LlmTool.Execution execution) {
            toolCalls.add(call)
        }
        @Override void onComplete(LlmResponse response) { complete = response }
        @Override void onError(LlmException e) { error = e }
        @Override void onFailure(Throwable t) { failure = t }
    }

    def "getClient-style builders are distinct instances"() {
        given:
        def proto = new FakeLlmProtocol()
        def profile = LlmFacadeImpl.ProfileState.forTest("default", proto, "m", false, 2, 0f, 5)
        when:
        def a = new LlmClientImpl(null, profile)
        def b = new LlmClientImpl(null, profile)
        then:
        !a.is(b)
    }

    @IgnoreIf({
        def k = System.getenv("llm_openai_api_key") ?: System.getProperty("llm_openai_api_key")
        k == null || k.toString().trim().isEmpty()
    })
    def "optional live OpenAI-compatible call when llm_openai_api_key is set"() {
        given:
        String key = System.getenv("llm_openai_api_key") ?: System.getProperty("llm_openai_api_key")
        String url = System.getenv("llm_openai_url") ?: System.getProperty("llm_openai_url") ?: "https://api.openai.com"
        String model = System.getenv("llm_openai_model") ?: System.getProperty("llm_openai_model") ?: "gpt-4o-mini"
        String endpoint = OpenAiCompatProtocol.composeEndpointUrl(url, null, null)
        ProtocolRequest req = new ProtocolRequest()
        req.profileName = "live"
        req.endpointUrl = endpoint
        req.apiKey = key
        req.authHeaderName = "Authorization"
        req.authHeaderValue = "Bearer " + key
        req.model = model
        req.window = [LlmMessage.user("Reply with the single word pong.")]
        req.maxTokens = 8
        req.temperature = 0d
        req.timeoutSeconds = 60
        req.timeoutRetry = true
        req.retryMax = 2
        req.requestFactory = new RestClient.SimpleRequestFactory()
        when:
        def result = new OpenAiCompatProtocol().chat(req)
        then:
        result.finishReason == LlmFinishReason.STOP || result.finishReason == LlmFinishReason.LENGTH
        result.content != null && !result.content.isBlank()
        cleanup:
        req.requestFactory.destroy()
    }
}
