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
import org.moqui.impl.entity.EntityConditionFactoryImpl
import org.moqui.impl.entity.condition.EntityConditionImplBase

import java.sql.Timestamp

import org.moqui.BaseException
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactExecutionFacade
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ArtifactTarpitException
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityCondition.ComparisonOperator
import org.moqui.entity.EntityCondition.JoinOperator
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ArtifactExecutionInfoImpl.ArtifactAuthzCheck
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.util.MNode

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ArtifactExecutionFacadeImpl implements ArtifactExecutionFacade {
    protected final static Logger logger = LoggerFactory.getLogger(ArtifactExecutionFacadeImpl.class)

    protected ExecutionContextImpl eci
    protected LinkedList<ArtifactExecutionInfoImpl> artifactExecutionInfoStack = new LinkedList<ArtifactExecutionInfoImpl>()
    protected LinkedList<ArtifactExecutionInfoImpl> artifactExecutionInfoHistory = new LinkedList<ArtifactExecutionInfoImpl>()

    // this is used by ScreenUrlInfo.isPermitted() which is called a lot, but that is transient so put here to have one per EC instance
    protected Map<String, Boolean> screenPermittedCache = null

    protected boolean authzDisabled = false
    protected boolean tarpitDisabled = false
    protected boolean entityEcaDisabled = false
    protected boolean entityAuditLogDisabled = false
    protected boolean entityFkCreateDisabled = false
    protected boolean entityDataFeedDisabled = false

    ArtifactExecutionFacadeImpl(ExecutionContextImpl eci) {
        this.eci = eci
    }

    Map<String, Boolean> getScreenPermittedCache() {
        if (screenPermittedCache == null) screenPermittedCache = new HashMap<>()
        return screenPermittedCache
    }

    @Override
    ArtifactExecutionInfo peek() { return this.artifactExecutionInfoStack.peekFirst() }

    @Override
    ArtifactExecutionInfo push(String name, ArtifactExecutionInfo.ArtifactType typeEnum, ArtifactExecutionInfo.AuthzAction actionEnum, boolean requiresAuthz) {
        ArtifactExecutionInfoImpl aeii = new ArtifactExecutionInfoImpl(name, typeEnum, actionEnum, "")
        pushInternal(aeii, requiresAuthz, true)
        return aeii
    }
    @Override
    void push(ArtifactExecutionInfo aei, boolean requiresAuthz) {
        ArtifactExecutionInfoImpl aeii = (ArtifactExecutionInfoImpl) aei
        pushInternal(aeii, requiresAuthz, true)
    }
    void pushInternal(ArtifactExecutionInfoImpl aeii, boolean requiresAuthz, boolean countTarpit) {
        ArtifactExecutionInfoImpl lastAeii = (ArtifactExecutionInfoImpl) artifactExecutionInfoStack.peekFirst()

        // always do this regardless of the authz checks, etc; keep a history of artifacts run
        if (lastAeii != null) { lastAeii.addChild(aeii); aeii.setParent(lastAeii) }
        else artifactExecutionInfoHistory.add(aeii)

        // if ("AT_XML_SCREEN" == aeii.typeEnumId) logger.warn("TOREMOVE artifact push ${username} - ${aeii}")

        if (!isPermitted(aeii, lastAeii, requiresAuthz, countTarpit, true, null)) {
            Deque<ArtifactExecutionInfo> curStack = getStack()
            StringBuilder warning = new StringBuilder()
            warning.append("User ${eci.user.username ?: eci.user.userId ?: '[No User]'} is not authorized for ${aeii.getActionDescription()} on ${aeii.getTypeDescription()} ${aeii.getName()}")

            ArtifactAuthorizationException e = new ArtifactAuthorizationException(warning.toString(), aeii, curStack)
            // end users see this message in vuet mode so better not to add all of this to the main message:
            warning.append("\nCurrent artifact info: ${aeii.toString()}\n")
            warning.append("Current artifact stack:")
            for (ArtifactExecutionInfo warnAei in curStack) warning.append("\n").append(warnAei.toString())
            logger.warn("Artifact authorization failed: " + warning.toString())
            throw e
        }

        // NOTE: if needed the isPermitted method will set additional info in aeii
        this.artifactExecutionInfoStack.addFirst(aeii)
    }


    @Override
    ArtifactExecutionInfo pop(ArtifactExecutionInfo aei) {
        try {
            ArtifactExecutionInfoImpl lastAeii = (ArtifactExecutionInfoImpl) artifactExecutionInfoStack.removeFirst()
            // removed this for performance reasons, generally just checking the name is adequate
            // || aei.typeEnumId != lastAeii.typeEnumId || aei.actionEnumId != lastAeii.actionEnumId
            if (aei != null && !lastAeii.nameInternal.equals(aei.getName())) {
                String popMessage = "Popped artifact (${aei.name}:${aei.getTypeDescription()}:${aei.getActionDescription()}) did not match top of stack (${lastAeii.name}:${lastAeii.getTypeDescription()}:${lastAeii.getActionDescription()}:${lastAeii.actionDetail})"
                logger.warn(popMessage, new BaseException("Pop Error Location"))
                //throw new IllegalArgumentException(popMessage)
            }
            // set end time
            lastAeii.setEndTime()
            // count artifact hit (now done here instead of by each caller)
            if (lastAeii.trackArtifactHit && lastAeii.internalAuthzWasRequired && lastAeii.isAccess)
                eci.ecfi.countArtifactHit(lastAeii.internalTypeEnum, lastAeii.actionDetail, lastAeii.nameInternal,
                        lastAeii.parameters, lastAeii.startTimeMillis, lastAeii.getRunningTimeMillisDouble(), lastAeii.outputSize)
            return lastAeii
        } catch(NoSuchElementException e) {
            logger.warn("Tried to pop from an empty ArtifactExecutionInfo stack", e)
            return null
        }
    }

    @Override
    Deque<ArtifactExecutionInfo> getStack() {
        Deque<ArtifactExecutionInfo> newStackDeque = new LinkedList<>()
        newStackDeque.addAll(this.artifactExecutionInfoStack)
        return newStackDeque
    }
    String getStackNameString() {
        StringBuilder sb = new StringBuilder()
        Iterator i = this.artifactExecutionInfoStack.iterator()
        while (i.hasNext()) {
            ArtifactExecutionInfo aei = (ArtifactExecutionInfo) i.next()
            sb.append(aei.name)
            if (i.hasNext()) sb.append(', ')
        }
        return sb.toString()
    }
    @Override
    List<ArtifactExecutionInfo> getHistory() {
        List<ArtifactExecutionInfo> newHistList = new ArrayList<>()
        newHistList.addAll(this.artifactExecutionInfoHistory)
        return newHistList
    }

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
        List<Map<String, Object>> ownHotSpotList = ArtifactExecutionInfoImpl.hotSpotByTime(artifactExecutionInfoHistory, true, "-time")
        ArtifactExecutionInfoImpl.printHotSpotList(sw, ownHotSpotList)
        logger.info(sw.toString())

        sw = new StringWriter()
        sw.append("========= Hot Spots by Total Time =========\n")
        sw.append("[{time}:{timeMin}:{timeAvg}:{timeMax}][{count}] {type} {action} {actionDetail} {name}\n")
        List<Map<String, Object>> totalHotSpotList = ArtifactExecutionInfoImpl.hotSpotByTime(artifactExecutionInfoHistory, false, "-time")
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
        if (aeii.authorizedAuthzType != ArtifactExecutionInfo.AUTHZT_ALWAYS) aeii.authorizedAuthzType = ArtifactExecutionInfo.AUTHZT_ALLOW
        aeii.internalAuthorizedActionEnum = ArtifactExecutionInfo.AUTHZA_ALL
    }

    void setAnonymousAuthorizedView() {
        ArtifactExecutionInfoImpl aeii = artifactExecutionInfoStack.peekFirst()
        aeii.authorizationInheritable = true
        aeii.authorizedUserId = eci.getUser().getUserId() ?: "_NA_"
        if (aeii.authorizedAuthzType != ArtifactExecutionInfo.AUTHZT_ALWAYS) aeii.authorizedAuthzType = ArtifactExecutionInfo.AUTHZT_ALLOW
        if (aeii.authorizedActionEnum != ArtifactExecutionInfo.AUTHZA_ALL) aeii.authorizedActionEnum = ArtifactExecutionInfo.AUTHZA_VIEW
    }

    boolean disableAuthz() { boolean alreadyDisabled = authzDisabled; authzDisabled = true; return alreadyDisabled }
    void enableAuthz() { authzDisabled = false }
    boolean getAuthzDisabled() { return authzDisabled }

    boolean disableTarpit() { boolean alreadyDisabled = tarpitDisabled; tarpitDisabled = true; return alreadyDisabled }
    void enableTarpit() { tarpitDisabled = false }
    // boolean getTarpitDisabled() { return tarpitDisabled }

    boolean disableEntityEca() { boolean alreadyDisabled = entityEcaDisabled; entityEcaDisabled = true; return alreadyDisabled }
    void enableEntityEca() { entityEcaDisabled = false }
    boolean entityEcaDisabled() { return entityEcaDisabled }

    boolean disableEntityAuditLog() { boolean alreadyDisabled = entityAuditLogDisabled; entityAuditLogDisabled = true; return alreadyDisabled }
    void enableEntityAuditLog() { entityAuditLogDisabled = false }
    boolean entityAuditLogDisabled() { return entityAuditLogDisabled }

    boolean disableEntityFkCreate() { boolean alreadyDisabled = entityFkCreateDisabled; entityFkCreateDisabled = true; return alreadyDisabled }
    void enableEntityFkCreate() { entityFkCreateDisabled = false }
    boolean entityFkCreateDisabled() { return entityFkCreateDisabled }

    boolean disableEntityDataFeed() { boolean alreadyDisabled = entityDataFeedDisabled; entityDataFeedDisabled = true; return alreadyDisabled }
    void enableEntityDataFeed() { entityDataFeedDisabled = false }
    boolean entityDataFeedDisabled() { return entityDataFeedDisabled }

    /** Checks to see if username is permitted to access given resource.
     *
     * @param resourceAccess Formatted as: "${typeEnumId}:${actionEnumId}:${name}"
     * @param nowTimestamp
     * @param eci
     */
    static boolean isPermitted(String resourceAccess, ExecutionContextImpl eci) {
        int firstColon = resourceAccess.indexOf(":")
        int secondColon = resourceAccess.indexOf(":", firstColon + 1)
        if (firstColon == -1 || secondColon == -1) throw new ArtifactAuthorizationException("Resource access string does not have two colons (':'), must be formatted like: \"\${typeEnumId}:\${actionEnumId}:\${name}\"", null, null)

        ArtifactExecutionInfo.ArtifactType typeEnum = ArtifactExecutionInfo.ArtifactType.valueOf(resourceAccess.substring(0, firstColon))
        ArtifactExecutionInfo.AuthzAction actionEnum = ArtifactExecutionInfo.AuthzAction.valueOf(resourceAccess.substring(firstColon + 1, secondColon))
        String name = resourceAccess.substring(secondColon + 1)

        return eci.artifactExecutionFacade.isPermitted(new ArtifactExecutionInfoImpl(name, typeEnum, actionEnum, ""),
                null, true, true, false, null)
    }

    boolean isPermitted(ArtifactExecutionInfoImpl aeii, ArtifactExecutionInfoImpl lastAeii, boolean requiresAuthz, boolean countTarpit,
                        boolean isAccess, LinkedList<ArtifactExecutionInfoImpl> currentStack) {
        ArtifactExecutionInfo.ArtifactType artifactTypeEnum = aeii.internalTypeEnum
        boolean isEntity = ArtifactExecutionInfo.AT_ENTITY.is(artifactTypeEnum)
        // right off record whether authz is required and is access
        aeii.setAuthzReqdAndIsAccess(requiresAuthz, isAccess)

        // never do this for entities when disableAuthz, as we might use any below and would cause infinite recursion
        // for performance reasons if this is an entity and no authz required don't bother looking at tarpit, checking for deny/etc
        if ((!requiresAuthz || this.authzDisabled) && isEntity) {
            if (lastAeii != null && lastAeii.authorizationInheritable) aeii.copyAuthorizedInfo(lastAeii)
            return true
        }

        // if ("AT_XML_SCREEN" == aeii.typeEnumId) logger.warn("TOREMOVE artifact isPermitted after authzDisabled ${aeii}")

        ExecutionContextFactoryImpl ecfi = eci.ecfi
        UserFacadeImpl ufi = eci.userFacade

        if (!isEntity && countTarpit && !tarpitDisabled && Boolean.TRUE.is((Boolean) ecfi.artifactTypeTarpitEnabled.get(artifactTypeEnum)) &&
                (requiresAuthz || (!ArtifactExecutionInfo.AT_XML_SCREEN.is(artifactTypeEnum) && !ArtifactExecutionInfo.AT_REST_PATH.is(artifactTypeEnum)))) {
            checkTarpit(aeii)
        }

        // if last was an always allow, then don't bother checking for deny/etc - this is a common case
        if (lastAeii != null && lastAeii.internalAuthorizationInheritable &&
                ArtifactExecutionInfo.AUTHZT_ALWAYS.is(lastAeii.internalAuthorizedAuthzType) &&
                (ArtifactExecutionInfo.AUTHZA_ALL.is(lastAeii.internalAuthorizedActionEnum) || aeii.internalActionEnum.is(lastAeii.internalAuthorizedActionEnum))) {
            // NOTE: used to also check userId.equals(lastAeii.internalAuthorizedUserId), but rare if ever that could even happen
            aeii.copyAuthorizedInfo(lastAeii)
            // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO"))
            //     logger.warn("TOREMOVE artifact isPermitted already authorized for user ${userId} - ${aeii}")
            return true
        }

        // tarpit enabled already checked, if authz not enabled return true immediately
        // NOTE: do this after the check above as authz is normally enabled so this doesn't normally save is any time
        if (!Boolean.TRUE.is((Boolean) ecfi.artifactTypeAuthzEnabled.get(artifactTypeEnum))) {
            if (lastAeii != null) aeii.copyAuthorizedInfo(lastAeii)
            return true
        }

        // search entire list for deny and allow authz, then check for allow with no deny after
        ArtifactAuthzCheck denyAacv = (ArtifactAuthzCheck) null
        ArtifactAuthzCheck allowAacv = (ArtifactAuthzCheck) null

        // see if there is a UserAccount for the username, and if so get its userId as a more permanent identifier
        String userId = ufi.getUserId()
        if (userId == null) userId = ""

        // don't check authz for these queries, would cause infinite recursion
        boolean alreadyDisabled = disableAuthz()
        try {
            // don't make a big condition for the DB to filter the list, or EntityList.filterByCondition from bigger
            //     cached list, both are slower than manual iterate and check fields explicitly
            ArrayList<ArtifactAuthzCheck> aacvList = new ArrayList<>()
            ArrayList<ArtifactAuthzCheck> origAacvList = ufi.getArtifactAuthzCheckList()
            int origAacvListSize = origAacvList.size()
            for (int i = 0; i < origAacvListSize; i++) {
                ArtifactAuthzCheck aacv = (ArtifactAuthzCheck) origAacvList.get(i)
                if (artifactTypeEnum.is(aacv.artifactType) &&
                        (ArtifactExecutionInfo.AUTHZA_ALL.is(aacv.authzAction) || aeii.internalActionEnum.is(aacv.authzAction)) &&
                        (aacv.nameIsPattern || aeii.nameInternal.equals(aacv.artifactName))) {
                    aacvList.add(aacv)
                }
            }

            // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO"))
            //     logger.warn("TOREMOVE for aeii [${aeii}] artifact isPermitted aacvList: ${aacvList}; aacvCond: ${aacvCond}")

            int aacvListSize = aacvList.size()
            for (int i = 0; i < aacvListSize; i++) {
                ArtifactAuthzCheck aacv = (ArtifactAuthzCheck) aacvList.get(i)

                // check the name
                if (aacv.nameIsPattern && !aeii.getName().matches(aacv.artifactName)) continue
                // check the filterMap
                if (aacv.filterMap != null && aeii.parameters != null) {
                    Map<String, Object> filterMapObj = (Map<String, Object>) eci.getResource().expression(aacv.filterMap, null)
                    boolean allMatches = true
                    for (Map.Entry<String, Object> filterEntry in filterMapObj.entrySet()) {
                        if (filterEntry.getValue() != aeii.parameters.get(filterEntry.getKey())) allMatches = false
                    }
                    if (!allMatches) continue
                }

                ArtifactExecutionInfo.AuthzType authzType = aacv.authzType
                String authzServiceName = aacv.authzServiceName
                if (authzServiceName != null && authzServiceName.length() > 0) {
                    Map result = eci.getService().sync().name(authzServiceName)
                            .parameters([userId:userId, authzActionEnumId:aeii.getActionEnum().name(),
                            artifactTypeEnumId:artifactTypeEnum.name(), artifactName:aeii.getName()]).call()
                    if (result?.authzTypeEnumId) authzType = ArtifactExecutionInfo.AuthzType.valueOf((String) result.authzTypeEnumId)
                }

                // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO"))
                //     logger.warn("TOREMOVE found authz record for aeii [${aeii}]: ${aacv}")
                if (ArtifactExecutionInfo.AUTHZT_DENY.is(authzType)) {
                    // we already know last was not always allow (checked above), so keep going in loop just in case
                    // we find an always allow in the query
                    denyAacv = aacv
                } else if (ArtifactExecutionInfo.AUTHZT_ALWAYS.is(authzType)) {
                    aeii.copyAacvInfo(aacv, userId, true)
                    // if ("AT_XML_SCREEN" == aeii.typeEnumId)
                    //     logger.warn("TOREMOVE artifact isPermitted found always allow for user ${userId} - ${aeii}")
                    return true
                } else if (denyAacv == null && ArtifactExecutionInfo.AUTHZT_ALLOW.is(authzType)) {
                    // see if there are any denies in AEIs on lower on the stack
                    boolean ancestorDeny = false
                    for (ArtifactExecutionInfoImpl ancestorAeii in (currentStack ?: artifactExecutionInfoStack))
                        if (ArtifactExecutionInfo.AUTHZT_DENY.is(ancestorAeii.getAuthorizedAuthzType())) ancestorDeny = true

                    if (!ancestorDeny) allowAacv = aacv
                }
            }
        } finally {
            if (!alreadyDisabled) enableAuthz()
        }

        if (denyAacv != null) {
            // record that this was an explicit deny (for push or exception in case something catches and handles it)
            aeii.copyAacvInfo(denyAacv, userId, false)

            if (!requiresAuthz || this.authzDisabled) {
                // if no authz required, just return true even though it was a failure
                // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO"))
                //     logger.warn("TOREMOVE artifact isPermitted (in deny) doesn't require authz or authzDisabled for user ${userId} - ${aeii}")
                return true
            } else {
                StringBuilder warning = new StringBuilder()
                warning.append("User [${userId}] is not authorized for ${aeii.getTypeDescription()} [${aeii.getName()}] because of a deny record [type:${artifactTypeEnum.name()},action:${aeii.getActionEnum().name()}], here is the current artifact stack:")
                for (warnAei in this.stack) warning.append("\n").append(warnAei.toString())
                logger.warn(warning.toString())

                eci.getService().sync().name("create", "moqui.security.ArtifactAuthzFailure").parameters(
                        [artifactName:aeii.getName(), artifactTypeEnumId:artifactTypeEnum.name(),
                        authzActionEnumId:aeii.getActionEnum().name(), userId:userId,
                        failureDate:new Timestamp(System.currentTimeMillis()), isDeny:"Y"]).disableAuthz().call()

                return false
            }
        } else if (allowAacv != null) {
            aeii.copyAacvInfo(allowAacv, userId, true)
            // if ("AT_XML_SCREEN" == aeii.typeEnumId && aeii.getName().contains("FOO"))
            //     logger.warn("TOREMOVE artifact isPermitted allow with no deny for user ${userId} - ${aeii}")
            return true
        } else {
            // no perms found for this, only allow if the current AEI has inheritable auth and same user, and (ALL action or same action)

            // NOTE: this condition allows any user to be authenticated and allow inheritance if the last artifact was
            //       logged in anonymously (ie userId="_NA_"); consider alternate approaches; an alternate approach is
            //       in place when no user is logged in, but when one is this is the only solution so far
            if (lastAeii != null && lastAeii.internalAuthorizationInheritable &&
                    ("_NA_".equals(lastAeii.internalAuthorizedUserId) || lastAeii.internalAuthorizedUserId == userId) &&
                    (ArtifactExecutionInfo.AUTHZA_ALL.is(lastAeii.internalAuthorizedActionEnum) || aeii.internalActionEnum.is(lastAeii.internalAuthorizedActionEnum)) &&
                    !ArtifactExecutionInfo.AUTHZT_DENY.is(lastAeii.internalAuthorizedAuthzType)) {
                aeii.copyAuthorizedInfo(lastAeii)
                // if ("AT_XML_SCREEN" == aeii.typeEnumId)
                //     logger.warn("TOREMOVE artifact isPermitted inheritable and same user and ALL or same action for user ${userId} - ${aeii}")
                return true
            }
        }

        if (!requiresAuthz || this.authzDisabled) {
            // if no authz required, just push it even though it was a failure
            if (lastAeii != null && lastAeii.internalAuthorizationInheritable) aeii.copyAuthorizedInfo(lastAeii)
            // if ("AT_XML_SCREEN" == aeii.typeEnumId)
            //     logger.warn("TOREMOVE artifact isPermitted doesn't require authz or authzDisabled for user ${userId} - ${aeii}")
            return true
        } else {
            // if we got here no authz found, so not granted (denied)
            aeii.setAuthorizationWasGranted(false)

            if (logger.isDebugEnabled()) {
                StringBuilder warning = new StringBuilder()
                warning.append("User [${userId}] is not authorized for ${aeii.getTypeDescription()} [${aeii.getName()}] because of no allow record [type:${artifactTypeEnum.name()},action:${aeii.getActionEnum().name()}]\nlastAeii=[${lastAeii}]\nHere is the artifact stack:")
                for (warnAei in this.stack) warning.append("\n").append(warnAei)
                logger.debug(warning.toString())
            }

            if (isAccess) {
                alreadyDisabled = disableAuthz()
                try {
                    // NOTE: this is called sync because failures should be rare and not as performance sensitive, and
                    //    because this is still in a disableAuthz block (if async a service would have to be written for that)
                    eci.service.sync().name("create", "moqui.security.ArtifactAuthzFailure").parameters(
                            [artifactName:aeii.getName(), artifactTypeEnumId:artifactTypeEnum.name(),
                             authzActionEnumId:aeii.getActionEnum().name(), userId:userId,
                             failureDate:new Timestamp(System.currentTimeMillis()), isDeny:"N"]).call()
                } finally {
                    if (!alreadyDisabled) enableAuthz()
                }
            }

            return false
        }

        // if ("AT_XML_SCREEN" == aeii.typeEnumId) logger.warn("TOREMOVE artifact isPermitted got to end for user ${userId} - ${aeii}")
        // return true
    }

    protected void checkTarpit(ArtifactExecutionInfoImpl aeii) {
        // logger.warn("Count tarpit ${aeii.toBasicString()}", new BaseException("loc"))

        ExecutionContextFactoryImpl ecfi = eci.ecfi
        UserFacadeImpl ufi = eci.userFacade
        ArtifactExecutionInfo.ArtifactType artifactTypeEnum = aeii.internalTypeEnum

        ArrayList<Map<String, Object>> artifactTarpitCheckList = ufi.getArtifactTarpitCheckList(artifactTypeEnum)
        if (artifactTarpitCheckList == null || artifactTarpitCheckList.size() == 0) return

        boolean alreadyDisabled = disableAuthz()
        try {
            // record and check velocity limit (tarpit)
            boolean recordHitTime = false
            long lockForSeconds = 0L
            long checkTime = System.currentTimeMillis()
            // if (artifactTypeEnumId == "AT_XML_SCREEN")
            //     logger.warn("TOREMOVE about to check tarpit [${tarpitKey}], userGroupIdSet=${userGroupIdSet}, artifactTarpitList=${artifactTarpitList}")

            // see if there is a UserAccount for the username, and if so get its userId as a more permanent identifier
            String userId = ufi.getUserId()
            if (userId == null) userId = ""

            String tarpitKey = userId + '@' + artifactTypeEnum.name() + ':' + aeii.getName()
            ArrayList<Long> hitTimeList = (ArrayList<Long>) null
            int artifactTarpitCheckListSize = artifactTarpitCheckList.size()
            for (int i = 0; i < artifactTarpitCheckListSize; i++) {
                Map<String, Object> artifactTarpit = (Map<String, Object>) artifactTarpitCheckList.get(i)
                if (('Y'.equals(artifactTarpit.nameIsPattern) &&
                        aeii.nameInternal.matches((String) artifactTarpit.artifactName)) ||
                        aeii.nameInternal.equals(artifactTarpit.artifactName)) {
                    recordHitTime = true
                    if (hitTimeList == null) hitTimeList = (ArrayList<Long>) eci.tarpitHitCache.get(tarpitKey)
                    long maxHitsDuration = artifactTarpit.maxHitsDuration as long
                    // count hits in this duration; start with 1 to count the current hit
                    long hitsInDuration = 1L
                    if (hitTimeList != null && hitTimeList.size() > 0) {
                        // copy the list to avoid a ConcurrentModificationException
                        // NOTE: a better approach to concurrency that won't ever miss hits would be better
                        ArrayList<Long> hitTimeListCopy = new ArrayList<Long>(hitTimeList)
                        for (int htlInd = 0; htlInd < hitTimeListCopy.size(); htlInd++) {
                            Long hitTime = (Long) hitTimeListCopy.get(htlInd)
                            if (hitTime != null && ((hitTime - checkTime) < maxHitsDuration)) hitsInDuration++
                        }
                    }
                    // logger.warn("TOREMOVE artifact [${tarpitKey}], now has ${hitsInDuration} hits in ${maxHitsDuration} seconds")
                    if (hitsInDuration > (artifactTarpit.maxHitsCount as long) && (artifactTarpit.tarpitDuration as long) > lockForSeconds) {
                        lockForSeconds = artifactTarpit.tarpitDuration as long
                        logger.warn("User [${userId}] exceeded ${artifactTarpit.maxHitsCount} in ${maxHitsDuration} seconds for artifact [${tarpitKey}], locking for ${lockForSeconds} seconds")
                    }
                }
            }
            if (recordHitTime) {
                if (hitTimeList == null) { hitTimeList = new ArrayList<Long>(); eci.tarpitHitCache.put(tarpitKey, hitTimeList) }
                hitTimeList.add(System.currentTimeMillis())
                // logger.warn("TOREMOVE recorded hit time for [${tarpitKey}], now has ${hitTimeList.size()} hits")

                // check the ArtifactTarpitLock for the current artifact attempt before seeing if there is a new lock to create
                // NOTE: this only runs if we are recording a hit time for an artifact, so no performance impact otherwise
                EntityFacadeImpl efi = ecfi.entityFacade
                EntityList tarpitLockList = efi.find('moqui.security.ArtifactTarpitLock')
                        .condition([userId:userId, artifactName:aeii.getName(), artifactTypeEnumId:artifactTypeEnum.name()] as Map<String, Object>)
                        .useCache(true).list()
                        .filterByCondition(efi.getConditionFactory().makeCondition('releaseDateTime', ComparisonOperator.GREATER_THAN, ufi.getNowTimestamp()), true)
                if (tarpitLockList.size() > 0) {
                    Timestamp releaseDateTime = tarpitLockList.get(0).getTimestamp('releaseDateTime')
                    int retryAfterSeconds = ((releaseDateTime.getTime() - System.currentTimeMillis())/1000).intValue()
                    throw new ArtifactTarpitException("User ${userId} has accessed ${aeii.getTypeDescription()} ${aeii.getName()} too many times and may not again until ${eci.l10nFacade.format(releaseDateTime, 'yyyy-MM-dd HH:mm:ss')} (retry after ${retryAfterSeconds} seconds)".toString(), retryAfterSeconds)
                }
            }
            // record the tarpit lock
            if (lockForSeconds > 0L) {
                eci.getService().sync().name('create', 'moqui.security.ArtifactTarpitLock').parameters(
                        [userId:userId, artifactName:aeii.getName(), artifactTypeEnumId:artifactTypeEnum.name(),
                         releaseDateTime:(new Timestamp(checkTime + ((lockForSeconds as BigDecimal) * 1000).intValue()))]).call()
                eci.tarpitHitCache.remove(tarpitKey)
            }
        } finally {
            if (!alreadyDisabled) enableAuthz()
        }
    }

    static class AuthzFilterInfo {
        String entityFilterSetId
        EntityValue entityFilter
        Map<String, ArrayList<MNode>> memberFieldAliases
        AuthzFilterInfo(String entityFilterSetId, EntityValue entityFilter, Map<String, ArrayList<MNode>> memberFieldAliases) {
            this.entityFilterSetId = entityFilterSetId
            this.entityFilter = entityFilter
            this.memberFieldAliases = memberFieldAliases
        }
    }
    ArrayList<AuthzFilterInfo> getFindFiltersForUser(String findEntityName) {
        EntityDefinition findEd = eci.entityFacade.getEntityDefinition(findEntityName)
        return getFindFiltersForUser(findEd, null)
    }
    ArrayList<AuthzFilterInfo> getFindFiltersForUser(EntityDefinition findEd, Set<String> entityAliasUsedSet) {
        // do nothing if authz disabled
        if (authzDisabled) return null

        // NOTE: look for filters in all unique aacv in stack? shouldn't be needed, most recent auth is the valid one
        ArtifactExecutionInfoImpl lastAeii = (ArtifactExecutionInfoImpl) artifactExecutionInfoStack.peekFirst()
        ArtifactAuthzCheck aacv = lastAeii.internalAacv
        if (aacv == null) return null

        String findEntityName = findEd.getFullEntityName()
        // skip all Moqui Framework entities;  note that this skips moqui.example too...
        if (findEntityName.startsWith("moqui.")) return null

        // find applicable EntityFilter records
        EntityList artifactAuthzFilterList = eci.entityFacade.find("moqui.security.ArtifactAuthzFilter")
                .condition("artifactAuthzId", aacv.artifactAuthzId).disableAuthz().useCache(true).list()

        if (artifactAuthzFilterList == null) return null
        int authzFilterSize = artifactAuthzFilterList.size()
        if (authzFilterSize == 0) return null

        ArrayList<AuthzFilterInfo> authzFilterInfoList = (ArrayList<AuthzFilterInfo>) null
        for (int i = 0; i < authzFilterSize; i++) {
            EntityValue artifactAuthzFilter = (EntityValue) artifactAuthzFilterList.get(i)
            String entityFilterSetId = (String) artifactAuthzFilter.getNoCheckSimple("entityFilterSetId")
            String authzApplyCond = (String) artifactAuthzFilter.getNoCheckSimple("applyCond")

            EntityValue entityFilterSet = eci.entityFacade.find("moqui.security.EntityFilterSet")
                    .condition("entityFilterSetId", entityFilterSetId).disableAuthz().useCache(true).one()
            String setApplyCond = (String) entityFilterSet.getNoCheckSimple("applyCond")

            boolean hasAuthzCond = authzApplyCond != null && !authzApplyCond.isEmpty()
            boolean hasSetCond = setApplyCond != null && !setApplyCond.isEmpty()
            if (hasAuthzCond || hasSetCond) {
                // for evaluating apply conditions add user context to ec.context
                // this might be more efficient outside the loop, or perhaps even expect it to be in place outside this method
                //     (fine for filterFindForUser(), cumbersome for other uses of this method)
                eci.contextStack.push(eci.userFacade.context)
                try {
                    if (hasAuthzCond && !eci.resourceFacade.condition(authzApplyCond, null)) continue
                    if (hasSetCond && !eci.resourceFacade.condition(setApplyCond, null)) continue
                } finally {
                    eci.contextStack.pop()
                }
            }

            // NOTE: at this level the results could be cached, but worth it? EntityFilter entity list cached already,
            //     some processing for view-entity but mostly only if entityAliasUsedSet, and could only cache if !entityAliasUsedSet
            EntityList entityFilterList = eci.entityFacade.find("moqui.security.EntityFilter")
                    .condition("entityFilterSetId", entityFilterSetId).disableAuthz().useCache(true).list()

            if (entityFilterList == null) continue
            int entFilterSize = entityFilterList.size()
            if (entFilterSize == 0) continue

            for (int j = 0; j < entFilterSize; j++) {
                EntityValue entityFilter = entityFilterList.get(j)
                String filterEntityName = (String) entityFilter.getNoCheckSimple("entityName")
                if (filterEntityName == null) continue

                // see if there if any filter entities match the current entity or if it is a view then a member entity
                Map<String, ArrayList<MNode>> memberFieldAliases = (Map<String, ArrayList<MNode>>) null
                if (!filterEntityName.equals(findEd.getFullEntityName())) {
                    if (findEd.isViewEntity) {
                        memberFieldAliases = findEd.getMemberFieldAliases(filterEntityName)
                        if (memberFieldAliases == null) continue
                    } else {
                        continue
                    }
                }

                if (memberFieldAliases != null && entityAliasUsedSet != null) {
                    // trim memberFieldAliases by entity aliases actually used
                    Map<String, ArrayList<MNode>> newFieldAliases = (Map<String, ArrayList<MNode>>) null

                    for (Map.Entry<String, ArrayList<MNode>> aliasesEntry in memberFieldAliases.entrySet()) {
                        ArrayList<MNode> aliasList = aliasesEntry.getValue()
                        if (aliasList == null) continue // should never happen, buy yeah
                        ArrayList<MNode> newAliasList = (ArrayList<MNode>) null

                        int aliasListSize = aliasList.size()
                        for (int ali = 0; ali < aliasListSize; ali++) {
                            MNode aliasNode = (MNode) aliasList.get(ali)
                            String entityAlias = aliasNode.attribute("entity-alias")
                            if (entityAliasUsedSet.contains(entityAlias)) {
                                // is used, copy over
                                if (newAliasList == null) {
                                    newAliasList = new ArrayList<>()
                                    if (newFieldAliases == null) newFieldAliases = new LinkedHashMap<>()
                                    newFieldAliases.put(aliasesEntry.getKey(), newAliasList)
                                }
                                newAliasList.add(aliasNode)
                            }
                        }
                    }

                    // if nothing added then nothing to filter on for this entity
                    if (newFieldAliases == (Map<String, ArrayList<MNode>>) null) continue
                    memberFieldAliases = newFieldAliases
                }

                // if we got to this point we found a matching filter
                if (authzFilterInfoList == (ArrayList<AuthzFilterInfo>) null) authzFilterInfoList = new ArrayList<>()
                authzFilterInfoList.add(new AuthzFilterInfo(entityFilterSetId, entityFilter, memberFieldAliases))
            }
        }

        return authzFilterInfoList
    }

    ArrayList<EntityConditionImplBase> filterFindForUser(EntityDefinition findEd, Set<String> entityAliasUsedSet) {
        ArrayList<AuthzFilterInfo> authzFilterInfoList = getFindFiltersForUser(findEd, entityAliasUsedSet)
        if (authzFilterInfoList == null) return null
        int authzFilterInfoListSize = authzFilterInfoList.size()
        if (authzFilterInfoListSize == 0) return null

        // for evaluating filter Maps add user context to ec.context
        eci.contextStack.push(eci.userFacade.context)

        ArrayList<EntityConditionImplBase> condList = (ArrayList<EntityConditionImplBase>) null
        try {
            for (int i = 0; i < authzFilterInfoListSize; i++) {
                AuthzFilterInfo authzFilterInfo = (AuthzFilterInfo) authzFilterInfoList.get(i)
                EntityValue entityFilter = authzFilterInfo.entityFilter
                Map<String, ArrayList<MNode>> memberFieldAliases = authzFilterInfo.memberFieldAliases

                // NOTE: this expression eval must be done for the current context, with eci.userFacade.context added
                Object filterMapObjEval = eci.resourceFacade.expression((String) entityFilter.getNoCheckSimple('filterMap'), null)
                Map<String, Object> filterMapObj
                if (filterMapObjEval instanceof Map) {
                    filterMapObj = filterMapObjEval as Map<String, Object>
                } else {
                    logger.error("EntityFiler filterMap did not evaluate to a Map<String, Object>: ${entityFilter.getString('filterMap')}")
                    continue
                }
                // logger.warn("===== ${findEd.getFullEntityName()} filterMapObj: ${filterMapObj}")

                EntityConditionFactoryImpl conditionFactory = eci.entityFacade.conditionFactoryImpl
                String efComparisonEnumId = (String) entityFilter.getNoCheckSimple('comparisonEnumId')
                ComparisonOperator compOp = efComparisonEnumId != null && efComparisonEnumId.length() > 0 ?
                        conditionFactory.comparisonOperatorFromEnumId(efComparisonEnumId) : null
                JoinOperator joinOp = "Y".equals(entityFilter.getNoCheckSimple('joinOr')) ? EntityCondition.OR : EntityCondition.AND

                // use makeCondition(Map) instead of breaking down here
                try {
                    EntityConditionImplBase entCond = conditionFactory.makeCondition(filterMapObj, compOp, joinOp, findEd, memberFieldAliases, true)
                    if (entCond == (EntityConditionImplBase) null) continue

                    // add the condition to the list to return
                    if (condList == (ArrayList<EntityConditionImplBase>) null) condList = new ArrayList<>()
                    condList.add(entCond)

                    // logger.info("Query on ${findEntityName} added authz filter conditions: ${entCond}")
                    // logger.info("Query on ${findEntityName} find: ${efb.toString()}")
                } catch (Exception e) {
                    logger.warn("Error adding authz entity filter ${entityFilter.getNoCheckSimple("entityFilterId")} condition: ${e.toString()}")
                }
            }
        } finally {
            eci.contextStack.pop()
        }

        // if (condList) logger.warn("Filters for ${findEd.getFullEntityName()}: ${condList}")
        return condList
    }
}
