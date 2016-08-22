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

import org.moqui.entity.EntityException
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.EntityJavaUtil.EntityConditionParameter
import org.moqui.impl.entity.EntityJavaUtil.FieldInfo
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Connection
import java.sql.ResultSet

@CompileStatic
class EntityValueImpl extends EntityValueBase {
    protected final static Logger logger = LoggerFactory.getLogger(EntityValueImpl.class)

    /** Default constructor for deserialization ONLY. */
    EntityValueImpl() { }

    EntityValueImpl(EntityDefinition ed, EntityFacadeImpl efip) { super(ed, efip) }

    @Override
    public EntityValue cloneValue() {
        EntityValueImpl newObj = new EntityValueImpl(getEntityDefinition(), getEntityFacadeImpl())
        newObj.getValueMap().putAll(getValueMap())
        if (getDbValueMap() != null) newObj.setDbValueMap(new HashMap<String, Object>(getDbValueMap()))
        // don't set mutable (default to mutable even if original was not) or modified (start out not modified)
        return newObj
    }

    @Override
    EntityValue cloneDbValue(boolean getOld) {
        EntityValueImpl newObj = new EntityValueImpl(getEntityDefinition(), getEntityFacadeImpl())
        newObj.getValueMap().putAll(getValueMap())
        for (String fieldName in getEntityDefinition().getAllFieldNames())
            newObj.put(fieldName, getOld ? getOldDbValue(fieldName) : getOriginalDbValue(fieldName))
        newObj.setSyncedWithDb()
        return newObj
    }

    @Override
    void createExtended(FieldInfo[] fieldInfoArray, Connection con) {
        EntityDefinition ed = getEntityDefinition()
        EntityFacadeImpl efi = getEntityFacadeImpl()

        if (ed.isViewEntity()) {
            throw new EntityException("Create not yet implemented for view-entity")
        } else {
            EntityQueryBuilder eqb = new EntityQueryBuilder(ed, efi)
            StringBuilder sql = eqb.getSqlTopLevel()
            sql.append("INSERT INTO ").append(ed.getFullTableName())

            sql.append(" (")
            StringBuilder values = new StringBuilder()

            int size = fieldInfoArray.length
            for (int i = 0; i < size; i++) {
                // explicit cast to avoid Groovy castToType
                FieldInfo fieldInfo = (FieldInfo) fieldInfoArray[i]
                if (fieldInfo == null) break
                // TODO: why was it doing this? fieldInfoList.add(fieldInfo)
                if (i > 0) {
                    sql.append(", ")
                    values.append(", ")
                }
                sql.append(fieldInfo.fullColumnName)
                values.append('?')
            }
            sql.append(") VALUES (").append(values.toString()).append(')')

            try {
                efi.getEntityDbMeta().checkTableRuntime(ed)

                if (con != null) eqb.useConnection(con) else eqb.makeConnection()
                eqb.makePreparedStatement()
                for (int i = 0; i < size; i++) {
                    FieldInfo fieldInfo = (FieldInfo) fieldInfoArray[i]
                    if (fieldInfo == null) break
                    String fieldName = fieldInfo.name
                    eqb.setPreparedStatementValue(i+1I, valueMapInternal.get(fieldName), fieldInfo)
                }

                // if (ed.entityName == "Subscription") logger.warn("Create ${this.toString()} in tenant ${efi.tenantId} tx ${efi.getEcfi().transaction.getTransactionManager().getTransaction()} con ${eqb.connection}")
                eqb.executeUpdate()
                setSyncedWithDb()
            } catch (Exception e) {
                throw new EntityException("Error in create of [${this.toString()}]", e)
            } finally {
                eqb.closeAll()
            }
        }
    }

    @Override
    void updateExtended(FieldInfo[] pkFieldArray, FieldInfo[] nonPkFieldArray, Connection con) {
        EntityDefinition ed = getEntityDefinition()
        EntityFacadeImpl efi = getEntityFacadeImpl()

        if (ed.isViewEntity()) {
            throw new EntityException("Update not yet implemented for view-entity")
        } else {
            EntityQueryBuilder eqb = new EntityQueryBuilder(ed, efi)
            ArrayList<EntityConditionParameter> parameters = eqb.getParameters()
            StringBuilder sql = eqb.getSqlTopLevel()
            sql.append("UPDATE ").append(ed.getFullTableName()).append(" SET ")

            int size = nonPkFieldArray.length
            for (int i = 0; i < size; i++) {
                FieldInfo fieldInfo = (FieldInfo) nonPkFieldArray[i]
                if (fieldInfo == null) break
                if (i > 0) sql.append(", ")
                sql.append(fieldInfo.fullColumnName).append("=?")
                parameters.add(new EntityConditionParameter(fieldInfo, valueMapInternal.get(fieldInfo.name), eqb))
            }
            sql.append(" WHERE ")
            int sizePk = pkFieldArray.length
            for (int i = 0; i < sizePk; i++) {
                FieldInfo fieldInfo = (FieldInfo) pkFieldArray[i]
                if (fieldInfo == null) break
                if (i > 0) sql.append(" AND ")
                sql.append(fieldInfo.fullColumnName).append("=?")
                parameters.add(new EntityConditionParameter(fieldInfo, valueMapInternal.get(fieldInfo.name), eqb))
            }

            try {
                efi.getEntityDbMeta().checkTableRuntime(ed)

                if (con != null) eqb.useConnection(con) else eqb.makeConnection()
                eqb.makePreparedStatement()
                eqb.setPreparedStatementValues()

                // if (ed.entityName == "Subscription") logger.warn("Update ${this.toString()} in tenant ${efi.tenantId} tx ${efi.getEcfi().transaction.getTransactionManager().getTransaction()} con ${eqb.connection}")
                if (eqb.executeUpdate() == 0)
                    throw new EntityException("Tried to update a value that does not exist [${this.toString()}]. SQL used was [${eqb.sqlTopLevelInternal}], parameters were [${eqb.parameters}]")
                setSyncedWithDb()
            } catch (Exception e) {
                throw new EntityException("Error in update of ${this.toString()} in tenant ${efi.tenantId} tx ${efi.getEcfi().transaction.getTransactionManager().getTransaction()} con ${eqb.connection}", e)
            } finally {
                eqb.closeAll()
            }
        }
    }

