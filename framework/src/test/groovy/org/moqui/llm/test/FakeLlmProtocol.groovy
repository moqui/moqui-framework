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
package org.moqui.llm.test

import org.moqui.llm.LlmFinishReason
import org.moqui.llm.LlmProtocol
import org.moqui.llm.LlmProtocol.ProtocolRequest
import org.moqui.llm.LlmProtocol.ProtocolResult
import org.moqui.llm.LlmProtocol.ProtocolStreamListener

class FakeLlmProtocol implements LlmProtocol {
    boolean failIfInvoked = false
    int chatCount = 0
    int chatStreamCount = 0
    ProtocolRequest lastRequest
    List<ProtocolResult> results = []
    Closure<ProtocolResult> handler
    List<String> streamDeltas
    Throwable streamFailure

    @Override String getName() { return "fake" }
    @Override boolean supportsTools() { return false }
    @Override boolean supportsStreaming() { return true }

    @Override
    ProtocolResult chat(ProtocolRequest request) {
        if (failIfInvoked) throw new AssertionError("FakeLlmProtocol.chat() should not be invoked")
        chatCount++
        lastRequest = request
        if (handler != null) return handler.call(request)
        if (results.size() > 0) {
            int i = Math.min(chatCount - 1, results.size() - 1)
            return results.get(i)
        }
        ProtocolResult r = new ProtocolResult(LlmFinishReason.STOP)
        r.content = "ok"
        r.httpStatus = 200
        r.model = request != null ? request.model : null
        return r
    }

    @Override
    void chatStream(ProtocolRequest request, ProtocolStreamListener listener) {
        if (failIfInvoked) throw new AssertionError("FakeLlmProtocol.chatStream() should not be invoked")
        if (listener == null) throw new IllegalArgumentException("ProtocolStreamListener is required")
        chatStreamCount++
        lastRequest = request
        if (streamFailure != null) {
            if (streamDeltas != null) {
                for (String d : streamDeltas) if (d) listener.onDelta(d)
            }
            listener.onFailure(streamFailure)
            return
        }
        ProtocolResult r
        if (handler != null) r = handler.call(request)
        else if (results.size() > 0) {
            int i = Math.min(chatStreamCount - 1, results.size() - 1)
            r = results.get(i)
        } else {
            r = stop("ok")
            r.model = request != null ? request.model : null
        }
        if (streamDeltas != null) {
            for (String d : streamDeltas) if (d) listener.onDelta(d)
        } else if (r != null && r.content != null && !r.content.isEmpty()) {
            listener.onDelta(r.content)
        }
        listener.onComplete(r)
    }

    static ProtocolResult stop(String content) {
        ProtocolResult r = new ProtocolResult(LlmFinishReason.STOP)
        r.content = content
        r.httpStatus = 200
        return r
    }
    static ProtocolResult empty() {
        ProtocolResult r = new ProtocolResult(LlmFinishReason.EMPTY)
        r.httpStatus = 200
        return r
    }
}
