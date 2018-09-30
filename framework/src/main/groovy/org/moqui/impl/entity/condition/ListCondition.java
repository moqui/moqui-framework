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
package org.moqui.impl.entity.condition;

import org.moqui.entity.EntityException;
import org.moqui.impl.entity.EntityConditionFactoryImpl;
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.impl.entity.EntityQueryBuilder;
import org.moqui.entity.EntityCondition;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.*;

public class ListCondition implements EntityConditionImplBase {
    private ArrayList<EntityConditionImplBase> conditionList = new ArrayList<>();
    protected JoinOperator operator;
    private int conditionListSize = 0;
    private int curHashCode;
    private static final Class thisClass = ListCondition.class;

    public ListCondition(List<EntityConditionImplBase> conditionList, JoinOperator operator) {
        this.operator = operator != null ? operator : AND;
        if (conditionList != null) {
            conditionListSize = conditionList.size();
            if (conditionListSize > 0) {
                if (conditionList instanceof RandomAccess) {
                    // avoid creating an iterator if possible
                    int listSize = conditionList.size();
                    for (int i = 0; i < listSize; i++) {
                        EntityConditionImplBase cond = conditionList.get(i);
                        if (cond != null) this.conditionList.add(cond);
                    }
                } else {
                    Iterator<EntityConditionImplBase> conditionIter = conditionList.iterator();
                    while (conditionIter.hasNext()) {
                        EntityConditionImplBase cond = conditionIter.next();
                        if (cond != null) this.conditionList.add(cond);
                    }
                }
            }
        }

        curHashCode = createHashCode();
    }

    public void addCondition(EntityConditionImplBase condition) {
        if (condition != null) conditionList.add(condition);
        curHashCode = createHashCode();
        conditionListSize = conditionList.size();
    }
    public void addConditions(ArrayList<EntityConditionImplBase> condList) {
        int condListSize = condList != null ? condList.size() : 0;
        if (condListSize == 0) return;
        for (int i = 0; i < condListSize; i++) addCondition(condList.get(i));
        curHashCode = createHashCode();
        conditionListSize = conditionList.size();
    }
    public void addConditions(ListCondition listCond) { addConditions(listCond.getConditionList()); }

    public JoinOperator getOperator() { return operator; }
    public ArrayList<EntityConditionImplBase> getConditionList() { return conditionList; }

    @SuppressWarnings("MismatchedQueryAndUpdateOfStringBuilder")
    @Override
    public void makeSqlWhere(EntityQueryBuilder eqb, EntityDefinition subMemberEd) {
        if (conditionListSize == 0) return;

        StringBuilder sql = eqb.sqlTopLevel;
        String joinOpString = EntityConditionFactoryImpl.getJoinOperatorString(this.operator);
        if (conditionListSize > 1) sql.append('(');
        for (int i = 0; i < conditionListSize; i++) {
            EntityConditionImplBase condition = conditionList.get(i);
            if (i > 0) sql.append(' ').append(joinOpString).append(' ');
            condition.makeSqlWhere(eqb, subMemberEd);
        }
        if (conditionListSize > 1) sql.append(')');
    }

    @Override
    public boolean mapMatches(Map<String, Object> map) {
        for (int i = 0; i < conditionListSize; i++) {
            EntityConditionImplBase condition = conditionList.get(i);
            boolean conditionMatches = condition.mapMatches(map);
            if (conditionMatches && this.operator == OR) return true;
            if (!conditionMatches && this.operator == AND) return false;
        }
        // if we got here it means that it's an OR with no trues, or an AND with no falses
        return (this.operator == AND);
    }
    @Override
    public boolean mapMatchesAny(Map<String, Object> map) {
        for (int i = 0; i < conditionListSize; i++) {
            EntityConditionImplBase condition = conditionList.get(i);
            boolean conditionMatches = condition.mapMatchesAny(map);
            if (conditionMatches) return true;
        }
        return false;
    }
    @Override
    public boolean mapKeysNotContained(Map<String, Object> map) {
        for (int i = 0; i < conditionListSize; i++) {
            EntityConditionImplBase condition = conditionList.get(i);
            boolean notContained = condition.mapKeysNotContained(map);
            if (!notContained) return false;
        }
        // if we got here it means that it's an OR with no trues, or an AND with no falses
        return true;
    }

    @Override
    public boolean populateMap(Map<String, Object> map) {
        if (operator != AND) return false;
        for (int i = 0; i < conditionListSize; i++) {
            EntityConditionImplBase condition = conditionList.get(i);
            if (!condition.populateMap(map)) return false;
        }
        return true;
    }

    @Override
    public void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        for (int i = 0; i < conditionListSize; i++) {
            EntityConditionImplBase condition = conditionList.get(i);
            condition.getAllAliases(entityAliasSet, fieldAliasSet);
        }
    }
    @Override
    public EntityConditionImplBase filter(String entityAlias, EntityDefinition mainEd) {
        ArrayList<EntityConditionImplBase> filteredList = new ArrayList<>(conditionList.size());
        for (int i = 0; i < conditionListSize; i++) {
            EntityConditionImplBase curCond = conditionList.get(i);
            EntityConditionImplBase filterCond = curCond.filter(entityAlias, mainEd);
            if (filterCond != null) filteredList.add(filterCond);
        }
        int filteredSize = filteredList.size();
        if (filteredSize == conditionListSize) return this;
        if (filteredSize == 0) return null;
        // keep OR conditions together: return all if entityAlias is null (top-level where) or null if not (sub-select where)
        if (operator == OR) {
            if (entityAlias == null) return this;
            return null;
        } else {
            if (filteredSize == 1) return filteredList.get(0);
            return new ListCondition(filteredList, operator);
        }
    }

    @Override
    public EntityCondition ignoreCase() { throw new EntityException("Ignore case not supported for this type of condition."); }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conditionListSize; i++) {
            EntityConditionImplBase condition = conditionList.get(i);
            if (sb.length() > 0) sb.append(' ').append(EntityConditionFactoryImpl.getJoinOperatorString(this.operator)).append(' ');
            sb.append('(').append(condition.toString()).append(')');
        }
        return sb.toString();
    }

    @Override public int hashCode() { return curHashCode; }
    private int createHashCode() { return (conditionList != null ? conditionList.hashCode() : 0) + operator.hashCode(); }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false;
        ListCondition that = (ListCondition) o;
        // NOTE: for Java Enums the != is WAY faster than the .equals
        return this.operator == that.operator && this.conditionList.equals(that.conditionList);
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(conditionList);
        out.writeObject(operator.name().toCharArray());
    }
    @Override
    @SuppressWarnings("unchecked")
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        conditionList = (ArrayList<EntityConditionImplBase>) in.readObject();
        operator = JoinOperator.valueOf(new String((char[]) in.readObject()));
        curHashCode = createHashCode();
        conditionListSize = conditionList != null ? conditionList.size() : 0;
    }
}
