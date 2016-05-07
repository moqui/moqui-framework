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


import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.jcache.MCache
import spock.lang.*

class CacheFacadeTests extends Specification {
    @Shared
    ExecutionContext ec
    @Shared
    MCache testCache

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        testCache = ec.cache.getLocalCache("CacheFacadeTests")
    }

    def cleanupSpec() {
        ec.destroy()
    }

    def "add cache element"() {
        when:
        testCache.put("key1", "value1")
        int hitCountBefore = testCache.stats.getCacheHits()

        then:
        testCache.get("key1") == "value1"
        testCache.stats.getCacheHits() == hitCountBefore + 1

        cleanup:
        testCache.clear()
    }

    /* New caches doesn't support this (local/MCache doesn't support size limit, distributed/Hazelcast can't be changed on the fly like this:
    def "overflow cache size limit"() {
        when:
        testCache.setMaxElements(3, Cache.LEAST_RECENTLY_ADDED)
        testCache.put("key1", "value1")
        testCache.put("key2", "value2")
        testCache.put("key3", "value3")
        testCache.put("key4", "value4")
        int hitCountBefore = testCache.getHitCount()
        int removeCountBefore = testCache.getRemoveCount()
        int missCountBefore = testCache.getMissCountTotal()

        then:
        testCache.getEvictionStrategy() == Cache.LEAST_RECENTLY_ADDED
        testCache.getMaxElements() == 3
        testCache.size() == 3
        testCache.getRemoveCount() == removeCountBefore
        testCache.get("key1") == null
        !testCache.containsKey("key1")
        testCache.getMissCountTotal() == missCountBefore + 1
        testCache.get("key2") == "value2"
        testCache.getHitCount() == hitCountBefore + 1

        cleanup:
        testCache.clear()
        // go back to size limit defaults
        testCache.setMaxElements(10000, Cache.LEAST_RECENTLY_USED)
    }
    */

    def "get cache concurrently"() {
        def getCache = {
            ec.cache.getLocalCache("CacheFacadeConcurrencyTests")
        }
        when:
        def caches = ConcurrentExecution.executeConcurrently(10, getCache)

        then:
        caches.size == 10
        // all elements must be instances of the Cache class, no exceptions or nulls
        caches.every { item ->
            item instanceof MCache
        }
        // all elements must be references to the same object
        caches.every { item ->
            item.equals(caches[0])
        }
    }

    // TODO: test cache expire time
}
