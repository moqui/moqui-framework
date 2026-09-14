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


final class A2AGateway {
    private A2AGateway() { }

    static Map<String, Object> sendMessage(ExecutionContext ec, Map<String, Object> request) {
        A2AExecutorImpl.sendMessage(ec, request)
    }

    static Map<String, Object> sendStreamingMessage(ExecutionContext ec, Map<String, Object> request) {
        A2AExecutorImpl.sendStreamingMessage(ec, request)
    }

    // ===== Public operations: every one starts a short isolated transaction and checks ownership =====

    /** SendStreamingMessage: the executor runs the turn and every A2A event goes to the sink as it happens. */
    static Map<String, Object> streamMessage(ExecutionContext ec, Map<String, Object> request, A2AStreamSink sink) {
        A2AExecutorImpl.streamMessage(ec, request, sink)
    }

    static Map<String, Object> acceptMessage(ExecutionContext ec, Map<String, Object> request) {
        Map<String, Object> message = A2ATypes.requireMessage(request.message, true)
        String userId = A2ATaskStore.requireUser(ec)
        String profile = A2ATypes.text(request.profile) ?: A2ATypes.defaultProfile()

        try {
            return A2ATaskStore.isolated(ec) { acceptMessageTx(ec, request, message, userId, profile) }
        } catch (Throwable failure) {
            Map<String, Object> replay = A2ATaskStore.isolated(ec) {
                EntityValue duplicate = A2ATaskStore.findMessage(ec, userId, message.messageId as String)
                duplicate != null ? A2ATaskStore.replayResult(ec, duplicate, request) : null
            }
            if (replay != null) return replay
            throw failure
        }
    }

    private static Map<String, Object> acceptMessageTx(ExecutionContext ec, Map<String, Object> request,
            Map<String, Object> message, String userId, String profile) {
        EntityValue duplicate = A2ATaskStore.findMessage(ec, userId, message.messageId as String)
        if (duplicate != null) return A2ATaskStore.replayResult(ec, duplicate, request)

        // referenced tasks must exist and belong to this user; ownedTask hides anything else as 'task not found'
        for (Object referenced in (message.referenceTaskIds as List) ?: []) A2ATaskStore.ownedTask(ec, referenced as String)

        EntityValue task
        EntityValue a2aContext
        boolean continuation = message.taskId != null
        if (continuation) {
            task = A2ATaskStore.ownedTask(ec, message.taskId as String, true)
            if (message.contextId != null && message.contextId != task.contextId)
                throw new A2AException(A2AException.TASK_NOT_FOUND, 'task not found')
            if (A2ATaskStore.terminal(ec, task))
                throw new A2AException(A2AException.UNSUPPORTED_OPERATION,
                    "task ${task.taskId} is ${A2ATaskStore.currentState(ec, task)} and cannot accept further messages")
            if (A2ATaskStore.currentState(ec, task) != 'TASK_STATE_INPUT_REQUIRED' || !task.pendingToolCallId)
                throw new A2AException(A2AException.INVALID_PARAMS,
                    'only an input-required task with a pending tool call can be continued')
            a2aContext = A2ATaskStore.ownedContext(ec, task.contextId as String)
            message.contextId = task.contextId
        } else {
            a2aContext = message.contextId != null ? A2ATaskStore.ownedContext(ec, message.contextId as String, true) : null
            if (a2aContext == null) a2aContext = createContext(ec, profile, request.metadata as Map<String, Object>)
            a2aContext = A2ATaskStore.ownedContext(ec, a2aContext.contextId as String, true)
            EntityValue active = A2ATaskStore.findActiveTask(ec, a2aContext.contextId as String)
            if (active != null)
                throw new A2AException(A2AException.INVALID_PARAMS,
                    'context has an in-flight task; include message.taskId to continue it or wait until it is terminal')
            task = A2ATaskStore.createTask(ec, a2aContext, profile, request.metadata as Map<String, Object>)
            message.contextId = a2aContext.contextId
            message.taskId = task.taskId
        }

        EntityValue messageValue = A2ATaskStore.persistMessage(ec, task, message)
        return [
            replayed: false,
            continuation: continuation,
            context: a2aContext,
            taskValue: task,
            messageValue: messageValue,
            message: message,
            task: A2ATaskStore.taskMap(ec, task, A2ATypes.historyLength((request.configuration as Map)?.historyLength), true)
        ] as Map<String, Object>
    }

    /** New context: the executor owns the LLM conversation, the store owns the A2AContext row. */
    private static EntityValue createContext(ExecutionContext ec, String profile, Map<String, Object> metadata) {
        String contextId = UUID.randomUUID().toString()
        A2AExecutorImpl.createConversation(ec, profile, contextId, metadata)
        A2ATaskStore.createContextRow(ec, contextId, metadata)
    }

