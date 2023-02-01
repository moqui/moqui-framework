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

import org.moqui.BaseArtifactException;
import org.moqui.entity.EntityException;
import org.moqui.impl.context.L10nFacadeImpl;
import org.moqui.impl.entity.condition.ConditionField;
import org.moqui.util.LiteStringMap;
import org.moqui.util.MNode;
import org.moqui.util.ObjectUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.*;
import java.util.*;

public class FieldInfo {
    protected final static Logger logger = LoggerFactory.getLogger(FieldInfo.class);
    protected final static boolean isTraceEnabled = logger.isTraceEnabled();
    public final static String[] aggFunctionArray = {"min", "max", "sum", "avg", "count", "count-distinct"};
    public final static Set<String> aggFunctions = new HashSet<>(Arrays.asList(aggFunctionArray));
    public final static String decryptFailedMagicString = "_DECRYPT_FAILED_";

    public final EntityDefinition ed;
    public final MNode fieldNode;
    public final int index;
    public final String entityName, name, aliasFieldName;
    public final ConditionField conditionField;
    public final String type, columnName;
    final String fullColumnNameInternal;
    public final String expandColumnName, defaultStr, javaType, enableAuditLog;
    public final int typeValue;
    public final boolean isPk, isTextVeryLong, encrypt, isSimple, enableLocalization, createOnly, isLastUpdatedStamp;
    public final MNode memberEntityNode;
    public final MNode directMemberEntityNode;
    public final boolean hasAggregateFunction;
    final Set<String> entityAliasUsedSet = new HashSet<>();

