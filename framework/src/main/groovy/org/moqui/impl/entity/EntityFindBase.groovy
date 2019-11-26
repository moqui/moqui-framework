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
import org.moqui.BaseException
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.entity.*
import org.moqui.etl.SimpleEtl
import org.moqui.etl.SimpleEtl.StopException
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.TransactionCache
import org.moqui.impl.context.TransactionFacadeImpl
import org.moqui.impl.entity.condition.*
import org.moqui.impl.entity.EntityJavaUtil.FieldOrderOptions
import org.moqui.util.CollectionUtilities
import org.moqui.util.MNode
import org.moqui.util.ObjectUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp

@CompileStatic
abstract class EntityFindBase implements EntityFind {
    protected final static Logger logger = LoggerFactory.getLogger(EntityFindBase.class)

    // these error strings are here for convenience for LocalizedMessage records
    // NOTE: don't change these unless there is a really good reason, will break localization
    private static final String ONE_ERROR = 'Error finding one ${entityName} by ${condition}'
    private static final String LIST_ERROR = 'Error finding list of ${entityName} by ${condition}'
    private static final String COUNT_ERROR = 'Error finding count of ${entityName} by ${condition}'

    final static int defaultResultSetType = ResultSet.TYPE_FORWARD_ONLY

    public final EntityFacadeImpl efi
    public final TransactionCache txCache

    protected String entityName
    protected EntityDefinition entityDef = (EntityDefinition) null
    protected EntityDynamicViewImpl dynamicView = (EntityDynamicViewImpl) null

    protected String singleCondField = (String) null
    protected Object singleCondValue = null
    protected Map<String, Object> simpleAndMap = (Map<String, Object>) null
    protected EntityConditionImplBase whereEntityCondition = (EntityConditionImplBase) null

    protected EntityConditionImplBase havingEntityCondition = (EntityConditionImplBase) null

    protected ArrayList<String> fieldsToSelect = (ArrayList<String>) null
    protected ArrayList<String> orderByFields = (ArrayList<String>) null

    protected Boolean useCache = (Boolean) null

    protected boolean distinct = false
    protected Integer offset = (Integer) null
    protected Integer limit = (Integer) null
    protected boolean forUpdate = false
    protected boolean useClone = false

    protected int resultSetType = defaultResultSetType
    protected int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY
    protected Integer fetchSize = (Integer) null
    protected Integer maxRows = (Integer) null

    protected boolean disableAuthz = false
    protected boolean requireSearchFormParameters = false
    protected boolean hasSearchFormParameters = false

    protected ArrayList<String> queryTextList = new ArrayList<>()


    EntityFindBase(EntityFacadeImpl efi, String entityName) {
        this.efi = efi
        this.entityName = entityName
        TransactionFacadeImpl tfi = efi.ecfi.transactionFacade
        txCache = tfi.getTransactionCache()
        // if (!tfi.isTransactionInPlace()) logger.warn("No transaction in place, creating find for entity ${entityName}")
    }
    EntityFindBase(EntityFacadeImpl efi, EntityDefinition ed) {
        this.efi = efi
        entityName = ed.fullEntityName
        entityDef = ed
        TransactionFacadeImpl tfi = efi.ecfi.transactionFacade
        txCache = tfi.getTransactionCache()
    }

    @Override EntityFind entity(String name) { entityName = name; return this }
    @Override String getEntity() { return entityName }

    // ======================== Conditions (Where and Having) =================

    @Override
    EntityFind condition(String fieldName, Object value) {
        boolean noSam = (simpleAndMap == null)
        boolean noScf = (singleCondField == null)
        if (noSam && noScf) {
            singleCondField = fieldName
            singleCondValue = value
        } else {
            if (noSam) simpleAndMap = new LinkedHashMap()
            if (!noScf) {
                simpleAndMap.put(singleCondField, singleCondValue)
                singleCondField = (String) null
                singleCondValue = null
            }
            simpleAndMap.put(fieldName, value)
        }
        return this
    }

    @Override
    EntityFind condition(String fieldName, EntityCondition.ComparisonOperator operator, Object value) {
        EntityDefinition ed = getEntityDef()
        FieldInfo fi = ed.getFieldInfo(fieldName)
        if (fi == null) throw new EntityException("Field ${fieldName} not found on entity ${entityName}, cannot add condition")
        if (operator == null) operator = EntityCondition.EQUALS
        if (ed.isViewEntity && fi.fieldNode.attribute("function")) {
            return havingCondition(new FieldValueCondition(fi.conditionField, operator, value))
        } else {
            if (EntityCondition.EQUALS.is(operator)) return condition(fieldName, value)
            return condition(new FieldValueCondition(fi.conditionField, operator, value))
        }
    }
    @Override
    EntityFind condition(String fieldName, String operator, Object value) {
        EntityCondition.ComparisonOperator opObj = operator == null || operator.isEmpty() ?
                EntityCondition.EQUALS : EntityConditionFactoryImpl.stringComparisonOperatorMap.get(operator)
        if (opObj == null) throw new EntityException("Operator [${operator}] is not a valid field comparison operator")
        return condition(fieldName, opObj, value)
    }

    @Override
    EntityFind conditionToField(String fieldName, EntityCondition.ComparisonOperator operator, String toFieldName) {
        return condition(efi.entityConditionFactory.makeConditionToField(fieldName, operator, toFieldName))
    }

    @Override
    EntityFind condition(Map<String, Object> fields) {
        if (fields == null) return this

        if (fields instanceof EntityValueBase) fields = ((EntityValueBase) fields).getValueMap()
        int fieldsSize = fields.size()
        if (fieldsSize == 0) return this

        boolean noSam = simpleAndMap == null
        boolean noScf = singleCondField == null
        if (fieldsSize == 1 && noSam && noScf) {
            // just set the singleCondField
            Map.Entry<String, Object> onlyEntry = fields.entrySet().iterator().next()
            singleCondField = (String) onlyEntry.key
            singleCondValue = onlyEntry.value
        } else {
            if (noSam) simpleAndMap = new LinkedHashMap<String, Object>()
            if (!noScf) {
                simpleAndMap.put(singleCondField, singleCondValue)
                singleCondField = (String) null
                singleCondValue = null
            }
            getEntityDef().entityInfo.setFields(fields, simpleAndMap, true, null, null)
        }
        return this
    }

    @Override
    EntityFind condition(EntityCondition condition) {
        if (condition == null) return this

        Class condClass = condition.getClass()
        if (condClass == FieldValueCondition.class) {
            // if this is a basic field/value EQUALS condition, just add to simpleAndMap
            FieldValueCondition fvc = (FieldValueCondition) condition
            if (EntityCondition.EQUALS.is(fvc.getOperator()) && !fvc.getIgnoreCase()) {
                this.condition(fvc.getFieldName(), fvc.getValue())
                return this
            }
        } else if (condClass == ListCondition.class) {
            ListCondition lc = (ListCondition) condition
            ArrayList<EntityConditionImplBase> condList = lc.getConditionList()
            // if empty list add nothing
            if (condList.size() == 0) return this
            // if this is an AND list condition, just unroll it and add each one; could end up as another list, but may add to simpleAndMap
            if (EntityCondition.AND.is(lc.getOperator())) {
                for (int i = 0; i < condList.size(); i++) this.condition(condList.get(i))
                return this
            }
        } else if (condClass == BasicJoinCondition.class) {
            BasicJoinCondition basicCond = (BasicJoinCondition) condition
            if (EntityCondition.AND.is(basicCond.getOperator())) {
                if (basicCond.getLhs() != null) this.condition(basicCond.getLhs())
                if (basicCond.getRhs() != null) this.condition(basicCond.getRhs())
                return this
            }
        }

        if (whereEntityCondition != null) {
            // use ListCondition instead of ANDing two at a time to avoid a bunch of nested ANDs
            if (whereEntityCondition instanceof ListCondition &&
                    ((ListCondition) whereEntityCondition).getOperator() == EntityCondition.AND) {
                ((ListCondition) whereEntityCondition).addCondition((EntityConditionImplBase) condition)
            } else {
                ArrayList<EntityConditionImplBase> condList = new ArrayList()
                condList.add(whereEntityCondition)
                condList.add((EntityConditionImplBase) condition)
                whereEntityCondition = new ListCondition(condList, EntityCondition.AND)
            }
        } else {
            whereEntityCondition = (EntityConditionImplBase) condition
        }
        return this
    }

