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

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;
import org.moqui.llm.LlmTool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Client-passthrough form tool. Server may sanitize and prefill; it never submits.
 */
public class WriteUiTool implements LlmTool {
    static final String NAME = "write_ui";
    static final int SCHEMA_VERSION = 1;
    static final Set<String> WIDGETS = new LinkedHashSet<>(Arrays.asList(
            "text-line", "text-area", "drop-down", "date-time", "check", "radio",
            "display", "display-entity", "hidden"));
    static final Set<String> DATE_TYPES = new LinkedHashSet<>(Arrays.asList(
            "timestamp", "date-time", "date", "time"));
    static final Set<String> HIDDEN_FORBIDDEN = new LinkedHashSet<>(Arrays.asList(
            "password", "currentpassword"));
    static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> widget = new LinkedHashMap<>();
        widget.put("type", "string");
        widget.put("enum", new ArrayList<>(WIDGETS));
        Map<String, Object> widgetType = new LinkedHashMap<>();
        widgetType.put("type", "string");
        widgetType.put("enum", new ArrayList<>(DATE_TYPES));
        widgetType.put("description", "For date-time widget only; xml-form-3.xsd date-time/@type");
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("type", "object");
        Map<String, Object> optionProps = new LinkedHashMap<>();
        optionProps.put("key", mapOf("type", "string"));
        optionProps.put("text", mapOf("type", "string"));
        option.put("properties", optionProps);
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "object");
        field.put("required", Arrays.asList("name", "widget"));
        Map<String, Object> fieldProps = new LinkedHashMap<>();
        fieldProps.put("name", mapOf("type", "string"));
        fieldProps.put("label", mapOf("type", "string"));
        fieldProps.put("help", mapOf("type", "string"));
        fieldProps.put("widget", widget);
        fieldProps.put("widgetType", widgetType);
        fieldProps.put("required", mapOf("type", "boolean"));
        fieldProps.put("defaultValue", new LinkedHashMap<>());
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("type", "array");
        options.put("items", option);
        fieldProps.put("options", options);
        fieldProps.put("entityName", mapOf("type", "string"));
        fieldProps.put("entityField", mapOf("type", "string"));
        fieldProps.put("entityFindOptions", mapOf("type", "object"));
        field.put("properties", fieldProps);
        Map<String, Object> prefill = new LinkedHashMap<>();
        prefill.put("type", "object");
        Map<String, Object> prefillProps = new LinkedHashMap<>();
        prefillProps.put("entityName", mapOf("type", "string"));
        prefillProps.put("pk", mapOf("type", "object"));
        prefillProps.put("fromConversation", mapOf("type", "boolean"));
        prefill.put("properties", prefillProps);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "array");
        fields.put("items", field);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", mapOf("type", "string"));
        props.put("instruction", mapOf("type", "string"));
        props.put("submitLabel", mapOf("type", "string"));
        props.put("cancelLabel", mapOf("type", "string"));
        props.put("formId", mapOf("type", "string"));
        props.put("prefill", prefill);
        props.put("fields", fields);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", Collections.singletonList("fields"));
        schema.put("properties", props);
        SCHEMA = Collections.unmodifiableMap(schema);
    }

    private final Set<String> allowedEntities = new LinkedHashSet<>();

    public WriteUiTool() { }

    public WriteUiTool addAllowedEntity(String entityName) {
        if (entityName != null && !entityName.isBlank()) allowedEntities.add(entityName);
        return this;
    }

    @Override public String getName() { return NAME; }
    @Override public String getDescription() {
        return "Present a structured form to the user using xml-form widgets. Never emit HTML. "
                + "Wait for the user to submit; values in the tool result are the only source of truth. "
                + "The server never submits this form.";
    }
    @Override public Map<String, Object> getParametersSchema() { return SCHEMA; }
    @Override public Execution getExecution() { return Execution.CLIENT; }

    @Override
    public Object execute(Map<String, Object> arguments, ExecutionContext ec) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "write_ui is a client tool; the server never submits");
        return err;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> enrichForClient(Map<String, Object> arguments, ExecutionContext ec) {
        Map<String, Object> out = arguments != null ? deepCopy(arguments) : new LinkedHashMap<>();
        out.put("schemaVersion", SCHEMA_VERSION);
        sanitizeString(out, "title");
        sanitizeString(out, "instruction");
        sanitizeString(out, "submitLabel");
        sanitizeString(out, "cancelLabel");
        sanitizeString(out, "formId");

        Object fieldsObj = out.get("fields");
        List<Map<String, Object>> kept = new ArrayList<>();
        if (fieldsObj instanceof List) {
            for (Object item : (List<?>) fieldsObj) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> field = deepCopy((Map<String, Object>) item);
                String name = str(field.get("name"));
                String widget = str(field.get("widget"));
                if (name == null || name.isBlank() || widget == null) continue;
                widget = widget.trim();
                if ("date".equals(widget)) {
                    widget = "date-time";
                    if (field.get("widgetType") == null) field.put("widgetType", "date");
                }
                if ("html".equalsIgnoreCase(widget) || !WIDGETS.contains(widget)) continue;
                field.put("widget", widget);
                if ("hidden".equals(widget) && HIDDEN_FORBIDDEN.contains(name.toLowerCase(Locale.ROOT))) continue;
                String wt = str(field.get("widgetType"));
                if (wt != null && !DATE_TYPES.contains(wt)) field.remove("widgetType");
                sanitizeString(field, "label");
                sanitizeString(field, "help");
                if (field.get("defaultValue") instanceof String)
                    field.put("defaultValue", clean((String) field.get("defaultValue")));
                Object options = field.get("options");
                if (options instanceof List) {
                    List<Map<String, Object>> cleanOpts = new ArrayList<>();
                    for (Object opt : (List<?>) options) {
                        if (!(opt instanceof Map)) continue;
                        Map<String, Object> om = deepCopy((Map<String, Object>) opt);
                        sanitizeString(om, "text");
                        sanitizeString(om, "key");
                        cleanOpts.add(om);
                    }
                    field.put("options", cleanOpts);
                }
                kept.add(field);
            }
        }
        out.put("fields", kept);

        Object prefillObj = out.get("prefill");
        if (prefillObj instanceof Map) {
            Map<String, Object> prefill = (Map<String, Object>) prefillObj;
            String entityName = str(prefill.get("entityName"));
            Object pkObj = prefill.get("pk");
            if (entityName != null && !entityName.isBlank() && pkObj instanceof Map) {
                if (!allowedEntities.contains(entityName)) {
                    out.remove("prefill");
                    out.put("prefillError", "entity not allowed");
                } else {
                    applyPrefill(out, kept, entityName, (Map<String, Object>) pkObj, ec);
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void applyPrefill(Map<String, Object> out, List<Map<String, Object>> fields,
            String entityName, Map<String, Object> pk, ExecutionContext ec) {
        if (ec == null || ec.getEntity() == null) {
            out.remove("prefill");
            out.put("prefillError", "no ExecutionContext for prefill");
            return;
        }
        try {
            EntityValue ev = ec.getEntity().find(entityName).condition(pk).one();
            if (ev == null) {
                out.remove("prefill");
                out.put("prefillError", "not found or not authorized");
                return;
            }
            Map<String, Object> row = ev.getMap();
            for (Map<String, Object> field : fields) {
                String name = str(field.get("name"));
                if (name == null || !row.containsKey(name)) continue;
                Object cur = field.get("defaultValue");
                if (cur == null || (cur instanceof CharSequence && cur.toString().isEmpty())) {
                    Object val = row.get(name);
                    if (val instanceof String) val = clean((String) val);
                    field.put("defaultValue", val);
                }
            }
        } catch (Throwable t) {
            out.remove("prefill");
            out.put("prefillError", "not found or not authorized");
        }
    }

    static String clean(String s) {
        if (s == null) return null;
        return Jsoup.clean(s, Safelist.none());
    }
    static void sanitizeString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof String) map.put(key, clean((String) v));
    }
    static String str(Object o) { return o == null ? null : o.toString(); }
    static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> deepCopy(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in == null) return out;
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map) out.put(e.getKey(), deepCopy((Map<String, Object>) v));
            else if (v instanceof List) {
                List<Object> copy = new ArrayList<>();
                for (Object item : (List<?>) v) {
                    if (item instanceof Map) copy.add(deepCopy((Map<String, Object>) item));
                    else copy.add(item);
                }
                out.put(e.getKey(), copy);
            } else out.put(e.getKey(), v);
        }
        return out;
    }
}
