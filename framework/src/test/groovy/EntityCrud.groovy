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

    def "create and find Example CRDTST1"() {
        when:
        ec.entity.makeValue("moqui.example.Example").setAll([exampleId:"CRDTST1", exampleName:"Test Name"]).createOrUpdate()

        then:
        EntityValue example = ec.entity.find("moqui.example.Example").condition("exampleId", "CRDTST1").one()
        example.exampleName == "Test Name"
    }

    def "update Example CRDTST1"() {
        when:
        EntityValue example = ec.entity.find("moqui.example.Example").condition("exampleId", "CRDTST1").one()
        example.exampleName = "Test Name 2"
        example.update()

        then:
        EntityValue exampleCheck = ec.entity.find("moqui.example.Example").condition([exampleId:"CRDTST1"]).one()
        exampleCheck.exampleName == "Test Name 2"
    }

    def "update Example CRDTST1 through cache"() {
        when:
        Exception immutableError = null
        EntityValue example = ec.entity.find("moqui.example.Example").condition("exampleId", "CRDTST1").useCache(true).one()
        try {
            example.exampleName = "Test Name Cache"
        } catch (EntityException e) {
            immutableError = e
        }

        then:
        immutableError != null
    }

    def "update Example from list through cache"() {
        when:
        Exception immutableError = null
        EntityList exampleList = ec.entity.find("moqui.example.Example").condition("exampleId", "CRDTST1").useCache(true).list()
        EntityValue example = exampleList.first()
        try {
            example.exampleName = "Test Name List Cache"
        } catch (EntityException e) {
            immutableError = e
        }

        then:
        immutableError != null
    }

    def "delete Example CRDTST1"() {
        when:
        ec.entity.find("moqui.example.Example").condition([exampleId:"CRDTST1"]).one().delete()

        then:
        EntityValue exampleCheck = ec.entity.find("moqui.example.Example").condition([exampleId:"CRDTST1"]).one()
        exampleCheck == null
    }
}
