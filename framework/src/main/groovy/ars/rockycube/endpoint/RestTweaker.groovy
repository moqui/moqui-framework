package ars.rockycube.endpoint

import org.moqui.context.ExecutionContext
import org.moqui.util.ObjectUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets

class RestTweaker {
    protected final static Logger logger = LoggerFactory.getLogger(EndpointServiceHandler.class);

    /**
     * Format response as plain text. This method has been tested with REST API,
     * it works simply as an endpoint to format and fetch data
     * @param ec
     * @param content
     * @param inline
     */
    public static void sendPlainTextResponse(ExecutionContext ec, String content, boolean inline) {
        def response = ec.web.response
        
        try {
            OutputStream os = response.outputStream
            InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            
            int totalLen = ObjectUtilities.copyStream(is, os)
            is.close()
            try {
                logger.info("Streamed ${totalLen} bytes from response")
            } finally {
                os.close()
            }
        } finally {
        }

        // 3. return response back to frontend as inline content
        if (inline) response.addHeader("Content-Disposition", "inline")

        // set content type
        response.setContentType("plain/text")
        response.setCharacterEncoding("UTF-8")
    }
}
