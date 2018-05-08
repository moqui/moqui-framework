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
package org.moqui.impl.entity;

import groovy.lang.Closure;
import org.moqui.Moqui;
import org.moqui.entity.EntityCondition;
import org.moqui.entity.EntityException;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.moqui.util.CollectionUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.io.Writer;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.*;

public class EntityListImpl implements EntityList {
    protected static final Logger logger = LoggerFactory.getLogger(EntityConditionFactoryImpl.class);
    private transient EntityFacadeImpl efiTransient;
    private ArrayList<EntityValue> valueList;
    private boolean fromCache = false;
    protected Integer offset = null;
    protected Integer limit = null;

    /** Default constructor for deserialization ONLY. */
    public EntityListImpl() { }

    public EntityListImpl(EntityFacadeImpl efi) {
        this.efiTransient = efi;
        valueList = new ArrayList<>(30);// default size, at least enough for common pagination
    }

    public EntityListImpl(EntityFacadeImpl efi, int initialCapacity) {
        this.efiTransient = efi;
        valueList = new ArrayList<>(initialCapacity);
    }

    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(valueList);
        // don't serialize fromCache, will default back to false which is fine for a copy
    }

    @SuppressWarnings("unchecked")
    @Override public void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
        valueList = (ArrayList<EntityValue>) objectInput.readObject();
    }

    @SuppressWarnings("unchecked")
    public EntityFacadeImpl getEfi() {
        if (efiTransient == null)
            efiTransient = ((ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()).entityFacade;
        return efiTransient;
    }

    @Override public EntityValue getFirst() { return valueList != null && valueList.size() > 0 ? valueList.get(0) : null; }

    @Override public EntityList filterByDate(String fromDateName, String thruDateName, Timestamp moment) {
        if (fromCache) return this.cloneList().filterByDate(fromDateName, thruDateName, moment);

        // default to now
        long momentLong = moment != null ? moment.getTime() : System.currentTimeMillis();
        long momentDateLong = new Date(momentLong).getTime();
        if (fromDateName == null || fromDateName.length() == 0) fromDateName = "fromDate";
        if (thruDateName == null || thruDateName.length() == 0) thruDateName = "thruDate";

        int valueIndex = 0;
        while (valueIndex < valueList.size()) {
            EntityValue value = valueList.get(valueIndex);
            Object fromDateObj = value.get(fromDateName);
            Object thruDateObj = value.get(thruDateName);

            Long fromDateLong = getDateLong(fromDateObj);
            Long thruDateLong = getDateLong(thruDateObj);

            if (fromDateObj instanceof Date || thruDateObj instanceof Date) {
                if (!((thruDateLong == null || thruDateLong >= momentDateLong) && (fromDateLong == null || fromDateLong <= momentDateLong))) {
                    valueList.remove(valueIndex);
                } else {
                    valueIndex++;
                }

            } else {
                if (!((thruDateLong == null || thruDateLong >= momentLong) && (fromDateLong == null || fromDateLong <= momentLong))) {
                    valueList.remove(valueIndex);
                } else {
                    valueIndex++;
                }
            }
        }

        return this;
    }

    private static Long getDateLong(Object dateObj) {
        if (dateObj instanceof java.util.Date) return ((java.util.Date) dateObj).getTime();
        else if (dateObj instanceof Long) return (Long) dateObj;
        else return null;
    }

    @Override public EntityList filterByDate(String fromDateName, String thruDateName, Timestamp moment, boolean ignoreIfEmpty) {
        if (ignoreIfEmpty && moment == null) return this;
        return filterByDate(fromDateName, thruDateName, moment);
    }

    @Override public EntityList filterByAnd(Map<String, Object> fields) { return filterByAnd(fields, true); }
    @Override public EntityList filterByAnd(Map<String, Object> fields, Boolean include) {
        if (fromCache) return this.cloneList().filterByAnd(fields, include);
        if (include == null) include = true;

        // iterate fields once, then use indexes within big loop
        int fieldsSize = fields.size();
        String[] names = new String[fieldsSize];
        Object[] values = new Object[fieldsSize];
        boolean hasSetValue = false;
        int fieldIndex = 0;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            names[fieldIndex] = entry.getKey();
            Object val = entry.getValue();
            values[fieldIndex] = val;
            if (val instanceof Collection) {
                hasSetValue = true;
                if (!(val instanceof Set)) values[fieldIndex] = new HashSet<Object>((Collection<? extends Object>) val);
            }
            fieldIndex++;
        }

        int valueIndex = 0;
        while (valueIndex < valueList.size()) {
            EntityValue value = valueList.get(valueIndex);
            boolean matches = true;
            for (int i = 0; i < fieldsSize; i++) {
                Object curValue = value.getNoCheckSimple(names[i]);
                Object compValue = values[i];
                if (curValue == null) {
                    matches = compValue == null;
                    if (!matches) { matches = false; break; }
                } else {
                    if (hasSetValue && compValue instanceof Set) {
                        Set valSet = (Set) compValue;
                        if (!valSet.contains(curValue)) { matches = false; break; }
                    } else if (!curValue.equals(compValue)) {
                        matches = false;
                        break;
                    }
                }
            }
            if ((matches && !include) || (!matches && include)) {
                valueList.remove(valueIndex);
            } else {
                valueIndex++;
            }
        }
        return this;
    }
    @Override public EntityList filterByAnd(Object... namesAndValues) {
        if (namesAndValues.length == 0) return this;
        if (namesAndValues.length % 2 != 0) throw new IllegalArgumentException("Must pass an even number of parameters for name/value pairs");
        Map<String, Object> fields = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i+=2) fields.put((String) namesAndValues[i], namesAndValues[i+1]);
        return filterByAnd(fields);
    }


    @Override public EntityList filter(Closure<Boolean> closure, Boolean include) {
        if (fromCache) return this.cloneList().filter(closure, include);
        int valueIndex = 0;
        while (valueIndex < valueList.size()) {
            EntityValue value = valueList.get(valueIndex);
            boolean matches = closure.call(value);
            if ((matches && !include) || (!matches && include)) {
                valueList.remove(valueIndex);
            } else {
                valueIndex++;
            }
        }
        return this;
    }

    @Override public EntityValue find(Closure<Boolean> closure) {
        int valueListSize = valueList.size();
        for (int i = 0; i < valueListSize; i++) {
            EntityValue value = valueList.get(i);
            boolean matches = closure.call(value);
            if (matches) return value;
        }
        return null;
    }

    @Override public EntityList findAll(Closure<Boolean> closure) {
        EntityListImpl newList = new EntityListImpl(getEfi());
        int valueListSize = valueList.size();
        for (int i = 0; i < valueListSize; i++) {
            EntityValue value = valueList.get(i);
            boolean matches = closure.call(value);
            if (matches) newList.add(value);
        }

        return newList;
    }

    @Override public EntityList removeByAnd(Map<String, Object> fields) { return filterByAnd(fields, false); }

    @Override public EntityList filterByCondition(EntityCondition condition, Boolean include) {
        if (fromCache) return this.cloneList().filterByCondition(condition, include);
        if (include == null) include = true;
        int valueIndex = 0;
        while (valueIndex < valueList.size()) {
            EntityValue value = valueList.get(valueIndex);
            boolean matches = condition.mapMatches(value);
            // logger.warn("TOREMOVE filter value [${value}] with condition [${condition}] include=${include}, matches=${matches}")
            // matched: if include is not true or false (default exclude) remove it
            // didn't match, if include is true remove it
            if ((matches && !include) || (!matches && include)) {
                valueList.remove(valueIndex);
            } else {
                valueIndex++;
            }
        }

        return this;
    }

    @Override public EntityList filterByLimit(Integer offset, Integer limit) {
        if (fromCache) return this.cloneList().filterByLimit(offset, limit);
        if (offset == null && limit == null) return this;
        if (offset == null) offset = 0;
        this.offset = offset;
        this.limit = limit;

        int vlSize = valueList.size();
        int toIndex = limit != null ? offset + limit : vlSize;
        if (toIndex > vlSize) toIndex = vlSize;
        ArrayList<EntityValue> newList = new ArrayList<>(limit != null && limit > 0 ? limit : (vlSize - offset));
        for (int i = offset; i < toIndex; i++) newList.add(valueList.get(i));
        valueList = newList;

        return this;
    }

    @Override public EntityList filterByLimit(String inputFieldsMapName, boolean alwaysPaginate) {
        if (fromCache) return this.cloneList().filterByLimit(inputFieldsMapName, alwaysPaginate);
        Map inf = inputFieldsMapName != null && inputFieldsMapName.length() > 0 ?
                (Map) getEfi().ecfi.getEci().contextStack.get(inputFieldsMapName) :
                getEfi().ecfi.getEci().contextStack;
        if (alwaysPaginate || inf.get("pageIndex") != null) {
            final Object pageIndexObj = inf.get("pageIndex");
            int pageIndex;
            if (pageIndexObj instanceof Number) { pageIndex = ((Number) pageIndexObj).intValue(); }
            else { pageIndex = Integer.parseInt(pageIndexObj.toString()); }

            final Object pageSizeObj = inf.get("pageSize");
            int pageSize;
            if (pageSizeObj != null) {
                if (pageSizeObj instanceof Number) { pageSize = ((Number) pageSizeObj).intValue(); }
                else { pageSize = Integer.parseInt(pageSizeObj.toString()); }
            } else {
                pageSize = 20;
            }

            int offset = pageIndex * pageSize;
            return filterByLimit(offset, pageSize);
        } else {
            return this;
        }

    }

    @Override public Integer getOffset() { return this.offset; }
    @Override public Integer getLimit() { return this.limit; }

    @Override public int getPageIndex() { return (offset != null ? offset : 0) / getPageSize(); }
    @Override public int getPageSize() { return limit != null ? limit : 20; }

    @Override public EntityList orderByFields(List<String> fieldNames) {
        if (fromCache) return this.cloneList().orderByFields(fieldNames);
        if (fieldNames != null && fieldNames.size() > 0) valueList.sort(new CollectionUtilities.MapOrderByComparator(fieldNames));
        return this;
    }
    @Override public void sort(Comparator<? super EntityValue> comparator) { valueList.sort(comparator); }

    @Override public int indexMatching(Map<String, Object> valueMap) {
        ListIterator<EntityValue> li = valueList.listIterator();
        int index = 0;
        while (li.hasNext()) {
            EntityValue ev = li.next();
            if (ev.mapMatches(valueMap)) return index;
            index++;
        }
        return -1;
    }

    @Override public void move(int fromIndex, int toIndex) {
        if (fromIndex == toIndex) return;

        EntityValue val = remove(fromIndex);
        if (toIndex > fromIndex) toIndex--;
        add(toIndex, val);
    }

    @Override public EntityList addIfMissing(EntityValue value) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache");
        if (!valueList.contains(value)) valueList.add(value);
        return this;
    }
    @Override public EntityList addAllIfMissing(EntityList el) {
        for (EntityValue value : el) addIfMissing(value);
        return this;
    }

    @Override public int writeXmlText(Writer writer, String prefix, int dependentLevels) {
        int recordsWritten = 0;
        for (EntityValue ev : this) recordsWritten += ev.writeXmlText(writer, prefix, dependentLevels);
        return recordsWritten;
    }

    @Override public @Nonnull Iterator<EntityValue> iterator() { return new EntityIterator(); }
    private class EntityIterator implements Iterator<EntityValue> {
        int curIndex = -1;
        boolean valueRemoved = false;
        @Override public boolean hasNext() { return (curIndex + 1) < valueList.size(); }
        @Override public EntityValue next() {
            if ((curIndex + 1) >= valueList.size()) throw new NoSuchElementException("Next is beyond end of list (index " + (curIndex + 1) + ", size " + valueList.size() + ")");
            curIndex++;
            valueRemoved = false;
            return valueList.get(curIndex);
        }
        @Override public void remove() {
            if (fromCache) throw new UnsupportedOperationException("Cannot modify EntityList from cache");
            if (curIndex == -1) throw new IllegalStateException("Cannot remove, next() has not been called");
            if (valueRemoved) throw new IllegalStateException("Cannot remove, next() has not been called since last remove");
            valueList.remove(curIndex);
            curIndex--;
            valueRemoved = true;
        }
    }

    @Override public List<Map<String, Object>> getPlainValueList(int dependentLevels) {
        List<Map<String, Object>> plainRelList = new ArrayList<>(valueList.size());
        for (EntityValue ev : valueList) plainRelList.add(ev.getPlainValueMap(dependentLevels));
        return plainRelList;
    }
    @Override public List<Map<String, Object>> getMasterValueList(String name) {
        List<Map<String, Object>> masterRelList = new ArrayList<>(valueList.size());
        for (EntityValue ev : valueList) masterRelList.add(ev.getMasterValueMap(name));
        return masterRelList;
    }
    @Override public ArrayList<Map<String, Object>> getValueMapList() {
        int elSize = valueList.size();
        ArrayList<Map<String, Object>> al = new ArrayList<>(elSize);
        for (int i = 0; i < elSize; i++) {
            EntityValue ev = valueList.get(i);
            Map<String, Object> evMap = ev.getMap();
            CollectionUtilities.removeNullsFromMap(evMap);
            al.add(evMap);
        }
        return al;
    }

    @Override public Object clone() { return this.cloneList(); }

    @Override public EntityList cloneList() {
        EntityListImpl newObj = new EntityListImpl(this.getEfi(), valueList.size());
        newObj.valueList.addAll(valueList);
        // NOTE: when cloning don't clone the fromCache value (normally when from cache will be cloned before filtering)
        return newObj;
    }
    public EntityListImpl deepCloneList() {
        EntityListImpl newObj = new EntityListImpl(this.getEfi(), valueList.size());
        int valueListSize = valueList.size();
        for (int i = 0; i < valueListSize; i++) {
            EntityValue ev = valueList.get(i);
            newObj.valueList.add(ev.cloneValue());
        }
        return newObj;
    }

    @Override public void setFromCache() {
        fromCache = true;
        for (EntityValue ev : valueList) if (ev instanceof EntityValueBase) ((EntityValueBase) ev).setFromCache();
    }

    @Override public boolean isFromCache() { return fromCache; }
    @Override public int size() { return valueList.size(); }
    @Override public boolean isEmpty() { return valueList.isEmpty(); }
    @Override public boolean contains(Object o) { return valueList.contains(o); }
    @Override public @Nonnull Object[] toArray() { return valueList.toArray(); }

    @SuppressWarnings("SuspiciousToArrayCall")
    @Override public @Nonnull <T> T[] toArray(@Nonnull T[] ts) { return valueList.toArray(ts); }

    @Override public boolean add(EntityValue e) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache");
        return e != null && valueList.add(e);
    }
    @Override public boolean remove(Object o) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache");
        return valueList.remove(o);
    }

    @Override public boolean containsAll(@Nonnull Collection<?> objects) { return valueList.containsAll(objects); }

    @Override public boolean addAll(@Nonnull Collection<? extends EntityValue> es) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache");
        return valueList.addAll(es);
    }
    @Override public boolean addAll(int i, @Nonnull Collection<? extends EntityValue> es) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache");
        return valueList.addAll(i, es);
    }

    @Override public boolean removeAll(@Nonnull Collection<?> objects) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache");
        return valueList.removeAll(objects);
    }
    @Override public boolean retainAll(@Nonnull Collection<?> objects) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache");
        return valueList.retainAll(objects);
    }
    @Override public void clear() {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache");
        valueList.clear();
    }

    @Override public EntityValue get(int i) { return valueList.get(i); }
    @Override public EntityValue set(int i, EntityValue e) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache");
        return valueList.set(i, e);
    }
    @Override public void add(int i, EntityValue e) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache");
        valueList.add(i, e);
    }
    @Override public EntityValue remove(int i) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache");
        return valueList.remove(i);
    }

    @Override public int indexOf(Object o) { return valueList.indexOf(o); }
    @Override public int lastIndexOf(Object o) { return valueList.lastIndexOf(o); }
    @Override public @Nonnull ListIterator<EntityValue> listIterator() { return valueList.listIterator(); }
    @Override public @Nonnull ListIterator<EntityValue> listIterator(int i) { return valueList.listIterator(i); }
    @Override public @Nonnull List<EntityValue> subList(int start, int end) { return valueList.subList(start, end); }
    @Override public String toString() { return valueList.toString(); }

    @SuppressWarnings("unused")
    public static class EmptyEntityList implements EntityList {
        public EmptyEntityList() { }
        @Override public void writeExternal(ObjectOutput out) throws IOException { }
        @Override public void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException { }
        @Override public EntityValue getFirst() { return null; }
        @Override public EntityList filterByDate(String fromDateName, String thruDateName, Timestamp moment) { return this; }
        @Override public EntityList filterByDate(String fromDateName, String thruDateName, Timestamp moment, boolean ignoreIfEmpty) { return this; }
        @Override public EntityList filterByAnd(Map<String, Object> fields) { return this; }
        @Override public EntityList filterByAnd(Map<String, Object> fields, Boolean include) { return this; }
        @Override public EntityList filterByAnd(Object... namesAndValues) { return this; }
        @Override public EntityList removeByAnd(Map<String, Object> fields) { return this; }
        @Override public EntityList filterByCondition(EntityCondition condition, Boolean include) { return this; }
        @Override public EntityList filter(Closure<Boolean> closure, Boolean include) { return this; }
        @Override public EntityValue find(Closure<Boolean> closure) { return null; }
        @Override public EntityList findAll(Closure<Boolean> closure) { return this; }
        @Override public EntityList filterByLimit(Integer offset, Integer limit) { this.offset = offset; this.limit = limit; return this; }
        @Override public EntityList filterByLimit(String inputFieldsMapName, boolean alwaysPaginate) { return this; }
        @Override public Integer getOffset() { return this.offset; }
        @Override public Integer getLimit() { return this.limit; }
        @Override public int getPageIndex() { return (offset != null ? offset : 0) / getPageSize(); }
        @Override public int getPageSize() { return limit != null ? limit : 20; }
        @Override public EntityList orderByFields(List<String> fieldNames) { return this; }
        @Override public int indexMatching(Map valueMap) { return -1; }
        @Override public void move(int fromIndex, int toIndex) { throw new IllegalArgumentException("EmptyEntityList does not support move"); }
        @Override public EntityList addIfMissing(EntityValue value) { throw new IllegalArgumentException("EmptyEntityList does not support add"); }
        @Override public EntityList addAllIfMissing(EntityList el) { throw new IllegalArgumentException("EmptyEntityList does not support add"); }
        @Override public @Nonnull Iterator<EntityValue> iterator() { return emptyIterator; }
        @Override public Object clone() { return this.cloneList(); }
        @Override public int writeXmlText(Writer writer, String prefix, int dependentLevels) { return 0; }
        @Override public List<Map<String, Object>> getPlainValueList(int dependentLevels) { return new ArrayList<>(); }
        @Override public List<Map<String, Object>> getMasterValueList(String name) { return new ArrayList<>(); }
        @Override public ArrayList<Map<String, Object>> getValueMapList() { return new ArrayList<>(); }
        @Override public EntityList cloneList() { return this; }
        @Override public void setFromCache() { }
        @Override public boolean isFromCache() { return false; }
        @Override public int size() { return 0; }
        @Override public boolean isEmpty() { return true; }
        @Override public boolean contains(Object o) { return false; }
        @SuppressWarnings("unchecked") @Override public @Nonnull Object[] toArray() { return new Object[0]; }
        @SuppressWarnings("unchecked") @Override public @Nonnull <T> T[] toArray(@Nonnull T[] ts) { return ((T[]) new EntityValue[0]); }
        @Override public boolean add(EntityValue e) { throw new IllegalArgumentException("EmptyEntityList does not support add"); }
        @Override public boolean remove(Object o) { return false; }
        @Override public boolean containsAll(@Nonnull Collection<?> objects) { return false; }
        @Override public boolean addAll(@Nonnull Collection<? extends EntityValue> es) { throw new IllegalArgumentException("EmptyEntityList does not support addAll"); }
        @Override public boolean addAll(int i, @Nonnull Collection<? extends EntityValue> es) { throw new IllegalArgumentException("EmptyEntityList does not support addAll"); }
        @Override public boolean removeAll(@Nonnull Collection<?> objects) { return false; }
        @Override public boolean retainAll(@Nonnull Collection<?> objects) { return false; }
        @Override public void clear() { }
        @Override public EntityValue get(int i) { return null; }
        @Override public EntityValue set(int i, EntityValue e) { throw new IllegalArgumentException("EmptyEntityList does not support set"); }
        @Override public void add(int i, EntityValue e) { throw new IllegalArgumentException("EmptyEntityList does not support add"); }
        @Override public EntityValue remove(int i) { return null; }
        @Override public int indexOf(Object o) { return -1; }
        @Override public int lastIndexOf(Object o) { return -1; }
        @Override public @Nonnull ListIterator<EntityValue> listIterator() { return emptyIterator; }
        @Override public @Nonnull ListIterator<EntityValue> listIterator(int i) { return emptyIterator; }
        @Override public @Nonnull List<EntityValue> subList(int start, int end) { return this; }
        @Override public String toString() { return "[]"; }

        public static ListIterator getEmptyIterator() { return emptyIterator; }
        private static final ListIterator<EntityValue> emptyIterator = new LinkedList<EntityValue>().listIterator();
        protected Integer offset = null;
        protected Integer limit = null;
    }
}
