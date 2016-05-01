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
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.entity.EntityValue
import org.moqui.impl.StupidUtilities

import java.math.RoundingMode

@CompileStatic
class ArtifactExecutionInfoImpl implements ArtifactExecutionInfo {

    protected final String name
    protected final String typeEnumId
    protected final String actionEnumId
    protected String actionDetail = ""
    protected Map<String, Object> parameters = (Map) null
    protected String internalAuthorizedUserId = (String) null
    protected String internalAuthorizedAuthzTypeId = (String) null
    protected String internalAuthorizedActionEnumId = (String) null
    protected boolean internalAuthorizationInheritable = false
    protected Map<String, Object> internalAacv = (Map<String, Object>) null

    //protected Exception createdLocation = null
    protected ArtifactExecutionInfoImpl parentAeii = (ArtifactExecutionInfoImpl) null
    protected long startTime
    protected long endTime = 0
    protected ArrayList<ArtifactExecutionInfoImpl> childList = new ArrayList<ArtifactExecutionInfoImpl>()
    protected long childrenRunningTime = 0

    ArtifactExecutionInfoImpl(String name, String typeEnumId, String actionEnumId) {
        this.name = name
        this.typeEnumId = typeEnumId
        this.actionEnumId = actionEnumId != null && actionEnumId.length() > 0 ? actionEnumId : "AUTHZA_ALL"
        //createdLocation = new Exception("Create AEII location for ${name}, type ${typeEnumId}, action ${actionEnumId}")
        this.startTime = System.nanoTime()
    }

    ArtifactExecutionInfoImpl setActionDetail(String detail) { this.actionDetail = detail; return this }
    ArtifactExecutionInfoImpl setParameters(Map<String, Object> parameters) { this.parameters = parameters; return this }

    @Override
    String getName() { return this.name }

    @Override
    String getTypeEnumId() { return this.typeEnumId }

    @Override
    String getActionEnumId() { return this.actionEnumId }

    @Override
    String getAuthorizedUserId() { return this.internalAuthorizedUserId }
    void setAuthorizedUserId(String authorizedUserId) { this.internalAuthorizedUserId = authorizedUserId }

    @Override
    String getAuthorizedAuthzTypeId() { return this.internalAuthorizedAuthzTypeId }
    void setAuthorizedAuthzTypeId(String authorizedAuthzTypeId) { this.internalAuthorizedAuthzTypeId = authorizedAuthzTypeId }

    @Override
    String getAuthorizedActionEnumId() { return this.internalAuthorizedActionEnumId }
    void setAuthorizedActionEnumId(String authorizedActionEnumId) { this.internalAuthorizedActionEnumId = authorizedActionEnumId }

    @Override
    boolean isAuthorizationInheritable() { return this.internalAuthorizationInheritable }
    void setAuthorizationInheritable(boolean isAuthorizationInheritable) { this.internalAuthorizationInheritable = isAuthorizationInheritable}

    Map<String, Object> getAacv() { return internalAacv }

    void copyAacvInfo(Map<String, Object> aacv, String userId) {
        internalAacv = aacv
        internalAuthorizedUserId = userId
        internalAuthorizedAuthzTypeId = (String) aacv.get('authzTypeEnumId')
        internalAuthorizedActionEnumId = (String) aacv.get('authzActionEnumId')
        internalAuthorizationInheritable = "Y".equals(aacv.get('inheritAuthz'))
    }

    void copyAuthorizedInfo(ArtifactExecutionInfoImpl aeii) {
        internalAacv = aeii.internalAacv
        internalAuthorizedUserId = aeii.internalAuthorizedUserId
        internalAuthorizedAuthzTypeId = aeii.internalAuthorizedAuthzTypeId
        internalAuthorizedActionEnumId = aeii.internalAuthorizedActionEnumId
        internalAuthorizationInheritable = aeii.internalAuthorizationInheritable
    }

