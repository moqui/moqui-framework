package dtq.rockycube.endpoint

import com.google.gson.Gson
import dtq.rockycube.cache.CacheQueryHandler
import dtq.rockycube.entity.ConditionHandler
import dtq.rockycube.entity.EntityHelper
import dtq.synchro.SynchroMaster
import dtq.rockycube.GenericUtilities
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityException
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.ViUtilities
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.FieldInfo
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.moqui.impl.entity.condition.FieldValueCondition
import org.moqui.impl.entity.condition.ListCondition
import org.moqui.util.CollectionUtilities
import org.moqui.util.ObjectUtilities
import org.moqui.util.RestClient
import org.moqui.util.StringUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

class EndpointServiceHandler {
    protected final static Logger logger = LoggerFactory.getLogger(EndpointServiceHandler.class);
    private ExecutionContext ec
    private ExecutionContextFactoryImpl ecfi
    private EntityHelper meh
    private EntityCondition.JoinOperator defaultListJoinOper = EntityCondition.JoinOperator.OR
    private Gson gson = new Gson()

    private static String CONST_UPDATE_IF_EXISTS                = 'updateIfExists'
    private static String CONST_ALLOWED_FIELDS                  = 'allowedFields'
    private static String CONST_CONVERT_OUTPUT_TO_LIST          = 'convertToList'
    private static String CONST_ALLOW_TIMESTAMPS                = 'allowTimestamps'
    private static String CONST_AUTO_CREATE_PKEY                = 'autoCreatePrimaryKey'
    private static String CONST_PREFER_OBJECT_IN_RETURN         = 'preferObjectInReturn'
    private static String CONST_RENAME_MAP                      = 'renameMap'
    private static String CONST_CONVERT_OUTPUT_TO_FLATMAP       = 'convertToFlatMap'
    // when using complex query builder, use this parameter for setup (e.g. AND(1, OR(2, 3)))
    private static String CONST_COMPLEX_CONDITION_RULE          = 'complexCondition'
    // intelligent-cache query feature is used by default, if you need to set this parameter to `true`
    private static String CONST_ALLOW_ICACHE_QUERY              = 'allowICacheQuery'
    private static String CONST_DEFAULT_LIST_JOIN_OPERATOR      = 'defaultListJoinOperator'
    // these fields shall not be squashed when converting to flatMap
    private static String CONST_DO_NOT_SQUASH_FIELDS            = 'doNotSquashFields'

    /*
    DEFAULTS
     */

    /*
    REQUEST ATTRIBUTES
    */
    private String entityName
    private ArrayList term
    private Integer inputIndex = 1
    private Integer pageSize = 20
    private ArrayList orderBy = []
    private HashMap<String, Object> args
    private ArrayList serviceAllowedOn = new ArrayList()

    // variables extracted
    private EntityDefinition ed
    private EntityConditionImplBase queryCondition
    private Integer pageIndex
    private String dsType

    public static HashMap defaultErrorResponse(String message)
    {
        return [
                result: false,
                message: message
        ]
    }

    EndpointServiceHandler(HashMap args, ArrayList term, String entityName, String tableName, Boolean failsafe, ArrayList serviceAllowedOn)
    {
        this.ec = Moqui.getExecutionContext()
        this.ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
        this.meh = new EntityHelper(this.ec)
        serviceAllowedOn.each {it->this.serviceAllowedOn.add((String) it)}

        // fill entity name
        this.fillEntityName(entityName, tableName)

        // check entity name against `allowedOn`
        if (!this.entityAllowed()) throw new EntityException("Operation not allowed on this specific entity [${this.entityName}]")

        // fill
        this.term = (ArrayList) term?:[]
        this.args = args?:[:] as HashMap<String, Object>

        // subsequent calculations
        this.calculateDependencies()

        def ds = ec.entity.getDatasourceFactory(ed.groupName)
        dsType = ds.getClass().simpleName
    }

