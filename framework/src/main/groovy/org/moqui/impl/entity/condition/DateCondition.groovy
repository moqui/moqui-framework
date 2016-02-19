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
class DateCondition extends EntityConditionImplBase {
    protected String fromFieldName
    protected String thruFieldName
    protected Timestamp compareStamp

    DateCondition(EntityConditionFactoryImpl ecFactoryImpl,
            String fromFieldName, String thruFieldName, Timestamp compareStamp) {
        super(ecFactoryImpl)
        this.fromFieldName = fromFieldName ?: "fromDate"
        this.thruFieldName = thruFieldName ?: "thruDate"
        this.compareStamp = compareStamp
    }

    @Override
    void makeSqlWhere(EntityQueryBuilder eqb) {
        this.makeCondition().makeSqlWhere(eqb)
    }

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        fieldAliasSet.add(fromFieldName)
        fieldAliasSet.add(thruFieldName)
    }

    @Override
    boolean mapMatches(Map<String, Object> map) {
        return this.makeCondition().mapMatches(map)
    }

    @Override
    boolean populateMap(Map<String, Object> map) { return false }

    @Override
    EntityCondition ignoreCase() { throw new IllegalArgumentException("Ignore case not supported for this type of condition.") }

    @Override
    String toString() {
        return this.makeCondition().toString()
    }

    protected EntityConditionImplBase makeCondition() {
        return (EntityConditionImplBase) ecFactoryImpl.makeCondition(
            ecFactoryImpl.makeCondition(
                ecFactoryImpl.makeCondition(thruFieldName, EQUALS, null),
                EntityCondition.JoinOperator.OR,
                ecFactoryImpl.makeCondition(thruFieldName, GREATER_THAN, compareStamp)
            ),
            EntityCondition.JoinOperator.AND,
            ecFactoryImpl.makeCondition(
                ecFactoryImpl.makeCondition(fromFieldName, EQUALS, null),
                EntityCondition.JoinOperator.OR,
                ecFactoryImpl.makeCondition(fromFieldName, LESS_THAN_EQUAL_TO, compareStamp)
            )
        )
    }

    @Override
    int hashCode() {
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
        if (!this.fromFieldName.equals(that.fromFieldName)) return false
        if (!this.thruFieldName.equals(that.thruFieldName)) return false
        return true
    }
}
