package dtq.rockycube.entity

import dtq.rockycube.endpoint.EndpointServiceHandler
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityException
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * This class is used as a handler that takes care of bulk insert
 * into database. Transaction support introduced.
 */
class BulkEntityHandler {
    protected final static Logger logger = LoggerFactory.getLogger(BulkEntityHandler.class);
    private ExecutionContext ec
    private ExecutionContextFactoryImpl ecfi
    private boolean failsafeSwitch = false

    BulkEntityHandler()
    {
        this.ec = Moqui.getExecutionContext()
        this.ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
    }

    boolean getFailsafeSwitch() {
        return failsafeSwitch
    }

    void setFailsafeSwitch(boolean failsafeSwitch) {
        this.failsafeSwitch = failsafeSwitch
    }
    /**
     * Write changes in a single transaction
     * @param entityName
     * @param changes
     * @param deletions
     * @return
     */
    public HashMap writeChanges(String entityName, ArrayList<HashMap> changes, ArrayList<HashMap> deletions, ArrayList serviceAllowedOn)
    {
        // create transaction and launch insert inside it
        def newTxCreated = ec.transaction.begin(null)
        if (!newTxCreated) throw new EntityException("Unable to initialize transaction prior to operations execution")

        // initialize service handler that will serve as a manipulator of
        // data throughout this procedure
        EndpointServiceHandler esh = new EndpointServiceHandler(
                [updateIfExists:true, searchUsingDataProvided:true],
                [],
                entityName,
                null,
                serviceAllowedOn
        )

        // some basic stats
        def upserts = 0
        def deletes = 0
        def upsertFails = []
        def deleteFails = []

        try {
            // IMPORTANT
            // store result of each respective attempt to modify data, to have a nice output data piece

            // inserts/updates first
            changes.each { it ->
                try {
                    def dbOperation = esh.createEntityData(it)
                    if (dbOperation.result) upserts += 1
                } catch (Exception exc)
                {
                    // store info on fails
                    upsertFails.push(exc.message)
                }
            }

            // deletes afterwards
            deletions.each { it ->
                try {
                    def dbOperation = esh.deleteEntityData((HashMap) it)
                    if (dbOperation.result) deletes += 1
                } catch (Exception exc)
                {
                    deleteFails.push(exc.message)
                }
            }

            // throw exception if any of these lists are empty
            if (!deleteFails.empty || !upsertFails.empty) throw new EntityException("Unsuccessful execution")

            // commit
            ec.transaction.commit()
        } catch (Exception exc){
            // rollback first
            ec.transaction.rollback("Exception in BulkEntityHandler", exc)

            logger.error("Error writing changes to entities [${exc.message}]")
            if (!failsafeSwitch) throw exc

            return [
                    result: false,
                    message: exc.message,
                    upsertFails: upsertFails,
                    deleteFails: deleteFails
            ]
        }

        return [
                result: true,
                message: "Operations performed successfully",
                upserts: upserts,
                deletes: deletes
        ]
    }
}
