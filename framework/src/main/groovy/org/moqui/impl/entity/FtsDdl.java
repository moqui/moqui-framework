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
package org.moqui.impl.entity;

import org.h2.fulltext.FullText;
import org.moqui.util.MNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idempotent full-text artifacts for {@code text-fts} columns. Not entity indexes.
 * Owned columns ({@code *_tsv}) must not be dropped by startup-add-missing.
 */
public final class FtsDdl {
    private static final Logger logger = LoggerFactory.getLogger(FtsDdl.class);
    private static final Set<String> H2_READY = ConcurrentHashMap.newKeySet();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private FtsDdl() { }

    public static boolean ownedColumn(String columnName) {
        return columnName != null && columnName.toLowerCase().endsWith("_tsv");
    }

    public static void ensure(EntityFacadeImpl efi, EntityDefinition ed) {
        ensure(efi, ed, null);
    }

    public static void ensure(EntityFacadeImpl efi, EntityDefinition ed, Connection sharedCon) {
        if (efi == null || ed == null || ed.isViewEntity) return;
        List<FieldInfo> fields = ftsFields(ed);
        if (fields.isEmpty()) return;
        String groupName = ed.getEntityGroupName();
        MNode databaseNode = efi.getDatabaseNode(groupName);
        if (databaseNode == null) return;
        String style = databaseNode.attribute("fts-style");
        if (style == null || style.isEmpty() || "none".equals(style) || FtsSql.isDisabled(groupName)) return;
        Connection con = sharedCon;
        boolean close = false;
        boolean began = false;
        try {
            if (con == null) {
                if (!efi.ecfi.transactionFacade.isTransactionInPlace())
                    began = efi.ecfi.transactionFacade.begin(60);
                con = efi.getConnection(groupName);
                close = true;
            }
            if ("h2-native".equals(style)) ensureH2(con, ed, fields);
            else if ("postgres-tsvector".equals(style)) ensurePostgres(con, databaseNode, ed, fields);
            else if ("mysql-fulltext".equals(style)) ensureMysql(con, ed, fields);
            else if ("mssql-contains".equals(style) || "oracle-text".equals(style)) {
                warnOnce(groupName, style + " finds use SQL LIKE until a full-text catalog is added");
                FtsSql.disable(groupName);
            }
            if (began) efi.ecfi.transactionFacade.commit();
        } catch (Throwable t) {
            logger.warn("FTS setup failed for {} ({}): {}", ed.getFullEntityName(), style, t.getMessage());
            FtsSql.disable(groupName);
            if (began) {
                try { efi.ecfi.transactionFacade.rollback(began, "FTS setup", t); }
                catch (Throwable ignored) { }
            }
        } finally {
            if (close && con != null) {
                try { if (!con.isClosed()) con.close(); }
                catch (SQLException ignored) { }
            }
        }
    }

    private static void ensureH2(Connection con, EntityDefinition ed, List<FieldInfo> fields) throws SQLException {
        String schema = ed.entityInfo.schemaName != null && !ed.entityInfo.schemaName.isEmpty()
                ? ed.entityInfo.schemaName : "PUBLIC";
        String table = ed.getTableName();
        String key = schema + "." + table;
        FullText.init(con);
        if (H2_READY.contains(key)) return;
        StringBuilder cols = new StringBuilder();
        for (FieldInfo fi : fields) {
            if (cols.length() > 0) cols.append(',');
            cols.append(fi.columnName);
        }
        if (indexExists(con, schema, table)) {
            H2_READY.add(key);
            return;
        }
        FullText.createIndex(con, schema, table, cols.toString());
        H2_READY.add(key);
        logger.info("H2 full-text index on {}.{} ({})", schema, table, cols);
    }

    private static boolean indexExists(Connection con, String schema, String table) {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM FT.INDEXES WHERE SCHEMA = ? AND \"TABLE\" = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private static void ensurePostgres(Connection con, MNode databaseNode, EntityDefinition ed, List<FieldInfo> fields)
            throws SQLException {
        String config = databaseNode.attribute("fts-config");
        if (config == null || config.isEmpty() || !config.matches("[A-Za-z][A-Za-z0-9_]*")) config = "english";
        String table = ed.getFullTableName();
        try (Statement st = con.createStatement()) {
            for (FieldInfo fi : fields) {
                String tsv = fi.columnName + "_tsv";
                String index = clip("fts_" + ed.getTableName() + "_" + fi.columnName);
                st.execute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + tsv
                        + " tsvector GENERATED ALWAYS AS (to_tsvector('" + config + "', coalesce("
                        + fi.columnName + ", ''))) STORED");
                st.execute("CREATE INDEX IF NOT EXISTS " + index + " ON " + table + " USING GIN (" + tsv + ")");
            }
        }
    }

    private static void ensureMysql(Connection con, EntityDefinition ed, List<FieldInfo> fields) throws SQLException {
        String table = ed.getFullTableName();
        try (Statement st = con.createStatement()) {
            for (FieldInfo fi : fields) {
                String index = clip(ed.getTableName() + "_" + fi.columnName + "_FTS");
                try {
                    st.execute("ALTER TABLE " + table + " ADD FULLTEXT INDEX " + index + " (" + fi.columnName + ")");
                } catch (SQLException e) {
                    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    if (!msg.contains("duplicate") && !msg.contains("already exists")) throw e;
                }
            }
        }
    }

    static List<FieldInfo> ftsFields(EntityDefinition ed) {
        List<FieldInfo> out = new ArrayList<>();
        FieldInfo[] all = ed.entityInfo != null ? ed.entityInfo.allFieldInfoArray : null;
        if (all == null) return out;
        for (FieldInfo fi : all) {
            if (fi != null && FtsSql.TYPE.equals(fi.type)) out.add(fi);
        }
        return out;
    }

    private static String clip(String name) {
        String n = name.replaceAll("[^A-Za-z0-9_]", "_");
        return n.length() <= 60 ? n : n.substring(0, 60);
    }

    private static void warnOnce(String groupName, String message) {
        if (WARNED.add(groupName + ":" + message)) logger.warn(message);
    }
}
