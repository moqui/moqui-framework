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
import org.moqui.impl.entity.EntityJavaUtil.RelationshipInfo
import org.moqui.util.CollectionUtilities
import org.moqui.util.MNode

import java.sql.Connection
import java.sql.Statement
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.sql.Timestamp

import org.moqui.entity.EntityException

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.locks.ReentrantLock

@CompileStatic
class EntityDbMeta {
    protected final static Logger logger = LoggerFactory.getLogger(EntityDbMeta.class)

    static final boolean useTxForMetaData = false

    // this keeps track of when tables are checked and found to exist or are created
    protected HashMap<String, Timestamp> entityTablesChecked = new HashMap<>()
    // a separate Map for tables checked to exist only (used in finds) so repeated checks are needed for unused entities
    protected HashMap<String, Boolean> entityTablesExist = new HashMap<>()

    protected HashMap<String, Boolean> runtimeAddMissingMap = new HashMap<>()

    protected EntityFacadeImpl efi

    EntityDbMeta(EntityFacadeImpl efi) { this.efi = efi }

    boolean checkTableRuntime(EntityDefinition ed) {
        EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo
        // most common case: not view entity and already checked
        boolean alreadyChecked = entityTablesChecked.containsKey(entityInfo.fullEntityName)
        if (alreadyChecked) return false

        String groupName = entityInfo.groupName
        Boolean runtimeAddMissing = (Boolean) runtimeAddMissingMap.get(groupName)
        if (runtimeAddMissing == null) {
            MNode datasourceNode = efi.getDatasourceNode(groupName)
            MNode dbNode = efi.getDatabaseNode(groupName)
            String ramAttr = datasourceNode?.attribute("runtime-add-missing")
            runtimeAddMissing = ramAttr ? !"false".equals(ramAttr) : !"false".equals(dbNode.attribute("default-runtime-add-missing"))
            runtimeAddMissingMap.put(groupName, runtimeAddMissing)
        }
        if (!runtimeAddMissing.booleanValue()) return false

        if (entityInfo.isView) {
            boolean tableCreated = false
            for (MNode memberEntityNode in ed.entityNode.children("member-entity")) {
                EntityDefinition med = efi.getEntityDefinition(memberEntityNode.attribute("entity-name"))
                if (checkTableRuntime(med)) tableCreated = true
            }
            return tableCreated
        } else {
            // already looked above to see if this entity has been checked
            // do the real check, in a synchronized method
            return internalCheckTable(ed, false)
        }
    }
    boolean checkTableStartup(EntityDefinition ed) {
        if (ed.isViewEntity) {
            boolean tableCreated = false
            for (MNode memberEntityNode in ed.entityNode.children("member-entity")) {
                EntityDefinition med = efi.getEntityDefinition(memberEntityNode.attribute("entity-name"))
                if (checkTableStartup(med)) tableCreated = true
            }
            return tableCreated
        } else {
            return internalCheckTable(ed, true)
        }
    }

    void forceCheckTableRuntime(EntityDefinition ed) {
        entityTablesExist.remove(ed.getFullEntityName())
        entityTablesChecked.remove(ed.getFullEntityName())
        checkTableRuntime(ed)
    }
    void forceCheckExistingTables() {
        entityTablesExist.clear()
        entityTablesChecked.clear()
        for (String entityName in efi.getAllEntityNames()) {
            EntityDefinition ed = efi.getEntityDefinition(entityName)
            if (ed.isViewEntity) continue
            if (tableExists(ed)) checkTableRuntime(ed)
        }
    }

    synchronized boolean internalCheckTable(EntityDefinition ed, boolean startup) {
        // if it's in this table we've already checked it
        if (entityTablesChecked.containsKey(ed.getFullEntityName())) return false

        MNode datasourceNode = efi.getDatasourceNode(ed.getEntityGroupName())
        // if there is no @database-conf-name skip this, it's probably not a SQL/JDBC datasource
        if (!datasourceNode.attribute('database-conf-name')) return false

        long startTime = System.currentTimeMillis()
        boolean doCreate = !tableExists(ed)
        if (doCreate) {
            createTable(ed)
            // create explicit and foreign key auto indexes
            createIndexes(ed)
            // create foreign keys to all other tables that exist
            createForeignKeys(ed, false)
        } else {
            // table exists, see if it is missing any columns
            ArrayList<FieldInfo> mcs = getMissingColumns(ed)
            int mcsSize = mcs.size()
            for (int i = 0; i < mcsSize; i++) addColumn(ed, (FieldInfo) mcs.get(i))
            // create foreign keys after checking each to see if it already exists
            if (startup) {
                createForeignKeys(ed, true)
            } else {
                MNode dbNode = efi.getDatabaseNode(ed.getEntityGroupName())
                String runtimeAddFks = datasourceNode.attribute("runtime-add-fks") ?: "true"
                if ((!runtimeAddFks && "true".equals(dbNode.attribute("default-runtime-add-fks"))) || "true".equals(runtimeAddFks))
                    createForeignKeys(ed, true)
            }
        }
        entityTablesChecked.put(ed.getFullEntityName(), new Timestamp(System.currentTimeMillis()))
        entityTablesExist.put(ed.getFullEntityName(), true)

        if (logger.isTraceEnabled()) logger.trace("Checked table for entity [${ed.getFullEntityName()}] in ${(System.currentTimeMillis()-startTime)/1000} seconds")
        return doCreate
    }