    void setEndTime() { this.endTime = System.nanoTime() }
    @Override
    long getRunningTime() { return endTime != 0 ? endTime - startTime : 0 }
    void calcChildTime(boolean recurse) {
        childrenRunningTime = 0
        for (ArtifactExecutionInfoImpl aeii in childList) {
            childrenRunningTime += aeii.getRunningTime()
            if (recurse) aeii.calcChildTime(true)
        }
    }
    @Override
    long getThisRunningTime() { return getRunningTime() - getChildrenRunningTime() }
    @Override
    long getChildrenRunningTime() {
        if (childrenRunningTime == 0) calcChildTime(false)
        return childrenRunningTime ?: 0
    }

    BigDecimal getRunningTimeMillis() { new BigDecimal(getRunningTime()).movePointLeft(6).setScale(2, RoundingMode.HALF_UP) }
    BigDecimal getThisRunningTimeMillis() { new BigDecimal(getThisRunningTime()).movePointLeft(6).setScale(2, RoundingMode.HALF_UP) }
    BigDecimal getChildrenRunningTimeMillis() { new BigDecimal(getChildrenRunningTime()).movePointLeft(6).setScale(2, RoundingMode.HALF_UP) }

    void setParent(ArtifactExecutionInfoImpl parentAeii) { this.parentAeii = parentAeii }
    @Override
    ArtifactExecutionInfo getParent() { return parentAeii }
    @Override
    BigDecimal getPercentOfParentTime() { parentAeii && endTime != 0 ?
        (((getRunningTime() / parentAeii.getRunningTime()) * 100) as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP) : 0 }


    void addChild(ArtifactExecutionInfoImpl aeii) { childList.add(aeii) }
    @Override
    List<ArtifactExecutionInfo> getChildList() {
        List<ArtifactExecutionInfo> newChildList = new ArrayList<>()
        newChildList.addAll(childList)
        return newChildList
    }

    void print(Writer writer, int level, boolean children) {
        for (int i = 0; i < (level * 2); i++) writer.append(' ')
        writer.append('[').append(parentAeii ? StupidUtilities.paddedString(getPercentOfParentTime() as String, 5, false) : '     ').append('%]')
        writer.append('[').append(StupidUtilities.paddedString(getRunningTimeMillis() as String, 5, false)).append(']')
        writer.append('[').append(StupidUtilities.paddedString(getThisRunningTimeMillis() as String, 3, false)).append(']')
        writer.append('[').append(childList ? StupidUtilities.paddedString(getChildrenRunningTimeMillis() as String, 3, false) : '   ').append('] ')
        writer.append(StupidUtilities.paddedString(ArtifactExecutionFacadeImpl.artifactTypeDescriptionMap.get(typeEnumId), 10, true)).append(' ')
        writer.append(StupidUtilities.paddedString(ArtifactExecutionFacadeImpl.artifactActionDescriptionMap.get(actionEnumId), 7, true)).append(' ')
        writer.append(StupidUtilities.paddedString(actionDetail, 5, true)).append(' ')
        writer.append(name).append('\n')

        if (children) for (ArtifactExecutionInfoImpl aeii in childList) aeii.print(writer, level + 1, true)
    }

    String getKeyString() { return name + ":" + typeEnumId + ":" + actionEnumId + ":" + actionDetail }

