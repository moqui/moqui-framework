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
import org.moqui.impl.llm.LlmConversationImpl
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.LlmGateway
import org.moqui.impl.webapp.SseSink
import org.moqui.impl.webapp.ServletStreamListener
import org.moqui.llm.LlmException
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmToolCall
import org.moqui.llm.test.FakeLlmProtocol
import spock.lang.Specification

import jakarta.servlet.http.HttpServletRequest

class LlmServletTests extends Specification {

    def "parseRoute maps /llm/v1 endpoints"() {
        expect:
        LlmGateway.parseRoute("/v1/chat").op == LlmGateway.Route.Op.CHAT
        LlmGateway.parseRoute("/v1/chat/100/resume").op == LlmGateway.Route.Op.RESUME
        LlmGateway.parseRoute("/v1/chat/100/resume").conversationId == "100"
        LlmGateway.parseRoute("/v1/chat/100/cancel").op == LlmGateway.Route.Op.CANCEL
        LlmGateway.parseRoute("/v1/conversations/100/cancel").op == LlmGateway.Route.Op.CANCEL
        LlmGateway.parseRoute("/v1/conversations/100").op == LlmGateway.Route.Op.GET_CONVERSATION
        LlmGateway.parseRoute("/v1/profiles").op == LlmGateway.Route.Op.GET_PROFILES
        LlmGateway.parseRoute("/v1/chat").isPost()
        LlmGateway.parseRoute("/v1/profiles").isGet()
        LlmGateway.parseRoute("/v2/chat") == null
        LlmGateway.parseRoute("/chat") == null
        LlmGateway.parseRoute(null) == null
    }

    def "CSRF required on POST unless login_key/Basic or authenticated attr"() {
        given:
        HttpServletRequest missing = Stub(HttpServletRequest) {
            getMethod() >> "POST"
            getAttribute(_) >> null
            getHeader(_) >> null
            getParameter(_) >> null
        }
        HttpServletRequest headerOk = Stub(HttpServletRequest) {
            getMethod() >> "POST"
            getAttribute(_) >> null
            getHeader("X-CSRF-Token") >> "tok"
            getHeader(_) >> { String n -> n == "X-CSRF-Token" ? "tok" : null }
            getParameter(_) >> null
        }
        HttpServletRequest basic = Stub(HttpServletRequest) {
            getMethod() >> "POST"
            getAttribute(_) >> null
            getHeader("Authorization") >> "Basic abc"
            getHeader(_) >> { String n -> n == "Authorization" ? "Basic abc" : null }
            getParameter(_) >> null
        }
        HttpServletRequest loginKey = Stub(HttpServletRequest) {
            getMethod() >> "POST"
            getAttribute(_) >> null
            getHeader("login_key") >> "k"
            getHeader(_) >> { String n -> n == "login_key" ? "k" : null }
            getParameter(_) >> null
        }
        HttpServletRequest authed = Stub(HttpServletRequest) {
            getMethod() >> "POST"
            getAttribute("moqui.request.authenticated") >> "true"
            getAttribute(_) >> { String n -> n == "moqui.request.authenticated" ? "true" : null }
            getHeader(_) >> null
            getParameter(_) >> null
        }
        HttpServletRequest get = Stub(HttpServletRequest) {
            getMethod() >> "GET"
            getAttribute(_) >> null
            getHeader(_) >> null
            getParameter(_) >> null
        }
        expect:
        LlmGateway.csrfError(missing, "tok", true) == "Session token required (in X-CSRF-Token)"
        LlmGateway.csrfError(headerOk, "tok", true) == null
        LlmGateway.csrfError(headerOk, "other", true).contains("does not match")
        LlmGateway.csrfError(basic, "tok", true) == null
        LlmGateway.csrfError(loginKey, "tok", true) == null
        LlmGateway.csrfError(authed, "tok", true) == null
        LlmGateway.csrfError(get, "tok", true) == null
        LlmGateway.csrfError(missing, "tok", false) == null
    }

    def "empty allowed-path is fail-closed: no request tool"() {
        expect:
        LlmGateway.requestToolForServlet(null) == null
        LlmGateway.requestToolForServlet([]) == null
        LlmGateway.requestToolForServlet([new LlmFacadeImpl.AllowedPath("/rest/s1/", "GET")]) != null
    }

    def "tools may only subset request and write_ui"() {
        expect:
        LlmGateway.parseTools(null).isEmpty()
        LlmGateway.parseTools(["request", "write-ui"]) == ["request", "write_ui"]
        when:
        LlmGateway.parseTools(["request", "clean_llm"])
        then:
        LlmException e = thrown()
        e.httpStatus == 400
    }

