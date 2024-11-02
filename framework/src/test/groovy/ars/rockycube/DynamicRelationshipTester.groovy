package ars.rockycube

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class DynamicRelationshipTester extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(DynamicRelationshipTester.class)

    private String CONST_PACKAGE_NAME = "moqui.test"
    private String CONST_TEST_RELATIONSHIP_NAME = "TestRelationshipName"
    private String CONST_TEST_RELATIONSHIP_PERSON = "TestRelationshipPerson"
    private String CONST_TEST_BASIC_ENTITY = "TestBasicEntity"
    private String CONST_SUFFIX = "test"

    @Shared
    ExecutionContext ec

    def setupSpec() {
        // set other conf - this configuration contains definition of Closure-related entities
        System.setProperty("moqui.conf", "../framework/src/test/resources/DynamicRelationships/DynamicRelationshipConf.xml")

        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.hardy', 'moqui')
    }

    private String getEntityName(String name) {
        return "${CONST_PACKAGE_NAME}.${name}@${CONST_SUFFIX}"
    }

    /**
     * Test dynamic entity relationships after creating new entity by using '@'
     */
    def test_changing_entity_relationships()
    {
        when:
            // disable authz
            this.ec.artifactExecution.disableAuthz()
            //clean all
            ec.entity.makeValue(getEntityName(CONST_TEST_RELATIONSHIP_NAME)).setAll(
                    testName: "test"
            ).delete()
            ec.entity.makeValue(getEntityName(CONST_TEST_RELATIONSHIP_PERSON)).setAll(
                    testName: "test",
                    testSurname: "test"
            ).delete()

            //insert new data
            ec.entity.makeValue(getEntityName(CONST_TEST_RELATIONSHIP_NAME)).setAll(
                        testName: "test"
            ).create()
            ec.entity.makeValue(getEntityName(CONST_TEST_RELATIONSHIP_PERSON)).setAll(
                        testName: "test",
                        testSurname: "test"
            ).create()
        then:
            ec.entity.find("${CONST_PACKAGE_NAME}.${CONST_TEST_RELATIONSHIP_NAME}_${CONST_SUFFIX}").count() == 1
            ec.entity.find("${CONST_PACKAGE_NAME}.${CONST_TEST_RELATIONSHIP_PERSON}_${CONST_SUFFIX}").count() == 1

    }

    def test_entity_without_relationship()
    {
        when:
            // disable authz
            this.ec.artifactExecution.disableAuthz()
            //clean all
            ec.entity.makeValue(getEntityName(CONST_TEST_BASIC_ENTITY)).setAll(
                    testId: 1
            ).delete()
            //insert new data
            ec.entity.makeValue(getEntityName(CONST_TEST_BASIC_ENTITY)).setAll(
                    testId: 1
            ).create()
        then:
            ec.entity.find("${CONST_PACKAGE_NAME}.${CONST_TEST_BASIC_ENTITY}_${CONST_SUFFIX}").count() == 1
    }
}