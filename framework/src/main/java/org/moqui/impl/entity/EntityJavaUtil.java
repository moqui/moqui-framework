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
import org.moqui.entity.EntityFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.*;
import java.util.ArrayList;

public class EntityJavaUtil {
    protected final static Logger logger = LoggerFactory.getLogger(EntityJavaUtil.class);

    static Object getResultSetValue(ResultSet rs, int index, String fieldType, int typeValue, EntityFacade efi) throws EntityException {
        if (typeValue == -1) throw new EntityException("No typeValue found for ${fieldInfo.ed.getFullEntityName()}.${fieldName}, type=${fieldType}");
        /*
        // jump straight to the type int for common/OOTB field types for FAR better performance than hunting through conf elements
        Integer directTypeInt = EntityFacadeImpl.fieldTypeIntMap.get(fieldType)
        if (directTypeInt == null) {
            String javaType = fieldType ? efi.getFieldJavaType(fieldType, entityValueImpl.getEntityDefinition()) : "String"
            typeValue = EntityFacadeImpl.getJavaTypeInt(javaType)
        } else {
            typeValue = directTypeInt
        }
        */

        Object value = null;
        try {
            switch (typeValue) {
            case 1:
                // getMetaData and the column type are somewhat slow (based on profiling), and String values are VERY
                //     common, so only do for text-very-long
                if ("text-very-long".equals(fieldType)) {
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
                                throw new EntityException("Error reading long character stream for field [${fieldName}] of entity [${entityValueImpl.getEntityName()}]", e);
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
                    if (logger.isTraceEnabled()) logger.trace("Ignoring SQLException for getTimestamp(), leaving null (found this in MySQL with a date/time value of [0000-00-00 00:00:00]): ${e.toString()}");
                }
                break;
            case 3: value = rs.getTime(index, efi.getCalendarForTzLc()); break;
            case 4: value = rs.getDate(index, efi.getCalendarForTzLc()); break;
            case 5: int intValue = rs.getInt(index); if (!rs.wasNull()) value = intValue; break;
            case 6: long longValue = rs.getLong(index); if (!rs.wasNull()) value = longValue; break;
            case 7: float floatValue = rs.getFloat(index); if (!rs.wasNull()) value = floatValue; break;
            case 8: double doubleValue = rs.getDouble(index); if (!rs.wasNull()) value = doubleValue; break;
            case 9:
                BigDecimal bigDecimalValue = rs.getBigDecimal(index);
                if (!rs.wasNull()) value = bigDecimalValue != null ? bigDecimalValue.stripTrailingZeros() : null;
                break;
            case 10: boolean booleanValue = rs.getBoolean(index); if (!rs.wasNull()) value = booleanValue; break;
            case 11:
                Object obj = null;
                byte[] originalBytes = rs.getBytes(index);
                InputStream binaryInput = null;
                if (originalBytes != null && originalBytes.length > 0) {
                    binaryInput = new ByteArrayInputStream(originalBytes);
                }
                if (originalBytes != null && originalBytes.length <= 0) {
                    logger.warn("Got byte array back empty for serialized Object with length [${originalBytes.length}] for field [${fieldName}] (${index})");
                }
                if (binaryInput != null) {
                    ObjectInputStream inStream = null;
                    try {
                        inStream = new ObjectInputStream(binaryInput);
                        obj = inStream.readObject();
                    } catch (IOException ex) {
                        if (logger.isTraceEnabled()) logger.trace("Unable to read BLOB from input stream for field [${fieldName}] (${index}): ${ex.toString()}");
                    } catch (ClassNotFoundException ex) {
                        if (logger.isTraceEnabled()) logger.trace("Class not found: Unable to cast BLOB data to an Java object for field [${fieldName}] (${index}); most likely because it is a straight byte[], so just using the raw bytes: ${ex.toString()}");
                    } finally {
                        if (inStream != null) {
                            try {
                                inStream.close();
                            } catch (IOException e) {
                                throw new EntityException("Unable to close binary input stream for field [${fieldName}] (${index}): ${e.toString()}", e);
                            }
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
                    if (logger.isTraceEnabled()) logger.trace("Ignoring exception trying getBytes(), trying getBlob(): ${e.toString()}");
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
            logger.error("SQL Exception while getting value for field: [${fieldName}] (${index})", sqle);
            throw new EntityException("SQL Exception while getting value for field: [${fieldName}] (${index})", sqle);
        }

        return value;
    }

    static void setPreparedStatementValue(PreparedStatement ps, int index, Object value, int typeValue,
                                          boolean useBinaryTypeForBlob, EntityFacade efi) throws EntityException {
        try {
            // allow setting, and searching for, String values for all types; JDBC driver should handle this okay
            if (value instanceof CharSequence) {
                ps.setString(index, value.toString());
            } else {
                switch (typeValue) {
                case 1: if (value != null) { ps.setString(index, value.toString()); } else { ps.setNull(index, Types.VARCHAR); } break;
                case 2:
                    if (value != null) { ps.setTimestamp(index, (Timestamp) value, efi.getCalendarForTzLc()); }
                    else { ps.setNull(index, Types.TIMESTAMP); }
                    break;
                case 3:
                    Time tm = (Time) value;
                    // logger.warn("=================== setting time tm=${tm} tm long=${tm.getTime()}, cal=${cal}")
                    if (value != null) { ps.setTime(index, tm, efi.getCalendarForTzLc()); }
                    else { ps.setNull(index, Types.TIME); }
                    break;
                case 4:
                    java.sql.Date dt = (java.sql.Date) value;
                    // logger.warn("=================== setting date dt=${dt} dt long=${dt.getTime()}, cal=${cal}")
                    if (value != null) { ps.setDate(index, dt, efi.getCalendarForTzLc()); }
                    else { ps.setNull(index, Types.DATE); }
                    break;
                case 5: if (value != null) { ps.setInt(index, (Integer) value); } else { ps.setNull(index, Types.NUMERIC); } break;
                case 6: if (value != null) { ps.setLong(index, (Long) value); } else { ps.setNull(index, Types.NUMERIC); } break;
                case 7: if (value != null) { ps.setFloat(index, (Float) value); } else { ps.setNull(index, Types.NUMERIC); } break;
                case 8: if (value != null) { ps.setDouble(index, (Double) value); } else { ps.setNull(index, Types.NUMERIC); } break;
                case 9: if (value != null) { ps.setBigDecimal(index, (BigDecimal) value); } else { ps.setNull(index, Types.NUMERIC); } break;
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
                            throw new EntityException("Error setting serialized object", ex);
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
                        // calling setBytes instead of setBlob
                        // ps.setBlob(index, (Blob) value)
                        // Blob blb = value
                        ps.setBytes(index, valueBlob.getBytes(1, (int) valueBlob.length()));
                    } else {
                        if (value != null) {
                            throw new IllegalArgumentException("Type not supported for BLOB field: ${value.getClass().getName()}");
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
            throw new EntityException("SQL Exception while setting value [${value}](${value?.getClass()?.getName()}), type ${typeValue}: " + sqle.toString(), sqle);
        } catch (Exception e) {
            throw new EntityException("Error while setting value: " + e.toString(), e);
        }
    }
}
