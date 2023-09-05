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
package org.moqui.impl.context

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.module.SimpleModule
import groovy.json.JsonOutput
import groovy.transform.CompileStatic

import org.moqui.BaseException
import org.moqui.context.ElasticFacade
import org.moqui.entity.EntityException
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.EntityDataDocument
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityJavaUtil
import org.moqui.impl.entity.FieldInfo
import org.moqui.impl.util.ElasticSearchLogger
import org.moqui.util.LiteStringMap
import org.moqui.util.MNode
import org.moqui.util.RestClient
import org.moqui.util.RestClient.Method
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.util.concurrent.Future

@CompileStatic
class ElasticFacadeImpl implements ElasticFacade {
    private final static Logger logger = LoggerFactory.getLogger(ElasticFacadeImpl.class)
    private final static Set<String> docSkipKeys = new HashSet<>(Arrays.asList("_index", "_type", "_id", "_timestamp"))

    // Max HTTP Response Size for Search - this may need to be configurable, set very high for now (appears that Jetty only grows the buffer as needed for response content)
    public static int MAX_RESPONSE_SIZE_SEARCH = 100 * 1024 * 1024
    // Request Timeout, another thing that could be configurable but can be specified via API, set to 50 to give plenty of time for TX/etc cleanup
    public static int DEFAULT_REQUEST_TIMEOUT = 50
    public static int SMALL_OP_REQUEST_TIMEOUT = 5

