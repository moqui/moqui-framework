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
class PostgresElasticClient implements ElasticFacade.ElasticClient {
    private final static Logger logger = LoggerFactory.getLogger(PostgresElasticClient.class)
    private final static Set<String> DOC_META_KEYS = new HashSet<>(["_index", "_type", "_id", "_timestamp"])

    /** Jackson mapper shared with ElasticFacadeImpl */
    static final ObjectMapper jacksonMapper = ElasticFacadeImpl.jacksonMapper

    private final ExecutionContextFactoryImpl ecfi
    private final MNode clusterNode
    private final String clusterName
    private final String indexPrefix
    /** Entity datasource group to get connections from (e.g. "transactional") */
    private final String datasourceGroup

    PostgresElasticClient(MNode clusterNode, ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        this.clusterNode = clusterNode
        this.clusterName = clusterNode.attribute("name")
        this.indexPrefix = clusterNode.attribute("index-prefix") ?: ""

        // url attribute for postgres type = datasource group name (or "transactional" by default)
        String urlAttr = clusterNode.attribute("url")
        this.datasourceGroup = (urlAttr && !"".equals(urlAttr.trim())) ? urlAttr.trim() : "transactional"

        logger.info("Initializing PostgresElasticClient for cluster '${clusterName}' using datasource group '${datasourceGroup}' with index prefix '${indexPrefix}'")

        // Initialize schema (CREATE TABLE IF NOT EXISTS, extensions, indexes)
        initSchema()
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
                // Enable pg_trgm extension for fuzzy search (available since PG 9.1)
                stmt.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")

                // moqui_search_index — index metadata (replaces ES index/alias concept)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS moqui_search_index (
                        index_name   TEXT NOT NULL,
                        alias_name   TEXT,
                        doc_type     TEXT,
                        mapping      TEXT,
                        settings     TEXT,
                        created_stamp TIMESTAMPTZ NOT NULL DEFAULT now(),
                        CONSTRAINT pk_moqui_search_index PRIMARY KEY (index_name)
                    )
                """.trim())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mq_sidx_alias ON moqui_search_index (alias_name)")

                // moqui_document — main document store
                stmt.execute("""
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
                """.trim())
                // Ensure PostgreSQL-specific columns exist (table may have been created by Moqui entity sync without them)
                stmt.execute("ALTER TABLE moqui_document ADD COLUMN IF NOT EXISTS content_tsv TSVECTOR")
                stmt.execute("ALTER TABLE moqui_document ADD COLUMN IF NOT EXISTS content_text TEXT")
                // Ensure document column is JSONB (entity sync may create it as TEXT from text-very-long mapping)
                try {
                    stmt.execute("ALTER TABLE moqui_document ALTER COLUMN document TYPE JSONB USING document::jsonb")
                } catch (Exception e) {
                    // Column already JSONB or table has no rows causing cast to fail — ignore
                    logger.trace("Note: could not alter document column to JSONB (may already be correct type): " + e.getMessage())
                }
                // GIN index on tsvector for full-text search
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mq_doc_tsv  ON moqui_document USING GIN (content_tsv)")
                // GIN index on document JSONB for arbitrary path queries
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mq_doc_json ON moqui_document USING GIN (document jsonb_path_ops)")
                // GIN trigram index on content_text for fuzzy/LIKE queries
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mq_doc_trgm ON moqui_document USING GIN (content_text gin_trgm_ops)")
                // Index for type-based filtering
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mq_doc_type ON moqui_document (doc_type)")
                // Index for time-based ordering
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mq_doc_upd  ON moqui_document (index_name, updated_stamp)")

                // moqui_logs — application log (replaces ES moqui_logs index)
                stmt.execute("""
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
                """.trim())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mq_logs_ts  ON moqui_logs USING BRIN (log_timestamp)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mq_logs_lvl ON moqui_logs (log_level)")
                // Fix log_id if Moqui entity sync created the table without a BIGSERIAL default
                stmt.execute("""
                    DO \$\$
                    BEGIN
                        IF (SELECT column_default FROM information_schema.columns
                            WHERE table_name = 'moqui_logs' AND column_name = 'log_id') IS NULL THEN
                            CREATE SEQUENCE IF NOT EXISTS moqui_logs_log_id_seq;
                            ALTER TABLE moqui_logs ALTER COLUMN log_id SET DEFAULT nextval('moqui_logs_log_id_seq');
                            ALTER SEQUENCE moqui_logs_log_id_seq OWNED BY moqui_logs.log_id;
                        END IF;
                    END \$\$;
                """.trim())

                // moqui_http_log — HTTP request log (replaces ES moqui_http_log index)
                stmt.execute("""
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
                """.trim())
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mq_hlog_ts   ON moqui_http_log USING BRIN (log_timestamp)")
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mq_hlog_path ON moqui_http_log (request_path)")
                // Fix log_id if Moqui entity sync created the table without a BIGSERIAL default
                stmt.execute("""
                    DO \$\$
                    BEGIN
                        IF (SELECT column_default FROM information_schema.columns
                            WHERE table_name = 'moqui_http_log' AND column_name = 'log_id') IS NULL THEN
                            CREATE SEQUENCE IF NOT EXISTS moqui_http_log_log_id_seq;
                            ALTER TABLE moqui_http_log ALTER COLUMN log_id SET DEFAULT nextval('moqui_http_log_log_id_seq');
                            ALTER SEQUENCE moqui_http_log_log_id_seq OWNED BY moqui_http_log.log_id;
                        END IF;
                    END \$\$;
                """.trim())

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
     * Get a JDBC Connection from the entity facade for the configured datasource group.
     * The returned Connection is a Moqui ConnectionWrapper that is transaction-managed:
     * close() is a no-op; the connection is automatically closed when the enclosing
     * transaction commits or rolls back via TransactionFacade. Callers MUST ensure an
     * active transaction exists before calling this method.
     */
    private Connection getConnection() {
        return ecfi.entityFacade.getConnection(datasourceGroup)
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
        String prefixed = prefixIndexName(index)
        Connection conn = getConnection()
        // Check both exact index_name and alias_name — mirrors ES behaviour where
        // aliases are treated as valid index references (e.g. "mantle" alias → true)
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
        createIndex(index, null, docMapping, alias, null)
    }

    /** Extended createIndex with docType and settings (used internally and by ElasticFacadeImpl.storeIndexAndMapping) */
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
    }

    @Override
    void deleteIndex(String index) {
        if (!index) throw new IllegalArgumentException("Index name required for deleteIndex")
        String prefixedIndex = prefixIndexName(index)
        Connection conn = getConnection()
        // Delete documents first, then index metadata
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

    @Override
    void index(String index, String _id, Map document) {
        if (!index) throw new IllegalArgumentException("Index name required for index()")
        if (!_id) throw new IllegalArgumentException("_id required for index()")
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
        Connection conn = getConnection()
        // Merge fragment into existing document using PostgreSQL jsonb concatenation operator ||
        PreparedStatement ps = conn.prepareStatement("""
            UPDATE moqui_document
            SET document = COALESCE(document, '{}'::jsonb) || ?::jsonb,
                content_text = (
                    SELECT string_agg(val::text, ' ')
                    FROM jsonb_each_text(COALESCE(document, '{}'::jsonb) || ?::jsonb) AS kv(key, val)
                    WHERE jsonb_typeof(COALESCE(document, '{}'::jsonb) || ?::jsonb -> kv.key) IN ('string', 'number')
                ),
                content_tsv = to_tsvector('english', coalesce((
                    SELECT string_agg(val::text, ' ')
                    FROM jsonb_each_text(COALESCE(document, '{}'::jsonb) || ?::jsonb) AS kv(key, val)
                ), '')),
                updated_stamp = now()
            WHERE index_name = ? AND doc_id = ?
        """.trim())
        try {
            ps.setString(1, fragmentJson)
            ps.setString(2, fragmentJson)
            ps.setString(3, fragmentJson)
            ps.setString(4, fragmentJson)
            ps.setString(5, prefixedIndex)
            ps.setString(6, _id)
            ps.executeUpdate()
        } finally { ps.close() }
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
        String prefixedIndex = prefixIndexName(index)
        ElasticQueryTranslator.QueryResult qr = ElasticQueryTranslator.translateQuery(queryMap ?: [match_all: [:]])

        // Build params: [prefixedIndex] + qr.params
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
            // Two tsvector params for query_string (where + tsquery for ranking) - dedup for DELETE
            // Actually deleteByQuery doesn't need tsquery scoring, already handled by WHERE clause
            return ps.executeUpdate()
        } finally { ps.close() }
    }

    @Override
    void bulk(String index, List<Map> actionSourceList) {
        if (!actionSourceList) return
        String prefixedIndex = index ? prefixIndexName(index) : null

        // Process pairs: action map + source map
        for (int i = 0; i + 1 < actionSourceList.size(); i += 2) {
            Map action = (Map) actionSourceList.get(i)
            Map source = (Map) actionSourceList.get(i + 1)

            // Determine operation type
            if (action.containsKey("index") || action.containsKey("create")) {
                Map actionSpec = (Map) (action.get("index") ?: action.get("create"))
                String idxName = ((String) actionSpec.get("_index")) ?: prefixedIndex
                String _id = (String) actionSpec.get("_id")
                if (idxName) {
                    String docJson = objectToJson(source)
                    String contentText = extractContentText(source)
                    upsertDocument(idxName, _id, null, docJson, contentText)
                }
            } else if (action.containsKey("update")) {
                Map actionSpec = (Map) action.get("update")
                String idxName = ((String) actionSpec.get("_index")) ?: prefixedIndex
                String _id = (String) actionSpec.get("_id")
                if (idxName && _id) {
                    Map doc = (Map) source.get("doc") ?: source
                    update(idxName, _id, doc)
                }
            } else if (action.containsKey("delete")) {
                Map actionSpec = (Map) action.get("delete")
                String idxName = ((String) actionSpec.get("_index")) ?: prefixedIndex
                String _id = (String) actionSpec.get("_id")
                if (idxName && _id) delete(idxName, _id)
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
        String prefixedIndex = prefixIndexName(index)
        boolean hasId = idField != null && !idField.isEmpty()

        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO moqui_document (index_name, doc_id, doc_type, document, content_text, content_tsv, updated_stamp)
            VALUES (?, ?, ?, ?::jsonb, ?, to_tsvector('english', COALESCE(?, '')), now())
            ON CONFLICT (index_name, doc_id) DO UPDATE SET
                doc_type      = EXCLUDED.doc_type,
                document      = EXCLUDED.document,
                content_text  = EXCLUDED.content_text,
                content_tsv   = EXCLUDED.content_tsv,
                updated_stamp = EXCLUDED.updated_stamp
        """.trim())
        try {
            int batchSize = 0
            for (Map doc in documentList) {
                String _id = hasId ? (doc.get(idField)?.toString() ?: UUID.randomUUID().toString()) : UUID.randomUUID().toString()
                String docJson = objectToJson(doc)
                String contentText = extractContentText(doc)
                ps.setString(1, prefixedIndex)
                ps.setString(2, _id)
                if (docType) ps.setString(3, docType) else ps.setNull(3, Types.VARCHAR)
                ps.setString(4, docJson)
                ps.setString(5, contentText)
                ps.setString(6, contentText)
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
        // Special case: moqui_logs is a dedicated table, not stored in moqui_document
        if (index && (index == 'moqui_logs' || prefixIndexName(index) == prefixIndexName('moqui_logs'))) {
            return searchLogsTable(searchMap ?: [:])
        }

        ElasticQueryTranslator.TranslatedQuery tq = ElasticQueryTranslator.translateSearchMap(searchMap ?: [:])

        // Determine index(es) to query
        List<String> indexNames = resolveIndexNames(index)
        if (indexNames.isEmpty()) {
            return [hits: [total: [value: 0, relation: "eq"], hits: []]]
        }

        // Build score expression
        String scoreExpr = tq.tsqueryExpr ?
                "ts_rank_cd(content_tsv, ${tq.tsqueryExpr})" : "1.0::float"

        // Build WHERE clause
        String idxPlaceholders = indexNames.collect { "?" }.join(", ")
        String whereClause = "index_name IN (${idxPlaceholders})"
        List<Object> allParams = new ArrayList<>(indexNames)

        if (tq.tsqueryExpr) {
            // For query_string: add the tsquery param for the WHERE clause
            whereClause += " AND " + tq.whereClause
            allParams.addAll(tq.params)
        } else if (tq.whereClause && tq.whereClause != "TRUE") {
            whereClause += " AND " + tq.whereClause
            allParams.addAll(tq.params)
        }

        // Add tsquery param for score expression if needed
        List<Object> scoreParams = []
        if (tq.tsqueryExpr) {
            scoreParams.addAll(tq.params) // same param(s) as the where clause tsquery
        }

        // ORDER BY
        String orderByClause = tq.orderBy ?: (tq.tsqueryExpr ? "_score DESC" : "updated_stamp DESC")

        // Build count query first
        String countSql = "SELECT COUNT(*) FROM moqui_document WHERE ${whereClause}"
        long totalCount = 0L

        // Build main query
        String mainSql = """
            SELECT doc_id, index_name, doc_type, document, ${buildScoreSelect(tq)} AS _score
            FROM moqui_document
            WHERE ${whereClause}
            ORDER BY ${orderByClause}
            LIMIT ? OFFSET ?
        """.trim()

        Connection conn = getConnection()

        // Execute count
        if (tq.trackTotal) {
            PreparedStatement countPs = conn.prepareStatement(countSql)
            try {
                for (int i = 0; i < allParams.size(); i++) setParam(countPs, i + 1, allParams[i])
                // Add score params to count query (it doesn't have them, so skip)
                ResultSet rs = countPs.executeQuery()
                try { if (rs.next()) totalCount = rs.getLong(1) } finally { rs.close() }
            } finally { countPs.close() }
        }

        // Execute main query — params must follow SQL ? order:
        // 1. score expression ? (in SELECT clause, comes before WHERE in SQL)
        // 2. WHERE clause ?s (index names + query params already in allParams)
        // 3. LIMIT and OFFSET
        List<Object> mainParams = []
        if (tq.tsqueryExpr) mainParams.addAll(tq.params) // score SELECT clause comes first in SQL
        mainParams.addAll(allParams) // WHERE clause: indexNames then query params
        mainParams.add(tq.sizeLimit)
        mainParams.add(tq.fromOffset)

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

                    // Add highlights if requested
                    if (tq.highlightFields && tq.tsqueryExpr) {
                        Map<String, List<String>> highlights = buildHighlights(source, tq)
                        if (highlights) hit.put("highlight", highlights)
                    }

                    hits.add(hit)
                }

                return [hits: [total: [value: totalCount, relation: "eq"], hits: hits],
                        _shards: [total: 1, successful: 1, failed: 0]]
            } finally { rs.close() }
        } finally { ps.close() }
    }

    /** Query the moqui_logs table directly and return ES-compatible response. */
    private Map searchLogsTable(Map searchMap) {
        ElasticQueryTranslator.TranslatedQuery tq = ElasticQueryTranslator.translateSearchMap(searchMap)

        // ── Parse @timestamp range from the original query_string ──
        // LogViewer sends queries like: @timestamp:[1740610800000 TO 1741215600000] AND (*)
        // The translator strips these, so we extract them here for direct SQL use.
        String rawQuery = null
        Map queryMap = (Map) searchMap?.get("query")
        if (queryMap) {
            Map qsMap = (Map) queryMap.get("query_string")
            if (qsMap) rawQuery = (String) qsMap.get("query")
        }

        List<String> conditions = []
        List<Object> params = []

        // Extract @timestamp range:  @timestamp:[from TO to]
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

        // FTS WHERE clause against message + logger_name columns
        // The original query_string may be: "@timestamp:[epoch TO epoch] AND (<userQuery>)"
        // The translator's cleanLuceneQuery doesn't fully strip the @timestamp parts,
        // leaving residue like "@ AND" as the tsquery text. We need to extract only the
        // user's text query portion and apply FTS only if it's meaningful.
        String userTextQuery = null
        if (rawQuery) {
            // Remove the @timestamp range clause and connectors
            String stripped = rawQuery.replaceAll(/@timestamp\s*:\s*\[[^\]]*\]/, '')
            stripped = stripped.replaceAll(/\bAND\b/, ' ').replaceAll(/\bOR\b/, ' ')
            stripped = stripped.replaceAll(/[()]/, ' ').replaceAll(/\s+/, ' ').trim()
            // After stripping, if only * or empty remains, it means "match all" — no FTS needed
            stripped = stripped.replaceAll(/\*/, '').trim()
            if (stripped) userTextQuery = stripped
        }
        if (userTextQuery) {
            conditions.add("to_tsvector('english', coalesce(message, '') || ' ' || coalesce(logger_name, '')) @@ websearch_to_tsquery('english', ?)")
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
                return [hits: [total: [value: totalCount, relation: "eq"], hits: hits],
                        _shards: [total: 1, successful: 1, failed: 0]]
            } finally { rs.close() }
        } catch (Throwable t) {
            logger.error("searchLogsTable error: " + t.message, t)
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
        // Best-effort validation: try to translate the query; if it throws return invalid
        try {
            ElasticQueryTranslator.QueryResult qr = ElasticQueryTranslator.translateQuery(queryMap ?: [match_all: [:]])
            return null  // valid
        } catch (Throwable t) {
            return [valid: false, error: t.message]
        }
    }

    @Override
    long count(String index, Map countMap) {
        Map result = countResponse(index, countMap)
        return ((Number) result.get("count"))?.longValue() ?: 0L
    }

    @Override
    Map countResponse(String index, Map countMap) {
        if (!countMap) countMap = [query: [match_all: [:]]]
        Map queryMap = (Map) countMap.get("query")
        ElasticQueryTranslator.QueryResult qr = queryMap ? ElasticQueryTranslator.translateQuery(queryMap) : new ElasticQueryTranslator.QueryResult()

        List<String> indexNames = resolveIndexNames(index)
        if (indexNames.isEmpty()) return [count: 0L]

        String idxPlaceholders = indexNames.collect { "?" }.join(", ")
        String whereClause = "index_name IN (${idxPlaceholders})"
        List<Object> allParams = new ArrayList<>(indexNames)

        if (qr.clause && qr.clause != "TRUE") {
            whereClause += " AND " + qr.clause
            allParams.addAll(qr.params)
        }

        // For tsvector queries, add the param for WHERE
        if (qr.tsqueryExpr && qr.params) {
            // params already added above
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
        // For postgres backend, return a synthetic PIT token: "pg::{indexPrefix}::{timestamp}"
        // Actual cursor-based paging is left to the caller to handle via search_after
        return "pg::${indexPrefix}::${System.currentTimeMillis()}"
    }

    @Override
    void deletePit(String pitId) {
        // No-op for postgres backend — cursor is stateless
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
        // If any document type for this index exists, we consider the index ready
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
        PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO moqui_document (index_name, doc_id, doc_type, document, content_text, content_tsv, updated_stamp)
            VALUES (?, ?, ?, ?::jsonb, ?, to_tsvector('english', COALESCE(?, '')), now())
            ON CONFLICT (index_name, doc_id) DO UPDATE SET
                doc_type      = EXCLUDED.doc_type,
                document      = EXCLUDED.document,
                content_text  = EXCLUDED.content_text,
                content_tsv   = EXCLUDED.content_tsv,
                updated_stamp = EXCLUDED.updated_stamp
        """.trim())
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

                // Derive the actual index name from _type (dataDocumentId)
                String esIndexName = ElasticFacadeImpl.ddIdToEsIndex(_type ?: "unknown")
                String prefixedIndex = prefixIndexName(esIndexName)

                // Clone document stripping metadata keys
                Map cleanDoc = new LinkedHashMap<>(document)
                for (String key in DOC_META_KEYS) cleanDoc.remove(key)

                String docJson = objectToJson(cleanDoc)
                String contentText = extractContentText(cleanDoc)

                ps.setString(1, prefixedIndex)
                ps.setString(2, _id)
                if (_type) ps.setString(3, _type) else ps.setNull(3, Types.VARCHAR)
                ps.setString(4, docJson)
                ps.setString(5, contentText)
                ps.setString(6, contentText)
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
        // Handle comma-separated index names
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

    private void upsertDocument(String prefixedIndex, String docId, String docType, String docJson, String contentText) {
        Connection conn = getConnection()
        PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO moqui_document (index_name, doc_id, doc_type, document, content_text, content_tsv, updated_stamp)
            VALUES (?, ?, ?, ?::jsonb, ?, to_tsvector('english', COALESCE(?, '')), now())
            ON CONFLICT (index_name, doc_id) DO UPDATE SET
                doc_type      = EXCLUDED.doc_type,
                document      = EXCLUDED.document,
                content_text  = EXCLUDED.content_text,
                content_tsv   = EXCLUDED.content_tsv,
                updated_stamp = EXCLUDED.updated_stamp
        """.trim())
        try {
            ps.setString(1, prefixedIndex)
            ps.setString(2, docId ?: UUID.randomUUID().toString())
            if (docType) ps.setString(3, docType) else ps.setNull(3, Types.VARCHAR)
            ps.setString(4, docJson)
            ps.setString(5, contentText)
            ps.setString(6, contentText)
            ps.executeUpdate()
        } finally { ps.close() }
    }

    /**
     * Extract all text values from a document Map recursively for full-text search indexing.
     * Concatenates strings from all levels of the document, space-separated.
     */
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
                // Skip non-text keys that are typically IDs or metadata
                if (k instanceof String) {
                    String key = (String) k
                    // Include most fields except pure-ID fields that add noise
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
            // Include numbers as text (useful for numeric search)
            if (sb.length() > 0) sb.append(' ')
            sb.append(value.toString())
        }
        // Skip null, Timestamp, Date etc. — not useful for full-text
    }

    /**
     * Store index and mapping information for a DataDocument.
     * This is the postgres equivalent of ElasticClientImpl.storeIndexAndMapping().
     */
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

    /**
     * Resolve comma-separated or single index name(s) to prefixed list.
     * Also handles cases where the index might be an alias.
     */
    private List<String> resolveIndexNames(String index) {
        if (!index) {
            // Query all documents if no index specified
            return getAllIndexNames()
        }
        List<String> result = []
        for (String part in index.split(",")) {
            String trimmed = part.trim()
            if (!trimmed) continue
            String prefixed = prefixIndexName(trimmed)
            // Try alias resolution first — an alias maps to one or more concrete indices
            List<String> aliasResolved = resolveAlias(prefixed)
            if (aliasResolved) {
                result.addAll(aliasResolved)
            } else {
                // Not an alias — treat as an exact index name
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

    /** Build the SELECT expression for _score (depends on whether we have a tsquery or not) */
    private static String buildScoreSelect(ElasticQueryTranslator.TranslatedQuery tq) {
        if (tq.tsqueryExpr) {
            return "ts_rank_cd(content_tsv, ${tq.tsqueryExpr})"
        }
        return "1.0::float"
    }

    /** Build highlight maps for a search result document */
    private static Map<String, List<String>> buildHighlights(Map source, ElasticQueryTranslator.TranslatedQuery tq) {
        // highlights are expensive — compute them in-memory with simple string matching
        // For full ts_headline support, would need a separate SQL call per field
        Map<String, List<String>> highlights = [:]
        if (!tq.tsqueryExpr || !tq.highlightFields) return highlights
        // Extract simple query terms for basic highlighting
        String firstParam = tq.params ? tq.params[0]?.toString() : null
        if (!firstParam) return highlights
        for (String field in tq.highlightFields.keySet()) {
            Object fieldVal = source.get(field)
            if (fieldVal instanceof String) {
                String text = (String) fieldVal
                // Simple highlight: surround occurrences of query terms with <em> tags
                String highlighted = simpleHighlight(text, firstParam)
                if (highlighted != text) highlights.put(field, [highlighted])
            }
        }
        return highlights
    }

    private static String simpleHighlight(String text, String query) {
        if (!text || !query) return text
        // Extract individual terms from query (ignore operators and quoted phrases for simplicity)
        List<String> terms = query.replaceAll(/["()+\-]/, ' ').split(/\s+/).findAll { it.length() > 2 } as List
        String result = text
        for (String term in terms) {
            result = result.replaceAll("(?i)\\b${java.util.regex.Pattern.quote(term)}\\b", "<em>\$0</em>")
        }
        return result
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
