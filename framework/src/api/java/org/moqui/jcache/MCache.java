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
package org.moqui.jcache;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CompletionListener;
import javax.cache.management.CacheStatisticsMXBean;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.EntryProcessorResult;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** A simple implementation of the javax.cache.Cache interface. Basically a wrapper around ConcurrentHashMap with stats and expiry. */
public class MCache<K, V> implements Cache<K, V> {

    private String name;
    private CacheManager manager;
    private CompleteConfiguration<K, V> configuration;
    private ConcurrentMap<K, MEntry<K, V>> entryStore = new ConcurrentHashMap<>();
    private MStats stats = new MStats();
    private Duration accessDuration = null;
    private Duration creationDuration = null;
    private Duration updateDuration = null;
    private boolean isClosed = false;

    public MCache(String name, CacheManager manager, CompleteConfiguration<K, V> configuration) {
        this.name = name;
        this.manager = manager;
        this.configuration = configuration;
        if (configuration != null) {
            if (configuration.getExpiryPolicyFactory() != null) {
                ExpiryPolicy ep = configuration.getExpiryPolicyFactory().create();
                accessDuration = ep.getExpiryForAccess();
                if (accessDuration != null && accessDuration.isEternal()) accessDuration = null;
                creationDuration = ep.getExpiryForCreation();
                if (creationDuration != null && creationDuration.isEternal()) creationDuration = null;
                updateDuration = ep.getExpiryForUpdate();
                if (updateDuration != null && updateDuration.isEternal()) updateDuration = null;
            }
            // TODO: support any other configuration?
        }
    }

    @Override
    public String getName() { return name; }

    @Override
    public V get(K key) {
        MEntry<K, V> entry = getEntryInternal(key, null, null, System.currentTimeMillis());
        if (entry == null) return null;
        return entry.value;
    }
    public V get(K key, ExpiryPolicy policy) {
        MEntry<K, V> entry = getEntryInternal(key, policy, null, System.currentTimeMillis());
        if (entry == null) return null;
        return entry.value;
    }
    /** Get with expire if the entry's last updated time is before the expireBeforeTime.
     * Useful when last updated time of a resource is known to see if the cached entry is out of date. */
    public V get(K key, long expireBeforeTime) {
        MEntry<K, V> entry = getEntryInternal(key, null, expireBeforeTime, System.currentTimeMillis());
        if (entry == null) return null;
        return entry.value;
    }
    /** Get an entry, if it is in the cache and not expired, otherwise returns null. */
    public MEntry<K, V> getEntry(final K key, final ExpiryPolicy policy) {
        return getEntryInternal(key, policy, null, System.currentTimeMillis());
    }
    private MEntry<K, V> getEntryInternal(final K key, final ExpiryPolicy policy, final Long expireBeforeTime, long currentTime) {
        MEntry<K, V> entry = entryStore.get(key);

        if (entry != null) {
            if (policy != null) {
                if (entry.isExpired(currentTime, policy)) {
                    entryStore.remove(key);
                    entry = null;
                    stats.countExpire();
                }
            } else {
                if (entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
                    entryStore.remove(key);
                    entry = null;
                    stats.countExpire();
                }
            }

            if (expireBeforeTime != null && entry != null && entry.lastUpdatedTime < expireBeforeTime) {
                entryStore.remove(key);
                entry = null;
                stats.countExpire();
            }

            if (entry != null) {
                stats.countHit(0);
                entry.countAccess(currentTime);
            }
        } else {
            stats.countMiss(0);
        }

        return entry;
    }
    /** Simple entry get, doesn't check if expired. */
    MEntry<K, V> getEntryNoCheck(K key) {
        MEntry<K, V> entry = entryStore.get(key);
        if (entry != null) {
            stats.countHit(0);
            entry.countAccess(System.currentTimeMillis());
        } else {
            stats.countMiss(0);
        }
        return entry;
    }

