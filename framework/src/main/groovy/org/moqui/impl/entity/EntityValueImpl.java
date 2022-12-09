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

import org.moqui.entity.EntityException;
import org.moqui.entity.EntityValue;
import org.moqui.impl.entity.EntityJavaUtil.EntityConditionParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Map;

public class EntityValueImpl extends EntityValueBase {
    protected static final Logger logger = LoggerFactory.getLogger(EntityValueImpl.class);

    /** Default constructor for deserialization ONLY. */
    public EntityValueImpl() { }
    /** Primary constructor, generally used only internally by EntityFacade */
    public EntityValueImpl(EntityDefinition ed, EntityFacadeImpl efip) { super(ed, efip); }

    @Override
    public EntityValue cloneValue() {
        EntityValueImpl newObj = new EntityValueImpl(getEntityDefinition(), getEntityFacadeImpl());
        newObj.valueMapInternal.putAll(this.valueMapInternal);
        if (this.dbValueMap != null) newObj.setDbValueMap(this.dbValueMap);
        // don't set immutable (default to mutable even if original was not) or modified (start out not modified)
        return newObj;
    }

    @Override
    public EntityValue cloneDbValue(boolean getOld) {
        EntityValueImpl newObj = new EntityValueImpl(getEntityDefinition(), getEntityFacadeImpl());
        newObj.valueMapInternal.putAll(this.valueMapInternal);
        for (FieldInfo fieldInfo : getEntityDefinition().entityInfo.allFieldInfoArray)
            newObj.putKnownField(fieldInfo, getOld ? getOldDbValue(fieldInfo.name) : getOriginalDbValue(fieldInfo.name));
        newObj.setSyncedWithDb();
        return newObj;
    }

    @SuppressWarnings("MismatchedQueryAndUpdateOfStringBuilder")
    @Override
    public void createExtended(FieldInfo[] fieldInfoArray, Connection con) throws SQLException {
        EntityDefinition ed = getEntityDefinition();
        EntityFacadeImpl efi = getEntityFacadeImpl();
        if (ed.isViewEntity) throw new EntityException("Create not yet implemented for view-entity");

        EntityQueryBuilder eqb = new EntityQueryBuilder(ed, efi);
        StringBuilder sql = eqb.sqlTopLevel;
        sql.append("INSERT INTO ").append(ed.getFullTableName()).append(" (");

        int size = fieldInfoArray.length;
        StringBuilder values = new StringBuilder(size*3);

        for (int i = 0; i < size; i++) {
            FieldInfo fieldInfo = fieldInfoArray[i];
            if (fieldInfo == null) break;
            if (i > 0) {
                sql.append(", ");
                values.append(", ");
            }

            sql.append(fieldInfo.getFullColumnName());
            values.append("?");
        }

        sql.append(") VALUES (").append(values.toString()).append(")");

        try {
            efi.getEntityDbMeta().checkTableRuntime(ed);

            if (con != null) eqb.useConnection(con);
            else eqb.makeConnection(false);
            eqb.makePreparedStatement();
            for (int i = 0; i < size; i++) {
                FieldInfo fieldInfo = fieldInfoArray[i];
                if (fieldInfo == null) break;
                eqb.setPreparedStatementValue(i + 1, valueMapInternal.getByIString(fieldInfo.name, fieldInfo.index), fieldInfo);
            }

            // if (ed.entityName == "Subscription") logger.warn("Create ${this.toString()} tx ${efi.getEcfi().transaction.getTransactionManager().getTransaction()} con ${eqb.connection}")
            eqb.executeUpdate();
            setSyncedWithDb();
        } catch (SQLException e) {
            String txName = "[could not get]";
            try { txName = efi.ecfi.transactionFacade.getTransactionManager().getTransaction().toString(); }
            catch (Exception txe) { if (logger.isTraceEnabled()) logger.trace("Error getting transaction name: " + txe.toString()); }
            logger.warn("Error creating " + this.toString() + " tx " + txName + " con " + eqb.connection.toString() + ": " + e.toString());
            throw e;
        } finally {
            try { eqb.closeAll(); }
            catch (SQLException sqle) { logger.error("Error in JDBC close in create of " + this.toString(), sqle); }
        }
    }

