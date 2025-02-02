package ars.rockycube.endpoint

import com.google.gson.internal.LinkedTreeMap
import ars.rockycube.cache.CacheQueryHandler
import ars.rockycube.entity.ConditionHandler
import ars.rockycube.entity.EntityHelper
import ars.rockycube.util.CollectionUtils
import ars.synchro.SynchroMaster
import ars.rockycube.GenericUtilities
import org.apache.commons.lang3.RandomStringUtils
import org.apache.groovy.json.internal.LazyMap
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityException
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.ViUtilities
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
import org.moqui.util.RestClient.RestResponse
import org.moqui.util.StringUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

import static ars.rockycube.GenericUtilities.debugFile

class EndpointServiceHandler {
    protected final static Logger logger = LoggerFactory.getLogger(EndpointServiceHandler.class);
    private ExecutionContext ec
    private EntityFacadeImpl efi
    private EntityHelper meh
    private EntityCondition.JoinOperator defaultListJoinOper = EntityCondition.JoinOperator.OR

    // attribute to allow writing to entities with companyId as an additional suffix (multi-company support)
    // if this string is filled-in, change the entityName used everywhere in the class
    private String companyId = null

    // customizing the calls to backend
    private static String CONST_UPDATE_IF_EXISTS                = 'updateIfExists'
    private static String CONST_ALLOWED_FIELDS                  = 'allowedFields'
    private static String CONST_SORT_OUTPUT_MAP                 = 'sortOutputMap'
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
    // if this flag is set, use data field to construct query condition
    // e.g. useful when using this class to modify data without providing
    // explicit query conditions (BulkEntityHandler)
    private static String CONST_SEARCH_USING_DATA_PROVIDED      = 'searchUsingDataProvided'
    // explicit update forbidding
    private static String CONST_FORBID_DATABASE_UPDATE          = 'forbidDatabaseUpdate'
    // field that stores identity ID, it shall be used to create a condition term
    private static String CONST_IDENTITY_ID_FOR_SEARCH          = 'identitySearch'
    // do we need timezone information?
    private static String CONST_REQ_TIMEZONE_FORMAT             = 'timeZoneInDatesFormat'
    private static String CONST_REQ_DATE_FIELD_FORMAT           = 'requiredDateFormat'
    private static String CONST_CREATE_OR_UPDATE                = 'requiredCreateOrUpdate'
    private static String CONST_GENERATE_RANDOM_SECONDARY       = 'generateRandomSecondary'
    private static String CONST_GENERATE_UUID_PRIMARY           = 'generateUUID'
    // add _entity to output (e.g. when we need to create a JSON for importing)
    private static String CONST_INCLUDE_ENTITY_NAME             = 'includeEntityName'
    private static String CONST_INCLUDE_ENDPOINT_SERVICE_NAME   = 'includeEndpointServiceName'
    // if set to true, only data gets returned, no pagination included
    private static String CONST_LEAN_OUTPUT                     = 'leanOutput'
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
    private Integer pageIndex = 0
    private String dsType

    // specific features, e.g. composite fields - used to return data from maps
    private ArrayList<String> compositeFields = new ArrayList<>()

    public static HashMap defaultErrorResponse(String message)
    {
        return [
                result: false,
                message: message
        ]
    }

    EndpointServiceHandler(
            ExecutionContext executionContext,
            String companyId,
            HashMap args,
            ArrayList term,
            String entityName,
            String tableName,
            ArrayList serviceAllowedOn)
    {
        this.ec = executionContext ?: Moqui.getExecutionContext()
        this.companyId = companyId
        this.efi = (EntityFacadeImpl) this.ec.entity
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

        logger.debug("Username when initializing ESH: ${ec.user.username}")
    }

