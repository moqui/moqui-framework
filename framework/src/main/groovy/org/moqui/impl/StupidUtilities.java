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

import groovy.util.Node;
import groovy.util.NodeList;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.moqui.BaseException;
import org.moqui.util.MClassLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import java.io.*;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Date;
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
public class StupidUtilities {
    protected static final Logger logger = LoggerFactory.getLogger(StupidUtilities.class);

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
        if (StupidJavaUtilities.isEmpty(toField)) toField = value;
        if (field == null) throw new IllegalArgumentException("Cannot compare null value");

        // FUTURE handle type conversion with format for Date, Time, Timestamp
        // if (format) { }

        field = basicConvert(field, type);
        toField = basicConvert(toField, type);

        boolean result;
        if ("less".equals(operator)) { result = (makeComparable(field).compareTo(makeComparable(toField)) < 0); }
        else if ("greater".equals(operator)) { result = (makeComparable(field).compareTo(makeComparable(toField)) > 0); }
        else if ("less-equals".equals(operator)) { result = (makeComparable(field).compareTo(makeComparable(toField)) <= 0); }
        else if ("greater-equals".equals(operator)) { result = (makeComparable(field).compareTo(makeComparable(toField)) >= 0); }
        else if ("contains".equals(operator)) { result = field.toString().contains(toField.toString()); }
        else if ("not-contains".equals(operator)) { result = !field.toString().contains(toField.toString()); }
        else if ("empty".equals(operator)) { result = StupidJavaUtilities.isEmpty(field); }
        else if ("not-empty".equals(operator)) { result = !StupidJavaUtilities.isEmpty(field); }
        else if ("matches".equals(operator)) { result = field.toString().matches(toField.toString()); }
        else if ("not-matches".equals(operator)) { result = !field.toString().matches(toField.toString()); }
        else if ("not-equals".equals(operator)) { result = !field.equals(toField); }
        else { result = field.equals(toField); }

