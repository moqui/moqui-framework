import dtq.rockycube.entity.EntityHelper
import dtq.rockycube.util.TestUtilities
import org.moqui.impl.entity.EntityDbMeta
import org.moqui.impl.entity.EntityFacadeImpl

import java.time.LocalDate
import junit.framework.Test
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.ViUtilities
import org.moqui.impl.entity.EntityDefinition
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

import java.time.LocalDate
import java.util.regex.Pattern

class EntityHelperTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(EntityHelperTests.class)

    @Shared
    ExecutionContext ec

    @Shared
    EntityFacadeImpl efi

    @Shared
    EntityHelper helper

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
        ec.user.loginUser('john.hardy', 'moqui')

        // initialize EFI
        efi = (EntityFacadeImpl) ec.entity

        // initialize tools for searching among entities
        helper = new EntityHelper(ec)
    }

    def cleanupSpec() {
        ec.destroy()
    }

    def "test_entity_lookup"()
    {
        when:

        def entitiesToBeFound = [
                [1, ".+testentity\$", 'moqui.test.TestEntity'],
                [1, "testentity", 'moqui.test.TestEntity'],
                [1, "TestEntity", 'moqui.test.TestEntity'],
                [2, "TestEntity@105550", 'moqui.test.TestEntity']
        ]

        int foundEntities = 0
        entitiesToBeFound.each {
            EntityDefinition ed
            if (it[0] == 1)
            {
                def recSearchEntity = Pattern.compile(it[1].toString(), Pattern.CASE_INSENSITIVE)
                ed = helper.getDefinition(recSearchEntity)
            } else {
                ed = helper.getDefinition(it[1].toString())
            }

            // quit
            if (!ed) return

            assert ed
            if (ed.fullEntityName == it[2]) foundEntities += 1
        }

        then:
            foundEntities == entitiesToBeFound.size()
            logger.info("Found entities [${foundEntities}] == entitiesToBeFound [${entitiesToBeFound.size()}]")
    }

    def test_connection_search()
    {
        when:
        assert EntityHelper.findDatasource(ec, { HashMap dsDetails ->
            return dsDetails['datasourceFactory'] == 'MongoDatasourceFactory'
        }) == null
        assert EntityHelper.findDatasource(ec, { HashMap dsDetails ->
            return dsDetails['databaseName'] == 'h2'
        }) == 'transactional'

        then:
        1 == 1
    }

    def "test entity name obfuscation"() {
        when:

        TestUtilities.testSingleFile((String[]) ["EntityHelper", "expected-obfuscate.json"], {Object processed, Object expected, Integer idx->
            def ed = efi.getEntityDefinition((String) processed['entityName'])
            StringBuilder sb = new StringBuilder()
            EntityDbMeta.obfuscateName((String) processed['prefix'], ed, sb)

            logger.info("Obfuscation result: ${sb.toString()}")

            assert sb.length() == expected['finalLength']
        }, logger)

        then:

        assert true
    }
}
