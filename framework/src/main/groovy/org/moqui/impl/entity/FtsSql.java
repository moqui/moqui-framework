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

import org.moqui.entity.EntityCondition;
import org.moqui.impl.context.TransactionCacheDb;
import org.moqui.impl.entity.EntityJavaUtil.EntityConditionParameter;
import org.moqui.util.MNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LIKE / NOT LIKE rewrite for {@code text-fts} fields. Boolean AND of tokens.
 * Prefix-only patterns stay SQL LIKE. HOLD overlays and {@code fts-style=none} stay SQL LIKE.
 */
public final class FtsSql {
    public static final String TYPE = "text-fts";
    private static final Set<String> DISABLED = ConcurrentHashMap.newKeySet();

    private FtsSql() { }

    public static void disable(String groupName) {
        if (groupName != null && !groupName.isEmpty()) DISABLED.add(groupName);
    }

    public static boolean isDisabled(String groupName) {
        return groupName != null && DISABLED.contains(groupName);
    }

    /** True when this pattern is {@code foo%} with no other wildcard. Those stay SQL LIKE. */
    public static boolean prefixOnly(String pattern) {
        if (pattern == null || pattern.length() < 2) return false;
        if (pattern.indexOf('_') >= 0) return false;
        if (!pattern.endsWith("%")) return false;
        return pattern.indexOf('%') == pattern.length() - 1;
    }

