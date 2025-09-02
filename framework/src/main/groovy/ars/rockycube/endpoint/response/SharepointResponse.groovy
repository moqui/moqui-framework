package ars.rockycube.endpoint.response

import ars.rockycube.endpoint.EndpointException
import org.moqui.context.ExecutionContext
import org.moqui.util.RestClient

import static ars.rockycube.endpoint.proc.EndpointCaller.checkPyCalcResponse

class SharepointResponse {
    /**
     * Fetch items from a Sharepoint list, provided we have credentials
     * @param ec
     * @param credentials
     * @param location
     * @return
     */
    public static ArrayList fetchItemsFromSpList(
            ExecutionContext ec,
            HashMap credentials,
            HashMap location) {

        // use existing method and return bytes
        def b = sendJsonToSharepoint(ec, credentials, location, 'api/v1/utility/sharepoint/fetch-list')

        return (ArrayList) b.jsonObject()
    }

    /**
     * Call against SharePoint API using JSON
     * @param ec
     * @param credentials - shall be used to initialize the connection to Sharepoint (e.g. tenantId, clientId, clientSecret)
     * @param location - where is the file located
     * @param contentType - JSON?
     * @return
     */
    public static RestClient.RestResponse sendJsonToSharepoint(
            ExecutionContext ec,
            HashMap credentials,
            HashMap location,
            String endpoint='api/v1/utility/sharepoint/fetch-bytes',
            RestClient.Method method = RestClient.Method.POST)
    {
        return genericSendJsonToSharepoint(ec, credentials, location, endpoint, null, method)
    }

    /**
     * Import data to Sharepoint via py-calc call, supports content as a list
     * @param ec
     * @param credentials
     * @param location
     * @param content
     * @param method
     * @return
     */
    public static RestClient.RestResponse genericSendJsonToSharepoint(
            ExecutionContext ec,
            HashMap credentials,
            HashMap location,
            String endpoint,
            ArrayList content,
            RestClient.Method method
    ) {
        def pycalcHost = System.properties.get("py.server.host")
        if (!pycalcHost) throw new EndpointException("PY-CALC server host not defined")

        // timeout
        def prop = (String) System.getProperty("py.server.request.timeout", '45000')
        def calcTimeout = prop.toLong()

        // check if we can use the caller's request headers here
        // it may be a sound solution to pass configuration parameters
        // from Apache Camel and use them to customize the next call
        Map<String, String> selectedHeaders = new HashMap()
        if (ec.web) {
            def headerNames = ec.web.request.headerNames
            headerNames.each {
                if (it.startsWith("ARS")) {
                    ec.logger.debug("Using header for subsequent call: ${it}")
                    selectedHeaders[it] = ec.web.request.getHeader(it)
                }
            }
        }

        ec.logger.debug("ARS headers used: ${selectedHeaders}")

        // data prep
        // @todo consider moving credentials to header
        HashMap<String, Object> payload = [credentials: credentials, location:location]
        // add content if provided
        if (content) if (!content.empty) payload.put('data', content)
        def customTimeoutReqFactory = new RestClient.SimpleRequestFactory(calcTimeout)

        RestClient restClient = ec.service.rest().method(method)
                .uri("${pycalcHost}/${endpoint}")
                .addHeaders(selectedHeaders)
                .timeout(480)
                .retry(2, 10)
                .maxResponseSize(50 * 1024 * 1024)
                .jsonObject(payload)
                .withRequestFactory(customTimeoutReqFactory)

        // execute
        RestClient.RestResponse resp = restClient.call()
        checkPyCalcResponse(resp)

        // return response
        return resp
    }
}
