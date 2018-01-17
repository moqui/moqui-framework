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

import java.io.*;

public class ConditionField implements Externalizable {
    private static final Class thisClass = ConditionField.class;
    String fieldName;
    private int curHashCode;
    private FieldInfo fieldInfo = null;

    public ConditionField() { }
    public ConditionField(String fieldName) {
        if (fieldName == null) throw new BaseArtifactException("Empty fieldName not allowed");
        this.fieldName = fieldName.intern();
        curHashCode = this.fieldName.hashCode();
    }
    public ConditionField(FieldInfo fi) {
        if (fi == null) throw new BaseArtifactException("FieldInfo required");
        fieldInfo = fi;
        // fi.name is interned in makeFieldInfo()
        fieldName = fi.name;
        curHashCode = fieldName.hashCode();
    }

    public String getFieldName() { return fieldName; }
    public String getColumnName(EntityDefinition ed) {
        if (fieldInfo != null && fieldInfo.ed.fullEntityName.equals(ed.fullEntityName)) return fieldInfo.getFullColumnName();
        return ed.getColumnName(fieldName);
    }
    public FieldInfo getFieldInfo(EntityDefinition ed) {
        if (fieldInfo != null && fieldInfo.ed.fullEntityName.equals(ed.fullEntityName)) return fieldInfo;
        return ed.getFieldInfo(fieldName);
    }

    @Override
    public String toString() { return fieldName; }

    @Override
    public int hashCode() { return curHashCode; }

    @Override
    public boolean equals(Object o) {
        if (o == null) return false;
        // because of reuse from EntityDefinition this may be the same object, so check that first
        if (this == o) return true;
        if (o.getClass() != thisClass) return false;
        ConditionField that = (ConditionField) o;
        // intern'ed String to use == operator
        return fieldName == that.fieldName;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeObject(fieldName.toCharArray());
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        fieldName = new String((char[]) in.readObject()).intern();
        curHashCode = fieldName.hashCode();
    }
}
