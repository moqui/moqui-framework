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

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import org.elasticsearch.action.admin.indices.create.CreateIndexRequestBuilder
import org.elasticsearch.action.admin.indices.exists.indices.IndicesExistsRequest
import org.elasticsearch.client.Client
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityDynamicView
import org.moqui.entity.EntityException
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityList
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.moqui.impl.StupidUtilities
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.impl.entity.condition.FieldValueCondition
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

@CompileStatic
class EntityDataDocument {
    protected final static Logger logger = LoggerFactory.getLogger(EntityDataDocument.class)

    protected final EntityFacadeImpl efi

    EntityDataDocument(EntityFacadeImpl efi) {
        this.efi = efi
    }

    // EntityFacadeImpl getEfi() { return efi }

    int writeDocumentsToFile(String filename, List<String> dataDocumentIds, EntityCondition condition,
                             Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp, boolean prettyPrint) {
        File outFile = new File(filename)
        if (!outFile.createNewFile()) {
            efi.ecfi.executionContext.message.addError("File ${filename} already exists.")
            return 0
        }

        PrintWriter pw = new PrintWriter(outFile)

        pw.write("[\n")

        int valuesWritten = writeDocumentsToWriter(pw, dataDocumentIds, condition, fromUpdateStamp, thruUpdatedStamp, prettyPrint)

        pw.write("{}\n]\n")
        pw.close()
        efi.ecfi.executionContext.message.addMessage("Wrote ${valuesWritten} documents to file ${filename}")
        return valuesWritten
    }

    int writeDocumentsToDirectory(String dirname, List<String> dataDocumentIds, EntityCondition condition,
                                  Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp, boolean prettyPrint) {
        File outDir = new File(dirname)
        if (!outDir.exists()) outDir.mkdir()
        if (!outDir.isDirectory()) {
            efi.ecfi.executionContext.message.addError("Path ${dirname} is not a directory.")
            return 0
        }

        int valuesWritten = 0

        for (String dataDocumentId in dataDocumentIds) {
            String filename = "${dirname}/${dataDocumentId}.json"
            File outFile = new File(filename)
            if (outFile.exists()) {
                efi.ecfi.executionContext.message.addError("File ${filename} already exists, skipping document ${dataDocumentId}.")
                continue
            }
            outFile.createNewFile()

            PrintWriter pw = new PrintWriter(outFile)
            pw.write("[\n")

            valuesWritten += writeDocumentsToWriter(pw, [dataDocumentId], condition, fromUpdateStamp, thruUpdatedStamp, prettyPrint)

            pw.write("{}\n]\n")
            pw.close()
            efi.ecfi.executionContext.message.addMessage("Wrote ${valuesWritten} records to file ${filename}")
        }

        return valuesWritten
    }

    int writeDocumentsToWriter(Writer pw, List<String> dataDocumentIds, EntityCondition condition,
                               Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp, boolean prettyPrint) {
        int valuesWritten = 0

        for (String dataDocumentId in dataDocumentIds) {
            List<Map> documentList = getDataDocuments(dataDocumentId, condition, fromUpdateStamp, thruUpdatedStamp)
            for (Map document in documentList) {
                String json = JsonOutput.toJson(document)
                if (prettyPrint) {
                    pw.write(JsonOutput.prettyPrint(json))
                } else {
                    pw.write(json)
                }
                pw.write(",\n")
                valuesWritten++
            }
        }

        return valuesWritten
    }

    List<Map> getDataDocuments(String dataDocumentId, EntityCondition condition, Timestamp fromUpdateStamp,
                               Timestamp thruUpdatedStamp) {
        EntityValue dataDocument = efi.find("moqui.entity.document.DataDocument")
                .condition("dataDocumentId", dataDocumentId).useCache(true).one()
        if (dataDocument == null) throw new EntityException("No DataDocument found with ID [${dataDocumentId}]")
        EntityList dataDocumentFieldList = dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, null, true, false)
        EntityList dataDocumentRelAliasList = dataDocument.findRelated("moqui.entity.document.DataDocumentRelAlias", null, null, true, false)
        EntityList dataDocumentConditionList = dataDocument.findRelated("moqui.entity.document.DataDocumentCondition", null, null, true, false)

