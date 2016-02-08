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


import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.*

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.Moqui
import java.sql.Timestamp
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityList

class EntityFindTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(EntityFindTests.class)

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
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"EXTST1", testIndicator:null,
                testLong:"", testMedium:"Test Name",
                testNumberInteger:4321, testDateTime:timestamp]).createOrUpdate()
    }

    def cleanup() {
        ec.entity.makeValue("moqui.test.TestEntity").set("testId", "EXTST1").delete()
        ec.artifactExecution.enableAuthz()
        ec.transaction.commit()
    }

    @Unroll
    def "find TestEntity by single condition (#fieldName = #value)"() {
        expect:
        EntityValue testEntity = ec.entity.find("moqui.test.TestEntity").condition(fieldName, value).one()
        testEntity != null
        testEntity.testId == "EXTST1"

        where:
        fieldName | value
        "testId" | "EXTST1"
        // fails on some DBs without pre-JDBC type conversion: "testNumberInteger" | "4321"
        "testNumberInteger" | 4321
        // fails on some DBs without pre-JDBC type conversion: "testDateTime" | ec.l10n.format(timestamp, "yyyy-MM-dd HH:mm:ss.SSS")
        "testDateTime" | timestamp
    }

    @Unroll
    def "find TestEntity by operator condition (#fieldName #operator #value)"() {
        expect:
        EntityValue testEntity = ec.entity.find("moqui.test.TestEntity").condition(fieldName, operator, value).one()
        testEntity != null
        testEntity.testId == "EXTST1"

        where:
        fieldName | operator | value
        "testId" | EntityCondition.BETWEEN | ["EXTST0", "EXTST2"]
        "testId" | EntityCondition.EQUALS | "EXTST1"
        "testId" | EntityCondition.IN | ["EXTST1"]
        "testId" | EntityCondition.LIKE | "%XTST%"
    }

    @Unroll
    def "find TestEntity by searchFormMap (#inputsMap #resultId)"() {
        expect:
        EntityValue testEntity = ec.entity.find("moqui.test.TestEntity").searchFormMap(inputsMap, "", false).one()
        resultId ? testEntity != null && testEntity.testId == resultId : testEntity == null

        where:
        inputsMap | resultId
        [testId: "EXTST1", testId_op: "equals"] | "EXTST1"
        [testId: "%XTST%", testId_op: "like"] | "EXTST1"
        [testId: "XTST", testId_op: "contains"] | "EXTST1"
        [testMedium:"Test Name", testIndicator_op: "empty"] | "EXTST1"
        [testMedium:"Test Name", testLong_op: "empty"] | "EXTST1"
        [testMedium:"Test Name", testDateTime_from: "", testDateTime_thru: ""] | "EXTST1"
        [testMedium:"Test Name", testDateTime_from: timestamp, testDateTime_thru: timestamp] | null
        [testMedium:"Test Name", testDateTime_from: timestamp, testDateTime_thru: timestamp + 1] | "EXTST1"
        [testNumberInteger:4321, testMedium_not: "Y", testMedium_op: "equals", testMedium: ""] | "EXTST1"
        [testNumberInteger:4321, testMedium_not: "Y", testMedium_op: "empty"] | "EXTST1"
    }

    def "auto cache clear for list"() {
        // update the testMedium and make sure we get the new value
        when:
        ec.entity.find("moqui.test.TestEntity").condition("testNumberInteger", 4321).useCache(true).list()
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"EXTST1", testMedium:"Test Name 2"]).update()
        EntityList testEntityList = ec.entity.find("moqui.test.TestEntity")
                .condition("testNumberInteger", 4321).useCache(true).list()

        then:
        testEntityList.size() == 1
        testEntityList.first.testMedium == "Test Name 2"
    }

    def "auto cache clear for one by primary key"() {
        when:
        ec.entity.find("moqui.test.TestEntity").condition("testId", "EXTST1").useCache(true).one()
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"EXTST1", testMedium:"Test Name 3"]).update()
        EntityValue testEntity = ec.entity.find("moqui.test.TestEntity").condition("testId", "EXTST1").useCache(true).one()

        then:
        testEntity.testMedium == "Test Name 3"
    }

    def "auto cache clear for one by non-primary key"() {
        when:
        ec.entity.find("moqui.test.TestEntity").condition([testNumberInteger:4321, testDateTime:timestamp]).useCache(true).one()
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"EXTST1", testMedium:"Test Name 4"]).update()
        EntityValue testEntity = ec.entity.find("moqui.test.TestEntity")
                .condition([testNumberInteger:4321, testDateTime:timestamp]).useCache(true).one()

        then:
        testEntity.testMedium == "Test Name 4"
    }

    def "auto cache clear for one by non-pk and initially no result"() {
        when:
        EntityValue testEntity1 = ec.entity.find("moqui.test.TestEntity").condition([testMedium:"Test Name 5"]).useCache(true).one()
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"EXTST1", testMedium:"Test Name 5"]).update()
        EntityValue testEntity2 = ec.entity.find("moqui.test.TestEntity").condition([testMedium:"Test Name 5"]).useCache(true).one()

        then:
        testEntity1 == null
        testEntity2 != null
        testEntity2.testMedium == "Test Name 5"
    }
}