    boolean tableExists(EntityDefinition ed) {
        Boolean exists = entityTablesExist.get(ed.getFullEntityName())
        if (exists != null) return exists.booleanValue()

        return tableExistsInternal(ed)
    }
    synchronized boolean tableExistsInternal(EntityDefinition ed) {
        Boolean exists = entityTablesExist.get(ed.getFullEntityName())
        if (exists != null) return exists.booleanValue()

        Boolean dbResult = null
        if (ed.isViewEntity) {
            boolean anyExist = false
            for (MNode memberEntityNode in ed.entityNode.children("member-entity")) {
                EntityDefinition med = efi.getEntityDefinition(memberEntityNode.attribute("entity-name"))
                if (tableExists(med)) { anyExist = true; break }
            }
            dbResult = anyExist
        } else {
            String groupName = ed.getEntityGroupName()
            Connection con = null
            ResultSet tableSet1 = null
            ResultSet tableSet2 = null
            boolean beganTx = useTxForMetaData ? efi.ecfi.transactionFacade.begin(5) : false
            try {
                con = efi.getConnection(groupName)
                DatabaseMetaData dbData = con.getMetaData()

                String[] types = ["TABLE", "VIEW", "ALIAS", "SYNONYM"]
                tableSet1 = dbData.getTables(null, ed.getSchemaName(), ed.getTableName(), types)
                if (tableSet1.next()) {
                    dbResult = true
                } else {
                    // try lower case, just in case DB is case sensitive
                    tableSet2 = dbData.getTables(null, ed.getSchemaName(), ed.getTableName().toLowerCase(), types)
                    if (tableSet2.next()) {
                        dbResult = true
                    } else {
                        if (logger.isTraceEnabled()) logger.trace("Table for entity ${ed.getFullEntityName()} does NOT exist")
                        dbResult = false
                    }
                }
            } catch (Exception e) {
                throw new EntityException("Exception checking to see if table ${ed.getTableName()} exists", e)
            } finally {
                if (tableSet1 != null && !tableSet1.isClosed()) tableSet1.close()
                if (tableSet2 != null && !tableSet2.isClosed()) tableSet2.close()
                if (con != null) con.close()
                if (beganTx) efi.ecfi.transactionFacade.commit()
            }
        }

        if (dbResult == null) throw new EntityException("No result checking if entity ${ed.getFullEntityName()} table exists")

        if (dbResult && !ed.isViewEntity) {
            // on the first check also make sure all columns/etc exist; we'll do this even on read/exist check otherwise query will blow up when doesn't exist
            ArrayList<FieldInfo> mcs = getMissingColumns(ed)
            int mcsSize = mcs.size()
            for (int i = 0; i < mcsSize; i++) addColumn(ed, (FieldInfo) mcs.get(i))
        }
        // don't remember the result for view-entities, get if from member-entities... if we remember it we have to set
        //     it for all view-entities when a member-entity is created
        if (!ed.isViewEntity) entityTablesExist.put(ed.getFullEntityName(), dbResult)
        return dbResult
    }

    void createTable(EntityDefinition ed) {
        if (ed == null) throw new IllegalArgumentException("No EntityDefinition specified, cannot create table")
        if (ed.isViewEntity) throw new IllegalArgumentException("Cannot create table for a view entity")

        String groupName = ed.getEntityGroupName()
        MNode databaseNode = efi.getDatabaseNode(groupName)

        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(ed.getFullTableName()).append(" (")

        FieldInfo[] allFieldInfoArray = ed.entityInfo.allFieldInfoArray
        for (int i = 0; i < allFieldInfoArray.length; i++) {
            FieldInfo fi = (FieldInfo) allFieldInfoArray[i]
            MNode fieldNode = fi.fieldNode
            String sqlType = efi.getFieldSqlType(fi.type, ed)
            String javaType = fi.javaType

            sql.append(fi.columnName).append(" ").append(sqlType)

            if ("String" == javaType || "java.lang.String" == javaType) {
                if (databaseNode.attribute("character-set")) sql.append(" CHARACTER SET ").append(databaseNode.attribute("character-set"))
                if (databaseNode.attribute("collate")) sql.append(" COLLATE ").append(databaseNode.attribute("collate"))
            }

            if (fi.isPk || fieldNode.attribute("not-null") == "true") {
                if (databaseNode.attribute("always-use-constraint-keyword") == "true") sql.append(" CONSTRAINT")
                sql.append(" NOT NULL")
            }
            sql.append(", ")
        }

        if (databaseNode.attribute("use-pk-constraint-names") != "false") {
            String pkName = "PK_" + ed.getTableName()
            int constraintNameClipLength = (databaseNode.attribute("constraint-name-clip-length")?:"30") as int
            if (pkName.length() > constraintNameClipLength) pkName = pkName.substring(0, constraintNameClipLength)
            sql.append("CONSTRAINT ")
            if (databaseNode.attribute("use-schema-for-all") == "true") sql.append(ed.getSchemaName() ? ed.getSchemaName() + "." : "")
            sql.append(pkName)
        }
        sql.append(" PRIMARY KEY (")

        FieldInfo[] pkFieldInfoArray = ed.entityInfo.pkFieldInfoArray
        for (int i = 0; i < pkFieldInfoArray.length; i++) {
            FieldInfo fi = (FieldInfo) pkFieldInfoArray[i]
            if (i > 0) sql.append(", ")
            sql.append(fi.getFullColumnName())
        }
        sql.append("))")

        // some MySQL-specific inconveniences...
        if (databaseNode.attribute("table-engine")) sql.append(" ENGINE ").append(databaseNode.attribute("table-engine"))
        if (databaseNode.attribute("character-set")) sql.append(" CHARACTER SET ").append(databaseNode.attribute("character-set"))
        if (databaseNode.attribute("collate")) sql.append(" COLLATE ").append(databaseNode.attribute("collate"))

        logger.info("Creating table for ${ed.getFullEntityName()} pks: ${ed.getPkFieldNames()}")
        if (logger.traceEnabled) logger.trace("Create Table with SQL: " + sql.toString())

        runSqlUpdate(sql, groupName)
        if (logger.infoEnabled) logger.info("Created table ${ed.getFullTableName()} for entity ${ed.getFullEntityName()} in group ${groupName}")
    }

