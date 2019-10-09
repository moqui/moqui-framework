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
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityException
import org.moqui.entity.EntityList
import org.moqui.entity.EntityNotFoundException
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.EntityJavaUtil.RelationshipInfo

import javax.cache.Cache
import javax.transaction.Status
import javax.transaction.Synchronization
import javax.transaction.Transaction
import javax.transaction.TransactionManager
import javax.transaction.xa.XAException
import java.sql.Timestamp

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.RejectedExecutionException

@CompileStatic
class EntityDataFeed {
    protected final static Logger logger = LoggerFactory.getLogger(EntityDataFeed.class)

    protected final EntityFacadeImpl efi

    protected final Cache<String, ArrayList<DocumentEntityInfo>> dataFeedEntityInfo
    Set<String> entitiesWithDataFeed = null

    EntityDataFeed(EntityFacadeImpl efi) {
        this.efi = efi
        dataFeedEntityInfo = efi.ecfi.cacheFacade.getCache("entity.data.feed.info")
    }

    EntityFacadeImpl getEfi() { return efi }

    /** This method gets the latest documents for a DataFeed based on DataFeed.lastFeedStamp, and updates lastFeedStamp
     * to the current time. This method should be called in a service or something to manage the transaction.
     * See the org.moqui.impl.EntityServices.get#DataFeedLatestDocuments service.*/
    List<Map> getFeedLatestDocuments(String dataFeedId) {
        EntityValue dataFeed = efi.find("moqui.entity.feed.DataFeed").condition("dataFeedId", dataFeedId)
                .useCache(false).forUpdate(true).one()
        Timestamp fromUpdateStamp = dataFeed.getTimestamp("lastFeedStamp")
        Timestamp thruUpdateStamp = new Timestamp(System.currentTimeMillis())
        // get the List first, if no errors update lastFeedStamp
        List<Map> documentList = getFeedDocuments(dataFeedId, fromUpdateStamp, thruUpdateStamp)
        dataFeed.lastFeedStamp = thruUpdateStamp
        dataFeed.update()
        return documentList
    }

    ArrayList<Map> getFeedDocuments(String dataFeedId, Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp) {
        EntityList dataFeedDocumentList = efi.find("moqui.entity.feed.DataFeedDocument")
                .condition("dataFeedId", dataFeedId).useCache(true).list()

        ArrayList<Map> fullDocumentList = new ArrayList<>()
        for (EntityValue dataFeedDocument in dataFeedDocumentList) {
            String dataDocumentId = dataFeedDocument.dataDocumentId
            ArrayList<Map> curDocList = efi.getDataDocuments(dataDocumentId, null, fromUpdateStamp, thruUpdatedStamp)
            fullDocumentList.addAll(curDocList)
        }
        return fullDocumentList
    }

    /* Notes for real-time push DataFeed:
    - doing update on entity have entityNames updated, for each fieldNames updated, field values (query as needed based
        on actual conditions if any conditions on fields not present in EntityValue
    - do this based on a committed transaction of changes, not just on a single record...
    - keep data for documents to include until transaction committed

    to quickly lookup DataDocuments updated with a corresponding real time (DTFDTP_RT_PUSH) DataFeed need:
    - don't have to constrain by real time DataFeed, will be done in advance for index
    - Map with entityName as key
    - value is List of Map with:
      - dataFeedId
      - List of DocumentEntityInfo objects with
        - dataDocumentId
        - Set of fields for DataDocument and the current entity
        - primaryEntityName
        - relationship path from primary to current entity
        - Map of field conditions for current entity - and for entire document? no, false positives filtered out when doc data queried
    - find with query on DataFeed and DataFeedDocument where DataFeed.dataFeedTypeEnumId=DTFDTP_RT_PUSH
      - iterate through dataDocumentId and call getDataDocumentEntityInfo() for each

    to produce the document with zero or minimal query
    - during transaction save all created or updated records in EntityList updatedList (in EntityFacadeImpl?)
    - EntityValues added to the list only if they are in the

    - once we have dataDocumentIdSet use to lookup all DTFDTP_RT_PUSH DataFeed with a matching DataFeedDocument record
    - look up primary entity value for the current updated value and use its PK fields as a condition to call
        getDataDocuments() so that we get a document for just the updated record(s)

     */

