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
package org.moqui.impl.service.runner

import groovy.transform.CompileStatic
import org.moqui.BaseException
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.entity.EntityException
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityValueNotFoundException
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.EntityJavaUtil.RelationshipInfo
import org.moqui.impl.entity.EntityValueBase
import org.moqui.impl.entity.FieldInfo
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.service.ServiceRunner
import org.moqui.service.ServiceException
import org.moqui.util.ObjectUtilities
import org.moqui.util.StringUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

@CompileStatic
class EntityAutoServiceRunner implements ServiceRunner {
    protected final static Logger logger = LoggerFactory.getLogger(EntityAutoServiceRunner.class)

    final static Set<String> verbSet = new HashSet(['create', 'update', 'delete', 'store'])
    final static Set<String> otherFieldsToSkip = new HashSet(['ec', '_entity', 'authUsername', 'authPassword'])

    private ServiceFacadeImpl sfi = null
    private ExecutionContextFactoryImpl ecfi = null

    EntityAutoServiceRunner() {}

    @Override ServiceRunner init(ServiceFacadeImpl sfi) { this.sfi = sfi; ecfi = sfi.ecfi; return this }
    @Override void destroy() { }

    // TODO: add update-expire and delete-expire entity-auto service verbs for entities with from/thru dates
    // TODO: add find (using search input parameters) and find-one (using literal PK, or as many PK fields as are passed on) entity-auto verbs
    Map<String, Object> runService(ServiceDefinition sd, Map<String, Object> parameters) {
        // check the verb and noun
        if (sd.verb == null || !verbSet.contains(sd.verb))
            throw new ServiceException("In service ${sd.serviceName} the verb must be one of ${verbSet} for entity-auto type services.")
        if (sd.noun == null || sd.noun.isEmpty()) throw new ServiceException("In service ${sd.serviceName} you must specify a noun for entity-auto service calls")

        ExecutionContextImpl eci = ecfi.getEci()
        EntityDefinition ed = eci.entityFacade.getEntityDefinition(sd.noun)
        if (ed == null) throw new ServiceException("In service ${sd.serviceName} the specified noun ${sd.noun} is not a valid entity name")

        Map<String, Object> result = new HashMap()

        try {
            boolean allPksInOnly = true
            for (String pkFieldName in ed.getPkFieldNames()) {
                if (!sd.getInParameter(pkFieldName) || sd.getOutParameter(pkFieldName)) { allPksInOnly = false; break }
            }

            if ("create".equals(sd.verb)) {
                createEntity(eci, ed, parameters, result, sd.getOutParameterNames())
            } else if ("update".equals(sd.verb)) {
                /* <auto-attributes include="pk" mode="IN" optional="false"/> */
                if (!allPksInOnly) throw new ServiceException("In entity-auto type service ${sd.serviceName} with update noun, not all pk fields have the mode IN")
                updateEntity(eci, ed, parameters, result, sd.getOutParameterNames(), null)
            } else if ("delete".equals(sd.verb)) {
                /* <auto-attributes include="pk" mode="IN" optional="false"/> */
                if (!allPksInOnly) throw new ServiceException("In entity-auto type service ${sd.serviceName} with delete noun, not all pk fields have the mode IN")
                deleteEntity(eci, ed, parameters)
            } else if ("store".equals(sd.verb)) {
                storeEntity(eci, ed, parameters, result, sd.getOutParameterNames())
            } else if ("update-expire".equals(sd.verb)) {
                // TODO
            } else if ("delete-expire".equals(sd.verb)) {
                // TODO
            } else if ("find".equals(sd.verb)) {
                // TODO
            } else if ("find-one".equals(sd.verb)) {
                // TODO
            }
        } catch (BaseException e) {
            throw new ServiceException("Error doing entity-auto operation for entity [${ed.fullEntityName}] in service [${sd.serviceName}]", e)
        }

        return result
    }

