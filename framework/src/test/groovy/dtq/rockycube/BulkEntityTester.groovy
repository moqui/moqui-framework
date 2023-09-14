package dtq.rockycube

import com.google.gson.Gson
import dtq.rockycube.entity.BulkEntityHandler
import dtq.rockycube.entity.EntityHelper
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import dtq.rockycube.util.TestUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class BulkEntityTester extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(BulkEntityTester.class)
    private Gson gson = new Gson()
    private String CONST_TEST_ENTITY = "moqui.test.TestEntity"

    @Shared
    ExecutionContext ec

    def setupSpec() {
        System.setProperty("moqui.conf", "conf/MoquiDevConf.xml")

        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.hardy', 'moqui')

        // gson
        this.gson = new Gson()
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

    private Long count()
    {
        return EntityHelper.filterEntity(ec, CONST_TEST_ENTITY, null).count()
    }

    /**
     * Calculate total of the entire table
     * @return
     */
    private long checksum()
    {
        def allRows = EntityHelper.filterEntity(ec, CONST_TEST_ENTITY, null).list()
        Long total = 0
        allRows.each {it->
            total += it.getDouble('testNumberDecimal')
        }
        return total
    }

    // simple import test - just insert nothing more
    def test_plain_import()
    {
        when:

        // delete
        ec.logger.info("Deleted rows [${EntityHelper.filterEntity(ec, CONST_TEST_ENTITY, null).deleteAll()}]")
        assert EntityHelper.filterEntity(ec, CONST_TEST_ENTITY, null).count() == 0

        // initialize new handler
        BulkEntityHandler handler = new BulkEntityHandler()

        // insert into entities
        def isToImport = TestUtilities.loadTestResource((String[]) ['bulk-entity', 'plain-import.json'])
        def result = handler.writeChanges(CONST_TEST_ENTITY, gson.fromJson(isToImport.newReader(), ArrayList.class), [], [])
        logger.info("Output: ${result}")

        then:

        assert EntityHelper.filterEntity(ec, CONST_TEST_ENTITY, null).count() > 0
    }

    // checksum based
    def test_check_totals()
    {
        when:

        // initialize new handler
        BulkEntityHandler handler = new BulkEntityHandler()
        handler.failsafeSwitch = true

        then:

        TestUtilities.testSingleFile((String[]) ["bulk-entity", "expected-checksums.json"], {Object processed, Object expected, Integer idx->
            // clean data every time
            ec.logger.info("Deleted rows [${EntityHelper.filterEntity(ec, CONST_TEST_ENTITY, null).deleteAll()}]")

            // file ID
            def fileStamp = "${idx + 1}_${TestUtilities.formattedTimestamp()}"

            def importData = TestUtilities.loadTestResource((String[]) ['bulk-entity', 'plain-import.json'], ArrayList.class)
            assert handler.writeChanges(CONST_TEST_ENTITY, importData, [], [])['result'] == true

            // data before test
            TestUtilities.dumpToDebug((String[])["__temp", "test_check_totals_BEFORE_${fileStamp}.json"], {
                return gson.toJson(importData)
            })

            // basic stats before start
            def checksumStart = this.checksum()
            def countStart = this.count()

            def testedData = TestUtilities.loadTestResource((String) processed.file, HashMap.class)
            assert !testedData.isEmpty()

            // PROCEDURE ITSELF
            def res = handler.writeChanges(CONST_TEST_ENTITY, testedData.changes as ArrayList<HashMap>, testedData.deletions as ArrayList<HashMap>, [])

            // store output in a file, both result and entire set
            TestUtilities.dumpToDebug((String[])["__temp", "test_check_totals_RESULT_${fileStamp}.json"], {
                return gson.toJson(res)
            })

            // data before test
            TestUtilities.dumpToDebug((String[])["__temp", "test_check_totals_AFTER_${fileStamp}.json"], {
                return gson.toJson(EntityHelper.filterEntity(ec, CONST_TEST_ENTITY, null, ['testId', 'testNumberDecimal', 'testDate']).list())
            })

            def realResult = res['result']
            assert realResult == expected['result']
            assert res['message'] == expected['message']

            def checksumEnd = this.checksum()
            def countEnd = this.count()
            assert (checksumEnd - checksumStart) == expected['checksum'], "Totals after writing into target entity must be the same as expected"
            assert (countEnd - countStart) == expected['rowsDiff']

            // if it's a positive result, test counts
            if (realResult)
            {
                assert res['upserts'] == expected['upserts']
                assert res['deletes'] == expected['deletes']
            } else {
                assert res['upsertFails'] == expected['upsertFails']
                assert res['deleteFails'] == expected['deleteFails']
            }
        })



    }
}
