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

import groovy.transform.CompileStatic

/** A2A protocol error carrying its JSON-RPC code (A2A 1.0 section 9.5 plus JSON-RPC 2.0 standard codes). */
@CompileStatic
class A2AException extends RuntimeException {
    static final int PARSE_ERROR = -32700
    static final int INVALID_REQUEST = -32600
    static final int METHOD_NOT_FOUND = -32601
    static final int INVALID_PARAMS = -32602
    static final int INTERNAL_ERROR = -32603
    static final int TASK_NOT_FOUND = -32001
    static final int TASK_NOT_CANCELABLE = -32002
    static final int PUSH_NOTIFICATION_NOT_SUPPORTED = -32003
    static final int UNSUPPORTED_OPERATION = -32004
    static final int CONTENT_TYPE_NOT_SUPPORTED = -32005
    static final int VERSION_NOT_SUPPORTED = -32009

    private static final Map<Integer, String> REASONS = [
        (TASK_NOT_FOUND): 'TASK_NOT_FOUND',
        (TASK_NOT_CANCELABLE): 'TASK_NOT_CANCELABLE',
        (PUSH_NOTIFICATION_NOT_SUPPORTED): 'PUSH_NOTIFICATION_NOT_SUPPORTED',
        (UNSUPPORTED_OPERATION): 'UNSUPPORTED_OPERATION',
        (CONTENT_TYPE_NOT_SUPPORTED): 'CONTENT_TYPE_NOT_SUPPORTED',
        (VERSION_NOT_SUPPORTED): 'VERSION_NOT_SUPPORTED'] as Map<Integer, String>

    final int code

    A2AException(int code, String message) {
        super(message)
        this.code = code
    }

    A2AException(int code, String message, Throwable cause) {
        super(message, cause)
        this.code = code
    }

    /** google.rpc.ErrorInfo reason for A2A-specific errors, null for plain JSON-RPC errors. */
    String getReason() { REASONS.get(code) }

    Map<String, Object> toErrorMap() {
        Map<String, Object> error = new LinkedHashMap<>()
        error.put('code', code)
        error.put('message', message ?: 'A2A error')
        String reason = getReason()
        if (reason != null) error.put('data', [['@type': 'type.googleapis.com/google.rpc.ErrorInfo',
            reason: reason, domain: 'a2a-protocol.org']])
        error
    }
}
