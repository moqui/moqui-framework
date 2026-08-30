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
import org.moqui.entity.EntityValue
import org.moqui.impl.llm.LlmClientImpl
import org.moqui.impl.llm.LlmConversationImpl
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.llm.LlmException
import org.moqui.llm.test.FakeLlmProtocol
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

@IgnoreIf({
    String runtime = System.getProperty("moqui.runtime") ?: "../runtime"
    String conf = System.getProperty("moqui.conf") ?: "conf/MoquiDevConf.xml"
    File direct = new File(conf)
    File nested = new File(runtime, conf.startsWith("conf/") ? conf : "conf/" + new File(conf).name)
    !direct.exists() && !nested.exists() && !new File(runtime, "conf/MoquiDevConf.xml").exists()
})

class LlmConversationTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
    }
    def cleanupSpec() {
        ec.destroy()
    }
    def setup() {
        ec.artifactExecution.disableAuthz()
        if (!ec.user.userId) ec.user.loginUser("john.doe", "moqui")
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
    }
    def cleanup() {
        if (ec.transaction.isTransactionInPlace()) ec.transaction.rollback("test cleanup", null)
        ec.artifactExecution.enableAuthz()
    }

    private LlmClientImpl client(FakeLlmProtocol proto, boolean allowTx = false,
            Closure<Boolean> tx = { ec.transaction.isTransactionInPlace() }) {
        def profile = LlmFacadeImpl.ProfileState.forTest("default", proto, "test-model", allowTx, 2, 0f, 5)
        return new LlmClientImpl(ec, profile, tx)
    }

    def "create conversation writes LlmConversation row"() {
        when:
        def conv = LlmConversationImpl.create(ec, "default", [canvasId: "c1"])
        EntityValue ev = ec.entity.find("moqui.llm.LlmConversation")
                .condition("conversationId", conv.conversationId).one()
        then:
        ev != null
        ev.statusId == LlmConversationImpl.STATUS_ACTIVE
        ev.profileName == "default"
        ev.userId == ec.user.userId
    }

    def "TX active throws and writes no Streaming row"() {
        given:
        def proto = new FakeLlmProtocol(failIfInvoked: true)
        def conv = LlmConversationImpl.create(ec, "default", null)
        String id = conv.conversationId
        LlmException thrownEx = null
        when:
        ec.transaction.begin(null)
        try {
            client(proto).conversation(conv).user("hi").call()
        } catch (LlmException e) {
            thrownEx = e
        } finally {
            if (ec.transaction.isTransactionInPlace()) ec.transaction.rollback("tx-active test", null)
        }
        EntityValue ev = ec.entity.find("moqui.llm.LlmConversation").condition("conversationId", id).one()
        long msgCount = ec.entity.find("moqui.llm.LlmMessage").condition("conversationId", id).count()
        then:
        thrownEx != null
        thrownEx.message.toLowerCase().contains("transaction")
        proto.chatCount == 0
        ev.statusId == LlmConversationImpl.STATUS_ACTIVE
        msgCount == 0
    }

    def "protocol throw after Streaming writes Failed row"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.handler = { throw new RuntimeException("provider boom") }
        def conv = LlmConversationImpl.create(ec, "default", null)
        when:
        client(proto).conversation(conv).user("hi").call()
        then:
        thrown(LlmException)
        EntityValue ev = ec.entity.find("moqui.llm.LlmConversation")
                .condition("conversationId", conv.conversationId).one()
        ev.statusId == LlmConversationImpl.STATUS_FAILED
        ec.entity.find("moqui.llm.LlmMessage").condition("conversationId", conv.conversationId)
                .condition("role", "USER").count() == 1
        ec.entity.find("moqui.llm.LlmMessage").condition("conversationId", conv.conversationId)
                .condition("role", "ASSISTANT").count() == 0
    }

    def "injectContext persists CONTEXT not SYSTEM"() {
        given:
        def conv = LlmConversationImpl.create(ec, "default", null)
        when:
        conv.replaceSystem("sys")
        conv.injectContext("entity:Party:1", "Acme Corp")
        def msgs = ec.entity.find("moqui.llm.LlmMessage")
                .condition("conversationId", conv.conversationId).orderBy("ordinal").list()
        then:
        msgs.findAll { it.role == "SYSTEM" }.size() == 1
        msgs.find { it.role == "SYSTEM" }.content == "sys"
        msgs.findAll { it.role == "CONTEXT" }.size() == 1
        msgs.find { it.role == "CONTEXT" }.content == "Acme Corp"
        EntityValue header = ec.entity.find("moqui.llm.LlmConversation")
                .condition("conversationId", conv.conversationId).one()
        header.systemText == "sys"
    }

    def "successful call writes assistant, LlmCallLog, Complete"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [FakeLlmProtocol.stop("ok")]
        def conv = LlmConversationImpl.create(ec, "default", null)
        when:
        def r = client(proto).conversation(conv).system("s").user("hi").call()
        then:
        r.content == "ok"
        EntityValue ev = ec.entity.find("moqui.llm.LlmConversation")
                .condition("conversationId", conv.conversationId).one()
        ev.statusId == LlmConversationImpl.STATUS_COMPLETE
        ev.systemText == "s"
        ec.entity.find("moqui.llm.LlmCallLog").condition("conversationId", conv.conversationId).count() == 1
        EntityValue log = ec.entity.find("moqui.llm.LlmCallLog")
                .condition("conversationId", conv.conversationId).one()
        !log.isField("artifactHitId")
        log.wasError == "N"
    }

    def "getConversation allows owner"() {
        given:
        def conv = LlmConversationImpl.create(ec, "default", null)
        when:
        def loaded = LlmConversationImpl.load(ec, conv.conversationId, true)
        then:
        loaded.conversationId == conv.conversationId
        loaded.userId == ec.user.userId
    }

    def "clean#LlmData service is registered"() {
        expect:
        ec.service.sync().name("org.moqui.impl.LlmServices.clean#LlmData")
                .parameter("daysToKeep", 90).call() != null
    }
}
