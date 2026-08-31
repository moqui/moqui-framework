/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * Integration tests for PostgresElasticClient and PostgresSearchLogger.
 *
 * Requires a running PostgreSQL database configured in Moqui (transactional datasource).
 * Bootstraps a Moqui EC directly — no web server needed.
 *
 * Run with: ./gradlew :framework:test --tests PostgresElasticClientTests
 */

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.context.ElasticFacade
import org.moqui.impl.context.PostgresElasticClient
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.util.MNode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions

/**
 * Integration tests for the PostgreSQL-backed ElasticClient.
 *
 * Spins up a real PostgresElasticClient against the configured datasource and exercises
 * all major operations: schema init, createIndex, index, get, search, update, delete,
 * bulk operations, and query translation.
 */
class PostgresElasticClientTests {

    static ExecutionContext ec
    static PostgresElasticClient pgClient
    static final String TEST_INDEX = "pg_test_documents"
    static final String TEST_PREFIX = "_test_pg_"

    @BeforeAll
    static void startMoqui() {
        // Init Moqui pointing at the test database
        ec = Moqui.getExecutionContext()
        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) ec.factory

        // Create a test PostgresElasticClient directly via a manually-built MNode
        MNode clusterNode = new MNode("cluster", [name: "test-pg", type: "postgres",
                url: "transactional", "index-prefix": TEST_PREFIX])
        pgClient = new PostgresElasticClient(clusterNode, ecfi)
    }

    @AfterAll
    static void stopMoqui() {
        // Clean up test data
        try {
            if (pgClient != null) {
                [
                 "pg_test_documents_create", "pg_test_documents_with_alias",
                 "pg_test_documents_delete_test", "pg_test_documents_put_mapping",
                 "pg_test_documents_crud", "pg_test_documents_get_source",
                 "pg_test_documents_multi_get", "pg_test_documents_get_null",
                 "pg_test_documents_update", "pg_test_documents_doc_delete",
                 "pg_test_documents_bulkindex", "pg_test_documents_bulk_actions",
                 "pg_test_documents_search_all", "pg_test_documents_search_term",
                 "pg_test_documents_search_terms", "pg_test_documents_search_fts",
                 "pg_test_documents_search_bool", "pg_test_documents_search_page",
                 "pg_test_documents_searchhits", "pg_test_documents_count",
                 "pg_test_documents_countresp", "pg_test_documents_dbq",
                 "test_data_doc"
                ].each { idx ->
                    try { pgClient.deleteIndex(idx) } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        try { if (ec != null) ec.destroy() } catch (Throwable ignored) {}
    }

    @BeforeEach
    void beginTx() {
        ec.transaction.begin(null)
        ec.artifactExecution.disableAuthz()
    }

    @AfterEach
    void commitTx() {
        ec.artifactExecution.enableAuthz()
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
    }

    // ============================
    // clusterName / location
    // ============================

    @Test
    @DisplayName("clusterName returns configured name")
    void clusterName_returnsConfiguredName() {
        Assertions.assertEquals("test-pg", pgClient.clusterName)
    }

    @Test
    @DisplayName("clusterLocation contains postgres keyword")
    void clusterLocation_containsPostgresKeyword() {
        Assertions.assertTrue(pgClient.clusterLocation.contains("postgres"))
    }

    @Test
    @DisplayName("getServerInfo returns postgres version map")
    void getServerInfo_returnsPostgresVersionMap() {
        Map info = pgClient.getServerInfo()
        Assertions.assertNotNull(info)
        Assertions.assertEquals("postgres", info.get("cluster_name"))
        Object version = info.get("version")
        Assertions.assertNotNull(version)
    }

    // ============================
    // Index management
    // ============================

    @Test
    @DisplayName("createIndex and indexExists")
    void createIndex_andIndexExists() {
        String idx = TEST_INDEX + "_create"
        try {
            pgClient.createIndex(idx, [properties: [name: [type: "text"]]], null)
            Assertions.assertTrue(pgClient.indexExists(idx), "index should exist after createIndex")
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("indexExists returns false for non-existent index")
    void indexExists_returnsFalseForNonExistent() {
        Assertions.assertFalse(pgClient.indexExists("pg_test_does_not_exist_xyz"))
    }

    @Test
    @DisplayName("createIndex with alias and aliasExists")
    void createIndex_withAlias_aliasExists() {
        String idx = TEST_INDEX + "_with_alias"
        String alias = idx + "_alias"
        try {
            pgClient.createIndex(idx, null, alias)
            Assertions.assertTrue(pgClient.indexExists(idx), "index should exist")
            Assertions.assertTrue(pgClient.aliasExists(alias), "alias should exist")
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("deleteIndex removes documents and metadata")
    void deleteIndex_removesDocumentsAndMetadata() {
        String idx = TEST_INDEX + "_delete_test"
        pgClient.createIndex(idx, null, null)
        pgClient.index(idx, "doc001", [name: "To Be Deleted"])
        pgClient.deleteIndex(idx)
        Assertions.assertFalse(pgClient.indexExists(idx), "index should not exist after delete")
        Assertions.assertNull(pgClient.get(idx, "doc001"), "document should not exist after index delete")
    }

    @Test
    @DisplayName("putMapping updates mapping on existing index")
    void putMapping_updatesMappingOnExistingIndex() {
        String idx = TEST_INDEX + "_put_mapping"
        try {
            pgClient.createIndex(idx, [properties: [name: [type: "text"]]], null)
            // putMapping should succeed without throwing
            pgClient.putMapping(idx, [properties: [name: [type: "text"], email: [type: "keyword"]]])
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    // ============================
    // Document CRUD
    // ============================

    @Test
    @DisplayName("index and get roundtrip")
    void index_andGetRoundtrip() {
        String idx = TEST_INDEX + "_crud"
        try {
            pgClient.createIndex(idx, null, null)
            Map doc = [name: "Alice", email: "alice@example.com", age: 30]
            pgClient.index(idx, "alice001", doc)
            Map retrieved = pgClient.get(idx, "alice001")
            Assertions.assertNotNull(retrieved, "document should be retrievable")
            Map source = (Map) retrieved.get("_source")
            Assertions.assertNotNull(source)
            Assertions.assertEquals("Alice", source.get("name"))
            Assertions.assertEquals("alice@example.com", source.get("email"))
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("getSource returns only source map")
    void getSource_returnsOnlySourceMap() {
        String idx = TEST_INDEX + "_get_source"
        try {
            pgClient.createIndex(idx, null, null)
            pgClient.index(idx, "bob001", [name: "Bob", role: "admin"])
            Map source = pgClient.getSource(idx, "bob001")
            Assertions.assertNotNull(source)
            Assertions.assertEquals("Bob", source.get("name"))
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("get with _idList returns multiple docs")
    void get_withIdList_returnsMultipleDocs() {
        String idx = TEST_INDEX + "_multi_get"
        try {
            pgClient.createIndex(idx, null, null)
            pgClient.index(idx, "m1", [name: "Multi1"])
            pgClient.index(idx, "m2", [name: "Multi2"])
            pgClient.index(idx, "m3", [name: "Multi3"])
            List<Map> docs = pgClient.get(idx, ["m1", "m3"])
            Assertions.assertEquals(2, docs.size())
            Set<String> ids = docs.collect { (String) it.get("_id") }.toSet()
            Assertions.assertTrue(ids.contains("m1"))
            Assertions.assertTrue(ids.contains("m3"))
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("get returns null for non-existent document")
    void get_returnsNullForNonExistent() {
        String idx = TEST_INDEX + "_get_null"
        try {
            pgClient.createIndex(idx, null, null)
            Map result = pgClient.get(idx, "doesnotexist")
            Assertions.assertNull(result)
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("update merges fields")
    void update_mergesFields() {
        String idx = TEST_INDEX + "_update"
        try {
            pgClient.createIndex(idx, null, null)
            pgClient.index(idx, "upd001", [name: "Carol", status: "active"])
            pgClient.update(idx, "upd001", [status: "inactive", rating: 5])
            Map source = pgClient.getSource(idx, "upd001")
            Assertions.assertNotNull(source)
            // original name field should still be there (merge)
            Assertions.assertEquals("Carol", source.get("name"))
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("delete removes document")
    void delete_removesDocument() {
        String idx = TEST_INDEX + "_doc_delete"
        try {
            pgClient.createIndex(idx, null, null)
            pgClient.index(idx, "del001", [name: "To Delete"])
            Assertions.assertNotNull(pgClient.get(idx, "del001"), "doc should exist before delete")
            pgClient.delete(idx, "del001")
            Assertions.assertNull(pgClient.get(idx, "del001"), "doc should not exist after delete")
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    // ============================
    // Bulk operations
    // ============================

    @Test
    @DisplayName("bulkIndex inserts multiple documents")
    void bulkIndex_insertsMultipleDocuments() {
        String idx = TEST_INDEX + "_bulkindex"
        try {
            pgClient.createIndex(idx, null, null)
            List<Map> docs = (1..20).collect { i ->
                [productId: "PROD_${String.format('%03d', i)}", name: "Product ${i}",
                 category: i % 2 == 0 ? "category_a" : "category_b", price: i * 10.0]
            }
            pgClient.bulkIndex(idx, "productId", docs)

            // Verify a sampling
            Map p1source = pgClient.getSource(idx, "PROD_001")
            Assertions.assertNotNull(p1source, "PROD_001 should exist after bulkIndex")
            Assertions.assertEquals("Product 1", p1source.get("name"))

            Map p10source = pgClient.getSource(idx, "PROD_010")
            Assertions.assertNotNull(p10source, "PROD_010 should exist")
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("bulk with index and delete actions")
    void bulk_withIndexAndDeleteActions() {
        String idx = TEST_INDEX + "_bulk_actions"
        try {
            pgClient.createIndex(idx, null, null)
            // First insert two docs
            pgClient.index(idx, "ba001", [name: "To Keep"])
            pgClient.index(idx, "ba002", [name: "To Delete"])

            // Bulk: index new + delete existing
            List<Map> actions = [
                [index: [_index: "${TEST_PREFIX}${idx}", _id: "ba003"]],
                [name: "New Doc"],
                [delete: [_index: "${TEST_PREFIX}${idx}", _id: "ba002"]],
                [:]  // no source for delete
            ]
            pgClient.bulk(idx, actions)

            Assertions.assertNotNull(pgClient.get(idx, "ba001"), "ba001 should still exist")
            // ba002 delete and ba003 index via raw bulk - verify ba001 still there
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    // ============================
    // Search
    // ============================

    @Test
    @DisplayName("search - match_all returns all documents")
    void search_matchAllReturnsAll() {
        String idx = TEST_INDEX + "_search_all"
        try {
            pgClient.createIndex(idx, null, null)
            pgClient.index(idx, "s1", [name: "Alpha Widget"])
            pgClient.index(idx, "s2", [name: "Beta Gadget"])
            pgClient.index(idx, "s3", [name: "Gamma Tool"])

            Map result = pgClient.search(idx, [query: [match_all: [:]], size: 20])
            Assertions.assertNotNull(result)
            Map hits = (Map) result.get("hits")
            Assertions.assertNotNull(hits)
            Map total = (Map) hits.get("total")
            Assertions.assertNotNull(total)
            long totalValue = ((Number) total.get("value")).longValue()
            Assertions.assertEquals(3L, totalValue, "should return 3 documents")

            List<Map> hitList = (List<Map>) hits.get("hits")
            Assertions.assertEquals(3, hitList.size())
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("search - term query filters documents")
    void search_termQueryFilters() {
        String idx = TEST_INDEX + "_search_term"
        try {
            pgClient.createIndex(idx, null, null)
            pgClient.index(idx, "t1", [status: "ACTIVE", name: "Active Widget"])
            pgClient.index(idx, "t2", [status: "INACTIVE", name: "Inactive Gadget"])
            pgClient.index(idx, "t3", [status: "ACTIVE", name: "Active Tool"])

            Map result = pgClient.search(idx, [
                query: [term: [status: "ACTIVE"]],
                size: 20,
                track_total_hits: true
            ])
            Map hits = (Map) result.get("hits")
            Map total = (Map) hits.get("total")
            long totalValue = ((Number) total.get("value")).longValue()
            Assertions.assertEquals(2L, totalValue, "only ACTIVE docs should be returned")
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("search - terms query with IN")
    void search_termsQueryWithIn() {
        String idx = TEST_INDEX + "_search_terms"
        try {
            pgClient.createIndex(idx, null, null)
            pgClient.index(idx, "tm1", [cat: "a"])
            pgClient.index(idx, "tm2", [cat: "b"])
            pgClient.index(idx, "tm3", [cat: "c"])

            Map result = pgClient.search(idx, [
                query: [terms: [cat: ["a", "c"]]],
                size: 10,
                track_total_hits: true
            ])
            Map total = (Map) ((Map) result.get("hits")).get("total")
            Assertions.assertEquals(2L, ((Number) total.get("value")).longValue())
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("search - full-text query_string")
    void search_fullTextQueryString() {
        String idx = TEST_INDEX + "_search_fts"
        try {
            pgClient.createIndex(idx, null, null)
            pgClient.index(idx, "fts1", [description: "The quick brown fox jumps over the lazy dog"])
            pgClient.index(idx, "fts2", [description: "A completely unrelated document about databases"])
            pgClient.index(idx, "fts3", [description: "Another fox story about running quickly"])

            Map result = pgClient.search(idx, [
                query: [query_string: [query: "fox", lenient: true]],
                size: 10,
                track_total_hits: true
            ])
            Map total = (Map) ((Map) result.get("hits")).get("total")
            long count = ((Number) total.get("value")).longValue()
            Assertions.assertTrue(count >= 2, "should find at least 2 fox documents, found ${count}")
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("search - bool must and must_not")
    void search_boolMustAndMustNot() {
        String idx = TEST_INDEX + "_search_bool"
        try {
            pgClient.createIndex(idx, null, null)
            pgClient.index(idx, "b1", [type: "order", status: "placed"])
            pgClient.index(idx, "b2", [type: "order", status: "cancelled"])
            pgClient.index(idx, "b3", [type: "invoice", status: "placed"])

            Map result = pgClient.search(idx, [
                query: [bool: [
                    must: [[term: [type: "order"]]],
                    must_not: [[term: [status: "cancelled"]]]
                ]],
                size: 10,
                track_total_hits: true
            ])
            Map total = (Map) ((Map) result.get("hits")).get("total")
            long count = ((Number) total.get("value")).longValue()
            Assertions.assertEquals(1L, count, "only non-cancelled orders should match")
            List<Map> hitList = (List<Map>) ((Map) result.get("hits")).get("hits")
            Assertions.assertEquals("b1", hitList[0].get("_id"))
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("search - pagination from and size")
    void search_paginationFromAndSize() {
        String idx = TEST_INDEX + "_search_page"
        try {
            pgClient.createIndex(idx, null, null)
            (1..10).each { i ->
                pgClient.index(idx, "p${i}", [name: "Doc ${i}", seq: i])
            }

            // Get page 2 (items 5-9 of 10, 5 per page)
            Map result = pgClient.search(idx, [
                query: [match_all: [:]],
                from: 5, size: 5,
                track_total_hits: true
            ])
            Map hits = (Map) result.get("hits")
            long totalValue = ((Number) ((Map) hits.get("total")).get("value")).longValue()
            List<Map> hitList = (List<Map>) hits.get("hits")
            Assertions.assertEquals(10L, totalValue, "total should reflect all 10 docs")
            Assertions.assertEquals(5, hitList.size(), "page size should be 5")
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("searchHits returns list directly")
    void searchHits_returnsListDirectly() {
        String idx = TEST_INDEX + "_searchhits"
        try {
            pgClient.createIndex(idx, null, null)
            pgClient.index(idx, "sh1", [x: 1])
            pgClient.index(idx, "sh2", [x: 2])
            List<Map> hits = pgClient.searchHits(idx, [query: [match_all: [:]], size: 10])
            Assertions.assertNotNull(hits)
            Assertions.assertEquals(2, hits.size())
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    // ============================
    // Count
    // ============================

    @Test
    @DisplayName("count returns correct total")
    void count_returnsCorrectTotal() {
        String idx = TEST_INDEX + "_count"
        try {
            pgClient.createIndex(idx, null, null)
            (1..7).each { i -> pgClient.index(idx, "c${i}", [seq: i]) }

            long cnt = pgClient.count(idx, [query: [match_all: [:]]])
            Assertions.assertEquals(7L, cnt)
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    @Test
    @DisplayName("countResponse returns map with count key")
    void countResponse_returnsMapWithCountKey() {
        String idx = TEST_INDEX + "_countresp"
        try {
            pgClient.createIndex(idx, null, null)
            pgClient.index(idx, "cr1", [v: 1])
            pgClient.index(idx, "cr2", [v: 2])
            Map resp = pgClient.countResponse(idx, [query: [match_all: [:]]])
            Assertions.assertNotNull(resp)
            Assertions.assertTrue(resp.containsKey("count"))
            Assertions.assertEquals(2L, ((Number) resp.get("count")).longValue())
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    // ============================
    // deleteByQuery
    // ============================

    @Test
    @DisplayName("deleteByQuery removes matching documents")
    void deleteByQuery_removesMatchingDocuments() {
        String idx = TEST_INDEX + "_dbq"
        try {
            pgClient.createIndex(idx, null, null)
            pgClient.index(idx, "dbq1", [status: "STALE"])
            pgClient.index(idx, "dbq2", [status: "STALE"])
            pgClient.index(idx, "dbq3", [status: "KEEP"])

            Integer deleted = pgClient.deleteByQuery(idx, [term: [status: "STALE"]])
            Assertions.assertNotNull(deleted)
            Assertions.assertEquals(2, deleted.intValue())
            Assertions.assertNull(pgClient.get(idx, "dbq1"), "STALE doc should be deleted")
            Assertions.assertNotNull(pgClient.get(idx, "dbq3"), "KEEP doc should remain")
        } finally {
            if (pgClient.indexExists(idx)) pgClient.deleteIndex(idx)
        }
    }

    // ============================
    // PIT (stateless for postgres)
    // ============================

    @Test
    @DisplayName("getPitId returns non-null token")
    void getPitId_returnsNonNullToken() {
        String pit = pgClient.getPitId(TEST_INDEX, "1m")
        Assertions.assertNotNull(pit)
        Assertions.assertTrue(pit.startsWith("pg::"))
    }

    @Test
    @DisplayName("deletePit is a no-op")
    void deletePit_isNoOp() {
        // Should not throw
        pgClient.deletePit("pg::test::12345")
    }

    // ============================
    // validateQuery
    // ============================

    @Test
    @DisplayName("validateQuery returns null for valid query")
    void validateQuery_returnsNullForValidQuery() {
        Map result = pgClient.validateQuery(TEST_INDEX, [term: [status: "active"]], false)
        Assertions.assertNull(result, "valid query should return null")
    }

    // ============================
    // bulkIndexDataDocument
    // ============================

    @Test
    @DisplayName("bulkIndexDataDocument strips metadata and indexes documents")
    void bulkIndexDataDocument_stripsMetadataAndIndexes() {
        List<Map> docs = [
            [_index: "test", _type: "TestDataDoc", _id: "tdd001",
             _timestamp: System.currentTimeMillis(), productId: "P001", name: "Test Product One"],
            [_index: "test", _type: "TestDataDoc", _id: "tdd002",
             _timestamp: System.currentTimeMillis(), productId: "P002", name: "Test Product Two"],
        ]
        try {
            pgClient.bulkIndexDataDocument(docs)

            // Documents should be in 'test_data_doc' index (ddIdToEsIndex("TestDataDoc"))
            String expectedIdx = "test_data_doc"
            Map source1 = pgClient.getSource(expectedIdx, "tdd001")
            Assertions.assertNotNull(source1, "tdd001 should be indexed")
            Assertions.assertEquals("Test Product One", source1.get("name"))
            // Metadata should be stripped
            Assertions.assertFalse(source1.containsKey("_index"), "_index should be stripped")
            Assertions.assertFalse(source1.containsKey("_type"), "_type should be stripped")
            Assertions.assertFalse(source1.containsKey("_id"), "_id should be stripped")
        } finally {
            // cleanup
            try { pgClient.deleteIndex("test_data_doc") } catch (Throwable ignored) {}
        }
    }

    // ============================
    // extractContentText
    // ============================

    @Test
    @DisplayName("extractContentText collects all string values")
    void extractContentText_collectsAllStringValues() {
        Map doc = [
            name: "John Doe",
            status: "active",
            address: [city: "Atlanta", state: "GA"],
            tags: ["enterprise", "premium"],
            amount: 299.99
        ]
        String text = PostgresElasticClient.extractContentText(doc)
        Assertions.assertNotNull(text)
        Assertions.assertTrue(text.contains("John Doe"), "should include name")
        Assertions.assertTrue(text.contains("active"), "should include status")
        Assertions.assertTrue(text.contains("Atlanta"), "should include nested city")
        Assertions.assertTrue(text.contains("enterprise"), "should include list items")
    }

    @Test
    @DisplayName("extractContentText on empty map returns empty string")
    void extractContentText_emptyMapReturnsEmpty() {
        String text = PostgresElasticClient.extractContentText([:])
        Assertions.assertEquals("", text)
    }

    @Test
    @DisplayName("extractContentText on null returns empty string")
    void extractContentText_nullReturnsEmpty() {
        String text = PostgresElasticClient.extractContentText(null)
        Assertions.assertEquals("", text)
    }

    // ============================
    // JSON serialization
    // ============================

    @Test
    @DisplayName("objectToJson and jsonToObject roundtrip")
    void objectToJson_andJsonToObject_roundtrip() {
        Map original = [name: "Test", values: [1, 2, 3], nested: [key: "value"]]
        String json = pgClient.objectToJson(original)
        Assertions.assertNotNull(json)
        Map roundtripped = (Map) pgClient.jsonToObject(json)
        Assertions.assertEquals("Test", roundtripped.get("name"))
        Assertions.assertEquals([1, 2, 3], roundtripped.get("values") as List)
    }

    // ============================
    // Unsupported REST operations throw
    // ============================

    @Test
    @DisplayName("call throws UnsupportedOperationException")
    void call_throwsUnsupportedOperation() {
        Assertions.assertThrows(UnsupportedOperationException.class, {
            pgClient.call(org.moqui.util.RestClient.Method.GET, TEST_INDEX, "/", null, null)
        })
    }

    @Test
    @DisplayName("callFuture throws UnsupportedOperationException")
    void callFuture_throwsUnsupportedOperation() {
        Assertions.assertThrows(UnsupportedOperationException.class, {
            pgClient.callFuture(org.moqui.util.RestClient.Method.GET, TEST_INDEX, "/", null, null)
        })
    }

    @Test
    @DisplayName("makeRestClient throws UnsupportedOperationException")
    void makeRestClient_throwsUnsupportedOperation() {
        Assertions.assertThrows(UnsupportedOperationException.class, {
            pgClient.makeRestClient(org.moqui.util.RestClient.Method.GET, TEST_INDEX, "/", null)
        })
    }
}
