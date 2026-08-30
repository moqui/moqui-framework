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

/** Fluent one-shot builder. Not thread-safe; do not intern or store in a singleton/service field. */
public interface LlmClient {
    String getProfileName();

    LlmClient conversation(String conversationId);
    LlmClient conversation(LlmConversation conversation);
    LlmClient newConversation();

    LlmClient system(String content);
    LlmClient user(String content);
    /** Extra messages for this HTTP round only. Never persisted. */
    LlmClient messages(List<LlmMessage> extra);
    LlmClient injectContext(String source, String content);

    LlmClient tool(LlmTool tool);
    LlmClient tools(List<LlmTool> tools);
    LlmClient toolResults(List<LlmToolResult> results);
    LlmClient allowClientTools(boolean allow);
    LlmClient allowedEntity(String entityName);
    LlmClient allowedPath(String prefix, String methodsCsv);

    LlmClient model(String model);
    LlmClient temperature(double t);
    LlmClient maxTokens(int n);
    LlmClient timeout(int seconds);
    LlmClient maxIterations(int n);
    LlmClient extraBody(Map<String, Object> body);
    LlmClient windowPolicy(WindowPolicy policy);

    LlmResponse call();
    LlmResponse stream(LlmStreamListener listener);
}
