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

import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;
import org.moqui.llm.LlmMessage;
import org.moqui.llm.LlmToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a message and its tool calls are stored in LlmMessage and read back. The conversation row and its transaction
 * handling stay in LlmConversationImpl, because they read and write that object's own state. These are the parts that
 * only translate between an LlmMessage and an LlmMessage entity row.
 */
final class ConversationStore {
    private ConversationStore() { }

    static void fillMessage(ExecutionContext ec, EntityValue ev, LlmMessage m) {
        ev.set("ordinal", m.ordinal);
        ev.set("role", m.role != null ? m.role.name() : LlmMessage.Role.USER.name());
        ev.set("content", m.content);
        ev.set("name", m.name);
        ev.set("toolCallId", m.toolCallId);
        ev.set("toolCallsJson", m.toolCalls == null || m.toolCalls.isEmpty() ? null : LlmJson.toJson(m.toolCalls));
        ev.set("metadataJson", m.metadata == null || m.metadata.isEmpty() ? null : LlmJson.toJson(m.metadata));
        ev.set("providerJson", m.providerState == null || m.providerState.isEmpty() ? null : LlmJson.toJson(m.providerState));
        ev.set("tokenEstimate", tokenEstimate(m.content));
        ev.set("sentDate", m.sentDate != null ? m.sentDate : LlmConversationImpl.now(ec));
    }

    static LlmMessage fromEntity(EntityValue ev) {
        LlmMessage m = new LlmMessage();
        m.messageId = ev.getString("messageId");
        Long ord = ev.getLong("ordinal");
        m.ordinal = ord != null ? ord.intValue() : 0;
        String role = ev.getString("role");
        try {
            m.role = role != null ? LlmMessage.Role.valueOf(role) : LlmMessage.Role.USER;
        } catch (IllegalArgumentException e) {
            m.role = LlmMessage.Role.USER;
        }
        m.content = ev.getString("content");
        m.name = ev.getString("name");
        m.toolCallId = ev.getString("toolCallId");
        m.toolCalls = parseToolCalls(ev.getString("toolCallsJson"));
        m.metadata = LlmJson.toMap(ev.getString("metadataJson"));
        m.providerState = LlmJson.toMap(ev.getString("providerJson"));
        m.sentDate = ev.getTimestamp("sentDate");
        return m;
    }

    static List<LlmToolCall> parseToolCalls(String json) {
        List<LlmToolCall> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        Object obj = LlmJson.toObject(json);
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) {
            if (item instanceof LlmToolCall) {
                out.add((LlmToolCall) item);
            } else if (item instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) item;
                LlmToolCall tc = new LlmToolCall();
                Object id = map.get("id");
                Object name = map.get("name");
                Object args = map.get("arguments");
                tc.id = id != null ? id.toString() : null;
                tc.name = name != null ? name.toString() : null;
                if (args instanceof Map || args instanceof List) tc.arguments = LlmJson.toJson(args);
                else tc.arguments = args != null ? args.toString() : null;
                Object exec = map.get("execution");
                if (exec != null) {
                    try {
                        tc.execution = org.moqui.llm.LlmTool.Execution.valueOf(exec.toString().toUpperCase());
                    } catch (Exception ignored) { }
                }
                if (Boolean.TRUE.equals(map.get("confirm")) || "true".equals(String.valueOf(map.get("confirm"))))
                    tc.confirm = Boolean.TRUE;
                Object risk = map.get("risk");
                if (risk != null) tc.risk = risk.toString();
                Object fields = map.get("providerFields");
                if (fields instanceof Map) {
                    tc.providerFields = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) fields).entrySet()) {
                        if (e.getKey() != null) tc.providerFields.put(e.getKey().toString(), e.getValue());
                    }
                }
                out.add(tc);
            }
        }
        return out;
    }

    static int tokenEstimate(String content) {
        if (content == null || content.isEmpty()) return 0;
        return Math.max(1, content.length() / 4);
    }
}