    public FieldInfo(EntityDefinition ed, MNode fieldNode, int index) {
        this.ed = ed;
        this.fieldNode = fieldNode;
        this.index = index;
        entityName = ed.getFullEntityName();

        Map<String, String> fnAttrs = fieldNode.getAttributes();
        String nameAttr = fnAttrs.get("name");
        if (nameAttr == null) throw new EntityException("No name attribute specified for field in entity " + entityName);
        // NOTE: intern a must here for use with LiteStringMap, without this all sorts of bad behavior, not finding any fields sort of thing
        name = nameAttr.intern();
        conditionField = new ConditionField(this);
        // column name from attribute or underscored name, may have override per DB
        String columnNameAttr = fnAttrs.get("column-name");
        String colNameToUse = columnNameAttr != null && columnNameAttr.length() > 0 ? columnNameAttr :
                EntityJavaUtil.camelCaseToUnderscored(name);
        // column name: see if there is a name-replace
        String groupName = ed.getEntityGroupName();
        MNode databaseNode = ed.efi.getDatabaseNode(groupName);
        // some datasources do not have a database node, like the Elastic Entity one
        if (databaseNode != null) {
            ArrayList<MNode> nameReplaceNodes = databaseNode.children("name-replace");
            for (int i = 0; i < nameReplaceNodes.size(); i++) {
                MNode nameReplaceNode = nameReplaceNodes.get(i);
                if (colNameToUse.equalsIgnoreCase(nameReplaceNode.attribute("original"))) {
                    String replaceName = nameReplaceNode.attribute("replace");
                    logger.info("Replacing column name " + colNameToUse + " with replace name " + replaceName + " for entity " + entityName);
                    colNameToUse = replaceName;
                }
            }
        }
        columnName = colNameToUse;

        defaultStr = fnAttrs.get("default");

        String typeAttr = fnAttrs.get("type");
        if ((typeAttr == null || typeAttr.length() == 0) && (fieldNode.hasChild("complex-alias") || fieldNode.hasChild("case")) && fnAttrs.get("function") != null) {
            // this is probably a calculated value, just default to number-decimal
            typeAttr = "number-decimal";
        }
        type = typeAttr;
        if (type != null && type.length() > 0) {
            String fieldJavaType = ed.efi.getFieldJavaType(type, ed);
            javaType = fieldJavaType != null ? fieldJavaType : "String";
            typeValue = EntityFacadeImpl.getJavaTypeInt(javaType);
            isTextVeryLong = "text-very-long".equals(type);
        } else {
            throw new EntityException("No type specified or found for field " + name + " on entity " + entityName);
        }
        isPk = "true".equals(fnAttrs.get("is-pk"));
        encrypt = "true".equals(fnAttrs.get("encrypt"));
        enableLocalization = "true".equals(fnAttrs.get("enable-localization"));
        isSimple = !enableLocalization;
        String createOnlyAttr = fnAttrs.get("create-only");
        createOnly = createOnlyAttr != null && createOnlyAttr.length() > 0 ?
                "true".equals(fnAttrs.get("create-only")) :
                "true".equals(ed.internalEntityNode.attribute("create-only"));
        isLastUpdatedStamp = "lastUpdatedStamp".equals(name);
        String enableAuditLogAttr = fieldNode.attribute("enable-audit-log");
        enableAuditLog = enableAuditLogAttr != null ? enableAuditLogAttr : ed.internalEntityNode.attribute("enable-audit-log");

        String fcn = ed.makeFullColumnName(fieldNode, true);
        if (fcn == null) {
            fullColumnNameInternal = columnName;
            expandColumnName = null;
        } else {
            if (fcn.contains("${")) {
                expandColumnName = fcn;
                fullColumnNameInternal = null;
            } else {
                fullColumnNameInternal = fcn;
                expandColumnName = null;
            }
        }

        if (ed.isViewEntity) {
            String fieldAttr = fieldNode.attribute("field");
            aliasFieldName = fieldAttr != null && !fieldAttr.isEmpty() ? fieldAttr : name;
            MNode tempMembEntNode = null;
            String entityAlias = fieldNode.attribute("entity-alias");
            if (entityAlias != null && entityAlias.length() > 0) {
                entityAliasUsedSet.add(entityAlias);
                tempMembEntNode = ed.memberEntityAliasMap.get(entityAlias);
            }
            directMemberEntityNode = tempMembEntNode;
            ArrayList<MNode> cafList = fieldNode.descendants("complex-alias-field");
            int cafListSize = cafList.size();
            for (int i = 0; i < cafListSize; i++) {
                MNode cafNode = cafList.get(i);
                String cafEntityAlias = cafNode.attribute("entity-alias");
                if (cafEntityAlias != null && cafEntityAlias.length() > 0) entityAliasUsedSet.add(cafEntityAlias);
            }
            if (tempMembEntNode == null && entityAliasUsedSet.size() == 1) {
                String singleEntityAlias = entityAliasUsedSet.iterator().next();
                tempMembEntNode = ed.memberEntityAliasMap.get(singleEntityAlias);
            }
            memberEntityNode = tempMembEntNode;
            String isAggregateAttr = fieldNode.attribute("is-aggregate");
            hasAggregateFunction = isAggregateAttr != null ? "true".equalsIgnoreCase(isAggregateAttr) :
                    aggFunctions.contains(fieldNode.attribute("function"));
        } else {
            aliasFieldName = null;
            memberEntityNode = null;
            directMemberEntityNode = null;
            hasAggregateFunction = false;
        }
    }

    /** Full column name for complex finds on view entities; plain entity column names are never expanded */
    public String getFullColumnName() {
        if (fullColumnNameInternal != null) return fullColumnNameInternal;
        return ed.efi.ecfi.resourceFacade.expand(expandColumnName, "", null, false);
    }

    static BigDecimal safeStripZeroes(BigDecimal input) {
        if (input == null) return null;
        BigDecimal temp = input.stripTrailingZeros();
        if (temp.scale() < 0) temp = temp.setScale(0);
        return temp;
    }

