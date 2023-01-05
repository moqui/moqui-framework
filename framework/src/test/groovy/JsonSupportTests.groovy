import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import dtq.rockycube.entity.EntityHelper

import spock.lang.Shared
import spock.lang.Specification

class JsonSupportTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(EndpointTests.class)

    @Shared
    ExecutionContext ec

    @Shared
    EntityHelper meh

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.doe', 'moqui')

        // init entity handler
        this.meh = new EntityHelper(ec)
    }

    def cleanupSpec() {
        ec.destroy()
    }

    // provided entity should contain JSONb in its definitions
    def "test_jsonb_ds_support"()
    {
        when:

        def ed = this.meh.getDefinition("moqui.test.TestEntity", "transactional")

        then:
            ed != null

    }

    // write JSONB into database and test the outcome when it's read from database
    def "store_jsonb"()
    {
        when:

        // disable authz
        ec.artifactExecution.disableAuthz()

        def valueTested = [
                value: 1981,
                theOtherList: [1, 2, 3],
                currentBudget: [
                        [month: "09.2022", amount: 1500],
                        [month: "09.2022", amount: 2500]
                ]
        ]

        // create new entity
        def newStoredJson = ec.entity.makeValue("moqui.test.TestEntity")
                .setAll([
                        testJsonField:valueTested,
                ])
                .setSequencedIdPrimary()
                .create()

        // must be created
        assert newStoredJson

        // load JSON via EndpointService
        def response = ec.service.sync().name("dtq.rockycube.EndpointServices.populate#EntityData").disableAuthz().parameters([
                entityName: "moqui.test.TestEntity",
                term      : [[field:'testId', value:newStoredJson.testId]]
        ]).call() as HashMap

        then:
        response['data'][0]['testJsonField'] == valueTested
    }
}
