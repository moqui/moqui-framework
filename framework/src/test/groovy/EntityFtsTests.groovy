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
