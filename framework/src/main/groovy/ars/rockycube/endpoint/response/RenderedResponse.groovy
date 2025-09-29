package ars.rockycube.endpoint.response

import ars.rockycube.endpoint.EndpointException
import ars.rockycube.entity.EntityHelper
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityException
import org.moqui.util.ObjectUtilities
import org.moqui.util.RestClient
import org.moqui.util.StringUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory


class RenderedResponse {
    protected final static Logger logger = LoggerFactory.getLogger(RenderedResponse.class);

    /**
     * Search for a BLOB content in an entity and return it as a response. Used
     * for serving content in Scaffold based Teams applications where content
     * is stored in ARS backend, rather than in SharePoint. This method returns the contents
     * in a response, e.g. for REST API bound frontend calls
     */
    public static sendEntityByteContentResponse(String fullEntityName, Map<String, Object> conditionMap, boolean disableAuthz, boolean inline) {
        // initialize EC
        def ec = Moqui.getExecutionContext()
        def response = ec.web.response

        // display tokens
        def request = ec.web.request
        ec.logger.debug(request.headerNames.toList().join(','))

        // search for the entity
        def eh = new EntityHelper(ec)
        def fileInfo = new HashMap<String, Object>()

        try {
            OutputStream os = response.outputStream
            def is = eh.getEntityByteContent(fullEntityName, (Map<String, Object>) conditionMap, fileInfo, disableAuthz)

            int totalLen = ObjectUtilities.copyStream(is, os)
            is.close()
            try {
                // pass
            } finally {
                os.close()
            }
        } finally {

        }

        // return response back to frontend as inline content
        if (inline) {
            response.setHeader("Content-Disposition", "inline")
        } else {
            def filename = fileInfo.containsKey('name') ? fileInfo.get('name') : 'unknown.file'
            response.setHeader("Content-Disposition", "attachment; filename=\"${filename}\"; filename*=utf-8''${StringUtilities.encodeAsciiFilename(filename)}")
        }

        // set content type
        // response.setContentType("application/octet-stream")
        response.setContentType("plain/text")
        response.setCharacterEncoding("UTF-8")
    }


    /**
     * Return rendered response. Entity data is loaded, then it's rendered using
     * PY-CALC templating engine
     *
     * Note: used in a dedicated file fore improved visibility in code
     * @param entityName
     * @param term
     * @param args
     * @param templateName
     * @param inline
     */
    public static void sendRenderedResponse(String entityName, ArrayList term, LinkedHashMap args, String templateName, boolean inline) {
        // add args
        if (!args.containsKey('preferObjectInReturn')) args['preferObjectInReturn'] = true

        // initialize EC
        def ec = Moqui.getExecutionContext()

        try {
            // 1. load data, use endpoint handler (and terms) to locate data
            def reportData = ec.service.sync().name("ars.rockycube.EndpointServices.populate#EntityData").parameters([
                    entityName: entityName,
                    term      : term,
                    args      : args
            ]).call() as HashMap

            // log error if provided by the called service
            if (ec.message.hasError()) ec.logger.error(ec.message.getErrors()[-1])

            // check result
            if (!reportData) throw new EntityException("Unable to retrieve data for rendering response")
            if (!reportData.containsKey('data')) throw new EntityException("No data in response, cannot proceed with template rendering")

            def dataToProcess = reportData['data']

            // 2. transfer data to PY-CALC to get it rendered
            // by writing into response's output stream
            InputStream renderedTemplate = renderTemplate(templateName, dataToProcess)
            def response = ec.web.response

            try {
                OutputStream os = response.outputStream
                try {
                    int totalLen = ObjectUtilities.copyStream(renderedTemplate, os)
                    logger.info("Streamed ${totalLen} bytes from response")
                } finally {
                    os.close()
                }
            } finally {
                // close stream
                renderedTemplate.close()
            }

            // 3. return response back to frontend as inline content
            if (inline) response.addHeader("Content-Disposition", "inline")

            // set content type
            response.setContentType("text/html")
            response.setCharacterEncoding("UTF-8")

        } catch (Exception exc) {
            ec.logger.error(exc.message)
            ec.web.response.sendError(400, "Unable to generate template")
        }
    }

    /**
     * Render data in PY-CALC using template
     * @param template
     * @param data
     * @return
     */
    public static InputStream renderTemplate(String template, Object data) {
        ExecutionContext ec = Moqui.getExecutionContext()

        // expect config in system variables
        def pycalcHost = System.properties.get("py.server.host")
        def renderTemplateEndpoint = System.properties.get("py.endpoint.render")

        // basic checks
        if (!pycalcHost) throw new EndpointException("PY-CALC server host not defined")
        if (!renderTemplateEndpoint) throw new EndpointException("PY-CALC server's render template endpoint not set")

        RestClient restClient = ec.service.rest().method(RestClient.POST)
                .uri("${pycalcHost}/${renderTemplateEndpoint}")
                .addHeader("Content-Type", "application/json")
                .addBodyParameter("template", template)
                .jsonObject(data)
        RestClient.RestResponse restResponse = restClient.call()

        // check status code
        if (restResponse.statusCode != 200) {
            throw new EndpointException("Response with status ${restResponse.statusCode} returned: ${restResponse.reasonPhrase}")
        }

        return new ByteArrayInputStream(restResponse.bytes());
    }
}
