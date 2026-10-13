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
import org.moqui.llm.LlmClient;
import org.moqui.llm.LlmException;
import org.moqui.llm.LlmResponse;
import org.moqui.util.RestClient;
import org.moqui.util.SystemBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-mostly helpers behind the System app LLM screens. Callers are ADMIN screens and services,
 * so nothing here widens access: profile credentials are never returned, and a connection test
 * goes through the normal client, which applies the profile's authz and tarpit.
 */
public final class LlmAdmin {
    private LlmAdmin() { }

    /** Effective config of every profile, with credentials removed. */
    public static List<Map<String, Object>> profileDetails(ExecutionContext ec) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ec == null || !(ec.getLlm() instanceof LlmFacadeImpl)) return out;
        LlmFacadeImpl facade = (LlmFacadeImpl) ec.getLlm();
        for (String name : facade.getProfileNames()) {
            LlmFacadeImpl.ProfileState ps = facade.getProfileState(name);
            if (ps == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("endpoint", RestClient.redactUri(ps.endpointUrl));
            m.put("protocol", ps.protocol != null ? ps.protocol.getName() : null);
            m.put("model", ps.model);
            m.put("hasApiKey", ps.apiKey != null && !ps.apiKey.isBlank());
            m.put("authHeaderName", ps.authHeaderName);
            m.put("maxTokens", ps.maxTokens);
            m.put("maxTokensParameter", ps.maxTokensParameter);
            m.put("temperature", ps.temperature);
            m.put("timeoutSeconds", ps.timeoutSeconds);
            m.put("retryInitialSeconds", ps.retryInitialSeconds);
            m.put("retryMax", ps.retryMax);
            m.put("timeoutRetry", ps.timeoutRetry);
            m.put("emptyRetries", ps.emptyRetries);
            m.put("logContent", ps.logContent);
            m.put("ssePingSeconds", ps.ssePingSeconds);
            m.put("summarizeConversation", ps.summarizeConversation);
            m.put("systemLocation", ps.systemLocation);
            m.put("apiConfig", ps.apiConfig != null && ps.apiConfig.present);
            // Names only. Header and query values can carry credentials.
            m.put("extraHeaderNames", new ArrayList<>(ps.extraHeaders.keySet()));
            m.put("extraQueryNames", new ArrayList<>(ps.extraQuery.keySet()));
            List<Map<String, Object>> tools = new ArrayList<>();
            addTool(tools, "browse", ps.allowBrowse);
            addTool(tools, "run_service", ps.allowRunService);
            addTool(tools, "request (any path)", ps.allowUnprefixedRequest);
            addTool(tools, "enter_sim", ps.allowEnterSim);
            addTool(tools, "write_ui", ps.allowWriteUi);
            addTool(tools, "Vue screens (vue-sfc)", ps.allowVueSfc);
            addTool(tools, "client system prompt", ps.allowClientSystem);
            m.put("tools", tools);
            List<String> paths = new ArrayList<>();
            for (LlmFacadeImpl.AllowedPath ap : ps.allowedPaths) paths.add(ap.prefix + " [" + ap.methodsCsv + "]");
            m.put("allowedPaths", paths);
            m.put("allowedEntities", new ArrayList<>(ps.allowedEntities));
            m.put("allowedBasicEntityCount", ps.allowedBasicEntities != null ? ps.allowedBasicEntities.size() : 0);
            out.add(m);
        }
        return out;
    }

    private static void addTool(List<Map<String, Object>> tools, String name, boolean allowed) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("allowed", allowed);
        tools.add(t);
    }

    /** Runtime switches that change LLM behavior and are set outside the profile. */
    public static Map<String, Object> runtimeSwitches() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String key : new String[] { "llm_trace_dump", "llm_skill_auto_admit" }) {
            String v = SystemBinding.getPropOrEnv(key);
            m.put(key, v != null ? v : "(not set)");
        }
        return m;
    }

    /** One small call through the normal client. Never throws. The result says how long it took or why it failed. */
    public static Map<String, Object> testProfile(ExecutionContext ec, String profileName) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profileName", profileName);
        long start = System.currentTimeMillis();
        try {
            LlmClient client = ec.getLlm().getClient(profileName);
            LlmResponse r = client.user("Reply with the single word OK.").maxTokens(16).call();
            out.put("ok", Boolean.TRUE);
            out.put("content", r != null ? r.getContent() : null);
            out.put("finishReason", r != null && r.finishReason != null ? r.finishReason.name() : null);
            out.put("model", r != null ? r.model : null);
        } catch (LlmException e) {
            out.put("ok", Boolean.FALSE);
            out.put("error", e.getMessage());
            out.put("httpStatus", e.getHttpStatus());
        } catch (RuntimeException e) {
            out.put("ok", Boolean.FALSE);
            out.put("error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        out.put("durationMs", System.currentTimeMillis() - start);
        return out;
    }

    /** What the agent would see for a request: ranked skills, why, and the text that goes into the prompt. */
    public static Map<String, Object> retrieveSkills(ExecutionContext ec, String query, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<SkillIndex.SkillDoc> docs = SkillIndex.retrieve(ec, query, limit > 0 ? limit : 15);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SkillIndex.SkillDoc d : docs) {
            SkillIndex.ScoreDetail sd = SkillIndex.scoreDetail(d, query);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", d.name);
            r.put("title", d.title);
            r.put("source", d.skillId == null ? "shipped file" : "database");
            r.put("skillId", d.skillId);
            r.put("risk", d.risk);
            r.put("statusId", d.statusId);
            r.put("provenanceId", d.provenanceId);
            r.put("score", sd.score);
            r.put("strongMatch", sd.strong);
            r.put("reference", SkillIndex.isReference(d));
            rows.add(r);
        }
        out.put("skills", rows);
        out.put("injectText", SkillIndex.formatInjectForDocs(ec, docs, query));
        return out;
    }
}