        if (logger.isTraceEnabled()) logger.trace("Compare result [" + result + "] for field [" + field + "] operator [" + operator + "] value [" + value + "] toField [" + toField + "] type [" + type + "]");
        return result;
    }

    public static Comparable makeComparable(final Object obj) {
        if (obj == null) return null;
        if (obj instanceof Comparable) return (Comparable) obj;
        else throw new IllegalArgumentException("Object of type " + obj.getClass().getName() + " is not Comparable, cannot compare");
    }

    public static void filterMapList(List<Map> theList, Map<String, Object> fieldValues) {
        if (theList == null || theList.size() == 0 || fieldValues == null || fieldValues.size() == 0) return;

        Iterator<Map> theIterator = theList.iterator();
        while (theIterator.hasNext()) {
            Map curMap = theIterator.next();
            for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
                if (!curMap.get(entry.getKey()).equals(entry.getValue())) {
                    theIterator.remove();
                    break;
                }
            }
        }
    }

    public static void filterMapListByDate(List<Map> theList, String fromDateName, String thruDateName, Timestamp compareStamp) {
        if (theList == null || theList.size() == 0) return;

        if (fromDateName == null || fromDateName.isEmpty()) fromDateName = "fromDate";
        if (thruDateName == null || thruDateName.isEmpty()) thruDateName = "thruDate";
        // no access to ec.user here, so this should always be passed in, but just in case
        if (compareStamp == null) compareStamp = new Timestamp(System.currentTimeMillis());

        Iterator<Map> theIterator = theList.iterator();
        while (theIterator.hasNext()) {
            Map curMap = theIterator.next();
            Timestamp fromDate = DefaultGroovyMethods.asType(curMap.get(fromDateName), Timestamp.class);
            if (fromDate != null && compareStamp.compareTo(fromDate) < 0) {
                theIterator.remove();
                continue;
            }
            Timestamp thruDate = DefaultGroovyMethods.asType(curMap.get(thruDateName), Timestamp.class);
            if (thruDate != null && compareStamp.compareTo(thruDate) >= 0) theIterator.remove();
        }
    }

    public static void filterMapListByDate(List<Map> theList, String fromDateName, String thruDateName, Timestamp compareStamp, boolean ignoreIfEmpty) {
        if (ignoreIfEmpty && compareStamp == null) return;
        filterMapListByDate(theList, fromDateName, thruDateName, compareStamp);
    }

    /**
     * Order list elements in place (modifies the list passed in), returns the list for convenience
     */
    public static List<Map<String, Object>> orderMapList(List<Map<String, Object>> theList, List<String> fieldNames) {
        if (fieldNames == null) throw new IllegalArgumentException("Cannot order List of Maps with null order by field list");
        // this seems unnecessary, but is because Groovy allows a GString even in a List<String>, but MapOrderByComparator in Java blows up
        ArrayList<String> fieldNameArray = new ArrayList<>();
        for (String fieldName : fieldNames) fieldNameArray.add(fieldName);
        if (theList != null && fieldNames.size() > 0)
            Collections.sort(theList, new StupidJavaUtilities.MapOrderByComparator(fieldNameArray));
        return theList;
    }

    /**
     * For a list of Map find the entry that best matches the fieldsByPriority Ordered Map; null field values in a Map
     * in mapList match against any value but do not contribute to maximal match score, otherwise value for each field
     * in fieldsByPriority must match for it to be a candidate.
     */
    public static Map<String, Object> findMaximalMatch(List<Map<String, Object>> mapList, LinkedHashMap<String, Object> fieldsByPriority) {
        int numFields = fieldsByPriority.size();
        String[] fieldNames = new String[numFields];
        Object[] fieldValues = new String[numFields];
        int index = 0;
        for (Map.Entry<String, Object> entry : fieldsByPriority.entrySet()) {
            fieldNames[index] = entry.getKey();
            fieldValues[index] = entry.getValue();
            index++;
        }

        int highScore = -1;
        Map<String, Object> highMap = null;
        for (Map<String, Object> curMap : mapList) {
            int curScore = 0;
            boolean skipMap = false;
            for (int i = 0; i < numFields; i++) {
                String curField = fieldNames[i];
                Object compareValue = fieldValues[i];
                // if curMap value is null skip field (null value in Map means allow any match value
                Object curValue = curMap.get(curField);
                if (curValue == null) continue;
                // if not equal skip Map
                if (!curValue.equals(compareValue)) {
                    skipMap = true;
                    break;
                }
                // add to score based on index (lower index higher score), also add numFields so more fields matched weights higher
                curScore += (numFields - i) + numFields;
            }

            if (skipMap) continue;
            // have a higher score?
            if (curScore > highScore) {
                highScore = curScore;
                highMap = curMap;
            }
        }

        return highMap;
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

    public static String getStreamText(InputStream is) {
        if (is == null) return null;
        Reader r = null;
        try {
            r = new InputStreamReader(new BufferedInputStream(is), StandardCharsets.UTF_8);

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

    @SuppressWarnings("unchecked")
    public static void addToListInMap(Object key, Object value, Map theMap) {
        if (theMap == null) return;

        List theList = (List) theMap.get(key);
        if (theList == null) {
            theList = new ArrayList();
            theMap.put(key, theList);
        }
        theList.add(value);
    }

    @SuppressWarnings("unchecked")
    public static boolean addToSetInMap(Object key, Object value, Map theMap) {
        if (theMap == null) return false;
        Set theSet = (Set) theMap.get(key);
        if (theSet == null) {
            theSet = new LinkedHashSet();
            theMap.put(key, theSet);
        }
        return theSet.add(value);
    }

    @SuppressWarnings("unchecked")
    public static void addToMapInMap(Object keyOuter, Object keyInner, Object value, Map theMap) {
        if (theMap == null) return;
        Map innerMap = (Map) theMap.get(keyOuter);
        if (innerMap == null) {
            innerMap = new LinkedHashMap();
            theMap.put(keyOuter, innerMap);
        }
        innerMap.put(keyInner, value);
    }

    @SuppressWarnings("unchecked")
    public static void addToBigDecimalInMap(Object key, BigDecimal value, Map theMap) {
        if (value == null || theMap == null) return;
        BigDecimal curVal = (BigDecimal) theMap.get(key);
        if (curVal == null) {
            theMap.put(key, value);
        } else {
            theMap.put(key, curVal.add(value));
        }
    }

    /** Find a field value in a nested Map containing fields, Maps, and Collections of Maps (Lists, etc) */
    public static Object findFieldNestedMap(String key, Map theMap) {
        if (theMap.containsKey(key)) return theMap.get(key);
        for (Object value : theMap.values()) {
            if (value instanceof Map) {
                Object fieldValue = findFieldNestedMap(key, (Map) value);
                if (fieldValue != null) return fieldValue;
            } else if (value instanceof Collection) {
                // only look in Collections of Maps
                for (Object colValue : (Collection) value) {
                    if (colValue instanceof Map) {
                        Object fieldValue = findFieldNestedMap(key, (Map) colValue);
                        if (fieldValue != null) return fieldValue;
                    }
                }
            }
        }
        return null;
    }

    /** Find all values of a named field in a nested Map containing fields, Maps, and Collections of Maps (Lists, etc) */
    public static void findAllFieldsNestedMap(String key, Map theMap, Set<Object> valueSet) {
        Object localValue = theMap.get(key);
        if (localValue != null) valueSet.add(localValue);
        for (Object value : theMap.values()) {
            if (value instanceof Map) {
                findAllFieldsNestedMap(key, (Map) value, valueSet);
            } else if (value instanceof Collection) {
                // only look in Collections of Maps
                for (Object colValue : (Collection) value) {
                    if (colValue instanceof Map) findAllFieldsNestedMap(key, (Map) colValue, valueSet);
                }
            }
        }
    }

    /** Creates a single Map with fields from the passed in Map and all nested Maps (for Map and Collection of Map entry values) */
    @SuppressWarnings("unchecked")
    public static Map flattenNestedMap(Map theMap) {
        if (theMap == null) return null;
        Map outMap = new LinkedHashMap();
        for (Object entryObj : theMap.entrySet()) {
            Map.Entry entry = (Map.Entry) entryObj;
            Object value = entry.getValue();
            if (value instanceof Map) {
                outMap.putAll(flattenNestedMap((Map) value));
            } else if (value instanceof Collection) {
                for (Object colValue : (Collection) value) {
                    if (colValue instanceof Map) outMap.putAll(flattenNestedMap((Map) colValue));
                }
            } else {
                outMap.put(entry.getKey(), entry.getValue());
            }
        }
        return outMap;
    }

    /** Removes entries with a null value from the Map, returns the passed in Map for convenience (does not clone before removes!). */
    @SuppressWarnings("unchecked")
    public static Map removeNullsFromMap(Map theMap) {
        Iterator<Map.Entry> iterator = theMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry entry = iterator.next();
            if (entry.getValue() == null) iterator.remove();
        }
        return theMap;
    }

    public static boolean mapMatchesFields(Map<String, Object> baseMap, Map<String, Object> compareMap) {
        for (Map.Entry<String, Object> entry : compareMap.entrySet()) {
            Object compareObj = compareMap.get(entry.getKey());
            Object baseObj = baseMap.get(entry.getKey());
            if (compareObj == null) {
                if (baseObj != null) return false;
            } else {
                if (!compareObj.equals(baseObj)) return false;
            }
        }
        return true;
    }

    public static Node deepCopyNode(Node original) { return deepCopyNode(original, null); }
    @SuppressWarnings("unchecked")
    public static Node deepCopyNode(Node original, Node parent) {
        if (original == null) return null;

        Node newNode = new Node(parent, original.name(), new HashMap(original.attributes()));
        Object newValue = original.value();
        if (newValue != null && newValue instanceof List) {
            NodeList childList = new NodeList();
            for (Object child : (List) newValue) {
                if (child instanceof Node) {
                    childList.add(deepCopyNode((Node) child, newNode));
                } else if (child != null) {
                    childList.add(child);
                }
            }
            newValue = childList;
        }

        if (newValue != null) newNode.setValue(newValue);
        return newNode;
    }

    public static String nodeText(Object nodeObj) {
        if (!DefaultGroovyMethods.asBoolean(nodeObj)) return "";
        Node theNode = null;
        if (nodeObj instanceof Node) {
            theNode = (Node) nodeObj;
        } else if (nodeObj instanceof NodeList) {
            NodeList nl = DefaultGroovyMethods.asType((Collection) nodeObj, NodeList.class);
            if (nl.size() > 0) theNode = (Node) nl.get(0);
        }

        if (theNode == null) return "";
        List<String> textList = theNode.localText();
        if (DefaultGroovyMethods.asBoolean(textList)) {
            if (textList.size() == 1) {
                return textList.get(0);
            } else {
                StringBuilder sb = new StringBuilder();
                for (String txt : textList) sb.append(txt).append("\n");
                return sb.toString();
            }
        } else {
            return "";
        }
    }

    public static Node nodeChild(Node parent, String childName) {
        if (parent == null) return null;
        NodeList childList = (NodeList) parent.get(childName);
        if (childList != null && childList.size() > 0) return (Node) childList.get(0);
        return null;
    }

    public static String elementValue(Element element) {
        if (element == null) return null;
        element.normalize();
        org.w3c.dom.Node textNode = element.getFirstChild();
        if (textNode == null) return null;

        StringBuilder value = new StringBuilder();
        if (textNode.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE || textNode.getNodeType() == org.w3c.dom.Node.TEXT_NODE)
            value.append(textNode.getNodeValue());
        while ((textNode = textNode.getNextSibling()) != null) {
            if (textNode.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE || textNode.getNodeType() == org.w3c.dom.Node.TEXT_NODE)
                value.append(textNode.getNodeValue());
        }

        return value.toString();
    }

    public static String encodeForXmlAttribute(String original) { return encodeForXmlAttribute(original, false); }
    public static String encodeForXmlAttribute(String original, boolean addZeroWidthSpaces) {
        StringBuilder newValue = new StringBuilder(original);
        for (int i = 0; i < newValue.length(); i++) {
            char curChar = newValue.charAt(i);
            switch (curChar) {
                case '\'': newValue.replace(i, i + 1, "&apos;"); i += 5; break;
                case '"': newValue.replace(i, i + 1, "&quot;"); i += 5; break;
                case '&': newValue.replace(i, i + 1, "&amp;"); i += 4; break;
                case '<': newValue.replace(i, i + 1, "&lt;"); i += 3; break;
                case '>': newValue.replace(i, i + 1, "&gt;"); i += 3; break;
                case 0x5: newValue.replace(i, i + 1, "..."); i += 2; break;
                case 0x12: newValue.replace(i, i + 1, "&apos;"); i += 5; break;
                case 0x13: newValue.replace(i, i + 1, "&quot;"); i += 5; break;
                case 0x14: newValue.replace(i, i + 1, "&quot;"); i += 5; break;
                case 0x16: newValue.replace(i, i + 1, "-"); break;
                case 0x17: newValue.replace(i, i + 1, "-"); break;
                case 0x19: newValue.replace(i, i + 1, "tm"); i++; break;
                default:
                    if (DefaultGroovyMethods.compareTo(curChar, 0x20) < 0 && curChar != 0x9 && curChar != 0xA && curChar != 0xD) {
                        // the only valid values < 0x20 are 0x9 (tab), 0xA (newline), 0xD (carriage return)
                        newValue.deleteCharAt(i);
                        i--;
                    } else if (DefaultGroovyMethods.compareTo(curChar, 0x7F) > 0) {
                        // Replace each char which is out of the ASCII range with a XML entity
                        String s = "&#" + ((int) curChar) + ";";
                        newValue.replace(i, i + 1, s);
                        i += s.length() - 1;
                    } else if (addZeroWidthSpaces) {
                        newValue.insert(i, "&#8203;");
                        i += 7;
                    }
            }
        }
        return newValue.toString();
    }

    private static final Map<String, String> xmlEntityMap;
    static {
        HashMap<String, String> map = new HashMap<>(5);
        map.put("apos", "\'"); map.put("quot", "\""); map.put("amp", "&"); map.put("lt", "<"); map.put("gt", ">");
        xmlEntityMap = map;
    }
    public static String decodeFromXml(String original) {
        if (original == null || original.isEmpty()) return original;
        int pos = original.indexOf("&");
        if (pos == -1) return original;

        StringBuilder newValue = new StringBuilder(original);
        while (pos < newValue.length() && pos >= 0) {
            int scIndex = newValue.indexOf(";", pos + 1);
            if (scIndex == -1) break;
            String entityName = newValue.substring(pos + 1, scIndex);
            String replaceChar;
            if (entityName.charAt(0) == '#') {
                String decStr = entityName.substring(1);
                int decInt = Integer.valueOf(decStr);
                replaceChar = new String(Character.toChars(decInt));
            } else {
                replaceChar = xmlEntityMap.get(entityName);
            }
            // logger.warn("========= pos=${pos}, entityName=${entityName}, replaceChar=${replaceChar}")
            if (replaceChar != null) newValue.replace(pos, scIndex + 1, replaceChar);
            pos = newValue.indexOf("&", pos + 1);
        }
        return newValue.toString();
    }

    private static final String badJavaNameChars = "\\*&?![]^+-.$:<>()#";
    public static String cleanStringForJavaName(String original) {
        StringBuilder newValue = new StringBuilder(original);
        for (int i = 0; i < newValue.length(); i++) {
            char curChar = newValue.charAt(i);
            if (badJavaNameChars.indexOf(curChar) != -1) newValue.replace(i, i + 1, "_");
        }

        return newValue.toString();
    }

    public static String encodeAsciiFilename(String filename) {
        try {
            URI uri = new URI(null, null, filename, null);
            return uri.toASCIIString();
        } catch (URISyntaxException e) {
            logger.warn("Error encoding ASCII filename: " + e.toString());
            return filename;
        }
    }

    public static String toStringCleanBom(byte[] bytes) {
        // NOTE: this only supports UTF-8 for now!
        if (bytes == null || bytes.length == 0) return "";
        try {
            // UTF-8 BOM = 239, 187, 191
            if (bytes[0] == (byte) 239) {
                return new String(bytes, 3, bytes.length - 3, "UTF-8");
            } else {
                return new String(bytes, "UTF-8");
            }
        } catch (UnsupportedEncodingException e) {
            throw new BaseException("Error converting bytes to String", e);
        }
    }

    public static String escapeElasticQueryString(CharSequence queryString) {
        int length = queryString.length();
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++) {
            char c = queryString.charAt(i);
            if ("+-=&|><!(){}[]^\"~*?:\\/".indexOf(c) != -1) sb.append("\\");
            sb.append(c);
        }
        return sb.toString();
    }

    public static String paddedNumber(long number, Integer desiredLength) {
        StringBuilder outStrBfr = new StringBuilder(Long.toString(number));
        if (desiredLength == null) return outStrBfr.toString();
        while (desiredLength > outStrBfr.length()) outStrBfr.insert(0, "0");
        return outStrBfr.toString();
    }

    public static String paddedString(String input, Integer desiredLength, Character padChar, boolean rightPad) {
        if (!DefaultGroovyMethods.asBoolean(padChar)) padChar = ' ';
        if (input == null) input = "";
        StringBuilder outStrBfr = new StringBuilder(input);
        if (desiredLength == null) return outStrBfr.toString();
        while (desiredLength > outStrBfr.length()) if (rightPad) outStrBfr.append(padChar);
        else outStrBfr.insert(0, padChar);
        return outStrBfr.toString();
    }

    public static String paddedString(String input, Integer desiredLength, boolean rightPad) {
        return paddedString(input, desiredLength, ' ', rightPad);
    }

    public static String getRandomString(int length) {
        SecureRandom sr = new SecureRandom();
        byte[] randomBytes = new byte[length];
        sr.nextBytes(randomBytes);
        String randomStr = Base64.getUrlEncoder().encodeToString(randomBytes);
        if (randomStr.length() > length) randomStr = randomStr.substring(0, length);
        return randomStr;
    }

    private static final Map<String, Integer> calendarFieldByUomId;
    private static final Map<String, TemporalUnit> temporalUnitByUomId;
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

    public static void paginateList(String listName, String pageListName, Map<String, Object> context) {
        if (pageListName == null || pageListName.isEmpty()) pageListName = listName;
        List theList = (List) context.get(listName);
        if (theList == null) theList = new ArrayList();

        final Object pageIndexObj = context.get("pageIndex");
        int pageIndex = StupidJavaUtilities.isEmpty(pageIndexObj) ? 0 : Integer.parseInt(pageIndexObj.toString());
        final Object pageSizeObj = context.get("pageSize");
        int pageSize = StupidJavaUtilities.isEmpty(pageSizeObj) ? 20 : Integer.parseInt(pageSizeObj.toString());

        int count = theList.size();

        // calculate the pagination values
        int maxIndex = (new BigDecimal(count - 1)).divide(new BigDecimal(pageSize), 0, BigDecimal.ROUND_DOWN).intValue();
        int pageRangeLow = (pageIndex * pageSize) + 1;
        int pageRangeHigh = (pageIndex * pageSize) + pageSize;
        if (pageRangeHigh > count) pageRangeHigh = count;

        List pageList = theList.subList(pageRangeLow - 1, pageRangeHigh);
        context.put(pageListName, pageList);
        context.put(pageListName + "Count", count);
        context.put(pageListName + "PageIndex", pageIndex);
        context.put(pageListName + "PageSize", pageSize);
        context.put(pageListName + "PageMaxIndex", maxIndex);
        context.put(pageListName + "PageRangeLow", pageRangeLow);
        context.put(pageListName + "PageRangeHigh", pageRangeHigh);
    }

    private static final String[] SCALES = new String[]{"", "thousand", "million", "billion", "trillion", "quadrillion", "quintillion", "sextillion"};
    private static final String[] SUBTWENTY = new String[]{"", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"};
    private static final String[] DECADES = new String[]{"", "ten", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
    private static final String NEG_NAME = "negative";

    /** Convert any value from 0 to 999 inclusive, to a string. */
    private static String tripleAsWords(int value, boolean useAnd) {
        if (value < 0 || value >= 1000) throw new IllegalArgumentException("Illegal triple-value " + value);
        if (value < SUBTWENTY.length) return SUBTWENTY[value];

        int subhun = value % 100;
        int hun = value / 100;
        StringBuilder sb = new StringBuilder(50);
        if (hun > 0) sb.append(SUBTWENTY[hun]).append(" hundred");
        if (subhun > 0) {
            if (hun > 0) sb.append(useAnd ? " and " : " ");
            if (subhun < SUBTWENTY.length) {
                sb.append(" ").append(SUBTWENTY[subhun]);
            } else {
                int tens = subhun / 10;
                int units = subhun % 10;
                if (tens > 0) sb.append(DECADES[tens]);
                if (units > 0) sb.append(" ").append(SUBTWENTY[units]);
            }
        }
        return sb.toString();
    }

    /** Convert any long input value to a text representation
     * @param value  The value to convert
     * @param useAnd true if you want to use the word 'and' in the text (eleven thousand and thirteen)
     */
    public static String numberToWords(long value, boolean useAnd) {
        if (value == 0L) return SUBTWENTY[0];

        // break the value down in to sets of three digits (thousands)
        Integer[] thous = new Integer[SCALES.length];
        boolean neg = value < 0;
        // do not make negative numbers positive, to handle Long.MIN_VALUE
        int scale = 0;
        while (value != 0) {
            // use abs to convert thousand-groups to positive, if needed.
            thous[scale] = Math.abs((int) (value % 1000));
            value = value / 1000;
            scale++;
        }

        StringBuilder sb = new StringBuilder(scale * 40);
        if (neg) sb.append(NEG_NAME).append(" ");
        boolean first = true;
        while ((scale = --scale) > 0) {
            if (!first) sb.append(", ");
            first = false;
            if (thous[scale] > 0) sb.append(tripleAsWords(thous[scale], useAnd)).append(" ").append(SCALES[scale]);
        }

        if (!first && thous[0] != 0) {
            if (useAnd) sb.append(" and ");
            else sb.append(" ");
        }

        sb.append(tripleAsWords(thous[0], useAnd));

        sb.setCharAt(0, DefaultGroovyMethods.toUpperCase((DefaultGroovyMethods.asType(sb.charAt(0), Character.class))));
        return sb.toString();
    }
    public static String numberToWordsWithDecimal(BigDecimal value) {
        final String integerText = numberToWords(value.longValue(), false);
        String decimalText = value.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString();
        decimalText = decimalText.substring(decimalText.indexOf(".") + 1);
        return integerText + " and " + decimalText + "/100";
    }
}
