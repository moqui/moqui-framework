import org.moqui.entity.EntityDataLoader

def insertFromJson() {
    EntityDataLoader edl = ec.entity.makeDataLoader()
    edl.jsonText(data.toString())

    if (timeout) edl.transactionTimeout(timeout as Integer)
    if (dummyFks) edl.dummyFks(true)
    if (useTryInsert) edl.useTryInsert(true)

    edl.onlyCreate(true)
    List<String> messages = new LinkedList<>()
    long recordsLoaded = edl.load(messages)

    ec.logger.info("Records created: [${recordsLoaded}]")
    //ec.message.addMessage("Loaded ${recordsLoaded} records from [${source}]")
    //ec.web.session.setAttribute("DataImport.messages", messages)
}