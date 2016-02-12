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
package org.moqui.impl.context

import groovy.transform.CompileStatic
import org.moqui.util.MNode

import java.sql.Timestamp

import org.moqui.BaseException
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactExecutionFacade
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ArtifactTarpitException
import org.moqui.context.Cache
import org.moqui.entity.EntityList
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityCondition.ComparisonOperator
import org.moqui.entity.EntityCondition.JoinOperator
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.EntityFindBase
import org.moqui.impl.entity.EntityValueBase
import org.moqui.entity.EntityCondition

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
public class ArtifactExecutionFacadeImpl implements ArtifactExecutionFacade {
    protected final static Logger logger = LoggerFactory.getLogger(ArtifactExecutionFacadeImpl.class)

    // NOTE: these need to be in a Map instead of the DB because Enumeration records may not yet be loaded
    final static Map<String, String> artifactTypeDescriptionMap = [AT_XML_SCREEN:"Screen",
            AT_XML_SCREEN_TRANS:"Transition", AT_SERVICE:"Service", AT_ENTITY:"Entity"]
    final static Map<String, String> artifactActionDescriptionMap = [AUTHZA_VIEW:"View",
            AUTHZA_CREATE:"Create", AUTHZA_UPDATE:"Update", AUTHZA_DELETE:"Delete", AUTHZA_ALL:"All"]

    protected ExecutionContextImpl eci
    protected Deque<ArtifactExecutionInfoImpl> artifactExecutionInfoStack = new LinkedList<ArtifactExecutionInfoImpl>()
    protected List<ArtifactExecutionInfoImpl> artifactExecutionInfoHistory = new LinkedList<ArtifactExecutionInfoImpl>()

    // NOTE: there is no code to clean out old entries in tarpitHitCache, using the cache idle expire time for that
    protected Cache tarpitHitCache
    protected EntityCondition nameIsPatternEqualsY
    // this is used by ScreenUrlInfo.isPermitted() which is called a lot, but that is transient so put here to have one per EC instance
    protected Map<String, Boolean> screenPermittedCache = null

    protected boolean authzDisabled = false
    protected boolean entityEcaDisabled = false

    ArtifactExecutionFacadeImpl(ExecutionContextImpl eci) {
        this.eci = eci
        this.tarpitHitCache = eci.cache.getCache("artifact.tarpit.hits")
        nameIsPatternEqualsY = eci.getEntity().getConditionFactory().makeCondition("nameIsPattern", ComparisonOperator.EQUALS, "Y")
    }

    Map<String, Boolean> getScreenPermittedCache() {
        if (screenPermittedCache == null) screenPermittedCache = new HashMap<>()
        return screenPermittedCache
    }

    @Override
    ArtifactExecutionInfo peek() { return this.artifactExecutionInfoStack.peekFirst() }

    @Override
    void push(String name, String typeEnumId, String actionEnumId, boolean requiresAuthz) {
        push(new ArtifactExecutionInfoImpl(name, typeEnumId, actionEnumId), requiresAuthz)
    }
    @Override
    void push(ArtifactExecutionInfo aei, boolean requiresAuthz) {
        ArtifactExecutionInfoImpl aeii = (ArtifactExecutionInfoImpl) aei
        // do permission check for this new aei that current user is trying to access
        String username = eci.getUser().getUsername()

        ArtifactExecutionInfoImpl lastAeii = artifactExecutionInfoStack.peekFirst()

        // always do this regardless of the authz checks, etc; keep a history of artifacts run
        if (lastAeii != null) { lastAeii.addChild(aeii); aeii.setParent(lastAeii) }
        else artifactExecutionInfoHistory.add(aeii)

        // if ("AT_XML_SCREEN" == aeii.typeEnumId) logger.warn("TOREMOVE artifact push ${username} - ${aeii}")

        if (!isPermitted(username, aeii, lastAeii, requiresAuthz, true, eci.getUser().getNowTimestamp())) {
            StringBuilder warning = new StringBuilder()
            warning.append("User [${username}] is not authorized for ${artifactActionDescriptionMap.get(aeii.getActionEnumId())} on ${artifactTypeDescriptionMap.get(aeii.getTypeEnumId())?:aeii.getTypeEnumId()} [${aeii.getName()}], here is the current artifact stack:")
            for (def warnAei in this.stack) warning.append("\n").append(warnAei)

            Exception e = new ArtifactAuthorizationException(warning.toString())
            logger.warn("Artifact authorization failed", e)
            throw e
        }

        // NOTE: if needed the isPermitted method will set additional info in aeii
        this.artifactExecutionInfoStack.addFirst(aeii)
    }

