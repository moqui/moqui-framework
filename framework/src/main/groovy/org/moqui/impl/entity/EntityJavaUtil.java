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
import org.apache.commons.codec.binary.Hex;
import org.moqui.BaseException;
import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.context.L10nFacade;
import org.moqui.entity.EntityException;
import org.moqui.entity.EntityFacade;
import org.moqui.impl.StupidJavaUtilities;
import org.moqui.impl.context.L10nFacadeImpl;
import org.moqui.impl.entity.condition.ConditionField;
import org.moqui.util.MNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.*;
import java.sql.Date;
import java.util.*;

public class EntityJavaUtil {
    protected final static Logger logger = LoggerFactory.getLogger(EntityJavaUtil.class);
    protected final static boolean isTraceEnabled = logger.isTraceEnabled();

    public static Object convertFromString(String value, FieldInfo fi, L10nFacade l10n) {
        Object outValue;
        boolean isEmpty = value.length() == 0;

        try {
            switch (fi.typeValue) {
                case 1: outValue = value; break;
                case 2: // outValue = java.sql.Timestamp.valueOf(value);
                    if (isEmpty) { outValue = null; break; }
                    outValue = l10n.parseTimestamp(value, null);
                    if (outValue == null) throw new BaseException("The value [" + value + "] is not a valid date/time for field " + fi.entityName + "." + fi.name);
                    break;
                case 3: // outValue = java.sql.Time.valueOf(value);
                    if (isEmpty) { outValue = null; break; }
                    outValue = l10n.parseTime(value, null);
                    if (outValue == null) throw new BaseException("The value [" + value + "] is not a valid time for field " + fi.entityName + "." + fi.name);
                    break;
                case 4: // outValue = java.sql.Date.valueOf(value);
                    if (isEmpty) { outValue = null; break; }
                    outValue = l10n.parseDate(value, null);
                    if (outValue == null) throw new BaseException("The value [" + value + "] is not a valid date for field " + fi.entityName + "." + fi.name);
                    break;
                case 5: // outValue = Integer.valueOf(value); break
                case 6: // outValue = Long.valueOf(value); break
                case 7: // outValue = Float.valueOf(value); break
                case 8: // outValue = Double.valueOf(value); break
                case 9: // outValue = new BigDecimal(value); break
                    if (isEmpty) { outValue = null; break; }
                    BigDecimal bdVal = l10n.parseNumber(value, null);
                    if (bdVal == null) {
                        throw new BaseException("The value [" + value + "] is not valid for type [" + fi.javaType + "] for field " + fi.entityName + "." + fi.name);
                    } else {
                        bdVal = bdVal.stripTrailingZeros();
                        switch (fi.typeValue) {
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
                        throw new BaseException("Error creating SerialBlob for value [" + value + "] for field " + fi.entityName + "." + fi.name);
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
            throw new BaseException("The value [" + value + "] is not valid for type [" + fi.javaType + "] for field " + fi.entityName + "." + fi.name, e);
        }

        return outValue;
    }

    public static String convertToString(Object value, FieldInfo fi, EntityFacadeImpl efi) {
        String outValue;
        try {
            switch (fi.typeValue) {
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
                    L10nFacadeImpl l10n = efi.getEcfi().getL10nFacade();
                    outValue = l10n.format(value, null);
                    break;
                case 10: outValue = value.toString(); break;
                case 11: outValue = value.toString(); break;
                case 12:
                    if (value instanceof byte[]) {
                        outValue = new String(Base64.encodeBase64((byte[]) value));
                    } else {
                        logger.info("Field on entity is not of type 'byte[]', is [" + value + "] so using plain toString() for field " + fi.entityName + "." + fi.name);
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
            throw new BaseException("The value [" + value + "] is not valid for type [" + fi.javaType + "] for field " + fi.entityName + "." + fi.name, e);
        }

        return outValue;
    }

    private static final int saltBytes = 8;
    public static String enDeCrypt(String value, boolean encrypt, EntityFacadeImpl efi) {
        MNode entityFacadeNode = efi.ecfi.getConfXmlRoot().first("entity-facade");
        String pwStr = entityFacadeNode.attribute("crypt-pass");
        if (pwStr == null || pwStr.length() == 0)
            throw new IllegalArgumentException("No entity-facade.@crypt-pass setting found, NOT doing encryption");

        String saltStr = entityFacadeNode.attribute("crypt-salt");
        byte[] salt = (saltStr != null && saltStr.length() > 0 ? saltStr : "default1").getBytes();
        if (salt.length > saltBytes) {
            byte[] trimmed = new byte[saltBytes];
            System.arraycopy(salt, 0, trimmed, 0, saltBytes);
            salt = trimmed;
        }
        if (salt.length < saltBytes) {
            byte[] newSalt = new byte[saltBytes];
            for (int i = 0; i < saltBytes; i++) {
                if (i < salt.length) newSalt[i] = salt[i];
                else newSalt[i] = 0x45;
            }
            salt = newSalt;
        }
        String iterStr = entityFacadeNode.attribute("crypt-iter");
        int count = iterStr != null && iterStr.length() > 0 ? Integer.valueOf(iterStr) : 10;
        char[] pass = pwStr.toCharArray();


        String algo = entityFacadeNode.attribute("crypt-algo");
        if (algo == null || algo.length() == 0) algo = "PBEWithMD5AndDES";

        // logger.info("TOREMOVE salt [${salt}] count [${count}] pass [${pass}] algo [${algo}]")
        PBEParameterSpec pbeParamSpec = new PBEParameterSpec(salt, count);
        PBEKeySpec pbeKeySpec = new PBEKeySpec(pass);
        try {
            SecretKeyFactory keyFac = SecretKeyFactory.getInstance(algo);
            SecretKey pbeKey = keyFac.generateSecret(pbeKeySpec);

            Cipher pbeCipher = Cipher.getInstance(algo);
            int mode = encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE;
            pbeCipher.init(mode, pbeKey, pbeParamSpec);

            byte[] inBytes;
            if (encrypt) {
                inBytes = value.getBytes();
            } else {
                inBytes = Hex.decodeHex(value.toCharArray());
            }
            byte[] outBytes = pbeCipher.doFinal(inBytes);
            return encrypt ? Hex.encodeHexString(outBytes) : new String(outBytes);
        } catch (Exception e) {
            throw new EntityException("Encryption error", e);
        }
    }

    @SuppressWarnings("ThrowFromFinallyBlock")
    public static void getResultSetValue(ResultSet rs, int index, FieldInfo fi, HashMap<String, Object> valueMap,
                                           EntityFacadeImpl efi) throws EntityException {
        if (fi.typeValue == -1) throw new EntityException("No typeValue found for " + fi.entityName + "." + fi.name);

        Object value = null;
        try {
            switch (fi.typeValue) {
            case 1:
                // getMetaData and the column type are somewhat slow (based on profiling), and String values are VERY
                //     common, so only do for text-very-long
                if (fi.isTextVeryLong) {
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
                                throw new EntityException("Error reading long character stream for field [" + fi.name + "] of entity [" + fi.entityName + "]", e);
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
                    logger.warn("Got byte array back empty for serialized Object with length [" + originalBytes.length + "] for field [" + fi.name + "] (" + index + ")");
                }
                if (binaryInput != null) {
                    ObjectInputStream inStream = null;
                    try {
                        inStream = new ObjectInputStream(binaryInput);
                        obj = inStream.readObject();
                    } catch (IOException ex) {
                        if (logger.isTraceEnabled()) logger.trace("Unable to read BLOB from input stream for field [" + fi.name + "] (" + index + "): " + ex.toString());
                    } catch (ClassNotFoundException ex) {
                        if (logger.isTraceEnabled()) logger.trace("Class not found: Unable to cast BLOB data to an Java object for field [" + fi.name + "] (" + index + "); most likely because it is a straight byte[], so just using the raw bytes: " + ex.toString());
                    } finally {
                        if (inStream != null) {
                            try {
                                inStream.close();
                            } catch (IOException e) {
                                throw new EntityException("Unable to close binary input stream for field [" + fi.name + "] (" + index + "): " + e.toString(), e);
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
            logger.error("SQL Exception while getting value for field: [" + fi.name + "] (" + index + ")", sqle);
            throw new EntityException("SQL Exception while getting value for field: [" + fi.name + "] (" + index + ")", sqle);
        }

        // if field is to be encrypted, do it now
        if (value != null && fi.encrypt) {
            if (fi.typeValue != 1) throw new IllegalArgumentException("The encrypt attribute was set to true on non-String field " + fi.name + " of entity " + fi.entityName);
            String original = value.toString();
            try {
                value = enDeCrypt(original, false, efi);
            } catch (Exception e) {
                logger.error("Error decrypting field [${fieldInfo.name}] of entity [${entityName}]", e);
            }
        }

        valueMap.put(fi.name, value);
    }

    private static final boolean checkPreparedStatementValueType = false;
    static void setPreparedStatementValue(PreparedStatement ps, int index, Object value, FieldInfo fieldInfo,
                                          EntityDefinition ed, EntityFacadeImpl efi) throws EntityException {
        String javaType = fieldInfo.javaType;
        int typeValue = fieldInfo.typeValue;
        if (value != null) {
            if (checkPreparedStatementValueType && !StupidJavaUtilities.isInstanceOf(value, javaType)) {
                // this is only an info level message because under normal operation for most JDBC
                // drivers this will be okay, but if not then the JDBC driver will throw an exception
                // and when lower debug levels are on this should help give more info on what happened
                String fieldClassName = value.getClass().getName();
                if (value instanceof byte[]) {
                    fieldClassName = "byte[]";
                } else if (value instanceof char[]) {
                    fieldClassName = "char[]";
                }

                if (isTraceEnabled) logger.trace("Type of field " + ed.getFullEntityName() + "." + fieldInfo.name +
                        " is " + fieldClassName + ", was expecting " + javaType + " this may " +
                        "indicate an error in the configuration or in the class, and may result " +
                        "in an SQL-Java data conversion error. Will use the real field type: " +
                        fieldClassName + ", not the definition.");
                javaType = fieldClassName;
                typeValue = EntityFacadeImpl.getJavaTypeInt(javaType);
            }

            // if field is to be encrypted, do it now
            if (fieldInfo.encrypt) {
                if (typeValue != 1) throw new IllegalArgumentException("The encrypt attribute was set to true on non-String field " + fieldInfo.name + " of entity " + fieldInfo.entityName);
                String original = value.toString();
                value = enDeCrypt(original, true, efi);
            }
        }

        boolean useBinaryTypeForBlob = false;
        if (typeValue == 11 || typeValue == 12) {
            useBinaryTypeForBlob = ("true".equals(efi.getDatabaseNode(ed.getEntityGroupName()).attribute("use-binary-type-for-blob")));
        }
        try {
            EntityJavaUtil.setPreparedStatementValue(ps, index, value, fieldInfo, useBinaryTypeForBlob, efi);
        } catch (EntityException e) {
            throw e;
        } catch (Exception e) {
            throw new EntityException("Error setting prepared statement field " + fieldInfo.name + " of entity " + fieldInfo.entityName, e);
        }
    }

    public static void setPreparedStatementValue(PreparedStatement ps, int index, Object value, FieldInfo fi,
                                                 boolean useBinaryTypeForBlob, EntityFacade efi) throws EntityException {
        try {
            // allow setting, and searching for, String values for all types; JDBC driver should handle this okay
            if (value instanceof CharSequence) {
                ps.setString(index, value.toString());
            } else {
                switch (fi.typeValue) {
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
                            throw new IllegalArgumentException("Class " + valClass.getName() + " not allowed for date-time (Timestamp) fields, for field " + fi.entityName + "." + fi.name);
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
                            throw new IllegalArgumentException("Class " + valClass.getName() + " not allowed for date fields, for field " + fi.entityName + "." + fi.name);
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
                            throw new IllegalArgumentException("Class " + valClass.getName() + " not allowed for number-decimal (BigDecimal) fields, for field " + fi.entityName + "." + fi.name);
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
                            throw new EntityException("Error setting serialized object, for field " + fi.entityName + "." + fi.name, ex);
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
                            throw new IllegalArgumentException("Type not supported for BLOB field: " + value.getClass().getName() + ", for field " + fi.entityName + "." + fi.name);
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
            throw new EntityException("SQL Exception while setting value [" + value + "](" + (value != null ? value.getClass().getName() : "null") + "), type " + fi.type + ", for field " + fi.entityName + "." + fi.name + ": " + sqle.toString(), sqle);
        } catch (Exception e) {
            throw new EntityException("Error while setting value for field " + fi.entityName + "." + fi.name + ": " + e.toString(), e);
        }
    }

    @SuppressWarnings("unused")
    public static FieldOrderOptions makeFieldOrderOptions(String orderByName) { return new FieldOrderOptions(orderByName); }
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
            char[] obnCharArray = orderByName.toCharArray();
            for (int i = 0; i < obnLength; i++) {
                char curChar = obnCharArray[i];
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

    /** This is a dumb data holder class for framework internal use only; in Java for efficiency as it is used a LOT,
     * though initialized in the EntityDefinition.makeFieldInfo() method. */
    public static class FieldInfo {
        public final EntityDefinition ed;
        public final MNode fieldNode;
        public final String entityName;
        public final String name;
        public final ConditionField conditionField;
        public final String type;
        public final String columnName;
        private final String fullColumnName;
        private final String expandColumnName;
        public final String defaultStr;
        public final String javaType;
        public final String enableAuditLog;
        public final int typeValue;
        public final boolean isTextVeryLong;
        public final boolean isPk;
        public final boolean encrypt;
        public final boolean isSimple;
        public final boolean enableLocalization;
        public final boolean isUserField;
        public final boolean createOnly;
        public final Set<String> entityAliasUsedSet = new HashSet<>();

        public FieldInfo(EntityDefinition ed, MNode fieldNode) {
            this.ed = ed;
            this.fieldNode = fieldNode;
            entityName = ed.getFullEntityName();

            Map<String, String> fnAttrs = fieldNode.getAttributes();
            String nameAttr = fnAttrs.get("name");
            if (nameAttr == null) throw new EntityException("No name attribute specified for field in entity " + entityName);
            name = nameAttr.intern();
            conditionField = new ConditionField(this);
            String columnNameAttr = fnAttrs.get("column-name");
            columnName = columnNameAttr != null && columnNameAttr.length() > 0 ? columnNameAttr : camelCaseToUnderscored(name);
            defaultStr = fnAttrs.get("default");

            String typeAttr = fnAttrs.get("type");
            if ((typeAttr == null || typeAttr.length() == 0) && (fieldNode.hasChild("complex-alias") || fieldNode.hasChild("case")) && fnAttrs.get("function") != null) {
                // this is probably a calculated value, just default to number-decimal
                typeAttr = "number-decimal";
            }
            type = typeAttr;
            if (type != null && type.length() > 0) {
                String fieldJavaType = ed.efi.getFieldJavaType(type, ed);
                javaType =  fieldJavaType != null ? fieldJavaType : "String";
                typeValue = EntityFacadeImpl.getJavaTypeInt(javaType);
                isTextVeryLong = "text-very-long".equals(type);
            } else {
                throw new EntityException("No type specified or found for field " + name + " on entity " + entityName);
            }
            isPk = "true".equals(fnAttrs.get("is-pk"));
            encrypt = "true".equals(fnAttrs.get("encrypt"));
            enableLocalization = "true".equals(fnAttrs.get("enable-localization"));
            isUserField = "true".equals(fnAttrs.get("is-user-field"));
            isSimple = !enableLocalization && !isUserField;
            String createOnlyAttr = fnAttrs.get("create-only");
            createOnly = createOnlyAttr != null && createOnlyAttr.length() > 0 ? "true".equals(fnAttrs.get("create-only")) : ed.createOnly();
            String enableAuditLogAttr = fieldNode.attribute("enable-audit-log");
            enableAuditLog = enableAuditLogAttr != null ? enableAuditLogAttr : ed.internalEntityNode.attribute("enable-audit-log");

            String fcn = ed.makeFullColumnName(fieldNode);
            if (fcn == null) {
                fullColumnName = columnName;
                expandColumnName = null;
            } else {
                if (fcn.contains("${")) {
                    expandColumnName = fcn;
                    fullColumnName = null;
                } else {
                    fullColumnName = fcn;
                    expandColumnName = null;
                }
            }

            if (ed.isViewEntity()) {
                String entityAlias = fieldNode.attribute("entity-alias");
                if (entityAlias != null && entityAlias.length() > 0) entityAliasUsedSet.add(entityAlias);
                ArrayList<MNode> cafList = fieldNode.descendants("complex-alias-field");
                int cafListSize = cafList.size();
                for (int i = 0; i < cafListSize; i++) {
                    MNode cafNode = cafList.get(i);
                    String cafEntityAlias = cafNode.attribute("entity-alias");
                    if (cafEntityAlias != null && cafEntityAlias.length() > 0) entityAliasUsedSet.add(cafEntityAlias);
                }
            }
        }

        public String getFullColumnName() {
            if (fullColumnName != null) return fullColumnName;
            return ed.efi.ecfi.getResourceFacade().expand(expandColumnName, "", null, false);
        }
    }
    private static Map<String, String> camelToUnderscoreMap = new HashMap<>();
    public static String camelCaseToUnderscored(String camelCase) {
        if (camelCase == null || camelCase.length() == 0) return "";
        String usv = camelToUnderscoreMap.get(camelCase);
        if (usv != null) return usv;

        StringBuilder underscored = new StringBuilder();
        underscored.append(Character.toUpperCase(camelCase.charAt(0)));
        int inPos = 1;
        while (inPos < camelCase.length()) {
            char curChar = camelCase.charAt(inPos);
            if (Character.isUpperCase(curChar)) underscored.append('_');
            underscored.append(Character.toUpperCase(curChar));
            inPos++;
        }

        usv = underscored.toString();
        camelToUnderscoreMap.put(camelCase, usv);
        return usv;
    }

    public static class EntityConditionParameter {
        protected FieldInfo fieldInfo;
        protected Object value;
        protected EntityQueryBuilder eqb;

        public EntityConditionParameter(FieldInfo fieldInfo, Object value, EntityQueryBuilder eqb) {
            this.fieldInfo = fieldInfo;
            this.value = value;
            this.eqb = eqb;
        }

        public FieldInfo getFieldInfo() { return fieldInfo; }

        public Object getValue() { return value; }

        public void setPreparedStatementValue(int index) throws EntityException {
            eqb.setPreparedStatementValue(index, value, fieldInfo);
        }

        @Override
        public String toString() { return fieldInfo.name + ':' + value; }
    }

    public static class QueryStatsInfo {
        private String entityName;
        private String sql;
        private long hitCount = 0, errorCount = 0;
        private long minTimeNanos = Long.MAX_VALUE, maxTimeNanos = 0, totalTimeNanos = 0, totalSquaredTime = 0;
        private Map<String, Integer> artifactCounts = new HashMap<>();
        public QueryStatsInfo(String entityName, String sql) {
            this.entityName = entityName;
            this.sql = sql;
        }
        public void countHit(EntityFacadeImpl efi, long runTimeNanos, boolean isError) {
            hitCount++;
            if (isError) errorCount++;
            if (runTimeNanos < minTimeNanos) minTimeNanos = runTimeNanos;
            if (runTimeNanos > maxTimeNanos) maxTimeNanos = runTimeNanos;
            totalTimeNanos += runTimeNanos;
            totalSquaredTime += runTimeNanos * runTimeNanos;
            // this gets much more expensive, consider commenting in the future
            ArtifactExecutionInfo aei = efi.getEcfi().getEci().getArtifactExecutionImpl().peek();
            if (aei != null) aei = aei.getParent();
            if (aei != null) {
                String artifactName = aei.getName();
                Integer artifactCount = artifactCounts.get(artifactName);
                artifactCounts.put(artifactName, artifactCount != null ? artifactCount + 1 : 1);
            }
        }
        public String getEntityName() { return entityName; }
        public String getSql() { return sql; }
        // public long getHitCount() { return hitCount; }
        public long getErrorCount() { return errorCount; }
        // public long getMinTimeNanos() { return minTimeNanos; }
        // public long getMaxTimeNanos() { return maxTimeNanos; }
        // public long getTotalTimeNanos() { return totalTimeNanos; }
        // public long getTotalSquaredTime() { return totalSquaredTime; }
        double getAverage() { return hitCount > 0 ? totalTimeNanos / hitCount : 0; }
        double getStdDev() {
            if (hitCount < 2) return 0;
            return Math.sqrt(Math.abs(totalSquaredTime - ((totalTimeNanos * totalTimeNanos) / hitCount)) / (hitCount - 1L));
        }
        final static long nanosDivisor = 1000;
        public Map<String, Object> makeDisplayMap() {
            Map<String, Object> dm = new HashMap<>();
            dm.put("entityName", entityName); dm.put("sql", sql);
            dm.put("hitCount", hitCount); dm.put("errorCount", errorCount);
            dm.put("minTime", new BigDecimal(minTimeNanos/nanosDivisor)); dm.put("maxTime", new BigDecimal(maxTimeNanos/nanosDivisor));
            dm.put("totalTime", new BigDecimal(totalTimeNanos/nanosDivisor)); dm.put("totalSquaredTime", new BigDecimal(totalSquaredTime/nanosDivisor));
            dm.put("average", new BigDecimal(getAverage()/nanosDivisor)); dm.put("stdDev", new BigDecimal(getStdDev()/nanosDivisor));
            dm.put("artifactCounts", new HashMap<>(artifactCounts));
            return dm;
        }
    }

    public enum WriteMode { CREATE, UPDATE, DELETE }
    public static class EntityWriteInfo {
        public WriteMode writeMode;
        public EntityValueBase evb;
        Map<String, Object> pkMap;
        public EntityWriteInfo(EntityValueBase evb, WriteMode writeMode) {
            // clone value so that create/update/delete stays the same no matter what happens after
            this.evb = (EntityValueBase) evb.cloneValue();
            this.writeMode = writeMode;
            this.pkMap = evb.getPrimaryKeys();
        }
    }
}
