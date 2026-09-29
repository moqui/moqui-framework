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

import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.llm.BrowseTool
import org.moqui.impl.screen.ScreenDefinition
import org.moqui.util.MNode

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * One-line widget tips from a resolved XML form (extends and widget-template-include already applied).
 * find_basic is named only for package moqui.basic. Other entities stay descriptive.
 */
class FormWidgetTips {
    static final String FIND_BASIC_PACKAGE = "moqui.basic"
    static final Set<String> INPUTS = [
            "drop-down", "text-line", "text-area", "date-time", "check", "radio",
            "date-find", "range-find", "file", "password"] as Set
    static final Set<String> SKIP_WIDGETS = ["hidden", "ignored", "submit", "reset", "display", "display-entity", "label", "image"] as Set

    static String section(ExecutionContext ec, String screenPath, String formName) {
        return section(ec, screenPath, formName, null)
    }

    static String section(ExecutionContext ec, String screenPath, String formName, Set<String> locked) {
        ScreenDefinition sd = screenAt(ec, screenPath)
        if (sd == null) return null
        MNode form
        try {
            form = sd.getForm(formName).getOrCreateFormNode()
        } catch (Throwable ignored) {
            return null
        }
        if (form == null) return null
        String transition = form.attribute("transition")
        StringBuilder sb = new StringBuilder()
        sb.append("Form `").append(screenPath).append("` `").append(formName).append("`")
        if (transition != null && !transition.isEmpty() && transition != ".")
            sb.append(" POST `").append(transition).append("`")
        sb.append(".\n")
        Map<String, String> ctx = contextFor(screenPath)
        MNode actions = sd.getScreenNode()?.first("actions")
        boolean any = false
        for (MNode field : form.children("field")) {
            String line = fieldLine(field, screenPath, actions, ctx, locked)
            if (line == null) continue
            sb.append("- ").append(line).append("\n")
            any = true
        }
        return any ? sb.toString().trim() : sb.toString().trim()
    }

    static Map<String, String> contextFor(String screenPath) {
        if (screenPath != null && screenPath.contains("/marble/"))
            return [appUserGroupTypeEnumId: "UgtMarbleErp"]
        return [:]
    }

    static ScreenDefinition screenAt(ExecutionContext ec, String screenPath) {
        if (!(ec instanceof ExecutionContextImpl) || screenPath == null) return null
        ExecutionContextImpl eci = (ExecutionContextImpl) ec
        List<String> segs = []
        for (String s : screenPath.split("/")) if (s != null && !s.isEmpty()) segs.add(s)
        if (segs.isEmpty() || !"apps".equals(segs.get(0))) return null
        ScreenDefinition cur = BrowseTool.getAppsScreen(eci)
        if (cur == null) return null
        for (int i = 1; i < segs.size(); i++) {
            def si = cur.getSubscreensItem(segs.get(i))
            if (si == null || si.location == null) return null
            cur = eci.screenFacade.getScreenDefinition(si.location)
            if (cur == null) return null
        }
        return cur
    }

