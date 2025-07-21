package ars.rockycube

import com.google.gson.Gson
import com.google.gson.JsonParseException
import groovy.json.JsonOutput
import org.apache.commons.lang3.RandomStringUtils
import org.apache.groovy.json.internal.LazyMap
import org.moqui.context.ExecutionContext
import org.moqui.context.TemplateRenderer
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.resource.ResourceReference
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.atomic.AtomicLong
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

    // global, thread-safe counter accessible across all method calls
    private static final AtomicLong globalFileCounter = new AtomicLong(0)

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
                fileName = "${fileName}.${internalCounter}"
            } else {
                fileName = "${fileName}"
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
    public static Long storeFileLocally(
            ExecutionContext ec, String location, String fileName, Object data, Boolean ignoreCounter=false){

        // if not provided, assume we are going to write to `tmp`
        if (!location) location = "tmp"
        ResourceReference ref = ec.resource.getLocationReference(location)

        // check if the file is JSON and act accordingly
        def fileExtension = null
        def recExt = Pattern.compile('(.+)\\.(\\w{3,4})$')
        def m = recExt.matcher(fileName)
        if (!m.matches()) { throw new IOException("Invalid input - unable to determine file name for storing file locally") }

        fileName = m.group(1)
        fileExtension = m.group(2)

        // support random file naming
        if (fileName == "*") {
            fileName = RandomStringUtils.randomAlphanumeric(10)
        } else if (fileName.endsWith("*")) {
            fileName = "${fileName.substring(0, fileName.length() - 2)}-${RandomStringUtils.randomAlphanumeric(10)}}"
        }

        def newFileName = "${fileName}.${fileExtension}"

        // add counter to the file-name
        if (!ignoreCounter) {
            newFileName = "${globalFileCounter.incrementAndGet().toString().padLeft(3, '0')}-${newFileName}"
        }

        // support storing both object and InputStream
        if (fileExtension == 'json') {
            if (isInputStream(data)) {
                ref.makeFile(newFileName).putStream((InputStream) data)
            } else {
                ref.makeFile(newFileName).putText(JsonOutput.prettyPrint(JsonOutput.toJson(data)))
            }
        }
        if (fileExtension != 'json') {
            // check for type, InputStream must be treated differently
            if (isInputStream(data)) {
                ref.makeFile(newFileName).putText((data as InputStream).getText('UTF-8'))
            } else {
                ref.makeFile(newFileName).putText(data as String)
            }
        }

        def created = ref.findChildFile(newFileName)
        return created.size
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

    /**
     * Expands values in a map by applying a transformation function
     * @param sourceMap Source map to process
     * @param valueProcessor Closure that processes each value
     * @return New map with processed values
     */
    public static LinkedHashMap expandMapValues(LinkedHashMap sourceMap, Closure valueProcessor) {
        def result = new LinkedHashMap()
        sourceMap.each { key, value ->
            if (value instanceof Map) {
                result[key] = expandMapValues(value as LinkedHashMap, valueProcessor)
            } else if (value instanceof List) {
                result[key] = value.collect { item ->
                    item instanceof Map ? expandMapValues(item as LinkedHashMap, valueProcessor) : valueProcessor(item)
                }
            } else {
                // only strings get expanded
                if (value.getClass() != String.class) {
                    result[key] = value
                    return
                }
                // is it set to be expanded
                if (!value.toString().contains('${')) {
                    result[key] = value
                    return
                }
                result[key] = valueProcessor(value)
            }
        }
        return result
    }

    /**
     * Parses a string in the format `2025-05-01T00:00Z[UTC]` into a ZonedDateTime.
     * The zone is extracted manually from the text inside the square brackets.
     *
     * @param input the date-time string to parse, in the format `yyyy-MM-dd'T'HH:mmX[ZoneId]`.
     * @return the parsed ZonedDateTime.
     * @throws IllegalArgumentException if the input format is invalid.
     */
    public static ZonedDateTime parseZonedDateTime(String input) {
        try {
            // Extract the zone enclosed in square brackets
            int zoneStart = input.indexOf('[');
            int zoneEnd = input.indexOf(']');

            // Validate the zone part
            if (zoneStart == -1 || zoneEnd == -1 || zoneEnd <= zoneStart + 1) {
                throw new IllegalArgumentException("Invalid format: ZoneId must be enclosed in square brackets");
            }

            // Extract ZoneId and clean up the input
            String zoneId = input.substring(zoneStart + 1, zoneEnd);
            String dateTimeWithoutZone = input.substring(0, zoneStart);

            // Handle UTC special case
            if ("UTC".equals(zoneId)) {
                zoneId = "Z"; // Equate UTC to the 'Z' ISO-8601 designator
            }

            // Parse the date-time without brackets using two steps
            ZonedDateTime zonedDateTime = ZonedDateTime.parse(dateTimeWithoutZone,
                    DateTimeFormatter.ISO_DATE_TIME.withZone(ZoneId.of(zoneId.equals("Z") ? "UTC" : zoneId)));

            return zonedDateTime;
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Failed to parse input string: " + input, e);
        }
    }

    /**
     * Simple conversion into String, of a ZonedDateTime input
     * @param input
     * @return
     */
    public static String formatZonedDatetime(ZonedDateTime input) {
        return input.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX"))
    }
}
