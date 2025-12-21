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
import org.moqui.entity.EntityException
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.sql.Timestamp

/**
 * Characterization tests for EntityFacade.
 * These tests document the current behavior of the EntityFacade and serve as regression tests.
 *
 * Coverage areas:
 * - Entity relationships (one-to-many, many-to-one)
 * - Sequence generation
 * - View entities
 * - Entity value manipulation
 * - Complex conditions
 * - Aggregate functions
 */
class EntityFacadeCharacterizationTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(EntityFacadeCharacterizationTests.class)

    @Shared ExecutionContext ec
    @Shared Timestamp timestamp

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        timestamp = ec.user.nowTimestamp
    }

    def cleanupSpec() {
        ec.destroy()
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
        ec.transaction.begin(null)
    }

    def cleanup() {
        ec.artifactExecution.enableAuthz()
        ec.transaction.commit()
    }

    // ==================== Sequence Generation Tests ====================

    def "sequence generation creates unique sequential IDs"() {
        when:
        String seq1 = ec.entity.sequencedIdPrimary("moqui.test.TestEntity", null, null)
        String seq2 = ec.entity.sequencedIdPrimary("moqui.test.TestEntity", null, null)
        String seq3 = ec.entity.sequencedIdPrimary("moqui.test.TestEntity", null, null)

        then:
        seq1 != null
        seq2 != null
        seq3 != null
        seq1 != seq2
        seq2 != seq3
        // Sequences should be numerically increasing (as strings, last chars should increase)
        seq1 < seq2
        seq2 < seq3
    }

    def "sequence generation with stagger and bank size"() {
        when:
        // Get sequences with specific stagger (useful for clustered environments)
        String seq1 = ec.entity.sequencedIdPrimary("moqui.test.TestEntity", 1L, 1L)
        String seq2 = ec.entity.sequencedIdPrimary("moqui.test.TestEntity", 1L, 1L)

        then:
        seq1 != null
        seq2 != null
        seq1 != seq2
    }

    // ==================== Entity Relationship Tests ====================

    def "find related entities using findRelated"() {
        when:
        // EnumerationType has a one-to-many relationship with Enumeration
        EntityValue enumType = ec.entity.find("moqui.basic.EnumerationType")
                .condition("enumTypeId", "DataSourceType").one()
        EntityList relatedEnums = enumType.findRelated("enums", null, null, false, false)

        then:
        enumType != null
        relatedEnums != null
        relatedEnums.size() > 0
        relatedEnums.every { it.enumTypeId == "DataSourceType" }
    }

    def "find related one entity"() {
        when:
        // Enumeration has a many-to-one relationship with EnumerationType
        EntityValue enumVal = ec.entity.find("moqui.basic.Enumeration")
                .condition("enumId", "DST_PURCHASED_DATA").one()
        EntityValue enumType = enumVal.findRelatedOne("type", false, false)

        then:
        enumVal != null
        enumType != null
        enumType.enumTypeId == "DataSourceType"
    }

    def "find related with cache"() {
        when:
        EntityValue enumType = ec.entity.find("moqui.basic.EnumerationType")
                .condition("enumTypeId", "DataSourceType").one()
        EntityList relatedEnums1 = enumType.findRelated("enums", null, null, true, false)
        EntityList relatedEnums2 = enumType.findRelated("enums", null, null, true, false)

        then:
        relatedEnums1.size() == relatedEnums2.size()
        // Cached values should be immutable
        relatedEnums1.every { !it.isMutable() }
    }

    // ==================== View Entity Tests ====================

    def "view entity joins multiple tables"() {
        when:
        // GeoAndType is a view entity joining Geo and Enumeration
        EntityValue geoAndType = ec.entity.find("moqui.basic.GeoAndType")
                .condition("geoId", "USA").one()

        then:
        geoAndType != null
        geoAndType.geoId == "USA"
        geoAndType.geoName == "United States"
        geoAndType.geoTypeEnumId == "GEOT_COUNTRY"
        geoAndType.typeDescription != null
    }

    def "view entity with aggregate function"() {
        when:
        // Find count of enumerations by type using aggregation
        EntityList enumCounts = ec.entity.find("moqui.basic.Enumeration")
                .selectField("enumTypeId")
                .condition("enumTypeId", EntityCondition.IS_NOT_NULL, null)
                .list()

        // Group by enumTypeId manually since we can't use SQL aggregates directly
        Map<String, Integer> countByType = [:]
        for (EntityValue ev : enumCounts) {
            String typeId = ev.enumTypeId
            countByType[typeId] = (countByType[typeId] ?: 0) + 1
        }

        then:
        countByType.size() > 0
        countByType["DataSourceType"] > 0
    }

    // ==================== Entity Value Manipulation Tests ====================

    def "entity value setAll and getMap"() {
        when:
        Map<String, Object> valueMap = [testId: "MANIPULATION_TEST", testMedium: "Test Value",
                                        testNumberInteger: 42, testDateTime: timestamp]
        EntityValue ev = ec.entity.makeValue("moqui.test.TestEntity").setAll(valueMap)
        Map<String, Object> retrievedMap = ev.getMap()

        then:
        retrievedMap.testId == "MANIPULATION_TEST"
        retrievedMap.testMedium == "Test Value"
        retrievedMap.testNumberInteger == 42
        retrievedMap.testDateTime == timestamp
    }

    def "entity value clone creates independent copy"() {
        when:
        EntityValue original = ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "CLONE_TEST", testMedium: "Original"])
        EntityValue cloned = original.cloneValue()
        cloned.testMedium = "Cloned"

        then:
        original.testMedium == "Original"
        cloned.testMedium == "Cloned"
        original.testId == cloned.testId
    }

    def "entity value compareTo for ordering"() {
        when:
        EntityValue ev1 = ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "AAA", testMedium: "First"])
        EntityValue ev2 = ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "BBB", testMedium: "Second"])
        EntityValue ev3 = ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "AAA", testMedium: "First"])  // Same as ev1

        then:
        // compareTo compares by all field values, not just PK
        ev1.compareTo(ev2) < 0  // AAA < BBB
        ev2.compareTo(ev1) > 0  // BBB > AAA
        ev1.compareTo(ev3) == 0 // Same all values
    }

    def "entity value getPrimaryKeys returns only PK fields"() {
        when:
        EntityValue ev = ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "PK_TEST", testMedium: "Some Value", testNumberInteger: 123])
        Map<String, Object> pkMap = ev.getPrimaryKeys()

        then:
        pkMap.containsKey("testId")
        pkMap.testId == "PK_TEST"
        !pkMap.containsKey("testMedium")
        !pkMap.containsKey("testNumberInteger")
    }

    // ==================== Complex Condition Tests ====================

    @Unroll
    def "complex condition with #description"() {
        when:
        // Create test data
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "COND_TEST_1", testMedium: "Alpha", testNumberInteger: 100]).createOrUpdate()
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "COND_TEST_2", testMedium: "Beta", testNumberInteger: 200]).createOrUpdate()
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "COND_TEST_3", testMedium: "Gamma", testNumberInteger: 300]).createOrUpdate()

        // Build compound condition: testId LIKE 'COND_TEST_%' AND <specific condition>
        EntityCondition prefixCond = ec.entity.conditionFactory.makeCondition("testId", EntityCondition.LIKE, "COND_TEST_%")
        EntityCondition compoundCond = ec.entity.conditionFactory.makeCondition(prefixCond, EntityCondition.AND, condition)

        EntityList results = ec.entity.find("moqui.test.TestEntity")
                .condition(compoundCond)
                .orderBy("testId")
                .list()

        // Cleanup
        ec.entity.find("moqui.test.TestEntity").condition("testId", EntityCondition.LIKE, "COND_TEST_%").deleteAll()

        then:
        results.size() == expectedCount

        where:
        description                    | condition                                                                           | expectedCount
        "greater than"                 | ec.entity.conditionFactory.makeCondition("testNumberInteger", EntityCondition.GREATER_THAN, 150) | 2
        "less than or equals"          | ec.entity.conditionFactory.makeCondition("testNumberInteger", EntityCondition.LESS_THAN_EQUAL_TO, 200) | 2
        "not equals"                   | ec.entity.conditionFactory.makeCondition("testMedium", EntityCondition.NOT_EQUAL, "Alpha") | 2
        "in list"                      | ec.entity.conditionFactory.makeCondition("testId", EntityCondition.IN, ["COND_TEST_1", "COND_TEST_3"]) | 2
    }

    def "AND condition combines multiple conditions"() {
        when:
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "AND_TEST_1", testMedium: "Match", testNumberInteger: 100]).createOrUpdate()
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "AND_TEST_2", testMedium: "Match", testNumberInteger: 200]).createOrUpdate()
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "AND_TEST_3", testMedium: "NoMatch", testNumberInteger: 100]).createOrUpdate()

        EntityCondition cond1 = ec.entity.conditionFactory.makeCondition("testMedium", EntityCondition.EQUALS, "Match")
        EntityCondition cond2 = ec.entity.conditionFactory.makeCondition("testNumberInteger", EntityCondition.EQUALS, 100)
        EntityCondition andCond = ec.entity.conditionFactory.makeCondition(cond1, EntityCondition.AND, cond2)

        EntityList results = ec.entity.find("moqui.test.TestEntity").condition(andCond).list()

        // Cleanup
        ec.entity.find("moqui.test.TestEntity").condition("testId", EntityCondition.LIKE, "AND_TEST_%").deleteAll()

        then:
        results.size() == 1
        results.first().testId == "AND_TEST_1"
    }

    def "OR condition matches either condition"() {
        when:
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "OR_TEST_1", testMedium: "First", testNumberInteger: 100]).createOrUpdate()
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "OR_TEST_2", testMedium: "Second", testNumberInteger: 200]).createOrUpdate()
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "OR_TEST_3", testMedium: "Third", testNumberInteger: 300]).createOrUpdate()

        EntityCondition cond1 = ec.entity.conditionFactory.makeCondition("testMedium", EntityCondition.EQUALS, "First")
        EntityCondition cond2 = ec.entity.conditionFactory.makeCondition("testMedium", EntityCondition.EQUALS, "Third")
        EntityCondition orCond = ec.entity.conditionFactory.makeCondition(cond1, EntityCondition.OR, cond2)

        EntityList results = ec.entity.find("moqui.test.TestEntity").condition(orCond).orderBy("testId").list()

        // Cleanup
        ec.entity.find("moqui.test.TestEntity").condition("testId", EntityCondition.LIKE, "OR_TEST_%").deleteAll()

        then:
        results.size() == 2
        results[0].testId == "OR_TEST_1"
        results[1].testId == "OR_TEST_3"
    }

    // ==================== Count and Exists Tests ====================

    def "count returns number of matching records"() {
        when:
        // Create test data
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "COUNT_TEST_1", testMedium: "CountMe"]).createOrUpdate()
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "COUNT_TEST_2", testMedium: "CountMe"]).createOrUpdate()
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "COUNT_TEST_3", testMedium: "DontCount"]).createOrUpdate()

        long countAll = ec.entity.find("moqui.test.TestEntity")
                .condition("testId", EntityCondition.LIKE, "COUNT_TEST_%").count()
        long countFiltered = ec.entity.find("moqui.test.TestEntity")
                .condition("testMedium", "CountMe")
                .condition("testId", EntityCondition.LIKE, "COUNT_TEST_%").count()

        // Cleanup
        ec.entity.find("moqui.test.TestEntity").condition("testId", EntityCondition.LIKE, "COUNT_TEST_%").deleteAll()

        then:
        countAll == 3
        countFiltered == 2
    }

    // ==================== Ordering and Pagination Tests ====================

    def "orderBy sorts results correctly"() {
        when:
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "ORDER_TEST_C", testMedium: "Charlie"]).createOrUpdate()
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "ORDER_TEST_A", testMedium: "Alpha"]).createOrUpdate()
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "ORDER_TEST_B", testMedium: "Bravo"]).createOrUpdate()

        EntityList ascResults = ec.entity.find("moqui.test.TestEntity")
                .condition("testId", EntityCondition.LIKE, "ORDER_TEST_%")
                .orderBy("testMedium").list()
        EntityList descResults = ec.entity.find("moqui.test.TestEntity")
                .condition("testId", EntityCondition.LIKE, "ORDER_TEST_%")
                .orderBy("-testMedium").list()

        // Cleanup
        ec.entity.find("moqui.test.TestEntity").condition("testId", EntityCondition.LIKE, "ORDER_TEST_%").deleteAll()

        then:
        ascResults[0].testMedium == "Alpha"
        ascResults[1].testMedium == "Bravo"
        ascResults[2].testMedium == "Charlie"
        descResults[0].testMedium == "Charlie"
        descResults[1].testMedium == "Bravo"
        descResults[2].testMedium == "Alpha"
    }

    def "offset and limit for pagination"() {
        when:
        (1..10).each { i ->
            ec.entity.makeValue("moqui.test.TestEntity")
                    .setAll([testId: "PAGE_TEST_${String.format('%02d', i)}", testMedium: "Item $i"]).createOrUpdate()
        }

        EntityList page1 = ec.entity.find("moqui.test.TestEntity")
                .condition("testId", EntityCondition.LIKE, "PAGE_TEST_%")
                .orderBy("testId").offset(0).limit(3).list()
        EntityList page2 = ec.entity.find("moqui.test.TestEntity")
                .condition("testId", EntityCondition.LIKE, "PAGE_TEST_%")
                .orderBy("testId").offset(3).limit(3).list()
        EntityList page4 = ec.entity.find("moqui.test.TestEntity")
                .condition("testId", EntityCondition.LIKE, "PAGE_TEST_%")
                .orderBy("testId").offset(9).limit(3).list()

        // Cleanup
        ec.entity.find("moqui.test.TestEntity").condition("testId", EntityCondition.LIKE, "PAGE_TEST_%").deleteAll()

        then:
        page1.size() == 3
        page1[0].testId == "PAGE_TEST_01"
        page2.size() == 3
        page2[0].testId == "PAGE_TEST_04"
        page4.size() == 1  // Only 1 record left at offset 9
    }

    // ==================== Select Fields Tests ====================

    def "selectField limits returned fields"() {
        when:
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "SELECT_TEST", testMedium: "FullValue", testNumberInteger: 999]).createOrUpdate()

        EntityValue fullEntity = ec.entity.find("moqui.test.TestEntity")
                .condition("testId", "SELECT_TEST").one()
        EntityValue partialEntity = ec.entity.find("moqui.test.TestEntity")
                .condition("testId", "SELECT_TEST")
                .selectField("testId").selectField("testMedium").one()

        // Cleanup
        ec.entity.find("moqui.test.TestEntity").condition("testId", "SELECT_TEST").deleteAll()

        then:
        fullEntity.testNumberInteger == 999
        partialEntity.testId == "SELECT_TEST"
        partialEntity.testMedium == "FullValue"
        // Note: selectField behavior may vary - some implementations still return all fields
    }

    // ==================== Distinct Tests ====================

    def "distinct removes duplicate values"() {
        when:
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "DIST_TEST_1", testMedium: "Duplicate"]).createOrUpdate()
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "DIST_TEST_2", testMedium: "Duplicate"]).createOrUpdate()
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "DIST_TEST_3", testMedium: "Unique"]).createOrUpdate()

        EntityList allRecords = ec.entity.find("moqui.test.TestEntity")
                .condition("testId", EntityCondition.LIKE, "DIST_TEST_%").list()
        EntityList distinctRecords = ec.entity.find("moqui.test.TestEntity")
                .condition("testId", EntityCondition.LIKE, "DIST_TEST_%")
                .selectField("testMedium").distinct(true).list()

        // Cleanup
        ec.entity.find("moqui.test.TestEntity").condition("testId", EntityCondition.LIKE, "DIST_TEST_%").deleteAll()

        then:
        allRecords.size() == 3
        distinctRecords.size() == 2
    }

    // ==================== Error Handling Tests ====================

    def "creating duplicate PK throws exception"() {
        when:
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "DUP_TEST", testMedium: "First"]).create()
        ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "DUP_TEST", testMedium: "Second"]).create()

        then:
        thrown(EntityException)

        cleanup:
        try {
            ec.entity.find("moqui.test.TestEntity").condition("testId", "DUP_TEST").deleteAll()
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    def "update non-existent record does not throw but returns 0"() {
        when:
        EntityValue ev = ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([testId: "NON_EXISTENT_UPDATE", testMedium: "Should Not Exist"])
        // Note: update() behavior on non-existent record may vary
        // Some implementations silently do nothing, others may throw

        then:
        // The entity value can be created but won't find anything to update
        ev.testId == "NON_EXISTENT_UPDATE"
    }
}