    static List<Map> hotSpotByTime(List<ArtifactExecutionInfoImpl> aeiiList, boolean ownTime, String orderBy) {
        Map<String, Map> timeByArtifact = [:]
        for (ArtifactExecutionInfoImpl aeii in aeiiList) aeii.addToMapByTime(timeByArtifact, ownTime)
        List<Map> hotSpotList = []
        hotSpotList.addAll(timeByArtifact.values())

        // in some cases we get REALLY long times before the system is warmed, knock those out
        for (Map val in hotSpotList) {
            int knockOutCount = 0
            List<BigDecimal> newTimes = []
            BigDecimal timeAvg = (BigDecimal) val.timeAvg
            for (BigDecimal time in (List<BigDecimal>) val.times) {
                // this ain't no standard deviation, but consider 3 times average to be abnormal
                if (time > (timeAvg * 3)) {
                    knockOutCount++
                } else {
                    newTimes.add(time)
                }
            }
            if (knockOutCount > 0 && newTimes.size() > 0) {
                // calc new average, add knockOutCount times to fill in gaps, calc new time total
                BigDecimal newTotal = 0
                BigDecimal newMax = 0
                for (BigDecimal time in newTimes) { newTotal += time; if (time > newMax) newMax = time }
                BigDecimal newAvg = ((newTotal / newTimes.size()) as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP)
                // long newTimeAvg = newAvg.setScale(0, BigDecimal.ROUND_HALF_UP)
                newTotal += newAvg * knockOutCount
                val.time = newTotal
                val.timeMax = newMax
                val.timeAvg = newAvg
            }
        }

        StupidUtilities.orderMapList(hotSpotList, [orderBy ?: '-time'])
        return hotSpotList
    }
    void addToMapByTime(Map<String, Map> timeByArtifact, boolean ownTime) {
        String key = getKeyString()
        Map val = timeByArtifact.get(key)
        BigDecimal curTime = ownTime ? getThisRunningTimeMillis() : getRunningTimeMillis()
        if (val == null) {
            timeByArtifact.put(key, [times:[curTime], time:curTime, timeMin:curTime, timeMax:curTime, timeAvg:curTime,
                    count:1, name:name, actionDetail:actionDetail,
                    type:ArtifactExecutionFacadeImpl.artifactTypeDescriptionMap.get(typeEnumId),
                    action:ArtifactExecutionFacadeImpl.artifactActionDescriptionMap.get(actionEnumId)])
        } else {
            val = timeByArtifact[key]
            val.count = (BigDecimal) val.count + 1
            if (val.count == 2 && ((List<BigDecimal>) val.times)[0] > (curTime * 3)) {
                // if the first is much higher than the 2nd, use the 2nd for both
                val.times = [curTime, curTime]
                val.time = curTime + curTime
                val.timeMin = curTime
                val.timeMax = curTime
                val.timeAvg = curTime
            } else {
                ((List) val.times).add(curTime)
                val.time = (BigDecimal) val.time + curTime
                val.timeMin = (BigDecimal) val.timeMin > curTime ? curTime : (BigDecimal) val.timeMin
                val.timeMax = (BigDecimal) val.timeMax > curTime ? (BigDecimal) val.timeMax : curTime
                val.timeAvg = (((BigDecimal) val.time / (BigDecimal) val.count) as BigDecimal).setScale(2, BigDecimal.ROUND_HALF_UP)
            }
        }
        for (ArtifactExecutionInfoImpl aeii in childList) aeii.addToMapByTime(timeByArtifact, ownTime)
    }
    static void printHotSpotList(Writer writer, List<Map> infoList) {
        // "[${time}:${timeMin}:${timeAvg}:${timeMax}][${count}] ${type} ${action} ${actionDetail} ${name}"
        for (Map info in infoList) {
            writer.append('[').append(StupidUtilities.paddedString(info.time as String, 8, false)).append(':')
            writer.append(StupidUtilities.paddedString(info.timeMin as String, 7, false)).append(':')
            writer.append(StupidUtilities.paddedString(info.timeAvg as String, 7, false)).append(':')
            writer.append(StupidUtilities.paddedString(info.timeMax as String, 7, false)).append(']')
            writer.append('[').append(StupidUtilities.paddedString(info.count as String, 4, false)).append('] ')
            writer.append(StupidUtilities.paddedString((String) info.type, 10, true)).append(' ')
            writer.append(StupidUtilities.paddedString((String) info.action, 7, true)).append(' ')
            writer.append(StupidUtilities.paddedString((String) info.actionDetail, 5, true)).append(' ')
            writer.append((String) info.name).append('\n')
        }
    }