    void dataFeedCheckAndRegister(EntityValue ev, boolean isUpdate, Map valueMap, Map oldValues) {
        // logger.warn("============== DataFeed checking entity isModified=${ev.isModified()} [${ev.getEntityName()}] value: ${ev}")
        // String debugEntityName = "Request"
        // if (ev.getEntityName().endsWith(debugEntityName)) logger.warn("======= dataFeedCheckAndRegister update? ${isUpdate} mod? ${ev.isModified()}\nev: ${ev}\noldValues=${oldValues}")

        // if the value isn't modified don't register for DataFeed at all
        if (!ev.isModified()) return
        if (isUpdate && oldValues == null) return

        // see if this should be added to the feed
        ArrayList<DocumentEntityInfo> entityInfoList
        try {
            entityInfoList = getDataFeedEntityInfoList(ev.getEntityName())
        } catch (Throwable t) {
            logger.error("Error getting DataFeed entity info, not registering value for entity ${ev.getEntityName()}", t)
            return
        }
        // if (ev.getEntityName().endsWith(debugEntityName)) logger.warn("======= dataFeedCheckAndRegister entityInfoList size ${entityInfoList.size()}")
        if (entityInfoList.size() > 0) {
            // logger.warn("============== found registered entity [${ev.getEntityName()}] value: ${ev}")

            // populate and pass the dataDocumentIdSet, and/or other things needed?
            Set<String> dataDocumentIdSet = new HashSet<String>()
            for (DocumentEntityInfo entityInfo in entityInfoList) {
                // only add value if a field in the document was changed
                boolean fieldModified = false
                for (String fieldName in entityInfo.fields) {
                    // logger.warn("DataFeed ${entityInfo.dataDocumentId} check field ${fieldName} isUpdate ${isUpdate} isFieldModified ${ev.isFieldModified(fieldName)} value ${valueMap.get(fieldName)} oldValue ${oldValues?.get(fieldName)}")
                    if (ev.isFieldModified(fieldName)) { fieldModified = true; break }

                    if (!valueMap.containsKey(fieldName)) continue

                    Object value = valueMap.get(fieldName)
                    Object oldValue = oldValues?.get(fieldName)

                    // logger.warn("DataFeed ${entityInfo.dataDocumentId} check field ${fieldName} isUpdate ${isUpdate} value ${value} oldValue ${oldValue} continue ${(isUpdate && value == oldValue) || (!isUpdate && value == null)}")

                    // if isUpdate but old value == new value, then it hasn't been updated, so skip it
                    if (isUpdate && value == oldValue) continue
                    // if it's a create and there is no value don't log a change
                    if (!isUpdate && value == null) continue

                    fieldModified = true
                }
                if (!fieldModified) continue

                // only add value and dataDocumentId if there are no conditions or if this record matches all conditions
                //     (not necessary, but this is an optimization to avoid false positives)
                boolean matchedConditions = true
                if (entityInfo.conditions) for (Map.Entry<String, String> conditionEntry in entityInfo.conditions.entrySet()) {
                    Object evValue = ev.get(conditionEntry.getKey())
                    // if ev doesn't have field populated, ignore the condition; we'll pick it up later in the big document query
                    if (evValue == null) continue
                    if (evValue != conditionEntry.getValue()) { matchedConditions = false; break }
                }

                if (!matchedConditions) continue

                // if we get here field(s) were modified and condition(s) passed
                dataDocumentIdSet.add((String) entityInfo.dataDocumentId)
            }

            if (dataDocumentIdSet) {
                // logger.warn("============== DataFeed registering entity value [${ev.getEntityName()}] value: ${ev.getPrimaryKeys()}")
                // NOTE: comment out this line to disable real-time push DataFeed in one simple place:
                getDataFeedSynchronization().addValueToFeed(ev, dataDocumentIdSet)
            }
        }
    }

    protected DataFeedSynchronization getDataFeedSynchronization() {
        DataFeedSynchronization dfxr = (DataFeedSynchronization) efi.ecfi.transactionFacade.getActiveSynchronization("DataFeedSynchronization")
        if (dfxr == null) {
            dfxr = new DataFeedSynchronization(this)
            dfxr.enlist()
        }
        return dfxr
    }

    final Set<String> dataFeedSkipEntities = new HashSet<String>(['moqui.entity.SequenceValueItem'])
    protected final static ArrayList<DocumentEntityInfo> emptyList = new ArrayList<DocumentEntityInfo>()

