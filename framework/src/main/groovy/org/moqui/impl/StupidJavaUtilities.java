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
package org.moqui.impl;

import groovy.lang.GString;

import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.*;

/** Methods that work better in Java than Groovy, helps with performance and for syntax and language feature reasons */
public class StupidJavaUtilities {
    public static class KeyValue {
        public String key;
        public Object value;
        public KeyValue(String key, Object value) { this.key = key; this.value = value; }
    }

    public static String toPlainString(Object obj) {
        if (obj == null) return "";
        Class objClass = obj.getClass();
        // Common case, check first
        if (objClass == String.class) return (String) obj;
        // BigDecimal toString() uses scientific notation, annoying, so use toPlainString()
        if (objClass == BigDecimal.class) return ((BigDecimal) obj).toPlainString();
        // handle the special case of timestamps used for primary keys, make sure we avoid TZ, etc problems
        if (objClass == Timestamp.class) return Long.toString(((Timestamp) obj).getTime());
        if (objClass == java.sql.Date.class) return Long.toString(((java.sql.Date) obj).getTime());
        if (objClass == Time.class) return Long.toString(((Time) obj).getTime());
        if (obj instanceof Collection) {
            Collection col = (Collection) obj;
            StringBuilder sb = new StringBuilder();
            for (Object entry : col) {
                if (entry == null) continue;
                if (sb.length() > 0) sb.append(",");
                sb.append(entry);
            }
            return sb.toString();
        }

        // no special case? do a simple toString()
        return obj.toString();
    }

    /** Like the Groovy empty except doesn't consider empty 0 value numbers, false Boolean, etc; only null values,
     *   0 length String (actually CharSequence to include GString, etc), and 0 size Collection/Map are considered empty. */
    public static boolean isEmpty(Object obj) {
        if (obj == null) return true;
        Class objClass = obj.getClass();
        // some common direct classes
        if (objClass == String.class) return ((String) obj).length() == 0;
        if (objClass == GString.class) return ((GString) obj).length() == 0;
        if (objClass == ArrayList.class) return ((ArrayList) obj).size() == 0;
        if (objClass == HashMap.class) return ((HashMap) obj).size() == 0;
        if (objClass == LinkedHashMap.class) return ((HashMap) obj).size() == 0;
        // hopefully less common sub-classes
        if (obj instanceof CharSequence) return ((CharSequence) obj).length() == 0;
        if (obj instanceof Collection) return ((Collection) obj).size() == 0;
        if (obj instanceof Map) return ((Map) obj).size() == 0;
        return false;
    }

    public static Class getClass(String javaType) {
        Class theClass = StupidClassLoader.commonJavaClassesMap.get(javaType);
        if (theClass == null) {
            try {
                theClass = Thread.currentThread().getContextClassLoader().loadClass(javaType);
            } catch (ClassNotFoundException e) { /* ignore */ }
        }
        return theClass;
    }
    public static boolean isInstanceOf(Object theObjectInQuestion, String javaType) {
        Class theClass = StupidClassLoader.commonJavaClassesMap.get(javaType);
        if (theClass == null) {
            try {
                theClass = Thread.currentThread().getContextClassLoader().loadClass(javaType);
            } catch (ClassNotFoundException e) { /* ignore */ }
        }
        if (theClass == null) throw new IllegalArgumentException("Cannot find class for type: " + javaType);
        return theClass.isInstance(theObjectInQuestion);
    }

    public static String removeChar(String orig, char ch) {
        // NOTE: this seems to run pretty slow, plain replace might be faster, but avoiding its use anyway (in ServiceFacadeImpl for SECA rules)
        char[] newChars = new char[orig.length()];
        int origLength = orig.length();
        int lastPos = 0;
        for (int i = 0; i < origLength; i++) {
            char curChar = orig.charAt(i);
            if (curChar != ch) {
                newChars[lastPos] = curChar;
                lastPos++;
            }
        }
        if (lastPos == origLength) return orig;
        return new String(newChars, 0, lastPos);
    }

    public static class MapOrderByComparator implements Comparator<Map> {
        ArrayList<String> fieldNameList;
        int fieldNameListSize;

        final static char minusChar = '-';
        final static char plusChar = '+';

        public MapOrderByComparator(List<String> fieldNameList) {
            this.fieldNameList = fieldNameList instanceof ArrayList ? (ArrayList<String>) fieldNameList : new ArrayList<>(fieldNameList);
            fieldNameListSize = fieldNameList.size();
        }

        @Override
        @SuppressWarnings("unchecked")
        public int compare(Map map1, Map map2) {
            if (map1 == null) return -1;
            if (map2 == null) return 1;
            for (int i = 0; i < fieldNameListSize; i++) {
                String fieldName = fieldNameList.get(i);
                boolean ascending = true;
                if (fieldName.charAt(0) == minusChar) {
                    ascending = false;
                    fieldName = fieldName.substring(1);
                } else if (fieldName.charAt(0) == plusChar) {
                    fieldName = fieldName.substring(1);
                }
                Comparable value1 = (Comparable) map1.get(fieldName);
                Comparable value2 = (Comparable) map2.get(fieldName);
                // NOTE: nulls go earlier in the list for ascending, later in the list for !ascending
                if (value1 == null) {
                    if (value2 != null) return ascending ? 1 : -1;
                } else {
                    if (value2 == null) {
                        return ascending ? -1 : 1;
                    } else {
                        int comp = value1.compareTo(value2);
                        if (comp != 0) return ascending ? comp : -comp;
                    }
                }
            }
            // all evaluated to 0, so is the same, so return 0
            return 0;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof MapOrderByComparator && fieldNameList.equals(((MapOrderByComparator) obj).fieldNameList);
        }

