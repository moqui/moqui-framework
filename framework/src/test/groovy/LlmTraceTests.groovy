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

import org.moqui.impl.llm.LlmTrace
import org.moqui.llm.LlmFinishReason
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmProtocol.ProtocolResult
import org.moqui.llm.LlmToolCall
import org.moqui.llm.LlmUsage
import spock.lang.Specification

class LlmTraceTests extends Specification {

    def "browse call shows path and match and omits default depth"() {
        expect:
        LlmTrace.summarizeCall("browse", [path: "/qapps/system", match: "UserAccount"]) ==
                "path=/qapps/system match=UserAccount"
        LlmTrace.summarizeCall("browse", [path: "/qapps", depth: 3, detail: true]) ==
                "path=/qapps depth=3 detail=true"
        LlmTrace.summarizeCall("browse", [:]) == "path=/"
    }

    def "request call shows method and path and query/body keys not values"() {
        when:
        String sum = LlmTrace.summarizeCall("request",
                [method: "post", path: "/qapps/x/actions",
                 query: [q: "secret-query"], body: [newPassword: "p", username: "u"]])
        then:
        sum.startsWith("POST /qapps/x/actions")
        sum.contains("queryKeys=q")
        sum.contains("bodyKeys=username")
        !sum.contains("secret-query")
        !sum.contains("newPassword")
        !sum.contains("bodyKeys=newPassword")
    }

    def "run_service redacts password values"() {
        when:
        String sum = LlmTrace.summarizeCall("run_service",
                [serviceName: "org.moqui.impl.UserServices.create#UserAccount",
                 parameters: [username: "john.doe", newPassword: "s3cret", authPassword: "x"]])
        then:
        sum.contains("org.moqui.impl.UserServices.create#UserAccount")
        sum.contains("username=john.doe")
        sum.contains("newPassword=***")
        !sum.contains("s3cret")
        !sum.contains("authPassword")
    }

    def "write_ui omits sfc source and shows kind title fields"() {
        when:
        String sum = LlmTrace.summarizeCall("write_ui",
                [kind: "vue-sfc", title: "Create User", fields: [[name: "n"], [name: "p"]],
                 sfc: "<template>huge</template>", script: "module.exports={}"])
        then:
        sum.contains("kind=vue-sfc")
        sum.contains("title=")
        sum.contains("Create User")
        sum.contains("fields=2")
        !sum.contains("huge")
        !sum.contains("module.exports")
        !sum.contains("sfc=")
    }

    def "find_skill and enter_sim summarize query and goal"() {
        expect:
        LlmTrace.summarizeCall("find_skill", [query: "create user", limit: 3]).contains("query=")
        LlmTrace.summarizeCall("find_skill", [query: "create user", limit: 3]).contains("limit=3")
        LlmTrace.summarizeCall("find_skill", [select: "create-user-account"]).contains("select=")
        LlmTrace.summarizeCall("enter_sim", [goal: "place an order", max_iterations: 40]).contains("goal=")
        LlmTrace.summarizeCall("enter_sim", [goal: "place an order", max_iterations: 40]).contains("maxIter=40")
    }

    def "generic call redacts token and skip keys"() {
        when:
        String sum = LlmTrace.summarizeCall("custom", [foo: "bar", token: "abc", currentPassword: "x"])
        then:
        sum.contains("foo=bar")
        sum.contains("token=***")
        !sum.contains("abc")
        !sum.contains("currentPassword")
    }

    def "result summaries are compact"() {
        expect:
        LlmTrace.summarizeResult("browse", [path: "/qapps", children: [[name: "a"], [name: "b"]]])
                .contains("2 children")
        LlmTrace.summarizeResult("request", [status: 200]).contains("status=200")
        LlmTrace.summarizeResult("run_service", [ok: true]).contains("ok")
        LlmTrace.summarizeResult("run_service", [error: "nope"]).contains("error=")
        LlmTrace.summarizeResult("find_skill", [skills: []]).contains("none")
        LlmTrace.summarizeResult("find_skill", [skills: [[name: "create-user-account"]]])
                .contains("create-user-account")
        LlmTrace.summarizeResult("find_skill", [skills: [], selected: [name: "create-user-account"]])
                .contains("selected=create-user-account")
        LlmTrace.summarizeResult("enter_sim", [proposedSkillName: "create-user-account"])
                .contains("proposed=create-user-account")
        LlmTrace.summarizeResult("enter_sim", [proposedSkillName: "n1", proposedSkillId: "SID1", selected: false])
                .contains("skillId=SID1")
        LlmTrace.summarizeResult("enter_sim", [proposedSkillName: "n1", proposedSkillId: "SID1", selected: false])
                .contains("notSelected")
        LlmTrace.summarizeResult("write_ui", [submitted: true, button: "submit"]).contains("submitted")
        LlmTrace.summarizeResult("write_ui", [submitted: false]).contains("not submitted")
    }

