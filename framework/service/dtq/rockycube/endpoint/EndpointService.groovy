import dtq.rockycube.endpoint.EndpointServiceHandler

def loadFromEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler()
    // ec.logger.debug("Executing loadFromEntity method")
    try {
        return ech.fetchEntityData()
    } catch (Exception exc){
        return [result: false, message: "Failed on fetch: ${exc.message}"]
    }
}

def deleteEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler()
    // ec.logger.debug("Executing deleteEntity method")
    try {
        return ech.deleteEntityData()
    } catch (Exception exc){
        return [result: false, message: "Failed on delete: ${exc.message}"]
    }

}

def updateEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler()
    try {
        return ech.updateEntityData()
    } catch (Exception exc){
        return [result: false, message: "Failed on update: ${exc.message}"]
    }
}

def createEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler()
    try {
        return ech.createEntityData()
    } catch (Exception exc){
        return [result: false, message: "Failed on create: ${exc.message}"]
    }
}