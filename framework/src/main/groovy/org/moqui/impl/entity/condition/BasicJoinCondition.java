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

import org.moqui.entity.EntityCondition;
import org.moqui.impl.entity.EntityQueryBuilder;
import org.moqui.impl.entity.EntityConditionFactoryImpl;

import java.util.Map;
import java.util.Set;

public class BasicJoinCondition extends EntityConditionImplBase {
    protected EntityConditionImplBase lhs;
    protected EntityCondition.JoinOperator operator;
    protected EntityConditionImplBase rhs;
    protected static final Class thisClass = BasicJoinCondition.class;

    public BasicJoinCondition(EntityConditionFactoryImpl ecFactoryImpl,
            EntityConditionImplBase lhs, EntityCondition.JoinOperator operator, EntityConditionImplBase rhs) {
        super(ecFactoryImpl);
        this.lhs = lhs;
        this.operator = operator != null ? operator : AND;
        this.rhs = rhs;
    }

    public EntityCondition.JoinOperator getOperator() { return operator; }
    public EntityConditionImplBase getLhs() { return lhs; }
    public EntityConditionImplBase getRhs() { return rhs; }

    @Override
    public void makeSqlWhere(EntityQueryBuilder eqb) {
        StringBuilder sql = eqb.getSqlTopLevel();
        sql.append('(');
        this.lhs.makeSqlWhere(eqb);
        sql.append(' ').append(EntityConditionFactoryImpl.getJoinOperatorString(this.operator)).append(' ');
        this.rhs.makeSqlWhere(eqb);
        sql.append(')');
    }

    @Override
    public boolean mapMatches(Map<String, Object> map) {
        boolean lhsMatches = this.lhs.mapMatches(map);

        // handle cases where we don't need to evaluate rhs
        if (lhsMatches && operator == OR) return true;
        if (!lhsMatches && operator == AND) return false;

        // handle opposite cases since we know cases above aren't true (ie if OR then lhs=false, if AND then lhs=true
        // if rhs then result is true whether AND or OR
        // if !rhs then result is false whether AND or OR
        return this.rhs.mapMatches(map);
    }

    @Override
    public boolean populateMap(Map<String, Object> map) {
        if (operator != AND) return false;
        return lhs.populateMap(map) && rhs.populateMap(map);
    }

    public void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        lhs.getAllAliases(entityAliasSet, fieldAliasSet);
        rhs.getAllAliases(entityAliasSet, fieldAliasSet);
    }

    @Override
    public EntityCondition ignoreCase() { throw new IllegalArgumentException("Ignore case not supported for this type of condition."); }

    @Override
    public String toString() {
        // general SQL where clause style text with values included
        return "(" + lhs.toString() + ") " + EntityConditionFactoryImpl.getJoinOperatorString(this.operator) + " (" + rhs.toString() + ")";
    }

    @Override
    public int hashCode() {
        return (lhs != null ? lhs.hashCode() : 0) + operator.hashCode() + (rhs != null ? rhs.hashCode() : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false;
        BasicJoinCondition that = (BasicJoinCondition) o;
        if (!this.lhs.equals(that.lhs)) return false;
        // NOTE: for Java Enums the != is WAY faster than the .equals
        if (this.operator != that.operator) return false;
        if (!this.rhs.equals(that.rhs)) return false;
        return true;
    }
}
