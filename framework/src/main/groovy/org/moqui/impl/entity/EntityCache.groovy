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
import net.sf.ehcache.Ehcache
import net.sf.ehcache.Element
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.StupidUtilities
import org.moqui.impl.context.CacheFacadeImpl
import org.moqui.impl.context.CacheImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class EntityCache {
    protected final static Logger logger = LoggerFactory.getLogger(EntityCache.class)

    protected final EntityFacadeImpl efi
    protected final CacheFacadeImpl cfi

    protected final String oneKeyBase
    protected final String oneRaKeyBase
    protected final String oneBfKey
    protected final String listKeyBase
    protected final String listRaKeyBase
    protected final String listViewRaKeyBase
    protected final String countKeyBase

    protected final Map<String, ArrayList<String>> cachedViewEntitiesByMember = new HashMap<>()

    EntityCache(EntityFacadeImpl efi) {
        this.efi = efi
        this.cfi = efi.ecfi.getCacheFacade()

        oneKeyBase = "entity.record.one."
        oneRaKeyBase = "entity.record.one_ra."
        oneBfKey = "entity.record.one_bf"
        listKeyBase = "entity.record.list."
        listRaKeyBase = "entity.record.list_ra."
        listViewRaKeyBase = "entity.record.list_view_ra."
        countKeyBase = "entity.record.count."
    }

    // EntityFacadeImpl getEfi() { return efi }

    CacheImpl getCacheOne(String entityName) { return cfi.getCacheImpl(oneKeyBase.concat(entityName), efi.tenantId) }
    private CacheImpl getCacheOneRa(String entityName) { return cfi.getCacheImpl(oneRaKeyBase.concat(entityName), efi.tenantId) }
    private CacheImpl getCacheOneBf() { return cfi.getCacheImpl(oneBfKey, efi.tenantId) }

    CacheImpl getCacheList(String entityName) { return cfi.getCacheImpl(listKeyBase.concat(entityName), efi.tenantId) }
    private CacheImpl getCacheListRa(String entityName) { return cfi.getCacheImpl(listRaKeyBase.concat(entityName), efi.tenantId) }
    private CacheImpl getCacheListViewRa(String entityName) { return cfi.getCacheImpl(listViewRaKeyBase.concat(entityName), efi.tenantId) }

    CacheImpl getCacheCount(String entityName) { return cfi.getCacheImpl(countKeyBase.concat(entityName), efi.tenantId) }

    static class EmptyRecord extends EntityValueImpl {
        EmptyRecord(EntityDefinition ed, EntityFacadeImpl efip) { super(ed, efip) }
    }
    EntityValueBase getFromOneCache(EntityDefinition ed, EntityCondition whereCondition, CacheImpl entityOneCache) {
        if (entityOneCache == null) entityOneCache = getCacheOne(ed.getFullEntityName())

        Element cacheElement = entityOneCache.getElement(whereCondition)
        if (cacheElement != null) {
            if (cacheElement.expired) {
                entityOneCache.removeElement(cacheElement)
            } else {
                // if objectValue is null return something else as a placeholder for known no record
                if (cacheElement.objectValue == null) return new EmptyRecord(ed, efi)
                return (EntityValueBase) cacheElement.objectValue
            }
        }

        return null
    }
    void putInOneCache(EntityDefinition ed, EntityCondition whereCondition, EntityValueBase newEntityValue, CacheImpl entityOneCache) {
        if (entityOneCache == null) entityOneCache = getCacheOne(ed.getFullEntityName())

        if (newEntityValue != null) newEntityValue.setFromCache()
        entityOneCache.put(whereCondition, newEntityValue)
        // need to register an RA just in case the condition was not actually a primary key
        registerCacheOneRa(ed.getFullEntityName(), whereCondition, newEntityValue)
    }

    EntityList getFromListCache(EntityDefinition ed, EntityCondition whereCondition, List<String> orderByList, CacheImpl entityListCache) {
        if (entityListCache == null) entityListCache = getCacheList(ed.getFullEntityName())

        Element cacheElement = entityListCache.getElement(whereCondition)
        if (cacheElement != null) {
            if (cacheElement.expired) {
                entityListCache.removeElement(cacheElement)
            } else {
                EntityList cacheHit = (EntityList) cacheElement.objectValue
                if (orderByList) cacheHit.orderByFields(orderByList)
                return cacheHit
            }
        }
        return null
    }
    void putInListCache(EntityDefinition ed, EntityListImpl el, EntityCondition whereCondition, CacheImpl entityListCache) {
        if (whereCondition == null) return
        if (entityListCache == null) entityListCache = getCacheList(ed.getFullEntityName())

        EntityList elToCache = el ?: EntityListImpl.EMPTY
        elToCache.setFromCache()
        entityListCache.put(whereCondition, elToCache)
        registerCacheListRa(ed.getFullEntityName(), whereCondition, elToCache)
    }

    Long getFromCountCache(EntityDefinition ed, EntityCondition whereCondition, CacheImpl entityCountCache) {
        if (entityCountCache == null) entityCountCache = getCacheCount(ed.getFullEntityName())

        Element cacheElement = entityCountCache.getElement(whereCondition)
        if (cacheElement != null) {
            if (cacheElement.expired) {
                entityCountCache.removeElement(cacheElement)
            } else {
                return (Long) cacheElement.objectValue
            }
        }
        return null
    }

    void clearCacheForValue(EntityValueBase evb, boolean isCreate) {
        try {
            EntityDefinition ed = evb.getEntityDefinition()
            // use getValueMap instead of getMap, faster and we don't want to cache localized values/etc
            Map evbMap = evb.getValueMap()
            if ('never'.equals(ed.getUseCache())) return
            String fullEntityName = ed.getFullEntityName()

            // init this as null, set below if needed (common case it isn't, will perform better
            EntityCondition pkCondition = null

            // clear one cache
            String oneKey = oneKeyBase.concat(fullEntityName)
            if (cfi.cacheExists(oneKey)) {
                pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys())

                CacheImpl entityOneCache = cfi.getCacheImpl(oneKey, efi.tenantId)
                Ehcache eocEhc = entityOneCache.getInternalCache()
                // clear by PK, most common scenario
                eocEhc.remove(pkCondition)

                // NOTE: these two have to be done whether or not it is a create because of non-pk updates, etc
                // see if there are any one RA entries
                CacheImpl oneRaCache = getCacheOneRa(fullEntityName)
                ArrayList<EntityCondition> raKeyList = (ArrayList<EntityCondition>) oneRaCache.get(pkCondition)
                if (raKeyList != null) {
                    int raKeyListSize = raKeyList.size()
                    for (int i = 0; i < raKeyListSize; i++) {
                        EntityCondition ec = (EntityCondition) raKeyList.get(i)
                        eocEhc.remove(ec)
                    }
                    // we've cleared all entries that this was referring to, so clean it out too
                    oneRaCache.remove(pkCondition)
                }
                // see if there are any cached entries with no result using the bf (brute-force) matching
                CacheImpl oneBfCache = getCacheOneBf()
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
                        eocEhc.remove(key)
                        bfKeySet.remove(key)
                    }
                }
            }

            // logger.warn("============= clearing list for entity ${fullEntityName}, for pkCondition [${pkCondition}] cacheExists=${cfi.cacheExists("entity.${efi.tenantId}.list.${fullEntityName}")}")
            // clear list cache, use reverse-associative Map (also a Cache)
            String listKey = listKeyBase.concat(fullEntityName)
            if (cfi.cacheExists(listKey)) {
                if (pkCondition == null) pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys())

                CacheImpl entityListCache = cfi.getCacheImpl(listKey, efi.tenantId)
                Ehcache elEhc = entityListCache.getInternalCache()

                // if this was a create the RA cache won't help, so go through EACH entry and see if it matches the created value
                // The RA cache doesn't work for updates in the scenario where a record exists but its fields don't
                //     match a find condition when the cached list find is initially done, but is then updated so the
                //     fields do match

                // Ehcache returns a plain List, may or may not be faster to iterate with index
                List<EntityCondition> elEhcKeys = (List<EntityCondition>) elEhc.getKeys()
                Iterator<EntityCondition> elEhcKeysIter = elEhcKeys.iterator()
                while (elEhcKeysIter.hasNext()) {
                    EntityCondition ec = (EntityCondition) elEhcKeysIter.next()
                    // any way to efficiently clear out the RA cache for these? for now just leave and they are handled eventually
                    if (ec.mapMatches(evbMap)) elEhc.remove(ec)
                }

                // if this is an update also check reverse associations (RA) as the condition check above may not match
                //     against the new values, or partially updated records
                if (!isCreate) {
                    // First just the list RA cache
                    CacheImpl listRaCache = getCacheListRa(fullEntityName)
                    // logger.warn("============= clearing list for entity ${fullEntityName}, for pkCondition [${pkCondition}] listRaCache=${listRaCache}")
                    if (listRaCache.containsKey(pkCondition)) {
                        ArrayList<EntityCondition> raKeyList = (ArrayList<EntityCondition>) listRaCache.get(pkCondition)
                        // logger.warn("============= for entity ${fullEntityName}, for pkCondition [${pkCondition}], raKeyList for clear=${raKeyList}")
                        int raKeyListSize = raKeyList.size()
                        for (int i = 0; i < raKeyListSize; i++) {
                            EntityCondition raKey = (EntityCondition) raKeyList.get(i)
                            // logger.warn("============= for entity ${fullEntityName}, removing raKey=${raKey} from ${entityListCache.getName()}")
                            EntityCondition ec = (EntityCondition) raKey
                            // this may have already been cleared, but it is a waste of time to check for that explicitly
                            elEhc.remove(ec)
                        }
                        // we've cleared all entries that this was referring to, so clean it out too
                        listRaCache.remove(pkCondition)
                    }

                    // Now to the same for the list view RA cache
                    CacheImpl listViewRaCache = getCacheListViewRa(fullEntityName)
                    // logger.warn("============= clearing list for entity ${fullEntityName}, for pkCondition [${pkCondition}] listRaCache=${listRaCache}")
                    if (listViewRaCache.containsKey(pkCondition)) {
                        ArrayList<ViewRaKey> raKeyList = (ArrayList<ViewRaKey>) listRaCache.get(pkCondition)
                        // logger.warn("============= for entity ${fullEntityName}, for pkCondition [${pkCondition}], raKeyList for clear=${raKeyList}")
                        int raKeyListSize = raKeyList.size()
                        for (int i = 0; i < raKeyListSize; i++) {
                            ViewRaKey raKey = (ViewRaKey) raKeyList.get(i)
                            // logger.warn("============= for entity ${fullEntityName}, removing raKey=${raKey} from ${entityListCache.getName()}")
                            CacheImpl viewEntityListCache = getCacheList(raKey.entityName)
                            Ehcache velcEhc = viewEntityListCache.getInternalCache()
                            // this may have already been cleared, but it is a waste of time to check for that explicitly
                            velcEhc.remove(raKey.ec)
                        }
                        // we've cleared all entries that this was referring to, so clean it out too
                        listRaCache.remove(pkCondition)
                    }
                }
            }

            // see if this entity is a member of a cached view-entity
            ArrayList<String> cachedViewEntityNames = cachedViewEntitiesByMember.get(fullEntityName)
            if (cachedViewEntityNames != null && cachedViewEntityNames.size() > 0) {
                for (int i = 0; i < cachedViewEntityNames.size(); i++) {
                    String cachedViewEntityName = (String) cachedViewEntityNames.get(i)
                    // logger.info("Found ${cachedViewEntityName} as a cached view-entity for member ${fullEntityName}")

                    String viewListKey = listKeyBase.concat(cachedViewEntityName)
                    CacheImpl entityListCache = cfi.getCacheImpl(viewListKey, efi.tenantId)
                    Ehcache elEhc = entityListCache.getInternalCache()

                    // Ehcache returns a plain List, may or may not be faster to iterate with index
                    List<EntityCondition> elEhcKeys = (List<EntityCondition>) elEhc.getKeys()
                    Iterator<EntityCondition> elEhcKeysIter = elEhcKeys.iterator()
                    while (elEhcKeysIter.hasNext()) {
                        EntityCondition ec = (EntityCondition) elEhcKeysIter.next()
                        // any way to efficiently clear out the RA cache for these? for now just leave and they are handled eventually
                        if (ec.mapMatches(evbMap)) elEhc.remove(ec)
                    }
                }
            }

            // clear count cache (no RA because we only have a count to work with, just match by condition)
            String countKey = countKeyBase.concat(fullEntityName)
            if (cfi.cacheExists(countKey)) {
                CacheImpl entityCountCache = cfi.getCacheImpl(countKey, efi.tenantId)
                Ehcache ecEhc = entityCountCache.getInternalCache()
                List<EntityCondition> ecEhcKeys = (List<EntityCondition>) ecEhc.getKeys()
                Iterator<EntityCondition> ecEhcKeysIter = ecEhcKeys.iterator()
                while (ecEhcKeysIter.hasNext()) {
                    EntityCondition ec = (EntityCondition) ecEhcKeysIter.next()
                    if (ec.mapMatches(evbMap)) ecEhc.remove(ec)
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
            CacheImpl oneBfCache = getCacheOneBf()
            Set<EntityCondition> bfKeySet = (Set<EntityCondition>) oneBfCache.get(entityName)
            if (bfKeySet == null) {
                bfKeySet = new HashSet<EntityCondition>()
                oneBfCache.put(entityName, bfKeySet)
            }
            bfKeySet.add(ec)
        } else {
            CacheImpl oneRaCache = getCacheOneRa(entityName)
            EntityCondition pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys())
            // if the condition matches the primary key, no need for an RA entry
            if (pkCondition == ec) return
            ArrayList<EntityCondition> raKeyList = (ArrayList<EntityCondition>) oneRaCache.get(pkCondition)
            if (raKeyList == null) {
                raKeyList = new ArrayList<EntityCondition>()
                oneRaCache.put(pkCondition, raKeyList)
            }
            raKeyList.add(ec)
        }
    }

    void registerCacheListRa(String entityName, EntityCondition ec, EntityList eli) {
        EntityDefinition ed = efi.getEntityDefinition(entityName)
        if (ed.isViewEntity()) {
            // go through each member-entity
            for (MNode memberEntityNode in ed.getEntityNode().children('member-entity')) {
                Map<String, String> mePkFieldToAliasNameMap = ed.getMePkFieldToAliasNameMap(memberEntityNode.attribute('entity-alias'))

                // logger.warn("TOREMOVE for view-entity ${entityName}, member-entity ${memberEntityNode.'@entity-name'}, got PK field to alias map: ${mePkFieldToAliasNameMap}")

                // create EntityCondition with pk fields
                // store with main ec with view-entity name in a RA cache for view entities for the member-entity name
                // with cache key of member-entity PK EntityCondition obj
                EntityDefinition memberEd = efi.getEntityDefinition(memberEntityNode.attribute('entity-name'))
                String memberEntityName = memberEd.getFullEntityName()

                // remember that this member entity has been used in a cached view entity
                ArrayList<String> cachedViewEntityNames = cachedViewEntitiesByMember.get(memberEntityName)
                if (cachedViewEntityNames == null) {
                    cachedViewEntityNames = new ArrayList<>()
                    cachedViewEntitiesByMember.put(memberEntityName, cachedViewEntityNames)
                    cachedViewEntityNames.add(entityName)
                    // logger.info("Added ${entityName} as a cached view-entity for member ${memberEntityName}")
                } else if (!cachedViewEntityNames.contains(entityName)) {
                    cachedViewEntityNames.add(entityName)
                    // logger.info("Added ${entityName} as a cached view-entity for member ${memberEntityName}")
                }

                CacheImpl listViewRaCache = getCacheListViewRa(memberEntityName)
                int eliSize = eli.size()
                for (int i = 0; i < eliSize; i++) {
                    EntityValue ev = (EntityValue) eli.get(i)
                    Map pkCondMap = new HashMap()
                    for (Map.Entry<String, String> mePkEntry in mePkFieldToAliasNameMap.entrySet())
                        pkCondMap.put(mePkEntry.getKey(), ev.get(mePkEntry.getValue()))

                    EntityCondition pkCondition = efi.getConditionFactory().makeCondition(pkCondMap)
                    ArrayList<ViewRaKey> raKeyList = (ArrayList<ViewRaKey>) listViewRaCache.get(pkCondition)
                    if (raKeyList == null) {
                        raKeyList = new ArrayList()
                        listViewRaCache.put(pkCondition, raKeyList)
                    }
                    raKeyList.add(new ViewRaKey(entityName, ec))
                    // logger.warn("TOREMOVE for view-entity ${entityName}, member-entity ${memberEntityNode.'@entity-name'}, for pkCondition [${pkCondition}], raKeyList after add=${raKeyList}")
                }
            }
        } else {
            CacheImpl listRaCache = getCacheListRa(ed.getFullEntityName())
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
