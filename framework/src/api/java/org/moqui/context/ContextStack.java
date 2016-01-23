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
    protected final ArrayList<ArrayList<Map<String, Object>>> contextStack = new ArrayList<ArrayList<Map<String, Object>>>();
    protected ArrayList<Map<String, Object>> stackList = new ArrayList<Map<String, Object>>();
    protected Map<String, Object> firstMap = null;

    public ContextStack() {
        // start with a single Map
        push();
    }

    /** Push (save) the entire context, ie the whole Map stack, to create an isolated empty context. */
    public ContextStack pushContext() {
        contextStack.add(0, stackList);
        stackList = new ArrayList<Map<String, Object>>();
        firstMap = null;
        push();
        return this;
    }

    /** Pop (restore) the entire context, ie the whole Map stack, undo isolated empty context and get the original one. */
    public ContextStack popContext() {
        stackList = contextStack.remove(0);
        firstMap = stackList.get(0);
        return this;
    }

    /** Puts a new Map on the top of the stack for a fresh local context
     * @return Returns reference to this ContextStack
     */
    public ContextStack push() {
        Map<String, Object> newMap = new HashMap<String, Object>();
        stackList.add(0, newMap);
        firstMap = newMap;
        return this;
    }

    /** Puts an existing Map on the top of the stack (top meaning will override lower layers on the stack)
     * @param existingMap An existing Map
     * @return Returns reference to this ContextStack
     */
    public ContextStack push(Map<String, Object> existingMap) {
        if (existingMap == null) throw new IllegalArgumentException("Cannot push null as an existing Map");
        stackList.add(0, existingMap);
        firstMap = existingMap;
        return this;
    }

    /** Remove and returns the Map from the top of the stack (the local context).
     * If there is only one Map on the stack it returns null and does not remove it.
     *
     * @return The first/top Map
     */
    public Map pop() {
        Map<String, Object> popped = stackList.size() > 0 ? stackList.remove(0) : null;
        firstMap = stackList.size() > 0 ? stackList.get(0) : null;
        return popped;
    }

    /** Add an existing Map as the Root Map, ie on the BOTTOM of the stack meaning it will be overridden by other Maps on the stack
     * @param  existingMap An existing Map
     */
    public void addRootMap(Map<String, Object> existingMap) {
        if (existingMap == null) throw new IllegalArgumentException("Cannot add null as an existing Map");
        stackList.add(existingMap);
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
        int size = stackList.size();
        for (int i = 0; i < size; i++) {
            Map<String, Object> curMap = stackList.get(i);
            if (key == null && curMap instanceof Hashtable) continue;
            if (curMap.containsKey(key)) return true;
        }
        return false;
    }

    public boolean containsValue(Object value) {
        // this keeps track of keys looked at for values at each level of the stack so that the same key is not
        // considered more than once (the earlier Maps overriding later ones)
        Set<Object> keysObserved = new HashSet<Object>();
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
    }

    public Object get(Object keyObj) {
        String key = null;
        if (keyObj != null) {
            if (keyObj instanceof String) {
                key = (String) keyObj;
            } else if (keyObj instanceof CharSequence) {
                key = keyObj.toString();
            } else {
                return null;
            }
        }

        // optimize for non-null get, avoid double lookup with containsKey/get
        // it sure would be nice if there was a getEntry method in Java Maps... could always avoid the double lookup
        Object value = firstMap.get(key);
        if (value != null) return value;

        if (firstMap.containsKey(key)) {
            // we already got it and it's null by this point
            return null;
        } else {
            int size = stackList.size();
            // start with 1 to skip the first Map
            for (int i = 1; i < size; i++) {
                Map<String, Object> curMap = stackList.get(i);
                try {
                    if (key == null && curMap instanceof Hashtable) continue;
                    // optimize for non-null get, avoid double lookup with containsKey/get
                    value = curMap.get(key);
                    if (value != null) return value;
                    if (curMap.containsKey(key)) return null;
                } catch (Exception e) {
                    logger.error("Error getting value for key [" + key + "], returning null", e);
                    return null;
                }
            }

            // didn't find it
            // the "context" key always gets a self-reference; look for this last as it takes a sec and is uncommon
            if ("context".equals(key)) return this;
            return null;
        }
    }

    public Object put(String key, Object value) {
        return firstMap.put(key, value);
    }

    public Object remove(Object key) {
        return firstMap.remove(key);
    }

    public void putAll(Map<? extends String, ? extends Object> arg0) { firstMap.putAll(arg0); }

    public void clear() { firstMap.clear(); }

    public Set<String> keySet() {
        Set<String> resultSet = new HashSet<String>();
        resultSet.add("context");
        int size = stackList.size();
        for (int i = 0; i < size; i++) {
            Map<String, Object> curMap = stackList.get(i);
            resultSet.addAll(curMap.keySet());
        }
        return Collections.unmodifiableSet(resultSet);
    }

    public Collection<Object> values() {
        Set<Object> keysObserved = new HashSet<Object>();
        List<Object> resultValues = new LinkedList<Object>();
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
        Set<Object> keysObserved = new HashSet<Object>();
        Set<Map.Entry<String, Object>> resultEntrySet = new HashSet<Map.Entry<String, Object>>();
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
}
