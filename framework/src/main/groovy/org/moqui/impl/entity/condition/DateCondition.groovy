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
import org.moqui.entity.EntityException
import org.moqui.impl.entity.EntityDefinition

import java.sql.Timestamp
import org.moqui.impl.entity.EntityQueryBuilder

@CompileStatic
class DateCondition implements EntityConditionImplBase, Externalizable {
    protected String fromFieldName
    protected String thruFieldName
    protected Timestamp compareStamp
    private EntityConditionImplBase conditionInternal
    private int hashCodeInternal

    DateCondition(String fromFieldName, String thruFieldName, Timestamp compareStamp) {
        this.fromFieldName = fromFieldName ?: "fromDate"
        this.thruFieldName = thruFieldName ?: "thruDate"
        if (compareStamp == (Timestamp) null) compareStamp = new Timestamp(System.currentTimeMillis())
        this.compareStamp = compareStamp
        conditionInternal = makeConditionInternal()
        hashCodeInternal = createHashCode()
    }

    @Override void makeSqlWhere(EntityQueryBuilder eqb, EntityDefinition subMemberEd) { conditionInternal.makeSqlWhere(eqb, subMemberEd) }

    @Override
    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) {
        fieldAliasSet.add(fromFieldName)
        fieldAliasSet.add(thruFieldName)
    }
    @Override EntityConditionImplBase filter(String entityAlias, EntityDefinition mainEd) { return conditionInternal.filter(entityAlias, mainEd) }

    @Override boolean mapMatches(Map<String, Object> map) { return conditionInternal.mapMatches(map) }
    @Override boolean mapMatchesAny(Map<String, Object> map) { return conditionInternal.mapMatchesAny(map) }
    @Override boolean mapKeysNotContained(Map<String, Object> map) { return conditionInternal.mapKeysNotContained(map) }

    @Override boolean populateMap(Map<String, Object> map) { return false }

    @Override EntityCondition ignoreCase() { throw new EntityException("Ignore case not supported for DateCondition.") }

    @Override String toString() { return conditionInternal.toString() }

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

    @Override int hashCode() { return hashCodeInternal }
    private int createHashCode() {
        return compareStamp.hashCode() + fromFieldName.hashCode() + thruFieldName.hashCode()
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != this.getClass()) return false
        DateCondition that = (DateCondition) o
        if (!this.compareStamp.equals(that.compareStamp)) return false
        if (!fromFieldName.equals(that.fromFieldName)) return false
        if (!thruFieldName.equals(that.thruFieldName)) return false
        return true
    }

    @Override
    void writeExternal(ObjectOutput out) throws IOException {
        out.writeUTF(fromFieldName);
        out.writeUTF(thruFieldName);
        out.writeLong(compareStamp.getTime());
    }
    @Override
    void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
        fromFieldName = objectInput.readUTF();
        thruFieldName = objectInput.readUTF();
        compareStamp = new Timestamp(objectInput.readLong());
        hashCodeInternal = createHashCode();
        conditionInternal = makeConditionInternal();
    }
}
