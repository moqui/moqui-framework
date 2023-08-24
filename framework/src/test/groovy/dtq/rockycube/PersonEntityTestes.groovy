package dtq.rockycube

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class PersonEntityTestes  extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(PersonEntityTestes.class)

    @Shared
    ExecutionContext ec

    def setupSpec() {
        // set other conf - this configuration contains definition of Closure-related entities
        System.setProperty("moqui.conf", "../framework/src/test/resources/person-entity-conf/PersonEntityTestConf.xml")

        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.hardy', 'moqui')
    }

    private void cleanAll() {
        ec.entity.find("dtq.test.PersonToRate").deleteAll()
        ec.entity.find("dtq.test.Person").deleteAll()
    }

    def "test person entity create"() {
        when:
            // disable authz
            this.ec.artifactExecution.disableAuthz()
            //clean all
            this.cleanAll()

            //create record in Person
            def result = this.ec.service.sync().name("dtq.rockycube.EndpointServices.create#EntityData")
                .disableAuthz()
                .parameters([
                    entityName: "dtq.test.Person",
                    data: [oscis: 1]
                ])
                .call()
        then:
            result.data.size() == 1
    }

    def "test person entity create PersonToRate"() {
        when:
        // disable authz
        this.ec.artifactExecution.disableAuthz()
        //clean all
        this.cleanAll()

        //create record
        ec.entity.makeValue("dtq.test.Person").setAll(oscis: 1).create()

        //create record in PersonToRate
        def result = this.ec.service.sync().name("dtq.rockycube.EndpointServices.create#EntityData")
                .disableAuthz()
                .parameters([
                        entityName: "dtq.test.PersonToRate",
                        data: [oscis: 1]
                ])
                .call()

        then:
            result.data.size() == 1
    }

    def "test create PersonToRate with correct oscis two times"() {
        when:
        // disable authz
        this.ec.artifactExecution.disableAuthz()
        //clean all
        this.cleanAll()

        //create record
        ec.entity.makeValue("dtq.test.Person").setAll(oscis: 1).create()

        //create record in PersonToRate with incorect oscis
        def result = this.ec.service.sync().name("dtq.rockycube.EndpointServices.create#EntityData")
                .disableAuthz()
                .parameters([
                        entityName: "dtq.test.PersonToRate",
                        data: [oscis: 1]
                ])
                .call()

        assert result.data.size() == 1

        //create record in PersonToRate with correct oscis
        result = this.ec.service.sync().name("dtq.rockycube.EndpointServices.create#EntityData")
                .disableAuthz()
                .parameters([
                        entityName: "dtq.test.PersonToRate",
                        data: [oscis: 1]
                ])
                .call()

        then:
        result.data.size() == 1
    }

    def "test create PersonToRate with incorrect and correct oscis"() {
        when:
        // disable authz
        this.ec.artifactExecution.disableAuthz()
        //clean all
        this.cleanAll()

        //create record
        ec.entity.makeValue("dtq.test.Person").setAll(oscis: 1).create()

        //create record in PersonToRate with incorect oscis
        def result = this.ec.service.sync().name("dtq.rockycube.EndpointServices.create#EntityData")
                .disableAuthz()
                .parameters([
                        entityName: "dtq.test.PersonToRate",
                        data: [oscis: 2]
                ])
                .call()

        assert result.size() == 0

        //create record in PersonToRate with correct oscis
        result = this.ec.service.sync().name("dtq.rockycube.EndpointServices.create#EntityData")
                .disableAuthz()
                .parameters([
                        entityName: "dtq.test.PersonToRate",
                        data: [oscis: 1]
                ])
                .call()

        then:
        result.data.size() == 1
    }
}
