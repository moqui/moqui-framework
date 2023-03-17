package dtq.rockycube

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import groovy.json.JsonOutput
import org.moqui.context.ExecutionContext
import org.moqui.resource.ResourceReference

import java.nio.charset.StandardCharsets

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
            default:
                return convertToComplexType(field.toString())
        }
    }

    /*
     * Store file in a `tmp` directory of the backend, for debugging purposes
     */
    public static void debugFile(ExecutionContext ec, String fileId, Object data)
    {
        ResourceReference ref = ec.resource.getLocationReference("tmp")
        ref.makeFile(fileId).putText (JsonOutput.prettyPrint(JsonOutput.toJson(data)))
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
}
