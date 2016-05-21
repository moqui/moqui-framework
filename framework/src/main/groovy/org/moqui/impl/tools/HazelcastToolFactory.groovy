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

import com.hazelcast.config.Config
import com.hazelcast.config.XmlConfigBuilder
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** ElasticSearch Client is used for indexing and searching documents */
class HazelcastToolFactory implements ToolFactory<HazelcastInstance> {
    protected final static Logger logger = LoggerFactory.getLogger(HazelcastToolFactory.class)
    final static String TOOL_NAME = "Hazelcast"

    protected ExecutionContextFactory ecf = null

    /** Hazelcast Instance */
    protected HazelcastInstance hazelcastInstance = null

    /** Default empty constructor */
    HazelcastToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }
    @Override
    void init(ExecutionContextFactory ecf) { }
    @Override
    void preFacadeInit(ExecutionContextFactory ecf) {
        this.ecf = ecf

        // initialize Hazelcast using hazelcast.xml on the classpath for config unless there is a hazelcast.config system property
        Config hzConfig
        if (System.getProperty("hazelcast.config")) {
            logger.info("Starting Hazelcast with hazelcast.config system property (${System.getProperty("hazelcast.config")})")
            hzConfig = new Config("moqui")
        } else {
            logger.info("Starting Hazelcast with hazelcast.xml from classpath")
            hzConfig = new XmlConfigBuilder(Thread.currentThread().getContextClassLoader().getResourceAsStream("hazelcast.xml")).build()
            hzConfig.setInstanceName("moqui")
        }
        hazelcastInstance = Hazelcast.getOrCreateHazelcastInstance(hzConfig)
    }

    @Override
    HazelcastInstance getInstance() {
        if (hazelcastInstance == null) throw new IllegalStateException("HazelcastToolFactory not initialized")
        return hazelcastInstance
    }

    @Override
    void destroy() {
        // shutdown Hazelcast
        Hazelcast.shutdownAll()
        // the above may be better than this: if (hazelcastInstance != null) hazelcastInstance.shutdown()
        logger.info("Hazelcast shutdown")
    }

    ExecutionContextFactory getEcf() { return ecf }
}