    static String fieldLine(MNode field, String screenPath, MNode actions, Map<String, String> ctx, Set<String> locked) {
        String name = field.attribute("name")
        if (name == null || name.isEmpty() || "submitButton".equals(name)) return null
        if (locked != null && locked.contains(name)) return null
        MNode fieldDef = editSub(field)
        if (fieldDef == null) return null
        MNode widget = inputWidget(fieldDef)
        if (widget == null) return null
        String title = plain(fieldDef.attribute("title"))
        if (title == null) title = plain(fieldDef.attribute("tooltip"))
        String wn = widget.getName()
        StringBuilder sb = new StringBuilder(name).append(" ").append(wn)
        if (title != null) sb.append(" \"").append(title).append("\"")
        if ("true".equals(widget.attribute("allow-empty"))) sb.append(" allow-empty")
        String selected = plain(widget.attribute("no-current-selected-key"))
        if (selected != null) sb.append(" default ").append(selected)
        String dv = fieldDefaultValue(widget)
        if (dv != null && selected == null) sb.append(" default ").append(dv)
        if ("text-line".equals(wn) || "text-area".equals(wn) || "password".equals(wn)) {
            appendAttr(sb, "size", widget.attribute("size"))
            appendAttr(sb, "maxlength", widget.attribute("maxlength"))
            if ("text-area".equals(wn)) {
                appendAttr(sb, "cols", widget.attribute("cols"))
                appendAttr(sb, "rows", widget.attribute("rows"))
            }
        } else if ("date-time".equals(wn)) {
            String type = widget.attribute("type")
            if (type != null && !type.isEmpty() && !"timestamp".equals(type)) sb.append(" type ").append(type)
            String format = plain(widget.attribute("format"))
            if (format != null) sb.append(" format ").append(format)
        } else if ("drop-down".equals(wn) || "check".equals(wn) || "radio".equals(wn)) {
            String options = optionClause(widget, fieldDef, screenPath, actions, ctx)
            if (options != null) sb.append(" ").append(options)
        }
        return sb.toString().trim()
    }

    /** Editable sub-field. A display-only default falls through to the first conditional input. */
    static MNode editSub(MNode field) {
        MNode defn = field.first("default-field")
        if (defn != null && inputWidget(defn) != null) return defn
        for (MNode cond : field.children("conditional-field")) {
            if (inputWidget(cond) != null) return cond
        }
        return null
    }

    static MNode inputWidget(MNode fieldDef) {
        for (MNode ch : fieldDef.getChildren()) {
            if (ch == null) continue
            String n = ch.getName()
            if ("label".equals(n) || "set".equals(n)) continue
            if (SKIP_WIDGETS.contains(n)) {
                if ("hidden".equals(n) || "ignored".equals(n) || "submit".equals(n) || "display".equals(n) || "display-entity".equals(n))
                    continue
            }
            if (INPUTS.contains(n)) return ch
        }
        return null
    }

    static String optionClause(MNode widget, MNode fieldDef, String screenPath, MNode actions, Map<String, String> ctx) {
        MNode dynamic = widget.first("dynamic-options")
        if (dynamic != null) return dynamicClause(dynamic, screenPath)
        MNode entity = widget.first("entity-options")
        if (entity != null) return entityClause(entity, fieldDef, ctx)
        MNode list = widget.first("list-options")
        if (list != null) return listClause(list, actions, screenPath)
        List<String> keys = []
        for (MNode opt : widget.children("option")) {
            String key = plain(opt.attribute("key"))
            if (key != null) keys.add(key)
        }
        if (keys.isEmpty()) return null
        return "options " + keys.join(",")
    }

    static String dynamicClause(MNode dynamic, String screenPath) {
        String transition = dynamic.attribute("transition")
        StringBuilder sb = new StringBuilder("Lookup GET `")
        sb.append(screenPath)
        if (transition != null && !transition.isEmpty()) sb.append("/").append(transition)
        sb.append("`")
        Map<String, String> params = parameterMap(dynamic.attribute("parameter-map"))
        for (Map.Entry<String, String> e : params.entrySet())
            sb.append(" ").append(e.key).append("=").append(e.value)
        String valueField = dynamic.attribute("value-field")
        if (valueField != null && !valueField.isEmpty() && !"value".equals(valueField))
            sb.append(" valueField ").append(valueField)
        String labelField = dynamic.attribute("label-field")
        if (labelField != null && !labelField.isEmpty() && !"label".equals(labelField))
            sb.append(" labelField ").append(labelField)
        List<String> depends = []
        for (MNode dep : dynamic.children("depends-on")) {
            String p = dep.attribute("parameter")
            String f = dep.attribute("field")
            if (p != null && !p.isEmpty()) depends.add(p)
            else if (f != null && !f.isEmpty()) depends.add(f)
        }
        if (!depends.isEmpty()) sb.append(" depends ").append(depends.join(","))
        if ("true".equals(dynamic.attribute("server-search"))) {
            sb.append(" server-search")
            String min = dynamic.attribute("min-length")
            if (min != null && !min.isEmpty()) sb.append(" min-length ").append(min)
        }
        return sb.toString()
    }

