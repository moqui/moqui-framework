package dtq.rockycube

import com.google.gson.Gson

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
            default:
                return convertToComplexType(field.toString())
        }
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
