package dtq.rockycube.connection

import com.google.gson.Gson
import dtq.rockycube.GenericUtilities
import org.moqui.util.MNode

class JsonFieldManipulator {
    private HashMap<String, MNode> dbConfigs = new HashMap<>()
    private Gson gson

    JsonFieldManipulator(MNode entityFacadeNode, Closure findDatabaseNode)
    {
        // initialize
        this.gson = new Gson()

        ArrayList<MNode> dsList = entityFacadeNode.children("datasource")
        for (MNode d in dsList)
        {
            def groupName = d.attribute("group-name")
            def dbConfig = (MNode) findDatabaseNode(groupName)
            dbConfigs.put(groupName, dbConfig)
        }
    }

    public Object entityValue(int typeValue, Object value)
    {
        Object res = null
        switch (typeValue)
        {
            case 15:
            case 16:
                res = GenericUtilities.processField(value)
                break
            default:
                res = value
        }
        return res
    }

    public String createConditionField(String group)
    {
        // default response
        String defResponse = "?"
        if (!dbConfigs.containsKey(group)) return defResponse

        try {
                MNode conf = dbConfigs.get(group)
                String createToJsonMethod = conf.attribute("create-to-json-method");
                String createFormatJson = conf.attribute("create-format-json-value");
                if (!createToJsonMethod || !createFormatJson) return defResponse
                // for PG   `to_json(?::json)
                // for H2   `(? FORMAT JSON)`
                return createToJsonMethod + "(" + createFormatJson + ")"
        } catch (Exception exc) {
            return defResponse
        }
    }
}
