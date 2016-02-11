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
import org.moqui.impl.entity.EntityQueryBuilder
import org.moqui.impl.entity.EntityConditionFactoryImpl

@CompileStatic
class BasicJoinCondition extends EntityConditionImplBase {
    protected EntityConditionImplBase lhs
    protected EntityCondition.JoinOperator operator
    protected EntityConditionImplBase rhs
    protected static final Class thisClass = BasicJoinCondition.class

    BasicJoinCondition(EntityConditionFactoryImpl ecFactoryImpl,
            EntityConditionImplBase lhs, EntityCondition.JoinOperator operator, EntityConditionImplBase rhs) {
        super(ecFactoryImpl)
        this.lhs = lhs
        this.operator = operator ?: AND
        this.rhs = rhs
    }

    EntityCondition.JoinOperator getOperator() { return operator }
    EntityConditionImplBase getLhs() { return lhs }
    EntityConditionImplBase getRhs() { return rhs }

    @Override
    void makeSqlWhere(EntityQueryBuilder eqb) {
        StringBuilder sql = eqb.getSqlTopLevel()
        sql.append('(')
        this.lhs.makeSqlWhere(eqb)
        sql.append(' ').append(EntityConditionFactoryImpl.getJoinOperatorString(this.operator)).append(' ')
        this.rhs.makeSqlWhere(eqb)
        sql.append(')')
    }

    @Override
    boolean mapMatches(Map<String, ?> map) {
        boolean lhsMatches = this.lhs.mapMatches(map)

        // handle cases where we don't need to evaluate rhs
        if (lhsMatches && operator == OR) return true
        if (!lhsMatches && operator == AND) return false

        // handle opposite cases since we know cases above aren't true (ie if OR then lhs=false, if AND then lhs=true
        // if rhs then result is true whether AND or OR
        // if !rhs then result is false whether AND or OR
        return this.rhs.mapMatches(map)
    }

    @Override
    boolean populateMap(Map<String, ?> map) {
        if (operator != AND) return false
        return lhs.populateMap(map) && rhs.populateMap(map)
    }

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        lhs.getAllAliases(entityAliasSet, fieldAliasSet)
        rhs.getAllAliases(entityAliasSet, fieldAliasSet)
    }

    @Override
    EntityCondition ignoreCase() { throw new IllegalArgumentException("Ignore case not supported for this type of condition.") }

    @Override
    String toString() {
        // general SQL where clause style text with values included
        return "(" + lhs.toString() + ") " + EntityConditionFactoryImpl.getJoinOperatorString(this.operator) + " (" + rhs.toString() + ")"
    }

    @Override
    int hashCode() {
        return (lhs ? lhs.hashCode() : 0) + operator.hashCode() + (rhs ? rhs.hashCode() : 0)
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false
        BasicJoinCondition that = (BasicJoinCondition) o
        if (!this.lhs.equals(that.lhs)) return false
        // NOTE: for Java Enums the != is WAY faster than the .equals
        if (this.operator != that.operator) return false
        if (!this.rhs.equals(that.rhs)) return false
        return true
    }
}
