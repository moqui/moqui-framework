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
package org.moqui.llm;

import java.util.List;

public interface LlmStreamListener {
    default void onConversation(String conversationId) { }
    default void onDelta(String textDelta) { }
    default void onToolCall(LlmToolCall call, LlmTool.Execution execution) { }
    default void onToolResult(LlmToolCall call, Object result, LlmTool.Execution execution) { }
    default void onYield(List<LlmToolCall> pendingClientCalls) { }
    /** Heartbeat while server tools run (servlet SSE ping). */
    default void onPing() { }
    default void onComplete(LlmResponse response) { }
    default void onError(LlmException error) { }
    /** Drop / disconnect. Default wraps as {@link #onError}. */
    default void onFailure(Throwable t) {
        if (t instanceof LlmException) onError((LlmException) t);
        else onError(new LlmException(t != null ? t.getMessage() : "LLM stream failed", t));
    }
}