    public Object convertFromString(String value, L10nFacadeImpl l10n) {
        if (value == null) return null;
        if ("null".equals(value)) return null;

        Object outValue;
        boolean isEmpty = value.length() == 0;

        try {
            switch (typeValue) {
                case 1: outValue = value; break;
                case 2: // outValue = java.sql.Timestamp.valueOf(value);
                    if (isEmpty) { outValue = null; break; }
                    outValue = l10n.parseTimestamp(value, null);
                    if (outValue == null) throw new BaseArtifactException("The value [" + value + "] is not a valid date/time for field " + entityName + "." + name);
                    break;
                case 3: // outValue = java.sql.Time.valueOf(value);
                    if (isEmpty) { outValue = null; break; }
                    outValue = l10n.parseTime(value, null);
                    if (outValue == null) throw new BaseArtifactException("The value [" + value + "] is not a valid time for field " + entityName + "." + name);
                    break;
                case 4: // outValue = java.sql.Date.valueOf(value);
                    if (isEmpty) { outValue = null; break; }
                    outValue = l10n.parseDate(value, null);
                    if (outValue == null) throw new BaseArtifactException("The value [" + value + "] is not a valid date for field " + entityName + "." + name);
                    break;
                case 5: // outValue = Integer.valueOf(value); break
                case 6: // outValue = Long.valueOf(value); break
                case 7: // outValue = Float.valueOf(value); break
                case 8: // outValue = Double.valueOf(value); break
                case 9: // outValue = new BigDecimal(value); break
                    if (isEmpty) { outValue = null; break; }
                    BigDecimal bdVal = l10n.parseNumber(value, null);
                    if (bdVal == null) {
                        throw new BaseArtifactException("The value [" + value + "] is not valid for type " + javaType + " for field " + entityName + "." + name);
                    } else {
                        bdVal = safeStripZeroes(bdVal);
                        switch (typeValue) {
                            case 5: outValue = bdVal.intValue(); break;
                            case 6: outValue = bdVal.longValue(); break;
                            case 7: outValue = bdVal.floatValue(); break;
                            case 8: outValue = bdVal.doubleValue(); break;
                            default: outValue = bdVal; break;
                        }
                    }
                    break;
                case 10:
                    if (isEmpty) { outValue = null; break; }
                    outValue = Boolean.valueOf(value); break;
                case 11: outValue = value; break;
                case 12:
                    try {
                        outValue = new SerialBlob(value.getBytes());
                    } catch (SQLException e) {
                        throw new BaseArtifactException("Error creating SerialBlob for value [" + value + "] for field " + entityName + "." + name);
                    }
                    break;
                case 13: outValue = value; break;
                case 14:
                    if (isEmpty) { outValue = null; break; }
                    Timestamp ts = l10n.parseTimestamp(value, null);
                    outValue = new java.util.Date(ts.getTime());
                    break;
            // better way for Collection (15)? maybe parse comma separated, but probably doesn't make sense in the first place
                case 15: outValue = value; break;
                default: outValue = value; break;
            }
        } catch (IllegalArgumentException e) {
            throw new BaseArtifactException("The value [" + value + "] is not valid for type " + javaType + " for field " + entityName + "." + name, e);
        }

        return outValue;
    }
    public String convertToString(Object value) {
        if (value == null) return null;
        String outValue;
        try {
            switch (typeValue) {
                case 1: outValue = value.toString(); break;
                case 2:
                case 3:
                case 4:
                case 5:
                case 6:
                case 7:
                case 8:
                case 9:
                    if (value instanceof BigDecimal) value = safeStripZeroes((BigDecimal) value);
                    L10nFacadeImpl l10n = ed.efi.ecfi.getEci().l10nFacade;
                    outValue = l10n.format(value, null);
                    break;
                case 10: outValue = value.toString(); break;
                case 11: outValue = value.toString(); break;
                case 12:
                    if (value instanceof byte[]) {
                        outValue = Base64.getEncoder().encodeToString((byte[]) value);
                    } else {
                        logger.info("Field on entity is not of type byte[], is [" + value + "] so using plain toString() for field " + entityName + "." + name);
                        outValue = value.toString();
                    }
                    break;
                case 13: outValue = value.toString(); break;
                case 14: outValue = value.toString(); break;
            // better way for Collection (15)? maybe parse comma separated, but probably doesn't make sense in the first place
                case 15: outValue = value.toString(); break;
                default: outValue = value.toString(); break;
            }
        } catch (IllegalArgumentException e) {
            throw new BaseArtifactException("The value [" + value + "] is not valid for type " + javaType + " for field " + entityName + "." + name, e);
        }

        return outValue;
    }