        String primaryEntityName = dataDocument.primaryEntityName
        EntityDefinition primaryEd = efi.getEntityDefinition(primaryEntityName)
        List<String> primaryPkFieldNames = primaryEd.getPkFieldNames()

        // build the field tree, nested Maps for relationship field path elements and field alias String for field name path elements
        Map<String, Object> fieldTree = [:]
        Map<String, String> fieldAliasPathMap = [:]
        populateFieldTreeAndAliasPathMap(dataDocumentFieldList, primaryPkFieldNames, fieldTree, fieldAliasPathMap)
        // logger.warn("=========== ${dataDocumentId} fieldTree=${fieldTree}")
        // logger.warn("=========== ${dataDocumentId} fieldAliasPathMap=${fieldAliasPathMap}")

        // make the relationship alias Map
        Map relationshipAliasMap = [:]
        for (EntityValue dataDocumentRelAlias in dataDocumentRelAliasList)
            relationshipAliasMap.put(dataDocumentRelAlias.relationshipName, dataDocumentRelAlias.documentAlias)

        // build the query condition for the primary entity and all related entities
        EntityFind mainFind = efi.find(primaryEntityName)
        EntityDynamicView dynamicView = mainFind.makeEntityDynamicView()

        // add member entities and field aliases to dynamic view
        dynamicView.addMemberEntity("PRIM_ENT", primaryEntityName, null, null, null)
        fieldTree.put("_ALIAS", "PRIM_ENT")
        StupidUtilities.Incrementer incrementer = new StupidUtilities.Incrementer()
        addDataDocRelatedEntity(dynamicView, "PRIM_ENT", fieldTree, incrementer)

        // logger.warn("=========== ${dataDocumentId} ViewEntityNode=${((EntityDynamicViewImpl) dynamicView).getViewEntityNode()}")

        // add conditions
        if (condition) mainFind.condition(condition)
        for (EntityValue dataDocumentCondition in dataDocumentConditionList) {
            if (!fieldAliasPathMap.containsKey(dataDocumentCondition.fieldNameAlias))
                throw new EntityException("Found DataDocumentCondition with fieldNameAlias [${dataDocumentCondition.fieldNameAlias}] that is not aliased in DataDocument [${dataDocumentId}]")
            if (dataDocumentCondition.postQuery != "Y") {
                mainFind.condition((String) dataDocumentCondition.fieldNameAlias, (String) dataDocumentCondition.operator ?: 'equals',
                        dataDocumentCondition.fieldValue)
            }
        }

        // create a condition with an OR list of date range comparisons to check that at least one member-entity has lastUpdatedStamp in range
        if ((Object) fromUpdateStamp != null || (Object) thruUpdatedStamp != null) {
            List<EntityCondition> dateRangeOrCondList = []
            for (MNode memberEntityNode in dynamicView.getMemberEntityNodes()) {
                ConditionField ludCf = new ConditionField(memberEntityNode.attribute("entity-alias"),
                        "lastUpdatedStamp", efi.getEntityDefinition(memberEntityNode.attribute("entity-name")))
                List<EntityCondition> dateRangeFieldCondList = []
                if ((Object) fromUpdateStamp != null) {
                    dateRangeFieldCondList.add(efi.getConditionFactory().makeCondition(
                            new FieldValueCondition(efi.entityConditionFactory, ludCf, EntityCondition.EQUALS, null),
                            EntityCondition.OR,
                            new FieldValueCondition(efi.entityConditionFactory, ludCf, EntityCondition.GREATER_THAN_EQUAL_TO, fromUpdateStamp)))
                }
                if ((Object) thruUpdatedStamp != null) {
                    dateRangeFieldCondList.add(efi.getConditionFactory().makeCondition(
                            new FieldValueCondition(efi.entityConditionFactory, ludCf, EntityCondition.EQUALS, null),
                            EntityCondition.OR,
                            new FieldValueCondition(efi.entityConditionFactory, ludCf, EntityCondition.LESS_THAN, thruUpdatedStamp)))
                }
                dateRangeOrCondList.add(efi.getConditionFactory().makeCondition(dateRangeFieldCondList, EntityCondition.AND))
            }
            mainFind.condition(efi.getConditionFactory().makeCondition(dateRangeOrCondList, EntityCondition.OR))
        }
        // logger.warn("=========== DataDocument query condition for ${dataDocumentId} mainFind.condition=${((EntityFindImpl) mainFind).getWhereEntityCondition()}")

