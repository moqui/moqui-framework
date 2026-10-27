/* This software is in the public domain under CC0 1.0 Universal plus a Grant of Patent License. */

import org.moqui.Moqui
import org.moqui.impl.llm.LlmConversationImpl
import org.moqui.impl.llm.LlmGateway
import org.moqui.llm.LlmException
import spock.lang.Specification

/** Steward shares the storage, but its harness owns conversation writes and retention. No Steward component needed. */
class LlmStewardIsolationTests extends Specification {
    def ec
    String prefix = "STWISO-" + UUID.randomUUID().toString().take(8)
    String stewardId = prefix + "_S"
    String normalId = prefix + "_N"

    def setup() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
        boolean began = ec.transaction.begin(null)
        try {
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId:prefix, username:prefix, userFullName:"Isolation test"]).create()
            ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userId:prefix, userGroupId:"ADMIN",
                    fromDate:ec.user.nowTimestamp]).create()
            ec.user.internalLoginUser(prefix)
            def old = new java.sql.Timestamp(System.currentTimeMillis() - 100L * 86400000)
            for (String id in [stewardId, normalId]) {
                ec.entity.makeValue("moqui.llm.LlmConversation").setAll([conversationId:id, profileName:"default",
                        userId:ec.user.userId, purpose:(id == stewardId ? "steward" : null), statusId:"LlmcsComplete",
                        createdDate:old, lastMessageDate:old, title:prefix]).create()
                ec.entity.makeValue("moqui.llm.LlmMessage").setAll([messageId:id, conversationId:id, ordinal:1,
                        role:"assistant", content:"history", sentDate:old]).create()
                ec.entity.makeValue("moqui.llm.LlmCallLog").setAll([callId:id, conversationId:id, startDate:old,
                        requestJson:'{"keep":true}', responseJson:'{"keep":true}']).create()
                ec.entity.makeValue("moqui.llm.LlmSkillUse").setAll([skillUseId:id, conversationId:id, usedDate:old]).create()
            }
        } finally { if (began) ec.transaction.commit() }
    }

    def cleanup() {
        ec.message.clearErrors()
        boolean began = ec.transaction.begin(null)
        try {
            ec.entity.find("moqui.service.message.SystemMessage").condition("systemMessageTypeId", "like", prefix + "%").deleteAll()
            ec.entity.find("moqui.service.message.SystemMessageType").condition("systemMessageTypeId", "like", prefix + "%").deleteAll()
            for (String entity in ["LlmSkillUse", "LlmCallLog", "LlmMessage", "LlmConversation"])
                ec.entity.find("moqui.llm." + entity).condition("conversationId", "in", [stewardId, normalId]).deleteAll()
            ec.entity.find("moqui.security.UserGroupMember").condition("userId", prefix).deleteAll()
            ec.entity.find("moqui.security.UserAccount").condition("userId", prefix).deleteAll()
        } finally {
            if (began) ec.transaction.commit()
            ec.destroy()
        }
    }

    def "the gateway hides harness conversations even from administrators"() {
        expect:
        LlmGateway.listConversations(ec, null, null, prefix, 0).conversations*.conversationId == [normalId]
        !LlmGateway.listConversations(ec, null, "steward", prefix, 0).conversations
    }

    def "the LLM API cannot load a harness conversation for chat or deletion"() {
        when:
        LlmConversationImpl.load(ec, stewardId, false)

        then:
        def error = thrown(LlmException)
        error.message.contains("managed by their harness")
        ec.entity.find("moqui.llm.LlmConversation").condition("conversationId", stewardId).count() == 1
    }

    def "the admin delete service preserves harness history"() {
        when:
        ec.service.sync().name("org.moqui.impl.LlmAdminServices.delete#LlmConversation")
                .parameters([conversationId:stewardId]).call()

        then:
        ec.message.errorsString.contains("managed by their harness")
        ec.entity.find("moqui.llm.LlmMessage").condition("conversationId", stewardId).count() == 1
    }

    def "cleanup removes ordinary old rows while preserving all harness history"() {
        when:
        ec.service.sync().name("org.moqui.impl.LlmServices.clean#LlmData")
                .parameters([daysToKeep:90, contentDaysToKeep:30]).call()

        then:
        !ec.message.hasError()
        ["LlmConversation", "LlmMessage", "LlmCallLog", "LlmSkillUse"].every { entity ->
            ec.entity.find("moqui.llm." + entity).condition("conversationId", stewardId).count() == 1 &&
                    ec.entity.find("moqui.llm." + entity).condition("conversationId", normalId).count() == 0
        }
        ec.entity.find("moqui.llm.LlmCallLog").condition("conversationId", stewardId).one().requestJson == '{"keep":true}'
    }

    def "a component queue can opt out of the unordered scheduled sender"() {
        given:
        Map ids = [:]
        for (String mode in ["N", "Y"]) {
            String typeId = prefix + mode
            ec.service.sync().name("create#moqui.service.message.SystemMessageType").parameters([
                    systemMessageTypeId:typeId, sendScheduled:mode, sendServiceName:"org.moqui.impl.BasicServices.noop"]).call()
            Map queued = ec.service.sync().name("org.moqui.impl.SystemMessageServices.queue#SystemMessage").parameters([
                    systemMessageTypeId:typeId, messageText:"test", sendNow:false]).call()
            ids[mode] = queued.systemMessageId
        }

        when:
        ec.service.sync().name("org.moqui.impl.SystemMessageServices.send#AllProducedSystemMessages").call()
        long end = System.currentTimeMillis() + 10000L
        while (System.currentTimeMillis() < end && ec.entity.find("moqui.service.message.SystemMessage")
                .condition("systemMessageId", ids.Y).useCache(false).one().statusId != "SmsgSent") Thread.sleep(50)

        then:
        !ec.message.hasError()
        ec.entity.find("moqui.service.message.SystemMessage").condition("systemMessageId", ids.Y).useCache(false).one().statusId == "SmsgSent"
        ec.entity.find("moqui.service.message.SystemMessage").condition("systemMessageId", ids.N).useCache(false).one().statusId == "SmsgProduced"
    }
}
