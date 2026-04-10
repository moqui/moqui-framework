/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * Unit tests for ElasticQueryTranslator — no database connection required.
 * Tests that ES Query DSL is correctly translated to PostgreSQL SQL.
 */
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions
import org.moqui.impl.context.ElasticQueryTranslator
import org.moqui.impl.context.ElasticQueryTranslator.TranslatedQuery
import org.moqui.impl.context.ElasticQueryTranslator.QueryResult

class PostgresSearchTranslatorTests {

    // ============================================================
    // TranslatedQuery / translateSearchMap
    // ============================================================

    @Test
    @DisplayName("translateSearchMap - pagination defaults")
    void translateSearchMap_paginationDefaults() {
        TranslatedQuery tq = ElasticQueryTranslator.translateSearchMap([:])
        Assertions.assertEquals(0, tq.fromOffset, "default from should be 0")
        Assertions.assertEquals(20, tq.sizeLimit, "default size should be 20")
        Assertions.assertEquals("TRUE", tq.whereClause)
    }

    @Test
    @DisplayName("translateSearchMap - explicit pagination")
    void translateSearchMap_explicitPagination() {
        TranslatedQuery tq = ElasticQueryTranslator.translateSearchMap([from: 40, size: 10])
        Assertions.assertEquals(40, tq.fromOffset)
        Assertions.assertEquals(10, tq.sizeLimit)
    }

    @Test
    @DisplayName("translateSearchMap - query_string sets tsqueryExpr")
    void translateSearchMap_queryStringSetstsqueryExpr() {
        TranslatedQuery tq = ElasticQueryTranslator.translateSearchMap([
            query: [query_string: [query: "hello world", lenient: true]]
        ])
        Assertions.assertNotNull(tq.tsqueryExpr, "tsqueryExpr should be set for query_string")
        Assertions.assertTrue(tq.whereClause.contains("content_tsv"), "WHERE should use content_tsv")
    }

    @Test
    @DisplayName("translateSearchMap - sort spec")
    void translateSearchMap_sortSpec() {
        TranslatedQuery tq = ElasticQueryTranslator.translateSearchMap([
            sort: [[postDate: [order: "desc"]]]
        ])
        Assertions.assertNotNull(tq.orderBy, "orderBy should be set")
        Assertions.assertTrue(tq.orderBy.contains("DESC"), "orderBy should be DESC")
    }

    @Test
    @DisplayName("translateSearchMap - highlight fields extracted")
    void translateSearchMap_highlightFieldsExtracted() {
        TranslatedQuery tq = ElasticQueryTranslator.translateSearchMap([
            query: [match_all: [:]],
            highlight: [fields: [title: [:], description: [:]]]
        ])
        Assertions.assertTrue(tq.highlightFields.containsKey("title"))
        Assertions.assertTrue(tq.highlightFields.containsKey("description"))
    }

    // ============================================================
    // match_all
    // ============================================================