    @Override
    EntityFind conditionDate(String fromFieldName, String thruFieldName, Timestamp compareStamp) {
        condition(efi.entityConditionFactory.makeConditionDate(fromFieldName, thruFieldName, compareStamp))
        return this
    }

    @Override
    boolean getHasCondition() {
        if (singleCondField != null) return true
        if (simpleAndMap != null && simpleAndMap.size() > 0) return true
        if (whereEntityCondition != null) return true
        return false
    }
    @Override boolean getHasHavingCondition() { havingEntityCondition != null }

    @Override
    EntityFind havingCondition(EntityCondition condition) {
        if (condition == null) return this
        if (havingEntityCondition != null) {
            // use ListCondition instead of ANDing two at a time to avoid a bunch of nested ANDs
            if (havingEntityCondition instanceof ListCondition) {
                ((ListCondition) havingEntityCondition).addCondition((EntityConditionImplBase) condition)
            } else {
                ArrayList<EntityConditionImplBase> condList = new ArrayList()
                condList.add(havingEntityCondition)
                condList.add((EntityConditionImplBase) condition)
                havingEntityCondition = new ListCondition(condList, EntityCondition.AND)
            }
        } else {
            havingEntityCondition = (EntityConditionImplBase) condition
        }
        return this
    }

    @Override
    EntityCondition getWhereEntityCondition() { return getWhereEntityConditionInternal(getEntityDef()) }
    EntityConditionImplBase getWhereEntityConditionInternal(EntityDefinition localEd) {
        boolean wecNull = (whereEntityCondition == null)
        int samSize = simpleAndMap != null ? simpleAndMap.size() : 0

        EntityConditionImplBase singleCond = (EntityConditionImplBase) null
        if (singleCondField != null) {
            if (samSize > 0) logger.warn("simpleAndMap size ${samSize} and singleCondField not null!")
            ConditionField cf
            if (localEd != null) {
                FieldInfo fi = localEd.getFieldInfo(singleCondField)
                if (fi == null) throw new EntityException("Error in find, field ${singleCondField} does not exist in entity ${localEd.getFullEntityName()}")
                cf = fi.conditionField
            } else {
                cf = new ConditionField(singleCondField)
            }
            singleCond = new FieldValueCondition(cf, EntityCondition.EQUALS, singleCondValue)
        }
        // special case, frequent operation: find by single key
        if (singleCond != null && wecNull && samSize == 0) return singleCond

        // see if we need to combine singleCond, simpleAndMap, and whereEntityCondition
        ArrayList<EntityConditionImplBase> condList = new ArrayList<EntityConditionImplBase>()
        if (singleCond != null) condList.add(singleCond)

        if (samSize > 0) {
            // create a ListCondition from the Map to allow for combination (simplification) with other conditions
            for (Map.Entry<String, Object> samEntry in simpleAndMap.entrySet()) {
                ConditionField cf
                if (localEd != null) {
                    FieldInfo fi = localEd.getFieldInfo((String) samEntry.getKey())
                    if (fi == null) throw new EntityException("Error in find, field ${samEntry.getKey()} does not exist in entity ${localEd.getFullEntityName()}")
                    cf = fi.conditionField
                } else {
                    cf = new ConditionField((String) samEntry.key)
                }
                condList.add(new FieldValueCondition(cf, EntityCondition.EQUALS, samEntry.value))
            }
        }
        if (condList.size() > 0) {
            if (!wecNull) {
                Class whereEntCondClass = whereEntityCondition.getClass()
                if (whereEntCondClass == ListCondition.class) {
                    ListCondition listCond = (ListCondition) this.whereEntityCondition
                    if (EntityCondition.AND.is(listCond.getOperator())) {
                        condList.addAll(listCond.getConditionList())
                        return new ListCondition(condList, EntityCondition.AND)
                    } else {
                        condList.add(listCond)
                        return new ListCondition(condList, EntityCondition.AND)
                    }
                } else if (whereEntCondClass == FieldValueCondition.class || whereEntCondClass == DateCondition.class ||
                        whereEntCondClass == FieldToFieldCondition.class) {
                    condList.add(whereEntityCondition)
                    return new ListCondition(condList, EntityCondition.AND)
                } else if (whereEntCondClass == BasicJoinCondition.class) {
                    BasicJoinCondition basicCond = (BasicJoinCondition) this.whereEntityCondition
                    if (EntityCondition.AND.is(basicCond.getOperator())) {
                        condList.add(basicCond.getLhs())
                        condList.add(basicCond.getRhs())
                        return new ListCondition(condList, EntityCondition.AND)
                    } else {
                        condList.add(basicCond)
                        return new ListCondition(condList, EntityCondition.AND)
                    }
                } else {
                    condList.add(whereEntityCondition)
                    return new ListCondition(condList, EntityCondition.AND)
                }
            } else {
                // no whereEntityCondition, just create a ListConditio for the simpleAndMap
                return new ListCondition(condList, EntityCondition.AND)
            }
        } else {
            return whereEntityCondition
        }
    }

    /** Used by TransactionCache */
    Map<String, Object> getSimpleMapPrimaryKeys() {
        int samSize = simpleAndMap != null ? simpleAndMap.size() : 0
        boolean scfNull = (singleCondField == null)
        if (samSize > 0 && !scfNull) logger.warn("simpleAndMap size ${samSize} and singleCondField not null!")
        Map<String, Object> pks = new HashMap<>()
        ArrayList<String> pkFieldNames = getEntityDef().getPkFieldNames()
        int pkFieldNamesSize = pkFieldNames.size()
        for (int i = 0; i < pkFieldNamesSize; i++) {
            // only include PK fields which has a non-empty value, leave others out of the Map
            String fieldName = (String) pkFieldNames.get(i)
            Object value = null
            if (samSize > 0) value = simpleAndMap.get(fieldName)
            if (value == null && !scfNull && singleCondField.equals(fieldName)) value = singleCondValue
            // if any fields have no value we don't have a full PK so bye bye
            if (ObjectUtilities.isEmpty(value)) return null
            pks.put(fieldName, value)
        }
        return pks
    }

    @Override EntityCondition getHavingEntityCondition() { return havingEntityCondition }

    @Override
    EntityFind searchFormInputs(String inputFieldsMapName, String defaultOrderBy, boolean alwaysPaginate) {
        return searchFormInputs(inputFieldsMapName, null, null, defaultOrderBy, alwaysPaginate)
    }
    EntityFind searchFormInputs(String inputFieldsMapName, Map<String, Object> defaultParameters, String skipFields,
                                String defaultOrderBy, boolean alwaysPaginate) {
        ExecutionContextImpl ec = efi.ecfi.getEci()
        Map<String, Object> inf = inputFieldsMapName ? (Map<String, Object>) ec.resource.expression(inputFieldsMapName, "") : ec.context
        return searchFormMap(inf, defaultParameters, skipFields, defaultOrderBy, alwaysPaginate)
    }

    @Override
    EntityFind searchFormMap(Map<String, Object> inputFieldsMap, Map<String, Object> defaultParameters, String skipFields,
                             String defaultOrderBy, boolean alwaysPaginate) {
        ExecutionContextImpl ec = efi.ecfi.getEci()

        // to avoid issues with entities that have cache=true, if no cache value is specified for this set it to false (avoids pagination errors, etc)
        if (useCache == null) useCache(false)

        Set<String> skipFieldSet = new HashSet<>()
        if (skipFields != null && !skipFields.isEmpty()) {
            String[] skipFieldArray = skipFields.split(",")
            for (int i = 0; i < skipFieldArray.length; i++) {
                String skipField = skipFieldArray[i].trim()
                if (skipField.length() > 0) skipFieldSet.add(skipField)
            }
        }

        boolean addedConditions = false
        if (inputFieldsMap != null && inputFieldsMap.size() > 0)
            addedConditions = processInputFields(inputFieldsMap, skipFieldSet, ec)
        hasSearchFormParameters = addedConditions

        if (!addedConditions && defaultParameters != null && defaultParameters.size() > 0) {
            processInputFields(defaultParameters, skipFieldSet, ec)
            for (Map.Entry<String, Object> dpEntry in defaultParameters.entrySet()) ec.contextStack.put(dpEntry.key, dpEntry.value)
        }

        // always look for an orderByField parameter too
        String orderByString = inputFieldsMap?.get("orderByField") ?: defaultOrderBy
        if (orderByString != null && orderByString.length() > 0) {
            ec.contextStack.put("orderByField", orderByString)
            this.orderBy(orderByString)
        }

        // look for the pageIndex and optional pageSize parameters; don't set these if should cache as will disable the cached query
        if ((alwaysPaginate || inputFieldsMap?.get("pageIndex") || inputFieldsMap?.get("pageSize")) && !shouldCache()) {
            int pageIndex = (inputFieldsMap?.get("pageIndex") ?: 0) as int
            int pageSize = (inputFieldsMap?.get("pageSize") ?: (this.limit ?: 20)) as int
            offset(pageIndex, pageSize)
            limit(pageSize)
        }

        // if there is a pageNoLimit clear out the limit regardless of other settings
        if ("true".equals(inputFieldsMap?.get("pageNoLimit")) || inputFieldsMap?.get("pageNoLimit") == true) {
            offset = null
            limit = null
        }

        return this
    }

