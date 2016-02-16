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
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.EntityDefinition.RelationshipInfo
import org.moqui.util.MNode

import java.sql.SQLException
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
    protected Map entityTablesChecked = new HashMap()
    // a separate Map for tables checked to exist only (used in finds) so repeated checks are needed for unused entities
    protected Map<String, Boolean> entityTablesExist = new HashMap<>()

    protected Map<String, Boolean> runtimeAddMissingMap = new HashMap<>()

    protected EntityFacadeImpl efi

    EntityDbMeta(EntityFacadeImpl efi) {
        this.efi = efi
        // this is nice as a cache but slower and checked MANY times with lots of entity/db traffic:
        // entityTablesChecked = efi.ecfi.cacheFacade.getCache("entity.${efi.tenantId}.tables.checked")
    }

    @CompileStatic
    void checkTableRuntime(EntityDefinition ed) {
        String groupName = ed.getEntityGroupName()
        Boolean runtimeAddMissing = (Boolean) runtimeAddMissingMap.get(groupName)
        if (runtimeAddMissing == null) {
            MNode datasourceNode = efi.getDatasourceNode(groupName)
            runtimeAddMissing = datasourceNode?.attribute('runtime-add-missing') != "false"
            runtimeAddMissingMap.put(groupName, runtimeAddMissing)
        }
        if (runtimeAddMissing == null || runtimeAddMissing == Boolean.FALSE) return

        if (ed.isViewEntity()) {
            for (MNode memberEntityNode in ed.entityNode.children("member-entity")) {
                EntityDefinition med = efi.getEntityDefinition(memberEntityNode.attribute('entity-name'))
                checkTableRuntime(med)
            }
        } else {
            // if it's in this table we've already checked it
            if (entityTablesChecked.containsKey(ed.getFullEntityName())) return
            // otherwise do the real check, in a synchronized method
            internalCheckTable(ed, false)
        }
    }
    void checkTableStartup(EntityDefinition ed) {
        if (ed.isViewEntity()) {
            for (MNode memberEntityNode in ed.entityNode.children("member-entity")) {
                EntityDefinition med = efi.getEntityDefinition(memberEntityNode.attribute("entity-name"))
                checkTableStartup(med)
            }
        } else {
            internalCheckTable(ed, true)
        }
    }

    @CompileStatic
    void forceCheckTableRuntime(EntityDefinition ed) {
        entityTablesChecked.remove(ed.getFullEntityName())
        checkTableRuntime(ed)
    }

    void forceCheckExistingTables() {
        entityTablesChecked.clear()
        for (String entityName in efi.getAllEntityNames()) {
            EntityDefinition ed = efi.getEntityDefinition(entityName)
            if (tableExists(ed)) checkTableRuntime(ed)
        }
    }

    @CompileStatic
    synchronized void internalCheckTable(EntityDefinition ed, boolean startup) {
        // if it's in this table we've already checked it
        if (entityTablesChecked.containsKey(ed.getFullEntityName())) return

        MNode datasourceNode = efi.getDatasourceNode(ed.getEntityGroupName())
        // if there is no @database-conf-name skip this, it's probably not a SQL/JDBC datasource
        if (!datasourceNode.attribute('database-conf-name')) return

        long startTime = System.currentTimeMillis()
        if (!tableExists(ed)) {
            createTable(ed)
            // create explicit and foreign key auto indexes
            createIndexes(ed)
            // create foreign keys to all other tables that exist
            createForeignKeys(ed, false)
        } else {
            // table exists, see if it is missing any columns
            List<String> mcs = getMissingColumns(ed)
            if (mcs) for (String fieldName in mcs) addColumn(ed, fieldName)
            // create foreign keys after checking each to see if it already exists
            if (startup || datasourceNode?.attribute('runtime-add-fks') == "true") createForeignKeys(ed, true)
        }
        entityTablesChecked.put(ed.getFullEntityName(), new Timestamp(System.currentTimeMillis()))
        entityTablesExist.put(ed.getFullEntityName(), true)

        if (logger.isTraceEnabled()) logger.trace("Checked table for entity [${ed.getFullEntityName()}] in ${(System.currentTimeMillis()-startTime)/1000} seconds")
    }

    @CompileStatic
    boolean tableExists(EntityDefinition ed) {
        Boolean exists = entityTablesExist.get(ed.getFullEntityName())
        if (exists != null) return exists.booleanValue()

        return tableExistsInternal(ed)
    }
    synchronized boolean tableExistsInternal(EntityDefinition ed) {
        Boolean exists = entityTablesExist.get(ed.getFullEntityName())
        if (exists != null) return exists.booleanValue()

        Boolean dbResult = null
        if (ed.isViewEntity()) {
            boolean anyExist = false
            for (MNode memberEntityNode in ed.entityNode.children("member-entity")) {
                EntityDefinition med = efi.getEntityDefinition(memberEntityNode.attribute("entity-name"))
                if (tableExists(med)) { anyExist = true; break }
            }
            dbResult = anyExist
        } else {
            String groupName = ed.getEntityGroupName()
            Connection con = null
            ResultSet tableSet = null
            boolean beganTx = useTxForMetaData ? efi.ecfi.transactionFacade.begin(5) : false
            try {
                con = efi.getConnection(groupName)
                DatabaseMetaData dbData = con.getMetaData()

                String[] types = ["TABLE", "VIEW", "ALIAS", "SYNONYM"]
                tableSet = dbData.getTables(null, ed.getSchemaName(), ed.getTableName(), types)
                if (tableSet.next()) {
                    dbResult = true
                } else {
                    // try lower case, just in case DB is case sensitive
                    tableSet = dbData.getTables(null, ed.getSchemaName(), ed.getTableName().toLowerCase(), types)
                    if (tableSet.next()) {
                        dbResult = true
                    } else {
                        if (logger.isTraceEnabled()) logger.trace("Table for entity [${ed.getFullEntityName()}] does NOT exist")
                        dbResult = false
                    }
                }
            } catch (Exception e) {
                throw new EntityException("Exception checking to see if table [${ed.getTableName()}] exists", e)
            } finally {
                if (tableSet != null) tableSet.close()
                if (con != null) con.close()
                if (beganTx) efi.ecfi.transactionFacade.commit()
            }
        }

        if (dbResult == null) throw new EntityException("No result checking if entity [${ed.getFullEntityName()}] table exists")

        if (dbResult && !ed.isViewEntity()) {
            // on the first check also make sure all columns/etc exist; we'll do this even on read/exist check otherwise query will blow up when doesn't exist
            List<String> mcs = getMissingColumns(ed)
            if (mcs) for (String fieldName in mcs) addColumn(ed, fieldName)
        }
        // don't remember the result for view-entities, get if from member-entities... if we remember it we have to set
        //     it for all view-entities when a member-entity is created
        if (!ed.isViewEntity()) entityTablesExist.put(ed.getFullEntityName(), dbResult)
        return dbResult
    }

    void createTable(EntityDefinition ed) {
        if (ed == null) throw new IllegalArgumentException("No EntityDefinition specified, cannot create table")
        if (ed.isViewEntity()) throw new IllegalArgumentException("Cannot create table for a view entity")

        String groupName = ed.getEntityGroupName()
        MNode databaseNode = efi.getDatabaseNode(groupName)

        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(ed.getFullTableName()).append(" (")

        for (String fieldName in ed.getFieldNames(true, true, false)) {
            MNode fieldNode = ed.getFieldNode(fieldName)
            String sqlType = efi.getFieldSqlType(fieldNode.attribute("type"), ed)
            String javaType = efi.getFieldJavaType(fieldNode.attribute("type"), ed)

            sql.append(ed.getColumnName(fieldName, false)).append(" ").append(sqlType)

            if ("String" == javaType || "java.lang.String" == javaType) {
                if (databaseNode.attribute("character-set")) sql.append(" CHARACTER SET ").append(databaseNode.attribute("character-set"))
                if (databaseNode.attribute("collate")) sql.append(" COLLATE ").append(databaseNode.attribute("collate"))
            }

            if (fieldNode.attribute("is-pk") == "true") {
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
        boolean isFirstPk = true
        logger.info("Creating ${ed.getFullEntityName()} pks: ${ed.getPkFieldNames()}")
        for (String pkName in ed.getPkFieldNames()) {
            if (isFirstPk) isFirstPk = false else sql.append(", ")
            sql.append(ed.getColumnName(pkName, false))
        }
        sql.append("))")

        // some MySQL-specific inconveniences...
        if (databaseNode.attribute("table-engine")) sql.append(" ENGINE ").append(databaseNode.attribute("table-engine"))
        if (databaseNode.attribute("character-set")) sql.append(" CHARACTER SET ").append(databaseNode.attribute("character-set"))
        if (databaseNode.attribute("collate")) sql.append(" COLLATE ").append(databaseNode.attribute("collate"))

        if (logger.traceEnabled) logger.trace("Create Table with SQL: " + sql.toString())

        runSqlUpdate(sql, groupName)
        if (logger.infoEnabled) logger.info("Created table [${ed.tableName}] for entity [${ed.getFullEntityName()}]")
    }

    List<String> getMissingColumns(EntityDefinition ed) {
        if (ed.isViewEntity()) return new ArrayList<String>()

        String groupName = ed.getEntityGroupName()
        Connection con = null
        ResultSet colSet = null
        boolean beganTx = useTxForMetaData ? efi.ecfi.transactionFacade.begin(5) : false
        try {
            con = efi.getConnection(groupName)
            DatabaseMetaData dbData = con.getMetaData()
            // con.setAutoCommit(false)

            List<String> fnSet = new ArrayList(ed.getFieldNames(true, true, false))
            int fieldCount = fnSet.size()
            colSet = dbData.getColumns(null, ed.getSchemaName(), ed.getTableName(), "%")
            if (colSet.isClosed()) {
                logger.error("Tried to get columns for entity ${ed.getFullEntityName()} but ResultSet was closed!")
                return new ArrayList<String>()
            }
            while (colSet.next()) {
                String colName = colSet.getString("COLUMN_NAME")
                for (String fn in fnSet) {
                    String fieldColName = ed.getColumnName(fn, false)
                    if (fieldColName == colName || fieldColName.toLowerCase() == colName) {
                        fnSet.remove(fn)
                        break
                    }
                }
            }

            if (fnSet.size() == fieldCount) {
                // try lower case table name
                colSet = dbData.getColumns(null, ed.getSchemaName(), ed.getTableName().toLowerCase(), "%")
                if (colSet.isClosed()) {
                    logger.error("Tried to get columns for entity ${ed.getFullEntityName()} but ResultSet was closed!")
                    return new ArrayList<String>()
                }
                while (colSet.next()) {
                    String colName = colSet.getString("COLUMN_NAME")
                    for (String fn in fnSet) {
                        String fieldColName = ed.getColumnName(fn, false)
                        if (fieldColName == colName || fieldColName.toLowerCase() == colName) {
                            fnSet.remove(fn)
                            break
                        }
                    }
                }

                if (fnSet.size() == fieldCount) {
                    logger.warn("Could not find any columns to match fields for entity [${ed.getFullEntityName()}]")
                    return null
                }
            }
            return fnSet
        } catch (Exception e) {
            logger.error("Exception checking for missing columns in table [${ed.getTableName()}]", e)
            return new ArrayList<String>()
        } finally {
            if (colSet != null && !colSet.isClosed()) colSet.close()
            if (con != null && !con.isClosed()) con.close()
            if (beganTx) efi.ecfi.transactionFacade.commit()
        }
    }

    void addColumn(EntityDefinition ed, String fieldName) {
        if (ed == null) throw new IllegalArgumentException("No EntityDefinition specified, cannot add column")
        if (ed.isViewEntity()) throw new IllegalArgumentException("Cannot add column for a view entity")

        String groupName = ed.getEntityGroupName()
        MNode databaseNode = efi.getDatabaseNode(groupName)

        MNode fieldNode = ed.getFieldNode(fieldName)

        if (fieldNode.attribute("is-user-field") == "true") throw new IllegalArgumentException("Cannot add column for a UserField")

        String sqlType = efi.getFieldSqlType(fieldNode.attribute("type"), ed)
        String javaType = efi.getFieldJavaType(fieldNode.attribute("type"), ed)

        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(ed.getFullTableName())
        // NOTE: if any databases need "ADD COLUMN" instead of just "ADD", change this to try both or based on config
        sql.append(" ADD ").append(ed.getColumnName(fieldName, false)).append(" ").append(sqlType)

        if ("String" == javaType || "java.lang.String" == javaType) {
            if (databaseNode.attribute("character-set")) sql.append(" CHARACTER SET ").append(databaseNode.attribute("character-set"))
            if (databaseNode.attribute("collate")) sql.append(" COLLATE ").append(databaseNode.attribute("collate"))
        }

        runSqlUpdate(sql, groupName)
        if (logger.infoEnabled) logger.info("Added column [${ed.getColumnName(fieldName, false)}] to table [${ed.tableName}] for field [${fieldName}] of entity [${ed.getFullEntityName()}]")
    }

    void createIndexes(EntityDefinition ed) {
        if (ed == null) throw new IllegalArgumentException("No EntityDefinition specified, cannot create indexes")
        if (ed.isViewEntity()) throw new IllegalArgumentException("Cannot create indexes for a view entity")

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
                sql.append(ed.getColumnName(indexFieldNode.attribute("name"), false))
            }
            sql.append(")")

            runSqlUpdate(sql, groupName)
        }

        // do fk auto indexes
        if (databaseNode.attribute("use-foreign-key-indexes") == "false") return
        for (RelationshipInfo relInfo in ed.getRelationshipsInfo(false)) {
            if (relInfo.type != "one") continue
            String relatedEntityName = relInfo.relatedEntityName

            StringBuilder indexName = new StringBuilder()
            if (relInfo.relNode.attribute("fk-name")) indexName.append(relInfo.relNode.attribute("fk-name"))
            if (!indexName) {
                String title = relInfo.title
                String entityName = ed.getEntityName()

                int commonChars = 0
                while (title.length() > commonChars && entityName.length() > commonChars &&
                        title.charAt(commonChars) == entityName.charAt(commonChars)) commonChars++

                if (relatedEntityName.contains("."))
                    relatedEntityName = relatedEntityName.substring(relatedEntityName.lastIndexOf(".") + 1)

                int relLength = relatedEntityName.length()
                int relEndCommonChars = relatedEntityName.length() - 1
                while (relEndCommonChars > 0 && entityName.length() > relEndCommonChars &&
                        relatedEntityName.charAt(relEndCommonChars) == entityName.charAt(entityName.length() - (relLength - relEndCommonChars)))
                    relEndCommonChars--

                if (commonChars > 0) {
                    indexName.append(ed.entityName)
                    for (char cc in title.substring(0, commonChars).chars) if (cc.isUpperCase()) indexName.append(cc)
                    indexName.append(title.substring(commonChars))
                    indexName.append(relatedEntityName.substring(0, relEndCommonChars + 1))
                    if (relEndCommonChars < (relLength - 1)) for (char cc in relatedEntityName.substring(relEndCommonChars + 1).chars)
                        if (cc.isUpperCase()) indexName.append(cc)
                } else {
                    indexName.append(ed.entityName).append(title)
                    indexName.append(relatedEntityName.substring(0, relEndCommonChars + 1))
                    if (relEndCommonChars < (relLength - 1)) for (char cc in relatedEntityName.substring(relEndCommonChars + 1).chars)
                        if (cc.isUpperCase()) indexName.append(cc)
                }

                // logger.warn("Index for entity [${ed.getFullEntityName()}], title=${title}, commonChars=${commonChars}, indexName=${indexName}")
                // logger.warn("Index for entity [${ed.getFullEntityName()}], relatedEntityName=${relatedEntityName}, relEndCommonChars=${relEndCommonChars}, indexName=${indexName}")
            }
            shrinkName(indexName, constraintNameClipLength - 3)
            indexName.insert(0, "IDX")

            StringBuilder sql = new StringBuilder("CREATE INDEX ")
            if (databaseNode.attribute("use-schema-for-all") == "true") sql.append(ed.getSchemaName() ? ed.getSchemaName() + "." : "")
            sql.append(indexName.toString()).append(" ON ").append(ed.getFullTableName())

            sql.append(" (")
            Map keyMap = relInfo.keyMap
            boolean isFirst = true
            for (String fieldName in keyMap.keySet()) {
                if (isFirst) isFirst = false else sql.append(", ")
                sql.append(ed.getColumnName(fieldName, false))
            }
            sql.append(")")

            // logger.warn("====== create relationship index [${indexName}] for entity [${ed.getFullEntityName()}]")
            runSqlUpdate(sql, groupName)
        }
    }

    /** Loop through all known entities and for each that has an existing table check each foreign key to see if it
     * exists in the database, and if it doesn't but the related table does then add the foreign key. */
    void createForeignKeysForExistingTables() {
        for (String en in efi.getAllEntityNames()) {
            EntityDefinition ed = efi.getEntityDefinition(en)
            if (tableExists(ed)) createForeignKeys(ed, true)
        }
    }

    Boolean foreignKeyExists(EntityDefinition ed, RelationshipInfo relInfo) {
        String groupName = ed.getEntityGroupName()
        EntityDefinition relEd = relInfo.relatedEd
        Connection con = null
        ResultSet ikSet = null
        try {
            con = efi.getConnection(groupName)
            DatabaseMetaData dbData = con.getMetaData()

            // don't rely on constraint name, look at related table name, keys

            // get set of fields on main entity to match against (more unique than fields on related entity)
            Map keyMap = relInfo.keyMap
            Set<String> fieldNames = new HashSet(keyMap.keySet())
            Set<String> fkColsFound = new HashSet()

            ikSet = dbData.getImportedKeys(null, ed.getSchemaName(), ed.getTableName())
            while (ikSet.next()) {
                String pkTable = ikSet.getString("PKTABLE_NAME")
                // logger.info("FK exists [${ed.getFullEntityName()}] - [${relNode."@title"}${relEd.getFullEntityName()}] PKTABLE_NAME [${ikSet.getString("PKTABLE_NAME")}] PKCOLUMN_NAME [${ikSet.getString("PKCOLUMN_NAME")}] FKCOLUMN_NAME [${ikSet.getString("FKCOLUMN_NAME")}]")
                if (pkTable != relEd.getTableName() && pkTable != relEd.getTableName().toLowerCase()) continue
                String fkCol = ikSet.getString("FKCOLUMN_NAME")
                fkColsFound.add(fkCol)
                for (String fn in fieldNames) {
                    String fnColName = ed.getColumnName(fn, false)
                    if (fnColName == fkCol || fnColName.toLowerCase() == fkCol) {
                        fieldNames.remove(fn)
                        break
                    }
                }
            }
            if (fieldNames.size() > 0) {
                // try with lower case table name
                ikSet = dbData.getImportedKeys(null, ed.getSchemaName(), ed.getTableName().toLowerCase())
                while (ikSet.next()) {
                    String pkTable = ikSet.getString("PKTABLE_NAME")
                    // logger.info("FK exists [${ed.getFullEntityName()}] - [${relNode."@title"}${relEd.getFullEntityName()}] PKTABLE_NAME [${ikSet.getString("PKTABLE_NAME")}] PKCOLUMN_NAME [${ikSet.getString("PKCOLUMN_NAME")}] FKCOLUMN_NAME [${ikSet.getString("FKCOLUMN_NAME")}]")
                    if (pkTable != relEd.getTableName() && pkTable != relEd.getTableName().toLowerCase()) continue
                    String fkCol = ikSet.getString("FKCOLUMN_NAME")
                    fkColsFound.add(fkCol)
                    for (String fn in fieldNames) {
                        String fnColName = ed.getColumnName(fn, false)
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
            logger.error("Exception checking to see if foreign key exists for table [${ed.getTableName()}]", e)
            return null
        } finally {
            if (ikSet != null) ikSet.close()
            if (con != null) con.close()
        }
    }

    void createForeignKeys(EntityDefinition ed, boolean checkFkExists) {
        if (ed == null) throw new IllegalArgumentException("No EntityDefinition specified, cannot create foreign keys")
        if (ed.isViewEntity()) throw new IllegalArgumentException("Cannot create foreign keys for a view entity")

        // NOTE: in order to get all FKs in place by the time they are used we will probably need to check all incoming
        //     FKs as well as outgoing because of entity use order, tables not rechecked after first hit, etc
        // NOTE2: with the createForeignKeysForExistingTables() method this isn't strictly necessary, that can be run
        //     after the system is run for a bit and/or all tables desired have been created and it will take care of it

        String groupName = ed.getEntityGroupName()
        MNode databaseNode = efi.getDatabaseNode(groupName)

        if (databaseNode.attribute("use-foreign-keys") == "false") return

        int constraintNameClipLength = (databaseNode.attribute("constraint-name-clip-length")?:"30") as int

        for (RelationshipInfo relInfo in ed.getRelationshipsInfo(false)) {
            if (relInfo.type != "one") continue

            EntityDefinition relEd = relInfo.relatedEd
            if (!tableExists(relEd)) {
                if (logger.traceEnabled) logger.trace("Not creating foreign key from entity [${ed.getFullEntityName()}] to related entity [${relEd.getFullEntityName()}] because related entity does not yet have a table for it")
                continue
            }
            if (checkFkExists) {
                Boolean fkExists = foreignKeyExists(ed, relInfo)
                if (fkExists != null && fkExists) {
                    if (logger.traceEnabled) logger.trace("Not creating foreign key from entity [${ed.getFullEntityName()}] to related entity [${relEd.getFullEntityName()}] with title [${relInfo.relNode.attribute("title")}] because it already exists (matched by key mappings)")
                    continue
                }
                // if we get a null back there was an error, and we'll try to create the FK, which may result in another error
            }

            StringBuilder constraintName = new StringBuilder()
            if (relInfo.relNode.attribute("fk-name")) constraintName.append(relInfo.relNode.attribute("fk-name"))
            if (!constraintName) {
                String title = relInfo.title
                int commonChars = 0
                while (title.length() > commonChars && ed.entityName.length() > commonChars &&
                        title.charAt(commonChars) == ed.entityName.charAt(commonChars)) commonChars++
                // related-entity-name may have the entity's package-name in it; if so, remove it
                String relatedEntityName = relInfo.relatedEntityName
                if (relatedEntityName.contains("."))
                    relatedEntityName = relatedEntityName.substring(relatedEntityName.lastIndexOf(".")+1)
                if (commonChars > 0) {
                    constraintName.append(ed.entityName)
                    for (char cc in title.substring(0, commonChars).chars) if (cc.isUpperCase()) constraintName.append(cc)
                    constraintName.append(title.substring(commonChars)).append(relatedEntityName)
                } else {
                    constraintName.append(ed.entityName).append(title).append(relatedEntityName)
                }
                // logger.warn("ed.getFullEntityName()=${ed.entityName}, title=${title}, commonChars=${commonChars}, constraintName=${constraintName}")
            }
            shrinkName(constraintName, constraintNameClipLength)

            Map keyMap = relInfo.keyMap
            List<String> keyMapKeys = new ArrayList(keyMap.keySet())
            StringBuilder sql = new StringBuilder("ALTER TABLE ").append(ed.getFullTableName()).append(" ADD ")
            if (databaseNode.attribute("fk-style") == "name_fk") {
                sql.append(" FOREIGN KEY ").append(constraintName.toString()).append(" (")
                boolean isFirst = true
                for (String fieldName in keyMapKeys) {
                    if (isFirst) isFirst = false else sql.append(", ")
                    sql.append(ed.getColumnName(fieldName, false))
                }
                sql.append(")")
            } else {
                sql.append("CONSTRAINT ")
                if (databaseNode.attribute("use-schema-for-all") == "true") sql.append(ed.getSchemaName() ? ed.getSchemaName() + "." : "")
                sql.append(constraintName.toString()).append(" FOREIGN KEY (")
                boolean isFirst = true
                for (String fieldName in keyMapKeys) {
                    if (isFirst) isFirst = false else sql.append(", ")
                    sql.append(ed.getColumnName(fieldName, false))
                }
                sql.append(")");
            }
            sql.append(" REFERENCES ").append(relEd.getFullTableName()).append(" (")
            boolean isFirst = true
            for (String keyName in keyMapKeys) {
                if (isFirst) isFirst = false else sql.append(", ")
                sql.append(relEd.getColumnName((String) keyMap.get(keyName), false))
            }
            sql.append(")")
            if (databaseNode.attribute("use-fk-initially-deferred") == "true") {
                sql.append(" INITIALLY DEFERRED")
            }

            runSqlUpdate(sql, groupName)
        }
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
    int runSqlUpdate(CharSequence sql, String groupName) {
        // only do one DB meta data operation at a time; may lock above before checking for existence of something to make sure it doesn't get created twice
        sqlLock.lock()
        int records = 0
        try {
            // use a short timeout here just in case this is in the middle of stuff going on with tables locked, may happen a lot for FK ops
            efi.ecfi.getTransactionFacade().runRequireNew(10, "Error in DB meta data change", useTxForMetaData, true, {
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
}
