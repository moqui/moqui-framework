package dtq.rockycube

import dtq.rockycube.entity.EntityHelper
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class PersonEntityTester extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(PersonEntityTester.class)

    @Shared
    ExecutionContext ec

    def setupSpec() {
        // set other conf - this configuration contains definition of Closure-related entities
        System.setProperty("moqui.conf", "../framework/src/test/resources/person-entity-conf/PersonEntityTestConf.xml")

        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.hardy', 'moqui')

        // disable authz
        this.ec.artifactExecution.disableAuthz()
    }

    def setup() {
        logger.info("Running before each test")

        //clean all
        this.cleanAll()
    }

    def cleanupSpec() {
        if (ec) {
            // add some delay before turning off
            logger.info("Delaying deconstruction of ExecutionContext, for the purpose of storing data. Without this delay, the commit would have failed.")
            sleep(3000)

            // stop it all
            ec.destroy()
        }
    }

    private void cleanAll() {
        ec.entity.find("dtq.test.PersonToRate").deleteAll()
        ec.entity.find("dtq.test.Person").deleteAll()
    }

    def "test person entity create"() {
        when:
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

        //create record
        ec.entity.makeValue("dtq.test.Person").setAll([oscis: 1]).create()

        //create record in PersonToRate with incorrect oscis
        def result = this.ec.service.sync().name("dtq.rockycube.EndpointServices.create#EntityData")
                .parameters([
                        entityName: "dtq.test.PersonToRate",
                        data: [oscis: 2],
                        failsafe: true
                ])
                .call()

        assert result['result'] == false

        //create record in PersonToRate with correct oscis
        result = this.ec.service.sync().name("dtq.rockycube.EndpointServices.create#EntityData")
                .disableAuthz()
                .parameters([
                        entityName: "dtq.test.PersonToRate",
                        data: [oscis: 1]
                ])
                .call()

        then:
        result['result'] == true
        result.data.size == 1
    }

    /**
     * This test method searches for an entity definition using EntityHelper
     * Originally, exception found by PB - while using EndpointHandler, its methods
     * search for entity definition. Here, own method had been used, which did not
     * take into account the need to load definition from file once it could not be
     * found among entities.
     */
    def "test loading entity definition with a delay"() {
    when:
    def eh = new EntityHelper(this.ec)

    assert eh.getDefinition('dtq.test.Person')

    // this has been fixed - before the fix, if the sleep time was greater
    // than max-idle-time of entity.definition cache, the search would fail
    sleep(2000)

    then:
    assert eh.getDefinition('dtq.test.Person')
    }
}
