package dtq.entity

import com.google.gson.Gson
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.util.TestUtilities
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

    def test_entity_creation()
    {
        when:

        // delete all
        logger.info("Deleted records: [${ec.entity.find(CONST_TEST_ENTITY).deleteAll()}]")

        TestUtilities.testSingleFile((String[]) ["complex-entities", "expected-counts.json"], { Object processed, Object expected, Integer idx ->
            def newEntity = ec.entity.makeValue(processed["entityName"])
                .setAll([
                        testId: processed['key'],
                        testMaps: processed['data']['dict'],
                        testArrays: processed['data']['array']
                ]).create()

            assert newEntity, "Entity must be created"

            // search for it
            def existingEntity = ec.entity.find(CONST_TEST_ENTITY).condition([testId: processed['key']]).one()

            assert newEntity.testMaps == existingEntity.testMaps, "Must be able to search for the newly created one"

            // delete it afterwards
            assert 1 == ec.entity.find(CONST_TEST_ENTITY).condition([testId: processed['key']]).deleteAll().toInteger()
        })

        then:
        ec.entity.find(CONST_TEST_ENTITY).count() == 0
    }
}
