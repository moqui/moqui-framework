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
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.FtsDdl
import org.moqui.impl.entity.FtsSql
import spock.lang.Shared
import spock.lang.Specification

class EntityFtsTests extends Specification {
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

    def "prefix patterns stay SQL LIKE and short tokens are dropped"() {
        expect:
        FtsSql.prefixOnly("hello%")
        !FtsSql.prefixOnly("%hello%")
        !FtsSql.prefixOnly("%hello")
        !FtsSql.prefixOnly("hel_o%")
        FtsSql.tokens(FtsSql.stripWildcards("%foo bar%")) == ["foo", "bar"]
        FtsSql.tokens("a to") == []
        !FtsSql.isDisabled("no-such-group")
    }

    def "text-fts LIKE matches both tokens and misses one token"() {
        given:
        String both = "FTSBOTH" + System.currentTimeMillis()
        String one = "FTSONE" + System.currentTimeMillis()
        ec.entity.makeValue("moqui.test.TestFts").setAll([testFtsId: both, note: "n", body: "warehouse then alpha"]).create()
        ec.entity.makeValue("moqui.test.TestFts").setAll([testFtsId: one, note: "n", body: "alpha only"]).create()

        when:
        EntityList hit = ec.entity.find("moqui.test.TestFts")
                .condition("body", EntityCondition.LIKE, "%alpha warehouse%").list()
        EntityList miss = ec.entity.find("moqui.test.TestFts")
                .condition("body", EntityCondition.LIKE, "%warehouse%").list()
        EntityValue eq = ec.entity.find("moqui.test.TestFts").condition("note", "n")
                .condition("testFtsId", both).one()
        EntityList prefix = ec.entity.find("moqui.test.TestFts")
                .condition("body", EntityCondition.LIKE, "alpha%").list()

        then:
        hit*.testFtsId.contains(both)
        !hit*.testFtsId.contains(one)
        miss*.testFtsId.contains(both)
        eq != null
        eq.note == "n"
        prefix*.testFtsId.contains(one)
        !prefix*.testFtsId.contains(both)
        !ec.entity.find("moqui.test.TestFts").condition("body", EntityCondition.NOT_LIKE, "%warehouse%")
                .condition("testFtsId", both).list()

        cleanup:
        ec.entity.find("moqui.test.TestFts").condition("testFtsId", both).deleteAll()
        ec.entity.find("moqui.test.TestFts").condition("testFtsId", one).deleteAll()
    }

    def "wildcard-only and short-word patterns keep plain LIKE meaning, and matching is case-insensitive"() {
        given:
        String id = "FTSSHORT" + System.currentTimeMillis()
        ec.entity.makeValue("moqui.test.TestFts").setAll([testFtsId: id, note: "s", body: "Zz abQxyz Mixed Case"]).create()

        when:
        def all = ec.entity.find("moqui.test.TestFts").condition("body", EntityCondition.LIKE, "%")
                .condition("testFtsId", id).list()
        def shortWord = ec.entity.find("moqui.test.TestFts").condition("body", EntityCondition.LIKE, "%Zz%")
                .condition("testFtsId", id).list()
        def mixed = ec.entity.find("moqui.test.TestFts").condition("body", EntityCondition.LIKE, "%mixed case%")
                .condition("testFtsId", id).list()

        then:
        all.size() == 1
        shortWord.size() == 1
        mixed.size() == 1

        cleanup:
        ec.entity.find("moqui.test.TestFts").condition("testFtsId", id).deleteAll()
    }

    def "adding a column to a table with an H2 full-text index does not break finds once the index is rebuilt"() {
        given:
        EntityFacadeImpl efi = (EntityFacadeImpl) ec.entity
        EntityDefinition ed = efi.getEntityDefinition("moqui.test.TestFts")
        String table = ed.getFullTableName()
        String id = "FTSALT" + System.currentTimeMillis()
        ec.entity.makeValue("moqui.test.TestFts").setAll([testFtsId: id, note: "n", body: "searchable alter words"]).create()
        ec.transaction.commit()
        ec.transaction.begin(null)

        when: "a column appears, as happens when an entity gains a field in an upgrade"
        def con = efi.getConnection(ed.getEntityGroupName())
        con.createStatement().withCloseable { it.execute("ALTER TABLE " + table + " ADD COLUMN FTS_ALTER_TMP VARCHAR(10)") }
        FtsDdl.ensure(efi, ed, null, true)
        def found = ec.entity.find("moqui.test.TestFts").condition("body", EntityCondition.LIKE, "%searchable alter%")
                .condition("testFtsId", id).list()

        then:
        found.size() == 1

        cleanup:
        def con2 = efi.getConnection(ed.getEntityGroupName())
        try { con2.createStatement().withCloseable { it.execute("ALTER TABLE " + table + " DROP COLUMN FTS_ALTER_TMP") } }
        catch (Throwable ignored) { }
        FtsDdl.ensure(efi, ed, null, true)
        ec.entity.find("moqui.test.TestFts").condition("testFtsId", id).deleteAll()
    }

    def "HOLD overlay find on text-fts still returns the row"() {
        given:
        String id = "FTSHOLD" + System.currentTimeMillis()

        when:
        ec.entity.startTxCacheDb(true)
        ec.entity.makeValue("moqui.test.TestFts").setAll([testFtsId: id, note: "hold", body: "overlay hello"]).create()
        EntityValue found = ec.entity.find("moqui.test.TestFts")
                .condition("body", EntityCondition.LIKE, "%hello%").condition("testFtsId", id).one()

        then:
        found != null
        found.body.toString().contains("hello")

        cleanup:
        if (ec.entity.isTxCacheActive()) ec.entity.stopTxCache()
    }
}
