package org.moqui.impl.entity

import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import java.sql.PreparedStatement
import java.sql.SQLException

class EntityBulkImporter extends EntityQueryBuilder {
    private ArrayList<EntityValue> entityList = new ArrayList<>()
    private ExecutionContext ec
    private String groupName

    EntityBulkImporter(EntityDefinition entityDefinition, EntityFacadeImpl efi) {
        super(entityDefinition, efi)
        this.groupName = entityDefinition.groupName
    }

    public void addEntity(EntityValue ev){
        this.entityList.add(ev)
    }

    public long insert() {
        FieldInfo[] fieldInfoArray = super.mainEntityDefinition.entityInfo.allFieldInfoArray;
        this.useConnection(efi.getConnection(groupName));
        Long imported = 0
        StringBuilder sql = this.sqlTopLevel;
        sql.append("INSERT INTO ").append(super.mainEntityDefinition.getFullTableName());

        sql.append(" (");
        StringBuilder valuesForCast = new StringBuilder();

        int size = fieldInfoArray.length;
        StringBuilder values = new StringBuilder(size * 3);

        for (int i = 0; i < size; i++) {
            FieldInfo fieldInfo = fieldInfoArray[i];
            if (fieldInfo == null) break;
            if (i > 0) {
                sql.append(", ");
                values.append(", ");
                valuesForCast.append(", ");
            }

            sql.append(fieldInfo.getFullColumnName());
            values.append("?");

            // cycle through values and construct list of fields
            // for those that are json, insert `cast` function
            if (fieldInfo.type.toLowerCase().contains("json")) {
                valuesForCast.append("to_json(?::json)");
            } else {
                valuesForCast.append("?");
            }
        }

        sql.append(") VALUES (").append(valuesForCast.toString()).append(")");

        try {
            efi.getEntityDbMeta().checkTableRuntime(super.mainEntityDefinition);
            PreparedStatement ps = this.makePreparedStatement();

            for (EntityValue ev in this.entityList) {
                for (int i = 0; i < size; i++) {
                    FieldInfo fieldInfo = fieldInfoArray[i];
                    if (fieldInfo == null) break;
                    this.setPreparedStatementValue(i + 1,  ev.get(fieldInfo.name), fieldInfo);
                }
                ps.addBatch()
            }

            imported = ps.executeBatch().sum()
            if (imported != (long) this.entityList.size())
            {
                logger.warn("Created records count do not match EntityList size: ${}!=${this.entityList.size()}")
            } else {
                logger.info("Records created: ${imported}")
            }
        } catch (SQLException e) {
            String txName = "[could not get]";
            try {
                txName = efi.ecfi.transactionFacade.getTransactionManager().getTransaction().toString();
            }
            catch (Exception txe) {
                if (logger.isTraceEnabled()) logger.trace("Error getting transaction name: " + txe.toString());
            }
            logger.warn("Error creating " + this.toString() + " tx " + txName + " con " + this.connection.toString() + ": " + e.toString());
            throw e;
        } finally {
            try {
                this.entityList.clear()
                this.closeAll();
            }
            catch (SQLException sqle) {
                logger.error("Error in JDBC close in create of " + this.toString(), sqle);
            }
        }
        return imported
    }
}
