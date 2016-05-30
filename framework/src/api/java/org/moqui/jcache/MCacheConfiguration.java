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

import javax.cache.configuration.CompleteConfiguration;
import javax.cache.configuration.MutableConfiguration;

@SuppressWarnings("unused")
public class MCacheConfiguration<K, V> extends MutableConfiguration<K, V> {
    public MCacheConfiguration() {
        super();
    }

    public MCacheConfiguration(CompleteConfiguration<K, V> conf) {
        super(conf);
    }

    int maxEntries = 0;
    long maxCheckSeconds = 30;

    /** Set maximum number of entries in the cache, 0 means no limit (default). Limit is enforced in a scheduled worker, not on put operations. */
    public MCacheConfiguration<K, V> setMaxEntries(int elements) {
        maxEntries = elements;
        return this;
    }
    public int getMaxEntries() {
        return maxEntries;
    }

    /** Set maximum number of entries in the cache, 0 means no limit (default). */
    public MCacheConfiguration<K, V> setMaxCheckSeconds(long seconds) {
        maxCheckSeconds = seconds;
        return this;
    }

    public long getMaxCheckSeconds() {
        return maxCheckSeconds;
    }
}
