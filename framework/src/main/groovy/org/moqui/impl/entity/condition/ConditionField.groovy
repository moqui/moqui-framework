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
    String entityAlias = null
    String fieldName
    EntityDefinition aliasEntityDef = null
    String aliasEntityName = null
    protected int curHashCode

    ConditionField(String fieldName) {
        if (!fieldName) throw new BaseException("Empty fieldName not allowed")
        this.fieldName = fieldName.intern()
        curHashCode = createHashCode()
    }
    ConditionField(String entityAlias, String fieldName, EntityDefinition aliasEntityDef) {
        if (!fieldName) throw new BaseException("Empty fieldName not allowed")
        this.entityAlias = entityAlias.intern()
        this.fieldName = fieldName.intern()
        this.aliasEntityDef = aliasEntityDef
        // NOTE: this is already intern()'ed
        if (aliasEntityDef != null) aliasEntityName = aliasEntityDef.getFullEntityName()
        curHashCode = createHashCode()
    }

    String getColumnName(EntityDefinition ed) {
        StringBuilder colName = new StringBuilder()
        // NOTE: this could have issues with view-entities as member entities where they have functions/etc; we may
        // have to pass the prefix in to have it added inside functions/etc
        if (this.entityAlias) colName.append(this.entityAlias).append('.')
        if (this.aliasEntityDef) {
            colName.append(this.aliasEntityDef.getColumnName(this.fieldName, false))
        } else {
            colName.append(ed.getColumnName(this.fieldName, false))
        }
        return colName.toString()
    }

    EntityDefinition.FieldInfo getFieldInfo(EntityDefinition ed) {
        if (this.aliasEntityDef) {
            return this.aliasEntityDef.getFieldInfo(fieldName)
        } else {
            return ed.getFieldInfo(fieldName)
        }
    }

    @Override
    String toString() { return (entityAlias ? entityAlias+"." : "") + fieldName }

    @Override
    int hashCode() { return curHashCode }
    protected int createHashCode() {
        return (entityAlias ? entityAlias.hashCode() : 0) + (fieldName ? fieldName.hashCode() : 0) +
                (aliasEntityDef ? aliasEntityDef.hashCode() : 0)
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
