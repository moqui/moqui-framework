package ars.rockycube.endpoint

import ars.rockycube.query.SqlExecutor
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ProcedureExecutor {
    protected final static Logger logger = LoggerFactory.getLogger(ProcedureExecutor.class);

    public static HashMap executeProcedureWithParams(String connectionName, String procedureName, String closureItemId) {
        ExecutionContext ec = Moqui.getExecutionContext()

        logger.info("Executing procedure: ${procedureName}")

        // execute the procedure via ViUtilities
        def conn = ec.entity.getConnection(connectionName)
        def res = SqlExecutor.executeStoredProcedure(conn, logger, procedureName, [closureItemId])

        return [result: true]
    }
}
