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

import com.hazelcast.cache.HazelcastCachingProvider
import com.hazelcast.core.HazelcastInstance
import groovy.transform.CompileStatic
import org.moqui.BaseException
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.CacheManager
import javax.cache.Caching
import javax.cache.spi.CachingProvider

/** A factory for getting a Hazelcast CacheManager */
@CompileStatic
class HazelcastCacheToolFactory implements ToolFactory<CacheManager> {
    protected final static Logger logger = LoggerFactory.getLogger(HazelcastCacheToolFactory.class)
    final static String TOOL_NAME = "HazelcastCache"

    protected ExecutionContextFactory ecf = null

    /** Hazelcast Instance */
    protected HazelcastInstance hazelcastInstance = null
    protected CacheManager cacheManager = null

    /** Default empty constructor */
    HazelcastCacheToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }
    @Override
    void init(ExecutionContextFactory ecf) { }
    @Override
    void preFacadeInit(ExecutionContextFactory ecf) {
        this.ecf = ecf

        ToolFactory<HazelcastInstance> hzToolFactory = ecf.getToolFactory(HazelcastToolFactory.TOOL_NAME)
        if (hzToolFactory == null) {
            throw new BaseException("Tried to create distributed cache but could not find Hazelcast ToolFactory")
        } else {
            HazelcastInstance hci = hzToolFactory.getInstance()
            Properties properties = new Properties()
            properties.setProperty(HazelcastCachingProvider.HAZELCAST_INSTANCE_NAME, hci.getName())
            // always use the server caching provider, the client one always goes over a network interface and is slow
            CachingProvider hcProviderInternal = Caching.getCachingProvider("com.hazelcast.cache.impl.HazelcastServerCachingProvider")
            // hcProviderInternal = Caching.getCachingProvider("com.hazelcast.cache.HazelcastCachingProvider")
            cacheManager = hcProviderInternal.getCacheManager(new URI("moqui-cache-manager"), null, properties)
            logger.info("Initialized Hazelcast CacheManager for instance ${hci.getName()}")
        }
    }

    @Override
    CacheManager getInstance() {
        if (cacheManager == null) throw new IllegalStateException("HazelcastCacheToolFactory not initialized")
        return cacheManager
    }

    @Override
    void destroy() {
        // do nothing, Hazelcast shutdown in HazelcastToolFactory
    }

    ExecutionContextFactory getEcf() { return ecf }
}
