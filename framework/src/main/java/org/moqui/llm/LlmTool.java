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
package org.moqui.llm;

import org.moqui.context.ExecutionContext;

import java.util.Map;

public interface LlmTool {
    String getName();
    String getDescription();
    Map<String, Object> getParametersSchema();
    Execution getExecution();
    enum Execution { SERVER, CLIENT }

    Object execute(Map<String, Object> arguments, ExecutionContext ec);

    default Map<String, Object> enrichForClient(Map<String, Object> arguments, ExecutionContext ec) {
        return arguments;
    }

    interface Factory {
        LlmTool create();
    }
}