    // NOTE: this is called frequently (every create/update/delete)
    ArrayList<DocumentEntityInfo> getDataFeedEntityInfoList(String fullEntityName) {
        // see if this is a known entity in a feed
        // NOTE: this avoids issues with false negatives from the cache or excessive rebuilds (for every entity the
        //     first time) but means if an entity is added to a DataDocument at runtime it won't pick it up!!!!
        // NOTE2: this could be cleared explicitly when a DataDocument is added or changed, but that is done through
        //     direct DB stuff now (data load, etc), there is no UI or services for it
        if (entitiesWithDataFeed == null) rebuildDataFeedEntityInfo()
        if (!entitiesWithDataFeed.contains(fullEntityName)) return emptyList

        ArrayList<DocumentEntityInfo> cachedList = (ArrayList<DocumentEntityInfo>) dataFeedEntityInfo.get(fullEntityName)
        if (cachedList != null) return cachedList

        // if this is an entity to skip, return now (do after primary lookup to avoid additional performance overhead in common case)
        if (dataFeedSkipEntities.contains(fullEntityName)) {
            dataFeedEntityInfo.put(fullEntityName, emptyList)
            return emptyList
        }

        // logger.warn("=============== getting DocumentEntityInfo for [${fullEntityName}], from cache: ${entityInfoList}")
        // MAYBE (often causes issues): only rebuild if the cache is empty, most entities won't have any entry in it and don't want a rebuild for each one
        rebuildDataFeedEntityInfo()
        // now we should have all document entityInfos for all entities
        cachedList = (ArrayList<DocumentEntityInfo>) dataFeedEntityInfo.get(fullEntityName)
        if (cachedList != null) return cachedList

        // remember that we don't have any info
        dataFeedEntityInfo.put(fullEntityName, emptyList)
        return emptyList
    }

    // this should never be called except through getDataFeedEntityInfoList()
    private long lastRebuildTime = 0
    protected synchronized void rebuildDataFeedEntityInfo() {
        // under load make sure waiting threads don't redo it, give it some time
        if (System.currentTimeMillis() < (lastRebuildTime + 60000)) return

        // logger.info("Building entity.data.feed.info cache")
        long startTime = System.currentTimeMillis()

        // rebuild from the DB for this and other entities, ie have to do it for all DataFeeds and
        //     DataDocuments because we can't query it by entityName
        Map<String, ArrayList<DocumentEntityInfo>> localInfo = new HashMap<>()

        EntityList dataFeedAndDocumentList = efi.find("moqui.entity.feed.DataFeedAndDocument")
                .condition("dataFeedTypeEnumId", "DTFDTP_RT_PUSH").useCache(true).disableAuthz().list()
        //logger.warn("============= got dataFeedAndDocumentList: ${dataFeedAndDocumentList}")
        Set<String> fullDataDocumentIdSet = new HashSet<String>()
        int dataFeedAndDocumentListSize = dataFeedAndDocumentList.size()
        for (int i = 0; i < dataFeedAndDocumentListSize; i++) {
            EntityValue dataFeedAndDocument = (EntityValue) dataFeedAndDocumentList.get(i)
            fullDataDocumentIdSet.add(dataFeedAndDocument.getString("dataDocumentId"))
        }

        for (String dataDocumentId in fullDataDocumentIdSet) {
            Map<String, DocumentEntityInfo> entityInfoMap = getDataDocumentEntityInfo(dataDocumentId)
            if (entityInfoMap == null) {
                logger.error("Invalid or missing DataDocument ${dataDocumentId}, ignoring for real time feed")
                continue
            }
            // got a Map for all entities in the document, now split them by entity and add to master list for the entity
            for (Map.Entry<String, DocumentEntityInfo> entityInfoMapEntry in entityInfoMap.entrySet()) {
                String entityName = entityInfoMapEntry.getKey()
                ArrayList<DocumentEntityInfo> newEntityInfoList = (ArrayList<DocumentEntityInfo>) localInfo.get(entityName)
                if (newEntityInfoList == null) {
                    newEntityInfoList = new ArrayList<DocumentEntityInfo>()
                    localInfo.put(entityName, newEntityInfoList)
                    // logger.warn("============= added dataFeedEntityInfo entry for entity [${entityInfoMapEntry.getKey()}]")
                }
                newEntityInfoList.add(entityInfoMapEntry.getValue())
            }
        }

        Set<String> entityNameSet = localInfo.keySet()
        if (entitiesWithDataFeed == null) {
            logger.info("Built entity.data.feed.info cache for in ${System.currentTimeMillis() - startTime}ms, entries for ${entityNameSet.size()} entities")
            if (logger.isTraceEnabled()) logger.trace("Built entity.data.feed.info cache for in ${System.currentTimeMillis() - startTime}ms, entries for ${entityNameSet.size()} entities: ${entityNameSet}")
        } else {
            logger.info("Rebuilt entity.data.feed.info cache for in ${System.currentTimeMillis() - startTime}ms, entries for ${entityNameSet.size()} entities")
        }
        dataFeedEntityInfo.putAll(localInfo)
        entitiesWithDataFeed = entityNameSet
        lastRebuildTime = System.currentTimeMillis()
    }