    void getResultSetValue(ResultSet rs, int index, LiteStringMap<Object> valueMap, EntityFacadeImpl efi) throws EntityException {
        if (typeValue == -1) throw new EntityException("No typeValue found for " + entityName + "." + name);

        Object value = null;
        try {
            switch (typeValue) {
            case 1:
                // getMetaData and the column type are somewhat slow (based on profiling), and String values are VERY
                //     common, so only do for text-very-long
                if (isTextVeryLong) {
                    ResultSetMetaData rsmd = rs.getMetaData();
                    if (Types.CLOB == rsmd.getColumnType(index)) {
                        // if the String is empty, try to get a text input stream, this is required for some databases
                        // for larger fields, like CLOBs
                        Clob valueClob = rs.getClob(index);
                        Reader valueReader = null;
                        if (valueClob != null) valueReader = valueClob.getCharacterStream();
                        if (valueReader != null) {
                            // read up to 4096 at a time
                            char[] inCharBuffer = new char[4096];
                            StringBuilder strBuf = new StringBuilder();
                            try {
                                int charsRead;
                                while ((charsRead = valueReader.read(inCharBuffer, 0, 4096)) > 0) {
                                    strBuf.append(inCharBuffer, 0, charsRead);
                                }
                                valueReader.close();
                            } catch (IOException e) {
                                throw new EntityException("Error reading long character stream for field " + name + " of entity " + entityName, e);
                            }
                            value = strBuf.toString();
                        }
                    } else {
                        value = rs.getString(index);
                    }
                } else {
                    value = rs.getString(index);
                }
                break;
            case 2:
                try {
                    value = rs.getTimestamp(index, efi.getCalendarForTzLc());
                } catch (SQLException e) {
                    if (logger.isTraceEnabled()) logger.trace("Ignoring SQLException for getTimestamp(), leaving null (found this in MySQL with a date/time value of [0000-00-00 00:00:00]): " + e.toString());
                }
                break;
            case 3: value = rs.getTime(index, efi.getCalendarForTzLc()); break;
            // for Date don't pass 2nd param efi.getCalendarForTzLc(), causes issues when Java TZ different from DB TZ
            // when the JDBC driver converts a string to a Date it uses the TZ from the Calendar but we want the Java default TZ
            case 4: value = rs.getDate(index); break;
            case 5: int intValue = rs.getInt(index); if (!rs.wasNull()) value = intValue; break;
            case 6: long longValue = rs.getLong(index); if (!rs.wasNull()) value = longValue; break;
            case 7: float floatValue = rs.getFloat(index); if (!rs.wasNull()) value = floatValue; break;
            case 8: double doubleValue = rs.getDouble(index); if (!rs.wasNull()) value = doubleValue; break;
            case 9: BigDecimal bdVal = rs.getBigDecimal(index); if (!rs.wasNull()) value = safeStripZeroes(bdVal); break;
            case 10: boolean booleanValue = rs.getBoolean(index); if (!rs.wasNull()) value = booleanValue; break;
            case 11:
                Object obj = null;
                byte[] originalBytes = rs.getBytes(index);
                InputStream binaryInput = null;
                if (originalBytes != null && originalBytes.length > 0) {
                    binaryInput = new ByteArrayInputStream(originalBytes);
                }
                if (originalBytes != null && originalBytes.length <= 0) {
                    logger.warn("Got byte array back empty for serialized Object with length [" + originalBytes.length + "] for field [" + name + "] (" + index + ")");
                }
                if (binaryInput != null) {
                    ObjectInputStream inStream = null;
                    try {
                        inStream = new ObjectInputStream(binaryInput);
                        obj = inStream.readObject();
                    } catch (IOException ex) {
                        if (logger.isTraceEnabled()) logger.trace("Unable to read BLOB from input stream for field [" + name + "] (" + index + "): " + ex.toString());
                    } catch (ClassNotFoundException ex) {
                        if (logger.isTraceEnabled()) logger.trace("Class not found: Unable to cast BLOB data to an Java object for field [" + name + "] (" + index + "); most likely because it is a straight byte[], so just using the raw bytes: " + ex.toString());
                    } finally {
                        if (inStream != null) {
                            try { inStream.close(); }
                            catch (IOException e) { logger.error("Unable to close binary input stream for field [" + name + "] (" + index + "): " + e.toString(), e); }
                        }
                    }
                }
                if (obj != null) {
                    value = obj;
                } else {
                    value = originalBytes;
                }
                break;
            case 12:
                SerialBlob sblob = null;
                try {
                    // NOTE: changed to try getBytes first because Derby blows up on getBlob and on then calling getBytes for the same field, complains about getting value twice
                    byte[] fieldBytes = rs.getBytes(index);
                    if (!rs.wasNull()) sblob = new SerialBlob(fieldBytes);
                    // fieldBytes = theBlob != null ? theBlob.getBytes(1, (int) theBlob.length()) : null
                } catch (SQLException e) {
                    if (logger.isTraceEnabled()) logger.trace("Ignoring exception trying getBytes(), trying getBlob(): " + e.toString());
                    Blob theBlob = rs.getBlob(index);
                    if (!rs.wasNull()) sblob = new SerialBlob(theBlob);
                }
                value = sblob;
                break;
            case 13: value = new SerialClob(rs.getClob(index)); break;
            case 14:
            case 15: value = rs.getObject(index); break;
            }
        } catch (SQLException sqle) {
            logger.error("SQL Exception while getting value for field: " + name + " (" + index + ")", sqle);
            throw new EntityException("SQL Exception while getting value for field: " + name + " (" + index + ")", sqle);
        }

        // if field is to be encrypted, do it now
        if (value != null && encrypt) {
            if (typeValue != 1) throw new EntityException("The encrypt attribute was set to true on non-String field " + name + " of entity " + entityName);
            String original = value.toString();
            try {
                value = EntityJavaUtil.enDeCrypt(original, false, efi);
            } catch (Exception e) {
                logger.error("Error decrypting field [" + name + "] of entity [" + entityName + "]", e);
                // NOTE DEJ 20200310 instead of using encrypted value return very clear fake placeholder; this is a bad design
                //     because it uses a magic value that can't change as other code may use it; an alternative might be to have
                //     the EntityValue internally handle it with a Set of fields that failed to decrypt, but this doesn't carry
                //     through to or from web and other clients
                value = decryptFailedMagicString;
            }
        }

        valueMap.putByIString(this.name, value, this.index);
    }