    protected boolean processInputFields(Map<String, Object> inputFieldsMap, Set<String> skipFieldSet, ExecutionContextImpl ec) {
        EntityDefinition ed = getEntityDef()
        boolean addedConditions = false
        for (FieldInfo fi in ed.allFieldInfoList) {
            String fn = fi.name
            if (skipFieldSet.contains(fn)) continue

            // NOTE: do we need to do type conversion here?

            // this will handle text-find
            if (inputFieldsMap.containsKey(fn) || inputFieldsMap.containsKey(fn + "_op")) {
                Object value = inputFieldsMap.get(fn)
                boolean valueEmpty = ObjectUtilities.isEmpty(value)
                String op = inputFieldsMap.get(fn + "_op") ?: "equals"
                boolean not = (inputFieldsMap.get(fn + "_not") == "Y" || inputFieldsMap.get(fn + "_not") == "true")
                boolean ic = (inputFieldsMap.get(fn + "_ic") == "Y" || inputFieldsMap.get(fn + "_ic") == "true")

                EntityCondition cond = null
                switch (op) {
                    case "equals":
                        if (!valueEmpty) {
                            Object convertedValue = value instanceof String ? ed.convertFieldString(fn, (String) value, ec) : value
                            cond = efi.entityConditionFactory.makeCondition(fn,
                                    not ? EntityCondition.NOT_EQUAL : EntityCondition.EQUALS, convertedValue, not)
                            if (ic) cond.ignoreCase()
                        }
                        break
                    case "like":
                        if (!valueEmpty) {
                            cond = efi.entityConditionFactory.makeCondition(fn,
                                    not ? EntityCondition.NOT_LIKE : EntityCondition.LIKE, value)
                            if (ic) cond.ignoreCase()
                        }
                        break
                    case "contains":
                        if (!valueEmpty) {
                            cond = efi.entityConditionFactory.makeCondition(fn,
                                    not ? EntityCondition.NOT_LIKE : EntityCondition.LIKE, "%${value}%")
                            if (ic) cond.ignoreCase()
                        }
                        break
                    case "begins":
                        if (!valueEmpty) {
                            cond = efi.entityConditionFactory.makeCondition(fn,
                                    not ? EntityCondition.NOT_LIKE : EntityCondition.LIKE, "${value}%")
                            if (ic) cond.ignoreCase()
                        }
                        break
                    case "empty":
                        cond = efi.entityConditionFactory.makeCondition(
                                efi.entityConditionFactory.makeCondition(fn,
                                        not ? EntityCondition.NOT_EQUAL : EntityCondition.EQUALS, null),
                                not ? EntityCondition.JoinOperator.AND : EntityCondition.JoinOperator.OR,
                                efi.entityConditionFactory.makeCondition(fn,
                                        not ? EntityCondition.NOT_EQUAL : EntityCondition.EQUALS, ""))
                        break
                    case "in":
                        if (!valueEmpty) {
                            Collection valueList = null
                            if (value instanceof CharSequence) {
                                valueList = Arrays.asList(value.toString().split(","))
                            } else if (value instanceof Collection) {
                                valueList = (Collection) value
                            }
                            if (valueList) {
                                cond = efi.entityConditionFactory.makeCondition(fn,
                                        not ? EntityCondition.NOT_IN : EntityCondition.IN, valueList, not)

                            }
                        }
                        break
                }
                if (cond != null) {
                    if (fi.hasAggregateFunction) { this.havingCondition(cond) } else { this.condition(cond) }
                    addedConditions = true
                }
            } else if (inputFieldsMap.get(fn + "_period")) {
                List<Timestamp> range = ec.user.getPeriodRange((String) inputFieldsMap.get(fn + "_period"),
                        (String) inputFieldsMap.get(fn + "_poffset"), (String) inputFieldsMap.get(fn + "_pdate"))
                EntityCondition fromCond = efi.entityConditionFactory.makeCondition(fn, EntityCondition.GREATER_THAN_EQUAL_TO, range.get(0))
                EntityCondition thruCond = efi.entityConditionFactory.makeCondition(fn, EntityCondition.LESS_THAN, range.get(1))
                if (fi.hasAggregateFunction) { this.havingCondition(fromCond); this.havingCondition(thruCond) }
                else { this.condition(fromCond); this.condition(thruCond) }
                addedConditions = true
            } else {
                // these will handle range-find and date-find
                Object fromValue = inputFieldsMap.get(fn + "_from")
                if (fromValue && fromValue instanceof CharSequence) {
                    if (fi.typeValue == 2 && fromValue.length() < 12)
                        fromValue = ec.l10nFacade.parseTimestamp(fromValue.toString() + " 00:00:00.000", "yyyy-MM-dd HH:mm:ss.SSS")
                    else fromValue = ed.convertFieldString(fn, fromValue.toString(), ec)
                }
                Object thruValue = inputFieldsMap.get(fn + "_thru")
                if (thruValue && thruValue instanceof CharSequence) {
                    if (fi.typeValue == 2 && thruValue.length() < 12)
                        thruValue = ec.l10nFacade.parseTimestamp(thruValue.toString() + " 23:59:59.999", "yyyy-MM-dd HH:mm:ss.SSS")
                    else thruValue = ed.convertFieldString(fn, thruValue.toString(), ec)
                }

                if (!ObjectUtilities.isEmpty(fromValue)) {
                    EntityCondition fromCond = efi.entityConditionFactory.makeCondition(fn, EntityCondition.GREATER_THAN_EQUAL_TO, fromValue)
                    if (fi.hasAggregateFunction) { this.havingCondition(fromCond) } else { this.condition(fromCond) }
                    addedConditions = true
                }
                if (!ObjectUtilities.isEmpty(thruValue)) {
                    EntityCondition thruCond = efi.entityConditionFactory.makeCondition(fn, EntityCondition.LESS_THAN_EQUAL_TO, thruValue)
                    if (fi.hasAggregateFunction) { this.havingCondition(thruCond) } else { this.condition(thruCond) }
                    addedConditions = true
                }
            }
        }
        return addedConditions
    }

    // ======================== General/Common Options ========================

    @Override
    EntityFind selectField(String fieldToSelect) {
        if (fieldToSelect == null || fieldToSelect.length() == 0) return this
        if (fieldsToSelect == null) fieldsToSelect = new ArrayList<>()
        if (fieldToSelect.contains(",")) {
            for (String ftsPart in fieldToSelect.split(",")) {
                String selectName = ftsPart.trim()
                if (getEntityDef().isField(selectName) && !fieldsToSelect.contains(selectName)) fieldsToSelect.add(selectName)
            }
        } else {
            if (getEntityDef().isField(fieldToSelect) && !fieldsToSelect.contains(fieldToSelect)) fieldsToSelect.add(fieldToSelect)
        }
        return this
    }
    @Override
    EntityFind selectFields(Collection<String> selectFields) {
        if (!selectFields) return this
        for (String fieldToSelect in selectFields) selectField(fieldToSelect)
        return this
    }
    @Override List<String> getSelectFields() { return fieldsToSelect }

