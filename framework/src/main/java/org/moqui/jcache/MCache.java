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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A simple implementation of the javax.cache.Cache interface. Basically a wrapper around a Map with stats and expiry. */
@SuppressWarnings("unused")
public class MCache<K, V> implements Cache<K, V> {
    private static final Logger logger = LoggerFactory.getLogger(MCache.class);

    private String name;
    private CacheManager manager;
    private Configuration<K, V> configuration;
    // NOTE: use ConcurrentHashMap for write locks and such even if can't so easily use putIfAbsent/etc
    private ConcurrentHashMap<K, MEntry<K, V>> entryStore = new ConcurrentHashMap<>();
    // currently for future reference, no runtime type checking
    // private Class<K> keyClass = null;
    // private Class<V> valueClass = null;

    private MStats stats = new MStats();
    private boolean statsEnabled = true;

    private Duration accessDuration = null;
    private Duration creationDuration = null;
    private Duration updateDuration = null;
    private final boolean hasExpiry;
    private boolean isClosed = false;

    private EvictRunnable evictRunnable = null;
    private ScheduledFuture<?> evictFuture = null;

    private static class WorkerThreadFactory implements ThreadFactory {
        private final ThreadGroup workerGroup = new ThreadGroup("MCacheEvict");
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        @Override public Thread newThread(Runnable r) { return new Thread(workerGroup, r, "MCacheEvict-" + threadNumber.getAndIncrement()); }
    }
    private static ScheduledThreadPoolExecutor workerPool = new ScheduledThreadPoolExecutor(1, new WorkerThreadFactory());
    static { workerPool.setRemoveOnCancelPolicy(true); }

    /** Supports a few configurations but both manager and configuration can be null. */
    public MCache(String name, CacheManager manager, Configuration<K, V> configuration) {
        this.name = name;
        this.manager = manager;
        this.configuration = configuration;
        if (configuration != null) {
            if (configuration instanceof CompleteConfiguration) {
                CompleteConfiguration<K, V> compConf = (CompleteConfiguration<K, V>) configuration;

                statsEnabled = compConf.isStatisticsEnabled();

                if (compConf.getExpiryPolicyFactory() != null) {
                    ExpiryPolicy ep = compConf.getExpiryPolicyFactory().create();
                    accessDuration = ep.getExpiryForAccess();
                    if (accessDuration != null && accessDuration.isEternal()) accessDuration = null;
                    creationDuration = ep.getExpiryForCreation();
                    if (creationDuration != null && creationDuration.isEternal()) creationDuration = null;
                    updateDuration = ep.getExpiryForUpdate();
                    if (updateDuration != null && updateDuration.isEternal()) updateDuration = null;
                }
            }

            // keyClass = configuration.getKeyType();
            // valueClass = configuration.getValueType();
            // TODO: support any other configuration?

            if (configuration instanceof MCacheConfiguration) {
                MCacheConfiguration<K, V> mCacheConf = (MCacheConfiguration<K, V>) configuration;

                if (mCacheConf.maxEntries > 0) {
                    evictRunnable = new EvictRunnable(this, mCacheConf.maxEntries);
                    evictFuture = workerPool.scheduleWithFixedDelay(evictRunnable, 30, mCacheConf.maxCheckSeconds, TimeUnit.SECONDS);
                }
            }
        }
        hasExpiry = accessDuration != null || creationDuration != null || updateDuration != null;
    }

    public synchronized void setMaxEntries(int elements) {
        if (elements == 0) {
            if (evictRunnable != null) {
                evictRunnable = null;
                evictFuture.cancel(false);
                evictFuture = null;
            }
        } else {
            if (evictRunnable != null) {
                evictRunnable.maxEntries = elements;
            } else {
                evictRunnable = new EvictRunnable(this, elements);
                long maxCheckSeconds = 30;
                if (configuration instanceof MCacheConfiguration) maxCheckSeconds = ((MCacheConfiguration) configuration).maxCheckSeconds;
                evictFuture = workerPool.scheduleWithFixedDelay(evictRunnable, 1, maxCheckSeconds, TimeUnit.SECONDS);
            }
        }
    }
    public int getMaxEntries() { return evictRunnable != null ? evictRunnable.maxEntries : 0; }

    @Override
    public String getName() { return name; }

