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

import java.io.*;
import java.util.concurrent.locks.Condition;

public class ConditionField implements Externalizable {
    private static final Class thisClass = ConditionField.class;
    String fieldName;

    public ConditionField() { }
    public ConditionField(String fieldName) {
        if (fieldName == null) throw new BaseException("Empty fieldName not allowed");
        this.fieldName = fieldName.intern();
    }

    public String getFieldName() { return fieldName; }

    public String getColumnName(EntityDefinition ed) {
        return ed.getColumnName(fieldName, false);
    }

    public EntityJavaUtil.FieldInfo getFieldInfo(EntityDefinition ed) {
        return ed.getFieldInfo(fieldName);
    }

    @Override
    public String toString() { return fieldName; }

    @Override
    public int hashCode() { return fieldName.hashCode(); }

    @Override
    public boolean equals(Object o) {
        if (o == null || o.getClass() != thisClass) return false;
        ConditionField that = (ConditionField) o;
        // intern'ed String to use == operator
        return fieldName == that.fieldName;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        out.writeUTF(fieldName);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        fieldName = in.readUTF().intern();
    }
}
