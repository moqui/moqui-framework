import com.google.gson.Gson
import dtq.rockycube.endpoint.EndpointServiceHandler
import dtq.rockycube.entity.ConditionHandler
import dtq.rockycube.entity.EntityHelper
import groovy.json.JsonSlurper
import net.javacrumbs.jsonunit.core.Configuration
import net.javacrumbs.jsonunit.core.internal.Options
import org.apache.groovy.json.internal.LazyMap
import org.junit.Test
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import dtq.rockycube.util.TestUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification
import net.javacrumbs.jsonunit.JsonAssert
import net.javacrumbs.jsonunit.core.Option
import java.nio.charset.StandardCharsets

class EndpointTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(EndpointTests.class)
    protected Gson gson

    @Shared
    ExecutionContext ec

    def setup() {
        // initialize Gson
        this.gson = new Gson()
    }

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.hardy', 'moqui')
    }

    def cleanupSpec() {
        if (ec) {
            // commit transactions
            ec.transaction.commit()

            // add some delay before turning off
            logger.info("Delaying deconstruction of ExecutionContext, for the purpose of storing data. Without this delay, the commit would have failed.")
            sleep(3000)

            // stop it all
            ec.destroy()
        }
    }

    def test_complex_query_conditions() {
        when:

        // declared once
        String testDir = "EntityEndpointTests"

        TestUtilities.testSingleFile(
                TestUtilities.extendList([testDir, "expected_complex_queries.json"] as String[]),
                { HashMap processed, HashMap expected, Integer idx ->

                    def entity = processed.entityName
                    def term = processed.term
                    def args = processed.args?:[:]

                    // 1. directly - via handler
                    // search querying - this section is primarily aimed at testing empty `queryCondition`
                    def handler = new EndpointServiceHandler((HashMap) args, (ArrayList) term, (String) entity, null, [])
                    def result = handler.fetchEntityData(0, 100, [])
                    assert result.data.size() == expected.expected

                    // 2. classic - via service
                    // search via EndpointService
                    def enums = this.ec.service.sync()
                            .name("dtq.rockycube.EndpointServices.populate#EntityData")
                            .parameters(
                                    [
                                            entityName: entity,
                                            term      : term,
                                            args      : args,
                                            index     : 0,
                                            size      : 100
                                    ]
                            )
                            .call()

                    // assert equality between JSON returned and the one set in the expected
                    assert enums
                    assert enums.data
                    assert enums.data.size() == expected.expected
                })

        then:
        true
    }

    def "test_sorting_endpoint_output"() {
        when:

        def enums = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.populate#EntityData")
                .parameters([entityName: "moqui.basic.Enumeration", index: 0, size: 2])
                .call()

        then:
        enums != null
        enums.data.size() == 2

        logger.info("enums: ${enums}")
    }

    def "test_conversion_to_flat_map"() {
        when:

        // disable authz
        ec.artifactExecution.disableAuthz()

        // declared once
        String testDir = "EntityEndpointTests"

        TestUtilities.testSingleFile(
                TestUtilities.extendList([testDir, "expected_flatmapping.json"] as String[]),
                { Object processed, Object expected, Integer idx ->

                    // create new entity
                    def newStoredJson = ec.entity.makeValue("moqui.test.TestEntity")
                            .setAll([
                                    testJsonField: processed,
                            ])
                            .setSequencedIdPrimary()
                            .create()

                    // must be created
                    assert newStoredJson

                    // commit transactions pending
                    ec.transaction.commit()

                    // load JSON via EndpointService
                    def response = ec.service.sync().name("dtq.rockycube.EndpointServices.populate#EntityData").disableAuthz().parameters([
                            entityName: "moqui.test.TestEntity",
                            term      : [[field: 'testId', value: newStoredJson.testId]],
                            args      : [convertToFlatMap: true, allowedFields: ['testJsonField'], preferObjectInReturn: true]
                    ]).call() as HashMap

                    def result = response.data

                    // log and store output
                    TestUtilities.dumpToDebug((String[])["__temp", "out.json"], {
                        return gson.toJson(result)
                    })

                    // assert equality between JSON returned and the one set in the expected
                    JsonAssert.setOptions(Option.IGNORING_ARRAY_ORDER, Option.IGNORING_EXTRA_FIELDS)
                    JsonAssert.assertJsonEquals(expected, result, new Configuration(0.01, Options.empty(), "#{json-unit.ignore}"))
                })

        then:
        1 == 1
    }

    def "test writing string into date"() {
        when:

        def rawStringWrite = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.create#EntityData")
                .parameters([
                        entityName: "moqui.test.TestEntity",
                        data      : [testDate: '2022-01-12']
                ])
                .call()

        then:

        rawStringWrite.data.size() == 1
    }

    def "test complex condition evaluator"() {
        when:

        def complexConditions = TestUtilities.loadTestResource((String[]) ['condition-evaluator', 'cond-complex-conditions-1.txt'])
        def terms = TestUtilities.loadTestResource((String[]) ['condition-evaluator', 'cond-term-1.json'])

        def resp = ConditionHandler.recCondition(
                new String(complexConditions.readAllBytes(), StandardCharsets.UTF_8),
                new Gson().fromJson(terms.newReader(), ArrayList.class)
        )

        then:
        assert resp
    }

    /**
     * This test scenario is used to showcase functionality that allows reading
     * from a map while using composite-fields functionality, e.g. adding 'general.salesPerson'
     * into fields extracted from endpoint extract single field from a map stored under key 'general'
     */
    def "test composite fields extraction"(){
        when:

        // delete all before hand
        EntityHelper.filterEntity(ec, 'moqui.test.TestJsonEntity', null).deleteAll()

        then:

        // import data from existing sample file
        TestUtilities.executeOnEachRecord((String[]) ["jsonb-column-query", 'sample-import.json'], (Integer idx, String entityName, Object data)->{
            def newEntity = ec.entity.makeValue(entityName)
            newEntity.setAll((HashMap) data)
                    .setSequencedIdPrimary()
                    .create()
        })

        // load using EndpointHandler
        TestUtilities.testSingleFile(
                TestUtilities.extendList(["composite-field", "expected-query.json"] as String[]),
                {Object processed, Object expected, Integer idx ->
                    def handler = new EndpointServiceHandler(
                            (HashMap) processed['args'],
                            (ArrayList) processed['term'],
                            (String) processed['entity'],
                            null,
                            [])

                    def result = handler.fetchEntityData(0, 6, [])
                    assert result['data'] == expected['result']
                })
    }

    /**
     * This method tests extraction of entity data using JSONB field querying
     */
    def "test JSON key in query"() {
        // declared once
        String testDir = "jsonb-column-query"

        when:

        // delete all
        ec.entity.find('moqui.test.TestJsonEntity').deleteAll()

        // load test resource
        def js = TestUtilities.loadTestResource((String[]) [testDir, 'sample-import.json'])

        // import data so that we have something to test on
        def importJs = new JsonSlurper().parse(js.bytes)
        for (i in importJs)
        {
            def newEntity = ec.entity.makeValue(i['entity'])
            newEntity.setAll(i['data'] as LinkedHashMap).setSequencedIdPrimary().create()

            // creation check
            assert newEntity
        }

        // run multiple tests
        TestUtilities.testSingleFile(
            TestUtilities.extendList([testDir, "expected-queries.json"] as String[]),
            { Object processed, Object expected, Integer idx ->
                try {
                    def endpointData = this.ec.service.sync()
                            .name("dtq.rockycube.EndpointServices.populate#EntityData")
                            .parameters([
                                    failsafe: true,
                                    entityName: processed.entity,
                                    term      : processed.term
                            ])
                            .call()

                    def res = endpointData.result
                    assert expected.result == res
                    if (res)
                    {
                        assert expected.count == endpointData.data.size()
                    } else {
                        if (expected.containsKey("message")) assert expected.message == endpointData.message
                    }
                } catch(Exception exc) {
                    assert expected.result == false
                }
            }, logger)

        then:

        assert true
    }
}
