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
import org.moqui.impl.context.EntityTxCache;
import org.moqui.impl.context.TransactionCacheDb;
import org.moqui.impl.entity.condition.EntityConditionImplBase;
import org.moqui.impl.entity.EntityJavaUtil.FieldOrderOptions;
import org.moqui.util.LiteStringMap;
import org.moqui.util.MNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Map;

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
        TransactionCacheDb db = overlayDb();
        if (db != null) {
            EntityDefinition ed = getEntityDef();
            // Actual-entity one(): miss in H2 means copy-on-read from production via onePut in EntityFindBase.
            // View-entity one(): query H2 after copying production members (overlay creates have no production row).
            if (ed.isViewEntity) {
                db.ensureReady(ed);
                db.beginBypass();
                try {
                    EntityValueBase prod = oneInternal(whereCondition, fieldInfoArray, fieldOptionsArray, null);
                    if (prod != null) db.copyFromProduction(prod);
                } finally {
                    db.endBypass();
                }
                return oneInternal(whereCondition, fieldInfoArray, fieldOptionsArray, db);
            }
        }
        return oneInternal(whereCondition, fieldInfoArray, fieldOptionsArray, null);
    }

    private EntityValueBase oneInternal(EntityConditionImplBase whereCondition, FieldInfo[] fieldInfoArray,
            FieldOrderOptions[] fieldOptionsArray, TransactionCacheDb overlay) throws SQLException {
        EntityDefinition ed = getEntityDef();

        // table doesn't exist, just return null
        if (overlay == null && !ed.tableExistsDbMetaOnly()) return null;

        EntityFindBuilder efb = new EntityFindBuilder(ed, this, whereCondition, fieldInfoArray);
        // flag as a find one, small changes to internal behavior to reduce overhead
        efb.isFindOne();

        // SELECT fields
        efb.makeSqlSelectFields(fieldInfoArray, fieldOptionsArray, "true".equals(efi.getDatabaseNode(ed.groupName).attribute("add-unique-as")));
        // FROM Clause
        efb.makeSqlFromClause();
        // WHERE clause only for one/pk query
        efb.makeWhereClause();
        // GROUP BY clause
        efb.makeGroupByClause();
        // NOTE 20200707 don't do this, databases such as Oracle (error ORA-02014) do not allow use of limit/offset with for update: LIMIT/OFFSET clause - for find one always limit to 1: efb.addLimitOffset(1, 0);
        // FOR UPDATE
        if (getForUpdate() && overlay == null) efb.makeForUpdate();

        // run the SQL now that it is built
        EntityValueBase newEntityValue = null;
        Connection overlayCon = null;
        try {
            if (overlay == null && ed.isViewEntity) efi.getEntityDbMeta().checkTableRuntime(ed);

            if (overlay != null) {
                overlayCon = overlay.getConnection();
                efb.useConnection(overlayCon);
            } else {
                efb.makeConnection(useClone);
            }
            efb.makePreparedStatement();
            efb.setPreparedStatementValues();

            final String condSql = isTraceEnabled && whereCondition != null ? whereCondition.toString() : null;
            ResultSet rs = efb.executeQuery();
            if (rs.next()) {
                newEntityValue = new EntityValueImpl(ed, efi);
                LiteStringMap<Object> valueMap = newEntityValue.valueMapInternal;
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
            if (overlayCon != null) {
                try { overlayCon.close(); }
                catch (SQLException sqle) { logger.error("Error closing overlay connection", sqle); }
            }
        }

        return newEntityValue;
    }

    @Override
    public EntityListIterator iteratorExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
                                               ArrayList<String> orderByExpanded, FieldInfo[] fieldInfoArray,
                                               FieldOrderOptions[] fieldOptionsArray) throws SQLException {
        TransactionCacheDb db = overlayDb();
        if (db != null) {
            copyWorkingSet(db, whereCondition, havingCondition, orderByExpanded, fieldInfoArray, fieldOptionsArray);
            return iteratorInternal(whereCondition, havingCondition, orderByExpanded, fieldInfoArray, fieldOptionsArray,
                    db, null);
        }
        return iteratorInternal(whereCondition, havingCondition, orderByExpanded, fieldInfoArray, fieldOptionsArray,
                null, txCache);
    }

    private TransactionCacheDb overlayDb() {
        if (!(txCache instanceof TransactionCacheDb)) return null;
        TransactionCacheDb db = (TransactionCacheDb) txCache;
        if (db.isBypass()) return null;
        EntityDefinition ed = getEntityDef();
        if (!db.handles(ed)) return null;
        return db;
    }

    private void copyWorkingSet(TransactionCacheDb db, EntityConditionImplBase whereCondition,
            EntityConditionImplBase havingCondition, ArrayList<String> orderByExpanded, FieldInfo[] fieldInfoArray,
            FieldOrderOptions[] fieldOptionsArray) throws SQLException {
        EntityDefinition ed = getEntityDef();
        db.ensureReady(ed);
        db.beginBypass();
        try {
            try (EntityListIterator eli = iteratorInternal(whereCondition, havingCondition, orderByExpanded,
                    fieldInfoArray, fieldOptionsArray, null, null)) {
                EntityValue ev;
                int n = 0;
                while ((ev = eli.next()) != null) {
                    if (++n > TransactionCacheDb.COPY_CAP)
                        throw new EntityException("TX cache DB copy-on-read exceeded " +
                                TransactionCacheDb.COPY_CAP + " rows for " + ed.getFullEntityName());
                    if (ev instanceof EntityValueBase) db.copyFromProduction((EntityValueBase) ev);
                }
            }
        } finally {
            db.endBypass();
        }
    }

    private EntityListIterator iteratorInternal(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
            ArrayList<String> orderByExpanded, FieldInfo[] fieldInfoArray, FieldOrderOptions[] fieldOptionsArray,
            TransactionCacheDb overlay, EntityTxCache mergeCache) throws SQLException {
        EntityDefinition ed = this.getEntityDef();

        // table doesn't exist, just return empty ELI (overlay still has tables we created)
        if (overlay == null && !ed.tableExistsDbMetaOnly())
            return new EntityListIteratorWrapper(new ArrayList<>(), ed, efi, null, null);

        EntityFindBuilder efb = new EntityFindBuilder(ed, this, whereCondition, fieldInfoArray);
        if (getDistinct()) efb.makeDistinct();

        // select fields
        efb.makeSqlSelectFields(fieldInfoArray, fieldOptionsArray, "true".equals(efi.getDatabaseNode(ed.groupName).attribute("add-unique-as")));
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
        if (getForUpdate() && overlay == null) efb.makeForUpdate();

        EntityListIteratorImpl elii;
        try {
            if (overlay == null && ed.isViewEntity) efi.getEntityDbMeta().checkTableRuntime(ed);

            Connection con;
            if (overlay != null) {
                con = overlay.getConnection();
                efb.useConnection(con);
            } else {
                con = efb.makeConnection(useClone);
            }
            efb.makePreparedStatement();
            efb.setPreparedStatementValues();

            ResultSet rs = efb.executeQuery();
            elii = new EntityListIteratorImpl(con, rs, ed, fieldInfoArray, efi, mergeCache, whereCondition, orderByExpanded);
            efb.releaseAll();
            queryTextList.add(efb.finalSql);
        } catch (Throwable t) {
            try { efb.closeAll(); }
            catch (SQLException sqle) { logger.error("Error closing query", sqle); }
            throw t;
        }

        return elii;
    }

    @Override
    public long countExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
                              FieldInfo[] fieldInfoArray, FieldOrderOptions[] fieldOptionsArray) throws SQLException {
        TransactionCacheDb db = overlayDb();
        if (db != null) {
            copyWorkingSet(db, whereCondition, havingCondition, null, fieldInfoArray, fieldOptionsArray);
            return countInternal(whereCondition, havingCondition, fieldInfoArray, fieldOptionsArray, db);
        }
        return countInternal(whereCondition, havingCondition, fieldInfoArray, fieldOptionsArray, null);
    }

    private long countInternal(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
            FieldInfo[] fieldInfoArray, FieldOrderOptions[] fieldOptionsArray, TransactionCacheDb overlay) throws SQLException {
        EntityDefinition ed = getEntityDef();

        // table doesn't exist, just return 0
        if (overlay == null && !ed.tableExistsDbMetaOnly()) return 0;

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
        Connection overlayCon = null;
        try {
            if (overlay == null && ed.isViewEntity) efi.getEntityDbMeta().checkTableRuntime(ed);

            if (overlay != null) {
                overlayCon = overlay.getConnection();
                efb.useConnection(overlayCon);
            } else {
                efb.makeConnection(useClone);
            }
            efb.makePreparedStatement();
            efb.setPreparedStatementValues();

            ResultSet rs = efb.executeQuery();
            if (rs.next()) count = rs.getLong(1);
            queryTextList.add(efb.finalSql);
        } finally {
            try { efb.closeAll(); }
            catch (SQLException sqle) { logger.error("Error closing query", sqle); }
            if (overlayCon != null) {
                try { overlayCon.close(); }
                catch (SQLException sqle) { logger.error("Error closing overlay connection", sqle); }
            }
        }

        return count;
    }
}
