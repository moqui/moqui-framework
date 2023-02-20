package dtq.synchro

import org.moqui.context.ExecutionContextFactory
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityEcaRule
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache

/**
 * SynchroMaster is a tool that helps developer reach for data of a cached-table.
 * Primary purpose is to have a straightforward access to data that the application
 * frequently uses. E.g. in a project-economy based application, it's access to
 * projects list and statistics, since this data is frequently being polled, used
 * for dashboards and likes.
 *
 * Besides this basic functionality, it is useful to have some statistics at hand.
 */
class SynchroMaster {
    public enum ActivitySemaphore {
        ACTIVE,
        INACTIVE,
        NOT_SUPERVISED
    }

    protected final Logger logger = LoggerFactory.getLogger(SynchroMaster.class)
    protected ExecutionContextFactory ecf
    protected EntityFacadeImpl efi
    protected HashMap<String, ActivitySemaphore> semaphores = new HashMap<>()
    protected HashMap<String, Long> missedSyncs = new HashMap<>()

    // list of caches the master is taking care of
    protected ArrayList<String> syncedCaches = new ArrayList<>()

    // map of caches and related entities
    protected HashMap<String, ArrayList<String>> syncedEntities = new HashMap<>()

    SynchroMaster(ExecutionContextFactory ecf, ArrayList<String> cachesSetup)
    {
        this.ecf = ecf
        this.efi = (EntityFacadeImpl) ecf.entity

        // process each item in configuration list
        cachesSetup.each {it->
            def sourceEntity = it
            EntityDefinition ed = efi.getEntityDefinition(sourceEntity)
            def cacheName = this.startSynchronization(ed)

            // add cache name to list
            this.syncedCaches.add(cacheName)

            // if we have entity in our map, extend the list
            if (syncedEntities.containsKey(sourceEntity))
            {
                def existingCaches = syncedEntities.get(sourceEntity)
                existingCaches.add(cacheName)
                syncedEntities.replace(sourceEntity, existingCaches)
            } else {
                syncedEntities.put(sourceEntity, [cacheName])
            }

            // create manually an EECA rule for handling events on entity
            efi.addNewEecaRule(sourceEntity, createCrudRule(ed))
        }
    }

    private static String getCacheName(String entityName)
    {
        return "i.cache.${entityName}"
    }

    Cache getEntityCache(String entityName){
        if (!syncedEntities.containsKey(entityName)) throw new SynchroException("No such entity is being cached")
        return this.ecf.cache.getCache(getCacheName(entityName))
    }

    boolean getEntityIsSynced(String entityName)
    {
        return this.syncedEntities.containsKey(entityName)
    }

    boolean getIsSynced(String entityName) {
        if (!this.semaphores.containsKey(entityName)) return false
        return this.missedSyncs[entityName] == 0 && this.semaphores[entityName] == ActivitySemaphore.ACTIVE
    }

    Long getMissedSyncCounter(String entityName) {
        if (!this.semaphores.containsKey(entityName)) return -1
        return this.missedSyncs[entityName]
    }

    ActivitySemaphore getSemaphore(String entityName) {
        if (!getEntityIsSynced(entityName)) return ActivitySemaphore.NOT_SUPERVISED
        return this.semaphores[entityName]
    }

    public boolean disableSynchronization(EntityDefinition ed)
    {
        String entityName = ed.fullEntityName
        if (!this.semaphores.containsKey(entityName)) throw new SynchroException("Entity not being supervised")
        if (!this.getIsSynced(entityName)) {
            logger.warn("Missed syncs present, please check")
            return false
        }

        // no need to change anything
        if (this.semaphores[entityName] == ActivitySemaphore.INACTIVE) return false

        // switch and return true
        this.semaphores[entityName] = ActivitySemaphore.INACTIVE
        return true
    }

    public boolean enableSynchronization(EntityDefinition ed)
    {
        String entityName = ed.fullEntityName
        if (!this.semaphores.containsKey(entityName)) throw new SynchroException("Entity not being supervised")
        if (this.getIsSynced(entityName))
        {
            logger.warn("Synchronized, no need to make any changes")
            return
        }

        // reload cache
        return this.startSynchronization(ed) != ""
    }

