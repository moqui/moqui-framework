import com.google.gson.Gson
import ars.rockycube.entity.EntityHelper
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.ViUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification

class MultiInstanceTests extends Specification{
    protected final static Logger logger = LoggerFactory.getLogger(MultiInstanceTests.class)
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

        // cleanup
        dropTables()
    }

    def dropTables() {
        // erase table from database
        def conn = ec.entity.getConnection('transactional')

        ViUtilities.executeQuery(conn, logger, 'drop table public.INVOICE_ITEM_REDWINGS')
        ViUtilities.executeQuery(conn, logger, 'drop table public.INVOICE_REDWINGS')
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

    /**
     * Test that checks how index is being created on a multi-instance table.
     * At the end of each execution, the table is removed from the database.
     */
    def "test index creation"() {
        when:

        // create the primary record
        ec.entity.makeValue("moqui.test.Invoice@redwings").setAll([invoiceId: 'invoice A', docNumber: 'A8936483']).create()
        ec.entity.makeValue("moqui.test.InvoiceItem@redwings").setAll([invoiceId: 'invoice A', value: 1005]).setSequencedIdPrimary().create()

        // check existence of the index
        def checkResult = ViUtilities.execute(
                ec.entity.getConnection('transactional'),
                logger,
                "SELECT \n" +
                "    INDEX_NAME \n" +
                "FROM \n" +
                "    INFORMATION_SCHEMA.INDEXES \n" +
                "WHERE \n" +
                "    TABLE_NAME = 'INVOICE_ITEM_REDWINGS' \n" +
                "    AND INDEX_SCHEMA = 'PUBLIC' \n" +
                "    AND INDEX_TYPE_NAME = 'INDEX'"
        )

        // check we have exactly one index
        assert checkResult.size() == 1

        // check the foreign key
        checkResult = ViUtilities.execute(
                ec.entity.getConnection('transactional'),
                logger,
                "SELECT \n" +
                        "    CONSTRAINT_NAME \n" +
                        "FROM \n" +
                        "    INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE \n" +
                        "WHERE \n" +
                        "    TABLE_NAME = 'INVOICE_ITEM_REDWINGS' \n" +
                        "    AND TABLE_SCHEMA = 'PUBLIC' \n" +
                        "    AND COLUMN_NAME = 'INVOICE_ID' \n" +
                        "    AND SUBSTRING(CONSTRAINT_NAME FROM 1 FOR 3) = 'CS_'"
        )

        assert checkResult.size() == 1

        then:

        EntityHelper.filterEntity(ec, 'moqui.test.Invoice@redwings', null).count() == 1
        EntityHelper.filterEntity(ec, 'moqui.test.InvoiceItem@redwings', null).count() == 1

    }
}
