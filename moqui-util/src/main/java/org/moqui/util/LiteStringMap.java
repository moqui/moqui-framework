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

/** Light weight String Keyed Map optimized for memory usage and garbage collection overhead.
 * Uses parallel key and value arrays internally and does not create an object for each Map.Entry unless entrySet() is used.
 * This is generally slower than HashMap unless key String objects are already interned.
 * With '*IString' variations of methods a call with a known already interned String can operate as fast, and for smaller Maps faster, than HashMap (such as in the EntityFacade where field names come from an interned String in a FieldInfo object).
 * This is most certainly not thread-safe.
 */
public class LiteStringMap<V> implements Map<String, V>, Externalizable, Comparable<Map<String,? extends V>>, Cloneable {
    // NOTE: for over the wire compatibility do not change this unless writeExternal() and readExternal() are changed OR the non-transient fields change from only keyArray, valueArray, and lastIndex
    private static final long serialVersionUID = 688763341199951234L;
    private static final int DEFAULT_CAPACITY = 8;

    // NOTE: from basic profiling HashMap.get() runs in just over half the time (0.13 microseconds) of String.intern() (0.24 microseconds) over ~500k runs with OpenJDK 8
    private static HashMap<String, String> internedMap = new HashMap<>();
    public static String internString(String orig) {
        String interned = internedMap.get(orig);
        if (interned != null) return interned;
        // don't even check for null until we have to
        if (orig == null) return null;
        interned = orig.intern();
        internedMap.put(interned, interned);
        return interned;
    }

    // NOTE: key design point is to use parallel arrays with simple values in each so that no Object need be created per entry (minimize GC overhead, etc)
    private String[] keyArray;
    private V[] valueArray;
    private int lastIndex = -1;
    private transient int mapHash = 0;
    private transient boolean useManualIndex = false;

    public LiteStringMap() { init(DEFAULT_CAPACITY); }
    public LiteStringMap(int initialCapacity) { init(initialCapacity); }
    public LiteStringMap(Map<String, V> cloneMap) {
        init(cloneMap.size());
        if (cloneMap instanceof LiteStringMap && ((LiteStringMap<V>) cloneMap).useManualIndex) useManualIndex = true;
        putAll(cloneMap);
    }
    public LiteStringMap(Map<String, V> cloneMap, Set<String> skipKeys) {
        init(cloneMap.size());
        if (cloneMap instanceof LiteStringMap && ((LiteStringMap<V>) cloneMap).useManualIndex) useManualIndex = true;
        putAll(cloneMap, skipKeys);
    }

    @SuppressWarnings("unchecked")
    private void init(int capacity) {
        keyArray = new String[capacity];
        valueArray = (V[]) new Object[capacity];
    }
    private void growArrays(Integer minLength) {
        int newLength = keyArray.length * 2;
        if (minLength != null && newLength < minLength) newLength = minLength;
        // System.out.println("=============================\n============= grow to " + newLength);
        keyArray = Arrays.copyOf(keyArray, newLength);
        valueArray = Arrays.copyOf(valueArray, newLength);
    }


    public LiteStringMap<V> ensureCapacity(int capacity) {
        if (keyArray.length < capacity) {
            keyArray = Arrays.copyOf(keyArray, capacity);
            valueArray = Arrays.copyOf(valueArray, capacity);
        }
        return this;
    }
    public LiteStringMap<V> useManualIndex() { useManualIndex = true; return this; }

