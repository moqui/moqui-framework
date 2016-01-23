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
import org.moqui.impl.entity.EntityQueryBuilder.EntityConditionParameter
import org.moqui.impl.entity.EntityConditionFactoryImpl
import org.moqui.impl.entity.EntityQueryBuilder

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class FieldValueCondition extends EntityConditionImplBase {
    protected final static Logger logger = LoggerFactory.getLogger(FieldValueCondition.class)

    protected final ConditionField field
    protected final EntityCondition.ComparisonOperator operator
    protected Object value
    protected boolean ignoreCase = false
    protected Integer curHashCode = null
    protected static final Class thisClass = FieldValueCondition.class

    FieldValueCondition(EntityConditionFactoryImpl ecFactoryImpl,
            ConditionField field, EntityCondition.ComparisonOperator operator, Object value) {
        super(ecFactoryImpl)
        this.field = field
        this.value = value

        // default to EQUALS
        EntityCondition.ComparisonOperator tempOp = operator != null ? operator : EQUALS
        // if EQUALS and we have a Collection value the IN operator is implied, similar with NOT_EQUAL
        if ((tempOp == EQUALS || tempOp == NOT_EQUAL) && value instanceof Collection) tempOp = tempOp == EQUALS ? IN : NOT_IN
        this.operator = tempOp
    }

    EntityCondition.ComparisonOperator getOperator() { return operator }
    String getFieldName() { return field.fieldName }
    Object getValue() { return value }
    boolean getIgnoreCase() { return ignoreCase }

    @Override
    void makeSqlWhere(EntityQueryBuilder eqb) {
        StringBuilder sql = eqb.getSqlTopLevel()
        int typeValue = -1
        if (ignoreCase) {
            typeValue = field.getFieldInfo(eqb.getMainEd())?.typeValue ?: 1
            if (typeValue == 1) sql.append("UPPER(")
        }

        sql.append(field.getColumnName(eqb.getMainEd()))
        if (ignoreCase && typeValue == 1) sql.append(')')
        sql.append(' ')

        boolean valueDone = false
        if (value == null) {
            if (operator == EQUALS || operator == LIKE || operator == IN || operator == BETWEEN) {
                sql.append(" IS NULL")
                valueDone = true
            } else if (operator == NOT_EQUAL || operator == NOT_LIKE || operator == NOT_IN || operator == NOT_BETWEEN) {
                sql.append(" IS NOT NULL")
                valueDone = true
            }
        } else if (value instanceof Collection && ((Collection) value).isEmpty()) {
            if (operator == IN) {
                sql.append(" 1 = 2 ")
                valueDone = true
            } else if (operator == NOT_IN) {
                sql.append(" 1 = 1 ")
                valueDone = true
            }
        }
        if (operator == IS_NULL || operator == IS_NOT_NULL) {
            sql.append(EntityConditionFactoryImpl.getComparisonOperatorString(operator))
            valueDone = true
        }
        if (!valueDone) {
            sql.append(EntityConditionFactoryImpl.getComparisonOperatorString(operator))
            if (operator == IN || operator == NOT_IN) {
                if (value instanceof CharSequence) {
                    String valueStr = value.toString()
                    if (valueStr.contains(",")) value = valueStr.split(",").collect()
                }
                if (value instanceof Collection) {
                    sql.append(" (")
                    boolean isFirst = true
                    for (Object curValue in value) {
                        if (isFirst) isFirst = false else sql.append(", ")
                        sql.append("?")
                        if (ignoreCase && (curValue instanceof CharSequence)) curValue = curValue.toString().toUpperCase()
                        eqb.getParameters().add(new EntityConditionParameter(field.getFieldInfo(eqb.mainEntityDefinition), curValue, eqb))
                    }
                    sql.append(')')
                } else {
                    if (ignoreCase && (value instanceof CharSequence)) value = value.toString().toUpperCase()
                    sql.append(" (?)")
                    eqb.getParameters().add(new EntityConditionParameter(field.getFieldInfo(eqb.mainEntityDefinition), value, eqb))
                }
            } else if ((operator == BETWEEN || operator == NOT_BETWEEN) && value instanceof Collection &&
                    ((Collection) value).size() == 2) {
                sql.append(" ? AND ?")
                Iterator iterator = ((Collection) value).iterator()
                Object value1 = iterator.next()
                if (ignoreCase && (value1 instanceof CharSequence)) value1 = value1.toString().toUpperCase()
                Object value2 = iterator.next()
                if (ignoreCase && (value2 instanceof CharSequence)) value2 = value2.toString().toUpperCase()
                eqb.getParameters().add(new EntityConditionParameter(field.getFieldInfo(eqb.mainEntityDefinition), value1, eqb))
                eqb.getParameters().add(new EntityConditionParameter(field.getFieldInfo(eqb.mainEntityDefinition), value2, eqb))
            } else {
                if (ignoreCase && (value instanceof CharSequence)) value = value.toString().toUpperCase()
                sql.append(" ?")
                eqb.getParameters().add(new EntityConditionParameter(field.getFieldInfo(eqb.mainEntityDefinition), value, eqb))
            }
        }
    }

    @Override
    boolean mapMatches(Map<String, ?> map) { return EntityConditionFactoryImpl.compareByOperator(map.get(field.fieldName), operator, value) }

    @Override
    boolean populateMap(Map<String, ?> map) {
        if (operator != EQUALS || ignoreCase || field.entityAlias) return false
        map.put(field.fieldName, value)
        return true
    }

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        // this will only be called for view-entity, so we'll either have a entityAlias or an aliased fieldName
        if (field.entityAlias) {
            entityAliasSet.add(field.entityAlias)
        } else {
            fieldAliasSet.add(field.fieldName)
        }
    }

    @Override
    EntityCondition ignoreCase() { this.ignoreCase = true; curHashCode = null; return this }

    @Override
    String toString() {
        return field.toString() + " " + EntityConditionFactoryImpl.getComparisonOperatorString(this.operator) + " " + (value as String)
    }

    @Override
    int hashCode() {
        if (curHashCode == null) curHashCode = createHashCode()
        return curHashCode
    }
    protected int createHashCode() {
        return (field ? field.hashCode() : 0) + operator.hashCode() + (value ? value.hashCode() : 0) + (ignoreCase ? 1 : 0)
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false
        FieldValueCondition that = (FieldValueCondition) o
        if (!field.equalsConditionField(that.field)) return false
        if (value == null && that.value != null) return false
        if (value != null) {
            if (that.value == null) {
                return false
            } else {
                if (!value.equals(that.value)) return false
            }
        }
        if (operator != that.operator) return false
        if (ignoreCase != that.ignoreCase) return false
        return true
    }
}
