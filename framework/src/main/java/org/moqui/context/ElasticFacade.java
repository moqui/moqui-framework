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
package org.moqui.context;

import org.moqui.util.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/** A facade for ElasticSearch operations.
 *
 * Designed for ElasticSearch 7.0 and later with the one doc type per index constraint.
 * See https://www.elastic.co/guide/en/elasticsearch/reference/current/removal-of-types.html
 */
public interface ElasticFacade {

    /** Get a client for the 'default' cluster, same as calling getClient("default") */
    ElasticClient getDefault();
    /** Get a client for named cluster configured in Moqui Conf XML elastic-facade.cluster */
    ElasticClient getClient(String clusterName);

    List<ElasticClient> getClientList();

    interface ElasticClient {
        String getClusterName();
        String getClusterLocation();
        /** Returns a Map with the response from ElasticSearch for GET on the root path with ES server info */
        Map getServerInfo();

        /** Returns true if index or alias exists. See https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-exists.html */
        boolean indexExists(String index);
        /** Returns true if alias exists. See https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-alias-exists.html */
        boolean aliasExists(String alias);
        /** Create an index with optional document mapping and alias. See https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-create-index.html */
        void createIndex(String index, Map docMapping, String alias);
        /** Put document mapping on an existing index. See https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-put-mapping.html */
        void putMapping(String index, Map docMapping);
        /** Delete an index. See https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-delete-index.html */
        void deleteIndex(String index);

        /** Index a complete document (create or update). See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-index_.html */
        void index(String index, String _id, Map document);
        /** Partial document update. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-update.html */
        void update(String index, String _id, Map documentFragment);
        /** Delete a document. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-delete.html */
        void delete(String index, String _id);
        /** Delete documents by query. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-delete-by-query.html */
        Integer deleteByQuery(String index, Map queryMap);
        /** Perform bulk operations. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html
         * @param actionSourceList List of action objects each followed by a source object if relevant. */
        void bulk(String index, List<Map> actionSourceList);
        /** Bulk index documents with given index name and _id from the idField in each document (if idField empty don't specify ID, let ES generate) */
        void bulkIndex(String index, String idField, List<Map> documentList);
        void bulkIndex(String index, String docType, String idField, List<Map> documentList, boolean refresh);

        /** Get full/wrapped single document by ID. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-get.html */
        Map get(String index, String _id);
        /** Get source for a single document by ID. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-get.html */
        Map getSource(String index, String _id);
        /** Get multiple documents by ID. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-multi-get.html */
        List<Map> get(String index, List<String> _idList);

        /** Search documents and get the plain object response.
         * See https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-body.html */
        Map search(String index, Map searchMap);
        /** Search documents. Result is the list in 'hits.hits' from the plain object returned for convenience.
         * See https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-body.html */
        List<Map> searchHits(String index, Map searchMap);
        /** Validate a query Map.
         * Returns null if valid otherwise returns Map from JSON response with 'valid' boolean and if explain is true also 'explanations' for more information.
         * See https://www.elastic.co/guide/en/elasticsearch/reference/current/search-validate.html
         * @param queryMap Map sent to ElasticSearch as the 'query' field (should not include 'query' entry, may include 'bool', 'query_string', etc) */
        Map validateQuery(String index, Map queryMap, boolean explain);

        /** Count documents and get the long int value from the response.
         * See https://www.elastic.co/guide/en/elasticsearch/reference/current/search-count.html */
        long count(String index, Map countMap);
        /** Count documents and get the plain object response.
         * See https://www.elastic.co/guide/en/elasticsearch/reference/current/search-count.html */
        Map countResponse(String index, Map countMap);

        /** Create a Point-In-Time checkpoint and get the ID
         * See https://www.elastic.co/guide/en/elasticsearch/reference/current/paginate-search-results.html#scroll-search-results */
        String getPitId(String index, String keepAlive);
        /** Delete a Point-In-Time checkpoint, should always be done when finished (close operation) */
        void deletePit(String pitId);

        /** Basic REST endpoint synchronous call */
        RestClient.RestResponse call(RestClient.Method method, String index, String path, Map<String, String> parameters, Object bodyJsonObject);
        /** Basic REST endpoint future (asynchronous) call */
        Future<RestClient.RestResponse> callFuture(RestClient.Method method, String index, String path, Map<String, String> parameters, Object bodyJsonObject);
        /** Make a RestClient with configured protocol/host/port and user/password if configured, RequestFactory for this ElasticClient, and the given parameters */
        RestClient makeRestClient(RestClient.Method method, String index, String path, Map<String, String> parameters);

        /** Check and if needed create ElasticSearch indexes for all DataDocument records with given indexName */
        void checkCreateDataDocumentIndexes(String indexName);
        /** Check and if needed create ElasticSearch index for DataDocument with given ID */
        void checkCreateDataDocumentIndex(String dataDocumentId);
        /** Put document mappings for all DataDocument records with given indexName */
        void putDataDocumentMappings(String indexName);
        /** Verify index aliases and dataDocumentId based indexes from all distinct _index and _type values in documentList */
        void verifyDataDocumentIndexes(List<Map> documentList);
        /** Bulk index documents with standard _index, _type, _id, and _timestamp fields which are used for the index and id per
         * document but removed from the actual document sent to ElasticSearch; note that for legacy reasons related to one type
         * per index the _type is used for the index name */
        void bulkIndexDataDocument(List<Map> documentList);

        /** Convert Object (generally Map or List) to JSON String using internal ElasticSearch specific settings */
        String objectToJson(Object jsonObject);
        /** Convert JSON String to Object (generally Map or List) using internal ElasticSearch specific settings */
        Object jsonToObject(String jsonString);
    }
}
