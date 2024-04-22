package dtq.rockycube

import com.google.gson.Gson
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
            ResourceReference ref = ec.resource.getLocationReference("tmp")

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

            if (fileExtension == 'json') ref.makeFile(fileName).putText (JsonOutput.prettyPrint(JsonOutput.toJson(data)))
            if (fileExtension != 'json') ref.makeFile(fileName).putText (data as String)
        } catch (Exception exc) {
            ec.logger.error("Cannot debug-store file: ${exc}")
        }
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
        } else if (obj.getClass() == LinkedHashMap.class){
            return (obj as LinkedHashMap).keySet().size()
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
}
