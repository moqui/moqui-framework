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
import org.moqui.entity.EntityValue;
import org.moqui.impl.StupidJavaUtilities;
import org.moqui.impl.entity.EntityValueBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Map;

public class ContextJavaUtil {
    protected final static Logger logger = LoggerFactory.getLogger(ContextJavaUtil.class);
    private static final long checkSlowThreshold = 50;
    protected static final double userImpactMinMillis = 1000;

    public static class ArtifactStatsInfo {
        private ArtifactExecutionInfo.ArtifactType artifactTypeEnum;
        private String artifactSubType;
        private String artifactName;
        public ArtifactBinInfo curHitBin = null;
        private long hitCount = 0L; // slowHitCount = 0L;
        private double totalTimeMillis = 0, totalSquaredTime = 0;

        ArtifactStatsInfo(ArtifactExecutionInfo.ArtifactType artifactTypeEnum, String artifactSubType, String artifactName) {
            this.artifactTypeEnum = artifactTypeEnum;
            this.artifactSubType = artifactSubType;
            this.artifactName = artifactName;
        }
        double getAverage() { return hitCount > 0 ? totalTimeMillis / hitCount : 0; }
        double getStdDev() {
            if (hitCount < 2) return 0;
            return Math.sqrt(Math.abs(totalSquaredTime - ((totalTimeMillis*totalTimeMillis) / hitCount)) / (hitCount - 1L));
        }
        public boolean countHit(long startTime, double runningTime) {
            hitCount++;
            boolean isSlow = hitCount > checkSlowThreshold && isHitSlow(runningTime);
            // if (isSlow) slowHitCount++;
            // do something funny with these so we get a better avg and std dev, leave out the first result (count 2nd
            //     twice) if first hit is more than 2x the second because the first hit is almost always MUCH slower
            if (hitCount == 2L && totalTimeMillis > (runningTime * 3)) {
                totalTimeMillis = runningTime * 2;
                totalSquaredTime = runningTime * runningTime * 2;
            } else {
                totalTimeMillis += runningTime;
                totalSquaredTime += runningTime * runningTime;
            }

            if (curHitBin == null) curHitBin = new ArtifactBinInfo(this, startTime);
            curHitBin.countHit(runningTime, isSlow);

            return isSlow;
        }
        boolean isHitSlow(double runningTime) {
            // calc new average and standard deviation
            double average = getAverage();
            double stdDev = getStdDev();

            // if runningTime is more than 2.6 std devs from the avg, count it and possibly log it
            // using 2.6 standard deviations because 2 would give us around 5% of hits (normal distro), shooting for more like 1%
            double slowTime = average + (stdDev * 2.6);
            if (slowTime != 0 && runningTime > slowTime) {
                if (runningTime > userImpactMinMillis)
                    logger.warn("Slow hit to " + artifactTypeEnum + ":" + artifactSubType + ":" + artifactName + " running time " + runningTime + " is greater than average " + average + " plus 2.6 standard deviations " + stdDev);
                return true;
            } else {
                return false;
            }
        }
    }

    public static class ArtifactBinInfo {
        private final ArtifactStatsInfo statsInfo;
        public final long startTime;

        private long hitCount = 0L, slowHitCount = 0L;
        private double totalTimeMillis = 0, totalSquaredTime = 0, minTimeMillis = Long.MAX_VALUE, maxTimeMillis = 0;

        ArtifactBinInfo(ArtifactStatsInfo statsInfo, long startTime) {
            this.statsInfo = statsInfo;
            this.startTime = startTime;
        }

        void countHit(double runningTime, boolean isSlow) {
            hitCount++;
            if (isSlow) slowHitCount++;

            if (hitCount == 2L && totalTimeMillis > (runningTime * 3)) {
                totalTimeMillis = runningTime * 2;
                totalSquaredTime = runningTime * runningTime * 2;
            } else {
                totalTimeMillis += runningTime;
                totalSquaredTime += runningTime * runningTime;
            }

            if (runningTime < minTimeMillis) minTimeMillis = runningTime;
            if (runningTime > maxTimeMillis) maxTimeMillis = runningTime;
        }

        // NOTE: ArtifactHitBin always created in DEFAULT tenant since data is aggregated across all tenants, mostly used to monitor performance
        EntityValue makeAhbValue(ExecutionContextFactoryImpl ecfi, Timestamp binEndDateTime) {
            EntityValueBase ahb = (EntityValueBase) ecfi.getEntityFacade("DEFAULT").makeValue("moqui.server.ArtifactHitBin");
            ahb.putNoCheck("artifactType", statsInfo.artifactTypeEnum.name());
            ahb.putNoCheck("artifactSubType", statsInfo.artifactSubType);
            ahb.putNoCheck("artifactName", statsInfo.artifactName);
            ahb.putNoCheck("binStartDateTime", new Timestamp(startTime));
            ahb.putNoCheck("binEndDateTime", binEndDateTime);
            ahb.putNoCheck("hitCount", hitCount);
            ahb.putNoCheck("totalTimeMillis", new BigDecimal(totalTimeMillis));
            ahb.putNoCheck("totalSquaredTime", new BigDecimal(totalSquaredTime));
            ahb.putNoCheck("minTimeMillis", new BigDecimal(minTimeMillis));
            ahb.putNoCheck("maxTimeMillis", new BigDecimal(maxTimeMillis));
            ahb.putNoCheck("slowHitCount", slowHitCount);
            ahb.putNoCheck("serverIpAddress", ecfi.localhostAddress != null ? ecfi.localhostAddress.getHostAddress() : "127.0.0.1");
            ahb.putNoCheck("serverHostName", ecfi.localhostAddress != null ? ecfi.localhostAddress.getHostName() : "localhost");
            return ahb;

        }
    }

