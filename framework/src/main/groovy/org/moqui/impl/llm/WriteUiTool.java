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
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;
import org.moqui.llm.LlmConversation;
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
    /** 2: form spec plus list/actions/writeThrough. kind=form in this pass. */
    static final int SCHEMA_VERSION = 2;
    static final String KIND_FORM = "form";
    // FUTURE: KIND_SCREEN_XML = "screen-xml" (server round-trip compile/render of generated XML screen)
    // FUTURE: KIND_VUE_SFC = "vue-sfc" (client http-vue-loader.parse of script+template+style)
    static final Set<String> WIDGETS = new LinkedHashSet<>(Arrays.asList(
            "text-line", "text-area", "drop-down", "date-time", "check", "radio",
            "display", "display-entity", "hidden"));
    static final Set<String> DATE_TYPES = new LinkedHashSet<>(Arrays.asList(
            "timestamp", "date-time", "date", "time"));
    static final Set<String> HIDDEN_FORBIDDEN = new LinkedHashSet<>(Arrays.asList(
            "password", "currentpassword", "newpassword", "passwordverify", "confirmpassword"));
    static final Set<String> FIELD_KEYS = new LinkedHashSet<>(Arrays.asList(
            "name", "label", "help", "widget", "widgetType", "required", "defaultValue",
            "options", "entityName", "entityField", "entityFindOptions"));
    static final Set<String> OPTION_KEYS = new LinkedHashSet<>(Arrays.asList("key", "text"));
    static final Set<String> TOP_KEYS = new LinkedHashSet<>(Arrays.asList(
            "title", "instruction", "submitLabel", "cancelLabel", "formId", "prefill", "fields",
            "schemaVersion", "prefillError", "kind", "writeThrough", "columns", "rows", "actions",
            "removeFields", "removeActions"));
    static final Set<String> COLUMN_KEYS = new LinkedHashSet<>(Arrays.asList("name", "label", "widget"));
    static final Set<String> ACTION_KEYS = new LinkedHashSet<>(Arrays.asList(
            "id", "label", "method", "path", "primary", "bodyFromFields", "queryFromFields",
            "bind", "dependsOn"));
    static final String ATTR_LAST_WRITE_UI = "lastWriteUi";
    static final Set<String> PREFILL_KEYS = new LinkedHashSet<>(Arrays.asList(
            "entityName", "pk", "fromConversation"));
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
        props.put("kind", mapOf("type", "string"));
        props.put("writeThrough", mapOf("type", "boolean"));
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("type", "object");
        Map<String, Object> colProps = new LinkedHashMap<>();
        colProps.put("name", mapOf("type", "string"));
        colProps.put("label", mapOf("type", "string"));
        colProps.put("widget", widget);
        col.put("properties", colProps);
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("type", "array");
        columns.put("items", col);
        props.put("columns", columns);
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("type", "array");
        rows.put("items", mapOf("type", "object"));
        props.put("rows", rows);
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "object");
        Map<String, Object> actionProps = new LinkedHashMap<>();
        actionProps.put("id", mapOf("type", "string"));
        actionProps.put("label", mapOf("type", "string"));
        actionProps.put("method", mapOf("type", "string"));
        actionProps.put("path", mapOf("type", "string"));
        actionProps.put("primary", mapOf("type", "boolean"));
        Map<String, Object> strArr = new LinkedHashMap<>();
        strArr.put("type", "array");
        strArr.put("items", mapOf("type", "string"));
        actionProps.put("bodyFromFields", strArr);
        actionProps.put("queryFromFields", strArr);
        actionProps.put("bind", strArr);
        actionProps.put("dependsOn", strArr);
        action.put("properties", actionProps);
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("type", "array");
        actions.put("items", action);
        props.put("actions", actions);
        props.put("removeFields", strArr);
        props.put("removeActions", strArr);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        SCHEMA = Collections.unmodifiableMap(schema);
    }

    private final Set<String> allowedEntities = new LinkedHashSet<>();
    private boolean allowAnyAuthorizedEntity = false;

    public WriteUiTool() { }

    public WriteUiTool addAllowedEntity(String entityName) {
        if (entityName != null && !entityName.isBlank()) allowedEntities.add(entityName);
        return this;
    }
    public WriteUiTool setAllowAnyAuthorizedEntity(boolean allow) {
        this.allowAnyAuthorizedEntity = allow;
        return this;
    }

    @Override public String getName() { return NAME; }
    @Override public String getDescription() {
        return "Present a structured form (kind=form) using xml-form widgets. Never emit HTML, Vue, or JS. "
                + "Wait for the user to submit; values in the tool result are the only source of truth. "
                + "The server never submits this form. "
                + "Set writeThrough=true to edit the current canvas: omitted fields/actions are kept; "
                + "use removeFields/removeActions to drop them. The resume tool result includes canvas "
                + "(current schema with user values) so you can correct errors or add/remove sections. "
                + "Always declare actions[] (method+path) so Script mode can run them.";
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
                if (field.get("defaultValue") instanceof CharSequence)
                    field.put("defaultValue", clean(field.get("defaultValue").toString()));
                Object options = field.get("options");
                if (options instanceof List) {
                    List<Map<String, Object>> cleanOpts = new ArrayList<>();
                    for (Object opt : (List<?>) options) {
                        if (!(opt instanceof Map)) continue;
                        Map<String, Object> om = deepCopy((Map<String, Object>) opt);
                        sanitizeString(om, "text");
                        sanitizeString(om, "key");
                        cleanOpts.add(keepKeys(om, OPTION_KEYS));
                    }
                    field.put("options", cleanOpts);
                }
                kept.add(keepKeys(field, FIELD_KEYS));
            }
        }
        out.put("fields", kept);

        String kind = str(out.get("kind"));
        if (kind == null || kind.isBlank()) kind = KIND_FORM;
        // First pass: only kind=form. FUTURE: screen-xml (server round-trip), vue-sfc (script/template/style).
        if (!KIND_FORM.equals(kind)) kind = KIND_FORM;
        out.put("kind", kind);
        if (!(out.get("writeThrough") instanceof Boolean)) out.put("writeThrough", Boolean.FALSE);

        out.put("columns", cleanColumns(out.get("columns")));
        out.put("rows", cleanRows(out.get("rows")));
        out.put("actions", cleanActions(out.get("actions")));
        out.put("removeFields", cleanStringList(out.get("removeFields")));
        out.put("removeActions", cleanStringList(out.get("removeActions")));

        Object prefillObj = out.get("prefill");
        if (prefillObj instanceof Map) {
            Map<String, Object> prefill = keepKeys(deepCopy((Map<String, Object>) prefillObj), PREFILL_KEYS);
            String entityName = str(prefill.get("entityName"));
            Object pkObj = prefill.get("pk");
            if (entityName != null && !entityName.isBlank() && pkObj instanceof Map) {
                if (!allowAnyAuthorizedEntity && !allowedEntities.contains(entityName)) {
                    out.remove("prefill");
                    out.put("prefillError", "entity not allowed");
                } else {
                    out.put("prefill", prefill);
                    applyPrefill(out, kept, entityName, (Map<String, Object>) pkObj, ec);
                }
            } else {
                out.put("prefill", prefill);
            }
        }
        return keepKeys(out, TOP_KEYS);
    }

    /**
     * Merge a new write_ui payload onto the conversation's last canvas when writeThrough is true.
     * Persists lastWriteUi on the conversation attributes.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> applyWriteThrough(Map<String, Object> incoming, LlmConversation conversation) {
        Map<String, Object> next = incoming != null ? incoming : new LinkedHashMap<>();
        Map<String, Object> last = null;
        if (conversation != null && conversation.getAttributes() != null) {
            Object raw = conversation.getAttributes().get(ATTR_LAST_WRITE_UI);
            if (raw instanceof Map) last = (Map<String, Object>) raw;
        }
        boolean writeThrough = Boolean.TRUE.equals(next.get("writeThrough")) && last != null;
        Map<String, Object> merged = writeThrough ? mergeCanvas(last, next) : next;
        merged.put("schemaVersion", SCHEMA_VERSION);
        if (conversation != null) conversation.setAttribute(ATTR_LAST_WRITE_UI, deepCopy(merged));
        return merged;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mergeCanvas(Map<String, Object> last, Map<String, Object> incoming) {
        Map<String, Object> out = deepCopy(last);
        for (String key : Arrays.asList("title", "instruction", "submitLabel", "cancelLabel", "formId", "kind")) {
            if (incoming.containsKey(key) && incoming.get(key) != null) out.put(key, incoming.get(key));
        }
        List<Map<String, Object>> fields = mergeByName(asMapList(out.get("fields")), asMapList(incoming.get("fields")),
                asStringList(incoming.get("removeFields")));
        out.put("fields", fields);
        if (incoming.containsKey("columns")) out.put("columns", incoming.get("columns"));
        if (incoming.containsKey("rows")) out.put("rows", incoming.get("rows"));
        List<Map<String, Object>> actions = mergeByName(asMapList(out.get("actions")), asMapList(incoming.get("actions")),
                asStringList(incoming.get("removeActions")));
        out.put("actions", actions);
        out.remove("removeFields");
        out.remove("removeActions");
        out.put("writeThrough", Boolean.TRUE);
        return keepKeys(out, TOP_KEYS);
    }

    static List<Map<String, Object>> mergeByName(List<Map<String, Object>> base, List<Map<String, Object>> overlay,
            List<String> remove) {
        LinkedHashMap<String, Map<String, Object>> byName = new LinkedHashMap<>();
        for (Map<String, Object> m : base) {
            String n = str(m.get("name"));
            if (n == null) n = str(m.get("id"));
            if (n != null) byName.put(n, m);
        }
        if (remove != null) for (String r : remove) byName.remove(r);
        for (Map<String, Object> m : overlay) {
            String n = str(m.get("name"));
            if (n == null) n = str(m.get("id"));
            if (n == null) continue;
            byName.put(n, m);
        }
        return new ArrayList<>(byName.values());
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cleanColumns(Object obj) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> col = keepKeys(deepCopy((Map<String, Object>) item), COLUMN_KEYS);
            sanitizeString(col, "name");
            sanitizeString(col, "label");
            String widget = str(col.get("widget"));
            if (widget != null && !WIDGETS.contains(widget)) col.put("widget", "display");
            if (str(col.get("name")) != null) out.add(col);
        }
        return out;
    }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cleanRows(Object obj) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> row = deepCopy((Map<String, Object>) item);
            for (Map.Entry<String, Object> e : new ArrayList<>(row.entrySet())) {
                if (e.getValue() instanceof CharSequence)
                    row.put(e.getKey(), clean(e.getValue().toString()));
            }
            out.add(row);
        }
        return out;
    }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cleanActions(Object obj) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> act = keepKeys(deepCopy((Map<String, Object>) item), ACTION_KEYS);
            sanitizeString(act, "id");
            sanitizeString(act, "label");
            sanitizeString(act, "method");
            sanitizeString(act, "path");
            String path = str(act.get("path"));
            if (path != null && RequestTool.validatePath(path) != null) continue;
            String method = str(act.get("method"));
            if (method != null) act.put("method", method.trim().toUpperCase(Locale.ROOT));
            act.put("bodyFromFields", cleanStringList(act.get("bodyFromFields")));
            act.put("queryFromFields", cleanStringList(act.get("queryFromFields")));
            act.put("bind", cleanStringList(act.get("bind")));
            act.put("dependsOn", cleanStringList(act.get("dependsOn")));
            if (str(act.get("id")) != null || path != null) out.add(act);
        }
        return out;
    }
    static List<String> cleanStringList(Object obj) {
        List<String> out = new ArrayList<>();
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) {
            if (item == null) continue;
            String s = clean(item.toString());
            if (s != null && !s.isBlank()) out.add(s);
        }
        return out;
    }
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> asMapList(Object obj) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) {
            if (item instanceof Map) out.add((Map<String, Object>) item);
        }
        return out;
    }
    static List<String> asStringList(Object obj) {
        List<String> out = new ArrayList<>();
        if (!(obj instanceof List)) return out;
        for (Object item : (List<?>) obj) if (item != null) out.add(item.toString());
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
        // Strip tags, then unescape so Vue text interpolation does not show &amp;
        return Parser.unescapeEntities(Jsoup.clean(s, Safelist.none()), false);
    }
    static void sanitizeString(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof CharSequence) map.put(key, clean(v.toString()));
    }
    static Map<String, Object> keepKeys(Map<String, Object> in, Set<String> keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (in == null) return out;
        for (String k : keys) {
            if (in.containsKey(k)) out.put(k, in.get(k));
        }
        return out;
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
