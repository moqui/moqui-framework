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
import com.hazelcast.cache.impl.AbstractHazelcastCacheManager
import com.hazelcast.config.CacheConfig
import com.hazelcast.config.EvictionConfig
import com.hazelcast.config.EvictionPolicy
import com.hazelcast.config.InMemoryFormat
import groovy.transform.CompileStatic
import org.apache.commons.jcs.jcache.JCSCachingManager
import org.moqui.impl.StupidJavaUtilities
import org.moqui.impl.tools.HazelcastCacheToolFactory
import org.moqui.jcache.MCache
import org.moqui.jcache.MCacheConfiguration
import org.moqui.jcache.MCacheManager
import org.moqui.jcache.MCacheToolFactory
import org.moqui.jcache.MEntry
import org.moqui.jcache.MStats
import org.moqui.util.MNode

import javax.cache.Cache
import javax.cache.CacheManager
import javax.cache.configuration.Configuration
import javax.cache.configuration.Factory
import javax.cache.configuration.MutableConfiguration
import javax.cache.expiry.AccessedExpiryPolicy
import javax.cache.expiry.CreatedExpiryPolicy
import javax.cache.expiry.Duration
import javax.cache.expiry.EternalExpiryPolicy
import javax.cache.expiry.ExpiryPolicy
import java.sql.Timestamp
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

    protected CacheManager localCacheManagerInternal = (CacheManager) null
    protected CacheManager distCacheManagerInternal = (CacheManager) null

    protected final ConcurrentMap<String, Cache> localCacheMap = new ConcurrentHashMap<>()
    protected final Map<String, Boolean> cacheTenantsShare = new HashMap<String, Boolean>()

    CacheFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi

        MNode cacheListNode = ecfi.getConfXmlRoot().first("cache-list")
        String localCacheFactoryName = cacheListNode.attribute("local-factory") ?: MCacheToolFactory.TOOL_NAME
        localCacheManagerInternal = ecfi.getTool(localCacheFactoryName, CacheManager.class)
    }

    CacheManager getDistCacheManager() {
        if (distCacheManagerInternal == null) {
            MNode cacheListNode = ecfi.getConfXmlRoot().first("cache-list")
            String distCacheFactoryName = cacheListNode.attribute("distributed-factory") ?: HazelcastCacheToolFactory.TOOL_NAME
            distCacheManagerInternal = ecfi.getTool(distCacheFactoryName, CacheManager.class)
        }
        return distCacheManagerInternal
    }

    void destroy() {
        // no need to do this, ECFI does Hazelcast shutdown: hcCacheManager.close()
    }

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
    Cache getCache(String cacheName) { return getCacheInternal(cacheName, null, "local") }
    @Override
    <K, V> Cache<K, V> getCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        return getCacheInternal(cacheName, null, "local")
    }

    @Override
    Cache getCache(String cacheName, String tenantId) {
        return getCacheInternal(cacheName, tenantId, "local")
    }
    @Override
    MCache getLocalCache(String cacheName) {
        return getCacheInternal(cacheName, null, "local").unwrap(MCache.class)
    }
    @Override
    Cache getDistributedCache(String cacheName) {
        return getCacheInternal(cacheName, null, "distributed").unwrap(ICache.class)
    }

    Cache getCacheInternal(String cacheName, String tenantId, String defaultCacheType) {
        String fullName = getFullName(cacheName, tenantId)
        Cache theCache = localCacheMap.get(fullName)
        if (theCache == null) {
            localCacheMap.putIfAbsent(fullName, initCache(cacheName, tenantId, defaultCacheType))
            theCache = localCacheMap.get(fullName)
        }
        return theCache
    }

    @Override
    void registerCache(Cache cache) {
        String cacheName = cache.getName()
        localCacheMap.putIfAbsent(cacheName, cache)
    }

    @Override
    boolean cacheExists(String cacheName) { return localCacheMap.containsKey(getFullName(cacheName, null)) }
    @Override
    Set<String> getCacheNames() { return localCacheMap.keySet() }

    List<Map<String, Object>> getAllCachesInfo(String orderByField, String filterRegexp) {
        String tenantId = ecfi.getEci().getTenantId()
        String tenantPrefix = tenantId + "__"
        List<Map<String, Object>> ci = new LinkedList()
        for (String cn in localCacheMap.keySet()) {
            if (tenantId != "DEFAULT" && !cn.startsWith(tenantPrefix)) continue
            if (filterRegexp && !cn.matches("(?i).*" + filterRegexp + ".*")) continue
            Cache co = getCache(cn)
            if (co instanceof ICache) {
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
                        evictionCount:cs.getCacheEvictions(), removeCount:cs.getCacheRemovals(),
                        expireCount:0] as Map<String, Object>)
            } else if (co instanceof MCache) {
                MCache mc = co.unwrap(MCache.class)
                MStats stats = mc.getMStats()
                Long expireIdle = mc.getAccessDuration()?.durationAmount ?: 0
                Long expireLive = mc.getCreationDuration()?.durationAmount ?: 0
                ci.add([name:co.getName(), expireTimeIdle:expireIdle,
                        expireTimeLive:expireLive, maxElements:mc.getMaxEntries(),
                        evictionStrategy:"LRU", size:mc.size(),
                        getCount:stats.getCacheGets(), putCount:stats.getCachePuts(),
                        hitCount:stats.getCacheHits(), missCountTotal:stats.getCacheMisses(),
                        evictionCount:stats.getCacheEvictions(), removeCount:stats.getCacheRemovals(),
                        expireCount:stats.getCacheExpires()] as Map<String, Object>)
            } else {
                logger.warn("Cannot get detailed info for cache ${cn} which is of type ${co.class.name}")
            }
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

    protected synchronized Cache initCache(String cacheName, String tenantId, String defaultCacheType) {
        if (cacheName.contains("__")) cacheName = cacheName.substring(cacheName.indexOf("__") + 2)
        String fullCacheName = getFullName(cacheName, tenantId)
        if (localCacheMap.containsKey(fullCacheName)) return localCacheMap.get(fullCacheName)

        if (!defaultCacheType) defaultCacheType = "local"

        Cache newCache
        MNode cacheNode = getCacheNode(cacheName)
        if (cacheNode != null) {
            String keyTypeName = cacheNode.attribute("key-type") ?: "String"
            String valueTypeName = cacheNode.attribute("value-type") ?: "Object"
            Class keyType = StupidJavaUtilities.getClass(keyTypeName)
            Class valueType = StupidJavaUtilities.getClass(valueTypeName)

            Factory<ExpiryPolicy> expiryPolicyFactory
            if (cacheNode.attribute("expire-time-idle") && cacheNode.attribute("expire-time-idle") != "0") {
                expiryPolicyFactory = AccessedExpiryPolicy.factoryOf(
                        new Duration(TimeUnit.SECONDS, Long.parseLong(cacheNode.attribute("expire-time-idle"))))
            } else if (cacheNode.attribute("expire-time-live") && cacheNode.attribute("expire-time-live") != "0") {
                expiryPolicyFactory = CreatedExpiryPolicy.factoryOf(
                        new Duration(TimeUnit.SECONDS, Long.parseLong(cacheNode.attribute("expire-time-live"))))
            } else {
                expiryPolicyFactory = EternalExpiryPolicy.factoryOf()
            }

            String cacheType = cacheNode.attribute("type") ?: defaultCacheType
            CacheManager cacheManager
            if ("local".equals(cacheType)) {
                cacheManager = localCacheManagerInternal
            } else if ("distributed".equals(cacheType)) {
                cacheManager = getDistCacheManager()
            } else {
                throw new IllegalArgumentException("Cache type ${cacheType} not supported")
            }

            Configuration config
            if (cacheManager instanceof MCacheManager) {
                // use MCache
                MCacheConfiguration mConf = new MCacheConfiguration()
                mConf.setTypes(keyType, valueType)
                mConf.setStoreByValue(false).setStatisticsEnabled(true)
                mConf.setExpiryPolicyFactory(expiryPolicyFactory)

                String maxElementsStr = cacheNode.attribute("max-elements")
                if (maxElementsStr && maxElementsStr != "0") {
                    int maxElements = Integer.parseInt(maxElementsStr)
                    mConf.setMaxEntries(maxElements)
                }

                config = (Configuration) mConf
            } else if (cacheManager instanceof AbstractHazelcastCacheManager) {
                // use Hazelcast
                CacheConfig cacheConfig = new CacheConfig()
                cacheConfig.setTypes(keyType, valueType)
                cacheConfig.setStoreByValue(true).setStatisticsEnabled(true)
                cacheConfig.setExpiryPolicyFactory(expiryPolicyFactory)

                // from here down the settings are specific to Hazelcast (not supported in javax.cache)
                cacheConfig.setName(fullCacheName)
                cacheConfig.setInMemoryFormat(InMemoryFormat.OBJECT)

                String maxElementsStr = cacheNode.attribute("max-elements")
                if (maxElementsStr && maxElementsStr != "0") {
                    int maxElements = Integer.parseInt(maxElementsStr)
                    EvictionPolicy ep = cacheNode.attribute("eviction-strategy") == "least-recently-used" ? EvictionPolicy.LRU : EvictionPolicy.LFU
                    EvictionConfig evictionConfig = new EvictionConfig(maxElements, EvictionConfig.MaxSizePolicy.ENTRY_COUNT, ep)
                    cacheConfig.setEvictionConfig(evictionConfig)
                }

                config = (Configuration) cacheConfig
            } else {
                logger.info("Initializing cache ${cacheName} which has a CacheManager of type ${cacheManager.class.name} and extended configuration not supported, using simple MutableConfigutation")
                MutableConfiguration mutConfig = new MutableConfiguration()
                mutConfig.setTypes(keyType, valueType)
                mutConfig.setStoreByValue(false).setStatisticsEnabled(true)
                mutConfig.setExpiryPolicyFactory(expiryPolicyFactory)

                config = (Configuration) mutConfig
            }

            newCache = cacheManager.createCache(fullCacheName, config)
        } else {
            CacheManager cacheManager
            boolean storeByValue
            if ("local".equals(defaultCacheType)) {
                cacheManager = localCacheManagerInternal
                storeByValue = false
            } else if ("distributed".equals(defaultCacheType)) {
                cacheManager = getDistCacheManager()
                storeByValue = true
            } else {
                throw new IllegalArgumentException("Default cache type ${defaultCacheType} not supported")
            }

            logger.info("Creating default ${defaultCacheType} cache ${cacheName}, storeByValue=${storeByValue}")
            MutableConfiguration mutConfig = new MutableConfiguration()
            mutConfig.setStoreByValue(storeByValue).setStatisticsEnabled(true)
            // any defaults we want here? better to use underlying defaults and conf file settings only
            newCache = cacheManager.createCache(fullCacheName, mutConfig)
        }

        // NOTE: put in localCacheMap done in caller (getCache)
        return newCache
    }

    List<Map> makeElementInfoList(String cacheName, String orderByField) {
        Cache cache = getCache(cacheName)
        if (cache instanceof MCache) {
            MCache mCache = cache.unwrap(MCache.class)
            List<Map> elementInfoList = new ArrayList<>();
            for (Cache.Entry ce in mCache.getEntryList()) {
                MEntry entry = ce.unwrap(MEntry.class)
                Map<String, Object> im = new HashMap<String, Object>([key:entry.key as String,
                        value:entry.value as String, hitCount:entry.getAccessCount(),
                        creationTime:new Timestamp(entry.getCreatedTime())])
                if (entry.getLastUpdatedTime()) im.lastUpdateTime = new Timestamp(entry.getLastUpdatedTime())
                if (entry.getLastAccessTime()) im.lastAccessTime = new Timestamp(entry.getLastAccessTime())
                elementInfoList.add(im)
            }
            if (orderByField) StupidUtilities.orderMapList(elementInfoList, [orderByField])
            return elementInfoList
        } else {
            return new ArrayList<Map>()
        }
    }
}
