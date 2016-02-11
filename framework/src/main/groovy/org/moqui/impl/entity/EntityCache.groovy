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
import org.moqui.context.Cache
import org.moqui.context.CacheFacade
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.CacheImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class EntityCache {
    protected final static Logger logger = LoggerFactory.getLogger(EntityCache.class)

    protected final EntityFacadeImpl efi

    EntityCache(EntityFacadeImpl efi) {
        this.efi = efi
    }

    // EntityFacadeImpl getEfi() { return efi }

    CacheImpl getCacheOne(String entityName) { return efi.ecfi.getCacheFacade().getCacheImpl("entity.record.one.${entityName}.${efi.tenantId}") }
    private CacheImpl getCacheOneRa(String entityName) { return efi.ecfi.getCacheFacade().getCacheImpl("entity.record.one_ra.${entityName}.${efi.tenantId}") }
    private CacheImpl getCacheOneBf() { return efi.ecfi.getCacheFacade().getCacheImpl("entity.record.one_bf.${efi.tenantId}") }
    CacheImpl getCacheList(String entityName) { return efi.ecfi.getCacheFacade().getCacheImpl("entity.record.list.${entityName}.${efi.tenantId}") }
    private CacheImpl getCacheListRa(String entityName) { return efi.ecfi.getCacheFacade().getCacheImpl("entity.record.list_ra.${entityName}.${efi.tenantId}") }
    CacheImpl getCacheCount(String entityName) { return efi.ecfi.getCacheFacade().getCacheImpl("entity.record.count.${entityName}.${efi.tenantId}") }

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
        efi.getEntityCache().registerCacheOneRa(ed.getFullEntityName(), whereCondition, newEntityValue)
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
        efi.getEntityCache().registerCacheListRa(ed.getFullEntityName(), whereCondition, elToCache)
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
            CacheFacade cf = efi.getEcfi().getCacheFacade()
            EntityDefinition ed = evb.getEntityDefinition()
            Map evbMap = evb.getMap()
            if ('never'.equals(ed.getUseCache())) return
            String fullEntityName = ed.getFullEntityName()

            // init this as null, set below if needed (common case it isn't, will perform better
            EntityCondition pkCondition = null

            // clear one cache
            if (cf.cacheExists("entity.record.one.${fullEntityName}.${efi.tenantId}")) {
                pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys())

                Cache entityOneCache = getCacheOne(fullEntityName)
                Ehcache eocEhc = entityOneCache.getInternalCache()
                // clear by PK, most common scenario
                eocEhc.remove(pkCondition)

                // NOTE: these two have to be done whether or not it is a create because of non-pk updates, etc
                // see if there are any one RA entries
                Cache oneRaCache = getCacheOneRa(fullEntityName)
                if (oneRaCache.containsKey(pkCondition)) {
                    List<EntityCondition> raKeyList = (List<EntityCondition>) oneRaCache.get(pkCondition)
                    for (EntityCondition ec in raKeyList) {
                        eocEhc.remove(ec)
                    }
                    // we've cleared all entries that this was referring to, so clean it out too
                    oneRaCache.remove(pkCondition)
                }
                // see if there are any cached entries with no result using the bf (brute-force) matching
                Cache oneBfCache = getCacheOneBf()
                Set<EntityCondition> bfKeySet = (Set<EntityCondition>) oneBfCache.get(fullEntityName)
                if (bfKeySet) {
                    Set<EntityCondition> keysToRemove = new HashSet<EntityCondition>()
                    for (EntityCondition bfKey in bfKeySet) {
                        if (bfKey.mapMatches(evbMap)) {
                            eocEhc.remove(bfKey)
                            keysToRemove.add(bfKey)
                        }
                    }
                    for (EntityCondition key in keysToRemove) bfKeySet.remove(key)
                }
            }

            // logger.warn("============= clearing list for entity ${fullEntityName}, for pkCondition [${pkCondition}] cacheExists=${efi.ecfi.getCacheFacade().cacheExists("entity.${efi.tenantId}.list.${fullEntityName}")}")
            // clear list cache, use reverse-associative Map (also a Cache)
            if (cf.cacheExists("entity.record.list.${fullEntityName}.${efi.tenantId}")) {
                if (pkCondition == null) pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys())

                // if this was a create the RA cache won't help, so go through EACH entry and see if it matches the created value
                if (isCreate) {
                    CacheImpl entityListCache = getCacheList(fullEntityName)
                    Ehcache elEhc = entityListCache.getInternalCache()
                    List<EntityCondition> elEhcKeys = (List<EntityCondition>) elEhc.getKeys()
                    for (EntityCondition ec in elEhcKeys) {
                        // any way to efficiently clear out the RA cache for these? for now just leave and they are handled eventually
                        if (ec.mapMatches(evbMap)) elEhc.remove(ec)
                    }
                } else {
                    Cache listRaCache = getCacheListRa(fullEntityName)
                    // logger.warn("============= clearing list for entity ${fullEntityName}, for pkCondition [${pkCondition}] listRaCache=${listRaCache}")
                    if (listRaCache.containsKey(pkCondition)) {
                        List raKeyList = (List) listRaCache.get(pkCondition)
                        // logger.warn("============= for entity ${fullEntityName}, for pkCondition [${pkCondition}], raKeyList for clear=${raKeyList}")
                        CacheImpl entityListCache = getCacheList(fullEntityName)
                        Ehcache elcEhc = entityListCache.getInternalCache()
                        for (Object raKey in raKeyList) {
                            // logger.warn("============= for entity ${fullEntityName}, removing raKey=${raKey} from ${entityListCache.getName()}")
                            if (raKey instanceof EntityCondition) {
                                EntityCondition ec = (EntityCondition) raKey
                                // this may have already been cleared, but it is a waste of time to check for that explicitly
                                elcEhc.remove(ec)
                            } else {
                                Map viewEcMap = (Map) raKey
                                CacheImpl viewEntityListCache = getCacheList((String) viewEcMap.ven)
                                Ehcache velcEhc = viewEntityListCache.getInternalCache()
                                // this may have already been cleared, but it is a waste of time to check for that explicitly
                                velcEhc.remove(viewEcMap.ec)
                            }
                        }
                        // we've cleared all entries that this was referring to, so clean it out too
                        listRaCache.remove(pkCondition)
                    }
                }
            }

            // clear count cache (no RA because we only have a count to work with, just match by condition)
            if (cf.cacheExists("entity.record.count.${fullEntityName}.${efi.tenantId}")) {
                CacheImpl entityCountCache = getCacheCount(fullEntityName)
                Ehcache ecEhc = entityCountCache.getInternalCache()
                List<EntityCondition> ecEhcKeys = (List<EntityCondition>) ecEhc.getKeys()
                for (EntityCondition ec in ecEhcKeys) {
                    if (ec.mapMatches(evbMap)) ecEhc.remove(ec)
                }
            }
        } catch (Throwable t) {
            logger.error("Suppressed error in entity cache clearing [${evb.getEntityName()}; ${isCreate ? 'create' : 'non-create'}]", t)
        }
    }
    void registerCacheOneRa(String entityName, EntityCondition ec, EntityValueBase evb) {
        EntityDefinition ed = efi.getEntityDefinition(entityName)
        // don't skip it for null values because we're caching those too: if (evb == null) return
        if (evb == null) {
            // can't use RA cache because we don't know the PK, so use a brute-force cache but keep it separate to perform better
            Cache oneBfCache = getCacheOneBf()
            Set bfKeySet = (Set) oneBfCache.get(ed.getFullEntityName())
            if (bfKeySet == null) {
                bfKeySet = new HashSet()
                oneBfCache.put(entityName, bfKeySet)
            }
            bfKeySet.add(ec)
        } else {
            Cache oneRaCache = getCacheOneRa(ed.getFullEntityName())
            EntityCondition pkCondition = efi.getConditionFactory().makeCondition(evb.getPrimaryKeys())
            // if the condition matches the primary key, no need for an RA entry
            if (pkCondition == ec) return
            List raKeyList = (List) oneRaCache.get(pkCondition)
            if (raKeyList == null) {
                raKeyList = new ArrayList()
                oneRaCache.put(pkCondition, raKeyList)
            }
            raKeyList.add(ec)
        }
    }
    void registerCacheListRa(String entityName, EntityCondition ec, EntityList eli) {
        EntityDefinition ed = efi.getEntityDefinition(entityName)
        if (ed.isViewEntity()) {
            // go through each member-entity
            for (MNode memberEntityNode in ed.getEntityNode().children) {
                if (!'member-entity'.equals(memberEntityNode.name)) continue

                Map mePkFieldToAliasNameMap = ed.getMePkFieldToAliasNameMap((String) memberEntityNode.attribute('entity-alias'))

                // logger.warn("TOREMOVE for view-entity ${entityName}, member-entity ${memberEntityNode.'@entity-name'}, got PK field to alias map: ${mePkFieldToAliasNameMap}")

                // create EntityCondition with pk fields
                // store with main ec with view-entity name in a RA cache for view entities for the member-entity name
                // with cache key of member-entity PK EntityCondition obj
                EntityDefinition memberEd = efi.getEntityDefinition((String) memberEntityNode.attribute('entity-name'))
                Cache listViewRaCache = getCacheListRa(memberEd.getFullEntityName())
                for (EntityValue ev in eli) {
                    Map pkCondMap = new HashMap()
                    for (Map.Entry mePkEntry in mePkFieldToAliasNameMap.entrySet()) pkCondMap.put(mePkEntry.getKey(), ev.get(mePkEntry.getValue()))
                    EntityCondition pkCondition = efi.getConditionFactory().makeCondition(pkCondMap)
                    List raKeyList = (List) listViewRaCache.get(pkCondition)
                    if (!raKeyList) {
                        raKeyList = new ArrayList()
                        listViewRaCache.put(pkCondition, raKeyList)
                    }
                    raKeyList.add([ven:entityName, ec:ec])
                    // logger.warn("TOREMOVE for view-entity ${entityName}, member-entity ${memberEntityNode.'@entity-name'}, for pkCondition [${pkCondition}], raKeyList after add=${raKeyList}")
                }
            }
        } else {
            Cache listRaCache = getCacheListRa(ed.getFullEntityName())
            for (EntityValue ev in eli) {
                EntityCondition pkCondition = efi.getConditionFactory().makeCondition(ev.getPrimaryKeys())
                List raKeyList = (List) listRaCache.get(pkCondition)
                if (!raKeyList) {
                    raKeyList = new ArrayList()
                    listRaCache.put(pkCondition, raKeyList)
                }
                raKeyList.add(ec)
            }
        }
    }
}
