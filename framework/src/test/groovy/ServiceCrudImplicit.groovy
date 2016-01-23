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

    def "create and find Example SVCTST1 with service"() {
        when:
        // do a "store" to create or update
        ec.service.sync().name("store#moqui.example.Example").parameters([exampleId:"SVCTST1", exampleName:"Test Name"]).call()

        then:
        EntityValue example = ec.entity.find("moqui.example.Example").condition([exampleId:"SVCTST1"]).one()
        example.exampleName == "Test Name"
    }

    def "update Example SVCTST1 with service"() {
        when:
        ec.service.sync().name("update#moqui.example.Example").parameters([exampleId:"SVCTST1", exampleName:"Test Name 2"]).call()

        then:
        EntityValue exampleCheck = ec.entity.find("moqui.example.Example").condition([exampleId:"SVCTST1"]).one()
        exampleCheck.exampleName == "Test Name 2"
    }

    def "store update Example SVCTST1 with service"() {
        when:
        ec.service.sync().name("store#moqui.example.Example").parameters([exampleId:"SVCTST1", exampleName:"Test Name 3"]).call()

        then:
        EntityValue exampleCheck = ec.entity.find("moqui.example.Example").condition([exampleId:"SVCTST1"]).one()
        exampleCheck.exampleName == "Test Name 3"
    }

    def "delete Example SVCTST1 with service"() {
        when:
        ec.service.sync().name("delete#moqui.example.Example").parameters([exampleId:"SVCTST1"]).call()

        then:
        EntityValue exampleCheck = ec.entity.find("moqui.example.Example").condition([exampleId:"SVCTST1"]).one()
        exampleCheck == null
    }

    /* No real point to this, muddies data
    def "store create Example TEST_A with service"() {
        when:
        ec.service.sync().name("store#moqui.example.Example").parameters([exampleId:"TEST_A", exampleName:"Test Name A"]).call()

        then:
        EntityValue exampleCheck = ec.entity.find("moqui.example.Example").condition([exampleId:"TEST_A"]).one()
        exampleCheck.exampleName == "Test Name A"
    }
    */
}
