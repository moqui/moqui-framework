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
import org.moqui.BaseException
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.StupidJavaUtilities

@CompileStatic
class ConditionField {
    String entityAlias = (String) null
    String fieldName
    EntityDefinition aliasEntityDef = (EntityDefinition) null
    String aliasEntityName = (String) null
    protected int curHashCode

    ConditionField(String fieldName) {
        if (fieldName == null) throw new BaseException("Empty fieldName not allowed")
        this.fieldName = fieldName.intern()
        curHashCode = fieldName.hashCode()
    }
    ConditionField(String entityAlias, String fieldName, EntityDefinition aliasEntityDef) {
        if (fieldName == null) throw new BaseException("Empty fieldName not allowed")
        this.entityAlias = entityAlias != null ? entityAlias.intern() : null
        this.fieldName = fieldName.intern()
        this.aliasEntityDef = aliasEntityDef
        if (aliasEntityDef != null) {
            String entName = aliasEntityDef.getFullEntityName()
            aliasEntityName = entName != null ? entName.intern() : null
        }
        curHashCode = createHashCode()
    }

    String getColumnName(EntityDefinition ed) {
        StringBuilder colName = new StringBuilder()
        // NOTE: this could have issues with view-entities as member entities where they have functions/etc; we may
        // have to pass the prefix in to have it added inside functions/etc
        if (entityAlias != null) colName.append(entityAlias).append('.')
        if (aliasEntityDef != null) {
            colName.append(aliasEntityDef.getColumnName(fieldName, false))
        } else {
            colName.append(ed.getColumnName(fieldName, false))
        }
        return colName.toString()
    }

    EntityDefinition.FieldInfo getFieldInfo(EntityDefinition ed) {
        if (aliasEntityDef != null) {
            return aliasEntityDef.getFieldInfo(fieldName)
        } else {
            return ed.getFieldInfo(fieldName)
        }
    }

    @Override
    String toString() { return (entityAlias != null ? entityAlias + "." : "") + fieldName }

    @Override
    int hashCode() { return curHashCode }
    protected int createHashCode() {
        return (entityAlias != null ? entityAlias.hashCode() : 0) + (fieldName != null ? fieldName.hashCode() : 0) +
                (aliasEntityDef != null ? aliasEntityDef.hashCode() : 0)
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != this.getClass()) return false
        ConditionField that = (ConditionField) o
        return equalsConditionField(that)
    }
    boolean equalsConditionField(ConditionField that) {
        if (that == null) return false
        if (!StupidJavaUtilities.internedNonNullStringsEqual(this.fieldName, that.fieldName)) return false
        if (!StupidJavaUtilities.internedStringsEqual(this.entityAlias, that.entityAlias)) return false
        if (!StupidJavaUtilities.internedStringsEqual(this.aliasEntityName, that.aliasEntityName)) return false
        return true
    }
}
