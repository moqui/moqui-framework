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
import groovy.transform.TypeChecked
import groovy.transform.TypeCheckingMode
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext
import org.moqui.entity.*
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.CacheImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.TransactionCache
import org.moqui.impl.entity.condition.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.ResultSet
import java.sql.Timestamp

@CompileStatic
abstract class EntityFindBase implements EntityFind {
    protected final static Logger logger = LoggerFactory.getLogger(EntityFindBase.class)

    protected final EntityFacadeImpl efi
    protected final TransactionCache txCache

    protected String entityName
    protected EntityDefinition entityDef = null
    protected EntityDynamicViewImpl dynamicView = null

    protected Map<String, Object> simpleAndMap = null
    protected EntityConditionImplBase whereEntityCondition = null
    protected EntityConditionImplBase havingEntityCondition = null

    protected ArrayList<String> fieldsToSelect = null
    protected List<String> orderByFields = null

    protected Boolean useCache = null

    protected boolean distinct = false
    protected Integer offset = null
    protected Integer limit = null
    protected boolean forUpdate = false

    protected int resultSetType = ResultSet.TYPE_SCROLL_INSENSITIVE
    protected int resultSetConcurrency = ResultSet.CONCUR_READ_ONLY
    protected Integer fetchSize = null
    protected Integer maxRows = null

    protected boolean disableAuthz = false

    EntityFindBase(EntityFacadeImpl efi, String entityName) {
        this.efi = efi
        this.entityName = entityName
        this.txCache = (TransactionCache) efi.getEcfi().getTransactionFacade().getActiveSynchronization("TransactionCache")
    }

    EntityFacadeImpl getEfi() { return efi }

    @Override
    EntityFind entity(String entityName) { this.entityName = entityName; return this }

    @Override
    String getEntity() { return this.entityName }

    // ======================== Conditions (Where and Having) =================

    @Override
    EntityFind condition(String fieldName, Object value) {
        if (!this.simpleAndMap) this.simpleAndMap = new LinkedHashMap()
        this.simpleAndMap.put(fieldName, value)
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
        return condition(efi.conditionFactory.makeCondition(fieldName, opObj, value))
    }

    @Override
    EntityFind conditionToField(String fieldName, EntityCondition.ComparisonOperator operator, String toFieldName) {
        return condition(efi.conditionFactory.makeConditionToField(fieldName, operator, toFieldName))
    }

