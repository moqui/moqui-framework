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

import java.util.LinkedHashMap;
import java.util.Map;

public final class LlmToolCall {
    public String id;
    public String name;
    /** Provider JSON arguments string (may be empty/invalid JSON). */
    public String arguments;
    /** Set when yielding client tools; not sent by the provider. */
    public LlmTool.Execution execution;
    /**
     * World-rim {@code request} write or {@code run_service} waiting for a click.
     * The server runs the original arguments only after resume content {@code {confirmed:true}}.
     */
    public Boolean confirm;
    /** Skill risk that required the click: {@code confirm} or {@code irreversible}. */
    public String risk;
    /**
     * Provider fields other than id, type, and function (Gemini thought signature under extra_content).
     * Persisted. Not copied onto the browser tool-call payload.
     */
    public Map<String, Object> providerFields;

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
    public Boolean getConfirm() { return confirm; }
    public String getRisk() { return risk; }
    public Map<String, Object> getProviderFields() { return providerFields; }
    public void setProviderFields(Map<String, Object> providerFields) { this.providerFields = providerFields; }

    public LlmToolCall copy() {
        LlmToolCall c = new LlmToolCall(id, name, arguments);
        c.execution = execution;
        c.confirm = confirm;
        c.risk = risk;
        if (providerFields != null) c.providerFields = new LinkedHashMap<>(providerFields);
        return c;
    }
}
