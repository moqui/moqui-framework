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
package org.moqui.impl.util

import groovy.transform.CompileStatic
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.util.ReadOnlyStringMap
import org.moqui.BaseArtifactException
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.LogEventSubscriber
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.PostgresElasticClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.sql.Types
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PostgreSQL-backed application log appender (replaces ElasticSearchLogger for postgres clusters).
 *
 * Consumes LogEvent objects from a queue and batch-inserts them into the moqui_logs table.
 * The queue is flushed every 3 seconds by a scheduled task, identical to ElasticSearchLogger behaviour.
 */
@CompileStatic
class PostgresSearchLogger {
    private final static Logger logger = LoggerFactory.getLogger(PostgresSearchLogger.class)

    final static int QUEUE_LIMIT = 16384

    private final PostgresElasticClient pgClient
    private final ExecutionContextFactoryImpl ecfi

    private boolean initialized = false
    private volatile boolean disabled = false

    final ConcurrentLinkedQueue<Map> logMessageQueue = new ConcurrentLinkedQueue<>()
    final AtomicBoolean flushRunning = new AtomicBoolean(false)

    protected PgLogSubscriber subscriber = null

    PostgresSearchLogger(PostgresElasticClient pgClient, ExecutionContextFactoryImpl ecfi) {
        this.pgClient = pgClient
        this.ecfi = ecfi
        init()
    }

    void init() {
        // moqui_logs table is created by PostgresElasticClient.initSchema() — no extra setup needed

        // Schedule flush every 3 seconds (same cadence as ElasticSearchLogger)
        PgLogQueueFlush flushTask = new PgLogQueueFlush(this)
        ecfi.scheduleAtFixedRate(flushTask, 10, 3)

        subscriber = new PgLogSubscriber(this)
        ecfi.registerLogEventSubscriber(subscriber)

        initialized = true
        logger.info("PostgresSearchLogger initialized for cluster '${pgClient.clusterName}'")
    }

    void destroy() { disabled = true }

    boolean isInitialized() { return initialized }

    // ============================================================
    // Log subscriber — mirrors ElasticSearchSubscriber
    // ============================================================

    static class PgLogSubscriber implements LogEventSubscriber {
        private final PostgresSearchLogger pgLogger
        private final InetAddress localAddr = InetAddress.getLocalHost()

        PgLogSubscriber(PostgresSearchLogger pgLogger) { this.pgLogger = pgLogger }

        @Override
        void process(LogEvent event) {
            if (pgLogger.disabled) return
            // Suppress DEBUG / TRACE (same rule as ElasticSearchLogger)
            if (Level.DEBUG.is(event.level) || Level.TRACE.is(event.level)) return
            // Back-pressure: if queue too full, drop the oldest-style (newest is not enqueued)
            if (pgLogger.logMessageQueue.size() >= QUEUE_LIMIT) return

            Map<String, Object> msgMap = [
                    '@timestamp'   : event.timeMillis,
                    level          : event.level.toString(),
                    thread_name    : event.threadName,
                    thread_id      : event.threadId,
                    thread_priority: event.threadPriority,
                    logger_name    : event.loggerName,
                    message        : event.message?.formattedMessage,
                    source_host    : localAddr.hostName
            ] as Map<String, Object>

            ReadOnlyStringMap contextData = event.contextData
            if (contextData != null && contextData.size() > 0) {
                Map<String, String> mdcMap = new HashMap<>(contextData.toMap())
                String userId = mdcMap.remove("moqui_userId")
                String visitorId = mdcMap.remove("moqui_visitorId")
                if (userId) msgMap.put("user_id", userId)
                if (visitorId) msgMap.put("visitor_id", visitorId)
                if (mdcMap.size() > 0) msgMap.put("mdc", mdcMap)
            }
            Throwable thrown = event.thrown
            if (thrown != null) msgMap.put("thrown", ElasticSearchLogger.ElasticSearchSubscriber.makeThrowableMap(thrown))

            pgLogger.logMessageQueue.add(msgMap)
        }
    }

    // ============================================================
    // Scheduled flush task — drains queue into moqui_logs via JDBC
    // ============================================================

    static class PgLogQueueFlush implements Runnable {
        private final static int MAX_BATCH = 200

        private final PostgresSearchLogger pgLogger

