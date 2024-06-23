package ars.rockycube.endpoint

import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class CalcHandler {
    protected final static Logger logger = LoggerFactory.getLogger(CalcHandler.class);
    private ExecutionContext ec

    CalcHandler(ExecutionContext executionContext) {
        this.ec = executionContext
    }

    public HashMap processSpreadsheetData(Object dataToVisualize)
    {
        logger.debug("Processing data, this is what needs to be processed: ${dataToVisualize}")

        // load API from parameters
        def endpoint = System.properties.get("py.endpoint.vizualize.spreadsheet")

        return EndpointServiceHandler.processItemsForVizualization(
                this.ec,
                dataToVisualize,
                (String) endpoint,
                (HashMap) this.ec.context.args?:[:] as HashMap,
                false) as HashMap
    }
}
