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
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityJavaUtil
import org.moqui.impl.llm.a2a.A2ACardBuilderImpl
import org.moqui.impl.llm.a2a.A2AGateway
import org.moqui.impl.llm.a2a.A2ATaskStore
import org.moqui.impl.llm.a2a.A2AException
import org.moqui.impl.llm.a2a.A2AStreamSink
import org.moqui.impl.llm.a2a.A2ATypes
import org.moqui.impl.llm.a2a.A2AExecutorImpl
import org.moqui.llm.LlmStreamListener
import org.xml.sax.SAXException
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.XMLConstants
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory

import static org.moqui.impl.llm.a2a.A2ATypes.*

@IgnoreIf({
    String runtime = System.getProperty('moqui.runtime') ?: '../runtime'
    !new File(runtime, 'conf/MoquiDevConf.xml').exists()
})
class A2ACoreTests extends Specification {
    static final String OTHER_USER_ID = 'A2A_TEST_OTHER'
    static final String OTHER_USERNAME = 'a2a.test.other'
    @Shared ExecutionContext ec

    def setupSpec() { ec = Moqui.getExecutionContext() }
    def cleanupSpec() { ec.destroy() }

    def setup() {
        ec.artifactExecution.disableAuthz()
        if (!ec.user.userId) assert ec.user.loginUser('john.doe', 'moqui')
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        clearA2AData()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
    }

    def cleanup() {
        if (ec.transaction.isTransactionInPlace()) ec.transaction.rollback('A2A test cleanup', null)
        clearA2AData()
        ec.artifactExecution.enableAuthz()
    }

    def 'part is a strict oneof'() {
        when:
        requireParts([[text: 'x', data: [x: 1]]])

        then:
        thrown(IllegalArgumentException)
    }

    def 'part supports JSON null data and validates structured fields'() {
        expect:
        requireParts([[data: null]]) == [[data: null]]

        when:
        requireMessage([
            messageId: 'bad-metadata',
            role: 'ROLE_USER',
            parts: [[text: 'x']],
            metadata: ['not', 'an', 'object']
            ], true)

        then:
        thrown(IllegalArgumentException)
    }

    def 'parts follow the A2A 1.0 oneof of text raw url and data'() {
        expect:
        requireParts([[raw: 'aGVsbG8=', filename: 'hello.txt', mediaType: 'text/plain']]).size() == 1
        requireParts([[url: 'https://example.invalid/report.pdf', mediaType: 'application/pdf']]).size() == 1
        requireParts([[data: 'a JSON string is a valid data value']]).size() == 1
        invalid { requireParts([[file: [uri: 'https://example.invalid/x']]]) }
        invalid { requireParts([[raw: 'not base64!']]) }
        invalid { requireParts([[url: '']]) }
        invalid { requireParts([[text: 'x', filename: 7]]) }
    }

    def 'raw url and data parts round trip through task history'() {
        given:
        Map req = request('m-parts')
        req.message.parts = [[text: 'see attached'], [raw: 'aGVsbG8=', filename: 'hello.txt', mediaType: 'text/plain'],
                             [url: 'https://example.invalid/r.pdf'], [data: [n: 1]]]

        when:
        Map accepted = A2AGateway.acceptMessage(ec, req)
        Map fetched = A2AGateway.getTask(ec, [taskId: accepted.task.id])

        then:
        fetched.task.history[0].parts == req.message.parts
        A2ATypes.messageText(req.message).contains('hello.txt')
    }

    def 'unsupported output modes fail before task creation'() {
        when:
        A2AExecutorImpl.sendMessage(ec, request('m-mode') +
                [configuration: [acceptedOutputModes: ['image/x-unsupported']]]) { Map ignored, boolean resume ->
            assert false: 'provider must not be invoked'
        }

        then:
        def error = thrown(A2AException)
        error.code == A2AException.CONTENT_TYPE_NOT_SUPPORTED
        ec.entity.find('moqui.a2a.A2ATask').count() == 0
    }

    def 'new message creates context task and an internal conversation for the context'() {
        when:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-new'))

        then:
        accepted.task.id
        accepted.task.contextId
        accepted.task.status.state == 'TASK_STATE_SUBMITTED'
        ec.entity.find('moqui.a2a.A2AContext').condition('contextId', accepted.task.contextId).count() == 1
        ec.entity.find('moqui.llm.LlmConversation').condition('contextId', accepted.task.contextId).count() == 1
    }

    def 'message id replay is scoped and idempotent'() {
        given:
        Map first = A2AGateway.acceptMessage(ec, request('m-replay'))
        Map result = [task: first.task]
        A2ATaskStore.saveResult(ec, first.messageValue, first.taskValue, result)

        when:
        Map replay = A2AGateway.acceptMessage(ec, request('m-replay'))

        then:
        replay.replayed
        replay.result.task.id == first.task.id
        ec.entity.find('moqui.a2a.A2AMessage').condition('messageId', 'm-replay').count() == 1
    }

    def 'in-flight message replay never invokes the provider twice'() {
        given:
        Map first = A2AGateway.acceptMessage(ec, request('m-in-flight'))
        int calls = 0

        when:
        Map replay = A2AExecutorImpl.sendMessage(ec, request('m-in-flight')) { Map ignored, boolean resume ->
            calls++
            [:]
        }

        then:
        calls == 0
        replay.task.id == first.task.id
        replay.task.status.state == 'TASK_STATE_SUBMITTED'
    }