        PgLogQueueFlush(PostgresSearchLogger pgLogger) { this.pgLogger = pgLogger }

        @Override
        void run() {
            if (!pgLogger.flushRunning.compareAndSet(false, true)) return
            try {
                while (pgLogger.logMessageQueue.size() > 0) { flushQueue() }
            } finally {
                pgLogger.flushRunning.set(false)
            }
        }

        void flushQueue() {
            final ConcurrentLinkedQueue<Map> queue = pgLogger.logMessageQueue
            List<Map> batch = new ArrayList<>(MAX_BATCH)
            long lastTs = 0L
            int sameCount = 0

            while (batch.size() < MAX_BATCH) {
                Map msg = queue.poll()
                if (msg == null) break
                // Ensure unique timestamps (same as ES logger behaviour)
                long ts = (msg.get("@timestamp") as long) ?: System.currentTimeMillis()
                if (ts == lastTs) {
                    sameCount++
                    ts += sameCount
                    msg.put("@timestamp", ts)
                } else {
                    lastTs = ts
                    sameCount = 0
                }
                batch.add(msg)
            }

            if (batch.isEmpty()) return

            int retries = 3
            while (retries-- > 0) {
                try {
                    writeBatch(batch)
                    return
                } catch (Throwable t) {
                    System.out.println("PostgresSearchLogger: error writing log batch, retries left ${retries}: ${t}")
                    if (retries == 0) System.out.println("PostgresSearchLogger: dropping ${batch.size()} log records after repeated failures")
                }
            }
        }

        private void writeBatch(List<Map> batch) {
            boolean txStarted = pgLogger.ecfi.transactionFacade.begin(null)
            try {
                Connection conn = pgLogger.ecfi.entityFacade.getConnection(pgLogger.pgClient.datasourceGroup)
                PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO moqui_logs (log_timestamp, log_level, thread_name, thread_id, thread_priority,
                        logger_name, message, source_host, user_id, visitor_id, mdc, thrown)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """.trim())
                try {
                    for (Map msg in batch) {
                        // Timestamp: stored as epoch_millis long in the map
                        long tsMillis = (msg.get("@timestamp") as long) ?: System.currentTimeMillis()
                        ps.setTimestamp(1, new Timestamp(tsMillis))
                        setStr(ps, 2, msg.get("level") as String)
                        setStr(ps, 3, msg.get("thread_name") as String)
                        setLong(ps, 4, msg.get("thread_id") as Long)
                        setInt(ps, 5, msg.get("thread_priority") as Integer)
                        setStr(ps, 6, msg.get("logger_name") as String)
                        setStr(ps, 7, msg.get("message") as String)
                        setStr(ps, 8, msg.get("source_host") as String)
                        setStr(ps, 9, msg.get("user_id") as String)
                        setStr(ps, 10, msg.get("visitor_id") as String)
                        // mdc and thrown as JSONB
                        Object mdcObj = msg.get("mdc")
                        setJsonb(ps, 11, mdcObj)
                        Object thrownObj = msg.get("thrown")
                        setJsonb(ps, 12, thrownObj)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                } finally { ps.close() }
                pgLogger.ecfi.transactionFacade.commit(txStarted)
            } catch (Throwable t) {
                pgLogger.ecfi.transactionFacade.rollback(txStarted, "Error writing log batch to moqui_logs", t)
                throw t
            }
        }

        private static void setStr(PreparedStatement ps, int i, String v) {
            if (v == null) ps.setNull(i, Types.VARCHAR) else ps.setString(i, v)
        }
        private static void setLong(PreparedStatement ps, int i, Long v) {
            if (v == null) ps.setNull(i, Types.BIGINT) else ps.setLong(i, v)
        }
        private static void setInt(PreparedStatement ps, int i, Integer v) {
            if (v == null) ps.setNull(i, Types.INTEGER) else ps.setInt(i, v)
        }
        private static void setJsonb(PreparedStatement ps, int i, Object v) {
            if (v == null) {
                ps.setNull(i, Types.OTHER)
            } else {
                try {
                    ps.setString(i, PostgresElasticClient.jacksonMapper.writeValueAsString(v))
                } catch (Throwable t) {
                    ps.setNull(i, Types.OTHER)
                }
            }
        }
    }
}
