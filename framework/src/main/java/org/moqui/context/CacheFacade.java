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
package org.moqui.context;

import org.moqui.jcache.MCache;
import javax.cache.Cache;
import java.util.Set;

/** A facade used for managing and accessing Cache instances. */
public interface CacheFacade {
    void clearAllCaches();
    void clearCachesByPrefix(String prefix);

    /** Get the named Cache, creating one based on configuration and defaults if none exists.
     * Defaults to local cache if no configuration found. */
    Cache getCache(String cacheName);
    /** A type-safe variation on getCache for configured caches. */
    <K, V> Cache<K, V> getCache(String cacheName, Class<K> keyType, Class<V> valueType);
    /** Get the named local Cache (MCache instance), creating one based on defaults if none exists.
     * If the cache is configured with type != 'local' this will return an error. */
    MCache getLocalCache(String cacheName);
    /** Get the named distributed Cache, creating one based on configuration and defaults if none exists.
     * If the cache is configured without type != 'distributed' this will return an error. */
    Cache getDistributedCache(String cacheName);

    /** Register an externally created cache for future gets, inclusion in cache management tools, etc.
     * If a cache with the same name exists the call will be ignored (ie like putIfAbsent). */
    void registerCache(Cache cache);

    Set<String> getCacheNames();
    boolean cacheExists(String cacheName);
}
