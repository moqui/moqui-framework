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

import org.moqui.context.ArtifactExecutionFacade
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext
import org.moqui.context.UserFacade
import org.moqui.impl.llm.LlmClientImpl
import org.moqui.impl.llm.LlmConversationImpl
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
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmToolCall
import org.moqui.llm.LlmToolResult
import org.moqui.llm.WindowPolicy
import org.moqui.impl.llm.RequestTool
import org.moqui.impl.llm.ServiceCallTool
import org.moqui.impl.llm.WriteUiTool
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

    def "stream still throws UnsupportedOperationException"() {
        given:
        LlmClient c = client(new FakeLlmProtocol())
        when: c.stream(null)
        then: thrown(UnsupportedOperationException)
    }

    def "tool builder methods do not throw"() {
        given:
        LlmClient c = client(new FakeLlmProtocol())
        when:
        c.tool(org.moqui.llm.LlmTool.request())
                .tools(null)
                .toolResults(null)
                .allowClientTools(true)
                .allowedEntity("moqui.basic.Geo")
                .allowedPath("/rest/", "GET")
                .maxIterations(3)
        then:
        notThrown(UnsupportedOperationException)
    }

    // ========== conversations, context, window, call sequence ==========

    def "TX active with conversation throws before Streaming row"() {
        given:
        def proto = new FakeLlmProtocol(failIfInvoked: true)
        def conv = LlmConversationImpl.create(null, "default", null)
        when:
        client(proto, "test-model", false, { true }).conversation(conv).user("hi").call()
        then:
        LlmException e = thrown()
        e.message.toLowerCase().contains("transaction")
        proto.chatCount == 0
        conv.status == LlmConversationImpl.STATUS_ACTIVE
        conv.history.findAll { it.role == LlmMessage.Role.USER }.isEmpty()
    }

    def "Fake protocol throw after Streaming persists Failed"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.handler = { throw new RuntimeException("provider boom") }
        def conv = LlmConversationImpl.create(null, "default", null)
        when:
        client(proto).conversation(conv).user("hi").call()
        then:
        LlmException e = thrown()
        e.message.contains("provider boom")
        proto.chatCount == 1
        conv.status == LlmConversationImpl.STATUS_FAILED
        conv.history.find { it.role == LlmMessage.Role.USER }?.content == "hi"
        conv.history.find { it.role == LlmMessage.Role.ASSISTANT } == null
    }

    def "replaceSystem is SYSTEM and injectContext is CONTEXT not SYSTEM"() {
        given:
        def conv = LlmConversationImpl.create(null, "default", null)
        when:
        conv.replaceSystem("you are a classifier")
        conv.injectContext("entity:Party:1", "Acme Corp")
        conv.replaceSystem("you classify tickets")
        def hist = conv.history
        def systems = hist.findAll { it.role == LlmMessage.Role.SYSTEM }
        def contexts = hist.findAll { it.role == LlmMessage.Role.CONTEXT }
        then:
        systems.size() == 1
        systems[0].content == "you classify tickets"
        contexts.size() == 1
        contexts[0].content == "Acme Corp"
        contexts[0].metadata.source == "entity:Party:1"
        contexts[0].role == LlmMessage.Role.CONTEXT
    }

    def "buildWindow keeps system first and does not split tool pairs"() {
        given:
        def conv = LlmConversationImpl.create(null, "default", null)
        conv.replaceSystem("sys")
        conv.appendUser("old-user")
        LlmMessage asst = LlmMessage.assistant(null)
        asst.toolCalls = [new LlmToolCall("c1", "request", "{}")]
        conv.append(asst)
        conv.appendToolResult("c1", "request", "tool-out")
        def policy = new WindowPolicy()
        policy.maxMessages = 2
        policy.keepSystemFirst = true
        policy.keepToolPairs = true
        when:
        def window = conv.buildWindow(policy)
        then:
        window[0].role == LlmMessage.Role.SYSTEM
        window[0].content == "sys"
        // pair is atomic: dropping old-user leaves assistant+tool, never an orphan TOOL
        window.find { it.role == LlmMessage.Role.TOOL } != null
        window.find { it.role == LlmMessage.Role.ASSISTANT } != null
        int asstIdx = window.findIndexOf { it.role == LlmMessage.Role.ASSISTANT }
        int toolIdx = window.findIndexOf { it.role == LlmMessage.Role.TOOL }
        toolIdx == asstIdx + 1
        window.find { it.role == LlmMessage.Role.USER } == null
    }

    def "call with conversation persists user and assistant and returns Complete"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [FakeLlmProtocol.stop("classified")]
        def conv = LlmConversationImpl.create(null, "default", null)
        when:
        LlmResponse r = client(proto).conversation(conv).system("classify").user("ticket").call()
        then:
        r.content == "classified"
        r.conversationId == conv.conversationId
        conv.status == LlmConversationImpl.STATUS_COMPLETE
        conv.history[0].role == LlmMessage.Role.SYSTEM
        conv.history[0].content == "classify"
        conv.history[1].role == LlmMessage.Role.USER
        conv.history[2].role == LlmMessage.Role.ASSISTANT
        proto.lastRequest.window[0].role == LlmMessage.Role.SYSTEM
    }

    def "persistIsolated restores in-memory status if work throws before commit"() {
        given:
        def conv = LlmConversationImpl.create(null, "default", null)
        when:
        Exception ex = null
        try {
            conv.persistIsolated({
                conv.@statusId = LlmConversationImpl.STATUS_STREAMING
                throw new RuntimeException("persist boom")
            } as Runnable)
        } catch (RuntimeException e) { ex = e }
        then:
        ex != null
        conv.status == LlmConversationImpl.STATUS_ACTIVE
    }

    def "single-flight CAS throws 409 when already Streaming"() {
        given:
        def proto = new FakeLlmProtocol(failIfInvoked: true)
        def conv = LlmConversationImpl.create(null, "default", null)
        conv.@statusId = LlmConversationImpl.STATUS_STREAMING
        when:
        client(proto).conversation(conv).user("hi").call()
        then:
        LlmException e = thrown()
        e.httpStatus == 409
        proto.chatCount == 0
        conv.status == LlmConversationImpl.STATUS_STREAMING
    }

    def "AT_LLM authz runs before Streaming persist"() {
        given:
        def proto = new FakeLlmProtocol(failIfInvoked: true)
        def conv = LlmConversationImpl.create(null, "default", null)
        ArtifactExecutionFacade aefi = Stub(ArtifactExecutionFacade) {
            push(_ as String, _ as ArtifactExecutionInfo.ArtifactType, _ as ArtifactExecutionInfo.AuthzAction, _ as Boolean) >> {
                throw new IllegalStateException("no LLM")
            }
        }
        ExecutionContext eci = Stub(ExecutionContext) {
            getArtifactExecution() >> aefi
            getTransaction() >> null
        }
        def profile = LlmFacadeImpl.ProfileState.forTest("default", proto, "test-model", false, 2, 0f, 5)
        def c = new LlmClientImpl(eci, profile, { false })
        when:
        c.conversation(conv).user("hi").call()
        then:
        thrown(IllegalStateException)
        proto.chatCount == 0
        conv.status == LlmConversationImpl.STATUS_ACTIVE
        conv.history.find { it.role == LlmMessage.Role.USER } == null
    }

    def "K22 non-owner non-ADMIN cannot view"() {
        given:
        UserFacade user = Stub(UserFacade) {
            getUserId() >> "B"
            isInGroup("ADMIN") >> false
        }
        ExecutionContext eci = Stub(ExecutionContext) {
            getUser() >> user
        }
        when:
        LlmConversationImpl.checkCanView(eci, "A")
        then:
        LlmException e = thrown()
        e.httpStatus == 403
    }

    def "K22 owner or ADMIN can view"() {
        given:
        UserFacade owner = Stub(UserFacade) {
            getUserId() >> "A"
            isInGroup("ADMIN") >> false
        }
        UserFacade admin = Stub(UserFacade) {
            getUserId() >> "B"
            isInGroup("ADMIN") >> true
        }
        when:
        LlmConversationImpl.checkCanView(Stub(ExecutionContext) { getUser() >> owner }, "A")
        LlmConversationImpl.checkCanView(Stub(ExecutionContext) { getUser() >> admin }, "A")
        then:
        notThrown(LlmException)
    }

    def "newConversation and windowPolicy do not throw"() {
        given:
        LlmClient c = client(new FakeLlmProtocol())
        when:
        c.newConversation().windowPolicy(new WindowPolicy()).injectContext("s", "c")
        then:
        notThrown(UnsupportedOperationException)
    }

    def "request path rejects http://, //, and .. before render"() {
        given:
        boolean rendered = false
        RequestTool spy = new RequestTool() {
            @Override
            protected Map renderOnScreen(org.moqui.context.ExecutionContext ec, String method, List segments,
                    Map query, Map body) {
                rendered = true
                throw new AssertionError("ScreenRender should not run")
            }
        }
        expect:
        RequestTool.validatePath("http://evil") != null
        RequestTool.validatePath("//evil") != null
        RequestTool.validatePath("/foo/../bar") != null
        RequestTool.validatePath("/rest/s1/moqui") == null
        when:
        def http = spy.execute([method: "GET", path: "http://evil"], null)
        def slash = spy.execute([method: "GET", path: "//evil.host/x"], null)
        def dotdot = spy.execute([method: "GET", path: "/foo/../secret"], null)
        then:
        http.status == 400
        slash.status == 400
        dotdot.status == 400
        !rendered
    }

    def "allow-list rejects path before render"() {
        given:
        boolean rendered = false
        RequestTool spy = new RequestTool() {
            @Override
            protected Map renderOnScreen(org.moqui.context.ExecutionContext ec, String method, List segments,
                    Map query, Map body) {
                rendered = true
                throw new AssertionError("ScreenRender should not run")
            }
        }
        spy.addAllowedPath("/rest/s1/", "GET")
        when:
        def denied = spy.execute([method: "GET", path: "/qapps/mantle/Order"], null)
        def methodDenied = spy.execute([method: "POST", path: "/rest/s1/moqui/basic/geos"], null)
        then:
        denied.status == 403
        denied.text == RequestTool.PATH_NOT_ALLOWED
        methodDenied.status == 403
        !rendered
    }

    def "service function encoding distinguishes a.b#c vs a#b.c and long names need alias"() {
        expect:
        ServiceCallTool.encodeFunctionName("a.b#c") == "s_a_pb_nc"
        ServiceCallTool.encodeFunctionName("a#b.c") == "s_a_nb_pc"
        ServiceCallTool.encodeFunctionName("a.b#c") != ServiceCallTool.encodeFunctionName("a#b.c")
        when:
        String longName = "org.moqui.impl.ReallyQuiteLongServiceNameThatExceedsLimit.do#SomethingExtra"
        LlmTool.service(longName)
        then:
        thrown(IllegalArgumentException)
        when:
        LlmTool aliased = LlmTool.service("org.moqui.impl.LlmServices.clean#LlmData", "clean_llm")
        then:
        aliased.name == "clean_llm"
        aliased.execution == LlmTool.Execution.SERVER
    }

    def "malformed tool JSON and unknown name are tool errors not crashes"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [
                FakeLlmProtocol.toolCalls(new LlmToolCall("c1", "nope", "{not-json"),
                        new LlmToolCall("c2", "request", "{")),
                FakeLlmProtocol.stop("recovered")
        ]
        LlmTool server = new RecordingTool("request")
        when:
        LlmResponse r = client(proto).tool(server).user("hi").call()
        then:
        r.content == "recovered"
        r.finishReason == LlmFinishReason.STOP
        proto.chatCount == 2
        proto.lastRequest.window.find { it.role == LlmMessage.Role.TOOL && it.toolCallId == "c1" } != null
        proto.lastRequest.window.find { it.role == LlmMessage.Role.TOOL && it.content.contains("unknown") } != null
        proto.lastRequest.window.find { it.role == LlmMessage.Role.TOOL && it.content.contains("malformed") } != null
    }

    def "mix request plus write_ui yields when allowClientTools"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [
                FakeLlmProtocol.toolCalls(
                        new LlmToolCall("c1", "request", '{"method":"GET","path":"/rest/s1/x"}'),
                        new LlmToolCall("c2", "write_ui", '{"title":"<b>Hi</b>","fields":[{"name":"n","widget":"text-line"}]}')),
                FakeLlmProtocol.stop("after-resume")
        ]
        LlmTool server = new RecordingTool("request")
        def conv = LlmConversationImpl.create(null, "default", null)
        when:
        LlmResponse r = client(proto).conversation(conv).tool(server).tool(LlmTool.writeUi())
                .allowClientTools(true).user("form").call()
        then:
        r.yielded
        r.pendingToolCalls.size() == 1
        r.pendingToolCalls[0].name == "write_ui"
        r.pendingToolCalls[0].execution == LlmTool.Execution.CLIENT
        ((RecordingTool) server).calls.size() == 1
        conv.status == LlmConversationImpl.STATUS_YIELDED
        conv.pendingClientToolCalls.size() == 1
        r.pendingToolCalls[0].arguments.contains("&lt;b&gt;") || r.pendingToolCalls[0].arguments.contains("Hi")
        when:
        LlmResponse r2 = client(proto).conversation(conv).tool(server).tool(LlmTool.writeUi())
                .allowClientTools(true)
                .toolResults([new LlmToolResult("c2", "write_ui", [submitted: true, values: [n: "x"]])])
                .call()
        then:
        !r2.yielded
        r2.content == "after-resume"
        conv.status == LlmConversationImpl.STATUS_COMPLETE
        conv.history.find { it.role == LlmMessage.Role.TOOL && it.toolCallId == "c2" } != null
    }

    def "allowClientTools false treats write_ui as tool error and does not yield"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [
                FakeLlmProtocol.toolCalls(
                        new LlmToolCall("c1", "request", '{"method":"GET","path":"/rest/s1/x"}'),
                        new LlmToolCall("c2", "write_ui", '{"fields":[{"name":"n","widget":"text-line"}]}')),
                FakeLlmProtocol.stop("done")
        ]
        LlmTool server = new RecordingTool("request")
        when:
        LlmResponse r = client(proto).tool(server).tool(LlmTool.writeUi())
                .allowClientTools(false).user("form").call()
        then:
        !r.yielded
        r.content == "done"
        ((RecordingTool) server).calls.size() == 1
        proto.chatCount == 2
        proto.lastRequest.window.find { it.role == LlmMessage.Role.TOOL && it.toolCallId == "c2" }
                .content.contains("client tool not available")
    }

    def "write_ui enricher strips html widgets and password hidden fields"() {
        given:
        WriteUiTool tool = new WriteUiTool()
        when:
        def enriched = tool.enrichForClient([
                title: "<script>x</script>Hi",
                fields: [
                        [name: "ok", widget: "text-line", label: "<b>Name</b>"],
                        [name: "bad", widget: "html"],
                        [name: "password", widget: "hidden"],
                        [name: "when", widget: "date"]
                ]
        ], null)
        then:
        !enriched.title.contains("<script>")
        enriched.fields.size() == 2
        enriched.fields[0].name == "ok"
        !enriched.fields[0].label.contains("<b>")
        enriched.fields[1].name == "when"
        enriched.fields[1].widget == "date-time"
        enriched.fields[1].widgetType == "date"
        enriched.schemaVersion == 1
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

class RecordingTool implements LlmTool {
    final String name
    final List<Map> calls = []
    RecordingTool(String name) { this.name = name }
    @Override String getName() { return name }
    @Override String getDescription() { return name }
    @Override Map<String, Object> getParametersSchema() { return [type: "object", properties: [:]] }
    @Override LlmTool.Execution getExecution() { return LlmTool.Execution.SERVER }
    @Override Object execute(Map<String, Object> arguments, org.moqui.context.ExecutionContext ec) {
        calls.add(arguments)
        return [status: 200, json: [ok: true]]
    }
}