    @Override
    public V get(K key) {
        MEntry<K, V> entry = getEntryInternal(key, null, null, 0);
        if (entry == null) return null;
        return entry.value;
    }
    public V get(K key, ExpiryPolicy policy) {
        MEntry<K, V> entry = getEntryInternal(key, policy, null, 0);
        if (entry == null) return null;
        return entry.value;
    }
    /** Get with expire if the entry's last updated time is before the expireBeforeTime.
     * Useful when last updated time of a resource is known to see if the cached entry is out of date. */
    public V get(K key, long expireBeforeTime) {
        MEntry<K, V> entry = getEntryInternal(key, null, expireBeforeTime, 0);
        if (entry == null) return null;
        return entry.value;
    }
    /** Get an entry, if it is in the cache and not expired, otherwise returns null. The policy can be null to use cache's policy. */
    public MEntry<K, V> getEntry(final K key, final ExpiryPolicy policy) { return getEntryInternal(key, policy, null, 0); }
    /** Simple entry get, doesn't check if expired. */
    public MEntry<K, V> getEntryNoCheck(K key) {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        if (key == null) throw new IllegalArgumentException("Cache key cannot be null");
        MEntry<K, V> entry = entryStore.get(key);
        if (entry != null) {
            if (statsEnabled) { stats.gets++; stats.hits++; }
            long accessTime = System.currentTimeMillis();
            entry.accessCount++; if (accessTime > entry.lastAccessTime) entry.lastAccessTime = accessTime;
        } else {
            if (statsEnabled) { stats.gets++; stats.misses++; }
        }
        return entry;
    }
    private MEntry<K, V> getEntryInternal(final K key, final ExpiryPolicy policy, final Long expireBeforeTime, long currentTime) {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        if (key == null) throw new IllegalArgumentException("Cache key cannot be null");
        MEntry<K, V> entry = entryStore.get(key);

        if (entry != null) {
            if (policy != null) {
                if (currentTime == 0) currentTime = System.currentTimeMillis();
                if (entry.isExpired(currentTime, policy)) {
                    entryStore.remove(key);
                    entry = null;
                    if (statsEnabled) stats.countExpire();
                }
            } else if (hasExpiry) {
                if (currentTime == 0) currentTime = System.currentTimeMillis();
                if (entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
                    entryStore.remove(key);
                    entry = null;
                    if (statsEnabled) stats.countExpire();
                }
            }

            if (expireBeforeTime != null && entry != null && entry.lastUpdatedTime < expireBeforeTime) {
                entryStore.remove(key);
                entry = null;
                if (statsEnabled) stats.countExpire();
            }

            if (entry != null) {
                if (statsEnabled) { stats.gets++; stats.hits++; }
                entry.accessCount++;
                // at this point if an ad-hoc policy is used or hasExpiry == true currentTime will be set, otherwise will be 0
                // meaning we don't need to track the lastAccessTime (only thing we need System.currentTimeMillis() for)
                // if (currentTime == 0) currentTime = System.currentTimeMillis();
                if (currentTime > entry.lastAccessTime) entry.lastAccessTime = currentTime;
            } else {
                if (statsEnabled) { stats.gets++; stats.misses++; }
            }
        } else {
            if (statsEnabled) { stats.gets++; stats.misses++; }
        }

        return entry;
    }
    private MEntry<K, V> getCheckExpired(K key) {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        if (key == null) throw new IllegalArgumentException("Cache key cannot be null");
        MEntry<K, V> entry = entryStore.get(key);
        if (hasExpiry && entry != null && entry.isExpired(accessDuration, creationDuration, updateDuration)) {
            entryStore.remove(key);
            entry = null;
            if (statsEnabled) stats.countExpire();
        }
        return entry;
    }
    private MEntry<K, V> getCheckExpired(K key, long currentTime) {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        if (key == null) throw new IllegalArgumentException("Cache key cannot be null");
        MEntry<K, V> entry = entryStore.get(key);
        if (hasExpiry && entry != null && entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
            entryStore.remove(key);
            entry = null;
            if (statsEnabled) stats.countExpire();
        }
        return entry;
    }

    @Override
    public Map<K, V> getAll(Set<? extends K> keys) {
        long currentTime = System.currentTimeMillis();
        Map<K, V> results = new HashMap<>();
        for (K key: keys) {
            MEntry<K, V> entry = getEntryInternal(key, null, null, currentTime);
            results.put(key, entry != null ? entry.value : null);
        }
        return results;
    }
    @Override
    public boolean containsKey(K key) {
        MEntry<K, V> entry = getCheckExpired(key);
        return entry != null;
    }

