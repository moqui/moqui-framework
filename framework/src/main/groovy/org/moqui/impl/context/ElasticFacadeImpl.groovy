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

import groovy.transform.CompileStatic
import org.moqui.context.ElasticFacade
import org.moqui.util.MNode
import org.moqui.util.RestClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.Future

@CompileStatic
class ElasticFacadeImpl implements ElasticFacade {
    protected final static Logger logger = LoggerFactory.getLogger(ElasticFacadeImpl.class)

    public final ExecutionContextFactoryImpl ecfi
    private final Map<String, ElasticClientImpl> clientByClusterName = new HashMap<>()

    ElasticFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi

        MNode elasticFacadeNode = ecfi.getConfXmlRoot().first("elastic-facade")
        ArrayList<MNode> clusterNodeList = elasticFacadeNode.children("cluster")
        for (MNode clusterNode in clusterNodeList) {
            String clusterName = clusterNode.attribute("name")
            try {
                clientByClusterName.put(clusterName, new ElasticClientImpl(clusterNode, ecfi))
            } catch (Throwable t) {
                logger.error("Error initializing ElasticClient for cluster ${clusterName}", t)
            }
        }
    }

    void destroy() {
        for (ElasticClientImpl eci in clientByClusterName.values()) eci.destroy()
    }

    @Override ElasticClient getDefault() {
        return clientByClusterName.get("default")
    }

    @Override ElasticClient getClient(String clusterName) {
        return clientByClusterName.get(clusterName)
    }

    static class ElasticClientImpl implements ElasticClient {
        private final ExecutionContextFactoryImpl ecfi
        private final MNode clusterNode
        private final String clusterName, clusterUser, clusterPassword
        private final String clusterUrl, clusterProtocol, clusterHost
        private final int clusterPort
        private RestClient.PooledRequestFactory requestFactory

        ElasticClientImpl(MNode clusterNode, ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi
            this.clusterNode = clusterNode

            this.clusterName = clusterNode.attribute("name")
            this.clusterUser = clusterNode.attribute("user")
            this.clusterPassword = clusterNode.attribute("password")
            String urlTemp = clusterNode.attribute("url")
            if (urlTemp.endsWith("/")) urlTemp = urlTemp.substring(0, urlTemp.length() - 1)
            this.clusterUrl = urlTemp
            URI uri = new URI(urlTemp)
            clusterProtocol = uri.getScheme()
            clusterHost = uri.getHost()
            clusterPort = uri.getPort() ?: 9200

            String poolMaxStr = clusterNode.attribute("pool-max")
            String queueSizeStr = clusterNode.attribute("queue-size")

            requestFactory = new RestClient.PooledRequestFactory("ES_" + clusterName)
            if (poolMaxStr) requestFactory.poolSize(Integer.parseInt(poolMaxStr))
            if (queueSizeStr) requestFactory.queueSize(Integer.parseInt(queueSizeStr))
            requestFactory.init()
        }

        void destroy() { requestFactory.destroy() }

        @Override
        boolean indexExists(String index) {
            return false
        }

        @Override
        boolean aliasExists(String alias) {
            return false
        }

        @Override
        void createIndex(String index, String docType, Map docMapping) {

        }

        @Override
        void putMapping(String index, String docType, Map docMapping) {

        }

        @Override
        void deleteIndex(String index) {

        }

        @Override
        void checkCreateDataDocumentIndex(String indexName) {

        }

        @Override
        void checkCreateDataDocument(String dataDocumentId) {

        }

        @Override
        void putDataDocumentMappings(String indexName) {

        }

        @Override
        void index(String index, String _id, Map<String, Object> document) {

        }

        @Override
        void update(String index, String _id, Map<String, Object> documentFragment) {

        }

        @Override
        void delete(String index, String _id) {

        }

        @Override
        void deleteByQuery(String index, Map<String, Object> query) {

        }

        @Override
        void bulk(String index, List<Map<String, Object>> actionSourceList) {

        }

        @Override
        Map<String, Object> get(String index, String _id) {
            return null
        }

        @Override
        List<Map<String, Object>> get(String index, List<String> _idList) {
            return null
        }

        @Override
        Map<String, Object> search(String index, Map<String, Object> query) {
            return null
        }

        @Override
        List<Map<String, Object>> searchHits(String index, Map<String, Object> query) {
            return null
        }

        @Override
        RestClient.RestResponse call(RestClient.Method method, String path, Map<String, String> parameters, Object bodyJsonObject) {
            RestClient restClient = makeRestClient(method, path, parameters).jsonObject(bodyJsonObject)
            return restClient.call()
        }

        @Override
        Future<RestClient.RestResponse> callFuture(RestClient.Method method, String path, Map<String, String> parameters, Object bodyJsonObject) {
            RestClient restClient = makeRestClient(method, path, parameters).jsonObject(bodyJsonObject)
            return restClient.callFuture()
        }

        @Override
        RestClient makeRestClient(RestClient.Method method, String path, Map<String, String> parameters) {
            RestClient restClient = new RestClient().withRequestFactory(requestFactory).method(method)
            restClient.uri().protocol(clusterProtocol).host(clusterHost).port(clusterPort).path(path).parameters(parameters).build()
            if (clusterUser) restClient.basicAuth(clusterUser, clusterPassword)
            return restClient
        }
    }
}
