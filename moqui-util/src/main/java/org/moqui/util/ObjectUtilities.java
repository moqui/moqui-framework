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
package org.moqui.util;

import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.moqui.BaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * These are utilities that should exist elsewhere, but I can't find a good simple library for them, and they are
 * stupid but necessary for certain things.
 */
@SuppressWarnings("unused")
public class ObjectUtilities {
    protected static final Logger logger = LoggerFactory.getLogger(ObjectUtilities.class);
    public static final Map<String, Integer> calendarFieldByUomId;
    public static final Map<String, TemporalUnit> temporalUnitByUomId;
    static {
        HashMap<String, Integer> cfm = new HashMap<>(8);
        cfm.put("TF_ms", Calendar.MILLISECOND); cfm.put("TF_s", Calendar.SECOND); cfm.put("TF_min", Calendar.MINUTE);
        cfm.put("TF_hr", Calendar.HOUR); cfm.put("TF_day", Calendar.DAY_OF_MONTH); cfm.put("TF_wk", Calendar.WEEK_OF_YEAR);
        cfm.put("TF_mon", Calendar.MONTH); cfm.put("TF_yr", Calendar.YEAR);
        calendarFieldByUomId = cfm;

        HashMap<String, TemporalUnit> tum = new HashMap<>(8);
        tum.put("TF_ms", ChronoUnit.MILLIS); tum.put("TF_s", ChronoUnit.SECONDS); tum.put("TF_min", ChronoUnit.MINUTES);
        tum.put("TF_hr", ChronoUnit.HOURS); tum.put("TF_day", ChronoUnit.DAYS); tum.put("TF_wk", ChronoUnit.WEEKS);
        tum.put("TF_mon", ChronoUnit.MONTHS); tum.put("TF_yr", ChronoUnit.YEARS);
        temporalUnitByUomId = tum;
    }


    @SuppressWarnings("unchecked")
    public static Object basicConvert(Object value, final String javaType) {
        if (value == null) return null;

        Class theClass = MClassLoader.getCommonClass(javaType);
        // only support the classes we have pre-configured
        if (theClass == null) return value;
        boolean origString = false;
        if (value instanceof CharSequence) {
            value = value.toString();
            origString = true;
        }
        Class origClass = origString ? String.class : value.getClass();
        if (origClass.equals(theClass)) return value;
        try {
            if (origString) {
                if (theClass == Boolean.class) {
                    // for non-empty String to Boolean don't use normal not-empty rules, look for "true", "false", etc
                    String valStr = value.toString();
                    return "true".equalsIgnoreCase(valStr) || "y".equalsIgnoreCase(valStr);
                } else if (theClass == Integer.class) {
                    // groovy does funny things with single character strings, ie gets the int value of the single char, so do it ourselves
                    return Integer.valueOf(value.toString());
                } else if (theClass == Long.class) {
                    return Long.valueOf(value.toString());
                } else if (theClass == BigDecimal.class) {
                    return new BigDecimal(value.toString());
                }
            }
            if (theClass == Date.class && value instanceof Timestamp) {
                // Groovy doesn't handle this one, but easy conversion
                return new Date(((Timestamp) value).getTime());
            } else {
                // let groovy do the work
                // logger.warn("Converted " + value + " of type " + origClass.getName() + " to " + DefaultGroovyMethods.asType(value, theClass) + " for class " + theClass.getName());
                return DefaultGroovyMethods.asType(value, theClass);
            }
        } catch (Throwable t) {
            logger.warn("Error doing type conversion to " + javaType + " for value [" + value + "]", t);
            return value;
        }
    }

