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

import org.moqui.llm.LlmClient;
import org.moqui.llm.LlmConversation;
import org.moqui.llm.LlmTool;

import java.util.List;
import java.util.Map;

/** Named LLM profile facade. Interns pool/protocol per profile, never the fluent client. */
public interface LlmFacade {
    /** New builder each call. Interns pool/protocol, not the builder. */
    LlmClient getDefault();
    LlmClient getClient(String profileName);
    /** Groovy alias of getClient. */
    LlmClient client(String profileName);

    List<String> getProfileNames();
    /** False if disabled or no profile has a url. */
    boolean isEnabled();

    LlmConversation getConversation(String conversationId);
    LlmConversation createConversation(String profileName);
    LlmConversation createConversation(String profileName, Map<String, Object> attributes);

    /** Register a client-side tool type (canonical function name, e.g. write_ui). */
    void registerClientToolType(String name, LlmTool.Factory factory);
}
