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
    public HashMap writeChanges(String entityName, ArrayList<HashMap> changes, ArrayList<HashMap> deletions)
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
                false,
                []
        )

        try {
            changes.each {it->
                esh.createEntityData(it)
            }

            // commit
            ec.transaction.commit()
        } catch (Exception exc){
            // rollback first
            ec.transaction.rollback("Exception in BulkEntityHandler", exc)

            logger.error("Error writing changes to entities [${exc.message}]")
            if (!failsafeSwitch) throw exc

            return [
                    result: false,
                    message: "Writing changes to entities via BulkEntityHandler failed: [${exc.message}]"
            ]
        }

        return [
                result: true,
                message: "Operations performed [${changes.size() + deletions.size()}]"
        ]
    }
}
