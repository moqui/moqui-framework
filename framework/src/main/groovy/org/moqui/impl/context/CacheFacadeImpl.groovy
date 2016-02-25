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
import org.moqui.util.MNode

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

@CompileStatic
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

    protected String getFullName(String cacheName, String tenantId) {
        if (cacheName == null) return null
        if (cacheName.contains("__")) return cacheName
        MNode cacheElement = getCacheNode(cacheName)
        if (cacheElement?.attribute("tenants-share") == "true") {
            return cacheName
        } else {
            if (!tenantId) tenantId = ecfi.getEci().getTenantId()
            return tenantId.concat("__").concat(cacheName)
        }
    }
    protected MNode getCacheNode(String cacheName) {
        MNode cacheListNode = ecfi.getConfXmlRoot().first("cache-list")
        MNode cacheElement = cacheListNode.first({ MNode it -> it.name == "cache" && it.attribute("name") == cacheName })
        // nothing found? try starts with, ie allow the cache configuration to be a prefix
        if (cacheElement == null) cacheElement = cacheListNode
                .first({ MNode it -> it.name == "cache" && cacheName.startsWith(it.attribute("name")) })
        return cacheElement
    }

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
    void clearCachesByPrefix(String prefix) { cacheManager.clearAllStartingWith(getFullName(prefix, null)) }

    @Override
    Cache getCache(String cacheName) { return getCacheImpl(cacheName, null) }

    CacheImpl getCacheImpl(String cacheName, String tenantId) {
        String fullName = getFullName(cacheName, tenantId)
        CacheImpl theCache = localCacheImplMap.get(fullName)
        if (theCache == null) {
            localCacheImplMap.putIfAbsent(fullName, new CacheImpl(initCache(cacheName, tenantId)))
            theCache = localCacheImplMap.get(fullName)
        }
        return theCache
    }

    boolean cacheExists(String cacheName) { return cacheManager.cacheExists(getFullName(cacheName, null)) }
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
                    removeCount:co.getRemoveCount()] as Map<String, Object>)
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


    protected synchronized net.sf.ehcache.Cache initCache(String cacheName, String tenantId) {
        if (cacheName.contains("__")) cacheName = cacheName.substring(cacheName.indexOf("__") + 2)
        String fullCacheName = getFullName(cacheName, tenantId)
        if (cacheManager.cacheExists(fullCacheName)) return cacheManager.getCache(fullCacheName)

        // make a cache with the default settings from ehcache.xml
        cacheManager.addCacheIfAbsent(fullCacheName)
        net.sf.ehcache.Cache newCache = cacheManager.getCache(fullCacheName)
        // not supported in 2.7.2: newCache.setSampledStatisticsEnabled(true)

        // set any applicable settings from the moqui conf xml file
        CacheConfiguration newCacheConf = newCache.getCacheConfiguration()
        MNode cacheElement = getCacheNode(cacheName)

        boolean eternal = true
        if (cacheElement?.attribute("expire-time-idle")) {
            newCacheConf.setTimeToIdleSeconds(Long.valueOf(cacheElement.attribute("expire-time-idle")))
            eternal = false
        }
        if (cacheElement?.attribute("expire-time-live")) {
            newCacheConf.setTimeToLiveSeconds(Long.valueOf(cacheElement.attribute("expire-time-live")))
            eternal = false
        }
        newCacheConf.setEternal(eternal)

        if (cacheElement?.attribute("max-elements")) {
            newCacheConf.setMaxEntriesLocalHeap(Integer.valueOf(cacheElement.attribute("max-elements")))
        }
        String evictionStrategy = cacheElement?.attribute("eviction-strategy")
        if (evictionStrategy) {
            if ("least-recently-used" == evictionStrategy) {
                newCacheConf.setMemoryStoreEvictionPolicyFromObject(MemoryStoreEvictionPolicy.LRU)
            } else if ("least-frequently-used" == evictionStrategy) {
                newCacheConf.setMemoryStoreEvictionPolicyFromObject(MemoryStoreEvictionPolicy.LFU)
            } else if ("least-recently-added" == evictionStrategy) {
                newCacheConf.setMemoryStoreEvictionPolicyFromObject(MemoryStoreEvictionPolicy.FIFO)
            }
        }

        if (logger.isTraceEnabled()) logger.trace("Initialized new cache [${fullCacheName}]")
        return newCache
    }
}
