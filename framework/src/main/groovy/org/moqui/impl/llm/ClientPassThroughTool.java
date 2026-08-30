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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Generic CLIENT tool: never executed on the server; yielded to the caller. */
public class ClientPassThroughTool implements LlmTool {
    private final String name;
    private final String description;
    private final Map<String, Object> schema;

    public ClientPassThroughTool(String name, String description, Map<String, Object> jsonSchema) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("client tool name is required");
        this.name = name;
        this.description = description != null ? description : name;
        this.schema = jsonSchema != null ? jsonSchema : Collections.emptyMap();
    }

    @Override public String getName() { return name; }
    @Override public String getDescription() { return description; }
    @Override public Map<String, Object> getParametersSchema() { return schema; }
    @Override public Execution getExecution() { return Execution.CLIENT; }

    @Override
    public Object execute(Map<String, Object> arguments, ExecutionContext ec) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "client tool not executed on the server: " + name);
        return err;
    }
}