    static List<Map> consolidateArtifactInfo(List<ArtifactExecutionInfoImpl> aeiiList) {
        List<Map> topLevelList = []
        Map<String, Map> flatMap = [:]
        for (ArtifactExecutionInfoImpl aeii in aeiiList) aeii.consolidateArtifactInfo(topLevelList, flatMap, null)
        return topLevelList
    }
    void consolidateArtifactInfo(List<Map> topLevelList, Map<String, Map> flatMap, Map parentArtifactMap) {
        String key = getKeyString()
        Map artifactMap = flatMap.get(key)
        if (artifactMap == null) {
            artifactMap = [time:getRunningTimeMillis(), thisTime:getThisRunningTimeMillis(), childrenTime:getChildrenRunningTimeMillis(),
                    count:1, name:name, actionDetail:actionDetail, childInfoList:[], key:key,
                    type:ArtifactExecutionFacadeImpl.artifactTypeDescriptionMap.get(typeEnumId),
                    action:ArtifactExecutionFacadeImpl.artifactActionDescriptionMap.get(actionEnumId)]
            flatMap.put(key, artifactMap)
            if (parentArtifactMap != null) {
                ((List) parentArtifactMap.childInfoList).add(artifactMap)
            } else {
                topLevelList.add(artifactMap)
            }
        } else {
            artifactMap.count = (BigDecimal) artifactMap.count + 1
            artifactMap.time = (BigDecimal) artifactMap.time + getRunningTimeMillis()
            artifactMap.thisTime = (BigDecimal) artifactMap.thisTime + getThisRunningTimeMillis()
            artifactMap.childrenTime = (BigDecimal) artifactMap.childrenTime + getChildrenRunningTimeMillis()
            if (parentArtifactMap != null) {
                // is the current artifact in the current parent's child list? if not add it (a given artifact may be under multiple parents, normal)
                boolean foundMap = false
                for (Map candidate in (List<Map>) parentArtifactMap.childInfoList) if (candidate.key == key) { foundMap = true; break }
                if (!foundMap) ((List) parentArtifactMap.childInfoList).add(artifactMap)
            }
        }

        for (ArtifactExecutionInfoImpl aeii in childList) aeii.consolidateArtifactInfo(topLevelList, flatMap, artifactMap)
    }
    static String printArtifactInfoList(List<Map> infoList) {
        StringWriter sw = new StringWriter()
        printArtifactInfoList(sw, infoList, 0)
        return sw.toString()
    }
    static void printArtifactInfoList(Writer writer, List<Map> infoList, int level) {
        // "[${time}:${thisTime}:${childrenTime}][${count}] ${type} ${action} ${actionDetail} ${name}"
        for (Map info in infoList) {
            for (int i = 0; i < level; i++) writer.append('|').append(' ')
            writer.append('[').append(StupidUtilities.paddedString(info.time as String, 8, false)).append(':')
            writer.append(StupidUtilities.paddedString(info.thisTime as String, 6, false)).append(':')
            writer.append(StupidUtilities.paddedString(info.childrenTime as String, 6, false)).append(']')
            writer.append('[').append(StupidUtilities.paddedString(info.count as String, 4, false)).append('] ')
            writer.append(StupidUtilities.paddedString((String) info.type, 10, true)).append(' ')
            writer.append(StupidUtilities.paddedString((String) info.action, 7, true)).append(' ')
            writer.append(StupidUtilities.paddedString((String) info.actionDetail, 5, true)).append(' ')
            writer.append((String) info.name).append('\n')
            // if we get past level 25 just give up, probably a loop in the tree
            if (level < 25) {
                printArtifactInfoList(writer, (List<Map>) info.childInfoList, level + 1)
            } else {
                for (int i = 0; i < level; i++) writer.append('|').append(' ')
                writer.append("Reached depth limit, not printing children (may be a cycle in the 'tree')\n")
            }
        }
    }

    @Override
    String toString() {
        return "[name:'${name}',type:'${typeEnumId}',action:'${actionEnumId}',user:'${internalAuthorizedUserId}',authz:'${internalAuthorizedAuthzTypeId}',authAction:'${internalAuthorizedActionEnumId}',inheritable:${internalAuthorizationInheritable},runningTime:${getRunningTime()}]"
    }
}
