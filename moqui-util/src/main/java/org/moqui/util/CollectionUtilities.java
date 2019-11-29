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

import groovy.util.Node;
import groovy.util.NodeList;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.*;

/**
 * These are utilities that should exist elsewhere, but I can't find a good simple library for them, and they are
 * stupid but necessary for certain things.
 */
@SuppressWarnings("unused")
public class CollectionUtilities {
    protected static final Logger logger = LoggerFactory.getLogger(CollectionUtilities.class);

    public static class KeyValue {
        public String key;
        public Object value;
        public KeyValue(String key, Object value) { this.key = key; this.value = value; }
    }

    public static void filterMapList(List<Map> theList, Map<String, Object> fieldValues) {
        filterMapList(theList, fieldValues, false);
    }
    /** Filter theList (of Map) using fieldValues; if exclude=true remove matching items, else keep only matching items */
    public static void filterMapList(List<Map> theList, Map<String, Object> fieldValues, boolean exclude) {
        if (theList == null || fieldValues == null) return;
        int listSize = theList.size();
        if (listSize == 0) return;
        int numFields = fieldValues.size();
        if (numFields == 0) return;

        String[] fieldNameArray = new String[numFields];
        Object[] fieldValueArray = new Object[numFields];
        int index = 0;
        for (Map.Entry<String, Object> entry : fieldValues.entrySet()) {
            fieldNameArray[index] = entry.getKey();
            fieldValueArray[index] = entry.getValue();
            index++;
        }

        if (theList instanceof RandomAccess) {
            for (int li = 0; li < listSize; ) {
                Map curMap = theList.get(li);
                if (checkRemove(curMap, fieldNameArray, fieldValueArray, numFields, exclude)) {
                    theList.remove(li);
                    listSize--;
                } else { li++; }
            }
        } else {
            Iterator<Map> theIterator = theList.iterator();
            while (theIterator.hasNext()) {
                Map curMap = theIterator.next();
                if (checkRemove(curMap, fieldNameArray, fieldValueArray, numFields, exclude)) theIterator.remove();
            }
        }
    }
    private static boolean checkRemove(Map curMap, String[] fieldNameArray, Object[] fieldValueArray, int numFields, boolean exclude) {
        boolean remove = exclude;
        for (int i = 0; i < numFields; i++) {
            String fieldName = fieldNameArray[i];
            Object compareObj = fieldValueArray[i];
            Object curObj = curMap.get(fieldName);
            if (compareObj == null) { if (curObj != null) { remove = !exclude; break; } }
            else { if (!compareObj.equals(curObj)) { remove = !exclude; break; } }
        }
        return remove;
    }

    public static List<Map> filterMapListByDate(List<Map> theList, String fromDateName, String thruDateName, Timestamp compareStamp) {
        if (theList == null || theList.size() == 0) return theList;

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
        return theList;
    }

    public static void filterMapListByDate(List<Map> theList, String fromDateName, String thruDateName, Timestamp compareStamp, boolean ignoreIfEmpty) {
        if (ignoreIfEmpty && compareStamp == null) return;
        filterMapListByDate(theList, fromDateName, thruDateName, compareStamp);
    }

    /** Order list elements in place (modifies the list passed in), returns the list for convenience */
    public static List<Map<String, Object>> orderMapList(List<Map<String, Object>> theList, List<? extends CharSequence> fieldNames) {
        return orderMapList(theList, fieldNames, null);
    }
    public static List<Map<String, Object>> orderMapList(List<Map<String, Object>> theList, List<? extends CharSequence> fieldNames, Boolean nullsLast) {
        if (fieldNames == null) throw new IllegalArgumentException("Cannot order List of Maps with null order by field list");
        if (theList != null && fieldNames.size() > 0) theList.sort(new MapOrderByComparator(fieldNames).nullsLast(nullsLast));
        return theList;
    }

    public static class MapOrderByComparator implements Comparator<Map> {
        String[] fieldNameArray;
        Boolean nullsLast = null;

        public MapOrderByComparator(List<? extends CharSequence> fieldNameList) {
            ArrayList<String> fieldArrayList = new ArrayList<>();
            for (CharSequence fieldName : fieldNameList) {
                String fieldStr = fieldName.toString();
                if (fieldStr.contains(",")) {
                    String[] curFieldArray = fieldStr.split(",");
                    for (int i = 0; i < curFieldArray.length; i++) {
                        String curField = curFieldArray[i];
                        if (curField == null) continue;
                        fieldArrayList.add(curField.trim());
                    }
                } else {
                    fieldArrayList.add(fieldStr);
                }
            }
            fieldNameArray = fieldArrayList.toArray(new String[0]);
            // logger.warn("Order list by " + Arrays.asList(fieldNameArray));
        }