    protected static void checkFromDate(EntityDefinition ed, Map<String, Object> parameters,
                              Map<String, Object> result, ExecutionContextFactoryImpl ecfi) {
        List<String> pkFieldNames = ed.getPkFieldNames()

        // always make fromDate optional, whether or not part of the pk; do this before the allPksIn check
        if (pkFieldNames.contains("fromDate") && parameters.get("fromDate") == null) {
            Timestamp fromDate = ecfi.getExecutionContext().getUser().getNowTimestamp()
            parameters.put("fromDate", fromDate)
            result.put("fromDate", fromDate)
            // logger.info("Set fromDate field to default [${parameters.fromDate}]")
        }
    }

    protected static boolean checkAllPkFields(EntityDefinition ed, Map<String, Object> parameters, Map<String, Object> tempResult,
                                    EntityValue newEntityValue, ArrayList<String> outParamNames) {
        FieldInfo[] pkFieldInfos = ed.entityInfo.pkFieldInfoArray

        // see if all PK fields were passed in
        boolean allPksIn = true
        int pkSize = pkFieldInfos.length
        ArrayList<String> missingPkFields = (ArrayList<String>) null
        for (int i = 0; i < pkSize; i++) {
            FieldInfo fieldInfo = (FieldInfo) pkFieldInfos[i]
            Object pkValue = parameters.get(fieldInfo.name)
            if (ObjectUtilities.isEmpty(pkValue) && (fieldInfo.defaultStr == null || fieldInfo.defaultStr.isEmpty())) {
                allPksIn = false
                if (missingPkFields == null) missingPkFields = new ArrayList<>()
                missingPkFields.add(fieldInfo.name)
            }
        }
        boolean isSinglePk = pkSize == 1
        boolean isDoublePk = pkSize == 2

        // logger.info("======= checkAllPkFields for ${ed.getEntityName()} allPksIn=${allPksIn}, isSinglePk=${isSinglePk}, isDoublePk=${isDoublePk}; parameters: ${parameters}")

        if (isSinglePk) {
            /* **** primary sequenced primary key **** */
            /* **** primary sequenced key with optional override passed in **** */
            FieldInfo singlePkField = pkFieldInfos[0]

            Object pkValue = parameters.get(singlePkField.name)
            if (!ObjectUtilities.isEmpty(pkValue)) {
                // convert from String if parameter type is String, PK field type may not be
                if (pkValue instanceof CharSequence) newEntityValue.setString(singlePkField.name, pkValue.toString())
                else newEntityValue.set(singlePkField.name, pkValue)
            } else {
                // if it has a default value don't sequence the PK
                if (singlePkField.defaultStr == null || singlePkField.defaultStr.isEmpty()) {
                    newEntityValue.setSequencedIdPrimary()
                    pkValue = newEntityValue.getNoCheckSimple(singlePkField.name)
                }
            }
            if (outParamNames == null || outParamNames.size() == 0 || outParamNames.contains(singlePkField.name))
                tempResult.put(singlePkField.name, pkValue)
        } else if (isDoublePk && !allPksIn) {
            /* **** secondary sequenced primary key **** */
            // don't do it this way, currently only supports second pk fields: String doublePkSecondaryName = parameters.get(pkFieldNames.get(0)) ? pkFieldNames.get(1) : pkFieldNames.get(0)
            FieldInfo doublePkSecondary = pkFieldInfos[1]
            newEntityValue.setFields(parameters, true, null, true)
            // if it has a default value don't sequence the PK
            if (doublePkSecondary.defaultStr == null || doublePkSecondary.defaultStr.isEmpty()) {
                newEntityValue.setSequencedIdSecondary()
                if (outParamNames == null || outParamNames.size() == 0 || outParamNames.contains(doublePkSecondary.name))
                    tempResult.put(doublePkSecondary.name, newEntityValue.getNoCheckSimple(doublePkSecondary.name))
            }
        } else if (allPksIn) {
            /* **** plain specified primary key **** */
            newEntityValue.setFields(parameters, true, null, true)
        } else {
            logger.error("Entity [${ed.fullEntityName}] auto create pk fields ${ed.getPkFieldNames()} incomplete: ${parameters}" +
                    "\nCould not find a valid combination of primary key settings to do a create operation; options include: " +
                    "1. a single entity primary-key field for primary auto-sequencing with or without matching in-parameter, and with or without matching out-parameter for the possibly sequenced value, " +
                    "2. a 2-part entity primary-key with one part passed in as an in-parameter (existing primary pk value) and with or without the other part defined as an out-parameter (the secodnary pk to sub-sequence), " +
                    "3. all entity pk fields are passed into the service")
            if (missingPkFields.size() == 1) {
                throw new ServiceException("Required field ${StringUtilities.camelCaseToPretty(missingPkFields.get(0))} is missing, cannot create ${StringUtilities.camelCaseToPretty(ed.entityName)}")
            } else {
                throw new ServiceException("Required fields ${missingPkFields.collect({ StringUtilities.camelCaseToPretty(it) }).join(', ')} are missing, cannot create ${StringUtilities.camelCaseToPretty(ed.entityName)}")
            }
        }

        // logger.info("In auto createEntity allPksIn [${allPksIn}] isSinglePk [${isSinglePk}] isDoublePk [${isDoublePk}] newEntityValue final [${newEntityValue}]")

        return allPksIn
    }

