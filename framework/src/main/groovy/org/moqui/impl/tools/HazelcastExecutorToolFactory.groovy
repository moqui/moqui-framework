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
package org.moqui.impl.tools

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.IExecutorService
import groovy.transform.CompileStatic
import org.moqui.BaseException
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ExecutorService

/** A factory for getting a ExecutorService (actually Hazelcast IExecutorService) */
@CompileStatic
class HazelcastExecutorToolFactory implements ToolFactory<ExecutorService> {
    protected final static Logger logger = LoggerFactory.getLogger(HazelcastExecutorToolFactory.class)
    final static String TOOL_NAME = "HazelcastExecutor"

    protected ExecutionContextFactory ecf = null

    /** Hazelcast Instance */
    protected HazelcastInstance hazelcastInstance = null
    protected IExecutorService executorService = null

    /** Default empty constructor */
    HazelcastExecutorToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }
    @Override
    void init(ExecutionContextFactory ecf) { }
    @Override
    void preFacadeInit(ExecutionContextFactory ecf) {
        this.ecf = ecf

        ToolFactory<HazelcastInstance> hzToolFactory = ecf.getToolFactory(HazelcastToolFactory.TOOL_NAME)
        if (hzToolFactory == null) {
            throw new BaseException("HazelcastToolFactory not in place, cannot use HazelcastExecutorToolFactory")
        } else {
            HazelcastInstance hazelcastInstance = hzToolFactory.getInstance()
            executorService = hazelcastInstance.getExecutorService("service-executor")
        }
    }

    @Override
    ExecutorService getInstance() {
        if (executorService == null) throw new IllegalStateException("HazelcastExecutorToolFactory not initialized")
        return executorService
    }

    @Override
    void destroy() {
        // do nothing, Hazelcast shutdown in HazelcastToolFactory
    }

    ExecutionContextFactory getEcf() { return ecf }
}