    private static final boolean checkPreparedStatementValueType = false;
    public void setPreparedStatementValue(PreparedStatement ps, int index, Object value,
                                          EntityDefinition ed, EntityFacadeImpl efi) throws EntityException {
        int localTypeValue = typeValue;
        if (value != null) {
            if (checkPreparedStatementValueType && !ObjectUtilities.isInstanceOf(value, javaType)) {
                // this is only an info level message because under normal operation for most JDBC
                // drivers this will be okay, but if not then the JDBC driver will throw an exception
                // and when lower debug levels are on this should help give more info on what happened
                String fieldClassName = value.getClass().getName();
                if (value instanceof byte[]) {
                    fieldClassName = "byte[]";
                } else if (value instanceof char[]) {
                    fieldClassName = "char[]";
                }

                if (isTraceEnabled) logger.trace("Type of field " + ed.getFullEntityName() + "." + name +
                        " is " + fieldClassName + ", was expecting " + javaType + " this may " +
                        "indicate an error in the configuration or in the class, and may result " +
                        "in an SQL-Java data conversion error. Will use the real field type: " +
                        fieldClassName + ", not the definition.");
                localTypeValue = EntityFacadeImpl.getJavaTypeInt(fieldClassName);
            }

            // if field is to be encrypted, do it now
            if (encrypt) {
                if (localTypeValue != 1) throw new EntityException("The encrypt attribute was set to true on non-String field " + name + " of entity " + entityName);
                String original = value.toString();
                if (decryptFailedMagicString.equals(original)) {
                    throw new EntityException("To prevent data loss, not allowing decrypt failed placeholder for field " + name + " of entity " + entityName);
                }
                value = EntityJavaUtil.enDeCrypt(original, true, efi);
            }
        }

        boolean useBinaryTypeForBlob = false;
        if (localTypeValue == 11 || localTypeValue == 12) {
            useBinaryTypeForBlob = ("true".equals(efi.getDatabaseNode(ed.getEntityGroupName()).attribute("use-binary-type-for-blob")));
        }
        // if a count function used set as Long (type 6)
        if (ed.isViewEntity) {
            String function = fieldNode.attribute("function");
            if (function != null && function.startsWith("count")) localTypeValue = 6;
        }

        try {
            setPreparedStatementValue(ps, index, value, localTypeValue, useBinaryTypeForBlob, efi);
        } catch (EntityException e) {
            throw e;
        } catch (Exception e) {
            throw new EntityException("Error setting prepared statement field " + name + " of entity " + entityName, e);
        }
    }