    static void createEntity(ExecutionContextImpl eci, EntityDefinition ed, Map<String, Object> parameters,
                             Map<String, Object> result, ArrayList<String> outParamNames) {
        createRecursive(eci.ecfi, eci.entityFacade, ed, parameters, result, outParamNames, null)
    }

    static void createRecursive(ExecutionContextFactoryImpl ecfi, EntityFacadeImpl efi, EntityDefinition ed, Map<String, Object> parameters,
                                Map<String, Object> result, ArrayList<String> outParamNames, Map<String, Object> parentPks) {
        EntityValue newEntityValue = ed.makeEntityValue()

        // add in all of the main entity's primary key fields, this is necessary for auto-generated, and to
        //     allow them to be left out of related records
        if (parentPks != null) {
            for (Map.Entry<String, Object> entry in parentPks.entrySet())
                if (!parameters.containsKey(entry.key)) parameters.put(entry.key, entry.value)
        }

        checkFromDate(ed, parameters, result, ecfi)

        Map<String, Object> tempResult = [:]
        checkAllPkFields(ed, parameters, tempResult, newEntityValue, outParamNames)

        newEntityValue.setFields(parameters, true, null, false)
        try {
            newEntityValue.create()
        } catch (Exception e) {
            if (e.getMessage().contains("primary key")) {
                long[] bank = (long[]) efi.entitySequenceBankCache.get(ed.getFullEntityName())
                EntityValue svi = efi.find("moqui.entity.SequenceValueItem").condition("seqName", ed.getFullEntityName())
                        .useCache(false).disableAuthz().one()
                logger.warn("Got PK violation, current bank is ${bank}, PK is ${newEntityValue.getPrimaryKeys()}, current SequenceValueItem: ${svi}")
            }
            throw e
        }

        // NOTE: keep a separate Map of parent PK values to pass down, can't just be current record's PK fields because
        //     we allow other entities to be nested, and they may have nested records that depend ANY ancestor's PKs
        // this returns a clone or new Map, so we'll modify it freely
        Map<String, Object> sharedPkMap = newEntityValue.getPrimaryKeys()
        if (parentPks != null) {
            for (Map.Entry<String, Object> entry in parentPks.entrySet())
                if (!sharedPkMap.containsKey(entry.key)) sharedPkMap.put(entry.key, entry.value)
        }

        // if a PK field has a @default get it and return it
        ArrayList<String> pkFieldNames = ed.getPkFieldNames()
        int size = pkFieldNames.size()
        for (int i = 0; i < size; i++) {
            String pkName = (String) pkFieldNames.get(i)
            FieldInfo pkInfo = ed.getFieldInfo(pkName)
            if (pkInfo.defaultStr != null && !pkInfo.defaultStr.isEmpty()) {
                tempResult.put(pkName, newEntityValue.getNoCheckSimple(pkName))
            }
        }

        // check parameters Map for relationships and other entities
        Map nonFieldEntries = ed.entityInfo.cloneMapRemoveFields(parameters, null)
        for (Map.Entry entry in nonFieldEntries.entrySet()) {
            Object relParmObj = entry.getValue()
            if (relParmObj == null) continue
            // if the entry is not a Map or List ignore it, we're only looking for those
            if (!(relParmObj instanceof Map) && !(relParmObj instanceof List)) continue

            String entryName = (String) entry.getKey()
            if (parentPks != null && parentPks.containsKey(entryName)) continue
            if (otherFieldsToSkip.contains(entryName)) continue

            EntityDefinition subEd = null
            Map<String, Object> pkMap = null
            RelationshipInfo relInfo = ed.getRelationshipInfo(entryName)
            if (relInfo != null) {
                if (!relInfo.mutable) {
                    if (logger.isTraceEnabled()) logger.trace("In create entity auto service found key [${entryName}] which is a non-mutable relationship of [${ed.getFullEntityName()}], skipping")
                    continue
                }
                subEd = relInfo.relatedEd
                // this is a relationship so add mapped key fields to the parentPks if any field names are different
                pkMap = new HashMap<>(sharedPkMap)
                pkMap.putAll(relInfo.getTargetParameterMap(sharedPkMap))
            } else if (efi.isEntityDefined(entryName)) {
                subEd = efi.getEntityDefinition(entryName)
                pkMap = sharedPkMap
            }
            if (subEd == null) {
                // this happens a lot, extra stuff passed to the service call, so be quiet unless trace is on
                if (logger.isTraceEnabled()) logger.trace("In create entity auto service found key [${entryName}] which is not a field or relationship of [${ed.getFullEntityName()}] and is not a defined entity")
                continue
            }

            boolean isEntityValue = relParmObj instanceof EntityValue
            if (relParmObj instanceof Map && !isEntityValue) {
                Map relResults = [:]
                createRecursive(ecfi, efi, subEd, (Map) relParmObj, relResults, null, pkMap)
                tempResult.put(entryName, relResults)
            } else if (relParmObj instanceof List) {
                List relResultList = []
                for (Object relParmEntry in relParmObj) {
                    Map relResults = [:]
                    if (relParmEntry instanceof Map) {
                        createRecursive(ecfi, efi, subEd, (Map) relParmEntry, relResults, null, pkMap)
                    } else {
                        logger.warn("In entity auto create for entity ${ed.getFullEntityName()} found list for sub-object ${entryName} with a non-Map entry: ${relParmEntry}")
                    }
                    relResultList.add(relResults)
                }
                tempResult.put(entryName, relResultList)
            } else {
                if (isEntityValue) {
                    if (logger.isTraceEnabled()) logger.trace("In entity auto create for entity ${ed.getFullEntityName()} found sub-object ${entryName} which is not a Map or List: ${relParmObj}")
                } else {
                    logger.warn("In entity auto create for entity ${ed.getFullEntityName()} found sub-object ${entryName} which is not a Map or List: ${relParmObj}")
                }
            }
        }

        result.putAll(tempResult)
    }