    Map<String, DocumentEntityInfo> getDataDocumentEntityInfo(String dataDocumentId) {
        EntityValue dataDocument = null
        EntityList dataDocumentFieldList = null
        EntityList dataDocumentConditionList = null
        boolean alreadyDisabled = efi.ecfi.getExecutionContext().getArtifactExecution().disableAuthz()
        try {
            dataDocument = efi.fastFindOne("moqui.entity.document.DataDocument", true, false, dataDocumentId)
            if (dataDocument == null) throw new EntityException("No DataDocument found with ID [${dataDocumentId}]")
            dataDocumentFieldList = dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, null, true, false)
            dataDocumentConditionList = dataDocument.findRelated("moqui.entity.document.DataDocumentCondition", null, null, true, false)
        } finally {
            if (!alreadyDisabled) efi.ecfi.getExecutionContext().getArtifactExecution().enableAuthz()
        }

        String primaryEntityName = dataDocument.primaryEntityName
        if (!efi.isEntityDefined(primaryEntityName)) {
            logger.error("Could not find primary entity ${primaryEntityName} for DataDocument ${dataDocumentId}")
            return null
        }
        EntityDefinition primaryEd = efi.getEntityDefinition(primaryEntityName)

        Map<String, DocumentEntityInfo> entityInfoMap = [:]

        // start with the primary entity
        entityInfoMap.put(primaryEntityName, new DocumentEntityInfo(primaryEntityName, dataDocumentId, primaryEntityName, ""))

        // have to go through entire fieldTree instead of entity names directly from fieldPath because may not have hash (#) separator
        Map<String, Object> fieldTree = [:]
        for (EntityValue dataDocumentField in dataDocumentFieldList) {
            String fieldPath = (String) dataDocumentField.fieldPath
            if (fieldPath.contains("(")) continue
            Map currentTree = fieldTree
            DocumentEntityInfo currentEntityInfo = entityInfoMap.get(primaryEntityName)
            StringBuilder currentRelationshipPath = new StringBuilder()
            EntityDefinition currentEd = primaryEd
            ArrayList<String> fieldPathElementList = EntityDataDocument.fieldPathToList(fieldPath)
            int fieldPathElementListSize = fieldPathElementList.size()
            for (int i = 0; i < fieldPathElementListSize; i++) {
                String fieldPathElement = (String) fieldPathElementList.get(i)
                if (i < (fieldPathElementListSize - 1)) {
                    if (currentRelationshipPath.length() > 0) currentRelationshipPath.append(":")
                    currentRelationshipPath.append(fieldPathElement)

                    Map subTree = (Map) currentTree.get(fieldPathElement)
                    if (subTree == null) { subTree = [:]; currentTree.put(fieldPathElement, subTree) }
                    currentTree = subTree

                    // make sure we have an entityInfo Map
                    RelationshipInfo relInfo = currentEd.getRelationshipInfo(fieldPathElement)
                    if (relInfo == null) throw new EntityException("Could not find relationship [${fieldPathElement}] from entity [${currentEd.getFullEntityName()}] as part of DataDocumentField.fieldPath [${fieldPath}]")
                    String relEntityName = relInfo.relatedEntityName
                    EntityDefinition relEd = relInfo.relatedEd

                    // add entry for the related entity
                    if (!entityInfoMap.containsKey(relEntityName)) entityInfoMap.put(relEntityName,
                            new DocumentEntityInfo(relEntityName, dataDocumentId, primaryEntityName,
                                    currentRelationshipPath.toString()))

                    // add PK fields of the related entity as fields for the current entity so changes on them will also trigger a data feed
                    Map relKeyMap = relInfo.keyMap
                    for (String fkFieldName in relKeyMap.keySet()) {
                        currentTree.put(fkFieldName, fkFieldName)
                        // save the current field name (not the alias)
                        currentEntityInfo.fields.add(fkFieldName)
                    }

                    currentEntityInfo = entityInfoMap.get(relEntityName)
                    currentEd = relEd
                } else {
                    String ddfFieldNameAlias = (String) dataDocumentField.fieldNameAlias
                    currentTree.put(fieldPathElement, ddfFieldNameAlias != null && !ddfFieldNameAlias.isEmpty() ? ddfFieldNameAlias : fieldPathElement)
                    // save the current field name (not the alias)
                    currentEntityInfo.fields.add(fieldPathElement)
                    // see if there are any conditions for this alias, if so add the fieldName/value to the entity conditions Map
                    for (EntityValue dataDocumentCondition in dataDocumentConditionList) {
                        if (dataDocumentCondition.fieldNameAlias == ddfFieldNameAlias)
                            currentEntityInfo.conditions.put(fieldPathElement, (String) dataDocumentCondition.fieldValue)
                    }
                }
            }
        }

