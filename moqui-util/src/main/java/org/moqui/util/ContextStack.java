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

import javax.annotation.Nonnull;
import java.util.*;

@SuppressWarnings("unchecked")
public class ContextStack implements Map<String, Object> {
    private final static org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ContextStack.class);
    private final int INITIAL_STACK_SIZE = 32;

    private HashMap<String, Object> sharedMap = null;
    private LinkedList<ContextInfo> contextStack = null;

    private Map<String, Object>[] stackArray = new Map[INITIAL_STACK_SIZE];
    private int stackIndex = 0;

    private boolean includeContext = true;

    private static class ContextInfo {
        Map<String, Object>[] stackArray;
        int stackIndex;
        ContextInfo(Map<String, Object>[] stackArray, int stackIndex) { this.stackArray = stackArray; this.stackIndex = stackIndex; }
        ContextInfo cloneInfo() {
            Map<String, Object>[] newArray = new Map[stackArray.length];
            System.arraycopy(stackArray, 0, newArray, 0, stackIndex + 1);
            return new ContextInfo(newArray, stackIndex);
        }
    }

    public ContextStack() { }
    public ContextStack(boolean includeContext) { this.includeContext = includeContext; }

    public Map<String, Object> getSharedMap() {
        if (sharedMap == null) sharedMap = new HashMap<>();
        return sharedMap;
    }

    /** Push (save) the entire context, ie the whole Map stack, to create an isolated empty context. */
    public ContextStack pushContext() {
        if (contextStack == null) contextStack = new LinkedList<>();
        contextStack.addFirst(new ContextInfo(stackArray, stackIndex));
        stackArray = new Map[INITIAL_STACK_SIZE];
        stackIndex = 0;
        return this;
    }

    /** Pop (restore) the entire context, ie the whole Map stack, undo isolated empty context and get the original one. */
    public ContextStack popContext() {
        if (contextStack == null || contextStack.size() == 0) throw new IllegalStateException("Cannot pop context, no context pushed");
        ContextInfo ci = contextStack.removeFirst();
        stackArray = ci.stackArray;
        stackIndex = ci.stackIndex;
        return this;
    }

    private void pushInternal(Map theMap) {
        stackIndex++;
        if (stackIndex >= stackArray.length) growStackArray();
        // NOTE: if null leave null for lazy init on put
        stackArray[stackIndex] = theMap;
    }
    private void growStackArray() {
        // logger.warn("Growing ContextStack internal array from " + stackArray.length);

        stackArray = Arrays.copyOf(stackArray, stackArray.length * 2);
    }

    /** Puts a new Map on the top of the stack for a fresh local context
     * @return Returns reference to this ContextStack
     */
    public ContextStack push() {
        pushInternal(null);
        return this;
    }

    /** Puts an existing Map on the top of the stack (top meaning will override lower layers on the stack)
     * @param existingMap An existing Map
     * @return Returns reference to this ContextStack
     */
    public ContextStack push(Map<String, Object> existingMap) {
        if (existingMap == null) throw new IllegalArgumentException("Cannot push null as an existing Map");
        if (includeContext && existingMap.containsKey("context"))
            throw new IllegalArgumentException("Cannot push existing Map containing key 'context', reserved key");

        pushInternal(existingMap);
        return this;
    }

    /** Remove and returns the Map from the top of the stack (the local context).
     * If there is only one Map on the stack it returns null and does not remove it.
     *
     * @return The first/top Map
     */
    public Map<String, Object> pop() {
        if (stackIndex == 0) throw new IllegalArgumentException("ContextStack is empty, cannot pop the context");
        Map<String, Object> oldMap = stackArray[stackIndex];
        stackArray[stackIndex] = null;
        stackIndex--;
        return oldMap;
    }

    /** Add an existing Map as the Root Map, ie on the BOTTOM of the stack meaning it will be overridden by other Maps on the stack
     * @param existingMap An existing Map
     */
    public void addRootMap(Map<String, Object> existingMap) {
        if (existingMap == null) throw new IllegalArgumentException("Cannot add null as an existing Map");
        if (includeContext && existingMap.containsKey("context"))
            throw new IllegalArgumentException("Cannot push existing Map containing key 'context', reserved key");

        if ((stackIndex + 1) >= stackArray.length) growStackArray();
        // move all elements up one
        for (int i = stackIndex; i >= 0; i--) stackArray[i+1] = stackArray[i];
        stackIndex++;
        stackArray[0] = existingMap;
    }

    public Map<String, Object> getRootMap() { return stackArray[0]; }

    /**
     * Creates a ContextStack object that has the same Map objects on its stack (a shallow clone).
     * Meant to be used to enable a situation where a parent and child context are operating simultaneously using two
     * different ContextStack objects, but sharing the Maps between them.
     *
     * @return Clone of this ContextStack
     */
    @Override public ContextStack clone() throws CloneNotSupportedException {
        ContextStack newStack = new ContextStack();
        newStack.stackArray = new Map[stackArray.length];
        System.arraycopy(stackArray, 0, newStack.stackArray, 0, stackIndex + 1);
        newStack.stackIndex = stackIndex;
        
        if (sharedMap != null) newStack.sharedMap = new HashMap<>(sharedMap);

        if (contextStack != null) {
            newStack.contextStack = new LinkedList<>();
            for (ContextInfo ci : contextStack) newStack.contextStack.add(ci.cloneInfo());
        }
        newStack.includeContext = includeContext;

        return newStack;
    }

    @Override public int size() {
        // use the keySet since this gets a set of all unique keys for all Maps in the stack
        Set keys = keySet();
        return keys.size();
    }
    @Override public boolean isEmpty() {
        for (int i = stackIndex; i >= 0; i--) { if (stackArray[i] != null && !stackArray[i].isEmpty()) return false; }
        return true;
    }

    @Override public boolean containsKey(Object key) {
        for (int i = stackIndex; i >= 0; i--) { if (stackArray[i] != null && stackArray[i].containsKey(key)) return true; }
        return false;
    }
    @Override public boolean containsValue(Object value) {
        // this keeps track of keys looked at for values at each level of the stack so that the same key is not
        // considered more than once (the earlier Maps overriding later ones)
        Set<Object> keysObserved = new HashSet<>();
        for (int i = stackIndex; i >= 0; i--) {
            Map<String, Object> curMap = stackArray[i];
            for (Map.Entry<String, Object> curEntry : curMap.entrySet()) {
                String curKey = curEntry.getKey();
                if (!keysObserved.contains(curKey)) {
                    keysObserved.add(curKey);
                    if (value == null) {
                        if (curEntry.getValue() == null) return true;
                    } else {
                        if (value.equals(curEntry.getValue())) return true;
                    }
                }
            }
        }
        return false;

        // maybe do simpler but not as correct? for (int i = stackIndex; i >= 0; i--) { if (stackArray[i] != null && stackArray[i].containsValue(value)) return true; }
    }

    /** For faster access to multiple entries; do not write to this Map or use when any changes to ContextStack are possible */
    public Map<String, Object> getCombinedMap() {
        Map<String, Object> combinedMap = new HashMap<>();
        // opposite order of get(), root down so later maps override earlier
        for (int i = 0; i <= stackIndex; i++) {
            if (stackArray[i] != null) combinedMap.putAll(stackArray[i]);
        }
        return combinedMap;
    }

    public Object getByString(String key) {
        for (int i = stackIndex; i >= 0; i--) {
            Map<String, Object> curMap = stackArray[i];
            if (curMap == null || curMap.isEmpty()) continue;
            // optimize for non-null get, avoid double lookup with containsKey/get
            Object value = curMap.get(key);
            if (value != null) return value;
            if (curMap.containsKey(key)) return null;
        }

        // handle "context" reserved key to represent this
        if (includeContext && "context".equals(key)) return this;

        return null;
    }
    @Override public Object get(Object keyObj) {
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
        return getByString(key);
    }

    @Override public Object put(String key, Object value) {
        if (includeContext && "context".equals(key)) throw new IllegalArgumentException("Cannot put with key 'context', reserved key");

        if (stackArray[stackIndex] == null) stackArray[stackIndex] = new HashMap<>();
        return stackArray[stackIndex].put(key, value);
    }

    @Override public Object remove(Object key) {
        if (stackArray[stackIndex] == null) return null;
        return stackArray[stackIndex].remove(key);
    }

    @Override public void putAll(@Nonnull Map<? extends String, ?> theMap) {
        // using Nonnull: if (theMap == null) return;
        if (includeContext && theMap.containsKey("context"))
            throw new IllegalArgumentException("Cannot push existing Map containing key 'context', reserved key");

        if (stackArray[stackIndex] == null) stackArray[stackIndex] = new HashMap<>();
        stackArray[stackIndex].putAll(theMap);
    }

    @Override public void clear() {
        if (stackArray[stackIndex] == null) return;
        stackArray[stackIndex].clear();
    }

    @Override public @Nonnull Set<String> keySet() {
        Set<String> resultSet = new HashSet<>();
        // resultSet.add("context");
        for (int i = stackIndex; i >= 0; i--) if (stackArray[i] != null) resultSet.addAll(stackArray[i].keySet());
        return Collections.unmodifiableSet(resultSet);
    }
    @Override public @Nonnull Collection<Object> values() {
        Set<Object> keysObserved = new HashSet<>();
        List<Object> resultValues = new LinkedList<>();
        for (int i = stackIndex; i >= 0; i--) {
            if (stackArray[i] == null) continue;
            for (Map.Entry<String, Object> curEntry: stackArray[i].entrySet()) {
                String curKey = curEntry.getKey();
                if (!keysObserved.contains(curKey)) {
                    keysObserved.add(curKey);
                    resultValues.add(curEntry.getValue());
                }
            }
        }
        return Collections.unmodifiableCollection(resultValues);
    }
    @Override public @Nonnull Set<Map.Entry<String, Object>> entrySet() {
        Set<Object> keysObserved = new HashSet<>();
        Set<Map.Entry<String, Object>> resultEntrySet = new HashSet<>();
        for (int i = stackIndex; i >= 0; i--) {
            if (stackArray[i] == null) continue;
            for (Map.Entry<String, Object> curEntry: stackArray[i].entrySet()) {
                String curKey = curEntry.getKey();
                if (!keysObserved.contains(curKey)) {
                    keysObserved.add(curKey);
                    resultEntrySet.add(curEntry);
                }
            }
        }
        return Collections.unmodifiableSet(resultEntrySet);    }

    @Override
    public String toString() {
        StringBuilder fullMapString = new StringBuilder();
        for (int i = 0; i <= stackIndex; i++) {
            Map<String, Object> curMap = stackArray[i];
            if (curMap == null) continue;
            fullMapString.append("========== Start stack level ").append(i).append("\n");
            for (Map.Entry<String, Object> curEntry: curMap.entrySet()) {
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
            fullMapString.append("========== End stack level ").append(i).append("\n");
        }
        return fullMapString.toString();
    }

    @Override public int hashCode() { return Arrays.deepHashCode(stackArray); }
    @Override public boolean equals(Object o) {
        return !(o == null || o.getClass() != this.getClass()) && Arrays.deepEquals(stackArray, ((ContextStack) o).stackArray);
    }
}