    /** Does a create if record does not exist, or update if it does. */
    static void storeEntity(ExecutionContextImpl eci, EntityDefinition ed, Map<String, Object> parameters,
                                   Map<String, Object> result, ArrayList<String> outParamNames) {
        storeRecursive(eci.ecfi, eci.getEntityFacade(), ed, parameters, result, outParamNames, null)
    }

    static void storeRecursive(ExecutionContextFactoryImpl ecfi, EntityFacadeImpl efi, EntityDefinition ed, Map<String, Object> parameters,
                               Map<String, Object> result, ArrayList<String> outParamNames, Map<String, Object> parentPks) {
        EntityValue newEntityValue = efi.makeValue(ed.getFullEntityName())

        // add in all of the main entity's primary key fields, this is necessary for auto-generated, and to
        //     allow them to be left out of related records
        if (parentPks != null) {
            for (Map.Entry<String, Object> entry in parentPks.entrySet())
                if (!parameters.containsKey(entry.key)) parameters.put(entry.key, entry.value)
        }

        checkFromDate(ed, parameters, result, ecfi)

        Map<String, Object> tempResult = [:]
        boolean allPksIn = checkAllPkFields(ed, parameters, tempResult, newEntityValue, outParamNames)
        result.putAll(tempResult)

        if (!allPksIn) {
            // we had to fill some stuff in, so do a create
            newEntityValue.setFields(parameters, true, null, false)
            newEntityValue.create()
            storeRelated(ecfi, efi, (EntityValueBase) newEntityValue, parameters, result, parentPks)
            return
        }

        EntityValue lookedUpValue = null
        if (parameters.containsKey("statusId") && ed.isField("statusId")) {
            // do the actual query so we'll have the current statusId
            lookedUpValue = efi.find(ed.fullEntityName)
                    .condition(newEntityValue).useCache(false).one()
            if (lookedUpValue != null) {
                checkStatus(ed, parameters, result, outParamNames, lookedUpValue, efi)
            } else {
                // no lookedUpValue at this point? doesn't exist so create
                newEntityValue.setFields(parameters, true, null, false)
                newEntityValue.create()
                storeRelated(ecfi, efi, (EntityValueBase) newEntityValue, parameters, result, parentPks)
                return
            }
        }

        if (lookedUpValue == null) lookedUpValue = newEntityValue
        lookedUpValue.setFields(parameters, true, null, false)
        // logger.info("In auto updateEntity lookedUpValue final [${lookedUpValue}] for parameters [${parameters}]")
        lookedUpValue.createOrUpdate()

        storeRelated(ecfi, efi, (EntityValueBase) lookedUpValue, parameters, result, parentPks)
    }

