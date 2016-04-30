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

    private LinkedList<ArrayList<MapWrapper>> contextStack = null;
    private LinkedList<Map<String, Object>> contextCombinedStack = null;

    private ArrayList<MapWrapper> stackList = new ArrayList<>();
    private Map<String, Object> topMap = null;
    private Map<String, Object> combinedMap = null;
    private boolean includeContext = true;
    private boolean toStringRecursion = false;

    public ContextStack() {
        // start with a single Map
        clearCombinedMap();
        push();
    }
    public ContextStack(boolean includeContext) {
        this.includeContext = includeContext;
        // start with a single Map
        clearCombinedMap();
        push();
    }

    // Internal methods for managing combinedMap

    private void clearCombinedMap() {
        combinedMap = new HashMap<>();
        if (includeContext) combinedMap.put("context", this);
    }
    private void rebuildCombinedMap() {
        clearCombinedMap();
        // iterate through stackList from end to beginning
        Map<String, Object> parentCombined = null;
        for (int i = stackList.size() - 1; i >= 0; i--) {
            MapWrapper curWrapper = stackList.get(i);
            Map<String, Object> curMap = curWrapper.getWrapped();
            Map<String, Object> curCombined = curWrapper.getCombined();

            curCombined.clear();
            if (parentCombined != null) curCombined.putAll(parentCombined);
            curCombined.putAll(curMap);
            parentCombined = curCombined;
            // make sure 'context' refers to this no matter what is in maps
            if (includeContext) curCombined.put("context", this);
        }
        combinedMap = parentCombined;
    }
    /* now using MapWrapper.combined instead of this:
    // faster than rebuildCombinedMap, but not by much
    protected void resetCombinedEntries(Set<String> keySet) {
        if (keySet.size() == 0) return;
        // if (keySet.contains("context")) throw new IllegalArgumentException("Cannot reset combined entry with key 'context', reserved key");
        // use a faster approach than rebuildCombinedMap()
        // for each key in the popped Map see if a Map on the stack contains it and if so set it, otherwise remove it
        int stackListSize = stackList.size();
        for (String key : keySet) resetCombinedEntry(key, stackListSize);
    }
    */
    private void resetCombinedEntry(String key, int stackListSize) {
        if ("context".equals(key)) return;
        boolean found = false;
        for (int i = 0; i < stackListSize; i++) {
            MapWrapper curMap = stackList.get(i);
            if (curMap.containsKey(key)) {
                combinedMap.put(key, curMap.get(key));
                found = true;
                break;
            }
        }
        if (!found) combinedMap.remove(key);
    }

    /** Push (save) the entire context, ie the whole Map stack, to create an isolated empty context. */
    public ContextStack pushContext() {
        if (contextStack == null) contextStack = new LinkedList<>();
        if (contextCombinedStack == null) contextCombinedStack = new LinkedList<>();
        contextStack.addFirst(stackList);
        contextCombinedStack.addFirst(combinedMap);
        stackList = new ArrayList<>();
        clearCombinedMap();
        push();
        return this;
    }

    /** Pop (restore) the entire context, ie the whole Map stack, undo isolated empty context and get the original one. */
    public ContextStack popContext() {
        if (contextStack == null || contextStack.size() == 0) throw new IllegalStateException("Cannot pop context, no context pushed");
        stackList = contextStack.removeFirst();
        combinedMap = contextCombinedStack.removeFirst();
        topMap = stackList.get(0).getWrapped();
        return this;
    }

    /** Puts a new Map on the top of the stack for a fresh local context
     * @return Returns reference to this ContextStack
     */
    public ContextStack push() {
        Map<String, Object> newMap = new HashMap<>();
        Map<String, Object> newCombined = new HashMap<>(combinedMap);
        stackList.add(0, new MapWrapper(this, newMap, newCombined));
        topMap = newMap;
        combinedMap = newCombined;

        return this;
    }

    /** Puts an existing Map on the top of the stack (top meaning will override lower layers on the stack)
     * @param existingMap An existing Map
     * @return Returns reference to this ContextStack
     */
    public ContextStack push(Map<String, Object> existingMap) {
        if (existingMap == null) throw new IllegalArgumentException("Cannot push null as an existing Map");

        // if (existingMap.containsKey("context")) throw new IllegalArgumentException("Cannot push existing with key 'context', reserved key");
        Map<String, Object> newCombined = new HashMap<>(combinedMap);
        newCombined.putAll(existingMap);
        // make sure 'context' refers to this no matter what is in the existingMap (may even be a ContextStack instance)
        if (includeContext) newCombined.put("context", this);
        stackList.add(0, new MapWrapper(this, existingMap, newCombined));
        topMap = existingMap;
        combinedMap = newCombined;

        return this;
    }

    /** Remove and returns the Map from the top of the stack (the local context).
     * If there is only one Map on the stack it returns null and does not remove it.
     *
     * @return The first/top Map
     */
    public Map<String, Object> pop() {
        if (topMap == null) {
            throw new IllegalArgumentException("ContextStack is empty, cannot pop the context");
            // return null;
        }

        Map<String, Object> oldMap = stackList.remove(0);
        if (stackList.size() > 0) {
            MapWrapper topWrapper = stackList.get(0);
            topMap = topWrapper.getWrapped();
            combinedMap = topWrapper.getCombined();
        } else {
            topMap = null;
            clearCombinedMap();
        }

        return oldMap;
    }

    /** Add an existing Map as the Root Map, ie on the BOTTOM of the stack meaning it will be overridden by other Maps on the stack
     * @param  existingMap An existing Map
     */
    public void addRootMap(Map<String, Object> existingMap) {
        if (existingMap == null) throw new IllegalArgumentException("Cannot add null as an existing Map");
        stackList.add(new MapWrapper(this, existingMap, new HashMap<>(existingMap)));
        rebuildCombinedMap();
    }

    public Map<String, Object> getRootMap() { return stackList.get(stackList.size() - 1); }

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
        newStack.topMap = topMap;
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

        /* with combinedMap now handling all changes this is a simple call
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

        /* with combinedMap now handling all changes this is a simple call
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

    public Object getByString(String key) {
        return combinedMap.get(key);
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

        // with combinedMap now handling all changes this is a simple call
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
        if ("context".equals(key)) throw new IllegalArgumentException("Cannot put with key 'context', reserved key");
        combinedMap.put(key, value);
        return topMap.put(key, value);
    }

    public Object remove(Object key) {
        Object oldVal = topMap.remove(key);
        resetCombinedEntry(key.toString(), stackList.size());
        return oldVal;
    }

    public void putAll(Map<? extends String, ?> arg0) {
        if (arg0 == null) return;
        for (Map.Entry<? extends String, ?> entry : arg0.entrySet()) {
            String key = entry.getKey();
            if ("context".equals(key)) continue;
            combinedMap.put(key, entry.getValue());
            topMap.put(key, entry.getValue());
        }
    }

    public void clear() {
        topMap.clear();
        if (stackList.size() > 1) {
            MapWrapper parentWrapper = stackList.get(1);
            combinedMap = parentWrapper.getCombined();
        } else {
            clearCombinedMap();
        }
    }

    public Set<String> keySet() {
        return combinedMap.keySet();

        /* with combinedMap now handling all changes this is a simple call
        Set<String> resultSet = new HashSet<>();
        resultSet.add("context");
        int size = stackList.size();
        for (int i = 0; i < size; i++) {
            Map<String, Object> curMap = stackList.get(i);
            resultSet.addAll(curMap.keySet());
        }
        return Collections.unmodifiableSet(resultSet);
        */
    }

    public Collection<Object> values() {
        return combinedMap.values();

        /* with combinedMap now handling all changes this is a simple call
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
        */
    }

    /** @see java.util.Map#entrySet() */
    public Set<Map.Entry<String, Object>> entrySet() {
        return combinedMap.entrySet();

        /* with combinedMap now handling all changes this is a simple call
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
        */
    }

    @Override
    public String toString() {
        if (toStringRecursion) return "<Instance of ContextStack, not printing to avoid infinite recursion>";
        toStringRecursion = true;
        StringBuilder fullMapString = new StringBuilder();

        for (int i = 0; i < stackList.size(); i++) {
            MapWrapper curMap = stackList.get(i);
            fullMapString.append("========== Start stack level ").append(i).append("\n");
            for (Map.Entry curEntry: curMap.entrySet()) {
                fullMapString.append("==>[");
                fullMapString.append(curEntry.getKey());
                fullMapString.append("]:");
                fullMapString.append(curEntry.getValue());
                fullMapString.append("\n");
            }
            fullMapString.append("========== End stack level ").append(i).append("\n");
        }

        fullMapString.append("========== Start combined Map").append("\n");
        for (Map.Entry curEntry: combinedMap.entrySet()) {
            fullMapString.append("==>[");
            fullMapString.append(curEntry.getKey());
            fullMapString.append("]:");
            fullMapString.append(curEntry.getValue());
            fullMapString.append("\n");
        }
        fullMapString.append("========== End combined Map").append("\n");

        toStringRecursion = false;
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
    private static class MapWrapper implements Map<String, Object> {
        private final ContextStack contextStack;
        private final Map<String, Object> internal;
        private final Map<String, Object> combined;

        MapWrapper(ContextStack contextStack, Map<String, Object> toWrap, Map<String, Object> newCombined) {
            this.contextStack = contextStack;
            this.internal = toWrap != null ? toWrap : new HashMap<String, Object>();
            this.combined = newCombined;
        }

        Map<String, Object> getWrapped() { return internal; }
        Map<String, Object> getCombined() { return combined; }

        public int size() { return internal.size(); }
        public boolean isEmpty() { return internal.isEmpty(); }
        public boolean containsKey(Object key) { return internal.containsKey(key); }
        public boolean containsValue(Object value) { return internal.containsValue(value); }
        public Object get(Object key) { return internal.get(key); }
        public Object put(String key, Object value) {
            if ("context".equals(key)) throw new IllegalArgumentException("Cannot put with key 'context', reserved key");
            Object orig = internal.put(key, value);
            // maybe do something more efficient than rebuilding all combined Maps? if it is an issue in profiling...
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
