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
package org.moqui.llm.a2a;

import java.util.Map;

/**
 * A2A 1.0 operations for Moqui components and services, in wire shapes (A2A 1.0 ProtoJSON maps), for the
 * authenticated user of the current ExecutionContext. Transport-neutral: no servlet, entity or transaction types
 * cross this boundary. Streaming (SendStreamingMessage, SubscribeToTask) stays with the transport bindings for now.
 */
public interface A2AFacade {
    /** False unless a2a_enabled is true; the HTTP endpoints answer 404 while it is false. */
    boolean isEnabled();

    /** LLM profile used for A2A work when a request does not name one (a2a_default_profile, default assist). */
    String getDefaultProfileName();

    /** SendMessage: runs one turn for a new or resumed task; returns {task}. */
    Map<String, Object> sendMessage(Map<String, Object> request);

    /** GetTask: the Task, with at most historyLength messages (null for the default). */
    Map<String, Object> getTask(String taskId, Integer historyLength);

    /** ListTasks: {tasks, nextPageToken, pageSize, totalSize} for the current user. */
    Map<String, Object> listTasks(Map<String, Object> request);

    /** CancelTask: cancels a non-terminal task and returns it. */
    Map<String, Object> cancelTask(String taskId, Integer historyLength);

    /** Task snapshot plus the persisted StreamResponse events after afterSequence. */
    Map<String, Object> subscribeTask(String taskId, Long afterSequence, Integer historyLength);

    /** Authenticated extended AgentCard, built from the skills this user can see. */
    Map<String, Object> getExtendedAgentCard(Map<String, Object> options);
}
