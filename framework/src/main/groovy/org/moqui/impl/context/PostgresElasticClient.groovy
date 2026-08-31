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

import com.fasterxml.jackson.databind.ObjectMapper
import groovy.transform.CompileStatic
import org.moqui.BaseException
import org.moqui.context.ElasticFacade
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityList
import org.moqui.util.MNode
import org.moqui.util.RestClient
import org.moqui.util.RestClient.Method
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.sql.Timestamp
import java.sql.Types
import java.util.concurrent.Future
import java.util.concurrent.ConcurrentHashMap

/**
 * PostgreSQL-backed implementation of ElasticFacade.ElasticClient.
 *
 * Stores and searches documents using:
 *   - moqui_search_index table — tracks index metadata (replaces ES index/alias management)
 *   - moqui_document table — stores documents as JSONB with tsvector for full-text search
 *
 * All ElasticSearch Query DSL is translated to PostgreSQL SQL by ElasticQueryTranslator.
 * Application logs go to moqui_logs table; HTTP request logs go to moqui_http_log table.
 *
 * Configured via MoquiConf.xml elastic-facade.cluster with type="postgres".
 * Example:
 *   &lt;cluster name="default" type="postgres" url="transactional" index-prefix="mq_"/&gt;
 */
@CompileStatic
// Issue #10 in PROBLEMS_RESOLUTION_PLAN.md: @CompileStatic here is intentional and compiles/runs cleanly —
// all GString SQL interpolation and Map/LinkedHashMap usage in this class are fully static-compile
// compatible; keep it for the type-safety/performance benefit rather than removing it for "dynamic" code.
class PostgresElasticClient implements ElasticFacade.ElasticClient {
    private final static Logger logger = LoggerFactory.getLogger(PostgresElasticClient.class)
    private final static Set<String> DOC_META_KEYS = new HashSet<>(["_index", "_type", "_id", "_timestamp"])

    /** Index names that map to the dedicated moqui_http_log table */
    private static final Set<String> HTTP_LOG_INDEX_NAMES = new HashSet<>(["moqui_http_log"])
    /** Index names that map to the dedicated moqui_logs table */
    private static final Set<String> APP_LOG_INDEX_NAMES = new HashSet<>(["moqui_logs"])

    /** Set once search() logs its one-time "aggs/aggregations not supported" warning (Issue #11) */
    private static volatile boolean aggsWarningLogged = false

    /** Jackson mapper shared with ElasticFacadeImpl */
    static final ObjectMapper jacksonMapper = ElasticFacadeImpl.jacksonMapper

    /** SQL for inserting HTTP request logs into the dedicated moqui_http_log table */
    static final String HTTP_LOG_INSERT_SQL = """
        INSERT INTO moqui_http_log (log_timestamp, remote_ip, remote_user, server_ip, content_type,
            request_method, request_scheme, request_host, request_path, request_query, http_version,
            response_code, time_initial_ms, time_final_ms, bytes_sent, referrer, agent, session_id, visitor_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """.trim()

    /** Valid PostgreSQL text search configuration identifier, e.g. "english", "simple", "pg_catalog.german".
     *  NOTE: a regconfig cannot be bound as a JDBC "?" parameter — it must be interpolated directly into
     *  the SQL text, so it is validated against this whitelist pattern before use (never taken from
     *  end-user request input; it only ever comes from trusted cluster configuration). */
    private static final java.util.regex.Pattern SAFE_TEXT_CONFIG_PATTERN =
            java.util.regex.Pattern.compile('^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)?$')

    private final ExecutionContextFactoryImpl ecfi
    private final MNode clusterNode
    private final String clusterName
    private final String indexPrefix
    /** Entity datasource group to get connections from (e.g. "transactional") */
    final String datasourceGroup
    /** PostgreSQL text search configuration (regconfig) used for to_tsvector/websearch_to_tsquery/ts_headline
     *  calls; configurable via the cluster element's text-config attribute (default "english"). See Issue #2
     *  in PROBLEMS_RESOLUTION_PLAN.md. */
    final String textConfig
    /** Whether to create the GIN(jsonb_path_ops) index on moqui_document.document (Issue #8 in
     *  PROBLEMS_RESOLUTION_PLAN.md). Indexes every JSON path so arbitrary-path queries stay fast, at the
     *  cost of extra write amplification/storage for large or deeply-nested documents. Configurable via
     *  the cluster element's "enable-jsonb-path-index" attribute; defaults to true (on) for correctness
     *  of arbitrary path queries out of the box — set to "false" to opt out for log-like/large-document
     *  indices where that tradeoff isn't worth it. */
    final boolean enableJsonbPathIndex
    /** Shared UPSERT SQL for moqui_document — used by upsertDocument(), bulkIndex(), and bulkIndexDataDocument() */
    private final String documentUpsertSql

    PostgresElasticClient(MNode clusterNode, ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        this.clusterNode = clusterNode
        this.clusterName = clusterNode.attribute("name")
        this.indexPrefix = clusterNode.attribute("index-prefix") ?: ""

        // url attribute for postgres type = datasource group name (or "transactional" by default)
        String urlAttr = clusterNode.attribute("url")
        this.datasourceGroup = (urlAttr && !"".equals(urlAttr.trim())) ? urlAttr.trim() : "transactional"

        String textConfigAttr = clusterNode.attribute("text-config")
        this.textConfig = validateTextConfig(textConfigAttr ? textConfigAttr.trim() : "english")

        String jsonbPathIndexAttr = clusterNode.attribute("enable-jsonb-path-index")
        this.enableJsonbPathIndex = jsonbPathIndexAttr == null || jsonbPathIndexAttr.trim().isEmpty() ||
                Boolean.parseBoolean(jsonbPathIndexAttr.trim())
        this.documentUpsertSql = """
            INSERT INTO moqui_document (index_name, doc_id, doc_type, document, content_text, content_tsv, updated_stamp)
            VALUES (?, ?, ?, ?::jsonb, ?, to_tsvector('${this.textConfig}', COALESCE(?, '')), now())
            ON CONFLICT (index_name, doc_id) DO UPDATE SET
                doc_type      = EXCLUDED.doc_type,
                document      = EXCLUDED.document,
                content_text  = EXCLUDED.content_text,
                content_tsv   = EXCLUDED.content_tsv,
                updated_stamp = EXCLUDED.updated_stamp
        """.trim()

        logger.info("Initializing PostgresElasticClient for cluster '${clusterName}' using datasource group '${datasourceGroup}' with index prefix '${indexPrefix}', text-config '${this.textConfig}', and jsonb-path-index ${this.enableJsonbPathIndex ? "enabled" : "disabled"}")

        // Initialize schema (CREATE TABLE IF NOT EXISTS, extensions, indexes)
        initSchema()
    }

    /**
     * Validate a PostgreSQL text search configuration name before it is interpolated into SQL (regconfig
     * values cannot be bound as JDBC parameters). Only simple identifiers (optionally schema-qualified,
     * e.g. "pg_catalog.english") are allowed.
     */
    private static String validateTextConfig(String textConfig) {
        if (!textConfig || !SAFE_TEXT_CONFIG_PATTERN.matcher(textConfig).matches()) {
            throw new IllegalArgumentException("Invalid text-config value '${textConfig}' for PostgresElasticClient cluster \u2014 " +
                    "must be a simple identifier matching a PostgreSQL text search configuration (e.g. 'english', 'simple')")
        }
        return textConfig
    }

    void destroy() {
        // Nothing to destroy — connection pool is managed by the entity facade datasource
    }

    // ============================================================
    // Schema initialization
    // ============================================================

