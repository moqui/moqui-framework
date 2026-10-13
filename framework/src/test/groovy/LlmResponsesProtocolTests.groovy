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
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.Callback
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.LlmGateway
import org.moqui.impl.llm.LlmRetryClassifier
import org.moqui.impl.llm.OpenAiCompatProtocol
import org.moqui.impl.llm.OpenAiResponsesProtocol
import org.moqui.llm.LlmFinishReason
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmProtocol
import org.moqui.llm.LlmProtocol.ProtocolRequest
import org.moqui.llm.LlmProtocol.ProtocolResult
import org.moqui.llm.LlmProtocol.ProtocolStreamListener
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmToolCall
import org.moqui.util.RestClient
import spock.lang.Shared
import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class LlmResponsesProtocolTests extends Specification {
    @Shared Server server
    @Shared int port
    @Shared SseHandler handler
    @Shared RestClient.RequestFactory requestFactory

    def setupSpec() {
        handler = new SseHandler()
        server = new Server(0)
        server.setHandler(handler)
        server.start()
        port = ((ServerConnector) server.connectors[0]).localPort
        requestFactory = new RestClient.SimpleRequestFactory()
    }

    def cleanupSpec() {
        if (requestFactory != null) requestFactory.destroy()
        if (server != null) server.stop()
    }

    def setup() { handler.reset() }

    def "omitted path follows the protocol and an explicit path is kept"() {
        expect:
        LlmFacadeImpl.ProfileState.resolvePath(null, new OpenAiResponsesProtocol()) == "/v1/responses"
        LlmFacadeImpl.ProfileState.resolvePath("  ", new OpenAiResponsesProtocol()) == "/v1/responses"
        LlmFacadeImpl.ProfileState.resolvePath("/openai/responses", new OpenAiResponsesProtocol()) == "/openai/responses"
        LlmFacadeImpl.ProfileState.resolvePath(null, new OpenAiCompatProtocol()) == "/v1/chat/completions"
        OpenAiCompatProtocol.composeEndpointUrl("https://api.openai.com",
                LlmFacadeImpl.ProfileState.resolvePath(null, new OpenAiResponsesProtocol()), null) ==
                "https://api.openai.com/v1/responses"
        new OpenAiResponsesProtocol().defaultPath() == "/v1/responses"
        new OpenAiResponsesProtocol().getName() == "openai-responses"
    }

    def "responses body is stateless, flat tools, and drops temperature when effort is set"() {
        when:
        ProtocolRequest req = new ProtocolRequest()
        req.model = "gpt-5.4"
        req.maxTokens = 32
        req.temperature = 0.2d
        req.stream = false
        req.window = [LlmMessage.system("Be brief."), LlmMessage.context("src", "Acme"), LlmMessage.user("hi")]
        req.tools = [LlmTool.pin()]
        req.apiConfig = [store: true, include: ["other"], input: [[role: "user", content: "hijack"]],
                instructions: "hijack", reasoning: [effort: "low"]]
        Map body = OpenAiResponsesProtocol.buildRequestBody(req)

        ProtocolRequest warm = new ProtocolRequest()
        warm.model = "gpt-5.4"
        warm.temperature = 0.4d
        warm.window = [LlmMessage.user("hi")]
        Map kept = OpenAiResponsesProtocol.buildRequestBody(warm)

        ProtocolRequest effort = new ProtocolRequest()
        effort.model = "gpt-5.4"
        effort.temperature = 0.4d
        effort.window = [LlmMessage.user("hi")]
        effort.apiConfig = [reasoning_effort: "low"]
        Map dropped = OpenAiResponsesProtocol.buildRequestBody(effort)

        then:
        body.model == "gpt-5.4"
        body.instructions == "Be brief."
        body.store == false
        body.include.contains("reasoning.encrypted_content")
        body.include.contains("other")
        body.max_output_tokens == 32
        !body.containsKey("max_tokens")
        !body.containsKey("stream_options")
        !body.containsKey("temperature")
        body.stream == false
        body.tools.size() == 1
        body.tools[0].type == "function"
        body.tools[0].name == "pin"
        !body.tools[0].containsKey("function")
        body.input.find { it.content == "hijack" } == null
        body.input.find { it.role == "user" && it.content == "hi" } != null
        body.input.find { it.content?.toString()?.contains("untrusted-context") } != null
        kept.temperature == 0.4d
        !dropped.containsKey("temperature")
    }

    def "responses replays items in order and does not synthesize a second function call"() {
        given:
        LlmMessage asst = LlmMessage.assistant("ignored")
        asst.toolCalls = [new LlmToolCall("call_1", "request", "{\"from\":\"calls\"}")]
        asst.providerState = [items: [
                [type: "reasoning", id: "rs_1", encrypted_content: "cipher", summary: []],
                [type: "function_call", call_id: "call_1", name: "request", arguments: "{}"],
                [type: "message", role: "assistant", content: [[type: "output_text", text: "done"]]]
        ]]
        LlmMessage tool = LlmMessage.tool("call_1", "request", "ok")

        when:
        List input = OpenAiResponsesProtocol.convertInput([
                LlmMessage.system("sys"), asst, tool, LlmMessage.user("next")])

        then:
        input.size() == 5
        input[0].type == "reasoning"
        input[0].encrypted_content == "cipher"
        input[1].type == "function_call"
        input[1].arguments == "{}"
        input[2].type == "message"
        input[3].type == "function_call_output"
        input[3].call_id == "call_1"
        input[3].output == "ok"
        input[4].role == "user"
        input.count { it.type == "function_call" } == 1
        !input.any { it.arguments == "{\"from\":\"calls\"}" }
    }

    def "responses parse maps usage, strips reasoning summary, and classifies errors"() {
        when:
        ProtocolResult ok = OpenAiResponsesProtocol.parseBody(200, '''
            {"status":"completed","model":"gpt-5.4",
             "usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5},
             "output":[
               {"type":"reasoning","id":"rs_1","encrypted_content":"cipher",
                "summary":[{"type":"summary_text","text":"think"}]},
               {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}
             ]}''', false)
        ProtocolResult calls = OpenAiResponsesProtocol.parseBody(200, '''
            {"status":"completed","output":[
               {"type":"function_call","call_id":"call_9","name":"request","arguments":"{\\"method\\":\\"GET\\"}"}
             ]}''', false)
        ProtocolResult length = OpenAiResponsesProtocol.parseBody(200,
                '{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"output":[]}', false)
        ProtocolResult boom = OpenAiResponsesProtocol.parseBody(500, '{"error":{"message":"boom"}}', false)
        ProtocolResult limited = OpenAiResponsesProtocol.parseBody(200,
                '{"error":{"message":"rate limit exceeded","code":"rate_limit_exceeded"}}', false)

        then:
        ok.finishReason == LlmFinishReason.STOP
        ok.content == "Hello"
        ok.reasoning == "think"
        ok.model == "gpt-5.4"
        ok.usage.promptTokens == 3
        ok.usage.completionTokens == 2
        ok.usage.totalTokens == 5
        ok.rawJson == null
        ok.providerState.items[0].encrypted_content == "cipher"
        ok.providerState.items[0].summary == []
        calls.finishReason == LlmFinishReason.TOOL_CALLS
        calls.toolCalls[0].id == "call_9"
        calls.toolCalls[0].name == "request"
        calls.toolCalls[0].arguments == '{"method":"GET"}'
        length.finishReason == LlmFinishReason.LENGTH
        boom.finishReason == LlmFinishReason.ERROR
        boom.retryable
        limited.finishReason == LlmFinishReason.ERROR
        limited.retryable
    }

    def "chat completions echoes reasoning_content and tool-call extras"() {
        when:
        ProtocolResult classified = LlmRetryClassifier.classify(200, '''
            {"choices":[{"finish_reason":"tool_calls","message":{
              "content":null,"reasoning_content":"",
              "tool_calls":[{"id":"c1","type":"function",
                "extra_content":{"google":{"thought_signature":"sig"}},
                "function":{"name":"request","arguments":"{}"}}]}}]}''')
        LlmMessage asst = LlmMessage.assistant(null)
        asst.toolCalls = classified.toolCalls
        asst.copyProviderStateFrom(classified.providerState)
        List converted = OpenAiCompatProtocol.convertMessages([asst])

        LlmMessage bare = LlmMessage.assistant(null)
        bare.toolCalls = [new LlmToolCall("c1", "request", "{}")]
        ProtocolRequest thinking = new ProtocolRequest()
        thinking.tools = [LlmTool.pin()]
        thinking.apiConfig = [thinking: [type: "enabled"]]
        List emptied = OpenAiCompatProtocol.convertMessages([bare], thinking)
        ProtocolRequest plain = new ProtocolRequest()
        plain.tools = [LlmTool.pin()]
        List quiet = OpenAiCompatProtocol.convertMessages([bare], plain)

        LlmMessage shown = LlmMessage.assistant("hi")
        shown.providerState = [reasoningContent: "secret-thought", items: [[encrypted_content: "cipher"]]]
        Map row = LlmGateway.messageToMap(shown)
        LlmToolCall call = new LlmToolCall("c", "request", "{}")
        call.providerFields = [extra_content: [google: [thought_signature: "sig"]]]
        Map tc = LlmGateway.toolCallToMap(call)

        then:
        classified.providerState.reasoningContent == ""
        classified.toolCalls[0].providerFields.extra_content.google.thought_signature == "sig"
        converted[0].reasoning_content == ""
        converted[0].tool_calls[0].extra_content.google.thought_signature == "sig"
        emptied[0].reasoning_content == ""
        !quiet[0].containsKey("reasoning_content")
        !row.containsKey("providerJson")
        !row.containsKey("providerState")
        !new JsonBuilder(row).toString().contains("secret-thought")
        !new JsonBuilder(row).toString().contains("cipher")
        !tc.containsKey("providerFields")
        !tc.containsKey("extra_content")
    }

    def "responses stream emits text and write_ui deltas and completes from response.completed"() {
        given:
        handler.scenario = "content"
        def listener = new Listener()
        ProtocolRequest req = streamReq()

        when:
        new OpenAiResponsesProtocol().chatStream(req, listener)

        then:
        listener.failure == null
        listener.deltas == ["Hel", "lo"]
        listener.complete.finishReason == LlmFinishReason.STOP
        listener.complete.content == "Hello"
        listener.complete.usage.totalTokens == 5
        listener.complete.providerState.items[0].encrypted_content == "cipher"

        when:
        handler.scenario = "write_ui"
        def tools = new Listener()
        def toolReq = streamReq()
        toolReq.tools = [org.moqui.llm.LlmTool.writeUi()]
        new OpenAiResponsesProtocol().chatStream(toolReq, tools)

        then:
        tools.failure == null
        tools.complete.finishReason == LlmFinishReason.TOOL_CALLS
        tools.complete.toolCalls[0].id == "call_w"
        tools.complete.toolCalls[0].name == "write_ui"
        !tools.toolCallDeltas.isEmpty()
        tools.toolCallDeltas[0].name == "write_ui"

        when:
        handler.scenario = "eof"
        def cut = new Listener()
        new OpenAiResponsesProtocol().chatStream(streamReq(), cut)

        then:
        cut.complete == null
        cut.failure != null
        cut.failure.message.contains("response.completed")
    }

    private ProtocolRequest streamReq() {
        ProtocolRequest r = new ProtocolRequest()
        r.profileName = "test"
        r.endpointUrl = "http://127.0.0.1:${port}/v1/responses"
        r.model = "gpt-5.4"
        r.window = [LlmMessage.user("hi")]
        r.timeoutSeconds = 15
        r.retryInitialSeconds = 0.05f
        r.retryMax = 2
        r.timeoutRetry = false
        r.stream = true
        r.requestFactory = requestFactory
        return r
    }

    static class Listener implements ProtocolStreamListener {
        List<String> deltas = []
        List<Map> toolCallDeltas = []
        ProtocolResult complete
        Throwable failure
        @Override void onDelta(String textDelta) { deltas.add(textDelta) }
        @Override void onToolCallDelta(String name, String argumentsSoFar) {
            toolCallDeltas.add([name: name, arguments: argumentsSoFar])
        }
        @Override void onComplete(ProtocolResult result) { complete = result }
        @Override void onFailure(Throwable t) { failure = t }
    }

    static class SseHandler extends Handler.Abstract {
        volatile String scenario = "content"

        void reset() { scenario = "content" }

        @Override
        boolean handle(Request request, Response response, Callback callback) throws Exception {
            String sc = scenario
            response.setStatus(200)
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/event-stream; charset=UTF-8")
            if ("content".equals(sc)) {
                write(response, event("response.output_text.delta", '{"delta":"Hel"}'), false)
                write(response, event("response.output_text.delta", '{"delta":"lo"}'), false)
                write(response, event("response.completed",
                        '{"response":{"status":"completed","model":"gpt-5.4",' +
                        '"usage":{"input_tokens":2,"output_tokens":3,"total_tokens":5},' +
                        '"output":[{"type":"reasoning","id":"rs_1","encrypted_content":"cipher","summary":[{"text":"t"}]},' +
                        '{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}]}}'),
                        true)
            } else if ("write_ui".equals(sc)) {
                String args = '{"kind":"openui","title":"Find assets screen with a long enough label","lang":"root"}'
                write(response, event("response.output_item.added",
                        '{"output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_w","name":"write_ui","arguments":""}}'),
                        false)
                write(response, event("response.function_call_arguments.delta",
                        '{"output_index":0,"item_id":"fc_1","delta":' + new JsonBuilder(args).toString() + '}'), false)
                String doneArgs = args.replace('"', '\\"')
                write(response, event("response.completed",
                        '{"response":{"status":"completed","output":[{"type":"function_call","call_id":"call_w","name":"write_ui","arguments":"' +
                        doneArgs + '"}]}}'), true)
            } else if ("eof".equals(sc)) {
                write(response, event("response.output_text.delta", '{"delta":"Hel"}'), true)
            } else {
                Response.writeError(request, response, callback, 404)
                return true
            }
            callback.succeeded()
            return true
        }

        private static void write(Response response, String text, boolean last) {
            Content.Sink.write(response, last, ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)))
        }

        private static String event(String name, String json) {
            return "event: " + name + "\ndata: " + json + "\n\n"
        }
    }
}