    ArrayList<FieldInfo> getMissingColumns(EntityDefinition ed) {
        if (ed.isViewEntity) return new ArrayList<FieldInfo>()

        String groupName = ed.getEntityGroupName()
        Connection con = null
        ResultSet colSet1 = null
        ResultSet colSet2 = null
        boolean beganTx = useTxForMetaData ? efi.ecfi.transactionFacade.begin(5) : false
        try {
            con = efi.getConnection(groupName)
            DatabaseMetaData dbData = con.getMetaData()
            // con.setAutoCommit(false)

            ArrayList<FieldInfo> fieldInfos = new ArrayList<>(ed.allFieldInfoList)
            int fieldCount = fieldInfos.size()
            colSet1 = dbData.getColumns(null, ed.getSchemaName(), ed.getTableName(), "%")
            if (colSet1.isClosed()) {
                logger.error("Tried to get columns for entity ${ed.getFullEntityName()} but ResultSet was closed!")
                return new ArrayList<FieldInfo>()
            }
            while (colSet1.next()) {
                String colName = colSet1.getString("COLUMN_NAME")
                int fieldInfosSize = fieldInfos.size()
                for (int i = 0; i < fieldInfosSize; i++) {
                    FieldInfo fi = (FieldInfo) fieldInfos.get(i)
                    if (fi.columnName == colName || fi.columnName.toLowerCase() == colName) {
                        fieldInfos.remove(i)
                        break
                    }
                }
            }

            if (fieldInfos.size() == fieldCount) {
                // try lower case table name
                colSet2 = dbData.getColumns(null, ed.getSchemaName(), ed.getTableName().toLowerCase(), "%")
                if (colSet2.isClosed()) {
                    logger.error("Tried to get columns for entity ${ed.getFullEntityName()} but ResultSet was closed!")
                    return new ArrayList<FieldInfo>()
                }
                while (colSet2.next()) {
                    String colName = colSet2.getString("COLUMN_NAME")
                    int fieldInfosSize = fieldInfos.size()
                    for (int i = 0; i < fieldInfosSize; i++) {
                        FieldInfo fi = (FieldInfo) fieldInfos.get(i)
                        if (fi.columnName == colName || fi.columnName.toLowerCase() == colName) {
                            fieldInfos.remove(i)
                            break
                        }
                    }
                }

                if (fieldInfos.size() == fieldCount) {
                    logger.warn("Could not find any columns to match fields for entity ${ed.getFullEntityName()}")
                    return new ArrayList<FieldInfo>()
                }
            }
            return fieldInfos
        } catch (Exception e) {
            logger.error("Exception checking for missing columns in table ${ed.getTableName()}", e)
            return new ArrayList<FieldInfo>()
        } finally {
            if (colSet1 != null && !colSet1.isClosed()) colSet1.close()
            if (colSet2 != null && !colSet2.isClosed()) colSet2.close()
            if (con != null && !con.isClosed()) con.close()
            if (beganTx) efi.ecfi.transactionFacade.commit()
        }
    }

    void addColumn(EntityDefinition ed, FieldInfo fi) {
        if (ed == null) throw new IllegalArgumentException("No EntityDefinition specified, cannot add column")
        if (ed.isViewEntity) throw new IllegalArgumentException("Cannot add column for a view entity")

        String groupName = ed.getEntityGroupName()
        MNode databaseNode = efi.getDatabaseNode(groupName)

        MNode fieldNode = fi.fieldNode

        String sqlType = efi.getFieldSqlType(fieldNode.attribute("type"), ed)
        String javaType = efi.getFieldJavaType(fieldNode.attribute("type"), ed)

        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(ed.getFullTableName())
        String colName = fi.columnName
        // NOTE: if any databases need "ADD COLUMN" instead of just "ADD", change this to try both or based on config
        sql.append(" ADD ").append(colName).append(" ").append(sqlType)

        if ("String" == javaType || "java.lang.String" == javaType) {
            if (databaseNode.attribute("character-set")) sql.append(" CHARACTER SET ").append(databaseNode.attribute("character-set"))
            if (databaseNode.attribute("collate")) sql.append(" COLLATE ").append(databaseNode.attribute("collate"))
        }

        runSqlUpdate(sql, groupName)
        if (logger.infoEnabled) logger.info("Added column ${colName} to table ${ed.tableName} for field ${fi.name} of entity ${ed.getFullEntityName()} in group ${groupName}")
    }

