import com.google.gson.Gson
import dtq.rockycube.entity.ConditionHandler
import groovy.json.JsonSlurper
import org.apache.groovy.json.internal.LazyMap
import org.junit.Test
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.util.TestUtilities
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
                    def args = TestUtilities.convertLazyMap(processed.args)

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

                    // extract path from object processed
                    String file = processed

                    // import file from test resources
                    String[] importFilePath = TestUtilities.extendList(TestUtilities.RESOURCE_PATH, file);
                    FileInputStream toImport = new FileInputStream(TestUtilities.getInputFile(importFilePath))

                    // load into hashmap
                    def js = gson.fromJson(new InputStreamReader(toImport, StandardCharsets.UTF_8), HashMap.class)

                    // create new entity
                    def newStoredJson = ec.entity.makeValue("moqui.test.TestEntity")
                            .setAll([
                                    testJsonField: js,
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
                    JsonAssert.assertJsonEquals(expected, result)
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
     * this method tests extraction of entity data using JSONB field querying
     */
    def "test JSON key in query"() {
        when:

        // delete all
        ec.entity.find('moqui.test.TestJsonEntity').deleteAll()

        // load test resource
        def js =TestUtilities.loadTestResource((String[]) ['jsonb-column-query', 'sample-import.json'])

        // import data so that we have something to test on
        def importJs = new JsonSlurper().parse(js.bytes)
        for (i in importJs)
        {
            def newEntity = ec.entity.makeValue(i['entity'])
            newEntity.setAll(i['data']).setSequencedIdPrimary().create()

            // creation check
            assert newEntity
        }

        // search check
        assert ec.entity.find('moqui.test.TestJsonEntity').condition([
                testJsonId: [projectId: 1, category: 'a']
        ]).count() == 1

        then:

        assert true
    }
}
