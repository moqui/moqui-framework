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

/** A facade used for managing and accessing Cache instances. */
public interface CacheFacade {
    void clearAllCaches();
    void clearExpiredFromAllCaches();
    void clearCachesByPrefix(String prefix);

    /** Get the named Cache, creating one based on configuration and defaults if none exists. */
    Cache getCache(String cacheName);
}
