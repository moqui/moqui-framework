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
import org.moqui.impl.llm.EnterSimTool
import org.moqui.impl.llm.LlmClientImpl
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.SkillIndex
import org.moqui.llm.LlmMessage
import org.moqui.llm.LlmProtocol.ProtocolRequest
import org.moqui.llm.LlmResponse
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmToolCall
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
class LlmSkillAgentTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        boolean d = ec.artifactExecution.disableAuthz()
        boolean b = ec.transaction.begin(null)
        try {
            ensureEnum("LlmSkillStatus", "LsksActive", "Active")
            ensureEnum("LlmSkillStatus", "LsksProposed", "Proposed")
            ensureEnum("LlmSkillRisk", "LskReversible", "Reversible")
            ensureEnum("LlmSkillRisk", "LskConfirm", "Confirm")
            ensureEnum("LlmSkillProvenance", "LskpSim", "Sim")
            ensureEnum("LlmSkillProvenance", "LskpMixed", "Mixed")
            if (b) ec.transaction.commit()
        } catch (Throwable t) {
            if (b) ec.transaction.rollback("seed llm skill enums", t)
            throw t
        } finally {
            if (!d) ec.artifactExecution.enableAuthz()
        }
    }
    def cleanupSpec() {
        ec.destroy()
    }
    def setup() {
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        ec.artifactExecution.disableAuthz()
    }
    def cleanup() {
        if (ec.entity.isTxCacheActive()) ec.entity.stopTxCache()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        ec.artifactExecution.enableAuthz()
    }

    def "matching skill does not enter sim"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.handler = { ProtocolRequest req ->
            def last = lastTool(req)
            if (last?.name == "find_skill") {
                assert last.content.contains("create-user-account")
                return FakeLlmProtocol.stop("follow create-user-account")
            }
            return FakeLlmProtocol.toolCalls(new LlmToolCall("f1", "find_skill",
                    '{"query":"create user account"}'))
        }
        LlmClientImpl client = agent(proto)

        when:
        LlmResponse r = client.user("create a user account").call()

        then:
        r.content == "follow create-user-account"
        proto.chatCount == 2
        !proto.lastRequest.window.any { it.role == LlmMessage.Role.TOOL && it.name == "enter_sim" }
    }

    def "skill miss enters sim, nested write does not persist, proposed skill admits after world pass"() {
        given:
        String stamp = Long.toString(System.currentTimeMillis())
        String skillName = "create-test-entity-" + stamp
        String simId = "SKLS" + stamp
        String worldId = "SKLW" + stamp
        def proto = new FakeLlmProtocol()
        proto.handler = { ProtocolRequest req ->
            boolean sim = isSim(req)
            def last = lastTool(req)
            if (sim) {
                if (last?.name == "run_service") {
                    return FakeLlmProtocol.stop("""---
name: ${skillName}
description: Create a moqui.test.TestEntity
risk: reversible
---
Call run_service create#moqui.test.TestEntity with testId and testMedium.
""")
                }
                String args = '{"serviceName":"create#moqui.test.TestEntity","parameters":{"testId":"' +
                        simId + '","testMedium":"from-sim"}}'
                return FakeLlmProtocol.toolCalls(new LlmToolCall("sim1", "run_service", args))
            }
            if (last?.name == "find_skill") {
                return FakeLlmProtocol.toolCalls(new LlmToolCall("e1", "enter_sim",
                        '{"goal":"create TestEntity","success_criteria":"row exists","max_iterations":6}'))
            }
            if (last?.name == "enter_sim") {
                String args = '{"serviceName":"create#moqui.test.TestEntity","parameters":{"testId":"' +
                        worldId + '","testMedium":"from-world"}}'
                return FakeLlmProtocol.toolCalls(new LlmToolCall("w1", "run_service", args))
            }
            if (last?.name == "run_service") {
                return FakeLlmProtocol.stop("world created " + worldId)
            }
            return FakeLlmProtocol.toolCalls(new LlmToolCall("f1", "find_skill",
                    '{"query":"frobnicate widget"}'))
        }
        LlmClientImpl client = agent(proto)

        when:
        LlmResponse r = client.user("frobnicate a widget").call()
        EntityValue simRow = ec.entity.find("moqui.test.TestEntity").condition("testId", simId).one()
        EntityValue worldRow = ec.entity.find("moqui.test.TestEntity").condition("testId", worldId).one()
        EntityValue proposed = ec.entity.find("moqui.llm.LlmSkill").condition("name", skillName).useCache(false).one()

        then:
        r.content == "world created " + worldId
        simRow == null
        worldRow != null
        worldRow.testMedium == "from-world"
        proposed != null
        proposed.statusId == "LsksActive"
        proposed.provenanceId == "LskpMixed"
        (proposed.worldSuccessCount as Number).longValue() >= 1L
        def enterTool = proto.lastRequest.window.findAll { it.role == LlmMessage.Role.TOOL && it.name == "enter_sim" }
        enterTool
        enterTool[-1].content.contains("proposedSkillId")
        enterTool[-1].content.contains("NOT selected")
        enterTool[-1].content.contains("select=" + skillName)

        cleanup:
        boolean d = ec.artifactExecution.disableAuthz()
        boolean b = ec.transaction.begin(null)
        try {
            ec.entity.find("moqui.test.TestEntity").condition("testId", simId).one()?.delete()
            ec.entity.find("moqui.test.TestEntity").condition("testId", worldId).one()?.delete()
            def sk = ec.entity.find("moqui.llm.LlmSkill").condition("name", skillName).useCache(false).one()
            if (sk != null) {
                ec.entity.find("moqui.llm.LlmSkillUse").condition("skillId", sk.skillId).deleteAll()
                sk.delete()
            }
            if (b) ec.transaction.commit()
        } catch (Throwable t) {
            if (b) ec.transaction.rollback("skill agent cleanup", t)
            throw t
        } finally {
            if (!d) ec.artifactExecution.enableAuthz()
        }
    }

    def "john.doe with authz enabled can persist and retrieve LlmSkill"() {
        given:
        ec.artifactExecution.enableAuthz()
        if (!ec.user.userId) assert ec.user.loginUser("john.doe", "moqui")
        String name = "authz-skill-" + System.currentTimeMillis()
        def doc = SkillIndex.parseMarkdown("---\nname: ${name}\ndescription: authz check\nrisk: reversible\n---\nsteps", null)

        when:
        boolean began = ec.transaction.begin(null)
        EntityValue persisted = SkillIndex.persistProposed(ec, doc, "steps")
        EntityValue admitted = SkillIndex.admitWorldPass(ec, name)
        if (began) ec.transaction.commit()
        def docs = SkillIndex.retrieve(ec, name, 5)

        then:
        persisted != null
        persisted.skillId
        admitted.statusId == "LsksActive"
        docs.any { it.name == name }

        cleanup:
        boolean d = ec.artifactExecution.disableAuthz()
        boolean b = ec.transaction.begin(null)
        try {
            def sk = ec.entity.find("moqui.llm.LlmSkill").condition("name", name).useCache(false).one()
            if (sk != null) {
                ec.entity.find("moqui.llm.LlmSkillUse").condition("skillId", sk.skillId).deleteAll()
                sk.delete()
            }
            if (b) ec.transaction.commit()
        } catch (Throwable t) {
            if (b) ec.transaction.rollback("authz skill cleanup", t)
            throw t
        } finally {
            if (!d) ec.artifactExecution.enableAuthz()
        }
    }

    def "force skill use refuses browse until select, does not auto enter_sim"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.handler = { ProtocolRequest req ->
            def last = lastTool(req)
            if (last?.name == "browse") return FakeLlmProtocol.stop("browsed")
            if (last?.name == "find_skill" && last.content?.contains("create-user-account")
                    && last.content?.contains("selected")) {
                return FakeLlmProtocol.toolCalls(new LlmToolCall("b1", "browse", '{"path":"/qapps"}'))
            }
            if (last?.name == "find_skill") {
                return FakeLlmProtocol.toolCalls(new LlmToolCall("b0", "browse", '{"path":"/qapps"}'))
            }
            return FakeLlmProtocol.toolCalls(new LlmToolCall("f1", "find_skill",
                    '{"query":"create user","select":"create-user-account"}'))
        }
        LlmClientImpl client = agent(proto).forceSkillUse(true)
        client.tool(LlmTool.browse())

        when:
        LlmResponse r = client.user("create a user").call()

        then:
        r.content == "browsed"
        client.getActiveSkillName() == "create-user-account"
        !proto.lastRequest.window.any { it.role == LlmMessage.Role.TOOL && it.name == "enter_sim" }
        r.toolResults.any { it.name == "browse" && it.content instanceof Map && ((Map) it.content).error != "skill_required" }
    }

    def "force skill use refuses browse with no skill and does not enter sim"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.handler = { ProtocolRequest req ->
            def last = lastTool(req)
            if (last?.name == "browse") return FakeLlmProtocol.stop("after browse")
            return FakeLlmProtocol.toolCalls(new LlmToolCall("b1", "browse", '{"path":"/qapps"}'))
        }
        LlmClientImpl client = agent(proto).forceSkillUse(true)
        client.tool(LlmTool.browse())

        when:
        LlmResponse r = client.user("look around").call()

        then:
        r.content == "after browse"
        client.getActiveSkillName() == null
        !proto.lastRequest.window.any { it.role == LlmMessage.Role.TOOL && it.name == "enter_sim" }
        def browse = r.toolResults.find { it.name == "browse" }
        browse != null
        browse.content instanceof Map
        ((Map) browse.content).error == "skill_required"
        ((Map) browse.content).instruction.toString().contains("enter_sim")
    }

    def "force off still allows browse without a skill"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.handler = { ProtocolRequest req ->
            def last = lastTool(req)
            if (last?.name == "browse") return FakeLlmProtocol.stop("ok")
            return FakeLlmProtocol.toolCalls(new LlmToolCall("b1", "browse", '{"path":"/qapps"}'))
        }
        LlmClientImpl client = agent(proto)
        client.tool(LlmTool.browse())

        when:
        LlmResponse r = client.user("look around").call()

        then:
        r.content == "ok"
        def browse = r.toolResults.find { it.name == "browse" }
        browse != null
        !(browse.content instanceof Map && ((Map) browse.content).error == "skill_required")
    }

    def "force skill use refuses write_ui without yield"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.handler = { ProtocolRequest req ->
            def last = lastTool(req)
            if (last?.name == "write_ui") return FakeLlmProtocol.stop("no canvas")
            return FakeLlmProtocol.toolCalls(new LlmToolCall("w1", "write_ui",
                    '{"title":"Hi","fields":[{"name":"n","widget":"text-line"}]}'))
        }
        LlmClientImpl client = agent(proto).forceSkillUse(true)
        client.allowClientTools(true)
        client.tool(LlmTool.writeUi())

        when:
        LlmResponse r = client.user("show a form").call()

        then:
        !r.yielded
        r.content == "no canvas"
        def ui = r.toolResults.find { it.name == "write_ui" }
        ui != null
        ui.content instanceof Map
        ((Map) ui.content).error == "skill_required"
    }

    def "unknown select does not activate; subsequent browse still refused"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.handler = { ProtocolRequest req ->
            def last = lastTool(req)
            if (last?.name == "browse") return FakeLlmProtocol.stop("after")
            if (last?.name == "find_skill")
                return FakeLlmProtocol.toolCalls(new LlmToolCall("b1", "browse", '{"path":"/qapps"}'))
            return FakeLlmProtocol.toolCalls(new LlmToolCall("f1", "find_skill",
                    '{"select":"no-such-skill-xyz"}'))
        }
        LlmClientImpl client = agent(proto).forceSkillUse(true)
        client.tool(LlmTool.browse())

        when:
        LlmResponse r = client.user("do a thing").call()

        then:
        client.getActiveSkillName() == null
        r.toolResults.any { it.name == "find_skill" && it.content instanceof Map && ((Map) it.content).error == "unknown_skill" }
        r.toolResults.any { it.name == "browse" && it.content instanceof Map && ((Map) it.content).error == "skill_required" }
    }

    def "find_skill select only activates shipped skill"() {
        given:
        def proto = new FakeLlmProtocol()
        proto.handler = { ProtocolRequest req ->
            def last = lastTool(req)
            if (last?.name == "find_skill") return FakeLlmProtocol.stop("selected")
            return FakeLlmProtocol.toolCalls(new LlmToolCall("f1", "find_skill",
                    '{"select":"create-user-account"}'))
        }
        LlmClientImpl client = agent(proto).forceSkillUse(true)

        when:
        LlmResponse r = client.user("use create user skill").call()

        then:
        r.content == "selected"
        client.getActiveSkillName() == "create-user-account"
        def fs = r.toolResults.find { it.name == "find_skill" }
        fs != null
        fs.content instanceof Map
        ((Map) fs.content).selected instanceof Map
        ((Map) ((Map) fs.content).selected).name == "create-user-account"
    }

    def "force on: sim nested writes run, parent write without select is refused"() {
        given:
        String stamp = Long.toString(System.currentTimeMillis())
        String skillName = "force-sim-skill-" + stamp
        String simId = "FSIM" + stamp
        def proto = new FakeLlmProtocol()
        proto.handler = { ProtocolRequest req ->
            boolean sim = isSim(req)
            def last = lastTool(req)
            if (sim) {
                if (last?.name == "run_service") {
                    return FakeLlmProtocol.stop("""---
name: ${skillName}
description: Force-gate sim skill
risk: reversible
---
Call run_service create#moqui.test.TestEntity.
""")
                }
                String args = '{"serviceName":"create#moqui.test.TestEntity","parameters":{"testId":"' +
                        simId + '","testMedium":"from-sim"}}'
                return FakeLlmProtocol.toolCalls(new LlmToolCall("sim1", "run_service", args))
            }
            if (last?.name == "run_service") return FakeLlmProtocol.stop("parent saw refusal")
            if (last?.name == "enter_sim") {
                String args = '{"serviceName":"create#moqui.test.TestEntity","parameters":{"testId":"' +
                        simId + 'W","testMedium":"from-world"}}'
                return FakeLlmProtocol.toolCalls(new LlmToolCall("w1", "run_service", args))
            }
            if (last?.name == "find_skill") {
                return FakeLlmProtocol.toolCalls(new LlmToolCall("e1", "enter_sim",
                        '{"goal":"create TestEntity"}'))
            }
            return FakeLlmProtocol.toolCalls(new LlmToolCall("f1", "find_skill",
                    '{"query":"frobnicate"}'))
        }
        LlmClientImpl client = agent(proto).forceSkillUse(true)
        client.maxIterations(16)

        when:
        LlmResponse r = client.user("frobnicate").call()
        EntityValue simRow = ec.entity.find("moqui.test.TestEntity").condition("testId", simId).one()
        EntityValue worldRow = ec.entity.find("moqui.test.TestEntity").condition("testId", simId + "W").one()
        def enterTool = proto.lastRequest.window.findAll { it.role == LlmMessage.Role.TOOL && it.name == "enter_sim" }

        then:
        r.content == "parent saw refusal"
        simRow == null
        worldRow == null
        client.getActiveSkillName() == null
        enterTool
        enterTool[-1].content.contains("proposedSkillId")
        enterTool[-1].content.contains("NOT selected")
        enterTool[-1].content.contains(skillName)
        r.toolResults.any { it.name == "run_service" && it.content instanceof Map && ((Map) it.content).error == "skill_required" }

        cleanup:
        boolean d = ec.artifactExecution.disableAuthz()
        boolean b = ec.transaction.begin(null)
        try {
            def sk = ec.entity.find("moqui.llm.LlmSkill").condition("name", skillName).useCache(false).one()
            if (sk != null) {
                ec.entity.find("moqui.llm.LlmSkillUse").condition("skillId", sk.skillId).deleteAll()
                sk.delete()
            }
            if (b) ec.transaction.commit()
        } catch (Throwable t) {
            if (b) ec.transaction.rollback("force sim cleanup", t)
            throw t
        } finally {
            if (!d) ec.artifactExecution.enableAuthz()
        }
    }

    def "sim exit hint names skill id and that it was not selected"() {
        expect:
        EnterSimTool.simExitHint([proposedSkillName: "n1", proposedSkillId: "SID1", proposedSkillStatus: "LsksProposed"])
                .contains("skillId=SID1")
        EnterSimTool.simExitHint([proposedSkillName: "n1", proposedSkillId: "SID1", proposedSkillStatus: "LsksProposed"])
                .contains("NOT selected")
        EnterSimTool.simExitHint([proposedSkillName: "n1", proposedSkillId: "SID1", proposedSkillStatus: "LsksProposed"])
                .contains("select=n1")
        EnterSimTool.simExitHint([:]).contains("No skill was persisted")
    }

    def "getByName finds shipped and proposed skills"() {
        given:
        String name = "getbyname-" + System.currentTimeMillis()
        def doc = SkillIndex.parseMarkdown("---\nname: ${name}\ndescription: proposed lookup\nrisk: reversible\n---\nsteps", null)

        when:
        boolean began = ec.transaction.begin(null)
        EntityValue persisted = SkillIndex.persistProposed(ec, doc, "steps")
        if (began) ec.transaction.commit()
        def shipped = SkillIndex.getByName(ec, "create-user-account")
        def proposed = SkillIndex.getByName(ec, name)
        def missing = SkillIndex.getByName(ec, "no-such-skill-xyz")

        then:
        shipped?.name == "create-user-account"
        persisted != null
        proposed?.name == name
        proposed?.skillId == persisted.skillId
        missing == null

        cleanup:
        boolean d = ec.artifactExecution.disableAuthz()
        boolean b = ec.transaction.begin(null)
        try {
            def sk = ec.entity.find("moqui.llm.LlmSkill").condition("name", name).useCache(false).one()
            sk?.delete()
            if (b) ec.transaction.commit()
        } catch (Throwable t) {
            if (b) ec.transaction.rollback("getByName cleanup", t)
            throw t
        } finally {
            if (!d) ec.artifactExecution.enableAuthz()
        }
    }

    private LlmClientImpl agent(FakeLlmProtocol proto) {
        def profile = LlmFacadeImpl.ProfileState.forTest("default", proto, "test-model", false, 2, 0f, 5)
        LlmClientImpl c = new LlmClientImpl(ec, profile, { false })
        c.tool(LlmTool.findSkill())
        c.tool(LlmTool.enterSim())
        c.tool(LlmTool.runService())
        c.maxIterations(12)
        return c
    }

    private void ensureEnum(String typeId, String enumId, String description) {
        if (ec.entity.find("moqui.basic.Enumeration").condition("enumId", enumId).one() != null) return
        if (ec.entity.find("moqui.basic.EnumerationType").condition("enumTypeId", typeId).one() == null) {
            ec.entity.makeValue("moqui.basic.EnumerationType")
                    .set("enumTypeId", typeId).set("description", typeId).create()
        }
        ec.entity.makeValue("moqui.basic.Enumeration")
                .set("enumId", enumId).set("enumTypeId", typeId).set("description", description).create()
    }

    private static boolean isSim(ProtocolRequest req) {
        return req?.window?.any { it.role == LlmMessage.Role.SYSTEM && it.content?.contains("You are in sim") }
    }
    private static LlmMessage lastTool(ProtocolRequest req) {
        def tools = req?.window?.findAll { it.role == LlmMessage.Role.TOOL }
        return tools ? tools[-1] : null
    }
}
