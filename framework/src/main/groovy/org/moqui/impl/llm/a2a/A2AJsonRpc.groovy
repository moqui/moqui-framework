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
import org.moqui.impl.context.ContextJavaUtil
import org.moqui.llm.LlmException

import java.sql.Timestamp
import java.time.Instant

/**
 * A2A 1.0 JSON-RPC binding (spec section 9): PascalCase methods, wire params and results, A2A error codes.
 * Transport-neutral so it can be tested without a servlet; A2AServlet handles HTTP and SSE framing.
 */
final class A2AJsonRpc {
    static final Set<String> STREAMING_METHODS = ['SendStreamingMessage', 'SubscribeToTask'] as Set<String>
    static final Set<String> SUPPORTED_VERSIONS = ['1.0', '1.0.0'] as Set<String>
    private static final Set<String> PUSH_METHODS = ['CreateTaskPushNotificationConfig', 'GetTaskPushNotificationConfig',
        'ListTaskPushNotificationConfigs', 'DeleteTaskPushNotificationConfig'] as Set<String>

    private A2AJsonRpc() { }

    static Map<String, Object> parseRequest(String text) {
        Object parsed
        try {
            parsed = ContextJavaUtil.jacksonMapper.readValue(text ?: '', Object.class)
        } catch (Throwable t) {
            throw new A2AException(A2AException.PARSE_ERROR, 'Invalid JSON: ' + (t.message?.readLines()?.first() ?: t.toString()))
        }
        if (parsed instanceof List) throw new A2AException(A2AException.INVALID_REQUEST, 'Batch requests are not supported')
        if (!(parsed instanceof Map)) throw new A2AException(A2AException.INVALID_REQUEST, 'Request must be a JSON object')
        (Map<String, Object>) parsed
    }

    static void validateEnvelope(Map<String, Object> call) {
        if (call.jsonrpc != '2.0') throw new A2AException(A2AException.INVALID_REQUEST, 'jsonrpc must be "2.0"')
        if (!(call.method instanceof String) || !call.method)
            throw new A2AException(A2AException.INVALID_REQUEST, 'method is required')
        if (call.params != null && !(call.params instanceof Map))
            throw new A2AException(A2AException.INVALID_PARAMS, 'params must be an object')
    }

    /** A2A-Version (spec 3.6.1): an empty value means 0.3, which this 1.0 server does not implement. */
    static void checkVersion(String version) {
        String requested = version?.trim()
        if (!requested)
            throw new A2AException(A2AException.VERSION_NOT_SUPPORTED,
                'A2A-Version header is required; an empty value means 0.3, which is not supported (supported: 1.0)')
        if (!SUPPORTED_VERSIONS.contains(requested))
            throw new A2AException(A2AException.VERSION_NOT_SUPPORTED, "A2A-Version ${requested} is not supported (supported: 1.0)")
    }

    static Map<String, Object> params(Map<String, Object> call) {
        call.params instanceof Map ? new LinkedHashMap<String, Object>((Map<String, Object>) call.params) : new LinkedHashMap<String, Object>()
    }

    /** Unary methods; the result is the wire object for the method (raw Task, SendMessageResponse, AgentCard, ...). */
    static Object invoke(ExecutionContext ec, String method, Map<String, Object> params, String baseUrl) {
        if (PUSH_METHODS.contains(method))
            throw new A2AException(A2AException.PUSH_NOTIFICATION_NOT_SUPPORTED, 'Push notifications are not supported by this agent')
        switch (method) {
            case 'SendMessage':
                return A2AGateway.sendMessage(ec, sendRequest(params))
            case 'GetTask':
                return A2AGateway.getTask(ec, [taskId: params.id, historyLength: params.historyLength]).task
            case 'ListTasks':
                return A2AGateway.listTasks(ec, listRequest(params))
            case 'CancelTask':
                return A2AGateway.cancelTask(ec, [taskId: params.id]).task
            case 'GetExtendedAgentCard':
                return A2ACardBuilderImpl.buildExtended(ec, [baseUrl: baseUrl, streaming: true])
            case 'SendStreamingMessage':
            case 'SubscribeToTask':
                throw new A2AException(A2AException.INVALID_REQUEST, "${method} is answered as a text/event-stream")
            default:
                throw new A2AException(A2AException.METHOD_NOT_FOUND, "Method ${method} not found")
        }
    }

