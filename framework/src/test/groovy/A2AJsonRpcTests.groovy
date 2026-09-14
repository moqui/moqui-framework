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
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.a2a.A2ACardBuilderImpl
import org.moqui.impl.llm.a2a.A2AGateway
import org.moqui.impl.llm.a2a.A2AException
import org.moqui.impl.llm.a2a.A2AJsonRpc
import org.moqui.impl.llm.a2a.A2AStreamSink
import org.moqui.impl.webapp.A2ACardServlet
import org.moqui.impl.webapp.A2ASseSink
import org.moqui.llm.test.FakeLlmProtocol
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

import jakarta.servlet.http.HttpServletRequest
import java.lang.reflect.Field
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A2A 1.0 JSON-RPC binding end to end through LlmGateway, with a fake LLM profile registered on the facade. */
@IgnoreIf({
    String runtime = System.getProperty('moqui.runtime') ?: '../runtime'
    !new File(runtime, 'conf/MoquiDevConf.xml').exists()
})
class A2AJsonRpcTests extends Specification {
    static final String PROFILE = 'a2a-fake'
    static final String BASE = 'https://agent.example.invalid/moqui'
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
        if (ec.transaction.isTransactionInPlace()) ec.transaction.rollback('A2A JSON-RPC test cleanup', null)
        clearA2AData()
        ec.artifactExecution.enableAuthz()
    }

    def 'envelope and version errors use JSON-RPC and A2A codes'() {
        expect:
        code { A2AJsonRpc.parseRequest('[{"jsonrpc":"2.0","method":"GetTask","id":1}]') } == A2AException.INVALID_REQUEST
        code { A2AJsonRpc.parseRequest('{"jsonrpc":') } == A2AException.PARSE_ERROR
        code { A2AJsonRpc.validateEnvelope([jsonrpc: '1.0', method: 'GetTask']) } == A2AException.INVALID_REQUEST
        code { A2AJsonRpc.validateEnvelope([jsonrpc: '2.0', method: 'GetTask', params: [1]]) } == A2AException.INVALID_PARAMS
        code { A2AJsonRpc.checkVersion(null) } == A2AException.VERSION_NOT_SUPPORTED
        code { A2AJsonRpc.checkVersion('0.3') } == A2AException.VERSION_NOT_SUPPORTED
        code { A2AJsonRpc.checkVersion('1.0') } == 0
        A2AJsonRpc.failure(7, new A2AException(A2AException.TASK_NOT_FOUND, 'task not found')) == [jsonrpc: '2.0', id: 7,
                error: [code: -32001, message: 'task not found', data: [['@type': 'type.googleapis.com/google.rpc.ErrorInfo',
                reason: 'TASK_NOT_FOUND', domain: 'a2a-protocol.org']]]]
    }

    def 'SendMessage runs the task through the LLM profile and GetTask returns the raw Task'() {
        given:
        proto.results = [FakeLlmProtocol.stop('Ciao da Moqui')]

        when:
        Map result = A2AJsonRpc.invoke(ec, 'SendMessage', [message: message('rpc-send', 'hello')], BASE) as Map
        Map fetched = A2AJsonRpc.invoke(ec, 'GetTask', [id: result.task.id, historyLength: 0], BASE) as Map
        Map listed = A2AJsonRpc.invoke(ec, 'ListTasks', [:], BASE) as Map

        then:
        result.task.status.state == 'TASK_STATE_COMPLETED'
        result.task.status.message.role == 'ROLE_AGENT'
        result.task.artifacts[0].parts[0].text == 'Ciao da Moqui'
        result.task.history*.role == ['ROLE_USER', 'ROLE_AGENT']
        fetched.id == result.task.id
        !fetched.containsKey('task')
        !fetched.containsKey('history')
        listed.keySet() == ['tasks', 'nextPageToken', 'pageSize', 'totalSize'] as Set
        listed.nextPageToken == ''
        listed.tasks*.id == [result.task.id]
        !listed.tasks[0].containsKey('artifacts')
        proto.chatCount + proto.chatStreamCount == 1
    }

    def 'SendStreamingMessage streams LLM deltas as response artifact chunks'() {
        given:
        proto.streamDeltas = ['Hel', 'lo']
        proto.results = [FakeLlmProtocol.stop('Hello')]
        List<Map> events = []

        when:
        A2AJsonRpc.stream(ec, 'SendStreamingMessage', [message: message('rpc-stream', 'hi')], collect(events), [:])
        List<Map> chunks = events.findAll { it.artifactUpdate }*.artifactUpdate
        Map fetched = A2AJsonRpc.invoke(ec, 'GetTask', [id: events.first().task.id], BASE) as Map

        then:
        proto.chatStreamCount == 1
        events.first().task.status.state == 'TASK_STATE_SUBMITTED'
        events[1].statusUpdate.status.state == 'TASK_STATE_WORKING'
        chunks.collect { it.artifact.parts[0].text } == ['Hel', 'lo', '']
        chunks.last().lastChunk
        events.last().statusUpdate.status.state == 'TASK_STATE_COMPLETED'
        fetched.artifacts[0].parts[0].text == 'Hello'
    }

    def 'protocol errors map to A2A error codes'() {
        given:
        Map sent = A2AJsonRpc.invoke(ec, 'SendMessage', [message: message('rpc-errors', 'hello')], BASE) as Map
        Map continuation = message('rpc-errors-2', 'again')
        continuation.taskId = sent.task.id

        expect:
        code { A2AJsonRpc.invoke(ec, 'GetTask', [id: 'missing'], BASE) } == A2AException.TASK_NOT_FOUND
        code { A2AJsonRpc.invoke(ec, 'CancelTask', [id: sent.task.id], BASE) } == A2AException.TASK_NOT_CANCELABLE
        code { A2AJsonRpc.invoke(ec, 'SendMessage', [message: continuation], BASE) } == A2AException.UNSUPPORTED_OPERATION
        code { A2AJsonRpc.invoke(ec, 'SendMessage', [message: [messageId: 'x', role: 'user', parts: [[text: 'x']]]], BASE) } ==
                A2AException.INVALID_PARAMS
        code { A2AJsonRpc.invoke(ec, 'SendMessage', [message: message('rpc-errors-3', 'x'),
                configuration: [returnImmediately: true]], BASE) } == A2AException.UNSUPPORTED_OPERATION
        code { A2AJsonRpc.invoke(ec, 'CreateTaskPushNotificationConfig', [taskId: sent.task.id,
                url: 'https://example.invalid/hook'], BASE) } == A2AException.PUSH_NOTIFICATION_NOT_SUPPORTED
        code { A2AJsonRpc.invoke(ec, 'message/send', [:], BASE) } == A2AException.METHOD_NOT_FOUND
        code { A2AJsonRpc.stream(ec, 'SubscribeToTask', [id: sent.task.id], collect([]), [:]) } == A2AException.UNSUPPORTED_OPERATION
    }

    def 'SubscribeToTask streams the Task and then status updates until terminal'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, [profile: PROFILE, message: message('rpc-subscribe', 'x')])
        String taskId = accepted.task.id

        when:
        CompletableFuture<List<Map>> subscription = CompletableFuture.supplyAsync {
            ExecutionContext other = Moqui.getExecutionContext()
            try {
                assert other.user.loginUser('john.doe', 'moqui')
                List<Map> seen = []
                A2AJsonRpc.stream(other, 'SubscribeToTask', [id: taskId], collect(seen), [pollMillis: 50L, timeoutSeconds: 20])
                seen
            } finally {
                other.destroy()
            }
        }
        Thread.sleep(500)
        A2AGateway.updateStatus(ec, taskId, 'TASK_STATE_WORKING')
        A2AGateway.updateStatus(ec, taskId, 'TASK_STATE_COMPLETED')
        List<Map> seen = subscription.get(30, TimeUnit.SECONDS)

        List<Long> ordinals = (0..2).collect { int after ->
            A2AGateway.subscribeTask(ec, [taskId: taskId, afterOrdinal: after as long]).lastOrdinal as long
        }

        then:
        seen.first().task.id == taskId
        seen.first().task.status.state == 'TASK_STATE_SUBMITTED'
        seen*.statusUpdate.findAll { it }*.status*.state == ['TASK_STATE_WORKING', 'TASK_STATE_COMPLETED']

        and: 'each event is delivered once and the replay ordinals only move forward'
        seen.size() == seen.unique(false).size()
        ordinals == ordinals.toSorted()
        A2AGateway.subscribeTask(ec, [taskId: taskId, afterOrdinal: ordinals.last()]).events.isEmpty()
    }

    def 'agent cards advertise the JSON-RPC interface'() {
        when:
        Map extended = A2AJsonRpc.invoke(ec, 'GetExtendedAgentCard', [:], BASE) as Map
        Map publicCard = A2ACardBuilderImpl.buildPublic(BASE + '/')

        then:
        extended.supportedInterfaces == [[url: BASE + '/llm/a2a/jsonrpc', protocolBinding: 'JSONRPC', protocolVersion: '1.0']]
        extended.capabilities == [streaming: true, pushNotifications: false, extendedAgentCard: true]
        extended.skills
        publicCard.supportedInterfaces == extended.supportedInterfaces
        publicCard.skills*.id == ['moqui-assist']
        publicCard.securitySchemes.basic.httpAuthSecurityScheme.scheme == 'Basic'
        publicCard.securitySchemes.loginKey.apiKeySecurityScheme.name == 'login_key'
        publicCard.securityRequirements == [[schemes: [basic: [list: []]]], [schemes: [loginKey: [list: []]]]]
    }

    def 'SSE sink frames JSON-RPC responses as data lines and pings as comments'() {
        given:
        StringWriter out = new StringWriter()
        A2ASseSink sink = new A2ASseSink(out, 'req-1')

        when:
        sink.emit([statusUpdate: [taskId: 't1']])
        sink.ping()
        sink.close()
        boolean afterClose = sink.emit([statusUpdate: [taskId: 't2']])

        then:
        out.toString() == 'data: {"jsonrpc":"2.0","id":"req-1","result":{"statusUpdate":{"taskId":"t1"}}}\n\n: ping\n\n'
        sink.emitted == 1
        !afterClose
    }

    def 'internal failures never leak implementation details to the caller'() {
        expect: 'ours are reported as-is'
        A2AJsonRpc.toA2A(new A2AException(A2AException.TASK_NOT_FOUND, 'task not found')).message == 'task not found'
        A2AJsonRpc.toA2A(new IllegalArgumentException('message.messageId is required')).message == 'message.messageId is required'

        and: 'anything else is generic, with the original kept for the log only'
        A2AJsonRpc.toA2A(new SQLException('ORA-00942: table SECRET_TABLE does not exist')).code == A2AException.INTERNAL_ERROR
        A2AJsonRpc.toA2A(new SQLException('ORA-00942: table SECRET_TABLE does not exist')).message == 'Internal error'
        A2AJsonRpc.toA2A(new NullPointerException('org.moqui.impl.Secret.field')).message == 'Internal error'
        !A2AJsonRpc.failure(1, A2AJsonRpc.toA2A(new SQLException('secret'))).toString().contains('secret')
    }

    def 'JSON-RPC envelopes keep the id shape and tolerate missing params'() {
        expect:
        A2AJsonRpc.success('abc', [ok: true]).id == 'abc'
        A2AJsonRpc.success(42, [ok: true]).id == 42
        A2AJsonRpc.success(null, [ok: true]).containsKey('id')
        A2AJsonRpc.params([jsonrpc: '2.0', method: 'ListTasks']) == [:]
        A2AJsonRpc.invoke(ec, 'ListTasks', A2AJsonRpc.params([jsonrpc: '2.0', method: 'ListTasks']), BASE).tasks != null
        code { A2AJsonRpc.invoke(ec, 'GetTask', [:], BASE) } == A2AException.INVALID_PARAMS
        code { A2AJsonRpc.invoke(ec, 'CancelTask', [:], BASE) } == A2AException.INVALID_PARAMS
    }

    def 'the Agent Card URL ignores forwarded headers unless they are trusted'() {
        given:
        HttpServletRequest forwarded = Stub(HttpServletRequest) {
            getHeader('X-Forwarded-Proto') >> 'https'
            getHeader('X-Forwarded-Host') >> 'attacker.invalid'
            getScheme() >> 'http'
            getServerName() >> 'moqui.local'
            getServerPort() >> 8080
            getContextPath() >> ''
        }
        String previousTrust = System.getProperty('a2a_trust_forwarded_headers')
        String previousUrl = System.getProperty('a2a_public_url')
        System.clearProperty('a2a_public_url')

        when:
        System.setProperty('a2a_trust_forwarded_headers', 'false')
        String untrusted = A2ACardServlet.baseUrl(forwarded)
        System.setProperty('a2a_trust_forwarded_headers', 'true')
        String trusted = A2ACardServlet.baseUrl(forwarded)
        System.setProperty('a2a_public_url', 'https://agent.example.invalid/moqui/')
        String configured = A2ACardServlet.baseUrl(forwarded)

        then:
        untrusted == 'http://moqui.local:8080'
        trusted == 'https://attacker.invalid'
        configured == 'https://agent.example.invalid/moqui'

        and: 'a public URL that is not an absolute, credential-free http(s) URL is refused'
        A2ACardServlet.validBaseUrl('javascript:alert(1)') == null
        A2ACardServlet.validBaseUrl('https://user:pw@example.invalid') == null
        A2ACardServlet.validBaseUrl('https://example.invalid/a\r\nX-Injected: 1') == null
        A2ACardServlet.validBaseUrl('not a url') == null
        A2ACardServlet.validBaseUrl('https://example.invalid/base/') == 'https://example.invalid/base'

        cleanup:
        if (previousTrust != null) System.setProperty('a2a_trust_forwarded_headers', previousTrust)
        else System.clearProperty('a2a_trust_forwarded_headers')
        if (previousUrl != null) System.setProperty('a2a_public_url', previousUrl)
        else System.clearProperty('a2a_public_url')
    }

    private static Map message(String messageId, String text) {
        [messageId: messageId, role: 'ROLE_USER', parts: [[text: text]]]
    }

    private static A2AStreamSink collect(List<Map> events) {
        [emit: { Map event -> events << event; true }, ping: { true }] as A2AStreamSink
    }

    private static int code(Closure work) {
        try {
            work.call()
            return 0
        } catch (Throwable t) {
            return A2AJsonRpc.toA2A(t).code
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
