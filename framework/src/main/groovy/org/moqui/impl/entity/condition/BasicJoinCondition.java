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
import org.moqui.entity.EntityException;
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.impl.entity.EntityQueryBuilder;
import org.moqui.impl.entity.EntityConditionFactoryImpl;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Map;
import java.util.Set;

public class BasicJoinCondition implements EntityConditionImplBase {
    private static final Class thisClass = BasicJoinCondition.class;
    private EntityConditionImplBase lhsInternal;
    protected JoinOperator operator;
    private EntityConditionImplBase rhsInternal;
    private int curHashCode;

    public BasicJoinCondition(EntityConditionImplBase lhs, JoinOperator operator, EntityConditionImplBase rhs) {
        this.lhsInternal = lhs;
        this.operator = operator != null ? operator : AND;
        this.rhsInternal = rhs;
        curHashCode = createHashCode();
    }

    public JoinOperator getOperator() { return operator; }
    public EntityConditionImplBase getLhs() { return lhsInternal; }
    public EntityConditionImplBase getRhs() { return rhsInternal; }

    @Override
    @SuppressWarnings("MismatchedQueryAndUpdateOfStringBuilder")
    public void makeSqlWhere(EntityQueryBuilder eqb, EntityDefinition subMemberEd) {
        StringBuilder sql = eqb.sqlTopLevel;
        sql.append('(');
        lhsInternal.makeSqlWhere(eqb, subMemberEd);
        sql.append(' ').append(EntityConditionFactoryImpl.getJoinOperatorString(this.operator)).append(' ');
        rhsInternal.makeSqlWhere(eqb, subMemberEd);
        sql.append(')');
    }

    @Override
    public boolean mapMatches(Map<String, Object> map) {
        boolean lhsMatches = lhsInternal.mapMatches(map);

        // handle cases where we don't need to evaluate rhs
        if (lhsMatches && operator == OR) return true;
        if (!lhsMatches && operator == AND) return false;

        // handle opposite cases since we know cases above aren't true (ie if OR then lhs=false, if AND then lhs=true
        // if rhs then result is true whether AND or OR
        // if !rhs then result is false whether AND or OR
        return rhsInternal.mapMatches(map);
    }
    @Override
    public boolean mapMatchesAny(Map<String, Object> map) {
        return lhsInternal.mapMatchesAny(map) || rhsInternal.mapMatchesAny(map);
    }
    @Override
    public boolean mapKeysNotContained(Map<String, Object> map) {
        return lhsInternal.mapKeysNotContained(map) && rhsInternal.mapKeysNotContained(map);
    }

    @Override
    public boolean populateMap(Map<String, Object> map) {
        return operator == AND && lhsInternal.populateMap(map) && rhsInternal.populateMap(map);
    }

    @Override
    public void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        lhsInternal.getAllAliases(entityAliasSet, fieldAliasSet);
        rhsInternal.getAllAliases(entityAliasSet, fieldAliasSet);
    }
    @Override
    public EntityConditionImplBase filter(String entityAlias, EntityDefinition mainEd) {
        EntityConditionImplBase filterLhs = lhsInternal.filter(entityAlias, mainEd);
        EntityConditionImplBase filterRhs = rhsInternal.filter(entityAlias, mainEd);
        if (filterLhs != null) {
            if (filterRhs != null) return this;
            else return filterLhs;
        } else {
            return filterRhs;
        }
    }

    @Override
    public EntityCondition ignoreCase() { throw new EntityException("Ignore case not supported for BasicJoinCondition"); }

    @Override
    public String toString() {
        // general SQL where clause style text with values included
        return "(" + lhsInternal.toString() + ") " + EntityConditionFactoryImpl.getJoinOperatorString(this.operator) + " (" + rhsInternal.toString() + ")";
    }

    @Override
    public int hashCode() { return curHashCode; }
    private int createHashCode() {
        return (lhsInternal != null ? lhsInternal.hashCode() : 0) + operator.hashCode() + (rhsInternal != null ? rhsInternal.hashCode() : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false;
        BasicJoinCondition that = (BasicJoinCondition) o;
        if (!this.lhsInternal.equals(that.lhsInternal)) return false;
        // NOTE: for Java Enums the != is WAY faster than the .equals
        return this.operator == that.operator && this.rhsInternal.equals(that.rhsInternal);
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(lhsInternal);
        out.writeUTF(operator.name());
        out.writeObject(rhsInternal);
    }
    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        lhsInternal = (EntityConditionImplBase) in.readObject();
        operator = JoinOperator.valueOf(in.readUTF());
        rhsInternal = (EntityConditionImplBase) in.readObject();
        curHashCode = createHashCode();
    }
}
