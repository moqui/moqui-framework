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
import org.moqui.impl.entity.EntityQueryBuilder
import org.moqui.impl.entity.EntityConditionFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class WhereCondition extends EntityConditionImplBase {
    protected final static Logger logger = LoggerFactory.getLogger(WhereCondition.class)
    protected String sqlWhereClause

    WhereCondition(EntityConditionFactoryImpl ecFactoryImpl, String sqlWhereClause) {
        super(ecFactoryImpl)
        this.sqlWhereClause = sqlWhereClause ? sqlWhereClause : ""
    }

    @Override
    void makeSqlWhere(EntityQueryBuilder eqb) {
        eqb.getSqlTopLevel().append(this.sqlWhereClause)
    }

    @Override
    boolean mapMatches(Map<String, Object> map) {
        // NOTE: always return false unless we eventually implement some sort of SQL parsing, for caching/etc
        // always consider not matching
        logger.warn("The mapMatches for the SQL Where Condition is not supported, text is [${this.sqlWhereClause}]")
        return false
    }

    @Override
    boolean populateMap(Map<String, Object> map) { return false }

    void getAllAliases(Set<String> entityAliasSet, Set<String> fieldAliasSet) { }

    @Override
    EntityCondition ignoreCase() { throw new IllegalArgumentException("Ignore case not supported for this type of condition.") }

    @Override
    String toString() { return this.sqlWhereClause }

    @Override
    int hashCode() { return (sqlWhereClause ? sqlWhereClause.hashCode() : 0) }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != this.getClass()) return false
        WhereCondition that = (WhereCondition) o
        if (!this.sqlWhereClause.equals(that.sqlWhereClause)) return false
        return true
    }
}
