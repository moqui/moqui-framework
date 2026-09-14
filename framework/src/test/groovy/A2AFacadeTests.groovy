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
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.a2a.A2ACardBuilderImpl
import org.moqui.impl.llm.a2a.A2AException
import org.moqui.impl.llm.a2a.A2AGateway
import org.moqui.impl.llm.a2a.A2ATypes
import org.moqui.llm.a2a.A2AFacade
import org.moqui.llm.test.FakeLlmProtocol
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

import java.lang.reflect.Field

/** The public A2A API components use: ec.getA2A(). It must be one instance, enforce ownership, and return the
 *  same wire shapes as the gateway it delegates to. */
@IgnoreIf({
    String runtime = System.getProperty('moqui.runtime') ?: '../runtime'
    !new File(runtime, 'conf/MoquiDevConf.xml').exists()
})
class A2AFacadeTests extends Specification {
    static final String PROFILE = 'a2a-facade-fake'
    static final String OTHER_USER_ID = 'A2A_FACADE_OTHER'
    static final String OTHER_USERNAME = 'a2a.facade.other'
    @Shared ExecutionContext ec
    @Shared FakeLlmProtocol proto = new FakeLlmProtocol()
    @Shared String previousProfile

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        profiles().put(PROFILE, LlmFacadeImpl.ProfileState.forTest(PROFILE, proto, 'fake-model', false, 0, 0f, 0))
        previousProfile = System.getProperty('a2a_default_profile')
        System.setProperty('a2a_default_profile', PROFILE)
    }

    def cleanupSpec() {
        profiles().remove(PROFILE)
        if (previousProfile != null) System.setProperty('a2a_default_profile', previousProfile)
        else System.clearProperty('a2a_default_profile')
        ec.destroy()
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
        if (!ec.user.userId) assert ec.user.loginUser('john.doe', 'moqui')
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        clearA2AData()
        proto.results = []
        proto.streamDeltas = null
        proto.handler = null
        proto.chatCount = 0
        proto.chatStreamCount = 0
    }

    def cleanup() {
        if (ec.transaction.isTransactionInPlace()) ec.transaction.rollback('A2A facade test cleanup', null)
        clearA2AData()
        ec.artifactExecution.enableAuthz()
    }

    def 'the facade is one instance for the life of the factory'() {
        expect:
        ec.getA2A() != null
        ec.getA2A().is(ec.getA2A())
        ec.getA2A() instanceof A2AFacade
        ec.factory.getA2A().is(ec.getA2A())
    }

    def 'configuration is readable through the facade'() {
        given:
        String previousEnabled = System.getProperty('a2a_enabled')

        when:
        System.setProperty('a2a_enabled', 'false')
        boolean disabled = ec.getA2A().isEnabled()
        System.setProperty('a2a_enabled', 'true')
        boolean enabled = ec.getA2A().isEnabled()

        then:
        !disabled
        enabled
        ec.getA2A().defaultProfileName == PROFILE
        ec.getA2A().defaultProfileName == A2ATypes.defaultProfile()

        cleanup:
        if (previousEnabled != null) System.setProperty('a2a_enabled', previousEnabled)
        else System.clearProperty('a2a_enabled')
    }

    def 'sendMessage through the facade runs the task and returns the wire Task'() {
        given:
        proto.results = [FakeLlmProtocol.stop('Risposta dalla facade')]

        when:
        Map sent = ec.getA2A().sendMessage([message: message('facade-send', 'ciao')])
        Map fetched = ec.getA2A().getTask(sent.task.id as String, 10)

        then:
        sent.task.status.state == 'TASK_STATE_COMPLETED'
        sent.task.artifacts[0].parts[0].text == 'Risposta dalla facade'
        fetched.task.id == sent.task.id
        fetched.task.history*.role == ['ROLE_USER', 'ROLE_AGENT']
        proto.chatCount + proto.chatStreamCount == 1
    }

    def 'facade and gateway return the same objects for the same task'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, [profile: PROFILE, message: message('facade-same', 'x')])
        String taskId = accepted.task.id

        expect:
        ec.getA2A().getTask(taskId, 10) == A2AGateway.getTask(ec, [taskId: taskId, historyLength: 10])
        ec.getA2A().listTasks([historyLength: 0]) == A2AGateway.listTasks(ec, [historyLength: 0])
        ec.getA2A().subscribeTask(taskId, -1L, 0) == A2AGateway.subscribeTask(ec,
                [taskId: taskId, afterOrdinal: -1L, historyLength: 0])
        ec.getA2A().getExtendedAgentCard([baseUrl: 'https://facade.invalid']) ==
                A2AGateway.getExtendedAgentCard(ec, [baseUrl: 'https://facade.invalid'])
    }

    def 'cancelTask through the facade is terminal and not repeatable'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, [profile: PROFILE, message: message('facade-cancel', 'x')])
        String taskId = accepted.task.id

        when:
        Map canceled = ec.getA2A().cancelTask(taskId, 0)

        then:
        canceled.task.status.state == 'TASK_STATE_CANCELED'
        code { ec.getA2A().cancelTask(taskId, 0) } == A2AException.TASK_NOT_CANCELABLE
    }

    def 'the facade hides tasks of other users'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, [profile: PROFILE, message: message('facade-owner', 'x')])
        String taskId = accepted.task.id
        List<Integer> codes = []

        when:
        withOtherUser {
            codes << code { ec.getA2A().getTask(taskId, null) }
            codes << code { ec.getA2A().cancelTask(taskId, null) }
            codes << code { ec.getA2A().subscribeTask(taskId, -1L, 0) }
            assert ec.getA2A().listTasks([:]).tasks.isEmpty()
        }

        then:
        codes.every { it == A2AException.TASK_NOT_FOUND }
        ec.getA2A().getTask(taskId, 0).task.status.state == 'TASK_STATE_SUBMITTED'
    }

    def 'the extended card built through the facade advertises the JSON-RPC interface'() {
        when:
        Map card = ec.getA2A().getExtendedAgentCard([baseUrl: 'https://facade.invalid/moqui/'])

        then:
        card.agentCard.supportedInterfaces == [[url: 'https://facade.invalid/moqui/llm/a2a/jsonrpc',
                protocolBinding: 'JSONRPC', protocolVersion: A2ACardBuilderImpl.PROTOCOL_VERSION]]
        card.agentCard.skills
    }

    private static Map message(String messageId, String text) {
        [messageId: messageId, role: 'ROLE_USER', parts: [[text: text]]]
    }

    private static int code(Closure work) {
        try {
            work.call()
            return 0
        } catch (A2AException e) {
            return e.code
        }
    }

    private void withOtherUser(Closure work) {
        ec.transaction.runUseOrBegin(null, 'A2A facade test user create failed') {
            ec.entity.makeValue('moqui.security.UserAccount')
                    .setAll([userId: OTHER_USER_ID, username: OTHER_USERNAME]).createOrUpdate()
        }
        ec.user.logoutUser()
        assert ((UserFacadeImpl) ec.user).internalLoginUser(OTHER_USERNAME, false)
        try {
            work.call()
        } finally {
            ec.user.logoutUser()
            assert ec.user.loginUser('john.doe', 'moqui')
            ec.transaction.runUseOrBegin(null, 'A2A facade test user delete failed') {
                ec.entity.find('moqui.security.UserAccount').condition('userId', OTHER_USER_ID).disableAuthz().deleteAll()
            }
        }
    }

    private Map profiles() {
        Field field = LlmFacadeImpl.getDeclaredField('profileByName')
        field.setAccessible(true)
        (Map) field.get(ec.llm)
    }

    private void clearA2AData() {
        ['A2APushConfig', 'A2ATaskEvent', 'A2AArtifactPart', 'A2AArtifact',
         'A2ATaskStatus', 'A2AMessagePart', 'A2AMessage', 'A2ATask', 'A2AContext'].each { name ->
            try { ec.entity.find("moqui.a2a.${name}").disableAuthz().deleteAll() }
            catch (Throwable ignored) { }
        }
    }
}
