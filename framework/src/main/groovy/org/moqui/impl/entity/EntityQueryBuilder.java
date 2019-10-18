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

import org.moqui.entity.EntityException;
import org.moqui.impl.entity.EntityJavaUtil.EntityConditionParameter;
import org.moqui.impl.entity.EntityJavaUtil.FieldOrderOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;

public class EntityQueryBuilder {
    protected static final Logger logger = LoggerFactory.getLogger(EntityQueryBuilder.class);
    static final boolean isDebugEnabled = logger.isDebugEnabled();

    public final EntityFacadeImpl efi;
    public final EntityDefinition mainEntityDefinition;

    private static final int sqlInitSize = 500;
    public final StringBuilder sqlTopLevel = new StringBuilder(sqlInitSize);
    String finalSql = null;

    private static final int parametersInitSize = 20;
    public final ArrayList<EntityConditionParameter> parameters = new ArrayList<>(parametersInitSize);

    protected PreparedStatement ps = null;
    private ResultSet rs = null;
    protected Connection connection = null;
    private boolean externalConnection = false;

    public EntityQueryBuilder(EntityDefinition entityDefinition, EntityFacadeImpl efi) {
        this.mainEntityDefinition = entityDefinition;
        this.efi = efi;
    }

    public EntityDefinition getMainEd() { return mainEntityDefinition; }

    Connection makeConnection(boolean useClone) {
        connection = efi.getConnection(mainEntityDefinition.getEntityGroupName(), useClone);
        return connection;
    }

    void useConnection(Connection c) {
        connection = c;
        externalConnection = true;
    }

    protected static void handleSqlException(Exception e, String sql) {
        throw new EntityException("SQL Exception with statement:" + sql + "; " + e.toString(), e);
    }

    public PreparedStatement makePreparedStatement() {
        if (connection == null)
            throw new IllegalStateException("Cannot make PreparedStatement, no Connection in place");
        finalSql = sqlTopLevel.toString();
        // if (this.mainEntityDefinition.getFullEntityName().contains("foo")) logger.warn("========= making crud PreparedStatement for SQL: ${sql}")
        if (isDebugEnabled) logger.debug("making crud PreparedStatement for SQL: " + finalSql);
        try {
            ps = connection.prepareStatement(finalSql);
        } catch (SQLException sqle) {
            handleSqlException(sqle, finalSql);
        }

        return ps;
    }

    ResultSet executeQuery() throws SQLException {
        if (ps == null) throw new IllegalStateException("Cannot Execute Query, no PreparedStatement in place");
        boolean isError = false;
        boolean queryStats = efi.getQueryStats();
        long beforeQuery = queryStats ? System.nanoTime() : 0;
        try {
            final long timeBefore = isDebugEnabled ? System.currentTimeMillis() : 0L;
            rs = ps.executeQuery();
            if (isDebugEnabled) logger.debug("Executed query with SQL [" + finalSql +
                    "] and parameters [" + parameters + "] in [" +
                    ((System.currentTimeMillis() - timeBefore) / 1000) + "] seconds");
            return rs;
        } catch (SQLException sqle) {
            isError = true;
            logger.warn("Error in JDBC query for SQL " + finalSql);
            throw sqle;
        } finally {
            if (queryStats) efi.saveQueryStats(mainEntityDefinition, finalSql, System.nanoTime() - beforeQuery, isError);
        }
    }

    int executeUpdate() throws SQLException {
        if (ps == null) throw new IllegalStateException("Cannot Execute Update, no PreparedStatement in place");
        boolean isError = false;
        boolean queryStats = efi.getQueryStats();
        long beforeQuery = queryStats ? System.nanoTime() : 0;
        try {
            final long timeBefore = isDebugEnabled ? System.currentTimeMillis() : 0L;
            final int rows = ps.executeUpdate();
            if (isDebugEnabled) logger.debug("Executed update with SQL [" + finalSql +
                    "] and parameters [" + parameters + "] in [" +
                    ((System.currentTimeMillis() - timeBefore) / 1000) + "] seconds changing [" +
                    rows + "] rows");
            return rows;
        } catch (SQLException sqle) {
            isError = true;
            logger.warn("Error in JDBC update for SQL " + finalSql);
            throw sqle;
        } finally {
            if (queryStats) efi.saveQueryStats(mainEntityDefinition, finalSql, System.nanoTime() - beforeQuery, isError);
        }
    }