        public MapOrderByComparator nullsLast(Boolean nl) {
            nullsLast = nl;
            return this;
        }

        @SuppressWarnings("unchecked")
        @Override public int compare(Map map1, Map map2) {
            if (map1 == null) return -1;
            if (map2 == null) return 1;
            for (int i = 0; i < fieldNameArray.length; i++) {
                String fieldName = fieldNameArray[i];
                boolean ascending = true;
                boolean ignoreCase = false;
                if (fieldName.charAt(0) == '-') {
                    ascending = false;
                    fieldName = fieldName.substring(1);
                } else if (fieldName.charAt(0) == '+') {
                    fieldName = fieldName.substring(1);
                }
                if (fieldName.charAt(0) == '^') {
                    ignoreCase = true;
                    fieldName = fieldName.substring(1);
                }

                boolean nullsFirst = nullsLast != null ? !nullsLast.booleanValue() : ascending;

                Comparable value1 = (Comparable) map1.get(fieldName);
                Comparable value2 = (Comparable) map2.get(fieldName);
                // NOTE: nulls go earlier in the list for ascending, later in the list for !ascending
                if (value1 == null) {
                    if (value2 != null) return nullsFirst ? -1 : 1;
                } else {
                    if (value2 == null) {
                        return nullsFirst ? 1 : -1;
                    } else {
                        if (ignoreCase && value1 instanceof String && value2 instanceof String) {
                            int comp = ((String) value1).compareToIgnoreCase((String) value2);
                            if (comp != 0) return ascending ? comp : -comp;
                        } else {
                            if (value1.getClass() != value2.getClass()) {
                                if (value1 instanceof Number && value2 instanceof Number) {
                                    value1 = new BigDecimal(value1.toString());
                                    value2 = new BigDecimal(value2.toString());
                                }
                                // NOTE: any other type normalization to avoid compareTo() casting exceptions?
                            }
                            int comp = value1.compareTo(value2);
                            if (comp != 0) return ascending ? comp : -comp;
                        }
                    }
                }
            }
            // all evaluated to 0, so is the same, so return 0
            return 0;
        }

        @Override public boolean equals(Object obj) {
            return obj instanceof MapOrderByComparator && Arrays.equals(fieldNameArray, ((MapOrderByComparator) obj).fieldNameArray);
        }

        @Override public String toString() { return Arrays.toString(fieldNameArray); }
    }

    /**
     * For a list of Map find the entry that best matches the fieldsByPriority Ordered Map; null field values in a Map
     * in mapList match against any value but do not contribute to maximal match score, otherwise value for each field
     * in fieldsByPriority must match for it to be a candidate.
     */
    public static Map<String, Object> findMaximalMatch(List<Map<String, Object>> mapList, LinkedHashMap<String, Object> fieldsByPriority) {
        int numFields = fieldsByPriority.size();
        String[] fieldNames = new String[numFields];
        Object[] fieldValues = new Object[numFields];
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
        Object curObj = theMap.get(key);
        if (curObj == null) {
            theMap.put(key, value);
        } else {
            BigDecimal curVal;
            if (curObj instanceof BigDecimal) curVal = (BigDecimal) curObj;
            else curVal = new BigDecimal(curObj.toString());
            theMap.put(key, curVal.add(value));
        }
    }

    public static void addBigDecimalsInMap(Map<String, Object> baseMap, Map<String, Object> addMap) {
        if (baseMap == null || addMap == null) return;
        for (Map.Entry<String, Object> entry : addMap.entrySet()) {
            if (!(entry.getValue() instanceof BigDecimal)) continue;
            BigDecimal addVal = (BigDecimal) entry.getValue();
            Object baseObj = baseMap.get(entry.getKey());
            if (baseObj == null || !(baseObj instanceof BigDecimal)) baseObj = BigDecimal.ZERO;
            BigDecimal baseVal = (BigDecimal) baseObj;
            baseMap.put(entry.getKey(), baseVal.add(addVal));
        }
    }
    public static void divideBigDecimalsInMap(Map<String, Object> baseMap, BigDecimal divisor) {
        if (baseMap == null || divisor == null || divisor.doubleValue() == 0.0) return;
        for (Map.Entry<String, Object> entry : baseMap.entrySet()) {
            if (!(entry.getValue() instanceof BigDecimal)) continue;
            BigDecimal baseVal = (BigDecimal) entry.getValue();
            entry.setValue(baseVal.divide(divisor, RoundingMode.HALF_UP));
        }
    }