    private void setPreparedStatementValue(PreparedStatement ps, int index, Object value, int localTypeValue,
                                                 boolean useBinaryTypeForBlob, EntityFacadeImpl efi) throws EntityException {
        try {
            // allow setting, and searching for, String values for all types; JDBC driver should handle this okay
            if (value instanceof CharSequence) {
                ps.setString(index, value.toString());
            } else {
                switch (localTypeValue) {
                case 1: if (value != null) { ps.setString(index, value.toString()); } else { ps.setNull(index, Types.VARCHAR); } break;
                case 2:
                    if (value != null) {
                        Class valClass = value.getClass();
                        if (valClass == Timestamp.class) {
                            ps.setTimestamp(index, (Timestamp) value, efi.getCalendarForTzLc());
                        } else if (valClass == java.sql.Date.class) {
                            ps.setDate(index, (java.sql.Date) value, efi.getCalendarForTzLc());
                        } else if (valClass == java.util.Date.class) {
                            ps.setTimestamp(index, new Timestamp(((java.util.Date) value).getTime()), efi.getCalendarForTzLc());
                        } else if (valClass == Long.class) {
                            ps.setTimestamp(index, new Timestamp((Long) value), efi.getCalendarForTzLc());
                        } else {
                            throw new EntityException("Class " + valClass.getName() + " not allowed for date-time (Timestamp) fields, for field " + entityName + "." + name);
                        }
                        // NOTE for Calendar use with different MySQL vs Oracle JDBC drivers: https://bugs.openjdk.java.net/browse/JDK-4986236
                        //     the TimeZone is treated differently; should stay consistent but may result in unexpected times when looking at
                        //     timestamp values directly in the database; not a huge issue, something to keep in mind when configuring the DB TimeZone
                        // NOTE that some JDBC drivers clone the Calendar, others don't; see the efi.getCalendarForTzLc() which now uses a ThreadLocal<Calendar>
                    } else { ps.setNull(index, Types.TIMESTAMP); }
                    break;
                case 3:
                    Time tm = (Time) value;
                    // logger.warn("=================== setting time tm=${tm} tm long=${tm.getTime()}, cal=${cal}")
                    if (value != null) { ps.setTime(index, tm, efi.getCalendarForTzLc()); }
                    else { ps.setNull(index, Types.TIME); }
                    break;
                case 4:
                    if (value != null) {
                        Class valClass = value.getClass();
                        if (valClass == java.sql.Date.class) {
                            java.sql.Date dt = (java.sql.Date) value;
                            // logger.warn("=================== setting date dt=${dt} dt long=${dt.getTime()}, cal=${cal}")
                            ps.setDate(index, dt);
                            // NOTE: don't pass Calendar, Date was likely generated in Java TZ and that's what we want, if DB TZ is different we don't want it to use that
                        } else if (valClass == Timestamp.class) {
                            ps.setDate(index, new java.sql.Date(((Timestamp) value).getTime()), efi.getCalendarForTzLc());
                        } else if (valClass == java.util.Date.class) {
                            ps.setDate(index, new java.sql.Date(((java.util.Date) value).getTime()), efi.getCalendarForTzLc());
                        } else if (valClass == Long.class) {
                            ps.setDate(index, new java.sql.Date((Long) value), efi.getCalendarForTzLc());
                        } else {
                            throw new EntityException("Class " + valClass.getName() + " not allowed for date fields, for field " + entityName + "." + name);
                        }
                    } else { ps.setNull(index, Types.DATE); }
                    break;
                case 5: if (value != null) { ps.setInt(index, ((Number) value).intValue()); } else { ps.setNull(index, Types.NUMERIC); } break;
                case 6: if (value != null) { ps.setLong(index, ((Number) value).longValue()); } else { ps.setNull(index, Types.NUMERIC); } break;
                case 7: if (value != null) { ps.setFloat(index, ((Number) value).floatValue()); } else { ps.setNull(index, Types.NUMERIC); } break;
                case 8: if (value != null) { ps.setDouble(index, ((Number) value).doubleValue()); } else { ps.setNull(index, Types.NUMERIC); } break;
                case 9:
                    if (value != null) {
                        Class valClass = value.getClass();
                        // most common cases BigDecimal, Double, Float; then allow any Number
                        if (valClass == BigDecimal.class) {
                            ps.setBigDecimal(index, (BigDecimal) value);
                        } else if (valClass == Double.class) {
                            ps.setDouble(index, (Double) value);
                        } else if (valClass == Float.class) {
                            ps.setFloat(index, (Float) value);
                        } else if (value instanceof Number) {
                            ps.setDouble(index, ((Number) value).doubleValue());
                        } else {
                            throw new EntityException("Class " + valClass.getName() + " not allowed for number-decimal (BigDecimal) fields, for field " + entityName + "." + name);
                        }
                    } else { ps.setNull(index, Types.NUMERIC); } break;
                case 10: if (value != null) { ps.setBoolean(index, (Boolean) value); } else { ps.setNull(index, Types.BOOLEAN); } break;
                case 11:
                    if (value != null) {
                        try {
                            ByteArrayOutputStream os = new ByteArrayOutputStream();
                            ObjectOutputStream oos = new ObjectOutputStream(os);
                            oos.writeObject(value);
                            oos.close();
                            byte[] buf = os.toByteArray();
                            os.close();

                            ByteArrayInputStream is = new ByteArrayInputStream(buf);
                            ps.setBinaryStream(index, is, buf.length);
                            is.close();
                        } catch (IOException ex) {
                            throw new EntityException("Error setting serialized object, for field " + entityName + "." + name, ex);
                        }
                    } else {
                        if (useBinaryTypeForBlob) { ps.setNull(index, Types.BINARY); } else { ps.setNull(index, Types.BLOB); }
                    }
                    break;
                case 12:
                    if (value instanceof byte[]) {
                        ps.setBytes(index, (byte[]) value);
                    /*
                    } else if (value instanceof ArrayList) {
                        ArrayList valueAl = (ArrayList) value;
                        byte[] theBytes = new byte[valueAl.size()];
                        valueAl.toArray(theBytes);
                        ps.setBytes(index, theBytes);
                    */
                    } else if (value instanceof ByteBuffer) {
                        ByteBuffer valueBb = (ByteBuffer) value;
                        ps.setBytes(index, valueBb.array());
                    } else if (value instanceof Blob) {
                        Blob valueBlob = (Blob) value;
                        // calling setBytes instead of setBlob - old github.com/moqui/moqui repo issue #28 with Postgres JDBC driver
                        // ps.setBlob(index, (Blob) value)
                        // Blob blb = value
                        try {
                            ps.setBytes(index, valueBlob.getBytes(1, (int) valueBlob.length()));
                        } catch (Exception bytesExc) {
                            // try ps.setBlob for larger byte arrays that H2 throws an exception for
                            try {
                                ps.setBlob(index, valueBlob);
                            } catch (Exception blobExc) {
                                // throw the original exception from setBytes()
                                throw bytesExc;
                            }
                        }
                    } else {
                        if (value != null) {
                            throw new EntityException("Type not supported for BLOB field: " + value.getClass().getName() + ", for field " + entityName + "." + name);
                        } else {
                            if (useBinaryTypeForBlob) { ps.setNull(index, Types.BINARY); } else { ps.setNull(index, Types.BLOB); }
                        }
                    }
                    break;
                case 13: if (value != null) { ps.setClob(index, (Clob) value); } else { ps.setNull(index, Types.CLOB); } break;
                case 14: if (value != null) { ps.setTimestamp(index, (Timestamp) value); } else { ps.setNull(index, Types.TIMESTAMP); } break;
                // TODO: is this the best way to do collections and such?
                case 15: if (value != null) { ps.setObject(index, value, Types.JAVA_OBJECT); } else { ps.setNull(index, Types.JAVA_OBJECT); } break;
                }
            }
        } catch (SQLException sqle) {
            throw new EntityException("SQL Exception while setting value [" + value + "](" + (value != null ? value.getClass().getName() : "null") + "), type " + type + ", for field " + entityName + "." + name + ": " + sqle.toString(), sqle);
        } catch (Exception e) {
            throw new EntityException("Error while setting value for field " + entityName + "." + name + ": " + e.toString(), e);
        }
    }

    @Override public String toString() { return name; }
}
