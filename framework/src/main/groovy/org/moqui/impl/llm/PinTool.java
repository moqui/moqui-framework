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
import org.moqui.llm.LlmTool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Remember ids a screen response already returned. Does not load those records.
 */
public class PinTool implements LlmTool {
    static final String NAME = "pin";
    static final String ATTR = "pins";
    static final Set<String> FIELDS = Set.of(
            "partyId", "orderId", "workEffortId", "invoiceId", "shipmentId");
    private static final int MAX_LEN = 80;
    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        for (String field : FIELDS) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", "string");
            p.put("description", "Id already returned by a screen. Empty string clears it.");
            props.put(field, p);
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        SCHEMA = Collections.unmodifiableMap(schema);
    }

    @Override public String getName() { return NAME; }
    @Override public String getDescription() {
        return "Remember ids from a screen response for later turns (partyId, orderId, workEffortId, invoiceId, shipmentId). "
                + "Does not load the record. Pass an empty string to clear one id.";
    }
    @Override public Map<String, Object> getParametersSchema() { return SCHEMA; }
    @Override public Execution getExecution() { return Execution.SERVER; }

    @Override
    public Object execute(Map<String, Object> arguments, ExecutionContext ec) {
        LlmClientImpl client = LlmAgentLoop.currentClient();
        if (client == null || client.conversation == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "no conversation");
            return err;
        }
        Map<String, Object> pins = currentPins(client);
        Map<String, Object> args = arguments != null ? arguments : Collections.emptyMap();
        for (String field : FIELDS) {
            if (!args.containsKey(field) || args.get(field) == null) continue;
            String value = args.get(field).toString().trim();
            if (value.isEmpty()) pins.remove(field);
            else if (value.length() <= MAX_LEN && value.indexOf('\n') < 0 && value.indexOf('\r') < 0)
                pins.put(field, value);
        }
        if (pins.isEmpty()) client.conversation.setAttribute(ATTR, null);
        else client.conversation.setAttribute(ATTR, pins);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pins", new LinkedHashMap<>(pins));
        return out;
    }

    public static String text(LlmClientImpl client) {
        Map<String, Object> pins = currentPins(client);
        if (pins.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Ids remembered from earlier screen responses. They do not load the record.\n");
        for (String field : FIELDS) {
            Object v = pins.get(field);
            if (v != null && !v.toString().isBlank()) sb.append(field).append('=').append(v).append('\n');
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> currentPins(LlmClientImpl client) {
        Map<String, Object> pins = new LinkedHashMap<>();
        if (client == null || client.conversation == null) return pins;
        Object raw = client.conversation.getAttributes().get(ATTR);
        if (!(raw instanceof Map)) return pins;
        Map<String, Object> stored = (Map<String, Object>) raw;
        for (String field : FIELDS) {
            Object v = stored.get(field);
            if (v != null && !v.toString().isBlank()) pins.put(field, v.toString());
        }
        return pins;
    }
}