    @SuppressWarnings("MismatchedQueryAndUpdateOfStringBuilder")
    @Override
    public void updateExtended(FieldInfo[] pkFieldArray, FieldInfo[] nonPkFieldArray, Connection con) throws SQLException {
        EntityDefinition ed = getEntityDefinition();
        final EntityFacadeImpl efi = getEntityFacadeImpl();
        if (ed.isViewEntity) throw new EntityException("Update not yet implemented for view-entity");

        final EntityQueryBuilder eqb = new EntityQueryBuilder(ed, efi);
        ArrayList<EntityConditionParameter> parameters = eqb.parameters;
        StringBuilder sql = eqb.sqlTopLevel;
        sql.append("UPDATE ").append(ed.getFullTableName()).append(" SET ");

        int size = nonPkFieldArray.length;
        for (int i = 0; i < size; i++) {
            FieldInfo fieldInfo = nonPkFieldArray[i];
            if (fieldInfo == null) break;
            if (i > 0) sql.append(", ");
            sql.append(fieldInfo.getFullColumnName()).append("=?");
            parameters.add(new EntityConditionParameter(fieldInfo, valueMapInternal.getByIString(fieldInfo.name, fieldInfo.index), eqb));
        }

        eqb.addWhereClause(pkFieldArray, valueMapInternal);

        try {
            efi.getEntityDbMeta().checkTableRuntime(ed);

            if (con != null) eqb.useConnection(con);
            else eqb.makeConnection(false);
            eqb.makePreparedStatement();
            eqb.setPreparedStatementValues();

            // if (ed.entityName == "Subscription") logger.warn("Update ${this.toString()} tx ${efi.getEcfi().transaction.getTransactionManager().getTransaction()} con ${eqb.connection}")
            if (eqb.executeUpdate() == 0)
                throw new EntityException("Tried to update a value that does not exist [" + this.toString() + "]. SQL used was " + eqb.sqlTopLevel.toString() + ", parameters were " + eqb.parameters.toString());
            setSyncedWithDb();
        } catch (SQLException e) {
            String txName = "[could not get]";
            try { txName = efi.ecfi.transactionFacade.getTransactionManager().getTransaction().toString(); }
            catch (Exception txe) { if (logger.isTraceEnabled()) logger.trace("Error getting transaction name: " + txe.toString()); }
            logger.warn("Error updating " + this.toString() + " tx " + txName + " con " + eqb.connection.toString() + ": " + e.toString());
            throw e;
        } finally {
            try { eqb.closeAll(); }
            catch (SQLException sqle) { logger.error("Error in JDBC close in update of " + this.toString(), sqle); }
        }
    }

    @SuppressWarnings("MismatchedQueryAndUpdateOfStringBuilder")
    @Override
    public void deleteExtended(Connection con) throws SQLException {
        EntityDefinition ed = getEntityDefinition();
        EntityFacadeImpl efi = getEntityFacadeImpl();
        if (ed.isViewEntity) throw new EntityException("Delete not implemented for view-entity");

        EntityQueryBuilder eqb = new EntityQueryBuilder(ed, efi);
        StringBuilder sql = eqb.sqlTopLevel;
        sql.append("DELETE FROM ").append(ed.getFullTableName());

        FieldInfo[] pkFieldArray = ed.entityInfo.pkFieldInfoArray;
        eqb.addWhereClause(pkFieldArray, valueMapInternal);

        try {
            efi.getEntityDbMeta().checkTableRuntime(ed);

            if (con != null) eqb.useConnection(con);
            else eqb.makeConnection(false);
            eqb.makePreparedStatement();
            eqb.setPreparedStatementValues();
            if (eqb.executeUpdate() == 0) logger.info("Tried to delete a value that does not exist " + this.toString());
        } catch (SQLException e) {
            String txName = "[could not get]";
            try { txName = efi.ecfi.transactionFacade.getTransactionManager().getTransaction().toString(); }
            catch (Exception txe) { if (logger.isTraceEnabled()) logger.trace("Error getting transaction name: " + txe.toString()); }
            logger.warn("Error deleting " + this.toString() + " tx " + txName + " con " + eqb.connection.toString() + ": " + e.toString());
            throw e;
        } finally {
            try { eqb.closeAll(); }
            catch (SQLException sqle) { logger.error("Error in JDBC close in delete of " + this.toString(), sqle); }
        }
    }

