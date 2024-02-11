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
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext
import dtq.rockycube.util.TestUtilities
import org.moqui.entity.EntityException
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification
import net.javacrumbs.jsonunit.JsonAssert
import net.javacrumbs.jsonunit.core.Option
import java.nio.charset.StandardCharsets
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class EndpointTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(EndpointTests.class)
    protected Gson gson

    @Shared
    ExecutionContext ec

    def setup() {
        this.ec.message.clearErrors()
    }

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.hardy', 'moqui')

        // initialize Gson
        this.gson = new Gson()
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
        String testDir = "EntityEndpoint"

        TestUtilities.testSingleFile(
                TestUtilities.extendList([testDir, "expected_complex_queries.json"] as String[]),
                { HashMap processed, HashMap expected, Integer idx ->

                    def entity = processed.entityName
                    def term = processed.term
                    def args = processed.args?:[:]

                    // 1. directly - via handler
                    // search querying - this section is primarily aimed at testing empty `queryCondition`
                    def handler = new EndpointServiceHandler(ec, null, (HashMap) args, (ArrayList) term, (String) entity, null, [])
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
        String testDir = "EntityEndpoint"

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

    def "test writing string into decimal"() {
        when:

        def decimalWrite = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.create#EntityData")
                .parameters([
                        entityName: "moqui.test.TestEntity",
                        data      : [testNumberDecimal: 500, testNumberInteger: 10]
                ])
                .call()

        then:

        assert decimalWrite.data.size() == 1

        def id = decimalWrite.data[0]['testId']

        // now, update the record with string
        def decimalUpdate = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.update#EntityData")
                .parameters([
                        entityName: "moqui.test.TestEntity",
                        term      : [[field: 'testId', value: id]],
                        data      : [testNumberDecimal: "200.01", testNumberInteger: "50"]
                ])
                .call()

        assert decimalUpdate.result

        // update with error
        def decimalUpdateError = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.update#EntityData")
                .parameters([
                        entityName: "moqui.test.TestEntity",
                        term      : [[field: 'testId', value: id]],
                        data      : [testNumberDecimalllll: "200.01"]
                ])
                .call()

        assert this.ec.message.errorsString == "The field name testNumberDecimalllll is not valid for entity moqui.test.TestEntity\n"
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

        def createdId = (String) rawStringWrite['data'][0]['testId']
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern('yyyy-MM-dd')
        LocalDate ld = LocalDate.parse('2022-03-18', formatter);
        LocalDate ld19 = LocalDate.parse('2022-03-19', formatter);

        // update 1. - with time
        def rawStringUpdate1 = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.update#EntityData")
                .parameters([
                        entityName: "moqui.test.TestEntity",
                        term      : [[field: 'testId', value: createdId]],
                        data      : [testDate: '2022-03-18 00:00:00']
                ])
                .call()

        assert rawStringUpdate1.data[0]['testDate'] == Date.valueOf(ld)

        // update 2. - no time
        def rawStringUpdate2 = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.update#EntityData")
                .parameters([
                        entityName: "moqui.test.TestEntity",
                        term      : [[field: 'testId', value: createdId]],
                        data      : [testDate: '2022-03-18']
                ])
                .call()

        assert rawStringUpdate2.data[0]['testDate'] == Date.valueOf(ld)

        // update 3. - using create/update mode
        def rawStringUpdate3 = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.update#EntityData")
                .parameters([
                        entityName: "moqui.test.TestEntity",
                        term      : [[field: 'testId', value: createdId]],
                        data      : [testDate: '2022-03-19 00:00:00'],
                        args      : [requiredCreateOrUpdate: true]
                ])
                .call()

        assert rawStringUpdate3.data[0]['testDate'] == Date.valueOf(ld19)


        // test reading
        def readFormatted1 = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.populate#EntityData")
                .parameters([
                        entityName: "moqui.test.TestEntity",
                        term      : [[field: 'testId', value: createdId]],
                        args      : [
                                        requiredDateFormat: 'MM.yy',
                                        'timeZoneInDatesFormat': 'dd.MM.yyyy',
                                        'allowTimestamps': true
                        ]
                ])
                .call()

        def today = ec.user.nowTimestamp.toLocalDateTime()
        def tf = DateTimeFormatter.ofPattern('dd.MM.yyyy')

        then:

        rawStringWrite.data.size() == 1
        rawStringUpdate2.data.size() == 1
        readFormatted1.data[0]['testDate'] == '03.22'
        readFormatted1.data[0]['lastUpdatedStamp'] == today.format(tf)
    }

    // we do not want TZ in the output
    def "test returning no time-zone information"() {
        when:

        EntityHelper.filterEntity(ec, 'moqui.test.TestEntity', null).deleteAll()

        def rawStringWrite = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.create#EntityData")
                .parameters([
                        entityName: "moqui.test.TestEntity",
                        data      : [testDate: '2022-01-12', testId: 'special-1'],
                        args      : [autoCreatePrimaryKey: false]
                ])
                .call()

        then:

        // TIMESTAMP
        // read with time-zone and then with no time-zone - see the difference
        def edWithTs = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.populate#EntityData")
                .parameters([
                        failsafe: true,
                        entityName: "moqui.test.TestEntity",
                        term: [[field: 'testId', value: 'special-1']],
                        args: [allowTimestamps: true]
                ])
                .call()

        assert edWithTs.result
        def dataWithTs = (ArrayList) edWithTs.data
        assert dataWithTs.size() == 1

        dataWithTs.each { it ->
            def lus = it['lastUpdatedStamp']
            logger.info("lastUpdatedStamp: [${lus}] ${lus.getClass().simpleName}")
            assert lus.getClass() == Timestamp.class
        }

        // SIMPLE DATE
        def edWithoutTs = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.populate#EntityData")
                .parameters([
                        failsafe: true,
                        entityName: "moqui.test.TestEntity",
                        term: [[field: 'testId', value: 'special-1']],
                        args: [allowTimestamps: true, timeZoneInDatesFormat: "yyyy-MM-dd"]
                ])
                .call()

        assert edWithoutTs.result
        def dataWithoutTs = (ArrayList) edWithoutTs.data
        assert dataWithoutTs.size() == 1

        dataWithoutTs.each { it ->
            def lus = it['lastUpdatedStamp']
            logger.info("lastUpdatedStamp: [${lus}] ${lus.getClass().simpleName}")
            assert lus.getClass() == String.class
        }
    }

    def "test complex condition evaluator"() {
        when:

        def complexConditions = TestUtilities.loadTestResource((String[]) ['EntityEndpoint', 'condition-evaluator', 'cond-complex-conditions-1.txt'])
        def terms = TestUtilities.loadTestResource((String[]) ['EntityEndpoint', 'condition-evaluator', 'cond-term-1.json'])

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
        TestUtilities.executeOnEachRecord((String[]) ['EntityEndpoint', "jsonb-column-query", 'sample-import.json'], (Integer idx, String entityName, Object data)->{
            def newEntity = ec.entity.makeValue(entityName)
            newEntity.setAll((HashMap) data)
                    .setSequencedIdPrimary()
                    .create()
        })

        // load using EndpointHandler
        TestUtilities.testSingleFile(
                TestUtilities.extendList(['EntityEndpoint', "composite-field", "expected-query.json"] as String[]),
                {Object processed, Object expected, Integer idx ->
                    def handler = new EndpointServiceHandler(
                            ec,
                            null,
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
        def js = TestUtilities.loadTestResource((String[]) ['EntityEndpoint', testDir, 'sample-import.json'])

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
            TestUtilities.extendList(['EntityEndpoint', testDir, "expected-queries.json"] as String[]),
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

    /**
     * This test is a good example of how creating inter-connected entities work, along
     * with ID creation
     * @return
     */
    def "test endpoint handling of multiple companies"(){
        when:

        // clean up, items first
        EntityHelper.filterEntity(ec, 'moqui.test.InvoiceItem@abcd', null).deleteAll()
        EntityHelper.filterEntity(ec, 'moqui.test.Invoice@abcd', null).deleteAll()

        // create a testing invoice
        def invoice = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.create#EntityData")
                .parameters([
                        companyId : 'abcd',
                        entityName: "moqui.test.Invoice",
                        data      : [docNumber: 'prva faktura'],
                        args      : [preferObjectInReturn: true]
                ])
                .call()
        assert invoice.result == true
        def invoiceId = invoice.data['invoiceId']
        def createService = "dtq.rockycube.EndpointServices.create#EntityData"
        def writeFirst = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.create#EntityData")
                .parameters([
                        companyId : 'abcd',
                        entityName: "moqui.test.InvoiceItem",
                        data      : [invoiceId: invoiceId, value: 500],
                        args      : [preferObjectInReturn: true]
                ])
                .call()

        def data2 = [companyId : 'abcd', entityName: "moqui.test.InvoiceItem", data: [invoiceId: invoiceId, value: 200]]
        def data3 = [companyId : 'abcd', entityName: "moqui.test.InvoiceItem", data: [invoiceId: invoiceId, value: 300]]
        this.ec.service.sync().name(createService).parameters(data2).call()
        this.ec.service.sync().name(createService).parameters(data3).call()

        def invoiceItemId = writeFirst.data['invoiceItemId']
        assert EntityHelper.filterEntity(ec, 'moqui.test.InvoiceItem@abcd', [invoiceItemId: invoiceItemId]).count() == 1

        // update
        def update = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.update#EntityData")
                .parameters([
                        companyId : 'abcd',
                        entityName: "moqui.test.InvoiceItem",
                        term      : [[field: 'invoiceItemId', value: invoiceItemId]],
                        data      : [value: 5000],
                        args      : [preferObjectInReturn: true]
                ])
                .call()

        assert update.result == true
        assert EntityHelper.filterEntity(ec, 'moqui.test.InvoiceItem@abcd', [invoiceItemId: invoiceItemId]).one()['value'] == 5000

        // read

        // delete
        def delete = this.ec.service.sync()
                .name("dtq.rockycube.EndpointServices.remove#EntityData")
                .parameters([
                        companyId : 'abcd',
                        entityName: "moqui.test.InvoiceItem",
                        term      : [[field: 'invoiceItemId', value: invoiceItemId]]
                ])
                .call()

        assert EntityHelper.filterEntity(ec, 'moqui.test.InvoiceItem@abcd', [invoiceItemId: invoiceItemId]).count() == 0

        then:

        delete.result == true
        writeFirst.result == true

    }

    def "test different contexts handling in initialization"(){
        when:

        // login using john.doe
        ec.user.loginUser('john.doe', 'moqui')

        try {
            def esh = new EndpointServiceHandler(ec, null, [:], [], 'moqui.test.TestEntity', null, [])
        } catch(ArtifactAuthorizationException exc) {
            assert exc.message == 'User john.doe is not authorized for View on Entity moqui.test.TestEntity'
        } catch (Exception ignored) {
            assert false
        }


        // login using john.hardy himself
        ec.user.loginUser('john.hardy', 'moqui')

        try {
            def esh = new EndpointServiceHandler(ec, null, [:], [], 'moqui.test.TestEntity', null, [])
            assert esh.fetchEntityData()['result'] == true
        } catch(ArtifactAuthorizationException ignored) {
            assert false
        } catch (Exception ignored) {
            assert false
        }

        then:

        1 == 1
    }
}