    static Map<String, Object> getTask(ExecutionContext ec, Map<String, Object> request) {
        A2ATaskStore.isolated(ec) {
            EntityValue task = A2ATaskStore.ownedTask(ec, request.taskId as String)
            [task: A2ATaskStore.taskMap(ec, task, A2ATypes.historyLength(request.historyLength), true)] as Map<String, Object>
        }
    }

    /** Current wire state of an owned task, read in its own transaction. */
    static String taskState(ExecutionContext ec, String taskId) {
        A2ATaskStore.isolated(ec) { A2ATaskStore.currentState(ec, A2ATaskStore.ownedTask(ec, taskId)) }
    }

    static Map<String, Object> listTasks(ExecutionContext ec, Map<String, Object> request) {
        A2ATaskStore.isolated(ec) { A2ATaskStore.listTasksTx(ec, request) }
    }

    static Map<String, Object> cancelTask(ExecutionContext ec, Map<String, Object> request) {
        Map<String, Object> outcome = A2ATaskStore.isolated(ec) {
            EntityValue task = A2ATaskStore.ownedTask(ec, request.taskId as String, true)
            if (A2ATaskStore.terminal(ec, task))
                throw new A2AException(A2AException.TASK_NOT_CANCELABLE,
                    "task ${task.taskId} is ${A2ATaskStore.currentState(ec, task)} and cannot be canceled")
            boolean inFlight = task.inFlight == 'Y'
            A2ATaskStore.setStatus(ec, task, A2ATypes.stateId(ec, 'TASK_STATE_CANCELED'), null, [cancelRequested: 'Y', inFlight: 'N'])
            [task: A2ATaskStore.taskMap(ec, task, A2ATypes.historyLength(request.historyLength), true), inFlight: inFlight,
             contextId: task.contextId] as Map<String, Object>
        }
        // the LLM turn runs on the thread that sent the message; abort its provider stream so it stops early
        if (outcome.inFlight) A2AExecutorImpl.requestCancel(ec, outcome.contextId as String)
        [task: outcome.task] as Map<String, Object>
    }

    static Map<String, Object> getExtendedAgentCard(ExecutionContext ec, Map<String, Object> request) {
        [agentCard: A2ACardBuilderImpl.buildExtended(ec, request)]
    }

    static Map<String, Object> updateStatus(ExecutionContext ec, String taskId, String state,
            Map<String, Object> statusMessage = null, Map<String, Object> extraFields = [:]) {
        A2ATaskStore.isolated(ec) {
            EntityValue task = A2ATaskStore.ownedTask(ec, taskId, true)
            A2ATaskStore.setStatus(ec, task, A2ATypes.stateId(ec, state), statusMessage, extraFields)
            [task: A2ATaskStore.taskMap(ec, task, 20, true)] as Map<String, Object>
        }
    }

    static Map<String, Object> updateStatus(ExecutionContext ec, EntityValue task, String state,
            Map<String, Object> statusMessage = null, Map<String, Object> extraFields = [:]) {
        Map<String, Object> result = updateStatus(ec, task.taskId as String, state, statusMessage, extraFields)
        EntityValue refreshed = A2ATaskStore.ownedTask(ec, task.taskId as String)
        task.setAll(refreshed.getMap())
        result
    }

    static Map<String, Object> addArtifact(ExecutionContext ec, Map<String, Object> request) {
        A2ATaskStore.isolated(ec) { A2ATaskStore.addArtifactTx(ec, request) }
    }

    static Map<String, Object> subscribeTask(ExecutionContext ec, Map<String, Object> request) {
        A2ATaskStore.isolated(ec) { A2ATaskStore.subscribeTaskTx(ec, request) }
    }

    static Map<String, Object> createPushConfig(ExecutionContext ec, Map<String, Object> request) {
        A2ATaskStore.isolated(ec) { A2ATaskStore.createPushConfigTx(ec, request) }
    }

    static Map<String, Object> getPushConfig(ExecutionContext ec, Map<String, Object> request) {
        A2ATaskStore.isolated(ec) { A2ATaskStore.getPushConfigTx(ec, request) }
    }

    static Map<String, Object> listPushConfigs(ExecutionContext ec, Map<String, Object> request) {
        A2ATaskStore.isolated(ec) { A2ATaskStore.listPushConfigsTx(ec, request) }
    }

    static Map<String, Object> deletePushConfig(ExecutionContext ec, Map<String, Object> request) {
        A2ATaskStore.isolated(ec) { A2ATaskStore.deletePushConfigTx(ec, request) }
    }

    // ===== Persistence: task lifecycle, messages, artifacts and the event log =====


}