    @Override
    ArtifactExecutionInfo pop() { return pop(null) }

    @Override
    ArtifactExecutionInfo pop(ArtifactExecutionInfo aei) {
        if (this.artifactExecutionInfoStack.size() > 0) {
            ArtifactExecutionInfoImpl lastAeii = artifactExecutionInfoStack.removeFirst()
            // removed this for performance reasons, generally just checking the name is adequate
            // || aei.typeEnumId != lastAeii.typeEnumId || aei.actionEnumId != lastAeii.actionEnumId
            if (aei != null && (aei.name != lastAeii.name)) {
                String popMessage = "Popped artifact (${aei.name}:${aei.typeEnumId}:${aei.actionEnumId}) did not match top of stack (${lastAeii.name}:${lastAeii.typeEnumId}:${lastAeii.actionEnumId}:${lastAeii.actionDetail})"
                logger.warn(popMessage, new BaseException("Pop Error Location"))
                //throw new IllegalArgumentException(popMessage)
            }
            lastAeii.setEndTime()
            return lastAeii
        } else {
            logger.warn("Tried to pop from an empty ArtifactExecutionInfo stack", new Exception("Bad pop location"))
            return null
        }
    }

    @Override
    Deque<ArtifactExecutionInfo> getStack() { return this.artifactExecutionInfoStack }
    String getStackNameString() {
        StringBuilder sb = new StringBuilder()
        Iterator i = this.artifactExecutionInfoStack.iterator()
        while (i.hasNext()) {
            ArtifactExecutionInfo aei = i.next()
            sb.append(aei.name)
            if (i.hasNext()) sb.append(',')
        }
        return sb.toString()
    }
    @Override
    List<ArtifactExecutionInfo> getHistory() { return this.artifactExecutionInfoHistory }

    String printHistory() {
        StringWriter sw = new StringWriter()
        for (ArtifactExecutionInfo aei in artifactExecutionInfoHistory) aei.print(sw, 0, true)
        return sw.toString()
    }

    void logProfilingDetail() {
        if (!logger.isInfoEnabled()) return

        StringWriter sw = new StringWriter()
        sw.append("========= Hot Spots by Own Time =========\n")
        sw.append("[{time}:{timeMin}:{timeAvg}:{timeMax}][{count}] {type} {action} {actionDetail} {name}\n")
        List<Map> ownHotSpotList = ArtifactExecutionInfoImpl.hotSpotByTime(artifactExecutionInfoHistory, true, "-time")
        ArtifactExecutionInfoImpl.printHotSpotList(sw, ownHotSpotList)
        logger.info(sw.toString())

        sw = new StringWriter()
        sw.append("========= Hot Spots by Total Time =========\n")
        sw.append("[{time}:{timeMin}:{timeAvg}:{timeMax}][{count}] {type} {action} {actionDetail} {name}\n")
        List<Map> totalHotSpotList = ArtifactExecutionInfoImpl.hotSpotByTime(artifactExecutionInfoHistory, false, "-time")
        ArtifactExecutionInfoImpl.printHotSpotList(sw, totalHotSpotList)
        logger.info(sw.toString())

        /* leave this out by default, sometimes interesting, but big
        sw = new StringWriter()
        sw.append("========= Consolidated Artifact List =========\n")
        sw.append("[{time}:{thisTime}:{childrenTime}][{count}] {type} {action} {actionDetail} {name}\n")
        List<Map> consolidatedList = ArtifactExecutionInfoImpl.consolidateArtifactInfo(artifactExecutionInfoHistory)
        ArtifactExecutionInfoImpl.printArtifactInfoList(sw, consolidatedList, 0)
        logger.info(sw.toString())
        */
    }


    void setAnonymousAuthorizedAll() {
        ArtifactExecutionInfoImpl aeii = artifactExecutionInfoStack.peekFirst()
        aeii.authorizationInheritable = true
        aeii.authorizedUserId = eci.getUser().getUserId() ?: "_NA_"
        if (aeii.authorizedAuthzTypeId != "AUTHZT_ALWAYS") aeii.authorizedAuthzTypeId = "AUTHZT_ALLOW"
        aeii.authorizedActionEnumId = "AUTHZA_ALL"
    }

