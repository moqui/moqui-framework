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
package org.moqui.context;

import java.util.List;
import java.util.Map;

/** A facade for AI/LLM operations.
 *
 * Configure models in MoquiConf XML under the ai-facade element with model-config children.
 */
public interface AiFacade {

    /** Get the client for the default model config (as set by ai-facade.@default-config). */
    AiClient getDefault();

    /** Get a client for a named model config as defined in ai-facade.model-config.@name. */
    AiClient getConfig(String name);

    interface AiClient {

        /** Send a list of chat messages and return the model's reply text.
         *
         * @param messages List of message Maps, each with "role" (e.g. "user", "assistant", "system")
         *                 and "content" (String) keys.
         * @return The model's reply as a plain String.
         */
        String generate(List<Map> messages);

        /** Send a list of chat messages and request a structured response conforming to the given schema.
         *
         * <p>Schema format uses JSON Schema vocabulary. Example:
         * <pre>
         * [status:    [type: "string"],
         *  amount:    [type: "number"],
         *  quantity:  [type: "integer"],
         *  approved:  [type: "boolean"],
         *  lineItems: [type: "array", items: [
         *      productId: [type: "string"],
         *      qty:       [type: "integer"]]]]
         * </pre>
         * Supported scalar types: {@code string}, {@code number}, {@code integer}, {@code boolean}.
         * Structural types: {@code array} (with {@code items} map), {@code object} (with {@code properties} map).
         *
         * @param messages List of message Maps, each with "role" and "content" keys.
         * @param schema   JSON Schema vocabulary Map: field names as keys, each value a Map
         *                 with a "type" key and optional "items" (array) or "properties" (object).
         * @return The model's response parsed into a Map matching the provided schema.
         */
        Map generateStructured(List<Map> messages, Map schema);
    }
}
