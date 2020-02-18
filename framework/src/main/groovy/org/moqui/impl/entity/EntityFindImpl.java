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
import org.moqui.entity.EntityListIterator;
import org.moqui.impl.entity.condition.EntityConditionImplBase;
import org.moqui.impl.entity.EntityJavaUtil.FieldOrderOptions;
import org.moqui.util.MNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;

public class EntityFindImpl extends EntityFindBase {
    protected static final Logger logger = LoggerFactory.getLogger(EntityFindImpl.class);
    protected static final boolean isTraceEnabled = logger.isTraceEnabled();

    public EntityFindImpl(EntityFacadeImpl efi, String entityName) { super(efi, entityName); }
    public EntityFindImpl(EntityFacadeImpl efi, EntityDefinition ed) { super(efi, ed); }

    @Override
    public EntityDynamicView makeEntityDynamicView() {
        if (this.dynamicView != null) return this.dynamicView;
        this.entityDef = null;
        this.dynamicView = new EntityDynamicViewImpl(this);
        return this.dynamicView;
    }

    @Override
    public EntityValueBase oneExtended(EntityConditionImplBase whereCondition, FieldInfo[] fieldInfoArray,
                                       FieldOrderOptions[] fieldOptionsArray) throws SQLException {
        EntityDefinition ed = getEntityDef();

        // table doesn't exist, just return null
        if (!ed.tableExistsDbMetaOnly()) return null;

        EntityFindBuilder efb = new EntityFindBuilder(ed, this, whereCondition, fieldInfoArray);

        // SELECT fields
        efb.makeSqlSelectFields(fieldInfoArray, fieldOptionsArray, false);
        // FROM Clause
        efb.makeSqlFromClause();
        // WHERE clause only for one/pk query
        efb.makeWhereClause();
        // GROUP BY clause
        efb.makeGroupByClause();
        // FOR UPDATE
        if (getForUpdate()) efb.makeForUpdate();

        // run the SQL now that it is built
        EntityValueBase newEntityValue = null;
        try {
            // don't check create, above tableExists check is done:
            // efi.getEntityDbMeta().checkTableRuntime(ed)
            // if this is a view-entity and any table in it exists check/create all or will fail with optional members, etc
            if (ed.isViewEntity) efi.getEntityDbMeta().checkTableRuntime(ed);

            efb.makeConnection(useClone);
            efb.makePreparedStatement();
            efb.setPreparedStatementValues();

            final String condSql = isTraceEnabled && whereCondition != null ? whereCondition.toString() : null;
            ResultSet rs = efb.executeQuery();
            if (rs.next()) {
                newEntityValue = new EntityValueImpl(ed, efi);
                HashMap<String, Object> valueMap = newEntityValue.getValueMap();
                int size = fieldInfoArray.length;
                for (int i = 0; i < size; i++) {
                    FieldInfo fi = fieldInfoArray[i];
                    if (fi == null) break;
                    fi.getResultSetValue(rs, i + 1, valueMap, efi);
                }
            } else {
                if (isTraceEnabled) logger.trace("Result set was empty for find on entity " + entityName + " with condition " + condSql);
            }

            if (isTraceEnabled && rs.next()) logger.trace("Found more than one result for condition " + condSql + " on entity " + entityName);
            queryTextList.add(efb.finalSql);
        } finally {
            try { efb.closeAll(); }
            catch (SQLException sqle) { logger.error("Error closing query", sqle); }
        }

        return newEntityValue;
    }

