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

import org.moqui.BaseArtifactException;
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.impl.entity.FieldInfo;
import org.moqui.util.MNode;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

public class ConditionAlias extends ConditionField implements Externalizable {
    private static final Class thisClass = ConditionAlias.class;

    String fieldName;
    String entityAlias = null;
    private String aliasEntityName = null;
    private transient EntityDefinition aliasEntityDefTransient = null;
    private int curHashCode;

    public ConditionAlias() { }
    public ConditionAlias(String entityAlias, String fieldName, EntityDefinition aliasEntityDef) {
        if (fieldName == null) throw new BaseArtifactException("Empty fieldName not allowed");
        if (entityAlias == null) throw new BaseArtifactException("Empty entityAlias not allowed");
        if (aliasEntityDef == null) throw new BaseArtifactException("Null aliasEntityDef not allowed");
        this.fieldName = fieldName.intern();
        this.entityAlias = entityAlias.intern();

        aliasEntityDefTransient = aliasEntityDef;
        String entName = aliasEntityDef.getFullEntityName();
        aliasEntityName = entName.intern();
        curHashCode = createHashCode();
    }

    public String getEntityAlias() { return entityAlias; }
    public String getFieldName() { return fieldName; }
    public String getAliasEntityName() { return aliasEntityName; }
    private EntityDefinition getAliasEntityDef(EntityDefinition otherEd) {
        if (aliasEntityDefTransient == null && aliasEntityName != null)
            aliasEntityDefTransient = otherEd.getEfi().getEntityDefinition(aliasEntityName);
        return aliasEntityDefTransient;
    }

    public String getColumnName(EntityDefinition ed) {
        StringBuilder colName = new StringBuilder();
        // NOTE: this could have issues with view-entities as member entities where they have functions/etc; we may
        // have to pass the prefix in to have it added inside functions/etc
        colName.append(entityAlias).append('.');
        EntityDefinition memberEd = getAliasEntityDef(ed);
        if (memberEd.isViewEntity) {
            MNode memberEntity = ed.getMemberEntityNode(entityAlias);
            if ("true".equals(memberEntity.attribute("sub-select"))) colName.append(memberEd.getFieldInfo(fieldName).columnName);
            else colName.append(memberEd.getColumnName(fieldName));
        } else {
            colName.append(memberEd.getColumnName(fieldName));
        }
        return colName.toString();
    }

    public FieldInfo getFieldInfo(EntityDefinition ed) {
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
        return fieldName.hashCode() + (entityAlias != null ? entityAlias.hashCode() : 0) +
                (aliasEntityName != null ? aliasEntityName.hashCode() : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false;
        ConditionAlias that = (ConditionAlias) o;
        // both Strings are intern'ed so use != operator for object compare
        if (fieldName != that.fieldName) return false;
        if (entityAlias != that.entityAlias) return false;
        if (aliasEntityName != that.aliasEntityName) return false;
        return true;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeUTF(fieldName);
        out.writeUTF(entityAlias);
        out.writeUTF(aliasEntityName);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        fieldName = in.readUTF().intern();
        entityAlias = in.readUTF().intern();
        aliasEntityName = in.readUTF().intern();
        curHashCode = createHashCode();
    }
}