    static String entityClause(MNode entityOptions, MNode fieldDef, Map<String, String> ctx) {
        MNode find = entityOptions.first("entity-find")
        if (find == null) return null
        String entityName = find.attribute("entity-name")
        if (entityName == null || entityName.isEmpty()) return null
        if (hasOrGroup(find))
            return "entity " + entityName + " is not an and-map (or-group)"
        String key = singleField(entityOptions.attribute("key"))
        String text = entityOptions.attribute("text")
        if (text == null || text.isEmpty()) text = "\${description}"
        List<String> ands = []
        List<String> nots = []
        List<String> orNulls = []
        for (MNode cond : find.children("econdition")) {
            if ("true".equals(cond.attribute("ignore"))) continue
            String field = cond.attribute("field-name")
            if (field == null || field.isEmpty()) continue
            String resolved = resolveCondition(cond, fieldDef, ctx)
            if (resolved == null) continue
            String op = cond.attribute("operator")
            if (op == null || op.isEmpty()) op = "equals"
            if ("true".equals(cond.attribute("or-null"))) orNulls.add(field)
            if ("not-equals".equals(op)) nots.add("not-equals " + field + "=" + resolved)
            else if ("not-in".equals(op)) nots.add("not-in " + field + "=" + resolved)
            else if ("in".equals(op) || "equals".equals(op)) ands.add(field + "=" + resolved)
            else ands.add(field + " " + op + " " + resolved)
        }
        boolean dateFilter = find.first("date-filter") != null
        List<String> orders = []
        for (MNode ob : find.children("order-by")) {
            String f = ob.attribute("field-name")
            if (f != null && !f.isEmpty()) orders.add(f)
        }
        String prefix = packageOf(entityName).matches(FIND_BASIC_PACKAGE) ? "find_basic" : "entity"
        StringBuilder sb = new StringBuilder(prefix).append(" ").append(entityName)
        if (key != null) sb.append(" key ").append(key)
        sb.append(" text ").append(text)
        if (!ands.isEmpty()) sb.append(" and ").append(ands.join(" "))
        if (!nots.isEmpty()) sb.append(" ").append(nots.join(" "))
        if (!orNulls.isEmpty()) sb.append(" or-null ").append(orNulls.join(","))
        if (dateFilter) sb.append(" date-filter")
        if (!orders.isEmpty()) sb.append(" order ").append(orders.join(","))
        return sb.toString()
    }

    static String listClause(MNode listOptions, MNode actions, String screenPath) {
        String listName = listOptions.attribute("list")
        if (listName == null || listName.isEmpty()) return null
        if (listName.contains(".") || listName.contains("(")) return "list " + listName
        MNode find = findList(actions, listName)
        if (find == null) return "list " + listName
        String entityName = find.attribute("entity-name")
        if (hasOrGroup(find)) {
            StringBuilder sb = new StringBuilder("list ").append(listName)
            sb.append(" is not an and-map (or-group on ").append(entityName != null ? entityName : "entity").append(")")
            if ("myProjectList".equals(listName))
                sb.append(". GET `/apps/marble/Project/FindProject/actions/ListProjects`")
            return sb.toString()
        }
        String key = singleField(listOptions.attribute("key"))
        String text = listOptions.attribute("text")
        if (text == null || text.isEmpty()) text = "\${description}"
        // Reuse entity-options wording by wrapping a synthetic description through entityClause's find.
        MNode fake = new MNode("entity-options", [key: key != null ? "\${" + key + "}" : null, text: text])
        fake.append(find.deepCopy(null))
        String clause = entityClause(fake, null, [:])
        if (clause == null) return "list " + listName
        return clause
    }

