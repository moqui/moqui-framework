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
package org.moqui.impl.entity.orientdb

import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx
import com.orientechnologies.orient.core.id.ORID
import com.orientechnologies.orient.core.record.impl.ODocument
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery
import com.orientechnologies.orient.core.sql.OCommandSQL
import groovy.transform.CompileStatic

import org.moqui.entity.EntityException
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityJavaUtil.FieldInfo
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.EntityValueBase

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Connection
import java.sql.Time
import java.sql.Timestamp

@CompileStatic
class OrientEntityValue extends EntityValueBase {
    protected final static Logger logger = LoggerFactory.getLogger(OrientEntityValue.class)

    OrientDatasourceFactory odf
    ORID recordId = null

    OrientEntityValue(EntityDefinition ed, EntityFacadeImpl efip, OrientDatasourceFactory odf) {
        super(ed, efip)
        this.odf = odf
    }

    OrientEntityValue(EntityDefinition ed, EntityFacadeImpl efip, OrientDatasourceFactory odf, ODocument document) {
        super(ed, efip)
        this.odf = odf
        this.recordId = document.getIdentity()

        ArrayList<String> fieldNameList = ed.getAllFieldNames()
        int size = fieldNameList.size()
        for (int i = 0; i < size; i++) {
            String fieldName = fieldNameList.get(i)
            Object fieldValue = document.field(ed.getColumnName(fieldName, false))
            if (fieldValue == null) { getValueMap().put(fieldName, null); continue }

            FieldInfo fieldInfo = ed.getFieldInfo(fieldName)
            int javaTypeInt = fieldInfo.typeValue
            switch (javaTypeInt) {
                case 1: break // should be fine as String
                case 2: fieldValue = new Timestamp(((Date) fieldValue).getTime()); break // DATETIME needs to be converted
                case 3: fieldValue = new Time(((Date) fieldValue).getTime()); break // NOTE: there doesn't seem to be a time only type...
                case 4: fieldValue = new java.sql.Date(((Date) fieldValue).getTime());  break
                case 5: break // should be fine as Integer
                case 6: break // should be fine as Long
                case 7: break // should be fine as Float
                case 8: break // should be fine as Double
                case 9: break // should be fine as BigDecimal
                case 10: break // Boolean should be fine
                case 11: break // NOTE: looks like we'll have to take care of the serialization for objects
                case 12: break // do anything with BLOB?
                case 13: break // CLOB should be fine as String
                case 14: break // java.util.Date is native type for DATETIME
                case 15: break // do anything with EMBEDDEDLIST (List/Set)
            }

            // TODO: add decryption (and encryption on write)

            getValueMap().put(fieldName, fieldValue)
        }
    }

    @Override
    public EntityValue cloneValue() {
        OrientEntityValue newObj = new OrientEntityValue(getEntityDefinition(), getEntityFacadeImpl(), odf)
        newObj.getValueMap().putAll(getValueMap())
        if (getDbValueMap()) newObj.setDbValueMap(new HashMap<String, Object>(getDbValueMap()))
        if (getRecordId() != null) newObj.setRecordId(getRecordId())
        // don't set mutable (default to mutable even if original was not) or modified (start out not modified)
        return newObj
    }

    @Override
    EntityValue cloneDbValue(boolean getOld) {
        OrientEntityValue newObj = new OrientEntityValue(getEntityDefinition(), getEntityFacadeImpl(), odf)
        newObj.getValueMap().putAll(getValueMap())
        ArrayList<String> fieldNameList = getEntityDefinition().getAllFieldNames()
        int size = fieldNameList.size()
        for (int i = 0; i < size; i++) {
            String fieldName = fieldNameList.get(i)
            newObj.put(fieldName, getOld ? getOldDbValue(fieldName) : getOriginalDbValue(fieldName))
        }
        newObj.setSyncedWithDb()
        return newObj
    }