        @Override
        public String toString() { return fieldNameList.toString(); }
    }

    // Lookup table for CRC16 based on irreducible polynomial: 1 + x^2 + x^15 + x^16
    private static final int[] crc16Table = {
            0x0000, 0xC0C1, 0xC181, 0x0140, 0xC301, 0x03C0, 0x0280, 0xC241,
            0xC601, 0x06C0, 0x0780, 0xC741, 0x0500, 0xC5C1, 0xC481, 0x0440,
            0xCC01, 0x0CC0, 0x0D80, 0xCD41, 0x0F00, 0xCFC1, 0xCE81, 0x0E40,
            0x0A00, 0xCAC1, 0xCB81, 0x0B40, 0xC901, 0x09C0, 0x0880, 0xC841,
            0xD801, 0x18C0, 0x1980, 0xD941, 0x1B00, 0xDBC1, 0xDA81, 0x1A40,
            0x1E00, 0xDEC1, 0xDF81, 0x1F40, 0xDD01, 0x1DC0, 0x1C80, 0xDC41,
            0x1400, 0xD4C1, 0xD581, 0x1540, 0xD701, 0x17C0, 0x1680, 0xD641,
            0xD201, 0x12C0, 0x1380, 0xD341, 0x1100, 0xD1C1, 0xD081, 0x1040,
            0xF001, 0x30C0, 0x3180, 0xF141, 0x3300, 0xF3C1, 0xF281, 0x3240,
            0x3600, 0xF6C1, 0xF781, 0x3740, 0xF501, 0x35C0, 0x3480, 0xF441,
            0x3C00, 0xFCC1, 0xFD81, 0x3D40, 0xFF01, 0x3FC0, 0x3E80, 0xFE41,
            0xFA01, 0x3AC0, 0x3B80, 0xFB41, 0x3900, 0xF9C1, 0xF881, 0x3840,
            0x2800, 0xE8C1, 0xE981, 0x2940, 0xEB01, 0x2BC0, 0x2A80, 0xEA41,
            0xEE01, 0x2EC0, 0x2F80, 0xEF41, 0x2D00, 0xEDC1, 0xEC81, 0x2C40,
            0xE401, 0x24C0, 0x2580, 0xE541, 0x2700, 0xE7C1, 0xE681, 0x2640,
            0x2200, 0xE2C1, 0xE381, 0x2340, 0xE101, 0x21C0, 0x2080, 0xE041,
            0xA001, 0x60C0, 0x6180, 0xA141, 0x6300, 0xA3C1, 0xA281, 0x6240,
            0x6600, 0xA6C1, 0xA781, 0x6740, 0xA501, 0x65C0, 0x6480, 0xA441,
            0x6C00, 0xACC1, 0xAD81, 0x6D40, 0xAF01, 0x6FC0, 0x6E80, 0xAE41,
            0xAA01, 0x6AC0, 0x6B80, 0xAB41, 0x6900, 0xA9C1, 0xA881, 0x6840,
            0x7800, 0xB8C1, 0xB981, 0x7940, 0xBB01, 0x7BC0, 0x7A80, 0xBA41,
            0xBE01, 0x7EC0, 0x7F80, 0xBF41, 0x7D00, 0xBDC1, 0xBC81, 0x7C40,
            0xB401, 0x74C0, 0x7580, 0xB541, 0x7700, 0xB7C1, 0xB681, 0x7640,
            0x7200, 0xB2C1, 0xB381, 0x7340, 0xB101, 0x71C0, 0x7080, 0xB041,
            0x5000, 0x90C1, 0x9181, 0x5140, 0x9301, 0x53C0, 0x5280, 0x9241,
            0x9601, 0x56C0, 0x5780, 0x9741, 0x5500, 0x95C1, 0x9481, 0x5440,
            0x9C01, 0x5CC0, 0x5D80, 0x9D41, 0x5F00, 0x9FC1, 0x9E81, 0x5E40,
            0x5A00, 0x9AC1, 0x9B81, 0x5B40, 0x9901, 0x59C0, 0x5880, 0x9841,
            0x8801, 0x48C0, 0x4980, 0x8941, 0x4B00, 0x8BC1, 0x8A81, 0x4A40,
            0x4E00, 0x8EC1, 0x8F81, 0x4F40, 0x8D01, 0x4DC0, 0x4C80, 0x8C41,
            0x4400, 0x84C1, 0x8581, 0x4540, 0x8701, 0x47C0, 0x4680, 0x8641,
            0x8201, 0x42C0, 0x4380, 0x8341, 0x4100, 0x81C1, 0x8081, 0x4040,
    };

    @SuppressWarnings("unused")
    public static int calculateCrc16(String input) {
        byte[] bytes = input.getBytes();
        int crc = 0x0000;
        for (byte b : bytes) crc = (crc >>> 8) ^ crc16Table[(crc ^ b) & 0xff];
        return crc;
    }
}