    @Override
    EntityFind orderBy(String orderByFieldName) {
        if (orderByFieldName == null || orderByFieldName.length() == 0) return this
        if (this.orderByFields == null) this.orderByFields = new ArrayList<>()
        if (orderByFieldName.contains(",")) {
            for (String obsPart in orderByFieldName.split(",")) {
                String orderByName = obsPart.trim()
                FieldOrderOptions foo = new FieldOrderOptions(orderByName)
                if (getEntityDef().isField(foo.fieldName) && !this.orderByFields.contains(orderByName)) this.orderByFields.add(orderByName)
            }
        } else {
            FieldOrderOptions foo = new FieldOrderOptions(orderByFieldName)
            if (getEntityDef().isField(foo.fieldName) && !this.orderByFields.contains(orderByFieldName)) this.orderByFields.add(orderByFieldName)
        }
        return this
    }
    @Override
    EntityFind orderBy(List<String> orderByFieldNames) {
        if (!orderByFieldNames) return this
        if (orderByFieldNames instanceof RandomAccess) {
            // avoid creating an iterator if possible
            int listSize = orderByFieldNames.size()
            for (int i = 0; i < listSize; i++) orderBy(orderByFieldNames.get(i))
        } else {
            for (String orderByFieldName in orderByFieldNames) orderBy(orderByFieldName)
        }
        return this
    }
    @Override List<String> getOrderBy() { return orderByFields != null ? Collections.unmodifiableList(orderByFields) : null }
    ArrayList<String> getOrderByFields() { return orderByFields }

    @Override EntityFind useCache(Boolean useCache) { this.useCache = useCache; return this }
    @Override boolean getUseCache() { return this.useCache }

    @Override EntityFind useClone(boolean uc) { useClone = uc; return this }

    // ======================== Advanced Options ==============================

    @Override EntityFind distinct(boolean distinct) { this.distinct = distinct; return this }
    @Override boolean getDistinct() { return distinct }

    @Override EntityFind offset(Integer offset) { this.offset = offset; return this }
    @Override EntityFind offset(int pageIndex, int pageSize) { offset(pageIndex * pageSize) }
    @Override Integer getOffset() { return offset }

    @Override EntityFind limit(Integer limit) { this.limit = limit; return this }
    @Override Integer getLimit() { return limit }

    @Override int getPageIndex() { return offset == null ? 0 : (offset/getPageSize()).intValue() }
    @Override int getPageSize() { return limit != null ? limit : 20 }

    @Override
    EntityFind forUpdate(boolean forUpdate) {
        this.forUpdate = forUpdate
        this.resultSetType = forUpdate ? ResultSet.TYPE_SCROLL_SENSITIVE : defaultResultSetType
        return this
    }
    @Override boolean getForUpdate() { return this.forUpdate }

    // ======================== JDBC Options ==============================

    @Override EntityFind resultSetType(int resultSetType) { this.resultSetType = resultSetType; return this }
    @Override int getResultSetType() { return this.resultSetType }

    @Override EntityFind resultSetConcurrency(int rsc) { resultSetConcurrency = rsc; return this }
    @Override int getResultSetConcurrency() { return this.resultSetConcurrency }

    @Override EntityFind fetchSize(Integer fetchSize) { this.fetchSize = fetchSize; return this }
    @Override Integer getFetchSize() { return this.fetchSize }

    @Override EntityFind maxRows(Integer maxRows) { this.maxRows = maxRows; return this }
    @Override Integer getMaxRows() { return this.maxRows }

    // ======================== Misc Methods ========================

    EntityDefinition getEntityDef() {
        if (entityDef != null) return entityDef
        if (dynamicView != null) {
            entityDef = dynamicView.makeEntityDefinition()
        } else {
            entityDef = efi.getEntityDefinition(entityName)
        }
        return entityDef
    }

    @Override EntityFind disableAuthz() { disableAuthz = true; return this }
    @Override EntityFind requireSearchFormParameters(boolean req) { this.requireSearchFormParameters = req; return this }

    @Override
    boolean shouldCache() {
        if (dynamicView != null) return false
        if (havingEntityCondition != null) return false
        if (limit != null || offset != null) return false
        if (forUpdate) return false
        if (useCache != null) {
            boolean useCacheLocal = useCache.booleanValue()
            if (!useCacheLocal) return false
            return !getEntityDef().entityInfo.neverCache
        } else {
            return "true".equals(getEntityDef().entityInfo.useCache)
        }
    }

    @Override
    String toString() {
        return "Find: ${entityName} WHERE [${singleCondField?:''}:${singleCondValue?:''}] [${simpleAndMap}] [${whereEntityCondition}] HAVING [${havingEntityCondition}] " +
                "SELECT [${fieldsToSelect}] ORDER BY [${orderByFields}] CACHE [${useCache}] DISTINCT [${distinct}] " +
                "OFFSET [${offset}] LIMIT [${limit}] FOR UPDATE [${forUpdate}]"
    }

    private static String makeErrorMsg(String baseMsg, String expandMsg, EntityConditionImplBase cond, EntityDefinition ed, ExecutionContextImpl ec) {
        Map<String, Object> errorContext = new HashMap<>()
        errorContext.put("entityName", ed.getEntityName()); errorContext.put("condition", cond)
        String errorMessage = null
        // TODO: need a different approach for localization, getting from DB may not be reliable after an error and may cause other errors (especially with Postgres and the auto rollback only)
        if (false && !"LocalizedMessage".equals(ed.getEntityName())) {
            try { errorMessage = ec.resourceFacade.expand(expandMsg, null, errorContext) }
            catch (Throwable t) { logger.trace("Error expanding error message", t) }
        }
        if (errorMessage == null) errorMessage = baseMsg + " " + ed.getEntityName() + " by " + cond
        return errorMessage
    }
    // ======================== Find and Abstract Methods ========================

    abstract EntityDynamicView makeEntityDynamicView()