    EndpointServiceHandler(ArrayList serviceAllowedOn) {
        this.ec = Moqui.getExecutionContext()
        this.ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
        this.meh = new EntityHelper(this.ec)

        // fill entity name
        this.fillEntityName((String) ec.context.entityName, (String) ec.context.tableName)

        // check allowed
        if (!this.entityAllowed()) throw new EntityException("Operation not allowed on this specific entity [${this.entityName}]")

        // initial fill
        this.fillRequestVariablesFromContext()

        // subsequent calculations
        this.calculateDependencies()

        def ds = ec.entity.getDatasourceFactory(ed.groupName)
        dsType = ds.getClass().simpleName
    }

    private boolean entityAllowed()
    {
        if (serviceAllowedOn.empty) return true
        return serviceAllowedOn.any{it->
            def rec = Pattern.compile((String) it)
            return rec.matcher(this.entityName).matches()
        }
    }

    private void fillEntityName(String inputEntityName, String inputTableName)
    {
        // do we have an entity?
        // try extracting from table name
        if (!inputEntityName) {
            String tableName = ec.context.tableName ?: null
            if (!inputTableName) throw new EntityException("Missing both entity and table name")

            this.ed = meh.getDefinition(inputTableName)
            if (!ed) throw new EntityException("Unable to find EntityDefinition for '${inputTableName}'")
            this.entityName = ed.fullEntityName
        } else {
            this.entityName = inputEntityName
        }
    }

    private void fillRequestVariablesFromContext()
    {
        this.term = (ArrayList) ec.context.term?:[]
        this.setInputIndex((Integer) ec.context.index?:1)
        this.pageSize = (Integer) ec.context.size?:20
        this.orderBy = (ArrayList) ec.context.orderBy?:[]
        this.args = ec.context.args?:[:] as HashMap<String, Object>
    }

    void setInputIndex(Integer inputIndex) {
        this.inputIndex = inputIndex
        this.pageIndex = Math.max(inputIndex - 1, 0)
    }

    private void calculateDependencies()
    {
        // fill in defaults if no arguments passed
        this.checkArgsSetup()

        // query condition setup
        this.queryCondition = this.extractQueryCondition(term)

        // entity definition is a must
        if (!this.ed) this.ed = this.meh.getDefinition(entityName)
        if (!this.ed) throw new EntityException("Entity definition not found [${entityName?:'NOT SET'}], cannot continue with populating service output")
    }

    private static Object cleanMongoObjects(Object incoming)
    {
        if (incoming.getClass().simpleName == "LinkedHashMap")
        {
            def newMap = [:]
            for (def itemKey in incoming.keySet())
            {
                newMap.put(itemKey, cleanMongoObjects(incoming[itemKey]))
            }
            return newMap
        } else if (incoming.getClass().simpleName == "ArrayList")
        {
            def newList = []
            for (def item in incoming)
            {
                newList.add( cleanMongoObjects(item))
            }
            return newList
        } else if (incoming.getClass().simpleName == "Document"){
            return (LinkedHashMap) incoming
        } else {
            return incoming
        }
    }