    @Override
    public EntityListIterator iteratorExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
                                               ArrayList<String> orderByExpanded, FieldInfo[] fieldInfoArray,
                                               FieldOrderOptions[] fieldOptionsArray) throws SQLException {
        EntityDefinition ed = this.getEntityDef();

        // table doesn't exist, just return empty ELI
        if (!ed.tableExistsDbMetaOnly()) return new EntityListIteratorWrapper(new ArrayList<>(), ed, efi, null, null);

        EntityFindBuilder efb = new EntityFindBuilder(ed, this, whereCondition, fieldInfoArray);
        if (getDistinct()) efb.makeDistinct();

        // select fields
        efb.makeSqlSelectFields(fieldInfoArray, fieldOptionsArray, false);
        // FROM Clause
        efb.makeSqlFromClause();
        // WHERE clause
        efb.makeWhereClause();
        // GROUP BY clause
        efb.makeGroupByClause();
        // HAVING clause
        efb.makeHavingClause(havingCondition);

        boolean hasLimitOffset = limit != null || offset != null;
        // ORDER BY clause
        efb.makeOrderByClause(orderByExpanded, hasLimitOffset);
        // LIMIT/OFFSET clause
        if (hasLimitOffset) efb.addLimitOffset(limit, offset);
        // FOR UPDATE
        if (getForUpdate()) efb.makeForUpdate();

        // run the SQL now that it is built
        EntityListIteratorImpl elii;
        try {
            // don't check create, above tableExists check is done:
            // efi.getEntityDbMeta().checkTableRuntime(ed)
            // if this is a view-entity and any table in it exists check/create all or will fail with optional members, etc
            if (ed.isViewEntity) efi.getEntityDbMeta().checkTableRuntime(ed);

            Connection con = efb.makeConnection(useClone);
            efb.makePreparedStatement();
            efb.setPreparedStatementValues();

            ResultSet rs = efb.executeQuery();
            elii = new EntityListIteratorImpl(con, rs, ed, fieldInfoArray, efi, txCache, whereCondition, orderByExpanded);
            // ResultSet will be closed in the EntityListIterator
            efb.releaseAll();
            queryTextList.add(efb.finalSql);
        } catch (Throwable t) {
            // close the ResultSet/etc on error as there won't be an ELI
            try { efb.closeAll(); }
            catch (SQLException sqle) { logger.error("Error closing query", sqle); }
            throw t;
        }
        // no finally block to close ResultSet, etc because contained in EntityListIterator and closed with it

        return elii;
    }

    @Override
    public long countExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
                              FieldInfo[] fieldInfoArray, FieldOrderOptions[] fieldOptionsArray) throws SQLException {
        EntityDefinition ed = getEntityDef();

        // table doesn't exist, just return 0
        if (!ed.tableExistsDbMetaOnly()) return 0;

        EntityFindBuilder efb = new EntityFindBuilder(ed, this, whereCondition, fieldInfoArray);

        ArrayList<MNode> entityConditionList = ed.internalEntityNode.children("entity-condition");
        MNode condNode = entityConditionList != null && entityConditionList.size() > 0 ? entityConditionList.get(0) : null;
        boolean isDistinct = getDistinct() || (ed.isViewEntity && condNode != null && "true".equals(condNode.attribute("distinct")));
        boolean isGroupBy = ed.entityInfo.hasFunctionAlias;

        // count function instead of select fields
        efb.makeCountFunction(fieldOptionsArray, isDistinct, isGroupBy);
        // FROM Clause
        efb.makeSqlFromClause();
        // WHERE clause
        efb.makeWhereClause();
        // GROUP BY clause
        efb.makeGroupByClause();
        // HAVING clause
        efb.makeHavingClause(havingCondition);

        efb.closeCountSubSelect(fieldInfoArray.length, isDistinct, isGroupBy);

        // run the SQL now that it is built
        long count = 0;
        try {
            // don't check create, above tableExists check is done:
            // efi.getEntityDbMeta().checkTableRuntime(ed)
            // if this is a view-entity and any table in it exists check/create all or will fail with optional members, etc
            if (ed.isViewEntity) efi.getEntityDbMeta().checkTableRuntime(ed);

            efb.makeConnection(useClone);
            efb.makePreparedStatement();
            efb.setPreparedStatementValues();

            ResultSet rs = efb.executeQuery();
            if (rs.next()) count = rs.getLong(1);
            queryTextList.add(efb.finalSql);
        } finally {
            try { efb.closeAll(); }
            catch (SQLException sqle) { logger.error("Error closing query", sqle); }
        }

        return count;
    }
}
