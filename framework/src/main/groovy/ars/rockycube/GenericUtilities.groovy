package ars.rockycube

import com.google.gson.Gson
import com.google.gson.JsonParseException
import groovy.json.JsonOutput
import org.apache.groovy.json.internal.LazyMap
import org.moqui.context.ExecutionContext
import org.moqui.context.TemplateRenderer
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.resource.ResourceReference
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class GenericUtilities {
    protected static Gson gson = new Gson()
    /**
     * This method expects, there is an FTL template provided,
     * subsequently, using the context, the template is rendered
     * @param conn
     * @param logger
     * @param queryFile
     * @param params
     * @return
     */
    static void renderTemplate(
            ExecutionContext ec,
            String templateFile,
            StringWriter sw,
            HashMap params) {

        // add to parameters
        ec.context.put('parameters', params)

        def ecfi = (ExecutionContextFactoryImpl) ec.factory
        TemplateRenderer templateRenderer = ecfi.resourceFacade.ftlTemplateRenderer
        templateRenderer.render(templateFile, sw)

        // remove from parameters
        ec.context.remove('parameters')
    }

    /**
     * Process JSONB fields into standard value
     * @param field
     * @return
     */
    public static Object processField(Object field)
    {
        def fieldClass = field.getClass().simpleName.toLowerCase()
        switch (fieldClass)
        {
            case "byte[]":
                String itStrVal = new String((byte[]) field, StandardCharsets.UTF_8)
                return convertToComplexType(itStrVal)
            case "hashmap":
            case "lazymap":
                return field
            case "arraylist":
                return field
            case "nullobject":
                return null
            default:
                return convertToComplexType(field.toString())
        }
    }

    /**
     * Shared debug procedure. Method should be used by all methods of this
     * class, to make sure procedureId is used to create file name wherever
     * possible.
     * @param ec
     * @param processingId
     * @param fileId
     * @param data
     * @param internalCounter
     */
    public static void debugFile(
            ExecutionContext ec,
            String processingId,
            String sessionId,
            String fileId,
            Object data,
            Long internalCounter=0) {
        try {
            // default ext
            def defaultExt = 'json'
            def fileName = fileId
            def fileExtension = defaultExt

            // check for extension
            def recExt = Pattern.compile('(.+)\\.(\\w{3,4})$')
            def m = recExt.matcher(fileName)
            if (m.matches())
            {
                fileName = m.group(1)
                fileExtension = m.group(2)
            }

            // calculate file name with counter, if provided
            if (internalCounter > 0) {
                fileName = "${fileName}.${internalCounter}.${fileExtension}"
            } else {
                fileName = "${fileName}.${fileExtension}"
            }

            // use processing ID if present
            fileName = sessionId ? "${sessionId}.${fileName}" : "nos.${fileName}"
            fileName = processingId ? "${processingId}.${fileName}" : "nop.${fileName}"

            // run internal method
            storeFileLocally(ec, null, "${fileName}.${fileExtension}".toString(), data)
        } catch (Exception exc) {
            ec.logger.error("Cannot debug-store file: ${exc}")
        }
    }

    /**
     * Method for storing file into local file system, by default it shall write to
     * `tmp` directory
     * @param ec
     * @param location
     * @param fileName
     * @param data
     */
    public static void storeFileLocally(
            ExecutionContext ec, String location, String fileName, Object data){

        // if not provided, assume we are going to write to `tmp`
        if (!location) location = "tmp"
        ResourceReference ref = ec.resource.getLocationReference(location)

        // check if the file is JSON and act accordingly
        def fileExtension = null
        def recExt = Pattern.compile('(.+)\\.(\\w{3,4})$')
        def m = recExt.matcher(fileName)
        if (m.matches())
        {
            fileName = m.group(1)
            fileExtension = m.group(2)
        }

        if (fileExtension == 'json') ref.makeFile(fileName).putText (JsonOutput.prettyPrint(JsonOutput.toJson(data)))
        if (fileExtension != 'json') ref.makeFile(fileName).putText (data as String)
    }

    /*
     * Store file in a `tmp` directory of the backend, for debugging purposes.
     * For situations when no processingId and sessionId is set.
     */
    public static void debugFile(ExecutionContext ec, String fileId, Object data, Long internalCounter=0)
    {
        debugFile(ec, null, null, fileId, data, internalCounter)
    }

    private static Object convertToComplexType(String incomingStr){
        def firstChar = incomingStr.substring(0,1)
        if (firstChar == "[")
        {
            return gson.fromJson(incomingStr, ArrayList.class)
        } else {
            return gson.fromJson(incomingStr, HashMap.class)
        }
    }

    public static Boolean isEmpty(Object obj)
    {
        if (obj == null) return true

        switch(obj.getClass())
        {
            case ArrayList.class:
                return (obj as ArrayList).isEmpty()
            case LinkedHashMap.class:
            case LazyMap.class:
            case HashMap.class:
                return (obj as HashMap).isEmpty()
            default:
                return obj.toString().size() == 0
        }
    }

    public static long length(Object obj) {
        if (obj.getClass().isArray()) {
            return (obj as ArrayList).size()
        } else if (obj.getClass() == ArrayList.class){
            return (obj as ArrayList).size()
        } else if (obj.getClass() == HashMap.class){
            return (obj as HashMap).keySet().size()
        } else if (obj.getClass() == LinkedHashMap.class){
            return (obj as LinkedHashMap).keySet().size()
        } else if (obj.getClass() == LazyMap.class){
            return (obj as LazyMap).keySet().size()
        } else {
            return -1
        }
    }

    /**
     * This method processes each map using a closure, no matter if it is standalone or in an array
     * @param inp
     * @param cbProcess
     */
    public static void processEachMap(Object inp, Closure cbProcess)
    {
        switch(inp.getClass())
        {
            case ArrayList.class:
                (inp as ArrayList).each {it->
                    cbProcess(it)
                }
                break
            default:
                cbProcess(inp)
        }
    }

    /**
     * Checks if object is of type InputStream
     * @param object
     * @return
     */
    public static boolean isInputStream(Object object) {
        return object instanceof FileInputStream || object instanceof ByteArrayInputStream || object instanceof BufferedInputStream
    }


    public static HashMap extractStatistics(Object input){
        // easy for the array
        if (input instanceof ArrayList) return [rows: length(input)]

        // specialities supported
        // 1. if the map contains formData, calculate it on the `formData`key
        try {
            def stats = [:]

            switch (input.getClass()) {
                case LinkedHashMap.class:
                    stats.put('rows', mapSize(input as HashMap))
                    return stats
                case HashMap.class:
                    stats.put('rows', mapSize(input as HashMap))
                    return stats
                case LazyMap.class:
                    stats.put('rows', mapSize(input as LazyMap))
                    return stats
            }
        } catch (Exception ignored){}
        return [rows: -1]
    }

    private static long mapSize(Map input)
    {
        def len = 0
        if (input.containsKey('formData')) {
            len = length(input['formData'])
        } else {
            len = length(input)
        }
        return len
    }

    private static boolean isFirstCharBracket(InputStream is) {
        is.mark(1)  // Mark the current position in the input stream
        int firstByte = is.read()
        if (firstByte == -1) {
            throw new IOException("InputStream is empty")
        }
        char firstChar = (char) firstByte
        is.reset()  // Reset to the marked position
        return firstChar == '['
    }

    /**
     * Method that converts InputStream to either map or list
     * @param is
     * @return
     */
    public static convertStreamToJs(InputStream is){
        def js
        try {
            if (isFirstCharBracket(is)) {
                js = gson.fromJson(is.newReader("UTF-8"), ArrayList.class)
            } else {
                js = gson.fromJson(is.newReader("UTF-8"), HashMap.class)
            }
        } catch (Exception ignored) {
            throw new JsonParseException("Unable to convert file to JSON")
        }
        return js
    }

    /**
     * Load credentials from System environment
     * @return
     */
    public static HashMap loadSharepointCredentialsFromEnv() {
        def tid = System.getenv('RS_MS_TENANT_ID')
        def cid = System.getenv('RS_MS_CLIENT_ID')
        def cs = System.getenv('RS_MS_CLIENT_SECRET')

        if (tid && cid && cs){
            return [tenantId: tid, clientId: cid, clientSecret: cs]
        }
        return [:]
    }

    /**
     * Converts simple array to a CSV
     * @param list
     * @return
     */
    static String convertToCsv(ArrayList<HashMap> list) {
        if (list == null || list.isEmpty()) {
            return ""
        }

        // Get headers from the keys of the first HashMap
        def headers = list[0].keySet().collect { header -> "\"${header}\"" }.join(",")

        // Convert each HashMap to a comma-separated string of values
        def rows = list.collect { row ->
            row.values().collect { value ->
                value instanceof Number ? value.toString() : "\"${value}\""
            }.join(",")
        }

        // Combine headers and rows
        return (headers + System.lineSeparator() + rows.join(System.lineSeparator()))
    }
}