    void setAnonymousAuthorizedView() {
        ArtifactExecutionInfoImpl aeii = artifactExecutionInfoStack.peekFirst()
        aeii.authorizationInheritable = true
        aeii.authorizedUserId = eci.getUser().getUserId() ?: "_NA_"
        if (aeii.authorizedAuthzTypeId != "AUTHZT_ALWAYS") aeii.authorizedAuthzTypeId = "AUTHZT_ALLOW"
        if (aeii.authorizedActionEnumId != "AUTHZA_ALL") aeii.authorizedActionEnumId = "AUTHZA_VIEW"
    }

    boolean disableAuthz() { boolean alreadyDisabled = this.authzDisabled; this.authzDisabled = true; return alreadyDisabled }
    void enableAuthz() { this.authzDisabled = false }
    boolean getAuthzDisabled() { return authzDisabled }

    boolean disableEntityEca() { boolean alreadyDisabled = this.entityEcaDisabled; this.entityEcaDisabled = true; return alreadyDisabled }
    void enableEntityEca() { this.entityEcaDisabled = false }
    boolean entityEcaDisabled() { return this.entityEcaDisabled }

    /** Checks to see if username is permitted to access given resource.
     *
     * @param username
     * @param resourceAccess Formatted as: "${typeEnumId}:${actionEnumId}:${name}"
     * @param nowTimestamp
     * @param eci
     * @return
     */
    static boolean isPermitted(String username, String resourceAccess, Timestamp nowTimestamp, ExecutionContextImpl eci) {
        int firstColon = resourceAccess.indexOf(":")
        int secondColon = resourceAccess.indexOf(":", firstColon + 1)
        if (firstColon == -1 || secondColon == -1) throw new ArtifactAuthorizationException("Resource access string does not have two colons (':'), must be formatted like: \"\${typeEnumId}:\${actionEnumId}:\${name}\"")

        String typeEnumId = resourceAccess.substring(0, firstColon)
        String actionEnumId = resourceAccess.substring(firstColon + 1, secondColon)
        String name = resourceAccess.substring(secondColon + 1)

        return eci.artifactExecutionFacade.isPermitted(username,
                new ArtifactExecutionInfoImpl(name, typeEnumId, actionEnumId), null, true, true, nowTimestamp)
    }

