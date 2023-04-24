package dtq.rockycube.entity

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityException
import org.moqui.entity.EntityFind
import org.moqui.impl.ViUtilities
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
    public static EntityFind filterEntity(ExecutionContext ec, String entityName, Object filter){
        // if filter is NULL, return all
        if (filter == null) return ec.entity.find(entityName)

        switch (filter.getClass())
        {
            case HashMap.class:
            case LinkedHashMap.class:
                HashMap filterMap = (HashMap) filter
                return ec.entity.find(entityName).condition(filterMap)
            case EntityCondition.class:
                return ec.entity.find(entityName).condition((EntityCondition) filter)
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

    private <K, V> Map.Entry<K, V> foundEntity(
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
        Map.Entry<String, Object> foundEntityDef = foundEntity(
                efi.frameworkEntityDefinitions.iterator(),
                recSearchEntity,
                groupName)

        if (!foundEntityDef) {
            // 2. search in normal entities
            foundEntityDef = foundEntity(
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

        def recEntitySearch = Pattern.compile(searchedEntityName)
        return getDefinition(recEntitySearch, groupName)
    }
}
