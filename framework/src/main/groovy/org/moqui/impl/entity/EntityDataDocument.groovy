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
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityDynamicView
import org.moqui.entity.EntityException
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityList
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.condition.ConditionAlias
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.impl.entity.condition.FieldValueCondition
import org.moqui.util.CollectionUtilities
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.util.concurrent.atomic.AtomicInteger

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
            efi.ecfi.getEci().message.addError(efi.ecfi.resource.expand('File ${filename} already exists.','',[filename:filename]))
            return 0
        }

        PrintWriter pw = new PrintWriter(outFile)

        pw.write("[\n")
        int valuesWritten = writeDocumentsToWriter(pw, dataDocumentIds, condition, fromUpdateStamp, thruUpdatedStamp, prettyPrint)
        pw.write("{}\n]\n")
        pw.close()
        efi.ecfi.getEci().message.addMessage(efi.ecfi.resource.expand('Wrote ${valuesWritten} documents to file ${filename}','',[valuesWritten:valuesWritten,filename:filename]))
        return valuesWritten
    }

    int writeDocumentsToDirectory(String dirname, List<String> dataDocumentIds, EntityCondition condition,
                                  Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp, boolean prettyPrint) {
        File outDir = new File(dirname)
        if (!outDir.exists()) outDir.mkdir()
        if (!outDir.isDirectory()) {
            efi.ecfi.getEci().message.addError(efi.ecfi.resource.expand('Path ${dirname} is not a directory.','',[dirname:dirname]))
            return 0
        }

        int valuesWritten = 0

        for (String dataDocumentId in dataDocumentIds) {
            String filename = "${dirname}/${dataDocumentId}.json"
            File outFile = new File(filename)
            if (outFile.exists()) {
                efi.ecfi.getEci().message.addError(efi.ecfi.resource.expand('File ${filename} already exists, skipping document ${dataDocumentId}.','',[filename:filename,dataDocumentId:dataDocumentId]))
                continue
            }
            outFile.createNewFile()

            PrintWriter pw = new PrintWriter(outFile)
            pw.write("[\n")
            valuesWritten += writeDocumentsToWriter(pw, [dataDocumentId], condition, fromUpdateStamp, thruUpdatedStamp, prettyPrint)
            pw.write("{}\n]\n")
            pw.close()
            efi.ecfi.getEci().message.addMessage(efi.ecfi.resource.expand('Wrote ${valuesWritten} records to file ${filename}','',[valuesWritten:valuesWritten, filename:filename]))
        }

        return valuesWritten
    }

    int writeDocumentsToWriter(Writer pw, List<String> dataDocumentIds, EntityCondition condition,
                               Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp, boolean prettyPrint) {
        if (dataDocumentIds == null || dataDocumentIds.size() == 0) return 0
        int valuesWritten = 0
        for (String dataDocumentId in dataDocumentIds) {
            ArrayList<Map> documentList = getDataDocuments(dataDocumentId, condition, fromUpdateStamp, thruUpdatedStamp)
            int docListSize = documentList.size()
            for (int i = 0; i < docListSize; i++) {
                if (valuesWritten > 0) pw.write(",\n")
                Map document = (Map) documentList.get(i)
                String json = JsonOutput.toJson(document)
                if (prettyPrint) {
                    pw.write(JsonOutput.prettyPrint(json))
                } else {
                    pw.write(json)
                }
                valuesWritten++
            }
        }
        if (valuesWritten > 0) pw.write("\n")

        return valuesWritten
    }

    EntityDefinition makeEntityDefinition(String dataDocumentId) {
        EntityValue dataDocument = efi.fastFindOne("moqui.entity.document.DataDocument", true, false, dataDocumentId)
        if (dataDocument == null) throw new EntityException("No DataDocument found with ID ${dataDocumentId}")
        EntityList dataDocumentFieldList = dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, ['sequenceNum', 'fieldPath'], true, false)

        String primaryEntityName = dataDocument.getNoCheckSimple("primaryEntityName")
        EntityDefinition primaryEd = efi.getEntityDefinition(primaryEntityName)
        List<String> primaryPkFieldNames = primaryEd.getPkFieldNames()

        Map<String, Object> fieldTree = [:]
        Map<String, String> fieldAliasPathMap = [:]
        populateFieldTreeAndAliasPathMap(dataDocumentFieldList, primaryPkFieldNames, fieldTree, fieldAliasPathMap, false)

        EntityDynamicViewImpl dynamicView = new EntityDynamicViewImpl(efi)
        dynamicView.entityNode.attributes.put("package", "DataDocument")
        dynamicView.entityNode.attributes.put("entity-name", dataDocumentId)

        // add member entities and field aliases to dynamic view
        dynamicView.addMemberEntity("PRIM_ENT", primaryEntityName, null, null, null)
        AtomicInteger incrementer = new AtomicInteger()
        fieldTree.put("_ALIAS", "PRIM_ENT")
        addDataDocRelatedEntity(dynamicView, "PRIM_ENT", fieldTree, incrementer)

        EntityDefinition ed = dynamicView.makeEntityDefinition()
        // logger.warn("Returning DataDoc ent def ${ed.entityNode}")
        return ed
    }

    EntityFind makeDataDocumentFind(String dataDocumentId) {
        EntityValue dataDocument = efi.fastFindOne("moqui.entity.document.DataDocument", true, false, dataDocumentId)
        if (dataDocument == null) throw new EntityException("No DataDocument found with ID ${dataDocumentId}")
        EntityList dataDocumentFieldList = dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, ['sequenceNum', 'fieldPath'], true, false)
        EntityList dataDocumentConditionList = dataDocument.findRelated("moqui.entity.document.DataDocumentCondition", null, null, true, false)

        String primaryEntityName = dataDocument.getNoCheckSimple("primaryEntityName")
        EntityDefinition primaryEd = efi.getEntityDefinition(primaryEntityName)
        List<String> primaryPkFieldNames = primaryEd.getPkFieldNames()

        // build the field tree, nested Maps for relationship field path elements and field alias String for field name path elements
        Map<String, Object> fieldTree = [:]
        Map<String, String> fieldAliasPathMap = [:]
        populateFieldTreeAndAliasPathMap(dataDocumentFieldList, primaryPkFieldNames, fieldTree, fieldAliasPathMap, false)

        return makeDataDocumentFind(dataDocumentId, primaryEntityName, fieldTree, fieldAliasPathMap, dataDocumentConditionList, null, null)
    }

    EntityFind makeDataDocumentFind(String dataDocumentId, String primaryEntityName, Map<String, Object> fieldTree,
            Map<String, String> fieldAliasPathMap, EntityList dataDocumentConditionList, Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp) {
        // build the query condition for the primary entity and all related entities
        EntityFind mainFind = efi.find(primaryEntityName)
        EntityDynamicViewImpl dynamicView = (EntityDynamicViewImpl) mainFind.makeEntityDynamicView()
        dynamicView.entityNode.attributes.put("package", "DataDocument")
        dynamicView.entityNode.attributes.put("entity-name", dataDocumentId)

        // add member entities and field aliases to dynamic view
        dynamicView.addMemberEntity("PRIM_ENT", primaryEntityName, null, null, null)
        AtomicInteger incrementer = new AtomicInteger()
        fieldTree.put("_ALIAS", "PRIM_ENT")
        addDataDocRelatedEntity(dynamicView, "PRIM_ENT", fieldTree, incrementer)

        // logger.warn("=========== ${dataDocumentId} ViewEntityNode=${((EntityDynamicViewImpl) dynamicView).getViewEntityNode()}")

        // add conditions
        for (EntityValue dataDocumentCondition in dataDocumentConditionList) {
            if (!fieldAliasPathMap.containsKey(dataDocumentCondition.getNoCheckSimple("fieldNameAlias")))
                throw new EntityException("Found DataDocumentCondition with fieldNameAlias [${dataDocumentCondition.fieldNameAlias}] that is not aliased in DataDocument [${dataDocumentId}]")
            if (dataDocumentCondition.getNoCheckSimple("postQuery") != "Y") {
                mainFind.condition((String) dataDocumentCondition.getNoCheckSimple("fieldNameAlias"),
                        (String) dataDocumentCondition.getNoCheckSimple("operator") ?: 'equals', dataDocumentCondition.getNoCheckSimple("fieldValue"))
            }
        }

        // create a condition with an OR list of date range comparisons to check that at least one member-entity has lastUpdatedStamp in range
        if ((Object) fromUpdateStamp != null || (Object) thruUpdatedStamp != null) {
            List<EntityCondition> dateRangeOrCondList = []
            for (MNode memberEntityNode in dynamicView.getMemberEntityNodes()) {
                ConditionField ludCf = new ConditionAlias(memberEntityNode.attribute("entity-alias"),
                        "lastUpdatedStamp", efi.getEntityDefinition(memberEntityNode.attribute("entity-name")))
                List<EntityCondition> dateRangeFieldCondList = []
                if ((Object) fromUpdateStamp != null) {
                    dateRangeFieldCondList.add(efi.getConditionFactory().makeCondition(
                            new FieldValueCondition(ludCf, EntityCondition.EQUALS, null),
                            EntityCondition.OR,
                            new FieldValueCondition(ludCf, EntityCondition.GREATER_THAN_EQUAL_TO, fromUpdateStamp)))
                }
                if ((Object) thruUpdatedStamp != null) {
                    dateRangeFieldCondList.add(efi.getConditionFactory().makeCondition(
                            new FieldValueCondition(ludCf, EntityCondition.EQUALS, null),
                            EntityCondition.OR,
                            new FieldValueCondition(ludCf, EntityCondition.LESS_THAN, thruUpdatedStamp)))
                }
                dateRangeOrCondList.add(efi.getConditionFactory().makeCondition(dateRangeFieldCondList, EntityCondition.AND))
            }
            mainFind.condition(efi.getConditionFactory().makeCondition(dateRangeOrCondList, EntityCondition.OR))
        }

        // logger.warn("=========== DataDocument query condition for ${dataDocumentId} mainFind.condition=${((EntityFindImpl) mainFind).getWhereEntityCondition()}")
        return mainFind
    }

    ArrayList<Map> getDataDocuments(String dataDocumentId, EntityCondition condition, Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp) {
        ExecutionContextImpl eci = efi.ecfi.getEci()

        EntityValue dataDocument = efi.fastFindOne("moqui.entity.document.DataDocument", true, false, dataDocumentId)
        if (dataDocument == null) throw new EntityException("No DataDocument found with ID ${dataDocumentId}")
        EntityList dataDocumentFieldList = dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, null, true, false)
        EntityList dataDocumentRelAliasList = dataDocument.findRelated("moqui.entity.document.DataDocumentRelAlias", null, null, true, false)
        EntityList dataDocumentConditionList = dataDocument.findRelated("moqui.entity.document.DataDocumentCondition", null, null, true, false)

        String primaryEntityName = dataDocument.getNoCheckSimple("primaryEntityName")
        EntityDefinition primaryEd = efi.getEntityDefinition(primaryEntityName)
        List<String> primaryPkFieldNames = primaryEd.getPkFieldNames()

        // build the field tree, nested Maps for relationship field path elements and field alias String for field name path elements
        Map<String, Object> fieldTree = [:]
        Map<String, String> fieldAliasPathMap = [:]
        populateFieldTreeAndAliasPathMap(dataDocumentFieldList, primaryPkFieldNames, fieldTree, fieldAliasPathMap, false)
        // logger.warn("=========== ${dataDocumentId} fieldTree=${fieldTree}")
        // logger.warn("=========== ${dataDocumentId} fieldAliasPathMap=${fieldAliasPathMap}")

        // make the relationship alias Map
        Map relationshipAliasMap = [:]
        for (EntityValue dataDocumentRelAlias in dataDocumentRelAliasList)
            relationshipAliasMap.put(dataDocumentRelAlias.getNoCheckSimple("relationshipName"), dataDocumentRelAlias.getNoCheckSimple("documentAlias"))

        EntityFind mainFind = makeDataDocumentFind(dataDocumentId, primaryEntityName, fieldTree, fieldAliasPathMap,
                dataDocumentConditionList, fromUpdateStamp, thruUpdatedStamp)
        if (condition != null) mainFind.condition(condition)

        boolean hasAllPrimaryPks = true
        for (String pkFieldName in primaryPkFieldNames) if (!fieldAliasPathMap.containsKey(pkFieldName)) hasAllPrimaryPks = false

        // do the one big query
        EntityListIterator mainEli = mainFind.iterator()
        Map<String, Map<String, Object>> documentMapMap = hasAllPrimaryPks ? new LinkedHashMap<>() : null
        ArrayList<Map<String, Object>> documentMapList = hasAllPrimaryPks ? null : new ArrayList<>()
        try {
            EntityValue ev
            while ((ev = (EntityValue) mainEli.next()) != null) {
                // logger.warn("=========== DataDocument query result for ${dataDocumentId}: ${ev}")

                StringBuffer pkCombinedSb = new StringBuffer()
                for (String pkFieldName in primaryPkFieldNames) {
                    if (!fieldAliasPathMap.containsKey(pkFieldName)) continue
                    if (pkCombinedSb.length() > 0) pkCombinedSb.append("::")
                    pkCombinedSb.append((String) ev.getNoCheckSimple(pkFieldName))
                }
                String docId = pkCombinedSb.toString()

                /*
                  - _index = DataDocument.indexName
                  - _type = dataDocumentId
                  - _id = pk field values from primary entity, double colon separated
                  - _timestamp = document created time
                  - Map for primary entity with primaryEntityName as key
                  - nested List of Maps for each related entity with aliased fields with relationship name as key
                 */
                Map<String, Object> docMap = hasAllPrimaryPks ? documentMapMap.get(docId) : null
                if (docMap == null) {
                    // add special entries
                    docMap = new LinkedHashMap<>()
                    docMap.put("_type", dataDocumentId)
                    if (docId) docMap.put("_id", docId)
                    docMap.put('_timestamp', eci.l10nFacade.format(
                            thruUpdatedStamp ?: new Timestamp(System.currentTimeMillis()), "yyyy-MM-dd'T'HH:mm:ssZ"))
                    String _index = dataDocument.indexName
                    if (_index) docMap.put('_index', _index.toLowerCase())
                    docMap.put('_entity', primaryEd.getShortOrFullEntityName())

                    // add Map for primary entity
                    Map primaryEntityMap = [:]
                    for (Map.Entry fieldTreeEntry in fieldTree.entrySet()) {
                        Object entryValue = fieldTreeEntry.getValue()
                        if (entryValue instanceof String) {
                            if (fieldTreeEntry.getKey() == "_ALIAS") continue
                            String fieldName = (String) entryValue
                            Object value = ev.get(fieldName)
                            if (value) primaryEntityMap.put(fieldName, value)
                        }
                    }
                    // docMap.put((String) relationshipAliasMap.get(primaryEntityName) ?: primaryEntityName, primaryEntityMap)
                    docMap.putAll(primaryEntityMap)

                    if (hasAllPrimaryPks) documentMapMap.put(docId, docMap)
                    else documentMapList.add(docMap)
                }

                // recursively add Map or List of Maps for each related entity
                populateDataDocRelatedMap(ev, docMap, primaryEd, fieldTree, relationshipAliasMap, false)
            }
        } finally {
            mainEli.close()
        }

        // make the actual list and return it
        if (hasAllPrimaryPks) {
            documentMapList = new ArrayList<>(documentMapMap.size())
            documentMapList.addAll(documentMapMap.values())
        }
        for (int i = 0; i < documentMapList.size(); ) {
            Map<String, Object> docMap = (Map<String, Object>) documentMapList.get(i)
            // call the manualDataServiceName service for each document
            if (dataDocument.getNoCheckSimple("manualDataServiceName")) {
                Map result = efi.ecfi.serviceFacade.sync().name((String) dataDocument.getNoCheckSimple("manualDataServiceName"))
                        .parameters([dataDocumentId:dataDocumentId, document:docMap]).call()
                if (result.document) {
                    docMap = (Map<String, Object>) result.document
                    documentMapList.set(i, docMap)
                }
            }

            // check postQuery conditions
            boolean allPassed = true
            for (EntityValue dataDocumentCondition in dataDocumentConditionList) if (dataDocumentCondition.postQuery == "Y") {
                Set<Object> valueSet = new HashSet<Object>()
                CollectionUtilities.findAllFieldsNestedMap((String) dataDocumentCondition.getNoCheckSimple("fieldNameAlias"), docMap, valueSet)
                if (valueSet.size() == 0) {
                    if (!dataDocumentCondition.getNoCheckSimple("fieldValue")) { continue }
                    else { allPassed = false; break }
                }
                if (!dataDocumentCondition.getNoCheckSimple("fieldValue")) { allPassed = false; break }
                Object fieldValueObj = dataDocumentCondition.getNoCheckSimple("fieldValue").asType(valueSet.first().class)
                if (!(fieldValueObj in valueSet)) { allPassed = false; break }
            }

            if (allPassed) {
                i++
            } else {
                documentMapList.remove(i)
            }
        }

        return documentMapList
    }

    static void populateFieldTreeAndAliasPathMap(EntityList dataDocumentFieldList, List<String> primaryPkFieldNames,
                                          Map<String, Object> fieldTree, Map<String, String> fieldAliasPathMap, boolean allPks) {
        for (EntityValue dataDocumentField in dataDocumentFieldList) {
            String fieldPath = dataDocumentField.getNoCheckSimple("fieldPath")
            Iterator<String> fieldPathElementIter = fieldPath.split(":").iterator()
            Map currentTree = fieldTree
            while (fieldPathElementIter.hasNext()) {
                String fieldPathElement = fieldPathElementIter.next()
                if (fieldPathElementIter.hasNext()) {
                    Map subTree = (Map) currentTree.get(fieldPathElement)
                    if (subTree == null) { subTree = [:]; currentTree.put(fieldPathElement, subTree) }
                    currentTree = subTree
                } else {
                    String fieldName = dataDocumentField.getNoCheckSimple("fieldNameAlias") ?: fieldPathElement
                    String function = dataDocumentField.getNoCheckSimple("functionName")
                    if (function != null && !function.isEmpty() && !fieldPathElement.contains("#"))
                        fieldPathElement = fieldPathElement + '#' + dataDocumentField.getNoCheckSimple("functionName")
                    currentTree.put(fieldPathElement, fieldName)
                    fieldAliasPathMap.put(fieldName, fieldPath)
                }
            }
        }
        // make sure all PK fields of the primary entity are aliased
        if (allPks) {
            for (String pkFieldName in primaryPkFieldNames) if (!fieldAliasPathMap.containsKey(pkFieldName)) {
                fieldTree.put(pkFieldName, pkFieldName)
                fieldAliasPathMap.put(pkFieldName, pkFieldName)
            }
        }
    }

    protected void populateDataDocRelatedMap(EntityValue ev, Map<String, Object> parentDocMap, EntityDefinition parentEd,
                                             Map fieldTreeCurrent, Map relationshipAliasMap, boolean setFields) {
        for (Map.Entry fieldTreeEntry in fieldTreeCurrent.entrySet()) {
            if ("_ALIAS".equals(fieldTreeEntry.getKey())) continue
            if (fieldTreeEntry.getValue() instanceof Map) {
                String relationshipName = fieldTreeEntry.getKey()
                Map fieldTreeChild = (Map) fieldTreeEntry.getValue()

                EntityJavaUtil.RelationshipInfo relationshipInfo = parentEd.getRelationshipInfo(relationshipName)
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
                                    if ("_ALIAS".equals(fieldTreeChildEntry.getKey())) continue
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
                                           Map fieldTreeCurrent, AtomicInteger incrementer) {
        for (Map.Entry fieldTreeEntry in fieldTreeCurrent.entrySet()) {
            if ("_ALIAS".equals(fieldTreeEntry.getKey())) continue
            Object entryValue = fieldTreeEntry.getValue()
            if (entryValue instanceof Map) {
                Map fieldTreeChild = (Map) entryValue
                // add member entity, and entity alias in "_ALIAS" entry
                String entityAlias = "MEMBER" + incrementer.getAndIncrement()
                dynamicView.addRelationshipMember(entityAlias, parentEntityAlias, (String) fieldTreeEntry.getKey(), true)
                fieldTreeChild.put("_ALIAS", entityAlias)
                // now time to recurse
                addDataDocRelatedEntity(dynamicView, entityAlias, fieldTreeChild, incrementer)
            } else {
                // add alias for field
                String entityAlias = fieldTreeCurrent.get("_ALIAS")
                String fieldName = (String) fieldTreeEntry.getKey()
                String function = null
                int hashIdx = fieldName.indexOf("#")
                if (hashIdx > 0) {
                    function = fieldName.substring(hashIdx+1)
                    fieldName = fieldName.substring(0, hashIdx)
                }
                dynamicView.addAlias(entityAlias, (String) entryValue, fieldName, function)
            }
        }
    }
}
