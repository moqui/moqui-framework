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

/** A facade for ElasticSearch operations. */
public interface ElasticFacade {

    /** Get a client for the 'default' cluster, same as calling getClient("default") */
    ElasticClient getDefault();
    /** Get a client for named cluster configured in Moqui Conf XML elastic-facade.cluster */
    ElasticClient getClient(String clusterName);

    interface ElasticClient {
        /** Returns true if index or alias exists. See https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-exists.html */
        boolean indexExists(String index);
        /** Returns true if alias exists. See https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-alias-exists.html */
        boolean aliasExists(String alias);
        /** Create an index with optional document mapping. See https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-create-index.html */
        void createIndex(String index, String docType, Map docMapping);
        /** Put document mapping on an existing index. See https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-put-mapping.html */
        void putMapping(String index, String docType, Map docMapping);
        /** Delete an index. See https://www.elastic.co/guide/en/elasticsearch/reference/current/indices-delete-index.html */
        void deleteIndex(String index);

        /** Check and if needed create ElasticSearch indexes for all DataDocument records with given indexName */
        void checkCreateDataDocumentIndex(String indexName);
        /** Check and if needed create ElasticSearch index for DataDocument with given ID */
        void checkCreateDataDocument(String dataDocumentId);
        /** Put document mappings for all DataDocument records with given indexName */
        void putDataDocumentMappings(String indexName);

        /** Index a document. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-index_.html */
        void index(String index, String _id, Map<String, Object> document);
        /** Partial document update. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-update.html */
        void update(String index, String _id, Map<String, Object> documentFragment);
        /** Delete a document. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-delete.html */
        void delete(String index, String _id);
        /** Delete documents by query. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-delete-by-query.html */
        void deleteByQuery(String index, Map<String, Object> query);
        /** Perform bulk operations. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-bulk.html
         * @param actionSourceList List of action objects each followed by a source object if relevant. */
        void bulk(String index, List<Map<String, Object>> actionSourceList);

        /** Get a single document by ID. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-get.html */
        Map<String, Object> get(String index, String _id);
        /** Get multiple documents by ID. See https://www.elastic.co/guide/en/elasticsearch/reference/current/docs-multi-get.html */
        List<Map<String, Object>> get(String index, List<String> _idList);

        /** Search documents and get the plain object response back.
         * See https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-body.html */
        Map<String, Object> search(String index, Map<String, Object> query);
        /** Search documents. Result is the list in 'hits.hits' from the plain object returned for convenience.
         * See https://www.elastic.co/guide/en/elasticsearch/reference/current/search-request-body.html */
        List<Map<String, Object>> searchHits(String index, Map<String, Object> query);

        /** Basic REST endpoint synchronous call, all operation specific synchronous methods use this method */
        RestClient.RestResponse call(RestClient.Method method, String path, Map<String, String> parameters, String jsonBody);
        /** Basic REST endpoint future (asynchronous) call */
        Future<RestClient.RestResponse> callFuture(RestClient.Method method, String path, Map<String, String> parameters, String jsonBody);
    }
}
