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
package org.moqui.impl.context

import groovy.transform.CompileStatic

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

import static org.moqui.context.Cache.EvictionStrategy.*

import org.moqui.context.CacheFacade
import org.moqui.context.Cache
import org.moqui.context.Cache.EvictionStrategy
import org.moqui.impl.StupidUtilities

import net.sf.ehcache.CacheManager
import net.sf.ehcache.Ehcache
import net.sf.ehcache.config.CacheConfiguration
import net.sf.ehcache.store.MemoryStoreEvictionPolicy
import org.slf4j.Logger
import org.slf4j.LoggerFactory

public class CacheFacadeImpl implements CacheFacade {
    protected final static Logger logger = LoggerFactory.getLogger(CacheFacadeImpl.class)

    protected final ExecutionContextFactoryImpl ecfi
    
    /** This is the Ehcache CacheManager singleton for use in Moqui.
     * Gets config from the default location, ie the ehcache.xml file from the classpath.
     */
    protected final CacheManager cacheManager

    protected final ConcurrentMap<String, CacheImpl> localCacheImplMap = new ConcurrentHashMap<String, CacheImpl>()

    CacheFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        cacheManager = CacheManager.create()
    }

    void destroy() { cacheManager.shutdown() }

    @Override
    void clearAllCaches() { cacheManager.clearAll() }

    @Override
    void clearExpiredFromAllCaches() {
        List<String> cacheNames = Arrays.asList(cacheManager.getCacheNames())
        for (String cacheName in cacheNames) {
            Ehcache ehcache = cacheManager.getEhcache(cacheName)
            ehcache.evictExpiredElements()
        }
    }

    @Override
    void clearCachesByPrefix(String prefix) { cacheManager.clearAllStartingWith(prefix) }

    @Override
    @CompileStatic
    Cache getCache(String cacheName) { return getCacheImpl(cacheName) }

    @CompileStatic
    CacheImpl getCacheImpl(String cacheName) {
        CacheImpl theCache = localCacheImplMap.get(cacheName)
        if (theCache == null) {
            localCacheImplMap.putIfAbsent(cacheName, new CacheImpl(initCache(cacheName)))
            theCache = localCacheImplMap.get(cacheName)
        }
        return theCache
    }

    @CompileStatic
    boolean cacheExists(String cacheName) { return cacheManager.cacheExists(cacheName) }
    String[] getCacheNames() { return cacheManager.getCacheNames() }

    List<Map<String, Object>> getAllCachesInfo(String orderByField) {
        List<Map<String, Object>> ci = new LinkedList()
        for (String cn in cacheManager.getCacheNames()) {
            Cache co = getCache(cn)
            ci.add([name:co.getName(), expireTimeIdle:co.getExpireTimeIdle(),
                    expireTimeLive:co.getExpireTimeLive(), maxElements:co.getMaxElements(),
                    evictionStrategy:getEvictionStrategyString(co.evictionStrategy), size:co.size(),
                    hitCount:co.getHitCount(), missCountNotFound:co.getMissCountNotFound(),
                    missCountExpired:co.getMissCountExpired(), missCountTotal:co.getMissCountTotal(),
                    removeCount:co.getRemoveCount()])
        }
        if (orderByField) StupidUtilities.orderMapList(ci, [orderByField])
        return ci
    }

    static String getEvictionStrategyString(EvictionStrategy es) {
        switch (es) {
            case LEAST_RECENTLY_USED: return "LRU"
            case LEAST_RECENTLY_ADDED: return "LRA"
            case LEAST_FREQUENTLY_USED: return "LFU"
        }
    }


    protected synchronized net.sf.ehcache.Cache initCache(String cacheName) {
        if (cacheManager.cacheExists(cacheName)) return cacheManager.getCache(cacheName)

        // make a cache with the default settings from ehcache.xml
        cacheManager.addCacheIfAbsent(cacheName)
        net.sf.ehcache.Cache newCache = cacheManager.getCache(cacheName)
        // not supported in 2.7.2: newCache.setSampledStatisticsEnabled(true)

        // set any applicable settings from the moqui conf xml file
        CacheConfiguration newCacheConf = newCache.getCacheConfiguration()
        Node confXmlRoot = this.ecfi.getConfXmlRoot()
        Node cacheElement = (Node) confXmlRoot."cache-list".cache.find({ it."@name" == cacheName })
        // nothing found? try starts with, ie allow the cache configuration to be a prefix
        if (cacheElement == null) cacheElement = (Node) confXmlRoot."cache-list".cache.find({ cacheName.startsWith(it."@name") })

        boolean eternal = true
        if (cacheElement?."@expire-time-idle") {
            newCacheConf.setTimeToIdleSeconds(Long.valueOf((String) cacheElement."@expire-time-idle"))
            eternal = false
        }
        if (cacheElement?."@expire-time-live") {
            newCacheConf.setTimeToLiveSeconds(Long.valueOf((String) cacheElement."@expire-time-live"))
            eternal = false
        }
        newCacheConf.setEternal(eternal)

        if (cacheElement?."@max-elements") {
            newCacheConf.setMaxEntriesLocalHeap(Integer.valueOf((String) cacheElement."@max-elements"))
        }
        String evictionStrategy = cacheElement?."@eviction-strategy"
        if (evictionStrategy) {
            if ("least-recently-used" == evictionStrategy) {
                newCacheConf.setMemoryStoreEvictionPolicyFromObject(MemoryStoreEvictionPolicy.LRU)
            } else if ("least-frequently-used" == evictionStrategy) {
                newCacheConf.setMemoryStoreEvictionPolicyFromObject(MemoryStoreEvictionPolicy.LFU)
            } else if ("least-recently-added" == evictionStrategy) {
                newCacheConf.setMemoryStoreEvictionPolicyFromObject(MemoryStoreEvictionPolicy.FIFO)
            }
        }

        if (logger.isTraceEnabled()) logger.trace("Initialized new cache [${cacheName}]")
        return newCache
    }
}
