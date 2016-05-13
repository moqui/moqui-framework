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
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.entity.*
import org.moqui.impl.StupidJavaUtilities
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.TransactionCache
import org.moqui.impl.context.TransactionFacadeImpl
import org.moqui.impl.entity.condition.*
import org.moqui.impl.entity.EntityJavaUtil.FieldInfo
import org.moqui.impl.entity.EntityJavaUtil.FieldOrderOptions
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache
import java.sql.ResultSet
import java.sql.Timestamp

@CompileStatic
abstract class EntityFindBase implements EntityFind {
    protected final static Logger logger = LoggerFactory.getLogger(EntityFindBase.class)

    protected final EntityFacadeImpl efi
    protected final TransactionCache txCache

    protected String entityName
    protected EntityDefinition entityDef = (EntityDefinition) null
    protected EntityDynamicViewImpl dynamicView = (EntityDynamicViewImpl) null

    protected Map<String, Object> simpleAndMap = (Map<String, Object>) null
    protected EntityConditionImplBase whereEntityCondition = (EntityConditionImplBase) null
    protected EntityConditionImplBase havingEntityCondition = (EntityConditionImplBase) null

    // always initialize this as it's always used in finds (even if populated with default of all fields)
    protected ArrayList<String> fieldsToSelect = new ArrayList<>()
    protected ArrayList<String> orderByFields = (ArrayList<String>) null

    protected Boolean useCache = (Boolean) null

    protected boolean distinct = false
    protected Integer offset = (Integer) null
    protected Integer limit = (Integer) null
    protected boolean forUpdate = false

    protected int resultSetType = ResultSet.TYPE_SCROLL_INSENSITIVE
    protected int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY
    protected Integer fetchSize = (Integer) null
    protected Integer maxRows = (Integer) null

    protected boolean disableAuthz = false

    protected String singleCondField = (String) null
    protected Object singleCondValue = null

    EntityFindBase(EntityFacadeImpl efi, String entityName) {
        this.efi = efi
        this.entityName = entityName
        TransactionFacadeImpl tfi = efi.getEcfi().getTransactionFacade()
        this.txCache = tfi.getTransactionCache()
        // if (!tfi.isTransactionInPlace()) logger.warn("No transaction in place, creating find for entity ${entityName}")
    }

    EntityFacadeImpl getEfi() { return efi }

    @Override
    EntityFind entity(String entityName) { this.entityName = entityName; return this }