    @Override
    EntityFind condition(Map<String, Object> fields) {
        if (!fields) return this
        if (this.simpleAndMap == null) this.simpleAndMap = new HashMap<String, Object>()
        getEntityDef().setFields(fields, this.simpleAndMap, true, null, null)
        return this
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

        if (whereEntityCondition) {
            // use ListCondition instead of ANDing two at a time to avoid a bunch of nested ANDs
            if (whereEntityCondition instanceof ListCondition &&
                    ((ListCondition) whereEntityCondition).getOperator() == EntityCondition.AND) {
                ((ListCondition) whereEntityCondition).addCondition((EntityConditionImplBase) condition)
            } else {
                ArrayList<EntityConditionImplBase> condList = new ArrayList()
                condList.add(whereEntityCondition)
                condList.add((EntityConditionImplBase) condition)
                whereEntityCondition = new ListCondition(efi.conditionFactoryImpl, condList, EntityCondition.AND)
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
        if (!condition) return this
        if (havingEntityCondition) {
            // use ListCondition instead of ANDing two at a time to avoid a bunch of nested ANDs
            if (havingEntityCondition instanceof ListCondition) {
                ((ListCondition) havingEntityCondition).addCondition((EntityConditionImplBase) condition)
            } else {
                ArrayList<EntityConditionImplBase> condList = new ArrayList()
                condList.add(havingEntityCondition)
                condList.add((EntityConditionImplBase) condition)
                havingEntityCondition = new ListCondition(efi.conditionFactoryImpl, condList, EntityCondition.AND)
            }
        } else {
            havingEntityCondition = (EntityConditionImplBase) condition
        }
        return this
    }

    @Override
    EntityCondition getWhereEntityCondition() {
        if (this.simpleAndMap) {
            EntityConditionImplBase simpleAndMapCond = this.efi.conditionFactoryImpl
                    .makeCondition(this.simpleAndMap, EntityCondition.EQUALS, EntityCondition.AND, null, null, false)

            if (this.whereEntityCondition != null) {
                ListCondition listCondition
                Class simpleAndCondClass = simpleAndMapCond.getClass()
                if (simpleAndCondClass == FieldValueCondition.class) {
                    ArrayList<EntityConditionImplBase> oneCondList = new ArrayList()
                    oneCondList.add(simpleAndMapCond)
                    listCondition = new ListCondition(efi.conditionFactoryImpl, oneCondList, EntityCondition.AND)
                } else if (simpleAndCondClass == ListCondition.class) {
                    listCondition = (ListCondition) simpleAndMapCond
                } else {
                    // this should never happen, based on impl of makeCondition(Map) should always be FieldValue or List
                    throw new EntityException("Condition for simpleAndMap is not a FieldValueCondition or ListCondition, is ${simpleAndCondClass.getName()}")
                }

                Class whereEntCondClass = this.whereEntityCondition.getClass()
                if (whereEntCondClass == ListCondition.class) {
                    ListCondition listCond = (ListCondition) this.whereEntityCondition
                    if (listCond.getOperator() == EntityCondition.AND) {
                        listCondition.addConditions(listCond)
                        return listCondition
                    } else {
                        listCondition.addCondition(listCond)
                        return listCondition
                    }
                } else if (whereEntCondClass == MapCondition.class) {
                    MapCondition mapCond = (MapCondition) this.whereEntityCondition
                    if (mapCond.getJoinOperator() == EntityCondition.AND) {
                        listCondition.addConditions(mapCond.makeCondition())
                        return listCondition
                    } else {
                        listCondition.addCondition(mapCond)
                        return listCondition
                    }
                } else if (whereEntCondClass == FieldValueCondition.class || whereEntCondClass == DateCondition.class ||
                        whereEntCondClass == FieldToFieldCondition.class) {
                    listCondition.addCondition(this.whereEntityCondition)
                    return listCondition
                } else if (whereEntCondClass == BasicJoinCondition.class) {
                    BasicJoinCondition basicCond = (BasicJoinCondition) this.whereEntityCondition
                    if (basicCond.getOperator() == EntityCondition.AND) {
                        listCondition.addCondition(basicCond.getLhs())
                        listCondition.addCondition(basicCond.getRhs())
                        return listCondition
                    } else {
                        listCondition.addCondition(basicCond)
                        return listCondition
                    }
                } else {
                    listCondition.addCondition(this.whereEntityCondition)
                    return listCondition
                }
            } else {
                return simpleAndMapCond
            }
        } else {
            return this.whereEntityCondition
        }
    }

    @Override
    EntityCondition getHavingEntityCondition() {
        return this.havingEntityCondition
    }

    @Override
    EntityFind searchFormInputs(String inputFieldsMapName, String defaultOrderBy, boolean alwaysPaginate) {
        ExecutionContext ec = efi.getEcfi().getExecutionContext()
        Map inf = inputFieldsMapName ? (Map) ec.resource.expression(inputFieldsMapName, "") : ec.context
        return searchFormMap(inf, defaultOrderBy, alwaysPaginate)
    }

    @Override
    EntityFind searchFormMap(Map inf, String defaultOrderBy, boolean alwaysPaginate) {
        ExecutionContext ec = efi.getEcfi().getExecutionContext()
        EntityDefinition ed = getEntityDef()

        // to avoid issues with entities that have cache=true, if no cache value is specified for this set it to false (avoids pagination errors, etc)
        if (useCache == null) useCache(false)

        if (inf) for (String fn in ed.getAllFieldNames()) {
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
                            Object convertedValue = value instanceof String ? ed.convertFieldString(fn, (String) value) : value
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
                if (fromValue && fromValue instanceof CharSequence) fromValue = ed.convertFieldString(fn, fromValue.toString())
                Object thruValue = inf.get(fn + "_thru")
                if (thruValue && thruValue instanceof CharSequence) thruValue = ed.convertFieldString(fn, thruValue.toString())

                if (fromValue) this.condition(efi.conditionFactory.makeCondition(fn, EntityCondition.GREATER_THAN_EQUAL_TO, fromValue))
                if (thruValue) this.condition(efi.conditionFactory.makeCondition(fn, EntityCondition.LESS_THAN, thruValue))
            }
        }

        // always look for an orderByField parameter too
        String orderByString = inf?.get("orderByField") ?: defaultOrderBy
        if (orderByString) {
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

    @TypeChecked(TypeCheckingMode.SKIP)
    EntityFind findNode(Node node) {
        ExecutionContext ec = this.efi.ecfi.executionContext

        this.entity((String) node.attribute('entity-name'))
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
        for (Node sf in (Collection<Node>) node["select-field"]) this.selectField((String) sf["@field-name"])
        for (Node ob in (Collection<Node>) node["order-by"]) this.orderBy((String) ob["@field-name"])

        // logger.warn("=== shouldCache ${this.entityName} ${shouldCache()}, limit=${this.limit}, offset=${this.offset}, useCache=${this.useCache}, getEntityDef().getUseCache()=${this.getEntityDef().getUseCache()}")
        if (!this.shouldCache()) {
            for (Node df in (Collection<Node>) node["date-filter"])
                this.condition(ec.entity.conditionFactory.makeConditionDate((String) df["@from-field-name"] ?: "fromDate",
                        (String) df["@thru-field-name"] ?: "thruDate",
                        (df["@valid-date"] ? ec.resource.expression((String) df["@valid-date"], null) as Timestamp : ec.user.nowTimestamp)))
        }

        for (Node ecn in (Collection<Node>) node["econdition"]) {
            EntityCondition econd = ((EntityConditionFactoryImpl) efi.conditionFactory).makeActionCondition(ecn)
            if (econd != null) this.condition(econd)
        }
        for (Node ecs in (Collection<Node>) node["econditions"])
            this.condition(((EntityConditionFactoryImpl) efi.conditionFactory).makeActionConditions(ecs))
        for (Node eco in (Collection<Node>) node["econdition-object"])
            this.condition((EntityCondition) ec.resource.expression((String) eco["@field"], null))

        if (node["search-form-inputs"]) {
            Node sfiNode = (Node) node["search-form-inputs"].first()
            searchFormInputs((String) sfiNode["@input-fields-map"], (String) sfiNode["@default-order-by"], (sfiNode["@paginate"] ?: "true") as boolean)
        }
        if (node["having-econditions"]) {
            for (Node havingCond in (Collection<Node>) node["having-econditions"])
                this.havingCondition(((EntityConditionFactoryImpl) efi.conditionFactory).makeActionCondition(havingCond))
        }

        // logger.info("TOREMOVE Added findNode\n${node}\n${this.toString()}")

        return this
    }

    // ======================== General/Common Options ========================

    @Override
    EntityFind selectField(String fieldToSelect) {
        if (!fieldToSelect) return this
        if (this.fieldsToSelect == null) this.fieldsToSelect = new ArrayList<String>()
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
        if (!orderByFieldName) return this
        if (this.orderByFields == null) this.orderByFields = new ArrayList()
        if (orderByFieldName.contains(",")) {
            for (String obsPart in orderByFieldName.split(",")) {
                String orderByName = obsPart.trim()
                EntityFindBuilder.FieldOrderOptions foo = new EntityFindBuilder.FieldOrderOptions(orderByName)
                if (getEntityDef().isField(foo.fieldName)) this.orderByFields.add(orderByName)
            }
        } else {
            EntityFindBuilder.FieldOrderOptions foo = new EntityFindBuilder.FieldOrderOptions(orderByFieldName)
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
    List<String> getOrderBy() { return this.orderByFields ? Collections.unmodifiableList(this.orderByFields) : null }

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
    int getPageSize() { return limit ?: 20 }

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

    Map<String, Object> getSimpleMapPrimaryKeys() {
        Map<String, Object> pks = new HashMap()
        for (String fieldName in getEntityDef().getPkFieldNames()) {
            // only include PK fields which has a non-empty value, leave others out of the Map
            Object value = simpleAndMap.get(fieldName)
            // if any fields have no value we don't have a full PK so bye bye
            if (!value) return null
            if (value) pks.put(fieldName, value)
        }
        return pks
    }

    public boolean shouldCache() {
        if (this.dynamicView) return false
        if (this.havingEntityCondition != null) return false
        if (this.limit != null || this.offset != null) return false
        if (this.useCache != null && !this.useCache) return false
        String entityCache = this.getEntityDef().getUseCache()
        return ((this.useCache == Boolean.TRUE && entityCache != "never") || entityCache == "true")
    }

    @Override
    String toString() {
        return "Find: ${entityName} WHERE [${simpleAndMap}] [${whereEntityCondition}] HAVING [${havingEntityCondition}] " +
                "SELECT [${fieldsToSelect}] ORDER BY [${orderByFields}] CACHE [${useCache}] DISTINCT [${distinct}] " +
                "OFFSET [${offset}] LIMIT [${limit}] FOR UPDATE [${forUpdate}]"
    }

    // ======================== Find and Abstract Methods ========================

    EntityFind disableAuthz() { disableAuthz = true; return this }

    abstract EntityDynamicView makeEntityDynamicView()

    @Override
    EntityValue one() throws EntityException {
        boolean enableAuthz = disableAuthz ? !efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz() : false
        try {
            EntityDefinition ed = this.getEntityDef()
            ExecutionContextImpl ec = efi.getEcfi().getEci()

            ArtifactExecutionInfo aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW")
                    .setActionDetail("one").setParameters(simpleAndMap)
            ec.getArtifactExecution().push(aei, !ed.authorizeSkipView())

            try {
                return oneInternal(ec)
            } finally {
                // pop the ArtifactExecutionInfo
                ec.getArtifactExecution().pop(aei)
            }
        } finally {
            if (enableAuthz) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
        }
    }
    protected EntityValue oneInternal(ExecutionContextImpl ec) throws EntityException {
        if (this.dynamicView) throw new IllegalArgumentException("Dynamic View not supported for 'one' find.")

        long startTime = System.currentTimeMillis()
        long startTimeNanos = System.nanoTime()
        EntityDefinition ed = this.getEntityDef()
        Node entityNode = ed.getEntityNode()

        if (ed.isViewEntity() && (!entityNode.get("member-entity") || !entityNode.get("alias")))
            throw new EntityException("Cannot do find for view-entity with name [${entityName}] because it has no member entities or no aliased fields.")

        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-one", true)

        // before combining conditions let ArtifactFacade add entity filters associated with authz
        boolean authzFilterAdded = ec.artifactExecutionImpl.filterFindForUser(this)

        // if over-constrained (anything in addition to a full PK), just use the full PK
        EntityConditionImplBase whereCondition
        if (!authzFilterAdded && ed.containsPrimaryKey(simpleAndMap)) {
            whereCondition = (EntityConditionImplBase) efi.getConditionFactory().makeCondition(ed.getPrimaryKeys(simpleAndMap))
        } else {
            whereCondition = (EntityConditionImplBase) getWhereEntityCondition()
        }

        // no condition means no condition/parameter set, so return null for find.one()
        if (!whereCondition) return null

        // try the TX cache before the entity cache, may be more up-to-date
        EntityValueBase txcValue = txCache != null ? txCache.oneGet(this) : null

        // if (txcValue != null && ed.getEntityName() == "foo") logger.warn("========= TX cache one value: ${txcValue}")

        boolean doCache = this.shouldCache()
        CacheImpl entityOneCache = doCache ? efi.getEntityCache().getCacheOne(getEntityDef().getFullEntityName()) : null
        EntityValueBase cacheHit = null
        if (doCache && txcValue == null) cacheHit = efi.getEntityCache().getFromOneCache(ed, whereCondition, entityOneCache)

        // we always want fieldsToSelect populated so that we know the order of the results coming back
        if (!this.fieldsToSelect || (txCache != null && txcValue == null) || (doCache && cacheHit == null))
            this.selectFields(ed.getFieldNames(true, true, false))


        // if (ed.getEntityName() == "Asset") logger.warn("=========== find one of Asset ${this.simpleAndMap.get('assetId')}", new Exception("Location"))

        // call the abstract method
        EntityValueBase newEntityValue = null
        if (txcValue != null) {
            if (txcValue instanceof TransactionCache.DeletedEntityValue) {
                // is deleted value, so leave newEntityValue as null
                // put in cache as null since this was deleted
                if (doCache) efi.getEntityCache().putInOneCache(ed, whereCondition, null, entityOneCache)
            } else {
                // if forUpdate unless this was a TX CREATE it'll be in the DB and should be locked, so do the query
                //     anyway, but ignore the result
                if (forUpdate && !txCache.isTxCreate(txcValue)) {
                    oneExtended(getConditionForQuery(ed, whereCondition))
                    // if (ed.getEntityName() == "Asset") logger.warn("======== doing find and ignoring result to pass through for update, for: ${txcValue}")
                }
                newEntityValue = txcValue
            }
        } else if (cacheHit != null) {
            if (cacheHit instanceof EntityCache.EmptyRecord) newEntityValue = null
            else newEntityValue = cacheHit
        } else {
            // for find one we'll always use the basic result set type and concurrency:
            this.resultSetType(ResultSet.TYPE_FORWARD_ONLY)
            this.resultSetConcurrency(ResultSet.CONCUR_READ_ONLY)

            // TODO: this will not handle query conditions on UserFields, it will blow up in fact

            newEntityValue = oneExtended(getConditionForQuery(ed, whereCondition))

            // it didn't come from the txCache so put it there
            if (txCache != null) txCache.onePut(newEntityValue)

            // put it in whether null or not (already know cacheHit is null)
            if (doCache) efi.getEntityCache().putInOneCache(ed, whereCondition, newEntityValue, entityOneCache)
        }

        if (logger.traceEnabled) logger.trace("Find one on entity [${ed.fullEntityName}] with condition [${whereCondition}] found value [${newEntityValue}]")

        // final ECA trigger
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), newEntityValue, "find-one", false)
        // count the artifact hit
        efi.ecfi.countArtifactHit("entity", "one", ed.getFullEntityName(), simpleAndMap, startTime,
                (System.nanoTime() - startTimeNanos)/1E6, newEntityValue ? 1L : 0L)

        return newEntityValue
    }

    EntityConditionImplBase getConditionForQuery(EntityDefinition ed, EntityConditionImplBase whereCondition) {
        // NOTE: do actual query condition as a separate condition because this will always be added on and isn't a
        //     part of the original where to use for the cache
        EntityConditionImplBase conditionForQuery
        EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
        if (viewWhere) {
            if (whereCondition) conditionForQuery = (EntityConditionImplBase) efi.getConditionFactory()
                    .makeCondition(whereCondition, EntityCondition.JoinOperator.AND, viewWhere)
            else conditionForQuery = viewWhere
        } else { conditionForQuery = whereCondition }

        return conditionForQuery
    }

    abstract EntityValueBase oneExtended(EntityConditionImplBase whereCondition) throws EntityException

    @Override
    Map<String, Object> oneMaster(String name) {
        boolean enableAuthz = disableAuthz ? !efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz() : false
        try {
            EntityDefinition ed = this.getEntityDef()
            ExecutionContextImpl ec = efi.getEcfi().getEci()

            ArtifactExecutionInfo aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW").setActionDetail("one")
            ec.getArtifactExecution().push(aei, !ed.authorizeSkipView())

            try {
                EntityValue ev = oneInternal(ec)
                return ev.getMasterValueMap(name)
            } finally {
                // pop the ArtifactExecutionInfo
                ec.getArtifactExecution().pop(aei)
            }
        } finally {
            if (enableAuthz) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
        }
    }

    @Override
    EntityList list() throws EntityException {
        boolean enableAuthz = disableAuthz ? !efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz() : false
        try {
            EntityDefinition ed = this.getEntityDef()
            ExecutionContextImpl ec = efi.getEcfi().getEci()

            ArtifactExecutionInfo aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW").setActionDetail("list")
            ec.getArtifactExecution().push(aei, !ed.authorizeSkipView())
            try {
                return listInternal(ec)
            } finally {
                // pop the ArtifactExecutionInfo
                ec.getArtifactExecution().pop(aei)
            }
        } finally {
            if (enableAuthz) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
        }
    }
    protected EntityList listInternal(ExecutionContextImpl ec) throws EntityException {
        long startTime = System.currentTimeMillis()
        long startTimeNanos = System.nanoTime()
        EntityDefinition ed = this.getEntityDef()
        Node entityNode = ed.getEntityNode()

        if (ed.isViewEntity() && (!entityNode.get("member-entity") || !entityNode.get("alias")))
            throw new EntityException("Cannot do find for view-entity with name [${entityName}] because it has no member entities or no aliased fields.")

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-list", true)

        ArrayList<String> orderByExpanded = new ArrayList()
        // add the manually specified ones, then the ones in the view entity's entity-condition
        if (this.getOrderBy()) orderByExpanded.addAll(this.getOrderBy())

        NodeList entityConditionList = (NodeList) entityNode.get("entity-condition")
        Node entityConditionNode = entityConditionList ? (Node) entityConditionList.get(0) : null
        NodeList ecObList = (NodeList) entityConditionNode?.get("order-by")
        if (ecObList) for (Object orderBy in ecObList)
            orderByExpanded.add((String) ((Node) orderBy).attribute('field-name'))

        if (entityConditionNode?.attribute('distinct') == "true") this.distinct(true)

        // before combining conditions let ArtifactFacade add entity filters associated with authz
        ec.artifactExecutionImpl.filterFindForUser(this)

        EntityConditionImplBase whereCondition = (EntityConditionImplBase) getWhereEntityCondition()

        // try the txCache first, more recent than general cache (and for update general cache entries will be cleared anyway)
        EntityListImpl txcEli = txCache != null ? txCache.listGet(ed, whereCondition, orderByExpanded) : null

        // NOTE: don't cache if there is a having condition, for now just support where
        boolean doEntityCache = this.shouldCache()
        CacheImpl entityListCache = doEntityCache ? efi.getEntityCache().getCacheList(getEntityDef().getFullEntityName()) : null
        EntityList cacheList = null
        if (doEntityCache) cacheList = efi.getEntityCache().getFromListCache(ed, whereCondition, orderByExpanded, entityListCache)

        EntityList el
        if (txcEli != null) {
            el = txcEli
            // if (ed.getFullEntityName().contains("OrderItem")) logger.warn("======== Got OrderItem from txCache ${el.size()} results where: ${whereCondition}")
        } else if (cacheList != null) {
            el = cacheList
        } else {
            // order by fields need to be selected (at least on some databases, Derby is one of them)
            if (this.fieldsToSelect && getDistinct() && orderByExpanded) {
                for (String orderByField in orderByExpanded) {
                    //EntityFindBuilder.FieldOrderOptions foo = new EntityFindBuilder.FieldOrderOptions(orderByField)
                    //fieldsToSelect.add(foo.fieldName)
                    fieldsToSelect.add(orderByField)
                }
            }
            // we always want fieldsToSelect populated so that we know the order of the results coming back
            if (!this.fieldsToSelect || txCache != null || doEntityCache) this.selectFields(ed.getFieldNames(true, true, false))
            // TODO: this will not handle query conditions on UserFields, it will blow up in fact

            EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
            whereCondition = (EntityConditionImplBase) efi.getConditionFactory()
                    .makeCondition(whereCondition, EntityCondition.AND, viewWhere)

            EntityConditionImplBase havingCondition = (EntityConditionImplBase) getHavingEntityCondition()
            EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
            havingCondition = (EntityConditionImplBase) efi.getConditionFactory()
                    .makeCondition(havingCondition, EntityCondition.AND, viewHaving)

            // call the abstract method
            EntityListIterator eli = this.iteratorExtended(whereCondition, havingCondition, orderByExpanded)
            // these are used by the TransactionCache methods to augment the resulting list and maintain the sort order
            eli.setQueryCondition(whereCondition)
            eli.setOrderByFields(orderByExpanded)

            Node databaseNode = this.efi.getDatabaseNode(ed.getEntityGroupName())
            if (this.limit != null && databaseNode != null && databaseNode.attribute('offset-style') == "cursor") {
                el = (EntityListImpl) eli.getPartialList(this.offset ?: 0, this.limit, true)
            } else {
                el = (EntityListImpl) eli.getCompleteList(true)
            }

            if (txCache != null) txCache.listPut(ed, whereCondition, el)
            if (doEntityCache) efi.getEntityCache().putInListCache(ed, el, whereCondition, entityListCache)

            // if (ed.getFullEntityName().contains("OrderItem")) logger.warn("======== Got OrderItem from DATABASE ${el.size()} results where: ${whereCondition}")
            // logger.warn("======== Got ${ed.getFullEntityName()} from DATABASE ${el.size()} results where: ${whereCondition}")
        }

        // run the final rules
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-list", false)
        // count the artifact hit
        efi.ecfi.countArtifactHit("entity", "list", ed.getFullEntityName(), simpleAndMap, startTime,
                (System.nanoTime() - startTimeNanos)/1E6, el ? (long) el.size() : 0L)

        return el
    }

    @Override
    List<Map<String, Object>> listMaster(String name) {
        boolean enableAuthz = disableAuthz ? !efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz() : false
        try {
            EntityDefinition ed = this.getEntityDef()
            ExecutionContextImpl ec = efi.getEcfi().getEci()

            ArtifactExecutionInfo aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW").setActionDetail("list")
            ec.getArtifactExecution().push(aei, !ed.authorizeSkipView())
            try {
                EntityList el = listInternal(ec)
                return el.getMasterValueList(name)
            } finally {
                // pop the ArtifactExecutionInfo
                ec.getArtifactExecution().pop(aei)
            }
        } finally {
            if (enableAuthz) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
        }
    }

    @Override
    EntityListIterator iterator() throws EntityException {
        boolean enableAuthz = disableAuthz ? !efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz() : false
        try {
            return iteratorInternal()
        } finally {
            if (enableAuthz) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
        }
    }
    protected EntityListIterator iteratorInternal() throws EntityException {
        long startTime = System.currentTimeMillis()
        long startTimeNanos = System.nanoTime()
        EntityDefinition ed = this.getEntityDef()
        Node entityNode = ed.getEntityNode()
        ExecutionContextImpl ec = efi.getEcfi().getEci()

        if (ed.isViewEntity() && (!entityNode.get("member-entity") || !entityNode.get("alias")))
            throw new EntityException("Cannot do find for view-entity with name [${entityName}] because it has no member entities or no aliased fields.")

        ArtifactExecutionInfo aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW").setActionDetail("iterator")
        ec.getArtifactExecution().push(aei, !ed.authorizeSkipView())

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-iterator", true)

        List<String> orderByExpanded = new ArrayList()
        // add the manually specified ones, then the ones in the view entity's entity-condition
        if (this.getOrderBy()) orderByExpanded.addAll(this.getOrderBy())

        NodeList entityConditionList = (NodeList) entityNode.get("entity-condition")
        Node entityConditionNode = entityConditionList ? (Node) entityConditionList.get(0) : null
        NodeList ecObList = (NodeList) entityConditionNode?.get("order-by")
        if (ecObList) for (Object orderBy in ecObList)
            orderByExpanded.add((String) ((Node) orderBy).attribute('field-name'))

        if (entityConditionNode?.attribute('distinct') == "true") this.distinct(true)

        // order by fields need to be selected (at least on some databases, Derby is one of them)
        if (this.fieldsToSelect && getDistinct() && orderByExpanded) {
            for (String orderByField in orderByExpanded) {
                //EntityFindBuilder.FieldOrderOptions foo = new EntityFindBuilder.FieldOrderOptions(orderByField)
                //fieldsToSelect.add(foo.fieldName)
                fieldsToSelect.add(orderByField)
            }
        }
        // we always want fieldsToSelect populated so that we know the order of the results coming back
        if (!this.fieldsToSelect) this.selectFields(ed.getFieldNames(true, true, false))
        // TODO: this will not handle query conditions on UserFields, it will blow up in fact

        // before combining conditions let ArtifactFacade add entity filters associated with authz
        ec.artifactExecutionImpl.filterFindForUser(this)

        EntityConditionImplBase whereCondition = (EntityConditionImplBase) getWhereEntityCondition()
        EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
        whereCondition = (EntityConditionImplBase) efi.getConditionFactory()
                .makeCondition(whereCondition, EntityCondition.AND, viewWhere)

        EntityConditionImplBase havingCondition = (EntityConditionImplBase) getHavingEntityCondition()
        EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
        havingCondition = (EntityConditionImplBase) efi.getConditionFactory()
                .makeCondition(havingCondition, EntityCondition.AND, viewHaving)

        // call the abstract method
        EntityListIterator eli = iteratorExtended(whereCondition, havingCondition, orderByExpanded)
        eli.setQueryCondition(whereCondition)
        eli.setOrderByFields(orderByExpanded)

        // NOTE: if we are doing offset/limit with a cursor no good way to limit results, but we'll at least jump to the offset
        Node databaseNode = this.efi.getDatabaseNode(ed.getEntityGroupName())
        // NOTE: allow databaseNode to be null because custom (non-JDBC) datasources may not have one
        if (this.offset != null && databaseNode != null && databaseNode.attribute('offset-style') == "cursor") {
            if (!eli.absolute(offset)) {
                // can't seek to desired offset? not enough results, just go to after last result
                eli.afterLast()
            }
        }

        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-iterator", false)
        // count the artifact hit
        efi.ecfi.countArtifactHit("entity", "iterator", ed.getFullEntityName(), simpleAndMap, startTime,
                (System.nanoTime() - startTimeNanos)/1E6, null)
        // pop the ArtifactExecutionInfo
        ec.getArtifactExecution().pop(aei)

        return eli
    }

    abstract EntityListIterator iteratorExtended(EntityConditionImplBase whereCondition,
            EntityConditionImplBase havingCondition, List<String> orderByExpanded)

    @Override
    long count() throws EntityException {
        boolean enableAuthz = disableAuthz ? !efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz() : false
        try {
            return countInternal()
        } finally {
            if (enableAuthz) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
        }
    }
    protected long countInternal() throws EntityException {
        long startTime = System.currentTimeMillis()
        long startTimeNanos = System.nanoTime()
        EntityDefinition ed = this.getEntityDef()
        Node entityNode = ed.getEntityNode()
        ExecutionContextImpl ec = efi.getEcfi().getEci()

        String authorizeSkip = (String) entityNode.attribute('authorize-skip')
        ArtifactExecutionInfo aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(), "AT_ENTITY", "AUTHZA_VIEW").setActionDetail("count")
        ec.getArtifactExecution().push(aei, (authorizeSkip != "true" && !authorizeSkip?.contains("view")))

        // there may not be a simpleAndMap, but that's all we have that can be treated directly by the EECA
        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-count", true)

        // before combining conditions let ArtifactFacade add entity filters associated with authz
        ec.artifactExecutionImpl.filterFindForUser(this)

        EntityConditionImplBase whereCondition = (EntityConditionImplBase) getWhereEntityCondition()
        // NOTE: don't cache if there is a having condition, for now just support where
        boolean doCache = !this.havingEntityCondition && this.shouldCache()
        CacheImpl entityCountCache = doCache ? efi.getEntityCache().getCacheCount(getEntityDef().getFullEntityName()) : null
        Long cacheCount = null
        if (doCache) cacheCount = efi.getEntityCache().getFromCountCache(ed, whereCondition, entityCountCache)

        long count
        if (cacheCount != null) {
            count = cacheCount
        } else {
            // select all pk and nonpk fields to match what list() or iterator() would do
            if (!this.fieldsToSelect) this.selectFields(ed.getFieldNames(true, true, false))
            // TODO: this will not handle query conditions on UserFields, it will blow up in fact

            NodeList entityConditionList = (NodeList) entityNode.get("entity-condition")
            Node entityConditionNode = entityConditionList ? (Node) entityConditionList.get(0) : null
            if (ed.isViewEntity() && entityConditionNode?.attribute('distinct') == "true") this.distinct(true)

            EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
            whereCondition = (EntityConditionImplBase) efi.getConditionFactory()
                    .makeCondition(whereCondition, EntityCondition.AND, viewWhere)

            EntityConditionImplBase havingCondition = (EntityConditionImplBase) getHavingEntityCondition()
            EntityConditionImplBase viewHaving = ed.makeViewHavingCondition()
            havingCondition = (EntityConditionImplBase) efi.getConditionFactory()
                    .makeCondition(havingCondition, EntityCondition.AND, viewHaving)

            // call the abstract method
            count = countExtended(whereCondition, havingCondition)

            if (doCache && whereCondition != null) entityCountCache.put(whereCondition, count)
        }

        // find EECA rules deprecated, not worth performance hit: efi.runEecaRules(ed.getFullEntityName(), simpleAndMap, "find-count", false)
        // count the artifact hit
        efi.ecfi.countArtifactHit("entity", "count", ed.getFullEntityName(), simpleAndMap, startTime,
                (System.nanoTime() - startTimeNanos)/1E6, count)
        // pop the ArtifactExecutionInfo
        ec.getArtifactExecution().pop(aei)

        return count
    }
    abstract long countExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition)
            throws EntityException

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

        if (getEntityDef().createOnly()) throw new EntityException("Entity [${getEntityDef().getFullEntityName()}] is create-only (immutable), cannot be updated.")

        this.useCache(false)
        long totalUpdated = 0
        EntityListIterator eli = null
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

        if (getEntityDef().createOnly()) throw new EntityException("Entity [${getEntityDef().getFullEntityName()}] is create-only (immutable), cannot be deleted.")

        // if there are no EECAs for the entity OR there is a TransactionCache in place just call ev.delete() on each
        boolean useEvDelete = txCache != null || efi.hasEecaRules(this.getEntityDef().getFullEntityName())
        if (!useEvDelete) this.resultSetConcurrency(ResultSet.CONCUR_UPDATABLE)
        this.useCache(false)
        EntityListIterator eli = null
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