    EndpointServiceHandler(ExecutionContext executionContext, String companyId) {
        this.ec = executionContext ?: Moqui.getExecutionContext()
        this.efi = (EntityFacadeImpl) this.ec.entity
        this.companyId = companyId
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
            String itString = it.toString()
            //get value which is allowed
            def rec = Pattern.compile(itString)
            //if rec contains *, we allow each entity which starts with value before *
            //e.g. Test*, would match TestDocument
            if (itString.contains("*"))
                if (this.entityName.startsWith(itString.substring(0,itString.length() - 1))) return true
            //else allow if the entity matches rec
            return rec.matcher(this.entityName).matches()
        }
    }

    private void fillEntityName(String inputEntityName, String inputTableName)
    {
        // do we have an entity?
        // try extracting from table name
        if (!inputEntityName) {
            if (!inputTableName) throw new EntityException("Missing both entity and table name")

            this.ed = meh.getDefinition(inputTableName)
            if (!ed) throw new EntityException("Unable to find EntityDefinition for '${inputTableName}'")
            this.entityName = ed.fullEntityName
        } else {
            this.entityName = inputEntityName
        }

        // set suffix
        if (this.companyId) {
            logger.debug("Company ID [${this.companyId}] is being used when working with entity: ${this.entityName}")

            // if entityName contains `@`, modify entity name
            // correct pattern is `<entityName>@<appendix>__<companyId>`
            if (this.entityName.contains('@')){
                this.entityName = "${this.entityName}__${this.companyId}"
            } else {
                this.entityName = "${this.entityName}@${this.companyId}"
            }
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

        // extract specific features
        this.checkCompositeFields()

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
     * Method checks if there are any composite fields in arguments,
     * if they are set, specific variables are set and loaded
     */
    private void checkCompositeFields()
    {
        // no need to set it, we require all fields to be returned
        if (args[CONST_ALLOWED_FIELDS] == '*') return

        // check if there is a dot, anywhere in the list of fields
        ArrayList<String> fieldsList = (ArrayList<String>) args[CONST_ALLOWED_FIELDS]
        def compositeFieldsSet = fieldsList.any{String fieldName->
            return fieldName.contains(".")
        }
        if (!compositeFieldsSet) return

        // now we know there are some included
        fieldsList.each {fieldName->
            if (fieldName.contains(".")) this.compositeFields.add(fieldName)
        }

        // remove composite fields from standard list of fields, we shall
        // treat this field differently
        this.compositeFields.each {specialFieldName->
            fieldsList.removeElement(specialFieldName)
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

        // by default, sort output map
        if (!args.containsKey(CONST_SORT_OUTPUT_MAP))
        {
            args.put(CONST_SORT_OUTPUT_MAP, true)
        }

        // do not allow search by using data provided
        if (!args.containsKey(CONST_SEARCH_USING_DATA_PROVIDED))
        {
            args.put(CONST_SEARCH_USING_DATA_PROVIDED, false)
        }

        // by default, not set
        if (!args.containsKey(CONST_FORBID_DATABASE_UPDATE))
        {
            args.put(CONST_FORBID_DATABASE_UPDATE, false)
        }

        // set term from args, if `identity` passed in
        if (args.containsKey(CONST_IDENTITY_ID_FOR_SEARCH))
        {
            // only supported for single primary key entities
            def hasSinglePrimaryKey = this.ed.pkFieldNames.size() == 1
            if (this.term.empty && hasSinglePrimaryKey)
            {
                this.term.add([field: this.ed.pkFieldNames[0], value: args[CONST_IDENTITY_ID_FOR_SEARCH]])
            }

            // remove from args, no need to store it
            args.remove(CONST_IDENTITY_ID_FOR_SEARCH)
        }

        // create/update
        if (!args.containsKey(CONST_CREATE_OR_UPDATE)) {
            args.put(CONST_CREATE_OR_UPDATE, false)
        }

        // random primary?
        if (!args.containsKey(CONST_GENERATE_UUID_PRIMARY)) {
            args.put(CONST_GENERATE_UUID_PRIMARY, false)
        }

        // do we need random secondaryId? By default, not exactly
        if (!args.containsKey(CONST_GENERATE_RANDOM_SECONDARY)) {
            args.put(CONST_GENERATE_RANDOM_SECONDARY, false)
        }

        // by default, do not put entity name into the result
        if (!args.containsKey(CONST_INCLUDE_ENTITY_NAME)) {
            args.put(CONST_INCLUDE_ENTITY_NAME, false)
        } else {
            if (args[CONST_INCLUDE_ENTITY_NAME]) {
                args.put(CONST_LEAN_OUTPUT, true)
            }
        }

        // use entity service name instead of entity-name
        if (!args.containsKey(CONST_INCLUDE_ENDPOINT_SERVICE_NAME)) {
            args.put(CONST_INCLUDE_ENDPOINT_SERVICE_NAME, false)
        } else {
            if (args[CONST_INCLUDE_ENDPOINT_SERVICE_NAME]) {
                args.put(CONST_LEAN_OUTPUT, true)
            }
        }

        // lean output switch, by default to false
        if (!args.containsKey(CONST_LEAN_OUTPUT))
        {
            args.put(CONST_LEAN_OUTPUT, false)
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

    /**
     * This method cleans up data that is about to be stored in database.
     * Is used for both create and update scenarios
     * @param ev
     * @param sourceData
     */
    private void sanitizeEntityValue(EntityValue ev, HashMap sourceData, boolean force)
    {
        sourceData.each {it->
            def col = (String) it.key
            def val = it.value
            def fi = ed.getFieldInfo(col)

            // for the case the column is unknown
            // either a mishap shall occur or a MongoDatabase is in place
            if (!fi)
            {
                // quit if not forced mode
                if (!force) return

                // modify entity data
                if (ev) ev.set(col, val)
                return
            } else {
                // for safer mode, let's quit
                if (fi.isPk && !force) return
            }

            // now we attempt to convert incoming value to the respective type
            // if the conversion fails, do not propagate the exception, just log it
            // let the exception be handled by the DB driver
            try {
                switch (fi.typeValue)
                {
                    case 4:
                        if (val.toString() == ''){ val = null; break }

                        def pt = 'yyyy-MM-dd HH:mm:ss'
                        if (val.toString().length() == 10) pt = 'yyyy-MM-dd'
                        DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pt)
                        LocalDate ld = LocalDate.parse(val.toString(), formatter);
                        val = Date.valueOf(ld)
                        break
                    case 5:
                        val = val.toString().toInteger()
                        break
                    case 6:
                        val = val.toString().toLong()
                        break
                    case 7:
                        val = val.toString().toFloat()
                        break
                    case 8:
                        val = val.toString().toDouble()
                        break
                    case 9:
                        val = val.toString().toBigDecimal()
                        break
                }
            } catch (Exception conversion){
                logger.error("Error converting value in sanitizeEntityValue method: ${conversion.message}")
            }

            // store in map that shall be used to set values in the DB
            // if no entity data, modify the sourceMap
            if (ev) ev.set(col, val)
            if (!ev) sourceData.replace(col, val)
        }
    }

    /**
     * Converts date fields to either formatted string (DATE) or local-date (TZ), otherwise returns original value
     * @param incoming
     * @return
     */
    private Object sanitizeDates(Object incoming, FieldInfo fi)
    {
        // fix null in dates
        if (fi != null)
        {
            if ((fi.typeValue == 2 || fi.typeValue == 3 || fi.typeValue == 4) && incoming.toString() == '') {
                return null
            }
        }

        // only for those two types
        if (incoming.getClass() != Date.class && incoming.getClass() != Timestamp.class) return incoming

        switch (incoming.getClass())
        {
            case Date.class:
                if (!this.args.containsKey(CONST_REQ_DATE_FIELD_FORMAT)) return incoming
                try {
                    def dt = (Date) incoming
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern((String) this.args[CONST_REQ_DATE_FIELD_FORMAT])
                    def ld = dt.toLocalDate()
                    return ld.format(formatter)
                } catch (Exception exc) {
                    logger.error("Error while converting Date to a custom format: ${exc.message}")
                }
                return incoming
            case Timestamp.class:
                if (!this.args.containsKey(CONST_REQ_TIMEZONE_FORMAT)) return incoming
                try {
                    def ts = (Timestamp) incoming
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern((String) this.args[CONST_REQ_TIMEZONE_FORMAT])
                    return ts.toLocalDateTime().format(formatter)
                } catch (Exception exc) {
                    logger.error("Error while converting Timestamp to a custom format: ${exc.message}")
                }
                return incoming
        }
        return incoming
    }

    private Object fillResultset(EntityValue single)
    {
        // we need unordered list of keys
        LinkedHashMap<String, Object> recordMap = [:]
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
            def itVal = sanitizeDates(it.value, pkInfo)

            // special treatment for maps
            // convert HashMap, watch out if it's array
            if (it.isMapField()) itVal = GenericUtilities.processField(it.value)
            recordMap.put(fieldName, itVal)
        }

        // handle specialities
        def modifiedOrder = false

        // 1. record ID
        modifiedOrder = this.manipulateRecordId(recordMap)

        // 2. extra fields
        modifiedOrder |= this.manipulateExtraFields(recordMap)

        // 3. composite fields handling
        this.compositeFields.each {String fieldName->
            def keyUsed = CollectionUtils.keyInUse(fieldName)
            def foundValue = CollectionUtils.findKeyInMap((HashMap) single, fieldName, Object.class, null)
            if (!foundValue) {
                // insert a default value
                recordMap.put(keyUsed, null)
                return
            }

            // last item name to be used
            recordMap.put(keyUsed, foundValue)
        }

        // add to output, sorted if necessary
        if (args.get(CONST_SORT_OUTPUT_MAP))
        {
            recordMap = (HashMap) recordMap.sort({m1, m2 -> m1.key <=> m2.key})
        } else if (!modifiedOrder) {
            // sort using allowed columns, if set
            // BUT ONLY IF ORDER HAS NOT BEEN MODIFIED
            def argAllowed = args.get(CONST_ALLOWED_FIELDS)
            def allowedColumns = new ArrayList<String>()
            if (argAllowed.class == String.class && argAllowed.toString() == '*')
            {
                allowedColumns.add('*')
            } else {
                (ArrayList<String>) args.get(CONST_ALLOWED_FIELDS).each {String col->
                    allowedColumns.add(col)
                }
            }
            def onlyNames = allowedColumns.every {it->
                return it != '*' && !it.contains('-')
            }

            // sort accordingly, using the list provided
            if (onlyNames){
                LinkedHashMap sorted = new LinkedHashMap()
                allowedColumns.each {it->
                    sorted[it] = recordMap[it]
                }
                recordMap = sorted
            }
        }

        // entity-name
        if (args[CONST_INCLUDE_ENTITY_NAME] == true) {
            recordMap.put('_entity', entityName)
        } else if (args[CONST_INCLUDE_ENDPOINT_SERVICE_NAME] == true) {
            LinkedHashMap<String, Object> newRecordMap = [data: recordMap]
            newRecordMap.put('_entity', 'ars.rockycube.EndpointServices.create#EntityData')
            newRecordMap.put('entityName', entityName)
            recordMap = newRecordMap
        }

        // change to list, if set in such way
        if (args[CONST_CONVERT_OUTPUT_TO_LIST] == true) {
            def conv2List = []
            recordMap.each { it ->
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
                if (!recordMap.containsKey(f)) continue

                // save for later addition
                // + pop from sorted map
                savedFields.put(f, recordMap[f])
                recordMap.remove(f)
            }

            def preliminary = CollectionUtilities.flattenNestedMap(recordMap)
            if (savedFields.isEmpty()) return preliminary

            // store back in output in original form
            for (k in savedFields.keySet()) preliminary.put(k, savedFields[k])
            return preliminary
        } else {
            return recordMap
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

    /**
     * Method that checks whether fields are allowed, specifically stamp fields
     * @param fieldName
     * @return
     */
    private boolean addField(String fieldName)
    {
        // allow timestamps? must be explicitly set
        def timestamps = ["lastUpdatedStamp", "creationTime", "created"]
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
        if (term.getClass() != ArrayList.class) throw new EntityException("Term can only be initialized when being an ArrayList")

        // initialize correctly, as a list
        ArrayList<HashMap<String, Object>> arrTerm = (ArrayList<HashMap<String, Object>>) term

        // short circuit to result
        if (arrTerm.empty) return null

        // should there be an argument specifying how the complex condition
        // must be set, let's do it this way
        if (this.args.containsKey(CONST_COMPLEX_CONDITION_RULE)) {
            return this.extractComplexCondition(arrTerm, this.args[CONST_COMPLEX_CONDITION_RULE] as String)
        }

        // otherwise make it simple and add `OR` between all conditions
        def resListCondition = []
        for (HashMap<String, Object> singleTerm in arrTerm) resListCondition.add(ConditionHandler.getSingleFieldCondition(singleTerm))
        // return null if condition list is null
        if (resListCondition.empty) return null
        // otherwise construct a list
        return new ListCondition(resListCondition, this.defaultListJoinOper)
    }

    private HashMap fetchPaginationInfo(
            Integer pageIndex,
            Integer pageSize)
    {
        def res = [:]
        def allEntriesSearch = efi.find(entityName)
        if (queryCondition) allEntriesSearch.condition(queryCondition)

        res['size'] = pageSize
        res['page'] = pageIndex + 1
        res['rows'] = allEntriesSearch.count()
        return res
    }

    private boolean manipulateRecordId(HashMap<String, Object> record)
    {
        if (!args.get('renameId', null)) return false

        // modify ID to a new value
        def newIdField = args.get('renameId').toString()

        // make sure it's a unique name
        Number occurrences = record.count {it->
            return newIdField == it.key
        }

        if (occurrences != 0)
        {
            logger.error("Field exist, cannot be renamed")
            return false
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
            return false
        }

        record[newIdField] = idValue
        record.remove(idFieldName)
        return true
    }

    private boolean manipulateExtraFields(HashMap<String, Object> record)
    {
        if (!args.get('removeFields', null)) false

        def modified = false
        def fields2remove = (ArrayList) args['removeFields']
        fields2remove.each {fld->
            if (ed.isPkField(fld)) return
            if (!record.containsKey(fld)) return

            // remove key
            record.remove(fld)

            modified = true
        }
        return modified
    }

    public HashMap createEntityData(Object data)
    {
        // ArrayList supported, this way we can run multiple create procedures in single moment
        // if (data.getClass().simpleName.startsWith('ArrayList')) throw new EntityException("Creating multiple entities not supported")

        def itemsCreated = 0
        HashMap<String, Object> lastItemResult = [:]

        // different for array and hashmap
        if (data instanceof HashMap || data instanceof LinkedHashMap || data instanceof LinkedTreeMap || data instanceof LazyMap) {
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

    private EntityConditionImplBase extractQueryConditionFromData(HashMap data)
    {
        // this thing will be returned
        EntityConditionImplBase result

        // search for primary key in the data
        def pkNames = this.ed.pkFieldNames
        if (pkNames.size() == 1)
        {
            def pkName = pkNames[0]
            if (data.containsKey(pkName)){
                logger.debug("Updating search query for purpose of checking existence of record with value [${[pkName: data[pkName]]}]")
                result = new FieldValueCondition(
                        new ConditionField(pkName),
                        EntityCondition.ComparisonOperator.EQUALS,
                        data[pkName]
                )

                return result
            }
        }

        return null
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
        // support for extraction of query condition from data provided introduced
        EntityConditionImplBase queryUsed = queryCondition
        // if
        // 1. no query is provided from the constructor
        // 2. search using data provided is switched on
        // 3. data provided is not empty
        // then try to set new query condition
        if (!queryUsed && args.get(CONST_SEARCH_USING_DATA_PROVIDED) && !singleEntityData.isEmpty())
        {
            queryUsed = extractQueryConditionFromData(singleEntityData)
        }

        // otherwise perform clean write
        if (args.get(CONST_UPDATE_IF_EXISTS) && queryUsed)
        {
            def alreadyExists = ec.entity.find(entityName).condition(queryUsed)
            if (alreadyExists.count() == 1)
            {
                return this.updateSingleEntity(alreadyExists, singleEntityData)
            } else if (alreadyExists.count() > 1)
            {
                return defaultErrorResponse("Unable to perform create/update, multiple entries exist")
            }
        }

        // sanitize (differently that in update) HashMap
        this.sanitizeEntityValue(null, singleEntityData, false)

        def created = ec.entity.makeValue(entityName)
                .setAll(singleEntityData)

        // explicit forbid update, on database level
        if (args.get(CONST_FORBID_DATABASE_UPDATE)) { created.forbidDatabaseUpdate() }

        // create primary key
        if (args[CONST_AUTO_CREATE_PKEY] == true) {
            // create ID - either incremental counter (by Moqui) or generated UUID
            def createPrimaryId = {EntityValue itemCreated, String primaryIdField-> {
                if (args.get(CONST_GENERATE_UUID_PRIMARY)) {
                    itemCreated.set(primaryIdField, UUID.randomUUID())
                } else {
                    created.setSequencedIdPrimary()
                }
            }}

            // helper function that creates secondary ID
            def createSecondaryId = {EntityValue itemBeingCreated, String secondaryIdField-> {
                if (args.get(CONST_GENERATE_RANDOM_SECONDARY)) {
                    itemBeingCreated.set(secondaryIdField, RandomStringUtils.randomAlphanumeric(10))
                } else {
                    itemBeingCreated.setSequencedIdSecondary()
                }
            }}

            // do not automatically generate, if the ID is among data provided
            def pk = this.findPrimaryKey()
            if (!pk) {
                throw new EndpointException("Cannot create ID for a record, that has no entity definition")
            } else if (pk.size() == 1) {
                if (!singleEntityData.containsKey(pk[0])) createPrimaryId(created, (String) pk[0])
                if (singleEntityData.containsKey(pk[0])) if (!singleEntityData[pk[0]]) createPrimaryId(created, (String) pk[0])
            } else if (pk.size() == 0) {
                throw new EndpointException("Cannot create ID for a record, that has no primary keys in definition")
            } else if (pk.size() == 2) {
                // create ID manually
                // if not contained in data provided
                // must check more than simple `containsKey`, the value under key may be null
                if (!singleEntityData.containsKey(pk[0])) createPrimaryId(created, (String) pk[0])
                if (singleEntityData.containsKey(pk[0])) if (!singleEntityData[pk[0]]) createPrimaryId(created, (String) pk[0])

                // the same for second PK
                if (!singleEntityData.containsKey(pk[1])) createSecondaryId(created, (String) pk[1])
                if (singleEntityData.containsKey(pk[1])) if (!singleEntityData[pk[1]]) createSecondaryId(created, (String) pk[1])
            }
        }

        // let it create
        if (args[CONST_CREATE_OR_UPDATE] == true)
        {
            created.createOrUpdate()
        } else {
            created.create()
        }

        // get result back to the caller
        return [
                result : true,
                message: "Records created (1)",
                data   : args[CONST_PREFER_OBJECT_IN_RETURN] ? fillResultset(created) : [fillResultset(created)]
        ]
    }

    /**
     * Run standard entity delete procedure, but allow the query condition to be
     * modified if set in such condition
     * @param data
     * @return
     */
    public HashMap deleteEntityData(HashMap data)
    {
        if (!queryCondition && args.get(CONST_SEARCH_USING_DATA_PROVIDED) && !data.isEmpty())
        {
            queryCondition = extractQueryConditionFromData(data)
        }
        return deleteEntityData()
    }

    /**
     * Delete data from context
     * @return
     */
    public HashMap deleteEntityData()
    {
        def toDeleteSearch = ec.entity.find(entityName)
        if (queryCondition) toDeleteSearch.condition(queryCondition)
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
    private Object fetchEntityData_standard()
    {
        // we do not need pagination info at all times
        def useLeanOutput = args[CONST_LEAN_OUTPUT]
        def evs = efi.find(entityName)
                .limit(pageSize)
                .offset(pageIndex * pageSize)

        if (queryCondition) evs.condition(queryCondition)

        // order by columns
        if (orderBy) evs.orderBy(orderBy)

        // update pagination info, so that count of rows being displayed is returned
        def result = evs.list()
        if (!useLeanOutput) {

            // pagination info
            def pagination = this.fetchPaginationInfo(pageIndex, pageSize)
            pagination['displayed'] = result.size()
            logger.debug("pagination: ${pagination}")

            return [
                    result    : true,
                    data      : this.fillResultset(result),
                    pagination: pagination
            ]
        } else {
            // cannot use `returnObject` flag
            args[CONST_PREFER_OBJECT_IN_RETURN] = false
            return (ArrayList) this.fillResultset(result)
        }
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
        def resIds = queryCondition?qh.fetch(queryCondition):qh.fetch(null)

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

    public Object fetchEntityData(Integer index, Integer size, ArrayList orderBy)
    {
        this.setInputIndex(index)
        this.pageSize = size
        this.orderBy = orderBy

        return fetchEntityData()
    }

    public Object fetchEntityData()
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
                .forUpdate(true)
        if (queryCondition) toUpdate.condition(queryCondition)
        logger.debug("UPDATE: entityName/term: ${entityName}/${queryCondition}")

        // one more derail, allow for record creation
        if (args[CONST_CREATE_OR_UPDATE])
        {
            return this.createSingleEntity(data)
        }

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

        // sanitize values
        this.sanitizeEntityValue(mod, updateData, true)

        // save
        mod.update()

        return [
                result : true,
                message: "Records updated (1)",
                data   : args[CONST_PREFER_OBJECT_IN_RETURN] ? fillResultset(mod) : [fillResultset(mod)]
        ]
    }

    public static void sendRenderedResponse(String entityName, ArrayList term, LinkedHashMap args, String templateName, boolean inline) {
        // add args
        if (!args.containsKey('preferObjectInReturn')) args['preferObjectInReturn'] = true

        // initialize EC
        def ec = Moqui.getExecutionContext()

        try {
            // 1. load data, use endpoint handler (and terms) to locate data
            def reportData = ec.service.sync().name("ars.rockycube.EndpointServices.populate#EntityData").parameters([
                    entityName: entityName,
                    term      : term,
                    args      : args
            ]).call() as HashMap

            // log error if provided by the called service
            if (ec.message.hasError()) ec.logger.error(ec.message.getErrors()[-1])

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
        RestResponse restResponse = restClient.call()

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
            Object proceduresList,
            HashMap extraParams,
            Closure cbCheckData,
            boolean extractPycalcArgs,
            boolean debug,
            String sessionId = null)
    {
        def pycalcHost = System.properties.get("py.server.host")

        // if there is a procHistoryId in the request, use it
        def execHistoryId = ec.web ? ec.web.request.getHeader("ARS-execHistoryId") : null
        def processingId = ec.web ? ec.web.request.getHeader("ARS-processingId") : 'nop'

        // set sessionId if not set
        if (debug && !sessionId) sessionId = StringUtilities.getRandomString(11)

        // store info for debugging purposes
        if (debug) {
            debugFile(ec, processingId, sessionId, "process-items.json", itemToCalculate)
            debugFile(ec, processingId, sessionId, "process-items-procedures.json", proceduresList)
            debugFile(ec, processingId, sessionId, "process-items-extra.json", extraParams)
        }

        // basic checks
        if (!pycalcHost) throw new EndpointException("PY-CALC server host not defined")

        // if the incoming data is InputStream, encode it to Base64
        boolean encodeToBase64 = itemToCalculate instanceof InputStream

        // data prep
        def payload = [
                setup: [
                        procedure: proceduresList,
                        output_only_last: true,
                        extra: extractPycalcArgs ? ViUtilities.extractPycalcArgs(extraParams) : extraParams,
                        proc_id: processingId,
                        session_id: sessionId
                ],
                data: encodeToBase64 ? Base64.encoder.encodeToString((itemToCalculate as InputStream).getBytes()) : itemToCalculate
        ]

        // debug what is going to py-calc
        if (debug) debugFile(ec, processingId, sessionId, "c-h-process-items-to-execute.json", payload)

        // use specific RequestFactory, with custom timeouts
        // timeout is set by settings
        def prop = (String) System.getProperty("py.server.request.timeout", '45000')
        def calcTimeout = prop.toLong()

        // pass ARS headers further down-stream
        HashMap<String, String> headers = new HashMap()
        headers.put("Content-Type", "application/json")
        if (execHistoryId) headers.put("ARS-execHistoryId", execHistoryId)
        if (processingId) headers.put("ARS-processingId", processingId)

        RestClient restClient = ec.service.rest().method(RestClient.POST)
                .uri("${pycalcHost}/api/v1/calculator/execute")
                .timeout(calcTimeout/1000 as int)
                .retry(2, 10)
                .maxResponseSize(50 * 1024 * 1024)
                .addHeaders(headers)
                .jsonObject(payload)

        RestResponse resp = restClient.call()
        checkPyCalcResponse(resp)

        // must handle all states of the response
        def rsp = (HashMap) resp.jsonObject()

        // debug what has come out of the processing
        if (debug) debugFile(ec, processingId, sessionId, "c-h-process-items-result.json", resp.jsonObject())

        // use callback to check/modify response
        if (cbCheckData) {
            rsp = cbCheckData(rsp)

            // another layer of processing
            if (debug) debugFile(ec, processingId, sessionId, "c-h-process-items-result-mod.json", rsp)
        }

        return rsp
    }

    /**
     * Method checks response from PY-CALC and throws an error, should
     * en unexpected response arrive
     * @param restResponse
     */
    private static void checkPyCalcResponse(RestResponse restResponse) {

        // check status code
        if (restResponse.statusCode != 200) {
            def errMessage = "Response with status ${restResponse.statusCode} returned: ${restResponse.reasonPhrase}"
            // display more info depending on what is being returned
            if (restResponse.headers().containsKey('x-exception-detail'))
            {
                errMessage = restResponse.headerFirst('x-exception-detail')
            }
            throw new EndpointException(errMessage)
        }
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

    public static Object processItemsForVizualization(
            ExecutionContext ec,
            ArrayList allItems,
            String endpoint,
            HashMap args,
            boolean debug,
            String identity = null)

    {
        def pycalcHost = System.properties.get("py.server.host")

        // set identity if not set
        if (debug && !identity) identity = StringUtilities.getRandomString(11)

        // debug for development purposes
        if (debug) debugFile(ec, null, null, "vizualize-items-before-calc.${identity}.json", allItems)

        // pass only those arguments, that have `pycalc` prefix
        def pyCalcArgs = ViUtilities.extractPycalcArgs(args as HashMap)

        // store parameters
        if (debug) debugFile(ec, null, null, "vizualize-items-calc-params.${identity}.json", pyCalcArgs)

        RestClient restClient = ec.service.rest().method(RestClient.POST)
                .uri("${pycalcHost}/${endpoint}")
                .addHeader("Content-Type", "application/json")
                .jsonObject(
                        [
                                data: allItems,
                                args: pyCalcArgs
                        ]
                )
        RestResponse resp = restClient.call()
        checkPyCalcResponse(resp)

        HashMap response = resp.jsonObject() as HashMap
        return response['data']
    }

    /**
     * Fetch items from a Sharepoint list, provided we have credentials
     * @param ec
     * @param credentials
     * @param location
     * @return
     */
    public static ArrayList fetchItemsFromSpList(
            ExecutionContext ec,
            HashMap credentials,
            HashMap location) {

        // use existing method and return bytes
        def b = sendJsonToSharepoint(ec, credentials, location, 'api/v1/utility/sharepoint/fetch-list')

        return (ArrayList) b.jsonObject()
    }

    /**
     * Import data to Sharepoint via py-calc call, supports content as a list
     * @param ec
     * @param credentials
     * @param location
     * @param content
     * @param method
     * @return
     */
    public static RestResponse genericSendJsonToSharepoint(
            ExecutionContext ec,
            HashMap credentials,
            HashMap location,
            String endpoint,
            ArrayList content,
            RestClient.Method method
    ) {
        def pycalcHost = System.properties.get("py.server.host")
        if (!pycalcHost) throw new EndpointException("PY-CALC server host not defined")

        // timeout
        def prop = (String) System.getProperty("py.server.request.timeout", '45000')
        def calcTimeout = prop.toLong()

        // check if we can use the caller's request headers here
        // it may be a sound solution to pass configuration parameters
        // from Apache Camel and use them to customize the next call
        Map<String, String> selectedHeaders = new HashMap()
        if (ec.web) {
            def headerNames = ec.web.request.headerNames
            headerNames.each {
                if (it.startsWith("ARS")) {
                    ec.logger.debug("Using header for subsequent call: ${it}")
                    selectedHeaders[it] = ec.web.request.getHeader(it)
                }
            }
        }

        ec.logger.debug("ARS headers used: ${selectedHeaders}")

        // data prep
        // @todo consider moving credentials to header
        HashMap<String, Object> payload = [credentials: credentials, location:location]
        // add content if provided
        if (content) if (!content.empty) payload.put('data', content)
        def customTimeoutReqFactory = new RestClient.SimpleRequestFactory(calcTimeout)

        RestClient restClient = ec.service.rest().method(method)
                .uri("${pycalcHost}/${endpoint}")
                .addHeaders(selectedHeaders)
                .timeout(480)
                .retry(2, 10)
                .maxResponseSize(50 * 1024 * 1024)
                .jsonObject(payload)
                .withRequestFactory(customTimeoutReqFactory)

        // execute
        RestResponse resp = restClient.call()
        checkPyCalcResponse(resp)

        // return response
        return resp
    }

    /**
     * Call against SharePoint API using JSON
     * @param ec
     * @param credentials - shall be used to initialize the connection to Sharepoint (e.g. tenantId, clientId, clientSecret)
     * @param location - where is the file located
     * @param contentType - JSON?
     * @return
     */
    public static RestResponse sendJsonToSharepoint(
            ExecutionContext ec,
            HashMap credentials,
            HashMap location,
            String endpoint='api/v1/utility/sharepoint/fetch-bytes',
            RestClient.Method method = RestClient.Method.POST)
    {
        return genericSendJsonToSharepoint(ec, credentials, location, endpoint, null, method)
    }
}