    private void initSchema() {
        boolean started = ecfi.transactionFacade.begin(null)
        try {
            Connection conn = ecfi.entityFacade.getConnection(datasourceGroup)
            Statement stmt = conn.createStatement()
            try {
                // Enable pg_trgm extension for fuzzy search (available since PG 9.1). Optional/best-effort
                // (Issue #7 in PROBLEMS_RESOLUTION_PLAN.md): creating an extension requires elevated
                // privileges the configured DB user may not have, and the rest of schema init (including
                // the trigram index below, which is skipped if this fails) should still proceed rather
                // than aborting the whole transaction over this one non-fatal piece.
                boolean pgTrgmAvailable = execOptional(stmt, "CREATE EXTENSION IF NOT EXISTS pg_trgm",
                        "enable pg_trgm extension (may require superuser)")

                // moqui_search_index — index metadata (replaces ES index/alias concept)
                execRequired(stmt, """
                    CREATE TABLE IF NOT EXISTS moqui_search_index (
                        index_name   TEXT NOT NULL,
                        alias_name   TEXT,
                        doc_type     TEXT,
                        mapping      TEXT,
                        settings     TEXT,
                        created_stamp TIMESTAMPTZ NOT NULL DEFAULT now(),
                        CONSTRAINT pk_moqui_search_index PRIMARY KEY (index_name)
                    )
                """.trim(), "create moqui_search_index table")
                execRequired(stmt, "CREATE INDEX IF NOT EXISTS idx_mq_sidx_alias ON moqui_search_index (alias_name)",
                        "create moqui_search_index alias index")

                // moqui_document — main document store
                execRequired(stmt, """
                    CREATE TABLE IF NOT EXISTS moqui_document (
                        index_name    TEXT NOT NULL,
                        doc_id        TEXT NOT NULL,
                        doc_type      TEXT,
                        document      JSONB,
                        content_text  TEXT,
                        content_tsv   TSVECTOR,
                        created_stamp TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_stamp TIMESTAMPTZ NOT NULL DEFAULT now(),
                        CONSTRAINT pk_moqui_document PRIMARY KEY (index_name, doc_id)
                    )
                """.trim(), "create moqui_document table")
                // Ensure PostgreSQL-specific columns exist and are the correct type. NOTE: Moqui's
                // EntityFacadeImpl runs its startup table/column check BEFORE ElasticFacadeImpl
                // constructs this client (see ExecutionContextFactoryImpl init order), so on a fresh
                // install entity-sync may create these columns first using the generic type mapping
                // declared in SearchEntities.xml (e.g. TEXT for document/content_tsv, since Moqui has
                // no native JSONB/TSVECTOR field types). ensureColumnType() heals the column to the
                // type this backend actually needs regardless of which side got there first.
                execRequired(stmt, "ALTER TABLE moqui_document ADD COLUMN IF NOT EXISTS content_text TEXT",
                        "add moqui_document.content_text column")
                ensureColumnType(stmt, "moqui_document", "document", "JSONB", "document::jsonb")
                ensureColumnType(stmt, "moqui_document", "content_tsv", "TSVECTOR", "content_tsv::tsvector")
                // GIN index on tsvector for full-text search
                execRequired(stmt, "CREATE INDEX IF NOT EXISTS idx_mq_doc_tsv  ON moqui_document USING GIN (content_tsv)",
                        "create moqui_document content_tsv GIN index")
                // GIN index on document JSONB for arbitrary path queries — optional (Issue #8 in
                // PROBLEMS_RESOLUTION_PLAN.md): indexes every JSON path, so write amplification/storage
                // cost can be significant for large or deeply-nested documents. Defaults to on for
                // correctness of arbitrary path queries; opt out via the cluster's
                // "enable-jsonb-path-index" attribute.
                if (enableJsonbPathIndex) {
                    execRequired(stmt, "CREATE INDEX IF NOT EXISTS idx_mq_doc_json ON moqui_document USING GIN (document jsonb_path_ops)",
                            "create moqui_document document GIN(jsonb_path_ops) index")
                } else {
                    logger.info("PostgresElasticClient: skipping GIN(jsonb_path_ops) index on moqui_document.document " +
                            "for cluster '${clusterName}' (enable-jsonb-path-index=false) — arbitrary JSON-path queries " +
                            "on this index may not use an index and could be slow on large document sets.")
                }
                // GIN trigram index on content_text for fuzzy/LIKE queries — depends on pg_trgm, so only
                // attempt it if that extension is available; still optional/best-effort in case the
                // extension exists but this particular DB/role can't create the index for some reason.
                if (pgTrgmAvailable) {
                    execOptional(stmt, "CREATE INDEX IF NOT EXISTS idx_mq_doc_trgm ON moqui_document USING GIN (content_text gin_trgm_ops)",
                            "create moqui_document content_text trigram GIN index")
                } else {
                    logger.info("PostgresElasticClient: skipping trigram GIN index on moqui_document.content_text " +
                            "for cluster '${clusterName}' because the pg_trgm extension is not available — " +
                            "fuzzy/LIKE-style queries on this index may not use an index and could be slow.")
                }
                // Index for type-based filtering
                execRequired(stmt, "CREATE INDEX IF NOT EXISTS idx_mq_doc_type ON moqui_document (doc_type)",
                        "create moqui_document doc_type index")
                // Index for time-based ordering
                execRequired(stmt, "CREATE INDEX IF NOT EXISTS idx_mq_doc_upd  ON moqui_document (index_name, updated_stamp)",
                        "create moqui_document updated_stamp index")

                // moqui_logs — application log (replaces ES moqui_logs index)
                execRequired(stmt, """
                    CREATE TABLE IF NOT EXISTS moqui_logs (
                        log_id          BIGSERIAL PRIMARY KEY,
                        log_timestamp   TIMESTAMPTZ NOT NULL,
                        log_level       TEXT,
                        thread_name     TEXT,
                        thread_id       BIGINT,
                        thread_priority INTEGER,
                        logger_name     TEXT,
                        message         TEXT,
                        source_host     TEXT,
                        user_id         TEXT,
                        visitor_id      TEXT,
                        mdc             JSONB,
                        thrown          JSONB
                    )
                """.trim(), "create moqui_logs table")
                execRequired(stmt, "CREATE INDEX IF NOT EXISTS idx_mq_logs_ts  ON moqui_logs USING BRIN (log_timestamp)",
                        "create moqui_logs timestamp BRIN index")
                execRequired(stmt, "CREATE INDEX IF NOT EXISTS idx_mq_logs_lvl ON moqui_logs (log_level)",
                        "create moqui_logs log_level index")
                // Heal mdc/thrown to JSONB in case entity-sync created the table first using the
                // generic TEXT mapping declared for mdcJson/thrownJson in SearchEntities.xml
                ensureColumnType(stmt, "moqui_logs", "mdc", "JSONB", "mdc::jsonb")
                ensureColumnType(stmt, "moqui_logs", "thrown", "JSONB", "thrown::jsonb")
                // Fix log_id if Moqui entity sync created the table without a BIGSERIAL default. Optional
                // (Issue #7): this only heals the auto-increment default for a table entity-sync may have
                // created first — if it fails (e.g. insufficient privileges), the rest of schema init
                // should still proceed rather than aborting the whole transaction over it.
                execOptional(stmt, """
                    DO \$\$
                    BEGIN
                        IF (SELECT column_default FROM information_schema.columns
                            WHERE table_name = 'moqui_logs' AND column_name = 'log_id') IS NULL THEN
                            CREATE SEQUENCE IF NOT EXISTS moqui_logs_log_id_seq;
                            ALTER TABLE moqui_logs ALTER COLUMN log_id SET DEFAULT nextval('moqui_logs_log_id_seq');
                            ALTER SEQUENCE moqui_logs_log_id_seq OWNED BY moqui_logs.log_id;
                        END IF;
                    END \$\$;
                """.trim(), "heal moqui_logs.log_id BIGSERIAL default")

                // moqui_http_log — HTTP request log (replaces ES moqui_http_log index)
                execRequired(stmt, """
                    CREATE TABLE IF NOT EXISTS moqui_http_log (
                        log_id          BIGSERIAL PRIMARY KEY,
                        log_timestamp   TIMESTAMPTZ NOT NULL,
                        remote_ip       TEXT,
                        remote_user     TEXT,
                        server_ip       TEXT,
                        content_type    TEXT,
                        request_method  TEXT,
                        request_scheme  TEXT,
                        request_host    TEXT,
                        request_path    TEXT,
                        request_query   TEXT,
                        http_version    TEXT,
                        response_code   INTEGER,
                        time_initial_ms BIGINT,
                        time_final_ms   BIGINT,
                        bytes_sent      BIGINT,
                        referrer        TEXT,
                        agent           TEXT,
                        session_id      TEXT,
                        visitor_id      TEXT
                    )
                """.trim(), "create moqui_http_log table")
                execRequired(stmt, "CREATE INDEX IF NOT EXISTS idx_mq_hlog_ts   ON moqui_http_log USING BRIN (log_timestamp)",
                        "create moqui_http_log timestamp BRIN index")
                execRequired(stmt, "CREATE INDEX IF NOT EXISTS idx_mq_hlog_path ON moqui_http_log (request_path)",
                        "create moqui_http_log request_path index")
                // Fix log_id if Moqui entity sync created the table without a BIGSERIAL default (optional,
                // see moqui_logs.log_id above for rationale)
                execOptional(stmt, """
                    DO \$\$
                    BEGIN
                        IF (SELECT column_default FROM information_schema.columns
                            WHERE table_name = 'moqui_http_log' AND column_name = 'log_id') IS NULL THEN
                            CREATE SEQUENCE IF NOT EXISTS moqui_http_log_log_id_seq;
                            ALTER TABLE moqui_http_log ALTER COLUMN log_id SET DEFAULT nextval('moqui_http_log_log_id_seq');
                            ALTER SEQUENCE moqui_http_log_log_id_seq OWNED BY moqui_http_log.log_id;
                        END IF;
                    END \$\$;
                """.trim(), "heal moqui_http_log.log_id BIGSERIAL default")

                logger.info("PostgresElasticClient schema initialized for cluster '${clusterName}'")
            } finally {
                stmt.close()
            }
            ecfi.transactionFacade.commit(started)
        } catch (Throwable t) {
            ecfi.transactionFacade.rollback(started, "Error initializing PostgresElasticClient schema", t)
            throw new BaseException("Error initializing PostgresElasticClient schema for cluster '${clusterName}'", t)
        }
    }

    /**
     * Run a required (fatal-on-failure) piece of schema-init DDL. Unlike a bare stmt.execute(), this logs
     * the specific failing statement/description before letting the exception propagate to initSchema()'s
     * outer catch (which rolls back the transaction and wraps it in a BaseException) — see Issue #7 in
     * PROBLEMS_RESOLUTION_PLAN.md ("ensure the fatal path logs the exact failing statement").
     */
    private void execRequired(Statement stmt, String sql, String description) {
        try {
            stmt.execute(sql)
        } catch (Exception e) {
            logger.error("PostgresElasticClient.initSchema(): failed executing required DDL step '${description}': ${e.message}\nSQL: ${sql}")
            throw e
        }
    }

    /**
     * Run an optional/best-effort piece of schema-init DDL — one whose failure (e.g. insufficient
     * privileges for CREATE EXTENSION) shouldn't abort the whole schema-init transaction (Issue #7 in
     * PROBLEMS_RESOLUTION_PLAN.md). Logs a warning and returns false on failure instead of throwing.
     */
    private boolean execOptional(Statement stmt, String sql, String description) {
        try {
            stmt.execute(sql)
            return true
        } catch (Exception e) {
            logger.warn("PostgresElasticClient.initSchema(): optional DDL step '${description}' failed (continuing): ${e.message}")
            return false
        }
    }


    /**
     * Ensure a column is (or is converted to) the given PostgreSQL type, healing it if entity-sync
     * (or a prior version of this backend) created it with a different/generic type first. Adds the
     * column if missing, attempts an ALTER ... TYPE ... USING cast, and verifies the resulting type so
     * a failed/no-op cast doesn't fail silently (previously logged only at .trace, see Issue #1 in
     * PROBLEMS_RESOLUTION_PLAN.md).
     */
    private void ensureColumnType(Statement stmt, String table, String column, String pgType, String usingExpr) {
        stmt.execute("ALTER TABLE ${table} ADD COLUMN IF NOT EXISTS ${column} ${pgType}".toString())
        try {
            stmt.execute("ALTER TABLE ${table} ALTER COLUMN ${column} TYPE ${pgType} USING ${usingExpr}".toString())
        } catch (Exception e) {
            logger.warn("Could not alter column '${table}.${column}' to type ${pgType} (may already be correct type, or contains data incompatible with the cast): ${e.message}")
        }
        try {
            ResultSet rs = stmt.getConnection().getMetaData().getColumns(null, null, table, column)
            try {
                if (rs.next()) {
                    String actualType = rs.getString("TYPE_NAME")
                    if (actualType && !actualType.equalsIgnoreCase(pgType)) {
                        logger.warn("Column '${table}.${column}' has type '${actualType}' but this backend requires '${pgType}' — " +
                                "full-text search, JSON queries, or log writes involving this column may fail. " +
                                "Manually run: ALTER TABLE ${table} ALTER COLUMN ${column} TYPE ${pgType} USING ${usingExpr};")
                    }
                }
            } finally { rs.close() }
        } catch (Exception e) {
            logger.trace("Could not verify resulting type of column '${table}.${column}': ${e.message}")
        }
    }

    /**
     * Get a JDBC Connection from the entity facade for the configured datasource group.
     * The returned Connection is a Moqui ConnectionWrapper that is transaction-managed.
     */
    private Connection getConnection() {
        return ecfi.entityFacade.getConnection(datasourceGroup)
    }

    /**
     * Wraps ElasticQueryTranslator.translateQuery(), setting/clearing this cluster's configured
     * text-config (regconfig) around the call so any generated websearch_to_tsquery()/ts_headline()
     * SQL uses the right language instead of a hardcoded default. See Issue #2 in
     * PROBLEMS_RESOLUTION_PLAN.md.
     *
     * @param indexNames Optional concrete (already-prefixed) index names being queried. When given, the
     *        declared field types from those indices' moqui_search_index.mapping are made available to
     *        guessCastType() so it can prefer them over its heuristics (Issue #9). Pass null/empty when
     *        no mapping lookup is desired/applicable (e.g. an index that doesn't exist yet).
     */
    private ElasticQueryTranslator.QueryResult translateQuery(Map queryMap, List<String> indexNames = null) {
        ElasticQueryTranslator.setTextConfig(textConfig)
        ElasticQueryTranslator.setFieldTypeMap(resolveFieldTypeMap(indexNames))
        try {
            return ElasticQueryTranslator.translateQuery(queryMap)
        } finally {
            ElasticQueryTranslator.clearTextConfig()
            ElasticQueryTranslator.clearFieldTypeMap()
        }
    }

