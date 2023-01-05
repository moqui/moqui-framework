import dtq.rockycube.endpoint.EndpointServiceHandler

def loadFromEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler()
    // ec.logger.debug("Executing loadFromEntity method")
    return ech.fetchEntityData()
}

def deleteEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler()
    // ec.logger.debug("Executing deleteEntity method")
    return ech.deleteEntityData()
}

def updateEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler()
    return ech.updateEntityData()
}

def createEntity()
{
    EndpointServiceHandler ech = new EndpointServiceHandler()
    return ech.createEntityData()
}