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

import org.moqui.entity.EntityDynamicView;
import org.moqui.entity.EntityException;
import org.moqui.entity.EntityListIterator;
import org.moqui.entity.EntityValue;
import org.moqui.impl.entity.condition.EntityConditionImplBase;
import org.moqui.impl.entity.EntityJavaUtil.FieldInfo;
import org.moqui.impl.entity.EntityJavaUtil.FieldOrderOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;

public class EntityFindImpl extends EntityFindBase {
    public EntityFindImpl(EntityFacadeImpl efi, String entityName) {
        super(efi, entityName);
    }
    public EntityFindImpl(EntityFacadeImpl efi, EntityDefinition ed) {
        super(efi, ed);
    }

    @Override
    public EntityDynamicView makeEntityDynamicView() {
        if (this.dynamicView != null) return this.dynamicView;
        this.entityDef = null;
        this.dynamicView = new EntityDynamicViewImpl(this);
        return this.dynamicView;
    }

    @Override
    public EntityValueBase oneExtended(EntityConditionImplBase whereCondition, FieldInfo[] fieldInfoArray,
                                       FieldOrderOptions[] fieldOptionsArray) throws EntityException {
        EntityDefinition ed = getEntityDef();

        // table doesn't exist, just return null
        if (!ed.tableExistsDbMetaOnly()) return null;

        EntityFindBuilder efb = new EntityFindBuilder(ed, this);

        // SELECT fields
        efb.makeSqlSelectFields(fieldInfoArray, fieldOptionsArray);
        // FROM Clause
        efb.makeSqlFromClause(fieldInfoArray);

        // WHERE clause only for one/pk query
        if (whereCondition != null) {
            efb.startWhereClause();
            whereCondition.makeSqlWhere(efb);
        }

        // GROUP BY clause
        efb.makeGroupByClause(fieldInfoArray);

        if (getForUpdate()) efb.makeForUpdate();

        // run the SQL now that it is built
        EntityValueBase newEntityValue = null;
        try {
            // don't check create, above tableExists check is done:
            // efi.getEntityDbMeta().checkTableRuntime(ed)
            // if this is a view-entity and any table in it exists check/create all or will fail with optional members, etc
            if (ed.isViewEntity()) getEfi().getEntityDbMeta().checkTableRuntime(ed);

            efb.makeConnection();
            efb.makePreparedStatement();
            efb.setPreparedStatementValues();

            final String condSql = isTraceEnabled && whereCondition != null ? whereCondition.toString() : null;
            ResultSet rs = efb.executeQuery();
            if (rs.next()) {
                boolean checkUserFields = getEntityDef().allowUserField;
                newEntityValue = new EntityValueImpl(getEntityDef(), getEfi());
                HashMap<String, Object> valueMap = newEntityValue.getValueMap();
                int size = fieldInfoArray.length;
                for (int i = 0; i < size; i++) {
                    FieldInfo fi = fieldInfoArray[i];
                    if (fi == null) break;
                    if (checkUserFields && fi.isUserField) continue;
                    EntityJavaUtil.getResultSetValue(rs, i + 1, fi, valueMap, getEfi());
                }

            } else {
                if (isTraceEnabled) logger.trace("Result set was empty for find on entity " + entityName + " with condition " + condSql);
            }

            if (isTraceEnabled && rs.next()) logger.trace("Found more than one result for condition " + condSql + " on entity " + entityName);
        } catch (SQLException e) {
            throw new EntityException("Error finding value", e);
        } finally {
            try { efb.closeAll(); }
            catch (SQLException sqle) { //noinspection ThrowFromFinallyBlock
                throw new EntityException("Error finding value", sqle); }
        }


        return newEntityValue;
    }