    def 'accepted task is committed independently of the callers transaction'() {
        given:
        assert ec.transaction.begin(30)
        Map accepted = A2AGateway.acceptMessage(ec, request('m-isolated'))

        when:
        long visible = java.util.concurrent.CompletableFuture.supplyAsync {
            ExecutionContext other = Moqui.getExecutionContext()
            try {
                assert other.user.loginUser('john.doe', 'moqui')
                other.entity.find('moqui.a2a.A2ATask').condition('taskId', accepted.task.id).disableAuthz().count()
            } finally {
                other.destroy()
            }
        }.get(20, java.util.concurrent.TimeUnit.SECONDS) as long

        then:
        ec.transaction.isTransactionInPlace()
        visible == 1

        cleanup:
        if (ec.transaction.isTransactionInPlace()) ec.transaction.rollback('outer A2A test transaction', null)
    }

    def 'context rejects a second active task without task id'() {
        given:
        Map first = A2AGateway.acceptMessage(ec, request('m-one'))
        Map second = request('m-two')
        second.message.contextId = first.task.contextId

        when:
        A2AGateway.acceptMessage(ec, second)

        then:
        def error = thrown(A2AException)
        error.code == A2AException.INVALID_PARAMS
        error.message.contains('in-flight task')
    }

    def 'continuation of a task that is not input-required is rejected before persisting'() {
        given:
        Map first = A2AGateway.acceptMessage(ec, request('m-not-waiting'))
        Map continuation = request('m-bad-resume')
        continuation.message.taskId = first.task.id

        when:
        A2AGateway.acceptMessage(ec, continuation)

        then:
        def error = thrown(A2AException)
        error.code == A2AException.INVALID_PARAMS
        ec.entity.find('moqui.a2a.A2AMessage').condition('messageId', 'm-bad-resume').count() == 0
        A2AGateway.getTask(ec, [taskId: first.task.id]).task.status.state == 'TASK_STATE_SUBMITTED'
    }

    def 'terminal task rejects further messages'() {
        given:
        Map first = A2AGateway.acceptMessage(ec, request('m-terminal'))
        A2AGateway.cancelTask(ec, [taskId: first.task.id])
        Map continuation = request('m-after-terminal')
        continuation.message.taskId = first.task.id

        when:
        A2AGateway.acceptMessage(ec, continuation)

        then:
        def error = thrown(A2AException)
        error.code == A2AException.UNSUPPORTED_OPERATION
    }

    def 'blocking executor completes and replay does not invoke model twice'() {
        given:
        int calls = 0
        Closure<Map> invokeLlm = { Map body, boolean resume ->
            calls++
            assert !resume
            assert body.user == 'hello'
            [content: 'completed', yielded: false, model: 'fake', profile: 'default', pendingToolCalls: []]
        }

        when:
        Map first = A2AExecutorImpl.sendMessage(ec, request('m-exec'), invokeLlm)
        Map replay = A2AExecutorImpl.sendMessage(ec, request('m-exec'), invokeLlm)

        then:
        calls == 1
        first.task.status.state == 'TASK_STATE_COMPLETED'
        replay.task.id == first.task.id
        first.task.history*.role == ['ROLE_USER', 'ROLE_AGENT']
        first.task.artifacts*.artifactId == [A2AExecutorImpl.RESPONSE_ARTIFACT_ID]
        first.task.artifacts[0].parts == [[text: 'completed', mediaType: 'text/plain']]
    }

    def 'streaming run emits task, working, response chunks and final status in order'() {
        given:
        List<Map> events = []
        A2AStreamSink sink = [emit: { Map event -> events << event; true }, ping: { true }] as A2AStreamSink
        Closure<Map> invoker = { Map body, boolean resume, LlmStreamListener listener ->
            listener.onDelta('Hel')
            listener.onDelta('lo')
            [content: 'Hello', yielded: false, model: 'fake', profile: 'default', pendingToolCalls: []]
        }

        when:
        Map result = A2AExecutorImpl.run(ec, request('m-stream'), invoker, sink)
        List<Map> chunks = events.findAll { it.artifactUpdate }*.artifactUpdate

        then:
        events.first().task.status.state == 'TASK_STATE_SUBMITTED'
        events[1].statusUpdate.status.state == 'TASK_STATE_WORKING'
        chunks.collect { it.artifact.parts[0].text } == ['Hel', 'lo', '']
        chunks*.append == [false, true, true]
        chunks*.lastChunk == [false, false, true]
        events.last().statusUpdate.status.state == 'TASK_STATE_COMPLETED'
        result.task.artifacts[0].parts[0].text == 'Hello'
    }

