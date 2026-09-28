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
package org.moqui.impl.entity;

import org.moqui.util.MNode;

import java.util.ArrayList;

/**
 * Column identifiers for the H2 overlay. {@link FieldInfo#columnName} is the primary database's
 * name (Postgres leaves {@code VALUE} alone). H2 2 rejects that reserved word, so overlay SQL
 * starts from the raw name and applies the {@code h2} database node's {@code name-replace} list.
 */
public final class OverlayColumnNames {
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private OverlayColumnNames() { }

    public static boolean active() { return Boolean.TRUE.equals(ACTIVE.get()); }

    public static void setActive(boolean on) {
        if (on) ACTIVE.set(Boolean.TRUE);
        else ACTIVE.remove();
    }

    /** Raw column name before any database's name-replace: the column-name attribute, or the underscored field name. */
    public static String rawName(FieldInfo fi) {
        String attr = fi.fieldNode != null ? fi.fieldNode.attribute("column-name") : null;
        if (attr != null && !attr.isEmpty()) return attr;
        return EntityJavaUtil.camelCaseToUnderscored(fi.name);
    }

    /** Apply one database node's name-replace list. A name that is already replaced is left alone. */
    public static String apply(MNode databaseNode, String rawName) {
        if (rawName == null) return null;
        String name = rawName;
        if (databaseNode == null) return name;
        ArrayList<MNode> replaces = databaseNode.children("name-replace");
        if (replaces == null) return name;
        for (int i = 0; i < replaces.size(); i++) {
            MNode node = replaces.get(i);
            String original = node.attribute("original");
            if (original != null && name.equalsIgnoreCase(original)) {
                String replace = node.attribute("replace");
                if (replace != null && !replace.isEmpty()) name = replace;
            }
        }
        return name;
    }

    /** H2 column identifier for an overlay table or an overlay find. */
    public static String column(FieldInfo fi) {
        MNode h2 = fi.ed != null && fi.ed.efi != null ? fi.ed.efi.getDatabaseNodeByConf("h2") : null;
        return apply(h2, rawName(fi));
    }
}
