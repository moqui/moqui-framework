package dtq.rockycube

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import groovy.json.JsonOutput
import org.apache.groovy.json.internal.LazyMap
import org.moqui.context.ExecutionContext
import org.moqui.resource.ResourceReference

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class GenericUtilities {
    protected static Gson gson = new Gson()

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
                return field
            case "arraylist":
                return field
            case "nullobject":
                return null
            default:
                return convertToComplexType(field.toString())
        }
    }

    /*
     * Store file in a `tmp` directory of the backend, for debugging purposes
     */
    public static void debugFile(ExecutionContext ec, String fileId, Object data, Long internalCounter=0)
    {
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

        if (fileExtension == 'json') ref.makeFile(fileName).putText (JsonOutput.prettyPrint(JsonOutput.toJson(data)))
        if (fileExtension != 'json') ref.makeFile(fileName).putText (data as String)
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
            case HashMap.class:
                return (obj as HashMap).isEmpty()
            default:
                return obj.toString().size() == 0
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
