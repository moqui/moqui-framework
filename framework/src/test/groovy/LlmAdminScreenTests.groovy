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

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.screen.ScreenTest
import org.moqui.screen.ScreenTest.ScreenTestRender
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.Unroll

import java.sql.Timestamp

/** System app LLM screens and the services behind them, against rows created here. */
@Stepwise
class LlmAdminScreenTests extends Specification {
    static final String CONV = "ADMTST_CONV"
    static final String CALL = "ADMTST_CALL"
    static final String SKILL = "ADMTST_SKILL"
    static final String SKILL_NAME = "admtst-skill"
    static final String LESSON = "ADMTST_LESSON"

    @Shared ExecutionContext ec
    @Shared ScreenTest screenTest

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui")
        ec.artifactExecution.disableAuthz()
        boolean began = ec.transaction.begin(null)
        try {
            Timestamp now = ec.user.nowTimestamp
            cleanRows()
            ec.entity.makeValue("moqui.llm.LlmConversation").setAll([conversationId: CONV, profileName: "default",
                    userId: ec.user.userId, statusId: "LlmcsComplete", title: "Admin screen test", summary: "a summary",
                    createdDate: now, lastMessageDate: now, messageCount: 2, searchText: "admin screen test question"]).create()
            ec.entity.makeValue("moqui.llm.LlmMessage").setAll([messageId: "ADMTST_M1", conversationId: CONV, ordinal: 1,
                    role: "USER", content: "How do I place an order?", sentDate: now]).create()
            ec.entity.makeValue("moqui.llm.LlmMessage").setAll([messageId: "ADMTST_M2", conversationId: CONV, ordinal: 2,
                    role: "ASSISTANT", content: "Use the order screen.", sentDate: now]).create()
            ec.entity.makeValue("moqui.llm.LlmCallLog").setAll([callId: CALL, conversationId: CONV, profileName: "default",
                    model: "test-model", httpStatus: 200, finishReason: "STOP", promptTokens: 10, completionTokens: 5,
                    totalTokens: 15, durationMs: 123, iteration: 1, wasError: "N", requestJson: '{"req":true}',
                    responseJson: '{"resp":true}', startDate: now, startDay: new java.sql.Date(now.time)]).create()
            ec.entity.makeValue("moqui.llm.LlmSkill").setAll([skillId: SKILL, name: SKILL_NAME, title: "Admin test skill",
                    description: "Created by the admin screen test", body: "Step one.\nStep two.", riskId: "LskReversible",
                    statusId: "LsksProposed", provenanceId: "LskpSim", version: 1, worldSuccessCount: 3, simSuccessCount: 1]).create()
            ec.entity.makeValue("moqui.llm.LlmLesson").setAll([lessonId: LESSON, skillId: SKILL, title: "A lesson",
                    body: "Send requireInventory false", provenanceId: "LskpWorld", statusId: "LsksProposed", createdDate: now]).create()
            ec.entity.makeValue("moqui.llm.LlmSkillUse").setAll([skillUseId: "ADMTST_USE", skillId: SKILL, conversationId: CONV,
                    contact: "world", outcome: "pass", usedDate: now]).create()
        } finally { if (began) ec.transaction.commit() }
        screenTest = ec.screen.makeTest().baseScreenPath("apps/system")
    }

    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        boolean began = ec.transaction.begin(null)
        try { cleanRows() } finally { if (began) ec.transaction.commit() }
        ec.destroy()
    }

    static void cleanRows() {
        def ec = Moqui.getExecutionContext()
        ec.entity.find("moqui.llm.LlmSkillUse").condition("skillUseId", "ADMTST_USE").deleteAll()
        ec.entity.find("moqui.llm.LlmLesson").condition("lessonId", LESSON).deleteAll()
        ec.entity.find("moqui.llm.LlmSkill").condition("skillId", SKILL).deleteAll()
        ec.entity.find("moqui.llm.LlmCallLog").condition("callId", CALL).deleteAll()
        ec.entity.find("moqui.llm.LlmMessage").condition("conversationId", CONV).deleteAll()
        ec.entity.find("moqui.llm.LlmConversation").condition("conversationId", CONV).deleteAll()
    }

    def setup() { ec.artifactExecution.disableAuthz() }
    def cleanup() { ec.artifactExecution.enableAuthz() }

    @Unroll
    def "render #screenPath contains #text1"() {
        when:
        ScreenTestRender str = screenTest.render(screenPath, [lastStandalone: "-2"], null)
        then:
        !str.errorMessages
        !text1 || str.assertContains(text1)
        !text2 || str.assertContains(text2)

        where:
        screenPath                                                | text1                        | text2
        "Llm"                                                     | ""                           | ""
        "Llm/Conversation/ConversationList"                       | CONV                         | "Admin screen test"
        "Llm/Conversation/ConversationDetail?conversationId=" + CONV | "How do I place an order?" | "Use the order screen."
        "Llm/Call/CallList"                                       | "test-model"                 | ""
        "Llm/Call/CallDetail?callId=" + CALL                      | '{&quot;req&quot;:true}'     | '{&quot;resp&quot;:true}'
        "Llm/Usage"                                               | "test-model"                 | ""
        "Llm/Profile"                                             | "Runtime Switches"           | "llm_skill_auto_admit"
        "Llm/Skill/SkillList?statusId=LsksProposed"               | SKILL_NAME                   | "Shipped Skill Files"
        "Llm/Skill/SkillDetail?skillId=" + SKILL                  | "Step one."                  | "A lesson"
        "Llm/Skill/LessonList"                                    | "Send requireInventory false" | ""
        "Llm/Skill/RetrievalTest?query=create+user+account"       | "create-user-account"        | "Text Added to the Prompt"
    }

    def "approve marks the skill active and human-reviewed with the chosen risk"() {
        when:
        ec.service.sync().name("org.moqui.impl.LlmAdminServices.approve#LlmSkill")
                .parameters([skillId: SKILL, riskId: "LskConfirm"]).call()
        def skill = ec.entity.find("moqui.llm.LlmSkill").condition("skillId", SKILL).useCache(false).one()
        then:
        !ec.message.hasError()
        skill.statusId == "LsksActive"
        skill.provenanceId == "LskpHuman"
        skill.riskId == "LskConfirm"
        skill.speaker == ec.user.username
        skill.warrant.startsWith("Approved by")
    }

    def "approve refuses a name owned by a shipped skill file"() {
        given:
        def shipped = org.moqui.impl.llm.SkillIndex.scanShipped(ec)
        String shippedName = shipped ? shipped[0].name : null
        ec.entity.makeValue("moqui.llm.LlmSkill").setAll([skillId: "ADMTST_SHADOW", name: shippedName, title: "shadow",
                description: "shadow", body: "x", riskId: "LskConfirm", statusId: "LsksProposed", provenanceId: "LskpSim",
                version: 1]).create()

        when:
        ec.service.sync().name("org.moqui.impl.LlmAdminServices.approve#LlmSkill").parameters([skillId: "ADMTST_SHADOW"]).call()
        def row = ec.entity.find("moqui.llm.LlmSkill").condition("skillId", "ADMTST_SHADOW").useCache(false).one()

        then:
        shippedName != null
        ec.message.hasError()
        row.statusId == "LsksProposed"

        cleanup:
        ec.message.clearErrors()
        ec.entity.find("moqui.llm.LlmSkill").condition("skillId", "ADMTST_SHADOW").deleteAll()
    }

    def "editing bumps the version and leaves status and provenance alone"() {
        when:
        ec.service.sync().name("org.moqui.impl.LlmAdminServices.update#LlmSkillContent")
                .parameters([skillId: SKILL, title: "Edited title", body: "New body"]).call()
        def skill = ec.entity.find("moqui.llm.LlmSkill").condition("skillId", SKILL).useCache(false).one()
        then:
        !ec.message.hasError()
        skill.title == "Edited title"
        skill.body == "New body"
        skill.version == 2
        skill.statusId == "LsksActive"
        skill.provenanceId == "LskpHuman"
    }

    def "lesson status changes and rejects an unknown status"() {
        when:
        ec.service.sync().name("org.moqui.impl.LlmAdminServices.set#LlmLessonStatus")
                .parameters([lessonId: LESSON, statusId: "LsksActive"]).call()
        def lesson = ec.entity.find("moqui.llm.LlmLesson").condition("lessonId", LESSON).useCache(false).one()
        then:
        !ec.message.hasError()
        lesson.statusId == "LsksActive"

        when:
        ec.service.sync().name("org.moqui.impl.LlmAdminServices.set#LlmLessonStatus")
                .parameters([lessonId: LESSON, statusId: "Bogus"]).call()
        then:
        ec.message.hasError()
        cleanup:
        ec.message.clearErrors()
    }

    def "retrieval shows a shipped skill and the prompt text"() {
        when:
        def out = ec.service.sync().name("org.moqui.impl.LlmAdminServices.retrieve#LlmSkills")
                .parameters([query: "create user account", limit: 5]).call()
        then:
        out.skills.any { it.name == "create-user-account" && it.source == "shipped file" }
        out.injectText
    }

    def "profile details never include credentials"() {
        when:
        def out = ec.service.sync().name("org.moqui.impl.LlmAdminServices.get#LlmProfileDetails").call()
        String dump = out.profileDetailList.toString()
        then:
        out.profileDetailList.every { it.name && it.containsKey("hasApiKey") }
        !dump.contains("apiKey=")
        out.runtimeSwitches.containsKey("llm_trace_dump")
    }

    def "delete removes the conversation with its messages and call logs"() {
        when:
        def out = ec.service.sync().name("org.moqui.impl.LlmAdminServices.delete#LlmConversation")
                .parameters([conversationId: CONV]).call()
        then:
        !ec.message.hasError()
        out.messagesRemoved == 2L
        out.callLogsRemoved == 1L
        ec.entity.find("moqui.llm.LlmConversation").condition("conversationId", CONV).useCache(false).count() == 0
    }

    def "delete refuses a Streaming conversation"() {
        given:
        ec.entity.makeValue("moqui.llm.LlmConversation").setAll([conversationId: "ADMTST_STREAM", profileName: "default",
                statusId: "LlmcsStreaming", createdDate: ec.user.nowTimestamp]).create()
        when:
        ec.service.sync().name("org.moqui.impl.LlmAdminServices.delete#LlmConversation").parameters([conversationId: "ADMTST_STREAM"]).call()
        then:
        ec.message.hasError()
        ec.entity.find("moqui.llm.LlmConversation").condition("conversationId", "ADMTST_STREAM").useCache(false).count() == 1
        cleanup:
        ec.message.clearErrors()
        ec.entity.find("moqui.llm.LlmConversation").condition("conversationId", "ADMTST_STREAM").deleteAll()
    }
}
