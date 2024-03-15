package dtq.rockycube.entity

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityDataLoader
import org.moqui.entity.EntityException
import org.moqui.entity.EntityFind
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
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
}