    void createIndexes(EntityDefinition ed) {
        if (ed == null) throw new IllegalArgumentException("No EntityDefinition specified, cannot create indexes")
        if (ed.isViewEntity) throw new IllegalArgumentException("Cannot create indexes for a view entity")

        String groupName = ed.getEntityGroupName()
        MNode databaseNode = efi.getDatabaseNode(groupName)

        if (databaseNode.attribute("use-indexes") == "false") return

        int constraintNameClipLength = (databaseNode.attribute("constraint-name-clip-length")?:"30") as int

        // first do index elements
        for (MNode indexNode in ed.entityNode.children("index")) {
            StringBuilder sql = new StringBuilder("CREATE ")
            if (databaseNode.attribute("use-indexes-unique") != "false" && indexNode.attribute("unique") == "true") {
                sql.append("UNIQUE ")
                if (databaseNode.attribute("use-indexes-unique-where-not-null") == "true") sql.append("WHERE NOT NULL ")
            }
            sql.append("INDEX ")
            if (databaseNode.attribute("use-schema-for-all") == "true") sql.append(ed.getSchemaName() ? ed.getSchemaName() + "." : "")
            sql.append(indexNode.attribute("name")).append(" ON ").append(ed.getFullTableName())

            sql.append(" (")
            boolean isFirst = true
            for (MNode indexFieldNode in indexNode.children("index-field")) {
                if (isFirst) isFirst = false else sql.append(", ")
                sql.append(ed.getColumnName(indexFieldNode.attribute("name")))
            }
            sql.append(")")

            runSqlUpdate(sql, groupName)
        }

        // do fk auto indexes
        if (databaseNode.attribute("use-foreign-key-indexes") == "false") return
        for (RelationshipInfo relInfo in ed.getRelationshipsInfo(false)) {
            if (relInfo.type != "one") continue

            String indexName = makeFkIndexName(ed, relInfo, constraintNameClipLength)
            StringBuilder sql = new StringBuilder("CREATE INDEX ")
            if (databaseNode.attribute("use-schema-for-all") == "true") sql.append(ed.getSchemaName() ? ed.getSchemaName() + "." : "")
            sql.append(indexName).append(" ON ").append(ed.getFullTableName())

            sql.append(" (")
            Map keyMap = relInfo.keyMap
            boolean isFirst = true
            for (String fieldName in keyMap.keySet()) {
                if (isFirst) isFirst = false else sql.append(", ")
                sql.append(ed.getColumnName(fieldName))
            }
            sql.append(")")

            // logger.warn("====== create relationship index [${indexName}] for entity [${ed.getFullEntityName()}]")
            runSqlUpdate(sql, groupName)
        }
    }

    static String makeFkIndexName(EntityDefinition ed, RelationshipInfo relInfo, int constraintNameClipLength) {
        String relatedEntityName = relInfo.relatedEd.entityInfo.internalEntityName
        StringBuilder indexName = new StringBuilder()
        if (relInfo.relNode.attribute("fk-name")) indexName.append(relInfo.relNode.attribute("fk-name"))
        if (!indexName) {
            String title = relInfo.title ?: ""
            String edEntityName = ed.entityInfo.internalEntityName
            int edEntityNameLength = edEntityName.length()

            int commonChars = 0
            while (title.length() > commonChars && edEntityNameLength > commonChars &&
                    title.charAt(commonChars) == edEntityName.charAt(commonChars)) commonChars++

            int relLength = relatedEntityName.length()
            int relEndCommonChars = relatedEntityName.length() - 1
            while (relEndCommonChars > 0 && edEntityNameLength > relEndCommonChars &&
                    relatedEntityName.charAt(relEndCommonChars) == edEntityName.charAt(edEntityNameLength - (relLength - relEndCommonChars)))
                relEndCommonChars--

            if (commonChars > 0) {
                indexName.append(edEntityName)
                for (char cc in title.substring(0, commonChars).chars) if (Character.isUpperCase(cc)) indexName.append(cc)
                indexName.append(title.substring(commonChars))
                indexName.append(relatedEntityName.substring(0, relEndCommonChars + 1))
                if (relEndCommonChars < (relLength - 1)) for (char cc in relatedEntityName.substring(relEndCommonChars + 1).chars)
                    if (Character.isUpperCase(cc)) indexName.append(cc)
            } else {
                indexName.append(edEntityName).append(title)
                indexName.append(relatedEntityName.substring(0, relEndCommonChars + 1))
                if (relEndCommonChars < (relLength - 1)) for (char cc in relatedEntityName.substring(relEndCommonChars + 1).chars)
                    if (Character.isUpperCase(cc)) indexName.append(cc)
            }

            // logger.warn("Index for entity [${ed.getFullEntityName()}], title=${title}, commonChars=${commonChars}, indexName=${indexName}")
            // logger.warn("Index for entity [${ed.getFullEntityName()}], relatedEntityName=${relatedEntityName}, relEndCommonChars=${relEndCommonChars}, indexName=${indexName}")
        }
        shrinkName(indexName, constraintNameClipLength - 3)
        indexName.insert(0, "IDX")
        return indexName.toString()
    }

    /** Loop through all known entities and for each that has an existing table check each foreign key to see if it
     * exists in the database, and if it doesn't but the related table does exist then add the foreign key. */
    int createForeignKeysForExistingTables() {
        int created = 0
        for (String en in efi.getAllEntityNames()) {
            EntityDefinition ed = efi.getEntityDefinition(en)
            if (ed.isViewEntity) continue
            if (tableExists(ed)) {
                int result = createForeignKeys(ed, true)
                created += result
            }
        }
        return created
    }
    int dropAllForeignKeys() {
        int dropped = 0
        for (String en in efi.getAllEntityNames()) {
            EntityDefinition ed = efi.getEntityDefinition(en)
            if (ed.isViewEntity) continue
            if (tableExists(ed)) {
                int result = dropForeignKeys(ed)
                logger.info("Dropped ${result} FKs for entity ${ed.fullEntityName}")
                dropped += result
            }
        }
        return dropped
    }