    def 'input required resumes the same task and context'() {
        given:
        Closure<Map> yieldInvoker = { Map body, boolean resume ->
            [content: 'provide input', yielded: true, model: 'fake', profile: 'default',
             pendingToolCalls: [[id: 'ui-1', name: 'write_ui', arguments: [:]]]]
        }
        Map first = A2AExecutorImpl.sendMessage(ec, request('m-yield'), yieldInvoker)
        Map continuation = request('m-resume')
        continuation.message.taskId = first.task.id
        continuation.message.contextId = first.task.contextId
        continuation.message.parts = [[data: [approved: true]]]

        when:
        Map resumed = A2AExecutorImpl.sendMessage(ec, continuation) { Map body, boolean resume ->
            assert resume
            assert body.toolResults[0].toolCallId == 'ui-1'
            assert body.toolResults[0].content.approved
            [content: 'done', yielded: false, model: 'fake', profile: 'default', pendingToolCalls: []]
        }

        then:
        first.task.status.state == 'TASK_STATE_INPUT_REQUIRED'
        resumed.task.id == first.task.id
        resumed.task.contextId == first.task.contextId
        resumed.task.status.state == 'TASK_STATE_COMPLETED'
        resumed.task.artifacts[0].parts[0].text == 'done'
    }

    def 'cancel is terminal and further status changes are rejected'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-cancel'))

        when:
        Map canceled = A2AGateway.cancelTask(ec, [taskId: accepted.task.id])
        A2AGateway.updateStatus(ec, accepted.task.id as String, 'TASK_STATE_WORKING')

