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
        props.put("query", q);
        props.put("limit", lim);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", Collections.singletonList("query"));
        schema.put("properties", props);
        SCHEMA = Collections.unmodifiableMap(schema);
    }

    @Override public String getName() { return NAME; }
    @Override public String getDescription() {
        return "Look up a playbook (skill) for how to do a task. Call this before browse. " +
                "If none match, call enter_sim instead of inventing writes.";
    }
    @Override public Map<String, Object> getParametersSchema() { return SCHEMA; }
    @Override public Execution getExecution() { return Execution.SERVER; }

    @Override
    public Object execute(Map<String, Object> arguments, ExecutionContext ec) {
        Map<String, Object> args = arguments != null ? arguments : Collections.emptyMap();
        String query = args.get("query") != null ? args.get("query").toString() : "";
        int limit = SkillIndex.DEFAULT_LIMIT;
        Object lim = args.get("limit");
        if (lim instanceof Number) limit = ((Number) lim).intValue();
        List<SkillIndex.SkillDoc> docs = SkillIndex.retrieve(ec, query, limit);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SkillIndex.SkillDoc d : docs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", d.name);
            m.put("title", d.title);
            m.put("description", d.description);
            m.put("risk", d.risk);
            m.put("body", d.body);
            if (d.sourceLocation != null) m.put("sourceLocation", d.sourceLocation);
            out.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skills", out);
        if (out.isEmpty()) result.put("hint", "No skill. Call enter_sim before writes.");
        return result;
    }
}
