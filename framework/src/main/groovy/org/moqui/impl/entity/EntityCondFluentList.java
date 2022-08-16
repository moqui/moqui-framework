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

import org.moqui.entity.EntityCondition;
import org.moqui.entity.EntityConditionFluent;
import org.moqui.impl.entity.condition.EntityConditionImplBase;
import org.moqui.impl.entity.condition.ListCondition;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.sql.Timestamp;
import java.util.Map;

public class EntityCondFluentList implements EntityConditionFluent {
    protected final ListCondition listCondition;
    protected final EntityConditionFluent parent;

    public EntityCondFluentList(EntityConditionFluent parent, JoinOperator joinOp) {
        this.parent = parent;
        listCondition = new ListCondition(null, joinOp != null ? joinOp : JoinOperator.AND);
    }

    @Override public EntityConditionFluent or() { return new EntityCondFluentList(this, JoinOperator.OR); }
    @Override public EntityConditionFluent and() { return new EntityCondFluentList(this, JoinOperator.AND); }
    @Override public EntityConditionFluent parent() { return parent; }

    @Override
    public EntityConditionFluent or(EntityCondition... conditions) {
        if (conditions.length == 0) return this;
        if (conditions.length == 1) {
            // small optimization for length one case
            listCondition.addCondition((EntityConditionImplBase) conditions[0]);
        } else {
            for (int i = 0; i < conditions.length; i++)
                listCondition.addCondition((EntityConditionImplBase) conditions[i]);
        }
        return this;
    }

    @Override
    public EntityConditionFluent and(EntityCondition... conditions) {
        return null;
    }

    @Override
    public EntityConditionFluent equals(String fieldName, Object value) {
        return null;
    }

    @Override
    public EntityConditionFluent compare(String fieldName, ComparisonOperator operator, Object value) {
        return null;
    }

    @Override
    public EntityConditionFluent compare(String fieldName, ComparisonOperator operator, Object value, boolean ignoreCase, boolean orNull, boolean ignoreIfEmpty) {
        return null;
    }

    @Override
    public EntityConditionFluent compare(String fieldName, String operator, Object value) {
        return null;
    }

    @Override
    public EntityConditionFluent compare(String fieldName, String operator, Object value, boolean ignoreCase, boolean orNull, boolean ignoreIfEmpty) {
        return null;
    }

    @Override
    public EntityConditionFluent compareToField(String fieldName, ComparisonOperator operator, String toFieldName) {
        return null;
    }

    @Override
    public EntityConditionFluent compareToField(String fieldName, ComparisonOperator operator, String toFieldName, boolean ignoreCase, boolean orNull) {
        return null;
    }

    @Override
    public EntityConditionFluent equals(Map<String, Object> fields) {
        return null;
    }

    @Override
    public EntityConditionFluent compare(Map<String, Object> fieldMap, ComparisonOperator comparisonOperator, JoinOperator joinOperator) {
        return null;
    }

    @Override
    public EntityConditionFluent effectiveDate(String fromFieldName, String thruFieldName, Timestamp compareStamp) {
        return null;
    }

    @Override
    public EntityConditionFluent rangeInclusive(String fieldName, Object fromInclusive, Object toInclusive) {
        return null;
    }

    @Override
    public EntityConditionFluent rangeExclusive(String fieldName, Object fromInclusive, Object toExclusive) {
        return null;
    }

    @Override
    public EntityConditionFluent range(String fieldName, Object from, boolean fromInclusive, Object to, boolean toInclusive) {
        return null;
    }

    @Override
    public EntityConditionFluent where(String whereString) {
        return null;
    }

    @Override
    public EntityConditionFluent condition(EntityCondition condition) {
        return null;
    }

    // Externalizable Methods

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {

    }
    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {

    }

    // EntityCondition Methods

    @Override
    public boolean mapMatches(Map<String, Object> map) {
        return false;
    }

    @Override
    public boolean mapMatchesAny(Map<String, Object> map) {
        return false;
    }

    @Override
    public boolean mapKeysNotContained(Map<String, Object> map) {
        return false;
    }

    @Override
    public boolean populateMap(Map<String, Object> map) {
        return false;
    }

    @Override
    public EntityCondition ignoreCase() {
        return null;
    }
}
