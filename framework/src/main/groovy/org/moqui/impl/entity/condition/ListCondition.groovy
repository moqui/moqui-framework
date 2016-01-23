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
class ListCondition extends EntityConditionImplBase {
    protected final ArrayList<EntityConditionImplBase> conditionList = new ArrayList<EntityConditionImplBase>()
    protected final EntityCondition.JoinOperator operator
    protected Integer curHashCode = null
    protected static final Class thisClass = ListCondition.class

    ListCondition(EntityConditionFactoryImpl ecFactoryImpl,
            List<EntityConditionImplBase> conditionList, EntityCondition.JoinOperator operator) {
        super(ecFactoryImpl)
        this.operator = operator != null ? operator : AND
        if (conditionList) {
            if (conditionList instanceof RandomAccess) {
                // avoid creating an iterator if possible
                int listSize = conditionList.size()
                for (int i = 0; i < listSize; i++) {
                    EntityConditionImplBase cond = conditionList.get(i)
                    if (cond != null) this.conditionList.add(cond)
                }
            } else {
                Iterator<EntityConditionImplBase> conditionIter = conditionList.iterator()
                while (conditionIter.hasNext()) {
                    EntityConditionImplBase cond = conditionIter.next()
                    if (cond != null) this.conditionList.add(cond)
                }
            }
        }
    }

    void addCondition(EntityConditionImplBase condition) {
        if (condition != null) conditionList.add(condition)
        curHashCode = null
    }
    void addConditions(ListCondition listCond) {
        ArrayList<EntityConditionImplBase> condList = listCond.getConditionList()
        int condListSize = condList.size()
        for (int i = 0; i < condListSize; i++) addCondition(condList.get(i))
    }

    EntityCondition.JoinOperator getOperator() { return operator }
    ArrayList<EntityConditionImplBase> getConditionList() { return conditionList }

    @Override
    void makeSqlWhere(EntityQueryBuilder eqb) {
        if (!this.conditionList) return

        StringBuilder sql = eqb.getSqlTopLevel()
        String joinOpString = EntityConditionFactoryImpl.getJoinOperatorString(this.operator)
        sql.append('(')
        int clSize = conditionList.size()
        for (int i = 0; i < clSize; i++) {
            EntityConditionImplBase condition = conditionList.get(i)
            if (i > 0) sql.append(' ').append(joinOpString).append(' ')
            condition.makeSqlWhere(eqb)
        }
        sql.append(')')
    }

    @Override
    boolean mapMatches(Map<String, ?> map) {
        int clSize = conditionList.size()
        for (int i = 0; i < clSize; i++) {
            EntityConditionImplBase condition = conditionList.get(i)
            boolean conditionMatches = condition.mapMatches(map)
            if (conditionMatches && this.operator == OR) return true
            if (!conditionMatches && this.operator == AND) return false
        }
        // if we got here it means that it's an OR with no trues, or an AND with no falses
        return (this.operator == AND)
    }

    @Override
    boolean populateMap(Map<String, ?> map) {
        if (operator != AND) return false
        int clSize = conditionList.size()
        for (int i = 0; i < clSize; i++) {
            EntityConditionImplBase condition = conditionList.get(i)
            if (!condition.populateMap(map)) return false
        }
        return true
    }

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        int clSize = conditionList.size()
        for (int i = 0; i < clSize; i++) {
            EntityConditionImplBase condition = conditionList.get(i)
            condition.getAllAliases(entityAliasSet, fieldAliasSet)
        }
    }

    @Override
    EntityCondition ignoreCase() { throw new IllegalArgumentException("Ignore case not supported for this type of condition.") }

    @Override
    String toString() {
        StringBuilder sb = new StringBuilder()
        int clSize = conditionList.size()
        for (int i = 0; i < clSize; i++) {
            EntityConditionImplBase condition = conditionList.get(i)
            if (sb.length() > 0) sb.append(' ').append(EntityConditionFactoryImpl.getJoinOperatorString(this.operator)).append(' ')
            sb.append('(').append(condition.toString()).append(')')
        }
        return sb.toString()
    }

    @Override
    int hashCode() {
        if (curHashCode != null) return curHashCode
        curHashCode = createHashCode()
        return curHashCode
    }
    protected int createHashCode() {
        return (conditionList ? conditionList.hashCode() : 0) + operator.hashCode()
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false
        ListCondition that = (ListCondition) o
        // NOTE: for Java Enums the != is WAY faster than the .equals
        if (this.operator != that.operator) return false
        if (!this.conditionList.equals(that.conditionList)) return false
        return true
    }
}
