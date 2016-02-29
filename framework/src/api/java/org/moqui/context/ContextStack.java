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
package org.moqui.context;

import java.util.*;

public class ContextStack implements Map<String, Object> {
    protected final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ContextStack.class);

    // Using ArrayList for more efficient iterating, this alone eliminate about 40% of the run time in get()
    protected ArrayList<ArrayList<MapWrapper>> contextStack = null;
    protected ArrayList<MapWrapper> stackList = new ArrayList<>();
    protected final Map<String, Object> combinedMap = new HashMap<>();

    public ContextStack() {
        // start with a single Map
        push();
        clearCombinedMap();
    }

    protected void clearCombinedMap() {
        combinedMap.clear();
        combinedMap.put("context", this);
    }
    protected void rebuildCombinedMap() {
        clearCombinedMap();
        // iterate through stackList from end to beginning
        for (int i = stackList.size() - 1; i >= 0; i--) {
            Map<String, Object> curMap = stackList.get(i);
            combinedMap.putAll(curMap);
        }
    }

    /** Push (save) the entire context, ie the whole Map stack, to create an isolated empty context. */
    public ContextStack pushContext() {
        if (contextStack == null) contextStack = new ArrayList<>();
        contextStack.add(0, stackList);
        stackList = new ArrayList<>();
        clearCombinedMap();
        push();
        return this;
    }

    /** Pop (restore) the entire context, ie the whole Map stack, undo isolated empty context and get the original one. */
    public ContextStack popContext() {
        if (contextStack == null || contextStack.size() == 0) throw new IllegalStateException("Cannot pop context, no context pushed");
        stackList = contextStack.remove(0);
        rebuildCombinedMap();
        return this;
    }

    /** Puts a new Map on the top of the stack for a fresh local context
     * @return Returns reference to this ContextStack
     */
    public ContextStack push() {
        stackList.add(0, new MapWrapper(this, null));
        return this;
    }

    /** Puts an existing Map on the top of the stack (top meaning will override lower layers on the stack)
     * @param existingMap An existing Map
     * @return Returns reference to this ContextStack
     */
    public ContextStack push(Map<String, Object> existingMap) {
        if (existingMap == null) throw new IllegalArgumentException("Cannot push null as an existing Map");
        stackList.add(0, new MapWrapper(this, existingMap));
        combinedMap.putAll(existingMap);
        return this;
    }

    /** Remove and returns the Map from the top of the stack (the local context).
     * If there is only one Map on the stack it returns null and does not remove it.
     *
     * @return The first/top Map
     */
    public Map pop() {
        MapWrapper popped = stackList.size() > 0 ? stackList.remove(0) : null;
        if (popped == null) return null;
        Map<String, Object> poppedMap = popped.getWrapped();
        resetCombinedEntries(poppedMap.keySet());
        return poppedMap;
    }
    protected void resetCombinedEntries(Set<String> keySet) {
        if (keySet.size() == 0) return;
        // use a faster approach than rebuildCombinedMap()
        // for each key in the popped Map see if a Map on the stack contains it and if so set it, otherwise remove it
        int stackListSize = stackList.size();
        for (String key : keySet) resetCombinedEntry(key, stackListSize);
    }
    protected void resetCombinedEntry(String key, int stackListSize) {
        boolean found = false;
        for (int i = 0; i < stackListSize; i++) {
            Map<String, Object> curMap = stackList.get(i).getWrapped();
            if (curMap.containsKey(key)) {
                combinedMap.put(key, curMap.get(key));
                found = true;
                break;
            }
        }
        if (!found) combinedMap.remove(key);
    }

    /** Add an existing Map as the Root Map, ie on the BOTTOM of the stack meaning it will be overridden by other Maps on the stack
     * @param  existingMap An existing Map
     */
    public void addRootMap(Map<String, Object> existingMap) {
        if (existingMap == null) throw new IllegalArgumentException("Cannot add null as an existing Map");
        stackList.add(new MapWrapper(this, existingMap));
        rebuildCombinedMap();
    }

    public Map getRootMap() { return stackList.get(stackList.size() - 1); }

    /**
     * Creates a ContextStack object that has the same Map objects on its stack (a shallow clone).
     * Meant to be used to enable a situation where a parent and child context are operating simultaneously using two
     * different ContextStack objects, but sharing the Maps between them.
     *
     * @return Clone of this ContextStack
     */
    public ContextStack clone() throws CloneNotSupportedException {
        ContextStack newStack = new ContextStack();
        newStack.stackList.addAll(stackList);
        newStack.rebuildCombinedMap();
        return newStack;
    }

    public int size() {
        // use the keySet since this gets a set of all unique keys for all Maps in the stack
        Set keys = keySet();
        return keys.size();
    }

    public boolean isEmpty() {
        for (Map curMap: stackList) {
            if (!curMap.isEmpty()) return false;
        }
        return true;
    }

    public boolean containsKey(Object key) {
        return combinedMap.containsKey(key);
        /* no longer allow changes to maps pushed on the context stack externally, turns this into a simple operation
        if (combinedMap.containsKey(key)) return true;

        int size = stackList.size();
        for (int i = 0; i < size; i++) {
            Map<String, Object> curMap = stackList.get(i);
            if (key == null && curMap instanceof Hashtable) continue;
            if (curMap.containsKey(key)) return true;
        }
        return false;
        */
    }

    public boolean containsValue(Object value) {
        return combinedMap.containsValue(value);

        /* no longer allow changes to maps pushed on the context stack externally, turns this into a simple operation
        if (combinedMap.containsValue(value)) return true;

        // this keeps track of keys looked at for values at each level of the stack so that the same key is not
        // considered more than once (the earlier Maps overriding later ones)
        Set<Object> keysObserved = new HashSet<>();
        int size = stackList.size();
        for (int i = 0; i < size; i++) {
            Map<String, Object> curMap = stackList.get(i);
            for (Map.Entry curEntry: curMap.entrySet()) {
                if (!keysObserved.contains(curEntry.getKey())) {
                    keysObserved.add(curEntry.getKey());
                    if (value == null) {
                        if (curEntry.getValue() == null) return true;
                    } else {
                        if (value.equals(curEntry.getValue())) return true;
                    }
                }
            }
        }
        return false;
        */
    }

    public Object get(Object keyObj) {
        String key = null;
        if (keyObj instanceof String) {
            key = (String) keyObj;
        } else if (keyObj != null) {
            if (keyObj instanceof CharSequence) {
                key = keyObj.toString();
            } else {
                return null;
            }
        }

        // no longer allow changes to maps pushed on the context stack externally, turns this into a simple get
        return combinedMap.get(key);

        /*
        // optimize for non-null get, avoid double lookup with containsKey/get
        // it sure would be nice if there was a getEntry method in Java Maps... could always avoid the double lookup
        Object value = combinedMap.get(key);
        if (value != null) return value;

        // we already got it and it's null by this point
        if (combinedMap.containsKey(key)) return null;

        // NOTE: no longer needed, "context" added to combinedMap (not used for toString, equals(), etc so won't result in infinite recursion
        // the "context" key always gets a self-reference; look for this last as it takes a sec and is uncommon
        // if ("context".equals(key)) return this;

        // this is slower than the combinedMap, but just in case a Map on the stack was modified directly (instead
        //     of through ContextStack) look through all maps on the stack
        int size = stackList.size();
        Object foundValue = null;
        for (int i = 0; i < size; i++) {
            Map<String, Object> curMap = stackList.get(i);
            try {
                if (key == null && curMap instanceof Hashtable) continue;
                // optimize for non-null get, avoid double lookup with containsKey/get
                value = curMap.get(key);
                if (value != null) {
                    foundValue = value;
                    break;
                }
                if (curMap.containsKey(key)) {
                    foundValue = null;
                    break;
                }
            } catch (Exception e) {
                logger.error("Error getting value for key [" + key + "], returning null", e);
                foundValue = null;
                break;
            }
        }

        // remember what we found, even if null, to avoid searching the stackList in future calls
        combinedMap.put(key, foundValue);

        return foundValue;
        */
    }

    public Object put(String key, Object value) {
        combinedMap.put(key, value);
        return stackList.get(0).getWrapped().put(key, value);
    }

    public Object remove(Object key) {
        Object oldVal = stackList.get(0).getWrapped().remove(key);
        resetCombinedEntry(key.toString(), stackList.size());
        return oldVal;
    }

    public void putAll(Map<? extends String, ?> arg0) {
        combinedMap.putAll(arg0);
        stackList.get(0).getWrapped().putAll(arg0);
    }

    public void clear() {
        Map<String, Object> topMap = stackList.get(0).getWrapped();
        resetCombinedEntries(topMap.keySet());
        topMap.clear();
    }

    public Set<String> keySet() {
        Set<String> resultSet = new HashSet<>();
        resultSet.add("context");
        int size = stackList.size();
        for (int i = 0; i < size; i++) {
            Map<String, Object> curMap = stackList.get(i);
            resultSet.addAll(curMap.keySet());
        }
        return Collections.unmodifiableSet(resultSet);
    }

    public Collection<Object> values() {
        Set<Object> keysObserved = new HashSet<>();
        List<Object> resultValues = new LinkedList<>();
        int size = stackList.size();
        for (int i = 0; i < size; i++) {
            Map<String, Object> curMap = stackList.get(i);
            for (Map.Entry curEntry: curMap.entrySet()) {
                if (!keysObserved.contains(curEntry.getKey())) {
                    keysObserved.add(curEntry.getKey());
                    resultValues.add(curEntry.getValue());
                }
            }
        }
        return Collections.unmodifiableCollection(resultValues);
    }

    /** @see java.util.Map#entrySet() */
    public Set<Map.Entry<String, Object>> entrySet() {
        Set<Object> keysObserved = new HashSet<>();
        Set<Map.Entry<String, Object>> resultEntrySet = new HashSet<>();
        int size = stackList.size();
        for (int i = 0; i < size; i++) {
            Map<String, Object> curMap = stackList.get(i);
            for (Map.Entry<String, Object> curEntry: curMap.entrySet()) {
                if (!keysObserved.contains(curEntry.getKey())) {
                    keysObserved.add(curEntry.getKey());
                    resultEntrySet.add(curEntry);
                }
            }
        }
        return Collections.unmodifiableSet(resultEntrySet);
    }

    @Override
    public String toString() {
        StringBuilder fullMapString = new StringBuilder();
        int curLevel = 0;
        for (Map<String, Object> curMap: stackList) {
            fullMapString.append("========== Start stack level ").append(curLevel).append("\n");
            for (Map.Entry curEntry: curMap.entrySet()) {
                fullMapString.append("==>[");
                fullMapString.append(curEntry.getKey());
                fullMapString.append("]:");
                if (curEntry.getValue() instanceof ContextStack) {
                    // skip instances of ContextStack to avoid infinite recursion
                    fullMapString.append("<Instance of ContextStack, not printing to avoid infinite recursion>");
                } else {
                    fullMapString.append(curEntry.getValue());
                }
                fullMapString.append("\n");
            }
            fullMapString.append("========== End stack level ").append(curLevel).append("\n");
            curLevel++;
        }
        return fullMapString.toString();
    }

    @Override
    public int hashCode() {
        return this.stackList.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        return !(o == null || o.getClass() != this.getClass()) && this.stackList.equals(((ContextStack) o).stackList);
    }

    /** Wrap a Map with a reference to the ContextStack to maintain the combinedMap on changes */
    public static class MapWrapper implements Map<String, Object> {
        protected final ContextStack contextStack;
        protected final Map<String, Object> internal;
        public MapWrapper(ContextStack contextStack, Map<String, Object> toWrap) {
            this.contextStack = contextStack;
            this.internal = toWrap != null ? toWrap : new HashMap<String, Object>();
        }

        public Map<String, Object> getWrapped() { return internal; }

        public int size() { return internal.size(); }
        public boolean isEmpty() { return internal.isEmpty(); }
        public boolean containsKey(Object key) { return internal.containsKey(key); }
        public boolean containsValue(Object value) { return internal.containsValue(value); }
        public Object get(Object key) { return internal.get(key); }
        public Object put(String key, Object value) {
            Object orig = internal.put(key, value);
            contextStack.rebuildCombinedMap();
            return orig;
        }
        public Object remove(Object key) {
            Object orig = internal.remove(key);
            contextStack.rebuildCombinedMap();
            return orig;
        }
        public void putAll(Map<? extends String, ?> m) {
            internal.putAll(m);
            contextStack.rebuildCombinedMap();
        }
        public void clear() {
            internal.clear();
            contextStack.rebuildCombinedMap();
        }
        public Set<String> keySet() { return internal.keySet(); }
        public Collection<Object> values() { return internal.values(); }
        public Set<Entry<String, Object>> entrySet() { return internal.entrySet(); }
    }
}