    def "prompt preview is 60 chars of head and tail; short prompt has no tail"() {
        when:
        String shortText = "hello world"
        LlmTrace.Preview shortPrev = LlmTrace.preview(shortText, 60)
        String longText = "A" * 40 + "B" * 40 + "C" * 40
        LlmTrace.Preview longPrev = LlmTrace.preview(longText, 60)
        then:
        shortPrev.chars == shortText.length()
        shortPrev.head == shortText
        shortPrev.tail == null
        longPrev.chars == 120
        longPrev.head == ("A" * 40 + "B" * 20)
        longPrev.tail == ("B" * 20 + "C" * 40)
        longPrev.head.length() == 60
        longPrev.tail.length() == 60
    }

    def "formatRequest includes promptChars head and omits tail when short"() {
        given:
        List<LlmMessage> window = [LlmMessage.system("You are Assist."), LlmMessage.user("hi")]
        when:
        String line = LlmTrace.formatRequest("assist", "c1", false, "gpt", true, window)
        then:
        line.startsWith("LLM request")
        line.contains("profile=assist")
        line.contains("conv=c1")
        line.contains("sim=false")
        line.contains("stream=true")
        line.contains("messages=2")
        line.contains("promptChars=")
        line.contains("head=")
        !line.contains(" tail=")
    }

    def "formatRequest long prompt has head and tail"() {
        given:
        String body = ("head-part-" * 10) + ("mid-" * 20) + ("tail-part-" * 10)
        List<LlmMessage> window = [LlmMessage.user(body)]
        when:
        String line = LlmTrace.formatRequest("assist", "c1", false, "m", false, window)
        then:
        line.contains("head=")
        line.contains("tail=")
        line.contains("promptChars=" + LlmTrace.preview(body, 60).chars)
    }

    def "formatResponse includes timing tokens and head tail of content"() {
        given:
        String content = ("BEGIN" * 20) + ("MID" * 20) + ("END!!" * 20)
        ProtocolResult result = new ProtocolResult(LlmFinishReason.STOP)
        result.content = content
        result.httpStatus = 200
        result.usage = new LlmUsage(100, 20, 120)
        when:
        String line = LlmTrace.formatResponse("assist", "c1", false, 842, result)
        then:
        line.startsWith("LLM response")
        line.contains("ms=842")
        line.contains("tokens=p100/c20/t120")
        line.contains("finish=stop")
        line.contains("http=200")
        line.contains("chars=")
        line.contains("head=")
        line.contains("tail=")
        line.contains("BEGIN")
        line.contains("END!!")
    }

    def "formatResponse short content omits tail and missing usage is question mark"() {
        given:
        ProtocolResult result = new ProtocolResult(LlmFinishReason.STOP)
        result.content = "ok"
        result.httpStatus = 200
        when:
        String line = LlmTrace.formatResponse("p", null, true, 5, result)
        then:
        line.contains("sim=true")
        line.contains("tokens=?")
        line.contains("head=\"ok\"")
        !line.contains(" tail=")
        line.contains("conv=-")
    }

    def "formatResponse tool_calls with empty content previews tool summaries"() {
        given:
        ProtocolResult result = new ProtocolResult(LlmFinishReason.TOOL_CALLS)
        result.httpStatus = 200
        result.toolCalls = [new LlmToolCall("c1", "browse", '{"path":"/qapps","match":"User"}')]
        when:
        String line = LlmTrace.formatResponse("assist", "c1", false, 10, result)
        then:
        line.contains("finish=tool_calls")
        line.contains("head=")
        line.contains("browse")
        line.contains("path=/qapps")
    }

    def "sim enter and exit lines"() {
        expect:
        LlmTrace.formatSimEnter("c1", "create a user", 32, true).contains("LLM sim enter")
        LlmTrace.formatSimEnter("c1", "create a user", 32, true).contains("overlay=start")
        LlmTrace.formatSimEnter("c1", "create a user", 32, true).contains("maxIter=32")
        LlmTrace.formatSimExit("c1", 5400, "create-user-account", null).contains("proposed=create-user-account")
        LlmTrace.formatSimExit("c1", 12, null, "nested failed").contains("error=")
        !LlmTrace.formatSimExit("c1", 12, null, null).contains("error=")
    }
}
