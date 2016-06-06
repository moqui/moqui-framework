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

import javax.cache.Cache
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.CacheFacadeImpl
import org.moqui.util.MNode
import org.moqui.util.SimpleTopic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class EntityCache {
    protected final static Logger logger = LoggerFactory.getLogger(EntityCache.class)

    protected final EntityFacadeImpl efi
    final CacheFacadeImpl cfi

    final String oneKeyBase
    final String oneRaKeyBase
    final String oneViewRaKeyBase
    final String listKeyBase
    final String listRaKeyBase
    final String listViewRaKeyBase
    final String countKeyBase

    Cache<String, Set<EntityCondition>> oneBfCache
    protected final Map<String, ArrayList<String>> cachedListViewEntitiesByMember = new HashMap<>()

    protected final boolean distributedCacheInvalidate
    /** Entity Cache Invalidate Topic */
    private SimpleTopic<EntityCacheInvalidate> entityCacheInvalidateTopic = null

    EntityCache(EntityFacadeImpl efi) {
        this.efi = efi
        this.cfi = efi.ecfi.getCacheFacade()

        oneKeyBase = efi.tenantId + "__entity.record.one."
        oneRaKeyBase = efi.tenantId + "__entity.record.one_ra."
        oneViewRaKeyBase = efi.tenantId + "__entity.record.one_view_ra."
        listKeyBase = efi.tenantId + "__entity.record.list."
        listRaKeyBase = efi.tenantId + "__entity.record.list_ra."
        listViewRaKeyBase = efi.tenantId + "__entity.record.list_view_ra."
        countKeyBase = efi.tenantId + "__entity.record.count."

        String oneBfKey = efi.tenantId + "__entity.record.one_bf"
        oneBfCache = cfi.getCache(oneBfKey, efi.tenantId)

        MNode entityFacadeNode = efi.getEntityFacadeNode()
        distributedCacheInvalidate = entityFacadeNode.attribute("distributed-cache-invalidate") == "true" && entityFacadeNode.attribute("dci-topic-factory")
        logger.info("Entity Cache initialized, distributed cache invalidate enabled: ${distributedCacheInvalidate}")

        if (distributedCacheInvalidate) {
            try {
                String dciTopicFactory = entityFacadeNode.attribute("dci-topic-factory")
                entityCacheInvalidateTopic = efi.ecfi.getTool(dciTopicFactory, SimpleTopic.class)
            } catch (Exception e) {
                logger.error("Entity distributed cache invalidate is enabled but could not initialize", e)
            }
        }
    }


    static class EntityCacheInvalidate implements Externalizable {
        String tenantId
        boolean isCreate
        EntityValueBase evb

        EntityCacheInvalidate() { }
        EntityCacheInvalidate(String tenantId, EntityValueBase evb, boolean isCreate) {
            this.tenantId = tenantId
            this.isCreate = isCreate
            this.evb = evb
        }

        @Override
        void writeExternal(ObjectOutput out) throws IOException {
            out.writeUTF(tenantId)
            out.writeBoolean(isCreate)
            // NOTE: this would be faster but can't because don't know which impl of the abstract class was used: evb.writeExternal(out)
            out.writeObject(evb)
        }

        @Override
        void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
            tenantId = objectInput.readUTF()
            isCreate = objectInput.readBoolean()
            evb = (EntityValueBase) objectInput.readObject()
        }
    }

    // EntityFacadeImpl getEfi() { return efi }

    /* Get caches through EntityDefinition so we don't have to look them up every time:
    Cache<EntityCondition, EntityValueBase> getCacheOne(String entityName) {
        return cfi.getCache(oneKeyBase.concat(entityName), efi.tenantId) }
    private Cache<EntityCondition, ArrayList<EntityCondition>> getCacheOneRa(String entityName) {
        return cfi.getCache(oneRaKeyBase.concat(entityName), efi.tenantId) }
    private Cache<EntityCondition, ArrayList<ViewRaKey>> getCacheOneViewRa(String entityName) {
        return cfi.getCache(oneViewRaKeyBase.concat(entityName), efi.tenantId) }

    private Cache<String, Set<EntityCondition>> getCacheOneBf() { return internalCacheOneBf }

    Cache<EntityCondition, EntityListImpl> getCacheList(String entityName) {
        return cfi.getCache(listKeyBase.concat(entityName), efi.tenantId) }
    private Cache<EntityCondition, ArrayList<EntityCondition>> getCacheListRa(String entityName) {
        return cfi.getCache(listRaKeyBase.concat(entityName), efi.tenantId) }
    private Cache<EntityCondition, ArrayList<ViewRaKey>> getCacheListViewRa(String entityName) {
        return cfi.getCache(listViewRaKeyBase.concat(entityName), efi.tenantId) }

    Cache<EntityCondition, Long> getCacheCount(String entityName) {
        return cfi.getCache(countKeyBase.concat(entityName), efi.tenantId) }
    */

    static class EmptyRecord extends EntityValueImpl {
        EmptyRecord() { }
        EmptyRecord(EntityDefinition ed, EntityFacadeImpl efip) { super(ed, efip) }
    }
    /*
    EntityValueBase getFromOneCache(EntityDefinition ed, EntityCondition whereCondition,
                                    Cache<EntityCondition, EntityValueBase> entityOneCache) {
        if (entityOneCache == null) entityOneCache = getCacheOne(ed.getFullEntityName())

        return (EntityValueBase) entityOneCache.get(whereCondition)
    }
    */
    void putInOneCache(EntityDefinition ed, EntityCondition whereCondition, EntityValueBase newEntityValue,
                       Cache<EntityCondition, EntityValueBase> entityOneCache) {
        if (entityOneCache == null) entityOneCache = ed.getCacheOne(this)

        if (newEntityValue != null) newEntityValue.setFromCache()
        entityOneCache.put(whereCondition, newEntityValue != null ? newEntityValue : new EmptyRecord(ed, efi))
        // need to register an RA just in case the condition was not actually a primary key
        registerCacheOneRa(ed.getFullEntityName(), whereCondition, newEntityValue)
    }

    EntityListImpl getFromListCache(EntityDefinition ed, EntityCondition whereCondition, List<String> orderByList,
                                    Cache<EntityCondition, EntityListImpl> entityListCache) {
        if (whereCondition == null) return null
        if (entityListCache == null) entityListCache = ed.getCacheList(this)

        EntityListImpl cacheHit = (EntityListImpl) entityListCache.get(whereCondition)
        if (cacheHit != null && orderByList != null && orderByList.size() > 0) cacheHit.orderByFields(orderByList)
        return cacheHit
    }
    void putInListCache(EntityDefinition ed, EntityListImpl el, EntityCondition whereCondition,
                        Cache<EntityCondition, EntityListImpl> entityListCache) {
        if (whereCondition == null) return
        if (entityListCache == null) entityListCache = ed.getCacheList(this)

        // EntityList elToCache = el != null ? el : EntityListImpl.EMPTY
        EntityListImpl elToCache = el != null ? el : efi.getEmptyList()
        elToCache.setFromCache()
        entityListCache.put(whereCondition, elToCache)
        registerCacheListRa(ed.getFullEntityName(), whereCondition, elToCache)
    }
    /*
    Long getFromCountCache(EntityDefinition ed, EntityCondition whereCondition, Cache<EntityCondition, Long> entityCountCache) {
        if (entityCountCache == null) entityCountCache = getCacheCount(ed.getFullEntityName())
        return (Long) entityCountCache.get(whereCondition)
    }
    */

    /** Called from EntityValueBase */
    void clearCacheForValue(EntityValueBase evb, boolean isCreate) {
        if (evb == null) return
        EntityDefinition ed = evb.getEntityDefinition()
        if ('never'.equals(ed.getUseCache())) return

        // String entityName = evb.getEntityName()
        // if (!entityName.startsWith("moqui.")) logger.info("========== ========== ========== clearCacheForValue ${entityName}")
        if (distributedCacheInvalidate && entityCacheInvalidateTopic != null) {
            // NOTE: this takes some time to run and is done a LOT, for nearly all entity CrUD ops
            // NOTE: have set many entities as never cache
            // NOTE: can't avoid message when caches don't exist and not used in view-entity as it might be on another server
            EntityCacheInvalidate eci = new EntityCacheInvalidate(efi.tenantId, evb, isCreate)
            entityCacheInvalidateTopic.publish(eci)
        } else {
            clearCacheForValueActual(evb, isCreate)
        }
    }
    /** Does actual cache clear, called directly or distributed through topic */
    void clearCacheForValueActual(EntityValueBase evb, boolean isCreate) {
        // logger.info("====== clearCacheForValueActual isCreate=${isCreate}, evb: ${evb}")
        try {
            EntityDefinition ed = evb.getEntityDefinition()
            // use getValueMap instead of getMap, faster and we don't want to cache localized values/etc
            Map evbMap = evb.getValueMap()
            // checked in clearCacheForValue(): if ('never'.equals(ed.getUseCache())) return
            String fullEntityName = ed.getFullEntityName()

            // init this as null, set below if needed (common case it isn't, will perform better
            EntityCondition pkCondition = null

            // clear one cache
            String oneKey = oneKeyBase.concat(fullEntityName)
            if (cfi.cacheExists(oneKey)) {
                pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys())

                Cache<EntityCondition, EntityValueBase> entityOneCache = cfi.getCache(oneKey, efi.tenantId)
                // clear by PK, most common scenario
                entityOneCache.remove(pkCondition)

                // NOTE: these two have to be done whether or not it is a create because of non-pk updates, etc
                // see if there are any one RA entries
                Cache<EntityCondition, ArrayList<EntityCondition>> oneRaCache = ed.getCacheOneRa(this)
                ArrayList<EntityCondition> raKeyList = (ArrayList<EntityCondition>) oneRaCache.get(pkCondition)
                if (raKeyList != null) {
                    int raKeyListSize = raKeyList.size()
                    for (int i = 0; i < raKeyListSize; i++) {
                        EntityCondition ec = (EntityCondition) raKeyList.get(i)
                        entityOneCache.remove(ec)
                    }
                    // we've cleared all entries that this was referring to, so clean it out too
                    oneRaCache.remove(pkCondition)
                }
                // see if there are any cached entries with no result using the bf (brute-force) matching
                Set<EntityCondition> bfKeySet = (Set<EntityCondition>) oneBfCache.get(fullEntityName)
                if (bfKeySet != null && bfKeySet.size() > 0) {
                    ArrayList<EntityCondition> keysToRemove = new ArrayList<EntityCondition>()
                    Iterator<EntityCondition> bfKeySetIter = bfKeySet.iterator()
                    while (bfKeySetIter.hasNext()) {
                        EntityCondition bfKey = (EntityCondition) bfKeySetIter.next()
                        if (bfKey.mapMatches(evbMap)) keysToRemove.add(bfKey)
                    }
                    int keysToRemoveSize = keysToRemove.size()
                    for (int i = 0; i < keysToRemoveSize; i++) {
                        EntityCondition key = (EntityCondition) keysToRemove.get(i)
                        entityOneCache.remove(key)
                        bfKeySet.remove(key)
                    }
                }
            }

            // check the One View RA entries for this entity
            String oneViewRaKey = oneViewRaKeyBase.concat(fullEntityName)
            if (cfi.cacheExists(oneViewRaKey)) {
                if (pkCondition == null) pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys())

                Cache<EntityCondition, ArrayList<ViewRaKey>> oneViewRaCache = cfi.getCache(oneViewRaKey, efi.tenantId)
                ArrayList<ViewRaKey> oneViewRaKeyList = (ArrayList<ViewRaKey>) oneViewRaCache.get(pkCondition)
                // if (fullEntityName.contains("FOO")) logger.warn("======= clearCacheForValue ${fullEntityName}, PK ${pkCondition}, oneViewRaKeyList: ${oneViewRaKeyList}")
                if (oneViewRaKeyList != null) {
                    int raKeyListSize = oneViewRaKeyList.size()
                    for (int i = 0; i < raKeyListSize; i++) {
                        ViewRaKey raKey = (ViewRaKey) oneViewRaKeyList.get(i)
                        EntityDefinition raEd = efi.getEntityDefinition(raKey.entityName)
                        Cache<EntityCondition, EntityValueBase> viewEntityOneCache = raEd.getCacheOne(this)
                        // this may have already been cleared, but it is a waste of time to check for that explicitly
                        viewEntityOneCache.remove(raKey.ec)
                    }
                    // we've cleared all entries that this was referring to, so clean it out too
                    oneViewRaCache.remove(pkCondition)
                }
            }

            // clear list cache, use reverse-associative Map (also a Cache)
            String listKey = listKeyBase.concat(fullEntityName)
            if (cfi.cacheExists(listKey)) {
                if (pkCondition == null) pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys())

                Cache<EntityCondition, EntityListImpl> entityListCache = cfi.getCache(listKey, efi.tenantId)

                // if this was a create the RA cache won't help, so go through EACH entry and see if it matches the created value
                // The RA cache doesn't work for updates in the scenario where a record exists but its fields don't
                //     match a find condition when the cached list find is initially done, but is then updated so the
                //     fields do match
                Iterator<Cache.Entry<EntityCondition, EntityListImpl>> elcIterator = entityListCache.iterator()
                while (elcIterator.hasNext()) {
                    Cache.Entry<EntityCondition, EntityListImpl> entry = (Cache.Entry<EntityCondition, EntityListImpl>) elcIterator.next()
                    EntityCondition ec = (EntityCondition) entry.getKey()
                    // any way to efficiently clear out the RA cache for these? for now just leave and they are handled eventually
                    if (ec.mapMatches(evbMap)) entityListCache.remove(ec)
                }

                // if this is an update also check reverse associations (RA) as the condition check above may not match
                //     against the new values, or partially updated records
                if (!isCreate) {
                    // First just the list RA cache
                    Cache<EntityCondition, ArrayList<EntityCondition>> listRaCache = ed.getCacheListRa(this)
                    // logger.warn("============= clearing list for entity ${fullEntityName}, for pkCondition [${pkCondition}] listRaCache=${listRaCache}")
                    ArrayList<EntityCondition> raKeyList = (ArrayList<EntityCondition>) listRaCache.get(pkCondition)
                    if (raKeyList != null) {
                        // logger.warn("============= for entity ${fullEntityName}, for pkCondition [${pkCondition}], raKeyList for clear=${raKeyList}")
                        int raKeyListSize = raKeyList.size()
                        for (int i = 0; i < raKeyListSize; i++) {
                            EntityCondition raKey = (EntityCondition) raKeyList.get(i)
                            // logger.warn("============= for entity ${fullEntityName}, removing raKey=${raKey} from ${entityListCache.getName()}")
                            EntityCondition ec = (EntityCondition) raKey
                            // this may have already been cleared, but it is a waste of time to check for that explicitly
                            entityListCache.remove(ec)
                        }
                        // we've cleared all entries that this was referring to, so clean it out too
                        listRaCache.remove(pkCondition)
                    }

                    // Now to the same for the list view RA cache
                    Cache<EntityCondition, ArrayList<ViewRaKey>> listViewRaCache = ed.getCacheListViewRa(this)
                    // logger.warn("============= clearing view list for entity ${fullEntityName}, for pkCondition [${pkCondition}] listViewRaCache=${listViewRaCache}")
                    ArrayList<ViewRaKey> listViewRaKeyList = (ArrayList<ViewRaKey>) listViewRaCache.get(pkCondition)
                    if (listViewRaKeyList != null) {
                        // logger.warn("============= for entity ${fullEntityName}, for pkCondition [${pkCondition}], listViewRaKeyList for clear=${listViewRaKeyList}")
                        int raKeyListSize = listViewRaKeyList.size()
                        for (int i = 0; i < raKeyListSize; i++) {
                            ViewRaKey raKey = (ViewRaKey) listViewRaKeyList.get(i)
                            // logger.warn("============= for entity ${fullEntityName}, removing raKey=${raKey} from ${entityListCache.getName()}")
                            EntityDefinition raEd = efi.getEntityDefinition(raKey.entityName)
                            Cache<EntityCondition, EntityListImpl> viewEntityListCache = raEd.getCacheList(this)
                            // this may have already been cleared, but it is a waste of time to check for that explicitly
                            viewEntityListCache.remove(raKey.ec)
                        }
                        // we've cleared all entries that this was referring to, so clean it out too
                        listViewRaCache.remove(pkCondition)
                    }
                }
            }

            // see if this entity is a member of a cached view-entity
            ArrayList<String> cachedViewEntityNames = (ArrayList<String>) cachedListViewEntitiesByMember.get(fullEntityName)
            if (cachedViewEntityNames != null) {
                int cachedViewEntityNamesSize = cachedViewEntityNames.size()
                for (int i = 0; i < cachedViewEntityNamesSize; i++) {
                    String cachedViewEntityName = (String) cachedViewEntityNames.get(i)
                    // logger.info("Found ${cachedViewEntityName} as a cached view-entity for member ${fullEntityName}")

                    EntityDefinition viewEd = efi.getEntityDefinition(cachedViewEntityName)

                    // generally match against view-entity aliases for fields on member entity
                    // handle cases where current record (evbMap) has some keys from view-entity but not all (like UserPermissionCheck)
                    Map<String, Object> viewMatchMap = new HashMap<>()
                    Map<String, ArrayList<MNode>> memberFieldAliases = viewEd.getMemberFieldAliases(fullEntityName)
                    for (Map.Entry<String, ArrayList<MNode>> mfAliasEntry in memberFieldAliases.entrySet()) {
                        String fieldName = mfAliasEntry.getKey()
                        if (!evbMap.containsKey(fieldName)) continue
                        Object fieldValue = evbMap.get(fieldName)
                        ArrayList<MNode> aliasNodeList = (ArrayList<MNode>) mfAliasEntry.getValue()
                        int aliasNodeListSize = aliasNodeList.size()
                        for (int j = 0 ; j < aliasNodeListSize; j++) {
                            MNode aliasNode = (MNode) aliasNodeList.get(j)
                            viewMatchMap.put(aliasNode.attribute("name"), fieldValue)
                        }
                    }
                    // logger.warn("========= viewMatchMap: ${viewMatchMap}")

                    String viewListKey = listKeyBase.concat(cachedViewEntityName)
                    Cache<EntityCondition, EntityListImpl> entityListCache = cfi.getCache(viewListKey, efi.tenantId)

                    Iterator<Cache.Entry<EntityCondition, EntityListImpl>> elcIterator = entityListCache.iterator()
                    while (elcIterator.hasNext()) {
                        Cache.Entry<EntityCondition, EntityListImpl> entry = (Cache.Entry<EntityCondition, EntityListImpl>) elcIterator.next()
                        // in javax.cache.Cache next() may return null for expired, etc entries
                        if (entry == null) continue;
                        EntityCondition ec = (EntityCondition) entry.getKey()
                        // logger.warn("======= entity ${fullEntityName} view-entity ${cachedViewEntityName} matches? ${ec.mapMatchesAny(viewMatchMap)} ec: ${ec}")
                        // FUTURE: any way to efficiently clear out the RA cache for these? for now just leave and they are handled eventually
                        // don't require a full match, if matches any part of condition clear it
                        if (ec.mapMatchesAny(viewMatchMap)) elcIterator.remove()
                    }
                }
            }

            // clear count cache (no RA because we only have a count to work with, just match by condition)
            String countKey = countKeyBase.concat(fullEntityName)
            if (cfi.cacheExists(countKey)) {
                Cache<EntityCondition, Long> entityCountCache = cfi.getCache(countKey, efi.tenantId)
                Iterator<Cache.Entry<EntityCondition, Long>> eccIterator = entityCountCache.iterator()
                while (eccIterator.hasNext()) {
                    Cache.Entry<EntityCondition, Long> entry = (Cache.Entry<EntityCondition, Long>) eccIterator.next()
                    EntityCondition ec = (EntityCondition) entry.getKey()
                    if (ec.mapMatches(evbMap)) eccIterator.remove()
                }
            }
        } catch (Throwable t) {
            logger.error("Suppressed error in entity cache clearing [${evb.getEntityName()}; ${isCreate ? 'create' : 'non-create'}]", t)
        }
    }
    void registerCacheOneRa(String entityName, EntityCondition ec, EntityValueBase evb) {
        // don't skip it for null values because we're caching those too: if (evb == null) return
        if (evb == null) {
            // can't use RA cache because we don't know the PK, so use a brute-force cache but keep it separate to perform better
            Set<EntityCondition> bfKeySet = (Set<EntityCondition>) oneBfCache.get(entityName)
            if (bfKeySet == null) {
                bfKeySet = new HashSet<EntityCondition>()
                oneBfCache.put(entityName, bfKeySet)
            }
            bfKeySet.add(ec)
        } else {
            EntityDefinition ed = evb.getEntityDefinition()
            Cache<EntityCondition, ArrayList<EntityCondition>> oneRaCache = ed.getCacheOneRa(this)
            EntityCondition pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys())
            // if the condition matches the primary key, no need for an RA entry
            if (pkCondition != ec) {
                ArrayList<EntityCondition> raKeyList = (ArrayList<EntityCondition>) oneRaCache.get(pkCondition)
                if (raKeyList == null) {
                    raKeyList = new ArrayList<EntityCondition>()
                    oneRaCache.put(pkCondition, raKeyList)
                }
                raKeyList.add(ec)
            }

            // if this is a view entity we need View RA entries for each member entity (that we have a PK for)
            if (ed.isViewEntity()) {
                // go through each member-entity
                ArrayList<MNode> memberEntityList = ed.getEntityNode().children('member-entity')
                int memberEntityListSize = memberEntityList.size()
                for (int i = 0; i < memberEntityListSize; i++) {
                    MNode memberEntityNode = (MNode) memberEntityList.get(i)
                    Map<String, String> mePkFieldToAliasNameMap = ed.getMePkFieldToAliasNameMap(memberEntityNode.attribute('entity-alias'))

                    // create EntityCondition with pk fields
                    // store with main ec with view-entity name in a RA cache for view entities for the member-entity name
                    // with cache key of member-entity PK EntityCondition obj
                    EntityDefinition memberEd = efi.getEntityDefinition(memberEntityNode.attribute('entity-name'))
                    // String memberEntityName = memberEd.getFullEntityName()

                    Map<String, Object> pkCondMap = new HashMap<>()
                    for (Map.Entry<String, String> mePkEntry in mePkFieldToAliasNameMap.entrySet())
                        pkCondMap.put(mePkEntry.getKey(), evb.get(mePkEntry.getValue()))
                    // no PK fields? view-entity must not have them, skip it
                    if (pkCondMap.size() == 0) continue

                    // logger.warn("====== for view-entity ${entityName}, member-entity ${memberEd.fullEntityName}, got PK field to alias map: ${mePkFieldToAliasNameMap}\npkCondMap: ${pkCondMap}")

                    Cache<EntityCondition, ArrayList<ViewRaKey>> oneViewRaCache = memberEd.getCacheOneViewRa(this)
                    EntityCondition memberPkCondition = efi.getConditionFactory().makeCondition(pkCondMap)
                    ArrayList<ViewRaKey> raKeyList = (ArrayList<ViewRaKey>) oneViewRaCache.get(memberPkCondition)
                    ViewRaKey newRaKey = new ViewRaKey(entityName, ec)
                    if (raKeyList == null) {
                        raKeyList = new ArrayList<ViewRaKey>()
                        oneViewRaCache.put(memberPkCondition, raKeyList)
                        raKeyList.add(newRaKey)
                        // logger.warn("===== added ViewRaKey for ${memberEntityName}, PK ${memberPkCondition}, raKeyList: ${raKeyList}")
                    } else if (!raKeyList.contains(newRaKey)) {
                        raKeyList.add(newRaKey)
                        // logger.warn("===== added ViewRaKey for ${memberEntityName}, PK ${memberPkCondition}, raKeyList: ${raKeyList}")
                    }
                }
            }
        }
    }

    void registerCacheListRa(String entityName, EntityCondition ec, EntityList eli) {
        EntityDefinition ed = efi.getEntityDefinition(entityName)
        if (ed.isViewEntity()) {
            // go through each member-entity
            ArrayList<MNode> memberEntityList = ed.getEntityNode().children('member-entity')
            int memberEntityListSize = memberEntityList.size()
            for (int j = 0; j < memberEntityListSize; j++) {
                MNode memberEntityNode = (MNode) memberEntityList.get(j)
                Map<String, String> mePkFieldToAliasNameMap = ed.getMePkFieldToAliasNameMap(memberEntityNode.attribute('entity-alias'))

                // logger.warn("TOREMOVE for view-entity ${entityName}, member-entity ${memberEntityNode.'@entity-name'}, got PK field to alias map: ${mePkFieldToAliasNameMap}")

                // create EntityCondition with pk fields
                // store with main ec with view-entity name in a RA cache for view entities for the member-entity name
                // with cache key of member-entity PK EntityCondition obj
                EntityDefinition memberEd = efi.getEntityDefinition(memberEntityNode.attribute('entity-name'))
                String memberEntityName = memberEd.getFullEntityName()

                // remember that this member entity has been used in a cached view entity
                ArrayList<String> cachedViewEntityNames = cachedListViewEntitiesByMember.get(memberEntityName)
                if (cachedViewEntityNames == null) {
                    cachedViewEntityNames = new ArrayList<>()
                    cachedListViewEntitiesByMember.put(memberEntityName, cachedViewEntityNames)
                    cachedViewEntityNames.add(entityName)
                    // logger.info("Added ${entityName} as a cached view-entity for member ${memberEntityName}")
                } else if (!cachedViewEntityNames.contains(entityName)) {
                    cachedViewEntityNames.add(entityName)
                    // logger.info("Added ${entityName} as a cached view-entity for member ${memberEntityName}")
                }

                Cache<EntityCondition, ArrayList<ViewRaKey>> listViewRaCache = memberEd.getCacheListViewRa(this)
                int eliSize = eli.size()
                for (int i = 0; i < eliSize; i++) {
                    EntityValue ev = (EntityValue) eli.get(i)
                    Map pkCondMap = new HashMap()
                    for (Map.Entry<String, String> mePkEntry in mePkFieldToAliasNameMap.entrySet())
                        pkCondMap.put(mePkEntry.getKey(), ev.get(mePkEntry.getValue()))

                    EntityCondition pkCondition = efi.getConditionFactory().makeCondition(pkCondMap)
                    ArrayList<ViewRaKey> raKeyList = (ArrayList<ViewRaKey>) listViewRaCache.get(pkCondition)
                    ViewRaKey newRaKey = new ViewRaKey(entityName, ec)
                    if (raKeyList == null) {
                        raKeyList = new ArrayList<ViewRaKey>()
                        listViewRaCache.put(pkCondition, raKeyList)
                        raKeyList.add(newRaKey)
                    } else if (!raKeyList.contains(newRaKey)) {
                        raKeyList.add(newRaKey)
                    }
                    // logger.warn("TOREMOVE for view-entity ${entityName}, member-entity ${memberEntityNode.'@entity-name'}, for pkCondition [${pkCondition}], raKeyList after add=${raKeyList}")
                }
            }
        } else {
            Cache<EntityCondition, ArrayList<EntityCondition>> listRaCache = ed.getCacheListRa(this)
            int eliSize = eli.size()
            for (int i = 0; i < eliSize; i++) {
                EntityValue ev = (EntityValue) eli.get(i)
                EntityCondition pkCondition = efi.getConditionFactory().makeCondition(ev.getPrimaryKeys())
                ArrayList<EntityCondition> raKeyList = (ArrayList<EntityCondition>) listRaCache.get(pkCondition)
                if (raKeyList == null) {
                    raKeyList = new ArrayList<EntityCondition>()
                    listRaCache.put(pkCondition, raKeyList)
                }
                raKeyList.add(ec)
            }
        }
    }

    static class ViewRaKey implements Serializable {
        final String entityName
        final EntityCondition ec
        final int hashCodeVal
        ViewRaKey(String entityName, EntityCondition ec) {
            this.entityName = entityName; this.ec = ec;
            hashCodeVal = entityName.hashCode() + ec.hashCode()
        }

        @Override
        int hashCode() { return hashCodeVal }
        @Override
        boolean equals(Object obj) {
            if (obj.getClass() != ViewRaKey.class) return false
            ViewRaKey that = (ViewRaKey) obj
            if (!entityName.equals(that.entityName)) return false
            if (!ec.equals(that.ec)) return false
            return true
        }
        @Override
        String toString() { return entityName + '(' + ec.toString() + ')' }
    }


}