    @Test
    @DisplayName("match_all translates to TRUE")
    void matchAll_translatesTrue() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([match_all: [:]])
        Assertions.assertEquals("TRUE", qr.clause)
        Assertions.assertTrue(qr.params.isEmpty())
    }

    // ============================================================
    // query_string
    // ============================================================

    @Test
    @DisplayName("query_string translates to websearch_to_tsquery with tsqueryExpr")
    void queryString_translatesWebsearchToTsquery() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([query_string: [query: "moqui framework"]])
        Assertions.assertTrue(qr.clause.contains("content_tsv"), "clause should use content_tsv")
        Assertions.assertTrue(qr.clause.contains("@@"), "clause should have @@ operator")
        Assertions.assertNotNull(qr.tsqueryExpr, "should produce tsqueryExpr for scoring")
        Assertions.assertFalse(qr.params.isEmpty(), "should have at least one param for the query string")
    }

    @Test
    @DisplayName("query_string wildcard stripped for tsquery")
    void queryString_wildcardStripped() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([query_string: [query: "moqui*"]])
        // Wildcard queries are cleaned to simple word for websearch_to_tsquery
        Assertions.assertTrue(qr.clause.contains("content_tsv"))
    }

    // ============================================================
    // term
    // ============================================================

    @Test
    @DisplayName("term translates to field = value")
    void term_translatesEquality() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([term: [status: "ACTIVE"]])
        Assertions.assertTrue(qr.clause.contains("->>'status'"), "should use ->>'status'")
        Assertions.assertTrue(qr.clause.contains("="), "should use equality")
        Assertions.assertEquals(1, qr.params.size())
        Assertions.assertEquals("ACTIVE", qr.params[0])
    }

    @Test
    @DisplayName("term on _id translates to doc_id equality")
    void term_onIdFieldUsesDocId() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([term: ["_id": "TEST_001"]])
        Assertions.assertTrue(qr.clause.contains("doc_id"), "should use doc_id for _id field")
        Assertions.assertEquals("TEST_001", qr.params[0])
    }

    @Test
    @DisplayName("term on nested field path uses JSONB path access")
    void term_nestedFieldPath() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([term: ["address.city": "Atlanta"]])
        Assertions.assertTrue(qr.clause.contains("document->'address'->>'city'"), "should use nested path")
        Assertions.assertEquals("Atlanta", qr.params[0])
    }

    // ============================================================
    // terms
    // ============================================================

    @Test
    @DisplayName("terms translates to IN clause")
    void terms_translatesInClause() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([terms: [statusId: ["ACTIVE", "PENDING", "DRAFT"]]])
        Assertions.assertTrue(qr.clause.contains("IN"), "should use IN operator")
        Assertions.assertEquals(3, qr.params.size())
        Assertions.assertTrue(qr.params.containsAll(["ACTIVE", "PENDING", "DRAFT"]))
    }

    @Test
    @DisplayName("terms with empty list translates to FALSE")
    void terms_emptyListTranslatesFalse() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([terms: [statusId: []]])
        Assertions.assertEquals("FALSE", qr.clause)
    }

    // ============================================================
    // range
    // ============================================================

    @Test
    @DisplayName("range with gte and lte")
    void range_gteAndLte() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([range: [orderDate: [gte: "2024-01-01", lte: "2024-12-31"]]])
        Assertions.assertTrue(qr.clause.contains(">="), "should have >=")
        Assertions.assertTrue(qr.clause.contains("<="), "should have <=")
        Assertions.assertEquals(2, qr.params.size())
    }

    @Test
    @DisplayName("range with gt only")
    void range_gtOnly() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([range: [amount: [gt: "100"]]])
        Assertions.assertTrue(qr.clause.contains(">"), "should have >")
        Assertions.assertFalse(qr.clause.contains(">="), "should not have >= if only gt")
        Assertions.assertEquals(1, qr.params.size())
        Assertions.assertEquals("100", qr.params[0])
    }

    @Test
    @DisplayName("range on date field gets timestamptz cast")
    void range_dateFieldGetsTimestamptzCast() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([range: [orderDate: [gte: "2024-01-01"]]])
        Assertions.assertTrue(qr.clause.contains("::timestamptz"), "date fields should cast to timestamptz")
    }

    @Test
    @DisplayName("range on amount field gets numeric cast")
    void range_amountFieldGetsNumericCast() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([range: [grandTotal: [gte: "0"]]])
        Assertions.assertTrue(qr.clause.contains("::numeric"), "amount fields should cast to numeric")
    }

    // ============================================================
    // exists
    // ============================================================

    @Test
    @DisplayName("exists translates to JSONB document ? field")
    void exists_translatesJsonbHasKey() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([exists: [field: "email"]])
        Assertions.assertTrue(qr.clause.contains("document ?"), "should use JSONB ? operator")
        Assertions.assertTrue(qr.clause.contains("email"))
    }

    // ============================================================
    // bool
    // ============================================================

    @Test
    @DisplayName("bool must translates to AND")
    void boolMust_translatesAnd() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([bool: [must: [
            [term: [type: "ORDER"]],
            [term: [status: "PLACED"]]
        ]]])
        Assertions.assertTrue(qr.clause.contains("AND"), "must should generate AND")
        Assertions.assertEquals(2, qr.params.size())
    }

    @Test
    @DisplayName("bool should translates to OR")
    void boolShould_translatesOr() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([bool: [should: [
            [term: [status: "PLACED"]],
            [term: [status: "SHIPPED"]]
        ]]])
        Assertions.assertTrue(qr.clause.contains("OR"), "should should generate OR")
    }

    @Test
    @DisplayName("bool must_not translates to NOT")
    void boolMustNot_translatesNot() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([bool: [must_not: [
            [term: [status: "CANCELLED"]]
        ]]])
        Assertions.assertTrue(qr.clause.toUpperCase().contains("NOT"), "must_not should generate NOT")
    }

    @Test
    @DisplayName("bool filter translates same as must")
    void boolFilter_translatesSameAsMust() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([bool: [filter: [
            [term: [tenantId: "DEMO"]]
        ]]])
        Assertions.assertTrue(qr.clause.contains("->>'tenantId'"), "filter should translate term like must")
    }

    @Test
    @DisplayName("bool combined must and must_not")
    void boolCombinedMustAndMustNot() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([bool: [
            must: [[term: [type: "ORDER"]]],
            must_not: [[term: [status: "CANCELLED"]]]
        ]])
        Assertions.assertTrue(qr.clause.contains("AND"), "should have AND")
        Assertions.assertTrue(qr.clause.toUpperCase().contains("NOT"), "should have NOT")
        Assertions.assertEquals(2, qr.params.size())
    }

    // ============================================================
    // nested
    // ============================================================

    @Test
    @DisplayName("nested query translates to EXISTS subquery with jsonb_array_elements")
    void nested_translatesExistsSubquery() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([nested: [
            path: "orderItems",
            query: [term: ["orderItems.productId": "PROD_001"]]
        ]])
        Assertions.assertTrue(qr.clause.contains("EXISTS"), "nested should use EXISTS subquery")
        Assertions.assertTrue(qr.clause.contains("jsonb_array_elements"), "nested should use jsonb_array_elements")
        Assertions.assertTrue(qr.clause.contains("orderItems"), "should reference the nested path")
    }

    // ============================================================
    // ids
    // ============================================================

    @Test
    @DisplayName("ids query translates to doc_id IN list")
    void ids_translatesDocIdIn() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([ids: [values: ["ID1", "ID2", "ID3"]]])
        Assertions.assertTrue(qr.clause.contains("doc_id IN"), "ids should use doc_id IN")
        Assertions.assertEquals(3, qr.params.size())
    }

    @Test
    @DisplayName("ids with empty values translates to FALSE")
    void ids_emptyValuesTranslatesFalse() {
        QueryResult qr = ElasticQueryTranslator.translateQuery([ids: [values: []]])
        Assertions.assertEquals("FALSE", qr.clause)
    }

    // ============================================================
    // translateSort
    // ============================================================

    @Test
    @DisplayName("translateSort - map with order desc")
    void translateSort_mapWithOrderDesc() {
        String result = ElasticQueryTranslator.translateSort([[orderDate: [order: "desc"]]])
        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.contains("DESC"))
        Assertions.assertTrue(result.contains("orderDate"))
    }

    @Test
    @DisplayName("translateSort - score special field")
    void translateSort_scoreSpecialField() {
        String result = ElasticQueryTranslator.translateSort([[_score: [order: "desc"]]])
        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.contains("_score"), "should produce _score sort entry")
    }

    @Test
    @DisplayName("translateSort - keyword suffix stripped")
    void translateSort_keywordSuffixStripped() {
        String result = ElasticQueryTranslator.translateSort([["statusId.keyword": [order: "asc"]]])
        Assertions.assertFalse(result.contains(".keyword"), ".keyword suffix should be stripped")
        Assertions.assertTrue(result.contains("statusId"))
    }

    @Test
    @DisplayName("translateSort - string shorthand")
    void translateSort_stringShorthand() {
        String result = ElasticQueryTranslator.translateSort(["orderDate"])
        Assertions.assertNotNull(result)
        Assertions.assertTrue(result.contains("orderDate"))
    }

    @Test
    @DisplayName("translateSort - null returns null")
    void translateSort_nullReturnsNull() {
        String result = ElasticQueryTranslator.translateSort(null)
        Assertions.assertNull(result)
    }

    @Test
    @DisplayName("translateSort - empty list returns null")
    void translateSort_emptyListReturnsNull() {
        String result = ElasticQueryTranslator.translateSort([])
        Assertions.assertNull(result)
    }

    // ============================================================
    // Security: sanitizeFieldName — SQL injection prevention
    // ============================================================

    @Test
    @DisplayName("sanitizeFieldName rejects SQL injection via single quote")
    void sanitizeFieldName_rejectsSingleQuote() {
        Assertions.assertThrows(IllegalArgumentException) {
            ElasticQueryTranslator.sanitizeFieldName("status'; DROP TABLE users;--")
        }
    }

    @Test
    @DisplayName("sanitizeFieldName rejects SQL injection via semicolon")
    void sanitizeFieldName_rejectsSemicolon() {
        Assertions.assertThrows(IllegalArgumentException) {
            ElasticQueryTranslator.sanitizeFieldName("field;DELETE FROM moqui_search_index")
        }
    }

    @Test
    @DisplayName("sanitizeFieldName rejects parentheses")
    void sanitizeFieldName_rejectsParentheses() {
        Assertions.assertThrows(IllegalArgumentException) {
            ElasticQueryTranslator.sanitizeFieldName("field()OR 1=1")
        }
    }

    @Test
    @DisplayName("sanitizeFieldName rejects double dash comment")
    void sanitizeFieldName_rejectsDoubleDash() {
        Assertions.assertThrows(IllegalArgumentException) {
            ElasticQueryTranslator.sanitizeFieldName("field--comment")
        }
    }

    @Test
    @DisplayName("sanitizeFieldName rejects null field")
    void sanitizeFieldName_rejectsNull() {
        Assertions.assertThrows(IllegalArgumentException) {
            ElasticQueryTranslator.sanitizeFieldName(null)
        }
    }

    @Test
    @DisplayName("sanitizeFieldName rejects empty field")
    void sanitizeFieldName_rejectsEmpty() {
        Assertions.assertThrows(IllegalArgumentException) {
            ElasticQueryTranslator.sanitizeFieldName("")
        }
    }

    @Test
    @DisplayName("sanitizeFieldName rejects spaces")
    void sanitizeFieldName_rejectsSpaces() {
        Assertions.assertThrows(IllegalArgumentException) {
            ElasticQueryTranslator.sanitizeFieldName("field name")
        }
    }

    @Test
    @DisplayName("sanitizeFieldName rejects UNION SELECT injection")
    void sanitizeFieldName_rejectsUnionSelect() {
        Assertions.assertThrows(IllegalArgumentException) {
            ElasticQueryTranslator.sanitizeFieldName("x' UNION SELECT * FROM pg_shadow--")
        }
    }

    @Test
    @DisplayName("sanitizeFieldName accepts valid field names")
    void sanitizeFieldName_acceptsValidNames() {
        // These should NOT throw
        Assertions.assertEquals("statusId", ElasticQueryTranslator.sanitizeFieldName("statusId"))
        Assertions.assertEquals("order.items.quantity", ElasticQueryTranslator.sanitizeFieldName("order.items.quantity"))
        Assertions.assertEquals("@timestamp", ElasticQueryTranslator.sanitizeFieldName("@timestamp"))
        Assertions.assertEquals("field-name", ElasticQueryTranslator.sanitizeFieldName("field-name"))
        Assertions.assertEquals("_id", ElasticQueryTranslator.sanitizeFieldName("_id"))
    }

    @Test
    @DisplayName("sanitizeFieldName rejects oversized field name")
    void sanitizeFieldName_rejectsOversized() {
        String longField = "a" * 257
        Assertions.assertThrows(IllegalArgumentException) {
            ElasticQueryTranslator.sanitizeFieldName(longField)
        }
    }

    @Test
    @DisplayName("term query with SQL injection field name is rejected")
    void term_sqlInjectionFieldRejected() {
        Assertions.assertThrows(IllegalArgumentException) {
            ElasticQueryTranslator.translateQuery([term: ["status'; DROP TABLE x;--": "active"]])
        }
    }

    @Test
    @DisplayName("range query with SQL injection field name is rejected")
    void range_sqlInjectionFieldRejected() {
        Assertions.assertThrows(IllegalArgumentException) {
            ElasticQueryTranslator.translateQuery([range: ["x' OR '1'='1": [gte: "2024-01-01"]]])
        }
    }

    @Test
    @DisplayName("exists query with SQL injection field name is rejected")
    void exists_sqlInjectionFieldRejected() {
        Assertions.assertThrows(IllegalArgumentException) {
            ElasticQueryTranslator.translateQuery([exists: [field: "x'; DELETE FROM moqui_search_index;--"]])
        }
    }

    // ============================================================
    // Full searchMap round-trip
    // ============================================================

    @Test
    @DisplayName("full searchMap with bool query and sort")
    void fullSearchMap_boolQueryAndSort() {
        Map searchMap = [
            from: 0, size: 25,
            sort: [[orderDate: [order: "desc"]]],
            query: [bool: [
                must: [[term: [statusId: "OrderPlaced"]]],
                filter: [[range: [orderDate: [gte: "2024-01-01"]]]]
            ]],
            highlight: [fields: [productName: [:]]]
        ]
        TranslatedQuery tq = ElasticQueryTranslator.translateSearchMap(searchMap)
        Assertions.assertEquals(0, tq.fromOffset)
        Assertions.assertEquals(25, tq.sizeLimit)
        Assertions.assertNotNull(tq.orderBy)
        Assertions.assertTrue(tq.whereClause.contains("AND"))
        Assertions.assertTrue(tq.highlightFields.containsKey("productName"))
    }
}