    @Override
    void deleteExtended(Connection con) {
        EntityDefinition ed = getEntityDefinition()
        EntityFacadeImpl efi = getEntityFacadeImpl()

        if (ed.isViewEntity()) {
            throw new EntityException("Delete not implemented for view-entity")
        } else {
            EntityQueryBuilder eqb = new EntityQueryBuilder(ed, efi)
            ArrayList<EntityConditionParameter> parameters = eqb.getParameters()
            StringBuilder sql = eqb.getSqlTopLevel()
            sql.append("DELETE FROM ").append(ed.getFullTableName()).append(" WHERE ")

            FieldInfo[] pkFieldArray = ed.getPkFieldInfoArray()
            int sizePk = pkFieldArray.length
            for (int i = 0; i < sizePk; i++) {
                FieldInfo fieldInfo = (FieldInfo) pkFieldArray[i]
                if (fieldInfo == null) break
                if (i > 0) sql.append(" AND ")
                sql.append(fieldInfo.fullColumnName).append("=?")
                parameters.add(new EntityConditionParameter(fieldInfo, valueMapInternal.get(fieldInfo.name), eqb))
            }

            try {
                efi.getEntityDbMeta().checkTableRuntime(ed)

                if (con != null) eqb.useConnection(con) else eqb.makeConnection()
                eqb.makePreparedStatement()
                eqb.setPreparedStatementValues()
                if (eqb.executeUpdate() == 0) logger.info("Tried to delete a value that does not exist [${this.toString()}]")
            } catch (Exception e) {
                throw new EntityException("Error in delete of [${this.toString()}]", e)
            } finally {
                eqb.closeAll()
            }
        }
    }

    @Override
    boolean refreshExtended() {
        EntityDefinition ed = getEntityDefinition()
        EntityFacadeImpl efi = getEntityFacadeImpl()

        // table doesn't exist, just return null
        if (!ed.tableExistsDbMetaOnly()) return null

        // NOTE: this simple approach may not work for view-entities, but not restricting for now

        FieldInfo[] pkFieldArray = ed.getPkFieldInfoArray()
        FieldInfo[] allFieldArray = ed.getAllFieldInfoArray()
        // NOTE: even if there are no non-pk fields do a refresh in order to see if the record exists or not

        EntityQueryBuilder eqb = new EntityQueryBuilder(ed, efi)
        ArrayList<EntityConditionParameter> parameters = eqb.getParameters()
        StringBuilder sql = eqb.getSqlTopLevel()
        sql.append("SELECT ")
        eqb.makeSqlSelectFields(allFieldArray, null)

        sql.append(" FROM ").append(ed.getFullTableName()).append(" WHERE ")

        int sizePk = pkFieldArray.length
        for (int i = 0; i < sizePk; i++) {
            FieldInfo fi = (FieldInfo) pkFieldArray[i]
            if (i > 0) sql.append(" AND ")
            sql.append(fi.fullColumnName).append("=?")
            parameters.add(new EntityConditionParameter(fi, valueMapInternal.get(fi.name), eqb))
        }

        boolean retVal = false
        try {
            // don't check create, above tableExists check is done:
            // efi.getEntityDbMeta().checkTableRuntime(ed)
            // if this is a view-entity and any table in it exists check/create all or will fail with optional members, etc
            if (ed.isViewEntity()) efi.getEntityDbMeta().checkTableRuntime(ed)

            eqb.makeConnection()
            eqb.makePreparedStatement()
            eqb.setPreparedStatementValues()

            ResultSet rs = eqb.executeQuery()
            if (rs.next()) {
                HashMap<String, Object> valueMap = valueMapInternal
                int nonPkSize = allFieldArray.length
                for (int j = 0; j < nonPkSize; j++) {
                    FieldInfo fi = (FieldInfo) allFieldArray[j]
                    EntityJavaUtil.getResultSetValue(rs, j + 1, fi, valueMap, efi)
                }
                retVal = true
                setSyncedWithDb()
            } else {
                if (logger.traceEnabled) logger.trace("No record found in refresh for entity [${getEntityName()}] with values [${getValueMap()}]")
            }
        } catch (Exception e) {
            throw new EntityException("Error in refresh of [${this.toString()}]", e)
        } finally {
            eqb.closeAll()
        }

        return retVal
    }
}
