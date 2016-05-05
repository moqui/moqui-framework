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

import org.moqui.BaseException;
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.impl.StupidJavaUtilities;
import org.moqui.impl.entity.EntityJavaUtil;

import java.io.Serializable;

public class ConditionField implements Serializable {
    String fieldName;
    String entityAlias = null;
    private String aliasEntityName = null;
    private int curHashCode;
    private transient EntityDefinition aliasEntityDefTransient = null;
    private final boolean hasAlias;

    public ConditionField(String fieldName) {
        if (fieldName == null) throw new BaseException("Empty fieldName not allowed");
        this.fieldName = fieldName.intern();
        curHashCode = fieldName.hashCode();
        hasAlias = false;
    }
    public ConditionField(String entityAlias, String fieldName, EntityDefinition aliasEntityDef) {
        if (fieldName == null) throw new BaseException("Empty fieldName not allowed");
        this.fieldName = fieldName.intern();
        if (entityAlias != null) {
            this.entityAlias = entityAlias.intern();
            aliasEntityDefTransient = aliasEntityDef;
            if (aliasEntityDef != null) {
                String entName = aliasEntityDef.getFullEntityName();
                aliasEntityName = entName.intern();
            }
            hasAlias = true;
        } else {
            hasAlias = false;
        }
        curHashCode = createHashCode();
    }

    public String getEntityAlias() { return entityAlias; }
    public String getFieldName() { return fieldName; }
    public String getAliasEntityName() { return aliasEntityName; }
    private EntityDefinition getAliasEntityDef(EntityDefinition otherEd) {
        if (aliasEntityDefTransient == null && aliasEntityName != null) {
            aliasEntityDefTransient = otherEd.getEfi().getEntityDefinition(aliasEntityName);
        }
        return aliasEntityDefTransient;
    }

    public String getColumnName(EntityDefinition ed) {
        StringBuilder colName = new StringBuilder();
        // NOTE: this could have issues with view-entities as member entities where they have functions/etc; we may
        // have to pass the prefix in to have it added inside functions/etc
        if (entityAlias != null) colName.append(entityAlias).append('.');
        if (aliasEntityName != null) {
            colName.append(getAliasEntityDef(ed).getColumnName(fieldName, false));
        } else {
            colName.append(ed.getColumnName(fieldName, false));
        }
        return colName.toString();
    }

    public EntityJavaUtil.FieldInfo getFieldInfo(EntityDefinition ed) {
        if (aliasEntityName != null) {
            return getAliasEntityDef(ed).getFieldInfo(fieldName);
        } else {
            return ed.getFieldInfo(fieldName);
        }
    }

    @Override
    public String toString() { return (entityAlias != null ? (entityAlias + ".") : "") + fieldName; }

    @Override
    public int hashCode() { return curHashCode; }
    private int createHashCode() {
        return (entityAlias != null ? entityAlias.hashCode() : 0) + (fieldName != null ? fieldName.hashCode() : 0) +
                (aliasEntityName != null ? aliasEntityName.hashCode() : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != this.getClass()) return false;
        ConditionField that = (ConditionField) o;
        return equalsConditionField(that);
    }
    public boolean equalsConditionField(ConditionField that) {
        if (that == null) return false;
        // both Strings are intern'ed so use != operator for object compare
        if (fieldName != that.fieldName) return false;
        if (hasAlias) {
            if (!that.hasAlias) return false;
            // both have aliases, so compare; note that we now know entityAlias is not null in both
            // both Strings are intern'ed so use != operator for object compare
            if (entityAlias != that.entityAlias) return false;
            if (aliasEntityName == null) {
                if (that.aliasEntityName != null) return false;
            } else {
                if (that.aliasEntityName == null) return false;
                // both Strings are intern'ed so use != operator for object compare
                if (aliasEntityName != that.aliasEntityName) return false;
            }
        } else {
            if (that.hasAlias) return false;
        }
        return true;
    }
}
