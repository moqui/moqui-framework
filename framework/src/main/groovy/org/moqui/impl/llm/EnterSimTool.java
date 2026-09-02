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
            "You are in sim. Overlay writes never commit. Authz stays on. " +
            "At most 2 browses, then run_service or request to test the write. " +
            "As soon as a write succeeds (or you know the exact service), STOP and reply with ONLY a markdown skill, no other prose:\n" +
            "---\nname: kebab-case-name\ntitle: short title\ndescription: one line\nrisk: reversible\n---\n" +
            "# Steps\n- run_service create#... with the parameters that worked\n" +
            "Do not call write_ui or enter_sim. Do not keep browsing after a successful write.";
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
            nested.system(SIM_SYSTEM);
            nested.user("Goal: " + goal + (criteria.isEmpty() ? "" : "\nSuccess criteria: " + criteria));
            LlmResponse resp = LlmGateway.withoutCallerTx(ec, nested::call);
            String content = resp != null ? resp.getContent() : null;
            result.put("content", content);
            if (resp != null && resp.finishReason != null) result.put("finishReason", resp.finishReason.name());
            if (content != null && content.contains("---")) {
                SkillIndex.SkillDoc doc = SkillIndex.parseMarkdown(content, null);
                if (doc.name != null && !doc.name.isEmpty()) {
                    result.put("proposedSkillName", doc.name);
                    result.put("proposedSkillBody", content);
                    persistProposedInTx(ec, doc, content);
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
        return result;
    }

    private static void persistProposedInTx(ExecutionContext ec, SkillIndex.SkillDoc doc, String content) {
        try {
            boolean began = ec.getTransaction().begin(60);
            try {
                SkillIndex.persistProposed(ec, doc, content);
                ec.getTransaction().commit(began);
            } catch (Throwable t) {
                ec.getTransaction().rollback(began, "persist proposed LlmSkill", t);
                throw t;
            }
        } catch (Throwable t) {
            logger.warn("Could not persist proposed skill: {}", t.getMessage());
        }
    }
}