    /**
     * Fills arguments with some defaults, should it be necessary
     * @param args
     */
    private void checkArgsSetup()
    {
        // by default
        //      do not overwrite
        if (!args.containsKey(CONST_UPDATE_IF_EXISTS)) args.put(CONST_UPDATE_IF_EXISTS, false)

        //      all fields are allowed
        if (!args.containsKey(CONST_ALLOWED_FIELDS)) args.put(CONST_ALLOWED_FIELDS, '*')

        //      we do not want list as output
        if (!args.containsKey(CONST_CONVERT_OUTPUT_TO_LIST)) args.put(CONST_CONVERT_OUTPUT_TO_LIST, false)

        //      we do not want timestamp fields, by default
        if (!args.containsKey(CONST_ALLOW_TIMESTAMPS)) args.put(CONST_ALLOW_TIMESTAMPS, false)

        //      let the entity manager create primary key
        if (!args.containsKey(CONST_AUTO_CREATE_PKEY)) args.put(CONST_AUTO_CREATE_PKEY, true)

        //      do not attempt to force-return an object
        if (!args.containsKey(CONST_PREFER_OBJECT_IN_RETURN)) args.put(CONST_PREFER_OBJECT_IN_RETURN, false)

        //      use iCache query only when explicitly set
        if (!args.containsKey(CONST_ALLOW_ICACHE_QUERY)) args.put(CONST_ALLOW_ICACHE_QUERY, false)

        //      default join operator change
        if (args.containsKey(CONST_DEFAULT_LIST_JOIN_OPERATOR))
        {
            switch(args[CONST_DEFAULT_LIST_JOIN_OPERATOR].toString().toLowerCase())
            {
                case "and":
                case "&&":
                    this.defaultListJoinOper = EntityCondition.JoinOperator.AND
                    break
                case "or":
                case "||":
                    this.defaultListJoinOper = EntityCondition.JoinOperator.OR
            }
        }

        // no fields saved from squash
        if (!args.containsKey(CONST_DO_NOT_SQUASH_FIELDS))
        {
            args.put(CONST_DO_NOT_SQUASH_FIELDS, [])
        } else {
            // what if the argument is set as simple string `identity, whatever, ...`?
            switch (args[CONST_DO_NOT_SQUASH_FIELDS].class.simpleName.toLowerCase())
            {
                case "string":
                    // convert to list
                    def fields = (String) args[CONST_DO_NOT_SQUASH_FIELDS]
                    def fieldsList = fields.split(',')
                    // pop field
                    args.remove(CONST_DO_NOT_SQUASH_FIELDS)
                    if (fieldsList.size() > 1)
                    {
                        args[CONST_DO_NOT_SQUASH_FIELDS] = fieldsList
                    } else {
                        args[CONST_DO_NOT_SQUASH_FIELDS] = [fields]
                    }
                    break
                default:
                    // do not do anything
                    logger.debug("Setting `doNotSquashFields` to [${args[CONST_DO_NOT_SQUASH_FIELDS]}]")
            }
        }
    }

    // rename field if necessary
    private String fieldName(String name)
    {
        // do we have a setup object?
        if (!args.containsKey(CONST_RENAME_MAP)) return name

        // find in map
        try {
            HashMap renameMap = args[CONST_RENAME_MAP] as HashMap
            return renameMap.get(name, name)
        } catch (Exception exc){
            logger.error("Invalid RENAME_MAP provided: ${exc.message}")
            return name
        }
    }

    private boolean checkIdentityColumn(
            HashMap recordMap,
            FieldInfo pkInfo,
            String fieldName,
            Object fieldValue)
    {
        if (!pkInfo) return false

        // not a case if column-name and table-column-name are equal
        if (pkInfo.columnName == pkInfo.name) return false

        // if we shall do ID-renaming later, do not perform this check
        def shouldRenameId = args.getOrDefault('renameId', null) != null
        if (shouldRenameId) return false

        if (fieldName == pkInfo.columnName){
            // still may be a situation where the ID is forbidden
            def allowField = addField(pkInfo.name)
            if (allowField) recordMap.put(pkInfo.name, fieldValue)
            // no need to repeat
            return true
        }

        return false
    }

    private Object fillResultset(EntityValue single)
    {
        HashMap<String, Object> res = [:]
        HashMap<String, Object> recordMap = [:]
        // logger.info("args.allowedFields: ${args[CONST_ALLOWED_FIELDS]}")

        FieldInfo pkInfo = null

        assert ed, "Entity definition must be available"

        def pk = this.findPrimaryKey()
        if (pk.size() == 1) pkInfo = ed.getFieldInfo((String) pk[0])

        single.entrySet().each { it->
            // check primary field
            // in case the column is different to fieldName
            // and we do not `renameId`, store it as a field name
            if (checkIdentityColumn(recordMap, pkInfo, it.key, it.value)) return

            if (!it.key) return
            if (!addField(it.key)) return
            def fieldName = fieldName(it.key)

            // for MONGO DATABASE, make it simple, we do not want to much
            // of EntityDefinition handling around
            if (dsType == "MongoDatasourceFactory")
            {
                recordMap.put(fieldName, cleanMongoObjects(it.value))
                return
            }

            // value and it's class
            def itVal = it.value

            // special treatment for maps
            // convert HashMap, watch out if it's array
            if (it.isMapField()) itVal = GenericUtilities.processField(it.value)
            recordMap.put(fieldName, itVal)
        }

        // handle specialities
        this.manipulateRecordId(recordMap)
        this.manipulateExtraFields(recordMap)

        // add to output, sorted
        def sortedMap = recordMap.sort({m1, m2 -> m1.key <=> m2.key})

        // change to list, if set in such way
        if (args[CONST_CONVERT_OUTPUT_TO_LIST] == true) {
            def conv2List = []
            sortedMap.each { it ->
                conv2List.push(it.value)
            }

            if (conv2List.size() == 1) {
                return conv2List[0]
            } else {
                return conv2List
            }
        } else if (args[CONST_CONVERT_OUTPUT_TO_FLATMAP] == true){
            // check if we must treat `identity` field with special care
            def savedFields = new HashMap<String, Object>()
            for (String f in args[CONST_DO_NOT_SQUASH_FIELDS])
            {
                // skip to next if field not in sorted map
                if (!sortedMap.containsKey(f)) continue

                // save for later addition
                // + pop from sorted map
                savedFields.put(f, sortedMap[f])
                sortedMap.remove(f)
            }

            def preliminary = CollectionUtilities.flattenNestedMap(sortedMap)
            if (savedFields.isEmpty()) return preliminary

            // store back in output in original form
            for (k in savedFields.keySet()) preliminary.put(k, savedFields[k])
            return preliminary
        } else {
            return sortedMap
        }
    }

