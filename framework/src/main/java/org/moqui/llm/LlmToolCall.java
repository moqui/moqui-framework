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

public final class LlmToolCall {
    public String id;
    public String name;
    /** Provider JSON arguments string (may be empty/invalid JSON). */
    public String arguments;
    /** Set when yielding client tools; not sent by the provider. */
    public LlmTool.Execution execution;

    public LlmToolCall() { }
    public LlmToolCall(String id, String name, String arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getArguments() { return arguments; }
    public LlmTool.Execution getExecution() { return execution; }

    public LlmToolCall copy() {
        LlmToolCall c = new LlmToolCall(id, name, arguments);
        c.execution = execution;
        return c;
    }
}
