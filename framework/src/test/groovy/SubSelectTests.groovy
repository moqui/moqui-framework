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
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Ignore
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.sql.Timestamp

class SubSelectTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(SubSelectTests.class)

    @Shared
    ExecutionContext ec
    @Shared
    Timestamp timestamp

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        timestamp = ec.user.nowTimestamp
    }

    def cleanupSpec() {
        ec.destroy()
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
        // create some entity to trigger the table creation.
        ec.entity.makeValue("moqui.test.Foo").setAll([fooId:"EXTST1"]).createOrUpdate()
        ec.entity.makeValue("moqui.test.Bar").setAll([barId:"EXTST1"]).createOrUpdate()
        ec.entity.makeValue("moqui.test.Foo").setAll([fooId:"EXTST1"]).delete()
        ec.entity.makeValue("moqui.test.Bar").setAll([barId:"EXTST1"]).delete()
    }

    def cleanup() {
        ec.artifactExecution.enableAuthz()
        ec.transaction.commit()
    }

    def "find subselect search form equal"() {
        when:
        EntityFind find =  ec.entity.find("moqui.test.FooBar").searchFormMap(["rank":100], null,null,null,true)
        EntityList list = find.list()

        then:
        list.isEmpty()
        find.getQueryTextList()[0].contains(" RANK = ? ")
    }

    def "find subselect search form range"() {
        when:
        EntityFind find = ec.entity.find("moqui.test.FooBar").searchFormMap(["rank_from":100], null,null,null,true)
        EntityList list = find.list()

        then:
        list.isEmpty()
        find.getQueryTextList()[0].contains(" RANK >= ? ")
    }
}