    private Object fillResultset(EntityList entities) {
        // return as array by default, only when set otherwise
        if (entities.size() == 1 && args[CONST_PREFER_OBJECT_IN_RETURN]) return fillResultset(entities[0])

        // otherwise return as an array
        def res = []
        for (EntityValue ev in entities) res.add(fillResultset(ev))
        return res
    }

    private boolean addField(String fieldName)
    {
        // allow timestamps? must be explicitly set
        def timestamps = ["lastUpdatedStamp", "creationTime"]
        if (timestamps.contains(fieldName)) return args[CONST_ALLOW_TIMESTAMPS]
        // otherwise use this method
        return fieldAllowed(args[CONST_ALLOWED_FIELDS], fieldName)
    }

    private boolean fieldAllowed(Object filter, String fieldName)
    {
        switch(filter.getClass().simpleName)
        {
            case 'String':
                def afld = (String) filter
                if (afld == '*') {
                    return true
                } else if (afld == fieldName) {
                    return true
                }
                break
            case 'ArrayList':
                def aflds = (ArrayList) filter
                if (aflds.size() == 1 && aflds[0] == "*")
                {
                    return true
                } else
                {
                    def forbiddenFields = []
                    aflds.each {it->
                        if (it.toString().startsWith('-')) forbiddenFields.add(it.toString().substring(1))
                    }

                    // forbidden fields
                    if (forbiddenFields.contains(fieldName)) return false

                    boolean allFieldsFlag = aflds.count {it->
                        return it == "*"
                    } == 1

                    if (allFieldsFlag) return true
                    if (aflds.contains(fieldName)) return true
                    return false
                }
                break
            default:
                return false
        }
    }

    private EntityConditionImplBase extractComplexCondition(Object term, String rule)
    {
        try {
            return ConditionHandler.recCondition(rule, term as ArrayList)
        } catch(Error err) {
            logger.error(err.message)
            throw new EndpointException("Invalid condition construction, possible StackOverflow error")
        } catch(Exception exc) {
            logger.error(exc.message)
            throw new EndpointException("Invalid condition construction")
        }
    }

    private EntityConditionImplBase extractQueryCondition(Object term)
    {
        // no Strings as term
        if (term.getClass().simpleName == "String") throw new EntityException("Unsupported type for Term: String")

        // should there be an argument specifying how the complex condition
        // must be set, let's do it this way
        if (this.args.containsKey(CONST_COMPLEX_CONDITION_RULE)) {
            return this.extractComplexCondition(term, this.args[CONST_COMPLEX_CONDITION_RULE] as String)
        }

        // otherwise make it simple and add `OR` between all conditions
        def resListCondition = []
        for (HashMap<String, Object> singleTerm in term) resListCondition.add(ConditionHandler.getSingleFieldCondition(singleTerm))
        return new ListCondition(resListCondition, this.defaultListJoinOper)
    }

