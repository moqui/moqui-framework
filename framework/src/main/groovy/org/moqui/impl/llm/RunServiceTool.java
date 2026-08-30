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

import org.moqui.context.ArtifactAuthorizationException;
import org.moqui.context.ArtifactTarpitException;
import org.moqui.context.ExecutionContext;
import org.moqui.llm.LlmTool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic SERVER tool to call any Moqui service the current user is authorized for.
 * Not the typed {@code LlmTool.service(name)} wrapper. Off by default on the servlet.
 */
public class RunServiceTool implements LlmTool {
    static final String NAME = "run_service";
    private static final Map<String, Object> SCHEMA;
    static {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("serviceName", mapOf("type", "string",
                "description", "Moqui service name, e.g. mantle.party.PartyServices.find#Party"));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        props.put("parameters", parameters);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", java.util.Collections.singletonList("serviceName"));
        schema.put("properties", props);
        SCHEMA = java.util.Collections.unmodifiableMap(schema);
    }

    @Override public String getName() { return NAME; }
    @Override public String getDescription() {
        return "Call a Moqui service as the current user (authz ON). "
                + "Prefer /rest/s1 via request when a REST path exists. "
                + "serviceName is package.verb#noun. Do not pass authUsername/authPassword.";
    }
    @Override public Map<String, Object> getParametersSchema() { return SCHEMA; }
    @Override public Execution getExecution() { return Execution.SERVER; }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> arguments, ExecutionContext ec) {
        Map<String, Object> args = arguments != null ? arguments : java.util.Collections.emptyMap();
        String serviceName = str(args.get("serviceName"));
        if (serviceName == null || serviceName.isBlank())
            return error("serviceName is required");
        serviceName = serviceName.trim();
        if (ec == null || ec.getService() == null)
            return error("no ExecutionContext for service call");
        Map<String, Object> in = ServiceCallTool.sanitizeArguments(asMap(args.get("parameters")));
        try {
            Map<String, Object> out = ec.getService().sync().name(serviceName).parameters(in).call();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("serviceName", serviceName);
            result.put("result", out);
            return result;
        } catch (ArtifactAuthorizationException e) {
            return errorStatus(403, e.getMessage());
        } catch (ArtifactTarpitException e) {
            return errorStatus(429, e.getMessage());
        } catch (Throwable t) {
            return error(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        return java.util.Collections.emptyMap();
    }
    static String str(Object o) { return o == null ? null : o.toString(); }
    static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }
    static Map<String, Object> mapOf(String k, Object v, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        m.put(k2, v2);
        return m;
    }
    static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }
    static Map<String, Object> errorStatus(int status, String message) {
        Map<String, Object> m = error(message);
        m.put("status", status);
        return m;
    }
}
