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
package org.moqui.impl.llm.a2a

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityList
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.LlmGateway
import org.moqui.llm.LlmConversation
import org.moqui.llm.LlmStreamListener
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.function.Supplier

final class A2AExecutorImpl implements A2AExecutor {
    private static final Logger logger = LoggerFactory.getLogger(A2AExecutorImpl.class)
    /** Artifact that carries the agent's textual answer; streamed as chunks, stored whole. */
    static final String RESPONSE_ARTIFACT_ID = 'response'
    private static final List<String> ASSIST_TOOLS =
        ['request', 'write_ui', 'browse', 'run_service', 'find_skill', 'enter_sim'].asImmutable() as List<String>
    private static final A2AExecutor executor = new A2AExecutorImpl()

    private A2AExecutorImpl() { }

    static Map<String, Object> sendMessage(ExecutionContext ec, Map<String, Object> request) {
        executor.execute(ec, request)
    }

    static Map<String, Object> sendStreamingMessage(ExecutionContext ec, Map<String, Object> request) {
        executor.executeStreaming(ec, request)
    }

    /** SendStreamingMessage: the LLM turn is streamed (LlmClient.stream) and every A2A event goes to the sink live. */
    static Map<String, Object> streamMessage(ExecutionContext ec, Map<String, Object> request, A2AStreamSink sink) {
        run(ec, request, liveInvoker(ec), sink)
    }

    @Override
    Map<String, Object> execute(ExecutionContext ec, Map<String, Object> request) {
        run(ec, request, blockingInvoker(ec), null)
    }

    /** Binding-neutral streaming for the internal service: same live execution, events collected in order. */
    @Override
    Map<String, Object> executeStreaming(ExecutionContext ec, Map<String, Object> request) {
        List<Map<String, Object>> events = []
        A2AStreamSink collector = [emit: { Map<String, Object> event -> events.add(event); true },
                                   ping: { true }] as A2AStreamSink
        Map<String, Object> result = run(ec, request, liveInvoker(ec), collector)
        [task: result.task, streamResponseList: events] as Map<String, Object>
    }

    /** Blocking run with a caller-supplied model call (body, resume) returning the LlmGateway.responseToMap shape. */
    static Map<String, Object> sendMessage(ExecutionContext ec, Map<String, Object> request,
            Closure<Map<String, Object>> invokeLlm) {
        run(ec, request, { Map<String, Object> body, boolean resume, LlmStreamListener listener ->
            invokeLlm.call(body, resume) }, null)
    }

    private static Closure<Map<String, Object>> blockingInvoker(ExecutionContext ec) {
        return { Map<String, Object> body, boolean resume, LlmStreamListener listener ->
            resume ? LlmGateway.resume(ec, body) : LlmGateway.chat(ec, body)
        }
    }

    private static Closure<Map<String, Object>> liveInvoker(ExecutionContext ec) {
        return { Map<String, Object> body, boolean resume, LlmStreamListener listener ->
            LlmGateway.withoutCallerTx(ec, {
                LlmGateway.responseToMap(LlmGateway.prepareClient(ec, body, resume).stream(listener))
            } as Supplier<Map<String, Object>>)
        }
    }

