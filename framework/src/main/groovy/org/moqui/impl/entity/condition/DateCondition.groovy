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
import org.moqui.entity.EntityCondition
import java.sql.Timestamp
import org.moqui.impl.entity.EntityConditionFactoryImpl
import org.moqui.impl.entity.EntityQueryBuilder

@CompileStatic
class DateCondition implements EntityConditionImplBase {
    protected String fromFieldName
    protected String thruFieldName
    protected Timestamp compareStamp
    private EntityConditionImplBase conditionInternal
    private int hashCodeInternal

    DateCondition(String fromFieldName, String thruFieldName, Timestamp compareStamp) {
        this.fromFieldName = fromFieldName ?: "fromDate"
        this.thruFieldName = thruFieldName ?: "thruDate"
        this.compareStamp = compareStamp
        conditionInternal = makeConditionInternal()
        hashCodeInternal = createHashCode()
    }

    @Override
    void makeSqlWhere(EntityQueryBuilder eqb) {
        conditionInternal.makeSqlWhere(eqb)
    }

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        fieldAliasSet.add(fromFieldName)
        fieldAliasSet.add(thruFieldName)
    }

    @Override
    boolean mapMatches(Map<String, Object> map) {
        return conditionInternal.mapMatches(map)
    }
    @Override
    boolean mapMatchesAny(Map<String, Object> map) {
        return conditionInternal.mapMatchesAny(map)
    }

    @Override
    boolean populateMap(Map<String, Object> map) { return false }

    @Override
    EntityCondition ignoreCase() { throw new IllegalArgumentException("Ignore case not supported for DateCondition.") }

    @Override
    String toString() {
        return conditionInternal.toString()
    }

    private EntityConditionImplBase makeConditionInternal() {
        ConditionField fromFieldCf = new ConditionField(fromFieldName)
        ConditionField thruFieldCf = new ConditionField(thruFieldName)
        return new ListCondition([
            new ListCondition([new FieldValueCondition(fromFieldCf, EQUALS, null),
                               new FieldValueCondition(fromFieldCf, LESS_THAN_EQUAL_TO, compareStamp)] as List<EntityConditionImplBase>,
                    EntityCondition.JoinOperator.OR),
            new ListCondition([new FieldValueCondition(thruFieldCf, EQUALS, null),
                               new FieldValueCondition(thruFieldCf, GREATER_THAN, compareStamp)] as List<EntityConditionImplBase>,
                    EntityCondition.JoinOperator.OR)
        ] as List<EntityConditionImplBase>, EntityCondition.JoinOperator.AND)
    }

    @Override
    int hashCode() { return hashCodeInternal }
    private int createHashCode() {
        return (compareStamp ? compareStamp.hashCode() : 0) + fromFieldName.hashCode() + thruFieldName.hashCode()
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != this.getClass()) return false
        DateCondition that = (DateCondition) o
        if ((Object) this.compareStamp == null && (Object) that.compareStamp != null) return false
        if ((Object) this.compareStamp != null) {
            if ((Object) that.compareStamp == null) {
                return false
            } else {
                if (!this.compareStamp.equals(that.compareStamp)) return false
            }
        }
        if (!fromFieldName.equals(that.fromFieldName)) return false
        if (!thruFieldName.equals(that.thruFieldName)) return false
        return true
    }
}