    // initialize cache and upload data from database table
    private String startSynchronization(EntityDefinition ed)
    {
        String entityName = ed.fullEntityName
        def cacheName = getCacheName(entityName)
        def cache = ecf.executionContext.cache.getCache(cacheName)
        if (!checkEntityKeys(ed)) return

        // reset cache
        cache.removeAll()

        // reset semaphores and missed counter
        if (semaphores.containsKey(entityName)) {semaphores[entityName] = ActivitySemaphore.ACTIVE} else {semaphores.put(entityName, ActivitySemaphore.ACTIVE)}
        if (missedSyncs.containsKey(entityName)) {missedSyncs[entityName] = 0} else {missedSyncs.put(entityName, 0)}

        //  only add those fields that are in entity definition
        def fields = ed.nonPkFieldNames
        fields.add(ed.pkFieldNames[0])
        def itemsToUpload = ecf.entity.find(entityName)
                .disableAuthz()
                .selectFields(fields)
                .limit(5000)
                .list()
        for (def i in itemsToUpload)
        {
            cache.put(i.get(ed.pkFieldNames[0]), i)
        }

        // return cache name, needed when running init phase
        return cacheName
    }

    private EntityEcaRule createCrudRule(EntityDefinition ed){
        String entityName = ed.fullEntityName

        // create new MNode
        HashMap<String, String> eecaAttrs = [:]
        eecaAttrs.put("entity", entityName)
        eecaAttrs.put("on-create", "true")
        eecaAttrs.put("on-update", "true")
        eecaAttrs.put("on-delete", "true")
        eecaAttrs.put("run-on-error", "true")
        MNode eecaRuleNode = new MNode("eeca", eecaAttrs)

        String pk = ed.pkFieldNames[0]

        // create new EECA rule for entity
        def scriptNode = new MNode("script", [:], null, null, """
                    def tool = ec.getTool("SynchroMaster", dtq.synchro.SynchroMaster.class)
                    tool.reactToChange(eecaOperation, "${entityName}", entityValue.${pk})
                """)
        def actionNode = eecaRuleNode.append("actions", [:])
        actionNode.append(scriptNode)

        logger.debug("Creating CRUD EECA rule for ${entityName}")
        // logger.debug("Script: ${scriptNode}")

        return new EntityEcaRule((ExecutionContextFactoryImpl) this.ecf, eecaRuleNode, "")
    }

    private boolean checkEntityKeys(EntityDefinition ed)
    {
        // checks in-place
        if (!ed) {logger.error("Entity not found, SynchroMaster cannot refresh cache"); return false}
        if (ed.pkFieldNames.empty) {logger.error("Entity has not primary field key, SynchroMaster cannot refresh cache"); return false}
        if (ed.pkFieldNames.size() != 1) {logger.warn("Entities with more primary keys are not supported with SynchroMaster"); return false}
        return true
    }

    public void reactToChange(String operationType, String entityName, Object recordId)
    {
        // check semaphore, it may be temporarily disabled
        if (this.semaphores[entityName] != ActivitySemaphore.ACTIVE) {
            logger.debug("Synchronization disabled");
            this.missedSyncs[entityName] += 1;
            return
        }

        logger.debug("Performing reaction to change [${operationType}] of object [${recordId}] in entity [${entityName}]")

        if (!syncedEntities.containsKey(entityName)) {
            logger.info("Entity is not being administered by SynchroMaster")
            return
        }

        // refresh caches
        def caches = syncedEntities.get(entityName)
        assert caches.size() > 0

        // entity definition required, we need PK
        def ed = efi.getEntityDefinition(entityName)

        // primary key
        def pk = "${ed.pkFieldNames[0]}".toString()
        caches.each {
            def ch = ecf.executionContext.cache.getCache(it)
            switch (operationType){
                case "create":
                case "update":
                    def flds = ed.getFieldNames(false, true)
                    def newItem = ecf.executionContext.entity.find(entityName)
                            .selectFields(flds)
                            .condition(pk, recordId)
                            .one()
                    if (operationType == "create") {ch.put(newItem.get(pk), newItem)}
                    if (operationType == "update") {ch.replace(newItem.get(pk), newItem)}
                    break
                case "delete":
                    ch.remove(recordId)
                    break
            }
        }
    }
}
