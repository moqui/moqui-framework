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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FindSkillTool implements LlmTool {
    static final String NAME = "find_skill";
    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", "string");
        q.put("description", "What the user wants done");
        Map<String, Object> lim = new LinkedHashMap<>();
        lim.put("type", "integer");
        lim.put("description", "Max skills to return (default 5)");
        Map<String, Object> sel = new LinkedHashMap<>();
        sel.put("type", "string");
        sel.put("description", "Exact skill name to activate for this conversation");
        props.put("query", q);
        props.put("limit", lim);
        props.put("select", sel);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        SCHEMA = Collections.unmodifiableMap(schema);
    }

    @Override public String getName() { return NAME; }
    @Override public String getDescription() {
        return "Look up a playbook (skill) for how to do a task. Call this before browse. "
                + "Pass select with an exact skill name to make it the active skill. "
                + "Provide query, select, or both. If none match, call enter_sim instead of inventing writes.";
    }
    @Override public Map<String, Object> getParametersSchema() { return SCHEMA; }
    @Override public Execution getExecution() { return Execution.SERVER; }

    @Override
    public Object execute(Map<String, Object> arguments, ExecutionContext ec) {
        Map<String, Object> args = arguments != null ? arguments : Collections.emptyMap();
        String query = args.get("query") != null ? args.get("query").toString().trim() : "";
        String select = args.get("select") != null ? args.get("select").toString().trim() : "";
        Map<String, Object> result = new LinkedHashMap<>();
        if (query.isEmpty() && select.isEmpty()) {
            result.put("error", "query_or_select_required");
            result.put("instruction", "Pass query to search skills and/or select with an exact skill name to activate.");
            result.put("skills", Collections.emptyList());
            return result;
        }
        int limit = SkillIndex.DEFAULT_LIMIT;
        Object lim = args.get("limit");
        if (lim instanceof Number) limit = ((Number) lim).intValue();
        List<Map<String, Object>> out = new ArrayList<>();
        if (!query.isEmpty()) {
            List<SkillIndex.SkillDoc> docs = SkillIndex.retrieve(ec, query, limit);
            for (SkillIndex.SkillDoc d : docs) out.add(toMap(d));
        }
        result.put("skills", out);
        if (!select.isEmpty()) {
            SkillIndex.SkillDoc chosen = SkillIndex.getByName(ec, select);
            if (chosen == null) {
                result.put("error", "unknown_skill");
                result.put("instruction", "No skill named \"" + select
                        + "\". Call find_skill with a query, or enter_sim to explore, test, and write a skill. "
                        + "The active skill was not changed.");
                return result;
            }
            SkillUseGate.activate(LlmAgentLoop.currentClient(), chosen.name);
            result.put("selected", toMap(chosen));
            result.put("hint", "Skill " + chosen.name
                    + " is now the active skill. browse, request, run_service, and write_ui are allowed.");
        } else if (out.isEmpty()) {
            result.put("hint", "No skill. Call enter_sim before writes, or find_skill with select to activate one.");
        }
        return result;
    }

    static Map<String, Object> toMap(SkillIndex.SkillDoc d) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (d == null) return m;
        m.put("name", d.name);
        m.put("title", d.title);
        m.put("description", d.description);
        m.put("risk", d.risk);
        m.put("body", d.body);
        if (d.skillId != null) m.put("skillId", d.skillId);
        if (d.statusId != null) m.put("status", d.statusId);
        if (d.sourceLocation != null) m.put("sourceLocation", d.sourceLocation);
        return m;
    }
}
