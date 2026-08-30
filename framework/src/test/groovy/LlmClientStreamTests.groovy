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

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.Callback
import org.moqui.impl.llm.OpenAiCompatProtocol
import org.moqui.llm.LlmFinishReason
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmProtocol.ProtocolRequest
import org.moqui.llm.LlmProtocol.ProtocolResult
import org.moqui.llm.LlmProtocol.ProtocolStreamListener
import org.moqui.util.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class LlmClientStreamTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(LlmClientStreamTests.class)

    @Shared Server server
    @Shared int port
    @Shared OpenAiSseHandler handler
    @Shared RestClient.RequestFactory requestFactory

    def setupSpec() {
        handler = new OpenAiSseHandler()
        server = new Server(0)
        server.setHandler(handler)
        server.start()
        port = ((ServerConnector) server.connectors[0]).localPort
        requestFactory = new RestClient.SimpleRequestFactory()
        logger.info("LlmClientStreamTests server on port ${port}")
    }

    def cleanupSpec() {
        if (requestFactory != null) requestFactory.destroy()
        if (server != null) server.stop()
    }

    def setup() {
        handler.reset()
    }

    private ProtocolRequest req(int timeoutSeconds = 15) {
        ProtocolRequest r = new ProtocolRequest()
        r.profileName = "test"
        r.endpointUrl = "http://127.0.0.1:${port}/v1/chat/completions"
        r.model = "test-model"
        r.window = [LlmMessage.user("hi")]
        r.timeoutSeconds = timeoutSeconds
        r.retryInitialSeconds = 0.05f
        r.retryMax = 5
        r.timeoutRetry = true
        r.stream = true
        r.requestFactory = requestFactory
        return r
    }

    def "OpenAI chunks assemble content, classify STOP, and honor [DONE]"() {
        given:
        handler.scenario = "content"
        def listener = new ProtoListener()
        ProtocolRequest request = req()
        request.apiKey = "sk-test"
        request.authHeaderName = "Authorization"
        request.authHeaderValue = "Bearer sk-test"
        when:
        new OpenAiCompatProtocol().chatStream(request, listener)
        Map body = new JsonSlurper().parseText(handler.lastBody) as Map
        then:
        listener.failure == null
        listener.complete != null
        listener.complete.finishReason == LlmFinishReason.STOP
        listener.complete.content == "Hello"
        listener.deltas == ["Hel", "lo"]
        listener.complete.usage?.totalTokens == 5
        listener.complete.httpStatus == 200
        !listener.deltas.contains("[DONE]")
        listener.complete.content != null && !listener.complete.content.contains("[DONE]")
        body.stream == true
        body.stream_options?.include_usage == true
        handler.lastAuthorization == "Bearer sk-test"
        handler.hits.get() == 1
    }

    def "blank api-key omits auth header on stream"() {
        given:
        handler.scenario = "content"
        def listener = new ProtoListener()
        ProtocolRequest request = req()
        request.apiKey = ""
        request.authHeaderName = "Authorization"
        request.authHeaderValue = null
        when:
        new OpenAiCompatProtocol().chatStream(request, listener)
        then:
        listener.complete?.finishReason == LlmFinishReason.STOP
        handler.lastAuthorization == null
        handler.lastApiKey == null
    }

    def "tool_call argument chunks are concatenated by index"() {
        given:
        handler.scenario = "tool_calls"
        def listener = new ProtoListener()
        when:
        new OpenAiCompatProtocol().chatStream(req(), listener)
        then:
        listener.failure == null
        listener.complete.finishReason == LlmFinishReason.TOOL_CALLS
        listener.complete.toolCalls.size() == 1
        listener.complete.toolCalls[0].id == "call_1"
        listener.complete.toolCalls[0].name == "request"
        listener.complete.toolCalls[0].arguments == '{"method":"GET"}'
        listener.deltas.isEmpty()
        listener.complete.usage?.totalTokens == 13
    }

    def "usage may be null when gateway ignores include_usage"() {
        given:
        handler.scenario = "no_usage"
        def listener = new ProtoListener()
        when:
        new OpenAiCompatProtocol().chatStream(req(), listener)
        then:
        listener.complete.finishReason == LlmFinishReason.STOP
        listener.complete.content == "hi"
        listener.complete.usage == null
    }

    def "finish_reason length is classified the same as sync"() {
        given:
        handler.scenario = "length"
        def listener = new ProtoListener()
        when:
        new OpenAiCompatProtocol().chatStream(req(), listener)
        then:
        listener.complete.finishReason == LlmFinishReason.LENGTH
        listener.complete.content == "partial"
        handler.hits.get() == 1
    }

    def "finish_reason content_filter is classified the same as sync"() {
        given:
        handler.scenario = "content_filter"
        def listener = new ProtoListener()
        when:
        new OpenAiCompatProtocol().chatStream(req(), listener)
        then:
        listener.complete.finishReason == LlmFinishReason.CONTENT_FILTER
        handler.hits.get() == 1
    }

    def "drop after first delta does not retry"() {
        given:
        handler.scenario = "drop"
        def listener = new ProtoListener()
        when:
        new OpenAiCompatProtocol().chatStream(req(3), listener)
        then:
        listener.failure != null
        listener.complete == null
        listener.deltas == ["Hel"]
        handler.hits.get() == 1
    }

    def "HTTP 400 before SSE is classified and not retried"() {
        given:
        handler.scenario = "http400"
        def listener = new ProtoListener()
        when:
        new OpenAiCompatProtocol().chatStream(req(), listener)
        then:
        listener.failure == null
        listener.complete != null
        listener.complete.finishReason == LlmFinishReason.ERROR
        listener.complete.httpStatus == 400
        handler.hits.get() == 1
    }

    static class ProtoListener implements ProtocolStreamListener {
        List<String> deltas = []
        ProtocolResult complete
        Throwable failure
        @Override void onDelta(String textDelta) { deltas.add(textDelta) }
        @Override void onComplete(ProtocolResult result) { complete = result }
        @Override void onFailure(Throwable t) { failure = t }
    }

    static class OpenAiSseHandler extends Handler.Abstract {
        final AtomicInteger hits = new AtomicInteger()
        final AtomicInteger generation = new AtomicInteger()
        volatile String scenario = "content"
        volatile String lastBody
        volatile String lastAuthorization
        volatile String lastApiKey

        void reset() {
            generation.incrementAndGet()
            hits.set(0)
            scenario = "content"
            lastBody = null
            lastAuthorization = null
            lastApiKey = null
        }

        @Override
        boolean handle(Request request, Response response, Callback callback) throws Exception {
            lastBody = Content.Source.asString(request)
            lastAuthorization = request.getHeaders().get("Authorization")
            lastApiKey = request.getHeaders().get("api-key")
            hits.incrementAndGet()
            String sc = scenario
            try {
                if ("http400".equals(sc)) {
                    response.setStatus(400)
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json")
                    Content.Sink.write(response, true, ByteBuffer.wrap(
                            '{"error":{"code":"context_length_exceeded","message":"too long"}}'
                                    .getBytes(StandardCharsets.UTF_8)))
                    callback.succeeded()
                    return true
                }
                writeSseHeaders(response)
                if ("content".equals(sc)) {
                    writeChunk(response, sse(contentChunk("Hel")), false)
                    writeChunk(response, sse(contentChunk("lo")), false)
                    writeChunk(response, sse(contentChunk(null, "stop")), false)
                    writeChunk(response, sse(usageChunk(3, 2, 5)), false)
                    writeChunk(response, "data: [DONE]\n\n", true)
                    callback.succeeded()
                } else if ("tool_calls".equals(sc)) {
                    writeChunk(response, sse('{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"request","arguments":""}}]}}]}'), false)
                    writeChunk(response, sse('{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"method\\":"}}]}}]}'), false)
                    writeChunk(response, sse('{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"GET\\"}"}}]}}]}'), false)
                    writeChunk(response, sse('{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}'), false)
                    writeChunk(response, sse(usageChunk(9, 4, 13)), false)
                    writeChunk(response, "data: [DONE]\n\n", true)
                    callback.succeeded()
                } else if ("no_usage".equals(sc)) {
                    writeChunk(response, sse(contentChunk("hi", "stop")), false)
                    writeChunk(response, "data: [DONE]\n\n", true)
                    callback.succeeded()
                } else if ("length".equals(sc)) {
                    writeChunk(response, sse(contentChunk("partial", "length")), false)
                    writeChunk(response, "data: [DONE]\n\n", true)
                    callback.succeeded()
                } else if ("content_filter".equals(sc)) {
                    writeChunk(response, sse('{"choices":[{"index":0,"delta":{"content":""},"finish_reason":"content_filter"}]}'), false)
                    writeChunk(response, "data: [DONE]\n\n", true)
                    callback.succeeded()
                } else if ("drop".equals(sc)) {
                    int gen = generation.get()
                    writeChunk(response, sse(contentChunk("Hel")), false)
                    for (int i = 0; i < 40 && generation.get() == gen; i++) Thread.sleep(250)
                    callback.succeeded()
                } else {
                    Response.writeError(request, response, callback, 404)
                }
            } catch (Exception e) {
                callback.failed(e)
            }
            return true
        }

        private static void writeSseHeaders(Response response) {
            response.setStatus(200)
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/event-stream; charset=UTF-8")
            response.getHeaders().put(HttpHeader.CACHE_CONTROL, "no-cache")
        }

        private static void writeChunk(Response response, String text, boolean last) throws IOException {
            Content.Sink.write(response, last, ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)))
        }

        private static String sse(String json) { return "data: " + json + "\n\n" }

        private static String contentChunk(String delta, String finish = null) {
            Map choice = [index: 0, delta: [:]]
            if (delta != null) choice.delta = [content: delta]
            if (finish != null) choice.finish_reason = finish
            return new JsonBuilder([model: "test-model", choices: [choice]]).toString()
        }

        private static String usageChunk(int prompt, int completion, int total) {
            return new JsonBuilder([choices: [], usage: [prompt_tokens: prompt,
                    completion_tokens: completion, total_tokens: total]]).toString()
        }
    }
}
