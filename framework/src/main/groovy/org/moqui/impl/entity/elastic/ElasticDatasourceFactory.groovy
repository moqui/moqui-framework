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

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import org.moqui.context.ElasticFacade
import org.moqui.entity.*
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.EntityValueBase
import org.moqui.impl.entity.FieldInfo
import org.moqui.util.LiteStringMap
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.sql.DataSource
import javax.xml.bind.DatatypeConverter
import java.sql.Time
import java.sql.Timestamp

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
    protected String indexPrefix, clusterName

    protected Set<String> checkedEntityIndexSet = new HashSet<String>()

    ElasticDatasourceFactory() { }

    @Override
    EntityDatasourceFactory init(EntityFacade ef, MNode datasourceNode) {
        // local fields
        this.efi = (EntityFacadeImpl) ef
        this.datasourceNode = datasourceNode

        // init the DataSource
        MNode inlineOtherNode = datasourceNode.first("inline-other")
        inlineOtherNode.setSystemExpandAttributes(true)
        indexPrefix = inlineOtherNode.attribute("index-prefix") ?: ""
        clusterName = inlineOtherNode.attribute("cluster-name") ?: "default"

        return this
    }

    @Override
    void destroy() {
    }

    @Override
    boolean checkTableExists(String entityName) {
        EntityDefinition ed
        try { ed = efi.getEntityDefinition(entityName) }
        catch (EntityException e) { return false }
        if (ed == null) return false

        String indexName = getIndexName(ed)
        if (checkedEntityIndexSet.contains(indexName)) return true

        if (!getElasticClient().indexExists(indexName)) return false

        checkedEntityIndexSet.add(indexName)
        return true
    }
    @Override
    boolean checkAndAddTable(String entityName) {
        EntityDefinition ed
        try { ed = efi.getEntityDefinition(entityName) }
        catch (EntityException e) { return false }
        if (ed == null) return false
        checkCreateDocumentIndex(ed)
        return true
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
        EntityDefinition ed = efi.getEntityDefinition(entityName)
        if (ed == null) throw new EntityException("Entity not found for name ${entityName}")
        return new ElasticEntityValue(ed, efi, this)
    }

    @Override
    EntityFind makeEntityFind(String entityName) { return new ElasticEntityFind(efi, entityName, this) }

    @Override
    void createBulk(List<EntityValue> valueList) {
        if (valueList == null || valueList.isEmpty()) return
        ElasticFacade.ElasticClient elasticClient = getElasticClient()

        EntityValueBase firstEv = (EntityValueBase) valueList.get(0)
        EntityDefinition ed = firstEv.getEntityDefinition()

        FieldInfo[] pkFieldInfos = ed.entityInfo.pkFieldInfoArray
        String idField = pkFieldInfos.length == 1 ? pkFieldInfos[0].name : "_id"

        List<Map> mapList = new ArrayList<Map>(valueList.size())
        Iterator<EntityValue> valueIterator = valueList.iterator()
        while (valueIterator.hasNext()) {
            EntityValueBase ev = (EntityValueBase) valueIterator.next()
            LiteStringMap<Object> evMap = ev.getValueMap()
            // to pass a key/id for each record it has to be in the Map, this will cause the LiteStringMap to grow
            //     the array for the additional field, so there is a performance overhead to this
            if (pkFieldInfos.length > 1) evMap.put("_id", ev.getPrimaryKeysString())
            mapList.add(evMap)
        }

        checkCreateDocumentIndex(ed)
        elasticClient.bulkIndex(getIndexName(ed), (String) null, idField, (List<Map>) mapList, true)
    }

    @Override
    DataSource getDataSource() {
        //  no DataSource for this 'db', return nothing and EntityFacade ignores it and Connection parameters will be null (in ElasticEntityValue, etc)
        return null
    }

    void checkCreateDocumentIndex(EntityDefinition ed) {
        String indexName = getIndexName(ed)
        if (checkedEntityIndexSet.contains(indexName)) return

        ElasticFacade.ElasticClient elasticClient = efi.ecfi.elasticFacade.getClient(clusterName)
        if (elasticClient == null) throw new IllegalStateException("No ElasticClient found for cluster name " + clusterName)
        if (!elasticClient.indexExists(indexName)) {
            Map mapping = makeElasticEntityMapping(ed)
            // logger.warn("Creating ES Index ${indexName} with mapping: ${JsonOutput.prettyPrint(JsonOutput.toJson(mapping))}")
            elasticClient.createIndex(indexName, mapping, null)
        }

        checkedEntityIndexSet.add(indexName)
    }

    ElasticFacade.ElasticClient getElasticClient() {
        ElasticFacade.ElasticClient client = efi.ecfi.elasticFacade.getClient(clusterName)
        if (client == null) throw new IllegalStateException("No ElasticClient found for cluster name " + clusterName)
        return client
    }
    String getIndexName(EntityDefinition ed) {
        return indexPrefix + ed.getTableNameLowerCase()
    }

    static Object convertFieldValue(FieldInfo fi, Object fValue) {
        if (fi.typeValue == EntityFacadeImpl.ENTITY_TIMESTAMP) {
            if (fValue instanceof Number) {
                return new Timestamp(((Number) fValue).longValue())
            } else if (fValue instanceof CharSequence) {
                Calendar cal = DatatypeConverter.parseDateTime(fValue.toString())
                if (cal != null) return new Timestamp(cal.getTimeInMillis())
            }
        } else if (fi.typeValue == EntityFacadeImpl.ENTITY_DATE) {
            if (fValue instanceof Number) {
                return new java.sql.Date(((Number) fValue).longValue())
            } else if (fValue instanceof CharSequence) {
                Calendar cal = DatatypeConverter.parseDate(fValue.toString())
                if (cal != null) return new java.sql.Date(cal.getTimeInMillis())
            }
        } else if (fi.typeValue == EntityFacadeImpl.ENTITY_TIME) {
            if (fValue instanceof Number) {
                return new Time(((Number) fValue).longValue())
            } else if (fValue instanceof CharSequence) {
                Calendar cal = DatatypeConverter.parseTime(fValue.toString())
                if (cal != null) return new Time(cal.getTimeInMillis())
            }
        }
        return fValue
    }

    static final Map<String, String> esEntityTypeMap = [id:'keyword', 'id-long':'keyword', date:'date', time:'keyword',
            'date-time':'date', 'number-integer':'long', 'number-decimal':'double', 'number-float':'double',
            'currency-amount':'double', 'currency-precise':'double', 'text-indicator':'keyword', 'text-short':'text',
            'text-medium':'text', 'text-intermediate':'text', 'text-long':'text', 'text-very-long':'text', 'binary-very-long':'binary']
    static final Set<String> esEntityIsKeywordSet = esEntityTypeMap.findAll({"keyword".equals(it.value)}).keySet()
    static final Set<String> esEntityAddKeywordSet = new HashSet<>(['text-short', 'text-medium', 'text-intermediate'])

    static Map makeElasticEntityMapping(EntityDefinition ed) {
        Map<String, Object> rootProperties = [_entity:[type:'keyword']] as Map<String, Object>
        Map<String, Object> mappingMap = [properties:rootProperties] as Map<String, Object>

        FieldInfo[] allFieldInfo = ed.entityInfo.allFieldInfoArray
        for (int i = 0; i < allFieldInfo.length; i++) {
            FieldInfo fieldInfo = allFieldInfo[i]
            rootProperties.put(fieldInfo.name, makeEntityFieldPropertyMap(fieldInfo))
        }

        return mappingMap
    }
    static Map makeEntityFieldPropertyMap(FieldInfo fieldInfo) {
        String mappingType = esEntityTypeMap.get(fieldInfo.type) ?: 'text'
        Map<String, Object> propertyMap = new LinkedHashMap<>()
        propertyMap.put("type", mappingType)
        if (esEntityAddKeywordSet.contains(fieldInfo.type) && "text".equals(mappingType))
            propertyMap.put("fields", [keyword: [type: "keyword"]])
        if ("date-time".equals(fieldInfo.type))
            propertyMap.format = "date_time||epoch_millis||date_time_no_millis||yyyy-MM-dd HH:mm:ss.SSS||yyyy-MM-dd HH:mm:ss.S||yyyy-MM-dd"
        else if ("date".equals(fieldInfo.type))
            propertyMap.format = "date||strict_date_optional_time||epoch_millis"
        return propertyMap
    }
}