    boolean isPermitted(String userId, ArtifactExecutionInfoImpl aeii, ArtifactExecutionInfoImpl lastAeii,
                        boolean requiresAuthz, boolean countTarpit, Timestamp nowTimestamp) {

        // never do this for entities when disableAuthz, as we might use any below and would cause infinite recursion
        // for performance reasons if this is an entity and no authz required don't bother looking at tarpit, checking for deny/etc
        if ((!requiresAuthz || this.authzDisabled) && aeii.getTypeEnumId() == 'AT_ENTITY') {
            if (lastAeii != null && lastAeii.authorizationInheritable) aeii.copyAuthorizedInfo(lastAeii)
            return true
        }

        // if ("AT_XML_SCREEN" == aeii.typeEnumId) logger.warn("TOREMOVE artifact isPermitted after authzDisabled ${aeii}")

        ExecutionContextFactoryImpl ecfi = eci.ecfi
        EntityFacadeImpl efi = ecfi.getEntityFacade(eci.tenantId)
        UserFacadeImpl ufi = eci.getUserFacade()

        // see if there is a UserAccount for the username, and if so get its userId as a more permanent identifier
        String curUserId = ufi.getUserId()
        if (curUserId != null) userId = curUserId

        boolean alreadyDisabled = disableAuthz()
        try {
            if (countTarpit && ecfi.isTarpitEnabled(aeii.getTypeEnumId())) {
                // record and check velocity limit (tarpit)
                boolean recordHitTime = false
                long lockForSeconds = 0
                long checkTime = System.currentTimeMillis()
                ArrayList<EntityValue> artifactTarpitCheckList = null
                // only check screens if they are the final screen in the chain (the target screen)
                if (aeii.getTypeEnumId() != 'AT_XML_SCREEN' || requiresAuthz) {
                    EntityList fullList = ufi.getArtifactTarpitCheckList()
                    if (fullList) {
                        artifactTarpitCheckList = new ArrayList()
                        for (int i = 0; i < fullList.size(); i++) {
                            EntityValue atEv = fullList.get(i)
                            if (atEv.get('artifactTypeEnumId') == aeii.getTypeEnumId())
                                artifactTarpitCheckList.add(atEv)
                        }
                    }
                }
                // if (aeii.getTypeEnumId() == "AT_XML_SCREEN")
                //     logger.warn("TOREMOVE about to check tarpit [${tarpitKey}], userGroupIdSet=${userGroupIdSet}, artifactTarpitList=${artifactTarpitList}")
                if (artifactTarpitCheckList != null && artifactTarpitCheckList.size() > 0) {
                    String tarpitKey = userId + '@' + aeii.getTypeEnumId() + ':' + aeii.getName()
                    List<Long> hitTimeList = null
                    for (int i = 0; i < artifactTarpitCheckList.size(); i++) {
                        EntityValue artifactTarpit = artifactTarpitCheckList.get(i)
                        if (('Y'.equals(artifactTarpit.get('nameIsPattern')) &&
                                aeii.getName().matches((String) artifactTarpit.get('artifactName'))) ||
                                aeii.getName().equals(artifactTarpit.get('artifactName'))) {
                            recordHitTime = true
                            if (hitTimeList == null) hitTimeList = (List<Long>) tarpitHitCache.get(tarpitKey)
                            long maxHitsDuration = artifactTarpit.getLong('maxHitsDuration')
                            // count hits in this duration; start with 1 to count the current hit
                            long hitsInDuration = 1
                            if (hitTimeList) {
                                // copy the list to avoid a ConcurrentModificationException
                                // NOTE: a better approach to concurrency that won't ever miss hits would be better
                                ArrayList<Long> hitTimeListCopy = new ArrayList(hitTimeList)
                                for (int htlInd = 0; htlInd < hitTimeListCopy.size(); htlInd++) {
                                    Long hitTime = hitTimeListCopy.get(htlInd)
                                    if ((hitTime - checkTime) < maxHitsDuration) hitsInDuration++
                                }
                            }
                            // logger.warn("TOREMOVE artifact [${tarpitKey}], now has ${hitsInDuration} hits in ${maxHitsDuration} seconds")
                            if (hitsInDuration > artifactTarpit.getLong('maxHitsCount') && artifactTarpit.getLong('tarpitDuration') > lockForSeconds) {
                                lockForSeconds = artifactTarpit.getLong('tarpitDuration')
                                logger.warn("User [${userId}] exceeded ${artifactTarpit.maxHitsCount} in ${maxHitsDuration} seconds for artifact [${tarpitKey}], locking for ${lockForSeconds} seconds")
                            }
                        }
                    }
                    if (recordHitTime) {
                        if (hitTimeList == null) { hitTimeList = new LinkedList<Long>(); tarpitHitCache.put(tarpitKey, hitTimeList) }
                        hitTimeList.add(System.currentTimeMillis())
                        // logger.warn("TOREMOVE recorded hit time for [${tarpitKey}], now has ${hitTimeList.size()} hits")

                        // check the ArtifactTarpitLock for the current artifact attempt before seeing if there is a new lock to create
                        // NOTE: this only runs if we are recording a hit time for an artifact, so no performance impact otherwise
                        EntityList tarpitLockList = efi.find('moqui.security.ArtifactTarpitLock')
                                .condition([userId:userId, artifactName:aeii.getName(), artifactTypeEnumId:aeii.getTypeEnumId()] as Map<String, Object>)
                                .useCache(true).list()
                                .filterByCondition(efi.getConditionFactory().makeCondition('releaseDateTime', ComparisonOperator.GREATER_THAN, ufi.getNowTimestamp()), true)
                        if (tarpitLockList) {
                            Timestamp releaseDateTime = tarpitLockList.first.getTimestamp('releaseDateTime')
                            int retryAfterSeconds = ((releaseDateTime.getTime() - System.currentTimeMillis())/1000).intValue()
                            throw new ArtifactTarpitException("User [${userId}] has accessed ${artifactTypeDescriptionMap.get(aeii.getTypeEnumId())?:aeii.getTypeEnumId()} [${aeii.getName()}] too many times and may not again until ${releaseDateTime} (retry after ${retryAfterSeconds} seconds)".toString(), retryAfterSeconds)
                        }
                    }
                    // record the tarpit lock
                    if (lockForSeconds > 0) {
                        eci.getService().sync().name('create', 'moqui.security.ArtifactTarpitLock').parameters(
                                [userId:userId, artifactName:aeii.getName(), artifactTypeEnumId:aeii.getTypeEnumId(),
                                 releaseDateTime:(new Timestamp(checkTime + ((lockForSeconds as BigDecimal) * 1000).intValue()))]).call()
                        tarpitHitCache.remove(tarpitKey)
                    }
                }
            }
        } finally {
            if (!alreadyDisabled) enableAuthz()
        }

        // tarpit enabled already checked, if authz not enabled return true immediately
        if (!ecfi.isAuthzEnabled(aeii.getTypeEnumId())) {
            // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO"))
            //     logger.warn("TOREMOVE artifact isPermitted authz disabled - ${aeii}")
            return true
        }

        // if last was an always allow, then don't bother checking for deny/etc
        if (lastAeii != null && lastAeii.isAuthorizationInheritable() && lastAeii.getAuthorizedUserId() == userId &&
                'AUTHZT_ALWAYS'.equals(lastAeii.getAuthorizedAuthzTypeId()) &&
                ('AUTHZA_ALL'.equals(lastAeii.getAuthorizedActionEnumId()) || aeii.getActionEnumId().equals(lastAeii.getAuthorizedActionEnumId()))) {
            aeii.copyAuthorizedInfo(lastAeii)
            // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO"))
            //     logger.warn("TOREMOVE artifact isPermitted already authorized for user ${userId} - ${aeii}")
            return true
        }

        EntityValue denyAacv = null

        // don't check authz for these queries, would cause infinite recursion
        alreadyDisabled = disableAuthz()
        try {
            // don't make a big condition for the DB to filter the list, or EntityList.filterByCondition from bigger
            //     cached list, both are slower than manual iterate and check fields explicitly
            ArrayList<EntityValue> aacvList = new ArrayList()
            EntityList origAacvList = ufi.getArtifactAuthzCheckList()
            int origAacvListSize = origAacvList.size()
            for (int i = 0; i < origAacvListSize; i++) {
                EntityValue aacv = origAacvList.get(i)
                Map aacvMap = ((EntityValueBase) aacv).getValueMap()
                String curAuthzActionEnumId = aacvMap.get('authzActionEnumId')
                if (aacvMap.get('artifactTypeEnumId') == aeii.getTypeEnumId() &&
                        (curAuthzActionEnumId == 'AUTHZA_ALL' ||  curAuthzActionEnumId == aeii.getActionEnumId()) &&
                        (aacvMap.get('nameIsPattern') == 'Y' || aacvMap.get('artifactName') == aeii.getName())) {
                    aacvList.add(aacv)
                }
            }

            // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO"))
            //     logger.warn("TOREMOVE for aeii [${aeii}] artifact isPermitted aacvList: ${aacvList}; aacvCond: ${aacvCond}")

            int aacvListSize = aacvList.size()
            for (int i = 0; i < aacvListSize; i++) {
                EntityValue aacv = aacvList.get(i)

                // check the name
                if ('Y'.equals(aacv.get('nameIsPattern')) && !aeii.getName().matches(aacv.getString('artifactName')))
                    continue
                // check the filterMap
                if (aacv.get('filterMap') && aeii.parameters) {
                    Map<String, Object> filterMapObj = (Map<String, Object>) eci.getResource().expression(aacv.getString('filterMap'), null)
                    boolean allMatches = true
                    for (Map.Entry<String, Object> filterEntry in filterMapObj.entrySet()) {
                        if (filterEntry.getValue() != aeii.parameters.get(filterEntry.getKey())) allMatches = false
                    }
                    if (!allMatches) continue
                }
                // check the record-level permission
                if (aacv.get('viewEntityName')) {
                    EntityValue artifactAuthzRecord = efi.find('moqui.security.ArtifactAuthzRecord')
                            .condition('artifactAuthzId', aacv.get('artifactAuthzId')).useCache(true).one()
                    EntityDefinition ed = efi.getEntityDefinition((String) aacv.get('viewEntityName'))
                    EntityFind ef = efi.find((String) aacv.get('viewEntityName'))

                    // add condition for the userId field
                    if (artifactAuthzRecord.userIdField) {
                        ef.condition((String) artifactAuthzRecord.get('userIdField'), userId)
                    } else if (ed.isField('userId')) {
                        ef.condition('userId', userId)
                    }
                    // filter by date if configured to do so
                    if (artifactAuthzRecord.filterByDate == 'Y') {
                        ef.conditionDate((String) artifactAuthzRecord.get('filterByDateFromField'),
                                (String) artifactAuthzRecord.get('filterByDateThruField'), nowTimestamp)
                    }

                    // add explicit conditions
                    EntityList condList = efi.find('moqui.security.ArtifactAuthzRecordCond')
                            .condition('artifactAuthzId', aacv.get('artifactAuthzId')).useCache(true).list()
                    if (condList.size() > 0) {
                        if (aeii.parameters) eci.context.push(aeii.parameters)
                        try {
                            for (EntityValue cond in condList) {
                                String expCondValue = eci.resource.expand((String) cond.get('condValue'),
                                        "moqui.security.ArtifactAuthzRecordCond.${cond.artifactAuthzId}.${cond.artifactAuthzCondSeqId}")
                                if (expCondValue) {
                                    ef.condition((String) cond.fieldName,
                                            efi.conditionFactory.comparisonOperatorFromEnumId((String) cond.operatorEnumId),
                                            expCondValue)
                                }
                            }
                        } finally {
                            if (aeii.parameters) eci.context.pop()
                        }
                    }

                    // add condition for each main entity PK field in the parameters
                    if (aeii.getTypeEnumId() == 'AT_ENTITY') {
                        EntityDefinition mainEd = efi.getEntityDefinition(aeii.getName())
                        ArrayList<String> pkFieldNames = mainEd.getPkFieldNames()
                        for (int j = 0; j < pkFieldNames.size(); j++) {
                            String pkFieldName = pkFieldNames[j]
                            if (!ed.isField(pkFieldName)) continue
                            Object pkParmValue = aeii.parameters.get(pkFieldName)
                            if (pkParmValue != null) ef.condition(pkFieldName, pkParmValue)
                        }
                    }

                    // do the count query; anything found? if not it fails this condition, so skip the authz
                    if (ef.useCache(true).count() == 0) continue
                }

                String authzTypeEnumId = aacv.get('authzTypeEnumId')
                if (aacv.get('authzServiceName')) {
                    Map result = eci.getService().sync().name(aacv.getString('authzServiceName'))
                            .parameters([userId:userId, authzActionEnumId:aeii.getActionEnumId(),
                            artifactTypeEnumId:aeii.getTypeEnumId(), artifactName:aeii.getName()]).call()
                    if (result?.authzTypeEnumId) authzTypeEnumId = result.authzTypeEnumId
                }

                // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO"))
                //     logger.warn("TOREMOVE found authz record for aeii [${aeii}]: ${aacv}")
                if (authzTypeEnumId == 'AUTHZT_DENY') {
                    // we already know last was not always allow (checked above), so keep going in loop just in case
                    // we find an always allow in the query
                    denyAacv = aacv
                } else if (authzTypeEnumId == 'AUTHZT_ALWAYS') {
                    aeii.copyAacvInfo(aacv, userId)
                    // if ("AT_XML_SCREEN" == aeii.typeEnumId)
                    //     logger.warn("TOREMOVE artifact isPermitted found always allow for user ${userId} - ${aeii}")
                    return true
                } else if (authzTypeEnumId == 'AUTHZT_ALLOW' && denyAacv == null) {
                    // see if there are any denies in AEIs on lower on the stack
                    boolean ancestorDeny = false
                    for (ArtifactExecutionInfoImpl ancestorAeii in artifactExecutionInfoStack)
                        if (ancestorAeii.getAuthorizedAuthzTypeId() == 'AUTHZT_DENY') ancestorDeny = true

                    if (!ancestorDeny) {
                        aeii.copyAacvInfo(aacv, userId)
                        // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO"))
                        //     logger.warn("TOREMOVE artifact isPermitted allow with no deny for user ${userId} - ${aeii}")
                        return true
                    }
                }
            }
        } finally {
            if (!alreadyDisabled) enableAuthz()
        }

        if (denyAacv != null) {
            // record that this was an explicit deny (for push or exception in case something catches and handles it)
            aeii.copyAacvInfo(denyAacv, userId)

            if (!requiresAuthz || this.authzDisabled) {
                // if no authz required, just return true even though it was a failure
                // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO"))
                //     logger.warn("TOREMOVE artifact isPermitted (in deny) doesn't require authz or authzDisabled for user ${userId} - ${aeii}")
                return true
            } else {
                StringBuilder warning = new StringBuilder()
                warning.append("User [${userId}] is not authorized for ${aeii.getTypeEnumId()} [${aeii.getName()}] because of a deny record [type:${aeii.getTypeEnumId()},action:${aeii.getActionEnumId()}], here is the current artifact stack:")
                for (def warnAei in this.stack) warning.append("\n").append(warnAei)
                logger.warn(warning.toString())

                alreadyDisabled = disableAuthz()
                try {
                    eci.getService().sync().name("create", "moqui.security.ArtifactAuthzFailure").parameters(
                            [artifactName:aeii.getName(), artifactTypeEnumId:aeii.getTypeEnumId(),
                            authzActionEnumId:aeii.getActionEnumId(), userId:userId,
                            failureDate:new Timestamp(System.currentTimeMillis()), isDeny:"Y"]).call()
                } finally {
                    if (!alreadyDisabled) enableAuthz()
                }

                return false
            }
        } else {
            // no perms found for this, only allow if the current AEI has inheritable auth and same user, and (ALL action or same action)

            // NOTE: this condition allows any user to be authenticated and allow inheritance if the last artifact was
            //       logged in anonymously (ie userId="_NA_"); consider alternate approaches; an alternate approach is
            //       in place when no user is logged in, but when one is this is the only solution so far
            if (lastAeii != null && lastAeii.authorizationInheritable &&
                    (lastAeii.authorizedUserId == "_NA_" || lastAeii.authorizedUserId == userId) &&
                    (lastAeii.authorizedActionEnumId == "AUTHZA_ALL" || lastAeii.authorizedActionEnumId == aeii.getActionEnumId()) &&
                    !"AUTHZT_DENY".equals(lastAeii.getAuthorizedAuthzTypeId())) {
                aeii.copyAuthorizedInfo(lastAeii)
                // if ("AT_XML_SCREEN" == aeii.typeEnumId)
                //     logger.warn("TOREMOVE artifact isPermitted inheritable and same user and ALL or same action for user ${userId} - ${aeii}")
                return true
            }
        }

        if (!requiresAuthz || this.authzDisabled) {
            // if no authz required, just push it even though it was a failure
            if (lastAeii != null && lastAeii.authorizationInheritable) aeii.copyAuthorizedInfo(lastAeii)
            // if ("AT_XML_SCREEN" == aeii.typeEnumId)
            //     logger.warn("TOREMOVE artifact isPermitted doesn't require authz or authzDisabled for user ${userId} - ${aeii}")
            return true
        } else {
            // if we got here no authz found, log it
            if (logger.isDebugEnabled()) {
                StringBuilder warning = new StringBuilder()
                warning.append("User [${userId}] is not authorized for ${aeii.getTypeEnumId()} [${aeii.getName()}] because of no allow record [type:${aeii.getTypeEnumId()},action:${aeii.getActionEnumId()}]\nlastAeii=[${lastAeii}]\nHere is the artifact stack:")
                for (def warnAei in this.stack) warning.append("\n").append(warnAei)
                logger.debug(warning.toString())
            }

            alreadyDisabled = disableAuthz()
            try {
                // NOTE: this is called sync because failures should be rare and not as performance sensitive, and
                //    because this is still in a disableAuthz block (if async a service would have to be written for that)
                eci.service.sync().name("create", "moqui.security.ArtifactAuthzFailure").parameters(
                        [artifactName:aeii.getName(), artifactTypeEnumId:aeii.getTypeEnumId(),
                         authzActionEnumId:aeii.getActionEnumId(), userId:userId,
                         failureDate:new Timestamp(System.currentTimeMillis()), isDeny:"N"]).call()
            } finally {
                if (!alreadyDisabled) enableAuthz()
            }

            return false
        }

        // if ("AT_XML_SCREEN" == aeii.typeEnumId) logger.warn("TOREMOVE artifact isPermitted got to end for user ${userId} - ${aeii}")
        // return true
    }

