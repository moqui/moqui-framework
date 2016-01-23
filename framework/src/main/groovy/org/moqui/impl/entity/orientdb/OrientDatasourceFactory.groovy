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

import com.orientechnologies.orient.core.db.OPartitionedDatabasePool
import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx
import com.orientechnologies.orient.core.metadata.schema.OClass
import com.orientechnologies.orient.core.metadata.schema.OProperty
import com.orientechnologies.orient.core.metadata.schema.OType
import com.orientechnologies.orient.server.OServer
import com.orientechnologies.orient.server.OServerMain
import groovy.transform.CompileStatic
import org.moqui.context.TransactionFacade
import org.moqui.entity.*
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.sql.DataSource

/**
 * To use this:
 * 1. add a datasource under the entity-facade element in the Moqui Conf file; for example:
 *      <datasource group-name="transactional_nosql" object-factory="org.moqui.impl.entity.orientdb.OrientDatasourceFactory">
 *          <inline-other uri="plocal:${ORIENTDB_HOME}/databases/MoquiNoSql" username="admin" password="admin"/>
 *      </datasource>
 *
 * 2. to get OrientDB to automatically create the database, add a corresponding "storage" element to the
 *      orientdb-server-config.xml file
 *
 * 3. add the group-name attribute to entity elements as needed to point them to the new datasource; for example:
 *      group-name="transactional_nosql"
 */
class OrientDatasourceFactory implements EntityDatasourceFactory {
    protected final static Logger logger = LoggerFactory.getLogger(OrientDatasourceFactory.class)

    protected EntityFacadeImpl efi
    protected Node datasourceNode
    protected String tenantId

    protected OServer oserver
    protected OPartitionedDatabasePool databaseDocumentPool

    protected String uri
    protected String username
    protected String password

    protected Set<String> checkedClassSet = new HashSet<String>()

    OrientDatasourceFactory() { }

    @Override
    EntityDatasourceFactory init(EntityFacade ef, Node datasourceNode, String tenantId) {
        // local fields
        this.efi = (EntityFacadeImpl) ef
        this.datasourceNode = datasourceNode
        this.tenantId = tenantId

        System.setProperty("ORIENTDB_HOME", efi.getEcfi().getRuntimePath() + "/db/orientdb")
        System.setProperty("ORIENTDB_ROOT_PASSWORD", "moqui")

        // init the DataSource
        EntityValue tenant = null
        EntityFacadeImpl defaultEfi = null
        if (this.tenantId != "DEFAULT") {
            defaultEfi = efi.ecfi.getEntityFacade("DEFAULT")
            tenant = defaultEfi.find("moqui.tenant.Tenant").condition("tenantId", this.tenantId).one()
        }

        EntityValue tenantDataSource = null
        if (tenant != null) {
            tenantDataSource = defaultEfi.find("moqui.tenant.TenantDataSource").condition("tenantId", this.tenantId)
                    .condition("entityGroupName", datasourceNode."@group-name").one()
        }

        Node inlineOtherNode = datasourceNode."inline-other".first()
        uri = tenantDataSource ? tenantDataSource.jdbcUri : (inlineOtherNode."@uri" ?: inlineOtherNode."@jdbc-uri")
        username = tenantDataSource ? tenantDataSource.jdbcUsername : (inlineOtherNode."@username" ?: inlineOtherNode."@jdbc-username")
        password = tenantDataSource ? tenantDataSource.jdbcPassword : (inlineOtherNode."@password" ?: inlineOtherNode."@jdbc-password")

        oserver = OServerMain.create()
        oserver.startup(efi.getEcfi().getResourceFacade().getLocationStream("db/orientdb/config/orientdb-server-config.xml"))
        oserver.activate()

        int maxSize = (inlineOtherNode."@pool-maxsize" ?: "50") as int
        databaseDocumentPool = new OPartitionedDatabasePool(uri, username, password, maxSize)
        // databaseDocumentPool.setup((inlineOtherNode."@pool-minsize" ?: "5") as int, (inlineOtherNode."@pool-maxsize" ?: "50") as int)

        return this
    }

    @CompileStatic
    OPartitionedDatabasePool getDatabaseDocumentPool() { return databaseDocumentPool }

    /** Returns the main database access object for OrientDB.
     * Remember to call close() on it when you're done with it (preferably in a try/finally block)!
     */
    @CompileStatic
    ODatabaseDocumentTx getDatabase() { return databaseDocumentPool.acquire() }
    // NOTE: maybe try ODatabaseDocumentTxPooled... performs better?

