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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.configuration.Configuration;
import javax.cache.spi.CachingProvider;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** This class does not completely support the javax.cache.CacheManager spec, it is just enough to use as a factory for MCache instances. */
public class MCacheManager implements CacheManager {
    private static final Logger logger = LoggerFactory.getLogger(MCacheManager.class);

    private static final MCacheManager singleCacheManager = new MCacheManager();
    public static MCacheManager getMCacheManager() { return singleCacheManager; }

    private URI cmUri = null;
    private ClassLoader localClassLoader;
    private Properties props = new Properties();
    private Map<String, MCache> cacheMap = new LinkedHashMap<>();
    private boolean isClosed = false;

    private MCacheManager() {
        try { cmUri = new URI("MCacheManager"); }
        catch (URISyntaxException e) { logger.error("URI Syntax error initializing MCacheManager", e); }
        localClassLoader = Thread.currentThread().getContextClassLoader();
    }

    @Override
    public CachingProvider getCachingProvider() { return null; }
    @Override
    public URI getURI() { return cmUri; }
    @Override
    public ClassLoader getClassLoader() { return localClassLoader; }
    @Override
    public Properties getProperties() { return props; }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <K, V, C extends Configuration<K, V>> Cache<K, V> createCache(String cacheName, C configuration) throws IllegalArgumentException {
        if (isClosed) throw new IllegalStateException("MCacheManager is closed");
        if (cacheMap.containsKey(cacheName)) {
            // not per spec, but be more friendly and just return the existing cache: throw new CacheException("Cache with name " + cacheName + " already exists");
            return cacheMap.get(cacheName);
        }

        MCache<K, V> newCache = new MCache(cacheName, this, configuration);
        cacheMap.put(cacheName, newCache);
        return newCache;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String cacheName, Class<K> keyType, Class<V> valueType) {
        if (isClosed) throw new IllegalStateException("MCacheManager is closed");
        return cacheMap.get(cacheName);
    }
    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String cacheName) {
        if (isClosed) throw new IllegalStateException("MCacheManager is closed");
        return cacheMap.get(cacheName);
    }

    @Override
    public Iterable<String> getCacheNames() {
        if (isClosed) throw new IllegalStateException("MCacheManager is closed");
        return cacheMap.keySet();
    }

    @Override
    public void destroyCache(String cacheName) {
        if (isClosed) throw new IllegalStateException("MCacheManager is closed");
        MCache cache = cacheMap.get(cacheName);
        if (cache != null) {
            cacheMap.remove(cacheName);
            cache.close();
        } else {
            throw new IllegalStateException("Cache with name " + cacheName + " does not exist, cannot be destroyed");
        }
    }

    @Override
    public void enableManagement(String cacheName, boolean enabled) {
        throw new UnsupportedOperationException("MCacheManager does not support CacheMXBean"); }
    @Override
    public void enableStatistics(String cacheName, boolean enabled) {
        throw new UnsupportedOperationException("MCacheManager does not support registered statistics; use the MCache.getStats() or getMStats() methods"); }

    @Override
    public void close() {
        cacheMap.clear();
        // doesn't work well with current singleton approach: isClosed = true;
    }
    @Override
    public boolean isClosed() { return isClosed; }

    @Override
    public <T> T unwrap(Class<T> clazz) {
        if (clazz.isAssignableFrom(this.getClass())) return clazz.cast(this);
        throw new IllegalArgumentException("Class " + clazz.getName() + " not compatible with MCacheManager");
    }
}
