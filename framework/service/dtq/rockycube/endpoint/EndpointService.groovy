import dtq.rockycube.endpoint.EndpointServiceHandler

def loadFromEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler(args, term, entityName, tableName, failsafe, serviceAllowedOn)
    // ec.logger.debug("Executing loadFromEntity method")
    try {
        return ech.fetchEntityData(index, size, orderBy)
    } catch (Exception exc){
        ec.logger.error("Error when fetching data: ${exc.message}")
        return [result: false, message: "Failed on fetch: ${exc.message}"]
    }
}

def deleteEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler(args, term, entityName, tableName, failsafe, serviceAllowedOn)
    // ec.logger.debug("Executing deleteEntity method")
    try {
        return ech.deleteEntityData()
    } catch (Exception exc){
        ec.logger.error("Error when deleting data: ${exc.message}")
        return [result: false, message: "Failed on delete: ${exc.message}"]
    }

}

def updateEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler(args, term, entityName, tableName, failsafe, serviceAllowedOn)
    try {
        return ech.updateEntityData(data)
    } catch (Exception exc){
        ec.logger.error("Error when updating data: ${exc.message}")
        return [result: false, message: "Failed on update: ${exc.message}"]
    }
}

def createEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler(args, term, entityName, tableName, failsafe, serviceAllowedOn)
    try {
        return ech.createEntityData(data)
    } catch (Exception exc){
        ec.logger.error("Error when creating data: ${exc.message}")
        return [result: false, message: "Failed on create: ${exc.message}"]
    }
}