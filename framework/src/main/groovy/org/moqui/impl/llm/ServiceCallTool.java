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

import org.moqui.Moqui;
import org.moqui.context.ExecutionContext;
import org.moqui.impl.service.ServiceDefinition;
import org.moqui.impl.service.ServiceFacadeImpl;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.moqui.impl.util.RestSchemaUtil;
import org.moqui.llm.LlmTool;
import org.moqui.service.ServiceFacade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Optional typed SERVER tool wrapping a Moqui service. Not the servlet default.
 * Function names: {@code s_} + escape {@code _}→{@code __}, {@code .}→{@code _p}, {@code #}→{@code _n}.
 */
public class ServiceCallTool implements LlmTool {
    private static final Pattern ALIAS = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final int MAX_NAME = 64;
    /** Copy of EntityAutoServiceRunner.otherFieldsToSkip (Groovy field not visible to javac stubs). */
    private static final Set<String> SKIP_FIELDS =
            new HashSet<>(Arrays.asList("ec", "_entity", "authUsername", "authPassword"));

    private final String serviceName;
    private final String functionName;
    private final Map<String, Object> schema;
    private final String description;

    public ServiceCallTool(String serviceName, String functionName) {
        if (serviceName == null || serviceName.isBlank())
            throw new IllegalArgumentException("serviceName is required");
        this.serviceName = serviceName;
        if (functionName != null && !functionName.isBlank()) {
            if (!ALIAS.matcher(functionName).matches())
                throw new IllegalArgumentException("function name must match [a-zA-Z0-9_-]{1,64}: " + functionName);
            this.functionName = functionName;
        } else {
            String encoded = encodeFunctionName(serviceName);
            if (encoded.length() > MAX_NAME)
                throw new IllegalArgumentException("encoded service name exceeds 64 characters; pass an explicit alias: "
                        + serviceName);
            this.functionName = encoded;
        }
        ServiceDefinition sd = lookupService(serviceName);
        this.schema = schemaFor(sd);
        this.description = "Call Moqui service " + serviceName;
    }

    public static String encodeFunctionName(String serviceName) {
        if (serviceName == null) return "s_";
        StringBuilder sb = new StringBuilder("s_");
        for (int i = 0; i < serviceName.length(); i++) {
            char c = serviceName.charAt(i);
            if (c == '_') sb.append("__");
            else if (c == '.') sb.append("_p");
            else if (c == '#') sb.append("_n");
            else sb.append(c);
        }
        return sb.toString();
    }

    static ServiceDefinition lookupService(String serviceName) {
        try {
            if (Moqui.getExecutionContextFactory() == null) return null;
            ExecutionContext ec = Moqui.getExecutionContext();
            if (ec == null || ec.getService() == null) return null;
            ServiceFacade sf = ec.getService();
            if (!(sf instanceof ServiceFacadeImpl)) return null;
            ServiceFacadeImpl sfi = (ServiceFacadeImpl) sf;
            if (!sfi.isServiceDefined(serviceName))
                throw new IllegalArgumentException("Service not found: " + serviceName);
            return sfi.getServiceDefinition(serviceName);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> schemaFor(ServiceDefinition sd) {
        if (sd == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("type", "object");
            empty.put("properties", new LinkedHashMap<>());
            return empty;
        }
        Map<String, Object> schema = RestSchemaUtil.getJsonSchemaMapIn(sd);
        if (schema == null) schema = new LinkedHashMap<>();
        Object propsObj = schema.get("properties");
        if (propsObj instanceof Map) {
            Map<String, Object> props = new LinkedHashMap<>((Map<String, Object>) propsObj);
            for (String skip : SKIP_FIELDS) props.remove(skip);
            schema.put("properties", props);
        }
        Object reqObj = schema.get("required");
        if (reqObj instanceof List) {
            List<String> req = new ArrayList<>();
            for (Object r : (List<?>) reqObj) {
                if (r != null && !SKIP_FIELDS.contains(r.toString()))
                    req.add(r.toString());
            }
            if (req.isEmpty()) schema.remove("required");
            else schema.put("required", req);
        }
        return schema;
    }

    @Override public String getName() { return functionName; }
    @Override public String getDescription() { return description; }
    @Override public Map<String, Object> getParametersSchema() { return schema; }
    @Override public Execution getExecution() { return Execution.SERVER; }

    @Override
    public Object execute(Map<String, Object> arguments, ExecutionContext ec) {
        if (ec == null || ec.getService() == null)
            return error("no ExecutionContext for service call");
        Map<String, Object> in = arguments != null ? arguments : Collections.emptyMap();
        return ec.getService().sync().name(serviceName).parameters(in).call();
    }

    static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }
}