    @Override
    String getEntity() { return this.entityName }

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
        if (operator == EntityCondition.EQUALS) return condition(fieldName, value)
        return condition(efi.conditionFactory.makeCondition(fieldName, operator, value))
    }
    @Override
    EntityFind condition(String fieldName, String operator, Object value) {
        EntityCondition.ComparisonOperator opObj = EntityConditionFactoryImpl.stringComparisonOperatorMap.get(operator)
        if (opObj == null) throw new IllegalArgumentException("Operator [${operator}] is not a valid field comparison operator")
        return condition(fieldName, opObj, value)
    }

    @Override
    EntityFind conditionToField(String fieldName, EntityCondition.ComparisonOperator operator, String toFieldName) {
        return condition(efi.conditionFactory.makeConditionToField(fieldName, operator, toFieldName))
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
            getEntityDef().setFields(fields, simpleAndMap, true, null, null)
        }
        return this
        /* maybe safer, not trying to do the single condition thing?
        if (fields == null || fields.size() == 0) return this;
        if (simpleAndMap == null) {
            simpleAndMap = new LinkedHashMap<String, Object>()
            if (singleCondField != null) {
                simpleAndMap.put(singleCondField, singleCondValue)
                singleCondField = (String) null
                singleCondValue = null
            }
        }
        getEntityDef().setFields(fields, simpleAndMap, true, null, null)
        return this
        */
    }

    @Override
    EntityFind condition(EntityCondition condition) {
        if (condition == null) return this

        Class condClass = condition.getClass()
        if (condClass == FieldValueCondition.class) {
            // if this is a basic field/value EQUALS condition, just add to simpleAndMap
            FieldValueCondition fvc = (FieldValueCondition) condition
            if (fvc.getOperator() == EntityCondition.EQUALS && !fvc.getIgnoreCase()) {
                this.condition(fvc.getFieldName(), fvc.getValue())
                return this
            }
        } else if (condClass == ListCondition.class) {
            ListCondition lc = (ListCondition) condition
            ArrayList<EntityConditionImplBase> condList = lc.getConditionList()
            // if empty list add nothing
            if (condList.size() == 0) return this
            // if this is an AND list condition, just unroll it and add each one; could end up as another list, but may add to simpleAndMap
            if (lc.getOperator() == EntityCondition.AND) {
                for (int i = 0; i < condList.size(); i++) this.condition(condList.get(i))
                return this
            }
        } else if (condClass == BasicJoinCondition.class) {
            BasicJoinCondition basicCond = (BasicJoinCondition) condition
            if (basicCond.getOperator() == EntityCondition.AND) {
                if (basicCond.getLhs() != null) this.condition(basicCond.getLhs())
                if (basicCond.getRhs() != null) this.condition(basicCond.getRhs())
                return this
            }
        } else if (condClass == MapCondition.class) {
            MapCondition mc = (MapCondition) condition
            if (mc.getJoinOperator() == EntityCondition.AND) {
                if (mc.getComparisonOperator() == EntityCondition.EQUALS && !mc.getIgnoreCase()) {
                    // simple AND Map, just add it
                    return this.condition(mc.fieldMap)
                } else {
                    // call back into this to break down the condition
                    return this.condition(mc.makeCondition())
                }
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
        condition(efi.conditionFactory.makeConditionDate(fromFieldName, thruFieldName, compareStamp))
        return this
    }

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
    EntityCondition getWhereEntityCondition() { return getWhereEntityConditionInternal() }
    EntityConditionImplBase getWhereEntityConditionInternal() {
        boolean wecNull = (whereEntityCondition == null)
        int samSize = simpleAndMap != null ? simpleAndMap.size() : 0

        EntityConditionImplBase singleCond = (EntityConditionImplBase) null
        if (singleCondField != null) {
            if (samSize > 0) logger.warn("simpleAndMap size ${samSize} and singleCondField not null!")
            singleCond = new FieldValueCondition(new ConditionField(singleCondField), EntityCondition.EQUALS, singleCondValue)
        }
        // special case, frequent operation: find by single key
        if (singleCond != null && wecNull && samSize == 0) return singleCond

        // see if we need to combine singleCond, simpleAndMap, and whereEntityCondition
        ArrayList<EntityConditionImplBase> condList = new ArrayList<EntityConditionImplBase>()
        if (singleCond != null) condList.add(singleCond)

        if (samSize > 0) {
            // create a ListCondition from the Map to allow for combination (simplification) with other conditions
            for (Map.Entry<String, Object> samEntry in simpleAndMap.entrySet())
                condList.add(new FieldValueCondition(new ConditionField((String) samEntry.key), EntityCondition.EQUALS, samEntry.value))
        }
        if (condList.size() > 0) {
            if (!wecNull) {
                Class whereEntCondClass = whereEntityCondition.getClass()
                if (whereEntCondClass == ListCondition.class) {
                    ListCondition listCond = (ListCondition) this.whereEntityCondition
                    if (listCond.getOperator() == EntityCondition.AND) {
                        condList.addAll(listCond.getConditionList())
                        return new ListCondition(condList, EntityCondition.AND)
                    } else {
                        condList.add(listCond)
                        return new ListCondition(condList, EntityCondition.AND)
                    }
                } else if (whereEntCondClass == MapCondition.class) {
                    MapCondition mapCond = (MapCondition) whereEntityCondition
                    if (mapCond.getJoinOperator() == EntityCondition.AND) {
                        mapCond.addToConditionList(condList)
                        return new ListCondition(condList, EntityCondition.AND)
                    } else {
                        condList.add(mapCond)
                        return new ListCondition(condList, EntityCondition.AND)
                    }
                } else if (whereEntCondClass == FieldValueCondition.class || whereEntCondClass == DateCondition.class ||
                        whereEntCondClass == FieldToFieldCondition.class) {
                    condList.add(whereEntityCondition)
                    return new ListCondition(condList, EntityCondition.AND)
                } else if (whereEntCondClass == BasicJoinCondition.class) {
                    BasicJoinCondition basicCond = (BasicJoinCondition) this.whereEntityCondition
                    if (basicCond.getOperator() == EntityCondition.AND) {
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
            if (StupidJavaUtilities.isEmpty(value)) return null
            pks.put(fieldName, value)
        }
        return pks
    }

    @Override
    EntityCondition getHavingEntityCondition() { return havingEntityCondition }

    @Override
    EntityFind searchFormInputs(String inputFieldsMapName, String defaultOrderBy, boolean alwaysPaginate) {
        ExecutionContextImpl ec = efi.getEcfi().getEci()
        Map inf = inputFieldsMapName ? (Map) ec.resource.expression(inputFieldsMapName, "") : ec.context
        return searchFormMap(inf, defaultOrderBy, alwaysPaginate)
    }

    @Override
    EntityFind searchFormMap(Map inf, String defaultOrderBy, boolean alwaysPaginate) {
        ExecutionContextImpl ec = efi.getEcfi().getEci()
        EntityDefinition ed = getEntityDef()

        // to avoid issues with entities that have cache=true, if no cache value is specified for this set it to false (avoids pagination errors, etc)
        if (useCache == null) useCache(false)

        if (inf != null && inf.size() > 0) for (String fn in ed.getAllFieldNames()) {
            // NOTE: do we need to do type conversion here?

            // this will handle text-find
            if (inf.containsKey(fn) || inf.containsKey(fn + "_op")) {
                Object value = inf.get(fn)
                String op = inf.get(fn + "_op") ?: "equals"
                boolean not = (inf.get(fn + "_not") == "Y" || inf.get(fn + "_not") == "true")
                boolean ic = (inf.get(fn + "_ic") == "Y" || inf.get(fn + "_ic") == "true")

                EntityCondition cond = null
                switch (op) {
                    case "equals":
                        if (value) {
                            Object convertedValue = value instanceof String ? ed.convertFieldString(fn, (String) value, ec) : value
                            cond = efi.conditionFactory.makeCondition(fn,
                                    not ? EntityCondition.NOT_EQUAL : EntityCondition.EQUALS, convertedValue)
                            if (ic) cond.ignoreCase()
                        }
                        break;
                    case "like":
                        if (value) {
                            cond = efi.conditionFactory.makeCondition(fn,
                                    not ? EntityCondition.NOT_LIKE : EntityCondition.LIKE, value)
                            if (ic) cond.ignoreCase()
                        }
                        break;
                    case "contains":
                        if (value) {
                            cond = efi.conditionFactory.makeCondition(fn,
                                    not ? EntityCondition.NOT_LIKE : EntityCondition.LIKE, "%${value}%")
                            if (ic) cond.ignoreCase()
                        }
                        break;
                    case "begins":
                        if (value) {
                            cond = efi.conditionFactory.makeCondition(fn,
                                    not ? EntityCondition.NOT_LIKE : EntityCondition.LIKE, "${value}%")
                            if (ic) cond.ignoreCase()
                        }
                        break;
                    case "empty":
                        cond = efi.conditionFactory.makeCondition(
                                efi.conditionFactory.makeCondition(fn,
                                        not ? EntityCondition.NOT_EQUAL : EntityCondition.EQUALS, null),
                                not ? EntityCondition.JoinOperator.AND : EntityCondition.JoinOperator.OR,
                                efi.conditionFactory.makeCondition(fn,
                                        not ? EntityCondition.NOT_EQUAL : EntityCondition.EQUALS, ""))
                        break;
                    case "in":
                        if (value) {
                            Collection valueList = null
                            if (value instanceof CharSequence) {
                                valueList = Arrays.asList(value.toString().split(","))
                            } else if (value instanceof Collection) {
                                valueList = (Collection) value
                            }
                            if (valueList) {
                                cond = efi.conditionFactory.makeCondition(fn,
                                        not ? EntityCondition.NOT_IN : EntityCondition.IN, valueList)

                            }
                        }
                        break;
                }
                if (cond != null) this.condition(cond)
            } else if (inf.get(fn + "_period")) {
                List<Timestamp> range = ec.user.getPeriodRange((String) inf.get(fn + "_period"), (String) inf.get(fn + "_poffset"))
                this.condition(efi.conditionFactory.makeCondition(fn,
                        EntityCondition.GREATER_THAN_EQUAL_TO, range.get(0)))
                this.condition(efi.conditionFactory.makeCondition(fn,
                        EntityCondition.LESS_THAN, range.get(1)))
            } else {
                // these will handle range-find and date-find
                Object fromValue = inf.get(fn + "_from")
                if (fromValue && fromValue instanceof CharSequence) fromValue = ed.convertFieldString(fn, fromValue.toString(), ec)
                Object thruValue = inf.get(fn + "_thru")
                if (thruValue && thruValue instanceof CharSequence) thruValue = ed.convertFieldString(fn, thruValue.toString(), ec)

                if (fromValue) this.condition(efi.conditionFactory.makeCondition(fn, EntityCondition.GREATER_THAN_EQUAL_TO, fromValue))
                if (thruValue) this.condition(efi.conditionFactory.makeCondition(fn, EntityCondition.LESS_THAN, thruValue))
            }
        }

        // always look for an orderByField parameter too
        String orderByString = inf?.get("orderByField") ?: defaultOrderBy
        if (orderByString != null && orderByString.length() > 0) {
            ec.context.put("orderByField", orderByString)
            this.orderBy(orderByString)
        }

        // look for the pageIndex and optional pageSize parameters; don't set these if should cache as will disable the cached query
        if ((alwaysPaginate || inf?.get("pageIndex") || inf?.get("pageSize")) && !shouldCache()) {
            int pageIndex = (inf?.get("pageIndex") ?: 0) as int
            int pageSize = (inf?.get("pageSize") ?: (this.limit ?: 20)) as int
            offset(pageIndex, pageSize)
            limit(pageSize)
        }

        // if there is a pageNoLimit clear out the limit regardless of other settings
        if (inf?.get("pageNoLimit") == "true" || inf?.get("pageNoLimit") == true) {
            this.offset = null
            this.limit = null
        }

        return this
    }

    EntityFind findNode(MNode node) {
        ExecutionContextImpl ec = efi.ecfi.getEci()

        this.entity(node.attribute('entity-name'))
        String cache = node.attribute('cache')
        if (cache) { this.useCache(cache == "true") }
        String forUpdate = node.attribute('for-update')
        if (forUpdate) this.forUpdate(forUpdate == "true")
        String distinct = node.attribute('distinct')
        if (distinct) this.distinct(distinct == "true")
        String offset = node.attribute('offset')
        if (offset) this.offset(offset as Integer)
        String limit = node.attribute('limit')
        if (limit) this.limit(limit as Integer)
        for (MNode sf in node.children("select-field")) this.selectField(sf.attribute("field-name"))
        for (MNode ob in node.children("order-by")) this.orderBy(ob.attribute("field-name"))

        // logger.warn("=== shouldCache ${this.entityName} ${shouldCache()}, limit=${this.limit}, offset=${this.offset}, useCache=${this.useCache}, getEntityDef().getUseCache()=${this.getEntityDef().getUseCache()}")
        if (!this.shouldCache()) {
            for (MNode df in node.children("date-filter"))
                this.condition(ec.entity.conditionFactory.makeConditionDate(df.attribute("from-field-name") ?: "fromDate",
                        df.attribute("thru-field-name") ?: "thruDate",
                        (df.attribute("valid-date") ? ec.resource.expression(df.attribute("valid-date"), null) as Timestamp : ec.user.nowTimestamp)))
        }

        for (MNode ecn in node.children("econdition")) {
            EntityCondition econd = ((EntityConditionFactoryImpl) efi.conditionFactory).makeActionCondition(ecn)
            if (econd != null) this.condition(econd)
        }
        for (MNode ecs in node.children("econditions"))
            this.condition(((EntityConditionFactoryImpl) efi.conditionFactory).makeActionConditions(ecs))
        for (MNode eco in node.children("econdition-object"))
            this.condition((EntityCondition) ec.resource.expression(eco.attribute("field"), null))

        if (node.hasChild("search-form-inputs")) {
            MNode sfiNode = node.first("search-form-inputs")
            searchFormInputs(sfiNode.attribute("input-fields-map"), sfiNode.attribute("default-order-by"), (sfiNode.attribute("paginate") ?: "true") as boolean)
        }
        if (node.hasChild("having-econditions")) {
            for (MNode havingCond in node.children("having-econditions"))
                this.havingCondition(((EntityConditionFactoryImpl) efi.conditionFactory).makeActionCondition(havingCond))
        }

        // logger.info("TOREMOVE Added findNode\n${node}\n${this.toString()}")

        return this
    }

    // ======================== General/Common Options ========================

    @Override
    EntityFind selectField(String fieldToSelect) {
        if (fieldToSelect == null || fieldToSelect.length() == 0) return this
        if (fieldToSelect.contains(",")) {
            for (String ftsPart in fieldToSelect.split(",")) {
                String selectName = ftsPart.trim()
                if (getEntityDef().isField(selectName)) this.fieldsToSelect.add(selectName)
            }
        } else {
            if (getEntityDef().isField(fieldToSelect)) this.fieldsToSelect.add(fieldToSelect)
        }
        return this
    }

    @Override
    EntityFind selectFields(Collection<String> fieldsToSelect) {
        if (!fieldsToSelect) return this
        for (String fieldToSelect in fieldsToSelect) selectField(fieldToSelect)
        return this
    }

    @Override
    List<String> getSelectFields() { return this.fieldsToSelect ? this.fieldsToSelect : null }

    @Override
    EntityFind orderBy(String orderByFieldName) {
        if (orderByFieldName == null || orderByFieldName.length() == 0) return this
        if (this.orderByFields == null) this.orderByFields = new ArrayList<>()
        if (orderByFieldName.contains(",")) {
            for (String obsPart in orderByFieldName.split(",")) {
                String orderByName = obsPart.trim()
                FieldOrderOptions foo = new FieldOrderOptions(orderByName)
                if (getEntityDef().isField(foo.fieldName)) this.orderByFields.add(orderByName)
            }
        } else {
            FieldOrderOptions foo = new FieldOrderOptions(orderByFieldName)
            if (getEntityDef().isField(foo.fieldName)) this.orderByFields.add(orderByFieldName)
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

    @Override
    List<String> getOrderBy() { return this.orderByFields != null ? Collections.unmodifiableList(this.orderByFields) : null }

    @Override
    EntityFind useCache(Boolean useCache) { this.useCache = useCache; return this }

    @Override
    boolean getUseCache() { return this.useCache }

    // ======================== Advanced Options ==============================

    @Override
    EntityFind distinct(boolean distinct) { this.distinct = distinct; return this }
    @Override
    boolean getDistinct() { return this.distinct }

    @Override
    EntityFind offset(Integer offset) { this.offset = offset; return this }
    @Override
    EntityFind offset(int pageIndex, int pageSize) { offset(pageIndex * pageSize) }
    @Override
    Integer getOffset() { return this.offset }

    @Override
    EntityFind limit(Integer limit) { this.limit = limit; return this }
    @Override
    Integer getLimit() { return this.limit }

    @Override
    int getPageIndex() { return offset == null ? 0 : (offset/getPageSize()).intValue() }
    @Override
    int getPageSize() { return limit != null ? limit : 20 }

    @Override
    EntityFind forUpdate(boolean forUpdate) { this.forUpdate = forUpdate; return this }
    @Override
    boolean getForUpdate() { return this.forUpdate }

    // ======================== JDBC Options ==============================

    @Override
    EntityFind resultSetType(int resultSetType) { this.resultSetType = resultSetType; return this }
    @Override
    int getResultSetType() { return this.resultSetType }

    @Override
    EntityFind resultSetConcurrency(int resultSetConcurrency) {
        this.resultSetConcurrency = resultSetConcurrency
        return this
    }
    @Override
    int getResultSetConcurrency() { return this.resultSetConcurrency }

    @Override
    EntityFind fetchSize(Integer fetchSize) { this.fetchSize = fetchSize; return this }
    @Override
    Integer getFetchSize() { return this.fetchSize }

    @Override
    EntityFind maxRows(Integer maxRows) { this.maxRows = maxRows; return this }
    @Override
    Integer getMaxRows() { return this.maxRows }

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

    public boolean shouldCache() {
        if (this.dynamicView != null) return false
        if (this.havingEntityCondition != null) return false
        if (this.limit != null || this.offset != null) return false
        if (this.useCache != null && this.useCache == Boolean.FALSE) return false
        String entityCache = this.getEntityDef().getUseCache()
        return ((this.useCache == Boolean.TRUE && entityCache != "never") || entityCache == "true")
    }

    @Override
    String toString() {
        return "Find: ${entityName} WHERE [${singleCondField?:''}:${singleCondValue?:''}] [${simpleAndMap}] [${whereEntityCondition}] HAVING [${havingEntityCondition}] " +
                "SELECT [${fieldsToSelect}] ORDER BY [${orderByFields}] CACHE [${useCache}] DISTINCT [${distinct}] " +
                "OFFSET [${offset}] LIMIT [${limit}] FOR UPDATE [${forUpdate}]"
    }

    // ======================== Find and Abstract Methods ========================

    EntityFind disableAuthz() { disableAuthz = true; return this }

    abstract EntityDynamicView makeEntityDynamicView()

    @Override
    EntityValue one() throws EntityException {
        ExecutionContextImpl ec = efi.getEcfi().getEci()
        ArtifactExecutionFacadeImpl aefi = ec.getArtifactExecutionImpl()
        boolean enableAuthz = disableAuthz ? !aefi.disableAuthz() : false
        try {
            EntityDefinition ed = getEntityDef()

            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW")
                    .setActionDetail("one")
            // really worth the overhead? if so change to handle singleCondField: .setParameters(simpleAndMap)
            aefi.pushInternal(aei, !ed.authorizeSkipView())

            try {
                return oneInternal(ec)
            } finally {
                // pop the ArtifactExecutionInfo
                aefi.pop(aei)
            }
        } finally {
            if (enableAuthz) aefi.enableAuthz()
        }
    }
    protected EntityValue oneInternal(ExecutionContextImpl ec) throws EntityException {
        if (this.dynamicView != null) throw new IllegalArgumentException("Dynamic View not supported for 'one' find.")

        long startTime = System.currentTimeMillis()
        long startTimeNanos = System.nanoTime()
        EntityDefinition ed = getEntityDef()
        MNode entityNode = ed.getEntityNode()

        if ('tenantcommon'.equals(ed.entityGroupName) && !'DEFAULT'.equals(efi.tenantId))
            throw new ArtifactAuthorizationException("Cannot view tenantcommon entities through tenant ${efi.tenantId}")

        if (ed.isViewEntity() && (!entityNode.hasChild("member-entity") || !entityNode.hasChild("alias")))
            throw new EntityException("Cannot do find for view-entity with name [${entityName}] because it has no member entities or no aliased fields.")

        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-one", true)

        // if over-constrained (anything in addition to a full PK), just use the full PK
        // NOTE: only do this if there is more than one field in the condition, ie optimize for common case of find by single PK field
        if (simpleAndMap != null && simpleAndMap.size() > 1 && ed.containsPrimaryKey(simpleAndMap))
            simpleAndMap = ed.getPrimaryKeys(simpleAndMap)

        // before combining conditions let ArtifactFacade add entity filters associated with authz
        ec.artifactExecutionImpl.filterFindForUser(this)

        EntityConditionImplBase whereCondition = getWhereEntityConditionInternal()

        // no condition means no condition/parameter set, so return null for find.one()
        if (whereCondition == null) return (EntityValue) null

        // try the TX cache before the entity cache, may be more up-to-date
        EntityValueBase txcValue = txCache != null ? txCache.oneGet(this) : (EntityValueBase) null

        // if (txcValue != null && ed.getEntityName() == "foo") logger.warn("========= TX cache one value: ${txcValue}")

        boolean doCache = shouldCache()
        Cache<EntityCondition, EntityValueBase> entityOneCache = doCache ?
                ed.getCacheOne(efi.getEntityCache()) : (Cache<EntityCondition, EntityValueBase>) null
        EntityValueBase cacheHit = (EntityValueBase) null
        if (doCache && txcValue == null && whereCondition != null) cacheHit = (EntityValueBase) entityOneCache.get(whereCondition)

        // we always want fieldsToSelect populated so that we know the order of the results coming back
        ArrayList<String> localFts = fieldsToSelect
        int ftsSize = localFts.size()
        ArrayList<FieldInfo> fieldInfoList
        ArrayList<FieldOrderOptions> fieldOptionsList = (ArrayList<FieldOrderOptions>) null
        if (ftsSize == 0 || (txCache != null && txcValue == null) || (doCache && cacheHit == null)) {
            fieldInfoList = ed.getAllFieldInfoList()
        } else {
            fieldInfoList = new ArrayList<>(ftsSize)
            fieldOptionsList = new ArrayList<>(ftsSize)
            for (int i = 0; i < ftsSize; i++) {
                String fieldName = (String) localFts.get(i)
                FieldInfo fi = ed.getFieldInfo(fieldName)
                if (fi == null) {
                    FieldOrderOptions foo = new FieldOrderOptions(fieldName)
                    fi = ed.getFieldInfo(foo.fieldName)
                    if (fi == null) throw new EntityException("Field to select ${fieldName} not found in entity ${ed.getFullEntityName()}")

                    fieldInfoList.add(fi)
                    fieldOptionsList.add(foo)
                } else {
                    fieldInfoList.add(fi)
                    fieldOptionsList.add(null)
                }
            }
        }

        // if (ed.getEntityName() == "Asset") logger.warn("=========== find one of Asset ${this.simpleAndMap.get('assetId')}", new Exception("Location"))

        // call the abstract method
        EntityValueBase newEntityValue = (EntityValueBase) null
        if (txcValue != null) {
            if (txcValue instanceof TransactionCache.DeletedEntityValue) {
                // is deleted value, so leave newEntityValue as null
                // put in cache as null since this was deleted
                if (doCache && whereCondition != null) efi.getEntityCache().putInOneCache(ed, whereCondition, null, entityOneCache)
            } else {
                // if forUpdate unless this was a TX CREATE it'll be in the DB and should be locked, so do the query
                //     anyway, but ignore the result
                if (forUpdate && !txCache.isTxCreate(txcValue)) {
                    oneExtended(getConditionForQuery(ed, whereCondition), fieldInfoList, fieldOptionsList)
                    // if (ed.getEntityName() == "Asset") logger.warn("======== doing find and ignoring result to pass through for update, for: ${txcValue}")
                }
                newEntityValue = txcValue
            }
        } else if (cacheHit != null) {
            if (cacheHit instanceof EntityCache.EmptyRecord) newEntityValue = (EntityValueBase) null
            else newEntityValue = cacheHit
        } else {
            // for find one we'll always use the basic result set type and concurrency:
            this.resultSetType(ResultSet.TYPE_FORWARD_ONLY)
            this.resultSetConcurrency(ResultSet.CONCUR_READ_ONLY)

            // TODO: this will not handle query conditions on UserFields, it will blow up in fact

            newEntityValue = oneExtended(getConditionForQuery(ed, whereCondition), fieldInfoList, fieldOptionsList)

            // it didn't come from the txCache so put it there
            if (txCache != null) txCache.onePut(newEntityValue)

            // put it in whether null or not (already know cacheHit is null)
            if (doCache) efi.getEntityCache().putInOneCache(ed, whereCondition, newEntityValue, entityOneCache)
        }

        // if (logger.traceEnabled) logger.trace("Find one on entity [${ed.fullEntityName}] with condition [${whereCondition}] found value [${newEntityValue}]")

        // final ECA trigger
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), newEntityValue, "find-one", false)
        // count the artifact hit
        // NOTE: passing simpleAndMap doesn't handle singleCondField, but not worth the overhead
        efi.ecfi.countArtifactHit("entity", "one", ed.getFullEntityName(), simpleAndMap, startTime,
                (System.nanoTime() - startTimeNanos)/1E6, newEntityValue ? 1L : 0L)

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
    abstract EntityValueBase oneExtended(EntityConditionImplBase whereCondition, ArrayList<FieldInfo> fieldInfoList,
                                         ArrayList<FieldOrderOptions> fieldOptionsList) throws EntityException

    @Override
    Map<String, Object> oneMaster(String name) {
        ExecutionContextImpl ec = efi.getEcfi().getEci()
        ArtifactExecutionFacadeImpl aefi = ec.getArtifactExecutionImpl()
        boolean enableAuthz = disableAuthz ? !aefi.disableAuthz() : false
        try {
            EntityDefinition ed = getEntityDef()

            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW").setActionDetail("one")
            aefi.pushInternal(aei, !ed.authorizeSkipView())

            try {
                EntityValue ev = oneInternal(ec)
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

    @Override
    EntityList list() throws EntityException {
        ExecutionContextImpl ec = efi.getEcfi().getEci()
        ArtifactExecutionFacadeImpl aefi = ec.getArtifactExecutionImpl()
        boolean enableAuthz = disableAuthz ? !aefi.disableAuthz() : false
        try {
            EntityDefinition ed = getEntityDef()

            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW").setActionDetail("list")
            aefi.pushInternal(aei, !ed.authorizeSkipView())
            try { return listInternal(ec) } finally { aefi.pop(aei) }
        } finally {
            if (enableAuthz) aefi.enableAuthz()
        }
    }
    protected EntityList listInternal(ExecutionContextImpl ec) throws EntityException {
        long startTime = System.currentTimeMillis()
        long startTimeNanos = System.nanoTime()
        EntityDefinition ed = getEntityDef()
        MNode entityNode = ed.getEntityNode()

        if ('tenantcommon'.equals(ed.entityGroupName) && !'DEFAULT'.equals(efi.tenantId))
            throw new ArtifactAuthorizationException("Cannot view tenantcommon entities through tenant ${efi.tenantId}")

        if (ed.isViewEntity() && (!entityNode.hasChild("member-entity") || !entityNode.hasChild("alias")))
            throw new EntityException("Cannot do find for view-entity with name [${entityName}] because it has no member entities or no aliased fields.")

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-list", true)

        ArrayList<String> orderByExpanded = new ArrayList()
        // add the manually specified ones, then the ones in the view entity's entity-condition
        if (orderByFields != null) orderByExpanded.addAll(orderByFields)

        MNode entityConditionNode = ed.getEntityConditionNode()
        if (entityConditionNode != null) {
            ArrayList<MNode> ecObList = entityConditionNode.children("order-by")
            if (ecObList != null) for (int i = 0; i < ecObList.size(); i++) {
                MNode orderBy = (MNode) ecObList.get(i)
                orderByExpanded.add(orderBy.attribute('field-name'))
            }
            if ("true".equals(entityConditionNode.attribute('distinct'))) this.distinct(true)
        }

        // before combining conditions let ArtifactFacade add entity filters associated with authz
        ec.artifactExecutionImpl.filterFindForUser(this)

        EntityConditionImplBase whereCondition = getWhereEntityConditionInternal()

        // try the txCache first, more recent than general cache (and for update general cache entries will be cleared anyway)
        EntityListImpl txcEli = txCache != null ? txCache.listGet(ed, whereCondition, orderByExpanded) : (EntityListImpl) null

        // NOTE: don't cache if there is a having condition, for now just support where
        boolean doEntityCache = shouldCache()
        Cache<EntityCondition, EntityListImpl> entityListCache = doEntityCache ?
                ed.getCacheList(efi.getEntityCache()) : (Cache<EntityCondition, EntityListImpl>) null
        EntityListImpl cacheList = (EntityListImpl) null
        if (doEntityCache) cacheList = efi.getEntityCache().getFromListCache(ed, whereCondition, orderByExpanded, entityListCache)

        EntityListImpl el
        if (txcEli != null) {
            el = txcEli
            // if (ed.getFullEntityName().contains("OrderItem")) logger.warn("======== Got OrderItem from txCache ${el.size()} results where: ${whereCondition}")
        } else if (cacheList != null) {
            el = cacheList
        } else {
            ArrayList<String> localFts = fieldsToSelect
            // order by fields need to be selected (at least on some databases, Derby is one of them)
            int orderByExpandedSize = orderByExpanded.size()
            if (getDistinct() && localFts.size() > 0 && orderByExpandedSize > 0) {
                for (int i = 0; i < orderByExpandedSize; i++) {
                    String orderByField = (String) orderByExpanded.get(i)
                    //EntityQueryBuilder.FieldOrderOptions foo = new EntityQueryBuilder.FieldOrderOptions(orderByField)
                    //localFts.add(foo.fieldName)
                    localFts.add(orderByField)
                }
            }

            // we always want fieldsToSelect populated so that we know the order of the results coming back
            int ftsSize = localFts.size()
            ArrayList<FieldInfo> fieldInfoList
            ArrayList<FieldOrderOptions> fieldOptionsList = (ArrayList<FieldOrderOptions>) null
            if (ftsSize == 0 || doEntityCache) {
                fieldInfoList = ed.getAllFieldInfoList()
            } else {
                fieldInfoList = new ArrayList<>(ftsSize)
                fieldOptionsList = new ArrayList<>(ftsSize)
                for (int i = 0; i < ftsSize; i++) {
                    String fieldName = (String) localFts.get(i)
                    FieldInfo fi = (FieldInfo) ed.getFieldInfo(fieldName)
                    if (fi == null) {
                        FieldOrderOptions foo = new FieldOrderOptions(fieldName)
                        fi = ed.getFieldInfo(foo.fieldName)
                        if (fi == null) throw new EntityException("Field to select ${fieldName} not found in entity ${ed.getFullEntityName()}")

                        fieldInfoList.add(fi)
                        fieldOptionsList.add(foo)
                    } else {
                        fieldInfoList.add(fi)
                        fieldOptionsList.add(null)
                    }
                }
            }

            // TODO: this will not handle query conditions on UserFields, it will blow up in fact

            EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
            whereCondition = (EntityConditionImplBase) efi.getConditionFactoryImpl()
                    .makeConditionImpl(whereCondition, EntityCondition.AND, viewWhere)

            EntityConditionImplBase havingCondition = havingEntityCondition
            EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
            havingCondition = efi.getConditionFactoryImpl()
                    .makeConditionImpl(havingCondition, EntityCondition.AND, viewHaving)

            // call the abstract method
            EntityListIterator eli = iteratorExtended(whereCondition, havingCondition, orderByExpanded,
                    fieldInfoList, fieldOptionsList)
            // these are used by the TransactionCache methods to augment the resulting list and maintain the sort order
            eli.setQueryCondition(whereCondition)
            eli.setOrderByFields(orderByExpanded)

            MNode databaseNode = this.efi.getDatabaseNode(ed.getEntityGroupName())
            if (limit != null && databaseNode != null && "cursor".equals(databaseNode.attribute('offset-style'))) {
                el = (EntityListImpl) eli.getPartialList(offset != null ? offset : 0, limit, true)
            } else {
                el = (EntityListImpl) eli.getCompleteList(true)
            }

            if (txCache != null && ftsSize == 0) txCache.listPut(ed, whereCondition, el)
            if (doEntityCache) efi.getEntityCache().putInListCache(ed, el, whereCondition, entityListCache)

            // if (ed.getFullEntityName().contains("OrderItem")) logger.warn("======== Got OrderItem from DATABASE ${el.size()} results where: ${whereCondition}")
            // logger.warn("======== Got ${ed.getFullEntityName()} from DATABASE ${el.size()} results where: ${whereCondition}")
        }

        // run the final rules
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-list", false)
        // count the artifact hit
        // NOTE: passing simpleAndMap doesn't handle singleCondField, but not worth the overhead
        efi.ecfi.countArtifactHit("entity", "list", ed.getFullEntityName(), simpleAndMap, startTime,
                (System.nanoTime() - startTimeNanos)/1E6, el != null ? (long) el.size() : 0L)

        return el
    }

    @Override
    List<Map<String, Object>> listMaster(String name) {
        ExecutionContextImpl ec = efi.getEcfi().getEci()
        ArtifactExecutionFacadeImpl aefi = ec.getArtifactExecutionImpl()
        boolean enableAuthz = disableAuthz ? !aefi.disableAuthz() : false
        try {
            EntityDefinition ed = getEntityDef()

            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW").setActionDetail("list")
            aefi.pushInternal(aei, !ed.authorizeSkipView())
            try {
                EntityList el = listInternal(ec)
                return el.getMasterValueList(name)
            } finally {
                // pop the ArtifactExecutionInfo
                aefi.pop(aei)
            }
        } finally {
            if (enableAuthz) aefi.enableAuthz()
        }
    }

    @Override
    EntityListIterator iterator() throws EntityException {
        ExecutionContextImpl ec = efi.getEcfi().getEci()
        boolean enableAuthz = disableAuthz ? !ec.getArtifactExecutionImpl().disableAuthz() : false
        try {
            return iteratorInternal(ec)
        } finally {
            if (enableAuthz) ec.getArtifactExecutionImpl().enableAuthz()
        }
    }
    protected EntityListIterator iteratorInternal(ExecutionContextImpl ec) throws EntityException {
        long startTime = System.currentTimeMillis()
        long startTimeNanos = System.nanoTime()
        EntityDefinition ed = getEntityDef()
        MNode entityNode = ed.getEntityNode()
        ArtifactExecutionFacadeImpl aefi = ec.getArtifactExecutionImpl()

        if ('tenantcommon'.equals(ed.entityGroupName) && !'DEFAULT'.equals(efi.tenantId))
            throw new ArtifactAuthorizationException("Cannot view tenantcommon entities through tenant ${efi.tenantId}")

        if (ed.isViewEntity() && (!entityNode.hasChild("member-entity") || !entityNode.hasChild("alias")))
            throw new EntityException("Cannot do find for view-entity with name [${entityName}] because it has no member entities or no aliased fields.")

        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW").setActionDetail("iterator")
        aefi.pushInternal(aei, !ed.authorizeSkipView())

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-iterator", true)

        ArrayList<String> orderByExpanded = new ArrayList()
        // add the manually specified ones, then the ones in the view entity's entity-condition
        if (this.orderByFields != null) orderByExpanded.addAll(this.orderByFields)

        MNode entityConditionNode = ed.getEntityConditionNode()
        if (entityConditionNode != null) {
            ArrayList<MNode> ecObList = entityConditionNode.children("order-by")
            if (ecObList != null) for (int i = 0; i < ecObList.size(); i++) {
                MNode orderBy = ecObList.get(i)
                orderByExpanded.add(orderBy.attribute('field-name'))
            }
            if (entityConditionNode.attribute('distinct') == "true") this.distinct(true)
        }

        ArrayList<String> localFts = fieldsToSelect
        // order by fields need to be selected (at least on some databases, Derby is one of them)
        if (getDistinct() && localFts.size() > 0 && orderByExpanded.size() > 0) {
            for (String orderByField in orderByExpanded) {
                //EntityFindBuilder.FieldOrderOptions foo = new EntityFindBuilder.FieldOrderOptions(orderByField)
                //fieldsToSelect.add(foo.fieldName)
                localFts.add(orderByField)
            }
        }

        // we always want fieldsToSelect populated so that we know the order of the results coming back
        int ftsSize = localFts.size()
        ArrayList<FieldInfo> fieldInfoList
        ArrayList<FieldOrderOptions> fieldOptionsList = (ArrayList<FieldOrderOptions>) null
        if (ftsSize == 0) {
            fieldInfoList = ed.getAllFieldInfoList()
        } else {
            fieldInfoList = new ArrayList<>(ftsSize)
            fieldOptionsList = new ArrayList<>(ftsSize)
            for (int i = 0; i < ftsSize; i++) {
                String fieldName = (String) localFts.get(i)
                FieldInfo fi = ed.getFieldInfo(fieldName)
                if (fi == null) {
                    FieldOrderOptions foo = new FieldOrderOptions(fieldName)
                    fi = ed.getFieldInfo(foo.fieldName)
                    if (fi == null) throw new EntityException("Field to select ${fieldName} not found in entity ${ed.getFullEntityName()}")

                    fieldInfoList.add(fi)
                    fieldOptionsList.add(foo)
                } else {
                    fieldInfoList.add(fi)
                    fieldOptionsList.add(null)
                }
            }
        }

        // TODO: this will not handle query conditions on UserFields, it will blow up in fact

        // before combining conditions let ArtifactFacade add entity filters associated with authz
        aefi.filterFindForUser(this)

        EntityConditionImplBase whereCondition = getWhereEntityConditionInternal()
        EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
        whereCondition = (EntityConditionImplBase) efi.getConditionFactory()
                .makeCondition(whereCondition, EntityCondition.AND, viewWhere)

        EntityConditionImplBase havingCondition = (EntityConditionImplBase) getHavingEntityCondition()
        EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
        havingCondition = (EntityConditionImplBase) efi.getConditionFactory()
                .makeCondition(havingCondition, EntityCondition.AND, viewHaving)

        // call the abstract method
        EntityListIterator eli = iteratorExtended(whereCondition, havingCondition, orderByExpanded, fieldInfoList, fieldOptionsList)
        eli.setQueryCondition(whereCondition)
        eli.setOrderByFields(orderByExpanded)

        // NOTE: if we are doing offset/limit with a cursor no good way to limit results, but we'll at least jump to the offset
        MNode databaseNode = this.efi.getDatabaseNode(ed.getEntityGroupName())
        // NOTE: allow databaseNode to be null because custom (non-JDBC) datasources may not have one
        if (this.offset != null && databaseNode != null && "cursor".equals(databaseNode.attribute('offset-style'))) {
            if (!eli.absolute(offset)) {
                // can't seek to desired offset? not enough results, just go to after last result
                eli.afterLast()
            }
        }

        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-iterator", false)
        // count the artifact hit
        // NOTE: passing simpleAndMap doesn't handle singleCondField, but not worth the overhead
        efi.ecfi.countArtifactHit("entity", "iterator", ed.getFullEntityName(), simpleAndMap, startTime,
                (System.nanoTime() - startTimeNanos)/1E6, null)
        // pop the ArtifactExecutionInfo
        aefi.pop(aei)

        return eli
    }

    abstract EntityListIterator iteratorExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
                                                 ArrayList<String> orderByExpanded, ArrayList<FieldInfo> fieldInfoList,
                                                 ArrayList<FieldOrderOptions> fieldOptionsList)

    @Override
    long count() throws EntityException {
        ExecutionContextImpl ec = efi.getEcfi().getEci()
        boolean enableAuthz = disableAuthz ? !ec.getArtifactExecutionImpl().disableAuthz() : false
        try {
            return countInternal(ec)
        } finally {
            if (enableAuthz) ec.getArtifactExecutionImpl().enableAuthz()
        }
    }
    protected long countInternal(ExecutionContextImpl ec) throws EntityException {
        long startTime = System.currentTimeMillis()
        long startTimeNanos = System.nanoTime()
        EntityDefinition ed = getEntityDef()
        ArtifactExecutionFacadeImpl aefi = ec.getArtifactExecutionImpl()

        if ('tenantcommon'.equals(ed.entityGroupName) && !'DEFAULT'.equals(efi.tenantId))
            throw new ArtifactAuthorizationException("Cannot view tenantcommon entities through tenant ${efi.tenantId}")

        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW").setActionDetail("count")
        aefi.pushInternal(aei, !ed.authorizeSkipView())

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-count", true)

        // before combining conditions let ArtifactFacade add entity filters associated with authz
        aefi.filterFindForUser(this)

        EntityConditionImplBase whereCondition = getWhereEntityConditionInternal()
        // NOTE: don't cache if there is a having condition, for now just support where
        boolean doCache = !this.havingEntityCondition && this.shouldCache()
        Cache<EntityCondition, Long> entityCountCache = doCache ?
                getEntityDef().getCacheCount(efi.getEntityCache()) : (Cache) null
        Long cacheCount = (Long) null
        if (doCache && whereCondition != null) cacheCount = (Long) entityCountCache.get(whereCondition)

        long count
        if (cacheCount != null) {
            count = cacheCount
        } else {
            ArrayList<String> localFts = fieldsToSelect
            // select all pk and nonpk fields to match what list() or iterator() would do
            int ftsSize = localFts.size()
            ArrayList<FieldInfo> fieldInfoList
            ArrayList<FieldOrderOptions> fieldOptionsList = (ArrayList<FieldOrderOptions>) null
            if (ftsSize == 0) {
                fieldInfoList = ed.getAllFieldInfoList()
            } else {
                fieldInfoList = new ArrayList<>(ftsSize)
                fieldOptionsList = new ArrayList<>(ftsSize)
                for (int i = 0; i < ftsSize; i++) {
                    String fieldName = (String) localFts.get(i)
                    FieldInfo fi = ed.getFieldInfo(fieldName)
                    if (fi == null) {
                        FieldOrderOptions foo = new FieldOrderOptions(fieldName)
                        fi = ed.getFieldInfo(foo.fieldName)
                        if (fi == null) throw new EntityException("Field to select ${fieldName} not found in entity ${ed.getFullEntityName()}")

                        fieldInfoList.add(fi)
                        fieldOptionsList.add(foo)
                    } else {
                        fieldInfoList.add(fi)
                        fieldOptionsList.add(null)
                    }
                }
            }

            // TODO: this will not handle query conditions on UserFields, it will blow up in fact

            MNode entityConditionNode = ed.getEntityConditionNode()
            if (entityConditionNode != null) if ("true".equals(entityConditionNode.attribute('distinct'))) this.distinct(true)

            EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
            whereCondition = (EntityConditionImplBase) efi.getConditionFactory()
                    .makeCondition(whereCondition, EntityCondition.AND, viewWhere)

            EntityConditionImplBase havingCondition = (EntityConditionImplBase) getHavingEntityCondition()
            EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
            havingCondition = (EntityConditionImplBase) efi.getConditionFactory()
                    .makeCondition(havingCondition, EntityCondition.AND, viewHaving)

            // call the abstract method
            count = countExtended(whereCondition, havingCondition, fieldInfoList, fieldOptionsList)

            if (doCache && whereCondition != null) entityCountCache.put(whereCondition, count)
        }

        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-count", false)
        // count the artifact hit
        // NOTE: passing simpleAndMap doesn't handle singleCondField, but not worth the overhead
        efi.ecfi.countArtifactHit("entity", "count", ed.getFullEntityName(), simpleAndMap, startTime,
                (System.nanoTime() - startTimeNanos)/1E6, count)
        // pop the ArtifactExecutionInfo
        aefi.pop(aei)

        return count
    }

    abstract long countExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
                                ArrayList<FieldInfo> fieldInfoList, ArrayList<FieldOrderOptions> fieldOptionsList) throws EntityException

    @Override
    long updateAll(Map<String, ?> fieldsToSet) {
        boolean enableAuthz = disableAuthz ? !efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz() : false
        try {
            return updateAllInternal(fieldsToSet)
        } finally {
            if (enableAuthz) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
        }
    }
    protected long updateAllInternal(Map<String, ?> fieldsToSet) {
        // NOTE: this code isn't very efficient, but will do the trick and cause all EECAs to be fired
        // NOTE: consider expanding this to do a bulk update in the DB if there are no EECAs for the entity

        EntityDefinition ed = getEntityDef()
        if (ed.createOnly()) throw new EntityException("Entity [${getEntityDef().getFullEntityName()}] is create-only (immutable), cannot be updated.")
        if ('tenantcommon'.equals(ed.entityGroupName) && !'DEFAULT'.equals(efi.tenantId))
            throw new ArtifactAuthorizationException("Cannot update tenantcommon entities through tenant ${efi.ecfi.eci.tenantId}")

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
        boolean enableAuthz = disableAuthz ? !efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz() : false
        try {
            return deleteAllInternal()
        } finally {
            if (enableAuthz) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
        }
    }
    protected long deleteAllInternal() {
        // NOTE: this code isn't very efficient (though eli.remove() is a little bit more), but will do the trick and cause all EECAs to be fired

        EntityDefinition ed = getEntityDef()
        if (ed.createOnly()) throw new EntityException("Entity [${getEntityDef().getFullEntityName()}] is create-only (immutable), cannot be deleted.")
        if ('tenantcommon'.equals(ed.entityGroupName) && !'DEFAULT'.equals(efi.tenantId))
            throw new ArtifactAuthorizationException("Cannot update tenantcommon entities through tenant ${efi.ecfi.eci.tenantId}")

        // if there are no EECAs for the entity OR there is a TransactionCache in place just call ev.delete() on each
        boolean useEvDelete = txCache != null || efi.hasEecaRules(this.getEntityDef().getFullEntityName())
        if (!useEvDelete) this.resultSetConcurrency(ResultSet.CONCUR_UPDATABLE)
        this.useCache(false)
        EntityListIterator eli = (EntityListIterator) null
        long totalDeleted = 0
        try {
            eli = iterator()
            EntityValue ev
            while ((ev = eli.next()) != null) {
                if (useEvDelete) {
                    ev.delete()
                } else {
                    // not longer need to clear cache, eli.remote() does that
                    eli.remove()
                }
                totalDeleted++
            }
        } finally {
            if (eli != null) eli.close()
        }
        return totalDeleted
    }
}