    public static class ArtifactHitInfo {
        String tenantId, visitId, userId;
        boolean isSlowHit;
        ArtifactExecutionInfo.ArtifactType artifactTypeEnum;
        String artifactSubType, artifactName;
        long startTime;
        double runningTimeMillis;
        Map<String, Object> parameters;
        Long outputSize;
        String errorMessage = null;
        String requestUrl = null, referrerUrl = null;

        ArtifactHitInfo(ExecutionContextImpl eci, boolean isSlowHit, ArtifactExecutionInfo.ArtifactType artifactTypeEnum,
                        String artifactSubType, String artifactName, long startTime, double runningTimeMillis,
                        Map<String, Object> parameters, Long outputSize) {
            tenantId = eci.getTenantId();
            visitId = eci.getUserFacade().getVisitId();
            userId = eci.getUserFacade().getUserId();
            this.isSlowHit = isSlowHit;
            this.artifactTypeEnum = artifactTypeEnum;
            this.artifactSubType = artifactSubType;
            this.artifactName = artifactName;
            this.startTime = startTime;
            this.runningTimeMillis = runningTimeMillis;
            this.parameters = parameters;
            this.outputSize = outputSize;
            if (eci.getMessage().hasError()) {
                StringBuilder errorMessage = new StringBuilder();
                for (String curErr: eci.getMessage().getErrors()) errorMessage.append(curErr).append(";");
                if (errorMessage.length() > 255) errorMessage.delete(255, errorMessage.length());
                this.errorMessage = errorMessage.toString();
            }
            WebFacadeImpl wfi = eci.getWebImpl();
            if (wfi != null) {
                String fullUrl = wfi.getRequestUrl();
                requestUrl = (fullUrl.length() > 255) ? fullUrl.substring(0, 255) : fullUrl;
                referrerUrl = wfi.getRequest().getHeader("Referrer");
            }
        }
        EntityValue makeAhiValue(ExecutionContextFactoryImpl ecfi) {
            // NOTE: ArtifactHit saved in current tenant, ArtifactHitBin saved in DEFAULT tenant
            EntityValueBase ahp = (EntityValueBase) ecfi.getEntityFacade(tenantId).makeValue("moqui.server.ArtifactHit");
            ahp.putNoCheck("visitId", visitId);
            ahp.putNoCheck("userId", userId);
            ahp.putNoCheck("isSlowHit", isSlowHit ? 'Y' : 'N');
            ahp.putNoCheck("artifactType", artifactTypeEnum.name());
            ahp.putNoCheck("artifactSubType", artifactSubType);
            ahp.putNoCheck("artifactName", artifactName);
            ahp.putNoCheck("startDateTime", new Timestamp(startTime));
            ahp.putNoCheck("runningTimeMillis", new BigDecimal(runningTimeMillis));

            if (parameters != null && parameters.size() > 0) {
                StringBuilder ps = new StringBuilder();
                for (Map.Entry<String, Object> pme: parameters.entrySet()) {
                    Object value = pme.getValue();
                    if (StupidJavaUtilities.isEmpty(value)) continue;
                    String key = pme.getKey();
                    if (key != null && key.contains("password")) continue;
                    if (ps.length() > 0) ps.append(",");
                    ps.append(key).append("=").append(value);
                }
                if (ps.length() > 255) ps.delete(255, ps.length());
                ahp.putNoCheck("parameterString", ps.toString());
            }
            if (outputSize != null) ahp.putNoCheck("outputSize", outputSize);
            if (errorMessage != null) {
                ahp.putNoCheck("wasError", "Y");
                ahp.putNoCheck("errorMessage", errorMessage);
            } else {
                ahp.putNoCheck("wasError", "N");
            }
            if (requestUrl != null && requestUrl.length() > 0) ahp.putNoCheck("requestUrl", requestUrl);
            if (referrerUrl != null && referrerUrl.length() > 0) ahp.putNoCheck("referrerUrl", referrerUrl);

            ahp.putNoCheck("serverIpAddress", ecfi.localhostAddress != null ? ecfi.localhostAddress.getHostAddress() : "127.0.0.1");
            ahp.putNoCheck("serverHostName", ecfi.localhostAddress != null ? ecfi.localhostAddress.getHostName() : "localhost");

            return ahp;
        }
    }
}
