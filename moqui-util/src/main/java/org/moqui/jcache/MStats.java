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

import javax.cache.management.CacheStatisticsMXBean;

public class MStats implements CacheStatisticsMXBean {
    long hits = 0;
    long misses = 0;
    long gets = 0;

    long puts = 0;
    private long removals = 0;
    long evictions = 0;
    private long expires = 0;

    // long totalGetMicros = 0, totalPutMicros = 0, totalRemoveMicros = 0;

    @Override
    public void clear() {
        hits = 0;
        misses = 0;
        gets = 0;
        puts = 0;
        removals = 0;
        evictions = 0;
        expires = 0;
    }

    @Override
    public long getCacheHits() {
        return hits;
    }

    @Override
    public float getCacheHitPercentage() {
        return (hits / gets) * 100;
    }

    @Override
    public long getCacheMisses() {
        return misses;
    }

    @Override
    public float getCacheMissPercentage() {
        return (misses / gets) * 100;
    }

    @Override
    public long getCacheGets() {
        return gets;
    }

    @Override
    public long getCachePuts() {
        return puts;
    }

    @Override
    public long getCacheRemovals() {
        return removals;
    }

    @Override
    public long getCacheEvictions() {
        return evictions;
    }

    @Override
    public float getAverageGetTime() {
        return 0;
    } // totalGetMicros / gets

    @Override
    public float getAveragePutTime() {
        return 0;
    } // totalPutMicros / puts

    @Override
    public float getAverageRemoveTime() {
        return 0;
    } // totalRemoveMicros / removals

    public long getCacheExpires() {
        return expires;
    }

    /* have callers access fields directly for performance reasons:
    void countHit() {
        gets++; hits++;
        // totalGetMicros += micros;
    }
    void countMiss() {
        gets++; misses++;
        // totalGetMicros += micros;
    }
    void countPut() {
        puts++;
        // totalPutMicros += micros;
    }
    */
    void countRemoval() {
        removals++;
        // totalRemoveMicros += micros;
    }

    void countBulkRemoval(long entries) {
        removals += entries;
    }

    void countExpire() {
        expires++;
    }
}