    static void storeRelated(ExecutionContextFactoryImpl ecfi, EntityFacadeImpl efi, EntityValueBase parentValue,
                             Map<String, Object> parameters, Map<String, Object> result, Map<String, Object> parentPks) {
        EntityDefinition ed = parentValue.getEntityDefinition()

        // NOTE: keep a separate Map of parent PK values to pass down, can't just be current record's PK fields because
        //     we allow other entities to be nested, and they may have nested records that depend ANY ancestor's PKs
        // this returns a clone or new Map, so we'll modify it freely
        Map<String, Object> sharedPkMap = parentValue.getPrimaryKeys()
        if (parentPks != null) {
            for (Map.Entry<String, Object> entry in parentPks.entrySet())
                if (!sharedPkMap.containsKey(entry.key)) sharedPkMap.put(entry.key, entry.value)
        }

        Map nonFieldEntries = ed.entityInfo.cloneMapRemoveFields(parameters, null)
        if (nonFieldEntries.size() > 0) for (Map.Entry entry in nonFieldEntries.entrySet()) {
            Object relParmObj = entry.getValue()
            if (relParmObj == null) continue
            // if the entry is not a Map or List ignore it, we're only looking for those
            if (!(relParmObj instanceof Map) && !(relParmObj instanceof List)) continue

            String entryName = (String) entry.getKey()
            if (parentPks != null && parentPks.containsKey(entryName)) continue
            if (otherFieldsToSkip.contains(entryName)) continue

            EntityDefinition subEd = null
            Map<String, Object> pkMap = null
            RelationshipInfo relInfo = ed.getRelationshipInfo(entryName)
            if (relInfo != null) {
                if (!relInfo.mutable) {
                    if (logger.isTraceEnabled()) logger.trace("In store entity auto service found key [${entryName}] which is a non-mutable relationship of [${ed.getFullEntityName()}], skipping")
                    continue
                }
                subEd = relInfo.relatedEd

                // this is a relationship so add mapped key fields to the parentPks if any field names are different
                pkMap = new HashMap<>(sharedPkMap)
                pkMap.putAll(relInfo.getTargetParameterMap(sharedPkMap))
            } else if (efi.isEntityDefined(entryName)) {
                subEd = efi.getEntityDefinition(entryName)
                pkMap = sharedPkMap
            }
            if (subEd == null) {
                // this happens a lot, extra stuff passed to the service call, so be quiet unless trace is on
                if (logger.isTraceEnabled()) logger.trace("In store entity auto service found key [${entryName}] which is not a field or relationship of [${ed.getFullEntityName()}] and is not a defined entity")
                continue
            }

            boolean isEntityValue = relParmObj instanceof EntityValue
            if (relParmObj instanceof Map && !isEntityValue) {
                Map relResults = [:]
                storeRecursive(ecfi, efi, subEd, (Map) relParmObj, relResults, null, pkMap)
                result.put(entryName, relResults)
            } else if (relParmObj instanceof List) {
                List relResultList = []
                for (Object relParmEntry in relParmObj) {
                    Map relResults = [:]
                    if (relParmEntry instanceof Map) {
                        storeRecursive(ecfi, efi, subEd, (Map) relParmEntry, relResults, null, pkMap)
                    } else {
                        logger.warn("In entity auto create for entity ${ed.getFullEntityName()} found list for sub-object ${entryName} with a non-Map entry: ${relParmEntry}")
                    }
                    relResultList.add(relResults)
                }
                result.put(entryName, relResultList)
            } else {
                if (isEntityValue) {
                    if (logger.isTraceEnabled()) logger.trace("In entity auto store for entity ${ed.getFullEntityName()} found sub-object ${entryName} which is not a Map or List: ${relParmObj}")
                } else {
                    logger.warn("In entity auto store for entity ${ed.getFullEntityName()} found sub-object ${entryName} which is not a Map or List: ${relParmObj}")
                }
            }
        }
    }