    private HashMap fetchPaginationInfo(
            String entityName,
            Integer pageIndex,
            Integer pageSize,
            EntityCondition queryCondition)
    {
        def res = [:]
        def allEntries = ec.entity.find(entityName)
                .condition(queryCondition)
                .count()

        res['size'] = pageSize
        res['page'] = pageIndex + 1
        res['rows'] = allEntries
        return res
    }

    private void manipulateRecordId(HashMap<String, Object> record)
    {
        if (!args.get('renameId', null)) return

        // modify ID to a new value
        def newIdField = args.get('renameId').toString()

        // make sure it's a unique name
        Number occurrences = record.count {it->
            return newIdField == it.key
        }

        if (occurrences != 0)
        {
            logger.error("Field exist, cannot be renamed")
            return
        }

        def idValue = null
        def idFieldName = null
        record.each {it->
            if (!ed.isPkField(it.key, true)) return
            idValue = it.value
            idFieldName = it.key
        }

        if (!idValue || !idFieldName)
        {
            logger.warn("Unable to extract ID field or ID field value, probably incorrectly set request arguments")
            return
        }

        record[newIdField] = idValue
        record.remove(idFieldName)
    }

    private void manipulateExtraFields(HashMap<String, Object> record)
    {
        if (!args.get('removeFields', null)) return

        def fields2remove = (ArrayList) args['removeFields']
        fields2remove.each {fld->
            if (ed.isPkField(fld)) return
            if (!record.containsKey(fld)) return

            // remove key
            record.remove(fld)
        }
    }

    public HashMap createEntityData(Object data)
    {
        // ArrayList supported, this way we can run multiple create procedures in single moment
        // if (data.getClass().simpleName.startsWith('ArrayList')) throw new EntityException("Creating multiple entities not supported")

        def itemsCreated = 0
        HashMap<String, Object> lastItemResult = [:]

        // different for array and hashmap
        if (data instanceof HashMap || data instanceof LinkedHashMap) {
            lastItemResult = this.createSingleEntity(data as HashMap)
            return lastItemResult

        } else {
            data.each { HashMap it ->
                lastItemResult = this.createSingleEntity(it)
                if (lastItemResult['result']) itemsCreated += 1
            }
        }

        // if only one, return last item's result
        if (itemsCreated == 1) return lastItemResult

        return [
                result : true,
                message: "Records created/updated (${itemsCreated})"
        ]
    }

    private ArrayList findPrimaryKey()
    {
        // nothing to return
        if (!this.ed) return null

        def pks = this.ed.pkFieldNames
        if (!pks) return []
        if (pks.size() == 0) return []
        return pks
    }

    public HashMap createEntityData()
    {
        return createEntityData(ec.context.data)
    }

    private HashMap createSingleEntity(HashMap singleEntityData) {
        if (singleEntityData.isEmpty()) {
            // return empty map
            return defaultErrorResponse("Single entity creation failed, no data provided")
        }

        // update if necessary
        // otherwise perform clean write
        if (args.get(CONST_UPDATE_IF_EXISTS) && queryCondition)
        {
            def alreadyExists = ec.entity.find(entityName).condition(queryCondition)
            if (alreadyExists.count() == 1)
            {
                return this.updateSingleEntity(alreadyExists, singleEntityData)
            } else if (alreadyExists.count() > 1)
            {
                return defaultErrorResponse("Unable to perform create/update, multiple entries exist")
            }
        }

        def created = ec.entity.makeValue(entityName)
                .setAll(singleEntityData)

        // create primary key
        if (args[CONST_AUTO_CREATE_PKEY] == true) {
            // do not automatically generate, if the ID is among data provided
            def pk = this.findPrimaryKey()
            if (!pk){
                // create ID manually
                created.setSequencedIdPrimary()
            } else if (pk.size() == 0) {
                // create ID manually
                created.setSequencedIdPrimary()
            } else {
                // create ID manually
                // if not contained in data provided
                if (!singleEntityData.containsKey(pk[0]))
                {
                    created.setSequencedIdPrimary()
                }
            }
        }

        // let it create
        created.create()

        // get result back to the caller
        return [
                result : true,
                message: "Records created (1)",
                data   : [fillResultset(created)]
        ]
    }

