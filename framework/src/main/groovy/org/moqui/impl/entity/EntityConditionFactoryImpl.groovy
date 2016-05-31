/*
 * This software is in the public domain under CC0 1.0 Universal plus a 
 * Grant of Patent License.
 * 
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.impl.entity

import groovy.transform.CompileStatic
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityCondition.ComparisonOperator
import org.moqui.entity.EntityCondition.JoinOperator
import org.moqui.entity.EntityConditionFactory
import org.moqui.entity.EntityException
import org.moqui.impl.StupidJavaUtilities
import org.moqui.impl.StupidJavaUtilities.KeyValue
import org.moqui.impl.StupidUtilities
import org.moqui.impl.entity.condition.*
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

@CompileStatic
class EntityConditionFactoryImpl implements EntityConditionFactory {
    protected final static Logger logger = LoggerFactory.getLogger(EntityConditionFactoryImpl.class)

    protected final EntityFacadeImpl efi
    protected final TrueCondition trueCondition

    EntityConditionFactoryImpl(EntityFacadeImpl efi) {
        this.efi = efi
        trueCondition = new TrueCondition()
    }

    EntityFacadeImpl getEfi() { return efi }

    @Override
    EntityCondition getTrueCondition() { return trueCondition }

    @Override
    EntityCondition makeCondition(EntityCondition lhs, JoinOperator operator, EntityCondition rhs) {
        return makeConditionImpl((EntityConditionImplBase) lhs, operator, (EntityConditionImplBase) rhs)
    }
    EntityConditionImplBase makeConditionImpl(EntityConditionImplBase lhs, JoinOperator operator, EntityConditionImplBase rhs) {
        if (lhs != null) {
            if (rhs != null) {
                // we have both lhs and rhs
                if (lhs instanceof ListCondition) {
                    ListCondition lhsLc = (ListCondition) lhs
                    if (lhsLc.getOperator() == operator) {
                        if (rhs instanceof ListCondition) {
                            ListCondition rhsLc = (ListCondition) rhs
                            if (rhsLc.getOperator() == operator) {
                                lhsLc.addConditions(rhsLc)
                                return lhsLc
                            } else {
                                lhsLc.addCondition(rhsLc)
                                return lhsLc
                            }
                        } else {
                            lhsLc.addCondition((EntityConditionImplBase) rhs)
                            return lhsLc
                        }
                    }
                }
                // no special handling, create a BasicJoinCondition
                return new BasicJoinCondition((EntityConditionImplBase) lhs, operator, (EntityConditionImplBase) rhs)
            } else {
                return lhs
            }
        } else {
            if (rhs != null) {
                return rhs
            } else {
                return null
            }
        }
    }

    @Override
    EntityCondition makeCondition(String fieldName, ComparisonOperator operator, Object value) {
        return new FieldValueCondition(new ConditionField(fieldName), operator, value)
    }

    @Override
    EntityCondition makeConditionToField(String fieldName, ComparisonOperator operator, String toFieldName) {
        return new FieldToFieldCondition(new ConditionField(fieldName), operator, new ConditionField(toFieldName))
    }

    @Override
    EntityCondition makeCondition(List<EntityCondition> conditionList) {
        return this.makeCondition(conditionList, JoinOperator.AND)
    }
    @Override
    EntityCondition makeCondition(List<EntityCondition> conditionList, JoinOperator operator) {
        if (conditionList == null || conditionList.size() == 0) return null
        ArrayList<EntityConditionImplBase> newList = new ArrayList()

        if (conditionList instanceof RandomAccess) {
            // avoid creating an iterator if possible
            int listSize = conditionList.size()
            for (int i = 0; i < listSize; i++) {
                EntityCondition curCond = conditionList.get(i)
                if (curCond == null) continue
                // this is all they could be, all that is supported right now
                if (curCond instanceof EntityConditionImplBase) newList.add((EntityConditionImplBase) curCond)
                else throw new IllegalArgumentException("EntityCondition of type [${curCond.getClass().getName()}] not supported")
            }
        } else {
            Iterator<EntityCondition> conditionIter = conditionList.iterator()
            while (conditionIter.hasNext()) {
                EntityCondition curCond = conditionIter.next()
                if (curCond == null) continue
                // this is all they could be, all that is supported right now
                if (curCond instanceof EntityConditionImplBase) newList.add((EntityConditionImplBase) curCond)
                else throw new IllegalArgumentException("EntityCondition of type [${curCond.getClass().getName()}] not supported")
            }
        }
        if (newList == null || newList.size() == 0) return null
        if (newList.size() == 1) {
            return newList.get(0)
        } else {
            return new ListCondition(newList, operator)
        }
    }

    @Override
    EntityCondition makeCondition(List<Object> conditionList, String listOperator, String mapComparisonOperator, String mapJoinOperator) {
        if (conditionList == null || conditionList.size() == 0) return null

        JoinOperator listJoin = listOperator ? getJoinOperator(listOperator) : JoinOperator.AND
        ComparisonOperator mapComparison = mapComparisonOperator ? getComparisonOperator(mapComparisonOperator) : ComparisonOperator.EQUALS
        JoinOperator mapJoin = mapJoinOperator ? getJoinOperator(mapJoinOperator) : JoinOperator.AND

        List<EntityConditionImplBase> newList = new ArrayList<EntityConditionImplBase>()
        Iterator<Object> conditionIter = conditionList.iterator()
        while (conditionIter.hasNext()) {
            Object curObj = conditionIter.next()
            if (curObj == null) continue
            if (curObj instanceof Map) {
                Map curMap = (Map) curObj
                if (curMap.size() == 0) continue
                EntityCondition curCond = makeCondition(curMap, mapComparison, mapJoin)
                newList.add((EntityConditionImplBase) curCond)
                continue
            }
            if (curObj instanceof EntityConditionImplBase) {
                EntityCondition curCond = (EntityConditionImplBase) curObj
                newList.add(curCond)
                continue
            }
            throw new IllegalArgumentException("The conditionList parameter must contain only Map and EntityCondition objects, found entry of type [${curObj.getClass().getName()}]")
        }
        if (newList.size() == 0) return null
        if (newList.size() == 1) {
            return newList.get(0)
        } else {
            return new ListCondition(newList, listJoin)
        }
    }

    @Override
    EntityCondition makeCondition(Map<String, Object> fieldMap, ComparisonOperator comparisonOperator, JoinOperator joinOperator) {
        return makeCondition(fieldMap, comparisonOperator, joinOperator, null, null, false)
    }
    EntityConditionImplBase makeCondition(Map<String, Object> fieldMap, ComparisonOperator comparisonOperator,
            JoinOperator joinOperator, EntityDefinition findEd, Map<String, ArrayList<MNode>> memberFieldAliases, boolean excludeNulls) {
        if (fieldMap == null || fieldMap.size() == 0) return (EntityConditionImplBase) null

        JoinOperator joinOp = joinOperator != null ? joinOperator : JoinOperator.AND
        ComparisonOperator compOp = comparisonOperator != null ? comparisonOperator : ComparisonOperator.EQUALS
        ArrayList<EntityConditionImplBase> condList = new ArrayList<EntityConditionImplBase>()
        ArrayList<KeyValue> fieldList = new ArrayList<KeyValue>()

        for (Map.Entry<String, Object> entry in fieldMap.entrySet()) {
            String key = entry.getKey()
            Object value = entry.getValue()
            if (key.startsWith("_")) {
                if (key == "_comp") {
                    compOp = getComparisonOperator((String) value)
                    continue
                } else if (key == "_join") {
                    joinOp = getJoinOperator((String) value)
                    continue
                } else if (key == "_list") {
                    // if there is an _list treat each as a condition Map, ie call back into this method
                    if (value instanceof List) {
                        List valueList = (List) value
                        for (Object listEntry in valueList) {
                            if (listEntry instanceof Map) {
                                EntityConditionImplBase entryCond = makeCondition((Map) listEntry, ComparisonOperator.EQUALS,
                                        JoinOperator.AND, findEd, memberFieldAliases, excludeNulls)
                                if (entryCond != null) condList.add(entryCond)
                            } else {
                                throw new EntityException("Entry in _list is not a Map: ${listEntry}")
                            }
                        }
                    } else {
                        throw new EntityException("Value for _list entry is not a List: ${value}")
                    }
                    continue
                }
            }

            if (excludeNulls && value == null) {
                if (logger.isTraceEnabled()) logger.trace("Tried to filter find on entity [${findEd.fullEntityName}] on field ${key} but value was null, not adding condition")
                continue
            }

            // add field key/value to a list to iterate over later for conditions once we have _comp for sure
            fieldList.add(new KeyValue(key, value))
        }

        // has fields? make conditions for them
        if (fieldList.size() > 0) {
            int fieldListSize = fieldList.size()
            for (int i = 0; i < fieldListSize; i++) {
                KeyValue fieldValue = (KeyValue) fieldList.get(i)
                String fieldName = fieldValue.key
                Object value = fieldValue.value

                if (memberFieldAliases != null && memberFieldAliases.size() > 0) {
                    // we have a view entity, more complex
                    ArrayList<MNode> aliases = (ArrayList<MNode>) memberFieldAliases.get(fieldName)
                    if (aliases == null || aliases.size() == 0)
                        throw new EntityException("Tried to filter on field ${fieldName} which is not included in view-entity ${findEd.fullEntityName}")

                    for (int k = 0; k < aliases.size(); k++) {
                        MNode aliasNode = (MNode) aliases.get(k)
                        // could be same as field name, but not if aliased with different name
                        String aliasName = aliasNode.attribute("name")
                        condList.add(new FieldValueCondition(new ConditionField(aliasName), compOp, value))
                    }
                } else {
                    condList.add(new FieldValueCondition(new ConditionField(fieldName), compOp, value))
                }

            }
        }

        if (condList.size() == 0) return (EntityConditionImplBase) null

        if (condList.size() == 1) {
            return (EntityConditionImplBase) condList.get(0)
        } else {
            return new ListCondition(condList, joinOp)
        }
    }
    @Override
    EntityCondition makeCondition(Map<String, Object> fieldMap) {
        return makeCondition(fieldMap, ComparisonOperator.EQUALS, JoinOperator.AND, null, null, false)
    }

    @Override
    EntityCondition makeConditionDate(String fromFieldName, String thruFieldName, Timestamp compareStamp) {
        return new DateCondition(fromFieldName, thruFieldName,
                compareStamp ?: efi.getEcfi().getExecutionContext().getUser().getNowTimestamp())
    }
    EntityCondition makeConditionDate(String fromFieldName, String thruFieldName, Timestamp compareStamp, boolean ignoreIfEmpty) {
        if (ignoreIfEmpty && (Object) compareStamp == null) return null
        return new DateCondition(fromFieldName, thruFieldName,
                compareStamp ?: efi.getEcfi().getExecutionContext().getUser().getNowTimestamp())
    }

    @Override
    EntityCondition makeConditionWhere(String sqlWhereClause) {
        if (!sqlWhereClause) return null
        return new WhereCondition(sqlWhereClause)
    }

    ComparisonOperator comparisonOperatorFromEnumId(String enumId) {
        switch (enumId) {
            case "ENTCO_LESS": return EntityCondition.LESS_THAN
            case "ENTCO_GREATER": return EntityCondition.GREATER_THAN
            case "ENTCO_LESS_EQ": return EntityCondition.LESS_THAN_EQUAL_TO
            case "ENTCO_GREATER_EQ": return EntityCondition.GREATER_THAN_EQUAL_TO
            case "ENTCO_EQUALS": return EntityCondition.EQUALS
            case "ENTCO_NOT_EQUALS": return EntityCondition.NOT_EQUAL
            case "ENTCO_IN": return EntityCondition.IN
            case "ENTCO_NOT_IN": return EntityCondition.NOT_IN
            case "ENTCO_BETWEEN": return EntityCondition.BETWEEN
            case "ENTCO_NOT_BETWEEN": return EntityCondition.NOT_BETWEEN
            case "ENTCO_LIKE": return EntityCondition.LIKE
            case "ENTCO_NOT_LIKE": return EntityCondition.NOT_LIKE
            case "ENTCO_IS_NULL": return EntityCondition.IS_NULL
            case "ENTCO_IS_NOT_NULL": return EntityCondition.IS_NOT_NULL
            default: return null
        }
    }

    EntityCondition makeActionCondition(String fieldName, String operator, String fromExpr, String value, String toFieldName, boolean ignoreCase, boolean ignoreIfEmpty, boolean orNull, String ignore) {
        Object from = fromExpr ? this.efi.ecfi.resourceFacade.expression(fromExpr, "") : null
        return makeActionConditionDirect(fieldName, operator, from, value, toFieldName, ignoreCase, ignoreIfEmpty, orNull, ignore)
    }
    EntityCondition makeActionConditionDirect(String fieldName, String operator, Object fromObj, String value, String toFieldName, boolean ignoreCase, boolean ignoreIfEmpty, boolean orNull, String ignore) {
        // logger.info("TOREMOVE makeActionCondition(fieldName ${fieldName}, operator ${operator}, fromExpr ${fromExpr}, value ${value}, toFieldName ${toFieldName}, ignoreCase ${ignoreCase}, ignoreIfEmpty ${ignoreIfEmpty}, orNull ${orNull}, ignore ${ignore})")

        if (efi.getEcfi().getResourceFacade().condition(ignore, null)) return null

        if (toFieldName) {
            EntityCondition ec = makeConditionToField(fieldName, getComparisonOperator(operator), toFieldName)
            if (ignoreCase) ec.ignoreCase()
            return ec
        } else {
            Object condValue
            if (value) {
                // NOTE: have to convert value (if needed) later on because we don't know which entity/field this is for, or change to pass in entity?
                condValue = value
            } else {
                condValue = fromObj
            }
            if (ignoreIfEmpty && StupidJavaUtilities.isEmpty(condValue)) return null

            EntityCondition mainEc = makeCondition(fieldName, getComparisonOperator(operator), condValue)
            if (ignoreCase) mainEc.ignoreCase()

            EntityCondition ec = mainEc
            if (orNull) ec = makeCondition(mainEc, JoinOperator.OR, makeCondition(fieldName, ComparisonOperator.EQUALS, null))
            return ec
        }
    }

    EntityCondition makeActionCondition(MNode node) {
        Map<String, String> attrs = node.attributes
        return makeActionCondition(attrs.get("field-name"),
                attrs.get("operator") ?: "equals", (attrs.get("from") ?: attrs.get("field-name")),
                attrs.get("value"), attrs.get("to-field-name"), (attrs.get("ignore-case") ?: "false") == "true",
                (attrs.get("ignore-if-empty") ?: "false") == "true", (attrs.get("or-null") ?: "false") == "true",
                (attrs.get("ignore") ?: "false"))
    }

    EntityCondition makeActionConditions(MNode node) {
        List<EntityCondition> condList = new ArrayList()
        for (MNode subCond in node.children) condList.add(makeActionCondition(subCond))
        return makeCondition(condList, getJoinOperator(node.attribute("combine")))
    }

    protected static final Map<ComparisonOperator, String> comparisonOperatorStringMap = new HashMap()
    static {
        comparisonOperatorStringMap.put(ComparisonOperator.EQUALS, "=")
        comparisonOperatorStringMap.put(ComparisonOperator.NOT_EQUAL, "<>")
        comparisonOperatorStringMap.put(ComparisonOperator.LESS_THAN, "<")
        comparisonOperatorStringMap.put(ComparisonOperator.GREATER_THAN, ">")
        comparisonOperatorStringMap.put(ComparisonOperator.LESS_THAN_EQUAL_TO, "<=")
        comparisonOperatorStringMap.put(ComparisonOperator.GREATER_THAN_EQUAL_TO, ">=")
        comparisonOperatorStringMap.put(ComparisonOperator.IN, "IN")
        comparisonOperatorStringMap.put(ComparisonOperator.NOT_IN, "NOT IN")
        comparisonOperatorStringMap.put(ComparisonOperator.BETWEEN, "BETWEEN")
        comparisonOperatorStringMap.put(ComparisonOperator.NOT_BETWEEN, "NOT BETWEEN")
        comparisonOperatorStringMap.put(ComparisonOperator.LIKE, "LIKE")
        comparisonOperatorStringMap.put(ComparisonOperator.NOT_LIKE, "NOT LIKE")
        comparisonOperatorStringMap.put(ComparisonOperator.IS_NULL, "IS NULL")
        comparisonOperatorStringMap.put(ComparisonOperator.IS_NOT_NULL, "IS NOT NULL")
    }
    protected static final Map<String, ComparisonOperator> stringComparisonOperatorMap = [
            "=":ComparisonOperator.EQUALS,
            "equals":ComparisonOperator.EQUALS,

            "not-equals":ComparisonOperator.NOT_EQUAL,
            "not-equal":ComparisonOperator.NOT_EQUAL,
            "!=":ComparisonOperator.NOT_EQUAL,
            "<>":ComparisonOperator.NOT_EQUAL,

            "less-than":ComparisonOperator.LESS_THAN,
            "less":ComparisonOperator.LESS_THAN,
            "<":ComparisonOperator.LESS_THAN,

            "greater-than":ComparisonOperator.GREATER_THAN,
            "greater":ComparisonOperator.GREATER_THAN,
            ">":ComparisonOperator.GREATER_THAN,

            "less-than-equal-to":ComparisonOperator.LESS_THAN_EQUAL_TO,
            "less-equals":ComparisonOperator.LESS_THAN_EQUAL_TO,
            "<=":ComparisonOperator.LESS_THAN_EQUAL_TO,

            "greater-than-equal-to":ComparisonOperator.GREATER_THAN_EQUAL_TO,
            "greater-equals":ComparisonOperator.GREATER_THAN_EQUAL_TO,
            ">=":ComparisonOperator.GREATER_THAN_EQUAL_TO,

            "in":ComparisonOperator.IN,
            "IN":ComparisonOperator.IN,

            "not-in":ComparisonOperator.NOT_IN,
            "NOT IN":ComparisonOperator.NOT_IN,

            "between":ComparisonOperator.BETWEEN,
            "BETWEEN":ComparisonOperator.BETWEEN,

            "not-between":ComparisonOperator.NOT_BETWEEN,
            "NOT BETWEEN":ComparisonOperator.NOT_BETWEEN,

            "like":ComparisonOperator.LIKE,
            "LIKE":ComparisonOperator.LIKE,

            "not-like":ComparisonOperator.NOT_LIKE,
            "NOT LIKE":ComparisonOperator.NOT_LIKE,

            "is-null":ComparisonOperator.IS_NULL,
            "IS NULL":ComparisonOperator.IS_NULL,

            "is-not-null":ComparisonOperator.IS_NOT_NULL,
            "IS NOT NULL":ComparisonOperator.IS_NOT_NULL
    ]

    static String getJoinOperatorString(JoinOperator op) {
        return op == JoinOperator.OR ? "OR" : "AND"
    }
    static JoinOperator getJoinOperator(String opName) {
        if (!opName) return JoinOperator.AND
        switch (opName) {
            case "or":
            case "OR": return JoinOperator.OR
            case "and":
            case "AND":
            default: return JoinOperator.AND
        }
    }
    static String getComparisonOperatorString(ComparisonOperator op) {
        return comparisonOperatorStringMap.get(op)
    }
    static ComparisonOperator getComparisonOperator(String opName) {
        if (!opName) return ComparisonOperator.EQUALS
        return stringComparisonOperatorMap.get(opName)
    }

    static boolean compareByOperator(Object value1, ComparisonOperator op, Object value2) {
        switch (op) {
        case ComparisonOperator.EQUALS:
            return value1 == value2
        case ComparisonOperator.NOT_EQUAL:
            return value1 != value2
        case ComparisonOperator.LESS_THAN:
            Comparable comp1 = StupidUtilities.makeComparable(value1)
            Comparable comp2 = StupidUtilities.makeComparable(value2)
            return comp1 < comp2
        case ComparisonOperator.GREATER_THAN:
            Comparable comp1 = StupidUtilities.makeComparable(value1)
            Comparable comp2 = StupidUtilities.makeComparable(value2)
            return comp1 > comp2
        case ComparisonOperator.LESS_THAN_EQUAL_TO:
            Comparable comp1 = StupidUtilities.makeComparable(value1)
            Comparable comp2 = StupidUtilities.makeComparable(value2)
            return comp1 <= comp2
        case ComparisonOperator.GREATER_THAN_EQUAL_TO:
            Comparable comp1 = StupidUtilities.makeComparable(value1)
            Comparable comp2 = StupidUtilities.makeComparable(value2)
            return comp1 >= comp2
        case ComparisonOperator.IN:
            if (value2 instanceof Collection) {
                return ((Collection) value2).contains(value1)
            } else {
                // not a Collection, try equals
                return value1 == value2
            }
        case ComparisonOperator.NOT_IN:
            if (value2 instanceof Collection) {
                return !((Collection) value2).contains(value1)
            } else {
                // not a Collection, try not-equals
                return value1 != value2
            }
        case ComparisonOperator.BETWEEN:
            if (value2 instanceof Collection && ((Collection) value2).size() == 2) {
                Comparable comp1 = StupidUtilities.makeComparable(value1)
                Iterator iterator = ((Collection) value2).iterator()
                Comparable lowObj = StupidUtilities.makeComparable(iterator.next())
                Comparable highObj = StupidUtilities.makeComparable(iterator.next())
                return lowObj <= comp1 && comp1 < highObj
            } else {
                return false
            }
        case ComparisonOperator.NOT_BETWEEN:
            if (value2 instanceof Collection && ((Collection) value2).size() == 2) {
                Comparable comp1 = StupidUtilities.makeComparable(value1)
                Iterator iterator = ((Collection) value2).iterator()
                Comparable lowObj = StupidUtilities.makeComparable(iterator.next())
                Comparable highObj = StupidUtilities.makeComparable(iterator.next())
                return lowObj > comp1 && comp1 >= highObj
            } else {
                return false
            }
        case ComparisonOperator.LIKE:
            return StupidUtilities.compareLike(value1, value2)
        case ComparisonOperator.NOT_LIKE:
            return !StupidUtilities.compareLike(value1, value2)
        case ComparisonOperator.IS_NULL:
            return value2 == null
        case ComparisonOperator.IS_NOT_NULL:
            return value2 != null
        }
        // default return false
        return false
    }
}