    @Override
    public Map<K, V> getAll(Set<? extends K> keys) {
        Map<K, V> results = new HashMap<>();
        for (K key: keys) results.put(key, get(key, null));
        return results;
    }
    @Override
    public boolean containsKey(K key) {
        MEntry<K, V> entry = entryStore.get(key);
        if (entry != null && entry.isExpired(accessDuration, creationDuration, updateDuration)) {
            entryStore.remove(key);
            entry = null;
            stats.countExpire();
        }
        return entry != null;
    }

    @Override
    public void put(K key, V value) { getAndPut(key, value); }
    @Override
    public V getAndPut(K key, V value) {
        long currentTime = System.currentTimeMillis();
        // get entry, count hit/miss
        MEntry<K, V> entry = getEntryInternal(key, null, null, currentTime);
        if (entry != null) {
            V oldValue = entry.value;
            entry.setValue(value, currentTime);
            stats.countPut(0);
            return oldValue;
        } else {
            entry = new MEntry<>(key, value);
            entryStore.put(key, entry);
            stats.countPut(0);
            return null;
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        if (map == null) return;
        for (Map.Entry<? extends K, ? extends V> me: map.entrySet()) put(me.getKey(), me.getValue());
    }
    @Override
    public boolean putIfAbsent(K key, V value) {
        MEntry<K, V> entry = getEntryInternal(key, null, null, System.currentTimeMillis());
        if (entry != null) {
            return false;
        } else {
            entry = new MEntry<>(key, value);
            entryStore.put(key, entry);
            stats.countPut(0);
            return true;
        }
    }

    @Override
    public boolean remove(K key) {
        MEntry<K, V> entry = entryStore.get(key);

        if (entry != null && entry.isExpired(accessDuration, creationDuration, updateDuration)) {
            entryStore.remove(key);
            entry = null;
            stats.countExpire();
        }

        if (entry != null) {
            entryStore.remove(key);
            stats.countRemoval(0);
            return true;
        } else {
            return false;
        }
    }
    @Override
    public boolean remove(K key, V oldValue) {
        MEntry<K, V> entry = entryStore.get(key);

        if (entry != null && entry.isExpired(accessDuration, creationDuration, updateDuration)) {
            entryStore.remove(key);
            entry = null;
            stats.countExpire();
        }

        if (entry != null) {
            boolean remove = false;
            if (oldValue != null) { if (oldValue.equals(entry.value)) remove = true; }
            else if (entry.value == null) { remove = true; }
            if (remove) {
                entryStore.remove(key);
                stats.countRemoval(0);
            }
            return remove;
        } else {
            return false;
        }
    }

    @Override
    public V getAndRemove(K key) {
        // get entry, count hit/miss
        MEntry<K, V> entry = getEntryInternal(key, null, null, System.currentTimeMillis());
        if (entry != null) {
            V oldValue = entry.value;
            entryStore.remove(key);
            stats.countRemoval(0);
            return oldValue;
        }
        return null;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        long currentTime = System.currentTimeMillis();
        MEntry<K, V> entry = entryStore.get(key);

        if (entry != null && entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
            entryStore.remove(key);
            entry = null;
            stats.countExpire();
        }

        if (entry != null) {
            boolean replace = false;
            if (oldValue != null) { if (oldValue.equals(entry.value)) replace = true; }
            else if (entry.value == null) { replace = true; }
            if (replace) {
                entry.setValue(newValue, currentTime);
                stats.countPut(0);
            }
            return replace;
        } else {
            return false;
        }
    }

    @Override
    public boolean replace(K key, V value) {
        long currentTime = System.currentTimeMillis();
        MEntry<K, V> entry = entryStore.get(key);

        if (entry != null && entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
            entryStore.remove(key);
            entry = null;
            stats.countExpire();
        }

        if (entry != null) {
            entry.setValue(value, currentTime);
            stats.countPut(0);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public V getAndReplace(K key, V value) {
        long currentTime = System.currentTimeMillis();
        // get entry, count hit/miss
        MEntry<K, V> entry = getEntryInternal(key, null, null, currentTime);
        if (entry != null) {
            V oldValue = entry.value;
            entry.setValue(value, currentTime);
            stats.countPut(0);
            return oldValue;
        } else {
            return null;
        }
    }

    @Override
    public void removeAll(Set<? extends K> keys) { for (K key: keys) remove(key); }

    @Override
    public void removeAll() {
        int size = entryStore.size();
        entryStore.clear();
        stats.countBulkRemoval(size);
    }

    @Override
    public void clear() {
        // don't track removals or do anything else, removeAll does that
        entryStore.clear();
    }

    @Override
    public <C extends Configuration<K, V>> C getConfiguration(Class<C> clazz) {
        if (configuration == null) return null;
        if (clazz.isAssignableFrom(configuration.getClass())) return clazz.cast(configuration);
        throw new IllegalArgumentException("Class " + clazz.getName() + " not compatible with configuration class " + configuration.getClass().getName());
    }

    @Override
    public void loadAll(Set<? extends K> keys, boolean replaceExistingValues, CompletionListener completionListener) {
        throw new CacheException("loadAll not supported in MCache");
    }
    @Override
    public <T> T invoke(K key, EntryProcessor<K, V, T> entryProcessor, Object... arguments) throws EntryProcessorException {
        throw new CacheException("invoke not supported in MCache");
    }
    @Override
    public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys, EntryProcessor<K, V, T> entryProcessor, Object... arguments) {
        throw new CacheException("invokeAll not supported in MCache");
    }
    @Override
    public void registerCacheEntryListener(CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
        throw new CacheException("registerCacheEntryListener not supported in MCache");
    }
    @Override
    public void deregisterCacheEntryListener(CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
        throw new CacheException("deregisterCacheEntryListener not supported in MCache");
    }

    @Override
    public CacheManager getCacheManager() { return manager; }

    @Override
    public void close() {
        isClosed = true;
        entryStore.clear();
    }
    @Override
    public boolean isClosed() { return isClosed; }

    @Override
    public <T> T unwrap(Class<T> clazz) {
        if (clazz.isAssignableFrom(this.getClass())) return clazz.cast(this);
        throw new IllegalArgumentException("Class " + clazz.getName() + " not compatible with MCache");
    }

    @Override
    public Iterator<Entry<K, V>> iterator() {
        ArrayList<Entry<K, V>> entryList = getEntryList();
        return entryList.iterator();
    }

    public ArrayList<Entry<K, V>> getEntryList() {
        long currentTime = System.currentTimeMillis();
        ArrayList<K> keyList = new ArrayList<>(entryStore.keySet());
        int keyListSize = keyList.size();
        ArrayList<Entry<K, V>> entryList = new ArrayList<>(keyListSize);
        for (int i = 0; i < keyListSize; i++) {
            K key = keyList.get(i);
            MEntry<K, V> entry = entryStore.get(key);
            if (entry != null) {
                if (entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
                    entryStore.remove(key);
                    stats.countExpire();
                } else {
                    entryList.add(entry);
                    entry.countAccess(currentTime);
                    stats.countHit(0);
                }
            }
        }
        return entryList;
    }
    int clearExpired() {
        long currentTime = System.currentTimeMillis();
        ArrayList<K> keyList = new ArrayList<>(entryStore.keySet());
        int keyListSize = keyList.size();
        int expireCount = 0;
        for (int i = 0; i < keyListSize; i++) {
            K key = keyList.get(i);
            MEntry<K, V> entry = entryStore.get(key);
            if (entry != null && entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
                entryStore.remove(key);
                stats.countExpire();
                expireCount++;
            }
        }
        return expireCount;
    }
    CacheStatisticsMXBean getStats() { return stats; }
    MStats getMStats() { return stats; }
    int size() { return entryStore.size(); }

    public static class MEntry<K, V> implements Cache.Entry<K, V> {
        private K key;
        private V value;
        private long createdTime;
        private long lastUpdatedTime;
        private long lastAccessTime = 0;
        private long accessCount = 0;

        MEntry(K key, V value) {
            this.key = key;
            this.value = value;
            createdTime = System.currentTimeMillis();
            lastUpdatedTime = createdTime;
        }

        @Override
        public K getKey() { return key; }
        @Override
        public V getValue() { return value; }
        @Override
        public <T> T unwrap(Class<T> clazz) {
            if (clazz.isAssignableFrom(this.getClass())) return clazz.cast(this);
            throw new IllegalArgumentException("Class " + clazz.getName() + " not compatible with MCache.MEntry");
        }

        void setValue(V val, long updateTime) {
            if (updateTime > lastUpdatedTime) {
                value = val;
                lastUpdatedTime = updateTime;
            }
        }

        public long getCreatedTime() { return createdTime; }
        public long getLastUpdatedTime() { return lastUpdatedTime; }
        public long getLastAccessTime() { return lastAccessTime; }
        public long getAccessCount() { return accessCount; }

        void countAccess(long accessTime) {
            accessCount++;
            if (accessTime > lastAccessTime) lastAccessTime = accessTime;
        }
        public boolean isExpired(ExpiryPolicy policy) {
            return isExpired(System.currentTimeMillis(), policy.getExpiryForAccess(), policy.getExpiryForCreation(),
                    policy.getExpiryForUpdate());
        }
        boolean isExpired(long accessTime, ExpiryPolicy policy) {
            return isExpired(accessTime, policy.getExpiryForAccess(), policy.getExpiryForCreation(),
                    policy.getExpiryForUpdate());
        }
        public boolean isExpired(Duration accessDuration, Duration creationDuration, Duration updateDuration) {
            return isExpired(System.currentTimeMillis(), accessDuration, creationDuration, updateDuration);
        }
        boolean isExpired(long accessTime, Duration accessDuration, Duration creationDuration, Duration updateDuration) {
            if (accessDuration != null && !accessDuration.isEternal()) {
                long adjustedTime = accessDuration.getAdjustedTime(lastAccessTime);
                if (adjustedTime < accessTime) return true;
            }
            if (creationDuration != null && !creationDuration.isEternal()) {
                long adjustedTime = creationDuration.getAdjustedTime(lastAccessTime);
                if (adjustedTime < accessTime) return true;
            }
            if (updateDuration != null && !updateDuration.isEternal()) {
                long adjustedTime = updateDuration.getAdjustedTime(lastAccessTime);
                if (adjustedTime < accessTime) return true;
            }
            return false;
        }
    }

    public static class MStats implements CacheStatisticsMXBean {
        long hits = 0;
        long misses = 0;
        long gets = 0;

        long puts = 0;
        long removals = 0;
        long evictions = 0;
        long expires = 0;

        long totalGetMicros = 0;
        long totalPutMicros = 0;
        long totalRemoveMicros = 0;

        @Override
        public void clear() {
            hits = 0;
            misses = 0;
            gets = 0;
            puts = 0;
            removals = 0;
            evictions = 0;
        }

        @Override
        public long getCacheHits() { return hits; }
        @Override
        public float getCacheHitPercentage() { return (hits / gets) * 100; }
        @Override
        public long getCacheMisses() { return misses; }
        @Override
        public float getCacheMissPercentage() { return (misses / gets) * 100; }
        @Override
        public long getCacheGets() { return gets; }

        @Override
        public long getCachePuts() { return puts; }
        @Override
        public long getCacheRemovals() { return removals; }
        @Override
        public long getCacheEvictions() { return evictions; }

        @Override
        public float getAverageGetTime() { return totalGetMicros / gets; }
        @Override
        public float getAveragePutTime() { return totalPutMicros / puts; }
        @Override
        public float getAverageRemoveTime() { return totalRemoveMicros / removals; }

        public long getCacheExpires() { return expires; }

        void countHit(long micros) {
            gets++;
            hits++;
            totalGetMicros += micros;
        }
        void countMiss(long micros) {
            gets++;
            misses++;
            totalGetMicros += micros;
        }
        void countPut(long micros) {
            puts++;
            totalPutMicros += micros;
        }
        void countRemoval(long micros) {
            removals++;
            totalRemoveMicros += micros;
        }
        void countBulkRemoval(long entries) {
            removals += entries;
        }
        void countEviction() {
            evictions++;
        }
        void countExpire() {
            expires++;
        }
    }
}