    /* This should only be called if statusId is a field of the entity and lookedUpValue != null */
    protected static void checkStatus(EntityDefinition ed, Map<String, Object> parameters, Map<String, Object> result,
                                      ArrayList<String> outParamNames, EntityValue lookedUpValue, EntityFacadeImpl efi) {
        if (!parameters.containsKey("statusId")) return

        // populate the oldStatusId out if there is a service parameter for it, and before we do the set non-pk fields
        if (outParamNames == null || outParamNames.size() == 0 || outParamNames.contains("oldStatusId")) {
            result.put("oldStatusId", lookedUpValue.getNoCheckSimple("statusId"))
        }
        if (outParamNames == null || outParamNames.size() == 0 || outParamNames.contains("statusChanged")) {
            result.put("statusChanged", !(lookedUpValue.getNoCheckSimple("statusId") == parameters.get("statusId")))
            // logger.warn("========= oldStatusId=${result.oldStatusId}, statusChanged=${result.statusChanged}, lookedUpValue.statusId=${lookedUpValue.statusId}, parameters.statusId=${parameters.statusId}, lookedUpValue=${lookedUpValue}")
        }

        // do the StatusValidChange check
        String parameterStatusId = (String) parameters.get("statusId")
        if (parameterStatusId) {
            String lookedUpStatusId = (String) lookedUpValue.getNoCheckSimple("statusId")
            if (lookedUpStatusId && !parameterStatusId.equals(lookedUpStatusId)) {
                // there was an old status, and in this call we are trying to change it, so do the StatusFlowTransition check
                // NOTE that we are using a cached list from a common pattern so it should generally be there instead of a count that wouldn't
                EntityList statusFlowTransitionList = efi.find("moqui.basic.StatusFlowTransition")
                        .condition("statusId", lookedUpStatusId).condition("toStatusId", parameterStatusId).useCache(true).list()
                if (statusFlowTransitionList.size() == 0) {
                    // uh-oh, no valid change...
                    ExecutionContext eci = efi.ecfi.getEci()
                    EntityValue lookedUpStatus = efi.find("moqui.basic.StatusItem")
                            .condition("statusId", lookedUpStatusId).useCache(true).one()
                    EntityValue parameterStatus = efi.find("moqui.basic.StatusItem")
                            .condition("statusId", parameterStatusId).useCache(true).one()
                    logger.warn("Status transition not allowed from ${lookedUpStatusId} to ${parameterStatusId} on entity ${ed.fullEntityName} with PK ${lookedUpValue.getPrimaryKeys()}")
                    throw new ServiceException(eci.resource.expand('StatusFlowTransitionNotFoundTemplate', "",
                            [fullEntityName:eci.l10n.localize(ed.fullEntityName + '##EntityName'),
                                lookedUpStatusId:lookedUpStatusId, parameterStatusId:parameterStatusId,
                                lookedUpStatusName:lookedUpStatus?.getNoCheckSimple("description"),
                                parameterStatusName:parameterStatus?.getNoCheckSimple("description")]))
                }
            }
        }

        // NOTE: nothing here to maintain the status history, that should be done with a custom service called by SECA rule or with audit log on field
    }

