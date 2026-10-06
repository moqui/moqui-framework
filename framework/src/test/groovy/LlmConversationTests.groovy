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
import org.moqui.impl.llm.LlmGateway
import org.moqui.llm.LlmException
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmStreamListener
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmToolCall
import org.moqui.llm.LlmToolResult
import org.moqui.llm.test.FakeLlmProtocol

import java.sql.Timestamp
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
        // One worker, one hit cache: the suite exceeds the 30/60s LlmProfiles tarpit on profile default.
        ec.artifactExecution.disableTarpit()
        if (!ec.user.userId) ec.user.loginUser("john.doe", "moqui")
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
    }
    def cleanup() {
        if (ec.transaction.isTransactionInPlace()) ec.transaction.rollback("test cleanup", null)
        ec.artifactExecution.enableAuthz()
        ec.artifactExecution.enableTarpit()
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

    def "yielded write_ui commits canvas before tool_call and yield events"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [FakeLlmProtocol.toolCalls(new LlmToolCall("c1", "write_ui",
                '{"title":"Order","fields":[{"name":"n","widget":"text-line","defaultValue":"1"}]}'))]
        def conv = LlmConversationImpl.create(ec, "assist", [purpose: "assist"])
        def seen = []
        def listener = new CanvasOrderListener(ec: ec, conversationId: conv.conversationId, seen: seen)
        when:
        def r = client(proto).conversation(conv).tool(LlmTool.writeUi()).allowClientTools(true)
                .user("show the order").stream(listener)
        def fresh = LlmConversationImpl.load(ec, conv.conversationId, true)
        def asst = fresh.history.find { it.role == LlmMessage.Role.ASSISTANT && it.toolCalls }
        then:
        r.yielded
        seen == ["tool_call", "yield"]
        fresh.canvasMap.title == "Order"
        fresh.canvasMap.fields[0].defaultValue == "1"
        fresh.hasCanvas()
        asst.toolCalls[0].arguments.contains("Order")
        asst.toolCalls[0].arguments.contains("schemaVersion")
        fresh.attributes.lastWriteUi == null
        EntityValue row = ec.entity.find("moqui.llm.LlmConversation")
                .condition("conversationId", conv.conversationId).one()
        row.canvasJson.contains("Order")
        row.statusId == LlmConversationImpl.STATUS_YIELDED
        !row.attributesJson?.contains("lastWriteUi")
    }

    def "writeThrough merges from canvasJson and unbound_fields does not replace it"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.results = [
                FakeLlmProtocol.toolCalls(new LlmToolCall("c1", "write_ui",
                        '{"title":"Keep","fields":[{"name":"n","widget":"text-line","defaultValue":"1"}]}')),
                FakeLlmProtocol.toolCalls(new LlmToolCall("c2", "write_ui",
                        '{"title":"Nope","writeThrough":true,"actions":[{"id":"go","method":"POST","path":"/rest/s1/x","bodyFromFields":["missing"]}]}')),
                FakeLlmProtocol.stop("still here"),
                FakeLlmProtocol.toolCalls(new LlmToolCall("c3", "write_ui",
                        '{"writeThrough":true,"fields":[{"name":"n","widget":"text-line","defaultValue":"9"}]}'))
        ]
        def conv = LlmConversationImpl.create(ec, "assist", [purpose: "assist"])
        when:
        client(proto).conversation(conv).tool(LlmTool.writeUi()).allowClientTools(true).user("form").call()
        client(proto).conversation(conv).tool(LlmTool.writeUi()).allowClientTools(true)
                .toolResults([new LlmToolResult("c1", "write_ui", [submitted: false])]).call()
        def afterReject = LlmConversationImpl.load(ec, conv.conversationId, true)
        then:
        afterReject.canvasMap.title == "Keep"
        afterReject.canvasMap.fields[0].defaultValue == "1"
        when:
        client(proto).conversation(conv).tool(LlmTool.writeUi()).allowClientTools(true).user("set 9").call()
        def merged = LlmConversationImpl.load(ec, conv.conversationId, true)
        then:
        merged.canvasMap.fields[0].defaultValue == "9"
        merged.canvasMap.title == "Keep"
    }

    def "legacy lastWriteUi moves into canvasJson on load"() {
        given:
        def conv = LlmConversationImpl.create(ec, "assist", [purpose: "assist"])
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        EntityValue ev = ec.entity.find("moqui.llm.LlmConversation")
                .condition("conversationId", conv.conversationId).one()
        ev.attributesJson = '{"purpose":"assist","lastWriteUi":{"title":"Old","kind":"form","fields":[{"name":"n","widget":"text-line"}]}}'
        ev.canvasJson = null
        ev.summary = "kept"
        ev.update()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        when:
        def loaded = LlmConversationImpl.load(ec, conv.conversationId, true)
        EntityValue again = ec.entity.find("moqui.llm.LlmConversation")
                .condition("conversationId", conv.conversationId).one()
        then:
        loaded.canvasMap.title == "Old"
        loaded.attributes.lastWriteUi == null
        again.canvasJson.contains("Old")
        again.hasCanvas == "Y"
        !again.attributesJson.contains("lastWriteUi")
    }

    def "conversation list is 20 per page, newest first, and search hits searchText"() {
        given:
        String purpose = "assist-page-test"
        long base = 1_700_000_000_000L
        List<String> ids = []
        if (!ec.transaction.isTransactionInPlace()) ec.transaction.begin(null)
        25.times { i ->
            EntityValue ev = ec.entity.makeValue("moqui.llm.LlmConversation")
            ev.setSequencedIdPrimary()
            ev.profileName = "assist"
            ev.userId = ec.user.userId
            ev.statusId = LlmConversationImpl.STATUS_COMPLETE
            ev.purpose = purpose
            ev.summary = "Order note ${i}"
            ev.title = i == 3 ? "qq" : "Note ${i}"
            ev.searchText = i == 7 ? "zephyrwidget order ${i}" : (i == 8 ? "qqhidden" : "order note ${i}")
            ev.createdDate = new Timestamp(base + i * 1000L)
            ev.lastMessageDate = ev.createdDate
            ev.messageCount = i + 1
            ev.hasCanvas = (i % 2 == 0) ? "Y" : "N"
            ev.create()
            ids << ev.conversationId
        }
        EntityValue other = ec.entity.makeValue("moqui.llm.LlmConversation")
        other.setSequencedIdPrimary()
        other.profileName = "assist"
        other.userId = "OTHER_USER_LIST"
        other.statusId = LlmConversationImpl.STATUS_COMPLETE
        other.purpose = purpose
        other.summary = "someone else"
        other.searchText = "someone else"
        other.createdDate = new Timestamp(base - 1000L)
        other.lastMessageDate = other.createdDate
        other.messageCount = 1
        other.hasCanvas = "N"
        other.create()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        boolean admin = ec.user.isInGroup("ADMIN")
        when:
        def page0 = LlmGateway.listConversations(ec, "assist", purpose, null, 0)
        def page1 = LlmGateway.listConversations(ec, "assist", purpose, null, 1)
        def found = LlmGateway.listConversations(ec, "assist", purpose, "zephyrwidget", 0)
        def shortTitle = LlmGateway.listConversations(ec, "assist", purpose, "qq", 0)
        then:
        page0.pageSize == 20
        page0.conversations.size() == 20
        page0.totalCount == (admin ? 26 : 25)
        page0.conversations[0].conversationId == ids[24]
        page0.conversations[0].hasCanvas == true
        page0.conversations[0].createdDate == base + 24 * 1000L
        page1.conversations.size() == (admin ? 6 : 5)
        page1.conversations*.conversationId.contains(ids[0])
        admin || !(page0.conversations*.conversationId + page1.conversations*.conversationId).contains(other.conversationId)
        !admin || page1.conversations*.conversationId.contains(other.conversationId)
        found.totalCount == 1
        found.conversations[0].conversationId == ids[7]
        shortTitle.totalCount == 1
        shortTitle.conversations[0].conversationId == ids[3]
        cleanup:
        ec.entity.find("moqui.llm.LlmConversation").condition("purpose", purpose).disableAuthz().deleteAll()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
    }

    def "listing a page past the end returns the last page that has rows"() {
        given:
        String purpose = "assist-clamp-test"
        if (!ec.transaction.isTransactionInPlace()) ec.transaction.begin(null)
        21.times { i ->
            EntityValue ev = ec.entity.makeValue("moqui.llm.LlmConversation")
            ev.setSequencedIdPrimary()
            ev.profileName = "assist"
            ev.userId = ec.user.userId
            ev.statusId = LlmConversationImpl.STATUS_COMPLETE
            ev.purpose = purpose
            ev.summary = "clamp ${i}"
            ev.createdDate = new Timestamp(1_700_100_000_000L + i * 1000L)
            ev.lastMessageDate = ev.createdDate
            ev.create()
        }
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        when:
        def page1 = LlmGateway.listConversations(ec, "assist", purpose, null, 1)
        def loneId = page1.conversations[0].conversationId
        ec.entity.find("moqui.llm.LlmConversation").condition("conversationId", loneId).disableAuthz().deleteAll()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        def after = LlmGateway.listConversations(ec, "assist", purpose, null, 1)
        then:
        page1.conversations.size() == 1
        page1.totalCount == 21
        after.pageIndex == 0
        after.totalCount == 20
        after.conversations.size() == 20
        cleanup:
        ec.entity.find("moqui.llm.LlmConversation").condition("purpose", purpose).disableAuthz().deleteAll()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
    }

    def "a non-admin with no user id gets an empty conversation list"() {
        given:
        String purpose = "assist-null-user-test"
        if (!ec.transaction.isTransactionInPlace()) ec.transaction.begin(null)
        EntityValue ev = ec.entity.makeValue("moqui.llm.LlmConversation")
        ev.setSequencedIdPrimary()
        ev.profileName = "assist"
        ev.userId = ec.user.userId
        ev.statusId = LlmConversationImpl.STATUS_COMPLETE
        ev.purpose = purpose
        ev.summary = "owned"
        ev.createdDate = new Timestamp(1_700_200_000_000L)
        ev.lastMessageDate = ev.createdDate
        ev.create()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        def infoField = ec.user.class.getDeclaredField("currentInfo")
        infoField.accessible = true
        def info = infoField.get(ec.user)
        def userIdField = info.class.getDeclaredField("userId")
        userIdField.accessible = true
        def saved = userIdField.get(info)
        when:
        userIdField.set(info, null)
        LlmGateway.listConversations(ec, "assist", purpose, null, 3)
        then:
        thrown(LlmException)
        cleanup:
        if (info != null && userIdField != null) userIdField.set(info, saved)
        ec.entity.find("moqui.llm.LlmConversation").condition("purpose", purpose).disableAuthz().deleteAll()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
    }

    def "messageCount skips system and context rows"() {
        when:
        def conv = LlmConversationImpl.create(ec, "assist", [purpose: "assist-count-test"])
        conv.replaceSystem("system prompt")
        conv.injectContext("pin", "pinned facts")
        conv.appendUser("hello")
        conv.appendAssistant("hi")
        EntityValue ev = ec.entity.find("moqui.llm.LlmConversation")
                .condition("conversationId", conv.conversationId).one()
        long stored = ec.entity.find("moqui.llm.LlmMessage")
                .condition("conversationId", conv.conversationId).count()
        then:
        stored > 2
        ev.messageCount == 2
        cleanup:
        ec.entity.find("moqui.llm.LlmMessage").condition("conversationId", conv.conversationId).disableAuthz().deleteAll()
        ec.entity.find("moqui.llm.LlmConversation").condition("conversationId", conv.conversationId).disableAuthz().deleteAll()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
    }

    def "summary uses the model once when the profile flag is on and keeps the truncation when it fails"() {
        given:
        String longText = "Please approve sales order 55300 for the retail customer Zizi and include the open lines " +
                "that are still waiting in the warehouse this afternoon"
        def proto = new FakeLlmProtocol()
        Integer summaryTimeout = null
        Boolean summaryRetry = null
        Integer turnTimeout = null
        proto.handler = { req ->
            def sys = req.window.find { it.role == LlmMessage.Role.SYSTEM }
            if (sys?.content?.contains("100 characters")) {
                summaryTimeout = req.timeoutSeconds
                summaryRetry = req.timeoutRetry
                return FakeLlmProtocol.stop("Approve order 55300")
            }
            turnTimeout = req.timeoutSeconds
            return FakeLlmProtocol.stop("ok")
        }
        def profile = LlmFacadeImpl.ProfileState.forTest("assist", proto, "test-model", false, 2, 0f, 5,
                [], false, false, false, false, true, false, true)
        def conv = LlmConversationImpl.create(ec, "assist", [purpose: "assist"])
        when:
        new LlmClientImpl(ec, profile, { ec.transaction.isTransactionInPlace() }).conversation(conv).user(longText).call()
        def loaded = LlmConversationImpl.load(ec, conv.conversationId, true)
        then:
        loaded.summary == "Approve order 55300"
        loaded.isSummaryFromLlm()
        summaryTimeout == 12
        summaryRetry == false
        turnTimeout == 120
        loaded.searchText.contains("Approve order 55300")
        loaded.searchText.contains("55300")
        proto.chatCount == 2
        ec.entity.find("moqui.llm.LlmCallLog").condition("conversationId", conv.conversationId).count() == 2
        when:
        def failProto = new FakeLlmProtocol()
        failProto.handler = { req ->
            def sys = req.window.find { it.role == LlmMessage.Role.SYSTEM }
            if (sys?.content?.contains("100 characters")) throw new RuntimeException("summary down")
            return FakeLlmProtocol.stop("ok")
        }
        def failProfile = LlmFacadeImpl.ProfileState.forTest("assist", failProto, "test-model", false, 2, 0f, 5,
                [], false, false, false, false, true, false, true)
        def failed = LlmConversationImpl.create(ec, "assist", [purpose: "assist"])
        new LlmClientImpl(ec, failProfile, { ec.transaction.isTransactionInPlace() }).conversation(failed).user(longText).call()
        def failedLoaded = LlmConversationImpl.load(ec, failed.conversationId, true)
        then:
        failedLoaded.summary.endsWith("...")
        failedLoaded.summary.length() == 100
        failedLoaded.isSummaryFromLlm()
        failProto.chatCount == 2
        when:
        def offProto = new FakeLlmProtocol()
        def off = LlmConversationImpl.create(ec, "assist", [purpose: "assist"])
        client(offProto).conversation(off).user(longText).call()
        def offLoaded = LlmConversationImpl.load(ec, off.conversationId, true)
        then:
        offLoaded.summary.endsWith("...")
        offLoaded.summary.length() == 100
        !offLoaded.isSummaryFromLlm()
        offProto.chatCount == 1
    }

    def "delete removes messages and refuses a streaming conversation"() {
        given:
        def conv = LlmConversationImpl.create(ec, "assist", [purpose: "assist"])
        conv.appendUser("hello there")
        String id = conv.conversationId
        when:
        def removed = LlmGateway.deleteConversation(ec, id)
        then:
        removed.httpStatus == 200
        removed.deleted == true
        ec.entity.find("moqui.llm.LlmConversation").condition("conversationId", id).one() == null
        ec.entity.find("moqui.llm.LlmMessage").condition("conversationId", id).count() == 0
        when:
        def streaming = LlmConversationImpl.create(ec, "assist", [purpose: "assist"])
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        EntityValue ev = ec.entity.find("moqui.llm.LlmConversation")
                .condition("conversationId", streaming.conversationId).one()
        ev.statusId = LlmConversationImpl.STATUS_STREAMING
        ev.update()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        def denied = LlmGateway.deleteConversation(ec, streaming.conversationId)
        then:
        denied.httpStatus == 409
        denied.deleted == false
        ec.entity.find("moqui.llm.LlmConversation")
                .condition("conversationId", streaming.conversationId).one() != null
    }

    def "Streaming with a live claim stays single-flight, and an abandoned row can continue"() {
        given:
        def conv = LlmConversationImpl.create(ec, "assist", [purpose: "assist"])
        String id = conv.conversationId
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        EntityValue ev = ec.entity.find("moqui.llm.LlmConversation").condition("conversationId", id).one()
        ev.statusId = LlmConversationImpl.STATUS_STREAMING
        ev.update()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        LlmFacadeImpl facade = (LlmFacadeImpl) ec.getLlm()
        long token = facade.claimTurn(id)
        facade.releaseTurn(id, 0L)
        def blocked = new FakeLlmProtocol(failIfInvoked: true)
        when:
        client(blocked).conversation(id).user("hi").call()
        then:
        facade.turnClaimed(id)
        LlmException busy = thrown()
        busy.httpStatus == 409
        blocked.chatCount == 0
        when:
        client(blocked).conversation(id).user("hi").call()
        then:
        LlmException stillBusy = thrown()
        stillBusy.httpStatus == 409
        blocked.chatCount == 0
        when:
        facade.releaseTurn(id, token)
        def proto = new FakeLlmProtocol()
        proto.results = [FakeLlmProtocol.stop("continued")]
        def r = client(proto).conversation(id).user("again").call()
        def loaded = LlmConversationImpl.load(ec, id, true)
        then:
        !facade.turnClaimed(id)
        r.content == "continued"
        loaded.status == LlmConversationImpl.STATUS_COMPLETE
        loaded.history.find { it.role == LlmMessage.Role.USER && it.content == "again" } != null
    }
}

class CanvasOrderListener implements LlmStreamListener {
    ExecutionContext ec
    String conversationId
    List seen = []

    @Override
    void onToolCall(LlmToolCall call, org.moqui.llm.LlmTool.Execution execution) {
        EntityValue row = ec.entity.find("moqui.llm.LlmConversation")
                .condition("conversationId", conversationId).one()
        assert row.canvasJson?.contains("Order")
        assert row.statusId == LlmConversationImpl.STATUS_YIELDED
        assert row.attributesJson == null || !row.attributesJson.contains("lastWriteUi")
        seen << "tool_call"
    }

    @Override
    void onYield(List pending) { seen << "yield" }
}
