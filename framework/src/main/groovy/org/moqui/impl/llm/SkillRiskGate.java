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

import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.llm.LlmToolCall;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * World-rim gate for mutating {@code request} and {@code run_service}.
 * Force Skill Use stays a separate, opt-in toggle. Sim is not gated.
 */
final class SkillRiskGate {
    static final String NOT_CONFIRMED = "not_confirmed";
    static final String DEFERRED = "deferred";
    static final String INSTRUCTION =
            "No skill is selected. Call find_skill and select a skill, or enter_sim, before request writes or run_service.";
    static final String DEFERRED_INSTRUCTION =
            "This call was not run because an earlier call is waiting for confirmation. Re-issue it after the user confirms.";

    private SkillRiskGate() { }

    static final class Decision {
        static final int RUN = 0;
        static final int REFUSE = 1;
        static final int YIELD = 2;

        final int action;
        final Map<String, Object> refusal;
        final String risk;

        private Decision(int action, Map<String, Object> refusal, String risk) {
            this.action = action;
            this.refusal = refusal;
            this.risk = risk;
        }

        static Decision run() { return new Decision(RUN, null, null); }
        static Decision refuse() { return new Decision(REFUSE, refusalMap(), null); }
        static Decision yield(String risk) { return new Decision(YIELD, null, risk); }
    }

    static Decision decide(LlmClientImpl client, LlmToolCall call) {
        if (client == null || call == null || !isGatedTool(call.name)) return Decision.run();
        if (client.ec instanceof ExecutionContextImpl && ((ExecutionContextImpl) client.ec).simSession)
            return Decision.run();
        Map<String, Object> args = LlmJson.tryToMap(call.arguments);
        if (args == null) return Decision.run();
        if (RequestTool.NAME.equals(call.name) && !mutatingRequest(args)) return Decision.run();
        String skillName = client.activeSkillName;
        if (skillName == null || skillName.isBlank()) return Decision.refuse();
        String risk = riskOf(client, skillName);
        if ("reversible".equals(risk)) return Decision.run();
        return Decision.yield(risk);
    }

    static boolean confirmed(Object content) {
        Map<String, Object> map = null;
        if (content instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) content;
            map = cast;
        } else if (content instanceof String) {
            map = LlmJson.tryToMap((String) content);
        }
        if (map == null) return false;
        Object value = map.get("confirmed");
        if (Boolean.TRUE.equals(value)) return true;
        return value instanceof String && "true".equalsIgnoreCase((String) value);
    }

    static Map<String, Object> notConfirmed() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", NOT_CONFIRMED);
        m.put("instruction", "The user did not confirm this call. Do not run it again unless they ask.");
        return m;
    }

    static Map<String, Object> deferred(LlmToolCall call) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", DEFERRED);
        m.put("instruction", DEFERRED_INSTRUCTION);
        Map<String, Object> args = call != null ? LlmJson.tryToMap(call.arguments) : null;
        if (args != null) {
            if (args.get("method") != null) m.put("method", args.get("method"));
            if (args.get("path") != null) m.put("path", args.get("path"));
            if (args.get("serviceName") != null) m.put("serviceName", args.get("serviceName"));
            Object submitted = args.get("body");
            if (!(submitted instanceof Map)) submitted = args.get("parameters");
            if (submitted instanceof Map && !((Map<?, ?>) submitted).isEmpty()) m.put("submitted", submitted);
        }
        return m;
    }

    private static boolean isGatedTool(String name) {
        return RunServiceTool.NAME.equals(name) || RequestTool.NAME.equals(name);
    }

    /** GET and HEAD are reads. A missing method is left to the tool, which reports it. */
    private static boolean mutatingRequest(Map<String, Object> args) {
        Object raw = args.get("method");
        if (raw == null) return false;
        String method = raw.toString().trim().toUpperCase(Locale.ROOT);
        if (method.isEmpty()) return false;
        return !"GET".equals(method) && !"HEAD".equals(method);
    }

    private static String riskOf(LlmClientImpl client, String skillName) {
        SkillIndex.SkillDoc doc = SkillIndex.getByName(client.ec, skillName);
        String risk = doc != null && doc.risk != null ? doc.risk.trim().toLowerCase(Locale.ROOT) : "";
        if ("reversible".equals(risk) || "irreversible".equals(risk)) return risk;
        return "confirm";
    }

    private static Map<String, Object> refusalMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", SkillUseGate.ERROR);
        m.put("instruction", INSTRUCTION);
        return m;
    }
}
