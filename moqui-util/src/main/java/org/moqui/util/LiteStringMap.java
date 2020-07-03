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

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.*;

/** Lightweight String/Object Map optimized for memory usage, slower than HashMap; this is most certainly not thread-safe */
public class LiteStringMap implements Map<String, Object>, Externalizable, Comparable<Map<String,?>>, Cloneable {
    private static final int DEFAULT_CAPACITY = 32;

    // NOTE: key design point is to use parallel arrays with simple values in each so that no Object need be created per entry (minimize GC overhead, etc)
    private String[] keyArray;
    private Object[] valueArray;
    private int arrayIndex = -1;
    private transient int mapHash = 0;

    public LiteStringMap() { init(DEFAULT_CAPACITY); }
    public LiteStringMap(int initialCapacity) { init(initialCapacity); }
    public LiteStringMap(Map<String, Object> cloneMap) {
        init(cloneMap.size());
        putAll(cloneMap);
    }
    public LiteStringMap(Map<String, Object> cloneMap, Set<String> skipKeys) {
        init(cloneMap.size());
        putAll(cloneMap, skipKeys);
    }

    private void init(int capacity) {
        keyArray = new String[capacity];
        valueArray = new Object[capacity];
    }
    private void growArrays() {
        int newLength = keyArray.length * 2;
        keyArray = Arrays.copyOf(keyArray, newLength);
        valueArray = Arrays.copyOf(valueArray, newLength);
    }

    public int findIndex(String keyOrig) {
        if (keyOrig == null) return -1;
        return findIndexIString(keyOrig.intern());

        /* safer but slower approach, needed? by String.intern() JavaDoc no, consistency guaranteed
        int keyLength = keyString.length();
        int keyHashCode = keyString.hashCode();
        // NOTE: can't use Arrays.binarySearch() as we want to maintain the insertion order and not use natural order for array elements
        for (int i = 0; i <= arrayIndex; i++) {
            // all strings in keyArray should be interned, only added via put()
            String curKey = keyArray[i];
            // first optimization is using interned String with identity compare, but don't always rely on this
            // next optimization comparing length() and hashCode() first to eliminate mismatches more quickly (by far the most common case)
            //     basic premise is that key Strings will be reused frequently and will already have a hashCode calculated
            if (curKey == keyString || (curKey.length() == keyLength && curKey.hashCode() == keyHashCode && keyString.equals(curKey))) return i;
        }
        return -1;
        */
    }
    /** For this method the String key must be non-null and interned (returned value from String.intern()) */
    public int findIndexIString(String key) {
        for (int i = 0; i <= arrayIndex; i++) {
            // all strings in keyArray should be interned, only added via put()
            if (keyArray[i] == key) return i;
        }
        return -1;
    }

    public String getKey(int index) { return keyArray[index]; }
    public Object getValue(int index) { return valueArray[index]; }

    @Override public int size() { return arrayIndex + 1; }
    @Override public boolean isEmpty() { return arrayIndex == -1; }
    @Override public boolean containsKey(Object key) {
        if (key == null) return false;
        return findIndex(key.toString()) != -1;
    }
    /** For this method the String key must be non-null and interned (returned value from String.intern()) */
    public boolean containsKeyIString(String key) {
        return findIndexIString(key) != -1;
    }

    @Override
    public boolean containsValue(Object value) {
        for (int i = 0; i <= arrayIndex; i++) {
            if (valueArray[i] == null) {
                if (value == null) return true;
            } else {
                if (valueArray[i].equals(value)) return true;
            }
        }
        return false;
    }

    @Override
    public Object get(Object key) {
        if (key == null) return null;
        int keyIndex = findIndex(key.toString());
        if (keyIndex == -1) return null;
        return valueArray[keyIndex];
    }
    public Object getByString(String key) {
        int keyIndex = findIndex(key);
        if (keyIndex == -1) return null;
        return valueArray[keyIndex];
    }
    /** For this method the String key must be non-null and interned (returned value from String.intern()) */
    public Object getByIString(String key) {
        int keyIndex = findIndexIString(key);
        if (keyIndex == -1) return null;
        return valueArray[keyIndex];
    }

