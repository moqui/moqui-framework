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
package org.moqui.impl.entity

import groovy.transform.CompileStatic
import org.moqui.Moqui
import org.moqui.impl.context.ExecutionContextFactoryImpl

import java.sql.Timestamp

import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityException
import org.moqui.impl.StupidJavaUtilities.MapOrderByComparator

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class EntityListImpl implements EntityList {
    protected final static Logger logger = LoggerFactory.getLogger(EntityConditionFactoryImpl.class)

    protected transient EntityFacadeImpl efiTransient
    protected String tenantId

    protected ArrayList<EntityValue> valueList
    protected boolean fromCache = false
    protected Integer offset = null
    protected Integer limit = null

    /** Default constructor for deserialization ONLY. */
    EntityListImpl() { }

    EntityListImpl(EntityFacadeImpl efi) {
        this.efiTransient = efi
        tenantId = efi.getTenantId()
        valueList = new ArrayList<EntityValue>(30) // default size, at least enough for common pagination
    }
    EntityListImpl(EntityFacadeImpl efi, int initialCapacity) {
        this.efiTransient = efi
        tenantId = efi.getTenantId()
        valueList = new ArrayList<EntityValue>(initialCapacity)
    }

    @Override
    void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(tenantId.toCharArray())
        out.writeObject(valueList)
        // don't serialize fromCache, will default back to false which is fine for a copy
    }
    @Override
    void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
        tenantId = new String((char[]) objectInput.readObject())
        valueList = (ArrayList<EntityValue>) objectInput.readObject()
    }

    EntityFacadeImpl getEfi() {
        if (efiTransient == null) efiTransient = ((ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()).getEntityFacade(tenantId)
        return efiTransient
    }

    @Override
    EntityValue getFirst() { return valueList ? valueList.get(0) : null }

    @Override
    EntityList filterByDate(String fromDateName, String thruDateName, Timestamp moment) {
        if (fromCache) return this.cloneList().filterByDate(fromDateName, thruDateName, moment)

        // default to now
        long momentLong = moment ? moment.getTime() : System.currentTimeMillis()
        long momentDateLong = new java.sql.Date(momentLong).getTime()
        if (!fromDateName) fromDateName = "fromDate"
        if (!thruDateName) thruDateName = "thruDate"

        int valueIndex = 0
        while (valueIndex < valueList.size()) {
            EntityValue value = (EntityValue) valueList.get(valueIndex)
            Object fromDateObj = value.get(fromDateName)
            Object thruDateObj = value.get(thruDateName)

            Long fromDateLong
            Long thruDateLong

            if (fromDateObj instanceof Date) fromDateLong = ((Date) fromDateObj).getTime()
            else if (fromDateObj instanceof Long) fromDateLong = (Long) fromDateObj
            else fromDateLong = null

            if (thruDateObj instanceof Date) thruDateLong = ((Date) thruDateObj).getTime()
            else if (thruDateObj instanceof Long) thruDateLong = (Long) thruDateObj
            else thruDateLong = null

            if (fromDateObj instanceof java.sql.Date || thruDateObj instanceof java.sql.Date) {
                if (!((thruDateLong == null || thruDateLong >= momentDateLong) &&
                        (fromDateLong == null || fromDateLong <= momentDateLong))) {
                    valueList.remove(valueIndex)
                } else {
                    valueIndex++
                }
            } else {
                if (!((thruDateLong == null || thruDateLong >= momentLong) &&
                        (fromDateLong == null || fromDateLong <= momentLong))) {
                    valueList.remove(valueIndex)
                } else {
                    valueIndex++
                }
            }
        }

        return this
    }
    @Override
    EntityList filterByDate(String fromDateName, String thruDateName, Timestamp moment, boolean ignoreIfEmpty) {
        if (ignoreIfEmpty && moment == (Timestamp) null) return this
        return filterByDate(fromDateName, thruDateName, moment)
    }

    @Override
    EntityList filterByAnd(Map<String, Object> fields) { return filterByAnd(fields, true) }
    @Override
    EntityList filterByAnd(Map<String, Object> fields, Boolean include) {
        if (fromCache) return this.cloneList().filterByAnd(fields, include)
        if (include == null) include = true

        // iterate fields once, then use indexes within big loop
        int fieldsSize = fields.size()
        String[] names = new String[fieldsSize]
        Object[] values = new Object[fieldsSize]
        int fieldIndex = 0
        for (Map.Entry<String, Object> entry in fields.entrySet()) {
            names[fieldIndex] = entry.key
            values[fieldIndex] = entry.value
            fieldIndex++
        }

        int valueIndex = 0
        while (valueIndex < valueList.size()) {
            EntityValue value = (EntityValue) valueList.get(valueIndex)
            boolean matches = true
            for (int i = 0; i < fieldsSize; i++) if (value.get(names[i]) != values[i]) matches = false
            if ((matches && !include) || (!matches && include)) {
                valueList.remove(valueIndex)
            } else {
                valueIndex++
            }
        }
        return this
    }

    EntityList filter(Closure<Boolean> closure, Boolean include) {
        if (fromCache) return this.cloneList().filter(closure, include)
        int valueIndex = 0
        while (valueIndex < valueList.size()) {
            EntityValue value = (EntityValue) valueList.get(valueIndex)
            boolean matches = closure.call(value)
            if ((matches && !include) || (!matches && include)) {
                valueList.remove(valueIndex)
            } else {
                valueIndex++
            }
        }
        return this
    }
    EntityValue find(Closure<Boolean> closure) {
        int valueListSize = valueList.size()
        for (int i = 0; i < valueListSize; i++) {
            EntityValue value = (EntityValue) valueList.get(i)
            boolean matches = closure.call(value)
            if (matches) return value
        }
        return null
    }
    EntityList findAll(Closure<Boolean> closure) {
        EntityListImpl newList = new EntityListImpl(efi)
        int valueListSize = valueList.size()
        for (int i = 0; i < valueListSize; i++) {
            EntityValue value = (EntityValue) valueList.get(i)
            boolean matches = closure.call(value)
            if (matches) newList.add(value)
        }
        return newList
    }

    @Override
    EntityList removeByAnd(Map<String, Object> fields) { return filterByAnd(fields, false) }

    @Override
    EntityList filterByCondition(EntityCondition condition, Boolean include) {
        if (fromCache) return this.cloneList().filterByCondition(condition, include)
        if (include == null) include = true
        int valueIndex = 0
        while (valueIndex < valueList.size()) {
            EntityValue value = (EntityValue) valueList.get(valueIndex)
            boolean matches = condition.mapMatches(value)
            // logger.warn("TOREMOVE filter value [${value}] with condition [${condition}] include=${include}, matches=${matches}")
            // matched: if include is not true or false (default exclude) remove it
            // didn't match, if include is true remove it
            if ((matches && !include) || (!matches && include)) {
                valueList.remove(valueIndex)
            } else {
                valueIndex++
            }
        }
        return this
    }

    @Override
    EntityList filterByLimit(Integer offset, Integer limit) {
        if (fromCache) return this.cloneList().filterByLimit(offset, limit)
        if (offset == null && limit == null) return this
        if (offset == null) offset = 0
        this.offset = offset ?: 0
        this.limit = limit

        int vlSize = valueList.size()
        int toIndex = limit != null ? offset + limit : vlSize
        if (toIndex > vlSize) toIndex = vlSize
        ArrayList<EntityValue> newList = new ArrayList<EntityValue>(limit ?: (vlSize - offset))
        for (int i = offset; i < toIndex; i++) newList.add(valueList.get(i))

        return this
    }

    @Override
    EntityList filterByLimit(String inputFieldsMapName, boolean alwaysPaginate) {
        if (fromCache) return this.cloneList().filterByLimit(inputFieldsMapName, alwaysPaginate)
        Map inf = inputFieldsMapName ? (Map) efi.ecfi.executionContext.context[inputFieldsMapName] : efi.ecfi.executionContext.context
        if (alwaysPaginate || inf.get("pageIndex")) {
            int pageIndex = (inf.get("pageIndex") ?: 0) as int
            int pageSize = (inf.get("pageSize") ?: (this.limit ?: 20)) as int
            int offset = pageIndex * pageSize
            return filterByLimit(offset, pageSize)
        } else {
            return this
        }
    }

    @Override
    Integer getOffset() { return this.offset }
    @Override
    Integer getLimit() { return this.limit }
    @Override
    int getPageIndex() { return offset == null ? 0 : (offset/getPageSize()).intValue() }
    @Override
    int getPageSize() { return limit ?: 20 }

    @Override
    EntityList orderByFields(List<String> fieldNames) {
        if (fromCache) return this.cloneList().orderByFields(fieldNames)
        if (fieldNames != null && fieldNames.size() > 0) Collections.sort(valueList, new MapOrderByComparator(fieldNames))
        return this
    }

    @Override
    int indexMatching(Map valueMap) {
        ListIterator li = valueList.listIterator()
        int index = 0
        while (li.hasNext()) {
            EntityValue ev = li.next()
            if (ev.mapMatches(valueMap)) return index
            index++
        }
        return -1
    }

    @Override
    void move(int fromIndex, int toIndex) {
        if (fromIndex == toIndex) return
        EntityValue val = remove(fromIndex)
        if (toIndex > fromIndex) toIndex--
        add(toIndex, val)
    }

    @Override
    EntityList addIfMissing(EntityValue value) {
        if (!valueList.contains(value)) valueList.add(value)
        return this
    }
    @Override
    EntityList addAllIfMissing(EntityList el) {
        for (EntityValue value in el) addIfMissing(value)
        return this
    }

    @Override
    int writeXmlText(Writer writer, String prefix, int dependentLevels) {
        int recordsWritten = 0
        for (EntityValue ev in this) recordsWritten += ev.writeXmlText(writer, prefix, dependentLevels)
        return recordsWritten
    }

    @Override
    Iterator<EntityValue> iterator() { return valueList.iterator() }

    @Override
    List<Map<String, Object>> getPlainValueList(int dependentLevels) {
        List<Map<String, Object>> plainRelList = new ArrayList(valueList.size())
        for (EntityValue ev in valueList) plainRelList.add(((EntityValueBase) ev).getPlainValueMap(dependentLevels))
        return plainRelList
    }

    @Override
    List<Map<String, Object>> getMasterValueList(String name) {
        List<Map<String, Object>> masterRelList = new ArrayList(valueList.size())
        for (EntityValue ev in valueList) masterRelList.add(((EntityValueBase) ev).getMasterValueMap(name))
        return masterRelList
    }

    @Override
    Object clone() { return this.cloneList() }

    @Override
    EntityList cloneList() {
        EntityListImpl newObj = new EntityListImpl(this.efi, valueList.size())
        newObj.valueList.addAll(valueList)
        // NOTE: when cloning don't clone the fromCache value (normally when from cache will be cloned before filtering)
        return newObj
    }

    EntityListImpl deepCloneList() {
        EntityListImpl newObj = new EntityListImpl(this.efi, valueList.size())
        for (EntityValue ev in valueList) newObj.valueList.add(ev.cloneValue())
        return newObj
    }

    @Override
    void setFromCache() {
        fromCache = true
        for (EntityValue ev in valueList) if (ev instanceof EntityValueBase) ((EntityValueBase) ev).setFromCache()
    }
    @Override
    boolean isFromCache() { return fromCache }

    // ========== List Interface Methods ==========

    @Override
    int size() { return valueList.size() }

    @Override
    boolean isEmpty() { return valueList.isEmpty() }

    @Override
    boolean contains(Object o) { return valueList.contains(o) }

    @Override
    Object[] toArray() { return valueList.toArray() }

    @Override
    Object[] toArray(Object[] ts) { return valueList.toArray((EntityValue[]) ts) }

    @Override
    boolean add(EntityValue e) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache")
        if (e == null) return false
        return valueList.add(e)
    }

    @Override
    boolean remove(Object o) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache")
        return valueList.remove(o)
    }

    @Override
    boolean containsAll(Collection<?> objects) { return valueList.containsAll(objects) }

    @Override
    boolean addAll(Collection<? extends EntityValue> es) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache")
        return valueList.addAll(es)
    }

    @Override
    boolean addAll(int i, Collection<? extends EntityValue> es) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache")
        return valueList.addAll(i, es)
    }

    @Override
    boolean removeAll(Collection<?> objects) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache")
        return valueList.removeAll(objects)
    }

    @Override
    boolean retainAll(Collection<?> objects) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache")
        return valueList.retainAll(objects)
    }

    @Override
    void clear() {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache")
        valueList.clear()
    }

    @Override
    EntityValue get(int i) { return (EntityValue) valueList.get(i) }

    @Override
    EntityValue set(int i, EntityValue e) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache")
        return valueList.set(i, e)
    }

    @Override
    void add(int i, EntityValue e) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache")
        valueList.add(i, e)
    }

    @Override
    EntityValue remove(int i) {
        if (fromCache) throw new EntityException("Cannot modify EntityList from cache")
        return valueList.remove(i)
    }

    @Override
    int indexOf(Object o) { return valueList.indexOf(o) }

    @Override
    int lastIndexOf(Object o) { return valueList.lastIndexOf(o) }

    @Override
    ListIterator<EntityValue> listIterator() { return valueList.listIterator() }

    @Override
    ListIterator<EntityValue> listIterator(int i) { return valueList.listIterator(i) }

    @Override
    List<EntityValue> subList(int start, int end) { return valueList.subList(start, end) }

    @Override
    String toString() { valueList.toString() }

    @CompileStatic
    static class EmptyEntityList implements EntityList {
        static final ListIterator emptyIterator = new LinkedList().listIterator()

        protected Integer offset = null
        protected Integer limit = null

        EmptyEntityList() { }

        @Override
        void writeExternal(ObjectOutput out) throws IOException { }
        @Override
        void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException { }

        EntityValue getFirst() { return null }
        EntityList filterByDate(String fromDateName, String thruDateName, Timestamp moment) { return this }
        EntityList filterByDate(String fromDateName, String thruDateName, Timestamp moment, boolean ignoreIfEmpty) { return this }
        EntityList filterByAnd(Map<String, Object> fields) { return this }
        EntityList filterByAnd(Map<String, Object> fields, Boolean include) { return this }
        EntityList removeByAnd(Map<String, Object> fields) { return this }
        EntityList filterByCondition(EntityCondition condition, Boolean include) { return this }
        EntityList filter(Closure<Boolean> closure, Boolean include) { return this }
        EntityValue find(Closure<Boolean> closure) { return null }
        EntityList findAll(Closure<Boolean> closure) { return this }
        EntityList filterByLimit(Integer offset, Integer limit) {
            this.offset = offset
            this.limit = limit
            return this
        }
        EntityList filterByLimit(String inputFieldsMapName, boolean alwaysPaginate) { return this }
        Integer getOffset() { return this.offset }
        Integer getLimit() { return this.limit }
        int getPageIndex() { return offset == null ? 0 : (offset/getPageSize()).intValue() }
        int getPageSize() { return limit ?: 20 }
        EntityList orderByFields(List<String> fieldNames) { return this }
        int indexMatching(Map valueMap) { return -1 }
        void move(int fromIndex, int toIndex) { throw new IllegalArgumentException("EmptyEntityList does not support move") }
        EntityList addIfMissing(EntityValue value) { throw new IllegalArgumentException("EmptyEntityList does not support add") }
        EntityList addAllIfMissing(EntityList el) { throw new IllegalArgumentException("EmptyEntityList does not support add") }
        Iterator<EntityValue> iterator() { return emptyIterator }
        Object clone() { return this.cloneList() }
        int writeXmlText(Writer writer, String prefix, int dependentLevels) { return 0 }
        List<Map<String, Object>> getPlainValueList(int dependentLevels) { return [] }
        List<Map<String, Object>> getMasterValueList(String name) { return [] }

        EntityList cloneList() { return this }
        void setFromCache() { }
        boolean isFromCache() { return false }

        // ========== List Interface Methods ==========
        int size() { return 0 }
        boolean isEmpty() { return true }
        boolean contains(Object o) { return false }
        Object[] toArray() { return new Object[0] }
        Object[] toArray(Object[] ts) { return new Object[0] }
        boolean add(EntityValue e) { throw new IllegalArgumentException("EmptyEntityList does not support add") }
        boolean remove(Object o) { return false }
        boolean containsAll(Collection<?> objects) { return false }
        boolean addAll(Collection<? extends EntityValue> es) { throw new IllegalArgumentException("EmptyEntityList does not support addAll") }
        boolean addAll(int i, Collection<? extends EntityValue> es) { throw new IllegalArgumentException("EmptyEntityList does not support addAll") }
        boolean removeAll(Collection<?> objects) { return false }
        boolean retainAll(Collection<?> objects) { return false }
        void clear() { }
        EntityValue get(int i) { return null }
        EntityValue set(int i, EntityValue e) { throw new IllegalArgumentException("EmptyEntityList does not support set") }
        void add(int i, EntityValue e) { throw new IllegalArgumentException("EmptyEntityList does not support add") }
        EntityValue remove(int i) { return null }
        int indexOf(Object o) { return -1 }
        int lastIndexOf(Object o) { return -1 }
        ListIterator<EntityValue> listIterator() { return emptyIterator }
        ListIterator<EntityValue> listIterator(int i) { return emptyIterator }
        List<EntityValue> subList(int start, int end) { return this }
        String toString() { return "[]" }
    }
}