        then:
        canceled.task.status.state == 'TASK_STATE_CANCELED'
        thrown(IllegalStateException)
    }

    def 'canceling a terminal task is TaskNotCancelable'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-cancel-twice'))
        A2AGateway.cancelTask(ec, [taskId: accepted.task.id])

        when:
        A2AGateway.cancelTask(ec, [taskId: accepted.task.id])

        then:
        def error = thrown(A2AException)
        error.code == A2AException.TASK_NOT_CANCELABLE
    }

    def 'invalid non-terminal transition is rejected'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-transition'))

        when:
        A2AGateway.updateStatus(ec, accepted.task.id as String, 'TASK_STATE_INPUT_REQUIRED')

        then:
        def error = thrown(IllegalStateException)
        error.message.contains('SUBMITTED')
        error.message.contains('INPUT_REQUIRED')
    }

    def 'internal A2A services are registered'() {
        expect:
        ec.service.getServiceDefinition('org.moqui.impl.A2AServices.get#A2ATask') != null
        ec.service.getServiceDefinition('org.moqui.impl.A2AServices.send#A2AMessage') != null
    }

    def 'internal task services execute through ServiceFacade'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-service'))

        when:
        Map fetched = ec.service.sync().name('org.moqui.impl.A2AServices.get#A2ATask')
                .parameter('taskId', accepted.task.id).parameter('historyLength', 1).call()
        Map canceled = ec.service.sync().name('org.moqui.impl.A2AServices.cancel#A2ATask')
                .parameter('taskId', accepted.task.id).call()
        Map listed = ec.service.sync().name('org.moqui.impl.A2AServices.find#A2ATasks')
                .parameter('status', 'TASK_STATE_CANCELED').call()

        then:
        fetched.task.id == accepted.task.id
        fetched.task.history*.messageId == ['m-service']
        canceled.task.status.state == 'TASK_STATE_CANCELED'
        listed.tasks*.id == [accepted.task.id]
    }

    def 'task list cursor is stable and does not duplicate rows'() {
        given:
        3.times { int index ->
            Map accepted = A2AGateway.acceptMessage(ec, request("m-page-${index}"))
            A2AGateway.cancelTask(ec, [taskId: accepted.task.id])
        }

        when:
        Map first = A2AGateway.listTasks(ec, [pageSize: 2, historyLength: 0])
        Map second = A2AGateway.listTasks(ec, [pageSize: 2, pageToken: first.nextPageToken, historyLength: 0])

        then:
        first.tasks.size() == 2
        first.nextPageToken
        first.totalSize == 3
        second.tasks.size() == 1
        !second.nextPageToken
        (first.tasks*.id + second.tasks*.id).toSet().size() == 3
        first.tasks.every { !it.containsKey('history') && !it.containsKey('artifacts') }
    }

    def 'artifact updates and event replay preserve order'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-artifact'))

        when:
        A2AGateway.addArtifact(ec, [taskId: accepted.task.id,
                artifact: [artifactId: 'report', name: 'Report', parts: [[text: 'first']]]])
        Map appended = A2AGateway.addArtifact(ec, [taskId: accepted.task.id, append: true, lastChunk: true,
                artifact: [artifactId: 'report', parts: [[data: [complete: true]]]]])
        Map replay = A2AGateway.subscribeTask(ec, [taskId: accepted.task.id, afterOrdinal: -1L])
        List<Map> artifactEvents = replay.events*.artifactUpdate.findAll { it }

        then:
        appended.artifact.parts == [[text: 'first'], [data: [complete: true]]]
        artifactEvents*.artifact*.artifactId == ['report', 'report']
        artifactEvents*.artifact*.parts == [[[text: 'first']], [[data: [complete: true]]]]
        artifactEvents*.append == [false, true]
        replay.events*.statusUpdate.findAll { it }*.status*.state == ['TASK_STATE_SUBMITTED']
        replay.lastOrdinal > 0
    }

    def 'event and status sequence numbers are unique and increasing'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-sequence'))
        String taskId = accepted.task.id

        when:
        A2AGateway.updateStatus(ec, taskId, 'TASK_STATE_WORKING')
        A2AGateway.addArtifact(ec, [taskId: taskId, artifact: [artifactId: 'a', parts: [[text: 'x']]]])
        A2AGateway.updateStatus(ec, taskId, 'TASK_STATE_COMPLETED')
        List<Integer> eventSeq = ec.entity.find('moqui.a2a.A2ATaskEvent').condition('taskId', taskId)
                .orderBy('sequenceNum').list()*.sequenceNum*.intValue()
        List<Integer> statusSeq = ec.entity.find('moqui.a2a.A2ATaskStatus').condition('taskId', taskId)
                .orderBy('sequenceNum').list()*.sequenceNum*.intValue()
        Map afterWorking = A2AGateway.subscribeTask(ec, [taskId: taskId, afterOrdinal: 2L])

        then:
        eventSeq == [1, 2, 3, 4]
        statusSeq == [1, 2, 3]
        afterWorking.events*.artifactUpdate.findAll { it }.size() == 1
        afterWorking.events*.statusUpdate.findAll { it }*.status*.state == ['TASK_STATE_COMPLETED']
    }

    def 'artifact id is scoped to its task'() {
        given:
        Map first = A2AGateway.acceptMessage(ec, request('m-art-1'))
        Map second = A2AGateway.acceptMessage(ec, request('m-art-2'))

        when:
        A2AGateway.addArtifact(ec, [taskId: first.task.id, artifact: [artifactId: 'report', parts: [[text: 'one']]]])
        A2AGateway.addArtifact(ec, [taskId: second.task.id, artifact: [artifactId: 'report', parts: [[text: 'two']]]])

        then:
        A2AGateway.getTask(ec, [taskId: first.task.id]).task.artifacts*.parts == [[[text: 'one']]]
        A2AGateway.getTask(ec, [taskId: second.task.id]).task.artifacts*.parts == [[[text: 'two']]]
    }

    def 'push configuration CRUD is scoped to its task and never returns credentials'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-push'))

        when:
        Map created = A2AGateway.createPushConfig(ec, [taskId: accepted.task.id,
                url: 'https://example.invalid/a2a-events', token: 'opaque',
                authentication: [scheme: 'Bearer', credentials: 'secret']])
        Map fetched = A2AGateway.getPushConfig(ec, [taskId: accepted.task.id, configId: created.config.id])
        Map listed = A2AGateway.listPushConfigs(ec, [taskId: accepted.task.id])
        Map stored = ec.entity.find('moqui.a2a.A2APushConfig').condition('configId', created.config.id).one().getMap()
        Map deleted = A2AGateway.deletePushConfig(ec, [taskId: accepted.task.id, configId: created.config.id])

        then:
        fetched.config == [id: created.config.id, taskId: accepted.task.id,
                url: 'https://example.invalid/a2a-events', authentication: [scheme: 'Bearer']]
        created.config == fetched.config
        listed.configs == [fetched.config]
        stored.token == 'opaque'
        stored.authenticationJson.contains('secret')
        deleted.deleted
    }

    def 'push configuration rejects non HTTP endpoint syntax'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-push-invalid'))

        when:
        A2AGateway.createPushConfig(ec, [taskId: accepted.task.id,
                url: 'https://user@example.invalid/events#fragment', authentication: [scheme: 'Bearer']])

        then:
        thrown(IllegalArgumentException)
    }

    def 'task list rejects a malformed cursor payload'() {
        given:
        String token = Base64.urlEncoder.withoutPadding().encodeToString('{"unexpected":true}'.bytes)

        when:
        A2AGateway.listTasks(ec, [pageToken: token])

        then:
        thrown(IllegalArgumentException)
    }

    def 'extended card requires an explicit transport binding'() {
        when:
        A2ACardBuilderImpl.buildExtended(ec, [:])

        then:
        thrown(IllegalArgumentException)
    }

    def 'extended card has protocol-required non-empty collections'() {
        when:
        Map card = A2ACardBuilderImpl.buildExtended(ec, [supportedInterfaces: [[
                url: 'https://example.invalid/a2a', protocolBinding: 'HTTP+JSON', protocolVersion: '1.0']]])

        then:
        card.supportedInterfaces.size() == 1
        card.skills
        card.skills.every { it.id && it.name && it.description && it.tags }
        card.defaultInputModes
        card.defaultOutputModes
        card.securitySchemes.basic.httpAuthSecurityScheme.scheme == 'Basic'
    }

    def 'task ownership hides another users task'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-owner'))
        // a second user of our own: framework-only data has just john.doe (joe.developer comes from mantle-usl);
        // in a transaction because components may register DataFeeds on UserAccount
        ec.transaction.runUseOrBegin(null, 'A2A test user create failed') {
            ec.entity.makeValue('moqui.security.UserAccount')
                    .setAll([userId: OTHER_USER_ID, username: OTHER_USERNAME]).createOrUpdate()
        }
        ec.user.logoutUser()
        assert ((UserFacadeImpl) ec.user).internalLoginUser(OTHER_USERNAME, false)

        when:
        A2AGateway.getTask(ec, [taskId: accepted.task.id])

        then:
        def error = thrown(A2AException)
        error.code == A2AException.TASK_NOT_FOUND
        error.message == 'task not found'

        cleanup:
        ec.user.logoutUser()
        assert ec.user.loginUser('john.doe', 'moqui')
        ec.transaction.runUseOrBegin(null, 'A2A test user delete failed') {
            ec.entity.find('moqui.security.UserAccount').condition('userId', OTHER_USER_ID).disableAuthz().deleteAll()
        }
    }

    def 'A2A XML validates against the entity XSD and uses no prohibited attributes'() {
        given:
        String runtimeDir = System.getProperty('moqui.runtime') ?: '../runtime'
        File xsdDir = new File(new File(runtimeDir).absoluteFile.parentFile, 'framework/xsd')
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        List<String> validated = []
        List<String> notValidated = []

        when:
        [['framework/entity/A2AEntities.xml', 'entity-definition-3.xsd'],
         ['framework/entity/LlmEntities.xml', 'entity-definition-3.xsd'],
         ['framework/service/org/moqui/impl/A2AServices.xml', 'service-definition-3.xsd'],
         ['framework/src/main/resources/MoquiDefaultConf.xml', 'moqui-conf-3.xsd']].each { List pair ->
            File xml = new File(new File(runtimeDir).absoluteFile.parentFile, pair[0] as String)
            File xsd = new File(xsdDir, pair[1] as String)
            Schema schema = null
            try {
                schema = factory.newSchema(xsd)
            } catch (SAXException e) {
                // an XSD that cannot be compiled is an upstream schema issue (xml-actions-3.xsd breaks Unique
                // Particle Attribution), not an invalid instance document; record it and move on
                notValidated.add(pair[0] as String)
            }
            if (schema != null) {
                try {
                    schema.newValidator().validate(new StreamSource(xml))
                    validated.add(pair[0] as String)
                } catch (SAXException e) {
                    throw new AssertionError("${pair[0]} is not valid against ${pair[1]}: ${e.message}" as Object)
                }
            }
        }

        then:
        validated.contains('framework/entity/A2AEntities.xml')
        validated.contains('framework/entity/LlmEntities.xml')
        validated.size() + notValidated.size() == 4
        // prohibited in entity-definition-3.xsd, tolerated by the runtime parser but invalid XML
        !new File(new File(runtimeDir).absoluteFile.parentFile, 'framework/entity/A2AEntities.xml').text.contains('related-field-name')
    }

    def 'every A2A relationship resolves to real fields on the related entity'() {
        expect:
        ['A2AContext', 'A2ATask', 'A2ATaskStatus', 'A2AMessage', 'A2AMessagePart', 'A2AArtifact',
         'A2AArtifactPart', 'A2ATaskEvent', 'A2APushConfig'].every { String name ->
            EntityDefinition ed = ec.entity.getEntityDefinition("moqui.a2a.${name}")
            ed.getRelationshipsInfo(false).every { EntityJavaUtil.RelationshipInfo info ->
                info.keyMap.every { String from, String to ->
                    ed.isField(from) && info.relatedEd.isField(to)
                }
            }
        }
    }

    def 'A2A HTTP endpoints are opt-in through a2a_enabled'() {
        given:
        String previous = System.getProperty('a2a_enabled')

        when:
        System.setProperty('a2a_enabled', 'false')
        boolean offByProperty = A2ACardBuilderImpl.enabled()
        System.setProperty('a2a_enabled', 'true')
        boolean onByProperty = A2ACardBuilderImpl.enabled()

        then:
        !offByProperty
        onByProperty

        cleanup:
        if (previous != null) System.setProperty('a2a_enabled', previous)
        else System.clearProperty('a2a_enabled')
    }

    def 'invalid Parts are rejected on every persistence path and write nothing'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-bad-parts'))
        String taskId = accepted.task.id
        long messagesBefore = ec.entity.find('moqui.a2a.A2AMessage').count()
        long partsBefore = ec.entity.find('moqui.a2a.A2AMessagePart').count()

        expect:
        // message Parts
        invalid { A2AGateway.acceptMessage(ec, request('m-p1') + [message: [messageId: 'm-p1', role: 'ROLE_USER', parts: []]]) }
        invalid { A2AGateway.acceptMessage(ec, [message: [messageId: 'm-p2', role: 'ROLE_USER', parts: [[:]]]]) }
        invalid { A2AGateway.acceptMessage(ec, [message: [messageId: 'm-p3', role: 'ROLE_USER', parts: [[text: 'x', url: 'https://e.invalid']]]]) }
        invalid { A2AGateway.acceptMessage(ec, [message: [messageId: 'm-p4', role: 'ROLE_USER', parts: [[raw: 'not base64!']]]]) }
        invalid { A2AGateway.acceptMessage(ec, [message: [messageId: 'm-p5', role: 'ROLE_USER', parts: [[url: '   ']]]]) }
        invalid { A2AGateway.acceptMessage(ec, [message: [messageId: 'm-p6', role: 'ROLE_USER', parts: [[text: 'x', filename: 7]]]]) }
        invalid { A2AGateway.acceptMessage(ec, [message: [messageId: 'm-p7', role: 'ROLE_USER', parts: [[text: 'x', mediaType: 7]]]]) }
        invalid { A2AGateway.acceptMessage(ec, [message: [messageId: 'm-p8', role: 'ROLE_USER', parts: [[text: 'x', metadata: 'no']]]]) }
        // artifact Parts
        invalid { A2AGateway.addArtifact(ec, [taskId: taskId, artifact: [artifactId: 'bad', parts: [[text: 'x', data: [a: 1]]]]]) }
        invalid { A2AGateway.addArtifact(ec, [taskId: taskId, artifact: [artifactId: 'bad', parts: []]]) }
        // status message Parts
        invalid { A2AGateway.updateStatus(ec, taskId, 'TASK_STATE_WORKING',
                [messageId: 'm-status-bad', role: 'ROLE_AGENT', parts: [[raw: '%%%']]]) }

        and: 'nothing was persisted by the rejected calls'
        ec.entity.find('moqui.a2a.A2AMessage').count() == messagesBefore
        ec.entity.find('moqui.a2a.A2AMessagePart').count() == partsBefore
        ec.entity.find('moqui.a2a.A2AArtifact').condition('artifactId', 'bad').count() == 0
        A2AGateway.getTask(ec, [taskId: taskId]).task.status.state == 'TASK_STATE_SUBMITTED'
    }

    def 'another user cannot read or change a task through any operation'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-isolation'))
        String taskId = accepted.task.id
        String contextId = accepted.task.contextId
        Map config = A2AGateway.createPushConfig(ec, [taskId: taskId, url: 'https://example.invalid/hook',
                authentication: [scheme: 'Bearer']])
        List<Integer> codes = []

        when:
        withOtherUser {
            codes << code { A2AGateway.getTask(ec, [taskId: taskId]) }
            codes << code { A2AGateway.cancelTask(ec, [taskId: taskId]) }
            codes << code { A2AGateway.subscribeTask(ec, [taskId: taskId]) }
            codes << code { A2AGateway.addArtifact(ec, [taskId: taskId, artifact: [artifactId: 'x', parts: [[text: 'x']]]]) }
            codes << code { A2AGateway.updateStatus(ec, taskId, 'TASK_STATE_WORKING') }
            codes << code { A2AGateway.getPushConfig(ec, [taskId: taskId, configId: config.config.id]) }
            codes << code { A2AGateway.listPushConfigs(ec, [taskId: taskId]) }
            codes << code { A2AGateway.deletePushConfig(ec, [taskId: taskId, configId: config.config.id]) }
            codes << code { A2AGateway.acceptMessage(ec, [message: [messageId: 'm-steal', role: 'ROLE_USER',
                    taskId: taskId, parts: [[text: 'resume']]]]) }
            codes << code { A2AGateway.acceptMessage(ec, [message: [messageId: 'm-ref', role: 'ROLE_USER',
                    referenceTaskIds: [taskId], parts: [[text: 'reference']]]]) }
            // listing in the other user's context must not leak the task
            assert A2AGateway.listTasks(ec, [contextId: contextId]).tasks.isEmpty()
        }

        then: 'every operation reports the task as missing, never as forbidden'
        codes.every { it == A2AException.TASK_NOT_FOUND }

        and: 'the owner still sees an untouched task'
        A2AGateway.getTask(ec, [taskId: taskId]).task.status.state == 'TASK_STATE_SUBMITTED'
        ec.entity.find('moqui.a2a.A2AMessage').condition('messageId', 'm-steal').count() == 0
        ec.entity.find('moqui.a2a.A2AMessage').condition('messageId', 'm-ref').count() == 0
    }

    def 'cleanup removes only old terminal tasks and keeps referenced contexts'() {
        given:
        ec.user.setEffectiveTime(Timestamp.from(Instant.now().minus(200, ChronoUnit.DAYS)))
        Map oldTerminal = A2AGateway.acceptMessage(ec, request('m-old-done'))
        A2AGateway.cancelTask(ec, [taskId: oldTerminal.task.id])
        Map oldOpen = A2AGateway.acceptMessage(ec, request('m-old-open'))
        ec.user.setEffectiveTime(null)
        Map recent = A2AGateway.acceptMessage(ec, request('m-recent'))
        A2AGateway.cancelTask(ec, [taskId: recent.task.id])

        when:
        Map cleaned = A2ATaskStore.cleanData(ec, [daysToKeep: 90])
        Map again = A2ATaskStore.cleanData(ec, [daysToKeep: 90])

        then: 'only the old terminal task goes'
        cleaned.tasksRemoved == 1
        ec.entity.find('moqui.a2a.A2ATask').condition('taskId', oldTerminal.task.id).count() == 0
        ec.entity.find('moqui.a2a.A2ATask').condition('taskId', oldOpen.task.id).count() == 1
        ec.entity.find('moqui.a2a.A2ATask').condition('taskId', recent.task.id).count() == 1

        and: 'its children go with it, leaving no orphans'
        ec.entity.find('moqui.a2a.A2ATaskStatus').condition('taskId', oldTerminal.task.id).count() == 0
        ec.entity.find('moqui.a2a.A2ATaskEvent').condition('taskId', oldTerminal.task.id).count() == 0
        ec.entity.find('moqui.a2a.A2AMessage').condition('taskId', oldTerminal.task.id).count() == 0

        and: 'the context stays while an LLM conversation still references it, and a second run is a no-op'
        ec.entity.find('moqui.a2a.A2AContext').condition('contextId', oldTerminal.task.contextId).count() == 1
        cleaned.contextsRemoved == 0
        again.tasksRemoved == 0

        cleanup:
        ec.user.setEffectiveTime(null)
    }

    def 'concurrent identical messageId creates one task and calls the model once'() {
        given:
        AtomicInteger calls = new AtomicInteger()
        Closure<Map> invokeLlm = { Map body, boolean resume ->
            calls.incrementAndGet()
            Thread.sleep(150)
            [content: 'once', yielded: false, model: 'fake', profile: 'default', pendingToolCalls: []]
        }

        when:
        List<Map> results = (1..2).collect {
            CompletableFuture.supplyAsync {
                ExecutionContext other = Moqui.getExecutionContext()
                try {
                    other.artifactExecution.disableAuthz()
                    assert other.user.loginUser('john.doe', 'moqui')
                    return A2AExecutorImpl.sendMessage(other, request('m-race'), invokeLlm)
                } catch (Throwable t) {
                    return [failed: t.class.simpleName] as Map
                } finally {
                    other.destroy()
                }
            }
        }*.get(90, TimeUnit.SECONDS)

        then:
        calls.get() == 1
        ec.entity.find('moqui.a2a.A2AMessage').condition('messageId', 'm-race').count() == 1
        ec.entity.find('moqui.a2a.A2ATask').count() == 1
        results.count { it.task != null } >= 1
    }

    def 'cancel racing with completion leaves exactly one terminal state'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-cancel-race'))
        String taskId = accepted.task.id
        A2AGateway.updateStatus(ec, taskId, 'TASK_STATE_WORKING')

        when:
        List<String> outcomes = [{ ExecutionContext other ->
            A2AGateway.updateStatus(other, taskId, 'TASK_STATE_COMPLETED'); 'completed'
        }, { ExecutionContext other ->
            A2AGateway.cancelTask(other, [taskId: taskId]); 'canceled'
        }].collect { Closure work ->
            CompletableFuture.supplyAsync {
                ExecutionContext other = Moqui.getExecutionContext()
                try {
                    other.artifactExecution.disableAuthz()
                    assert other.user.loginUser('john.doe', 'moqui')
                    return work.call(other) as String
                } catch (Throwable t) {
                    return 'rejected'
                } finally {
                    other.destroy()
                }
            }
        }*.get(90, TimeUnit.SECONDS)
        List<Integer> sequences = ec.entity.find('moqui.a2a.A2ATaskStatus').condition('taskId', taskId)
                .orderBy('sequenceNum').list()*.sequenceNum*.intValue()
        String finalState = A2AGateway.getTask(ec, [taskId: taskId]).task.status.state

        then: 'one of the two wins, the loser is rejected, and the task ends terminal'
        outcomes.count { it == 'rejected' } == 1
        finalState in ['TASK_STATE_COMPLETED', 'TASK_STATE_CANCELED']
        sequences == sequences.toSorted().unique()
        ec.entity.find('moqui.a2a.A2ATaskStatus').condition('taskId', taskId).count() == sequences.size()
    }

    def 'a disconnected stream client does not affect the task'() {
        given:
        List<Map> seen = []
        boolean[] open = [true]
        A2AStreamSink sink = [emit: { Map event ->
            if (!open[0]) return false
            seen << event
            open[0] = seen.size() < 2
            open[0]
        }, ping: { false }] as A2AStreamSink
        Closure<Map> invoker = { Map body, boolean resume, LlmStreamListener listener ->
            listener.onDelta('a')
            listener.onDelta('b')
            [content: 'ab', yielded: false, model: 'fake', profile: 'default', pendingToolCalls: []]
        }

        when:
        Map result = A2AExecutorImpl.run(ec, request('m-disconnect'), invoker, sink)

        then: 'the producer stops writing to the gone client but finishes and persists the task'
        seen.size() == 2
        result.task.status.state == 'TASK_STATE_COMPLETED'
        result.task.artifacts[0].parts[0].text == 'ab'
        ec.entity.find('moqui.a2a.A2ATask').condition('taskId', result.task.id).one().inFlight == 'N'
    }

    def 'a provider failure after partial output fails the task exactly once'() {
        given:
        List<Map> events = []
        A2AStreamSink sink = [emit: { Map event -> events << event; true }, ping: { true }] as A2AStreamSink
        Closure<Map> invoker = { Map body, boolean resume, LlmStreamListener listener ->
            listener.onDelta('partial')
            throw new IllegalStateException('provider failed')
        }

        when:
        A2AExecutorImpl.run(ec, request('m-stream-fail'), invoker, sink)

        then:
        thrown(IllegalStateException)

        and:
        String taskId = events.first().task.id
        events*.statusUpdate.findAll { it }*.status*.state.count('TASK_STATE_FAILED') == 1
        events.last().statusUpdate.status.state == 'TASK_STATE_FAILED'
        A2AGateway.getTask(ec, [taskId: taskId]).task.status.state == 'TASK_STATE_FAILED'
        ec.entity.find('moqui.a2a.A2ATask').condition('taskId', taskId).one().inFlight == 'N'
        A2AGateway.subscribeTask(ec, [taskId: taskId, afterOrdinal: -1L])
                .events*.statusUpdate.findAll { it }*.status*.state.count('TASK_STATE_FAILED') == 1
    }

    def 'a terminal task accepts no further events'() {
        given:
        Map accepted = A2AGateway.acceptMessage(ec, request('m-terminal-events'))
        String taskId = accepted.task.id
        A2AGateway.cancelTask(ec, [taskId: taskId])
        long eventsAfterCancel = ec.entity.find('moqui.a2a.A2ATaskEvent').condition('taskId', taskId).count()

        expect:
        eventsAfterCancel == 2
        thrownIllegalState { A2AGateway.updateStatus(ec, taskId, 'TASK_STATE_COMPLETED') }
        thrownIllegalState { A2AGateway.addArtifact(ec, [taskId: taskId, artifact: [artifactId: 'late', parts: [[text: 'x']]]]) }
        ec.entity.find('moqui.a2a.A2ATaskEvent').condition('taskId', taskId).count() == eventsAfterCancel
        A2AGateway.subscribeTask(ec, [taskId: taskId, afterOrdinal: -1L]).events.size() == eventsAfterCancel
    }

    def 'the A2A layers keep their dependencies'() {
        given:
        String runtimeDir = System.getProperty('moqui.runtime') ?: '../runtime'
        File frameworkDir = new File(new File(runtimeDir).absoluteFile.parentFile, 'framework/src/main/groovy/org/moqui/impl')
        Map<String, List<String>> forbidden = [
            'llm/a2a/A2ATypes.groovy'      : ['jakarta.servlet', 'LlmClient', 'A2ATaskStore', 'A2AGateway', 'A2AExecutor'],
            'llm/a2a/A2ATaskStore.groovy'  : ['jakarta.servlet', 'LlmClient', 'LlmFacade', 'A2AJsonRpc', 'A2AGateway', 'A2AExecutor'],
            'llm/a2a/A2AExecutorImpl.groovy': ['jakarta.servlet', 'A2AJsonRpc', 'A2AServlet'],
            'llm/a2a/A2AJsonRpc.groovy'    : ['jakarta.servlet', 'EntityValue', 'ec.entity', 'LlmClient', 'A2ATaskStore', 'A2AExecutor'],
            'llm/a2a/A2AFacadeImpl.groovy' : ['jakarta.servlet', 'EntityValue', 'ec.entity', 'A2ATaskStore', 'A2AExecutor'],
            'webapp/A2AServlet.groovy'     : ['EntityValue', 'ec.entity', 'LlmClient', 'A2ATaskStore', 'A2AGateway', 'A2AExecutor'],
            'webapp/A2ACardServlet.groovy' : ['EntityValue', 'ec.entity', 'LlmClient', 'A2ATaskStore', 'A2AGateway', 'A2AExecutor']]

        expect: 'no layer reaches across its boundary'
        forbidden.every { String path, List<String> tokens ->
            String source = new File(frameworkDir, path).text
            tokens.every { String token -> !source.contains(token) }
        }

        and: 'the monolith is gone'
        !new File(frameworkDir, 'llm/a2a/A2AClientImpl.groovy').exists()
        new File(frameworkDir, 'llm/a2a/A2AGateway.groovy').exists()
        new File(frameworkDir, 'llm/a2a/A2ATaskStore.groovy').exists()
        new File(frameworkDir, 'llm/a2a/A2ATypes.groovy').exists()
        new File(frameworkDir, 'llm/a2a/A2AExecutor.groovy').exists()
        new File(frameworkDir, 'llm/a2a/A2AFacadeImpl.groovy').exists()

        and: 'no event bus was introduced with this layering'
        !new File(frameworkDir, 'llm/a2a/A2ATaskBus.groovy').exists()
    }

    private static Map request(String messageId) {
        [profile: 'default', message: [messageId: messageId, role: 'ROLE_USER', parts: [[text: 'hello']]]]
    }

    /** Runs work as a second, throwaway user, then restores john.doe. */
    private void withOtherUser(Closure work) {
        ec.transaction.runUseOrBegin(null, 'A2A test user create failed') {
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
            ec.transaction.runUseOrBegin(null, 'A2A test user delete failed') {
                ec.entity.find('moqui.security.UserAccount').condition('userId', OTHER_USER_ID).disableAuthz().deleteAll()
            }
        }
    }

    private static int code(Closure work) {
        try {
            work.call()
            return 0
        } catch (A2AException e) {
            return e.code
        }
    }

    private static boolean thrownIllegalState(Closure work) {
        try {
            work.call()
            return false
        } catch (IllegalStateException ignored) {
            return true
        }
    }

    private static boolean invalid(Closure work) {
        try {
            work.call()
            return false
        } catch (IllegalArgumentException ignored) {
            return true
        }
    }

    private void clearA2AData() {
        ['A2APushConfig', 'A2ATaskEvent', 'A2AArtifactPart', 'A2AArtifact',
         'A2ATaskStatus', 'A2AMessagePart', 'A2AMessage', 'A2ATask', 'A2AContext'].each { name ->
            try { ec.entity.find("moqui.a2a.${name}").disableAuthz().deleteAll() }
            catch (Throwable ignored) { }
        }
    }
}
