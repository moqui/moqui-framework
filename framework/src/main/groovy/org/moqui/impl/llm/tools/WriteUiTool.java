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
package org.moqui.impl.llm.tools;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;
import org.moqui.context.ExecutionContext;
import org.moqui.entity.EntityValue;
import org.moqui.impl.llm.LlmConversationImpl;
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
import java.util.regex.Pattern;

/**
 * Client-passthrough UI tool (form spec or Vue SFC). Server may sanitize and prefill; it never submits.
 */
public class WriteUiTool implements LlmTool {
    public static final String NAME = "write_ui";
    /** 4: kind=openui | vue-sfc | form. openui is OpenUI Lang; vue-sfc is escape-hatch SFC; form is legacy widgets. */
    public static final int SCHEMA_VERSION = 4;
    static final String KIND_FORM = "form";
    static final String KIND_VUE_SFC = "vue-sfc";
    static final String KIND_OPENUI = "openui";
    // FUTURE: KIND_SCREEN_XML = "screen-xml" (server round-trip compile/render of generated XML screen)
    static final int MAX_SFC_CHARS = 64 * 1024;
    static final int MAX_LANG_CHARS = 64 * 1024;
    static final Pattern TEMPLATE_TAG = Pattern.compile("(?is)<template\\b");
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
            "removeFields", "removeActions", "sfc", "template", "script", "style", "sfcError",
            "lang", "langError"));
    static final Set<String> SFC_KEYS = new LinkedHashSet<>(Arrays.asList("sfc", "template", "script", "style"));
    static final Set<String> COLUMN_KEYS = new LinkedHashSet<>(Arrays.asList("name", "label", "widget"));
    static final Set<String> ACTION_KEYS = new LinkedHashSet<>(Arrays.asList(
            "id", "label", "method", "path", "primary", "bodyFromFields", "queryFromFields",
            "bind", "dependsOn"));
    public static final String ATTR_LAST_WRITE_UI = "lastWriteUi";
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
        optionProps.put("key", CanvasSanitizer.mapOf("type", "string"));
        optionProps.put("text", CanvasSanitizer.mapOf("type", "string"));
        option.put("properties", optionProps);
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("type", "object");
        field.put("required", Arrays.asList("name", "widget"));
        Map<String, Object> fieldProps = new LinkedHashMap<>();
        fieldProps.put("name", CanvasSanitizer.mapOf("type", "string"));
        fieldProps.put("label", CanvasSanitizer.mapOf("type", "string"));
        fieldProps.put("help", CanvasSanitizer.mapOf("type", "string"));
        fieldProps.put("widget", widget);
        fieldProps.put("widgetType", widgetType);
        fieldProps.put("required", CanvasSanitizer.mapOf("type", "boolean"));
        fieldProps.put("defaultValue", new LinkedHashMap<>());
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("type", "array");
        options.put("items", option);
        fieldProps.put("options", options);
        fieldProps.put("entityName", CanvasSanitizer.mapOf("type", "string"));
        fieldProps.put("entityField", CanvasSanitizer.mapOf("type", "string"));
        fieldProps.put("entityFindOptions", CanvasSanitizer.mapOf("type", "object"));
        field.put("properties", fieldProps);
        Map<String, Object> prefill = new LinkedHashMap<>();
        prefill.put("type", "object");
        Map<String, Object> prefillProps = new LinkedHashMap<>();
        prefillProps.put("entityName", CanvasSanitizer.mapOf("type", "string"));
        prefillProps.put("pk", CanvasSanitizer.mapOf("type", "object"));
        prefillProps.put("fromConversation", CanvasSanitizer.mapOf("type", "boolean"));
        prefill.put("properties", prefillProps);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("type", "array");
        fields.put("items", field);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", CanvasSanitizer.mapOf("type", "string"));
        Map<String, Object> instructionProp = CanvasSanitizer.mapOf("type", "string");
        instructionProp.put("description", "One short sentence for the person, or omit. Not the lang program.");
        props.put("instruction", instructionProp);
        props.put("submitLabel", CanvasSanitizer.mapOf("type", "string"));
        props.put("cancelLabel", CanvasSanitizer.mapOf("type", "string"));
        props.put("formId", CanvasSanitizer.mapOf("type", "string"));
        props.put("prefill", prefill);
        props.put("fields", fields);
        Map<String, Object> kindSchema = new LinkedHashMap<>();
        kindSchema.put("type", "string");
        kindSchema.put("enum", Arrays.asList(KIND_OPENUI, KIND_VUE_SFC, KIND_FORM));
        kindSchema.put("description", "openui: OpenUI Lang in lang (default when lang is present). "
                + "vue-sfc: Vue 2 SFC escape hatch. form: legacy xml-form widgets.");
        props.put("kind", kindSchema);
        Map<String, Object> langProp = CanvasSanitizer.mapOf("type", "string");
        langProp.put("description", "OpenUI Lang program (kind=openui). One statement per line. root is a Stack of the screen.");
        props.put("lang", langProp);
        Map<String, Object> sfcProp = CanvasSanitizer.mapOf("type", "string");
        sfcProp.put("description", "Full Vue 2 SFC. Wins over template/script/style. module.exports, not export default.");
        props.put("sfc", sfcProp);
        Map<String, Object> templateProp = CanvasSanitizer.mapOf("type", "string");
        templateProp.put("description", "Vue template inner HTML, or a full <template> block");
        props.put("template", templateProp);
        Map<String, Object> scriptProp = CanvasSanitizer.mapOf("type", "string");
        scriptProp.put("description", "Vue 2 script body assigning module.exports, or a full <script> block");
        props.put("script", scriptProp);
        props.put("style", CanvasSanitizer.mapOf("type", "string"));
        props.put("writeThrough", CanvasSanitizer.mapOf("type", "boolean"));
        Map<String, Object> col = new LinkedHashMap<>();
        col.put("type", "object");
        Map<String, Object> colProps = new LinkedHashMap<>();
        colProps.put("name", CanvasSanitizer.mapOf("type", "string"));
        colProps.put("label", CanvasSanitizer.mapOf("type", "string"));
        colProps.put("widget", widget);
        col.put("properties", colProps);
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("type", "array");
        columns.put("items", col);
        props.put("columns", columns);
        Map<String, Object> rows = new LinkedHashMap<>();
        rows.put("type", "array");
        rows.put("items", CanvasSanitizer.mapOf("type", "object"));
        props.put("rows", rows);
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "object");
        Map<String, Object> actionProps = new LinkedHashMap<>();
        actionProps.put("id", CanvasSanitizer.mapOf("type", "string"));
        actionProps.put("label", CanvasSanitizer.mapOf("type", "string"));
        actionProps.put("method", CanvasSanitizer.mapOf("type", "string"));
        actionProps.put("path", CanvasSanitizer.mapOf("type", "string"));
        actionProps.put("primary", CanvasSanitizer.mapOf("type", "boolean"));
        Map<String, Object> strArr = new LinkedHashMap<>();
        strArr.put("type", "array");
        strArr.put("items", CanvasSanitizer.mapOf("type", "string"));
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
    private boolean allowVueSfc = false;

    public WriteUiTool() { }

    public WriteUiTool addAllowedEntity(String entityName) {
        if (entityName != null && !entityName.isBlank()) allowedEntities.add(entityName);
        return this;
    }
    public WriteUiTool setAllowAnyAuthorizedEntity(boolean allow) {
        this.allowAnyAuthorizedEntity = allow;
        return this;
    }
    public WriteUiTool setAllowVueSfc(boolean allow) {
        this.allowVueSfc = allow;
        return this;
    }

    @Override public boolean streamsPartialArguments() { return true; }
    @Override public String getName() { return NAME; }
    @Override public String getDescription() {
        return "Present a UI on Assist. Prefer kind=openui with lang (OpenUI Lang; root is a Stack; "
                + "Query/Mutation + Button Action). kind=vue-sfc is an escape hatch when the registered "
                + "library cannot express the layout (Vue 2 SFC, module.exports, Quasar v1). "
                + "kind=form is legacy xml-form widgets. Wait for the user to submit; values in the tool "
                + "result are the only source of truth. The server never submits. Set writeThrough=true to "
                + "edit the current canvas (openui merges statements by name). The resume tool result "
                + "includes canvas (current schema with user values).";
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
        Map<String, Object> out = arguments != null ? CanvasSanitizer.deepCopy(arguments) : new LinkedHashMap<>();
        out.put("schemaVersion", SCHEMA_VERSION);
        CanvasSanitizer.sanitizeString(out, "title");
        CanvasSanitizer.sanitizeString(out, "instruction");
        CanvasSanitizer.sanitizeString(out, "submitLabel");
        CanvasSanitizer.sanitizeString(out, "cancelLabel");
        CanvasSanitizer.sanitizeString(out, "formId");

        Object fieldsObj = out.get("fields");
        List<Map<String, Object>> kept = new ArrayList<>();
        if (fieldsObj instanceof List) {
            for (Object item : (List<?>) fieldsObj) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> field = CanvasSanitizer.deepCopy((Map<String, Object>) item);
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
                CanvasSanitizer.sanitizeString(field, "label");
                CanvasSanitizer.sanitizeString(field, "help");
                Object dv = field.get("defaultValue");
                if (dv instanceof Map || dv instanceof List) field.remove("defaultValue");
                else if (dv instanceof CharSequence)
                    field.put("defaultValue", CanvasSanitizer.clean(dv.toString()));
                else if (dv != null && !(dv instanceof Number) && !(dv instanceof Boolean))
                    field.put("defaultValue", CanvasSanitizer.clean(dv.toString()));
                if ("date-time".equals(widget) && field.containsKey("defaultValue")) {
                    Object normalized = DateDefaults.normalize(field.get("defaultValue"), str(field.get("widgetType")),
                            System.currentTimeMillis(), DateDefaults.dateLocale(ec), DateDefaults.dateZone(ec));
                    if (normalized != null) field.put("defaultValue", normalized);
                }
                Object options = field.get("options");
                if (options instanceof List) {
                    List<Map<String, Object>> cleanOpts = new ArrayList<>();
                    for (Object opt : (List<?>) options) {
                        if (!(opt instanceof Map)) continue;
                        Map<String, Object> om = CanvasSanitizer.deepCopy((Map<String, Object>) opt);
                        CanvasSanitizer.sanitizeString(om, "text");
                        CanvasSanitizer.sanitizeString(om, "key");
                        cleanOpts.add(CanvasSanitizer.keepKeys(om, OPTION_KEYS));
                    }
                    field.put("options", cleanOpts);
                }
                kept.add(CanvasSanitizer.keepKeys(field, FIELD_KEYS));
            }
        }
        out.put("fields", kept);

        boolean writeThroughFlag = Boolean.TRUE.equals(out.get("writeThrough"));
        String kind = str(out.get("kind"));
        if (kind == null || kind.isBlank()) {
            if (writeThroughFlag) {
                out.remove("kind");
                kind = null;
            } else if (hasLang(out)) {
                kind = KIND_OPENUI;
                out.put("kind", kind);
            } else {
                kind = KIND_FORM;
                out.put("kind", kind);
            }
        } else if (!KIND_FORM.equals(kind) && !KIND_VUE_SFC.equals(kind) && !KIND_OPENUI.equals(kind)) {
            kind = hasLang(out) ? KIND_OPENUI : KIND_FORM;
            out.put("kind", kind);
        } else {
            out.put("kind", kind);
        }
        applyOpenUiLang(out, kind);
        applyVueSfc(out, kind, kept);
        if (!allowVueSfc && KIND_VUE_SFC.equals(out.get("kind"))) {
            out.remove("sfc");
            out.put("sfcError", "vue-sfc is disabled");
            out.put("kind", hasLang(out) ? KIND_OPENUI : KIND_FORM);
        }
        if (!(out.get("writeThrough") instanceof Boolean)) out.put("writeThrough", Boolean.FALSE);

        out.put("columns", CanvasSanitizer.cleanColumns(out.get("columns")));
        out.put("rows", CanvasSanitizer.cleanRows(out.get("rows")));
        out.put("actions", CanvasSanitizer.cleanActions(out.get("actions")));
        out.put("removeFields", CanvasSanitizer.cleanStringList(out.get("removeFields")));
        out.put("removeActions", CanvasSanitizer.cleanStringList(out.get("removeActions")));

        Object prefillObj = out.get("prefill");
        if (prefillObj instanceof Map) {
            Map<String, Object> prefill = CanvasSanitizer.keepKeys(CanvasSanitizer.deepCopy((Map<String, Object>) prefillObj), PREFILL_KEYS);
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
        return CanvasSanitizer.keepKeys(out, TOP_KEYS);
    }

    /**
     * Merge a new write_ui payload onto the conversation's last canvas when writeThrough is true.
     * Updates the in-memory canvas only. The yield commit writes canvasJson.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> applyWriteThrough(Map<String, Object> incoming, LlmConversation conversation) {
        Map<String, Object> next = incoming != null ? incoming : new LinkedHashMap<>();
        Map<String, Object> last = null;
        LlmConversationImpl impl = conversation instanceof LlmConversationImpl ? (LlmConversationImpl) conversation : null;
        if (impl != null) last = impl.getCanvasMap();
        boolean writeThrough = Boolean.TRUE.equals(next.get("writeThrough")) && last != null;
        Map<String, Object> merged = writeThrough ? mergeCanvas(last, next) : next;
        merged.put("schemaVersion", SCHEMA_VERSION);
        if (impl != null) impl.setCanvasMap(CanvasSanitizer.deepCopy(merged));
        return merged;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mergeCanvas(Map<String, Object> last, Map<String, Object> incoming) {
        Map<String, Object> out = CanvasSanitizer.deepCopy(last);
        for (String key : Arrays.asList("title", "instruction", "submitLabel", "cancelLabel", "formId")) {
            if (incoming.containsKey(key) && incoming.get(key) != null) out.put(key, incoming.get(key));
        }
        String lastKind = str(out.get("kind"));
        if (lastKind == null || lastKind.isBlank()) lastKind = KIND_FORM;
        String nextKind = lastKind;
        if (incoming.containsKey("kind") && incoming.get("kind") != null) {
            String k = str(incoming.get("kind"));
            if (KIND_FORM.equals(k) || KIND_VUE_SFC.equals(k) || KIND_OPENUI.equals(k)) nextKind = k;
        }
        out.put("kind", nextKind);
        if (KIND_OPENUI.equals(nextKind)) {
            clearSfcKeys(out);
            if (hasLang(incoming)) {
                String lastLang = str(out.get("lang"));
                String nextLang = str(incoming.get("lang"));
                out.put("lang", OpenUiLangMerge.mergeLang(lastLang, nextLang));
                out.remove("langError");
            }
        } else if (KIND_VUE_SFC.equals(nextKind)) {
            out.remove("lang");
            out.remove("langError");
            if (hasSfcParts(incoming)) {
                out.remove("template");
                out.remove("script");
                out.remove("style");
                out.remove("sfcError");
                if (incoming.get("sfc") != null) out.put("sfc", incoming.get("sfc"));
                else {
                    out.remove("sfc");
                    for (String k : Arrays.asList("template", "script", "style")) {
                        if (incoming.containsKey(k) && incoming.get(k) != null) out.put(k, incoming.get(k));
                    }
                }
            }
        } else {
            clearSfcKeys(out);
            out.remove("lang");
            out.remove("langError");
        }
        List<Map<String, Object>> fields = mergeByName(CanvasSanitizer.asMapList(out.get("fields")), CanvasSanitizer.asMapList(incoming.get("fields")),
                CanvasSanitizer.asStringList(incoming.get("removeFields")));
        out.put("fields", fields);
        if (incoming.containsKey("columns")) out.put("columns", incoming.get("columns"));
        if (incoming.containsKey("rows")) out.put("rows", incoming.get("rows"));
        List<Map<String, Object>> actions = mergeByName(CanvasSanitizer.asMapList(out.get("actions")), CanvasSanitizer.asMapList(incoming.get("actions")),
                CanvasSanitizer.asStringList(incoming.get("removeActions")));
        out.put("actions", actions);
        out.remove("removeFields");
        out.remove("removeActions");
        out.put("writeThrough", Boolean.TRUE);
        return CanvasSanitizer.keepKeys(out, TOP_KEYS);
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

    /** bodyFromFields and bind names that are not fields. The click sends only field values. */
    public static List<String> unboundActionFields(Map<String, Object> schema) {
        List<String> missing = new ArrayList<>();
        if (schema == null) return missing;
        if (KIND_OPENUI.equals(str(schema.get("kind"))) && hasLang(schema)) return missing;
        Set<String> names = new LinkedHashSet<>();
        Object fieldsObj = schema.get("fields");
        if (fieldsObj instanceof List) {
            for (Object item : (List<?>) fieldsObj) {
                if (!(item instanceof Map)) continue;
                String name = str(((Map<?, ?>) item).get("name"));
                if (name != null) names.add(name);
            }
        }
        Object actionsObj = schema.get("actions");
        if (!(actionsObj instanceof List)) return missing;
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : (List<?>) actionsObj) {
            if (!(item instanceof Map)) continue;
            Map<?, ?> act = (Map<?, ?>) item;
            for (String key : new String[] { "bodyFromFields", "bind" }) {
                Object list = act.get(key);
                if (!(list instanceof List)) continue;
                for (Object n : (List<?>) list) {
                    if (n == null) continue;
                    String name = n.toString();
                    if (name.isEmpty() || names.contains(name) || !seen.add(name)) continue;
                    missing.add(name);
                }
            }
        }
        return missing;
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
                    if (val instanceof String) val = CanvasSanitizer.clean((String) val);
                    field.put("defaultValue", val);
                }
            }
        } catch (Throwable t) {
            out.remove("prefill");
            out.put("prefillError", "not found or not authorized");
        }
    }

    static boolean hasFormBody(Map<String, Object> map) {
        if (map == null) return false;
        Object fields = map.get("fields");
        if (fields instanceof List && !((List<?>) fields).isEmpty()) return true;
        Object actions = map.get("actions");
        if (actions instanceof List && !((List<?>) actions).isEmpty()) return true;
        String submit = str(map.get("submitLabel"));
        return submit != null && !submit.isBlank();
    }
    static boolean hasLang(Map<String, Object> map) {
        String lang = str(map != null ? map.get("lang") : null);
        return lang != null && !lang.isBlank();
    }

    /**
     * Sanitize OpenUI Lang: strip markdown fences, size-cap. Do not Jsoup-clean (Lang is not HTML).
     */
    static void applyOpenUiLang(Map<String, Object> out, String kind) {
        boolean writeThrough = Boolean.TRUE.equals(out.get("writeThrough"));
        boolean asOpenUi = KIND_OPENUI.equals(kind) || (kind == null && hasLang(out));
        if (KIND_FORM.equals(kind) || KIND_VUE_SFC.equals(kind) || (!asOpenUi && kind != null)) {
            if (!KIND_OPENUI.equals(kind)) {
                out.remove("lang");
                out.remove("langError");
            }
            return;
        }
        if (!asOpenUi) {
            out.remove("lang");
            out.remove("langError");
            return;
        }
        String lang = OpenUiLangMerge.stripLangFences(str(out.get("lang")));
        if (lang == null || lang.isBlank()) {
            if (writeThrough) return;
            // Models often label a fields/actions confirm form as openui and omit lang.
            // That shape is the form canvas: keep the fields and the submit action.
            if (hasFormBody(out)) {
                out.put("kind", KIND_FORM);
                out.remove("lang");
                out.remove("langError");
                return;
            }
            out.put("kind", KIND_OPENUI);
            out.put("langError", "openui requires lang");
            out.remove("lang");
            return;
        }
        if (lang.length() > MAX_LANG_CHARS) {
            out.put("kind", KIND_OPENUI);
            out.put("langError", "openui lang exceeds 64KiB");
            out.remove("lang");
            return;
        }
        if (kind == null) out.put("kind", KIND_OPENUI);
        out.put("lang", lang);
        out.remove("langError");
    }

    /**
     * Sanitize vue-sfc source: assemble parts, strip remote loads and fences, enforce size.
     * kind=form drops SFC keys. writeThrough with omitted kind still sanitizes parts if present.
     */
    static void applyVueSfc(Map<String, Object> out, String kind, List<Map<String, Object>> fields) {
        boolean writeThrough = Boolean.TRUE.equals(out.get("writeThrough"));
        boolean asSfc = KIND_VUE_SFC.equals(kind) || (kind == null && hasSfcParts(out));
        if (KIND_FORM.equals(kind) || (!asSfc && kind != null)) {
            clearSfcKeys(out);
            return;
        }
        if (!asSfc) {
            clearSfcKeys(out);
            return;
        }
        String assembled = assembleSfc(out);
        clearSfcKeys(out);
        if (assembled == null || assembled.isBlank() || !TEMPLATE_TAG.matcher(assembled).find()) {
            if (writeThrough) return;
            if (fields != null && !fields.isEmpty()) {
                out.put("kind", KIND_FORM);
                return;
            }
            out.put("kind", KIND_VUE_SFC);
            out.put("sfcError", "vue-sfc requires a template");
            return;
        }
        if (assembled.length() > MAX_SFC_CHARS) {
            if (writeThrough) return;
            out.put("kind", KIND_VUE_SFC);
            out.put("sfcError", "vue-sfc exceeds 64KiB");
            if (fields != null && !fields.isEmpty()) out.put("kind", KIND_FORM);
            return;
        }
        if (kind == null) out.put("kind", KIND_VUE_SFC);
        out.put("sfc", assembled);
        out.remove("sfcError");
    }
    static boolean hasSfcParts(Map<String, Object> map) {
        if (map == null) return false;
        for (String k : SFC_KEYS) {
            String v = str(map.get(k));
            if (v != null && !v.isBlank()) return true;
        }
        return false;
    }
    static void clearSfcKeys(Map<String, Object> map) {
        if (map == null) return;
        for (String k : SFC_KEYS) map.remove(k);
        map.remove("sfcError");
    }
    static String assembleSfc(Map<String, Object> in) {
        String sfc = CanvasSanitizer.stripFences(str(in.get("sfc")));
        if (sfc != null && !sfc.isBlank()) return CanvasSanitizer.stripRemoteLoads(sfc);
        String template = str(in.get("template"));
        String script = str(in.get("script"));
        String style = str(in.get("style"));
        boolean any = (template != null && !template.isBlank())
                || (script != null && !script.isBlank())
                || (style != null && !style.isBlank());
        if (!any) return null;
        StringBuilder sb = new StringBuilder();
        if (template != null && !template.isBlank()) {
            String t = template.trim();
            if (startsWithTag(t, "template")) sb.append(t);
            else sb.append("<template>\n").append(template).append("\n</template>");
            sb.append('\n');
        }
        if (script != null && !script.isBlank()) {
            String sc = script.trim();
            if (startsWithTag(sc, "script")) sb.append(sc);
            else sb.append("<script>\n").append(script).append("\n</script>");
            sb.append('\n');
        } else {
            sb.append("<script>\nmodule.exports = {}\n</script>\n");
        }
        if (style != null && !style.isBlank()) {
            String st = style.trim();
            if (startsWithTag(st, "style")) sb.append(st);
            else sb.append("<style>\n").append(style).append("\n</style>");
        }
        return CanvasSanitizer.stripRemoteLoads(sb.toString());
    }
    static boolean startsWithTag(String s, String tag) {
        if (s == null || s.length() < tag.length() + 1 || s.charAt(0) != '<') return false;
        return s.regionMatches(true, 1, tag, 0, tag.length());
    }
    static String str(Object o) { return o == null ? null : o.toString(); }
}
