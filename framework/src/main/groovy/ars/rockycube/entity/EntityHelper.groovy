package ars.rockycube.entity

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityDataLoader
import org.moqui.entity.EntityException
import org.moqui.entity.EntityFind
import org.moqui.impl.entity.EntityDbMeta
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl

import javax.sql.rowset.serial.SerialBlob
import java.util.regex.Pattern

class EntityHelper {
    // contexts and facades
    private ExecutionContext ec
    private EntityFacadeImpl efi

    EntityHelper(ExecutionContext ec){
        this.ec = ec

        // EntityFacadeImpl
        efi = (EntityFacadeImpl) ec.getEntity()
    }

    /**
     * Extract BLOB content from an entity
     * TESTED in Framework - `test file upload - using a service (obo IPS)`
     * @param fullEntityName
     * @param conditionMap
     * @param fileInfo - fill a map with additional info on the file being fetched
     * @param disableAuthz - no authorization checks
     * @return
     */
    public InputStream getEntityByteContent(
            String fullEntityName,
            Map<String, Object> conditionMap,
            Map<String, Object> fileInfo = null,
            boolean disableAuthz=false){
        EntityFind search = null
        // allow disabling authorization for special cases
        // BE SURE TO CHECK AUTHORIZATION BEFOREHAND
        if (disableAuthz) {
            search = ec.entity.find(fullEntityName).condition(conditionMap).disableAuthz()
        } else {
            search = ec.entity.find(fullEntityName).condition(conditionMap)
        }
        if (search.count() == 0) throw new EntityException("No records found, cannot return Entity's byte content")
        if (search.count() > 1) throw new EntityException("Multiple records found, cannot pick which record to return byte content from")
        def document = search.one()

        // search for BLOB field
        def ed = efi.getEntityDefinition(fullEntityName)
        def doFillFileInfo = fileInfo != null

        def blobFields = ed.allFieldNames.findAll {String fieldName ->
            def fi = ed.getFieldInfo(fieldName)
            def javaSqlBlobType = 'java.sql.blob'
            boolean isBlob = fi.javaType.toLowerCase() == javaSqlBlobType

            // fill information about the file
            if (doFillFileInfo && !isBlob) {
                fileInfo.put(fieldName, document.get(fieldName))
            }

            return isBlob
        }

        // Take first blob field
        if (blobFields.size() > 1) ec.logger.warn("More BLOB fields found.")
        def blobFieldName = blobFields ? blobFields.get(0) : null
        if (!blobFieldName) throw new EntityException("No BLOB field found.")

        SerialBlob blob = document.get(blobFieldName)
        return blob.getBinaryStream()
    }

    /**
     * Method is used to filter entity by providing it name and passing filter
     * as an object
     * @param ec
     * @param entityName
     * @param filter
     * @return
     */
    public static EntityFind filterEntity(ExecutionContext ec, String entityName, Object filter, ArrayList fields=[]){
        // if filter is NULL, return all
        if (filter == null) {
            if (!fields.empty) return ec.entity.find(entityName).selectFields(fields)
            return ec.entity.find(entityName)
        }

        switch (filter.getClass())
        {
            case HashMap.class:
            case LinkedHashMap.class:
                HashMap filterMap = (HashMap) filter
                def f = ec.entity.find(entityName).condition(filterMap)
                if (!fields.empty) return f.selectFields(fields)
                return f
            case EntityCondition.class:
                def f = ec.entity.find(entityName).condition((EntityCondition) filter)
                if (!fields.empty) return f.selectFields(fields)
                return f
            default:
                throw new EntityException("Unsupported filter when searching in entity [${filter.getClass().name}]")
        }
    }

    public static String findDatasource(ExecutionContext ec, clMatchSource)
    {
        EntityFacadeImpl efi = (EntityFacadeImpl) ec.getEntity()
        def sources = efi.getDataSourcesInfo()

        def foundSource = sources.find {it->
            def details = [
                    databaseName: it.get('database', null),
                    datasourceFactory: it.get('dsFactory'),
                    group: it.get('group')
            ]

            if (clMatchSource(details)) return true
            return false
        }

        return foundSource?foundSource.get("group"):null
    }

    private <K, V> Map.Entry<K, V> findEntity(
            Iterator iterator,
            Pattern recSearchEntity,
            String groupName) {

        def res = null
        while (iterator.hasNext())
        {
            def it = iterator.next()

            String actualKey = it.key
            EntityDefinition actualEd = (EntityDefinition) it.value
            def match = recSearchEntity.matcher(actualKey)
            boolean strictMatch = match.matches()
            // if group name set, confirm match
            if (groupName) strictMatch &= (actualEd.groupName == groupName)
            if (strictMatch)
            {
                res = it
                break
            }
        }

        // for debugging purposes
        return res as Map.Entry<K, V>
    }

    public EntityDefinition getDefinition(Pattern recSearchEntity, String groupName = null)
    {
        // 1. search among framework entities
        Map.Entry<String, Object> foundEntityDef = findEntity(
                efi.frameworkEntityDefinitions.iterator(),
                recSearchEntity,
                groupName)

        if (!foundEntityDef) {
            // 2. search in normal entities
            foundEntityDef = findEntity(
                    efi.entityDefinitionCache.iterator(),
                    recSearchEntity,
                    groupName)
        }

        if (!foundEntityDef) return null
        return foundEntityDef.value as EntityDefinition
    }

    public EntityDefinition getDefinition(String entityName, String groupName = null)
    {
        // treat special entities
        def recSpecial = Pattern.compile("(.+)@(?:.+)")
        def match = recSpecial.matcher(entityName.toString())
        def searchedEntityName = entityName
        if (match.matches()) searchedEntityName = match.group(1)

        def entityDef = this.efi.getEntityDefinition(searchedEntityName)
        if (!entityDef) return null
        if (!groupName) return entityDef

        // test groupName condition, otherwise return null
        if (groupName == entityDef.groupName) return entityDef
        return null
    }

    public static long importEntityFromJson(
            ExecutionContext ec,
            String jsonText,
            List<String> messages) {
        EntityDataLoader edl = ec.entity.makeDataLoader()
        edl.jsonText(jsonText)
        edl.onlyCreate(false)
        return edl.load(messages)
    }

    /**
     * This method is used to check/create table in a database, mostly to make sure table exists before using it
     * @param ec
     * @param entityName
     * @param groupName
     * @return
     */
    public static boolean checkRuntime(ExecutionContext ec, String entityName) {
        def efi = (EntityFacadeImpl) ec.getEntity()
        def edb = new EntityDbMeta(efi)
        def edn = efi.getEntityDefinition(entityName)
        return edb.internalCheckTable(edn, false)
    }
}