        // do the one big query
        EntityListIterator mainEli = mainFind.iterator()
        Map<String, Map<String, Object>> documentMapMap = [:]
        try {
            for (EntityValue ev in mainEli) {
                // logger.warn("=========== DataDocument query result for ${dataDocumentId}: ${ev}")

                StringBuffer pkCombinedSb = new StringBuffer()
                for (String pkFieldName in primaryPkFieldNames) {
                    if (pkCombinedSb.length() > 0) pkCombinedSb.append("::")
                    pkCombinedSb.append(ev.getString(pkFieldName))
                }
                String docId = pkCombinedSb.toString()

                /*
                  - _index = tenantId + "__" + DataDocument.indexName
                  - _type = dataDocumentId
                  - _id = pk field values from primary entity, double colon separated
                  - _timestamp = document created time
                  - Map for primary entity with primaryEntityName as key
                  - nested List of Maps for each related entity with aliased fields with relationship name as key
                 */
                Map<String, Object> docMap = documentMapMap.get(docId)
                if (docMap == null) {
                    // add special entries
                    docMap = [_type:dataDocumentId, _id:docId] as Map<String, Object>
                    docMap.put('_timestamp', efi.ecfi.getL10nFacade().format(
                            thruUpdatedStamp ?: new Timestamp(System.currentTimeMillis()), "yyyy-MM-dd'T'HH:mm:ssZ"))
                    String _index = efi.getEcfi().getExecutionContext().getTenantId()
                    if (dataDocument.indexName) _index = _index + "__" + dataDocument.indexName
                    docMap.put('_index', _index.toLowerCase())
                    docMap.put('_entity', primaryEd.getShortAlias() ?: primaryEd.getFullEntityName())

                    // add Map for primary entity
                    Map primaryEntityMap = [:]
                    for (Map.Entry fieldTreeEntry in fieldTree.entrySet()) {
                        if (fieldTreeEntry.getValue() instanceof String) {
                            if (fieldTreeEntry.getKey() == "_ALIAS") continue
                            String fieldName = fieldTreeEntry.getValue()
                            Object value = ev.get(fieldName)
                            if (value) primaryEntityMap.put(fieldName, value)
                        }
                    }
                    // docMap.put((String) relationshipAliasMap.get(primaryEntityName) ?: primaryEntityName, primaryEntityMap)
                    docMap.putAll(primaryEntityMap)

                    documentMapMap.put(docId, docMap)
                }

                // recursively add Map or List of Maps for each related entity
                populateDataDocRelatedMap(ev, docMap, primaryEd, fieldTree, relationshipAliasMap, false)
            }
        } finally {
            mainEli.close()
        }