    public HashMap deleteEntityData()
    {
        def toDeleteSearch = ec.entity.find(entityName).condition(queryCondition)
        logger.debug("DELETE: entityName/term: ${entityName}/${queryCondition}")

        // convert to list for message
        def toDelete = toDeleteSearch.list()

        // if no records deleted, quit, with false flag
        if (toDelete.size() == 0)
        {
            return [
                    result: false,
                    message: "No records to delete were found"
            ]
        }

        for (EntityValue ev in toDeleteSearch)
        {
            // delete
            ev.delete()
        }

        // store items being deleted
        def deleted = this.fillResultset(toDelete)

        return [
                result: true,
                message: "Records deleted (${deleted.size()})",
                data: deleted
        ]
    }

    // fetch Entity data using `standard entity model`
    private HashMap fetchEntityData_standard()
    {
        // pagination info
        def pagination = this.fetchPaginationInfo(entityName, pageIndex, pageSize, queryCondition)
        logger.debug("pagination: ${pagination}")

        def evs = ec.entity.find(entityName)
                .condition(queryCondition)
                .limit(pageSize)
                .offset(pageIndex * pageSize)

        // order by columns
        if (orderBy) evs.orderBy(orderBy)

        // update pagination info, so that count of rows being displayed is returned
        def result = evs.list()
        pagination['displayed'] = result.size()

        return [
                result: true,
                data: this.fillResultset(result),
                pagination: pagination
        ]
    }

    // fetch Entity data using `intelligent cache model`
    public HashMap fetchCachedData(SynchroMaster syncMaster)
    {
        // is there a cache?
        if (!syncMaster.getEntityIsSynced(this.entityName)) throw new EndpointException("Entity is not synced in a cache")

        // is the cache synced?
        if (!syncMaster.getIsSynced(this.entityName)) throw new EndpointException("Entity is not synchronized, cannot proceed")

        // 1. pagination not supported
        if (this.inputIndex > 1) throw new EndpointException("Pagination not supported for i-cache queries")

        // 3. fields must be explicitly set
        if (args[CONST_ALLOWED_FIELDS] == "*") throw new EndpointException("Fields to retrieve must be explicitly set when reaching to cache")
        if (!args[CONST_ALLOWED_FIELDS] instanceof List) throw new EndpointException("Fields to retrieve must be set as list")

        // populate using query handler
        def cacheUsed = syncMaster.getEntityCache(this.entityName)
        def qh = new CacheQueryHandler(
                cacheUsed,
                this.ed
        )

        // IDs returned
        def resIds = qh.fetch(queryCondition)

        // now it's time to populate result
        FieldInfo pkInfo = null
        def pk = this.findPrimaryKey()

        assert ed, "Entity definition must be available"

        if (pk.size() == 1) pkInfo = ed.getFieldInfo((String) pk[0])
        def res = new ArrayList()
        def fields = args[CONST_ALLOWED_FIELDS]
        resIds.each {it->
            def fullEntity = (HashMap) cacheUsed.get(it)
            def resMap = [:]
            fullEntity.each {key, value ->
                // check primary field
                // in case the column is different to fieldName
                // and we do not `renameId`, store it as a field name
                if (checkIdentityColumn(resMap, pkInfo, key, value)) return

                // standard checks
                if (!this.fieldAllowed(fields, key)) return

                def fieldClass = value.getClass().simpleName
                //logger.debug("Field [${f}][${fieldClass}]: [${fieldValue}]")
                // byte's - that shall be a JSON
                if (fieldClass == "byte[]") value = GenericUtilities.processField(value)
                resMap.put(key, value)
            }
            res.add(resMap)
        }

        return [
                result: true,
                data: res
        ]
    }

    public HashMap fetchEntityData(Integer index, Integer size, ArrayList orderBy)
    {
        this.setInputIndex(index)
        this.pageSize = size
        this.orderBy = orderBy

        return fetchEntityData()
    }

    public HashMap fetchEntityData()
    {
        logger.info("entityName/term/index/size: ${entityName}/${queryCondition}/${pageIndex}/${pageSize}")

        // only available when specific flag is switched
        if (args[CONST_ALLOW_ICACHE_QUERY]) {
            // SynchroMaster required
            SynchroMaster syncMaster = null
            try {
                syncMaster = ec.getTool("SynchroMaster", SynchroMaster.class)
            } catch (Exception exc) {
                logger.error("Unable to use initialize SynchroMaster for cache-based data loading [${exc.message}]")
            }

            // if tool is not ready, throw exception, we expect it works
            if (!syncMaster) throw new EndpointException("Unable to perform cache-based data loading, no cache handler")

            return this.fetchCachedData(syncMaster)
        }

        // standard way
        return this.fetchEntityData_standard()
    }