        // logger.warn("============ got entityInfoMap for doc [${dataDocumentId}]: ${entityInfoMap}\n============ for fieldTree: ${fieldTree}")

        return entityInfoMap
    }

    static class DocumentEntityInfo implements Serializable {
        String fullEntityName
        String dataDocumentId
        String primaryEntityName
        String relationshipPath
        Set<String> fields = new HashSet<String>()
        Map<String, String> conditions = [:]
        // will we need this? Map<String, DocumentEntityInfo> subEntities

        DocumentEntityInfo(String fullEntityName, String dataDocumentId, String primaryEntityName, String relationshipPath) {
            this.fullEntityName = fullEntityName
            this.dataDocumentId = dataDocumentId
            this.primaryEntityName = primaryEntityName
            this.relationshipPath = relationshipPath
        }

        @Override
        String toString() {
            StringBuilder sb = new StringBuilder()
            sb.append("DocumentEntityInfo [")
            sb.append("fullEntityName:").append(fullEntityName).append(",")
            sb.append("dataDocumentId:").append(dataDocumentId).append(",")
            sb.append("primaryEntityName:").append(primaryEntityName).append(",")
            sb.append("relationshipPath:").append(relationshipPath).append(",")
            sb.append("fields:").append(fields).append(",")
            sb.append("conditions:").append(conditions).append(",")
            sb.append("]")
            return sb.toString()
        }
    }

    @CompileStatic
    static class DataFeedSynchronization implements Synchronization {
        protected final static Logger logger = LoggerFactory.getLogger(DataFeedSynchronization.class)

        protected ExecutionContextFactoryImpl ecfi
        protected EntityDataFeed edf

        protected Transaction tx = null

        protected EntityList feedValues
        protected Set<String> allDataDocumentIds = new HashSet<String>()

        DataFeedSynchronization(EntityDataFeed edf) {
            // logger.warn("========= Creating new DataFeedSynchronization")
            this.edf = edf
            ecfi = edf.getEfi().ecfi
            feedValues = new EntityListImpl(edf.getEfi())
        }

        void enlist() {
            // logger.warn("========= Enlisting new DataFeedSynchronization")
            TransactionManager tm = ecfi.transactionFacade.getTransactionManager()
            if (tm == null || tm.getStatus() != Status.STATUS_ACTIVE) throw new XAException("Cannot enlist: no transaction manager or transaction not active")
            Transaction tx = tm.getTransaction()
            if (tx == null) throw new XAException(XAException.XAER_NOTA)
            this.tx = tx

            // logger.warn("================= puttng and enlisting new DataFeedSynchronization")
            ecfi.transactionFacade.putAndEnlistActiveSynchronization("DataFeedSynchronization", this)
        }

        void addValueToFeed(EntityValue ev, Set<String> dataDocumentIdSet) {
            // this log message is for an issue where Atomikos seems to suspend and resume without calling start() on
            //     this XAResource; everything seems to work fine, but it results in funny state
            // this can be reproduced by running the data load with DataFeed/DataDocument data already in the DB
            // if (!active && logger.isTraceEnabled()) logger.trace("Adding value to inactive DataFeedSynchronization! \nThis shouldn't happen and may mean the same DataFeedSynchronization is being used after a TX suspend; suspended=${suspended}")
            feedValues.add(ev)
            allDataDocumentIds.addAll(dataDocumentIdSet)
        }

        @Override
        void beforeCompletion() { }

        @Override
        void afterCompletion(int status) {
            if (status == Status.STATUS_COMMITTED) {
                // send feed in new thread and tx
                FeedRunnable runnable = new FeedRunnable(ecfi, edf, feedValues, allDataDocumentIds)
                try {
                    ecfi.workerPool.execute(runnable)
                } catch (RejectedExecutionException e) {
                    logger.error("Worker pool rejected DataFeed run: " + e.toString())
                }
                // logger.warn("================================================================\n================ feeding DataFeed with documents ${allDataDocumentIds}")
            }
        }
    }

    static class FeedRunnable implements Runnable {
        private ExecutionContextFactoryImpl ecfi
        private EntityDataFeed edf
        private EntityList feedValues
        private Set<String> allDataDocumentIds
        FeedRunnable(ExecutionContextFactoryImpl ecfi, EntityDataFeed edf, EntityList feedValues, Set<String> allDataDocumentIds) {
            this.ecfi = ecfi
            this.edf = edf
            this.allDataDocumentIds = allDataDocumentIds
            this.feedValues = feedValues
        }

        @Override
        void run() {
            ExecutionContextImpl threadEci = ecfi.getEci()
            boolean beganTransaction = ecfi.transactionFacade.begin(600)
            try {
                if (logger.isTraceEnabled()) logger.trace("Doing DataFeed with allDataDocumentIds: ${allDataDocumentIds}, feedValues: ${feedValues}")

                EntityFacadeImpl efi = ecfi.entityFacade
                Timestamp feedStamp = new Timestamp(System.currentTimeMillis())
                // assemble data and call DataFeed services

                // iterate through dataDocumentIdSet
                for (String dataDocumentId in allDataDocumentIds) {
                    EntityValue dataDocument = null
                    EntityList dataDocumentFieldList = null
                    boolean alreadyDisabled = threadEci.artifactExecutionFacade.disableAuthz()
                    try {
                        // for each DataDocument go through feedValues and get the primary entity's PK field(s) for each
                        dataDocument = efi.fastFindOne("moqui.entity.document.DataDocument", true, false, dataDocumentId)
                        dataDocumentFieldList =
                            dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, null, true, false)
                    } finally {
                        if (!alreadyDisabled) threadEci.artifactExecutionFacade.enableAuthz()
                    }

                    String primaryEntityName = dataDocument.primaryEntityName
                    EntityDefinition primaryEd = efi.getEntityDefinition(primaryEntityName)
                    List<String> primaryPkFieldNames = primaryEd.getPkFieldNames()
                    Set<Map> primaryPkFieldValues = new HashSet<Map>()

                    Map<String, String> pkFieldAliasMap = [:]
                    for (String pkFieldName in primaryPkFieldNames) {
                        boolean aliasSet = false
                        for (EntityValue dataDocumentField in dataDocumentFieldList) {
                            if (dataDocumentField.fieldPath == pkFieldName) {
                                pkFieldAliasMap.put(pkFieldName, (String) dataDocumentField.fieldNameAlias ?: pkFieldName)
                                aliasSet = true
                            }
                        }
                        if (aliasSet) pkFieldAliasMap.put(pkFieldName, pkFieldName)
                    }


                    for (EntityValue currentEv in feedValues) {
                        String currentEntityName = currentEv.getEntityName()
                        List<DocumentEntityInfo> currentEntityInfoList = edf.getDataFeedEntityInfoList(currentEntityName)
                        for (DocumentEntityInfo currentEntityInfo in currentEntityInfoList) {
                            if (currentEntityInfo.dataDocumentId == dataDocumentId) {
                                if (currentEntityName == primaryEntityName) {
                                    // this is the easy one, primary entity updated just use it's values
                                    Map pkFieldValue = [:]
                                    for (String pkFieldName in primaryPkFieldNames)
                                        pkFieldValue.put(pkFieldName, currentEv.get(pkFieldName))
                                    primaryPkFieldValues.add(pkFieldValue)
                                } else {
                                    // more complex, need to follow relationships backwards (reverse
                                    //     relationships) to get the primary entity's value(s)
                                    List<String> relationshipList = Arrays.asList(currentEntityInfo.relationshipPath.split(":"))
                                    // ArrayList<RelationshipInfo> relInfoList = new ArrayList<RelationshipInfo>()
                                    ArrayList<String> backwardRelList = new ArrayList<String>()
                                    // add the relationships backwards, get relInfo for each
                                    EntityDefinition lastRelEd = primaryEd
                                    for (String relElement in relationshipList) {
                                        RelationshipInfo relInfo = lastRelEd.getRelationshipInfo(relElement)
                                        backwardRelList.add(0, relInfo.relationshipName)
                                        lastRelEd = relInfo.relatedEd
                                    }
                                    // add the primary entity name to the end as that is the target
                                    backwardRelList.add(primaryEntityName)

                                    String prevRelName = backwardRelList.get(0)
                                    List<EntityValueBase> prevRelValueList = [(EntityValueBase) currentEv]
                                    // skip the first one, it is the current entity
                                    for (int i = 1; i < backwardRelList.size(); i++) {
                                        // try to find the relationship be the title of the previous
                                        //     relationship name + the current entity name, then by the current
                                        //     entity name alone
                                        String currentRelName = backwardRelList.get(i)
                                        String currentRelEntityName = currentRelName.contains("#") ?
                                            currentRelName.substring(0, currentRelName.indexOf("#")) :
                                            currentRelName
                                        // all values should be for the same entity, so just use the first
                                        EntityDefinition prevRelValueEd = prevRelValueList.get(0).getEntityDefinition()


                                        RelationshipInfo backwardRelInfo = null
                                        // Node backwardRelNode = null
                                        if (prevRelName.contains("#")) {
                                            String title = prevRelName.substring(0, prevRelName.indexOf("#"))
                                            backwardRelInfo = prevRelValueEd.getRelationshipInfo((String) title + "#" + currentRelEntityName)
                                        }
                                        if (backwardRelInfo == null)
                                            backwardRelInfo = prevRelValueEd.getRelationshipInfo(currentRelEntityName)

                                        if (backwardRelInfo == null) throw new EntityException("For DataFeed could not find backward relationship for DataDocument [${dataDocumentId}] from entity [${prevRelValueEd.getFullEntityName()}] to entity [${currentRelEntityName}], previous relationship is [${prevRelName}], current relationship is [${currentRelName}]")

                                        String backwardRelName = backwardRelInfo.relationshipName
                                        List<EntityValueBase> currentRelValueList = []
                                        alreadyDisabled = threadEci.artifactExecutionFacade.disableAuthz()
                                        try {
                                            for (EntityValueBase prevRelValue in prevRelValueList) {
                                                EntityList backwardRelValueList = prevRelValue.findRelated(backwardRelName, null, null, false, false)
                                                for (EntityValue backwardRelValue in backwardRelValueList)
                                                    currentRelValueList.add((EntityValueBase) backwardRelValue)
                                            }
                                        } finally {
                                            if (!alreadyDisabled) threadEci.artifactExecutionFacade.enableAuthz()
                                        }

                                        prevRelName = currentRelName
                                        prevRelValueList = currentRelValueList

                                        if (!prevRelValueList) {
                                            if (logger.isTraceEnabled()) logger.trace("Creating DataFeed for DataDocument [${dataDocumentId}], no backward rel values found for [${backwardRelName}] on updated values: ${prevRelValueList}")
                                            break
                                        }
                                    }

                                    // go through final prevRelValueList (which should be for the primary
                                    //     entity) and get the PK for each
                                    if (prevRelValueList) for (EntityValue primaryEv in prevRelValueList) {
                                        Map pkFieldValue = [:]
                                        for (String pkFieldName in primaryPkFieldNames)
                                            pkFieldValue.put(pkFieldName, primaryEv.get(pkFieldName))
                                        primaryPkFieldValues.add(pkFieldValue)
                                    }
                                }
                            }
                        }
                    }

                    // if there aren't really any values for the document (a value updated that isn't really in
                    //    a document) then skip it, don't want to query with no constraints and get a huge document
                    if (!primaryPkFieldValues) {
                        if (logger.isTraceEnabled()) {
                            String errMsg = "Skipping feed for DataDocument [${dataDocumentId}], no primary PK values found in feed values"
                            /*
                            StringBuilder sb = new StringBuilder()
                            sb.append(errMsg).append('\n')
                            sb.append("Primary Entity: ").append(primaryEntityName).append(": ").append(primaryPkFieldNames).append('\n')
                            sb.append("Feed Values:").append('\n')
                            for (EntityValue ev in feedValues) {
                                sb.append('    ').append(ev).append('\n')
                            }
                            */
                            logger.trace(errMsg)
                        }
                        continue
                    }

                    // for primary entity with 1 PK field do an IN condition, for >1 PK field do an and cond for
                    //     each PK and an or list cond to combine them
                    EntityCondition condition
                    if (primaryPkFieldNames.size() == 1) {
                        String pkFieldName = primaryPkFieldNames.get(0)
                        Set<Object> pkValues = new HashSet<Object>()
                        for (Map pkFieldValueMap in primaryPkFieldValues)
                            pkValues.add(pkFieldValueMap.get(pkFieldName))
                        // if pk field is aliased use the alias name
                        String aliasedPkName = pkFieldAliasMap.get(pkFieldName) ?: pkFieldName
                        condition = efi.getConditionFactory().makeCondition(aliasedPkName, EntityCondition.IN, pkValues)
                    } else {
                        List<EntityCondition> condList = []
                        for (Map pkFieldValueMap in primaryPkFieldValues) {
                            Map<String, Object> condAndMap = new LinkedHashMap<String, Object>()
                            // if pk field is aliased used the alias name
                            for (String pkFieldName in primaryPkFieldNames)
                                condAndMap.put(pkFieldAliasMap.get(pkFieldName), pkFieldValueMap.get(pkFieldName))
                            condList.add(efi.getConditionFactory().makeCondition(condAndMap))
                        }
                        condition = efi.getConditionFactory().makeCondition(condList, EntityCondition.OR)
                    }

                    alreadyDisabled = threadEci.artifactExecutionFacade.disableAuthz()
                    try {
                        // generate the document with the extra condition and send it to all DataFeeds
                        //     associated with the DataDocument
                        List<Map> documents = efi.getDataDocuments(dataDocumentId, condition, null, null)

                        if (documents) {
                            EntityList dataFeedAndDocumentList = efi.find("moqui.entity.feed.DataFeedAndDocument")
                                    .condition("dataFeedTypeEnumId", "DTFDTP_RT_PUSH")
                                    .condition("dataDocumentId", dataDocumentId).useCache(true).list()

                            // logger.warn("=========== FEED document ${dataDocumentId}, documents ${documents.size()}, condition: ${condition}\n dataFeedAndDocumentList: ${dataFeedAndDocumentList.feedReceiveServiceName}")

                            // do the actual feed receive service calls (authz is disabled to allow the service
                            //     call, but also allows anything in the services...)
                            for (EntityValue dataFeedAndDocument in dataFeedAndDocumentList) {
                                // NOTE: this is a sync call so authz disabled is preserved; it is in its own thread
                                //     so user/etc are not inherited here
                                ecfi.serviceFacade.sync().name((String) dataFeedAndDocument.feedReceiveServiceName)
                                        .parameters([dataFeedId:dataFeedAndDocument.dataFeedId, feedStamp:feedStamp,
                                        documentList:documents]).call()
                                if (threadEci.messageFacade.hasError()) break
                            }
                        } else {
                            // this is pretty common, some operation done on a record that doesn't match the conditions for the feed
                            if (logger.isTraceEnabled()) logger.trace("In DataFeed no documents found for dataDocumentId [${dataDocumentId}]")
                        }
                    } finally {
                        if (!alreadyDisabled) threadEci.artifactExecutionFacade.enableAuthz()
                    }

                    if (threadEci.messageFacade.hasError()) break
                } // close dataDocumentId for loop
            } catch (Throwable t) {
                logger.error("Error running Real-time DataFeed", t)
                ecfi.transactionFacade.rollback(beganTransaction, "Error running Real-time DataFeed", t)
            } finally {
                // commit transaction if we started one and still there
                if (beganTransaction && ecfi.transactionFacade.isTransactionInPlace())
                    ecfi.transactionFacade.commit()
                // destroy the ECI created for this Runnable in this thread
                if (threadEci != null) threadEci.destroy()
            }
        }
    }
}
