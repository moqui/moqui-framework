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
package org.moqui.impl.llm;

import org.moqui.llm.LlmFinishReason;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmProtocol.ProtocolRequest;
import org.moqui.llm.LlmProtocol.ProtocolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * One-line conversation description. The truncated first user message is stored with that message.
 * When the profile asks for a model summary, one tool-free completion runs after the first turn is
 * committed and before the turn's terminal SSE events. A failure keeps the truncation.
 */
final class ConversationSummary {
    private static final Logger logger = LoggerFactory.getLogger(ConversationSummary.class);
    static final int SUMMARY_MAX = 100;
    static final int SEARCH_MAX = 4000;
    static final String PROMPT =
            "Reply with a single line of at most 100 characters describing this request. No quotes.";

    private ConversationSummary() { }

    static String truncate(String text) {
        if (text == null) return "";
        String flat = text.trim().replaceAll("\\s+", " ");
        if (flat.length() <= SUMMARY_MAX) return flat;
        return flat.substring(0, SUMMARY_MAX - 3) + "...";
    }

    static String cap(String text, int max) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.length() <= max) return trimmed;
        return trimmed.substring(0, max);
    }

    /** Call after the user message is on the conversation and summary is still empty. Does not commit by itself. */
    static void noteFirstUser(LlmConversationImpl conv, String content) {
        if (conv == null || content == null || content.isBlank()) return;
        if (conv.getSummary() != null && !conv.getSummary().isBlank()) return;
        conv.assignSummary(truncate(content), cap(content, SEARCH_MAX), false);
    }

    /**
     * One attempt per conversation. Runs only when the profile flag is on and no attempt has been recorded.
     * Swallows provider errors so the user turn still completes.
     */
    static void maybe(LlmClientImpl client) {
        if (client == null || client.profile == null || !client.profile.summarizeConversation) return;
        LlmConversationImpl conv = client.conversation;
        if (conv == null || conv.isSummaryFromLlm()) return;
        String first = conv.firstUserContent();
        if (first == null || first.isBlank()) return;
        ProtocolResult result = null;
        boolean failed = false;
        try {
            List<LlmMessage> window = new ArrayList<>();
            window.add(LlmMessage.system(PROMPT));
            window.add(LlmMessage.user(first));
            ProtocolRequest req = client.buildRequest(client.resolveModel(), window);
            req.tools = null;
            req.stream = false;
            req.maxTokens = 80;
            req.temperature = 0.0d;
            req.timeoutSeconds = 12;
            req.timeoutRetry = false;
            result = client.profile.protocol.chat(req);
        } catch (Throwable t) {
            failed = true;
            logger.warn("Conversation summary failed for " + conv.getConversationId() + ": " + t.getMessage());
        }
        String line = failed ? null : firstLine(result);
        ProtocolResult logResult = result;
        boolean wasError = failed || logResult == null
                || (logResult.finishReason != null && logResult.finishReason != LlmFinishReason.STOP
                && logResult.finishReason != LlmFinishReason.LENGTH);
        try {
            conv.persistIsolated(() -> {
                if (line != null && !line.isBlank()) {
                    conv.assignSummary(truncate(line), truncate(line) + "\n" + cap(first, SEARCH_MAX), true);
                } else {
                    conv.markSummaryAttempted();
                }
                conv.updateHeader();
                conv.writeCallLog(client.profile.name,
                        client.profile.protocol != null ? client.profile.protocol.getName() : null,
                        logResult != null && logResult.model != null ? logResult.model : client.resolveModel(),
                        client.profile.logContent, null, logResult, 0L, 0, wasError || line == null || line.isBlank());
            });
        } catch (Throwable t) {
            logger.warn("Could not store conversation summary for " + conv.getConversationId() + ": " + t.getMessage());
        }
    }

    private static String firstLine(ProtocolResult result) {
        if (result == null || result.content == null) return null;
        String text = result.content.trim();
        if (text.isEmpty()) return null;
        int nl = text.indexOf('\n');
        if (nl >= 0) text = text.substring(0, nl).trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2)
            text = text.substring(1, text.length() - 1).trim();
        return text;
    }
}
