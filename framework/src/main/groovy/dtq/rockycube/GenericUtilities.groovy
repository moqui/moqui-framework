package dtq.rockycube

import com.google.gson.Gson

import java.nio.charset.StandardCharsets

class GenericUtilities {
    protected static Gson gson = new Gson()

    public static Object processField(Object field)
    {
        def res
        def fieldClass = field.getClass().simpleName.toLowerCase()
        switch (fieldClass)
        {
            case "byte[]":
                String itStrVal = new String((byte[]) field, StandardCharsets.UTF_8)
                def firstChar = itStrVal.substring(0,1)
                if (firstChar == "[")
                {
                    res = gson.fromJson(itStrVal, ArrayList.class)
                } else {
                    res = gson.fromJson(itStrVal, HashMap.class)
                }
                break
            default:
                res = gson.fromJson(field.toString(), HashMap.class)
        }
        return res
    }
}
