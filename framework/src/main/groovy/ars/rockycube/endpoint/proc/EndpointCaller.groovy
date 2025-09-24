package ars.rockycube.endpoint.proc

import ars.rockycube.endpoint.EndpointException
import org.moqui.context.ExecutionContext
import org.moqui.impl.ViUtilities
import org.moqui.util.RestClient
import org.moqui.util.StringUtilities

import static ars.rockycube.GenericUtilities.debugFile

class EndpointCaller {
    // debug mode
    private static debug() {
        return "dev".equals(System.getProperty("instance_purpose"))
    }

    /**
     * Method that runs item through PY-CALC and let it be transformed there.
     * This is the default method to be used, with the exception of test run.
     * @param ec
     * @param itemToCalculate
     * @param proceduresList
     * @param extraParams
     * @param cbCheckData
     * @param identity
     * @return
     */
    public static Object processItemInCalc(
            ExecutionContext ec,
            Object itemToCalculate,
            Object proceduresList,
            HashMap extraParams,
            Closure cbCheckData,
            boolean extractPycalcArgs,
            String sessionId = null)
    {
        def pycalcHost = System.properties.get("py.server.host")

        // if there is a procHistoryId in the request, use it
        def execHistoryId = ec.web ? ec.web.request.getHeader("ARS-execHistoryId") : null
        def processingId = ec.web ? ec.web.request.getHeader("ARS-processingId") : 'nop'

        // set sessionId if not set
        if (debug() && !sessionId) sessionId = StringUtilities.getRandomString(11)

        // store info for debugging purposes
        if (debug()) {
            debugFile(ec, processingId, sessionId, "process-items.json", itemToCalculate)
            debugFile(ec, processingId, sessionId, "process-items-procedures.json", proceduresList)
            debugFile(ec, processingId, sessionId, "process-items-extra.json", extraParams)
        }

        // basic checks
        if (!pycalcHost) throw new EndpointException("PY-CALC server host not defined")

        // if the incoming data is InputStream, encode it to Base64
        boolean encodeToBase64 = itemToCalculate instanceof InputStream

        // data prep
        def payload = [
                setup: [
                        procedure: proceduresList,
                        output_only_last: true,
                        extra: extractPycalcArgs ? ViUtilities.extractPycalcArgs(extraParams) : extraParams,
                        proc_id: processingId,
                        session_id: sessionId
                ],
                data: encodeToBase64 ? Base64.encoder.encodeToString((itemToCalculate as InputStream).getBytes()) : itemToCalculate
        ]

        // debug what is going to py-calc
        if (debug()) debugFile(ec, processingId, sessionId, "c-h-process-items-to-execute.json", payload)

        // use specific RequestFactory, with custom timeouts
        // timeout is set by settings
        def prop = (String) System.getProperty("py.server.request.timeout", '45000')
        def calcTimeout = prop.toLong()

        // pass ARS headers further down-stream
        HashMap<String, String> headers = new HashMap()
        headers.put("Content-Type", "application/json")
        if (execHistoryId) headers.put("ARS-execHistoryId", execHistoryId)
        if (processingId) headers.put("ARS-processingId", processingId)

        RestClient restClient = ec.service.rest().method(RestClient.POST)
                .uri("${pycalcHost}/api/v1/calculator/execute")
                .timeout(calcTimeout/1000 as int)
                .retry(2, 10)
                .maxResponseSize(50 * 1024 * 1024)
                .addHeaders(headers)
                .jsonObject(payload)

        RestClient.RestResponse resp = restClient.call()
        checkPyCalcResponse(resp)

        // must handle all states of the response
        def rsp = (HashMap) resp.jsonObject()

        // debug what has come out of the processing
        if (debug()) debugFile(ec, processingId, sessionId, "c-h-process-items-result.json", resp.jsonObject())

        // use callback to check/modify response
        if (cbCheckData) {
            rsp = cbCheckData(rsp)

            // another layer of processing
            if (debug()) debugFile(ec, processingId, sessionId, "c-h-process-items-result-mod.json", rsp)
        }

        return rsp
    }

    /**
     * This method processes items into list, ready for vizualization
     * @param ec
     * @param allItems
     * @param endpoint
     * @param args
     * @param debug
     * @param identity
     * @return
     */
    public static Object processItemsForVizualization(
            ExecutionContext ec,
            ArrayList allItems,
            String endpoint,
            HashMap args,
            boolean debug,
            String identity = null)
    {
        def pycalcHost = System.properties.get("py.server.host")

        // set identity if not set
        if (debug && !identity) identity = StringUtilities.getRandomString(11)

        // debug for development purposes
        if (debug) debugFile(ec, null, null, "vizualize-items-before-calc.${identity}.json", allItems)

        // pass only those arguments, that have `pycalc` prefix
        def pyCalcArgs = ViUtilities.extractPycalcArgs(args as HashMap)

        // store parameters
        if (debug) debugFile(ec, null, null, "vizualize-items-calc-params.${identity}.json", pyCalcArgs)

        RestClient restClient = ec.service.rest().method(RestClient.POST)
                .uri("${pycalcHost}/${endpoint}")
                .addHeader("Content-Type", "application/json")
                .jsonObject(
                        [
                                data: allItems,
                                args: pyCalcArgs
                        ]
                )
        RestClient.RestResponse resp = restClient.call()
        checkPyCalcResponse(resp)

        HashMap response = resp.jsonObject() as HashMap
        return response['data']
    }

    /**
     * Method checks response from PY-CALC and throws an error, should
     * en unexpected response arrive
     * @param restResponse
     */
    public static void checkPyCalcResponse(RestClient.RestResponse restResponse) {

        // check status code
        if (restResponse.statusCode != 200) {
            def errMessage = "Response with status ${restResponse.statusCode} returned: ${restResponse.reasonPhrase}"
            // display more info depending on what is being returned
            if (restResponse.headers().containsKey('x-exception-detail'))
            {
                errMessage = restResponse.headerFirst('x-exception-detail')
            }
            throw new EndpointException(errMessage)
        }
    }
}