    /** Wraps ElasticQueryTranslator.translateSearchMap() — see translateQuery() above for why. */
    private ElasticQueryTranslator.TranslatedQuery translateSearchMap(Map searchMap, List<String> indexNames = null) {
        ElasticQueryTranslator.setTextConfig(textConfig)
        ElasticQueryTranslator.setFieldTypeMap(resolveFieldTypeMap(indexNames))
        try {
            return ElasticQueryTranslator.translateSearchMap(searchMap)
        } finally {
            ElasticQueryTranslator.clearTextConfig()
            ElasticQueryTranslator.clearFieldTypeMap()
        }
    }

    /**
     * Per-cluster cache of index name -> declared field type map (field name/dot-path -> ES mapping
     * "type" string, e.g. "date", "long", "keyword"), built from moqui_search_index.mapping. Avoids a
     * DB round-trip + JSON parse on every single search/query call. Invalidated whenever a mapping is
     * written (createIndex()/putMapping()) so stale types can't leak across a mapping update.
     */
    private final Map<String, Map<String, String>> fieldTypeMapCache = new ConcurrentHashMap<>()

    /** Remove any cached field-type map for the given (already-prefixed) index name. */
    private void invalidateFieldTypeMapCache(String prefixedIndex) {
        if (prefixedIndex) fieldTypeMapCache.remove(prefixedIndex)
    }

    /**
     * Resolve and merge the declared field-type maps for a set of concrete (already-prefixed) index
     * names, from their stored moqui_search_index.mapping (ES-style {properties: {field: {type: ...}}}).
     * Returns an empty map (never null) if no indexNames given or none have a mapping — guessCastType()
     * treats an empty/absent map as "no declared types, use heuristics only".
     */
    private Map<String, String> resolveFieldTypeMap(List<String> indexNames) {
        if (!indexNames) return Collections.emptyMap()
        Map<String, String> merged = new LinkedHashMap<>()
        for (String idx in indexNames) {
            Map<String, String> forIndex = fieldTypeMapCache.get(idx)
            if (forIndex == null) {
                forIndex = loadFieldTypeMapFromDb(idx)
                fieldTypeMapCache.put(idx, forIndex)
            }
            if (forIndex) merged.putAll(forIndex)
        }
        return merged
    }

    /** Load and flatten the field-type map for one concrete (already-prefixed) index from the DB. */
    private Map<String, String> loadFieldTypeMapFromDb(String prefixedIndex) {
        Map<String, String> result = new LinkedHashMap<>()
        try {
            Connection conn = getConnection()
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT mapping FROM moqui_search_index WHERE index_name = ?")
            try {
                ps.setString(1, prefixedIndex)
                ResultSet rs = ps.executeQuery()
                try {
                    if (rs.next()) {
                        String mappingJson = rs.getString("mapping")
                        if (mappingJson) {
                            Object mappingObj = jsonToObject(mappingJson)
                            if (mappingObj instanceof Map) flattenMappingProperties((Map) mappingObj, null, result)
                        }
                    }
                } finally { rs.close() }
            } finally { ps.close() }
        } catch (Exception e) {
            logger.trace("Could not load field-type mapping for index '${prefixedIndex}': ${e.message}")
        }
        return result
    }

    /**
     * Recursively flatten an ES mapping's "properties" tree into dot-path -> "type" entries.
     * E.g. {properties: {address: {properties: {city: {type: keyword}}}}} -> {"address.city": "keyword"}
     */
    @SuppressWarnings("unchecked")
    private static void flattenMappingProperties(Map mappingNode, String prefix, Map<String, String> result) {
        Object propsObj = mappingNode.get("properties")
        if (!(propsObj instanceof Map)) return
        Map<String, Object> props = (Map<String, Object>) propsObj
        for (Map.Entry<String, Object> entry in props.entrySet()) {
            String fieldName = prefix ? "${prefix}.${entry.key}".toString() : entry.key
            Object fieldDefObj = entry.value
            if (fieldDefObj instanceof Map) {
                Map<String, Object> fieldDef = (Map<String, Object>) fieldDefObj
                Object typeObj = fieldDef.get("type")
                if (typeObj instanceof String) result.put(fieldName, (String) typeObj)
                // Recurse into nested/object field definitions
                if (fieldDef.get("properties") instanceof Map) flattenMappingProperties(fieldDef, fieldName, result)
            }
        }
    }

    // ============================================================
    // ElasticClient — Cluster info
    // ============================================================

    @Override String getClusterName() { return clusterName }
    @Override String getClusterLocation() { return "postgres:${datasourceGroup}:${indexPrefix}" }

    @Override
    Map getServerInfo() {
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement("SELECT version()")
        try {
            ResultSet rs = ps.executeQuery()
            try {
                if (rs.next()) {
                    return [name: clusterName, cluster_name: "postgres",
                            version: [distribution: "postgres", number: rs.getString(1)],
                            tagline: "Moqui PostgresElasticClient"]
                }
            } finally { rs.close() }
        } finally { ps.close() }
        return [name: clusterName, cluster_name: "postgres", version: [distribution: "postgres"]]
    }

    // ============================================================
    // Index management
    // ============================================================

    @Override
    boolean indexExists(String index) {
        if (!index) return false
        // Dedicated tables always exist (created in initSchema)
        if (isHttpLogIndex(index) || isAppLogIndex(index)) return true
        String prefixed = prefixIndexName(index)
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM moqui_search_index WHERE index_name = ? OR alias_name = ?")
        try {
            ps.setString(1, prefixed)
            ps.setString(2, prefixed)
            ResultSet rs = ps.executeQuery()
            try { return rs.next() } finally { rs.close() }
        } finally { ps.close() }
    }

    @Override
    boolean aliasExists(String alias) {
        if (!alias) return false
        String prefixed = prefixIndexName(alias)
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM moqui_search_index WHERE alias_name = ?")
        try {
            ps.setString(1, prefixed)
            ResultSet rs = ps.executeQuery()
            try { return rs.next() } finally { rs.close() }
        } finally { ps.close() }
    }

    @Override
    void createIndex(String index, Map docMapping, String alias) {
        // Dedicated tables are created in initSchema — nothing to do
        if (isHttpLogIndex(index) || isAppLogIndex(index)) return
        createIndex(index, null, docMapping, alias, null)
    }

