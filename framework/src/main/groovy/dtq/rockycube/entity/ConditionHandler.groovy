package dtq.rockycube.entity

import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityException
import org.moqui.impl.ViUtilities
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.moqui.impl.entity.condition.FieldValueCondition
import org.moqui.impl.entity.condition.ListCondition

import java.util.regex.Pattern

class ConditionHandler {
    // good old recursion once again
    // we need to process special string and return complex condition out of it
    public static EntityConditionImplBase recCondition(String ruleIn, ArrayList term)
    {
        // if it's simple component, a number, return baseCondition
        def recSingle = Pattern.compile("^(\\d)\$")
        def mSingle = recSingle.matcher(ruleIn)
        if (mSingle)
        {
            def groupNum = mSingle.group(1).toString().toInteger()
            HashMap<String, Object> fieldCond = term.get(groupNum - 1)
            return getSingleFieldCondition(fieldCond)
        }

        // use regex to search for both OPERATOR and CONTENT itself
        def rec = Pattern.compile("^(AND|OR)\\((.+)\\)")
        def m = rec.matcher(ruleIn)
        boolean isEntireListCond = m.matches()
        // another condition is not to be separable by comma
        boolean separable = ViUtilities.splitWithBracketsCheck(ruleIn, ",").size() > 1
        isEntireListCond &= !separable
        // 1. if string matches pattern from above, then it's the entire condition
        // 2. if not, than we may have a list in place
        if (!isEntireListCond)
        {
            // then, split them using comma and return one by one
            def items = ViUtilities.splitWithBracketsCheck(ruleIn, ",")

            List<EntityCondition> res = new ArrayList()
            items.each {it->
                res.add(recCondition(it, term))
            }
            return res as EntityConditionImplBase
        }

        def joinOp = EntityCondition.JoinOperator.AND
        switch (m.group(1))
        {
            case "OR":
                joinOp = EntityCondition.JoinOperator.OR
                break
        }
        def entireCond = new ListCondition(recCondition(m.group(2), term), joinOp)
        return entireCond
    }

    public static List<FieldValueCondition> getFieldsCondition(List<HashMap<String, Object>> term)
    {
        List<FieldValueCondition> res = new ArrayList<FieldValueCondition>()
        for (def t in term) {
            res.add(getSingleFieldCondition((HashMap) t))
        }
        return res
    }

    private static boolean isArray(Object val)
    {
        return val.getClass().isArray() || val.getClass().name == "java.util.ArrayList"
    }

    public static boolean evaluateCondition(FieldValueCondition cond, Object item){
        def val = item.getAt(cond.fieldName)
        def condVal = cond.value

        switch (cond.operator){
            case EntityCondition.ComparisonOperator.IS_NULL:
                return val == null
            case EntityCondition.ComparisonOperator.IS_NOT_NULL:
                return val != null
            case EntityCondition.ComparisonOperator.EQUALS:
                return val == condVal
            case EntityCondition.ComparisonOperator.NOT_EQUAL:
                return val != condVal
            case EntityCondition.ComparisonOperator.GREATER_THAN:
//                if (!val.toString().isNumber() || !condVal.toString().isNumber()) throw new EntityException("Both artifacts must be numeric when using 'GT' operator")
                return val > condVal
            case EntityCondition.ComparisonOperator.GREATER_THAN_EQUAL_TO:
//                if (!val.toString().isNumber() || !condVal.toString().isNumber()) throw new EntityException("Both artifacts must be numeric when using 'GTE' operator")
                return val >= condVal
            case EntityCondition.ComparisonOperator.LESS_THAN:
//                if (!val.toString().isNumber() || !condVal.toString().isNumber()) throw new EntityException("Both artifacts must be numeric when using 'LT' operator")
                return val < condVal
            case EntityCondition.ComparisonOperator.LESS_THAN_EQUAL_TO:
//                if (!val.toString().isNumber() || !condVal.toString().isNumber()) throw new EntityException("Both artifacts must be numeric when using 'LTE' operator")
                return val <= condVal
            case EntityCondition.ComparisonOperator.BETWEEN:
                if (!val) return false
                if (!isArray(condVal)) throw new EntityException("Comparison value is not of type Array when using 'BETWEEN' operator")
                ArrayList condArr = (ArrayList) condVal
                if (condArr.size() != 2) throw new EntityException("Two comparison values required for 'BETWEEN' operator check")
                if (condArr[0] > condArr[1]) throw new EntityException("Comparison values for 'BETWEEN' check need to be in ascending order")
                return condArr[0] <= val && val <= condArr[1]
            case EntityCondition.ComparisonOperator.NOT_BETWEEN:
                if (!val) return false
                if (!isArray(condVal)) throw new EntityException("Comparison value is not of type Array when using 'NOT-BETWEEN' operator")
                ArrayList condArr = (ArrayList) condVal
                if (condArr.size() != 2) throw new EntityException("Two comparison values required for 'NOT-BETWEEN' operator check")
                if (condArr[0] > condArr[1]) throw new EntityException("Comparison values for 'NOT-BETWEEN' check need to be in ascending order")
                return condArr[0] > val && val > condArr[1]
            case EntityCondition.ComparisonOperator.IN:
                if (!val) return false
                if (!isArray(condVal)) throw new EntityException("Comparison value is not of type Array when using 'IN' operator")
                ArrayList condArr = (ArrayList) condVal
                return condArr.contains(val)
            case EntityCondition.ComparisonOperator.NOT_IN:
                if (!val) return false
                if (!isArray(condVal)) throw new EntityException("Comparison value is not of type Array when using 'NOT_IN' operator")
                ArrayList condArr = (ArrayList) condVal
                return !condArr.contains(val)
            case EntityCondition.ComparisonOperator.LIKE:
                // if null, return false
                if (!val) return false
                def rec = cond.value
                def recLike = Pattern.compile(rec as String)
                return recLike.matcher(val).matches()
            case EntityCondition.ComparisonOperator.NOT_LIKE:
                // if null, return false
                if (!val) return false
                def rec = cond.value
                def recLike = Pattern.compile(rec as String)
                return !recLike.matcher(val).matches()
            default:
                throw new EntityException("Operator [${cond.operator}] not supported when evaluating condition")
        }
    }

