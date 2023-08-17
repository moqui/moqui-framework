package dtq.rockycube

import dtq.rockycube.entity.ConditionHandler
import dtq.rockycube.entity.EntityHelper
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.condition.ListCondition
import org.moqui.util.TestUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class DynamicRelationshipTester extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(DynamicRelationshipTester.class)

    private String CONST_TEST_RELATIONSHIP_NAME = "moqui.test.TestRelationshipName"
    private String CONST_TEST_RELATIONSHIP_PERSON = "moqui.test.TestRelationshipPerson"
    private String CONST_POSTFIX = "1"

    @Shared
    ExecutionContext ec

    def setupSpec() {
        // set other conf - this configuration contains definition of Closure-related entities
        System.setProperty("moqui.conf", "../framework/src/test/resources/dynamic-relationship/DynamicRelationshipConf.xml")

        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.hardy', 'moqui')
    }

    /**
     * Test dynamic entity relationships after creating new entity by using '@'
     */
    def test_changing_entity_relationships()
    {
        when:
            // disable authz
            this.ec.artifactExecution.disableAuthz()
            //before setting relationships the entities have to exist
            ec.entity.makeValue("${CONST_TEST_RELATIONSHIP_NAME}@${CONST_POSTFIX}")
            ec.entity.makeValue("${CONST_TEST_RELATIONSHIP_PERSON}@${CONST_POSTFIX}")

            // delete all if entities already existed
            logger.info("\nDeleted records: [${ec.entity.find("${CONST_TEST_RELATIONSHIP_PERSON}_${CONST_POSTFIX}").deleteAll()}]\n")
            logger.info("\nDeleted records: [${ec.entity.find("${CONST_TEST_RELATIONSHIP_NAME}_${CONST_POSTFIX}").deleteAll()}]\n")

            //create entities with correct relationships
            ec.entity.makeValue("${CONST_TEST_RELATIONSHIP_NAME}@${CONST_POSTFIX}").setAll(
                    testName: "test"
            ).create()
            ec.entity.makeValue("${CONST_TEST_RELATIONSHIP_PERSON}@${CONST_POSTFIX}").setAll(
                    testName: "test",
                    testSurname: "test"
            ).create()
        then:
            ec.entity.find("${CONST_TEST_RELATIONSHIP_PERSON}_${CONST_POSTFIX}").count() == 1
            ec.entity.find("${CONST_TEST_RELATIONSHIP_NAME}_${CONST_POSTFIX}").count() == 1
    }
}