    /**
     * Accept the message, run one LLM turn for the task, and record the outcome. invokeLlm is called as
     * (body, resume, listener); with a sink, events are emitted in A2A order: Task, WORKING, response artifact
     * chunks, final status. The stream ends at a terminal or interrupted state (resume is a new message).
     */
    static Map<String, Object> run(ExecutionContext ec, Map<String, Object> request,
            Closure<Map<String, Object>> invokeLlm, A2AStreamSink sink) {
        request.configuration = A2ATypes.requireConfiguration(request.configuration)
        Object historyLength = (request.configuration as Map)?.historyLength
        Map<String, Object> accepted = A2AGateway.acceptMessage(ec, request)
        if (accepted.replayed == true) {
            Map<String, Object> replay = accepted.pending == true ?
                    A2ATaskStore.awaitReplay(ec, accepted, request) :
                    (accepted.result instanceof Map ? (Map<String, Object>) accepted.result :
                            [task: accepted.task] as Map<String, Object>)
            emit(sink, [task: replay.task] as Map<String, Object>)
            return replay
        }

        EntityValue task = accepted.taskValue as EntityValue
        EntityValue inputMessage = accepted.messageValue as EntityValue
        Map<String, Object> message = accepted.message as Map<String, Object>
        boolean continuation = accepted.continuation == true
        emit(sink, [task: accepted.task] as Map<String, Object>)
        try {
            String pendingToolCallId = task.pendingToolCallId as String
            String pendingToolName = task.pendingToolName as String
            emitStatus(sink, A2AGateway.updateStatus(ec, task, 'TASK_STATE_WORKING', null,
                    [inFlight: 'Y', pendingToolCallId: null, pendingToolName: null]))
            String profile = task.workerProfileName as String ?: request.profile as String ?: A2ATypes.defaultProfile()
            Map<String, Object> body = [
                profile: profile,
                conversationId: conversationIdForContext(ec, task.contextId as String, profile),
                tools: ASSIST_TOOLS
            ]
            Map<String, Object> metadata = message.metadata instanceof Map ? (Map<String, Object>) message.metadata : [:]
            if (metadata.containsKey('forceSkillUse')) body.forceSkillUse = metadata.forceSkillUse
            if (metadata.activeSkillName) body.activeSkillName = metadata.activeSkillName

            ResponseChunks chunks = sink != null ? new ResponseChunks(task, sink) : null
            Map<String, Object> llmResult
            if (continuation) {
                body.toolResults = [[toolCallId: pendingToolCallId, name: pendingToolName, content: resumeContent(message)]]
                llmResult = invokeLlm.call(body, true, chunks)
            } else {
                body.user = A2ATypes.messageText(message)
                llmResult = invokeLlm.call(body, false, chunks)
            }

            Map<String, Object> agentMessage = responseMessage(task, llmResult)
            if (agentMessage.parts) A2ATaskStore.appendAgentMessage(ec, task, agentMessage)
            String content = llmResult.content as String
            if (content) {
                A2AGateway.addArtifact(ec, [taskId: task.taskId, lastChunk: true, artifact: [
                    artifactId: RESPONSE_ARTIFACT_ID, name: 'Response', parts: [[text: content, mediaType: 'text/plain']]]])
                chunks?.finish(content)
            }
            List<Map<String, Object>> pending = llmResult.pendingToolCalls instanceof List ?
                    (List<Map<String, Object>>) llmResult.pendingToolCalls : []
            if (llmResult.yielded == true) {
                Map<String, Object> pendingCall = pending ? pending.first() : [:]
                emitStatus(sink, A2AGateway.updateStatus(ec, task, 'TASK_STATE_INPUT_REQUIRED', agentMessage,
                    [inFlight: 'N', pendingToolCallId: pendingCall.id, pendingToolName: pendingCall.name]))
            } else {
                emitStatus(sink, A2AGateway.updateStatus(ec, task, 'TASK_STATE_COMPLETED', agentMessage,
                    [inFlight: 'N', pendingToolCallId: null, pendingToolName: null]))
            }
            Map<String, Object> result = [task: A2AGateway.getTask(ec, [taskId: task.taskId,
                historyLength: historyLength]).task] as Map<String, Object>
            A2ATaskStore.saveResult(ec, inputMessage, task, result)
            result
        } catch (Throwable failure) {
            // CancelTask from another request wins: the aborted or late turn reports the canceled task
            if (stateOrNull(ec, task.taskId as String) == 'TASK_STATE_CANCELED') {
                Map<String, Object> canceled = [task: A2AGateway.getTask(ec, [taskId: task.taskId,
                    historyLength: historyLength]).task] as Map<String, Object>
                emitStatus(sink, canceled)
                return canceled
            }
            logger.error("A2A task ${task.taskId} failed", failure)
            try {
                String statusText = failure instanceof A2AException ?
                        (failure.message ?: 'Internal error') : 'Internal error'
                Map<String, Object> errorMessage = [
                    messageId: UUID.randomUUID().toString(), role: 'ROLE_AGENT',
                    parts: [[text: statusText]]
                ]
                emitStatus(sink, A2AGateway.updateStatus(ec, task, 'TASK_STATE_FAILED', errorMessage,
                    [inFlight: 'N', pendingToolCallId: null, pendingToolName: null]))
                Map<String, Object> result = [task: A2AGateway.getTask(ec, [taskId: task.taskId,
                    historyLength: historyLength]).task] as Map<String, Object>
                A2ATaskStore.saveResult(ec, inputMessage, task, result)
                return result
            } catch (Throwable ignored) { }
            throw failure
        }
    }

    /** LLM conversation backing an A2A context; the conversation row carries the contextId. */
    static String createConversation(ExecutionContext ec, String profile, String contextId, Map<String, Object> metadata) {
        LlmConversation conversation = ec.llm.createConversation(profile,
            [purpose: 'a2a', a2aContext: true, contextId: contextId, metadata: metadata ?: [:]])
        conversation.persist()
        A2ATaskStore.disabled(ec) {
            ec.entity.find('moqui.llm.LlmConversation').condition('conversationId', conversation.conversationId)
                .forUpdate(true).one()?.set('contextId', contextId)?.update()
        }
        conversation.conversationId
    }