    /* ========= Start Mutate Methods ========= */

    @Override
    public Object put(String keyOrig, Object value) {
        if (keyOrig == null) throw new IllegalArgumentException("LiteStringMap Key may not be null");
        return putByIString(keyOrig.intern(), value);
    }
    /** For this method the String key must be non-null and interned (returned value from String.intern()) */
    public Object putByIString(String key, Object value) {
        int keyIndex = findIndexIString(key);
        if (keyIndex == -1) {
            arrayIndex++;
            if (arrayIndex >= keyArray.length) growArrays();
            keyArray[arrayIndex] = key;
            valueArray[arrayIndex] = value;
            mapHash = 0;
            return null;
        } else {
            Object oldValue = valueArray[keyIndex];
            valueArray[keyIndex] = value;
            mapHash = 0;
            return oldValue;
        }
    }

    @Override
    public Object remove(Object key) {
        if (key == null) return null;
        int keyIndex = findIndexIString(key.toString().intern());
        if (keyIndex == -1) {
            return null;
        } else {
            Object oldValue = valueArray[keyIndex];
            // shift all later values up one position
            for (int i = keyIndex; i < arrayIndex; i++) {
                keyArray[i] = keyArray[i+1];
                valueArray[i] = valueArray[i+1];
            }
            // null the last values to avoid memory leak
            keyArray[arrayIndex] = null;
            valueArray[arrayIndex] = null;
            // decrement last index
            arrayIndex--;
            // reset hash
            mapHash = 0;
            // done
            return oldValue;
        }
    }

    @Override public void putAll(Map<? extends String, ?> map) { putAll(map, null); }

    public void putAll(Map<? extends String, ?> map, Set<String> skipKeys) {
        if (map == null) return;
        boolean initialEmpty = arrayIndex == -1;
        if (map instanceof LiteStringMap) {
            LiteStringMap lsm = (LiteStringMap) map;
            for (int i = 0; i <= lsm.arrayIndex; i++) {
                if (skipKeys != null && skipKeys.contains(lsm.keyArray[i])) continue;
                if (initialEmpty) {
                    putNoFind(lsm.keyArray[i], lsm.valueArray[i]);
                } else {
                    putByIString(lsm.keyArray[i], lsm.valueArray[i]);
                }
            }
        } else {
            for (Map.Entry<? extends String, ?> entry : map.entrySet()) {
                String key = entry.getKey();
                if (key == null) throw new IllegalArgumentException("LiteStringMap Key may not be null");
                if (skipKeys != null && skipKeys.contains(key)) continue;
                if (initialEmpty) {
                    putNoFind(key.intern(), entry.getValue());
                } else {
                    putByIString(key.intern(), entry.getValue());
                }
            }
        }
        mapHash = 0;
    }
    /** For this method the String key must be non-null and interned (returned value from String.intern()) */
    private void putNoFind(String key, Object value) {
        arrayIndex++;
        if (arrayIndex >= keyArray.length) growArrays();
        keyArray[arrayIndex] = key;
        valueArray[arrayIndex] = value;
        mapHash = 0;
    }

    @Override
    public void clear() {
        arrayIndex = -1;
        Arrays.fill(keyArray, null);
        Arrays.fill(valueArray, null);
        mapHash = 0;
    }

    /* ========= End Mutate Methods ========= */

    @Override public Set<String> keySet() { return new KeySetWrapper(this); }
    @Override public Collection<Object> values() { return new ValueCollectionWrapper(this); }
    @Override public Set<Entry<String, Object>> entrySet() { return new EntrySetWrapper(this); }