    /** Returns Map with total, squaredTotal, count, average, stdDev, maximum; fieldName field in Maps must have type BigDecimal;
     * if count of non-null fields is less than 2 returns null as cannot calculate a standard deviation */
    public static Map<String, BigDecimal> stdDevMaxFromMapField(List<Map<String, Object>> dataList, String fieldName, BigDecimal stdDevMultiplier) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal squaredTotal = BigDecimal.ZERO;
        int count = 0;
        for (Map<String, Object> dataMap : dataList) {
            if (dataMap == null) continue;
            BigDecimal value = (BigDecimal) dataMap.get(fieldName);
            if (value == null) continue;
            total = total.add(value);
            squaredTotal = squaredTotal.add(value.multiply(value));
            count++;
        }
        if (count < 2) return null;

        BigDecimal countBd = new BigDecimal(count);
        BigDecimal average = total.divide(countBd, RoundingMode.HALF_UP);
        double totalDouble = total.doubleValue();
        BigDecimal stdDev = new BigDecimal(Math.sqrt(Math.abs(squaredTotal.doubleValue() - ((totalDouble*totalDouble) / count)) / (count - 1)));

        Map<String, BigDecimal> retMap = new HashMap<>(6);
        retMap.put("total", total); retMap.put("squaredTotal", squaredTotal); retMap.put("count", countBd);
        retMap.put("average", average); retMap.put("stdDev", stdDev);

        if (stdDevMultiplier != null) retMap.put("maximum", average.add(stdDev.multiply(stdDevMultiplier)));

        return retMap;
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

    @SuppressWarnings("unchecked")
    public static void mergeNestedMap(Map<Object, Object> baseMap, Map<Object, Object> overrideMap, boolean overrideEmpty) {
        if (baseMap == null || overrideMap == null) return;
        for (Map.Entry<Object, Object> entry : overrideMap.entrySet()) {
            Object key = entry.getKey();
            Object value = entry.getValue();
            if (baseMap.containsKey(key)) {
                if (value == null) {
                    if (overrideEmpty) baseMap.put(key, null);
                } else {
                    if (value instanceof CharSequence) {
                        if (overrideEmpty || ((CharSequence) value).length() > 0) baseMap.put(key, value);
                    } else if (value instanceof Map) {
                        Object baseValue = baseMap.get(key);
                        if (baseValue != null && baseValue instanceof Map) {
                            mergeNestedMap((Map) baseValue, (Map) value, overrideEmpty);
                        } else {
                            baseMap.put(key, value);
                        }
                    } else if (value instanceof Collection) {
                        Object baseValue = baseMap.get(key);
                        if (baseValue != null && baseValue instanceof Collection) {
                            Collection baseCol = (Collection) baseValue;
                            Collection overrideCol = (Collection) value;
                            for (Object overrideObj : overrideCol) {
                                // NOTE: if we have a Collection of Map we have no way to merge the Maps without knowing the 'key' entries to use to match them
                                if (!baseCol.contains(overrideObj)) baseCol.add(overrideObj);
                            }
                        } else {
                            baseMap.put(key, value);
                        }
                    } else {
                        // NOTE: no way to check empty, if not null not empty so put it
                        baseMap.put(key, value);
                    }
                }
            } else {
                baseMap.put(key, value);
            }
        }
    }

    public final static Collection<Object> singleNullCollection;
    static {
        singleNullCollection = new ArrayList<>();
        singleNullCollection.add(null);
    }
    /** Removes entries with a null value from the Map, returns the passed in Map for convenience (does not clone before removes!). */
    @SuppressWarnings("unchecked")
    public static Map removeNullsFromMap(Map theMap) {
        if (theMap == null) return null;
        theMap.values().removeAll(singleNullCollection);
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

    public static void paginateList(String listName, String pageListName, Map<String, Object> context) {
        if (pageListName == null || pageListName.isEmpty()) pageListName = listName;
        List theList = (List) context.get(listName);
        if (theList == null) theList = new ArrayList();

        final Object pageIndexObj = context.get("pageIndex");
        int pageIndex = ObjectUtilities.isEmpty(pageIndexObj) ? 0 : Integer.parseInt(pageIndexObj.toString());
        final Object pageSizeObj = context.get("pageSize");
        int pageSize = ObjectUtilities.isEmpty(pageSizeObj) ? 20 : Integer.parseInt(pageSizeObj.toString());

        int count = theList.size();

        // calculate the pagination values
        int maxIndex = (new BigDecimal(count - 1)).divide(new BigDecimal(pageSize), 0, RoundingMode.DOWN).intValue();
        int pageRangeLow = (pageIndex * pageSize) + 1;
        if (pageRangeLow > count) pageRangeLow = count + 1;
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
}
