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
package org.moqui.impl.entity

import groovy.transform.CompileStatic
import org.moqui.impl.StupidJavaUtilities
import org.moqui.entity.EntityException
import org.moqui.impl.entity.EntityJavaUtil.FieldInfo
import org.moqui.util.MNode

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEParameterSpec
import javax.crypto.spec.PBEKeySpec
import org.apache.commons.codec.binary.Hex

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class EntityQueryBuilder {
    protected final static Logger logger = LoggerFactory.getLogger(EntityQueryBuilder.class)
    protected final static boolean isTraceEnabled = logger.isTraceEnabled()

    protected final EntityFacadeImpl efi
    final EntityDefinition mainEntityDefinition
    // init the StringBuilder fairly large to avoid having to grow the buffer
    protected final static int sqlInitSize = 500
    protected StringBuilder sqlTopLevelInternal = new StringBuilder(sqlInitSize)
    protected String finalSql = (String) null
    protected final static int parametersInitSize = 10
    protected ArrayList<EntityJavaUtil.EntityConditionParameter> parameters = new ArrayList(parametersInitSize)

    protected PreparedStatement ps = (PreparedStatement) null
    protected ResultSet rs = (ResultSet) null
    protected Connection connection = (Connection) null
    protected boolean externalConnection = false

    EntityQueryBuilder(EntityDefinition entityDefinition, EntityFacadeImpl efi) {
        this.mainEntityDefinition = entityDefinition
        this.efi = efi
    }

    EntityDefinition getMainEd() { return mainEntityDefinition }

    /** @return StringBuilder meant to be appended to */
    StringBuilder getSqlTopLevel() { return sqlTopLevelInternal }

    /** returns List of EntityConditionParameter meant to be added to */
    ArrayList<EntityJavaUtil.EntityConditionParameter> getParameters() { return parameters }

    Connection makeConnection() {
        connection = efi.getConnection(mainEntityDefinition.getEntityGroupName())
        return connection
    }

    void useConnection(Connection c) { connection = c; externalConnection = true }

    protected static void handleSqlException(Exception e, String sql) {
        throw new EntityException("SQL Exception with statement:" + sql + "; " + e.toString(), e)
    }

    PreparedStatement makePreparedStatement() {
        if (connection == null) throw new IllegalStateException("Cannot make PreparedStatement, no Connection in place")
        finalSql = sqlTopLevelInternal.toString()
        // if (this.mainEntityDefinition.getFullEntityName().contains("foo")) logger.warn("========= making crud PreparedStatement for SQL: ${sql}")
        if (isTraceEnabled) logger.trace("making crud PreparedStatement for SQL: ${finalSql}")
        try {
            ps = connection.prepareStatement(finalSql)
        } catch (SQLException sqle) {
            handleSqlException(sqle, finalSql)
        }
        return ps
    }

    ResultSet executeQuery() throws EntityException {
        if (ps == null) throw new IllegalStateException("Cannot Execute Query, no PreparedStatement in place")
        boolean isError = false
        boolean queryStats = efi.queryStats
        long queryTime = 0
        try {
            long timeBefore = isTraceEnabled ? System.currentTimeMillis() : 0L
            long beforeQuery = queryStats ? System.nanoTime() : 0
            rs = ps.executeQuery()
            if (queryStats) queryTime = System.nanoTime() - beforeQuery
            if (isTraceEnabled) logger.trace("Executed query with SQL [${sqlTopLevelInternal.toString()}] and parameters [${parameters}] in [${(System.currentTimeMillis() - timeBefore)/1000}] seconds")
            return rs
        } catch (SQLException sqle) {
            isError = true
            throw new EntityException("Error in query for:" + sqlTopLevelInternal, sqle)
        } finally {
            if (queryStats) efi.saveQueryStats(mainEntityDefinition, finalSql, queryTime, isError)
        }
    }

    public int executeUpdate() throws EntityException {
        if (this.ps == null) throw new IllegalStateException("Cannot Execute Update, no PreparedStatement in place")
        boolean isError = false
        boolean queryStats = efi.queryStats
        long queryTime = 0
        try {
            long timeBefore = isTraceEnabled ? System.currentTimeMillis() : 0L
            long beforeQuery = queryStats ? System.nanoTime() : 0
            int rows = ps.executeUpdate()
            if (queryStats) queryTime = System.nanoTime() - beforeQuery

            if (isTraceEnabled) logger.trace("Executed update with SQL [${sqlTopLevelInternal.toString()}] and parameters [${parameters}] in [${(System.currentTimeMillis() - timeBefore)/1000}] seconds changing [${rows}] rows")
            return rows
        } catch (SQLException sqle) {
            isError = true
            throw new EntityException("Error in update for:" + sqlTopLevelInternal, sqle)
        } finally {
            if (queryStats) efi.saveQueryStats(mainEntityDefinition, finalSql, queryTime, isError)
        }
    }

    /** NOTE: this should be called in a finally clause to make sure things are closed */
    void closeAll() {
        if (ps != null) {
            ps.close()
            ps = (PreparedStatement) null
        }
        if (rs != null) {
            rs.close()
            rs = (ResultSet) null
        }
        if (connection != null && !externalConnection) {
            connection.close()
            connection = (Connection) null
        }
    }

    /** For when closing to be done in other places, like a EntityListIteratorImpl */
    void releaseAll() {
        ps = (PreparedStatement) null
        rs = (ResultSet) null
        connection = (Connection) null
    }

    static String sanitizeColumnName(String colName) {
        String interim = colName.replace('.', '_').replace('(','_').replace(')','_').replace('+','_').replace(' ','')
        while (interim.charAt(0) == (char) '_') interim = interim.substring(1)
        while (interim.charAt(interim.length()-1) == (char) '_') interim = interim.substring(0, interim.length()-1)
        while (interim.contains('__')) interim = interim.replace('__', '_')
        return interim
    }

    /* no longer used, the static method is used directly everywhere, more efficient
    void getResultSetValue(int index, FieldInfo fieldInfo, EntityValueImpl entityValueImpl) throws EntityException {
        Map<String, Object> valueMap = entityValueImpl.getValueMap()
        String entityName = entityValueImpl.getEntityName()
        getResultSetValue(this.rs, index, fieldInfo, valueMap, entityName, this.efi)
    }
    */

    void setPreparedStatementValue(int index, Object value, FieldInfo fieldInfo) throws EntityException {
        EntityJavaUtil.setPreparedStatementValue(this.ps, index, value, fieldInfo, this.mainEntityDefinition, this.efi)
    }

    void setPreparedStatementValues() {
        // set all of the values from the SQL building in efb
        ArrayList<EntityJavaUtil.EntityConditionParameter> parms = parameters
        int size = parms.size()
        for (int i = 0; i < size; i++) {
            EntityJavaUtil.EntityConditionParameter entityConditionParam = (EntityJavaUtil.EntityConditionParameter) parms.get(i)
            entityConditionParam.setPreparedStatementValue(i + 1)
        }
    }

    void makeSqlSelectFields(ArrayList<FieldInfo> fieldInfoList, ArrayList<EntityJavaUtil.FieldOrderOptions> fieldOptionsList) {
        boolean checkUserFields = mainEntityDefinition.allowUserField
        int size = fieldInfoList.size()
        if (size > 0) {
            if (fieldOptionsList == null && mainEntityDefinition.getAllFieldInfoList().size() == size) {
                String allFieldsSelect = mainEntityDefinition.allFieldsSqlSelect
                if (allFieldsSelect != null) {
                    sqlTopLevelInternal.append(mainEntityDefinition.allFieldsSqlSelect)
                    return
                }
            }

            for (int i = 0; i < size; i++) {
                FieldInfo fi = (FieldInfo) fieldInfoList.get(i)
                if (fi.isUserField && !checkUserFields) continue

                if (i > 0) sqlTopLevelInternal.append(", ")

                boolean appendCloseParen = false
                if (fieldOptionsList != null) {
                    EntityJavaUtil.FieldOrderOptions foo = (EntityJavaUtil.FieldOrderOptions) fieldOptionsList.get(i)
                    if (foo != null && foo.caseUpperLower != null && fi.typeValue == 1) {
                        sqlTopLevelInternal.append(foo.caseUpperLower ? "UPPER(" : "LOWER(")
                        appendCloseParen = true
                    }
                }

                sqlTopLevelInternal.append(fi.fullColumnName)

                if (appendCloseParen) sqlTopLevelInternal.append(")")
            }
        } else {
            sqlTopLevelInternal.append("*")
        }
    }

}
