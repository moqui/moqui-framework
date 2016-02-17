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
import org.moqui.impl.entity.EntityDefinition.FieldInfo
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

    protected EntityFacadeImpl efi
    EntityDefinition mainEntityDefinition
    // init the StringBuilder fairly large to avoid having to grow the buffer
    protected final static int sqlInitSize = 500
    protected StringBuilder sqlTopLevelInternal = new StringBuilder(sqlInitSize)
    protected final static int parametersInitSize = 10
    protected ArrayList<EntityConditionParameter> parameters = new ArrayList(parametersInitSize)

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
    ArrayList<EntityConditionParameter> getParameters() { return parameters }

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
        String sql = sqlTopLevelInternal.toString()
        // if (this.mainEntityDefinition.getFullEntityName().contains("foo")) logger.warn("========= making crud PreparedStatement for SQL: ${sql}")
        if (logger.isDebugEnabled()) logger.debug("making crud PreparedStatement for SQL: ${sql}")
        try {
            ps = connection.prepareStatement(sql)
        } catch (SQLException sqle) {
            handleSqlException(sqle, sql)
        }
        return ps
    }

    ResultSet executeQuery() throws EntityException {
        if (ps == null) throw new IllegalStateException("Cannot Execute Query, no PreparedStatement in place")
        try {
            long timeBefore = logger.isTraceEnabled() ? System.currentTimeMillis() : 0L
            rs = ps.executeQuery()
            if (logger.isTraceEnabled()) logger.trace("Executed query with SQL [${sqlTopLevelInternal.toString()}] and parameters [${parameters}] in [${(System.currentTimeMillis() - timeBefore)/1000}] seconds")
            return rs
        } catch (SQLException sqle) {
            throw new EntityException("Error in query for:" + sqlTopLevelInternal, sqle)
        }
    }

    public int executeUpdate() throws EntityException {
        if (this.ps == null) throw new IllegalStateException("Cannot Execute Update, no PreparedStatement in place")
        try {
            long timeBefore = logger.isTraceEnabled() ? System.currentTimeMillis() : 0
            int rows = ps.executeUpdate()
            if (logger.isTraceEnabled()) logger.trace("Executed update with SQL [${sqlTopLevelInternal.toString()}] and parameters [${parameters}] in [${(System.currentTimeMillis() - timeBefore)/1000}] seconds changing [${rows}] rows")
            return rows
        } catch (SQLException sqle) {
            throw new EntityException("Error in update for:" + sqlTopLevelInternal, sqle)
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

    void getResultSetValue(int index, FieldInfo fieldInfo, EntityValueImpl entityValueImpl) throws EntityException {
        getResultSetValue(this.rs, index, fieldInfo, entityValueImpl, this.efi)
    }

    void setPreparedStatementValue(int index, Object value, FieldInfo fieldInfo) throws EntityException {
        setPreparedStatementValue(this.ps, index, value, fieldInfo, this.mainEntityDefinition, this.efi)
    }

    void setPreparedStatementValues() {
        // set all of the values from the SQL building in efb
        ArrayList<EntityConditionParameter> parms = parameters
        int size = parms.size()
        for (int i = 0; i < size; i++) {
            EntityConditionParameter entityConditionParam = (EntityConditionParameter) parms.get(i)
            entityConditionParam.setPreparedStatementValue(i + 1)
        }
    }

    @CompileStatic
    static class EntityConditionParameter {
        protected final static Logger logger = LoggerFactory.getLogger(EntityConditionParameter.class)

        protected FieldInfo fieldInfo
        protected Object value
        protected EntityQueryBuilder eqb

        EntityConditionParameter(FieldInfo fieldInfo, Object value, EntityQueryBuilder eqb) {
            this.fieldInfo = fieldInfo
            this.value = value
            this.eqb = eqb
        }

        FieldInfo getFieldInfo() { return this.fieldInfo }

        Object getValue() { return this.value }

        void setPreparedStatementValue(int index) throws EntityException {
            setPreparedStatementValue(this.eqb.ps, index, this.value, this.fieldInfo,
                    this.eqb.mainEntityDefinition, this.eqb.efi)
        }

        @Override
        String toString() { return fieldInfo.name + ':' + value }
    }

    static void getResultSetValue(ResultSet rs, int index, FieldInfo fieldInfo, EntityValueBase entityValueBase,
                                            EntityFacadeImpl efi) throws EntityException {
        String fieldName = fieldInfo.name

        Object value = EntityJavaUtil.getResultSetValue(rs, index, fieldInfo.type, fieldInfo.typeValue, efi)

        // if field is to be encrypted, do it now
        if (value != null && fieldInfo.encrypt) {
            if (fieldInfo.typeValue != 1) throw new IllegalArgumentException("The encrypt attribute was set to true on non-String field [${fieldName}] of entity [${entityValueBase.getEntityName()}]")
            String original = value.toString()
            try {
                value = enDeCrypt(original, false, efi)
            } catch (Exception e) {
                logger.error("Error decrypting field [${fieldName}] of entity [${entityValueBase.getEntityName()}]", e)
            }
        }

        entityValueBase.getValueMap().put(fieldName, value)
    }

    public static String enDeCrypt(String value, boolean encrypt, EntityFacadeImpl efi) {
        MNode entityFacadeNode = efi.ecfi.confXmlRoot.first("entity-facade")
        String pwStr = entityFacadeNode.attribute("crypt-pass")
        if (!pwStr) throw new IllegalArgumentException("No entity-facade.@crypt-pass setting found, NOT doing encryption")

        byte[] salt = (entityFacadeNode.attribute("crypt-salt") ?: "default1").getBytes()
        if (salt.length > 8) salt = salt[0..7]
        if (salt.length < 8) {
            byte[] newSalt = new byte[8]
            for (int i = 0; i < 8; i++) {
                if (i < salt.length) newSalt[i] = salt[i]
                else (newSalt[i] = 0x45 as byte)
            }
        }
        int count = (entityFacadeNode.attribute("crypt-iter") as Integer) ?: 10
        char[] pass = pwStr.toCharArray()

        String algo = entityFacadeNode.attribute("crypt-algo") ?: "PBEWithMD5AndDES"

        // logger.info("TOREMOVE salt [${salt}] count [${count}] pass [${pass}] algo [${algo}]")
        PBEParameterSpec pbeParamSpec = new PBEParameterSpec(salt, count)
        PBEKeySpec pbeKeySpec = new PBEKeySpec(pass)
        SecretKeyFactory keyFac = SecretKeyFactory.getInstance(algo)
        SecretKey pbeKey = keyFac.generateSecret(pbeKeySpec)

        Cipher pbeCipher = Cipher.getInstance(algo)
        int mode = encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE
        pbeCipher.init(mode, pbeKey, pbeParamSpec)

        byte[] inBytes
        if (encrypt) {
            inBytes = value.getBytes()
        } else {
            inBytes = Hex.decodeHex(value.toCharArray())
        }
        byte[] outBytes = pbeCipher.doFinal(inBytes)
        return encrypt ? Hex.encodeHexString(outBytes) : new String(outBytes)
    }

    static final boolean checkPreparedStatementValueType = false
    static void setPreparedStatementValue(PreparedStatement ps, int index, Object value, FieldInfo fieldInfo,
                                          EntityDefinition ed, EntityFacadeImpl efi) throws EntityException {
        String javaType = fieldInfo.javaType
        int typeValue = fieldInfo.typeValue
        if (value != null) {
            if (checkPreparedStatementValueType && !StupidJavaUtilities.isInstanceOf(value, javaType)) {
                // this is only an info level message because under normal operation for most JDBC
                // drivers this will be okay, but if not then the JDBC driver will throw an exception
                // and when lower debug levels are on this should help give more info on what happened
                String fieldClassName = value.getClass().getName()
                if (value instanceof byte[]) {
                    fieldClassName = "byte[]"
                } else if (value instanceof char[]) {
                    fieldClassName = "char[]"
                }

                if (logger.isTraceEnabled()) logger.trace((String) "Type of field " + ed.getFullEntityName() + "." + fieldInfo.name +
                        " is " + fieldClassName + ", was expecting " + javaType + " this may " +
                        "indicate an error in the configuration or in the class, and may result " +
                        "in an SQL-Java data conversion error. Will use the real field type: " +
                        fieldClassName + ", not the definition.")
                javaType = fieldClassName
                typeValue = EntityFacadeImpl.getJavaTypeInt(javaType)
            }

            // if field is to be encrypted, do it now
            if (fieldInfo.encrypt) {
                if (typeValue != 1) throw new IllegalArgumentException("The encrypt attribute was set to true on non-String field [${fieldInfo.name}] of entity [${ed.getFullEntityName()}]")
                String original = value as String
                value = enDeCrypt(original, true, efi)
            }
        }

        boolean useBinaryTypeForBlob = false
        if (typeValue == 11 || typeValue == 12) {
            useBinaryTypeForBlob = ("true" == efi.getDatabaseNode(ed.getEntityGroupName()).attribute('use-binary-type-for-blob'))
        }
        try {
            EntityJavaUtil.setPreparedStatementValue(ps, index, value, typeValue, useBinaryTypeForBlob, efi)
        } catch (EntityException e) {
            throw e
        } catch (Exception e) {
            throw new EntityException("Error setting prepared statement field [${fieldInfo.name}] of entity [${ed.getFullEntityName()}]", e)
        }
    }

    static void setPreparedStatementValue(PreparedStatement ps, int index, Object value, EntityDefinition ed,
                                          EntityFacadeImpl efi) throws EntityException {
        int typeValue = value ? EntityFacadeImpl.getJavaTypeInt(value.class.name) : 1
        // useBinaryTypeForBlob is only needed for types 11/Object and 12/Blob, faster to not determine otherwise
        boolean useBinaryTypeForBlob = false
        if (typeValue == 11 || typeValue == 12) {
            useBinaryTypeForBlob = ("true" == efi.getDatabaseNode(ed.getEntityGroupName()).attribute('use-binary-type-for-blob'))
        }
        EntityJavaUtil.setPreparedStatementValue(ps, index, value, typeValue, useBinaryTypeForBlob, efi)

    }
}
