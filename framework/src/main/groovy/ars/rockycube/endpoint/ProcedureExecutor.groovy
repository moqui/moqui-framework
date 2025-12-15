package ars.rockycube.endpoint

import ars.rockycube.query.SqlExecutor
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ProcedureExecutor {
    protected final static Logger logger = LoggerFactory.getLogger(ProcedureExecutor.class);

    /**
     * Execute a stored procedure and pass data on the actual closureItemId into it. The
     * rest is responsibility of the SP, to use the ID to load data.
     * @param connectionName
     * @param procedureName
     * @param companyId
     * @param closureItemId
     * @return
     */
    public static HashMap executeProcedureWithParams(String connectionName, String procedureName, String companyId, String closureItemId) {
        ExecutionContext ec = Moqui.getExecutionContext()

        logger.debug("Executing procedure: ${procedureName}")

        // execute the procedure via ViUtilities
        def conn = ec.entity.getConnection(connectionName)
        def res = SqlExecutor.executeStoredProcedure(conn, logger, procedureName, [companyId, closureItemId])

        return [result: true]
    }

    /**
     * Execute a stored procedure and pass data (in form of a list) to it
     * @param connectionName
     * @param procedureName
     * @param companyId
     * @param closureItemId
     * @return
     */
    public static HashMap executeProcedureWithList(String connectionName, String procedureName, String companyId, ArrayList data) {
        ExecutionContext ec = Moqui.getExecutionContext()

        // execute the procedure via ViUtilities
        def conn = ec.entity.getConnection(connectionName)
        def res = SqlExecutor.executeStoredProcedure(conn, logger, procedureName, [companyId, data])

        return [result: true]
    }
}
