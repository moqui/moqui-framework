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
        boolean began = ec.transaction.begin(null)
        EntityValue admitted = SkillIndex.admitWorldPass(ec, skillName)
        ec.transaction.commit(began)

        then:
        r.content == "world created " + worldId
        simRow == null
        worldRow != null
        worldRow.testMedium == "from-world"
        proposed != null
        proposed.statusId == "LsksProposed"
        proposed.provenanceId == "LskpSim"
        admitted.statusId == "LsksActive"
        admitted.provenanceId == "LskpMixed"
        (admitted.worldSuccessCount as Number).longValue() >= 1L

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
