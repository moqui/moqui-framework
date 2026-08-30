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
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.llm.LlmClient;
import org.moqui.llm.LlmResponse;
import org.moqui.llm.LlmTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spawn a nested agent on a HOLD TransactionCacheDb overlay. Nothing commits to production.
 */
public class EnterSimTool implements LlmTool {
    private static final Logger logger = LoggerFactory.getLogger(EnterSimTool.class);
    static final String NAME = "enter_sim";
    static final String SIM_SYSTEM =
            "You are in sim. Entity writes stay in an overlay and never commit. " +
            "Email and outbound HTTP are fenced. Authz stays on. " +
            "Explore with browse / run_service / request, test until success_criteria, " +
            "then reply with a markdown skill (YAML front matter name/description/risk plus steps). " +
            "Do not call write_ui or enter_sim.";
    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("type", "string");
        goal.put("description", "What to figure out how to do");
        Map<String, Object> crit = new LinkedHashMap<>();
        crit.put("type", "string");
        crit.put("description", "How to know the approach worked");
        Map<String, Object> max = new LinkedHashMap<>();
        max.put("type", "integer");
        max.put("description", "Inner agent iterations (default 8)");
        props.put("goal", goal);
        props.put("success_criteria", crit);
        props.put("max_iterations", max);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", Collections.singletonList("goal"));
        schema.put("properties", props);
        SCHEMA = Collections.unmodifiableMap(schema);
    }

    @Override public String getName() { return NAME; }
    @Override public String getDescription() {
        return "When no skill matches, spawn a sub-agent in a sim overlay on production data. " +
                "Writes do not commit. Returns a proposed skill body.";
    }
    @Override public Map<String, Object> getParametersSchema() { return SCHEMA; }
    @Override public Execution getExecution() { return Execution.SERVER; }

    @Override
    public Object execute(Map<String, Object> arguments, ExecutionContext ec) {
        Map<String, Object> args = arguments != null ? arguments : Collections.emptyMap();
        String goal = args.get("goal") != null ? args.get("goal").toString() : "";
        String criteria = args.get("success_criteria") != null ? args.get("success_criteria").toString() : "";
        int maxIter = 8;
        Object maxObj = args.get("max_iterations");
        if (maxObj instanceof Number) maxIter = Math.max(1, Math.min(16, ((Number) maxObj).intValue()));

        ExecutionContextImpl eci = (ExecutionContextImpl) ec;
        boolean startedOverlay = false;
        boolean prevSim = eci.simSession;
        if (!ec.getEntity().isTxCacheActive()) {
            ec.getEntity().startTxCacheDb(true);
            startedOverlay = true;
        }
        eci.simSession = true;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sim", Boolean.TRUE);
        result.put("goal", goal);
        try {
            LlmClient nested = ec.getLlm().getDefault();
            nested.system(SIM_SYSTEM);
            nested.user("Goal: " + goal + (criteria.isEmpty() ? "" : "\nSuccess criteria: " + criteria));
            nested.tool(LlmTool.browse());
            nested.tool(LlmTool.runService());
            nested.maxIterations(maxIter);
            LlmResponse resp = nested.call();
            String content = resp != null ? resp.getContent() : null;
            result.put("content", content);
            if (resp != null && resp.finishReason != null) result.put("finishReason", resp.finishReason.name());
            if (content != null && content.contains("---")) {
                SkillIndex.SkillDoc doc = SkillIndex.parseMarkdown(content, null);
                if (doc.name != null && !doc.name.isEmpty()) {
                    result.put("proposedSkillName", doc.name);
                    result.put("proposedSkillBody", content);
                    persistProposed(ec, doc, content);
                }
            }
        } catch (Throwable t) {
            logger.warn("enter_sim nested agent failed: " + t.getMessage());
            result.put("error", t.getMessage());
        } finally {
            eci.simSession = prevSim;
            if (startedOverlay) {
                try { ec.getEntity().stopTxCache(); }
                catch (Throwable t) { logger.warn("enter_sim overlay stop: " + t.getMessage()); }
            }
        }
        return result;
    }

    private static void persistProposed(ExecutionContext ec, SkillIndex.SkillDoc doc, String content) {
        try {
            EntityValue existing = ec.getEntity().find("moqui.llm.LlmSkill")
                    .condition("name", doc.name).useCache(false).one();
            if (existing != null) return;
            ec.getEntity().makeValue("moqui.llm.LlmSkill")
                    .set("name", doc.name)
                    .set("title", doc.title)
                    .set("description", doc.description)
                    .set("body", doc.body != null ? doc.body : content)
                    .set("riskId", riskId(doc.risk))
                    .set("statusId", "LsksProposed")
                    .set("provenanceId", "LskpSim")
                    .set("speaker", "sim")
                    .set("version", 1)
                    .set("worldSuccessCount", 0)
                    .set("simSuccessCount", 0)
                    .create();
        } catch (Throwable t) {
            logger.debug("Could not persist proposed skill: " + t.getMessage());
        }
    }

    private static String riskId(String risk) {
        if ("reversible".equalsIgnoreCase(risk)) return "LskReversible";
        if ("irreversible".equalsIgnoreCase(risk)) return "LskIrreversible";
        return "LskConfirm";
    }
}
