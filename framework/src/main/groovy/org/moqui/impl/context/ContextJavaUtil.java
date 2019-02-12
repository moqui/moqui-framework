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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import groovy.lang.GString;
import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.entity.EntityFind;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.entity.EntityValueBase;
import org.moqui.impl.screen.ScreenRenderImpl;
import org.moqui.util.ContextStack;
import org.moqui.util.ObjectUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.Synchronization;
import javax.transaction.Transaction;
import javax.transaction.xa.XAResource;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ContextJavaUtil {
    protected final static Logger logger = LoggerFactory.getLogger(ContextJavaUtil.class);
    private static final long checkSlowThreshold = 50;
    protected static final double userImpactMinMillis = 1000;

    /** the Groovy JsonBuilder doesn't handle various Moqui objects very well, ends up trying to access all
     * properties and results in infinite recursion, so need to unwrap and exclude some */
    public static Map<String, Object> unwrapMap(Map<String, Object> sourceMap) {
        Map<String, Object> targetMap = new HashMap<>();
        for (Map.Entry<String, Object> entry: sourceMap.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) continue;
            // logger.warn("======== actionsResult - ${entry.key} (${entry.value?.getClass()?.getName()}): ${entry.value}")
            Object unwrapped = unwrap(key, value);
            if (unwrapped != null) targetMap.put(key, unwrapped);
        }
        return targetMap;
    }

    @SuppressWarnings("unchecked")
    public static Object unwrap(String key, Object value) {
        if (value == null) return null;
        if (value instanceof CharSequence || value instanceof Number || value instanceof java.util.Date) {
            return value;
        } else if (value instanceof EntityFind || value instanceof ExecutionContextImpl ||
                value instanceof ScreenRenderImpl || value instanceof ContextStack) {
            // intentionally skip, commonly left in context by entity-find XML action
            return null;
        } else if (value instanceof EntityValue) {
            EntityValue ev = (EntityValue) value;
            return ev.getPlainValueMap(0);
        } else if (value instanceof EntityList) {
            EntityList el = (EntityList) value;
            ArrayList<Map> newList = new ArrayList<>();
            int elSize = el.size();
            for (int i = 0; i < elSize; i++) {
                EntityValue ev = el.get(i);
                newList.add(ev.getPlainValueMap(0));
            }
            return newList;
        } else if (value instanceof Collection) {
            Collection valCol = (Collection) value;
            ArrayList<Object> newList = new ArrayList<>(valCol.size());
            for (Object entry: valCol) newList.add(unwrap(key, entry));
            return newList;
        } else if (value instanceof Map) {
            Map<Object, Object> valMap = (Map) value;
            Map<Object, Object> newMap = new HashMap<>(valMap.size());
            for (Map.Entry entry: valMap.entrySet()) newMap.put(entry.getKey(), unwrap(key, entry.getValue()));
            return newMap;
        } else {
            logger.info("In screen actions skipping value from actions block that is not supported; key=" + key + ", type=" + value.getClass().getName() + ", value=" + value);
            return null;
        }
    }

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
            boolean isSlow = isHitSlow(runningTime);
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
            if (hitCount < checkSlowThreshold) return false;
            // calc new average and standard deviation
            double average = hitCount > 0 ? totalTimeMillis / hitCount : 0;
            double stdDev = Math.sqrt(Math.abs(totalSquaredTime - ((totalTimeMillis*totalTimeMillis) / hitCount)) / (hitCount - 1L));

            // if runningTime is more than 2.6 std devs from the avg, count it and possibly log it
            // using 2.6 standard deviations because 2 would give us around 5% of hits (normal distro), shooting for more like 1%
            double slowTime = average + (stdDev * 2.6);
            if (slowTime != 0 && runningTime > slowTime) {
                if (runningTime > userImpactMinMillis) logger.warn("Slow hit to " + artifactTypeEnum + ":" + artifactSubType +
                        ":" + artifactName + " running time " + runningTime + " is greater than average " + average +
                        " plus 2.6 standard deviations " + stdDev);
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

        EntityValue makeAhbValue(ExecutionContextFactoryImpl ecfi, Timestamp binEndDateTime) {
            EntityValueBase ahb = (EntityValueBase) ecfi.entityFacade.makeValue("moqui.server.ArtifactHitBin");
            ahb.putNoCheck("artifactType", statsInfo.artifactTypeEnum.name());
            ahb.putNoCheck("artifactSubType", statsInfo.artifactSubType);
            ahb.putNoCheck("artifactName", statsInfo.artifactName);
            ahb.putNoCheck("binStartDateTime", new Timestamp(startTime));
            ahb.putNoCheck("binEndDateTime", binEndDateTime);
            ahb.putNoCheck("hitCount", hitCount);
            // NOTE: use 6 digit precision for nanos in millisecond unit
            ahb.putNoCheck("totalTimeMillis", new BigDecimal(totalTimeMillis).setScale(6, RoundingMode.HALF_UP));
            ahb.putNoCheck("totalSquaredTime", new BigDecimal(totalSquaredTime).setScale(6, RoundingMode.HALF_UP));
            ahb.putNoCheck("minTimeMillis", new BigDecimal(minTimeMillis).setScale(6, RoundingMode.HALF_UP));
            ahb.putNoCheck("maxTimeMillis", new BigDecimal(maxTimeMillis).setScale(6, RoundingMode.HALF_UP));
            ahb.putNoCheck("slowHitCount", slowHitCount);
            ahb.putNoCheck("serverIpAddress", ecfi.localhostAddress != null ? ecfi.localhostAddress.getHostAddress() : "127.0.0.1");
            ahb.putNoCheck("serverHostName", ecfi.localhostAddress != null ? ecfi.localhostAddress.getHostName() : "localhost");
            return ahb;

        }
    }

    public static class ArtifactHitInfo {
        String visitId, userId;
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
            visitId = eci.userFacade.getVisitId();
            userId = eci.userFacade.getUserId();
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
            EntityValueBase ahp = (EntityValueBase) ecfi.entityFacade.makeValue("moqui.server.ArtifactHit");
            ahp.putNoCheck("visitId", visitId);
            ahp.putNoCheck("userId", userId);
            ahp.putNoCheck("isSlowHit", isSlowHit ? 'Y' : 'N');
            ahp.putNoCheck("artifactType", artifactTypeEnum.name());
            ahp.putNoCheck("artifactSubType", artifactSubType);
            ahp.putNoCheck("artifactName", artifactName);
            ahp.putNoCheck("startDateTime", new Timestamp(startTime));
            ahp.putNoCheck("runningTimeMillis", new BigDecimal(runningTimeMillis).setScale(6, RoundingMode.HALF_UP));

            if (parameters != null && parameters.size() > 0) {
                StringBuilder ps = new StringBuilder();
                for (Map.Entry<String, Object> pme: parameters.entrySet()) {
                    Object value = pme.getValue();
                    if (ObjectUtilities.isEmpty(value)) continue;
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

    static class RollbackInfo {
        public String causeMessage;
        /** A rollback is often done because of another error, this represents that error. */
        public Throwable causeThrowable;
        /** This is for a stack trace for where the rollback was actually called to help track it down more easily. */
        public Exception rollbackLocation;

        public RollbackInfo(String causeMessage, Throwable causeThrowable, Exception rollbackLocation) {
            this.causeMessage = causeMessage;
            this.causeThrowable = causeThrowable;
            this.rollbackLocation = rollbackLocation;
        }
    }

    static class TxStackInfo {
        public Exception transactionBegin = null;
        public Long transactionBeginStartTime = null;
        public RollbackInfo rollbackOnlyInfo = null;

        public Transaction suspendedTx = null;
        public Exception suspendedTxLocation = null;

        Map<String, XAResource> activeXaResourceMap = new LinkedHashMap<>();
        Map<String, Synchronization> activeSynchronizationMap = new LinkedHashMap<>();
        Map<String, ConnectionWrapper> txConByGroup = new HashMap<>();
        public TransactionCache txCache = null;

        public Map<String, XAResource> getActiveXaResourceMap() { return activeXaResourceMap; }
        public Map<String, Synchronization> getActiveSynchronizationMap() { return activeSynchronizationMap; }
        public Map<String, ConnectionWrapper> getTxConByGroup() { return txConByGroup; }


        public void clearCurrent() {
            rollbackOnlyInfo = null;
            transactionBegin = null;
            transactionBeginStartTime = null;
            activeXaResourceMap.clear();
            activeSynchronizationMap.clear();
            txCache = null;
            // this should already be done, but make sure
            closeTxConnections();
        }

        public void closeTxConnections() {
            for (ConnectionWrapper con: txConByGroup.values()) {
                try {
                    if (con != null && !con.isClosed()) con.closeInternal();
                } catch (Throwable t) {
                    logger.error("Error closing connection for group " + con.getGroupName(), t);
                }
            }
            txConByGroup.clear();
        }
    }

    /** A simple delegating wrapper for java.sql.Connection.
     *
     * The close() method does nothing, only closed when closeInternal() called by TransactionFacade on commit,
     * rollback, or destroy (when transactions are also cleaned up as a last resort).
     *
     * Connections are attached to 2 things: entity group and transaction.
     */
    public static class ConnectionWrapper implements Connection {
        protected Connection con;
        TransactionFacadeImpl tfi;
        String groupName;

        public ConnectionWrapper(Connection con, TransactionFacadeImpl tfi, String groupName) {
            this.con = con;
            this.tfi = tfi;
            this.groupName = groupName;
        }

        public String getGroupName() { return groupName; }

        public void closeInternal() throws SQLException {
            con.close();
        }

        @Override public Statement createStatement() throws SQLException { return con.createStatement(); }
        @Override public PreparedStatement prepareStatement(String sql) throws SQLException { return con.prepareStatement(sql); }
        @Override public CallableStatement prepareCall(String sql) throws SQLException { return con.prepareCall(sql); }
        @Override public String nativeSQL(String sql) throws SQLException { return con.nativeSQL(sql); }
        @Override public void setAutoCommit(boolean autoCommit) throws SQLException { con.setAutoCommit(autoCommit); }
        @Override public boolean getAutoCommit() throws SQLException { return con.getAutoCommit(); }
        @Override public void commit() throws SQLException { con.commit(); }
        @Override public void rollback() throws SQLException { con.rollback(); }

        @Override
        public void close() throws SQLException {
            // do nothing! see closeInternal
        }

        @Override public boolean isClosed() throws SQLException { return con.isClosed(); }
        @Override public DatabaseMetaData getMetaData() throws SQLException { return con.getMetaData(); }
        @Override public void setReadOnly(boolean readOnly) throws SQLException { con.setReadOnly(readOnly); }
        @Override public boolean isReadOnly() throws SQLException { return con.isReadOnly(); }
        @Override public void setCatalog(String catalog) throws SQLException { con.setCatalog(catalog); }
        @Override public String getCatalog() throws SQLException { return con.getCatalog(); }
        @Override public void setTransactionIsolation(int level) throws SQLException { con.setTransactionIsolation(level); }
        @Override public int getTransactionIsolation() throws SQLException { return con.getTransactionIsolation(); }
        @Override public SQLWarning getWarnings() throws SQLException { return con.getWarnings(); }
        @Override public void clearWarnings() throws SQLException { con.clearWarnings(); }

        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
            return con.createStatement(resultSetType, resultSetConcurrency); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return con.prepareStatement(sql, resultSetType, resultSetConcurrency); }
        @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return con.prepareCall(sql, resultSetType, resultSetConcurrency); }

        @Override public Map<String, Class<?>> getTypeMap() throws SQLException { return con.getTypeMap(); }
        @Override public void setTypeMap(Map<String, Class<?>> map) throws SQLException { con.setTypeMap(map); }
        @Override public void setHoldability(int holdability) throws SQLException { con.setHoldability(holdability); }
        @Override public int getHoldability() throws SQLException { return con.getHoldability(); }
        @Override public Savepoint setSavepoint() throws SQLException { return con.setSavepoint(); }
        @Override public Savepoint setSavepoint(String name) throws SQLException { return con.setSavepoint(name); }
        @Override public void rollback(Savepoint savepoint) throws SQLException { con.rollback(savepoint); }
        @Override public void releaseSavepoint(Savepoint savepoint) throws SQLException { con.releaseSavepoint(savepoint); }

        @Override public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return con.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return con.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return con.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability); }
        @Override public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
            return con.prepareStatement(sql, autoGeneratedKeys); }
        @Override public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
            return con.prepareStatement(sql, columnIndexes); }
        @Override public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
            return con.prepareStatement(sql, columnNames); }

        @Override public Clob createClob() throws SQLException { return con.createClob(); }
        @Override public Blob createBlob() throws SQLException { return con.createBlob(); }
        @Override public NClob createNClob() throws SQLException { return con.createNClob(); }
        @Override public SQLXML createSQLXML() throws SQLException { return con.createSQLXML(); }
        @Override public boolean isValid(int timeout) throws SQLException { return con.isValid(timeout); }
        @Override public void setClientInfo(String name, String value) throws SQLClientInfoException { con.setClientInfo(name, value); }
        @Override public void setClientInfo(Properties properties) throws SQLClientInfoException { con.setClientInfo(properties); }
        @Override public String getClientInfo(String name) throws SQLException { return con.getClientInfo(name); }
        @Override public Properties getClientInfo() throws SQLException { return con.getClientInfo(); }
        @Override public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
            return con.createArrayOf(typeName, elements); }
        @Override public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
            return con.createStruct(typeName, attributes); }

        @Override public void setSchema(String schema) throws SQLException { con.setSchema(schema); }
        @Override public String getSchema() throws SQLException { return con.getSchema(); }

        @Override public void abort(Executor executor) throws SQLException { con.abort(executor); }
        @Override public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
            con.setNetworkTimeout(executor, milliseconds); }
        @Override public int getNetworkTimeout() throws SQLException { return con.getNetworkTimeout(); }

        @Override public <T> T unwrap(Class<T> iface) throws SQLException { return con.unwrap(iface); }
        @Override public boolean isWrapperFor(Class<?> iface) throws SQLException { return con.isWrapperFor(iface); }

        // Object overrides
        @Override public int hashCode() { return con.hashCode(); }
        @Override public boolean equals(Object obj) { return obj instanceof Connection && con.equals(obj); }
        @Override public String toString() {
            return "Group: " + groupName + ", Con: " + con.toString();
        }
        /* these don't work, don't think we need them anyway:
        protected Object clone() throws CloneNotSupportedException {
            return new ConnectionWrapper((Connection) con.clone(), tfi, groupName) }
        protected void finalize() throws Throwable { con.finalize() }
        */
    }


    public final static ObjectMapper jacksonMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
    static {
        // Jackson custom serializers, etc
        SimpleModule module = new SimpleModule();
        module.addSerializer(GString.class, new ContextJavaUtil.GStringJsonSerializer());
        jacksonMapper.registerModule(module);
    }
    static class GStringJsonSerializer extends StdSerializer<GString> {
        GStringJsonSerializer() { super(GString.class); }
        @Override public void serialize(GString value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException, JsonProcessingException { if (value != null) gen.writeString(value.toString()); }
    }

    // NOTE: using unbound LinkedBlockingQueue, so max pool size in ThreadPoolExecutor has no effect
    static class WorkerThreadFactory implements ThreadFactory {
        private final ThreadGroup workerGroup = new ThreadGroup("MoquiWorkers");
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        public Thread newThread(Runnable r) { return new Thread(workerGroup, r, "MoquiWorker-" + threadNumber.getAndIncrement()); }
    }
    static class WorkerThreadPoolExecutor extends ThreadPoolExecutor {
        private ExecutionContextFactoryImpl ecfi;
        public WorkerThreadPoolExecutor(ExecutionContextFactoryImpl ecfi, int coreSize, int maxSize, long aliveTime,
                                 TimeUnit timeUnit, BlockingQueue<Runnable> blockingQueue) {
            super(coreSize, maxSize, aliveTime, timeUnit, blockingQueue, new WorkerThreadFactory());
            this.ecfi = ecfi;
        }

        @Override protected void afterExecute(Runnable runnable, Throwable throwable) {
            ExecutionContextImpl activeEc = ecfi.activeContext.get();
            if (activeEc != null) {
                logger.warn("In WorkerThreadPoolExecutor.afterExecute() there is still an ExecutionContext for runnable " + runnable.getClass().getName() + " in thread (" + Thread.currentThread().getId() + ":" + Thread.currentThread().getName() + "), destroying");
                try {
                    activeEc.destroy();
                } catch (Throwable t) {
                    logger.error("Error destroying ExecutionContext in WorkerThreadPoolExecutor.afterExecute()", t);
                }
            }

            super.afterExecute(runnable, throwable);
        }
    }
}
