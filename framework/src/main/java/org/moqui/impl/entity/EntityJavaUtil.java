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

import org.apache.commons.codec.binary.Base64;
import org.moqui.BaseException;
import org.moqui.context.L10nFacade;
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

public class EntityJavaUtil {
    protected final static Logger logger = LoggerFactory.getLogger(EntityJavaUtil.class);

    public static Object convertFromString(String value, int typeValue, String javaType, L10nFacade l10n) {
        Object outValue;
        boolean isEmpty = value.length() == 0;

        try {
            switch (typeValue) {
                case 1: outValue = value; break;
                case 2: // outValue = java.sql.Timestamp.valueOf(value);
                    if (isEmpty) { outValue = null; break; }
                    outValue = l10n.parseTimestamp(value, null);
                    if (outValue == null) throw new BaseException("The value [" + value + "] is not a valid date/time");
                    break;
                case 3: // outValue = java.sql.Time.valueOf(value);
                    if (isEmpty) { outValue = null; break; }
                    outValue = l10n.parseTime(value, null);
                    if (outValue == null) throw new BaseException("The value [" + value + "] is not a valid time");
                    break;
                case 4: // outValue = java.sql.Date.valueOf(value);
                    if (isEmpty) { outValue = null; break; }
                    outValue = l10n.parseDate(value, null);
                    if (outValue == null) throw new BaseException("The value [" + value + "] is not a valid date");
                    break;
                case 5: // outValue = Integer.valueOf(value); break
                case 6: // outValue = Long.valueOf(value); break
                case 7: // outValue = Float.valueOf(value); break
                case 8: // outValue = Double.valueOf(value); break
                case 9: // outValue = new BigDecimal(value); break
                    if (isEmpty) { outValue = null; break; }
                    BigDecimal bdVal = l10n.parseNumber(value, null);
                    if (bdVal == null) {
                        throw new BaseException("The value [" + value + "] is not valid for type [" + javaType + "]");
                    } else {
                        bdVal = bdVal.stripTrailingZeros();
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
                        throw new BaseException("Error creating SerialBlob for value [" + value + "]");
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
            throw new BaseException("The value [" + value + "] is not valid for type [" + javaType + "]", e);
        }

        return outValue;
    }

    public static String convertToString(Object value, int typeValue, String javaType, L10nFacade l10n) {
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
                    if (value instanceof BigDecimal) value = ((BigDecimal) value).stripTrailingZeros();
                    outValue = l10n.format(value, null);
                    break;
                case 10: outValue = value.toString(); break;
                case 11: outValue = value.toString(); break;
                case 12:
                    if (value instanceof byte[]) {
                        outValue = new String(Base64.encodeBase64((byte[]) value));
                    } else {
                        logger.info("Field on entity is not of type 'byte[]', is [" + value + "] so using plain toString()");
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
            throw new BaseException("The value [" + value + "] is not valid for type [" + javaType + "]", e);
        }

        return outValue;
    }

    public static Object getResultSetValue(ResultSet rs, int index, String fieldType, int typeValue, EntityFacade efi) throws EntityException {
        if (typeValue == -1) throw new EntityException("No typeValue found for ${fieldInfo.ed.getFullEntityName()}.${fieldName}, type=${fieldType}");

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

    public static void setPreparedStatementValue(PreparedStatement ps, int index, Object value, int typeValue,
                                                 boolean useBinaryTypeForBlob, EntityFacade efi) throws EntityException {
        try {
            // allow setting, and searching for, String values for all types; JDBC driver should handle this okay
            if (value instanceof CharSequence) {
                ps.setString(index, value.toString());
            } else {
                switch (typeValue) {
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
                        } else {
                            throw new IllegalArgumentException("Class " + valClass.getName() + " not allowed for date-time (Timestamp) fields");
                        }
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
                            ps.setDate(index, dt, efi.getCalendarForTzLc());
                        } else if (valClass == Timestamp.class) {
                            ps.setDate(index, new java.sql.Date(((Timestamp) value).getTime()), efi.getCalendarForTzLc());
                        } else if (valClass == java.util.Date.class) {
                            ps.setDate(index, new java.sql.Date(((java.util.Date) value).getTime()), efi.getCalendarForTzLc());
                        } else {
                            throw new IllegalArgumentException("Class " + valClass.getName() + " not allowed for date fields");
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
                            throw new IllegalArgumentException("Class " + valClass.getName() + " not allowed for number-decimal (BigDecimal) fields");
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

    public static class FieldOrderOptions {
        final static char spaceChar = ' ';
        final static char minusChar = '-';
        final static char plusChar = '+';
        final static char caretChar = '^';
        final static char openParenChar = '(';
        final static char closeParenChar = ')';

        String fieldName = null;
        Boolean nullsFirstLast = null;
        boolean descending = false;
        Boolean caseUpperLower = null;

        public String getFieldName() { return fieldName; }
        public Boolean getNullsFirstLast() { return nullsFirstLast; }
        public boolean getDescending() { return descending; }
        public Boolean getCaseUpperLower() { return caseUpperLower; }

        FieldOrderOptions(String orderByName) {
            StringBuilder fnSb = new StringBuilder(40);
            // simple first parse pass, single run through and as fast as possible
            boolean containsSpace = false;
            boolean foundNonSpace = false;
            boolean containsOpenParen = false;
            int obnLength = orderByName.length();
            for (int i = 0; i < obnLength; i++) {
                char curChar = orderByName.charAt(i);
                if (curChar == spaceChar) {
                    if (foundNonSpace) {
                        containsSpace = true;
                        fnSb.append(curChar);
                    }
                    // otherwise ignore the space
                } else {
                    // leading characters (-,+,^), don't consider them non-spaces so we'll remove spaces after
                    if (curChar == minusChar) {
                        descending = true;
                    } else if (curChar == plusChar) {
                        descending = false;
                    } else if (curChar == caretChar) {
                        caseUpperLower = true;
                    } else {
                        foundNonSpace = true;
                        fnSb.append(curChar);
                        if (curChar == openParenChar) containsOpenParen = true;
                    }
                }
            }

            if (fnSb.length() == 0) return;

            if (containsSpace) {
                // trim ending spaces
                while (fnSb.charAt(fnSb.length() - 1) == spaceChar) fnSb.delete(fnSb.length() - 1, fnSb.length());

                String orderByUpper = fnSb.toString().toUpperCase();
                int fnSbLength = fnSb.length();
                if (orderByUpper.endsWith(" NULLS FIRST")) {
                    nullsFirstLast = true;
                    fnSb.delete(fnSbLength - 12, fnSbLength);
                    // remove from orderByUpper as we'll use it below
                    orderByUpper = orderByUpper.substring(0, orderByName.length() - 12);
                } else if (orderByUpper.endsWith(" NULLS LAST")) {
                    nullsFirstLast = false;
                    fnSb.delete(fnSbLength - 11, fnSbLength);
                    // remove from orderByUpper as we'll use it below
                    orderByUpper = orderByUpper.substring(0, orderByName.length() - 11);
                }

                fnSbLength = fnSb.length();
                if (orderByUpper.endsWith(" DESC")) {
                    descending = true;
                    fnSb.delete(fnSbLength - 5, fnSbLength);
                } else if (orderByUpper.endsWith(" ASC")) {
                    descending = false;
                    fnSb.delete(fnSbLength - 4, fnSbLength);
                }
            }
            if (containsOpenParen) {
                String upperText = fnSb.toString().toUpperCase();
                if (upperText.startsWith("UPPER(")) {
                    caseUpperLower = true;
                    fnSb.delete(0, 6);
                } else if (upperText.startsWith("LOWER(")) {
                    caseUpperLower = false;
                    fnSb.delete(0, 6);
                }
                int fnSbLength = fnSb.length();
                if (fnSb.charAt(fnSbLength - 1) == closeParenChar) fnSb.delete(fnSbLength - 1, fnSbLength);
            }

            fieldName = fnSb.toString();
        }
    }
}