    Boolean foreignKeyExists(EntityDefinition ed, RelationshipInfo relInfo) {
        String groupName = ed.getEntityGroupName()
        EntityDefinition relEd = relInfo.relatedEd
        Connection con = null
        ResultSet ikSet1 = null
        ResultSet ikSet2 = null
        try {
            con = efi.getConnection(groupName)
            DatabaseMetaData dbData = con.getMetaData()

            // don't rely on constraint name, look at related table name, keys

            // get set of fields on main entity to match against (more unique than fields on related entity)
            Map keyMap = relInfo.keyMap
            Set<String> fieldNames = new HashSet(keyMap.keySet())
            Set<String> fkColsFound = new HashSet()

            ikSet1 = dbData.getImportedKeys(null, ed.getSchemaName(), ed.getTableName())
            while (ikSet1.next()) {
                String pkTable = ikSet1.getString("PKTABLE_NAME")
                // logger.info("FK exists [${ed.getFullEntityName()}] - [${relNode."@title"}${relEd.getFullEntityName()}] PKTABLE_NAME [${ikSet.getString("PKTABLE_NAME")}] PKCOLUMN_NAME [${ikSet.getString("PKCOLUMN_NAME")}] FKCOLUMN_NAME [${ikSet.getString("FKCOLUMN_NAME")}]")
                if (pkTable != relEd.getTableName() && pkTable != relEd.getTableName().toLowerCase()) continue
                String fkCol = ikSet1.getString("FKCOLUMN_NAME")
                fkColsFound.add(fkCol)
                for (String fn in fieldNames) {
                    String fnColName = ed.getColumnName(fn)
                    if (fnColName == fkCol || fnColName.toLowerCase() == fkCol) {
                        fieldNames.remove(fn)
                        break
                    }
                }
            }
            if (fieldNames.size() > 0) {
                // try with lower case table name
                ikSet2 = dbData.getImportedKeys(null, ed.getSchemaName(), ed.getTableName().toLowerCase())
                while (ikSet2.next()) {
                    String pkTable = ikSet2.getString("PKTABLE_NAME")
                    // logger.info("FK exists [${ed.getFullEntityName()}] - [${relNode."@title"}${relEd.getFullEntityName()}] PKTABLE_NAME [${ikSet.getString("PKTABLE_NAME")}] PKCOLUMN_NAME [${ikSet.getString("PKCOLUMN_NAME")}] FKCOLUMN_NAME [${ikSet.getString("FKCOLUMN_NAME")}]")
                    if (pkTable != relEd.getTableName() && pkTable != relEd.getTableName().toLowerCase()) continue
                    String fkCol = ikSet2.getString("FKCOLUMN_NAME")
                    fkColsFound.add(fkCol)
                    for (String fn in fieldNames) {
                        String fnColName = ed.getColumnName(fn)
                        if (fnColName == fkCol || fnColName.toLowerCase() == fkCol) {
                            fieldNames.remove(fn)
                            break
                        }
                    }
                }
            }

            // logger.info("Checking FK exists for entity [${ed.getFullEntityName()}] relationship [${relNode."@title"}${relEd.getFullEntityName()}] fields to match are [${keyMap.keySet()}] FK columns found [${fkColsFound}] final fieldNames (empty for match) [${fieldNames}]")

            // if we found all of the key-map field-names then fieldNames will be empty, and we have a full fk
            return (fieldNames.size() == 0)
        } catch (Exception e) {
            logger.error("Exception checking to see if foreign key exists for table ${ed.getTableName()}", e)
            return null
        } finally {
            if (ikSet1 != null && !ikSet1.isClosed()) ikSet1.close()
            if (ikSet2 != null && !ikSet2.isClosed()) ikSet2.close()
            if (con != null) con.close()
        }
    }
    String getForeignKeyName(EntityDefinition ed, RelationshipInfo relInfo) {
        String groupName = ed.getEntityGroupName()
        EntityDefinition relEd = relInfo.relatedEd
        Connection con = null
        ResultSet ikSet1 = null
        ResultSet ikSet2 = null
        try {
            con = efi.getConnection(groupName)
            DatabaseMetaData dbData = con.getMetaData()

            // don't rely on constraint name, look at related table name, keys

            // get set of fields on main entity to match against (more unique than fields on related entity)
            Map keyMap = relInfo.keyMap
            List<String> fieldNames = new ArrayList(keyMap.keySet())
            Map<String, Set<String>> fieldsByFkName = new HashMap<>()

            ikSet1 = dbData.getImportedKeys(null, ed.getSchemaName(), ed.getTableName())
            while (ikSet1.next()) {
                String pkTable = ikSet1.getString("PKTABLE_NAME")
                // logger.info("FK exists [${ed.getFullEntityName()}] - [${relNode."@title"}${relEd.getFullEntityName()}] PKTABLE_NAME [${ikSet.getString("PKTABLE_NAME")}] PKCOLUMN_NAME [${ikSet.getString("PKCOLUMN_NAME")}] FKCOLUMN_NAME [${ikSet.getString("FKCOLUMN_NAME")}]")
                if (pkTable != relEd.getTableName() && pkTable != relEd.getTableName().toLowerCase()) continue
                String fkCol = ikSet1.getString("FKCOLUMN_NAME")
                String fkName = ikSet1.getString("FK_NAME")
                // logger.warn("FK pktable ${pkTable} fkcol ${fkCol} fkName ${fkName}")
                if (!fkName) continue
                for (String fn in fieldNames) {
                    String fnColName = ed.getColumnName(fn)
                    if (fnColName == fkCol || fnColName.toLowerCase() == fkCol) {
                        CollectionUtilities.addToSetInMap(fkName, fn, fieldsByFkName)
                        break
                    }
                }
            }
            if (fieldNames.size() > 0) {
                // try with lower case table name
                ikSet2 = dbData.getImportedKeys(null, ed.getSchemaName(), ed.getTableName().toLowerCase())
                while (ikSet2.next()) {
                    String pkTable = ikSet2.getString("PKTABLE_NAME")
                    // logger.info("FK exists [${ed.getFullEntityName()}] - [${relNode."@title"}${relEd.getFullEntityName()}] PKTABLE_NAME [${ikSet.getString("PKTABLE_NAME")}] PKCOLUMN_NAME [${ikSet.getString("PKCOLUMN_NAME")}] FKCOLUMN_NAME [${ikSet.getString("FKCOLUMN_NAME")}]")
                    if (pkTable != relEd.getTableName() && pkTable != relEd.getTableName().toLowerCase()) continue
                    String fkCol = ikSet2.getString("FKCOLUMN_NAME")
                    String fkName = ikSet2.getString("FK_NAME")
                    // logger.warn("FK pktable ${pkTable} fkcol ${fkCol} fkName ${fkName}")
                    if (!fkName) continue
                    for (String fn in fieldNames) {
                        String fnColName = ed.getColumnName(fn)
                        if (fnColName == fkCol || fnColName.toLowerCase() == fkCol) {
                            CollectionUtilities.addToSetInMap(fkName, fn, fieldsByFkName)
                            break
                        }
                    }
                }
            }

            // logger.warn("fieldNames: ${fieldNames}"); logger.warn("fieldsByFkName: ${fieldsByFkName}")
            for (Map.Entry<String, Set<String>> entry in fieldsByFkName.entrySet()) {
                if (entry.value.containsAll(fieldNames)) return entry.key
            }
            return null
        } catch (Exception e) {
            logger.error("Exception getting foreign key name for table ${ed.getTableName()}", e)
            return null
        } finally {
            if (ikSet1 != null && !ikSet1.isClosed()) ikSet1.close()
            if (ikSet2 != null && !ikSet2.isClosed()) ikSet2.close()
            if (con != null) con.close()
        }
    }

