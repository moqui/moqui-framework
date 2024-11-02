import ars.rockycube.endpoint.EndpointServiceHandler

def loadFromEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler(ec, companyId, args, term, entityName, tableName, serviceAllowedOn)
    // ec.logger.debug("Executing loadFromEntity method")
    try {
        result = ech.fetchEntityData(index, size, orderBy)
    } catch (Exception exc){
        ec.logger.error("Error when fetching data [FAILSAFE option: ${failsafe}]: ${exc.message}")
        if (failsafe) return [result: false, message: "Failed on fetch: ${exc.message}"]
        ec.message.addPublic(exc.message, 'error')
        throw exc
    }
}

def deleteEntity()
{
    // set term using identity field
    // the condition will be calculated inside ESH
    if (identity) args.put('identitySearch', identity)

    EndpointServiceHandler ech = new EndpointServiceHandler(ec, companyId, args, term, entityName, tableName, serviceAllowedOn)
    // ec.logger.debug("Executing deleteEntity method")
    try {
        return ech.deleteEntityData()
    } catch (Exception exc){
        ec.logger.error("Error when deleting data: ${exc.message}")
        if (failsafe) return [result: false, message: "Failed on delete: ${exc.message}"]
        ec.message.addPublic(exc.message, 'error')
        throw exc
    }

}

def updateEntity()
{
    // set term using identity field
    // the condition will be calculated inside ESH
    if (identity) args.put('identitySearch', identity)

    EndpointServiceHandler ech = new EndpointServiceHandler(ec, companyId, args, term, entityName, tableName, serviceAllowedOn)
    try {
        return ech.updateEntityData(data)
    } catch (Exception exc){
        ec.logger.error("Error when updating data: ${exc.message}")
        if (failsafe) return [result: false, message: "Failed on update: ${exc.message}"]
        ec.message.addPublic(exc.message, 'error')
        throw exc
    }
}

def createEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler(ec, companyId, args, term, entityName, tableName, serviceAllowedOn)
    try {
        return ech.createEntityData(data)
    } catch (Exception exc){
        ec.logger.error("Error when creating data: ${exc.message}")
        if (failsafe) return [result: false, message: "Failed on create: ${exc.message}"]
        ec.message.addPublic(exc.message, 'error')
        throw exc
    }
}