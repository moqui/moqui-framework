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
package org.moqui.impl.entity.condition

import groovy.transform.CompileStatic
import org.moqui.impl.entity.EntityConditionFactoryImpl
import org.moqui.impl.entity.EntityQueryBuilder
import org.moqui.entity.EntityCondition

@CompileStatic
class MapCondition implements EntityConditionImplBase {
    protected Map<String, Object> fieldMap
    protected EntityCondition.ComparisonOperator comparisonOperator
    protected EntityCondition.JoinOperator joinOperator
    protected boolean ignoreCase = false

    protected ListCondition internalCond = null
    protected int curHashCode
    protected static final Class thisClass = MapCondition.class

    protected int fieldsSize
    protected String[] names
    protected Object[] values

    MapCondition(Map<String, Object> fieldMap, EntityCondition.ComparisonOperator comparisonOperator,
            EntityCondition.JoinOperator joinOperator) {
        this.fieldMap = fieldMap != null ? fieldMap : new HashMap<String, Object>()
        this.comparisonOperator = comparisonOperator ?: EQUALS
        this.joinOperator = joinOperator ?: AND
        derivedValues()
    }

    void derivedValues() {
        fieldsSize = fieldMap.size()
        names = new String[fieldsSize]
        values = new Object[fieldsSize]
        int fieldIndex = 0
        for (Map.Entry<String, Object> entry in fieldMap.entrySet()) {
            names[fieldIndex] = entry.key
            values[fieldIndex] = entry.value
            fieldIndex++
        }
        curHashCode = createHashCode()
    }

    Map<String, Object> getFieldMap() { return fieldMap }
    EntityCondition.ComparisonOperator getComparisonOperator() { return comparisonOperator }
    EntityCondition.JoinOperator getJoinOperator() { return joinOperator }
    boolean getIgnoreCase() { return ignoreCase }

    @Override
    void makeSqlWhere(EntityQueryBuilder eqb) {
        this.makeCondition().makeSqlWhere(eqb)
    }

    @Override
    boolean mapMatches(Map<String, Object> map) {
        // do this directly instead of going through condition, faster
        // return this.makeCondition().mapMatches(map)

        for (int i = 0; i < fieldsSize; i++) {
            boolean conditionMatches = EntityConditionFactoryImpl.compareByOperator(map.get(names[i]),
                    comparisonOperator, values[i])
            if (conditionMatches && joinOperator == OR) return true
            if (!conditionMatches && joinOperator == AND) return false
        }

        // if we got here it means that it's an OR with no true, or an AND with no false
        return (joinOperator == AND)
    }
    @Override
    boolean mapMatchesAny(Map<String, Object> map) {
        for (int i = 0; i < fieldsSize; i++) {
            boolean conditionMatches = EntityConditionFactoryImpl.compareByOperator(map.get(names[i]),
                    comparisonOperator, values[i])
            if (conditionMatches) return true
        }
        return false
    }

    @Override
    boolean populateMap(Map<String, Object> map) {
        if (joinOperator != AND || comparisonOperator != EQUALS || ignoreCase) return false
        map.putAll(fieldMap)
        return true
    }

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        for (int i = 0; i < fieldsSize; i++) fieldAliasSet.add(names[i])
    }

    @Override
    EntityCondition ignoreCase() { this.ignoreCase = true; curHashCode++; return this }

    @Override
    String toString() {
        return this.makeCondition().toString()
        /* might want to do something like this at some point, but above is probably better for now
        StringBuilder sb = new StringBuilder()
        for (Map.Entry fieldEntry in this.fieldMap.entrySet()) {
            if (sb.length() > 0) {
                sb.append(' ')
                sb.append(EntityConditionFactoryImpl.getJoinOperatorString(this.joinOperator))
                sb.append(' ')
            }
            sb.append(fieldEntry.getKey())
            sb.append(' ')
            sb.append(EntityConditionFactoryImpl.getComparisonOperatorString(this.comparisonOperator))
            sb.append(' ')
            sb.append(fieldEntry.getValue())
        }
        return sb.toString()
        */
    }

    ListCondition makeCondition() {
        if (internalCond != null) return internalCond

        ArrayList conditionList = new ArrayList()
        for (int i = 0; i < fieldsSize; i++) {
            EntityConditionImplBase newCondition = new FieldValueCondition(new ConditionField(names[i]), comparisonOperator, values[i])
            if (ignoreCase) newCondition.ignoreCase()
            conditionList.add(newCondition)
        }

        internalCond = new ListCondition(conditionList, joinOperator)
        return internalCond
    }
    void addToConditionList(List<EntityConditionImplBase> condList) {
        for (int i = 0; i < fieldsSize; i++) {
            EntityConditionImplBase newCondition = new FieldValueCondition(new ConditionField(names[i]), comparisonOperator, values[i])
            if (ignoreCase) newCondition.ignoreCase()
            condList.add(newCondition)
        }
    }

    @Override
    int hashCode() { return curHashCode }
    protected int createHashCode() {
        return (fieldMap ? fieldMap.hashCode() : 0) + comparisonOperator.hashCode() + joinOperator.hashCode() +
                (ignoreCase ? 1 : 0)
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false
        MapCondition that = (MapCondition) o
        if (!fieldMap.equals(that.fieldMap)) return false
        if (!comparisonOperator.equals(that.comparisonOperator)) return false
        if (!joinOperator.equals(that.joinOperator)) return false
        if (ignoreCase != that.ignoreCase) return false
        return true
    }

    @Override
    void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(fieldMap)
        out.writeUTF(comparisonOperator.name())
        out.writeUTF(joinOperator.name())
        out.writeBoolean(ignoreCase)
    }
    @Override
    void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
        fieldMap = (Map<String, Object>) objectInput.readObject()
        comparisonOperator = EntityCondition.ComparisonOperator.valueOf(objectInput.readUTF())
        joinOperator = EntityCondition.JoinOperator.valueOf(objectInput.readUTF())
        ignoreCase = objectInput.readBoolean()
        derivedValues()
    }
}