    int createForeignKeys(EntityDefinition ed, boolean checkFkExists) {
        if (ed == null) throw new IllegalArgumentException("No EntityDefinition specified, cannot create foreign keys")
        if (ed.isViewEntity) throw new IllegalArgumentException("Cannot create foreign keys for a view entity")

        if (ed.getEfi().ecfi.getEci().artifactExecutionFacade.entityFkCreateDisabled()) return 0

        // NOTE: in order to get all FKs in place by the time they are used we will probably need to check all incoming
        //     FKs as well as outgoing because of entity use order, tables not rechecked after first hit, etc
        // NOTE2: with the createForeignKeysForExistingTables() method this isn't strictly necessary, that can be run
        //     after the system is run for a bit and/or all tables desired have been created and it will take care of it

        String groupName = ed.getEntityGroupName()
        MNode databaseNode = efi.getDatabaseNode(groupName)

        if (databaseNode.attribute("use-foreign-keys") == "false") return 0
        int constraintNameClipLength = (databaseNode.attribute("constraint-name-clip-length")?:"30") as int

        int created = 0
        for (RelationshipInfo relInfo in ed.getRelationshipsInfo(false)) {
            if (relInfo.type != "one") continue

            EntityDefinition relEd = relInfo.relatedEd
            if (!tableExists(relEd)) {
                if (logger.traceEnabled) logger.trace("Not creating foreign key from entity ${ed.getFullEntityName()} to related entity ${relEd.getFullEntityName()} because related entity does not yet have a table")
                continue
            }
            if (checkFkExists) {
                Boolean fkExists = foreignKeyExists(ed, relInfo)
                if (fkExists != null && fkExists) {
                    if (logger.traceEnabled) logger.trace("Not creating foreign key from entity ${ed.getFullEntityName()} to related entity ${relEd.getFullEntityName()} with title ${relInfo.relNode.attribute("title")} because it already exists (matched by key mappings)")
                    continue
                }
                // if we get a null back there was an error, and we'll try to create the FK, which may result in another error
            }

            String constraintName = makeFkConstraintName(ed, relInfo, constraintNameClipLength)

            Map keyMap = relInfo.keyMap
            List<String> keyMapKeys = new ArrayList(keyMap.keySet())
            StringBuilder sql = new StringBuilder("ALTER TABLE ").append(ed.getFullTableName()).append(" ADD ")
            if (databaseNode.attribute("fk-style") == "name_fk") {
                sql.append("FOREIGN KEY ").append(constraintName).append(" (")
                boolean isFirst = true
                for (String fieldName in keyMapKeys) {
                    if (isFirst) isFirst = false else sql.append(", ")
                    sql.append(ed.getColumnName(fieldName))
                }
                sql.append(")")
            } else {
                sql.append("CONSTRAINT ")
                if (databaseNode.attribute("use-schema-for-all") == "true") sql.append(ed.getSchemaName() ? ed.getSchemaName() + "." : "")
                sql.append(constraintName).append(" FOREIGN KEY (")
                boolean isFirst = true
                for (String fieldName in keyMapKeys) {
                    if (isFirst) isFirst = false else sql.append(", ")
                    sql.append(ed.getColumnName(fieldName))
                }
                sql.append(")")
            }
            sql.append(" REFERENCES ").append(relEd.getFullTableName()).append(" (")
            boolean isFirst = true
            for (String keyName in keyMapKeys) {
                if (isFirst) isFirst = false else sql.append(", ")
                sql.append(relEd.getColumnName((String) keyMap.get(keyName)))
            }
            sql.append(")")
            if (databaseNode.attribute("use-fk-initially-deferred") == "true") {
                sql.append(" INITIALLY DEFERRED")
            }

            runSqlUpdate(sql, groupName)
            created++
        }
        if (created > 0 && checkFkExists) logger.info("Created ${created} FKs for entity ${ed.fullEntityName}")
        return created
    }