    @Override
    public EntityListIterator iteratorExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
                                               ArrayList<String> orderByExpanded, FieldInfo[] fieldInfoArray,
                                               FieldOrderOptions[] fieldOptionsArray) throws EntityException {
        EntityDefinition ed = this.getEntityDef();

        // table doesn't exist, just return empty ELI
        if (!ed.tableExistsDbMetaOnly())
            return new EntityListIteratorWrapper(new ArrayList<EntityValue>(), ed, getEfi());

        EntityFindBuilder efb = new EntityFindBuilder(ed, this);
        if (getDistinct()) efb.makeDistinct();

        // select fields
        efb.makeSqlSelectFields(fieldInfoArray, fieldOptionsArray);
        // FROM Clause
        efb.makeSqlFromClause(fieldInfoArray);

        // WHERE clause
        if (whereCondition != null) {
            efb.startWhereClause();
            whereCondition.makeSqlWhere(efb);
        }

        // GROUP BY clause
        efb.makeGroupByClause(fieldInfoArray);
        // HAVING clause
        if (havingCondition != null) {
            efb.startHavingClause();
            havingCondition.makeSqlWhere(efb);
        }


        // ORDER BY clause
        efb.makeOrderByClause(orderByExpanded);
        // LIMIT/OFFSET clause
        efb.addLimitOffset(getLimit(), getOffset());
        // FOR UPDATE
        if (getForUpdate()) efb.makeForUpdate();

        // run the SQL now that it is built
        EntityListIteratorImpl elii;
        try {
            // don't check create, above tableExists check is done:
            // efi.getEntityDbMeta().checkTableRuntime(ed)
            // if this is a view-entity and any table in it exists check/create all or will fail with optional members, etc
            if (ed.isViewEntity()) getEfi().getEntityDbMeta().checkTableRuntime(ed);

            Connection con = efb.makeConnection();
            efb.makePreparedStatement();
            efb.setPreparedStatementValues();

            ResultSet rs = efb.executeQuery();
            elii = new EntityListIteratorImpl(con, rs, ed, fieldInfoArray, getEfi(), txCache);
            // ResultSet will be closed in the EntityListIterator
            efb.releaseAll();
        } catch (EntityException e) {
            try { efb.closeAll(); }
            catch (SQLException sqle) { //noinspection ThrowFromFinallyBlock
                throw new EntityException("Error in find", sqle); }
            throw e;
        } catch (Throwable t) {
            try { efb.closeAll(); }
            catch (SQLException sqle) { //noinspection ThrowFromFinallyBlock
                throw new EntityException("Error finding value", sqle); }
            throw new EntityException("Error in find", t);
        }


        return elii;
    }

    @Override
    public long countExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
                              FieldInfo[] fieldInfoArray, FieldOrderOptions[] fieldOptionsArray) throws EntityException {
        EntityDefinition ed = getEntityDef();

        // table doesn't exist, just return 0
        if (!ed.tableExistsDbMetaOnly()) return 0;

        EntityFindBuilder efb = new EntityFindBuilder(ed, this);

        // count function instead of select fields
        efb.makeCountFunction(fieldInfoArray);
        // FROM Clause
        efb.makeSqlFromClause(fieldInfoArray);

        // WHERE clause
        if (whereCondition != null) {
            efb.startWhereClause();
            whereCondition.makeSqlWhere(efb);
        }

        // GROUP BY clause
        efb.makeGroupByClause(fieldInfoArray);
        // HAVING clause
        if (havingCondition != null) {
            efb.startHavingClause();
            havingCondition.makeSqlWhere(efb);
        }


        efb.closeCountFunctionIfGroupBy();

        // run the SQL now that it is built
        long count = 0;
        try {
            // don't check create, above tableExists check is done:
            // efi.getEntityDbMeta().checkTableRuntime(ed)
            // if this is a view-entity and any table in it exists check/create all or will fail with optional members, etc
            if (ed.isViewEntity()) getEfi().getEntityDbMeta().checkTableRuntime(ed);

            efb.makeConnection();
            efb.makePreparedStatement();
            efb.setPreparedStatementValues();

            ResultSet rs = efb.executeQuery();
            if (rs.next()) count = rs.getLong(1);
        } catch (SQLException e) {
            throw new EntityException("Error finding count", e);
        } finally {
            try { efb.closeAll(); }
            catch (SQLException sqle) { //noinspection ThrowFromFinallyBlock
                throw new EntityException("Error finding value", sqle); }
        }


        return count;
    }

    protected static final Logger logger = LoggerFactory.getLogger(EntityFindImpl.class);
    protected static final boolean isTraceEnabled = logger.isTraceEnabled();
}
