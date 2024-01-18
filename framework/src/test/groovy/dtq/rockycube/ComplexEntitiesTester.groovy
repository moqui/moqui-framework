package dtq.rockycube

import dtq.rockycube.entity.ConditionHandler
import dtq.rockycube.entity.EntityHelper
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.condition.ListCondition
import dtq.rockycube.util.TestUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class ComplexEntitiesTester extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(ComplexEntitiesTester.class)

    private String CONST_TEST_ENTITY = "moqui.test.TestEntitySpecial"

    @Shared
    ExecutionContext ec

    def setupSpec() {
        // set other conf - this configuration contains definition of Closure-related entities
        System.setProperty("moqui.conf", "conf/MoquiDevConf.xml")
//        System.setProperty("entity_ds_db_conf", "postgres")
//        System.setProperty("entity_ds_host", "localhost")
//        System.setProperty("entity_ds_database", "moqui_app_db")
//        System.setProperty("entity_ds_user", "moqui")
//        System.setProperty("entity_ds_password", "SjVf7tlFKPj5AbXufXQPRxlyANHYufS")

        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.hardy', 'moqui')
    }

    private EntityValue createNewItem(HashMap processed)
    {
        def dataChunk = (HashMap) processed.get('data')
        def dataToImport = [
                testId: processed['identity'],
                testMaps: dataChunk['dict'],
                testArrays: dataChunk['array']
        ]

        // add search field if present
        if (dataChunk.containsKey('searchField'))
        {
            dataToImport['testSearchField'] = dataChunk['searchField']
        }

        return ec.entity.makeValue(processed["entityName"])
                .setAll(dataToImport).create()
    }

    /**
     * Method for testing generic EntityFind returning method for mimicking search
     */
    def test_entity_search()
    {
        when:

        // 1. first, delete all records to start from scratch
        logger.info("\nDeleted records: [${ec.entity.find(CONST_TEST_ENTITY).deleteAll()}]\n")

        // 2. import test data
        TestUtilities.testSingleFile((String[]) ['ComplexEntities', "complex-entities", "expected-counts.json"], { Object processed, Object expected, Integer idx ->
            def newEntity = this.createNewItem((HashMap) processed)
            assert newEntity, "Entity must be created"
        })

        // run through all entities and perform search
        TestUtilities.testSingleFile((String[]) ['ComplexEntities', "complex-entities", "expected-counts.json"], { Object processed, Object expected, Integer idx ->
            switch (expected["searchObject"]["filterType"])
            {
                case "HashMap":
                    def search = EntityHelper.filterEntity(
                            this.ec,
                            CONST_TEST_ENTITY,
                            (HashMap) expected["searchObject"]["filterContent"])

                    // expected count
                    logger.info("expected count [${expected["count"]}]")

                    assert search.count() == expected["count"]
                    break
                case "EntityCondition":
                    def resListCondition = []
                    ArrayList<HashMap> conditions = (ArrayList) expected["searchObject"]["conditions"]
                    conditions.each {it->
                        resListCondition.add(ConditionHandler.getSingleFieldCondition((HashMap) it))
                    }
                    def resCondition = new ListCondition(resListCondition, EntityCondition.JoinOperator.AND)
                    def search = ec.entity.find(CONST_TEST_ENTITY).condition(resCondition)
                    // error for H2 fields
                    def count = search.count()
                    assert count == expected["count"]
                    break
                default:
                    throw new Exception("Forgot to set filterType in test definition")
            }
        }, logger)

        then:

        // when we
        assert 1 == 1
    }

    /**
     * Testing entity creation when using JSONb fields - maps and arrays
     */
    def test_entity_creation()
    {
        when:

        // delete all
        logger.info("\nDeleted records: [${ec.entity.find(CONST_TEST_ENTITY).deleteAll()}]\n")

        TestUtilities.testSingleFile((String[]) ["ComplexEntities", "complex-entities", "expected-counts.json"], { Object processed, Object expected, Integer idx ->
            def newEntity = this.createNewItem((HashMap) processed)
            assert newEntity, "Entity must be created"

            // search for it
            def existingEntity = ec.entity.find(CONST_TEST_ENTITY).condition([testId: processed['identity']]).one()

            assert newEntity.testMaps == existingEntity.testMaps, "Must be able to search for the newly created one"

            // delete it afterwards
            assert 1 == ec.entity.find(CONST_TEST_ENTITY).condition([testId: processed['identity']]).deleteAll().toInteger()
        })

        then:
        ec.entity.find(CONST_TEST_ENTITY).count() == 0
    }
}
