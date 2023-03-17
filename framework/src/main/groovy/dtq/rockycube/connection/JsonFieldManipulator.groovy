package dtq.rockycube.connection

import dtq.rockycube.GenericUtilities
import org.moqui.entity.EntityCondition.ComparisonOperator
import org.moqui.entity.EntityException
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.FieldInfo
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class JsonFieldManipulator {
    private HashMap<String, MNode> dbConfigs = new HashMap<>()
    protected final static Logger logger = LoggerFactory.getLogger(this.class);
    private EntityFacadeImpl entityFacadeImpl

    JsonFieldManipulator(EntityFacadeImpl efi, MNode entityFacadeNode, Closure findDatabaseNode)
    {
        this.entityFacadeImpl = efi

        ArrayList<MNode> dsList = entityFacadeNode.children("datasource")
        for (MNode d in dsList)
        {
            def groupName = d.attribute("group-name")
            def dbConfig = (MNode) findDatabaseNode(groupName)
            dbConfigs.put(groupName, dbConfig)
        }
    }

    JsonFieldManipulator(EntityFacadeImpl efi, ArrayList<String> configurations, Closure findDatabaseNode)
    {
        this.entityFacadeImpl = efi
        configurations.each {it->
            dbConfigs.put(it, (MNode) findDatabaseNode(it))
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

    public formatNestedCondition(EntityDefinition ed, ConditionField field, ArrayList nestedFields, ComparisonOperator operator)
    {
        // it a JSON field at all?
        def tp = ed.getFieldInfo(field.fieldName).type.toLowerCase()
        if (tp != "jsonb")
        {
            logger.error("Cannot perform nested condition calculation on field [${field.fieldName}]")
            throw new EntityException("Cannot perform nested condition calculation on field [${field.fieldName}]")
        }

        def syntax = this.getNestedFieldSyntax(ed.groupName).attribute("syntax")
        if (!syntax)
        {
            logger.error("Nested fields syntax not defined [${ed.groupName}]")
            throw new EntityException("Nested fields syntax not defined [${ed.groupName}]")
        }

        if (nestedFields.size() > 1)
        {
            logger.error("Nested fields with depth greater than 1 are not supported when creating where condition")
            throw new EntityException("Nested fields with depth greater than 1 not supported")
        }

        // operator calculation
        def operatorString = this.entityFacadeImpl.conditionFactoryImpl.comparisonOperatorStringMap.get(operator)

        // expect something in the realm of `<nested-field syntax="{{column}}->>'{{nestedField}}'{{operator}}?"/>`
        return syntax
                .replace("{{column}}", field.getColumnName(ed))
                .replace("{{nestedField}}", (String) nestedFields[0])
                .replace("{{operator}}", operatorString)
    }

    /**
     * Method that searches for database setup
     * @param group
     * @return
     */
    private MNode searchForDatabaseSetup(String group)
    {
        // use default setup - transactional
        def groupName = group

        if (!dbConfigs.containsKey(group))
        {
            // return original, if no transactional
            if (dbConfigs.containsKey("transactional")) groupName = "transactional"
        }

        if (!dbConfigs.containsKey(groupName)) return null
        return dbConfigs.get(groupName)
    }

    /**
     * Search for evaluation operator, calculate only for JSON-like fields
     * @param ed
     * @param group
     * @param defaultIfNotFound
     * @return
     */
    public String findComparisonOperator(ComparisonOperator operator, FieldInfo fi, String group, String defaultIfNotFound)
    {
        // short-circuit the result in case it's not JSON-like field
        if (!fieldIsJson(fi)) return defaultIfNotFound

        // the specification
        ArrayList<MNode> operatorSpecs = searchForOperatorsConfig(group)
        if (!operatorSpecs) return defaultIfNotFound

        def operatorConf = operatorSpecs.find({it->
            return it.attribute("operator-type").toLowerCase() == operator.toString().toLowerCase()
        })
        if (!operatorConf) return defaultIfNotFound
        def res = operatorConf.attribute("value-to-use")
        return res ?: defaultIfNotFound
    }

    private ArrayList<MNode> searchForOperatorsConfig(String group)
    {
        MNode conf = searchForDatabaseSetup(group)
        if (!conf) return null

        // do we have json-config?
        def foundJsConf = conf.children("json-config")
        if (foundJsConf.empty) return null

        def operatorSpecs = foundJsConf.first().children("json-operator")
        if (operatorSpecs.empty) return null

        return operatorSpecs
    }

    private MNode searchForOperationConfig(String group, String operation)
    {
        MNode conf = searchForDatabaseSetup(group)
        if (!conf) return null

        // do we have json-config?
        def foundJsConf = conf.children("json-config")
        if (foundJsConf.empty) return null

        def specName = "${operation.toLowerCase()}-specs".toString()
        def specs = foundJsConf.first().children(specName)
        if (specs.empty) return null

        return specs[0]
    }

    private boolean fieldIsJson(FieldInfo fi)
    {
        return fi.type.toLowerCase().contains("json")
    }

    public boolean getHasNestedFieldSetup(String group)
    {
        return this.getNestedFieldSyntax(group) != null
    }

    public MNode getNestedFieldSyntax(String group)
    {
        MNode conf = searchForDatabaseSetup(group)
        if (!conf) return null

        // do we have json-config?
        def foundJsConf = conf.children("json-config")
        if (foundJsConf.empty) return false

        def nestedFieldSetup = foundJsConf.first().children("nested-field")
        if (nestedFieldSetup.empty) return null
        return nestedFieldSetup[0]
    }

    public String fieldCondition(FieldInfo fi, String group, String operation, String defaultNotSet=null)
    {
        if (!fieldIsJson(fi)) return defaultNotSet
        return fieldCondition(group, operation, defaultNotSet)
    }

    /**
     * Search in XML configuration to find the correct formula for specific DB
     * @param group
     * @param operation
     * @param defaultNotSet
     * @return
     */
    public String fieldCondition(String group, String operation, String defaultNotSet=null)
    {
        // the specification
        MNode spec = searchForOperationConfig(group, operation)
        if (!spec) return defaultNotSet

        String toJsonMethod = spec.attribute("to-json-method");
        String formatJson = spec.attribute("format-json-value");
        if (!toJsonMethod && !formatJson) return defaultNotSet
        if (!toJsonMethod) return formatJson

        // UPDATE+CREATE:
        // for PG   `to_json(?::json)
        // for H2   `(? FORMAT JSON)`

        def startingBracket = toJsonMethod.substring(toJsonMethod.length() - 1)
        def endingBracket = ""

        switch (startingBracket)
        {
            case "(":
                endingBracket = ")"
                break
            case "[":
                endingBracket = "]"
                break
            case "{":
                endingBracket = "}"
                break
            case "'":
                endingBracket = "'"
                break
        }

        // remove bracket from the `toJsonMethod`
        if (endingBracket != '') toJsonMethod = toJsonMethod.substring(0, toJsonMethod.length() - 1)

        return "${toJsonMethod}${startingBracket}${formatJson}${endingBracket}".toString()
    }

    public String fieldCondition(String group)
    {
        def fc = fieldCondition(group, "create")
        return fc ? fc : "?"
    }

    // UNUSED METHOD
    //    private String createConditionField(String group)
    //    {
    //        // default response
    //        String defResponse = "?"
    //        MNode conf = searchForDatabaseSetup(group)
    //        if (!conf) return defResponse
    //
    //        try {
    //                String createToJsonMethod = conf.attribute("create-to-json-method");
    //                String createFormatJson = conf.attribute("create-format-json-value");
    //                if (!createToJsonMethod || !createFormatJson) return defResponse
    //                // for PG   `to_json(?::json)
    //                // for H2   `(? FORMAT JSON)`
    //                return createToJsonMethod + "(" + createFormatJson + ")"
    //        } catch (Exception exc) {
    //            return defResponse
    //        }
    //    }
}
