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

import org.moqui.BaseException
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.TransactionCacheDb
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.FieldInfo
import org.moqui.impl.entity.OverlayColumnNames
import org.moqui.util.RestClient
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
        ((ExecutionContextImpl) ec).simSession = false
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

    def "HOLD paged list applies offset once and partial-field selects do not null other columns"() {
        given:
        (1..5).each { i ->
            ec.entity.makeValue("moqui.test.TestEntity").setAll([testId: "TCPG" + i, testMedium: "m" + i, testLong: "l" + i]).create()
        }
        ec.transaction.commit()
        ec.transaction.begin(null)

        when:
        ec.entity.startTxCacheDb(true)
        def page = ec.entity.find("moqui.test.TestEntity").condition("testId", EntityCondition.LIKE, "TCPG%")
                .orderBy("testId").offset(2).limit(2).list()
        def partial = ec.entity.find("moqui.test.TestEntity").condition("testId", EntityCondition.LIKE, "TCPG%")
                .selectField("testId").orderBy("testId").list()
        def full = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCPG1").one()
        def counted = ec.entity.find("moqui.test.TestEntity").condition("testId", EntityCondition.LIKE, "TCPG%").limit(1).count()

        then:
        page*.testId == ["TCPG3", "TCPG4"]
        partial.size() == 5
        full.testMedium == "m1"
        full.testLong == "l1"
        counted == 5

        cleanup:
        if (ec.entity.isTxCacheActive()) ec.entity.stopTxCache()
        (1..5).each { i -> ec.entity.find("moqui.test.TestEntity").condition("testId", "TCPG" + i).one()?.delete() }
    }

    def "HOLD create of a row that exists in production fails like production, and update of a missing row fails"() {
        given:
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId: "TCDUP1", testMedium: "real"]).create()
        ec.transaction.commit()
        ec.transaction.begin(null)

        when:
        ec.entity.startTxCacheDb(true)
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId: "TCDUP1", testMedium: "dup"]).create()

        then:
        thrown(org.moqui.entity.EntityException)

        when:
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId: "TCNOROW", testMedium: "x"]).update()

        then:
        thrown(org.moqui.entity.EntityException)

        cleanup:
        if (ec.entity.isTxCacheActive()) ec.entity.stopTxCache()
        ec.entity.find("moqui.test.TestEntity").condition("testId", "TCDUP1").one()?.delete()
    }

    def "a rolled-back transaction that wrote to the sim terminates it, one that only read does not"() {
        when: "a read-only transaction rolls back"
        ec.entity.startTxCacheDb(true)
        ec.entity.find("moqui.test.TestEntity").condition("testId", "NOPE").one()
        ec.transaction.rollback("test rollback after read", null)
        ec.transaction.begin(null)
        def afterRead = ec.entity.find("moqui.test.TestEntity").condition("testId", "NOPE").one()

        then:
        afterRead == null
        !((TransactionCacheDb) ((EntityFacadeImpl) ec.entity).getActiveTxCache()).isPoisoned()

        when: "a transaction that wrote rolls back"
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId: "TCPOI1", testMedium: "overlay"]).create()
        ec.transaction.rollback("test rollback after write", null)
        ec.transaction.begin(null)
        ec.entity.find("moqui.test.TestEntity").condition("testId", "TCPOI1").one()

        then:
        def e = thrown(org.moqui.impl.context.SimTerminatedException)
        e.message.contains("Start a new sim")
        ((TransactionCacheDb) ((EntityFacadeImpl) ec.entity).getActiveTxCache()).isPoisoned()
    }

    def "a committed transaction that wrote to the sim keeps it running"() {
        when:
        ec.entity.startTxCacheDb(true)
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId: "TCCOM1", testMedium: "overlay"]).create()
        ec.transaction.commit()
        ec.transaction.begin(null)
        def found = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCCOM1").one()
        then:
        found != null
        !((TransactionCacheDb) ((EntityFacadeImpl) ec.entity).getActiveTxCache()).isPoisoned()
    }

    def "flush mode is no longer supported"() {
        when:
        ec.entity.startTxCacheDb(false)
        then:
        thrown(UnsupportedOperationException)
        !ec.entity.isTxCacheActive()
    }

    def "public getConnection is refused in a sim session but not otherwise"() {
        when:
        ((ExecutionContextImpl) ec).simSession = true
        ec.entity.getConnection("transactional")
        then:
        thrown(BaseException)

        when:
        ((ExecutionContextImpl) ec).simSession = false
        def con = ec.entity.getConnection("transactional")
        then:
        con != null
    }

    def "every moqui.llm entity is classified as production-write, so a new one forces a decision"() {
        expect:
        def llmEntities = ((EntityFacadeImpl) ec.entity).getAllEntityNames().findAll { it.startsWith("moqui.llm.") }
        llmEntities
        llmEntities.every { TransactionCacheDb.PRODUCTION_ENTITIES.contains(it) }
        !TransactionCacheDb.PRODUCTION_ENTITIES.contains("moqui.test.TestEntity")
    }

    def "find by a non-key field sees sim updates and creates, and not the stale production row"() {
        given:
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId: "TCNPK1", testMedium: "npk-orig"]).create()
        ec.transaction.commit()
        ec.transaction.begin(null)

        when:
        ec.entity.startTxCacheDb(true)
        def ev = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCNPK1").one()
        ev.testMedium = "npk-changed"
        ev.update()
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId: "TCNPK2", testMedium: "npk-new"]).create()
        def byNewValue = ec.entity.find("moqui.test.TestEntity").condition("testMedium", "npk-changed").one()
        def byOldValue = ec.entity.find("moqui.test.TestEntity").condition("testMedium", "npk-orig").one()
        def byCreated = ec.entity.find("moqui.test.TestEntity").condition("testMedium", "npk-new").one()
        def keyPlusOther = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCNPK1").condition("testMedium", "npk-orig").one()

        then:
        byNewValue?.testId == "TCNPK1"
        byOldValue == null
        byCreated?.testId == "TCNPK2"
        // Moqui find-one with a full primary key ignores extra conditions, in production and in the sim
        keyPlusOther?.testId == "TCNPK1"

        cleanup:
        if (ec.entity.isTxCacheActive()) ec.entity.stopTxCache()
        ec.entity.find("moqui.test.TestEntity").condition("testId", "TCNPK1").one()?.delete()
    }

    def "aggregate view counts production rows and sim changes together, and uses production directly when unchanged"() {
        given:
        ec.entity.makeValue("moqui.test.Foo").setAll([fooId: "TCAGG1", fooText: "agg"]).create()
        ec.entity.makeValue("moqui.test.Bar").setAll([barId: "TCAGGB1", fooId: "TCAGG1", barRank: 1, score: 2]).create()
        ec.transaction.commit()
        ec.transaction.begin(null)

        when: "nothing in the view changed in the sim"
        ec.entity.startTxCacheDb(true)
        def before = ec.entity.find("moqui.test.FooBar").condition("fooId", "TCAGG1").list()

        then:
        before.size() == 1
        before[0].score == 2

        when: "the sim adds a Bar to the same Foo and rank"
        ec.entity.makeValue("moqui.test.Bar").setAll([barId: "TCAGGB2", fooId: "TCAGG1", barRank: 1, score: 3]).create()
        def after = ec.entity.find("moqui.test.FooBar").condition("fooId", "TCAGG1").list()
        def worldScore = null
        TransactionCacheDb db = (TransactionCacheDb) ((EntityFacadeImpl) ec.entity).getActiveTxCache()
        db.beginBypass()
        try { worldScore = ec.entity.find("moqui.test.FooBar").condition("fooId", "TCAGG1").list()[0].score }
        finally { db.endBypass() }

        then:
        after.size() == 1
        after[0].score == 5
        worldScore == 2

        cleanup:
        if (ec.entity.isTxCacheActive()) ec.entity.stopTxCache()
        ec.entity.find("moqui.test.Bar").condition("fooId", "TCAGG1").deleteAll()
        ec.entity.find("moqui.test.Foo").condition("fooId", "TCAGG1").deleteAll()
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

    def "HOLD partial update does not null other columns"() {
        when:
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"TCPART1", testMedium:"keep-me", testLong:"long-keep"]).create()
        ec.transaction.commit()
        ec.transaction.begin(null)

        ec.entity.startTxCacheDb(true)
        ec.entity.makeValue("moqui.test.TestEntity").set("testId", "TCPART1").set("testMedium", "changed").update()
        EntityValue overlay = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCPART1").one()
        EntityValue world = worldFind("moqui.test.TestEntity", "testId", "TCPART1")

        then:
        overlay.testMedium == "changed"
        overlay.testLong == "long-keep"
        world.testMedium == "keep-me"
        world.testLong == "long-keep"

        cleanup:
        if (ec.entity.isTxCacheActive()) ec.entity.stopTxCache()
        ec.entity.find("moqui.test.TestEntity").condition("testId", "TCPART1").one()?.delete()
    }

    def "sqlFind is refused in sim"() {
        when:
        ((org.moqui.impl.context.ExecutionContextImpl) ec).simSession = true
        ec.entity.sqlFind("select TEST_ID from TEST_ENTITY", null, "moqui.test.TestEntity", ["testId"])

        then:
        thrown(Exception)

        cleanup:
        ((org.moqui.impl.context.ExecutionContextImpl) ec).simSession = false
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

    def "RestClient is refused in simSession"() {
        when:
        ((ExecutionContextImpl) ec).simSession = true
        new RestClient().uri("http://127.0.0.1:9/").timeout(1).call()
        then:
        BaseException e = thrown()
        e.message.contains("sim session")
    }

    def "RestClient allowInSim is not the sim fence"() {
        when:
        ((ExecutionContextImpl) ec).simSession = true
        new RestClient().allowInSim(true).uri("http://127.0.0.1:1/").timeout(1).call()
        then:
        BaseException e = thrown()
        !e.message.contains("sim session")
    }

    def "async service is skipped in simSession"() {
        when:
        ((ExecutionContextImpl) ec).simSession = true
        def future = ec.service.async().name("create#moqui.test.TestEntity")
                .parameter("testId", "TCSIMASYNC").parameter("testMedium", "nope").callFuture()
        def result = future.get()
        EntityValue world = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCSIMASYNC").one()
        then:
        result.simSkipped == true
        world == null
    }

    def "overlay column names follow the H2 name-replace list"() {
        when:
        EntityDefinition ed = ((EntityFacadeImpl) ec.entity).getEntityDefinition("moqui.test.TestEntity")
        FieldInfo fi = ed.getFieldInfo("value")
        def h2 = ((EntityFacadeImpl) ec.entity).getDatabaseNodeByConf("h2")
        def postgres = ((EntityFacadeImpl) ec.entity).getDatabaseNodeByConf("postgres")
        String full
        OverlayColumnNames.setActive(true)
        try {
            full = fi.getFullColumnName()
        } finally {
            OverlayColumnNames.setActive(false)
        }
        ec.entity.startTxCacheDb(true)
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId:"TCVAL1", testMedium:"m", value:"kept"]).create()
        EntityValue overlay = ec.entity.find("moqui.test.TestEntity").condition("testId", "TCVAL1").one()

        then:
        OverlayColumnNames.rawName(fi) == "VALUE"
        OverlayColumnNames.apply(h2, "VALUE") == "THE_VALUE"
        OverlayColumnNames.apply(postgres, "VALUE") == "VALUE"
        OverlayColumnNames.apply(h2, "THE_VALUE") == "THE_VALUE"
        OverlayColumnNames.column(fi) == "THE_VALUE"
        full == "THE_VALUE"
        overlay != null
        overlay.value == "kept"

        cleanup:
        if (ec.entity.isTxCacheActive()) ec.entity.stopTxCache()
        ec.entity.find("moqui.test.TestEntity").condition("testId", "TCVAL1").one()?.delete()
    }
}
