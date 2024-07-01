import com.google.gson.Gson
import ars.rockycube.util.CollectionUtils
import com.google.gson.GsonBuilder
import net.javacrumbs.jsonunit.core.Option
import org.apache.commons.io.FileUtils
import org.moqui.Moqui
import org.moqui.entity.EntityCondition
import org.moqui.impl.ViUtilities
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.entity.EntityConditionFactoryImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.util.CollectionUtilities
import org.moqui.util.MNode
import ars.rockycube.util.TestUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ars.rockycube.connection.JsonFieldManipulator
import spock.lang.Specification
import net.javacrumbs.jsonunit.JsonAssert

import java.time.LocalDate
import java.util.regex.Pattern

import static ars.rockycube.util.CollectionUtils.getModifiedVersion

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

    // helper method loading configuration
    public Tuple<ArrayList<MNode>> loadTestDatasourceConfig(){
        def testConfFile = FileUtils.getFile(TestUtilities.extendList(testDir, (String[]) ["Utils", "JsonConfig", "TestConf.xml"]))
        MNode testConfiguration = MNode.parse(testConfFile)

        // manually extract nodes that will be used in the testing
        def entityFacade = testConfiguration.child(0)
        def datasourceList = entityFacade.children("datasource")
        def databaseList = testConfiguration.child(1)
        return new Tuple(datasourceList, databaseList.children("database"))
    }

    def test_json_config_reader()
    {
        when:

        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
        def confs = this.loadTestDatasourceConfig()

        // initialize JsonFieldManipulator before running any tests
        JsonFieldManipulator jfm = new JsonFieldManipulator(ecfi.entityFacade, ["transactional", "transactional_postgres"], (confName)-> {
            // search in datasource list
            def ds = confs[0].find({it->
                if (it.attribute("group-name") == confName) return true
                return false
            })

            // quit if no datasource
            if (!ds) return null

            def config = confs[1].find({it->
                return it.attribute("name") == ds.attribute("database-conf-name")
            })
            return config
        })

        assert jfm, "JSON field manipulator must be loaded"

        // testing itself
        // 1. conversions
        TestUtilities.testSingleFile((String[]) ["Utils", "JsonConfig", "expected-conversions.json"], {Object processed, Object expected, Integer idx->
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

    /**
     * This feature is related to passing map as a string in a GET request
     */
    def "test string to map conversion"(){
        when:

        // from map to string
        TestUtilities.testSingleFile((String[]) ["Utils", "utils-string-2-map", "expected-string-conversions.json"],
                {Object processed, Object expected, Integer idx->
                    def conv = ViUtilities.mapToString((HashMap) processed.inputMap)
                    assert conv == expected.convertedTo
                })

        // from string to map
        TestUtilities.testSingleFile((String[]) ["Utils", "utils-string-2-map", "expected-map-conversions.json"],
                {Object processed, Object expected, Integer idx->
                    def conv = ViUtilities.stringToMap(processed.inputString)
                    assert conv == expected.convertedTo
                })

        // converting to URL encoded string
        // def converted = URLEncoder.encode(toString, 'UTF-8')

        then:
        assert true
    }

    /**
     * Test how JSON nested field conditions is calculated
     */
    def "test JSON nested field conditions"(){
        when:
        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
        EntityConditionFactoryImpl ecf = ecfi.entityFacade.conditionFactoryImpl
        def confs = this.loadTestDatasourceConfig()

        // initialization
        JsonFieldManipulator jfm = new JsonFieldManipulator(ecfi.entityFacade, ["transactional", "transactional_postgres"], (confName)-> {
            // search in datasource list
            def ds = confs[0].find({it->
                if (it.attribute("group-name") == confName) return true
                return false
            })

            // quit if no datasource
            if (!ds) return null

            def config = confs[1].find({it->
                return it.attribute("name") == ds.attribute("database-conf-name")
            })
            return config
        })

        TestUtilities.testSingleFile((String[]) ["Utils", "JsonConfig", "expected-nested-field-queries.json"],
                {Object processed, Object expected, Integer idx->

                    EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(processed.entityName)
                    ConditionField field = new ConditionField(ed.getFieldInfo(processed.fieldName))
                    ArrayList nested = processed.nestedFields
                    def operator = EntityCondition.ComparisonOperator.EQUALS
                    if (processed.containsKey("operator")) operator = ecf.stringComparisonOperatorMap.get((String) processed.operator)

                    // check the operator
                    assert operator, "Must have an operator set"

                    def res = true
                    def excMessage = null
                    def resCondition = null

                    // failure allowed
                    try {
                        resCondition = jfm.formatNestedCondition(ed, field, nested, operator)
                    } catch (Exception exc){
                        res = false
                        excMessage = exc.message
                    }

                    assert expected.result == res
                    if (res)
                    {
                        assert resCondition == expected.modifiedCondition
                    } else {
                        assert resCondition == null
                        assert excMessage == expected.exception
                    }
        })

        then:

        assert true
    }

    def "test map content extraction"(){
        when:
        TestUtilities.testSingleFile((String[]) ["Utils", "extract-map", "expected-map-conversion.json"],
                {Object processed, Object expected, Integer idx->

                    HashMap toExtract = (HashMap) processed.toExtract

                    // use CollectionUtils' tool to extract from the input
                    def extracted = CollectionUtilities.extractSingleKeyMapContent(toExtract)
                    assert extracted == expected.extracted
                })
        then:

        assert true
    }

    def "test record matches condition"(){
        when:
        TestUtilities.testSingleFile((String[]) ["EntityEndpoint", "condition-evaluator", "expected-condition-matches.json"],
        {Object processed, Object expected, Integer idx->

            HashMap toTest = (HashMap) processed.mapToCheck
            HashMap conditionToUse = (HashMap) processed.condition

            def res = ViUtilities.recordMatchesCondition(toTest, conditionToUse)
            assert res == expected.result
        })

        then:
        assert true
    }

    def "test date-beginning and date-ending conversion"(){
        when:

        // assert that we are able to convert dates to BOM and EOM dates
        def resultBom = LocalDate.parse('1981-03-01')
        def resultEom = LocalDate.parse('1981-03-31')

        assert resultBom == ViUtilities.convertToBom('1981-03-20')
        assert resultEom == ViUtilities.convertToEom('1981-03-20')

        then:
        assert true
    }

    def "test collection search"(){
        when:
        TestUtilities.testSingleFile((String[]) ["Utils", "collection-search", "expected-search-result.json"],
                {Object processed, Object expected, Integer idx->

                    HashMap whereToLook = (HashMap) processed['whereToLook']
                    String searchKey = (String) processed['searchKey']
                    def keyUsed = CollectionUtils.keyInUse(searchKey)
                    def ret = CollectionUtils.findKeyInMap(whereToLook, searchKey, HashMap.class, [:])

                    logger.info("Searched map: ${whereToLook}")
                    logger.info("Searched key: ${searchKey}")
                    logger.info("Result: ${ret}")

                    assert keyUsed == (String) expected['key']
                    assert ret == (HashMap) expected['result']
                },
                logger)
        then:

        assert true
    }

    /**
     * Test replacement of <param_XXX> inside a map
     */
    def test_recursive_replace() {
        when:

        def gson = new GsonBuilder().setPrettyPrinting().create()

        // mimic a context, for searching
        def contextToSearchIn = [:]

        // iterate
        TestUtilities.testSingleFile((String[]) ["Utils", "recursive-replace", "expected-replace-results.json"], {Object processed, Object expected, Integer idx->
            // convert file to map
            def f = (String) processed['filename']
            def js = null

            if (f.endsWith('yml')) {
                def is = TestUtilities.loadTestResource(f)
                js = TestUtilities.readYamlToLazyMap(is)
            } else {
                js = TestUtilities.loadTestResourceJs(f)
            }

            def recParam = Pattern.compile('<param_(.+)>')
            def converted = getModifiedVersion(
                    js as Map,
                    (val)->{
                        def m = recParam.matcher((String) val)
                        def containsParams = m.matches()
                        if (!containsParams) return val

                        // check for default value if nothing is found among passed parameters
                        def paramName = m.group(1)

                        // return value from the context, if there is such
                        if (contextToSearchIn.containsKey(paramName)) return "found"

                        // check for default value inside the parameter's name
                        // split the paramName using the `elvis` operator and return the default value
                        if (paramName.contains('?:')){
                            def p = paramName.split("\\?:")
                            return p[1]
                        }

                        return "not-found-in-context"
            })

            // store as file
            assert converted

            // store result in a JSON
            TestUtilities.dumpToDebug((String[])["__temp", "Utils", "recursive-replace", "${(idx + 1).toString().padLeft(3, "0")}.output.json"], {
                return gson.toJson(converted)
            })

            // expected
            def exp = TestUtilities.loadTestResourceJs((String) expected['filename'])

            // store expected in a JSON
            TestUtilities.dumpToDebug((String[])["__temp", "Utils", "recursive-replace", "${(idx + 1).toString().padLeft(3, "0")}.expected.json"], {
                return gson.toJson(exp)
            })

            JsonAssert.setOptions(Option.IGNORING_ARRAY_ORDER, Option.IGNORING_EXTRA_FIELDS)
            JsonAssert.assertJsonEquals(exp, converted)
        }, logger)

        then:

        assert true
    }

    /**
     * This time using search as well
     */
    def test_recursive_replace_w_search() {
        when:

        def gson = new Gson()

        TestUtilities.testSingleFile((String[]) ["Utils", "recursive-replace", "expected-replace-w-search-results.json"], {Object processed, Object expected, Integer idx->
            // convert file to map
            def f = (String) processed['filename']
            def js = null

            if (f.endsWith('yml')) {
                def is = TestUtilities.loadTestResource(f)
                js = TestUtilities.readYamlToLazyMap(is)
            } else {
                js = TestUtilities.loadTestResourceJs(f)
            }

            def converted = getModifiedVersion(js as Map, "stats", (val)-> {
                return "none"
            })

            // store as file
            assert converted

            TestUtilities.dumpToDebug((String[])["__temp", "Utils", "recursive-replace-w-search", "${(idx + 1).toString().padLeft(3, "0")}.output.json"], {
                return gson.toJson(converted)
            })

            def exp = TestUtilities.loadTestResourceJs((String) expected['filename'])
            // JsonAssert.assertJsonEquals(exp, converted)
            assert converted == exp
        })

        then:

        assert true
    }
}
