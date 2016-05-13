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
package org.moqui.impl.context;

import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.impl.StupidUtilities;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public class ArtifactExecutionInfoImpl implements ArtifactExecutionInfo {

    // NOTE: these need to be in a Map instead of the DB because Enumeration records may not yet be loaded
    final static Map<String, String> artifactTypeDescriptionMap = new HashMap<>();
    final static Map<String, String> artifactActionDescriptionMap = new HashMap<>();
    static {
        artifactTypeDescriptionMap.put("AT_XML_SCREEN", "Screen"); artifactTypeDescriptionMap.put("AT_XML_SCREEN_TRANS", "Transition");
        artifactTypeDescriptionMap.put("AT_SERVICE", "Service"); artifactTypeDescriptionMap.put("AT_ENTITY", "Entity");

        artifactActionDescriptionMap.put("AUTHZA_VIEW", "View"); artifactActionDescriptionMap.put("AUTHZA_CREATE", "Create");
        artifactActionDescriptionMap.put("AUTHZA_UPDATE", "Update"); artifactActionDescriptionMap.put("AUTHZA_DELETE", "Delete");
        artifactActionDescriptionMap.put("AUTHZA_ALL", "All");
    }

    public final String nameInternal;
    public final String typeEnumId;
    public final String internalActionEnumId;
    protected String actionDetail = "";
    protected Map<String, Object> parameters = null;
    public String internalAuthorizedUserId = null;
    public String internalAuthorizedAuthzTypeId = null;
    public String internalAuthorizedActionEnumId = null;
    public boolean internalAuthorizationInheritable = false;
    Boolean internalAuthzWasRequired = null;
    Boolean internalAuthzWasGranted = null;
    public Map<String, Object> internalAacv = null;

    //protected Exception createdLocation = null
    private ArtifactExecutionInfoImpl parentAeii = (ArtifactExecutionInfoImpl) null;
    protected long startTime;
    private long endTime = 0;
    private ArrayList<ArtifactExecutionInfoImpl> childList = new ArrayList<>();
    private long childrenRunningTime = 0;

    public ArtifactExecutionInfoImpl(String name, String typeEnumId, String actionEnumId) {
        nameInternal = name;
        this.typeEnumId = typeEnumId;
        this.internalActionEnumId = actionEnumId != null && actionEnumId.length() > 0 ? actionEnumId : "AUTHZA_ALL";
        //createdLocation = new Exception("Create AEII location for ${name}, type ${typeEnumId}, action ${actionEnumId}")
        this.startTime = System.nanoTime();
    }

    public ArtifactExecutionInfoImpl setActionDetail(String detail) { this.actionDetail = detail; return this; }
    public ArtifactExecutionInfoImpl setParameters(Map<String, Object> parameters) { this.parameters = parameters; return this; }

    @Override
    public String getName() { return nameInternal; }

    @Override
    public String getTypeEnumId() { return typeEnumId; }
    String getTypeDescription() {
        String desc = artifactTypeDescriptionMap.get(typeEnumId);
        return desc != null ? desc : typeEnumId;
    }

    @Override
    public String getActionEnumId() { return internalActionEnumId; }
    String getActionDescription() {
        String desc = artifactActionDescriptionMap.get(internalActionEnumId);
        return desc != null ? desc : internalActionEnumId;
    }

    @Override
    public String getAuthorizedUserId() { return internalAuthorizedUserId; }
    void setAuthorizedUserId(String authorizedUserId) { this.internalAuthorizedUserId = authorizedUserId; }

    @Override
    public String getAuthorizedAuthzTypeId() { return internalAuthorizedAuthzTypeId; }
    void setAuthorizedAuthzTypeId(String authorizedAuthzTypeId) { this.internalAuthorizedAuthzTypeId = authorizedAuthzTypeId; }

    @Override
    public String getAuthorizedActionEnumId() { return internalAuthorizedActionEnumId; }
    void setAuthorizedActionEnumId(String authorizedActionEnumId) { this.internalAuthorizedActionEnumId = authorizedActionEnumId; }

    @Override
    public boolean isAuthorizationInheritable() { return internalAuthorizationInheritable; }
    void setAuthorizationInheritable(boolean isAuthorizationInheritable) { this.internalAuthorizationInheritable = isAuthorizationInheritable; }

    @Override
    public Boolean getAuthorizationWasRequired() { return internalAuthzWasRequired; }
    void setAuthorizationWasRequired(boolean value) { internalAuthzWasRequired = value; }
    @Override
    public Boolean getAuthorizationWasGranted() { return internalAuthzWasGranted; }
    void setAuthorizationWasGranted(boolean value) { internalAuthzWasGranted = value; }

    Map<String, Object> getAacv() { return internalAacv; }

    public void copyAacvInfo(Map<String, Object> aacv, String userId, boolean wasGranted) {
        internalAacv = aacv;
        internalAuthorizedUserId = userId;
        internalAuthorizedAuthzTypeId = (String) aacv.get("authzTypeEnumId");
        internalAuthorizedActionEnumId = (String) aacv.get("authzActionEnumId");
        internalAuthorizationInheritable = "Y".equals(aacv.get("inheritAuthz"));
        internalAuthzWasGranted = wasGranted;
    }

    public void copyAuthorizedInfo(ArtifactExecutionInfoImpl aeii) {
        internalAacv = aeii.internalAacv;
        internalAuthorizedUserId = aeii.internalAuthorizedUserId;
        internalAuthorizedAuthzTypeId = aeii.internalAuthorizedAuthzTypeId;
        internalAuthorizedActionEnumId = aeii.internalAuthorizedActionEnumId;
        internalAuthorizationInheritable = aeii.internalAuthorizationInheritable;
        // NOTE: don't copy internalAuthzWasRequired, always set in isPermitted()
        internalAuthzWasGranted = aeii.internalAuthzWasGranted;
    }

    void setEndTime() { this.endTime = System.nanoTime(); }
    @Override
    public long getRunningTime() { return endTime != 0 ? endTime - startTime : 0; }
    void calcChildTime(boolean recurse) {
        childrenRunningTime = 0;
        for (ArtifactExecutionInfoImpl aeii: childList) {
            childrenRunningTime += aeii.getRunningTime();
            if (recurse) aeii.calcChildTime(true);
        }
    }
    @Override
    public long getThisRunningTime() { return getRunningTime() - getChildrenRunningTime(); }
    @Override
    public long getChildrenRunningTime() {
        if (childrenRunningTime == 0) calcChildTime(false);
        return childrenRunningTime;
    }

    public BigDecimal getRunningTimeMillis() { return new BigDecimal(getRunningTime()).movePointLeft(6).setScale(2, RoundingMode.HALF_UP); }
    public BigDecimal getThisRunningTimeMillis() { return new BigDecimal(getThisRunningTime()).movePointLeft(6).setScale(2, RoundingMode.HALF_UP); }
    public BigDecimal getChildrenRunningTimeMillis() { return new BigDecimal(getChildrenRunningTime()).movePointLeft(6).setScale(2, RoundingMode.HALF_UP); }

    void setParent(ArtifactExecutionInfoImpl parentAeii) { this.parentAeii = parentAeii; }
    @Override
    public ArtifactExecutionInfo getParent() { return parentAeii; }
    @Override
    public BigDecimal getPercentOfParentTime() { return parentAeii != null && endTime != 0 ?
        new BigDecimal((getRunningTime() / parentAeii.getRunningTime()) * 100).setScale(2, BigDecimal.ROUND_HALF_UP) : BigDecimal.ZERO; }


    void addChild(ArtifactExecutionInfoImpl aeii) { childList.add(aeii); }
    @Override
    public List<ArtifactExecutionInfo> getChildList() {
        List<ArtifactExecutionInfo> newChildList = new ArrayList<>();
        newChildList.addAll(childList);
        return newChildList;
    }

    public void print(Writer writer, int level, boolean children) {
        try {
            for (int i = 0; i < (level * 2); i++) writer.append(" ");
            writer.append("[").append(parentAeii != null ? StupidUtilities.paddedString(getPercentOfParentTime().toPlainString(), 5, false) : "     ").append("%]");
            writer.append("[").append(StupidUtilities.paddedString(getRunningTimeMillis().toPlainString(), 5, false)).append("]");
            writer.append("[").append(StupidUtilities.paddedString(getThisRunningTimeMillis().toPlainString(), 3, false)).append("]");
            writer.append("[").append(childList != null ? StupidUtilities.paddedString(getChildrenRunningTimeMillis().toPlainString(), 3, false) : "   ").append("] ");
            writer.append(StupidUtilities.paddedString(getTypeDescription(), 10, true)).append(" ");
            writer.append(StupidUtilities.paddedString(getActionDescription(), 7, true)).append(" ");
            writer.append(StupidUtilities.paddedString(actionDetail, 5, true)).append(" ");
            writer.append(nameInternal).append("\n");

            if (children) for (ArtifactExecutionInfoImpl aeii: childList) aeii.print(writer, level + 1, true);
        } catch (IOException e) { }
    }

    String getKeyString() { return nameInternal + ":" + typeEnumId + ":" + internalActionEnumId + ":" + actionDetail; }

    static List<Map<String, Object>> hotSpotByTime(List<ArtifactExecutionInfoImpl> aeiiList, boolean ownTime, String orderBy) {
        Map<String, Map<String, Object>> timeByArtifact = new LinkedHashMap<>();
        for (ArtifactExecutionInfoImpl aeii: aeiiList) aeii.addToMapByTime(timeByArtifact, ownTime);
        List<Map<String, Object>> hotSpotList = new LinkedList<>();
        hotSpotList.addAll(timeByArtifact.values());

        // in some cases we get REALLY long times before the system is warmed, knock those out
        for (Map<String, Object> val: hotSpotList) {
            int knockOutCount = 0;
            List<BigDecimal> newTimes = new LinkedList<>();
            BigDecimal timeAvg = (BigDecimal) val.get("timeAvg");
            for (BigDecimal time: (List<BigDecimal>) val.get("times")) {
                // this ain"t no standard deviation, but consider 3 times average to be abnormal
                if (time.floatValue() > (timeAvg.floatValue() * 3)) {
                    knockOutCount++;
                } else {
                    newTimes.add(time);
                }
            }
            if (knockOutCount > 0 && newTimes.size() > 0) {
                // calc new average, add knockOutCount times to fill in gaps, calc new time total
                BigDecimal newTotal = BigDecimal.ZERO;
                BigDecimal newMax = BigDecimal.ZERO;
                for (BigDecimal time: newTimes) { newTotal.add(time); if (time.compareTo(newMax) > 0) newMax = time; }
                BigDecimal newAvg = newTotal.divide(new BigDecimal(newTimes.size()), 2, BigDecimal.ROUND_HALF_UP);
                // long newTimeAvg = newAvg.setScale(0, BigDecimal.ROUND_HALF_UP)
                newTotal = newTotal.add(newAvg.multiply(new BigDecimal(knockOutCount)));
                val.put("time", newTotal);
                val.put("timeMax", newMax);
                val.put("timeAvg", newAvg);
            }
        }

        List<String> obList = new LinkedList<>();
        if (orderBy != null && orderBy.length() > 0) obList.add(orderBy); else obList.add("-time");
        StupidUtilities.orderMapList(hotSpotList, obList);
        return hotSpotList;
    }
    void addToMapByTime(Map<String, Map<String, Object>> timeByArtifact, boolean ownTime) {
        String key = getKeyString();
        Map<String, Object> val = timeByArtifact.get(key);
        BigDecimal curTime = ownTime ? getThisRunningTimeMillis() : getRunningTimeMillis();
        if (val == null) {
            Map<String, Object> newMap = new LinkedHashMap<>();
            List<BigDecimal> timesList = new LinkedList<>();
            timesList.add(curTime);
            newMap.put("times", timesList); newMap.put("time", curTime); newMap.put("timeMin", curTime);
            newMap.put("timeMax", curTime); newMap.put("timeAvg", curTime); newMap.put("count", BigDecimal.ONE);
            newMap.put("name", nameInternal); newMap.put("actionDetail", actionDetail);
            newMap.put("type", getTypeDescription()); newMap.put("action", getActionDescription());
            timeByArtifact.put(key, newMap);
        } else {
            val = timeByArtifact.get(key);
            BigDecimal newCount = ((BigDecimal) val.get("count")).add(BigDecimal.ONE);
            val.put("count", newCount);
            if (newCount.intValue() == 2 && ((List<BigDecimal>) val.get("times")).get(0).compareTo(curTime.multiply(new BigDecimal(3))) > 0) {
                // if the first is much higher than the 2nd, use the 2nd for both
                List<BigDecimal> timesList = new LinkedList<>();
                timesList.add(curTime); timesList.add(curTime);
                val.put("times", timesList);
                val.put("time", curTime.add(curTime)); val.put("timeMin", curTime);
                val.put("timeMax", curTime); val.put("timeAvg", curTime);
            } else {
                ((List<BigDecimal>) val.get("times")).add(curTime);
                val.put("time", ((BigDecimal) val.get("time")).add(curTime));
                val.put("timeMin", ((BigDecimal) val.get("timeMin")).compareTo(curTime) > 0 ? curTime : (BigDecimal) val.get("timeMin"));
                val.put("timeMax", ((BigDecimal) val.get("timeMax")).compareTo(curTime) > 0 ? (BigDecimal) val.get("timeMax") : curTime);
                val.put("timeAvg", ((BigDecimal) val.get("time")).divide((BigDecimal) val.get("count"), 2, BigDecimal.ROUND_HALF_UP));
            }
        }
        for (ArtifactExecutionInfoImpl aeii: childList) aeii.addToMapByTime(timeByArtifact, ownTime);
    }
    static void printHotSpotList(Writer writer, List<Map> infoList) throws IOException {
        // "[${time}:${timeMin}:${timeAvg}:${timeMax}][${count}] ${type} ${action} ${actionDetail} ${name}"
        for (Map info: infoList) {
            writer.append("[").append(StupidUtilities.paddedString(((BigDecimal) info.get("time")).toPlainString(), 8, false)).append(":");
            writer.append(StupidUtilities.paddedString(((BigDecimal) info.get("timeMin")).toPlainString(), 7, false)).append(":");
            writer.append(StupidUtilities.paddedString(((BigDecimal) info.get("timeAvg")).toPlainString(), 7, false)).append(":");
            writer.append(StupidUtilities.paddedString(((BigDecimal) info.get("timeMax")).toPlainString(), 7, false)).append("]");
            writer.append("[").append(StupidUtilities.paddedString(((BigDecimal) info.get("count")).toPlainString(), 4, false)).append("] ");
            writer.append(StupidUtilities.paddedString((String) info.get("type"), 10, true)).append(" ");
            writer.append(StupidUtilities.paddedString((String) info.get("action"), 7, true)).append(" ");
            writer.append(StupidUtilities.paddedString((String) info.get("actionDetail"), 5, true)).append(" ");
            writer.append((String) info.get("name")).append("\n");
        }
    }


    static List<Map> consolidateArtifactInfo(List<ArtifactExecutionInfoImpl> aeiiList) {
        List<Map> topLevelList = new LinkedList<>();
        Map<String, Map<String, Object>> flatMap = new LinkedHashMap<>();
        for (ArtifactExecutionInfoImpl aeii: aeiiList) aeii.consolidateArtifactInfo(topLevelList, flatMap, null);
        return topLevelList;
    }
    void consolidateArtifactInfo(List<Map> topLevelList, Map<String, Map<String, Object>> flatMap, Map parentArtifactMap) {
        String key = getKeyString();
        Map<String, Object> artifactMap = flatMap.get(key);
        if (artifactMap == null) {
            artifactMap = new LinkedHashMap<>();
            artifactMap.put("time", getRunningTimeMillis()); artifactMap.put("thisTime", getThisRunningTimeMillis());
            artifactMap.put("childrenTime", getChildrenRunningTimeMillis()); artifactMap.put("count", BigDecimal.ONE);
            artifactMap.put("name", nameInternal); artifactMap.put("actionDetail", actionDetail);
            artifactMap.put("childInfoList", new LinkedList()); artifactMap.put("key", key);
            artifactMap.put("type", getTypeDescription()); artifactMap.put("action", getActionDescription());
            flatMap.put(key, artifactMap);
            if (parentArtifactMap != null) {
                ((List) parentArtifactMap.get("childInfoList")).add(artifactMap);
            } else {
                topLevelList.add(artifactMap);
            }
        } else {
            artifactMap.put("count", ((BigDecimal) artifactMap.get("count")).add(BigDecimal.ONE));
            artifactMap.put("time", ((BigDecimal) artifactMap.get("time")).add(getRunningTimeMillis()));
            artifactMap.put("thisTime", ((BigDecimal) artifactMap.get("thisTime")).add(getThisRunningTimeMillis()));
            artifactMap.put("childrenTime", ((BigDecimal) artifactMap.get("childrenTime")).add(getChildrenRunningTimeMillis()));
            if (parentArtifactMap != null) {
                // is the current artifact in the current parent"s child list? if not add it (a given artifact may be under multiple parents, normal)
                boolean foundMap = false;
                for (Map candidate: (List<Map>) parentArtifactMap.get("childInfoList")) if (key.equals(candidate.get("key"))) { foundMap = true; break; }
                if (!foundMap) ((List) parentArtifactMap.get("childInfoList")).add(artifactMap);
            }
        }

        for (ArtifactExecutionInfoImpl aeii: childList) aeii.consolidateArtifactInfo(topLevelList, flatMap, artifactMap);
    }
    static String printArtifactInfoList(List<Map> infoList) throws IOException {
        StringWriter sw = new StringWriter();
        printArtifactInfoList(sw, infoList, 0);
        return sw.toString();
    }
    static void printArtifactInfoList(Writer writer, List<Map> infoList, int level) throws IOException {
        // "[${time}:${thisTime}:${childrenTime}][${count}] ${type} ${action} ${actionDetail} ${name}"
        for (Map info: infoList) {
            for (int i = 0; i < level; i++) writer.append("|").append(" ");
            writer.append("[").append(StupidUtilities.paddedString(((BigDecimal) info.get("time")).toPlainString(), 8, false)).append(":");
            writer.append(StupidUtilities.paddedString(((BigDecimal) info.get("thisTime")).toPlainString(), 6, false)).append(":");
            writer.append(StupidUtilities.paddedString(((BigDecimal) info.get("childrenTime")).toPlainString(), 6, false)).append("]");
            writer.append("[").append(StupidUtilities.paddedString(((BigDecimal) info.get("count")).toPlainString(), 4, false)).append("] ");
            writer.append(StupidUtilities.paddedString((String) info.get("type"), 10, true)).append(" ");
            writer.append(StupidUtilities.paddedString((String) info.get("action"), 7, true)).append(" ");
            writer.append(StupidUtilities.paddedString((String) info.get("actionDetail"), 5, true)).append(" ");
            writer.append((String) info.get("name")).append("\n");
            // if we get past level 25 just give up, probably a loop in the tree
            if (level < 25) {
                printArtifactInfoList(writer, (List<Map>) info.get("childInfoList"), level + 1);
            } else {
                for (int i = 0; i < level; i++) writer.append("|").append(" ");
                writer.append("Reached depth limit, not printing children (may be a cycle in the 'tree')\n");
            }
        }
    }

    @Override
    public String toString() {
        return "[name:'" + nameInternal + "', type:'" + typeEnumId + "', action:'" + internalActionEnumId + "', required: " + internalAuthzWasRequired + ", granted:" + internalAuthzWasGranted + ", user:'" + internalAuthorizedUserId + "', authz:'" + internalAuthorizedAuthzTypeId + "', authAction:'" + internalAuthorizedActionEnumId + "', inheritable:" + internalAuthorizationInheritable + ", runningTime:" + getRunningTime() + "]";
    }
}