    public static ExtendedFieldValueCondition getSingleFieldCondition(HashMap singleTerm)
    {
        // check required fields
        if (!singleTerm.containsKey("field")) throw new EntityException("Field condition not set correctly, 'field' missing [${singleTerm}]")
        if (!singleTerm.containsKey("value")){
            // allow for specific cases - NULL-related
            if (!singleTerm["operator"].toString().toLowerCase().contains("null"))
            {
                throw new EntityException("Field condition not set correctly, 'value' missing [${singleTerm}]")
            }
        }

        def compOperator = EntityCondition.ComparisonOperator.EQUALS

        // logger.debug("singleTerm.value.getClass() = ${singleTerm.value.getClass().simpleName}")
        if (singleTerm.containsKey("operator"))
        {
            switch(singleTerm["operator"].toString().toLowerCase())
            {
                case "is-null":
                case "isnull":
                case "null":
                    compOperator = EntityCondition.ComparisonOperator.IS_NULL
                    break
                case "is-not-null":
                case "notnull":
                case "not-null":
                    compOperator = EntityCondition.ComparisonOperator.IS_NOT_NULL
                    break
                case "=":
                case "eq":
                case "equal":
                case "equals":
                    // do nothing
                    break
                case "!=":
                case "not-equal":
                case "not-equals":
                    compOperator = EntityCondition.ComparisonOperator.NOT_EQUAL
                    break
                case ">":
                case "gt":
                    compOperator = EntityCondition.ComparisonOperator.GREATER_THAN
                    break
                case ">=":
                case "gte":
                    compOperator = EntityCondition.ComparisonOperator.GREATER_THAN_EQUAL_TO
                    break
                case "<":
                case "lt":
                    compOperator = EntityCondition.ComparisonOperator.LESS_THAN
                    break
                case "<=":
                case "lte":
                    compOperator = EntityCondition.ComparisonOperator.LESS_THAN_EQUAL_TO
                    break
                case "like":
                    compOperator = EntityCondition.ComparisonOperator.LIKE
                    // do not need to modify the value for regex, the ObjectUtilities will take care of it
                    //singleTerm.value = ViUtilities.fixLikeCondition((String) singleTerm.value)
                    break
                case "not-like":
                case "not_like":
                    compOperator = EntityCondition.ComparisonOperator.NOT_LIKE
                    // do not need to modify the value for regex, the ObjectUtilities will take care of it
                    //singleTerm.value = ViUtilities.fixLikeCondition((String) singleTerm.value)
                    break
                case "in":
                    compOperator = EntityCondition.ComparisonOperator.IN
                    if (singleTerm.value.getClass().simpleName != "ArrayList") throw new EntityException("Operator requires List value, but was not provided")
                    break
                case "not-in":
                case "not_in":
                    compOperator = EntityCondition.ComparisonOperator.NOT_IN
                    if (singleTerm.value.getClass().simpleName != "ArrayList") throw new EntityException("Operator requires List value, but was not provided")
                    break
                case "between":
                    compOperator = EntityCondition.ComparisonOperator.BETWEEN
                    if (singleTerm.value.getClass().simpleName != "ArrayList") throw new EntityException("Operator requires List value, but was not provided")
                    if (singleTerm.value.size() != 2) throw new EntityException("Operator requires exactly two values in array")
                    break
                case "not-between":
                case "not_between":
                    compOperator = EntityCondition.ComparisonOperator.NOT_BETWEEN
                    if (singleTerm.value.getClass().simpleName != "ArrayList") throw new EntityException("Operator requires List value, but was not provided")
                    if (singleTerm.value.size() != 2) throw new EntityException("Operator requires exactly two values in array")
                    break
                case "text":
                    compOperator = EntityCondition.ComparisonOperator.TEXT
                    break
            }
        }
        // using new fieldValueCondition
        def newCond = new ExtendedFieldValueCondition(
                new ConditionField((String) singleTerm.field),
                compOperator,
                (Object) singleTerm.value
        )
        // add new feature to condition, nested field
        if (singleTerm.containsKey('nested')) newCond.setNestedFields(singleTerm.nested as String)
        return newCond
    }
}