    boolean filterFindForUser(EntityFindBase efb) {
        // do nothing if authz disabled
        if (authzDisabled) return false

        // NOTE: look for filters in all unique aacv in stack? shouldn't be needed, most recent auth is the valid one
        ArtifactExecutionInfoImpl lastAeii = artifactExecutionInfoStack.peekFirst()
        EntityValue aacv = lastAeii.aacv
        if (aacv == null) return false

        EntityDefinition findEd = efb.getEntityDef()
        String findEntityName = findEd.getFullEntityName()
        // skip all Moqui Framework entities;  note that this skips moqui.example too...
        if (findEntityName.startsWith("moqui.")) return false

        // find applicable EntityFilter records
        EntityList artifactAuthzFilterList = eci.entity.find("moqui.security.ArtifactAuthzFilter")
                .condition("artifactAuthzId", aacv.artifactAuthzId).disableAuthz().useCache(true).list()

        if (artifactAuthzFilterList == null) return false
        int authzFilterSize = artifactAuthzFilterList.size()
        if (authzFilterSize == 0) return false

        // for evaluating filter Maps add user context to ec.context
        eci.context.push(eci.user.context)

        boolean addedFilter = false
        try {
            for (int i = 0; i < authzFilterSize; i++) {
                EntityValue artifactAuthzFilter = artifactAuthzFilterList.get(i)
                EntityList entityFilterList = eci.entity.find("moqui.security.EntityFilter")
                        .condition("entityFilterSetId", artifactAuthzFilter.entityFilterSetId)
                        .disableAuthz().useCache(true).list()

                if (entityFilterList == null) continue
                int entFilterSize = entityFilterList.size()
                if (entFilterSize == 0) continue

                for (int j = 0; j < entFilterSize; j++) {
                    EntityValue entityFilter = entityFilterList.get(j)
                    String filterEntityName = entityFilter.getString("entityName")

                    // see if there if any filter entities match the current entity or if it is a view then a member entity
                    Map<String, ArrayList<MNode>> memberFieldAliases = null
                    if (filterEntityName != findEd.fullEntityName) {
                        if (findEd.isViewEntity()) {
                            memberFieldAliases = findEd.getMemberFieldAliases(filterEntityName)
                            if (memberFieldAliases == null) continue
                        } else {
                            continue
                        }
                    }

                    Object filterMapObjEval = eci.getResource().expression(entityFilter.getString('filterMap'), null)
                    Map<String, Object> filterMapObj
                    if (filterMapObjEval instanceof Map<String, Object>) {
                        filterMapObj = filterMapObjEval as Map<String, Object>
                    } else {
                        logger.error("EntityFiler filterMap did not evaluate to a Map<String, Object>: ${entityFilter.getString('filterMap')}")
                        continue
                    }
                    // logger.info("===== ${findEntityName} filterMapObj: ${filterMapObj}")

                    ComparisonOperator compOp = entityFilter.comparisonEnumId ? eci.entity.conditionFactory
                            .comparisonOperatorFromEnumId((String) entityFilter.comparisonEnumId) : null
                    JoinOperator joinOp = entityFilter.joinOr == "Y" ? EntityCondition.OR : EntityCondition.AND

                    // use makeCondition(Map) instead of breaking down here
                    try {
                        EntityCondition entCond = eci.ecfi.getEntityFacade(eci.tenantId).conditionFactoryImpl
                                .makeCondition(filterMapObj, compOp, joinOp, findEd, memberFieldAliases, true)
                        if (entCond == null) continue

                        // add the condition to the find
                        // NOTE: just create a list cond and add it, EntityFindBase will put it in simpleAndMap or otherwise optimize it
                        addedFilter = true
                        efb.condition(entCond)

                        // TODO: once more tested change this to trace
                        logger.info("Query on ${findEntityName} added authz filter conditions: ${entCond}")
                        // logger.info("Query on ${findEntityName} find: ${efb.toString()}")
                    } catch (Exception e) {
                        logger.warn("Error adding authz entity filter condition: ${e.toString()}")
                    }
                }
            }
        } finally {
            eci.context.pop()
        }

        return addedFilter
    }
}
