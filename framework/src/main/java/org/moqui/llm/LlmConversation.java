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
import java.util.Map;

/** Durable conversation handle. Persistence and windowing are implemented in a later PR. */
public interface LlmConversation {
    String getConversationId();
    String getProfileName();
    String getUserId();
    String getStatus();
    String getTitle();
    void setTitle(String title);

    List<LlmMessage> getHistory();
    List<LlmMessage> buildWindow();
    List<LlmMessage> buildWindow(WindowPolicy p);

    LlmConversation append(LlmMessage message);
    LlmConversation appendUser(String content);
    LlmConversation appendAssistant(String content);
    LlmConversation appendToolResult(String toolCallId, String name, Object content);

    LlmConversation replaceSystem(String content);
    LlmConversation injectContext(String source, String content);
    LlmConversation removeContextBySource(String source);
    LlmConversation clearContext();

    LlmConversation replaceMessage(String messageId, LlmMessage replacement);
    LlmConversation removeMessage(String messageId);

    LlmConversation setWindowPolicy(WindowPolicy policy);
    WindowPolicy getWindowPolicy();

    List<LlmToolCall> getPendingClientToolCalls();
    Map<String, Object> getAttributes();
    void setAttribute(String name, Object value);

    void cancel();
    void persist();
}