    public static boolean compareLike(Object value1, Object value2) {
        // nothing to be like? consider a match
        if (value2 == null) return true;
        // something to be like but nothing to compare? consider a mismatch
        if (value1 == null) return false;
        if (value1 instanceof CharSequence && value2 instanceof CharSequence) {
            // first escape the characters that would be interpreted as part of the regular expression
            int length2 = ((CharSequence) value2).length();
            StringBuilder sb = new StringBuilder(length2 * 2);
            for (int i = 0; i < length2; i++) {
                char c = ((CharSequence) value2).charAt(i);
                if ("[](){}.*+?$^|#\\".indexOf(c) != -1) sb.append('\\');
                sb.append(c);
            }
            // change the SQL wildcards to regex wildcards
            String regex = sb.toString().replace("_", ".").replace("%", ".*?");
            // run it...
            Pattern pattern = Pattern.compile(regex, (Pattern.CASE_INSENSITIVE | Pattern.DOTALL));
            return pattern.matcher(value1.toString()).matches();
        } else {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    public static boolean compare(Object field, final String operator, final String value, Object toField, String format, final String type) {
        if (isEmpty(toField)) toField = value;

        // FUTURE handle type conversion with format for Date, Time, Timestamp
        // if (format) { }

        field = basicConvert(field, type);
        toField = basicConvert(toField, type);

        boolean result;
        if ("less".equals(operator)) { result = compareObj(field, toField) < 0; }
        else if ("greater".equals(operator)) { result = compareObj(field, toField) > 0; }
        else if ("less-equals".equals(operator)) { result = compareObj(field, toField) <= 0; }
        else if ("greater-equals".equals(operator)) { result = compareObj(field, toField) >= 0; }
        else if ("contains".equals(operator)) { result = Objects.toString(field).contains(Objects.toString(toField)); }
        else if ("not-contains".equals(operator)) { result = !Objects.toString(field).contains(Objects.toString(toField)); }
        else if ("empty".equals(operator)) { result = isEmpty(field); }
        else if ("not-empty".equals(operator)) { result = !isEmpty(field); }
        else if ("matches".equals(operator)) { result = Objects.toString(field).matches(toField.toString()); }
        else if ("not-matches".equals(operator)) { result = !Objects.toString(field).matches(toField.toString()); }
        else if ("not-equals".equals(operator)) { result = !Objects.equals(field, toField); }
        else { result = Objects.equals(field, toField); }

        if (logger.isTraceEnabled()) logger.trace("Compare result [" + result + "] for field [" + field + "] operator [" + operator + "] value [" + value + "] toField [" + toField + "] type [" + type + "]");
        return result;
    }

    @SuppressWarnings("unchecked")
    public static int compareObj(Object field1, Object field2) {
        if (field1 == null) {
            if (field2 == null) return 0;
            else return 1;
        }
        Comparable comp1 = makeComparable(field1);
        Comparable comp2 = makeComparable(field2);
        return comp1.compareTo(comp2);
    }
    public static Comparable makeComparable(final Object obj) {
        if (obj == null) return null;
        if (obj instanceof Comparable) return (Comparable) obj;
        else throw new IllegalArgumentException("Object of type " + obj.getClass().getName() + " is not Comparable, cannot compare");
    }

    public static int countChars(String s, boolean countDigits, boolean countLetters, boolean countOthers) {
        // this seems like it should be part of some standard Java API, but I haven't found it
        // (can use Pattern/Matcher, but that is even uglier and probably a lot slower)
        int count = 0;
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                if (countDigits) count++;
            } else if (Character.isLetter(c)) {
                if (countLetters) count++;
            } else {
                if (countOthers) count++;
            }
        }
        return count;
    }

    public static int countChars(String s, char cMatch) {
        int count = 0;
        for (char c : s.toCharArray()) if (c == cMatch) count++;
        return count;
    }

    public static String getStreamText(InputStream is) { return getStreamText(is, StandardCharsets.UTF_8); }
    public static String getStreamText(InputStream is, Charset charset) {
        if (is == null) return null;
        Reader r = null;
        try {
            r = new InputStreamReader(new BufferedInputStream(is), charset);

            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int i;
            while ((i = r.read(buf, 0, 4096)) > 0) sb.append(buf, 0, i);
            return sb.toString();
        } catch (IOException e) {
            throw new BaseException("Error getting stream text", e);
        } finally {
            try {
                if (r != null) r.close();
            } catch (IOException e) {
                logger.warn("Error in close after reading text from stream", e);
            }
        }
    }