    int dropForeignKeys(EntityDefinition ed) {
        if (ed == null) throw new IllegalArgumentException("No EntityDefinition specified, cannot drop foreign keys")
        if (ed.isViewEntity) throw new IllegalArgumentException("Cannot drop foreign keys for a view entity")

        // NOTE: in order to get all FKs in place by the time they are used we will probably need to check all incoming
        //     FKs as well as outgoing because of entity use order, tables not rechecked after first hit, etc
        // NOTE2: with the createForeignKeysForExistingTables() method this isn't strictly necessary, that can be run
        //     after the system is run for a bit and/or all tables desired have been created and it will take care of it

        String groupName = ed.getEntityGroupName()
        MNode databaseNode = efi.getDatabaseNode(groupName)

        if (databaseNode.attribute("use-foreign-keys") == "false") return 0
        int constraintNameClipLength = (databaseNode.attribute("constraint-name-clip-length")?:"30") as int

        int dropped = 0
        for (RelationshipInfo relInfo in ed.getRelationshipsInfo(false)) {
            if (relInfo.type != "one") continue

            EntityDefinition relEd = relInfo.relatedEd
            if (!tableExists(relEd)) {
                if (logger.traceEnabled) logger.trace("Not dropping foreign key from entity ${ed.getFullEntityName()} to related entity ${relEd.getFullEntityName()} because related entity does not yet have a table")
                continue
            }
            Boolean fkExists = foreignKeyExists(ed, relInfo)
            if (fkExists != null && !fkExists) {
                if (logger.traceEnabled) logger.trace("Not dropping foreign key from entity ${ed.getFullEntityName()} to related entity ${relEd.getFullEntityName()} with title ${relInfo.relNode.attribute("title")} because it does not exist (matched by key mappings)")
                continue
            }

            String fkName = getForeignKeyName(ed, relInfo)
            String constraintName = fkName ?: makeFkConstraintName(ed, relInfo, constraintNameClipLength)

            StringBuilder sql = new StringBuilder("ALTER TABLE ").append(ed.getFullTableName()).append(" DROP ")
            if (databaseNode.attribute("fk-style") == "name_fk") {
                sql.append("FOREIGN KEY ").append(constraintName.toString())
            } else {
                sql.append("CONSTRAINT ")
                if (databaseNode.attribute("use-schema-for-all") == "true") sql.append(ed.getSchemaName() ? ed.getSchemaName() + "." : "")
                sql.append(constraintName.toString())
            }

            Integer records = runSqlUpdate(sql, groupName)
            if (records != null) dropped++
        }
        return dropped
    }

    static String makeFkConstraintName(EntityDefinition ed, RelationshipInfo relInfo, int constraintNameClipLength) {
        StringBuilder constraintName = new StringBuilder()
        if (relInfo.relNode.attribute("fk-name")) constraintName.append(relInfo.relNode.attribute("fk-name"))
        if (!constraintName) {
            EntityDefinition relEd = relInfo.relatedEd
            String title = relInfo.title ?: ""
            String edEntityName = ed.entityInfo.internalEntityName
            int commonChars = 0
            while (title.length() > commonChars && edEntityName.length() > commonChars &&
                    title.charAt(commonChars) == edEntityName.charAt(commonChars)) commonChars++
            String relatedEntityName = relEd.entityInfo.internalEntityName
            if (commonChars > 0) {
                constraintName.append(ed.entityInfo.internalEntityName)
                for (char cc in title.substring(0, commonChars).chars) if (Character.isUpperCase(cc)) constraintName.append(cc)
                constraintName.append(title.substring(commonChars)).append(relatedEntityName)
            } else {
                constraintName.append(ed.entityInfo.internalEntityName).append(title).append(relatedEntityName)
            }
            // logger.warn("ed.getFullEntityName()=${ed.entityName}, title=${title}, commonChars=${commonChars}, constraintName=${constraintName}")
        }
        shrinkName(constraintName, constraintNameClipLength)
        return constraintName.toString()
    }

    static void shrinkName(StringBuilder name, int maxLength) {
        if (name.length() > maxLength) {
            // remove vowels from end toward beginning
            for (int i = name.length()-1; i >= 0 && name.length() > maxLength; i--) {
                if ("AEIOUaeiou".contains(name.charAt(i) as String)) name.deleteCharAt(i)
            }
            // clip
            if (name.length() > maxLength) {
                name.delete(maxLength-1, name.length())
            }
        }
    }

    final ReentrantLock sqlLock = new ReentrantLock()
    Integer runSqlUpdate(CharSequence sql, String groupName) {
        // only do one DB meta data operation at a time; may lock above before checking for existence of something to make sure it doesn't get created twice
        sqlLock.lock()
        Integer records = null
        try {
            // use a short timeout here just in case this is in the middle of stuff going on with tables locked, may happen a lot for FK ops
            efi.ecfi.transactionFacade.runRequireNew(10, "Error in DB meta data change", useTxForMetaData, true, {
                Connection con = null
                Statement stmt = null

                try {
                    con = efi.getConnection(groupName)
                    stmt = con.createStatement()
                    records = stmt.executeUpdate(sql.toString())
                } finally {
                    if (stmt != null) stmt.close()
                    if (con != null) con.close()
                }
            })
        } catch (Throwable t) {
            logger.error("SQL Exception while executing the following SQL [${sql.toString()}]: ${t.toString()}")
        } finally {
            sqlLock.unlock()
        }
        return records
    }

    /* ================= */
    /* Liquibase Methods */
    /* ================= */

