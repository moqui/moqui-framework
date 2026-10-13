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

import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmToolCall;
import org.moqui.llm.WindowPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chooses which stored messages go into one provider call. The system message first, context messages, then as much of
 * the rest as the policy allows, newest kept. The latest user message and everything after it, the turn in progress, is
 * never trimmed, and a tool result is never left without the assistant message that asked for it.
 * Pure: it reads the message list and the policy and changes nothing.
 */
final class WindowBuilder {
    private static final Logger logger = LoggerFactory.getLogger(WindowBuilder.class);

    private WindowBuilder() { }

    static List<LlmMessage> build(List<LlmMessage> messages, WindowPolicy p, String conversationId) {
        WindowPolicy policy = p != null ? p : new WindowPolicy();
        LlmMessage system = null;
        List<LlmMessage> context = new ArrayList<>();
        List<LlmMessage> rest = new ArrayList<>();
        for (LlmMessage m : messages) {
            if (m == null || m.role == null) continue;
            if (m.role == LlmMessage.Role.SYSTEM) {
                if (system == null) system = m;
            } else if (m.role == LlmMessage.Role.CONTEXT) {
                if (policy.includeContext) context.add(m);
            } else {
                rest.add(m);
            }
        }
        List<LlmMessage> fullRest = rest;
        rest = trimRest(rest, policy, charsOf(system) + charsOfAll(context));
        int droppedCount = fullRest.size() - rest.size();
        List<LlmMessage> window = new ArrayList<>();
        if (policy.keepSystemFirst && system != null) window.add(system);
        window.addAll(context);
        if (droppedCount > 0) window.add(omissionNote(fullRest.subList(0, droppedCount), conversationId));
        window.addAll(rest);
        return window;
    }

    /**
     * Describes a prefix trimRest left out of the prompt. Not stored. Does not quote a message body.
     * Included even when includeContext is false, because it describes omitted history.
     */
    private static LlmMessage omissionNote(List<LlmMessage> dropped, String conversationId) {
        int user = 0;
        int assistant = 0;
        int tool = 0;
        int other = 0;
        LlmMessage oldestUser = null;
        for (LlmMessage m : dropped) {
            if (m.role == LlmMessage.Role.USER) {
                user++;
                if (oldestUser == null) oldestUser = m;
            } else if (m.role == LlmMessage.Role.ASSISTANT) {
                assistant++;
            } else if (m.role == LlmMessage.Role.TOOL) {
                tool++;
            } else {
                other++;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Earlier messages omitted from this prompt: ").append(dropped.size());
        sb.append(" (").append(roleCounts(user, assistant, tool, other)).append(").");
        if (oldestUser != null)
            sb.append("\nOldest user message: ordinal ").append(oldestUser.ordinal).append(".");
        logger.warn("LLM window omitted {} messages for conversation {} (user {}, assistant {}, tool {})",
                dropped.size(), conversationId, user, assistant, tool);
        return LlmMessage.context("omitted", sb.toString());
    }

    private static List<LlmMessage> trimRest(List<LlmMessage> rest, WindowPolicy policy, int reservedChars) {
        if (rest.isEmpty()) return rest;
        List<LlmMessage> kept = new ArrayList<>(rest);
        // The latest user message and everything after it is the turn in progress. It is never trimmed,
        // or the model would be asked to continue a tool chain that has lost its question.
        int pinFrom = -1;
        for (int i = rest.size() - 1; i >= 0; i--) {
            if (rest.get(i).role == LlmMessage.Role.USER) { pinFrom = i; break; }
        }
        int dropped = 0;
        while (!kept.isEmpty()) {
            boolean overMessages = policy.maxMessages > 0 && kept.size() > policy.maxMessages;
            boolean overChars = policy.maxChars > 0 && (reservedChars + charsOfAll(kept)) > policy.maxChars;
            if (!overMessages && !overChars) break;
            int drop = 1;
            if (policy.keepToolPairs) drop = pairLength(kept, 0);
            if (drop < 1) drop = 1;
            if (drop > kept.size()) drop = kept.size();
            if (pinFrom >= 0 && dropped + drop > pinFrom) drop = pinFrom - dropped;
            if (drop <= 0) break;
            kept.subList(0, drop).clear();
            dropped += drop;
        }
        // A tool result whose assistant message was trimmed away is rejected by providers
        while (dropped > 0 && !kept.isEmpty() && kept.get(0).role == LlmMessage.Role.TOOL && (pinFrom < 0 || dropped < pinFrom)) {
            kept.remove(0);
            dropped++;
        }
        return kept;
    }

    private static int pairLength(List<LlmMessage> msgs, int i) {
        LlmMessage first = msgs.get(i);
        if (first.role == LlmMessage.Role.ASSISTANT && first.toolCalls != null && !first.toolCalls.isEmpty()) {
            Set<String> ids = new LinkedHashSet<>();
            for (LlmToolCall tc : first.toolCalls) if (tc != null && tc.id != null) ids.add(tc.id);
            int n = 1;
            for (int j = i + 1; j < msgs.size(); j++) {
                LlmMessage t = msgs.get(j);
                if (t.role == LlmMessage.Role.TOOL && (t.toolCallId == null || ids.contains(t.toolCallId))) n++;
                else break;
            }
            return n;
        }
        return 1;
    }

    static int charsOf(LlmMessage m) {
        if (m == null || m.content == null) return 0;
        return m.content.length();
    }
    static int charsOfAll(List<LlmMessage> list) {
        int n = 0;
        for (LlmMessage m : list) n += charsOf(m);
        return n;
    }
    private static String roleCounts(int user, int assistant, int tool, int other) {
        StringBuilder sb = new StringBuilder();
        addCount(sb, "user", user);
        addCount(sb, "assistant", assistant);
        addCount(sb, "tool", tool);
        addCount(sb, "other", other);
        return sb.toString();
    }

    private static void addCount(StringBuilder sb, String label, int n) {
        if (n <= 0) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(label).append(' ').append(n);
    }
}
