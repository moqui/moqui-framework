package ars.entity

import groovy.json.JsonSlurper
import org.moqui.entity.EntityDynamicView
import org.moqui.entity.EntityException
import org.moqui.entity.EntityListIterator
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.EntityFindBase
import org.moqui.impl.entity.EntityFindBuilder
import org.moqui.impl.entity.EntityJavaUtil
import org.moqui.impl.entity.EntityValueBase
import org.moqui.impl.entity.FieldInfo
import org.moqui.impl.entity.condition.EntityConditionImplBase

import java.sql.ResultSet
import java.sql.SQLException

/**
 * This class is used for advanced querying. In contrast to original EntityFind, SmartFind supports
 * searching using querying multiple tables at once.
 *
 * A major drawback is that it cannot return EntityListImpl which allows direct data modification, but
 * only a list of HashMaps.
 */
class SmartFind extends EntityFindBase {
    SmartFind(EntityFacadeImpl efi, EntityDefinition ed) {
        super(efi, ed)
    }

    @Override
    EntityDynamicView makeEntityDynamicView() {
        throw new EntityException("Creating dynamic-view not supported for SmartFind")
    }

    @Override
    EntityValueBase oneExtended(EntityConditionImplBase whereCondition, FieldInfo[] fieldInfoArray, EntityJavaUtil.FieldOrderOptions[] fieldOptionsArray) throws SQLException {
        throw new EntityException("Method one-extended not supported for SmartFind")
    }

    @Override
    EntityListIterator iteratorExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition, ArrayList<String> orderByExpanded, FieldInfo[] fieldInfoArray, EntityJavaUtil.FieldOrderOptions[] fieldOptionsArray) throws SQLException {
        throw new EntityException("Method iterator-extended not supported for SmartFind")
    }

    @Override
    long countExtended(EntityConditionImplBase whereCondition, EntityConditionImplBase havingCondition, FieldInfo[] fieldInfoArray, EntityJavaUtil.FieldOrderOptions[] fieldOptionsArray) throws SQLException {
        throw new EntityException("Method count-extended not supported for SmartFind")
    }

    ArrayList queryMultipleTables(
            ArrayList<String> fields,
            String fromDefinition,
            EntityConditionImplBase primaryTableWhereCondition,
            String joinSpec)
    {
        FieldInfo[] fi = new ArrayList<FieldInfo>()
        EntityFindBuilder efb = new EntityFindBuilder(this.entityDef, this, primaryTableWhereCondition, fi)

        // select fields
        efb.addSelectMultipleTable(fields);
        // FROM clause
        efb.addManualScript("from", "${this.entityDef.fullTableName} table1, ${fromDefinition}" )
        // WHERE clause
        efb.makeWhereClause();
        // fix the WHERE clause
        efb.fixWhereMultipleTableCondition(primaryTableWhereCondition, joinSpec)

        def columns = new ArrayList<String>()
        def records = new ArrayList<ArrayList>()

        // run the SQL now that it is built
        try {
            // don't check create, above tableExists check is done:
            // efi.getEntityDbMeta().checkTableRuntime(ed)
            // if this is a view-entity and any table in it exists check/create all or will fail with optional members, etc
            if (this.entityDef.isViewEntity) efi.getEntityDbMeta().checkTableRuntime(this.entityDef);

            efb.makeConnection(useClone);
            efb.makePreparedStatement();
            efb.setPreparedStatementValues();

            JsonSlurper slurper = new JsonSlurper()
            ResultSet rs = efb.executeQuery();
            if (rs != null) {
                def rsmd = rs.getMetaData()
                def columnCount = rsmd.getColumnCount()
                for (int i = 1; i <= columnCount; i++) columns.add(rsmd.getColumnName(i).toLowerCase())

                def limitReached = false
                while (rs.next()) {
                    if (limit > 0 && records.size() >= limit) {
                        limitReached = true
                        break
                    }
                    def record = new ArrayList<Object>(columnCount)
                    for (int i = 1; i <= columnCount; i++) {
                        def obj = rs.getObject(i)
                        String tp = "unknown"
                        if (obj.hasProperty("type")) tp = obj["type"]
                        switch (tp) {
                            case 'jsonb':
                                Object objVal = slurper.parseText(obj.value)
                                record.add(objVal)
                                break
                            default:
                                record.add(obj)
                        }
                    }
                    records.add(record)
                }
                rs.close()
            }

            efb.releaseAll();

        } catch (Throwable t) {
            // close the ResultSet/etc on error as there won't be an ELI
            try { efb.closeAll(); }
            catch (SQLException sqle) { logger.error("Error closing query", sqle); }
            throw t;
        }
        def resultset = []
        for (r in records)
        {
            def rec = [:]
            columns.eachWithIndex{ String col, int i ->
                def colName = EntityJavaUtil.underscoredToCamelCase(col, false)
                rec.put(colName, r[i]?r[i]:'')
            }
            resultset.push(rec)
        }

        return resultset;
    }
}
