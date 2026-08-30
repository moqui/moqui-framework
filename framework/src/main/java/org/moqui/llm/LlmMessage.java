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

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LlmMessage {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL, CONTEXT }

    public String messageId;
    public Role role;
    public String content;
    public String name;
    public String toolCallId;
    public List<LlmToolCall> toolCalls;
    public Map<String, Object> metadata;
    public int ordinal;
    public Timestamp sentDate;

    public LlmMessage() { }
    public LlmMessage(Role role, String content) {
        this.role = role;
        this.content = content;
    }

    public static LlmMessage system(String content) { return new LlmMessage(Role.SYSTEM, content); }
    public static LlmMessage user(String content) { return new LlmMessage(Role.USER, content); }
    public static LlmMessage assistant(String content) { return new LlmMessage(Role.ASSISTANT, content); }
    public static LlmMessage tool(String toolCallId, String name, String content) {
        LlmMessage m = new LlmMessage(Role.TOOL, content);
        m.toolCallId = toolCallId;
        m.name = name;
        return m;
    }
    public static LlmMessage context(String source, String content) {
        LlmMessage m = new LlmMessage(Role.CONTEXT, content);
        m.metadata = new LinkedHashMap<>();
        if (source != null) m.metadata.put("source", source);
        return m;
    }

    public String getMessageId() { return messageId; }
    public Role getRole() { return role; }
    public String getContent() { return content; }
    public String getName() { return name; }
    public String getToolCallId() { return toolCallId; }
    public List<LlmToolCall> getToolCalls() { return toolCalls; }
    public Map<String, Object> getMetadata() { return metadata; }
    public int getOrdinal() { return ordinal; }
    public Timestamp getSentDate() { return sentDate; }

    public LlmMessage copy() {
        LlmMessage c = new LlmMessage();
        c.messageId = messageId;
        c.role = role;
        c.content = content;
        c.name = name;
        c.toolCallId = toolCallId;
        if (toolCalls != null) c.toolCalls = new ArrayList<>(toolCalls);
        if (metadata != null) c.metadata = new LinkedHashMap<>(metadata);
        c.ordinal = ordinal;
        c.sentDate = sentDate;
        return c;
    }
}
