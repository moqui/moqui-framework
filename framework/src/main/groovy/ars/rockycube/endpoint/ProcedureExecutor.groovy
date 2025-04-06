package ars.rockycube.endpoint

import ars.rockycube.query.SqlExecutor
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ProcedureExecutor {
    protected final static Logger logger = LoggerFactory.getLogger(ProcedureExecutor.class);

    public static HashMap executeProcedureWithParams(String connectionName, String procedureName, String companyId, String closureItemId) {
        ExecutionContext ec = Moqui.getExecutionContext()

        logger.debug("Executing procedure: ${procedureName}")

        // execute the procedure via ViUtilities
        def conn = ec.entity.getConnection(connectionName)
        def res = SqlExecutor.executeStoredProcedure(conn, logger, procedureName, [companyId, closureItemId])

        return [result: true]
    }
}
