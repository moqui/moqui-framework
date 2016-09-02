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
import org.moqui.impl.entity.EntityJavaUtil;
import org.moqui.impl.entity.EntityJavaUtil.EntityConditionParameter;
import org.moqui.impl.entity.EntityConditionFactoryImpl;
import org.moqui.impl.entity.EntityQueryBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.*;

public class FieldValueCondition implements EntityConditionImplBase, Externalizable {
    protected final static Logger logger = LoggerFactory.getLogger(FieldValueCondition.class);
    private static final Class thisClass = FieldValueCondition.class;

    protected ConditionField field;
    protected ComparisonOperator operator;
    protected Object value;
    protected boolean ignoreCase = false;
    private int curHashCode;

    public FieldValueCondition() { }
    public FieldValueCondition(ConditionField field, ComparisonOperator operator, Object value) {
        this.field = field;
        this.value = value;

        // default to EQUALS
        ComparisonOperator tempOp = operator != null ? operator : EQUALS;
        // if EQUALS and we have a Collection value the IN operator is implied, similar with NOT_EQUAL
        if (value instanceof Collection) {
            if (tempOp == EQUALS) tempOp = IN;
            else if (tempOp == NOT_EQUAL) tempOp = NOT_IN;
        }
        this.operator = tempOp;

        curHashCode = createHashCode();
    }

    public ComparisonOperator getOperator() { return operator; }
    public String getFieldName() { return field.fieldName; }
    public Object getValue() { return value; }
    public boolean getIgnoreCase() { return ignoreCase; }

    @Override
    public void makeSqlWhere(EntityQueryBuilder eqb) {
        @SuppressWarnings("MismatchedQueryAndUpdateOfStringBuilder")
        StringBuilder sql = eqb.getSqlTopLevel();
        boolean valueDone = false;
        EntityDefinition mainEd = eqb.getMainEd();
        EntityJavaUtil.FieldInfo fi = field.getFieldInfo(mainEd);
        if (fi == null) throw new EntityException("Could not find field " + field.fieldName + " in entity " + eqb.getMainEd().getFullEntityName());

        if (value instanceof Collection && ((Collection) value).isEmpty()) {
            if (operator == IN) {
                sql.append(" 1 = 2 ");
                valueDone = true;
            } else if (operator == NOT_IN) {
                sql.append(" 1 = 1 ");
                valueDone = true;
            }
        } else {
            if (ignoreCase && fi.typeValue == 1) sql.append("UPPER(");
            sql.append(field.getColumnName(mainEd));
            if (ignoreCase && fi.typeValue == 1) sql.append(')');
            sql.append(' ');

            if (value == null) {
                if (operator == EQUALS || operator == LIKE || operator == IN || operator == BETWEEN) {
                    sql.append(" IS NULL");
                    valueDone = true;
                } else if (operator == NOT_EQUAL || operator == NOT_LIKE || operator == NOT_IN || operator == NOT_BETWEEN) {
                    sql.append(" IS NOT NULL");
                    valueDone = true;
                }
            }
        }
        if (operator == IS_NULL || operator == IS_NOT_NULL) {
            sql.append(EntityConditionFactoryImpl.getComparisonOperatorString(operator));
            valueDone = true;
        }
        if (!valueDone) {
            sql.append(EntityConditionFactoryImpl.getComparisonOperatorString(operator));
            if (operator == IN || operator == NOT_IN) {
                if (value instanceof CharSequence) {
                    String valueStr = value.toString();
                    if (valueStr.contains(",")) value = Arrays.asList(valueStr.split(","));
                }
                if (value instanceof Collection) {
                    sql.append(" (");
                    boolean isFirst = true;
                    for (Object curValue : (Collection) value) {
                        if (isFirst) isFirst = false; else sql.append(", ");
                        sql.append("?");
                        if (ignoreCase && (curValue instanceof CharSequence)) curValue = curValue.toString().toUpperCase();
                        eqb.getParameters().add(new EntityConditionParameter(fi, curValue, eqb));
                    }
                    sql.append(')');
                } else {
                    if (ignoreCase && (value instanceof CharSequence)) value = value.toString().toUpperCase();
                    sql.append(" (?)");
                    eqb.getParameters().add(new EntityConditionParameter(fi, value, eqb));
                }
            } else if ((operator == BETWEEN || operator == NOT_BETWEEN) && value instanceof Collection &&
                    ((Collection) value).size() == 2) {
                sql.append(" ? AND ?");
                Iterator iterator = ((Collection) value).iterator();
                Object value1 = iterator.next();
                if (ignoreCase && (value1 instanceof CharSequence)) value1 = value1.toString().toUpperCase();
                Object value2 = iterator.next();
                if (ignoreCase && (value2 instanceof CharSequence)) value2 = value2.toString().toUpperCase();
                eqb.getParameters().add(new EntityConditionParameter(fi, value1, eqb));
                eqb.getParameters().add(new EntityConditionParameter(fi, value2, eqb));
            } else {
                if (ignoreCase && (value instanceof CharSequence)) value = value.toString().toUpperCase();
                sql.append(" ?");
                eqb.getParameters().add(new EntityConditionParameter(fi, value, eqb));
            }
        }
    }

    @Override
    public boolean mapMatches(Map<String, Object> map) { return EntityConditionFactoryImpl.compareByOperator(map.get(field.fieldName), operator, value); }
    @Override
    public boolean mapMatchesAny(Map<String, Object> map) { return mapMatches(map); }

    @Override
    public boolean populateMap(Map<String, Object> map) {
        if (operator != EQUALS || ignoreCase || field instanceof ConditionAlias) return false;
        map.put(field.fieldName, value);
        return true;
    }

    public void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        // this will only be called for view-entity, so we'll either have a entityAlias or an aliased fieldName
        if (field instanceof ConditionAlias) {
            entityAliasSet.add(((ConditionAlias) field).entityAlias);
        } else {
            fieldAliasSet.add(field.fieldName);
        }
    }

    @Override
    public EntityCondition ignoreCase() { this.ignoreCase = true; curHashCode++; return this; }

    @Override
    public String toString() {
        return field.toString() + " " + EntityConditionFactoryImpl.getComparisonOperatorString(this.operator) + " " +
                (value != null ? value.toString() + " (" + value.getClass().getName() + ")" : "null");
    }

    @Override
    public int hashCode() { return curHashCode; }
    private int createHashCode() {
        return (field != null ? field.hashCode() : 0) + operator.hashCode() + (value != null ? value.hashCode() : 0) + (ignoreCase ? 1 : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false;
        FieldValueCondition that = (FieldValueCondition) o;
        if (!field.equals(that.field)) return false;
        if (value != null) {
            if (!value.equals(that.value)) return false;
        } else {
            if (that.value != null) return false;
        }
        return operator == that.operator && ignoreCase == that.ignoreCase;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        field.writeExternal(out);
        // NOTE: found that the serializer in Hazelcast is REALLY slow with writeUTF(), uses String.chatAt() in a for loop, crazy
        out.writeObject(operator.name().toCharArray());
        out.writeObject(value);
        out.writeBoolean(ignoreCase);
    }
    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        field = new ConditionField();
        field.readExternal(in);
        operator = ComparisonOperator.valueOf(new String((char[]) in.readObject()));
        value = in.readObject();
        ignoreCase = in.readBoolean();
        curHashCode = createHashCode();
    }
}
