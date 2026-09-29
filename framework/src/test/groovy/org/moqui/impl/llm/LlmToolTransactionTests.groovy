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
package org.moqui.impl.llm

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.llm.LlmTool
import org.moqui.llm.LlmToolCall
import org.moqui.llm.test.FakeLlmProtocol

import jakarta.transaction.Status
import spock.lang.Shared
import spock.lang.Specification

class LlmToolTransactionTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
    }

    def cleanupSpec() {
        ec?.destroy()
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
        deleteSeq("LLM_TOOL_TX_OUTER")
        deleteSeq("LLM_TOOL_TX_FAIL")
        deleteSeq("LLM_TOOL_TX_OK")
    }

    def cleanup() {
        try {
            if (ec.transaction.isTransactionInPlace()) ec.transaction.rollback("test cleanup", null)
            deleteSeq("LLM_TOOL_TX_OUTER")
            deleteSeq("LLM_TOOL_TX_FAIL")
            deleteSeq("LLM_TOOL_TX_OK")
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    def "a tool that marks rollback-only does not poison the caller transaction"() {
        when:
        boolean began = ec.transaction.begin(null)
        writeSeq("LLM_TOOL_TX_OUTER", 1)
        Object result = execute(new RollbackTool())

        then:
        result instanceof Map
        ((Map) result).get("error").toString().contains("vendorPartyId")
        ec.transaction.status == Status.STATUS_ACTIVE
        ec.transaction.commit(began)
        countSeq("LLM_TOOL_TX_OUTER") == 1
        countSeq("LLM_TOOL_TX_FAIL") == 0
    }

    def "a tool commit survives rollback of the caller transaction"() {
        when:
        boolean began = ec.transaction.begin(null)
        writeSeq("LLM_TOOL_TX_OUTER", 2)
        Object result = execute(new CommitTool())
        ec.transaction.rollback(began, "caller rolls back", null)

        then:
        result instanceof Map
        ((Map) result).get("ok") == true
        !ec.transaction.isTransactionInPlace()
        countSeq("LLM_TOOL_TX_OK") == 1
        countSeq("LLM_TOOL_TX_OUTER") == 0
    }

    private Object execute(LlmTool tool) {
        def profile = LlmFacadeImpl.ProfileState.forTest("default", new FakeLlmProtocol(), "test-model", false, 0, 0f, 0)
        def client = new LlmClientImpl(ec, profile).tool(tool)
        return new LlmAgentLoop(client).executeOne(new LlmToolCall("t1", tool.getName(), "{}"), true)
    }

    private void writeSeq(String name, int num) {
        ec.entity.makeValue("moqui.entity.SequenceValueItem").set("seqName", name).set("seqNum", num).create()
    }

    private long countSeq(String name) {
        boolean began = ec.transaction.begin(null)
        try {
            return ec.entity.find("moqui.entity.SequenceValueItem").condition("seqName", name).count()
        } finally {
            ec.transaction.commit(began)
        }
    }

    private void deleteSeq(String name) {
        boolean began = ec.transaction.begin(null)
        try {
            ec.entity.find("moqui.entity.SequenceValueItem").condition("seqName", name).deleteAll()
        } finally {
            ec.transaction.commit(began)
        }
    }

    /** Begins its own transaction, writes, marks rollback-only, and throws. Mirrors a joined service failure. */
    static class RollbackTool implements LlmTool {
        String getName() { "boom" }
        String getDescription() { "boom" }
        Map<String, Object> getParametersSchema() { [:] }
        Execution getExecution() { Execution.SERVER }
        Object execute(Map<String, Object> arguments, ExecutionContext ec) {
            boolean began = ec.transaction.begin(null)
            ec.entity.makeValue("moqui.entity.SequenceValueItem")
                    .set("seqName", "LLM_TOOL_TX_FAIL").set("seqNum", 1).create()
            if (!began) throw new IllegalStateException("tool joined the caller transaction")
            ec.transaction.setRollbackOnly("vendorPartyId", new RuntimeException("vendorPartyId"))
            throw new RuntimeException("Cannot get property 'vendorPartyId' on null object")
        }
    }

    /** Begins its own transaction, writes, and commits before returning. */
    static class CommitTool implements LlmTool {
        String getName() { "ok" }
        String getDescription() { "ok" }
        Map<String, Object> getParametersSchema() { [:] }
        Execution getExecution() { Execution.SERVER }
        Object execute(Map<String, Object> arguments, ExecutionContext ec) {
            boolean began = ec.transaction.begin(null)
            if (!began) throw new IllegalStateException("tool joined the caller transaction")
            ec.entity.makeValue("moqui.entity.SequenceValueItem")
                    .set("seqName", "LLM_TOOL_TX_OK").set("seqNum", 7).create()
            ec.transaction.commit(began)
            return [ok: true]
        }
    }
}
