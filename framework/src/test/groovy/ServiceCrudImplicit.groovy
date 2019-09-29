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

import spock.lang.*

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.Moqui

class ServiceCrudImplicit extends Specification {
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
    }

    def cleanup() {
        ec.artifactExecution.enableAuthz()
    }

    def "create and find TestEntity SVCTST1 with service"() {
        when:
        ec.service.sync().name("create#moqui.test.TestEntity").parameters([testId:"SVCTST1", testMedium:"Test Name"]).call()
        EntityValue testEntity = ec.entity.find("moqui.test.TestEntity").condition([testId:"SVCTST1"]).one()

        then:
        testEntity.testMedium == "Test Name"
    }

    def "update TestEntity SVCTST1 with service"() {
        when:
        ec.service.sync().name("update#moqui.test.TestEntity").parameters([testId:"SVCTST1", testMedium:"Test Name 2"]).call()
        EntityValue testEntityCheck = ec.entity.find("moqui.test.TestEntity").condition([testId:"SVCTST1"]).one()

        then:
        testEntityCheck.testMedium == "Test Name 2"
    }

    def "store update TestEntity SVCTST1 with service"() {
        when:
        ec.service.sync().name("store#moqui.test.TestEntity").parameters([testId:"SVCTST1", testMedium:"Test Name 3"]).call()
        EntityValue testEntityCheck = ec.entity.find("moqui.test.TestEntity").condition([testId:"SVCTST1"]).one()

        then:
        testEntityCheck.testMedium == "Test Name 3"
    }

    def "delete TestEntity SVCTST1 with service"() {
        when:
        ec.service.sync().name("delete#moqui.test.TestEntity").parameters([testId:"SVCTST1"]).call()
        EntityValue testEntityCheck = ec.entity.find("moqui.test.TestEntity").condition([testId:"SVCTST1"]).one()

        then:
        testEntityCheck == null
    }

    def "store create TestEntity TEST_A with service"() {
        when:
        ec.service.sync().name("store#moqui.test.TestEntity").parameters([testId:"SVCTSTA", testMedium:"Test Name A"]).call()
        EntityValue testEntityCheck = ec.entity.find("moqui.test.TestEntity").condition([testId:"SVCTSTA"]).one()

        then:
        testEntityCheck.testMedium == "Test Name A"
    }

    def "create and find TestIntPk 123 with service"() {
        when:
        // create with String for ID though is type number-integer, test single PK type conversion
        ec.service.sync().name("create#moqui.test.TestIntPk").parameters([intId:"123", testMedium:"Test Name"]).call()
        EntityValue testString = ec.entity.find("moqui.test.TestIntPk").condition([intId:"123"]).one()
        EntityValue testInt = ec.entity.find("moqui.test.TestIntPk").condition([intId:123]).one()

        then:
        testString?.testMedium == "Test Name"
        testInt?.testMedium == "Test Name"
    }

}