    def "attachServletTools skips request when profile has no allowed-path"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [FakeLlmProtocol.stop("ok")]
        def profile = LlmFacadeImpl.ProfileState.forTest("default", proto, "m", false, 2, 0f, 5)
        def client = new LlmClientImpl(null, profile, { false })
        when:
        LlmGateway.attachServletTools(client, profile, ["request", "write_ui"])
        def r = client.user("hi").call()
        then:
        r.content == "ok"
        proto.lastRequest.tools == null || proto.lastRequest.tools.isEmpty()
    }

    def "attachServletTools adds request when allowed-path is present and write_ui when allowed"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [FakeLlmProtocol.stop("ok")]
        def paths = [new LlmFacadeImpl.AllowedPath("/rest/", "GET")]
        def profile = LlmFacadeImpl.ProfileState.forTest("default", proto, "m", false, 2, 0f, 5, paths, true)
        def client = new LlmClientImpl(null, profile, { false })
        when:
        LlmGateway.attachServletTools(client, profile, ["request", "write_ui"])
        client.user("hi").call()
        then:
        proto.lastRequest.tools.size() == 2
        proto.lastRequest.tools.find { it.name == "request" } != null
        proto.lastRequest.tools.find { it.name == "write_ui" } != null
    }

    def "golden SSE event sequence includes conversation, delta, done"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [FakeLlmProtocol.stop("Hello")]
        proto.streamDeltas = ["Hel", "lo"]
        def conv = LlmConversationImpl.create(null, "default", null)
        def client = new LlmClientImpl(null, LlmFacadeImpl.ProfileState.forTest("default", proto, "m", false, 2, 0f, 5), { false })
        def sw = new StringWriter()
        def sink = new SseSink(sw)
        def listener = new ServletStreamListener(sink, client)
        when:
        def r = client.conversation(conv).user("hi").stream(listener)
        then:
        r.content == "Hello"
        def text = sw.toString()
        def events = text.findAll(/event: (\w+)/) { it[1] }
        events[0] == "conversation"
        events.contains("delta")
        events.last() == "done"
        text.contains('"conversationId"')
        text.contains('"content":"Hel"')
        text.contains("event: done")
        !text.toLowerCase().contains("sk-")
        !text.toLowerCase().contains("api-key")
    }

    def "stream with server tool emits ping then tool_call/tool_result then done"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [
                FakeLlmProtocol.toolCalls(new LlmToolCall("c1", "request", '{"method":"GET"}')),
                FakeLlmProtocol.stop("after")
        ]
        def conv = LlmConversationImpl.create(null, "default", null)
        def client = new LlmClientImpl(null, LlmFacadeImpl.ProfileState.forTest("default", proto, "m", false, 2, 0f, 5), { false })
        def sw = new StringWriter()
        def sink = new SseSink(sw)
        def listener = new ServletStreamListener(sink, client)
        when:
        def r = client.conversation(conv).tool(new GatewayRecordingTool("request")).user("hi").stream(listener)
        then:
        r.content == "after"
        def events = sw.toString().findAll(/event: (\w+)/) { it[1] }
        events.contains("conversation")
        events.contains("ping")
        events.contains("tool_call")
        events.contains("tool_result")
        events.last() == "done"
        proto.chatStreamCount == 2
        proto.chatCount == 0
    }

    def "cancel is 200 on Streaming and Yielded, 409 otherwise"() {
        given:
        def streaming = LlmConversationImpl.create(null, "default", null)
        streaming.@statusId = LlmConversationImpl.STATUS_STREAMING
        def yielded = LlmConversationImpl.create(null, "default", null)
        yielded.@statusId = LlmConversationImpl.STATUS_YIELDED
        def active = LlmConversationImpl.create(null, "default", null)
        def complete = LlmConversationImpl.create(null, "default", null)
        complete.@statusId = LlmConversationImpl.STATUS_COMPLETE
        when:
        def s = LlmGateway.cancel(streaming)
        def y = LlmGateway.cancel(yielded)
        def a = LlmGateway.cancel(active)
        def c = LlmGateway.cancel(complete)
        then:
        s.httpStatus == 200
        streaming.status == LlmConversationImpl.STATUS_CANCELLED
        y.httpStatus == 200
        yielded.status == LlmConversationImpl.STATUS_CANCELLED
        a.httpStatus == 409
        c.httpStatus == 409
        complete.status == LlmConversationImpl.STATUS_COMPLETE
    }

    def "wantsStream from Accept or body.stream"() {
        expect:
        LlmGateway.wantsStream([stream: true], null)
        LlmGateway.wantsStream([:], "text/event-stream")
        LlmGateway.wantsStream([stream: "true"], "application/json")
        !LlmGateway.wantsStream([:], "application/json")
    }

    def "409 single-flight on chat while Streaming"() {
        given:
        def proto = new FakeLlmProtocol(failIfInvoked: true)
        def conv = LlmConversationImpl.create(null, "default", null)
        conv.@statusId = LlmConversationImpl.STATUS_STREAMING
        def client = new LlmClientImpl(null, LlmFacadeImpl.ProfileState.forTest("default", proto, "m", false, 2, 0f, 5), { false })
        when:
        client.conversation(conv).user("hi").call()
        then:
        LlmException e = thrown()
        e.httpStatus == 409
    }

    def "SseSink golden ping format"() {
        given:
        def sw = new StringWriter()
        def sink = new SseSink(sw)
        when:
        sink.event("conversation", [conversationId: "1"])
        sink.ping()
        sink.event("done", [finishReason: "stop", yielded: false])
        then:
        def text = sw.toString()
        text.startsWith("event: conversation\n")
        text.contains("event: ping\n")
        text.contains('"t":')
        text.contains("event: done\n")
        text.contains("data: ")
    }
}

class GatewayRecordingTool implements LlmTool {
    final String name
    GatewayRecordingTool(String name) { this.name = name }
    @Override String getName() { return name }
    @Override String getDescription() { return name }
    @Override Map<String, Object> getParametersSchema() { return [type: "object", properties: [:]] }
    @Override LlmTool.Execution getExecution() { return LlmTool.Execution.SERVER }
    @Override Object execute(Map<String, Object> arguments, org.moqui.context.ExecutionContext ec) {
        return [status: 200, json: [ok: true]]
    }
}