    @Override
    EntityValue one() throws EntityException {
        ExecutionContextImpl ec = efi.ecfi.getEci()
        ArtifactExecutionFacadeImpl aefi = ec.artifactExecutionFacade
        boolean enableAuthz = disableAuthz ? !aefi.disableAuthz() : false
        try {
            EntityDefinition ed = getEntityDef()

            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(),
                    ArtifactExecutionInfo.AT_ENTITY, ArtifactExecutionInfo.AUTHZA_VIEW, "one")
            // really worth the overhead? if so change to handle singleCondField: .setParameters(simpleAndMap)
            aefi.pushInternal(aei, !ed.entityInfo.authorizeSkipView, false)

            try {
                return oneInternal(ec, ed)
            } finally {
                // pop the ArtifactExecutionInfo
                aefi.pop(aei)
            }
        } finally {
            if (enableAuthz) aefi.enableAuthz()
        }
    }
    @Override
    Map<String, Object> oneMaster(String name) {
        ExecutionContextImpl ec = efi.ecfi.getEci()
        ArtifactExecutionFacadeImpl aefi = ec.artifactExecutionFacade
        boolean enableAuthz = disableAuthz ? !aefi.disableAuthz() : false
        try {
            EntityDefinition ed = getEntityDef()

            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(),
                    ArtifactExecutionInfo.AT_ENTITY, ArtifactExecutionInfo.AUTHZA_VIEW, "one")
            aefi.pushInternal(aei, !ed.entityInfo.authorizeSkipView, false)

            try {
                EntityValue ev = oneInternal(ec, ed)
                if (ev == null) return null
                return ev.getMasterValueMap(name)
            } finally {
                // pop the ArtifactExecutionInfo
                aefi.pop(aei)
            }
        } finally {
            if (enableAuthz) aefi.enableAuthz()
        }
    }

    protected EntityValue oneInternal(ExecutionContextImpl ec, EntityDefinition ed) throws EntityException, SQLException {
        if (this.dynamicView != null) throw new EntityException("Dynamic View not supported for 'one' find.")

        boolean isViewEntity = ed.isViewEntity
        EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo

        if (entityInfo.isInvalidViewEntity) throw new EntityException("Cannot do find for view-entity with name ${entityName} because it has no member entities or no aliased fields.")

        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-one", true)

        boolean hasEmptyPk = false
        boolean hasFullPk = true
        if (singleCondField != null && ed.isPkField(singleCondField) && ObjectUtilities.isEmpty(singleCondValue)) {
            hasEmptyPk = true; hasFullPk = false }
        ArrayList<String> pkNameList = ed.getPkFieldNames()
        int pkSize = pkNameList.size()
        int samSize = simpleAndMap != null ? simpleAndMap.size() : 0
        if (hasFullPk && samSize > 1) {
            for (int i = 0; i < pkSize; i++) {
                String fieldName = (String) pkNameList.get(i)
                Object fieldValue = simpleAndMap.get(fieldName)
                if (ObjectUtilities.isEmpty(fieldValue)) {
                    if (simpleAndMap.containsKey(fieldName)) hasEmptyPk = true
                    hasFullPk = false
                    break
                }
            }
        }
        // if over-constrained (anything in addition to a full PK), just use the full PK
        if (hasFullPk && samSize > 1) {
            Map<String, Object> pks = new HashMap<>()
            if (singleCondField != null) {
                // this shouldn't generally happen, added to simpleAndMap internally on the fly when needed, but just in case
                pks.put(singleCondField, singleCondValue)
                singleCondField = (String) null; singleCondValue = null
            }
            for (int i = 0; i < pkSize; i++) {
                String fieldName = (String) pkNameList.get(i)
                pks.put(fieldName, simpleAndMap.get(fieldName))
            }
            simpleAndMap = pks
        }

        // if any PK fields are null, for whatever reason in calling code, the result is null so no need to send to DB or cache or anything
        if (hasEmptyPk) return (EntityValue) null

        boolean doCache = shouldCache()
        // NOTE: artifactExecutionFacade.filterFindForUser() no longer called here, called in EntityFindBuilder after trimming if needed for view-entity
        if (doCache) {
            // don't cache if there are any applicable filter conditions
            ArrayList findFilterList = ec.artifactExecutionFacade.getFindFiltersForUser(ed, null)
            if (findFilterList != null && findFilterList.size() > 0) doCache = false
        }

        EntityConditionImplBase whereCondition = getWhereEntityConditionInternal(ed)

        // no condition means no condition/parameter set, so return null for find.one()
        if (whereCondition == null) return (EntityValue) null

        // try the TX cache before the entity cache, should be more up-to-date
        EntityValueBase txcValue = (EntityValueBase) null
        if (txCache != null) {
            txcValue = txCache.oneGet(this)
            // NOTE: don't do this, opt to get latest from tx cache instead of from DB instead of trying to merge, lock
            //     only query done below; tx cache causes issues when for update used after non for update query if
            //     latest values from DB are needed!
            // if we got a value from txCache and we're doing a for update and it was not created in this tx cache then
            //     don't use it, we want the latest value from the DB (may have been queried without for update in this tx)
            // if (txcValue != null && forUpdate && !txCache.isTxCreate(txcValue)) txcValue = (EntityValueBase) null
        }

        // if (txcValue != null && ed.getEntityName() == "foo") logger.warn("========= TX cache one value: ${txcValue}")

        Cache<EntityCondition, EntityValueBase> entityOneCache = doCache ?
                ed.getCacheOne(efi.getEntityCache()) : (Cache<EntityCondition, EntityValueBase>) null
        EntityValueBase cacheHit = (EntityValueBase) null
        if (doCache && txcValue == null && !forUpdate) cacheHit = (EntityValueBase) entityOneCache.get(whereCondition)

        // we always want fieldInfoArray populated so that we know the order of the results coming back
        int ftsSize = fieldsToSelect != null ? fieldsToSelect.size() : 0
        FieldInfo[] fieldInfoArray
        FieldOrderOptions[] fieldOptionsArray = (FieldOrderOptions[]) null
        if (ftsSize == 0 || (txCache != null && txcValue == null) || (doCache && cacheHit == null)) {
            fieldInfoArray = entityInfo.allFieldInfoArray
        } else {
            fieldInfoArray = new FieldInfo[ftsSize]
            fieldOptionsArray = new FieldOrderOptions[ftsSize]
            boolean hasFieldOptions = false
            int fieldInfoArrayIndex = 0
            for (int i = 0; i < ftsSize; i++) {
                String fieldName = (String) fieldsToSelect.get(i)
                FieldInfo fi = ed.getFieldInfo(fieldName)
                if (fi == null) {
                    FieldOrderOptions foo = new FieldOrderOptions(fieldName)
                    fi = ed.getFieldInfo(foo.fieldName)
                    if (fi == null) throw new EntityException("Field to select ${fieldName} not found in entity ${ed.getFullEntityName()}")

                    fieldInfoArray[fieldInfoArrayIndex] = fi
                    fieldOptionsArray[fieldInfoArrayIndex] = foo
                    fieldInfoArrayIndex++
                    hasFieldOptions = true
                } else {
                    fieldInfoArray[fieldInfoArrayIndex] = fi
                    fieldInfoArrayIndex++
                }
            }
            if (!hasFieldOptions) fieldOptionsArray = (FieldOrderOptions[]) null
            if (fieldOptionsArray == null && ftsSize == entityInfo.allFieldInfoArray.length)
                fieldInfoArray = entityInfo.allFieldInfoArray
        }

        // if (ed.getEntityName() == "Asset") logger.warn("=========== find one of Asset ${this.simpleAndMap.get('assetId')}", new Exception("Location"))

        // call the abstract method
        EntityValueBase newEntityValue = (EntityValueBase) null
        if (txcValue != null) {
            if (txcValue instanceof EntityValueBase.DeletedEntityValue) {
                // is deleted value, so leave newEntityValue as null
                // put in cache as null since this was deleted
                if (doCache) efi.getEntityCache().putInOneCache(ed, whereCondition, null, entityOneCache)
            } else {
                // if forUpdate unless this was a TX CREATE it'll be in the DB and should be locked, so do the query
                //     anyway, but ignore the result unless it's a read only tx cache
                if (forUpdate && !txCache.isKnownLocked(txcValue) && !txCache.isTxCreate(txcValue)) {
                    EntityValueBase fuDbValue
                    EntityConditionImplBase cond = isViewEntity ? getConditionForQuery(ed, whereCondition) : whereCondition
                    try { fuDbValue = oneExtended(cond, fieldInfoArray, fieldOptionsArray) }
                    catch (SQLException e) { throw new EntitySqlException(makeErrorMsg("Error finding one", ONE_ERROR, cond, ed, ec), e) }
                    catch (Exception e) { throw new EntityException(makeErrorMsg("Error finding one", ONE_ERROR, cond, ed, ec), e) }

                    if (txCache.isReadOnly()) {
                        // is read only tx cache so use the value from the DB
                        newEntityValue = fuDbValue
                        // tell the tx cache about the new value
                        txCache.update(fuDbValue)
                    } else {
                        // we could try to merge the TX cache value and the latest DB value, but for now opt for the
                        //     TX cache value over the DB value
                        // if txcValue has been modified (fields in dbValueMap) see if those match what is coming from DB
                        Map<String, Object> txDbValueMap = txcValue.getDbValueMap()
                        Map<String, Object> fuDbValueMap = fuDbValue.getValueMap()
                        if (txDbValueMap != null && txDbValueMap.size() > 0 &&
                                !CollectionUtilities.mapMatchesFields(fuDbValueMap, txDbValueMap)) {
                            StringBuilder fieldDiffBuilder = new StringBuilder()
                            for (Map.Entry<String, Object> entry in txDbValueMap.entrySet()) {
                                Object compareObj = txDbValueMap.get(entry.getKey())
                                Object baseObj = fuDbValueMap.get(entry.getKey())
                                if (compareObj != baseObj) fieldDiffBuilder.append("- ").append(entry.key).append(": ")
                                        .append(compareObj).append(" (txc) != ").append(baseObj).append(" (db)\n")
                            }
                            logger.warn("Did for update query on ${ed.getFullEntityName()} and result did not match value in transaction cache: \n${fieldDiffBuilder}", new BaseException("location"))
                        }
                        newEntityValue = txcValue
                    }
                } else {
                    newEntityValue = txcValue
                }
                // put it in whether null or not (already know cacheHit is null)
                if (doCache) efi.getEntityCache().putInOneCache(ed, whereCondition, newEntityValue, entityOneCache)
            }
        } else if (cacheHit != null) {
            if (cacheHit instanceof EntityCache.EmptyRecord) newEntityValue = (EntityValueBase) null
            else newEntityValue = cacheHit
        } else {
            // for find one we'll always use the basic result set type and concurrency:
            this.resultSetType = ResultSet.TYPE_FORWARD_ONLY
            this.resultSetConcurrency = ResultSet.CONCUR_READ_ONLY

            EntityConditionImplBase cond = isViewEntity ? getConditionForQuery(ed, whereCondition) : whereCondition
            try { newEntityValue = oneExtended(cond, fieldInfoArray, fieldOptionsArray) }
            catch (SQLException e) { throw new EntitySqlException(makeErrorMsg("Error finding one", ONE_ERROR, cond, ed, ec), e) }
            catch (Exception e) { throw new EntityException(makeErrorMsg("Error finding one", ONE_ERROR, cond, ed, ec), e) }

            // it didn't come from the txCache so put it there
            if (txCache != null) txCache.onePut(newEntityValue, forUpdate)

            // put it in whether null or not (already know cacheHit is null)
            if (doCache) efi.getEntityCache().putInOneCache(ed, whereCondition, newEntityValue, entityOneCache)
        }

        // if (logger.traceEnabled) logger.trace("Find one on entity [${ed.fullEntityName}] with condition [${whereCondition}] found value [${newEntityValue}]")

        // final ECA trigger
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), newEntityValue, "find-one", false)

        return newEntityValue
    }

    EntityConditionImplBase getConditionForQuery(EntityDefinition ed, EntityConditionImplBase whereCondition) {
        // NOTE: do actual query condition as a separate condition because this will always be added on and isn't a
        //     part of the original where to use for the cache
        EntityConditionImplBase conditionForQuery
        EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
        if (viewWhere != null) {
            if (whereCondition != null) conditionForQuery = (EntityConditionImplBase) efi.getConditionFactory()
                    .makeCondition(whereCondition, EntityCondition.JoinOperator.AND, viewWhere)
            else conditionForQuery = viewWhere
        } else {
            conditionForQuery = whereCondition
        }

        return conditionForQuery
    }

    /** The abstract oneExtended method to implement */
    abstract EntityValueBase oneExtended(EntityConditionImplBase whereCondition, FieldInfo[] fieldInfoArray,
                                         FieldOrderOptions[] fieldOptionsArray) throws SQLException

    @Override
    EntityList list() throws EntityException {
        ExecutionContextImpl ec = efi.ecfi.getEci()
        ArtifactExecutionFacadeImpl aefi = ec.artifactExecutionFacade
        boolean enableAuthz = disableAuthz ? !aefi.disableAuthz() : false
        try {
            EntityDefinition ed = getEntityDef()

            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(),
                    ArtifactExecutionInfo.AT_ENTITY, ArtifactExecutionInfo.AUTHZA_VIEW, "list")
            aefi.pushInternal(aei, !ed.entityInfo.authorizeSkipView, false)
            try {
                return listInternal(ec, ed)
            } finally {
                aefi.pop(aei)
            }
        } finally {
            if (enableAuthz) aefi.enableAuthz()
        }
    }
    @Override
    List<Map<String, Object>> listMaster(String name) {
        ExecutionContextImpl ec = efi.ecfi.getEci()
        ArtifactExecutionFacadeImpl aefi = ec.artifactExecutionFacade
        boolean enableAuthz = disableAuthz ? !aefi.disableAuthz() : false
        try {
            EntityDefinition ed = getEntityDef()

            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(),
                    ArtifactExecutionInfo.AT_ENTITY, ArtifactExecutionInfo.AUTHZA_VIEW, "list")
            aefi.pushInternal(aei, !ed.entityInfo.authorizeSkipView, false)
            try {
                EntityList el = listInternal(ec, ed)
                return el.getMasterValueList(name)
            } finally {
                // pop the ArtifactExecutionInfo
                aefi.pop(aei)
            }
        } finally {
            if (enableAuthz) aefi.enableAuthz()
        }
    }

    protected EntityList listInternal(ExecutionContextImpl ec, EntityDefinition ed) throws EntityException, SQLException {
        if (requireSearchFormParameters && !hasSearchFormParameters) {
            logger.info("No parameters for list find on ${ed.fullEntityName}, not doing search")
            return new EntityListImpl(efi)
        }

        EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo
        boolean isViewEntity = entityInfo.isView

        if (entityInfo.isInvalidViewEntity) throw new EntityException("Cannot do find for view-entity with name ${entityName} because it has no member entities or no aliased fields.")

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-list", true)

        ArrayList<String> orderByExpanded = new ArrayList()
        // add the manually specified ones, then the ones in the view entity's entity-condition
        if (orderByFields != null) orderByExpanded.addAll(orderByFields)

        if (isViewEntity) {
            MNode entityConditionNode = ed.entityConditionNode
            if (entityConditionNode != null) {
                ArrayList<MNode> ecObList = entityConditionNode.children("order-by")
                if (ecObList != null) for (int i = 0; i < ecObList.size(); i++) {
                    MNode orderBy = (MNode) ecObList.get(i)
                    String fieldName = orderBy.attribute("field-name")
                    if(!orderByExpanded.contains(fieldName)) orderByExpanded.add(fieldName)
                }
                if ("true".equals(entityConditionNode.attribute("distinct"))) this.distinct(true)
            }
        }

        boolean doEntityCache = shouldCache()

        // NOTE: artifactExecutionFacade.filterFindForUser() no longer called here, called in EntityFindBuilder after trimming if needed for view-entity
        if (doEntityCache) {
            // don't cache if there are any applicable filter conditions
            ArrayList findFilterList = ec.artifactExecutionFacade.getFindFiltersForUser(ed, null)
            if (findFilterList != null && findFilterList.size() > 0) doEntityCache = false
        }

        EntityConditionImplBase whereCondition = getWhereEntityConditionInternal(ed)
        // don't cache if no whereCondition
        if (whereCondition == null) doEntityCache = false

        // try the txCache first, more recent than general cache (and for update general cache entries will be cleared anyway)
        EntityListImpl txcEli = txCache != null ? txCache.listGet(ed, whereCondition, orderByExpanded) : (EntityListImpl) null

        // NOTE: don't cache if there is a having condition, for now just support where
        // NOTE: could avoid caching lists if it is a filtered find, but mostly by org so reusable: && !filteredFind
        Cache<EntityCondition, EntityListImpl> entityListCache = doEntityCache ?
                ed.getCacheList(efi.getEntityCache()) : (Cache<EntityCondition, EntityListImpl>) null
        EntityListImpl cacheList = (EntityListImpl) null
        if (doEntityCache && txcEli == null && !forUpdate)
            cacheList = efi.getEntityCache().getFromListCache(ed, whereCondition, orderByExpanded, entityListCache)

        EntityListImpl el
        if (txcEli != null) {
            el = txcEli
            // if (ed.getFullEntityName().contains("OrderItem")) logger.warn("======== Got OrderItem from txCache ${el.size()} results where: ${whereCondition}")
        } else if (cacheList != null) {
            el = cacheList
        } else {
            // order by fields need to be selected (at least on some databases, Derby is one of them)
            int orderByExpandedSize = orderByExpanded.size()
            if (getDistinct() && fieldsToSelect != null && fieldsToSelect.size() > 0 && orderByExpandedSize > 0) {
                for (int i = 0; i < orderByExpandedSize; i++) {
                    String orderByField = (String) orderByExpanded.get(i)
                    FieldOrderOptions foo = new FieldOrderOptions(orderByField)
                    if (!fieldsToSelect.contains(foo.fieldName)) fieldsToSelect.add(foo.fieldName)
                }
            }

            // we always want fieldInfoArray populated so that we know the order of the results coming back
            int ftsSize = fieldsToSelect != null ? fieldsToSelect.size() : 0
            FieldInfo[] fieldInfoArray
            FieldOrderOptions[] fieldOptionsArray = (FieldOrderOptions[]) null
            if (ftsSize == 0 || doEntityCache) {
                fieldInfoArray = entityInfo.allFieldInfoArray
            } else {
                fieldInfoArray = new FieldInfo[ftsSize]
                fieldOptionsArray = new FieldOrderOptions[ftsSize]
                boolean hasFieldOptions = false
                int fieldInfoArrayIndex = 0
                for (int i = 0; i < ftsSize; i++) {
                    String fieldName = (String) fieldsToSelect.get(i)
                    FieldInfo fi = (FieldInfo) ed.getFieldInfo(fieldName)
                    if (fi == null) {
                        FieldOrderOptions foo = new FieldOrderOptions(fieldName)
                        fi = ed.getFieldInfo(foo.fieldName)
                        if (fi == null) throw new EntityException("Field to select ${fieldName} not found in entity ${ed.getFullEntityName()}")

                        fieldInfoArray[fieldInfoArrayIndex] = fi
                        fieldOptionsArray[fieldInfoArrayIndex] = foo
                        fieldInfoArrayIndex++
                        hasFieldOptions = true
                    } else {
                        fieldInfoArray[fieldInfoArrayIndex] = fi
                        fieldInfoArrayIndex++
                    }
                }
                if (!hasFieldOptions) fieldOptionsArray = (FieldOrderOptions[]) null
                if (fieldOptionsArray == null && ftsSize == entityInfo.allFieldInfoArray.length)
                    fieldInfoArray = entityInfo.allFieldInfoArray
            }

            EntityConditionImplBase queryWhereCondition = whereCondition
            EntityConditionImplBase havingCondition = havingEntityCondition
            if (isViewEntity) {
                EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
                queryWhereCondition = EntityConditionFactoryImpl.makeConditionImpl(whereCondition, EntityCondition.AND, viewWhere)

                havingCondition = havingEntityCondition
                EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
                havingCondition = EntityConditionFactoryImpl.makeConditionImpl(havingCondition, EntityCondition.AND, viewHaving)
            }

            // call the abstract method
            EntityListIterator eli
            try { eli = iteratorExtended(queryWhereCondition, havingCondition, orderByExpanded, fieldInfoArray, fieldOptionsArray) }
            catch (SQLException e) { throw new EntitySqlException(makeErrorMsg("Error finding list of", LIST_ERROR, queryWhereCondition, ed, ec), e) }
            catch (Exception e) { throw new EntityException(makeErrorMsg("Error finding list of", LIST_ERROR, queryWhereCondition, ed, ec), e) }

            MNode databaseNode = this.efi.getDatabaseNode(ed.getEntityGroupName())
            if (limit != null && databaseNode != null && "cursor".equals(databaseNode.attribute("offset-style"))) {
                el = (EntityListImpl) eli.getPartialList(offset != null ? offset : 0, limit, true)
            } else {
                el = (EntityListImpl) eli.getCompleteList(true)
            }

            // don't put in tx cache if it is going in list cache
            if (txCache != null && !doEntityCache && ftsSize == 0) txCache.listPut(ed, whereCondition, el)
            if (doEntityCache) efi.getEntityCache().putInListCache(ed, el, whereCondition, entityListCache)

            // if (ed.getFullEntityName().contains("OrderItem")) logger.warn("======== Got OrderItem from DATABASE ${el.size()} results where: ${whereCondition}")
            // logger.warn("======== Got ${ed.getFullEntityName()} from DATABASE ${el.size()} results where: ${whereCondition}")
        }

        // run the final rules
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-list", false)

        return el
    }

    @Override
    EntityListIterator iterator() throws EntityException {
        ExecutionContextImpl ec = efi.ecfi.getEci()
        ArtifactExecutionFacadeImpl aefi = ec.artifactExecutionFacade
        boolean enableAuthz = disableAuthz ? !ec.artifactExecutionFacade.disableAuthz() : false
        try {
            EntityDefinition ed = getEntityDef()

            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(),
                    ArtifactExecutionInfo.AT_ENTITY, ArtifactExecutionInfo.AUTHZA_VIEW, "iterator")
            aefi.pushInternal(aei, !ed.entityInfo.authorizeSkipView, false)
            try {
                return iteratorInternal(ec, ed)
            } finally {
                aefi.pop(aei)
            }
        } finally {
            if (enableAuthz) ec.artifactExecutionFacade.enableAuthz()
        }
    }
    protected EntityListIterator iteratorInternal(ExecutionContextImpl ec, EntityDefinition ed) throws EntityException, SQLException {
        if (requireSearchFormParameters && !hasSearchFormParameters) return null

        EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo
        boolean isViewEntity = entityInfo.isView

        if (entityInfo.isInvalidViewEntity) throw new EntityException("Cannot do find for view-entity with name ${entityName} because it has no member entities or no aliased fields.")

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-iterator", true)

        ArrayList<String> orderByExpanded = new ArrayList()
        // add the manually specified ones, then the ones in the view entity's entity-condition
        if (this.orderByFields != null) orderByExpanded.addAll(this.orderByFields)

        if (isViewEntity) {
            MNode entityConditionNode = ed.entityConditionNode
            if (entityConditionNode != null) {
                ArrayList<MNode> ecObList = entityConditionNode.children("order-by")
                if (ecObList != null) for (int i = 0; i < ecObList.size(); i++) {
                    MNode orderBy = ecObList.get(i)
                    String fieldName = orderBy.attribute("field-name")
                    if(!orderByExpanded.contains(fieldName)) orderByExpanded.add(fieldName)
                }
                if ("true".equals(entityConditionNode.attribute("distinct"))) this.distinct(true)
            }
        }

        // order by fields need to be selected (at least on some databases, Derby is one of them)
        if (getDistinct() && fieldsToSelect != null && fieldsToSelect.size() > 0 && orderByExpanded.size() > 0) {
            for (String orderByField in orderByExpanded) {
                FieldOrderOptions foo = new FieldOrderOptions(orderByField)
                if (!fieldsToSelect.contains(foo.fieldName)) fieldsToSelect.add(foo.fieldName)
            }
        }

        // we always want fieldInfoArray populated so that we know the order of the results coming back
        int ftsSize = fieldsToSelect != null ? fieldsToSelect.size() : 0
        FieldInfo[] fieldInfoArray
        FieldOrderOptions[] fieldOptionsArray = (FieldOrderOptions[]) null
        if (ftsSize == 0) {
            fieldInfoArray = entityInfo.allFieldInfoArray
        } else {
            fieldInfoArray = new FieldInfo[ftsSize]
            fieldOptionsArray = new FieldOrderOptions[ftsSize]
            boolean hasFieldOptions = false
            int fieldInfoArrayIndex = 0
            for (int i = 0; i < ftsSize; i++) {
                String fieldName = (String) fieldsToSelect.get(i)
                FieldInfo fi = ed.getFieldInfo(fieldName)
                if (fi == null) {
                    FieldOrderOptions foo = new FieldOrderOptions(fieldName)
                    fi = ed.getFieldInfo(foo.fieldName)
                    if (fi == null) throw new EntityException("Field to select ${fieldName} not found in entity ${ed.getFullEntityName()}")

                    fieldInfoArray[fieldInfoArrayIndex] = fi
                    fieldOptionsArray[fieldInfoArrayIndex] = foo
                    fieldInfoArrayIndex++
                    hasFieldOptions = true
                } else {
                    fieldInfoArray[fieldInfoArrayIndex] = fi
                    fieldInfoArrayIndex++
                }
            }
            if (!hasFieldOptions) fieldOptionsArray = (FieldOrderOptions[]) null
            if (fieldOptionsArray == null && ftsSize == entityInfo.allFieldInfoArray.length)
                fieldInfoArray = entityInfo.allFieldInfoArray
        }

        // NOTE: artifactExecutionFacade.filterFindForUser() no longer called here, called in EntityFindBuilder after trimming if needed for view-entity

        EntityConditionImplBase whereCondition = getWhereEntityConditionInternal(ed)
        EntityConditionImplBase havingCondition = havingEntityCondition
        if (isViewEntity) {
            EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
            whereCondition = EntityConditionFactoryImpl.makeConditionImpl(whereCondition, EntityCondition.AND, viewWhere)

            EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
            havingCondition = EntityConditionFactoryImpl.makeConditionImpl(havingCondition, EntityCondition.AND, viewHaving)
        }

        // call the abstract method
        EntityListIterator eli
        try { eli = iteratorExtended(whereCondition, havingCondition, orderByExpanded, fieldInfoArray, fieldOptionsArray) }
        catch (SQLException e) { throw new EntitySqlException(makeErrorMsg("Error finding list of", LIST_ERROR, whereCondition, ed, ec), e) }
        catch (Exception e) { throw new EntityException(makeErrorMsg("Error finding list of", LIST_ERROR, whereCondition, ed, ec), e) }

        // NOTE: if we are doing offset/limit with a cursor no good way to limit results, but we'll at least jump to the offset
        MNode databaseNode = this.efi.getDatabaseNode(ed.getEntityGroupName())
        // NOTE: allow databaseNode to be null because custom (non-JDBC) datasources may not have one
        if (this.offset != null && databaseNode != null && "cursor".equals(databaseNode.attribute("offset-style"))) {
            if (!eli.absolute(offset)) {
                // can't seek to desired offset? not enough results, just go to after last result
                eli.afterLast()
            }
        }

        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-iterator", false)

        return eli
    }

    abstract EntityListIterator iteratorExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
            ArrayList<String> orderByExpanded, FieldInfo[] fieldInfoArray, FieldOrderOptions[] fieldOptionsArray) throws SQLException

    @Override
    long count() throws EntityException {
        ExecutionContextImpl ec = efi.ecfi.getEci()
        ArtifactExecutionFacadeImpl aefi = ec.artifactExecutionFacade
        boolean enableAuthz = disableAuthz ? !ec.artifactExecutionFacade.disableAuthz() : false
        try {
            EntityDefinition ed = getEntityDef()

            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(),
                    ArtifactExecutionInfo.AT_ENTITY, ArtifactExecutionInfo.AUTHZA_VIEW, "count")
            aefi.pushInternal(aei, !ed.entityInfo.authorizeSkipView, false)
            try {
                return countInternal(ec, ed)
            } finally {
                aefi.pop(aei)
            }
        } finally {
            if (enableAuthz) ec.artifactExecutionFacade.enableAuthz()
        }
    }
    protected long countInternal(ExecutionContextImpl ec, EntityDefinition ed) throws EntityException, SQLException {
        if (requireSearchFormParameters && !hasSearchFormParameters) return 0L

        EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo
        boolean isViewEntity = entityInfo.isView

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-count", true)

        boolean doCache = shouldCache()

        // NOTE: artifactExecutionFacade.filterFindForUser() no longer called here, called in EntityFindBuilder after trimming if needed for view-entity
        if (doCache) {
            // don't cache if there are any applicable filter conditions
            ArrayList findFilterList = ec.artifactExecutionFacade.getFindFiltersForUser(ed, null)
            if (findFilterList != null && findFilterList.size() > 0) doCache = false
        }

        EntityConditionImplBase whereCondition = getWhereEntityConditionInternal(ed)
        // don't cache if no whereCondition
        if (whereCondition == null) doCache = false
        // NOTE: don't cache if there is a having condition, for now just support where

        Cache<EntityCondition, Long> entityCountCache = doCache ? ed.getCacheCount(efi.getEntityCache()) : (Cache) null
        Long cacheCount = (Long) null
        if (doCache) cacheCount = (Long) entityCountCache.get(whereCondition)

        long count
        if (cacheCount != null) {
            count = cacheCount
        } else {
            // select all pk and nonpk fields to match what list() or iterator() would do
            int ftsSize = fieldsToSelect != null ? fieldsToSelect.size() : 0
            FieldInfo[] fieldInfoArray
            FieldOrderOptions[] fieldOptionsArray = (FieldOrderOptions[]) null
            if (ftsSize == 0) {
                fieldInfoArray = entityInfo.allFieldInfoArray
            } else {
                fieldInfoArray = new FieldInfo[ftsSize]
                fieldOptionsArray = new FieldOrderOptions[ftsSize]
                boolean hasFieldOptions = false
                int fieldInfoArrayIndex = 0
                for (int i = 0; i < ftsSize; i++) {
                    String fieldName = (String) fieldsToSelect.get(i)
                    FieldInfo fi = ed.getFieldInfo(fieldName)
                    if (fi == null) {
                        FieldOrderOptions foo = new FieldOrderOptions(fieldName)
                        fi = ed.getFieldInfo(foo.fieldName)
                        if (fi == null) throw new EntityException("Field to select ${fieldName} not found in entity ${ed.getFullEntityName()}")

                        fieldInfoArray[fieldInfoArrayIndex] = fi
                        fieldOptionsArray[fieldInfoArrayIndex] = foo
                        fieldInfoArrayIndex++
                        hasFieldOptions = true
                    } else {
                        fieldInfoArray[fieldInfoArrayIndex] = fi
                        fieldInfoArrayIndex++
                    }
                }
                if (!hasFieldOptions) fieldOptionsArray = (FieldOrderOptions[]) null
                if (fieldOptionsArray == null && ftsSize == entityInfo.allFieldInfoArray.length)
                    fieldInfoArray = entityInfo.allFieldInfoArray
            }
            // logger.warn("fieldsToSelect: ${fieldsToSelect} fieldInfoArray: ${fieldInfoArray}")

            if (isViewEntity) {
                MNode entityConditionNode = ed.entityConditionNode
                if (entityConditionNode != null && "true".equals(entityConditionNode.attribute("distinct"))) this.distinct(true)
            }

            EntityConditionImplBase queryWhereCondition = whereCondition
            EntityConditionImplBase havingCondition = havingEntityCondition
            if (isViewEntity) {
                EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
                queryWhereCondition = EntityConditionFactoryImpl.makeConditionImpl(whereCondition, EntityCondition.AND, viewWhere)

                havingCondition = havingEntityCondition
                EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
                havingCondition = EntityConditionFactoryImpl.makeConditionImpl(havingCondition, EntityCondition.AND, viewHaving)
            }

            // call the abstract method
            try { count = countExtended(queryWhereCondition, havingCondition, fieldInfoArray, fieldOptionsArray) }
            catch (SQLException e) { throw new EntitySqlException(makeErrorMsg("Error finding count of", COUNT_ERROR, queryWhereCondition, ed, ec), e) }
            catch (Exception e) { throw new EntityException(makeErrorMsg("Error finding count of", COUNT_ERROR, queryWhereCondition, ed, ec), e) }

            if (doCache) entityCountCache.put(whereCondition, count)
        }

        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-count", false)

        return count
    }

    abstract long countExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
                                FieldInfo[] fieldInfoArray, FieldOrderOptions[] fieldOptionsArray) throws SQLException

    @Override
    long updateAll(Map<String, ?> fieldsToSet) {
        boolean enableAuthz = disableAuthz ? !efi.ecfi.getEci().artifactExecutionFacade.disableAuthz() : false
        try {
            return updateAllInternal(fieldsToSet)
        } finally {
            if (enableAuthz) efi.ecfi.getEci().artifactExecutionFacade.enableAuthz()
        }
    }
    protected long updateAllInternal(Map<String, ?> fieldsToSet) {
        // NOTE: this code isn't very efficient, but will do the trick and cause all EECAs to be fired
        // NOTE: consider expanding this to do a bulk update in the DB if there are no EECAs for the entity

        EntityDefinition ed = getEntityDef()
        if (ed.entityInfo.createOnly) throw new EntityException("Entity ${ed.getFullEntityName()} is create-only (immutable), cannot be updated.")

        this.useCache(false)
        long totalUpdated = 0
        EntityListIterator eli = (EntityListIterator) null
        try {
            eli = iterator()
            EntityValue value
            while ((value = eli.next()) != null) {
                value.putAll(fieldsToSet)
                if (value.isModified()) {
                    // NOTE: consider implement and use the eli.set(value) method to update within a ResultSet
                    value.update()
                    totalUpdated++
                }
            }
        } finally {
            if (eli != null) eli.close()
        }
        return totalUpdated
    }

    @Override
    long deleteAll() {
        boolean enableAuthz = disableAuthz ? !efi.ecfi.getEci().artifactExecutionFacade.disableAuthz() : false
        try {
            return deleteAllInternal()
        } finally {
            if (enableAuthz) efi.ecfi.getEci().artifactExecutionFacade.enableAuthz()
        }
    }
    protected long deleteAllInternal() {
        // NOTE: this code isn't very efficient (though eli.remove() is a little bit more), but will do the trick and cause all EECAs to be fired

        EntityDefinition ed = getEntityDef()
        if (ed.entityInfo.createOnly) throw new EntityException("Entity ${ed.getFullEntityName()} is create-only (immutable), cannot be deleted.")

        // if there are no EECAs for the entity OR there is a TransactionCache in place just call ev.delete() on each
        boolean useEvDelete = txCache != null || efi.hasEecaRules(ed.getFullEntityName())
        this.useCache(false)
        long totalDeleted = 0
        if (useEvDelete) {
            EntityList el = list()
            int elSize = el.size()
            for (int i = 0; i < elSize; i++) {
                EntityValue ev = (EntityValue) el.get(i)
                ev.delete()
                totalDeleted++
            }
        } else {
            this.resultSetConcurrency(ResultSet.CONCUR_UPDATABLE)
            EntityListIterator eli = (EntityListIterator) null
            try {
                eli = iterator()
                while (eli.next() != null) {
                    // no longer need to clear cache, eli.remove() does that
                    eli.remove()
                    totalDeleted++
                }
            } finally {
                if (eli != null) eli.close()
            }
        }
        return totalDeleted
    }

    @Override
    void extract(SimpleEtl etl) {
        EntityListIterator eli = iterator()
        try {
            EntityValue ev
            while ((ev = eli.next()) != null) {
                etl.processEntry(ev)
            }
        } catch (StopException e) {
            logger.warn("EntityFind extract stopped on: " + (e.getCause()?.toString() ?: e.toString()))
        } finally {
            eli.close()
        }
    }

    @Override
    ArrayList<String> getQueryTextList() { return queryTextList }
}