    @Override
    public void put(K key, V value) {
        long currentTime = System.currentTimeMillis();
        // get entry, count hit/miss
        MEntry<K, V> entry = getCheckExpired(key, currentTime);
        if (entry != null) {
            entry.setValue(value, currentTime);
            if (statsEnabled) stats.puts++;
        } else {
            entry = new MEntry<>(key, value, currentTime);
            entryStore.put(key, entry);
            if (statsEnabled) stats.puts++;
        }
    }
    @Override
    public V getAndPut(K key, V value) {
        long currentTime = System.currentTimeMillis();
        // get entry, count hit/miss
        MEntry<K, V> entry = getCheckExpired(key, currentTime);
        if (entry != null) {
            V oldValue = entry.value;
            entry.setValue(value, currentTime);
            if (statsEnabled) stats.puts++;
            return oldValue;
        } else {
            entry = new MEntry<>(key, value, currentTime);
            entryStore.put(key, entry);
            if (statsEnabled) stats.puts++;
            return null;
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        if (map == null) return;
        for (Map.Entry<? extends K, ? extends V> me: map.entrySet()) getAndPut(me.getKey(), me.getValue());
    }
    @Override
    public boolean putIfAbsent(K key, V value) {
        long currentTime = System.currentTimeMillis();
        MEntry<K, V> entry = getCheckExpired(key, currentTime);
        if (entry != null) {
            return false;
        } else {
            entry = new MEntry<>(key, value, currentTime);
            MEntry<K, V> existingValue = entryStore.putIfAbsent(key, entry);
            if (existingValue == null) {
                if (statsEnabled) stats.puts++;
                return true;
            } else {
                return false;
            }
        }
    }

    @Override
    public boolean remove(K key) {
        MEntry<K, V> entry = getCheckExpired(key);
        if (entry != null) {
            entryStore.remove(key);
            if (statsEnabled) stats.countRemoval();
            return true;
        } else {
            return false;
        }
    }
    @Override
    public boolean remove(K key, V oldValue) {
        MEntry<K, V> entry = getCheckExpired(key);

        if (entry != null) {
            boolean remove = entry.valueEquals(oldValue);
            if (remove) {
                // remove with dummy MEntry instance for comparison to ensure still equals
                remove = entryStore.remove(key, new MEntry<>(key, oldValue));
                if (remove && statsEnabled) stats.countRemoval();
            }
            return remove;
        } else {
            return false;
        }
    }

    @Override
    public V getAndRemove(K key) {
        // get entry, count hit/miss
        MEntry<K, V> entry = getEntryInternal(key, null, null, 0);
        if (entry != null) {
            V oldValue = entry.value;
            entryStore.remove(key);
            if (statsEnabled) stats.countRemoval();
            return oldValue;
        }
        return null;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        long currentTime = System.currentTimeMillis();
        MEntry<K, V> entry = getCheckExpired(key, currentTime);

        if (entry != null) {
            boolean replaced = entry.setValueIfEquals(oldValue, newValue, currentTime);
            if (replaced) if (statsEnabled) stats.puts++;
            return replaced;
        } else {
            return false;
        }
    }

    @Override
    public boolean replace(K key, V value) {
        long currentTime = System.currentTimeMillis();
        MEntry<K, V> entry = getCheckExpired(key, currentTime);

        if (entry != null) {
            entry.setValue(value, currentTime);
            if (statsEnabled) stats.puts++;
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
            if (statsEnabled) stats.puts++;
            return oldValue;
        } else {
            return null;
        }
    }

    @Override
    public void removeAll(Set<? extends K> keys) {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        for (K key: keys) remove(key);
    }

    @Override
    public void removeAll() {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        int size = entryStore.size();
        entryStore.clear();
        if (statsEnabled) stats.countBulkRemoval(size);
    }

    @Override
    public void clear() {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
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
        if (isClosed) throw new IllegalStateException("Cache " + name + " is already closed");
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
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        return new CacheIterator<>(this);
    }

    private static class CacheIterator<K, V> implements Iterator<Entry<K, V>> {
        final MCache<K, V> mCache;
        final long initialTime;
        final ArrayList<MEntry<K, V>> entryList;
        final int maxIndex;
        int curIndex = -1;
        MEntry<K, V> curEntry = null;

        CacheIterator(MCache<K, V> mCache) {
            this.mCache = mCache;
            entryList = new ArrayList<>(mCache.entryStore.values());
            maxIndex = entryList.size() - 1;
            initialTime = System.currentTimeMillis();
        }

        @Override
        public boolean hasNext() { return curIndex < maxIndex; }

        @Override
        public Entry<K, V> next() {
            curEntry = null;
            while (curIndex < maxIndex) {
                curIndex++;
                curEntry = entryList.get(curIndex);
                if (curEntry.isExpired) {
                    curEntry = null;
                } else if (mCache.hasExpiry && curEntry.isExpired(initialTime, mCache.accessDuration, mCache.creationDuration, mCache.updateDuration)) {
                    mCache.entryStore.remove(curEntry.getKey());
                    if (mCache.statsEnabled) mCache.stats.countExpire();
                    curEntry = null;
                } else {
                    if (mCache.statsEnabled)  { mCache.stats.gets++; mCache.stats.hits++; }
                    break;
                }
            }
            return curEntry;
        }

        @Override
        public void remove() {
            if (curEntry != null) {
                mCache.entryStore.remove(curEntry.getKey());
                if (mCache.statsEnabled) mCache.stats.countRemoval();
                curEntry = null;
            }
        }
    }

    /** Gets all entries, checking for expiry and counts a get for each */
    public ArrayList<Entry<K, V>> getEntryList() {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        long currentTime = System.currentTimeMillis();
        ArrayList<K> keyList = new ArrayList<>(entryStore.keySet());
        int keyListSize = keyList.size();
        ArrayList<Entry<K, V>> entryList = new ArrayList<>(keyListSize);
        for (int i = 0; i < keyListSize; i++) {
            K key = keyList.get(i);
            MEntry<K, V> entry = getCheckExpired(key, currentTime);
            if (entry != null) {
                entryList.add(entry);
                if (statsEnabled) { stats.gets++; stats.hits++; }
                entry.accessCount++; if (currentTime > entry.lastAccessTime) entry.lastAccessTime = currentTime;
            }
        }
        return entryList;
    }
    public int clearExpired() {
        if (isClosed) throw new IllegalStateException("Cache " + name + " is closed");
        if (!hasExpiry) return 0;
        long currentTime = System.currentTimeMillis();
        ArrayList<K> keyList = new ArrayList<>(entryStore.keySet());
        int keyListSize = keyList.size();
        int expireCount = 0;
        for (int i = 0; i < keyListSize; i++) {
            K key = keyList.get(i);
            MEntry<K, V> entry = entryStore.get(key);
            if (entry != null && entry.isExpired(currentTime, accessDuration, creationDuration, updateDuration)) {
                entryStore.remove(key);
                if (statsEnabled) stats.countExpire();
                expireCount++;
            }
        }
        return expireCount;
    }
    public CacheStatisticsMXBean getStats() { return stats; }
    public MStats getMStats() { return stats; }
    public int size() { return entryStore.size(); }

    public Duration getAccessDuration() { return accessDuration; }
    public Duration getCreationDuration() { return creationDuration; }
    public Duration getUpdateDuration() { return updateDuration; }

    private static class EvictRunnable<K, V> implements Runnable {
        static AccessComparator comparator = new AccessComparator();
        MCache cache;
        int maxEntries;
        EvictRunnable(MCache mc, int entries) { cache = mc; maxEntries = entries; }
        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            if (maxEntries == 0) return;
            int entriesToEvict = cache.entryStore.size() - maxEntries;
            if (entriesToEvict <= 0) return;

            long startTime = System.currentTimeMillis();

            Collection<MEntry> entrySet = (Collection<MEntry>) cache.entryStore.values();
            PriorityQueue<MEntry> priorityQueue = new PriorityQueue<>(entrySet.size(), comparator);
            priorityQueue.addAll(entrySet);

            int entriesEvicted = 0;
            while (entriesToEvict > 0 && priorityQueue.size() > 0) {
                MEntry curEntry = priorityQueue.poll();
                // if an entry was expired after pulling the initial value set
                if (curEntry.isExpired) continue;
                cache.entryStore.remove(curEntry.getKey());
                cache.stats.evictions++;
                entriesEvicted++;
                entriesToEvict--;
            }
            long timeElapsed = System.currentTimeMillis() - startTime;
            logger.info("Evicted " + entriesEvicted + " entries in " + timeElapsed + "ms from cache " + cache.name);
        }
    }
    private static class AccessComparator implements Comparator<MEntry> {
        @Override
        public int compare(MEntry e1, MEntry e2) {
            if (e1.accessCount == e2.accessCount) {
                if (e1.lastAccessTime == e2.lastAccessTime) return 0;
                else return e1.lastAccessTime > e2.lastAccessTime ? 1 : -1;
            } else {
                return e1.accessCount > e2.accessCount ? 1 : -1;
            }
        }
    }

}