        // make the actual list and return it
        List<Map> documentMapList = []
        for (Map.Entry<String, Map> documentMapEntry in documentMapMap.entrySet()) {
            Map docMap = documentMapEntry.getValue()
            // call the manualDataServiceName service for each document
            if (dataDocument.manualDataServiceName) {
                Map result = efi.ecfi.getServiceFacade().sync().name((String) dataDocument.manualDataServiceName)
                        .parameters([dataDocumentId:dataDocumentId, document:docMap]).call()
                if (result.document) docMap = (Map) result.document
            }

            // check postQuery conditions
            boolean allPassed = true
            for (EntityValue dataDocumentCondition in dataDocumentConditionList) if (dataDocumentCondition.postQuery == "Y") {
                Set<Object> valueSet = new HashSet<Object>()
                StupidUtilities.findAllFieldsNestedMap((String) dataDocumentCondition.fieldNameAlias, docMap, valueSet)
                if (!valueSet) {
                    if (!dataDocumentCondition.fieldValue) { continue }
                    else { allPassed = false; break }
                }
                if (!dataDocumentCondition.fieldValue) { allPassed = false; break }
                Object fieldValueObj = dataDocumentCondition.fieldValue.asType(valueSet.first().class)
                if (!(fieldValueObj in valueSet)) { allPassed = false; break }
            }

            if (allPassed) documentMapList.add(docMap)
        }
        return documentMapList
    }

    static void populateFieldTreeAndAliasPathMap(EntityList dataDocumentFieldList, List<String> primaryPkFieldNames,
                                          Map<String, Object> fieldTree, Map<String, String> fieldAliasPathMap) {
        for (EntityValue dataDocumentField in dataDocumentFieldList) {
            String fieldPath = dataDocumentField.fieldPath
            Iterator<String> fieldPathElementIter = fieldPath.split(":").iterator()
            Map currentTree = fieldTree
            while (fieldPathElementIter.hasNext()) {
                String fieldPathElement = fieldPathElementIter.next()
                if (fieldPathElementIter.hasNext()) {
                    Map subTree = (Map) currentTree.get(fieldPathElement)
                    if (subTree == null) { subTree = [:]; currentTree.put(fieldPathElement, subTree) }
                    currentTree = subTree
                } else {
                    currentTree.put(fieldPathElement, dataDocumentField.fieldNameAlias ?: fieldPathElement)
                    fieldAliasPathMap.put((String) dataDocumentField.fieldNameAlias ?: fieldPathElement, (String) dataDocumentField.fieldPath)
                }
            }
        }
        // make sure all PK fields of the primary entity are aliased
        for (String pkFieldName in primaryPkFieldNames) if (!fieldAliasPathMap.containsKey(pkFieldName)) {
            fieldTree.put(pkFieldName, pkFieldName)
            fieldAliasPathMap.put(pkFieldName, pkFieldName)
        }
    }

    protected void populateDataDocRelatedMap(EntityValue ev, Map<String, Object> parentDocMap, EntityDefinition parentEd,
                                             Map fieldTreeCurrent, Map relationshipAliasMap, boolean setFields) {
        for (Map.Entry fieldTreeEntry in fieldTreeCurrent.entrySet()) {
            if (fieldTreeEntry.getKey() instanceof String && fieldTreeEntry.getKey() == "_ALIAS") continue
            if (fieldTreeEntry.getValue() instanceof Map) {
                String relationshipName = fieldTreeEntry.getKey()
                Map fieldTreeChild = (Map) fieldTreeEntry.getValue()

                EntityDefinition.RelationshipInfo relationshipInfo = parentEd.getRelationshipInfo(relationshipName)
                String relDocumentAlias = relationshipAliasMap.get(relationshipName) ?: relationshipInfo.shortAlias ?: relationshipName
                EntityDefinition relatedEd = relationshipInfo.relatedEd
                boolean isOneRelationship = relationshipInfo.isTypeOne

                Map relatedEntityDocMap = null
                boolean recurseSetFields = true
                if (isOneRelationship) {
                    // we only need a single Map
                    // don't put related one fields in child Map: relatedEntityDocMap = (Map) parentDocMap.get(relDocumentAlias)
                    if (relatedEntityDocMap == null) {
                        // don't put related one fields in child Map: relatedEntityDocMap = [:]
                        // now time to recurse
                        populateDataDocRelatedMap(ev, parentDocMap, relatedEd, fieldTreeChild, relationshipAliasMap, recurseSetFields)
                        // don't put related one fields in child Map: populateDataDocRelatedMap(ev, relatedEntityDocMap, relatedEd, fieldTreeChild, relationshipAliasMap, recurseSetFields)
                        // don't put related one fields in child Map: if (relatedEntityDocMap) parentDocMap.put(relDocumentAlias, relatedEntityDocMap)
                    } else {
                        recurseSetFields = false
                        // now time to recurse
                        populateDataDocRelatedMap(ev, relatedEntityDocMap, relatedEd, fieldTreeChild, relationshipAliasMap, recurseSetFields)
                    }
                } else {
                    // we need a List of Maps

                    // see if there is a Map in the List in the matching entry
                    List<Map> relatedEntityDocList = (List<Map>) parentDocMap.get(relDocumentAlias)
                    if (relatedEntityDocList != null) {
                        for (Map candidateMap in relatedEntityDocList) {
                            boolean allMatch = true
                            for (Map.Entry fieldTreeChildEntry in fieldTreeChild.entrySet()) {
                                if (fieldTreeChildEntry.getValue() instanceof String) {
                                    if (fieldTreeChildEntry.getKey() == "_ALIAS") continue
                                    String fieldName = fieldTreeChildEntry.getValue()
                                    if (candidateMap.get(fieldName) != ev.get(fieldName)) {
                                        allMatch = false
                                        break
                                    }
                                }
                            }
                            if (allMatch) {
                                relatedEntityDocMap = candidateMap
                                break
                            }
                        }
                    }

                    if (relatedEntityDocMap == null) {
                        // no matching Map? create a new one... and it will get populated in the recursive call
                        relatedEntityDocMap = [:]
                        // now time to recurse
                        populateDataDocRelatedMap(ev, relatedEntityDocMap, relatedEd, fieldTreeChild, relationshipAliasMap, recurseSetFields)
                        if (relatedEntityDocMap) {
                            if (relatedEntityDocList == null) {
                                relatedEntityDocList = []
                                parentDocMap.put(relDocumentAlias, relatedEntityDocList)
                            }
                            relatedEntityDocList.add(relatedEntityDocMap)
                        }
                    } else {
                        recurseSetFields = false
                        // now time to recurse
                        populateDataDocRelatedMap(ev, relatedEntityDocMap, relatedEd, fieldTreeChild, relationshipAliasMap, recurseSetFields)
                    }
                }
            } else {
                if (setFields) {
                    // set the field
                    String fieldName = fieldTreeEntry.getValue()
                    if (ev.get(fieldName) != null) parentDocMap.put(fieldName, ev.get(fieldName))
                }
            }
        }
    }

    protected void addDataDocRelatedEntity(EntityDynamicView dynamicView, String parentEntityAlias,
                                           Map fieldTreeCurrent, StupidUtilities.Incrementer incrementer) {
        for (Map.Entry fieldTreeEntry in fieldTreeCurrent.entrySet()) {
            if (fieldTreeEntry.getKey() instanceof String && fieldTreeEntry.getKey() == "_ALIAS") continue
            if (fieldTreeEntry.getValue() instanceof Map) {
                // add member entity, and entity alias in "_ALIAS" entry
                String entityAlias = "MEMBER${incrementer.getAndIncrement()}"
                Map fieldTreeChild = (Map) fieldTreeEntry.getValue()
                dynamicView.addRelationshipMember(entityAlias, parentEntityAlias, (String) fieldTreeEntry.getKey(), true)
                fieldTreeChild.put("_ALIAS", entityAlias)
                // now time to recurse
                addDataDocRelatedEntity(dynamicView, entityAlias, fieldTreeChild, incrementer)
            } else {
                // add alias for field
                String entityAlias = fieldTreeCurrent.get("_ALIAS")
                dynamicView.addAlias(entityAlias, (String) fieldTreeEntry.getValue(), (String) fieldTreeEntry.getKey(), null)
            }
        }
    }

    void checkCreateIndex(String indexName) {
        String baseIndexName = indexName.contains("__") ? indexName.substring(indexName.indexOf("__") + 2) : indexName

        Client client = efi.getEcfi().getElasticSearchClient()
        boolean hasIndex = client.admin().indices().exists(new IndicesExistsRequest(indexName)).actionGet().exists
        // logger.warn("========== Checking index ${indexName} (${baseIndexName}), hasIndex=${hasIndex}")
        if (hasIndex) return

        logger.info("Creating ElasticSearch index ${indexName} (${baseIndexName}) and adding document mappings")

        CreateIndexRequestBuilder cirb = client.admin().indices().prepareCreate(indexName)

        EntityList ddList = efi.find("moqui.entity.document.DataDocument").condition("indexName", baseIndexName).list()
        for (EntityValue dd in ddList) {
            Map docMapping = makeElasticSearchMapping((String) dd.dataDocumentId)
            cirb.addMapping((String) dd.dataDocumentId, docMapping)
            // logger.warn("========== Added mapping for ${dd.dataDocumentId} to index ${indexName}:\n${docMapping}")

        }
        cirb.execute().actionGet()
    }

    void putIndexMappings(String indexName) {
        String baseIndexName = indexName.contains("__") ? indexName.substring(indexName.indexOf("__") + 2) : indexName

        Client client = efi.getEcfi().getElasticSearchClient()
        boolean hasIndex = client.admin().indices().exists(new IndicesExistsRequest(indexName)).actionGet().isExists()
        if (!hasIndex) {
            client.admin().indices().prepareCreate(indexName).execute().actionGet()
        }

        EntityList ddList = efi.find("moqui.entity.document.DataDocument").condition("indexName", baseIndexName).list()
        for (EntityValue dd in ddList) {
            Map docMapping = makeElasticSearchMapping((String) dd.dataDocumentId)
            client.admin().indices().preparePutMapping(indexName).setType((String) dd.dataDocumentId)
                    .setSource(docMapping).execute().actionGet() // .setIgnoreConflicts(true) no longer supported?
        }
    }

    static final Map<String, String> esTypeMap = [id:'string', 'id-long':'string', date:'date', time:'string',
        'date-time':'date', 'number-integer':'long', 'number-decimal':'double', 'number-float':'double',
        'currency-amount':'double', 'currency-precise':'double', 'text-indicator':'string', 'text-short':'string',
        'text-medium':'string', 'text-long':'string', 'text-very-long':'string', 'binary-very-long':'binary']

    Map makeElasticSearchMapping(String dataDocumentId) {
        EntityValue dataDocument = efi.find("moqui.entity.document.DataDocument")
                .condition("dataDocumentId", dataDocumentId).useCache(true).one()
        if (dataDocument == null) throw new EntityException("No DataDocument found with ID [${dataDocumentId}]")
        EntityList dataDocumentFieldList = dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, null, true, false)
        EntityList dataDocumentRelAliasList = dataDocument.findRelated("moqui.entity.document.DataDocumentRelAlias", null, null, true, false)

        Map<String, String> relationshipAliasMap = [:]
        for (EntityValue dataDocumentRelAlias in dataDocumentRelAliasList)
            relationshipAliasMap.put((String) dataDocumentRelAlias.relationshipName, (String) dataDocumentRelAlias.documentAlias)

        String primaryEntityName = dataDocument.primaryEntityName
        // String primaryEntityAlias = relationshipAliasMap.get(primaryEntityName) ?: primaryEntityName
        EntityDefinition primaryEd = efi.getEntityDefinition(primaryEntityName)

        Map rootProperties = [_entity:[type:'string', index:'not_analyzed']]
        Map mappingMap = [properties:rootProperties]

        List<String> remainingPkFields = new ArrayList(primaryEd.getPkFieldNames())
        for (EntityValue dataDocumentField in dataDocumentFieldList) {
            String fieldPath = dataDocumentField.fieldPath
            if (!fieldPath.contains(':')) {
                // is a field on the primary entity, put it there
                String fieldName = dataDocumentField.fieldNameAlias ?: dataDocumentField.fieldPath
                EntityJavaUtil.FieldInfo fieldInfo = primaryEd.getFieldInfo((String) dataDocumentField.fieldPath)
                if (fieldInfo == null) throw new EntityException("Could not find field [${dataDocumentField.fieldPath}] for entity [${primaryEd.getFullEntityName()}] in DataDocument [${dataDocumentId}]")

                String fieldType = fieldInfo.type
                String mappingType = esTypeMap.get(fieldType) ?: 'string'
                Map propertyMap = [type:mappingType]
                if (fieldType.startsWith("id")) propertyMap.index = 'not_analyzed'
                rootProperties.put(fieldName, propertyMap)

                if (remainingPkFields.contains(dataDocumentField.fieldPath)) remainingPkFields.remove((String) dataDocumentField.fieldPath)
                continue
            }

            Iterator<String> fieldPathElementIter = fieldPath.split(":").iterator()
            Map currentProperties = rootProperties
            EntityDefinition currentEd = primaryEd
            while (fieldPathElementIter.hasNext()) {
                String fieldPathElement = fieldPathElementIter.next()
                if (fieldPathElementIter.hasNext()) {
                    EntityDefinition.RelationshipInfo relInfo = currentEd.getRelationshipInfo(fieldPathElement)
                    if (relInfo == null) throw new EntityException("Could not find relationship [${fieldPathElement}] for entity [${currentEd.getFullEntityName()}] in DataDocument [${dataDocumentId}]")
                    currentEd = relInfo.relatedEd
                    if (currentEd == null) throw new EntityException("Could not find entity [${relInfo.relatedEntityName}] in DataDocument [${dataDocumentId}]")

                    // only put type many in sub-objects, same as DataDocument generation
                    if (!relInfo.isTypeOne) {
                        String objectName = relationshipAliasMap.get(fieldPathElement) ?: fieldPathElement
                        Map subObject = (Map) currentProperties.get(objectName)
                        Map subProperties
                        if (subObject == null) {
                            subProperties = [:]
                            subObject = [properties:subProperties]
                            currentProperties.put(objectName, subObject)
                        } else {
                            subProperties = subObject.properties
                        }
                        currentProperties = subProperties
                    }
                } else {
                    String fieldName = dataDocumentField.fieldNameAlias ?: fieldPathElement
                    EntityJavaUtil.FieldInfo fieldInfo = currentEd.getFieldInfo(fieldPathElement)
                    if (fieldInfo == null) throw new EntityException("Could not find field [${fieldPathElement}] for entity [${currentEd.getFullEntityName()}] in DataDocument [${dataDocumentId}]")
                    String fieldType = fieldInfo.type
                    String mappingType = esTypeMap.get(fieldType) ?: 'string'
                    Map propertyMap = [type:mappingType]
                    if (fieldType.startsWith("id")) propertyMap.index = 'not_analyzed'
                    currentProperties.put(fieldName, propertyMap)
                }
            }
        }

        // now get all the PK fields not aliased explicitly
        for (String remainingPkName in remainingPkFields) {
            EntityJavaUtil.FieldInfo fieldInfo = primaryEd.getFieldInfo(remainingPkName)
            String fieldType = fieldInfo.type
            String mappingType = esTypeMap.get(fieldType) ?: 'string'
            Map propertyMap = [type:mappingType]
            if (fieldType.startsWith("id")) propertyMap.index = 'not_analyzed'
            rootProperties.put(remainingPkName, propertyMap)
        }

        return mappingMap
    }
}