    public final static ObjectMapper jacksonMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.ALWAYS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true)
    static {
        // Jackson custom serializers, etc
        SimpleModule module = new SimpleModule()
        module.addSerializer(GString.class, new ContextJavaUtil.GStringJsonSerializer())
        module.addSerializer(LiteStringMap.class, new ContextJavaUtil.LiteStringMapJsonSerializer())
        // NOTE: using custom serializer for Timestamps because ElasticSearch 7+ does NOT allow negative longs for epoch_millis format... sigh
        //     .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        module.addSerializer(Timestamp.class, new ContextJavaUtil.TimestampNoNegativeJsonSerializer())
        jacksonMapper.registerModule(module)
    }

    public final ExecutionContextFactoryImpl ecfi
    private final Map<String, ElasticClientImpl> clientByClusterName = new LinkedHashMap<>()
    private ElasticSearchLogger esLogger = null

    ElasticFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        init()
    }
    void init() {
        MNode elasticFacadeNode = ecfi.getConfXmlRoot().first("elastic-facade")
        ArrayList<MNode> clusterNodeList = elasticFacadeNode.children("cluster")
        for (MNode clusterNode in clusterNodeList) {
            clusterNode.setSystemExpandAttributes(true)

            String clusterName = clusterNode.attribute("name")
            String clusterUrl = clusterNode.attribute("url")
            logger.info("Initializing ElasticFacade Client for cluster ${clusterName} at ${clusterUrl}")

            if (clientByClusterName.containsKey(clusterName)) {
                logger.warn("ElasticFacade Client for cluster ${clusterName} already initialized, skipping")
                continue
            }
            if (!clusterUrl) {
                logger.warn("ElasticFacade Client for cluster ${clusterName} has no url, skipping")
                continue
            }

            try {
                ElasticClientImpl elci = new ElasticClientImpl(clusterNode, ecfi)
                clientByClusterName.put(clusterName, elci)
            } catch (Throwable t) {
                Throwable cause = t.getCause()
                if (cause != null && cause.message.contains("refused")) {
                    logger.error("Error initializing ElasticClient for cluster ${clusterName}: ${cause.toString()}")
                } else {
                    logger.error("Error initializing ElasticClient for cluster ${clusterName}", t)
                }
            }
        }

        // init ElasticSearchLogger
        if (esLogger == null || !esLogger.isInitialized()) {
            ElasticClientImpl loggerEci = clientByClusterName.get("logger") ?: clientByClusterName.get("default")
            if (loggerEci != null) {
                logger.info("Initializing ElasticSearchLogger with cluster ${loggerEci.getClusterName()}")
                esLogger = new ElasticSearchLogger(loggerEci, ecfi)
            } else {
                logger.warn("No Elastic Client found with name 'logger' or 'default', not initializing ElasticSearchLogger")
            }
        } else {
            logger.warn("ElasticSearchLogger in place and initialized, not initializing ElasticSearchLogger")
        }

        // Index DataFeed with indexOnStartEmpty=Y
        try {
            ElasticClientImpl defaultEci = clientByClusterName.get("default")
            if (defaultEci != null) {
                EntityList dataFeedList = ecfi.entityFacade.find("moqui.entity.feed.DataFeed")
                        .condition("indexOnStartEmpty", "Y").disableAuthz().list()
                for (EntityValue dataFeed in dataFeedList) {
                    EntityList dfddList = ecfi.entityFacade.find("moqui.entity.feed.DataFeedDocumentDetail")
                            .condition("dataFeedId", dataFeed.dataFeedId).disableAuthz().list()
                    Set<String> indexNames = new HashSet<String>()
                    for (int i = 0; i < dfddList.size(); i++) {
                        EntityValue dfdd = (EntityValue) dfddList.get(i)
                        indexNames.add(dfdd.getString("indexName"))
                    }
                    boolean foundNotExists = false
                    for (String indexName in indexNames) if (!defaultEci.indexExists(indexName)) foundNotExists = true
                    if (foundNotExists) {
                        // NOTE: called with localOnly(true) to avoid issues during startup if a distributed executor service is configured
                        String jobRunId = ecfi.service.job("IndexDataFeedDocuments").parameter("dataFeedId", dataFeed.dataFeedId).localOnly(true).run()
                        logger.info("Found index does not exist for DataFeed ${dataFeed.dataFeedId}, started job ${jobRunId} to index")
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Error checking or indexing for all DataFeed with indexOnStartEmpty=Y", t)
        }
    }

    void destroy() {
        if (esLogger != null) esLogger.destroy()
        for (ElasticClientImpl eci in clientByClusterName.values()) eci.destroy()
    }

    @Override ElasticClient getDefault() { return clientByClusterName.get("default") }
    @Override ElasticClient getClient(String clusterName) { return clientByClusterName.get(clusterName) }
    @Override List<ElasticClient> getClientList() { return new ArrayList<ElasticClient>(clientByClusterName.values()) }

    static class ElasticClientImpl implements ElasticClient {
        private final ExecutionContextFactoryImpl ecfi
        private final MNode clusterNode
        private final String clusterName, clusterUser, clusterPassword, indexPrefix
        private final String clusterUrl, clusterProtocol, clusterHost
        private final int clusterPort
        private RestClient.PooledRequestFactory requestFactory
        private Map serverInfo = (Map) null
        private String esVersion = (String) null
        private boolean esVersionUnder7 = false
        private boolean isOpenSearch = false

        ElasticClientImpl(MNode clusterNode, ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi
            this.clusterNode = clusterNode

            this.clusterName = clusterNode.attribute("name")
            this.clusterUser = clusterNode.attribute("user")
            this.clusterPassword = clusterNode.attribute("password")
            this.indexPrefix = clusterNode.attribute("index-prefix")
            String urlTemp = clusterNode.attribute("url")
            if (urlTemp.endsWith("/")) urlTemp = urlTemp.substring(0, urlTemp.length() - 1)
            this.clusterUrl = urlTemp
            URI uri = new URI(urlTemp)
            clusterProtocol = uri.getScheme() ?: "http"
            clusterHost = uri.getHost()
            int portTemp = uri.getPort()
            clusterPort = portTemp > 0 ? portTemp : 9200

            String poolMaxStr = clusterNode.attribute("pool-max")
            String queueSizeStr = clusterNode.attribute("queue-size")

            requestFactory = new RestClient.PooledRequestFactory("ES_" + clusterName)
            if (poolMaxStr) requestFactory.poolSize(Integer.parseInt(poolMaxStr))
            if (queueSizeStr) requestFactory.queueSize(Integer.parseInt(queueSizeStr))
            requestFactory.init()

            // try connecting and get server info
            int retries = ((clusterHost == 'localhost' || clusterHost == '127.0.0.1') && !"true".equals(System.getProperty("moqui.elasticsearch.started"))) ? 1 : 20
            for (int i = 1; i <= retries; i++) {
                try {
                    serverInfo = getServerInfo()
                } catch (Throwable t) {
                    if (i == retries) {
                        requestFactory.destroy()
                        throw t
                        // logger.error("Final error connecting to ElasticSearch cluster ${clusterName} at ${clusterProtocol}://${clusterHost}:${clusterPort}, try ${i} of ${retries}: ${t.toString()}", t)
                    } else {
                        logger.warn("Error connecting to ElasticSearch cluster ${clusterName} at ${clusterProtocol}://${clusterHost}:${clusterPort}, try ${i} of ${retries}: ${t.toString()}")
                        Thread.sleep(1000)
                    }
                }
                if (serverInfo != null) {
                    // [name:dejc-m1p.local, cluster_name:opensearch, cluster_uuid:aoMc3T7ES9yCC6yzi-_Ghg, version:[distribution:opensearch, number:1.3.1, build_type:tar, build_hash:c4c0672877bf0f787ca857c7c37b775967f93d81, build_date:2022-03-29T18:34:46.566802Z, build_snapshot:false, lucene_version:8.10.1, minimum_wire_compatibility_version:6.8.0, minimum_index_compatibility_version:6.0.0-beta1], tagline:The OpenSearch Project: https://opensearch.org/]
                    Map versionMap = ((Map) serverInfo.version)
                    String distro = versionMap?.distribution ?: "elasticsearch"
                    isOpenSearch = "opensearch".equals(distro)
                    esVersion = versionMap?.number
                    esVersionUnder7 = !isOpenSearch && esVersion?.charAt(0) < ((char) '7')
                    logger.info("Connected to ElasticSearch cluster ${clusterName} at ${clusterProtocol}://${clusterHost}:${clusterPort} distribution ${distro} version ${esVersion}, ES earlier than 7.0? ${esVersionUnder7}\n${serverInfo}")
                    break
                }
            }
        }

        @Override String getClusterName() { return clusterName }
        @Override String getClusterLocation() { return clusterProtocol + "://" + clusterHost + ":" + clusterPort }
        boolean isEsVersionUnder7() { return esVersionUnder7 }

        void destroy() { requestFactory.destroy() }

        @Override
        Map getServerInfo() {
            RestClient.RestResponse response = makeRestClient(Method.GET, null, null, null).call()
            checkResponse(response, "Server info", null)
            return (Map) jsonToObject(response.text())
        }

        @Override
        boolean indexExists(final String index) {
            if (index == null || index.isEmpty()) throw new IllegalArgumentException("Index name required")
            RestClient.RestResponse response = makeRestClient(Method.HEAD, index, null, null).call()
            return response.statusCode == 200
        }
        @Override
        boolean aliasExists(final String origAlias) {
            String alias = prefixIndexName(origAlias)
            if (alias == null) throw new IllegalArgumentException("Alias required")
            RestClient.RestResponse response = makeRestClient(Method.HEAD, null, "_alias/" + alias, null).call()
            return response.statusCode == 200
        }

        @Override
        void createIndex(String index, Map docMapping, String origAlias) { createIndex(index, null, docMapping, origAlias, null) }
        void createIndex(String index, String docType, Map docMapping, String origAlias) { createIndex(index, docType, docMapping, origAlias, null) }
        void createIndex(String index, String docType, Map docMapping, String origAlias, Map settings) {
            if (index == null || index.isEmpty()) throw new IllegalArgumentException("Index name required")
            RestClient restClient = makeRestClient(Method.PUT, index, null, null)
            if (docMapping || origAlias) {
                Map requestMap = new HashMap()
                if (docMapping) {
                    if (esVersionUnder7) requestMap.put("mappings", [(docType?:'_doc'):docMapping])
                    else requestMap.put("mappings", docMapping)
                }
                if (settings != null && settings.size() > 0) {
                    requestMap.put('settings', settings)
                }
                if (origAlias != null && !origAlias.isEmpty()) {
                    String alias = prefixIndexName(origAlias)
                    requestMap.put("aliases", [(alias):[:]])
                }
                restClient.text(objectToJson(requestMap))
            }
            // NOTE: this is for ES 7.0+ only, before that mapping needed to be named
            RestClient.RestResponse response = restClient.call()
            checkResponse(response, "Create index", index)
        }
        @Override
        void putMapping(String index, Map docMapping) { putMapping(index, null, docMapping) }
        void putMapping(String index, String docType, Map docMapping) {
            if (!docMapping) throw new IllegalArgumentException("Mapping may not be empty for put mapping")
            // NOTE: this is for ES 7.0+ only, before that mapping needed to be named in the path
            String path = esVersionUnder7 ? "_mapping/" + (docType?:'_doc') : "_mapping"
            RestClient restClient = makeRestClient(Method.PUT, index, path, null)
            restClient.text(objectToJson(docMapping))
            RestClient.RestResponse response = restClient.call()
            checkResponse(response, "Put mapping", index)
        }
        @Override
        void deleteIndex(String index) {
            RestClient restClient = makeRestClient(Method.DELETE, index, null, null)
            RestClient.RestResponse response = restClient.call()
            checkResponse(response, "Delete index", index)
        }

        @Override
        void index(String index, String _id, Map document) {
            if (index == null || index.isEmpty()) throw new IllegalArgumentException("In index document the index name may not be empty")
            if (_id == null || _id.isEmpty()) throw new IllegalArgumentException("In index document the _id may not be empty")
            RestClient.RestResponse response = makeRestClient(Method.PUT, index, "_doc/" + _id, null, SMALL_OP_REQUEST_TIMEOUT)
                    .text(objectToJson(document)).call()
            checkResponse(response, "Index document ${_id}", index)
        }

        @Override
        void update(String index, String _id, Map documentFragment) {
            if (index == null || index.isEmpty()) throw new IllegalArgumentException("In update document the index name may not be empty")
            if (_id == null || _id.isEmpty()) throw new IllegalArgumentException("In update document the _id may not be empty")
            RestClient.RestResponse response = makeRestClient(Method.POST, index, "_update/" + _id, null, SMALL_OP_REQUEST_TIMEOUT)
                    .text(objectToJson([doc:documentFragment])).call()
            checkResponse(response, "Update document ${_id}", index)
        }

        @Override
        void delete(String index, String _id) {
            if (index == null || index.isEmpty()) throw new IllegalArgumentException("In delete document the index name may not be empty")
            if (_id == null || _id.isEmpty()) throw new IllegalArgumentException("In delete document the _id may not be empty")
            RestClient.RestResponse response = makeRestClient(Method.DELETE, index, "_doc/" + _id, null, SMALL_OP_REQUEST_TIMEOUT).call()
            if (response.statusCode == 404) {
                logger.warn("In delete document not found in index ${index} with ID ${_id}")
            } else {
                checkResponse(response, "Delete document ${_id}", index)
            }
        }

        @Override
        Integer deleteByQuery(String index, Map queryMap) {
            if (index == null || index.isEmpty()) throw new IllegalArgumentException("In delete by query the index name may not be empty")
            RestClient.RestResponse response = makeRestClient(Method.POST, index, "_delete_by_query", null)
                    .text(objectToJson([query:queryMap])).call()
            checkResponse(response, "Delete by query", index)
            Map responseMap = (Map) jsonToObject(response.text())
            return responseMap.deleted as Integer
        }

        @Override
        void bulk(String index, List<Map> actionSourceList) {
            if (actionSourceList == null || actionSourceList.size() == 0) return

            RestClient.RestResponse response = bulkResponse(index, actionSourceList, false)
            checkResponse(response, "Bulk operations", index)
        }
        RestClient.RestResponse bulkResponse(String index, List<Map> actionSourceList, boolean refresh) {
            // NOTE: don't use logger in this method, with ElasticSearchLogger in place results in infinite log feedback
            if (actionSourceList == null || actionSourceList.size() == 0) return null

            StringWriter bodyWriter = new StringWriter(actionSourceList.size() * 100)
            for (Map entry in actionSourceList) {
                // look for _index fields in each Map, if found prefix
                if (entry.size() == 1) {
                    Map actionMap = (Map) entry.values().first()
                    Object _indexVal = actionMap.get("_index")
                    if (_indexVal != null && _indexVal instanceof String) actionMap.put("_index", prefixIndexName((String) _indexVal))
                }
                // System.out.println("bulk entry ${entry}")
                // now done mucking around with the data, write it
                jacksonMapper.writeValue(bodyWriter, entry)
                bodyWriter.append((char) '\n')
            }
            RestClient restClient = makeRestClient(Method.POST, index, "_bulk", [refresh:(refresh ? "true" : "wait_for")])
                    .contentType("application/x-ndjson")
            restClient.timeout(600)
            restClient.text(bodyWriter.toString())
            // System.out.println("Bulk:\n${bodyWriter.toString()}")

            RestClient.RestResponse response = restClient.call()
            // System.out.println("Bulk Response: ${response.statusCode} ${response.reasonPhrase}\n${response.text()}")
            return response
        }

        @Override
        void bulkIndex(String index, String idField, List<Map> documentList) { bulkIndex(index, null, idField, documentList, false) }
        void bulkIndex(String index, String docType, String idField, List<Map> documentList, boolean refresh) {
            List<Map> actionSourceList = new ArrayList<>(documentList.size() * 2)
            boolean hasId = idField != null && !idField.isEmpty()
            int loopIdx = 0
            for (Map document in documentList) {
                Map indexMap = new LinkedHashMap()
                indexMap.put("_index", index)
                if (hasId) {
                    Object idValue = document.get(idField)
                    if (idValue != null) {
                        indexMap.put("_id", idValue)
                    } else {
                        logger.warn("Bulk Index to ${index} found null value for ${idField} in doc ${loopIdx}")
                    }
                }
                if (esVersionUnder7) indexMap.put("_type", docType ?: "_doc")
                Map actionMap = [index:indexMap]
                actionSourceList.add(actionMap)
                actionSourceList.add(document)
                loopIdx++
            }

            RestClient.RestResponse response = bulkResponse(index, actionSourceList, refresh)
            checkResponse(response, "Bulk operations", index)
        }

        @Override
        Map get(final String index, String _id) {
            if (index == null || index.isEmpty()) throw new IllegalArgumentException("In get document the index name may not be empty")
            if (_id == null || _id.isEmpty()) throw new IllegalArgumentException("In get document the _id may not be empty")
            String path = "_doc/" + _id
            if (esVersionUnder7) {
                // need actual doc type, this is a hack that will only work with old moqui-elasticsearch DataDocument based index name, otherwise need another parameter so API changes
                // NOTE: this is for partial backwards compatibility for specific scenarios, remove after moqui-elasticsearch deprecate
                path = esIndexToDdId(index) + "/" + _id
            }
            RestClient.RestResponse response = makeRestClient(Method.GET, index, path, null, SMALL_OP_REQUEST_TIMEOUT).call()
            if (response.statusCode == 404) {
                return null
            } else {
                checkResponse(response, "Get document ${_id}", index)
                return (Map) jsonToObject(response.text())
            }
        }
        @Override
        Map getSource(String index, String _id) { return (Map) get(index, _id)?._source }

        @Override
        List<Map> get(String index, List<String> _idList) {
            if (_idList == null || _idList.size() == 0) return []
            if (index == null || index.isEmpty()) throw new IllegalArgumentException("In get documents the index name may not be empty")
            RestClient.RestResponse response = makeRestClient(Method.GET, index, "_mget", null)
                    .text(objectToJson([ids:_idList])).call()
            checkResponse(response, "Get document multi", index)
            Map bodyMap = (Map) jsonToObject(response.text())
            return (List) bodyMap.docs
        }

        @Override
        Map search(String index, Map searchMap) {
            // logger.warn("Search ${index}\n${objectToJson(searchMap)}")
            RestClient.RestResponse response = makeRestClient(Method.GET, index, "_search", null).maxResponseSize(MAX_RESPONSE_SIZE_SEARCH)
                    .text(objectToJson(searchMap)).call()
            // System.out.println("Search Response: ${response.statusCode} ${response.reasonPhrase}\n${response.text()}")
            checkResponse(response, "Search", index)
            Map resultMap = (Map) jsonToObject(response.text())
            // go through each hit (in resultMap.hits.hits) and replace _index value from ES
            List<Map> hitsList = (List<Map>) ((Map) resultMap.hits).hits
            for (Map hit in hitsList) {
                Object _indexVal = hit.get("_index")
                if (_indexVal != null && _indexVal instanceof String) hit.put("_index", unprefixIndexName((String) _indexVal))
                // logger.warn("search hit ${hit}")
            }
            // now done mucking around with the data, return it
            return resultMap
        }
        @Override
        List<Map> searchHits(String origIndex, Map searchMap) {
            Map resultMap = search(origIndex, searchMap)
            return (List) ((Map) resultMap.hits).hits
        }
        @Override
        Map validateQuery(String index, Map queryMap, boolean explain) {
            String queryJson = objectToJson([query:queryMap])
            RestClient.RestResponse response = makeRestClient(Method.GET, index, "_validate/query", explain ? [explain:'true'] : null, SMALL_OP_REQUEST_TIMEOUT)
                    .text(queryJson).call()
            checkResponse(response, "Validate Query", index)
            String responseText = response.text()
            Map responseMap = (Map) jsonToObject(responseText)
            // System.out.println("Validate Query Response: ${response.statusCode} ${response.reasonPhrase} Value? ${responseMap.get("valid") as boolean}\n${response.text()}")
            // return null if valid
            if (responseMap.get("valid")) return null
            logger.warn("Invalid ElasticSearch query\n${JsonOutput.prettyPrint(queryJson)}\nResponse: ${JsonOutput.prettyPrint(responseText)}")
            return responseMap
        }

        @Override
        long count(String index, Map countMap) {
            Map resultMap = countResponse(index, countMap)
            Number count = (Number) resultMap.count
            return count != null ? count.longValue() : 0
        }
        @Override
        Map countResponse(String index, Map countMap) {
            if (countMap == null || countMap.isEmpty()) countMap = [query:[match_all:[:]]]
            // System.out.println("Count Request index ${index} ${countMap}")
            RestClient.RestResponse response = makeRestClient(Method.GET, index, "_count", null)
                    .text(objectToJson(countMap)).call()
            // System.out.println("Count Response: ${response.statusCode} ${response.reasonPhrase}\n${response.text()}")
            checkResponse(response, "Count", index)
            Map resultMap = (Map) jsonToObject(response.text())
            return resultMap
        }

        @Override
        String getPitId(String index, String keepAlive) {
            if (keepAlive == null) keepAlive = "60s"
            RestClient.RestResponse response
            if (isOpenSearch) {
                // see: https://opensearch.org/docs/latest/opensearch/point-in-time-api#create-a-pit
                // requires 2.4.0 or later
                response = makeRestClient(Method.POST, index, "_search/point_in_time", [keep_alive:keepAlive]).call()
            } else {
                // see: https://www.elastic.co/guide/en/elasticsearch/reference/7.10/paginate-search-results.html#scroll-search-results
                // whatever the docs say:
                // - it doesn't work with the keep_alive parameter at all "contains unrecognized parameter: [keep_alive]"
                // - does not work with no body "request body is required"
                // - and it doesn't work without the doc type _doc before _pit in the path "mapping type name [_pit] can't start with '_' unless it is called [_doc]"
                // in other words, the docs are completely wrong for ES 7.10.2
                // response = makeRestClient(Method.POST, index, "_pit", [keep_alive:keepAlive]).call()
                response = makeRestClient(Method.POST, index, "_doc/_pit", null).text(objectToJson([keep_alive:keepAlive])).call()
            }
            // System.out.println("Get PIT Response: ${response.statusCode} ${response.reasonPhrase}\n${response.text()}")
            checkResponse(response, "PIT", index)
            Map resultMap = (Map) jsonToObject(response.text())
            return isOpenSearch ? resultMap?.pit_id : resultMap?.id
        }
        @Override
        void deletePit(String pitId) {
            RestClient.RestResponse response
            if (isOpenSearch) {
                // see: https://opensearch.org/docs/latest/opensearch/point-in-time-api#delete-pits
                // requires 2.4.0 or later
                response = makeRestClient(Method.DELETE, null, "_search/point_in_time", null)
                        .text(objectToJson([pit_id:[pitId]])).call()
            } else {
                // see: https://www.elastic.co/guide/en/elasticsearch/reference/7.10/paginate-search-results.html#scroll-search-results
                response = makeRestClient(Method.DELETE, null, "_pit", null).text(objectToJson([id:pitId])).call()
            }
            // System.out.println("Delete PIT Response: ${response.statusCode} ${response.reasonPhrase}\n${response.text()}")
            checkResponse(response, "PIT", null)
        }

        @Override
        RestClient.RestResponse call(Method method, String index, String path, Map<String, String> parameters, Object bodyJsonObject) {
            RestClient restClient = makeRestClient(method, index, path, parameters).text(objectToJson(bodyJsonObject))
            return restClient.call()
        }

        @Override
        Future<RestClient.RestResponse> callFuture(Method method, String index, String path, Map<String, String> parameters, Object bodyJsonObject) {
            RestClient restClient = makeRestClient(method, index, path, parameters).text(objectToJson(bodyJsonObject))
            return restClient.callFuture()
        }

        @Override
        RestClient makeRestClient(Method method, String index, String path, Map<String, String> parameters) {
            return makeRestClient(method, index, path, parameters, null)
        }
        RestClient makeRestClient(Method method, String index, String path, Map<String, String> parameters, Integer timeout) {
            // NOTE: don't use logger in this method, with ElasticSearchLogger in place results in infinite log feedback
            String serverIndex = prefixIndexName(index)
            // System.out.println("=== ES call index ${serverIndex} path ${path} parameters ${parameters}")
            RestClient restClient = new RestClient().withRequestFactory(requestFactory).method(method)
                    .contentType("application/json").timeout(timeout != null ? timeout : DEFAULT_REQUEST_TIMEOUT)
            restClient.uri().protocol(clusterProtocol).host(clusterHost).port(clusterPort)
                    .path(serverIndex).path(path).parameters(parameters).build()
            // see https://www.elastic.co/guide/en/elasticsearch/reference/7.4/http-clients.html
            if (clusterUser != null && !clusterUser.isEmpty()) restClient.basicAuth(clusterUser, clusterPassword)
            return restClient
        }

        @Override
        void checkCreateDataDocumentIndexes(String indexName) {
            // if the index alias exists call it good
            if (indexExists(indexName)) return

            EntityList ddList = ecfi.entityFacade.find("moqui.entity.document.DataDocument").condition("indexName", indexName).list()
            for (EntityValue dd in ddList) storeIndexAndMapping(indexName, dd)
        }
        @Override
        void checkCreateDataDocumentIndex(String dataDocumentId) {
            String idxName = ddIdToEsIndex(dataDocumentId)
            if (indexExists(idxName)) return

            EntityValue dd = ecfi.entityFacade.find("moqui.entity.document.DataDocument").condition("dataDocumentId", dataDocumentId).one()
            storeIndexAndMapping((String) dd.indexName, dd)
        }
        @Override
        void putDataDocumentMappings(String indexName) {
            EntityList ddList = ecfi.entityFacade.find("moqui.entity.document.DataDocument").condition("indexName", indexName).list()
            for (EntityValue dd in ddList) storeIndexAndMapping(indexName, dd)
        }
        synchronized protected void storeIndexAndMapping(String indexName, EntityValue dd) {
            String dataDocumentId = (String) dd.getNoCheckSimple("dataDocumentId")
            String manualMappingServiceName = (String) dd.getNoCheckSimple("manualMappingServiceName")
            String esIndexName = ddIdToEsIndex(dataDocumentId)

            // logger.warn("========== Checking index ${esIndexName} with alias ${indexName} , hasIndex=${hasIndex}")
            boolean hasIndex = indexExists(esIndexName)
            Map docMapping = makeElasticSearchMapping(dataDocumentId, ecfi)
            Map settings = null

            if (manualMappingServiceName) {
                def serviceResult = ecfi.service.sync().name(manualMappingServiceName).parameter('mapping', docMapping).call()
                docMapping = (Map) serviceResult.mapping
                settings = (Map) serviceResult.settings
            }

            if (hasIndex) {
                logger.info("Updating ElasticSearch index ${esIndexName} for ${dataDocumentId} document mapping")
                putMapping(esIndexName, dataDocumentId, docMapping)
            } else {
                logger.info("Creating ElasticSearch index ${esIndexName} for ${dataDocumentId} with alias ${indexName} and adding document mapping")
                createIndex(esIndexName, dataDocumentId, docMapping, indexName, settings)
                // logger.warn("========== Added mapping for ${dataDocumentId} to index ${esIndexName}:\n${docMapping}")
            }
        }

        @Override
        void verifyDataDocumentIndexes(List<Map> documentList) {
            Set<String> indexNames = new HashSet()
            Set<String> dataDocumentIds = new HashSet()
            for (Map document in documentList) {
                indexNames.add((String) document.get("_index"))
                dataDocumentIds.add((String) document.get("_type"))
            }
            for (String indexName in indexNames) checkCreateDataDocumentIndexes(indexName)
            for (String dataDocumentId in dataDocumentIds) checkCreateDataDocumentIndex(dataDocumentId)
        }

        @Override
        void bulkIndexDataDocument(List<Map> documentList) {
            int docsPerBulk = 1000
            int docListSize = documentList.size()

            String _index = null
            String _type = null
            String _id = null
            String esIndexName = null

            ArrayList<Map> actionSourceList = new ArrayList<Map>(docsPerBulk * 2)
            int curBulkDocs = 0
            int batchCount = 0
            for (Map document in documentList) {
                // logger.warn("====== Indexing document: ${document}")

                _index = document._index
                _type = document._type
                _id = document._id
                // String _timestamp = document._timestamp
                // As of ES 2.0 _index, _type, _id, and _timestamp shouldn't be in document to be indexed
                // clone document before removing fields so they are present for other code using the same data
                document = new LiteStringMap(document, docSkipKeys)
                // no longer needed with docSkipKeys: document.remove('_index'); document.remove('_type'); document.remove('_id'); document.remove('_timestamp')

                // as of ES 6.0, and required for 7 series, one index per doc type so one per dataDocumentId, cleaned up to be valid ES index name (all lower case, etc)
                esIndexName = ddIdToEsIndex(_type)

                // before indexing convert types needed for ES
                // hopefully not needed with Jackson settings, but if so: ElasticSearchUtil.convertTypesForEs(document)

                // add the document to the bulk index
                if (esVersionUnder7) {
                    actionSourceList.add([index:[_index:esIndexName, _type:_type, _id:_id]])
                } else {
                    actionSourceList.add([index:[_index:esIndexName, _id:_id]])
                }
                actionSourceList.add(document)

                curBulkDocs++

                if (curBulkDocs >= docsPerBulk) {
                    // logger.info("Bulk index batch ${batchCount}, cur docs ${curBulkDocs} of ${docListSize}, last index ${esIndexName} (for index ${_index} type ${_type})")
                    // logger.warn("last document: ${document}")
                    RestClient.RestResponse response = bulkResponse(null, actionSourceList, false)
                    if (response.statusCode < 200 || response.statusCode >= 300) {
                        checkResponse(response, "Bulk index", null)
                        curBulkDocs = 0
                        actionSourceList = null
                        break
                    }

                    /* don't support getting versions any more, generally waste of resources:
                    BulkItemResponse[] itemResponses = bulkResponse.getItems()
                    int itemResponsesSize = itemResponses.length
                    for (int i = 0; i < itemResponsesSize; i++) documentVersionList.add(itemResponses[i].getVersion())
                     */

                    // reset for the next set
                    curBulkDocs = 0
                    actionSourceList = new ArrayList<Map>(docsPerBulk * 2)
                    batchCount++
                }
            }
            if (curBulkDocs > 0) {
                // logger.info("Bulk index last, cur docs ${curBulkDocs} of ${docListSize}, last index ${esIndexName} (for index ${_index} type ${_type})")
                RestClient.RestResponse response = bulkResponse(null, actionSourceList, false)
                checkResponse(response, "Bulk index", null)

                /* don't support getting versions any more, generally waste of resources:
                BulkItemResponse[] itemResponses = bulkResponse.getItems()
                int itemResponsesSize = itemResponses.length
                for (int i = 0; i < itemResponsesSize; i++) documentVersionList.add(itemResponses[i].getVersion())
                 */
            }
        }

        @Override String objectToJson(Object jsonObject) { return ElasticFacadeImpl.objectToJson(jsonObject) }
        @Override Object jsonToObject(String jsonString) { return ElasticFacadeImpl.jsonToObject(jsonString) }

        String prefixIndexName(String index) {
            if (index == null) return null
            index = index.trim()
            if (index.isEmpty()) return null
            return indexPrefix != null && !index.startsWith(indexPrefix) ? indexPrefix.concat(index) : index
        }
        String unprefixIndexName(String index) {
            if (index == null) return null
            index = index.trim()
            if (index.isEmpty()) return null
            return indexPrefix != null && index.startsWith(indexPrefix) ? index.substring(indexPrefix.length()) : index
        }
    }

    // ============== Utility Methods ==============

    static void checkResponse(RestClient.RestResponse response, String operation, String index) {
        if (response.statusCode >= 200 && response.statusCode < 300) return

        String msg = "${operation}${index ? ' on index ' + index : ''} failed with code ${response.statusCode}: ${response.reasonPhrase}"
        String responseText = response.text()
        boolean logRequestBody = true
        try {
            Map responseMap = (Map) jsonToObject(response.text())
            Map errorMap = (Map) responseMap.error
            if (errorMap) {
                msg = msg + ' - ' + errorMap.reason + ' (line ' + errorMap.line + ' col ' + errorMap.col + ')'
                // maybe not, just always do it: logRequestBody = errorMap.type == 'parsing_exception'
            }
        } catch (Throwable t) {
            logger.error("Error parsing ElasticSearch response: ${t.toString()}")
        }

        String requestUri = response.getClient().getUriString()
        String requestBody = response.getClient().getBodyText()
        if (requestBody != null && requestBody.length() > 2000) requestBody = requestBody.substring(0, 2000)
        logger.error("ElasticSearch ${msg}${responseText ? '\nResponse: ' + responseText : ''}${requestUri ? '\nURI: ' + requestUri : ''}${requestBody ? '\nRequest: ' + requestBody : ''}")

        throw new BaseException(msg)
    }

    static String objectToJson(Object jsonObject) {
        if (jsonObject instanceof String) return (String) jsonObject
        return jacksonMapper.writeValueAsString(jsonObject)
    }
    static Object jsonToObject(String jsonString) {
        try {
            JsonNode jsonNode = jacksonMapper.readTree(jsonString)
            if (jsonNode.isObject()) {
                return jacksonMapper.treeToValue(jsonNode, Map.class)
            } else if (jsonNode.isArray()) {
                return jacksonMapper.treeToValue(jsonNode, List.class)
            } else {
                throw new BaseException("JSON text root is not an Object or Array")
            }
        } catch (Throwable t) {
            throw new BaseException("Error parsing JSON: " + t.toString(), t)
        }
    }

    /* with Jackson configuration for serialization should not need this:
    static void convertTypesForEs(Map theMap) {
        // initially just Timestamp to Long using Timestamp.getTime() to handle ES time zone issues with Timestamp objects
        for (Map.Entry entry in theMap.entrySet()) {
            Object valObj = entry.getValue()
            if (valObj instanceof Timestamp) {
                entry.setValue(((Timestamp) valObj).getTime())
            } else if (valObj instanceof java.sql.Date) {
                entry.setValue(valObj.toString())
            } else if (valObj instanceof BigDecimal) {
                entry.setValue(((BigDecimal) valObj).doubleValue())
            } else if (valObj instanceof GString) {
                entry.setValue(valObj.toString())
            } else if (valObj instanceof Map) {
                convertTypesForEs((Map) valObj)
            } else if (valObj instanceof Collection) {
                for (Object colObj in ((Collection) valObj)) {
                    if (colObj instanceof Map) {
                        convertTypesForEs((Map) colObj)
                    } else {
                        // if first in list isn't a Map don't expect others to be
                        break
                    }
                }
            }
        }
    }
    */

    static String ddIdToEsIndex(String dataDocumentId) {
        if (dataDocumentId.contains("_")) return dataDocumentId.toLowerCase()
        return EntityJavaUtil.camelCaseToUnderscored(dataDocumentId).toLowerCase()
    }
    static String esIndexToDdId(String index) {
        return EntityJavaUtil.underscoredToCamelCase(index, true)
    }

    static final Map<String, String> esTypeMap = [id:'keyword', 'id-long':'keyword', date:'date', time:'text',
            'date-time':'date', 'number-integer':'long', 'number-decimal':'double', 'number-float':'double',
            'currency-amount':'double', 'currency-precise':'double', 'text-indicator':'keyword', 'text-short':'text',
            'text-medium':'text', 'text-intermediate':'text', 'text-long':'text', 'text-very-long':'text', 'binary-very-long':'binary']

    static Map makeElasticSearchMapping(String dataDocumentId, ExecutionContextFactoryImpl ecfi) {
        EntityValue dataDocument = ecfi.entityFacade.find("moqui.entity.document.DataDocument")
                .condition("dataDocumentId", dataDocumentId).useCache(true).one()
        if (dataDocument == null) throw new EntityException("No DataDocument found with ID [${dataDocumentId}]")
        EntityList dataDocumentFieldList = dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, null, true, false)
        EntityList dataDocumentRelAliasList = dataDocument.findRelated("moqui.entity.document.DataDocumentRelAlias", null, null, true, false)

        Map<String, String> relationshipAliasMap = [:]
        for (EntityValue dataDocumentRelAlias in dataDocumentRelAliasList)
            relationshipAliasMap.put((String) dataDocumentRelAlias.relationshipName, (String) dataDocumentRelAlias.documentAlias)

        String primaryEntityName = dataDocument.primaryEntityName
        // String primaryEntityAlias = relationshipAliasMap.get(primaryEntityName) ?: primaryEntityName
        EntityDefinition primaryEd = ecfi.entityFacade.getEntityDefinition(primaryEntityName)

        Map<String, Object> rootProperties = [_entity:[type:'keyword']] as Map<String, Object>
        Map<String, Object> mappingMap = [properties:rootProperties] as Map<String, Object>

        List<String> remainingPkFields = new ArrayList(primaryEd.getPkFieldNames())
        for (EntityValue dataDocumentField in dataDocumentFieldList) {
            String fieldPath = (String) dataDocumentField.fieldPath
            ArrayList<String> fieldPathElementList = EntityDataDocument.fieldPathToList(fieldPath)
            if (fieldPathElementList.size() == 1) {
                // is a field on the primary entity, put it there
                String fieldName = ((String) dataDocumentField.fieldNameAlias) ?: fieldPath
                String mappingType = (String) dataDocumentField.fieldType
                String sortable = (String) dataDocumentField.sortable
                if (fieldPath.startsWith("(")) {
                    rootProperties.put(fieldName, makePropertyMap(null, mappingType ?: 'double', sortable))
                } else {
                    FieldInfo fieldInfo = primaryEd.getFieldInfo(fieldPath)
                    if (fieldInfo == null) throw new EntityException("Could not find field [${fieldPath}] for entity [${primaryEd.getFullEntityName()}] in DataDocument [${dataDocumentId}]")
                    rootProperties.put(fieldName, makePropertyMap(fieldInfo.type, mappingType, sortable))
                    if (remainingPkFields.contains(fieldPath)) remainingPkFields.remove(fieldPath)
                }

                continue
            }

            Map<String, Object> currentProperties = rootProperties
            EntityDefinition currentEd = primaryEd
            int fieldPathElementListSize = fieldPathElementList.size()
            for (int i = 0; i < fieldPathElementListSize; i++) {
                String fieldPathElement = (String) fieldPathElementList.get(i)
                if (i < (fieldPathElementListSize - 1)) {
                    EntityJavaUtil.RelationshipInfo relInfo = currentEd.getRelationshipInfo(fieldPathElement)
                    if (relInfo == null) throw new EntityException("Could not find relationship [${fieldPathElement}] for entity [${currentEd.getFullEntityName()}] in DataDocument [${dataDocumentId}]")
                    currentEd = relInfo.relatedEd
                    if (currentEd == null) throw new EntityException("Could not find entity [${relInfo.relatedEntityName}] in DataDocument [${dataDocumentId}]")

                    // only put type many in sub-objects, same as DataDocument generation
                    if (!relInfo.isTypeOne) {
                        String objectName = relationshipAliasMap.get(fieldPathElement) ?: fieldPathElement
                        Map<String, Object> subObject = (Map<String, Object>) currentProperties.get(objectName)
                        Map<String, Object> subProperties
                        if (subObject == null) {
                            subProperties = new HashMap<>()
                            // using type:'nested' with include_in_root:true seems to support nested queries and currently works with query string full path field names too
                            // NOTE: keep an eye on this and if it breaks for our primary use case which is query strings with full path field names then remove type:'nested' and include_in_root
                            subObject = [properties:subProperties, type:'nested', include_in_root:true] as Map<String, Object>
                            currentProperties.put(objectName, subObject)
                        } else {
                            subProperties = (Map<String, Object>) subObject.get("properties")
                        }
                        currentProperties = subProperties
                    }
                } else {
                    String fieldName = (String) dataDocumentField.fieldNameAlias ?: fieldPathElement
                    String mappingType = (String) dataDocumentField.fieldType
                    String sortable = (String) dataDocumentField.sortable
                    if (fieldPathElement.startsWith("(")) {
                        currentProperties.put(fieldName, makePropertyMap(null, mappingType ?: 'double', sortable))
                    } else {
                        FieldInfo fieldInfo = currentEd.getFieldInfo(fieldPathElement)
                        if (fieldInfo == null) throw new EntityException("Could not find field [${fieldPathElement}] for entity [${currentEd.getFullEntityName()}] in DataDocument [${dataDocumentId}]")
                        currentProperties.put(fieldName, makePropertyMap(fieldInfo.type, mappingType, sortable))
                    }
                }
            }
        }

        // now get all the PK fields not aliased explicitly
        for (String remainingPkName in remainingPkFields) {
            FieldInfo fieldInfo = primaryEd.getFieldInfo(remainingPkName)
            String mappingType = esTypeMap.get(fieldInfo.type) ?: 'keyword'
            Map propertyMap = makePropertyMap(null, mappingType, null)
            // don't use not_analyzed in more recent ES: if (fieldInfo.type.startsWith("id")) propertyMap.index = 'not_analyzed'
            rootProperties.put(remainingPkName, propertyMap)
        }

        if (logger.isTraceEnabled()) logger.trace("Generated ElasticSearch mapping for ${dataDocumentId}: \n${JsonOutput.prettyPrint(JsonOutput.toJson(mappingMap))}")

        return mappingMap
    }
    static Map makePropertyMap(String fieldType, String mappingType, String sortable) {
        if (!mappingType) mappingType = esTypeMap.get(fieldType) ?: 'text'
        Map<String, Object> propertyMap = new LinkedHashMap<>()
        propertyMap.put("type", mappingType)
        if ("Y".equals(sortable) && "text".equals(mappingType)) propertyMap.put("fields", [keyword: [type: "keyword"]])
        if ("date-time".equals(fieldType)) propertyMap.format = "date_time||epoch_millis||date_time_no_millis||yyyy-MM-dd HH:mm:ss.SSS||yyyy-MM-dd HH:mm:ss.S||yyyy-MM-dd"
        else if ("date".equals(fieldType)) propertyMap.format = "date||strict_date_optional_time||epoch_millis"
        // if (fieldType.startsWith("id")) propertyMap.index = 'not_analyzed'
        return propertyMap
    }
}