    public HashMap updateEntityData(HashMap<String, Object> data)
    {
        if (data.isEmpty())
        {
            return [
                    result: false,
                    message: 'No data for update found'
            ]
        }

        def toUpdate = ec.entity.find(entityName)
                .condition(queryCondition)
                .forUpdate(true)
        logger.debug("UPDATE: entityName/term: ${entityName}/${queryCondition}")

        // update record
        return this.updateSingleEntity(toUpdate, data)
    }

    public HashMap updateEntityData()
    {
        return updateEntityData(ec.context.data?:[:] as HashMap<String, Object>)
    }

    private HashMap updateSingleEntity(EntityFind toUpdate, HashMap<String, Object> updateData){
        // if no records deleted, quit, with false flag
        if (toUpdate.count() == 0)
        {
            return [
                    result: false,
                    message: "No record to update was found"
            ]
        }

        // allow only single record update
        if (toUpdate.count() > 1)
        {
            return [
                    result: false,
                    message: "Multiple records to update were found"
            ]
        }

        def mod = toUpdate.one()

        // set new values
        updateData.each {it->
            mod.set((String) it.key, it.value)
        }

        // save
        mod.update()

        return [
                result : true,
                message: "Records updated (1)",
                data   : [fillResultset(mod)]
        ]
    }

    public static void sendRenderedResponse(String entityName, ArrayList term, LinkedHashMap args, String templateName, boolean inline) {
        // add args
        if (!args.containsKey('preferObjectInReturn')) args['preferObjectInReturn'] = true

        // initialize EC
        def ec = Moqui.getExecutionContext()

        try {
            // 1. load data, use endpoint handler (and terms) to locate data
            def reportData = ec.service.sync().name("dtq.rockycube.EndpointServices.populate#EntityData").parameters([
                    entityName: entityName,
                    term      : term,
                    args      : args
            ]).call() as HashMap

            // check result
            if (!reportData) throw new EntityException("Unable to retrieve data for rendering response")
            if (!reportData.containsKey('data')) throw new EntityException("No data in response, cannot proceed with template rendering")

            def dataToProcess = reportData['data']

            // 2. transfer data to PY-CALC to get it rendered
            // by writing into response's output stream
            InputStream renderedTemplate = renderTemplate(templateName, dataToProcess)
            def response = ec.web.response

            try {
                OutputStream os = response.outputStream
                try {
                    int totalLen = ObjectUtilities.copyStream(renderedTemplate, os)
                    logger.info("Streamed ${totalLen} bytes from response")
                } finally {
                    os.close()
                }
            } finally {
                // close stream
                renderedTemplate.close()
            }

            // 3. return response back to frontend as inline content
            if (inline) response.addHeader("Content-Disposition", "inline")

            // set content type
            response.setContentType("text/html")
            response.setCharacterEncoding("UTF-8")

        } catch (Exception exc) {
            ec.logger.error(exc.message)
            ec.web.response.sendError(400, "Unable to generate template")
        }
    }

    // render data in PY-CALC using template
    public static InputStream renderTemplate(String template, Object data) {
        ExecutionContext ec = Moqui.getExecutionContext()

        // expect config in system variables
        def pycalcHost = System.properties.get("py.server.host")
        def renderTemplateEndpoint = System.properties.get("py.endpoint.render")

        // basic checks
        if (!pycalcHost) throw new EndpointException("PY-CALC server host not defined")
        if (!renderTemplateEndpoint) throw new EndpointException("PY-CALC server's render template endpoint not set")

        RestClient restClient = ec.service.rest().method(RestClient.POST)
                .uri("${pycalcHost}/${renderTemplateEndpoint}")
                .addHeader("Content-Type", "application/json")
                .addBodyParameter("template", template)
                .jsonObject(data)
        RestClient.RestResponse restResponse = restClient.call()

        // check status code
        if (restResponse.statusCode != 200) {
            throw new EndpointException("Response with status ${restResponse.statusCode} returned: ${restResponse.reasonPhrase}")
        }

        return new ByteArrayInputStream(restResponse.bytes());
    }

