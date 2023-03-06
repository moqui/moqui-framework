import org.apache.commons.io.FileUtils
import org.moqui.Moqui
import org.moqui.entity.EntityCondition
import org.moqui.impl.ViUtilities
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.entity.EntityConditionFactoryImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.util.MNode
import org.moqui.util.TestUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import dtq.rockycube.connection.JsonFieldManipulator
import spock.lang.Specification

import java.time.LocalDate

class UtilsTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(UtilsTests.class)

    protected String[] testDir = ["src", "test", "resources"]

    def setupSpec() {

    }

    def cleanupSpec() {

    }

    def test_to_LocalDate_conversion() {
        when:

        assert ViUtilities.stringToDate("2022-04-01") == LocalDate.of(2022, 4, 1)

        then:
        1 == 1
    }

    def test_comma_splitting() {
        when:

        assert ViUtilities.splitWithBracketsCheck("AND(OR(1,2,3),AND(4,5))") == ["AND(OR(1,2,3),AND(4,5))"]
        assert ViUtilities.splitWithBracketsCheck("OR(1,2,3),AND(4,5)") == ["OR(1,2,3)", "AND(4,5)"]
        assert ViUtilities.splitWithBracketsCheck("AND(OR(1,2,3),AND(4,5)),6") == ["AND(OR(1,2,3),AND(4,5))", "6"]
        assert ViUtilities.splitWithBracketsCheck("OR(1,AND(5,6)),AND(3,4)") == ["OR(1,AND(5,6))", "AND(3,4)"]
        assert ViUtilities.splitWithBracketsCheck("OR(1,AND(5,6)),AND(AND(11,10, OR(7,8)),4)") == ["OR(1,AND(5,6))", "AND(AND(11,10, OR(7,8)),4)"]

        then:
        1 == 1
    }

    def test_file_w_ts(){
        when:

        def ts = TestUtilities.formattedTimestamp()

        assert TestUtilities.insertBeforeExtension("test.json", ts) == "test_${ts}.json".toString()

        then:
        1 == 1
    }

    // method used for filtering args coming from PY-CALC
    def test_args_filtering(){
        when:
            def args = [
            "periodSince": "01.2021",
            "periodThru": "03.2022",
            "cumulative": true,
            "dateColumn": "date",
            "parsingFormat": "%Y.%m",
            "pycalc_multiSeriesCategory": "type",
            "pycalc_removeZeros": true
        ]

        def expectedCleaned = [
                "multiSeriesCategory": "type",
                "removeZeros": true
        ]

        // cleanup
        def cleaned = ViUtilities.extractPycalcArgs(args)

        then:
        cleaned == expectedCleaned
    }

    def test_json_config_reader()
    {
        when:

        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()

        def testConfFile = FileUtils.getFile(TestUtilities.extendList(testDir, (String[]) ["JsonConfig", "TestConf.xml"]))
        MNode testConfiguration = MNode.parse(testConfFile)

        // manually extract nodes that will be used in the testing
        def entityFacade = testConfiguration.child(0)
        def datasourceList = entityFacade.children("datasource")
        def databaseList = testConfiguration.child(1)
        def databaseConfigs = databaseList.children("database")

        // initialize JsonFieldManipulator before running any tests
        JsonFieldManipulator jfm = new JsonFieldManipulator(["transactional", "transactional_postgres"], (confName)-> {
            // search in datasource list
            def ds = datasourceList.find({it->
                if (it.attribute("group-name") == confName) return true
                return false
            })

            // quit if no datasource
            if (!ds) return null

            def config = databaseConfigs.find({it->
                return it.attribute("name") == ds.attribute("database-conf-name")
            })
            return config
        })

        // testing itself
        // 1. conversions
        TestUtilities.testSingleFile((String[]) ["JsonConfig", "expected-conversions.json"], {Object processed, Object expected, Integer idx->
            def fieldCond = jfm.fieldCondition((String) processed['groupName'], (String) processed['operation'])
            assert fieldCond == expected['fieldCondition']
        })

        // 2. operators
        EntityDefinition ed = ecfi.entityFacade.getEntityDefinition("moqui.test.TestEntity");
        def calcOperator = jfm.findComparisonOperator(
                EntityCondition.ComparisonOperator.EQUALS,
                ed.getFieldInfo("testJsonField"),
                "transactional",
                EntityConditionFactoryImpl.getComparisonOperatorString(EntityCondition.ComparisonOperator.EQUALS)
        )
        assert calcOperator == "@>"

        then:
        true == true
    }
}