    /** NOTE: this should be called in a finally clause to make sure things are closed */
    void closeAll() throws SQLException {
        if (ps != null) {
            ps.close();
            ps = null;
        }
        if (rs != null) {
            rs.close();
            rs = null;
        }
        if (connection != null && !externalConnection) {
            connection.close();
            connection = null;
        }
    }

    /** For when closing to be done in other places, like a EntityListIteratorImpl */
    void releaseAll() {
        ps = null;
        rs = null;
        connection = null;
    }

    public static String sanitizeColumnName(String colName) {
        StringBuilder interim = new StringBuilder(colName);
        boolean lastUnderscore = false;
        for (int i = 0; i < interim.length(); ) {
            char curChar = interim.charAt(i);
            if (Character.isLetterOrDigit(curChar)) {
                i++;
                lastUnderscore = false;
            } else {
                if (lastUnderscore) {
                    interim.deleteCharAt(i);
                } else {
                    interim.setCharAt(i, '_');
                    i++;
                    lastUnderscore = true;
                }
            }
        }
        while (interim.charAt(0) == '_') interim.deleteCharAt(0);
        while (interim.charAt(interim.length() - 1) == '_') interim.deleteCharAt(interim.length() - 1);
        int duIdx; while ((duIdx = interim.indexOf("__")) >= 0) interim.deleteCharAt(duIdx);
        return interim.toString();
    }

    void setPreparedStatementValue(int index, Object value, FieldInfo fieldInfo) throws EntityException {
        fieldInfo.setPreparedStatementValue(this.ps, index, value, this.mainEntityDefinition, this.efi);
    }

    void setPreparedStatementValues() {
        // set all of the values from the SQL building in efb
        ArrayList<EntityConditionParameter> parms = parameters;
        int size = parms.size();
        for (int i = 0; i < size; i++) {
            EntityConditionParameter entityConditionParam = parms.get(i);
            entityConditionParam.setPreparedStatementValue(i + 1);
        }
    }

    public void makeSqlSelectFields(FieldInfo[] fieldInfoArray, FieldOrderOptions[] fieldOptionsArray, boolean addUniqueAs) {
        int size = fieldInfoArray.length;
        if (size > 0) {
            if (fieldOptionsArray == null && mainEntityDefinition.entityInfo.allFieldInfoArray.length == size) {
                String allFieldsSelect = mainEntityDefinition.entityInfo.allFieldsSqlSelect;
                if (allFieldsSelect != null) {
                    sqlTopLevel.append(mainEntityDefinition.entityInfo.allFieldsSqlSelect);
                    return;
                }
            }

            for (int i = 0; i < size; i++) {
                FieldInfo fi = fieldInfoArray[i];
                if (fi == null) break;
                if (i > 0) sqlTopLevel.append(", ");
                boolean appendCloseParen = false;
                if (fieldOptionsArray != null) {
                    FieldOrderOptions foo = fieldOptionsArray[i];
                    if (foo != null && foo.getCaseUpperLower() != null && fi.typeValue == 1) {
                        sqlTopLevel.append(foo.getCaseUpperLower() ? "UPPER(" : "LOWER(");
                        appendCloseParen = true;
                    }
                }
                String fullColName = fi.getFullColumnName();
                sqlTopLevel.append(fullColName);
                if (appendCloseParen) sqlTopLevel.append(")");
                // H2 (and perhaps other DBs?) require a unique name for each selected column, even if not used elsewhere; seems like a bug...
                if (addUniqueAs && fullColName.contains(".")) sqlTopLevel.append(" AS ").append(sanitizeColumnName(fullColName));
            }

        } else {
            sqlTopLevel.append("*");
        }
    }

    public void addWhereClause(FieldInfo[] pkFieldArray, HashMap<String, Object> valueMapInternal) {
        sqlTopLevel.append(" WHERE ");
        int sizePk = pkFieldArray.length;
        for (int i = 0; i < sizePk; i++) {
            FieldInfo fieldInfo = pkFieldArray[i];
            if (fieldInfo == null) break;
            if (i > 0) sqlTopLevel.append(" AND ");
            sqlTopLevel.append(fieldInfo.getFullColumnName()).append("=?");
            parameters.add(new EntityJavaUtil.EntityConditionParameter(fieldInfo, valueMapInternal.get(fieldInfo.name), this));
        }
    }
}