    @SuppressWarnings("MismatchedQueryAndUpdateOfStringBuilder")
    @Override
    public boolean refreshExtended() throws SQLException {
        EntityDefinition ed = getEntityDefinition();
        EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo;
        EntityFacadeImpl efi = getEntityFacadeImpl();

        // table doesn't exist, just return false
        if (!ed.tableExistsDbMetaOnly()) return false;

        // NOTE: this simple approach may not work for view-entities, but not restricting for now

        FieldInfo[] pkFieldArray = entityInfo.pkFieldInfoArray;
        FieldInfo[] allFieldArray = entityInfo.allFieldInfoArray;
        // NOTE: even if there are no non-pk fields do a refresh in order to see if the record exists or not

        EntityQueryBuilder eqb = new EntityQueryBuilder(ed, efi);
        ArrayList<EntityConditionParameter> parameters = eqb.parameters;
        StringBuilder sql = eqb.sqlTopLevel;
        sql.append("SELECT ");
        eqb.makeSqlSelectFields(allFieldArray, null, "true".equals(efi.getDatabaseNode(ed.groupName).attribute("add-unique-as")));

        sql.append(" FROM ").append(ed.getFullTableName()).append(" WHERE ");

        int sizePk = pkFieldArray.length;
        for (int i = 0; i < sizePk; i++) {
            FieldInfo fi = pkFieldArray[i];
            if (i > 0) sql.append(" AND ");
            sql.append(fi.getFullColumnName()).append("=?");
            parameters.add(new EntityConditionParameter(fi, valueMapInternal.getByIString(fi.name, fi.index), eqb));
        }

        boolean retVal = false;
        try {
            // don't check create, above tableExists check is done:
            // efi.getEntityDbMeta().checkTableRuntime(ed)
            // if this is a view-entity and any table in it exists check/create all or will fail with optional members, etc
            if (ed.isViewEntity) efi.getEntityDbMeta().checkTableRuntime(ed);

            eqb.makeConnection(false);
            eqb.makePreparedStatement();
            eqb.setPreparedStatementValues();

            ResultSet rs = eqb.executeQuery();
            if (rs.next()) {
                int nonPkSize = allFieldArray.length;
                for (int j = 0; j < nonPkSize; j++) {
                    FieldInfo fi = allFieldArray[j];
                    fi.getResultSetValue(rs, j + 1, valueMapInternal, efi);
                }

                retVal = true;
                setSyncedWithDb();
            } else {
                if (logger.isTraceEnabled())
                    logger.trace("No record found in refresh for entity [" + getEntityName() + "] with values [" + String.valueOf(getValueMap()) + "]");
            }
        } catch (SQLException e) {
            String txName = "[could not get]";
            try { txName = efi.ecfi.transactionFacade.getTransactionManager().getTransaction().toString(); }
            catch (Exception txe) { if (logger.isTraceEnabled()) logger.trace("Error getting transaction name: " + txe.toString()); }
            logger.warn("Error finding " + this.toString() + " tx " + txName + " con " + eqb.connection.toString() + ": " + e.toString());
            throw e;
        } finally {
            try { eqb.closeAll(); }
            catch (SQLException sqle) { logger.error("Error in JDBC close in refresh of " + this.toString(), sqle); }
        }

        return retVal;
    }
}
