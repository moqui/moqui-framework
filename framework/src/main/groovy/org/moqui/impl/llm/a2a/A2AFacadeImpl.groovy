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
package org.moqui.impl.llm.a2a

import groovy.transform.CompileStatic
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.llm.a2a.A2AFacade

/** Public A2A entry point: resolves the current ExecutionContext and delegates to A2AGateway. No logic here. */
@CompileStatic
class A2AFacadeImpl implements A2AFacade {
    private final ExecutionContextFactoryImpl ecfi

    A2AFacadeImpl(ExecutionContextFactoryImpl ecfi) { this.ecfi = ecfi }

    @Override
    boolean isEnabled() { A2ACardBuilderImpl.enabled() }

    @Override
    String getDefaultProfileName() { A2ATypes.defaultProfile() }

    @Override
    Map<String, Object> sendMessage(Map<String, Object> request) {
        A2AGateway.sendMessage(ecfi.getEci(), request)
    }

    @Override
    Map<String, Object> getTask(String taskId, Integer historyLength) {
        A2AGateway.getTask(ecfi.getEci(), [taskId: taskId, historyLength: historyLength] as Map<String, Object>)
    }

    @Override
    Map<String, Object> listTasks(Map<String, Object> request) {
        A2AGateway.listTasks(ecfi.getEci(), request)
    }

    @Override
    Map<String, Object> cancelTask(String taskId, Integer historyLength) {
        A2AGateway.cancelTask(ecfi.getEci(), [taskId: taskId, historyLength: historyLength] as Map<String, Object>)
    }

    @Override
    Map<String, Object> subscribeTask(String taskId, Long afterSequence, Integer historyLength) {
        A2AGateway.subscribeTask(ecfi.getEci(), [taskId: taskId, afterOrdinal: afterSequence,
                historyLength: historyLength] as Map<String, Object>)
    }

    @Override
    Map<String, Object> getExtendedAgentCard(Map<String, Object> options) {
        A2AGateway.getExtendedAgentCard(ecfi.getEci(), options ?: new LinkedHashMap<String, Object>())
    }
}