    /**
     * Method that runs item through PY-CALC and let it be transformed there.
     * This is the default method to be used, with the exception of test run.
     * @param ec
     * @param itemToCalculate
     * @param proceduresList
     * @param extraParams
     * @param cbCheckData
     * @param debug
     * @param identity
     * @return
     */
    public static Object processItemInCalc(
            ExecutionContext ec,
            Object itemToCalculate,
            ArrayList proceduresList,
            HashMap extraParams,
            Closure cbCheckData,
            boolean debug,
            String identity = null)
    {
        def pycalcHost = System.properties.get("py.server.host")

        // set identity if not set
        if (debug && !identity) identity = StringUtilities.getRandomString(11)

        // store info for debugging purposes
        if (debug) {
            GenericUtilities.debugFile(ec, "closure-handler-process-items.${identity}.json", itemToCalculate)
            GenericUtilities.debugFile(ec, "closure-handler-process-items-procedures.${identity}.json", proceduresList)
            GenericUtilities.debugFile(ec, "closure-handler-process-items-extra.${identity}.json", extraParams)
        }

        // basic checks
        if (!pycalcHost) throw new EndpointException("PY-CALC server host not defined")

        // data prep
        def payload = [
                setup: [
                        procedure: proceduresList,
                        output_only_last: true,
                        extra: extraParams
                ],
                data: itemToCalculate
        ]

        RestClient restClient = ec.service.rest().method(RestClient.POST)
                .uri("${pycalcHost}/api/v1/calculator/execute")
                .addHeader("Content-Type", "application/json")
                .jsonObject(payload)

        // execute
        RestClient.RestResponse restResponse = restClient.call()

        // check status code
        if (restResponse.statusCode != 200) {
            throw new EndpointException("Response with status ${restResponse.statusCode} returned: ${restResponse.reasonPhrase}")
        }

        // must handle all states of the response
        def rsp = (HashMap) restResponse.jsonObject()

        // use callback to check/modify response
        if (cbCheckData) return cbCheckData(rsp)

        return rsp
    }

    /**
     * This method processes items into list, ready for vizualization
     * @param ec
     * @param allItems
     * @param endpoint
     * @param args
     * @param debug
     * @param identity
     * @return
     */
    public static ArrayList processItemsForVizualization(ExecutionContext ec, ArrayList allItems, String endpoint, HashMap args, boolean debug, String identity = null)
    {
        def pycalcHost = System.properties.get("py.server.host")

        // set identity if not set
        if (debug && !identity) identity = StringUtilities.getRandomString(11)

        // debug for development purposes
        if (debug) GenericUtilities.debugFile(ec, "vizualize-items-before-calc.${identity}.json", allItems)

        // pass only those arguments, that have `pycalc` prefix
        def pyCalcArgs = ViUtilities.extractPycalcArgs(args as HashMap)

        // store parameters
        if (debug) GenericUtilities.debugFile(ec, "vizualize-items-calc-params.${identity}.json", pyCalcArgs)

        RestClient restClient = ec.service.rest().method(RestClient.POST)
                .uri("${pycalcHost}/${endpoint}")
                .addHeader("Content-Type", "application/json")
                .jsonObject(
                        [
                                data: allItems,
                                args: pyCalcArgs
                        ]
                )
        RestClient.RestResponse restResponse = restClient.call()

        // check status code
        if (restResponse.statusCode != 200) {
            if (debug) GenericUtilities.debugFile(ec, "vizualize-items-exception.${identity}.txt", restResponse.reasonPhrase)

            logger.error("Error in response from pyCalc [${restResponse.reasonPhrase}] for session [${identity}]")
            throw new EndpointException("Response with status ${restResponse.statusCode} returned: ${restResponse.reasonPhrase}")
        }

        if (debug) GenericUtilities.debugFile(ec, "vizualize-items-after-calc.${identity}.json", restResponse.jsonObject())

        HashMap response = restResponse.jsonObject() as HashMap
        return response['data']
    }
}