    @Override
    void createExtended(ArrayList<FieldInfo> fieldList, Connection con) {
        EntityDefinition ed = getEntityDefinition()
        if (ed.isViewEntity()) throw new EntityException("Create not yet implemented for view-entity")

        boolean isXaDatabase = true
        ODatabaseDocumentTx oddt = odf.getSynchronizationDatabase()
        if (oddt == null) { oddt = odf.getDatabase(); isXaDatabase = false }
        // logger.warn("======= createExtended isXaDatabase=${isXaDatabase}")

        try {
            odf.checkCreateDocumentClass(oddt, ed)

            ODocument od = oddt.newInstance(ed.getTableName())
            for (Map.Entry<String, Object> valueEntry in getValueMap()) {
                od.field(ed.getColumnName(valueEntry.getKey(), false), valueEntry.getValue())
            }
            od.save()
            recordId = od.getIdentity()
        } finally {
            if (!isXaDatabase) oddt.close()
        }
    }

    @Override
    void updateExtended(ArrayList<FieldInfo> pkFieldList, ArrayList<FieldInfo> nonPkFieldList, Connection con) {
        EntityDefinition ed = getEntityDefinition()
        if (ed.isViewEntity()) throw new EntityException("Update not yet implemented for view-entity")

        // NOTE: according to OrientDB documentation the native Java query API does not use indexes and such, so use the OSQL approach

        boolean isXaDatabase = true
        ODatabaseDocumentTx oddt = odf.getSynchronizationDatabase()
        if (oddt == null) { oddt = odf.getDatabase(); isXaDatabase = false }

        try {
            odf.checkCreateDocumentClass(oddt, ed)

            // logger.warn("========== updating ${ed.getFullEntityName()} recordId=${recordId} pk=${this.getPrimaryKeys()}")

            // TODO: try (works? faster? do same with delete...):
            // ODocument document = recordId.getRecord()
            // for (String fieldName in nonPkFieldList) document.field(fieldName, getValueMap().get(fieldName))
            // document.save()

            StringBuilder sql = new StringBuilder()
            List<Object> paramValues = new ArrayList<Object>()
            sql.append("UPDATE ")
            if (recordId == null) sql.append(ed.getTableName())
            else sql.append("#").append(recordId.getClusterId()).append(":").append(recordId.getClusterPosition())
            sql.append(" SET ")

            int size = nonPkFieldList.size()
            for (int i = 0; i < size; i++) {
                FieldInfo fi = nonPkFieldList.get(i)
                if (i > 0) sql.append(", ")
                sql.append(fi.fullColumnName).append("=?")
                paramValues.add(getValueMap().get(fi.name))
            }
            if (recordId == null) {
                sql.append(" WHERE ")
                int sizePk = pkFieldList.size()
                for (int i = 0; i < sizePk; i++) {
                    FieldInfo fi = pkFieldList.get(i)
                    if (i > 0) sql.append(" AND ")
                    sql.append(fi.fullColumnName).append("=?")
                    paramValues.add(getValueMap().get(fi.name))
                }
            }

            int recordsChanged = oddt.command(new OCommandSQL(sql.toString())).execute(paramValues.toArray(new Object[paramValues.size()])) as int
            if (recordsChanged == 0) throw new IllegalArgumentException("Cannot update entity [${ed.getFullEntityName()}] value with pk [${this.getPrimaryKeys()}], document not found")

            /* an interesting alternative, in basic tests is about the same speed...
            StringBuilder sql = new StringBuilder()
            List<Object> paramValues = new ArrayList<Object>()
            sql.append("SELECT FROM ").append(ed.getTableName()).append(" WHERE ")

            boolean isFirstPk = true
            for (String fieldName in pkFieldList) {
                if (isFirstPk) isFirstPk = false else sql.append(" AND ")
                sql.append(ed.getColumnName(fieldName, false)).append(" = ?")
                paramValues.add(getValueMap().get(fieldName))
            }

            // logger.warn("=========== orient update sql=${sql.toString()}; paramValues=${paramValues}")
            OSQLSynchQuery<ODocument> query = new OSQLSynchQuery<ODocument>(sql.toString())
            List<ODocument> documentList = oddt.command(query).execute(paramValues.toArray(new Object[paramValues.size]))

            // there should only be one value since we're querying by a set of fields with a unique index (the pk)
            if (!documentList) throw new IllegalArgumentException("Cannot update entity [${ed.getEntityName()}] value with pk [${this.getPrimaryKeys()}], document not found")

            ODocument document = documentList.first()
            for (String fieldName in nonPkFieldList) document.field(fieldName, getValueMap().get(fieldName))
            document.save()
            */
        } finally {
            if (!isXaDatabase) oddt.close()
        }
    }

