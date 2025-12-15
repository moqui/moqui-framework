import ars.rockycube.endpoint.ProcedureExecutor

/**
 * Launch a stored procedure and pass data of closureItemId into the procedure,
 * the procedure should use this parameter to load related data
 *  @return data
 */
def launchWithParams() {
    ProcedureExecutor pe = new ProcedureExecutor()
    try {
        return pe.executeProcedureWithParams(connectionName, procedureName, companyId, closureItemId)
    } catch (Exception exc){
        ec.logger.error("Procedure executor failed when executing: ${exc.message}")
        ec.message.addPublic(exc.message, 'error')
        throw exc
    }
}

/**
 * Launch stored procedure and use direct data
 * @return data
 */
def launchWithList() {
    ProcedureExecutor pe = new ProcedureExecutor()
    try {
        return pe.executeProcedureWithList(connectionName, procedureName, companyId, data)
    } catch (Exception exc){
        ec.logger.error("Procedure executor failed when executing: ${exc.message}")
        ec.message.addPublic(exc.message, 'error')
        throw exc
    }
}