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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opt-in Assist gate: refuse tools other than find_skill / enter_sim until a skill is
 * selected or the nested sim is active. Does not auto-enter sim.
 */
final class SkillUseGate {
    static final String ERROR = "skill_required";
    static final String INSTRUCTION =
            "No skill is selected and you are not in sim. Call find_skill (use select to activate a matching skill) "
                    + "or enter_sim to explore, test, and write a skill. Do not browse, request, run_service, or write_ui until then.";
    static final String SYSTEM_ADDENDUM =
            "Force Skill Use is on. Before browse, request, run_service, or write_ui: find_skill and select a skill "
                    + "(select argument), or enter_sim to explore, test, and write a skill. The server will refuse other "
                    + "tools until then. It will not enter sim for you.";

    private SkillUseGate() { }

    static boolean allowed(LlmClientImpl client, String toolName) {
        if (client == null || !client.forceSkillUse) return true;
        if (FindSkillTool.NAME.equals(toolName) || EnterSimTool.NAME.equals(toolName)) return true;
        if (client.ec instanceof ExecutionContextImpl && ((ExecutionContextImpl) client.ec).simSession)
            return true;
        return client.activeSkillName != null && !client.activeSkillName.isBlank();
    }

    static Map<String, Object> refusal() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", ERROR);
        m.put("instruction", INSTRUCTION);
        return m;
    }

    static void activate(LlmClientImpl client, String skillName) {
        if (client == null || skillName == null || skillName.isBlank()) return;
        client.activeSkillName = skillName;
        if (client.conversation != null) client.conversation.setAttribute("activeSkillName", skillName);
    }
}