    void createIndex(String index, String docType, Map docMapping, String alias, Map settings) {
        if (!index) throw new IllegalArgumentException("Index name required for createIndex")
        String prefixedIndex = prefixIndexName(index)
        String prefixedAlias = alias ? prefixIndexName(alias) : null

        String mappingJson = docMapping ? objectToJson(docMapping) : null
        String settingsJson = settings ? objectToJson(settings) : null

        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO moqui_search_index (index_name, alias_name, doc_type, mapping, settings)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (index_name) DO UPDATE SET
                alias_name = EXCLUDED.alias_name,
                doc_type   = EXCLUDED.doc_type,
                mapping    = EXCLUDED.mapping,
                settings   = EXCLUDED.settings
        """.trim())
        try {
            ps.setString(1, prefixedIndex)
            if (prefixedAlias) ps.setString(2, prefixedAlias) else ps.setNull(2, Types.VARCHAR)
            if (docType) ps.setString(3, docType) else ps.setNull(3, Types.VARCHAR)
            if (mappingJson) ps.setString(4, mappingJson) else ps.setNull(4, Types.VARCHAR)
            if (settingsJson) ps.setString(5, settingsJson) else ps.setNull(5, Types.VARCHAR)
            ps.executeUpdate()
        } finally { ps.close() }
        invalidateFieldTypeMapCache(prefixedIndex)
        logger.info("PostgresElasticClient.createIndex: created index '${prefixedIndex}'${prefixedAlias ? ' with alias ' + prefixedAlias : ''}")
    }

    @Override
    void putMapping(String index, Map docMapping) {
        if (!docMapping) throw new IllegalArgumentException("Mapping may not be empty for putMapping")
        String prefixedIndex = prefixIndexName(index)
        String mappingJson = objectToJson(docMapping)
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(
                "UPDATE moqui_search_index SET mapping = ? WHERE index_name = ?")
        try {
            ps.setString(1, mappingJson)
            ps.setString(2, prefixedIndex)
            ps.executeUpdate()
        } finally { ps.close() }
        invalidateFieldTypeMapCache(prefixedIndex)
    }

    @Override
    void deleteIndex(String index) {
        if (!index) throw new IllegalArgumentException("Index name required for deleteIndex")
        String prefixedIndex = prefixIndexName(index)
        Connection conn = getConnection()
        PreparedStatement ps1 = conn.prepareStatement("DELETE FROM moqui_document WHERE index_name = ?")
        try {
            ps1.setString(1, prefixedIndex)
            int deleted = ps1.executeUpdate()
            logger.info("PostgresElasticClient.deleteIndex: deleted ${deleted} documents from index '${prefixedIndex}'")
        } finally { ps1.close() }
        PreparedStatement ps2 = conn.prepareStatement("DELETE FROM moqui_search_index WHERE index_name = ?")
        try {
            ps2.setString(1, prefixedIndex)
            ps2.executeUpdate()
        } finally { ps2.close() }
    }

    // ============================================================
    // Document CRUD
    // ============================================================

    /** Check if the given index name (raw or prefixed) refers to the dedicated HTTP log table */
    private boolean isHttpLogIndex(String index) {
        if (!index) return false
        String raw = unprefixIndexName(index.trim())
        return HTTP_LOG_INDEX_NAMES.contains(raw)
    }

    /** Check if the given index name (raw or prefixed) refers to the dedicated app logs table */
    private boolean isAppLogIndex(String index) {
        if (!index) return false
        String raw = unprefixIndexName(index.trim())
        return APP_LOG_INDEX_NAMES.contains(raw)
    }

    @Override
    void index(String index, String _id, Map document) {
        if (!index) throw new IllegalArgumentException("Index name required for index()")
        if (!_id) throw new IllegalArgumentException("_id required for index()")
        // Route HTTP log documents to dedicated table
        if (isHttpLogIndex(index)) {
            insertHttpLog(document)
            return
        }
        String prefixedIndex = prefixIndexName(index)
        String docJson = objectToJson(document)
        String contentText = extractContentText(document)
        upsertDocument(prefixedIndex, _id, null, docJson, contentText)
    }

    @Override
    void update(String index, String _id, Map documentFragment) {
        if (!index) throw new IllegalArgumentException("Index name required for update()")
        if (!_id) throw new IllegalArgumentException("_id required for update()")
        String prefixedIndex = prefixIndexName(index)
        String fragmentJson = objectToJson(documentFragment)

        // Merge the fragment into the existing document, then re-extract content_text using
        // the same recursive extractContentText() logic used by index()/upsert to keep FTS consistent
        Connection conn = getConnection()
        PreparedStatement getPs = conn.prepareStatement(
                "SELECT document FROM moqui_document WHERE index_name = ? AND doc_id = ?")
        try {
            getPs.setString(1, prefixedIndex)
            getPs.setString(2, _id)
            ResultSet rs = getPs.executeQuery()
            try {
                if (rs.next()) {
                    String existingJson = rs.getString("document")
                    Map existingDoc = existingJson ? (Map) jsonToObject(existingJson) : [:]
                    // Deep merge: fragment overrides existing values key-by-key, recursing into nested
                    // Maps so sibling keys not present in the fragment are preserved (matches ES _update
                    // partial-doc semantics). Lists/scalars in the fragment fully replace the existing value.
                    Map mergedDoc = deepMerge(existingDoc, documentFragment)
                    String mergedJson = objectToJson(mergedDoc)
                    String contentText = extractContentText(mergedDoc)
                    // Update with the fully merged document and properly extracted content
                    PreparedStatement updPs = conn.prepareStatement("""
                        UPDATE moqui_document
                        SET document = ?::jsonb,
                            content_text = ?,
                            content_tsv = to_tsvector('${textConfig}', COALESCE(?, '')),
                            updated_stamp = now()
                        WHERE index_name = ? AND doc_id = ?
                    """.trim())
                    try {
                        updPs.setString(1, mergedJson)
                        updPs.setString(2, contentText)
                        updPs.setString(3, contentText)
                        updPs.setString(4, prefixedIndex)
                        updPs.setString(5, _id)
                        updPs.executeUpdate()
                    } finally { updPs.close() }
                } else {
                    logger.warn("update(): document not found in index '${prefixedIndex}' with id '${_id}', inserting as new")
                    upsertDocument(prefixedIndex, _id, null, fragmentJson, extractContentText(documentFragment))
                }
            } finally { rs.close() }
        } finally { getPs.close() }
    }

    /**
     * Recursively merge {@code fragment} into {@code base}, returning a new Map. Nested Maps are merged
     * key-by-key (recursively); any other value type (List, scalar, null) in the fragment fully replaces
     * the corresponding value in base. Matches Elasticsearch's partial-document `_update` semantics.
     */
    @SuppressWarnings("unchecked")
    private static Map deepMerge(Map base, Map fragment) {
        Map result = new LinkedHashMap<>(base)
        for (Object entryObj in fragment.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObj
            Object key = entry.key
            Object fragmentValue = entry.value
            Object baseValue = result.get(key)
            if (baseValue instanceof Map && fragmentValue instanceof Map) {
                result.put(key, deepMerge((Map) baseValue, (Map) fragmentValue))
            } else {
                result.put(key, fragmentValue)
            }
        }
        return result
    }

    @Override
    void delete(String index, String _id) {
        if (!index) throw new IllegalArgumentException("Index name required for delete()")
        if (!_id) throw new IllegalArgumentException("_id required for delete()")
        String prefixedIndex = prefixIndexName(index)
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM moqui_document WHERE index_name = ? AND doc_id = ?")
        try {
            ps.setString(1, prefixedIndex)
            ps.setString(2, _id)
            int deleted = ps.executeUpdate()
            if (deleted == 0) logger.warn("delete() document not found in index '${prefixedIndex}' with id '${_id}'")
        } finally { ps.close() }
    }

    @Override
    Integer deleteByQuery(String index, Map queryMap) {
        if (!index) throw new IllegalArgumentException("Index name required for deleteByQuery()")

        // Route to dedicated tables for logs
        if (isHttpLogIndex(index)) return deleteByQueryHttpLog(queryMap)
        if (isAppLogIndex(index)) return deleteByQueryAppLog(queryMap)

        String prefixedIndex = prefixIndexName(index)
        ElasticQueryTranslator.QueryResult qr = translateQuery(queryMap ?: [match_all: [:]], [prefixedIndex])

        List<Object> allParams = new ArrayList<>()
        allParams.add(prefixedIndex)
        if (qr.params) allParams.addAll(qr.params)
        String sql = "DELETE FROM moqui_document WHERE index_name = ? AND (${qr.clause})"

        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(sql)
        try {
            for (int i = 0; i < allParams.size(); i++) {
                setParam(ps, i + 1, allParams[i])
            }
            return ps.executeUpdate()
        } finally { ps.close() }
    }

    /** Delete from the dedicated moqui_http_log table based on query (supports @timestamp range) */
    private Integer deleteByQueryHttpLog(Map queryMap) {
        TimestampRange range = extractTimestampRange(queryMap)
        if (range == null) {
            // match_all → delete everything, still batched (see batchedDelete() for why)
            return batchedDelete("moqui_http_log", "TRUE", [])
        }
        List<String> conditions = []
        List<Object> params = []
        if (range.lte != null) { conditions.add("log_timestamp <= ?"); params.add(range.lte) }
        if (range.lt != null) { conditions.add("log_timestamp < ?"); params.add(range.lt) }
        if (range.gte != null) { conditions.add("log_timestamp >= ?"); params.add(range.gte) }
        if (range.gt != null) { conditions.add("log_timestamp > ?"); params.add(range.gt) }
        String whereClause = conditions ? conditions.join(" AND ") : "TRUE"
        return batchedDelete("moqui_http_log", whereClause, params)
    }

    /** Delete from the dedicated moqui_logs table based on query (supports @timestamp range) */
    private Integer deleteByQueryAppLog(Map queryMap) {
        TimestampRange range = extractTimestampRange(queryMap)
        if (range == null) {
            return batchedDelete("moqui_logs", "TRUE", [])
        }
        List<String> conditions = []
        List<Object> params = []
        if (range.lte != null) { conditions.add("log_timestamp <= ?"); params.add(range.lte) }
        if (range.lt != null) { conditions.add("log_timestamp < ?"); params.add(range.lt) }
        if (range.gte != null) { conditions.add("log_timestamp >= ?"); params.add(range.gte) }
        if (range.gt != null) { conditions.add("log_timestamp > ?"); params.add(range.gt) }
        String whereClause = conditions ? conditions.join(" AND ") : "TRUE"
        return batchedDelete("moqui_logs", whereClause, params)
    }

    /**
     * Retention deletes on moqui_logs/moqui_http_log (Issue #6 in PROBLEMS_RESOLUTION_PLAN.md) can
     * remove a very large number of rows in one call (e.g. "delete everything older than 90 days").
     * A single unbounded DELETE would acquire row locks for and generate a WAL burst proportional to
     * the ENTIRE matching set in one statement, which can stall replication, blow up a long-running
     * transaction, and (if the caller has a statement/lock timeout) fail outright partway through with
     * no rows removed.
     *
     * This chunks the delete into repeated bounded statements (ctid + LIMIT, since these log tables
     * have no natural single-column ordering guaranteed to be indexed for keyset chunking) so each
     * individual DELETE does bounded work. Note: because getConnection() returns a transaction-managed
     * connection (see its docs), all chunks still execute within whatever single transaction the caller
     * is already in — this does NOT provide independent commits between chunks (that would require
     * suspending/beginning a new transaction per chunk via TransactionFacade, a heavier change not made
     * here). The benefit is smaller per-statement lock/WAL footprint and forward progress even if a
     * later chunk hits a timeout. Periodic VACUUM (or pg_repack for full bloat reclaim) is still
     * recommended after large deletes, same as for any high-churn PostgreSQL table.
     */
    private Integer batchedDelete(String table, String whereClause, List<Object> params, int batchSize = 5000) {
        String sql = "DELETE FROM ${table} WHERE ctid IN (SELECT ctid FROM ${table} WHERE ${whereClause} LIMIT ?)".toString()
        Connection conn = getConnection()
        int totalDeleted = 0
        while (true) {
            PreparedStatement ps = conn.prepareStatement(sql)
            int deleted
            try {
                int i = 1
                for (Object param in params) setParam(ps, i++, param)
                ps.setInt(i, batchSize)
                deleted = ps.executeUpdate()
            } finally { ps.close() }
            totalDeleted += deleted
            if (deleted < batchSize) break
        }
        return totalDeleted
    }

    /** Simple holder for timestamp range bounds extracted from query DSL */
    private static class TimestampRange {
        Timestamp lte, lt, gte, gt
    }

    /**
     * Extract @timestamp range bounds from an ES query map (as used by the nightly cleanup jobs).
     * Handles both epoch millis (Long/String number) and ISO date strings.
     * Returns null if no timestamp range is found (e.g. match_all).
     */
    private static TimestampRange extractTimestampRange(Map queryMap) {
        if (queryMap == null) return null
        // Direct range query: {range: {@timestamp: {lte: ...}}}
        if (queryMap.containsKey("range")) {
            return parseTimestampRangeSpec(queryMap)
        }
        // Bool query with range in must list
        Map boolMap = (Map) queryMap.get("bool")
        if (boolMap == null) return null
        Object mustVal = boolMap.get("must")
        List<Map> mustList = []
        if (mustVal instanceof List) mustList = (List<Map>) mustVal
        else if (mustVal instanceof Map) mustList = [(Map) mustVal]
        for (Map clause in mustList) {
            if (clause.containsKey("range")) {
                return parseTimestampRangeSpec(clause)
            }
        }
        return null
    }

    private static TimestampRange parseTimestampRangeSpec(Map rangeClause) {
        Map rangeMap = (Map) rangeClause.get("range")
        if (rangeMap == null) return null
        // Look for @timestamp or any timestamp-like field
        Map specMap = null
        for (String key in ["@timestamp", "log_timestamp"]) {
            if (rangeMap.containsKey(key)) { specMap = (Map) rangeMap.get(key); break }
        }
        if (specMap == null) {
            // Try first key
            String firstKey = rangeMap.keySet().isEmpty() ? null : (String) rangeMap.keySet().iterator().next()
            if (firstKey) specMap = (Map) rangeMap.get(firstKey)
        }
        if (specMap == null) return null
        TimestampRange range = new TimestampRange()
        range.lte = toTimestamp(specMap.get("lte"))
        range.lt = toTimestamp(specMap.get("lt"))
        range.gte = toTimestamp(specMap.get("gte"))
        range.gt = toTimestamp(specMap.get("gt"))
        if (range.lte == null && range.lt == null && range.gte == null && range.gt == null) return null
        return range
    }

    /** Convert a value (epoch millis long, numeric string, or ISO string) to a Timestamp */
    private static Timestamp toTimestamp(Object val) {
        if (val == null) return null
        if (val instanceof Number) return new Timestamp(((Number) val).longValue())
        String s = val.toString().trim()
        if (!s) return null
        // Try epoch millis
        try { return new Timestamp(Long.parseLong(s)) } catch (NumberFormatException ignored) {}
        // Try ISO format
        try { return Timestamp.valueOf(s.replace('T', ' ').replace('Z', '')) } catch (Exception ignored) {}
        return null
    }

    @Override
    void bulk(String index, List<Map> actionSourceList) {
        if (!actionSourceList) return
        String prefixedIndex = index ? prefixIndexName(index) : null

        int i = 0
        while (i < actionSourceList.size()) {
            Map action = (Map) actionSourceList.get(i)

            if (action.containsKey("delete")) {
                Map actionSpec = (Map) action.get("delete")
                String idxName = actionSpec.get("_index") ? prefixIndexName((String) actionSpec.get("_index")) : prefixedIndex
                String _id = (String) actionSpec.get("_id")
                if (idxName && _id) delete(idxName, _id)
                i += 1
            } else if (i + 1 < actionSourceList.size()) {
                Map source = (Map) actionSourceList.get(i + 1)

                if (action.containsKey("index") || action.containsKey("create")) {
                    Map actionSpec = (Map) (action.get("index") ?: action.get("create"))
                    String idxName = actionSpec.get("_index") ? prefixIndexName((String) actionSpec.get("_index")) : prefixedIndex
                    String _id = (String) actionSpec.get("_id")
                    if (idxName) {
                        String docJson = objectToJson(source)
                        String contentText = extractContentText(source)
                        upsertDocument(idxName, _id, null, docJson, contentText)
                    }
                } else if (action.containsKey("update")) {
                    Map actionSpec = (Map) action.get("update")
                    String idxName = actionSpec.get("_index") ? prefixIndexName((String) actionSpec.get("_index")) : prefixedIndex
                    String _id = (String) actionSpec.get("_id")
                    if (idxName && _id) {
                        Map doc = (Map) source.get("doc") ?: source
                        update(idxName, _id, doc)
                    }
                }
                i += 2
            } else {
                logger.warn("bulk(): action at index ${i} has no following source document, skipping")
                i += 1
            }
        }
    }

    @Override
    void bulkIndex(String index, String idField, List<Map> documentList) {
        bulkIndex(index, null, idField, documentList, false)
    }

    @Override
    void bulkIndex(String index, String docType, String idField, List<Map> documentList, boolean refresh) {
        if (!documentList) return
        // Route HTTP log documents to dedicated table
        if (isHttpLogIndex(index)) {
            bulkInsertHttpLogs(documentList)
            return
        }
        String prefixedIndex = prefixIndexName(index)
        boolean hasId = idField != null && !idField.isEmpty()

        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(documentUpsertSql)
        try {
            int batchSize = 0
            for (Map doc in documentList) {
                String _id = hasId ? (doc.get(idField)?.toString() ?: UUID.randomUUID().toString()) : UUID.randomUUID().toString()
                String docJson = objectToJson(doc)
                String contentText = extractContentText(doc)
                setUpsertParams(ps, prefixedIndex, _id, docType, docJson, contentText)
                ps.addBatch()
                batchSize++
                if (batchSize >= 500) {
                    ps.executeBatch()
                    batchSize = 0
                }
            }
            if (batchSize > 0) ps.executeBatch()
        } finally { ps.close() }
    }

    @Override
    Map get(String index, String _id) {
        if (!index || !_id) return null
        String prefixedIndex = prefixIndexName(index)
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(
                "SELECT doc_id, index_name, doc_type, document FROM moqui_document WHERE index_name = ? AND doc_id = ?")
        try {
            ps.setString(1, prefixedIndex)
            ps.setString(2, _id)
            ResultSet rs = ps.executeQuery()
            try {
                if (rs.next()) {
                    Map source = (Map) jsonToObject(rs.getString("document"))
                    return [_index: unprefixIndexName(rs.getString("index_name")),
                            _id   : rs.getString("doc_id"),
                            _type : rs.getString("doc_type"),
                            _source: source]
                }
                return null
            } finally { rs.close() }
        } finally { ps.close() }
    }

    @Override
    Map getSource(String index, String _id) {
        Map result = get(index, _id)
        return result ? (Map) result.get("_source") : null
    }

    @Override
    List<Map> get(String index, List<String> _idList) {
        if (!_idList || !index) return []
        String prefixedIndex = prefixIndexName(index)
        String placeholders = _idList.collect { "?" }.join(", ")
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(
                "SELECT doc_id, index_name, doc_type, document FROM moqui_document WHERE index_name = ? AND doc_id IN (${placeholders})")
        try {
            ps.setString(1, prefixedIndex)
            for (int i = 0; i < _idList.size(); i++) ps.setString(i + 2, _idList[i])
            ResultSet rs = ps.executeQuery()
            try {
                List<Map> results = []
                while (rs.next()) {
                    Map source = (Map) jsonToObject(rs.getString("document"))
                    results.add([_index: unprefixIndexName(rs.getString("index_name")),
                                 _id   : rs.getString("doc_id"),
                                 _type : rs.getString("doc_type"),
                                 _source: source])
                }
                return results
            } finally { rs.close() }
        } finally { ps.close() }
    }

    // ============================================================
    // Search
    // ============================================================

    @Override
    Map search(String index, Map searchMap) {
        // Aggregations (aggs/aggregations) are NOT implemented by this backend (Issue #11 in
        // PROBLEMS_RESOLUTION_PLAN.md) — a request that includes them will still run (returning
        // ordinary hits) but silently gets no "aggregations" key in the response, which can be an easy
        // thing to miss when porting screens/services from a real Elasticsearch cluster. Warn loudly,
        // once per JVM, so this limitation surfaces during development/testing rather than being
        // discovered as "why are my aggregation charts always empty" in production.
        if (searchMap != null && (searchMap.containsKey("aggs") || searchMap.containsKey("aggregations"))
                && !aggsWarningLogged) {
            aggsWarningLogged = true
            logger.warn("PostgresElasticClient.search(): the request includes 'aggs'/'aggregations', " +
                    "which are NOT supported by the PostgreSQL search backend (cluster '${clusterName}') " +
                    "and will be ignored — the response will contain hits but no 'aggregations' key. " +
                    "See Issue #11 in PROBLEMS_RESOLUTION_PLAN.md. (This warning is logged only once.)")
        }

        // If no index was given but the search map carries a PIT id (point-in-time cursor), recover
        // the original index name from the token — see getPitId() for the encoding used. This is
        // needed because ElasticEntityListIterator passes index=null on every search() call once a
        // PIT id has been obtained (mirroring real Elasticsearch, where the PIT itself identifies
        // the index).
        if (!index) {
            Object pitObj = searchMap?.get("pit")
            if (pitObj instanceof Map) {
                String pitId = (String) ((Map) pitObj).get("id")
                if (pitId && pitId.startsWith("pg::")) {
                    List<String> pitParts = pitId.split("::", 3) as List<String>
                    if (pitParts.size() >= 2 && pitParts[1]) index = pitParts[1]
                }
            }
        }

        // Route dedicated log tables
        if (index && isAppLogIndex(index)) {
            return searchLogsTable(searchMap ?: [:])
        }
        if (index && isHttpLogIndex(index)) {
            return searchHttpLogTable(searchMap ?: [:])
        }

        List<String> indexNames = resolveIndexNames(index)
        if (indexNames.isEmpty()) {
            return [hits: [total: [value: 0, relation: "eq"], hits: []]]
        }

        ElasticQueryTranslator.TranslatedQuery tq = translateSearchMap(searchMap ?: [:], indexNames)

        String idxPlaceholders = indexNames.collect { "?" }.join(", ")
        String whereClause = "index_name IN (${idxPlaceholders})"
        List<Object> allParams = new ArrayList<>(indexNames)

        if (tq.tsqueryExpr) {
            whereClause += " AND " + tq.whereClause
            allParams.addAll(tq.params)
        } else if (tq.whereClause && tq.whereClause != "TRUE") {
            whereClause += " AND " + tq.whereClause
            allParams.addAll(tq.params)
        }
        // Captured before the keyset predicate (if any) is appended below — used for the COUNT query,
        // which must reflect the filter only, not the pagination cursor.
        String filterWhereClause = whereClause

        // Keyset pagination: PIT (getPitId()) is a synthetic best-effort token, not a real MVCC
        // snapshot (see getPitId()/deletePit() docs — Issue #13 in PROBLEMS_RESOLUTION_PLAN.md), so
        // plain LIMIT/OFFSET pagination combined with it can skip or duplicate rows if the underlying
        // table changes mid-scroll. When the caller hasn't requested a custom sort or a full-text-ranked
        // query, we control the ordering ourselves (updated_stamp DESC, doc_id ASC as a deterministic
        // tiebreaker) and can support ES-style search_after keyset pagination: each returned hit carries
        // a "sort" value pair that ElasticEntityListIterator feeds back as search_after on the next
        // fetch, letting us page with a stable WHERE predicate instead of a shifting OFFSET.
        //
        // The updated_stamp column can carry microsecond precision in PostgreSQL, but the "sort" cursor
        // we hand back to callers is a plain epoch-millis Long (matching ES's usual sort value shape).
        // If we compared the raw (microsecond-precision) column against a millis-truncated cursor value,
        // the equality branch of the keyset predicate below would almost never match, silently dropping
        // rows. To keep ORDER BY, the WHERE predicate, and the emitted cursor all consistent, we truncate
        // updated_stamp to millisecond precision everywhere it's used for keyset ordering/comparison.
        String tsExpr = "date_trunc('milliseconds', updated_stamp)"
        boolean useDefaultOrder = !tq.orderBy && !tq.tsqueryExpr
        String orderByClause = tq.orderBy ? "${tq.orderBy}, doc_id ASC".toString()
                : (tq.tsqueryExpr ? "_score DESC, doc_id ASC" : "${tsExpr} DESC, doc_id ASC".toString())

        boolean useKeyset = false
        Timestamp keysetStamp = null
        String keysetDocId = null
        if (useDefaultOrder && tq.searchAfter != null && tq.searchAfter.size() == 2) {
            try {
                keysetStamp = new Timestamp(((Number) tq.searchAfter[0]).longValue())
                keysetDocId = tq.searchAfter[1]?.toString()
                useKeyset = keysetDocId != null
            } catch (Exception e) {
                logger.warn("search(): ignoring unparseable search_after value ${tq.searchAfter}: ${e.message}")
            }
        }
        if (useKeyset) {
            whereClause += " AND (${tsExpr} < ? OR (${tsExpr} = ? AND doc_id > ?))".toString()
        }

        // Build highlight columns (use PostgreSQL ts_headline when we have a tsquery)
        boolean useDbHighlights = tq.highlightFields && tq.tsqueryExpr
        List<String> hlColumnExprs = []
        List<String> hlFieldNames = []
        List<Object> hlParams = []
        if (useDbHighlights) {
            for (String hlField in tq.highlightFields.keySet()) {
                String jsonPath = ElasticQueryTranslator.fieldToJsonPath("document", hlField)
                String hlExpr = ElasticQueryTranslator.buildHighlightExpr(jsonPath, tq.tsqueryExpr)
                hlColumnExprs.add("${hlExpr} AS hl_${hlFieldNames.size()}".toString())
                hlFieldNames.add(hlField)
                hlParams.addAll(tq.tsqueryParams)
            }
        }

        String hlSelect = hlColumnExprs ? ", " + hlColumnExprs.join(", ") : ""

        // COUNT excludes the keyset predicate — it's a pagination cursor, not a result filter. Note
        // that computing an exact "total" alongside a moving keyset cursor is inherently approximate
        // for any backend without a held snapshot (see PIT limitation docs on getPitId()).
        String countSql = "SELECT COUNT(*) FROM moqui_document WHERE ${filterWhereClause}"
        long totalCount = 0L

        // Issue #12 in PROBLEMS_RESOLUTION_PLAN.md: avoid a separate COUNT round-trip for the common
        // case by carrying the total alongside each hit via count(*) OVER(), computed against the same
        // WHERE clause as the main query. This is only safe to do here — instead of the two-query
        // approach below — when whereClause and filterWhereClause are identical, i.e. no keyset
        // predicate is active (useKeyset false); otherwise the OVER() total would reflect "rows
        // remaining after the cursor" instead of the filter-only total the rest of this method
        // documents/relies on. It also can't report a total when the page itself comes back empty
        // (e.g. from/size past the end of the result set) since there's no row to carry the value on —
        // that (rare) case falls back to the original standalone COUNT query.
        boolean useOverCountInMainQuery = tq.trackTotal && !useKeyset

        String mainSql = """
            SELECT doc_id, index_name, doc_type, document, ${tsExpr} AS updated_stamp_ms, ${buildScoreSelect(tq)} AS _score${hlSelect}${useOverCountInMainQuery ? ", count(*) OVER() AS _total" : ""}
            FROM moqui_document
            WHERE ${whereClause}
            ORDER BY ${orderByClause}
            LIMIT ? OFFSET ?
        """.trim()

        Connection conn = getConnection()

        if (tq.trackTotal && !useOverCountInMainQuery) {
            PreparedStatement countPs = conn.prepareStatement(countSql)
            try {
                for (int i = 0; i < allParams.size(); i++) setParam(countPs, i + 1, allParams[i])
                ResultSet rs = countPs.executeQuery()
                try { if (rs.next()) totalCount = rs.getLong(1) } finally { rs.close() }
            } finally { countPs.close() }
        }


        List<Object> mainParams = []
        if (tq.tsqueryExpr) mainParams.addAll(tq.tsqueryParams)
        // Add highlight params (one set of tsquery params per highlight field)
        mainParams.addAll(hlParams)
        mainParams.addAll(allParams)
        if (useKeyset) { mainParams.add(keysetStamp); mainParams.add(keysetStamp); mainParams.add(keysetDocId) }
        mainParams.add(tq.sizeLimit)
        // Once a keyset cursor is active it fully determines position, so OFFSET must be 0 (mirrors
        // ElasticEntityListIterator always sending from=0 alongside search_after)
        mainParams.add(useKeyset ? 0 : tq.fromOffset)

        PreparedStatement ps = conn.prepareStatement(mainSql)
        try {
            for (int i = 0; i < mainParams.size(); i++) setParam(ps, i + 1, mainParams[i])
            ResultSet rs = ps.executeQuery()
            try {
                List<Map> hits = []
                while (rs.next()) {
                    String docJson = rs.getString("document")
                    Map source = docJson ? (Map) jsonToObject(docJson) : [:]
                    String docId = rs.getString("doc_id")
                    String idxName = unprefixIndexName(rs.getString("index_name"))
                    String docType = rs.getString("doc_type")
                    double score = rs.getDouble("_score")

                    Map hit = [_index: idxName, _id: docId, _type: docType,
                               _score: score, _source: source] as Map

                    // Emit a keyset "sort" cursor for the default ordering so callers using ES-style
                    // search_after (e.g. ElasticEntityListIterator) can page deterministically. Uses the
                    // same millisecond-truncated expression as ORDER BY/WHERE (see tsExpr above) so a
                    // round-tripped cursor value compares equal on the next fetch.
                    if (useDefaultOrder) {
                        Timestamp updStamp = rs.getTimestamp("updated_stamp_ms")
                        hit.put("sort", [updStamp?.getTime(), docId])
                    }

                    // Read ts_headline results from result set columns
                    if (useDbHighlights) {
                        Map<String, List<String>> highlights = [:]
                        for (int h = 0; h < hlFieldNames.size(); h++) {
                            String hlResult = rs.getString("hl_${h}")
                            if (hlResult) highlights.put(hlFieldNames[h], [hlResult])
                        }
                        if (highlights) hit.put("highlight", highlights)
                    }

                    if (useOverCountInMainQuery) totalCount = rs.getLong("_total")

                    hits.add(hit)
                }

                // Rare fallback: an empty page (e.g. from/size past the end of the result set) means
                // there was no row to carry the count(*) OVER() total on above — run the standalone
                // COUNT query in that case so the reported total stays accurate.
                if (useOverCountInMainQuery && hits.isEmpty()) {
                    PreparedStatement countPs = conn.prepareStatement(countSql)
                    try {
                        for (int i = 0; i < allParams.size(); i++) setParam(countPs, i + 1, allParams[i])
                        ResultSet countRs = countPs.executeQuery()
                        try { if (countRs.next()) totalCount = countRs.getLong(1) } finally { countRs.close() }
                    } finally { countPs.close() }
                }

                return [hits: [total: [value: (int) totalCount, relation: (tq.trackTotal ? "eq" : "gte")], hits: hits],
                        _shards: [total: 1, successful: 1, failed: 0]]
            } finally { rs.close() }
        } finally { ps.close() }
    }

    private Map searchLogsTable(Map searchMap) {
        ElasticQueryTranslator.TranslatedQuery tq = translateSearchMap(searchMap)

        String rawQuery = null
        Map queryMap = (Map) searchMap?.get("query")
        if (queryMap) {
            Map qsMap = (Map) queryMap.get("query_string")
            if (qsMap) rawQuery = (String) qsMap.get("query")
        }

        List<String> conditions = []
        List<Object> params = []

        if (rawQuery) {
            java.util.regex.Matcher m = (rawQuery =~ /@timestamp\s*:\s*\[\s*(\*|\d+)\s+TO\s+(\*|\d+)\s*\]/)
            if (m.find()) {
                String fromVal = m.group(1)
                String toVal = m.group(2)
                if (fromVal != '*') {
                    conditions.add("log_timestamp >= ?")
                    params.add(new java.sql.Timestamp(Long.parseLong(fromVal)))
                }
                if (toVal != '*') {
                    conditions.add("log_timestamp <= ?")
                    params.add(new java.sql.Timestamp(Long.parseLong(toVal)))
                }
            }
        }

        String userTextQuery = null
        if (rawQuery) {
            String stripped = rawQuery.replaceAll(/@timestamp\s*:\s*\[[^\]]*\]/, '')
            stripped = stripped.replaceAll(/\bAND\b/, ' ').replaceAll(/\bOR\b/, ' ')
            stripped = stripped.replaceAll(/[()]/, ' ').replaceAll(/\s+/, ' ').trim()
            stripped = stripped.replaceAll(/\*/, '').trim()
            if (stripped) userTextQuery = stripped
        }
        if (userTextQuery) {
            conditions.add("to_tsvector('${textConfig}', coalesce(message, '') || ' ' || coalesce(logger_name, '')) @@ websearch_to_tsquery('${textConfig}', ?)".toString())
            params.add(userTextQuery)
        }

        String whereClause = conditions ? conditions.join(" AND ") : "TRUE"

        Connection conn = getConnection()
        long totalCount = 0L
        if (tq.trackTotal) {
            PreparedStatement countPs = conn.prepareStatement("SELECT COUNT(*) FROM moqui_logs WHERE ${whereClause}")
            try {
                for (int i = 0; i < params.size(); i++) setParam(countPs, i + 1, params[i])
                ResultSet rs = countPs.executeQuery()
                try { if (rs.next()) totalCount = rs.getLong(1) } finally { rs.close() }
            } finally { countPs.close() }
        }

        String mainSql = """
            SELECT log_id, log_timestamp, log_level, thread_name, thread_id, thread_priority,
                   logger_name, message, source_host, user_id, visitor_id, mdc::text, thrown::text
            FROM moqui_logs
            WHERE ${whereClause}
            ORDER BY log_timestamp DESC
            LIMIT ? OFFSET ?
        """.trim()

        PreparedStatement ps = conn.prepareStatement(mainSql)
        try {
            int pIdx = 0
            for (int i = 0; i < params.size(); i++) setParam(ps, ++pIdx, params[i])
            ps.setInt(++pIdx, tq.sizeLimit)
            ps.setInt(++pIdx, tq.fromOffset)
            ResultSet rs = ps.executeQuery()
            try {
                List<Map> hits = []
                while (rs.next()) {
                    long logId = rs.getLong("log_id")
                    java.sql.Timestamp ts = rs.getTimestamp("log_timestamp")
                    Map source = [
                            "@timestamp"    : ts?.time,
                            level           : rs.getString("log_level"),
                            thread_name     : rs.getString("thread_name"),
                            thread_id       : rs.getLong("thread_id"),
                            thread_priority : rs.getInt("thread_priority"),
                            logger_name     : rs.getString("logger_name"),
                            message         : rs.getString("message"),
                            source_host     : rs.getString("source_host"),
                            user_id         : rs.getString("user_id"),
                            visitor_id      : rs.getString("visitor_id"),
                    ] as Map
                    String mdcStr = rs.getString("mdc")
                    if (mdcStr) source.put("mdc", jsonToObject(mdcStr))
                    String thrownStr = rs.getString("thrown")
                    if (thrownStr) source.put("thrown", jsonToObject(thrownStr))
                    hits.add([_index: "moqui_logs", _id: String.valueOf(logId),
                              _type: "LogMessage", _score: 1.0, _source: source] as Map)
                }
                return [hits: [total: [value: (int) totalCount, relation: (tq.trackTotal ? "eq" : "gte")], hits: hits],
                        _shards: [total: 1, successful: 1, failed: 0]]
            } finally { rs.close() }
        } catch (Throwable t) {
            logger.error("searchLogsTable error: " + t.message, t)
            return [hits: [total: [value: 0, relation: "eq"], hits: []]]
        } finally { ps.close() }
    }

    /** Search the dedicated moqui_http_log table (mirrors searchLogsTable pattern) */
    private Map searchHttpLogTable(Map searchMap) {
        ElasticQueryTranslator.TranslatedQuery tq = translateSearchMap(searchMap)

        String rawQuery = null
        Map queryMap = (Map) searchMap?.get("query")
        if (queryMap) {
            Map qsMap = (Map) queryMap.get("query_string")
            if (qsMap) rawQuery = (String) qsMap.get("query")
        }

        List<String> conditions = []
        List<Object> params = []

        if (rawQuery) {
            java.util.regex.Matcher m = (rawQuery =~ /@timestamp\s*:\s*\[\s*(\*|\d+)\s+TO\s+(\*|\d+)\s*\]/)
            if (m.find()) {
                String fromVal = m.group(1)
                String toVal = m.group(2)
                if (fromVal != '*') {
                    conditions.add("log_timestamp >= ?")
                    params.add(new java.sql.Timestamp(Long.parseLong(fromVal)))
                }
                if (toVal != '*') {
                    conditions.add("log_timestamp <= ?")
                    params.add(new java.sql.Timestamp(Long.parseLong(toVal)))
                }
            }
        }

        // Also check for range query in bool.must
        if (queryMap) {
            TimestampRange range = extractTimestampRange(queryMap)
            if (range != null) {
                if (range.lte != null) { conditions.add("log_timestamp <= ?"); params.add(range.lte) }
                if (range.lt != null) { conditions.add("log_timestamp < ?"); params.add(range.lt) }
                if (range.gte != null) { conditions.add("log_timestamp >= ?"); params.add(range.gte) }
                if (range.gt != null) { conditions.add("log_timestamp > ?"); params.add(range.gt) }
            }
        }

        String whereClause = conditions ? conditions.join(" AND ") : "TRUE"

        Connection conn = getConnection()
        long totalCount = 0L
        if (tq.trackTotal) {
            PreparedStatement countPs = conn.prepareStatement("SELECT COUNT(*) FROM moqui_http_log WHERE ${whereClause}")
            try {
                for (int i = 0; i < params.size(); i++) setParam(countPs, i + 1, params[i])
                ResultSet rs = countPs.executeQuery()
                try { if (rs.next()) totalCount = rs.getLong(1) } finally { rs.close() }
            } finally { countPs.close() }
        }

        String mainSql = """
            SELECT log_id, log_timestamp, remote_ip, remote_user, server_ip, content_type,
                   request_method, request_scheme, request_host, request_path, request_query,
                   http_version, response_code, time_initial_ms, time_final_ms, bytes_sent,
                   referrer, agent, session_id, visitor_id
            FROM moqui_http_log
            WHERE ${whereClause}
            ORDER BY log_timestamp DESC
            LIMIT ? OFFSET ?
        """.trim()

        PreparedStatement ps = conn.prepareStatement(mainSql)
        try {
            int pIdx = 0
            for (int i = 0; i < params.size(); i++) setParam(ps, ++pIdx, params[i])
            ps.setInt(++pIdx, tq.sizeLimit)
            ps.setInt(++pIdx, tq.fromOffset)
            ResultSet rs = ps.executeQuery()
            try {
                List<Map> hits = []
                while (rs.next()) {
                    long logId = rs.getLong("log_id")
                    java.sql.Timestamp ts = rs.getTimestamp("log_timestamp")
                    Map source = [
                            "@timestamp"    : ts?.time,
                            remote_ip       : rs.getString("remote_ip"),
                            remote_user     : rs.getString("remote_user"),
                            server_ip       : rs.getString("server_ip"),
                            content_type    : rs.getString("content_type"),
                            request_method  : rs.getString("request_method"),
                            request_scheme  : rs.getString("request_scheme"),
                            request_host    : rs.getString("request_host"),
                            request_path    : rs.getString("request_path"),
                            request_query   : rs.getString("request_query"),
                            http_version    : rs.getString("http_version"),
                            response        : rs.getInt("response_code"),
                            time_initial_ms : rs.getLong("time_initial_ms"),
                            time_final_ms   : rs.getLong("time_final_ms"),
                            bytes           : rs.getLong("bytes_sent"),
                            referrer        : rs.getString("referrer"),
                            agent           : rs.getString("agent"),
                            session         : rs.getString("session_id"),
                            visitor_id      : rs.getString("visitor_id"),
                    ] as Map
                    hits.add([_index: "moqui_http_log", _id: String.valueOf(logId),
                              _type: "MoquiHttpRequest", _score: 1.0, _source: source] as Map)
                }
                return [hits: [total: [value: (int) totalCount, relation: (tq.trackTotal ? "eq" : "gte")], hits: hits],
                        _shards: [total: 1, successful: 1, failed: 0]]
            } finally { rs.close() }
        } catch (Throwable t) {
            logger.error("searchHttpLogTable error: " + t.message, t)
            return [hits: [total: [value: 0, relation: "eq"], hits: []]]
        } finally { ps.close() }
    }

    @Override
    List<Map> searchHits(String index, Map searchMap) {
        Map result = search(index, searchMap)
        return (List<Map>) ((Map) result.get("hits")).get("hits")
    }

    @Override
    Map validateQuery(String index, Map queryMap, boolean explain) {
        try {
            List<String> indexNames = resolveIndexNames(index)
            ElasticQueryTranslator.QueryResult qr = translateQuery(queryMap ?: [match_all: [:]], indexNames)
            return null  // valid
        } catch (Throwable t) {
            return [valid: false, error: t.message]
        }
    }

    @Override
    long count(String index, Map countMap) {
        // Route dedicated tables
        if (isHttpLogIndex(index)) return countHttpLog(countMap)
        if (isAppLogIndex(index)) return countAppLog(countMap)
        Map result = countResponse(index, countMap)
        return ((Number) result.get("count"))?.longValue() ?: 0L
    }

    private long countHttpLog(Map countMap) {
        TimestampRange range = countMap?.get("query") ? extractTimestampRange((Map) countMap.get("query")) : null
        String sql = "SELECT COUNT(*) FROM moqui_http_log"
        List<Object> params = []
        if (range != null) {
            List<String> conditions = []
            if (range.lte != null) { conditions.add("log_timestamp <= ?"); params.add(range.lte) }
            if (range.gte != null) { conditions.add("log_timestamp >= ?"); params.add(range.gte) }
            if (conditions) sql += " WHERE " + conditions.join(" AND ")
        }
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(sql)
        try {
            for (int i = 0; i < params.size(); i++) setParam(ps, i + 1, params[i])
            ResultSet rs = ps.executeQuery()
            try { return rs.next() ? rs.getLong(1) : 0L } finally { rs.close() }
        } finally { ps.close() }
    }

    private long countAppLog(Map countMap) {
        TimestampRange range = countMap?.get("query") ? extractTimestampRange((Map) countMap.get("query")) : null
        String sql = "SELECT COUNT(*) FROM moqui_logs"
        List<Object> params = []
        if (range != null) {
            List<String> conditions = []
            if (range.lte != null) { conditions.add("log_timestamp <= ?"); params.add(range.lte) }
            if (range.gte != null) { conditions.add("log_timestamp >= ?"); params.add(range.gte) }
            if (conditions) sql += " WHERE " + conditions.join(" AND ")
        }
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(sql)
        try {
            for (int i = 0; i < params.size(); i++) setParam(ps, i + 1, params[i])
            ResultSet rs = ps.executeQuery()
            try { return rs.next() ? rs.getLong(1) : 0L } finally { rs.close() }
        } finally { ps.close() }
    }

    @Override
    Map countResponse(String index, Map countMap) {
        if (!countMap) countMap = [query: [match_all: [:]]]
        Map queryMap = (Map) countMap.get("query")

        List<String> indexNames = resolveIndexNames(index)
        if (indexNames.isEmpty()) return [count: 0L]

        ElasticQueryTranslator.QueryResult qr = queryMap ? translateQuery(queryMap, indexNames) : new ElasticQueryTranslator.QueryResult()

        String idxPlaceholders = indexNames.collect { "?" }.join(", ")
        String whereClause = "index_name IN (${idxPlaceholders})"
        List<Object> allParams = new ArrayList<>(indexNames)

        if (qr.clause && qr.clause != "TRUE") {
            whereClause += " AND " + qr.clause
            allParams.addAll(qr.params)
        }

        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM moqui_document WHERE ${whereClause}")
        try {
            for (int i = 0; i < allParams.size(); i++) setParam(ps, i + 1, allParams[i])
            ResultSet rs = ps.executeQuery()
            try {
                if (rs.next()) return [count: rs.getLong(1)]
                return [count: 0L]
            } finally { rs.close() }
        } finally { ps.close() }
    }

    // ============================================================
    // Point-In-Time (PIT) — Keyset-based cursor
    // ============================================================

    @Override
    String getPitId(String index, String keepAlive) {
        // IMPORTANT / KNOWN LIMITATION (Issue #13 in PROBLEMS_RESOLUTION_PLAN.md):
        // This is a synthetic PIT token — "pg::{index}::{timestamp}" — NOT a real point-in-time
        // snapshot. Unlike Elasticsearch, PostgreSQL has no equivalent of holding a lightweight
        // MVCC-consistent view open across many separate queries without pinning a single long-lived
        // REPEATABLE READ transaction/connection for the whole scroll (which this backend does not do,
        // to avoid holding connections/locks for the duration of a UI-driven scroll). That means rows
        // inserted, updated or deleted between fetches CAN still affect what a caller sees while
        // scrolling through results with this token.
        //
        // What IS guaranteed: search() implements ES-style search_after keyset pagination (ordering by
        // updated_stamp DESC, doc_id ASC as a stable tiebreaker whenever no custom sort or full-text
        // ranking is requested) rather than plain LIMIT/OFFSET. This avoids the classic "shifting
        // window" bug where rows are skipped or duplicated across pages purely because earlier pages'
        // OFFSET shifted due to concurrent inserts/deletes — but it does NOT provide snapshot isolation:
        // a row updated after being read on an earlier page can still appear changed (or, if its sort
        // key changes such that it moves behind the cursor, could be missed) on a later page.
        //
        // A true snapshot would require dedicating a single REPEATABLE READ connection/transaction to
        // the entire scroll and keeping it open until deletePit() is called, which is a heavier
        // architecture change than this best-effort token; not implemented here.
        //
        // Since the caller (ElasticEntityListIterator) passes index=null on every search() call once a
        // PIT id has been obtained (mirroring real Elasticsearch, where the PIT itself identifies the
        // index), we must be able to recover the original index name from the PIT token in search() —
        // see there for the resolution logic.
        return "pg::${index}::${System.currentTimeMillis()}"
    }

    @Override
    void deletePit(String pitId) {
        // No-op for postgres backend
    }

    // ============================================================
    // Raw REST — Not supported on postgres backend
    // ============================================================

    @Override
    RestClient.RestResponse call(Method method, String index, String path,
                                 Map<String, String> parameters, Object bodyJsonObject) {
        throw new UnsupportedOperationException(
                "Raw REST calls (call()) are not supported by PostgresElasticClient for cluster '${clusterName}'. " +
                "Use the higher-level API methods instead, or switch to type=elastic for this cluster.")
    }

    @Override
    Future<RestClient.RestResponse> callFuture(Method method, String index, String path,
                                               Map<String, String> parameters, Object bodyJsonObject) {
        throw new UnsupportedOperationException(
                "Raw REST calls (callFuture()) are not supported by PostgresElasticClient for cluster '${clusterName}'.")
    }

    @Override
    RestClient makeRestClient(Method method, String index, String path, Map<String, String> parameters) {
        throw new UnsupportedOperationException(
                "makeRestClient() is not supported by PostgresElasticClient for cluster '${clusterName}'.")
    }

    // ============================================================
    // DataDocument helpers
    // ============================================================

    @Override
    void checkCreateDataDocumentIndexes(String indexName) {
        if (!indexName) return
        if (indexExists(indexName)) return
        EntityList ddList = ecfi.entityFacade.find("moqui.entity.document.DataDocument")
                .condition("indexName", indexName).disableAuthz().list()
        for (EntityValue dd in ddList) {
            storeIndexAndMapping(indexName, dd)
        }
    }

    @Override
    void checkCreateDataDocumentIndex(String dataDocumentId) {
        String idxName = ElasticFacadeImpl.ddIdToEsIndex(dataDocumentId)
        String prefixed = prefixIndexName(idxName)
        if (indexExists(prefixed)) return

        EntityValue dd = ecfi.entityFacade.find("moqui.entity.document.DataDocument")
                .condition("dataDocumentId", dataDocumentId).disableAuthz().one()
        if (dd == null) throw new BaseException("No DataDocument found with ID [${dataDocumentId}]")
        storeIndexAndMapping((String) dd.getNoCheckSimple("indexName"), dd)
    }

    @Override
    void putDataDocumentMappings(String indexName) {
        EntityList ddList = ecfi.entityFacade.find("moqui.entity.document.DataDocument")
                .condition("indexName", indexName).disableAuthz().list()
        for (EntityValue dd in ddList) storeIndexAndMapping(indexName, dd)
    }

    @Override
    void verifyDataDocumentIndexes(List<Map> documentList) {
        Set<String> indexNames = new HashSet<>()
        Set<String> dataDocumentIds = new HashSet<>()
        for (Map doc in documentList) {
            Object idxObj = doc.get("_index")
            Object typeObj = doc.get("_type")
            if (idxObj) indexNames.add((String) idxObj)
            if (typeObj) dataDocumentIds.add((String) typeObj)
        }
        for (String idxName in indexNames) checkCreateDataDocumentIndexes(idxName)
        for (String ddId in dataDocumentIds) checkCreateDataDocumentIndex(ddId)
    }

    @Override
    void bulkIndexDataDocument(List<Map> documentList) {
        if (!documentList) return

        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(documentUpsertSql)
        try {
            int batchCount = 0
            for (Map document in documentList) {
                String _index = (String) document.get("_index")
                String _type  = (String) document.get("_type")
                String _id    = (String) document.get("_id")

                if (!_id) {
                    logger.warn("bulkIndexDataDocument: skipping document with null _id (type=${_type})")
                    continue
                }

                String esIndexName = ElasticFacadeImpl.ddIdToEsIndex(_type ?: "unknown")
                String prefixedIndex = prefixIndexName(esIndexName)

                Map cleanDoc = new LinkedHashMap<>(document)
                for (String key in DOC_META_KEYS) cleanDoc.remove(key)

                String docJson = objectToJson(cleanDoc)
                String contentText = extractContentText(cleanDoc)

                setUpsertParams(ps, prefixedIndex, _id, _type, docJson, contentText)
                ps.addBatch()
                batchCount++

                if (batchCount >= 500) {
                    ps.executeBatch()
                    batchCount = 0
                }
            }
            if (batchCount > 0) ps.executeBatch()
            logger.info("bulkIndexDataDocument: indexed ${documentList.size()} documents")
        } finally { ps.close() }
    }

    // ============================================================
    // JSON serialization
    // ============================================================

    @Override String objectToJson(Object obj) { return ElasticFacadeImpl.objectToJson(obj) }
    @Override Object jsonToObject(String json) { return ElasticFacadeImpl.jsonToObject(json) }

    // ============================================================
    // Index prefixing helpers
    // ============================================================

    String prefixIndexName(String index) {
        if (!index) return index
        index = index.trim()
        if (!index) return index
        return index.split(",").collect { String it ->
            it = it.trim()
            return (indexPrefix && !it.startsWith(indexPrefix)) ? indexPrefix + it : it
        }.join(",")
    }

    String unprefixIndexName(String index) {
        if (!index || !indexPrefix) return index
        index = index.trim()
        return index.split(",").collect { String it ->
            it = it.trim()
            return (indexPrefix && it.startsWith(indexPrefix)) ? it.substring(indexPrefix.length()) : it
        }.join(",")
    }

    // ============================================================
    // Private helpers
    // ============================================================

    /** Set the 6 parameters on a PreparedStatement using the shared documentUpsertSql */
    private static void setUpsertParams(PreparedStatement ps, String prefixedIndex, String docId, String docType, String docJson, String contentText) {
        ps.setString(1, prefixedIndex)
        ps.setString(2, docId ?: UUID.randomUUID().toString())
        if (docType) ps.setString(3, docType) else ps.setNull(3, Types.VARCHAR)
        ps.setString(4, docJson)
        ps.setString(5, contentText)
        ps.setString(6, contentText)
    }

    private void upsertDocument(String prefixedIndex, String docId, String docType, String docJson, String contentText) {
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(documentUpsertSql)
        try {
            setUpsertParams(ps, prefixedIndex, docId, docType, docJson, contentText)
            ps.executeUpdate()
        } finally { ps.close() }
    }

    static String extractContentText(Map document) {
        if (document == null || document.isEmpty()) return ""
        StringBuilder sb = new StringBuilder()
        extractTextFromValue(document, sb)
        return sb.toString().trim()
    }

    private static void extractTextFromValue(Object value, StringBuilder sb) {
        if (value instanceof Map) {
            for (Map.Entry entry in ((Map) value).entrySet()) {
                Object k = entry.key
                Object v = entry.value
                if (k instanceof String) {
                    String key = (String) k
                    if (!key.endsWith("Id") || key.length() < 20) {
                        extractTextFromValue(v, sb)
                    }
                } else {
                    extractTextFromValue(v, sb)
                }
            }
        } else if (value instanceof List) {
            for (Object item in (List) value) extractTextFromValue(item, sb)
        } else if (value instanceof String) {
            String s = (String) value
            if (s.length() > 0) {
                if (sb.length() > 0) sb.append(' ')
                sb.append(s)
            }
        } else if (value instanceof Number || value instanceof Boolean) {
            if (sb.length() > 0) sb.append(' ')
            sb.append(value.toString())
        }
    }

    protected synchronized void storeIndexAndMapping(String indexName, EntityValue dd) {
        String dataDocumentId = (String) dd.getNoCheckSimple("dataDocumentId")
        String manualMappingServiceName = (String) dd.getNoCheckSimple("manualMappingServiceName")
        String esIndexName = ElasticFacadeImpl.ddIdToEsIndex(dataDocumentId)
        String prefixedIndex = prefixIndexName(esIndexName)

        boolean hasIndex = indexExists(prefixedIndex)
        Map docMapping = ElasticFacadeImpl.makeElasticSearchMapping(dataDocumentId, ecfi)
        Map settings = null

        if (manualMappingServiceName) {
            Map serviceResult = ecfi.service.sync().name(manualMappingServiceName)
                    .parameter("mapping", docMapping).call()
            docMapping = (Map) serviceResult.get("mapping")
            settings = (Map) serviceResult.get("settings")
        }

        if (hasIndex) {
            logger.info("PostgresElasticClient: updating mapping for index '${prefixedIndex}' (${dataDocumentId})")
            putMapping(prefixedIndex, docMapping)
        } else {
            logger.info("PostgresElasticClient: creating index '${prefixedIndex}' for DataDocument '${dataDocumentId}' with alias '${indexName}'")
            createIndex(prefixedIndex, dataDocumentId, docMapping, indexName, settings)
        }
    }

    private List<String> resolveIndexNames(String index) {
        if (!index) {
            return getAllIndexNames()
        }
        List<String> result = []
        for (String part in index.split(",")) {
            String trimmed = part.trim()
            if (!trimmed) continue
            String prefixed = prefixIndexName(trimmed)
            List<String> aliasResolved = resolveAlias(prefixed)
            if (aliasResolved) {
                result.addAll(aliasResolved)
            } else {
                result.add(prefixed)
            }
        }
        return result
    }

    private List<String> resolveAlias(String alias) {
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(
                "SELECT index_name FROM moqui_search_index WHERE alias_name = ?")
        try {
            ps.setString(1, alias)
            ResultSet rs = ps.executeQuery()
            try {
                List<String> names = []
                while (rs.next()) names.add(rs.getString("index_name"))
                return names
            } finally { rs.close() }
        } finally { ps.close() }
    }

    private List<String> getAllIndexNames() {
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement("SELECT index_name FROM moqui_search_index")
        try {
            ResultSet rs = ps.executeQuery()
            try {
                List<String> names = []
                while (rs.next()) names.add(rs.getString("index_name"))
                return names
            } finally { rs.close() }
        } finally { ps.close() }
    }

    private static String buildScoreSelect(ElasticQueryTranslator.TranslatedQuery tq) {
        if (tq.tsqueryExpr) {
            return "ts_rank_cd(content_tsv, ${tq.tsqueryExpr})"
        }
        return "1.0::float"
    }

    // ============================================================
    // HTTP log insert helpers
    // ============================================================

    /** Insert a single HTTP log record into the dedicated moqui_http_log table */
    private void insertHttpLog(Map document) {
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(HTTP_LOG_INSERT_SQL)
        try {
            setHttpLogParams(ps, document)
            ps.executeUpdate()
        } finally { ps.close() }
    }

    /** Bulk insert HTTP log records into the dedicated moqui_http_log table */
    private void bulkInsertHttpLogs(List<Map> documentList) {
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement(HTTP_LOG_INSERT_SQL)
        try {
            int batchSize = 0
            for (Map doc in documentList) {
                setHttpLogParams(ps, doc)
                ps.addBatch()
                batchSize++
                if (batchSize >= 500) {
                    ps.executeBatch()
                    batchSize = 0
                }
            }
            if (batchSize > 0) ps.executeBatch()
        } finally { ps.close() }
    }

    /** Set parameters on an HTTP_LOG_INSERT_SQL PreparedStatement from an HTTP log document map */
    private static void setHttpLogParams(PreparedStatement ps, Map doc) {
        // @timestamp: epoch millis long (from ElasticRequestLogFilter)
        Object tsObj = doc.get("@timestamp")
        if (tsObj instanceof Number) ps.setTimestamp(1, new Timestamp(((Number) tsObj).longValue()))
        else if (tsObj != null) ps.setTimestamp(1, new Timestamp(Long.parseLong(tsObj.toString())))
        else ps.setTimestamp(1, new Timestamp(System.currentTimeMillis()))

        setStrParam(ps, 2, doc.get("remote_ip"))
        setStrParam(ps, 3, doc.get("remote_user"))
        setStrParam(ps, 4, doc.get("server_ip"))
        setStrParam(ps, 5, doc.get("content_type"))
        setStrParam(ps, 6, doc.get("request_method"))
        setStrParam(ps, 7, doc.get("request_scheme"))
        setStrParam(ps, 8, doc.get("request_host"))
        setStrParam(ps, 9, doc.get("request_path"))
        setStrParam(ps, 10, doc.get("request_query"))

        // http_version: stored as text (filter sends a float/half_float)
        Object httpVer = doc.get("http_version")
        setStrParam(ps, 11, httpVer != null ? httpVer.toString() : null)

        // response code
        Object respObj = doc.get("response")
        if (respObj instanceof Number) ps.setInt(12, ((Number) respObj).intValue())
        else if (respObj != null) try { ps.setInt(12, Integer.parseInt(respObj.toString())) } catch (Exception e) { ps.setNull(12, Types.INTEGER) }
        else ps.setNull(12, Types.INTEGER)

        // timing
        setLongParam(ps, 13, doc.get("time_initial_ms"))
        setLongParam(ps, 14, doc.get("time_final_ms"))
        setLongParam(ps, 15, doc.get("bytes"))

        setStrParam(ps, 16, doc.get("referrer"))
        setStrParam(ps, 17, doc.get("agent"))
        setStrParam(ps, 18, doc.get("session"))
        setStrParam(ps, 19, doc.get("visitor_id"))
    }

    private static void setStrParam(PreparedStatement ps, int idx, Object val) {
        if (val == null) ps.setNull(idx, Types.VARCHAR) else ps.setString(idx, val.toString())
    }
    private static void setLongParam(PreparedStatement ps, int idx, Object val) {
        if (val instanceof Number) ps.setLong(idx, ((Number) val).longValue())
        else if (val != null) try { ps.setLong(idx, Long.parseLong(val.toString())) } catch (Exception e) { ps.setNull(idx, Types.BIGINT) }
        else ps.setNull(idx, Types.BIGINT)
    }

    private static void setParam(PreparedStatement ps, int idx, Object value) {
        if (value == null) {
            ps.setNull(idx, Types.VARCHAR)
        } else if (value instanceof String) {
            ps.setString(idx, (String) value)
        } else if (value instanceof Long || value instanceof Integer) {
            ps.setLong(idx, ((Number) value).longValue())
        } else if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            ps.setDouble(idx, ((Number) value).doubleValue())
        } else if (value instanceof Timestamp) {
            ps.setTimestamp(idx, (Timestamp) value)
        } else {
            ps.setString(idx, value.toString())
        }
    }
}