    public static int copyStream(InputStream is, OutputStream os) {
        byte[] buffer = new byte[4096];
        int totalLen = 0;
        try {
            int len = is.read(buffer);
            while (len != -1) {
                totalLen += len;
                os.write(buffer, 0, len);
                len = is.read(buffer);
                if (Thread.interrupted()) break;
            }
            return totalLen;
        } catch (IOException e) {
            throw new BaseException("Error copying stream", e);
        }
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
        if (objClass == Date.class) return Long.toString(((Date) obj).getTime());
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
        /* faster not to do this
        Class objClass = obj.getClass();
        // some common direct classes
        if (objClass == String.class) return ((String) obj).length() == 0;
        if (objClass == GString.class) return ((GString) obj).length() == 0;
        if (objClass == ArrayList.class) return ((ArrayList) obj).size() == 0;
        if (objClass == HashMap.class) return ((HashMap) obj).size() == 0;
        if (objClass == LinkedHashMap.class) return ((HashMap) obj).size() == 0;
        // hopefully less common sub-classes
        */
        if (obj instanceof CharSequence) return ((CharSequence) obj).length() == 0;
        if (obj instanceof Collection) return ((Collection) obj).size() == 0;
        return obj instanceof Map && ((Map) obj).size() == 0;
    }

    public static Class getClass(String javaType) {
        Class theClass = MClassLoader.getCommonClass(javaType);
        if (theClass == null) {
            try {
                theClass = Thread.currentThread().getContextClassLoader().loadClass(javaType);
            } catch (ClassNotFoundException e) { /* ignore */ }
        }
        return theClass;
    }

    public static boolean isInstanceOf(Object theObjectInQuestion, String javaType) {
        Class theClass = MClassLoader.getCommonClass(javaType);
        if (theClass == null) {
            try {
                theClass = Thread.currentThread().getContextClassLoader().loadClass(javaType);
            } catch (ClassNotFoundException e) { /* ignore */ }
        }
        if (theClass == null) throw new IllegalArgumentException("Cannot find class for type: " + javaType);
        return theClass.isInstance(theObjectInQuestion);
    }

    public static Number addNumbers(Number a, Number b) {
        if (a == null) return b;
        if (b == null) return a;
        Class<?> aClass = a.getClass();
        Class<?> bClass = b.getClass();
        // handle BigDecimal as a special case, most common case
        if (aClass == BigDecimal.class) {
            if (bClass == BigDecimal.class) return ((BigDecimal) a).add((BigDecimal) b);
            else return ((BigDecimal) a).add(new BigDecimal(b.toString()));
        }
        if (bClass == BigDecimal.class) {
            if (aClass == BigDecimal.class) return ((BigDecimal) b).add((BigDecimal) a);
            else return ((BigDecimal) b).add(new BigDecimal(a.toString()));
        }
        // handle other numbers in descending order of most to least precision
        if (aClass == Double.class || bClass == Double.class) {
            return a.doubleValue() + b.doubleValue();
        } else if (aClass == Float.class || bClass == Float.class) {
            return a.floatValue() + b.floatValue();
        } else if (aClass == Long.class || bClass == Long.class) {
            return a.longValue() + b.longValue();
        } else {
            return a.intValue() + b.intValue();
        }
    }

    public static int getCalendarFieldFromUomId(final String uomId) {
        Integer calField = calendarFieldByUomId.get(uomId);
        if (calField == null) throw new IllegalArgumentException("No equivalent Calendar field found for UOM ID " + uomId);
        return calField;
    }

    public static TemporalUnit getTemporalUnitFromUomId(final String uomId) {
        TemporalUnit temporalUnit = temporalUnitByUomId.get(uomId);
        if (temporalUnit == null) throw new IllegalArgumentException("No equivalent Temporal Unit found for UOM ID " + uomId);
        return temporalUnit;
    }
}