    @Override
    public int hashCode() {
        if (mapHash == 0) {
            // NOTE: this mimics the HashMap implementation from AbstractMap.java for the outer (add entry hash codes) and HashMap.java for the Map.Entry impl
            for (int i = 0; i <= arrayIndex; i++) {
                mapHash += (keyArray[i] == null ? 0 : keyArray[i].hashCode()) ^ (valueArray[i] == null ? 0 : valueArray[i].hashCode());
            }
        }
        return mapHash;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof LiteStringMap) {
            LiteStringMap lsm = (LiteStringMap) o;
            if (arrayIndex != lsm.arrayIndex) return false;
            for (int i = 0; i <= arrayIndex; i++) {
                // identity compare of interned String keys, if equal the value in the other LSM is conveniently at the same index
                if (keyArray[i] == lsm.keyArray[i]) {
                    if (!Objects.equals(valueArray[i], lsm.valueArray[i])) return false;
                } else {
                    Object value = lsm.getByIString(keyArray[i]);
                    if (!Objects.equals(valueArray[i], value)) return false;
                }
            }
            return true;
        } else if (o instanceof Map) {
            Map map = (Map) o;
            if ((arrayIndex + 1) != map.size()) return false;
            for (int i = 0; i <= arrayIndex; i++) {
                Object value = map.get(keyArray[i]);
                if (!Objects.equals(valueArray[i], value)) return false;
            }
            return true;
        } else {
            return false;
        }
    }

    @Override protected Object clone() { return new LiteStringMap(this); }
    public LiteStringMap cloneLite() { return new LiteStringMap(this); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i <= arrayIndex; i++) {
            if (i != 0) sb.append(", ");
            sb.append(keyArray[i]).append(":").append(valueArray[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        int size = arrayIndex + 1;
        out.writeInt(size);
        // after writing size write each key/value pair
        for (int i = 0; i < size; i++) {
            out.writeUTF(keyArray[i]);
            out.writeObject(valueArray[i]);
        }
    }
    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        int size = in.readInt();
        arrayIndex = size - 1;
        mapHash = 0;
        // now that we know the size read each key/value pair
        for (int i = 0; i < size; i++) {
            keyArray[i] = in.readUTF();
            valueArray[i] = in.readObject();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public int compareTo(Map<String, ?> that) {
        int result = 0;
        if (that instanceof LiteStringMap) {
            LiteStringMap lsm = (LiteStringMap) that;
            result = Integer.compare(arrayIndex, lsm.arrayIndex);
            if (result != 0) return result;

            for (int i = 0; i <= arrayIndex; i++) {
                Comparable thisVal = (Comparable) valueArray[i];
                // identity compare of interned String keys, if equal the value in the other LSM is conveniently at the same index
                Comparable thatVal = keyArray[i] == lsm.keyArray[i] ? (Comparable) lsm.valueArray[i] : (Comparable) lsm.getByIString(keyArray[i]);
                // NOTE: nulls go earlier in the list
                if (thisVal == null) {
                    result = thatVal == null ? 0 : 1;
                } else {
                    result = thatVal == null ? -1 : thisVal.compareTo(thatVal);
                }
                if (result != 0) return result;
            }
        } else {
            result = Integer.compare(arrayIndex + 1, that.size());
            if (result != 0) return result;

            for (int i = 0; i <= arrayIndex; i++) {
                Comparable thisVal = (Comparable) valueArray[i];
                Comparable thatVal = (Comparable) that.get(keyArray[i]);
                // NOTE: nulls go earlier in the list
                if (thisVal == null) {
                    result = thatVal == null ? 0 : 1;
                } else {
                    result = thatVal == null ? -1 : thisVal.compareTo(thatVal);
                }
                if (result != 0) return result;
            }
        }

        return result;
    }

    /* ========== Interface Wrapper Classes ========== */

    public static class KeyIterator implements Iterator<String> {
        private final LiteStringMap lsm;
        private int curIndex = -1;
        KeyIterator(LiteStringMap liteStringMap) { lsm = liteStringMap; }
        @Override public boolean hasNext() { return lsm.arrayIndex > curIndex; }
        @Override public String next() { curIndex++; return lsm.keyArray[curIndex]; }
    }
    public static class ValueIterator implements Iterator<Object> {
        private final LiteStringMap lsm;
        private int curIndex = -1;
        ValueIterator(LiteStringMap liteStringMap) { lsm = liteStringMap; }
        @Override public boolean hasNext() { return lsm.arrayIndex > curIndex; }
        @Override public Object next() { curIndex++; return lsm.valueArray[curIndex]; }
    }

    public static class KeySetWrapper implements Set<String> {
        private final LiteStringMap lsm;
        KeySetWrapper(LiteStringMap liteStringMap) { lsm = liteStringMap; }

        @Override public int size() { return lsm.size(); }
        @Override public boolean isEmpty() { return lsm.isEmpty(); }
        @Override public boolean contains(Object o) { return lsm.containsKey(o); }
        @Override public Iterator<String> iterator() { return new KeyIterator(lsm); }

        @Override public Object[] toArray() { return Arrays.copyOf(lsm.keyArray, lsm.arrayIndex + 1); }
        @Override public <String> String[] toArray(String[] ts) {
            int toCopy = ts.length > lsm.arrayIndex ? lsm.arrayIndex + 1 : ts.length;
            System.arraycopy(lsm.keyArray, 0, ts, 0, toCopy);
            return ts;
        }
        @Override
        public boolean containsAll(Collection<?> collection) {
            if (collection == null) return false;
            for (Object obj : collection)  if (obj == null || lsm.findIndex(obj.toString()) == -1) return false;
            return true;
        }

        @Override public boolean add(String s) { throw new UnsupportedOperationException("Key Set add not allowed"); }
        @Override public boolean remove(Object o) { throw new UnsupportedOperationException("Key Set remove not allowed"); }
        @Override public boolean addAll(Collection<? extends String> collection) { throw new UnsupportedOperationException("Key Set add all not allowed"); }
        @Override public boolean retainAll(Collection<?> collection) { throw new UnsupportedOperationException("Key Set retain all not allowed"); }
        @Override public boolean removeAll(Collection<?> collection) { throw new UnsupportedOperationException("Key Set remove all not allowed"); }
        @Override public void clear() { throw new UnsupportedOperationException("Key Set clear not allowed"); }
    }

    public static class ValueCollectionWrapper implements Collection<Object> {
        private final LiteStringMap lsm;
        ValueCollectionWrapper(LiteStringMap liteStringMap) { lsm = liteStringMap; }

        @Override public int size() { return lsm.size(); }
        @Override public boolean isEmpty() { return lsm.isEmpty(); }
        @Override public boolean contains(Object o) { return lsm.containsValue(o); }
        @Override public boolean containsAll(Collection<?> collection) {
            if (collection == null || collection.isEmpty()) return true;
            for (Object obj : collection) {
                if (!lsm.containsValue(obj)) return false;
            }
            return true;
        }

        @Override public Iterator<Object> iterator() { return new ValueIterator(lsm); }

        @Override public Object[] toArray() { return Arrays.copyOf(lsm.valueArray, lsm.arrayIndex + 1); }
        @Override public <Object> Object[] toArray(Object[] ts) {
            int toCopy = ts.length > lsm.arrayIndex ? lsm.arrayIndex + 1 : ts.length;
            System.arraycopy(lsm.valueArray, 0, ts, 0, toCopy);
            return ts;
        }

        @Override public boolean add(Object s) { throw new UnsupportedOperationException("Value Collection add not allowed"); }
        @Override public boolean remove(Object o) { throw new UnsupportedOperationException("Value Collection remove not allowed"); }
        @Override public boolean addAll(Collection<?> collection) { throw new UnsupportedOperationException("Value Collection add all not allowed"); }
        @Override public boolean retainAll(Collection<?> collection) { throw new UnsupportedOperationException("Value Collection retain all not allowed"); }
        @Override public boolean removeAll(Collection<?> collection) { throw new UnsupportedOperationException("Value Collection remove all not allowed"); }
        @Override public void clear() { throw new UnsupportedOperationException("Value Collection clear not allowed"); }
    }

    public static class EntryWrapper implements Entry<String, Object> {
        private final LiteStringMap lsm;
        private final String key;
        private int curIndex;

        EntryWrapper(LiteStringMap liteStringMap, int index) {
            lsm = liteStringMap;
            curIndex = index;
            key = lsm.keyArray[index];
        }

        @Override public String getKey() { return key; }
        @Override public Object getValue() {
            String keyCheck = lsm.keyArray[curIndex];
            if (!Objects.equals(key, keyCheck)) curIndex = lsm.findIndex(key);
            if (curIndex == -1) return null;
            return lsm.valueArray[curIndex];
        }
        @Override public Object setValue(Object value) {
            String keyCheck = lsm.keyArray[curIndex];
            if (!Objects.equals(key, keyCheck)) curIndex = lsm.findIndex(key);
            if (curIndex == -1) return lsm.put(key, value);
            Object oldValue = lsm.valueArray[curIndex];
            lsm.valueArray[curIndex] = value;
            return oldValue;
        }
    }
    public static class EntrySetWrapper implements Set<Entry<String, Object>> {
        private final LiteStringMap lsm;
        EntrySetWrapper(LiteStringMap liteStringMap) { lsm = liteStringMap; }

        @Override public int size() { return lsm.size(); }
        @Override public boolean isEmpty() { return lsm.isEmpty(); }
        @Override public boolean contains(Object obj) {
            if (obj instanceof Entry) {
                Entry entry = (Entry) obj;
                Object keyObj = entry.getKey();
                if (keyObj == null) return false;
                int idx = lsm.findIndex(keyObj.toString());
                if (idx == -1) return false;
                Object entryValue = entry.getValue();
                Object keyValue = lsm.valueArray[idx];
                return Objects.equals(entryValue, keyValue);
            } else {
                return false;
            }
        }
        @Override public Iterator<Entry<String, Object>> iterator() {
            ArrayList<Entry<String, Object>> entryList = new ArrayList<>(lsm.arrayIndex + 1);
            for (int i = 0; i <= lsm.arrayIndex; i++) { entryList.add(new EntryWrapper(lsm, i)); }
            return entryList.iterator();
        }

        @Override public Object[] toArray() { throw new UnsupportedOperationException("Entry Set to array not supported"); }
        @Override public <T> T[] toArray(T[] ts) { throw new UnsupportedOperationException("Entry Set copy to array not supported"); }

        @Override
        public boolean containsAll(Collection<?> collection) {
            if (collection == null) return false;
            for (Object obj : collection) if (obj == null || lsm.findIndex(obj.toString()) == -1) return false;
            return true;
        }

        @Override public boolean add(Entry<String, Object> entry) { throw new UnsupportedOperationException("Entry Set add not allowed"); }
        @Override public boolean remove(Object o) { throw new UnsupportedOperationException("Entry Set remove not allowed"); }
        @Override public boolean addAll(Collection<? extends Entry<String, Object>> collection) { throw new UnsupportedOperationException("Entry Set add all not allowed"); }
        @Override public boolean retainAll(Collection<?> collection) { throw new UnsupportedOperationException("Entry Set retain all not allowed"); }
        @Override public boolean removeAll(Collection<?> collection) { throw new UnsupportedOperationException("Entry Set remove all not allowed"); }
        @Override public void clear() { throw new UnsupportedOperationException("Entry Set clear not allowed"); }
    }
}
