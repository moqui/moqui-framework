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
package org.moqui.impl.entity

import groovy.transform.CompileStatic

import java.sql.ResultSet
import java.sql.Connection
import java.sql.SQLException

import org.moqui.entity.EntityDynamicView
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityException
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.moqui.impl.entity.EntityDefinition.FieldInfo
import org.moqui.impl.entity.EntityJavaUtil.FieldOrderOptions

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class EntityFindImpl extends EntityFindBase {
    protected final static Logger logger = LoggerFactory.getLogger(EntityFindImpl.class)

    EntityFindImpl(EntityFacadeImpl efi, String entityName) {
        super(efi, entityName)
    }

    @Override
    EntityDynamicView makeEntityDynamicView() {
        if (this.dynamicView != null) return this.dynamicView
        this.dynamicView = new EntityDynamicViewImpl(this)
        return this.dynamicView
    }

    // ======================== Run Find Methods ==============================

    @Override
    EntityValueBase oneExtended(EntityConditionImplBase whereCondition, ArrayList<FieldInfo> fieldInfoList,
                                ArrayList<FieldOrderOptions> fieldOptionsList) throws EntityException {
        EntityDefinition ed = this.getEntityDef()

        // table doesn't exist, just return null
        if (!efi.getEntityDbMeta().tableExists(ed)) return null

        EntityFindBuilder efb = new EntityFindBuilder(ed, this)

        // SELECT fields
        efb.makeSqlSelectFields(fieldInfoList, fieldOptionsList)
        // FROM Clause
        efb.makeSqlFromClause(fieldInfoList)

        // WHERE clause only for one/pk query
        if (whereCondition != null) {
            efb.startWhereClause()
            whereCondition.makeSqlWhere(efb)
        }
        // GROUP BY clause
        efb.makeGroupByClause(fieldInfoList)

        if (this.forUpdate) efb.makeForUpdate()

        // run the SQL now that it is built
        EntityValueBase newEntityValue = null
        try {
            // don't check create, above tableExists check is done:
            // efi.getEntityDbMeta().checkTableRuntime(ed)
            // if this is a view-entity and any table in it exists check/create all or will fail with optional members, etc
            if (ed.isViewEntity()) efi.getEntityDbMeta().checkTableRuntime(ed)

            efb.makeConnection()
            efb.makePreparedStatement()
            efb.setPreparedStatementValues()

            String condSql = whereCondition.toString()
            ResultSet rs = efb.executeQuery()
            if (rs.next()) {
                newEntityValue = new EntityValueImpl(this.entityDef, this.efi)
                int size = fieldInfoList.size()
                for (int i = 0; i < size; i++) {
                    FieldInfo fi = (FieldInfo) fieldInfoList.get(i)
                    if (fi.isUserField) continue
                    EntityQueryBuilder.getResultSetValue(rs, i+1, fi, newEntityValue, this.efi)
                }
            } else {
                if (logger.isTraceEnabled()) logger.trace("Result set was empty for find on entity [${this.entityName}] with condition [${condSql}]")
            }
            if (rs.next()) {
                if (logger.isTraceEnabled()) logger.trace("Found more than one result for condition [${condSql}] on entity [${this.entityDef.getFullEntityName()}]")
            }
        } catch (SQLException e) {
            throw new EntityException("Error finding value", e)
        } finally {
            efb.closeAll()
        }

        return newEntityValue
    }

    @Override
    EntityListIterator iteratorExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
                                        ArrayList<String> orderByExpanded, ArrayList<FieldInfo> fieldInfoList,
                                        ArrayList<FieldOrderOptions> fieldOptionsList) throws EntityException {
        EntityDefinition ed = this.getEntityDef()

        // table doesn't exist, just return empty ELI
        if (!efi.getEntityDbMeta().tableExists(ed)) return new EntityListIteratorWrapper([], ed, this.fieldsToSelect, this.efi)

        EntityFindBuilder efb = new EntityFindBuilder(ed, this)
        if (this.getDistinct()) efb.makeDistinct()

        // select fields
        efb.makeSqlSelectFields(fieldInfoList, fieldOptionsList)
        // FROM Clause
        efb.makeSqlFromClause(fieldInfoList)

        // WHERE clause
        if (whereCondition != null) {
            efb.startWhereClause()
            whereCondition.makeSqlWhere(efb)
        }
        // GROUP BY clause
        efb.makeGroupByClause(fieldInfoList)
        // HAVING clause
        if (havingCondition != null) {
            efb.startHavingClause()
            havingCondition.makeSqlWhere(efb)
        }

        // ORDER BY clause
        efb.makeOrderByClause(orderByExpanded)
        // LIMIT/OFFSET clause
        efb.addLimitOffset(this.limit, this.offset)
        // FOR UPDATE
        if (this.forUpdate) efb.makeForUpdate()

        // run the SQL now that it is built
        EntityListIteratorImpl elii
        try {
            // don't check create, above tableExists check is done:
            // efi.getEntityDbMeta().checkTableRuntime(ed)
            // if this is a view-entity and any table in it exists check/create all or will fail with optional members, etc
            if (ed.isViewEntity()) efi.getEntityDbMeta().checkTableRuntime(ed)

            Connection con = efb.makeConnection()
            efb.makePreparedStatement()
            efb.setPreparedStatementValues()

            ResultSet rs = efb.executeQuery()
            elii = new EntityListIteratorImpl(con, rs, ed, fieldInfoList, this.efi)
            // ResultSet will be closed in the EntityListIterator
            efb.releaseAll()
        } catch (EntityException e) {
            efb.closeAll()
            throw e
        } catch (Throwable t) {
            efb.closeAll()
            throw new EntityException("Error in find", t)
        }

        return elii
    }

    @Override
    long countExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
                       ArrayList<FieldInfo> fieldInfoList, ArrayList<FieldOrderOptions> fieldOptionsList) throws EntityException {
        EntityDefinition ed = this.getEntityDef()

        // table doesn't exist, just return 0
        if (!efi.getEntityDbMeta().tableExists(ed)) return 0

        EntityFindBuilder efb = new EntityFindBuilder(ed, this)

        // count function instead of select fields
        efb.makeCountFunction(fieldInfoList)
        // FROM Clause
        efb.makeSqlFromClause(fieldInfoList)

        // WHERE clause
        if (whereCondition != null) {
            efb.startWhereClause()
            whereCondition.makeSqlWhere(efb)
        }
        // GROUP BY clause
        efb.makeGroupByClause(fieldInfoList)
        // HAVING clause
        if (havingCondition != null) {
            efb.startHavingClause()
            havingCondition.makeSqlWhere(efb)
        }

        efb.closeCountFunctionIfGroupBy()

        // run the SQL now that it is built
        long count = 0
        try {
            // don't check create, above tableExists check is done:
            // efi.getEntityDbMeta().checkTableRuntime(ed)
            // if this is a view-entity and any table in it exists check/create all or will fail with optional members, etc
            if (ed.isViewEntity()) efi.getEntityDbMeta().checkTableRuntime(ed)

            efb.makeConnection()
            efb.makePreparedStatement()
            efb.setPreparedStatementValues()

            ResultSet rs = efb.executeQuery()
            if (rs.next()) count = rs.getLong(1)
        } catch (SQLException e) {
            throw new EntityException("Error finding count", e)
        } finally {
            efb.closeAll()
        }

        return count
    }
}