    /** Generate a Liquibase Changelog for a set of entity definitions */
    MNode liquibaseInitChangelog(String filterRegexp) {
        MNode rootNode = new MNode("databaseChangeLog", [xmlns:"http://www.liquibase.org/xml/ns/dbchangelog",
                "xmlns:xsi":"http://www.w3.org/2001/XMLSchema-instance", "xmlns:ext":"http://www.liquibase.org/xml/ns/dbchangelog-ext",
                "xsi:schemaLocation":"http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-2.0.xsd http://www.liquibase.org/xml/ns/dbchangelog-ext http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-ext.xsd"])

        // add property elements for data type dictionary entry for each database
        // see http://www.liquibase.org/documentation/changelog_parameters.html
        // see http://www.liquibase.org/databases.html
        // <property name="clob.type" value="clob" dbms="oracle"/>
        MNode databaseListNode = efi.ecfi.confXmlRoot.first("database-list")
        ArrayList<MNode> dictTypeList = databaseListNode.children("dictionary-type")
        ArrayList<MNode> databaseList = databaseListNode.children("database")
        for (MNode dictType in dictTypeList) {
            String type = dictType.attribute("type")
            String propName = "type." + type.replaceAll("-", "_")
            Set<String> dbmsDefault = new TreeSet<>()
            for (MNode database in databaseList) {
                String lbName = database.attribute("lb-name") ?: database.attribute("name")
                MNode dbTypeNode = database.first({ MNode it -> it.name == 'database-type' && it.attribute("type") == type })
                if (dbTypeNode != null) {
                    rootNode.append("property", [name:propName, value:dbTypeNode.attribute("sql-type"), dbms:lbName])
                } else {
                    dbmsDefault.add(lbName)
                }
            }
            if (dbmsDefault.size() > 0)
                rootNode.append("property", [name:propName, value:dictType.attribute("default-sql-type"),
                        dbms:dbmsDefault.join(",")])
        }

        String dateStr = efi.ecfi.l10n.format(new Timestamp(System.currentTimeMillis()), "yyyyMMdd")
        int changeSetIdx = 1
        Set<String> entityNames = efi.getAllEntityNames(filterRegexp)

        // add changeSet per entity
        // see http://www.liquibase.org/documentation/generating_changelogs.html
        for (String en in entityNames) {
            EntityDefinition ed = null
            try { ed = efi.getEntityDefinition(en) } catch (EntityException e) { logger.warn("Problem finding entity definition", e) }
            if (ed == null || ed.isViewEntity) continue

            MNode changeSet = rootNode.append("changeSet", [author:"moqui-init", id:"${dateStr}-${changeSetIdx}".toString()])
            changeSetIdx++

            // createTable
            MNode createTable = changeSet.append("createTable", [name:ed.getTableName()])
            if (ed.getSchemaName()) createTable.attributes.put("schemaName", ed.getSchemaName())
            FieldInfo[] allFieldInfoArray = ed.entityInfo.allFieldInfoArray
            for (int i = 0; i < allFieldInfoArray.length; i++) {
                FieldInfo fi = (FieldInfo) allFieldInfoArray[i]
                MNode fieldNode = fi.fieldNode
                MNode column = createTable.append("column", [name:fi.columnName, type:('${type.' + fi.type.replaceAll("-", "_") + '}')])
                if (fi.isPk || fieldNode.attribute("not-null") == "true") {
                    MNode constraints = column.append("constraints", [nullable:"false"])
                    if (fi.isPk) constraints.attributes.put("primaryKey", "true")
                }
            }

            // createIndex: first do index elements
            for (MNode indexNode in ed.entityNode.children("index")) {
                MNode createIndex = changeSet.append("createIndex",
                        [indexName:indexNode.attribute("name"), tableName:ed.getTableName()])
                if (ed.getSchemaName()) createIndex.attributes.put("schemaName", ed.getSchemaName())
                createIndex.attributes.put("unique", indexNode.attribute("unique") ?: "false")

                for (MNode indexFieldNode in indexNode.children("index-field"))
                    createIndex.append("column", [name:ed.getColumnName(indexFieldNode.attribute("name"))])
            }

            // do fk auto indexes
            for (RelationshipInfo relInfo in ed.getRelationshipsInfo(false)) {
                if (relInfo.type != "one") continue

                String indexName = makeFkIndexName(ed, relInfo, 30)

                MNode createIndex = changeSet.append("createIndex",
                        [indexName:indexName, tableName:ed.getTableName(), unique:"false"])
                if (ed.getSchemaName()) createIndex.attributes.put("schemaName", ed.getSchemaName())

                Map keyMap = relInfo.keyMap
                for (String fieldName in keyMap.keySet())
                    createIndex.append("column", [name:ed.getColumnName(fieldName)])
            }
        }

        // do foreign keys in a separate pass
        for (String en in entityNames) {
            EntityDefinition ed = null
            try { ed = efi.getEntityDefinition(en) } catch (EntityException e) { logger.warn("Problem finding entity definition", e) }
            if (ed == null || ed.isViewEntity) continue

            MNode changeSet = rootNode.append("changeSet", [author:"moqui-init", id:"${dateStr}-${changeSetIdx}".toString()])
            changeSetIdx++

            for (RelationshipInfo relInfo in ed.getRelationshipsInfo(false)) {
                if (relInfo.type != "one") continue

                EntityDefinition relEd = relInfo.relatedEd
                String constraintName = makeFkConstraintName(ed, relInfo, 30)
                Map keyMap = relInfo.keyMap
                List<String> keyMapKeys = new ArrayList(keyMap.keySet())

                StringBuilder baseNames = new StringBuilder()
                for (String fieldName in keyMapKeys) {
                    if (baseNames.length() > 0) baseNames.append(",")
                    baseNames.append(ed.getColumnName(fieldName))
                }
                StringBuilder referencedNames = new StringBuilder()
                for (String keyName in keyMapKeys) {
                    if (referencedNames.length() > 0) referencedNames.append(",")
                    referencedNames.append(relEd.getColumnName((String) keyMap.get(keyName)))
                }

                MNode addForeignKeyConstraint = changeSet.append("addForeignKeyConstraint", [baseTableName:ed.getTableName(),
                        baseColumnNames:baseNames.toString(), constraintName:constraintName,
                        referencedTableName:relEd.getTableName(), referencedColumnNames:referencedNames.toString()])
                if (ed.getSchemaName()) addForeignKeyConstraint.attributes.put("baseTableSchemaName", ed.getSchemaName())
                if (relEd.getSchemaName()) addForeignKeyConstraint.attributes.put("referencedTableSchemaName", relEd.getSchemaName())
            }
        }

        return rootNode
    }
    MNode liquibaseDiffChangelog(String filterRegexp) {
        return null
    }
}
