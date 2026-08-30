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
    void onConversation(String conversationId);
    void onDelta(String textDelta);
    void onToolCall(LlmToolCall call, LlmTool.Execution execution);
    void onToolResult(LlmToolCall call, Object result, LlmTool.Execution execution);
    void onYield(List<LlmToolCall> pendingClientCalls);
    void onComplete(LlmResponse response);
    void onError(LlmException error);
}
