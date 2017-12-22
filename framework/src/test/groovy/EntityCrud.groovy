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


import org.moqui.entity.EntityException
import org.moqui.entity.EntityList
import spock.lang.*

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.Moqui

class EntityCrud extends Specification {
    @Shared
    ExecutionContext ec

    def setupSpec() {
        // init the framework, get the ec
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
        ec.artifactExecution.enableAuthz()
        ec.transaction.commit()
    }

    def "create and find TestEntity CRDTST1"() {
        when:
        ec.entity.makeValue("moqui.test.TestEntity").setAll([testId:"CRDTST1", testMedium:"Test Name"]).create()
        EntityValue testEntity = ec.entity.find("moqui.test.TestEntity").condition("testId", "CRDTST1").one()

        then:
        testEntity.testMedium == "Test Name"
    }

    def "update TestEntity CRDTST1"() {
        when:
        EntityValue testEntity = ec.entity.find("moqui.test.TestEntity").condition("testId", "CRDTST1").one()
        testEntity.testMedium = "Test Name 2"
        testEntity.update()
        EntityValue testEntityCheck = ec.entity.find("moqui.test.TestEntity").condition([testId:"CRDTST1"]).one()

        then:
        testEntityCheck.testMedium == "Test Name 2"
    }

    def "update TestEntity CRDTST1 through cache"() {
        when:
        Exception immutableError = null
        EntityValue testEntity = ec.entity.find("moqui.test.TestEntity").condition("testId", "CRDTST1").useCache(true).one()
        try {
            testEntity.testMedium = "Test Name Cache"
        } catch (EntityException e) {
            immutableError = e
        }

        then:
        immutableError != null
    }

    def "update TestEntity from list through cache"() {
        when:
        Exception immutableError = null
        EntityList testEntityList = ec.entity.find("moqui.test.TestEntity").condition("testId", "CRDTST1").useCache(true).list()
        EntityValue testEntity = testEntityList.first()
        try {
            testEntity.testMedium = "Test Name List Cache"
        } catch (EntityException e) {
            immutableError = e
        }

        then:
        immutableError != null
    }

    def "delete TestEntity CRDTST1"() {
        when:
        ec.entity.find("moqui.test.TestEntity").condition([testId:"CRDTST1"]).one().delete()
        EntityValue testEntityCheck = ec.entity.find("moqui.test.TestEntity").condition([testId:"CRDTST1"]).one()

        then:
        testEntityCheck == null
    }

    def "delete EnumerationType cascade"() {
        when:
        ec.entity.makeValue("moqui.basic.EnumerationType").setAll([enumTypeId:"TEST_DEL_ET", description:"Test delete enum type"]).create()
        ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId:"TDELEN1", enumTypeId:"TEST_DEL_ET", description:"Test delete enum 1"]).create()
        ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId:"TDELEN2", enumTypeId:"TEST_DEL_ET", description:"Test delete enum 2"]).create()

        EntityValue enumType = ec.entity.find("moqui.basic.EnumerationType").condition("enumTypeId", "TEST_DEL_ET").one()
        EntityList enumsBefore = enumType.findRelatedFk(null)
        boolean gotExpectedError = false
        try {
            enumType.deleteWithCascade(null, new HashSet<String>())
        } catch (EntityException e) {
            gotExpectedError = true
        }
        EntityList enumsBetween = enumType.findRelatedFk(null)
        enumType.deleteWithCascade(null, null)
        EntityValue enumTypeAfter = ec.entity.find("moqui.basic.EnumerationType").condition("enumTypeId", "TEST_DEL_ET").one()
        EntityList enumsAfter = enumType.findRelatedFk(null)

        then:
        enumsBefore.size() == 2
        gotExpectedError
        enumsBetween.size() == 2
        enumTypeAfter == null
        enumsAfter.size() == 0
    }
}
