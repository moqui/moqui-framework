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

import com.hazelcast.cache.CacheStatistics
import com.hazelcast.cache.ICache
import com.hazelcast.config.CacheConfig
import com.hazelcast.config.EvictionConfig
import com.hazelcast.config.EvictionPolicy
import com.hazelcast.config.InMemoryFormat
import groovy.transform.CompileStatic
import org.moqui.impl.StupidJavaUtilities
import org.moqui.util.MNode

import javax.cache.Cache
import javax.cache.CacheManager
import javax.cache.Caching
import javax.cache.configuration.Configuration
import javax.cache.expiry.AccessedExpiryPolicy
import javax.cache.expiry.CreatedExpiryPolicy
import javax.cache.expiry.Duration
import javax.cache.expiry.EternalExpiryPolicy
import javax.cache.expiry.ExpiryPolicy
import javax.cache.spi.CachingProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

import org.moqui.context.CacheFacade
import org.moqui.impl.StupidUtilities

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.TimeUnit

@CompileStatic
public class CacheFacadeImpl implements CacheFacade {
    protected final static Logger logger = LoggerFactory.getLogger(CacheFacadeImpl.class)

    protected final ExecutionContextFactoryImpl ecfi

    protected final CachingProvider provider
    protected final CacheManager cacheManager

    protected final ConcurrentMap<String, Cache> localCacheMap = new ConcurrentHashMap<>()
    protected final Map<String, Boolean> cacheTenantsShare = new HashMap<String, Boolean>()

    CacheFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        // FUTURE: make provider and conf file configurable in Moqui Conf XML file?
        provider = Caching.getCachingProvider("com.hazelcast.cache.HazelcastCachingProvider")
        cacheManager = provider.getCacheManager()
    }

    void destroy() { cacheManager.close() }

    protected String getFullName(String cacheName, String tenantId) {
        if (cacheName == null) return null
        if (cacheName.contains("__")) return cacheName
        if (isTenantsShare(cacheName)) {
            return cacheName
        } else {
            if (!tenantId) tenantId = ecfi.getEci().getTenantId()
            return tenantId.concat("__").concat(cacheName)
        }
    }
    protected boolean isTenantsShare(String cacheName) {
        Boolean savedVal = cacheTenantsShare.get(cacheName)
        if (savedVal != null) return savedVal.booleanValue()

        MNode cacheElement = getCacheNode(cacheName)
        boolean attrVal = cacheElement?.attribute("tenants-share") == "true"
        cacheTenantsShare.put(cacheName, attrVal)
        return attrVal
    }

    @Override
    void clearAllCaches() { for (Cache cache in localCacheMap.values()) cache.clear() }

    @Override
    void clearCachesByPrefix(String prefix) {
        for (Map.Entry<String, Cache> entry in localCacheMap.entrySet()) {
            String tempName = entry.key
            int separatorIndex = tempName.indexOf("__")
            if (separatorIndex > 0) tempName = tempName.substring(separatorIndex + 2)
            if (!tempName.startsWith(prefix)) continue

            entry.value.clear()
        }
    }

    @Override
    Cache getCache(String cacheName) { return getCache(cacheName, null) }
    @Override
    <K, V> Cache<K, V> getCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        return getCache(cacheName, null)
    }

    Cache getCache(String cacheName, String tenantId) {
        String fullName = getFullName(cacheName, tenantId)
        Cache theCache = localCacheMap.get(fullName)
        if (theCache == null) {
            localCacheMap.putIfAbsent(fullName, initCache(cacheName, tenantId))
            theCache = localCacheMap.get(fullName)
        }
        return theCache
    }

    @Override
    CacheManager getCacheManager() { return cacheManager }

    boolean cacheExists(String cacheName) { return localCacheMap.containsKey(getFullName(cacheName, null)) }
    Set<String> getCacheNames() { return localCacheMap.keySet() }

    List<Map<String, Object>> getAllCachesInfo(String orderByField, String filterRegexp) {
        String tenantId = ecfi.getEci().getTenantId()
        String tenantPrefix = tenantId + "__"
        List<Map<String, Object>> ci = new LinkedList()
        for (String cn in cacheManager.getCacheNames()) {
            if (tenantId != "DEFAULT" && !cn.startsWith(tenantPrefix)) continue
            if (filterRegexp && !cn.matches("(?i).*" + filterRegexp + ".*")) continue
            Cache co = getCache(cn)
            ICache ico = co.unwrap(ICache.class)
            CacheStatistics cs = ico.getLocalCacheStatistics()
            CacheConfig conf = co.getConfiguration(CacheConfig.class)
            EvictionConfig evConf = conf.getEvictionConfig()
            ExpiryPolicy expPol = conf.getExpiryPolicyFactory()?.create()
            Long expireIdle = expPol.expiryForAccess?.durationAmount ?: 0
            Long expireLive = expPol.expiryForCreation?.durationAmount ?: 0
            ci.add([name:co.getName(), expireTimeIdle:expireIdle,
                    expireTimeLive:expireLive, maxElements:evConf.getSize(),
                    evictionStrategy:evConf.getEvictionPolicy().name(), size:ico.size(),
                    getCount:cs.getCacheGets(), putCount:cs.getCachePuts(),
                    hitCount:cs.getCacheHits(), missCountTotal:cs.getCacheMisses(),
                    evictionCount:cs.getCacheEvictions(), removeCount:cs.getCacheRemovals()] as Map<String, Object>)
        }
        if (orderByField) StupidUtilities.orderMapList(ci, [orderByField])
        return ci
    }

    protected MNode getCacheNode(String cacheName) {
        MNode cacheListNode = ecfi.getConfXmlRoot().first("cache-list")
        MNode cacheElement = cacheListNode.first({ MNode it -> it.name == "cache" && it.attribute("name") == cacheName })
        // nothing found? try starts with, ie allow the cache configuration to be a prefix
        if (cacheElement == null) cacheElement = cacheListNode
                .first({ MNode it -> it.name == "cache" && cacheName.startsWith(it.attribute("name")) })
        return cacheElement
    }

    protected synchronized Cache initCache(String cacheName, String tenantId) {
        if (cacheName.contains("__")) cacheName = cacheName.substring(cacheName.indexOf("__") + 2)
        String fullCacheName = getFullName(cacheName, tenantId)
        if (localCacheMap.containsKey(fullCacheName)) return localCacheMap.get(fullCacheName)

        // Configuration is a javax.cache interface, though actual config directly in Ehcache objects as some options
        //     like resource pools cannot be configured through javax.cache interfaces
        Configuration configuration

        MNode cacheNode = getCacheNode(cacheName)
        if (cacheNode != null) {
            String keyTypeName = cacheNode.attribute("key-type") ?: "String"
            String valueTypeName = cacheNode.attribute("value-type") ?: "Object"
            Class keyType = StupidJavaUtilities.getClass(keyTypeName)
            Class valueType = StupidJavaUtilities.getClass(valueTypeName)

            CacheConfig cacheConfig = new CacheConfig()
            cacheConfig.setTypes(keyType, valueType)
            cacheConfig.setStoreByValue(false).setStatisticsEnabled(true).setManagementEnabled(false)
            cacheConfig.setName(fullCacheName)
            // cacheConfig.setAsyncBackupCount(0).setBackupCount(0).setInMemoryFormat(InMemoryFormat.OBJECT)
            // cacheConfig.setReadThrough(false).setWriteThrough(false)




            if (cacheNode.attribute("expire-time-idle") && cacheNode.attribute("expire-time-idle") != "0") {
                cacheConfig.setExpiryPolicyFactory(AccessedExpiryPolicy.factoryOf(
                        new Duration(TimeUnit.SECONDS, Long.parseLong(cacheNode.attribute("expire-time-idle")))))
            } else if (cacheNode.attribute("expire-time-live") && cacheNode.attribute("expire-time-live") != "0") {
                cacheConfig.setExpiryPolicyFactory(CreatedExpiryPolicy.factoryOf(
                        new Duration(TimeUnit.SECONDS, Long.parseLong(cacheNode.attribute("expire-time-live")))))
            } else {
                cacheConfig.setExpiryPolicyFactory(EternalExpiryPolicy.factoryOf())
            }

            String maxElementsStr = cacheNode.attribute("max-elements")
            if (maxElementsStr && maxElementsStr != "0") {
                int maxElements = Integer.parseInt(cacheNode.attribute("max-elements"))
                EvictionPolicy ep = cacheNode.attribute("eviction-strategy") == "least-recently-used" ? EvictionPolicy.LRU : EvictionPolicy.LFU
                EvictionConfig evictionConfig = new EvictionConfig(maxElements, EvictionConfig.MaxSizePolicy.ENTRY_COUNT, ep)
                cacheConfig.setEvictionConfig(evictionConfig)
            }

            configuration = cacheConfig
        } else {
            CacheConfig cacheConfig = new CacheConfig()
            // any defaults we want here? better to use underlying defaults and conf file settings only
            configuration = cacheConfig
        }

        Cache newCache = cacheManager.createCache(cacheName, configuration)

        return newCache
    }
}
