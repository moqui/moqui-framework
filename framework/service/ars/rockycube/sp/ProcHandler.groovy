import ars.rockycube.endpoint.ProcedureExecutor

def launchWithParams()
{
    ProcedureExecutor pe = new ProcedureExecutor()
    try {
        return pe.executeProcedureWithParams(connectionName, procedureName, companyId, closureItemId)
    } catch (Exception exc){
        ec.logger.error("Procedure executor failed when executing: ${exc.message}")
        ec.message.addPublic(exc.message, 'error')
        throw exc
    }
}