    static String conversationIdForContext(ExecutionContext ec, String contextId, String profile) {
        EntityValue conversation = A2ATaskStore.disabled(ec) {
            EntityList rows = ec.entity.find('moqui.llm.LlmConversation').condition('contextId', contextId)
                .orderBy('conversationId').limit(1).list()
            rows ? rows.first() : null
        }
        if (conversation != null) return conversation.conversationId as String

        LlmConversation created = ec.llm.createConversation(profile, [purpose: 'a2a', a2aContext: true, contextId: contextId])
        created.persist()
        A2ATaskStore.disabled(ec) {
            EntityValue stored = ec.entity.find('moqui.llm.LlmConversation')
                .condition('conversationId', created.conversationId).forUpdate(true).one()
            if (stored != null) stored.set('contextId', contextId).update()
        }
        created.conversationId
    }

    /** Aborts the provider stream of the conversations behind a canceled task. */
    static void requestCancel(ExecutionContext ec, String contextId) {
        if (!(ec.llm instanceof LlmFacadeImpl)) return
        EntityList conversations = A2ATaskStore.disabled(ec) {
            ec.entity.find('moqui.llm.LlmConversation').condition('contextId', contextId).useCache(false).list()
        }
        for (EntityValue conversation in conversations)
            ((LlmFacadeImpl) ec.llm).abortInFlight(conversation.conversationId as String)
    }

    private static String stateOrNull(ExecutionContext ec, String taskId) {
        try {
            return A2AGateway.taskState(ec, taskId)
        } catch (Throwable ignored) {
            return null
        }
    }

    private static void emit(A2AStreamSink sink, Map<String, Object> streamResponse) {
        if (sink != null) sink.emit(streamResponse)
    }

    private static void emitStatus(A2AStreamSink sink, Map<String, Object> taskResult) {
        if (sink == null) return
        Map<String, Object> wireTask = taskResult.task as Map<String, Object>
        sink.emit([statusUpdate: [taskId: wireTask.id, contextId: wireTask.contextId, status: wireTask.status]]
                as Map<String, Object>)
    }

    private static Map<String, Object> responseMessage(EntityValue task, Map<String, Object> response) {
        List<Map<String, Object>> parts = []
        if (response.content) parts.add([text: response.content as String])
        if (response.yielded == true && response.pendingToolCalls)
            parts.add([data: [inputRequired: true, pendingToolCalls: response.pendingToolCalls]])
        [
            messageId: UUID.randomUUID().toString(),
            role: 'ROLE_AGENT',
            taskId: task.taskId,
            contextId: task.contextId,
            parts: parts,
            metadata: [
                model: response.model,
                profile: response.profile
            ]
        ]
    }

    private static Object resumeContent(Map<String, Object> message) {
        List<Map<String, Object>> parts = message.parts as List<Map<String, Object>>
        Map<String, Object> dataPart = parts.find { Map<String, Object> part -> part.containsKey('data') }
        if (dataPart) return dataPart.data
        String text = A2ATypes.messageText(message)
        text ?: [:]
    }

    /**
     * LLM stream listener that turns text deltas into TaskArtifactUpdateEvents for the response artifact
     * (append after the first chunk, lastChunk on finish). Deltas are live only; the whole text is persisted once.
     */
    static final class ResponseChunks implements LlmStreamListener {
        private final A2AStreamSink sink
        private final String taskId
        private final String contextId
        private boolean started = false
        private boolean open = true

        ResponseChunks(EntityValue task, A2AStreamSink sink) {
            this.sink = sink
            this.taskId = task.taskId as String
            this.contextId = task.contextId as String
        }

        @Override
        void onDelta(String textDelta) { if (textDelta) send(textDelta, started, false) }

        @Override
        void onPing() { if (open) open = sink.ping() }

        void finish(String content) {
            if (started) send('', true, true)
            else send(content, false, true)
        }

        private void send(String text, boolean append, boolean lastChunk) {
            started = true
            if (!open) return
            open = sink.emit([artifactUpdate: [taskId: taskId, contextId: contextId, append: append, lastChunk: lastChunk,
                artifact: [artifactId: RESPONSE_ARTIFACT_ID, name: 'Response', parts: [[text: text]]]]] as Map<String, Object>)
        }
    }
}