    static MNode findList(MNode node, String listName) {
        if (node == null) return null
        if ("entity-find".equals(node.getName()) && listName.equals(node.attribute("list"))) return node
        for (MNode ch : node.getChildren()) {
            MNode hit = findList(ch, listName)
            if (hit != null) return hit
        }
        return null
    }

    static boolean hasOrGroup(MNode node) {
        if (node == null) return false
        if ("econditions".equals(node.getName()) && "or".equals(node.attribute("combine"))) return true
        for (MNode ch : node.getChildren()) if (hasOrGroup(ch)) return true
        return false
    }

    static String resolveCondition(MNode cond, MNode fieldDef, Map<String, String> ctx) {
        boolean ignoreEmpty = "true".equals(cond.attribute("ignore-if-empty"))
        String value = cond.attribute("value")
        if (value != null && !value.isEmpty()) {
            if (value.contains("\${")) return ignoreEmpty ? null : value
            return value
        }
        String from = cond.attribute("from")
        String field = cond.attribute("field-name")
        String name = (from != null && !from.isEmpty()) ? from : field
        String lit = literal(name, fieldDef, ctx)
        if (lit != null) return lit
        if (ignoreEmpty) return null
        return "<" + name + ">"
    }

    static String literal(String name, MNode fieldDef, Map<String, String> ctx) {
        if (name == null) return null
        if (ctx != null && ctx.containsKey(name) && ctx.get(name) != null) return ctx.get(name)
        if (fieldDef == null) return null
        for (MNode set : fieldDef.children("set")) {
            if (!name.equals(set.attribute("field"))) continue
            String v = set.attribute("value")
            if (v != null && !v.isEmpty() && !v.contains("\${")) return v
        }
        return null
    }

    static String fieldDefaultValue(MNode widget) {
        String dv = widget.attribute("default-value")
        if (dv == null || dv.isEmpty()) return null
        def pref = (dv =~ /getPreference\('([^']+)'\).*?: '([^']+)'/)
        if (pref.find()) return "pref " + pref.group(1) + " or " + pref.group(2)
        if (dv.contains("\${")) return null
        return dv
    }

    static String plain(String raw) {
        if (raw == null) return null
        String s = raw.trim()
        if (s.isEmpty() || s.contains("\${")) return null
        return s
    }

    static String singleField(String raw) {
        if (raw == null || raw.isEmpty()) return null
        String s = raw.trim()
        if (s.startsWith("\${") && s.endsWith("}") && s.indexOf("\${", 2) < 0)
            return s.substring(2, s.length() - 1)
        if (s.contains("\${")) return s
        return s
    }

    static String packageOf(String entityName) {
        int dot = entityName.lastIndexOf('.')
        return dot > 0 ? entityName.substring(0, dot) : entityName
    }

    static final Pattern PARAM_SQ = Pattern.compile("([A-Za-z_]\\w*)\\s*:\\s*'([^']*)'")
    static final Pattern PARAM_DQ = Pattern.compile("([A-Za-z_]\\w*)\\s*:\\s*\"([^\"]*)\"")

    static Map<String, String> parameterMap(String raw) {
        Map<String, String> out = new LinkedHashMap<>()
        if (raw == null || raw.isEmpty()) return out
        Matcher m = PARAM_SQ.matcher(raw)
        while (m.find()) out.put(m.group(1), m.group(2))
        Matcher q = PARAM_DQ.matcher(raw)
        while (q.find()) out.put(q.group(1), q.group(2))
        return out
    }

    static void appendAttr(StringBuilder sb, String label, String value) {
        String v = plain(value)
        if (v != null) sb.append(" ").append(label).append(" ").append(v)
    }
}