    static void updateEntity(ExecutionContextImpl eci, EntityDefinition ed, Map<String, Object> parameters,
                             Map<String, Object> result, ArrayList<String> outParamNames, EntityValue preLookedUpValue) {
        ExecutionContextFactoryImpl ecfi = eci.ecfi
        EntityFacadeImpl efi = eci.getEntityFacade()

        EntityValue lookedUpValue = preLookedUpValue ?: efi.makeValue(ed.getFullEntityName()).setFields(parameters, true, null, true)
        // this is much slower, and we don't need to do the query: sfi.getEcfi().getEntityFacade().find(ed.entityName).condition(parameters).useCache(false).one()
        if (lookedUpValue == null) throw new EntityValueNotFoundException("In entity-auto update service for entity [${ed.fullEntityName}] value not found, cannot update; using parameters [${parameters}]")

        if (parameters.containsKey("statusId") && ed.isField("statusId")) {
            // do the actual query so we'll have the current statusId
            Map<String, Object> pkParms = ed.getPrimaryKeys(parameters)
            lookedUpValue = preLookedUpValue ?: efi.find(ed.getFullEntityName()).condition(pkParms).useCache(false).one()
            if (lookedUpValue == null) throw new EntityValueNotFoundException("In entity-auto update service for entity [${ed.fullEntityName}] value not found, cannot update; using parameters [${parameters}]")

            checkStatus(ed, parameters, result, outParamNames, lookedUpValue, efi)
        }

        lookedUpValue.setFields(parameters, true, null, false)
        // logger.info("In auto updateEntity lookedUpValue final [${((EntityValueBase) lookedUpValue).getValueMap()}] for parameters [${parameters}]")
        lookedUpValue.update()

        storeRelated(ecfi, efi, (EntityValueBase) lookedUpValue, parameters, result, null)
    }

    static void deleteEntity(ExecutionContextImpl eci, EntityDefinition ed, Map<String, Object> parameters) {
        if (!ed.containsPrimaryKey(parameters)) throw new EntityException("Must specify all primary key fields to delete, can use wildcard of '*' in one or more PK fields to delete multiple records")

        Map<String, Object> newParms = new HashMap<>(parameters)
        boolean hasWildcard = false
        ArrayList<String> fieldNameList = ed.getPkFieldNames()
        int size = fieldNameList.size()
        for (int i = 0; i < size; i++) {
            String fieldName = (String) fieldNameList.get(i)
            if ("*".equals(newParms.get(fieldName))) {
                hasWildcard = true
                newParms.remove(fieldName)
                break
            }
        }
        if (hasWildcard) {
            // long deleted =
            eci.entityFacade.find(ed.fullEntityName).condition(newParms).deleteAll()
            // logger.info("Deleted ${deleted} ${ed.fullEntityName} records with PK wildcard: ${parameters}")
        } else {
            EntityValue ev = eci.entityFacade.makeValue(ed.fullEntityName).setFields(parameters, true, null, true)
            ev.delete()
        }
    }
}
