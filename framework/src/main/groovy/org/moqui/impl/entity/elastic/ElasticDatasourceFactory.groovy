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
package org.moqui.impl.entity.elastic

import groovy.transform.CompileStatic
import org.moqui.entity.*
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.sql.DataSource

/**
 * To use this:
 * 1. add a datasource under the entity-facade element in the Moqui Conf file; for example:
 *      <datasource group-name="nontransactional" object-factory="org.moqui.impl.entity.orientdb.OrientDatasourceFactory">
 *          <inline-other uri="plocal:${ORIENTDB_HOME}/databases/MoquiNoSql" username="admin" password="admin"/>
 *      </datasource>
 *
 * 2. to get OrientDB to automatically create the database, add a corresponding "storage" element to the
 *      orientdb-server-config.xml file
 *
 * 3. add the group attribute to entity elements as needed to point them to the new datasource; for example:
 *      group="nontransactional"
 */
@CompileStatic
class ElasticDatasourceFactory implements EntityDatasourceFactory {
    protected final static Logger logger = LoggerFactory.getLogger(ElasticDatasourceFactory.class)

    protected EntityFacadeImpl efi
    protected MNode datasourceNode
    protected String indexPrefix

    protected Set<String> checkedEntitySet = new HashSet<String>()

    ElasticDatasourceFactory() { }

    @Override
    EntityDatasourceFactory init(EntityFacade ef, MNode datasourceNode) {
        // local fields
        this.efi = (EntityFacadeImpl) ef
        this.datasourceNode = datasourceNode

        // init the DataSource
        MNode inlineOtherNode = datasourceNode.first("inline-other")
        inlineOtherNode.setSystemExpandAttributes(true)
        indexPrefix = inlineOtherNode.attribute("index-prefix")

        return this
    }

    @Override
    void destroy() {
    }

    @Override
    boolean checkTableExists(String entityName) {
        EntityDefinition ed
        // just ignore EntityException on getEntityDefinition
        try { ed = efi.getEntityDefinition(entityName) }
        catch (EntityException e) { return false }
        // may happen if all entity names includes a DB view entity or other that doesn't really exist
        if (ed == null) return false

        String fullEntityName = ed.getFullEntityName()
        if (checkedEntitySet.contains(fullEntityName)) return true

        // TODO
        // if (TODO) return false

        checkedEntitySet.add(fullEntityName)
        return true
    }
    @Override
    boolean checkAndAddTable(String entityName) {
        EntityDefinition ed
        // just ignore EntityException on getEntityDefinition
        try { ed = efi.getEntityDefinition(entityName) } catch (EntityException e) { return false }
        // may happen if all entity names includes a DB view entity or other that doesn't really exist
        if (ed == null) return false

        // TODO
    }
    @Override
    int checkAndAddAllTables() {
        int tablesAdded = 0
        String groupName = datasourceNode.attribute("group-name")

        for (String entityName in efi.getAllEntityNames()) {
            String entGroupName = efi.getEntityGroupName(entityName) ?: efi.defaultGroupName
            if (entGroupName.equals(groupName)) {
                if (checkAndAddTable(entityName)) tablesAdded++
            }
        }

        return tablesAdded
    }

    @Override
    EntityValue makeEntityValue(String entityName) {
        EntityDefinition entityDefinition = efi.getEntityDefinition(entityName)
        if (!entityDefinition) {
            throw new EntityException("Entity not found for name [${entityName}]")
        }

        // TODO
        return null
    }

    @Override
    EntityFind makeEntityFind(String entityName) { return new ElasticEntityFind(efi, entityName, this) }

    @Override
    DataSource getDataSource() { return null }

    void checkCreateDocumentIndex(EntityDefinition ed) {
        // TODO
    }
}
