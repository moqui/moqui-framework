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
import org.moqui.impl.entity.EntityConditionFactoryImpl
import org.moqui.impl.entity.EntityQueryBuilder

@CompileStatic
abstract class EntityConditionImplBase implements EntityCondition {
    EntityConditionFactoryImpl ecFactoryImpl

    EntityConditionImplBase(EntityConditionFactoryImpl ecFactoryImpl) {
        this.ecFactoryImpl = ecFactoryImpl
    }

    /** Build SQL Where text to evaluate condition in a database. */
    public abstract void makeSqlWhere(EntityQueryBuilder eqb)

    public abstract void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet)
}