    /** The database object returned here is shared in the transaction and should not be closed each time used. Will be
     * closed when the tx commits or rolls back.
     *
     * This will return null if no transaction is in place.
     */
    @CompileStatic
    ODatabaseDocumentTx getSynchronizationDatabase() {
        TransactionFacade tf = efi.getEcfi().getTransactionFacade()
        OrientSynchronization oxr = (OrientSynchronization) tf.getActiveSynchronization("OrientSynchronization")
        if (oxr == null) {
            if (tf.isTransactionInPlace()) {
                oxr = new OrientSynchronization(efi.getEcfi(), this).enlistOrGet()
            } else {
                return null
            }
        }
        return oxr.getDatabase()
    }


    @Override
    void destroy() {
        databaseDocumentPool.close()
        oserver.shutdown()
    }

    @CompileStatic
    @Override
    void checkAndAddTable(String entityName) {
        ODatabaseDocumentTx createOddt = getDatabase()
        try {
            checkCreateDocumentClass(createOddt, efi.getEntityDefinition(entityName))
        } finally {
            createOddt.close()
        }
    }

    @CompileStatic
    @Override
    EntityValue makeEntityValue(String entityName) {
        EntityDefinition entityDefinition = efi.getEntityDefinition(entityName)
        if (!entityDefinition) {
            throw new EntityException("Entity not found for name [${entityName}]")
        }
        return new OrientEntityValue(entityDefinition, efi, this)
    }

    @CompileStatic
    @Override
    EntityFind makeEntityFind(String entityName) { return new OrientEntityFind(efi, entityName, this) }

    @CompileStatic
    @Override
    DataSource getDataSource() { return null }

    @CompileStatic
    void checkCreateDocumentClass(ODatabaseDocumentTx oddt, EntityDefinition ed) {
        // TODO: do something with view entities
        if (ed.isViewEntity()) return

        if (checkedClassSet.contains(ed.getFullEntityName())) return

        OClass oc = oddt.getMetadata().getSchema().getClass(ed.getTableName())
        if (oc == null) createDocumentClass(oddt, ed)

        checkedClassSet.add(ed.getFullEntityName())
    }
    synchronized void createDocumentClass(ODatabaseDocumentTx oddt, EntityDefinition ed) {
        // TODO: do something with view entities
        if (ed.isViewEntity()) return

        OClass oc = oddt.getMetadata().getSchema().getClass(ed.getTableName())
        if (oc == null) {
            logger.info("Creating OrientDB class for entity [${ed.getEntityName()}] for database at [${uri}]")
            oc = oddt.getMetadata().getSchema().createClass(ed.getTableName())

            // create all properties
            List<String> pkFieldNames = ed.getPkFieldNames()
            for (Node fieldNode in ed.getFieldNodes(true, true, false)) {
                String fieldName = fieldNode."@name"
                OProperty op = oc.createProperty(ed.getColumnName(fieldName, false), getFieldType(fieldName, ed))
                if (pkFieldNames.contains(fieldName)) op.setMandatory(true).setNotNull(true)
            }

            // create "pk" index
            String indexName = ed.getTableName() + "_PK"
            List colNames = []
            for (String pkFieldName in pkFieldNames) colNames.add(ed.getColumnName(pkFieldName, false))
            // toArray because method uses Java ellipses syntax
            oc.createIndex(indexName, OClass.INDEX_TYPE.UNIQUE, (String[]) colNames.toArray(new String[colNames.size()]))

            // TODO: create other indexes

            // TODO: create relationships
        }
    }

    @CompileStatic
    static OType getFieldType(String fieldName, EntityDefinition ed) {
        EntityDefinition.FieldInfo fieldInfo = ed.getFieldInfo(fieldName)
        int javaTypeInt = fieldInfo.typeValue

        OType fieldType = null
        switch (javaTypeInt) {
            case 1: fieldType = OType.STRING; break
            case 2: fieldType = OType.DATETIME; break
            case 3: fieldType = OType.DATETIME; break // NOTE: there doesn't seem to be a time only type...
            case 4: fieldType = OType.DATE; break
            case 5: fieldType = OType.INTEGER; break
            case 6: fieldType = OType.LONG; break
            case 7: fieldType = OType.FLOAT; break
            case 8: fieldType = OType.DOUBLE; break
            case 9: fieldType = OType.DECIMAL; break
            case 10: fieldType = OType.BOOLEAN; break
            case 11: fieldType = OType.BINARY; break // NOTE: looks like we'll have to take care of the serialization for objects
            case 12: fieldType = OType.BINARY; break
            case 13: fieldType = OType.STRING; break
            case 14: fieldType = OType.DATETIME; break
            case 15: fieldType = OType.EMBEDDEDLIST; break
        }

        return fieldType
    }
}