    public int findIndex(String keyOrig) {
        if (keyOrig == null) return -1;
        return findIndexIString(internString(keyOrig));

        /* safer but slower approach, needed? by String.intern() JavaDoc no, consistency guaranteed
        int keyLength = keyString.length();
        int keyHashCode = keyString.hashCode();
        // NOTE: can't use Arrays.binarySearch() as we want to maintain the insertion order and not use natural order for array elements
        for (int i = 0; i <= lastIndex; i++) {
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
        for (int i = 0; i <= lastIndex; i++) {
            // all strings in keyArray should be interned, only added via put()
            if (keyArray[i] == key) return i;
        }
        return -1;
    }

    public String getKey(int index) { return keyArray[index]; }

    public V getValue(int index) { return (V) valueArray[index]; }

    @Override public int size() { return lastIndex + 1; }
    @Override public boolean isEmpty() { return lastIndex == -1; }
    @Override public boolean containsKey(Object key) {
        if (key == null) return false;
        return findIndex(key.toString()) != -1;
    }
    /** For this method the String key must be non-null and interned (returned value from String.intern()) */
    public boolean containsKeyIString(String key) {
        return findIndexIString(key) != -1;
    }
    /** For this method the String key must be non-null and interned (returned value from String.intern()) */
    public boolean containsKeyIString(String key, int index) {
        String idxKey = keyArray[index];
        if (idxKey == null) return false;
        if (idxKey != key) throw new IllegalArgumentException("Index " + index + " has key " + keyArray[index] + ", cannot check contains with key " + key);
        return true;
    }

    @Override
    public boolean containsValue(Object value) {
        for (int i = 0; i <= lastIndex; i++) {
            if (valueArray[i] == null) {
                if (value == null) return true;
            } else {
                if (valueArray[i].equals(value)) return true;
            }
        }
        return false;
    }

    @Override
    public V get(Object key) {
        if (key == null) return null;
        int keyIndex = findIndex(key.toString());
        if (keyIndex == -1) return null;
        return valueArray[keyIndex];
    }
    public V getByString(String key) {
        int keyIndex = findIndex(key);
        if (keyIndex == -1) return null;
        return valueArray[keyIndex];
    }
    /** For this method the String key must be non-null and interned (returned value from String.intern()) */
    public V getByIString(String key) {
        int keyIndex = findIndexIString(key);
        if (keyIndex == -1) return null;
        return valueArray[keyIndex];
    }
    /** For this method the String key must be non-null and interned (returned value from String.intern()) */
    public V getByIString(String key, int index) {
        if (index >= keyArray.length) throw new ArrayIndexOutOfBoundsException("Index " + index + " invalid, internal array length " + keyArray.length + "; for key: " + key);
        String idxKey = keyArray[index];
        if (idxKey == null) return null;
        if (idxKey != key) throw new IllegalArgumentException("Index " + index + " has key " + keyArray[index] + ", cannot get with key " + key);
        return valueArray[index];
    }

    /* ========= Start Mutate Methods ========= */

    @Override
    public V put(String keyOrig, V value) {
        if (keyOrig == null) throw new IllegalArgumentException("LiteStringMap Key may not be null");
        return putByIString(internString(keyOrig), value);
    }
    /** For this method the String key must be non-null and interned (returned value from String.intern()) */
    public V putByIString(String key, V value) {
        // if ("pseudoId".equals(key)) { System.out.println("========= put no index " + key + ": " + value); new Exception("location").printStackTrace(); }
        int keyIndex = findIndexIString(key);
        if (keyIndex == -1) {
            lastIndex++;
            if (lastIndex >= keyArray.length) growArrays(null);
            keyArray[lastIndex] = key;
            valueArray[lastIndex] = value;
            mapHash = 0;
            return null;
        } else {
            V oldValue = valueArray[keyIndex];
            valueArray[keyIndex] = value;
            mapHash = 0;
            return oldValue;
        }
    }
    /** For this method the String key must be non-null and interned (returned value from String.intern()) */
    public V putByIString(String key, V value, int index) {
        // if ("pseudoId".equals(key)) { System.out.println("========= put index " + index + " key " + key + ": " + value); new Exception("location").printStackTrace(); }
        useManualIndex = true;
        if (index >= keyArray.length) growArrays(index + 1);
        if (index > lastIndex) lastIndex = index;
        if (keyArray[index] == null) {
            keyArray[index] = key;
            valueArray[index] = value;
            mapHash = 0;
            return null;
        } else {
            // identity compare for interned String
            if (key != keyArray[index]) throw new IllegalArgumentException("Index " + index + " already has key " + keyArray[index] + ", cannot use with key " + key);
            V oldValue = valueArray[index];
            valueArray[index] = value;
            mapHash = 0;
            return oldValue;
        }
    }

    @Override
    public V remove(Object key) {
        if (key == null) return null;
        int keyIndex = findIndexIString(internString(key.toString()));
        return removeByIndex(keyIndex);
    }

    private V removeByIndex(int keyIndex) {
        if (keyIndex == -1) {
            return null;
        } else {
            V oldValue = valueArray[keyIndex];
            if (useManualIndex) {
                // with manual indexes don't shift entries, will cause manually specified indexes to be wrong
                keyArray[keyIndex] = null;
                valueArray[keyIndex] = null;
            } else {
                // shift all later values up one position
                for (int i = keyIndex; i < lastIndex; i++) {
                    keyArray[i] = keyArray[i+1];
                    valueArray[i] = valueArray[i+1];
                }
                // null the last values to avoid memory leak
                keyArray[lastIndex] = null;
                valueArray[lastIndex] = null;
                // decrement last index
                lastIndex--;
            }
            // reset hash
            mapHash = 0;
            return oldValue;
        }
    }

    public boolean removeAllKeys(Collection<?> collection) {
        if (collection == null) return false;
        boolean removedAny = false;
        for (Object obj : collection) {
            // keys in LiteStringMap cannot be null
            if (obj == null) continue;
            int idx = findIndex(obj.toString());
            if (idx != -1) {
                removeByIndex(idx);
                removedAny = true;
            }
        }
        return removedAny;
    }
    public boolean removeValue(Object value) {
        boolean removedAny = false;
        for (int i = 0; i < valueArray.length; i++) {
            Object curVal = valueArray[i];
            if (value == null) {
                if (curVal == null) {
                    removeByIndex(i);
                    removedAny = true;
                }
            } else if (value.equals(curVal)) {
                removeByIndex(i);
                removedAny = true;
            }
        }
        return removedAny;
    }
    public boolean removeAllValues(Collection<?> collection) {
        if (collection == null) return false;
        boolean removedAny = false;
        // NOTE: could iterate over valueArray outer and collection inner but value array has no Iterator overhead so do that inner (and nice to reuse removeValue())
        for (Object obj : collection) {
            if (removeValue(obj)) removedAny = true;
        }
        return removedAny;
    }

    @Override public void putAll(Map<? extends String, ? extends V> map) { putAll(map, null); }

    @SuppressWarnings("unchecked")
    public void putAll(Map<? extends String, ? extends V> map, Set<String> skipKeys) {
        if (map == null) return;
        boolean initialEmpty = lastIndex == -1;
        if (map instanceof LiteStringMap) {
            LiteStringMap<V> lsm = (LiteStringMap<V>) map;
            if (useManualIndex) {
                this.lastIndex = lsm.lastIndex;
                if (keyArray.length <= lsm.lastIndex) growArrays(lsm.lastIndex);
            }
            for (int i = 0; i <= lsm.lastIndex; i++) {
                if (skipKeys != null && skipKeys.contains(lsm.keyArray[i])) continue;
                if (useManualIndex) {
                    keyArray[i] = lsm.keyArray[i];
                    valueArray[i] = lsm.valueArray[i];
                } else if (initialEmpty) {
                    putNoFind(lsm.keyArray[i], lsm.valueArray[i]);
                } else {
                    putByIString(lsm.keyArray[i], lsm.valueArray[i]);
                }
            }
        } else {
            for (Map.Entry<? extends String, ? extends V> entry : map.entrySet()) {
                String key = entry.getKey();
                if (key == null) throw new IllegalArgumentException("LiteStringMap Key may not be null");
                if (skipKeys != null && skipKeys.contains(key)) continue;
                if (initialEmpty) {
                    putNoFind(internString(key), entry.getValue());
                } else {
                    putByIString(internString(key), entry.getValue());
                }
            }
        }
        mapHash = 0;
    }
    /** For this method the String key must be non-null and interned (returned value from String.intern()) */
    private void putNoFind(String key, V value) {
        lastIndex++;
        if (lastIndex >= keyArray.length) growArrays(null);
        keyArray[lastIndex] = key;
        valueArray[lastIndex] = value;
        mapHash = 0;
    }

    @Override
    public void clear() {
        lastIndex = -1;
        Arrays.fill(keyArray, null);
        Arrays.fill(valueArray, null);
        mapHash = 0;
    }

    /* ========= End Mutate Methods ========= */

    @Override public Set<String> keySet() { return new KeySetWrapper(this); }
    @Override public Collection<V> values() { return new ValueCollectionWrapper<>(this); }
    @Override public Set<Entry<String, V>> entrySet() { return new EntrySetWrapper<>(this); }

    @Override
    public int hashCode() {
        if (mapHash == 0) {
            // NOTE: this mimics the HashMap implementation from AbstractMap.java for the outer (add entry hash codes) and HashMap.java for the Map.Entry impl
            for (int i = 0; i <= lastIndex; i++) {
                mapHash += (keyArray[i] == null ? 0 : keyArray[i].hashCode()) ^ (valueArray[i] == null ? 0 : valueArray[i].hashCode());
            }
        }
        return mapHash;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof LiteStringMap) {
            LiteStringMap lsm = (LiteStringMap) o;
            if (lastIndex != lsm.lastIndex) return false;
            for (int i = 0; i <= lastIndex; i++) {
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
            if ((lastIndex + 1) != map.size()) return false;
            for (int i = 0; i <= lastIndex; i++) {
                Object value = map.get(keyArray[i]);
                if (!Objects.equals(valueArray[i], value)) return false;
            }
            return true;
        } else {
            return false;
        }
    }

    @Override protected Object clone() { return new LiteStringMap<V>(this); }
    public LiteStringMap<V> cloneLite() { return new LiteStringMap<V>(this); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i <= lastIndex; i++) {
            if (i != 0) sb.append(", ");
            sb.append(keyArray[i]).append(":").append(valueArray[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        int size = lastIndex + 1;
        out.writeInt(size);
        // after writing size write each key/value pair
        for (int i = 0; i < size; i++) {
            out.writeObject(keyArray[i]);
            out.writeObject(valueArray[i]);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        int size = in.readInt();
        if (keyArray.length < size) {
            keyArray = new String[size];
            valueArray = (V[]) new Object[size];
        }
        lastIndex = size - 1;
        mapHash = 0;
        // now that we know the size read each key/value pair
        for (int i = 0; i < size; i++) {
            // intern Strings, from deserialize they will not be interned
            String key = (String) in.readObject();
            keyArray[i] = key != null ? internString(key) : null;
            valueArray[i] = (V) in.readObject();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public int compareTo(Map<String, ? extends V> that) {
        int result = 0;
        if (that instanceof LiteStringMap) {
            LiteStringMap lsm = (LiteStringMap) that;
            result = Integer.compare(lastIndex, lsm.lastIndex);
            if (result != 0) return result;

            for (int i = 0; i <= lastIndex; i++) {
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
            result = Integer.compare(lastIndex + 1, that.size());
            if (result != 0) return result;

            for (int i = 0; i <= lastIndex; i++) {
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
        @Override public boolean hasNext() { return lsm.lastIndex > curIndex; }
        @Override public String next() { curIndex++; return lsm.keyArray[curIndex]; }
    }
    public static class ValueIterator<V> implements Iterator<V> {
        private final LiteStringMap<V> lsm;
        private int curIndex = -1;
        ValueIterator(LiteStringMap<V> liteStringMap) { lsm = liteStringMap; }
        @Override public boolean hasNext() { return lsm.lastIndex > curIndex; }
        @Override public V next() { curIndex++; return lsm.valueArray[curIndex]; }
    }

    public static class KeySetWrapper implements Set<String> {
        private final LiteStringMap lsm;
        KeySetWrapper(LiteStringMap liteStringMap) { lsm = liteStringMap; }

        @Override public int size() { return lsm.size(); }
        @Override public boolean isEmpty() { return lsm.isEmpty(); }
        @Override public boolean contains(Object o) { return lsm.containsKey(o); }
        @Override public Iterator<String> iterator() { return new KeyIterator(lsm); }

        @Override public Object[] toArray() { return Arrays.copyOf(lsm.keyArray, lsm.lastIndex + 1); }
        @Override public <T> T[] toArray(T[] ts) {
            int toCopy = ts.length > lsm.lastIndex ? lsm.lastIndex + 1 : ts.length;
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
        @Override public boolean remove(Object o) {
            if (o == null) return false;
            int idx = lsm.findIndex(o.toString());
            if (idx == -1) {
                return false;
            } else {
                lsm.removeByIndex(idx);
                return true;
            }
        }
        @Override public boolean addAll(Collection<? extends String> collection) { throw new UnsupportedOperationException("Key Set add all not allowed"); }
        @Override public boolean retainAll(Collection<?> collection) { throw new UnsupportedOperationException("Key Set retain all not allowed"); }
        @Override @SuppressWarnings("unchecked")
        public boolean removeAll(Collection<?> collection) {
            return lsm.removeAllKeys(collection);
        }
        @Override public void clear() { throw new UnsupportedOperationException("Key Set clear not allowed"); }
    }

    public static class ValueCollectionWrapper<V> implements Collection<V> {
        private final LiteStringMap<V> lsm;
        ValueCollectionWrapper(LiteStringMap<V> liteStringMap) { lsm = liteStringMap; }

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

        @Override public Iterator<V> iterator() { return new ValueIterator<V>(lsm); }

        @Override public Object[] toArray() { return Arrays.copyOf(lsm.valueArray, lsm.lastIndex + 1); }
        @Override public <T> T[] toArray(T[] ts) {
            int toCopy = ts.length > lsm.lastIndex ? lsm.lastIndex + 1 : ts.length;
            System.arraycopy(lsm.valueArray, 0, ts, 0, toCopy);
            return ts;
        }

        @Override public boolean add(Object s) { throw new UnsupportedOperationException("Value Collection add not allowed"); }
        @Override public boolean remove(Object o) {
            return lsm.removeValue(o);
        }
        @Override public boolean addAll(Collection<? extends V> collection) { throw new UnsupportedOperationException("Value Collection add all not allowed"); }
        @Override public boolean retainAll(Collection<?> collection) { throw new UnsupportedOperationException("Value Collection retain all not allowed"); }
        @Override public boolean removeAll(Collection<?> collection) {
            return lsm.removeAllValues(collection);
        }
        @Override public void clear() { throw new UnsupportedOperationException("Value Collection clear not allowed"); }
    }

    public static class EntryWrapper<V> implements Entry<String, V> {
        private final LiteStringMap<V> lsm;
        private final String key;
        private int curIndex;

        EntryWrapper(LiteStringMap<V> liteStringMap, int index) {
            lsm = liteStringMap;
            curIndex = index;
            key = lsm.keyArray[index];
        }

        @Override public String getKey() { return key; }

        @Override public V getValue() {
            String keyCheck = lsm.keyArray[curIndex];
            if (!Objects.equals(key, keyCheck)) curIndex = lsm.findIndex(key);
            if (curIndex == -1) return null;
            return lsm.valueArray[curIndex];
        }
        @Override public V setValue(V value) {
            String keyCheck = lsm.keyArray[curIndex];
            if (!Objects.equals(key, keyCheck)) curIndex = lsm.findIndex(key);
            if (curIndex == -1) return lsm.put(key, value);
            V oldValue = lsm.valueArray[curIndex];
            lsm.valueArray[curIndex] = value;
            return oldValue;
        }
    }
    public static class EntrySetWrapper<V> implements Set<Entry<String, V>> {
        private final LiteStringMap<V> lsm;
        EntrySetWrapper(LiteStringMap<V> liteStringMap) { lsm = liteStringMap; }

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
        @Override public Iterator<Entry<String, V>> iterator() {
            ArrayList<Entry<String, V>> entryList = new ArrayList<>(lsm.lastIndex + 1);
            for (int i = 0; i <= lsm.lastIndex; i++) {
                if (lsm.getKey(i) == null) continue;
                entryList.add(new EntryWrapper<V>(lsm, i));
            }
            return entryList.iterator();
        }

        @Override public V[] toArray() { throw new UnsupportedOperationException("Entry Set to array not supported"); }
        @Override public <T> T[] toArray(T[] ts) { throw new UnsupportedOperationException("Entry Set copy to array not supported"); }

        @Override
        public boolean containsAll(Collection<?> collection) {
            if (collection == null) return false;
            for (Object obj : collection) if (obj == null || lsm.findIndex(obj.toString()) == -1) return false;
            return true;
        }

        @Override public boolean add(Entry<String, V> entry) { throw new UnsupportedOperationException("Entry Set add not allowed"); }
        @Override public boolean remove(Object o) { throw new UnsupportedOperationException("Entry Set remove not allowed"); }
        @Override public boolean addAll(Collection<? extends Entry<String, V>> collection) { throw new UnsupportedOperationException("Entry Set add all not allowed"); }
        @Override public boolean retainAll(Collection<?> collection) { throw new UnsupportedOperationException("Entry Set retain all not allowed"); }
        @Override public boolean removeAll(Collection<?> collection) { throw new UnsupportedOperationException("Entry Set remove all not allowed"); }
        @Override public void clear() { throw new UnsupportedOperationException("Entry Set clear not allowed"); }
    }
}
