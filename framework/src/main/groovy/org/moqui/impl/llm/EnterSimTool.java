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
        max.put("description", "Inner agent iterations (default 32, floor 32 for live prove)");
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
        // Prove-then-optimize: give the nested model enough rounds to actually return a skill.
        // Floor 32 so a parent tool-call cannot shrink the budget back to 8.
        int maxIter = 32;
        Object maxObj = args.get("max_iterations");
        if (maxObj instanceof Number) maxIter = Math.max(32, Math.min(64, ((Number) maxObj).intValue()));

        ExecutionContextImpl eci = (ExecutionContextImpl) ec;
        LlmClientImpl parent = LlmAgentLoop.currentClient();
        String convId = parent != null ? parent.convId() : null;
        if (eci.simSession) {
            Map<String, Object> already = new LinkedHashMap<>();
            already.put("error", "already in sim");
            already.put("sim", Boolean.TRUE);
            already.put("simActive", Boolean.TRUE);
            already.put("selected", Boolean.FALSE);
            already.put("hint", "Already in sim. Continue exploring here. A skill is not selected as active until the parent calls find_skill with select after sim exits.");
            LlmTrace.logSimEnter(convId, goal, maxIter, false);
            LlmTrace.logSimExit(convId, 0, null, "already in sim");
            return already;
        }
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
        long simStart = System.currentTimeMillis();
        LlmTrace.logSimEnter(convId, goal, maxIter, startedOverlay);
        try {
            LlmClientImpl nested;
            if (parent != null) nested = parent.nestForSim(maxIter);
            else {
                LlmClient c = ec.getLlm().getDefault();
                if (!(c instanceof LlmClientImpl)) throw new IllegalStateException("nested LLM client required");
                nested = (LlmClientImpl) c;
                nested.tool(LlmTool.browse());
                nested.tool(LlmTool.runService());
                nested.maxIterations(maxIter);
            }
            Map<String, Object> promptCtx = new LinkedHashMap<>();
            promptCtx.put("goal", goal);
            promptCtx.put("successCriteria", criteria);
            String simSystem = LlmGateway.renderPrompt(ec, LlmGateway.PROMPT_SIM, promptCtx);
            if (simSystem != null && !simSystem.isBlank()) nested.system(simSystem);
            nested.user(goal != null && !goal.isBlank() ? goal : "Go.");
            LlmResponse resp = LlmGateway.withoutCallerTx(ec, nested::call);
            String content = resp != null ? resp.getContent() : null;
            result.put("content", content);
            if (resp != null && resp.finishReason != null) result.put("finishReason", resp.finishReason.name());
            if (content != null && content.contains("---")) {
                SkillIndex.SkillDoc doc = SkillIndex.parseMarkdown(content, null);
                if (doc.name != null && !doc.name.isEmpty()) {
                    result.put("proposedSkillName", doc.name);
                    result.put("proposedSkillBody", content);
                    EntityValue persisted = persistProposedInTx(ec, doc, content);
                    if (persisted != null) {
                        Object sid = persisted.get("skillId");
                        if (sid != null) result.put("proposedSkillId", sid.toString());
                        Object st = persisted.get("statusId");
                        if (st != null) result.put("proposedSkillStatus", st.toString());
                    }
                }
            }
        } catch (Throwable t) {
            logger.warn("enter_sim nested agent failed: " + t.getMessage());
            result.put("error", t.getMessage());
        } finally {
            Object proposed = result.get("proposedSkillName");
            Object err = result.get("error");
            LlmTrace.logSimExit(convId, System.currentTimeMillis() - simStart,
                    proposed != null ? proposed.toString() : null,
                    err != null ? err.toString() : null);
            eci.simSession = prevSim;
            if (startedOverlay) {
                try { ec.getEntity().stopTxCache(); }
                catch (Throwable t) { logger.warn("enter_sim overlay stop: " + t.getMessage()); }
            }
        }
        result.put("simActive", Boolean.FALSE);
        result.put("selected", Boolean.FALSE);
        result.put("hint", simExitHint(result));
        return result;
    }

    public static String simExitHint(Map<String, Object> result) {
        Object name = result != null ? result.get("proposedSkillName") : null;
        Object id = result != null ? result.get("proposedSkillId") : null;
        Object status = result != null ? result.get("proposedSkillStatus") : null;
        if (name != null && !name.toString().isBlank()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Sim overlay is closed; you are back in the world. A proposed skill was ");
            if (id != null && !id.toString().isBlank()) {
                sb.append("persisted: name=").append(name)
                        .append(", skillId=").append(id);
                if (status != null) sb.append(", status=").append(status);
                sb.append('.');
            } else {
                sb.append("extracted (name=").append(name)
                        .append(") but was not persisted (no skillId).");
            }
            sb.append(" The skill was NOT selected as the active skill. Call find_skill with select=")
                    .append(name)
                    .append(" before browse, request, run_service, or write_ui.");
            return sb.toString();
        }
        return "Sim overlay is closed; you are back in the world. No skill was persisted and no skill was "
                + "selected as active. Call find_skill to select a skill, or enter_sim again to explore, test, "
                + "and write a skill.";
    }

    private static EntityValue persistProposedInTx(ExecutionContext ec, SkillIndex.SkillDoc doc, String content) {
        try {
            boolean began = ec.getTransaction().begin(60);
            try {
                EntityValue ev = SkillIndex.persistProposed(ec, doc, content);
                ec.getTransaction().commit(began);
                return ev;
            } catch (Throwable t) {
                ec.getTransaction().rollback(began, "persist proposed LlmSkill", t);
                throw t;
            }
        } catch (Throwable t) {
            logger.warn("Could not persist proposed skill: {}", t.getMessage());
            return null;
        }
    }
}
