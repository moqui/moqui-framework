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
import org.moqui.entity.EntityValue
import org.moqui.impl.context.TransactionCacheDb
import org.moqui.impl.entity.EntityFacadeImpl
import spock.lang.Shared
import spock.lang.Specification

class TransactionCacheDbTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
    }
    def cleanupSpec() {
        ec.destroy()
    }
    def setup() {
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
    }
    def cleanup() {
        if (ec.entity.isTxCacheActive()) ec.entity.stopTxCache()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        ec.artifactExecution.enableAuthz()
    }

    private EntityValue worldFind(String entityName, String pkField, String pkVal) {
        TransactionCacheDb db = (TransactionCacheDb) ((EntityFacadeImpl) ec.entity).getActiveTxCache()
        db.beginBypass()
        try {
            return ec.entity.find(entityName).condition(pkField, pkVal).one()
        } finally {
            db.endBypass()
        }
    }

    def "HOLD create is not in production"() {
        when:
        ec.entity.startTxCacheDb(true)
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"TCHOLD1", testMedium:"overlay"]).create()
        EntityValue overlay = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCHOLD1").one()
        EntityValue world = worldFind("moqui.test.TestEntity", "testId", "TCHOLD1")

        then:
        overlay != null
        overlay.testMedium == "overlay"
        world == null
    }

    def "HOLD update does not change production"() {
        when:
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"TCUPD1", testMedium:"orig"]).create()
        ec.transaction.commit()
        ec.transaction.begin(null)

        ec.entity.startTxCacheDb(true)
        EntityValue ev = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCUPD1").one()
        ev.testMedium = "changed"
        ev.update()
        EntityValue overlay = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCUPD1").one()
        EntityValue world = worldFind("moqui.test.TestEntity", "testId", "TCUPD1")

        then:
        overlay.testMedium == "changed"
        world.testMedium == "orig"

        cleanup:
        if (ec.entity.isTxCacheActive()) ec.entity.stopTxCache()
        ec.entity.find("moqui.test.TestEntity").condition("testId", "TCUPD1").one()?.delete()
    }

    def "HOLD delete does not remove production and copy-on-read does not resurrect"() {
        when:
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"TCDEL1", testMedium:"keep"]).create()
        ec.transaction.commit()
        ec.transaction.begin(null)

        ec.entity.startTxCacheDb(true)
        ec.entity.find("moqui.test.TestEntity").condition("testId", "TCDEL1").one().delete()
        EntityValue overlay = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCDEL1").one()
        EntityValue world = worldFind("moqui.test.TestEntity", "testId", "TCDEL1")

        then:
        overlay == null
        world != null
        world.testMedium == "keep"

        cleanup:
        if (ec.entity.isTxCacheActive()) ec.entity.stopTxCache()
        ec.entity.find("moqui.test.TestEntity").condition("testId", "TCDEL1").one()?.delete()
    }

    def "HOLD view-entity sees overlay creates"() {
        when:
        ec.entity.startTxCacheDb(true)
        ec.entity.makeValue("moqui.test.Foo").setAll([fooId:"TCFOO1", fooText:"hello"]).create()
        ec.entity.makeValue("moqui.test.Bar").setAll([barId:"TCBAR1", fooId:"TCFOO1", barRank:1, score:2.5]).create()
        EntityValue view = ec.entity.find("moqui.test.FooBar").condition("fooId", "TCFOO1").one()
        EntityValue worldFoo = worldFind("moqui.test.Foo", "fooId", "TCFOO1")

        then:
        view != null
        view.fooText == "hello"
        worldFoo == null
    }

    def "HOLD survives begin and commit cycles"() {
        when:
        ec.entity.startTxCacheDb(true)
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"TCTX1", testMedium:"across"]).create()
        ec.transaction.commit()
        ec.transaction.begin(null)
        EntityValue overlay = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCTX1").one()
        EntityValue world = worldFind("moqui.test.TestEntity", "testId", "TCTX1")

        then:
        overlay != null
        overlay.testMedium == "across"
        world == null
    }

    def "HOLD does not bump SequenceValueItem"() {
        when:
        Long before = ec.entity.find("moqui.entity.SequenceValueItem")
                .condition("seqName", "moqui.test.TestEntity").useCache(false).one()?.seqNum as Long
        ec.entity.startTxCacheDb(true)
        String seq = ec.entity.sequencedIdPrimary("moqui.test.TestEntity", null, null)
        Long after = ec.entity.find("moqui.entity.SequenceValueItem")
                .condition("seqName", "moqui.test.TestEntity").useCache(false).one()?.seqNum as Long

        then:
        seq != null
        Long.parseLong(seq) >= 900000000L
        before == after
    }

    def "HOLD does not clear unrelated entity cache"() {
        when:
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"TCCACHE1", testMedium:"cached"]).create()
        EntityValue cached = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCCACHE1").useCache(true).one()
        ec.entity.startTxCacheDb(true)
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"TCCACHE2", testMedium:"overlay"]).create()
        EntityValue still = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCCACHE1").useCache(true).one()

        then:
        cached.testMedium == "cached"
        still.testMedium == "cached"

        cleanup:
        if (ec.entity.isTxCacheActive()) ec.entity.stopTxCache()
        ec.entity.find("moqui.test.TestEntity").condition("testId", "TCCACHE1").one()?.delete()
    }

    def "FLUSH create appears in production on stop"() {
        when:
        ec.entity.startTxCacheDb(false)
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"TCFLUSH1", testMedium:"flushed"]).create()
        EntityValue during = worldFind("moqui.test.TestEntity", "testId", "TCFLUSH1")
        ec.entity.stopTxCache()
        EntityValue after = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCFLUSH1").one()

        then:
        during == null
        after != null
        after.testMedium == "flushed"

        cleanup:
        ec.entity.find("moqui.test.TestEntity").condition("testId", "TCFLUSH1").one()?.delete()
    }
}