    @Override
    void deleteExtended(Connection con) {
        EntityDefinition ed = getEntityDefinition()
        if (ed.isViewEntity()) throw new EntityException("Delete not yet implemented for view-entity")

        // NOTE: according to OrientDB documentation the native Java query API does not use indexes and such, so use the OSQL approach

        boolean isXaDatabase = true
        ODatabaseDocumentTx oddt = odf.getSynchronizationDatabase()
        if (oddt == null) { oddt = odf.getDatabase(); isXaDatabase = false }

        try {
            odf.checkCreateDocumentClass(oddt, ed)

            StringBuilder sql = new StringBuilder()
            List<Object> paramValues = new ArrayList<Object>()
            sql.append("DELETE FROM ")
            if (recordId == null) {
                sql.append(ed.getTableName()).append(" WHERE ")
                ArrayList<FieldInfo> pkFieldList = ed.getPkFieldInfoList()
                int sizePk = pkFieldList.size()
                for (int i = 0; i < sizePk; i++) {
                    FieldInfo fi = pkFieldList.get(i)
                    if (i > 0) sql.append(" AND ")
                    sql.append(fi.fullColumnName).append(" = ?")
                    paramValues.add(getValueMap().get(fi.name))
                }
            } else {
                sql.append("#").append(recordId.getClusterId()).append(":").append(recordId.getClusterPosition())
            }

            int recordsChanged = oddt.command(new OCommandSQL(sql.toString())).execute(paramValues.toArray(new Object[paramValues.size()])) as int
            if (recordsChanged == 0) throw new IllegalArgumentException("Cannot delete entity [${ed.getFullEntityName()}] value with pk [${this.getPrimaryKeys()}], document not found")

            /* alternate approach with query then delete():
            OSQLSynchQuery<ODocument> query = new OSQLSynchQuery<ODocument>(sql.toString())
            List<ODocument> documentList = oddt.command(query).execute(paramValues.toArray(new Object[paramValues.size()]))
            // there should only be one value since we're querying by a set of fields with a unique index (the pk)
            if (!documentList) throw new IllegalArgumentException("Cannot delete entity [${ed.getEntityName()}] value with pk [${this.getPrimaryKeys()}], document not found")
            ODocument document = documentList.first()
            document.delete()
            */
        } finally {
            if (!isXaDatabase) oddt.close()
        }
    }

    @Override
    boolean refreshExtended() {
        EntityDefinition ed = getEntityDefinition()

        // NOTE: according to OrientDB documentation the native Java query API does not use indexes and such, so use the OSQL approach

        boolean isXaDatabase = true
        ODatabaseDocumentTx oddt = odf.getSynchronizationDatabase()
        if (oddt == null) { oddt = odf.getDatabase(); isXaDatabase = false }

        try {
            odf.checkCreateDocumentClass(oddt, ed)

            StringBuilder sql = new StringBuilder()
            List<Object> paramValues = new ArrayList<Object>()
            sql.append("SELECT FROM ")
            if (recordId == null) {
                sql.append(ed.getTableName()).append(" WHERE ")
                ArrayList<FieldInfo> pkFieldList = ed.getPkFieldInfoList()
                int sizePk = pkFieldList.size()
                for (int i = 0; i < sizePk; i++) {
                    FieldInfo fi = pkFieldList.get(i)
                    if (i > 0) sql.append(" AND ")
                    sql.append(fi.fullColumnName).append(" = ?")
                    paramValues.add(getValueMap().get(fi.name))
                }
            } else {
                // TODO: try recordId.getRecord()... works? faster?
                sql.append("#").append(recordId.getClusterId()).append(":").append(recordId.getClusterPosition())
            }

            OSQLSynchQuery<ODocument> query = new OSQLSynchQuery<ODocument>(sql.toString())
            List<ODocument> documentList = oddt.command(query).execute(paramValues.toArray(new Object[paramValues.size()])) as List<ODocument>

            // there should only be one value since we're querying by a set of fields with a unique index (the pk)
            if (!documentList) {
                logger.info("In refresh document not found for entity [${ed.getFullEntityName()}] with pk [${this.getPrimaryKeys()}]")
                return false
            }

            ODocument document = documentList.get(0)
            for (String fieldName in ed.getNonPkFieldNames())
                getValueMap().put(fieldName, document.field(fieldName))

            return true
        } finally {
            if (!isXaDatabase) oddt.close()
        }
    }
}
