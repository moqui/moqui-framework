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
class FieldToFieldCondition implements EntityConditionImplBase {
    protected final ConditionField field
    protected final EntityCondition.ComparisonOperator operator
    protected final ConditionField toField
    protected boolean ignoreCase = false
    protected static final Class thisClass = FieldValueCondition.class
    protected int curHashCode;

    FieldToFieldCondition(ConditionField field, EntityCondition.ComparisonOperator operator, ConditionField toField) {
        this.field = field
        this.operator = operator ?: EQUALS
        this.toField = toField
        curHashCode = createHashCode()
    }

    @Override
    void makeSqlWhere(EntityQueryBuilder eqb) {
        StringBuilder sql = eqb.getSqlTopLevel()
        int typeValue = -1
        if (ignoreCase) {
            typeValue = field.getFieldInfo(eqb.getMainEd())?.typeValue ?: 1
            if (typeValue == 1) sql.append("UPPER(")
        }
        sql.append(field.getColumnName(eqb.getMainEd()))
        if (ignoreCase && typeValue == 1) sql.append(")")

        sql.append(' ').append(EntityConditionFactoryImpl.getComparisonOperatorString(operator)).append(' ')

        int toTypeValue = -1
        if (ignoreCase) {
            toTypeValue = toField.getFieldInfo(eqb.getMainEd())?.typeValue ?: 1
            if (toTypeValue == 1) sql.append("UPPER(")
        }
        sql.append(toField.getColumnName(eqb.getMainEd()))
        if (ignoreCase && toTypeValue == 1) sql.append(")")
    }

    @Override
    boolean mapMatches(Map<String, Object> map) {
        return EntityConditionFactoryImpl.compareByOperator(map.get(field.getFieldName()), operator, map.get(toField.getFieldName()))
    }
    @Override
    boolean mapMatchesAny(Map<String, Object> map) {
        return mapMatches(map)
    }

    @Override
    boolean populateMap(Map<String, Object> map) { return false }

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        // this will only be called for view-entity, so we'll either have a entityAlias or an aliased fieldName
        if (field.entityAlias) {
            entityAliasSet.add(field.entityAlias)
        } else {
            fieldAliasSet.add(field.fieldName)
        }
        if (toField.entityAlias) {
            entityAliasSet.add(toField.entityAlias)
        } else {
            fieldAliasSet.add(toField.fieldName)
        }
    }

    @Override
    EntityCondition ignoreCase() { ignoreCase = true; curHashCode++; return this }

    @Override
    String toString() {
        return field.toString() + " " + EntityConditionFactoryImpl.getComparisonOperatorString(operator) + " " + toField.toString()
    }

    @Override
    int hashCode() { return curHashCode }
    private int createHashCode() {
        return (field ? field.hashCode() : 0) + operator.hashCode() + (toField ? toField.hashCode() : 0) + (ignoreCase ? 1 : 0)
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false
        FieldToFieldCondition that = (FieldToFieldCondition) o
        if (!field.equalsConditionField(that.field)) return false
        // NOTE: for Java Enums the != is WAY faster than the .equals
        if (operator != that.operator) return false
        if (!toField.equalsConditionField(that.toField)) return false
        if (ignoreCase != that.ignoreCase) return false
        return true
    }
}