    /** Streaming methods: StreamResponse objects go to the sink; throws before the first event on request errors. */
    static void stream(ExecutionContext ec, String method, Map<String, Object> params, A2AStreamSink sink,
            Map<String, Object> options) {
        if (method == 'SendStreamingMessage') A2AGateway.streamMessage(ec, sendRequest(params), sink)
        else if (method == 'SubscribeToTask') subscribe(ec, params, sink, options ?: [:])
        else throw new A2AException(A2AException.METHOD_NOT_FOUND, "Method ${method} not found")
    }

    /**
     * SubscribeToTask: current Task first, then persisted status/artifact events until the task is terminal.
     * The task runs on another request thread, so events are read from the A2ATaskEvent log by polling.
     */
    private static void subscribe(ExecutionContext ec, Map<String, Object> params, A2AStreamSink sink, Map<String, Object> options) {
        long pollMillis = (options.pollMillis ?: 500L) as long
        long timeoutMillis = ((options.timeoutSeconds ?: 600) as long) * 1000L
        long pingMillis = 15000L
        Map<String, Object> snapshot = A2AGateway.subscribeTask(ec, [taskId: params.id, afterOrdinal: -1L,
            historyLength: params.historyLength])
        String state = (snapshot.task as Map).status?.state
        if (A2ATypes.terminalState(state))
            throw new A2AException(A2AException.UNSUPPORTED_OPERATION, "task is ${state}; terminal tasks cannot be subscribed to")
        if (!sink.emit([task: snapshot.task] as Map<String, Object>)) return
        long lastOrdinal = snapshot.lastOrdinal as long
        long deadline = System.currentTimeMillis() + timeoutMillis
        long lastWrite = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(pollMillis)
            Map<String, Object> delta = A2AGateway.subscribeTask(ec, [taskId: params.id, afterOrdinal: lastOrdinal,
                historyLength: 0, includeArtifacts: false])
            for (Map<String, Object> event in (delta.events as List<Map<String, Object>>)) {
                if (!sink.emit(event)) return
                lastWrite = System.currentTimeMillis()
            }
            lastOrdinal = delta.lastOrdinal as long
            if (A2ATypes.terminalState((delta.task as Map).status?.state as String)) return
            if (System.currentTimeMillis() - lastWrite >= pingMillis) {
                if (!sink.ping()) return
                lastWrite = System.currentTimeMillis()
            }
        }
    }

    static Map<String, Object> success(Object id, Object result) {
        [jsonrpc: '2.0', id: id, result: result] as Map<String, Object>
    }

    static Map<String, Object> failure(Object id, A2AException error) {
        [jsonrpc: '2.0', id: id, error: error.toErrorMap()] as Map<String, Object>
    }

    static A2AException toA2A(Throwable t) {
        if (t instanceof A2AException) return (A2AException) t
        if (t instanceof IllegalArgumentException)
            return new A2AException(A2AException.INVALID_PARAMS, t.message ?: 'Invalid params', t)
        if (t instanceof UnsupportedOperationException)
            return new A2AException(A2AException.UNSUPPORTED_OPERATION, t.message ?: 'Unsupported operation', t)
        if (t instanceof LlmException) return new A2AException(A2AException.INTERNAL_ERROR, t.message ?: 'LLM error', t)
        // anything else may carry internals (SQL, file paths, class names): the caller gets a generic message,
        // the servlet logs the original with its stack trace
        new A2AException(A2AException.INTERNAL_ERROR, 'Internal error', t)
    }

    private static Map<String, Object> sendRequest(Map<String, Object> params) {
        if (params.metadata != null && !(params.metadata instanceof Map))
            throw new IllegalArgumentException('metadata must be an object')
        [message: params.message, configuration: params.configuration, metadata: params.metadata] as Map<String, Object>
    }

    private static Map<String, Object> listRequest(Map<String, Object> params) {
        Timestamp after = null
        if (params.statusTimestampAfter != null) {
            try {
                after = Timestamp.from(Instant.parse(params.statusTimestampAfter as String))
            } catch (Throwable ignored) {
                throw new IllegalArgumentException('statusTimestampAfter must be an RFC 3339 timestamp')
            }
        }
        [contextId: params.contextId, status: params.status, pageSize: params.pageSize, pageToken: params.pageToken,
         historyLength: params.historyLength, includeArtifacts: params.includeArtifacts,
         statusTimestampAfter: after] as Map<String, Object>
    }
}
