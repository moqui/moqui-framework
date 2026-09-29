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
import org.moqui.entity.EntityCondition;
import org.moqui.entity.EntityConditionFactory;
import org.moqui.entity.EntityFind;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.impl.entity.EntityFacadeImpl;
import org.moqui.impl.llm.LlmFacadeImpl.BasicEntityAllow;
import org.moqui.llm.LlmTool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only list for drop-down entity-options on allow-listed configuration entities.
 * Artifact authz is off; the profile allowed-basic-entity list is the gate.
 * Returns key and text only.
 */
public class FindBasicTool implements LlmTool {
    static final String NAME = "find_basic";
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    static final String USAGE = "find_basic reads allow-listed configuration entities with artifact authz off. "
            + "Pass entityName, keyField, and text (a field, ${field}, or a LocalizedMessage template whose "
            + "placeholders are fields on that entity). and is a field map; a list or comma-separated string is IN. "
            + "Optional: orNull, notEquals, notIn, orderBy, dateFilter, limit (max 200). "
            + "Omit entityName to list the entities this profile allows.";
    private static final Pattern SAFE_FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SAFE_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("entityName", desc("string",
                "Full entity name, such as moqui.basic.Enumeration. Omit to list allowed entities."));
        props.put("keyField", desc("string", "Field used as the option key. Required when entityName is set."));
        props.put("text", desc("string",
                "Option label: a field name, ${field}, or a LocalizedMessage template name. Required when entityName is set."));
        props.put("and", mapOf("type", "object"));
        props.put("orNull", stringArray("Field names that also match null."));
        props.put("notEquals", mapOf("type", "object"));
        props.put("notIn", mapOf("type", "object"));
        props.put("orderBy", desc("string", "Comma-separated field names. Prefix a name with - for descending."));
        props.put("dateFilter", mapOf("type", "boolean"));
        props.put("limit", desc("integer", "Max rows. Default 50, hard max 200."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        SCHEMA = Collections.unmodifiableMap(schema);
    }

    private final List<BasicEntityAllow> allows;

    public FindBasicTool() { this(null); }
    public FindBasicTool(List<BasicEntityAllow> allows) { this.allows = allows; }

    @Override public String getName() { return NAME; }
    @Override public String getDescription() { return USAGE; }
    @Override public Map<String, Object> getParametersSchema() { return SCHEMA; }
    @Override public Execution getExecution() { return Execution.SERVER; }

    @Override
    public Object execute(Map<String, Object> arguments, ExecutionContext ec) {
        Map<String, Object> args = arguments != null ? arguments : Collections.emptyMap();
        List<BasicEntityAllow> rules = rules(ec);
        String entityName = str(args.get("entityName"));
        if (entityName == null) return catalog(ec, rules);
        if (!(ec instanceof ExecutionContextImpl)) return error("execution_context", "ExecutionContextImpl is required");
        EntityFacadeImpl efi = ((ExecutionContextImpl) ec).ecfi.entityFacade;
        EntityDefinition ed = efi.getEntityDefinition(entityName);
        if (ed == null) return error("unknown_entity", entityName);
        String packageName = packageName(ed);
        if (!BasicEntityAllow.any(rules, packageName, ed.getEntityName()))
            return error("entity_not_allowed", entityName);

        String keyField = fieldName(str(args.get("keyField")));
        String text = str(args.get("text"));
        if (keyField == null) return error("key_required", "keyField is required");
        if (text == null) return error("text_required", "text is required");
        if (!ed.isField(keyField)) return error("unknown_field", keyField);
        if (!textRenderable(ed, text, ec)) return error("unknown_text", text);

        int limit = limit(args.get("limit"));
        EntityConditionFactory cf = efi.getConditionFactory();
        EntityFind ef = efi.find(ed.getFullEntityName()).disableAuthz();
        Map<String, Object> err = applyConditions(ef, cf, ed, args);
        if (err != null) return err;
        if (Boolean.TRUE.equals(bool(args.get("dateFilter")))) {
            if (!ed.isField("fromDate") || !ed.isField("thruDate"))
                return error("unknown_field", "fromDate");
            ef.conditionDate("fromDate", "thruDate", ec.getUser().getNowTimestamp());
        }
        try {
            long total = ef.count();
            ef.limit(limit);
            EntityList rows = ef.list();
            List<Map<String, Object>> options = new ArrayList<>();
            if (rows != null) {
                for (EntityValue ev : rows) {
                    if (ev == null) continue;
                    Object key = ev.get(keyField);
                    if (key == null) continue;
                    String label = renderText(ed, ev, text, ec);
                    Map<String, Object> opt = new LinkedHashMap<>();
                    opt.put("key", key.toString());
                    opt.put("text", label != null ? label : "");
                    options.add(opt);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("options", options);
            out.put("count", total);
            out.put("truncated", total > options.size());
            return out;
        } catch (RuntimeException e) {
            return error("find_failed", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private List<BasicEntityAllow> rules(ExecutionContext ec) {
        if (allows != null) return allows;
        LlmClientImpl client = LlmAgentLoop.currentClient();
        if (client != null && client.profile != null && client.profile.allowedBasicEntities != null)
            return client.profile.allowedBasicEntities;
        return Collections.emptyList();
    }

    private Map<String, Object> catalog(ExecutionContext ec, List<BasicEntityAllow> rules) {
        List<String> names = new ArrayList<>();
        if (ec instanceof ExecutionContextImpl) {
            EntityFacadeImpl efi = ((ExecutionContextImpl) ec).ecfi.entityFacade;
            for (String name : efi.getAllEntityNames()) {
                EntityDefinition ed = efi.getEntityDefinition(name);
                if (ed == null) continue;
                if (BasicEntityAllow.any(rules, packageName(ed), ed.getEntityName()))
                    names.add(ed.getFullEntityName());
            }
        }
        Collections.sort(names);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entities", names);
        out.put("usage", USAGE);
        return out;
    }

    private static Map<String, Object> applyConditions(EntityFind ef, EntityConditionFactory cf,
            EntityDefinition ed, Map<String, Object> args) {
        List<String> orNull = stringList(args.get("orNull"));
        Map<String, Object> and = map(args.get("and"));
        for (Map.Entry<String, Object> e : and.entrySet()) {
            if (!ed.isField(e.getKey())) return error("unknown_field", e.getKey());
            Object value = inOrEquals(e.getValue());
            boolean nullable = orNull.remove(e.getKey());
            addCompare(ef, cf, e.getKey(),
                    value instanceof Collection ? EntityCondition.IN : EntityCondition.EQUALS, value, nullable);
        }
        Map<String, Object> notEquals = map(args.get("notEquals"));
        for (Map.Entry<String, Object> e : notEquals.entrySet()) {
            if (!ed.isField(e.getKey())) return error("unknown_field", e.getKey());
            if (e.getValue() instanceof Collection) return error("unknown_field", e.getKey());
            boolean nullable = orNull.remove(e.getKey());
            addCompare(ef, cf, e.getKey(), EntityCondition.NOT_EQUAL, e.getValue(), nullable);
        }
        Map<String, Object> notIn = map(args.get("notIn"));
        for (Map.Entry<String, Object> e : notIn.entrySet()) {
            if (!ed.isField(e.getKey())) return error("unknown_field", e.getKey());
            boolean nullable = orNull.remove(e.getKey());
            addCompare(ef, cf, e.getKey(), EntityCondition.NOT_IN, asCollection(e.getValue()), nullable);
        }
        for (String field : orNull) {
            if (!ed.isField(field)) return error("unknown_field", field);
            ef.condition(field, EntityCondition.IS_NULL, null);
        }
        String orderBy = str(args.get("orderBy"));
        if (orderBy != null) {
            for (String part : orderBy.split(",")) {
                String token = part.trim();
                if (token.isEmpty()) continue;
                String field = token.startsWith("-") ? token.substring(1) : token;
                if (!SAFE_FIELD.matcher(field).matches() || !ed.isField(field))
                    return error("unknown_field", field);
                ef.orderBy(token);
            }
        }
        return null;
    }

    private static void addCompare(EntityFind ef, EntityConditionFactory cf, String field,
            EntityCondition.ComparisonOperator op, Object value, boolean orNull) {
        if (orNull) ef.condition(cf.makeCondition(field, op, value, true));
        else if (op == EntityCondition.EQUALS) ef.condition(field, value);
        else ef.condition(field, op, value);
    }

    static boolean textRenderable(EntityDefinition ed, String text, ExecutionContext ec) {
        return renderTemplate(ed, null, text, ec, true) != null || fieldOnly(ed, text);
    }

    static String renderText(EntityDefinition ed, EntityValue ev, String text, ExecutionContext ec) {
        if (fieldOnly(ed, text)) return stringVal(ev, fieldName(text), ec);
        String rendered = renderTemplate(ed, ev, text, ec, false);
        return rendered != null ? rendered : "";
    }

    /** True when text is exactly one field on the entity. */
    private static boolean fieldOnly(EntityDefinition ed, String text) {
        String field = fieldName(text);
        return field != null && ed.isField(field) && (text.trim().equals(field) || text.trim().equals("${" + field + "}"));
    }

    /**
     * @return rendered label, empty string when checking only, or null when text is not a safe template
     */
    private static String renderTemplate(EntityDefinition ed, EntityValue ev, String text,
            ExecutionContext ec, boolean checkOnly) {
        String src = text.trim();
        if (fieldOnly(ed, src)) return checkOnly ? "" : null;
        if (!SAFE_FIELD.matcher(src).matches()) return null;
        String localized = ec.getL10n().localize(src);
        if (localized == null || localized.equals(src) || !localized.contains("${")) return null;
        String stripped = SAFE_PLACEHOLDER.matcher(localized).replaceAll("");
        if (stripped.contains("${")) return null;
        Matcher m = SAFE_PLACEHOLDER.matcher(localized);
        while (m.find()) {
            if (!ed.isField(m.group(1))) return null;
        }
        if (checkOnly) return "";
        Matcher sub = SAFE_PLACEHOLDER.matcher(localized);
        StringBuffer sb = new StringBuffer();
        while (sub.find()) {
            String rep = stringVal(ev, sub.group(1), ec);
            sub.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        sub.appendTail(sb);
        return sb.toString();
    }

    private static String stringVal(EntityValue ev, String field, ExecutionContext ec) {
        if (ev == null || field == null) return "";
        Object v = ev.get(field);
        if (v == null) return "";
        String s = v.toString();
        try {
            String loc = ec.getL10n().localize(s);
            return loc != null ? loc : s;
        } catch (RuntimeException e) {
            return s;
        }
    }

    static String packageName(EntityDefinition ed) {
        String full = ed.getFullEntityName();
        String shortName = ed.getEntityName();
        String suffix = "." + shortName;
        if (full != null && shortName != null && full.endsWith(suffix))
            return full.substring(0, full.length() - suffix.length());
        if (full == null) return "";
        int dot = full.lastIndexOf('.');
        return dot > 0 ? full.substring(0, dot) : full;
    }

    static String fieldName(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("${") && s.endsWith("}") && s.indexOf("${", 2) < 0)
            s = s.substring(2, s.length() - 1).trim();
        if (!SAFE_FIELD.matcher(s).matches()) return null;
        return s;
    }

    private static Object inOrEquals(Object value) {
        if (value instanceof Collection) return value;
        if (value instanceof String && ((String) value).indexOf(',') >= 0) return asCollection(value);
        return value;
    }

    private static Collection<?> asCollection(Object value) {
        if (value instanceof Collection) return (Collection<?>) value;
        List<String> parts = new ArrayList<>();
        if (value == null) return parts;
        for (String p : value.toString().split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        return parts;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object raw) {
        if (!(raw instanceof Map)) return Collections.emptyMap();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            if (e.getKey() != null) out.put(e.getKey().toString(), e.getValue());
        }
        return out;
    }

    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof Collection) {
            for (Object o : (Collection<?>) raw) {
                if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
            }
        } else if (raw instanceof String && !((String) raw).isBlank()) {
            for (String p : ((String) raw).split(",")) {
                if (!p.isBlank()) out.add(p.trim());
            }
        }
        return out;
    }

    private static int limit(Object raw) {
        int n = DEFAULT_LIMIT;
        if (raw instanceof Number) n = ((Number) raw).intValue();
        else if (raw instanceof String && !((String) raw).isBlank()) {
            try { n = Integer.parseInt(((String) raw).trim()); } catch (NumberFormatException ignored) { }
        }
        if (n < 1) n = 1;
        if (n > MAX_LIMIT) n = MAX_LIMIT;
        return n;
    }

    private static Boolean bool(Object raw) {
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof String) return Boolean.valueOf((String) raw);
        return Boolean.FALSE;
    }

    private static String str(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", code);
        if (message != null) err.put("message", message);
        return err;
    }

    private static Map<String, Object> desc(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    private static Map<String, Object> mapOf(String k, String v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static Map<String, Object> stringArray(String description) {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", "string");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put("items", items);
        m.put("description", description);
        return m;
    }
}
