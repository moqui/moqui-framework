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

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.CacheManager
import javax.cache.Caching
import javax.cache.spi.CachingProvider

/** A factory for getting a JCS CacheManager; this has no compile time dependency on Commons JCS, just add the jar files
 * Current artifact: org.apache.commons:commons-jcs-jcache:2.0-beta-1
 */
@CompileStatic
class JCSCacheToolFactory implements ToolFactory<CacheManager> {
    protected final static Logger logger = LoggerFactory.getLogger(JCSCacheToolFactory.class)
    final static String TOOL_NAME = "JCSCache"

    protected ExecutionContextFactory ecf = null

    protected CacheManager cacheManager = null

    /** Default empty constructor */
    JCSCacheToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }
    @Override
    void init(ExecutionContextFactory ecf) { }
    @Override
    void preFacadeInit(ExecutionContextFactory ecf) {
        this.ecf = ecf
        // always use the server caching provider, the client one always goes over a network interface and is slow
        ClassLoader cl = Thread.currentThread().getContextClassLoader()
        CachingProvider providerInternal = Caching.getCachingProvider("org.apache.commons.jcs.jcache.JCSCachingProvider", cl)
        URL cmUrl = cl.getResource("cache.ccf")
        logger.info("JCS config URI: ${cmUrl}")
        cacheManager = providerInternal.getCacheManager(cmUrl.toURI(), cl)
        logger.info("Initialized JCS CacheManager")
    }

    @Override
    CacheManager getInstance(Object... parameters) {
        if (cacheManager == null) throw new IllegalStateException("JCSCacheToolFactory not initialized")
        return cacheManager
    }

    @Override
    void destroy() {
        // do nothing?
    }

    ExecutionContextFactory getEcf() { return ecf }
}
