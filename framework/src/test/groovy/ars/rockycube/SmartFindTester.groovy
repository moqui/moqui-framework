package ars.rockycube

import ars.entity.SmartFind
import ars.rockycube.entity.ConditionHandler
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class SmartFindTester extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(SmartFindTester.class)

    @Shared
    ExecutionContext ec

    @Shared
    EntityFacadeImpl efi

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.hardy', 'moqui')

        efi = (EntityFacadeImpl) ec.entity

        // disable authz
        this.ec.artifactExecution.disableAuthz()
    }

    def setup() {
        logger.info("Running before each test")
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

    /**
     * Basic test that takes care of testing SQL script creation
     */
    def "test primitive SQL creation"(){
        when:

        def ed = efi.getEntityDefinition("moqui.basic.Enumeration")
        def ed2 = efi.getEntityDefinition("moqui.basic.EnumerationType")
        def sf = new SmartFind(efi, ed)

        def conds = ConditionHandler.getSingleFieldCondition([field: 'enumTypeId', value: 'Activity'])
        def res = sf.queryMultipleTables(
                ["table1.enumId", "table2.description"],
                "${ed2.fullTableName} table2",
                conds as EntityConditionImplBase,
                "table1.ENUM_TYPE_ID = table2.ENUM_TYPE_ID")

        then:
        assert res.size() == 2
    }
}
