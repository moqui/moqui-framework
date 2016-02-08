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
import com.orientechnologies.orient.core.record.impl.ODocument
import com.orientechnologies.orient.core.sql.query.OSQLSynchQuery
import groovy.transform.CompileStatic
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.moqui.entity.*
import org.moqui.impl.entity.*

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class OrientEntityFind extends EntityFindBase {
    protected final static Logger logger = LoggerFactory.getLogger(OrientEntityValue.class)

    OrientDatasourceFactory odf

    OrientEntityFind(EntityFacadeImpl efip, String entityName, OrientDatasourceFactory odf) {
        super(efip, entityName)
        this.odf = odf
    }

    @Override
    EntityDynamicView makeEntityDynamicView() {
        throw new UnsupportedOperationException("EntityDynamicView is not yet supported for Orient DB")
    }

    @Override
    EntityValueBase oneExtended(EntityConditionImplBase whereCondition, ArrayList<EntityDefinition.FieldInfo> fieldInfoList) throws EntityException {
        EntityDefinition ed = this.getEntityDef()

        // NOTE: the native Java query API does not used indexes and such, so use the OSQL approach

        boolean isXaDatabase = true
        ODatabaseDocumentTx oddt = odf.getSynchronizationDatabase()
        if (oddt == null) { oddt = odf.getDatabase(); isXaDatabase = false }

        try {
            EntityFindBuilder efb = new EntityFindBuilder(ed, this)

            // SELECT fields
            // NOTE: for OrientDB don't bother listing fields to select: efb.makeSqlSelectFields(this.fieldsToSelect)

            // FROM Clause
            efb.makeSqlFromClause(fieldInfoList)

            // WHERE clause only for one/pk query
            // NOTE: do this here after caching because this will always be added on and isn't a part of the original where
            EntityConditionImplBase viewWhere = ed.makeViewWhereCondition()
            if (viewWhere != null) whereCondition =
                (EntityConditionImplBase) efi.getConditionFactory().makeCondition(whereCondition, EntityCondition.JoinOperator.AND, viewWhere)
            efb.startWhereClause()
            whereCondition.makeSqlWhere(efb)

            // FOR UPDATE doesn't seem to be supported for OrientDB: if (this.forUpdate) efb.makeForUpdate()

            // run the SQL now that it is built
            odf.checkCreateDocumentClass(oddt, ed)

            String sqlString = efb.getSqlTopLevel().toString()
            // logger.warn("TOREMOVE: running OrientDB query: ${sqlString}")

            OSQLSynchQuery<ODocument> query = new OSQLSynchQuery<ODocument>(sqlString)

            List<Object> paramValues = new ArrayList<Object>()
            for (EntityQueryBuilder.EntityConditionParameter entityConditionParam in efb.getParameters()) {
                paramValues.add(entityConditionParam.getValue())
            }
            List<ODocument> documentList = oddt.command(query).execute(paramValues.toArray(new Object[paramValues.size()])) as List<ODocument>

            // there should only be one value since we're querying by a set of fields with a unique index (the pk)
            if (!documentList) return null

            ODocument document = documentList.get(0)
            OrientEntityValue newEntityValue = new OrientEntityValue(ed, efi, odf, document)

            return newEntityValue
        } catch (Exception e) {
            throw new EntityException("Error finding value", e)
        } finally {
            if (!isXaDatabase) oddt.close()
        }
    }

    @Override
    EntityListIterator iteratorExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
                                        List<String> orderByExpanded, ArrayList<EntityDefinition.FieldInfo> fieldInfoList) throws EntityException {
        EntityDefinition ed = this.getEntityDef()

        // NOTE: see syntax at https://github.com/orientechnologies/orientdb/wiki/SQL-Query

        EntityFindBuilder efb = new EntityFindBuilder(ed, this)
        if (this.getDistinct()) efb.makeDistinct()

        // select fields
        efb.makeSqlSelectFields(fieldInfoList)
        // FROM Clause
        efb.makeSqlFromClause(fieldInfoList)

        // WHERE clause
        if (whereCondition) {
            efb.startWhereClause()
            whereCondition.makeSqlWhere(efb)
        }
        // GROUP BY clause
        efb.makeGroupByClause(fieldInfoList)
        // HAVING clause
        if (havingCondition) {
            efb.startHavingClause()
            havingCondition.makeSqlWhere(efb)
        }

        // ORDER BY clause
        efb.makeOrderByClause(orderByExpanded)
        // LIMIT/OFFSET clause
        // efb.addLimitOffset(this.limit, this.offset)
        if (offset) efb.getSqlTopLevel().append(" SKIP ").append(offset)
        if (limit) efb.getSqlTopLevel().append(" LIMIT ").append(limit)

        // FOR UPDATE (TODO: supported in ODB?)
        // if (this.forUpdate) efb.makeForUpdate()

        // run the SQL now that it is built
        EntityListIterator eli = null

        boolean isXaDatabase = true
        ODatabaseDocumentTx oddt = odf.getSynchronizationDatabase()
        if (oddt == null) { oddt = odf.getDatabase(); isXaDatabase = false }

        try {
            odf.checkCreateDocumentClass(oddt, ed)

            // get SQL and do query with OrientDB API
            String sqlString = efb.getSqlTopLevel().toString()
            // logger.warn("TOREMOVE: running OrientDB query: ${sqlString}")

            OSQLSynchQuery<ODocument> query = new OSQLSynchQuery<ODocument>(sqlString.toString())
            List<Object> paramValues = new ArrayList<Object>()
            for (EntityQueryBuilder.EntityConditionParameter entityConditionParam in efb.getParameters()) {
                paramValues.add(entityConditionParam.getValue())
            }
            List<ODocument> documentList = oddt.command(query).execute(paramValues.toArray(new Object[paramValues.size()])) as List<ODocument>
            // logger.warn("TOREMOVE: got OrientDb query results: ${documentList}")

            // NOTE: for now don't pass in oddt (pass in null), we're getting the whole list up front we can close it in finally
            eli = new OrientEntityListIterator(odf, null, documentList, getEntityDef(), fieldInfoList, this.efi)
        } catch (EntityException e) {
            throw e
        } catch (Throwable t) {
            throw new EntityException("Error in find", t)
        } finally {
            if (!isXaDatabase) oddt.close()
        }

        return eli
    }

    @Override
    long countExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition,
                       ArrayList<EntityDefinition.FieldInfo> fieldInfoList) throws EntityException {
        EntityDefinition ed = this.getEntityDef()
        EntityFindBuilder efb = new EntityFindBuilder(ed, this)

        // count function instead of select fields
        efb.getSqlTopLevel().append("COUNT(1) ")
        // efb.makeCountFunction()
        // FROM Clause
        efb.makeSqlFromClause(fieldInfoList)

        // WHERE clause
        if (whereCondition) {
            efb.startWhereClause()
            whereCondition.makeSqlWhere(efb)
        }
        // GROUP BY clause
        efb.makeGroupByClause(fieldInfoList)
        // HAVING clause
        if (havingCondition) {
            efb.startHavingClause()
            havingCondition.makeSqlWhere(efb)
        }

        efb.closeCountFunctionIfGroupBy()

        // run the SQL now that it is built
        long count = 0

        boolean isXaDatabase = true
        ODatabaseDocumentTx oddt = odf.getSynchronizationDatabase()
        if (oddt == null) { oddt = odf.getDatabase(); isXaDatabase = false }

        try {
            odf.checkCreateDocumentClass(oddt, ed)

            // get SQL and do query with OrientDB API
            String sqlString = efb.getSqlTopLevel().toString()
            // logger.warn("TOREMOVE: running OrientDB count query: ${sqlString}")

            OSQLSynchQuery<ODocument> query = new OSQLSynchQuery<ODocument>(sqlString.toString())
            List<Object> paramValues = new ArrayList<Object>()
            for (EntityQueryBuilder.EntityConditionParameter entityConditionParam in efb.getParameters()) {
                paramValues.add(entityConditionParam.getValue())
            }
            List<ODocument> documentList = oddt.command(query).execute(paramValues.toArray(new Object[paramValues.size()])) as List<ODocument>
            if (!documentList) logger.warn("Got no result for count query: ${sqlString}")
            ODocument countDoc = documentList?.get(0)
            Object countVal = countDoc?.field("COUNT")
            // logger.warn("========= Got count ${countVal} type ${countVal?.class?.getName()}; countDoc: ${countDoc?.fieldNames()}:${countDoc?.fieldValues()}")
            count = (countVal as Long) ?: 0
        } catch (Exception e) {
            throw new EntityException("Error finding value", e)
        } finally {
            if (!isXaDatabase) oddt.close()
        }

        return count
    }
}
