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
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.impl.entity.EntityQueryBuilder;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.*;

public class TrueCondition implements EntityConditionImplBase {
    private static final Class thisClass = TrueCondition.class;

    public TrueCondition() { }

    @Override public void makeSqlWhere(EntityQueryBuilder eqb, EntityDefinition subMemberEd) { eqb.sqlTopLevel.append("1=1"); }

    @Override public boolean mapMatches(Map<String, Object> map) { return true; }
    @Override public boolean mapMatchesAny(Map<String, Object> map) { return true; }
    @Override public boolean mapKeysNotContained(Map<String, Object> map) { return true; }

    @Override public boolean populateMap(Map<String, Object> map) { return true; }
    @Override public void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) { }
    @Override public EntityConditionImplBase filter(String entityAlias, EntityDefinition mainEd) { return entityAlias == null ? this : null; }

    @Override public EntityCondition ignoreCase() { return this; }
    @Override public String toString() { return "1=1"; }

    @Override public int hashCode() { return 127; }
    @Override public boolean equals(Object o) { return !(o == null || o.getClass() != thisClass); }

    @Override public void writeExternal(ObjectOutput out) throws IOException { }
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException { }
}