    public static String stripWildcards(String pattern) {
        if (pattern == null) return "";
        StringBuilder sb = new StringBuilder(pattern.length());
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c != '%' && c != '_') sb.append(c);
        }
        return sb.toString().trim();
    }

    /** Tokens of length &gt;= 3. Shorter words are dropped, same floor as SkillIndex.score. */
    public static List<String> tokens(String stripped) {
        if (stripped == null || stripped.isEmpty()) return Collections.emptyList();
        String[] parts = stripped.split("\\s+");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            if (part == null) continue;
            String tok = part.trim();
            if (tok.length() >= 3) out.add(tok);
        }
        return out;
    }

    /**
     * @return true when the predicate was written (FTS or an empty-query 1=1/1=2). False means the caller emits SQL LIKE.
     */
    public static boolean appendIfRewritten(EntityQueryBuilder eqb, EntityDefinition ed, FieldInfo fi,
            EntityCondition.ComparisonOperator operator, Object value) {
        if (eqb == null || ed == null || fi == null || !TYPE.equals(fi.type)) return false;
        if (operator != EntityCondition.ComparisonOperator.LIKE
                && operator != EntityCondition.ComparisonOperator.NOT_LIKE) return false;
        if (!(value instanceof CharSequence)) return false;
        String pattern = value.toString();
        if (prefixOnly(pattern)) return false;

        String groupName = ed.getEntityGroupName();
        if (isDisabled(groupName) || holdOverlay(eqb)) return false;
        MNode databaseNode = eqb.efi.getDatabaseNode(groupName);
        String style = databaseNode != null ? databaseNode.attribute("fts-style") : null;
        if (style == null || style.isEmpty() || "none".equals(style)) return false;

        boolean not = operator == EntityCondition.ComparisonOperator.NOT_LIKE;
        List<String> toks = tokens(stripWildcards(pattern));
        StringBuilder sql = eqb.sqlTopLevel;
        if (toks.isEmpty()) {
            sql.append(not ? " 1 = 1 " : " 1 = 2 ");
            return true;
        }
        String column = fi.getFullColumnName() != null ? fi.getFullColumnName() : fi.columnName;
        if (not) sql.append(" NOT (");
        boolean wrote = false;
        if ("h2-native".equals(style)) wrote = appendH2(eqb, ed, column, toks);
        else if ("postgres-tsvector".equals(style)) wrote = appendPostgres(eqb, databaseNode, fi, toks);
        else if ("mysql-fulltext".equals(style)) wrote = appendMysql(eqb, fi, toks);
        else if ("mssql-contains".equals(style)) wrote = appendMssql(eqb, fi, toks);
        else if ("oracle-text".equals(style)) wrote = appendOracle(eqb, fi, toks);
        if (!wrote) {
            if (not) {
                int start = sql.lastIndexOf(" NOT (");
                if (start >= 0) sql.delete(start, sql.length());
            }
            return false;
        }
        // Per-field filter. H2's one index covers every text-fts column on the table.
        for (String tok : toks) {
            sql.append(" AND ").append(column).append(" ILIKE ?");
            eqb.parameters.add(new EntityConditionParameter(fi, "%" + tok + "%", eqb));
        }
        if (not) sql.append(')');
        return true;
    }

    private static boolean holdOverlay(EntityQueryBuilder eqb) {
        try {
            Object active = eqb.efi.getActiveTxCache();
            return active instanceof TransactionCacheDb && ((TransactionCacheDb) active).isHold();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean appendH2(EntityQueryBuilder eqb, EntityDefinition ed, String pkReadyColumn, List<String> toks) {
        FieldInfo[] pks = ed.entityInfo != null ? ed.entityInfo.pkFieldInfoArray : null;
        if (pks == null || pks.length != 1) return false;
        FieldInfo pk = pks[0];
        String pkCol = pk.getFullColumnName() != null ? pk.getFullColumnName() : pk.columnName;
        String schema = ed.entityInfo != null && ed.entityInfo.schemaName != null && !ed.entityInfo.schemaName.isEmpty()
                ? ed.entityInfo.schemaName : "PUBLIC";
        String table = ed.getTableName();
        StringBuilder sql = eqb.sqlTopLevel;
        sql.append("EXISTS (SELECT 1 FROM FT_SEARCH_DATA(?, 0, 0) FTS WHERE FTS.SCHEMA = ? AND FTS.\"TABLE\" = ? AND FTS.\"KEYS\"[1] = ")
                .append(pkCol).append(')');
        eqb.parameters.add(new EntityConditionParameter(pk, String.join(" ", toks), eqb));
        eqb.parameters.add(new EntityConditionParameter(pk, schema, eqb));
        eqb.parameters.add(new EntityConditionParameter(pk, table, eqb));
        return pkReadyColumn != null;
    }

    private static boolean appendPostgres(EntityQueryBuilder eqb, MNode databaseNode, FieldInfo fi, List<String> toks) {
        String config = databaseNode.attribute("fts-config");
        if (config == null || config.isEmpty()) config = "english";
        if (!config.matches("[A-Za-z][A-Za-z0-9_]*")) config = "english";
        String tsv = fi.columnName + "_tsv";
        String qualified = fi.getFullColumnName();
        if (qualified != null && qualified.endsWith(fi.columnName)) {
            tsv = qualified.substring(0, qualified.length() - fi.columnName.length()) + tsv;
        }
        eqb.sqlTopLevel.append(tsv).append(" @@ plainto_tsquery('").append(config).append("', ?)");
        eqb.parameters.add(new EntityConditionParameter(fi, String.join(" ", toks), eqb));
        return true;
    }

    private static boolean appendMysql(EntityQueryBuilder eqb, FieldInfo fi, List<String> toks) {
        StringBuilder against = new StringBuilder();
        for (String tok : toks) {
            if (against.length() > 0) against.append(' ');
            against.append('+').append(tok.replace("+", "").replace("-", ""));
        }
        eqb.sqlTopLevel.append("MATCH(").append(fi.columnName).append(") AGAINST (? IN BOOLEAN MODE)");
        eqb.parameters.add(new EntityConditionParameter(fi, against.toString(), eqb));
        return true;
    }

    private static boolean appendMssql(EntityQueryBuilder eqb, FieldInfo fi, List<String> toks) {
        eqb.sqlTopLevel.append("CONTAINS(").append(fi.columnName).append(", ?)");
        eqb.parameters.add(new EntityConditionParameter(fi, "\"" + String.join("\" AND \"", toks) + "\"", eqb));
        return true;
    }

    private static boolean appendOracle(EntityQueryBuilder eqb, FieldInfo fi, List<String> toks) {
        eqb.sqlTopLevel.append("CONTAINS(").append(fi.columnName).append(", ?, 1) > 0");
        eqb.parameters.add(new EntityConditionParameter(fi, String.join(" AND ", toks), eqb));
        return true;
    }
}
