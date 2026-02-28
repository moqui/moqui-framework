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

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Translates ElasticSearch/OpenSearch Query DSL (Map structures) into PostgreSQL SQL WHERE clauses,
 * ORDER BY expressions, and OFFSET/LIMIT pagination for use by PostgresElasticClient.
 *
 * Supports the query types used by Moqui's SearchServices.xml and entity condition makeSearchFilter() methods:
 *   - query_string (→ websearch_to_tsquery / plainto_tsquery on content_tsv)
 *   - bool (must / should / must_not / filter)
 *   - term, terms
 *   - range
 *   - match_all
 *   - exists
 *   - nested (→ jsonb_array_elements EXISTS subquery)
 */
class ElasticQueryTranslator {
    private final static Logger logger = LoggerFactory.getLogger(ElasticQueryTranslator.class)

    /** Regex pattern for valid field names — alphanumeric, underscores, dots, hyphens, and @ (for @timestamp) */
    private static final java.util.regex.Pattern SAFE_FIELD_PATTERN = java.util.regex.Pattern.compile('^[a-zA-Z0-9_@][a-zA-Z0-9_.\\-]*$')

    /**
     * Validate that a field name is safe for interpolation into SQL.
     * Rejects any field containing SQL metacharacters (quotes, semicolons, parentheses, etc.)
     * @throws IllegalArgumentException if the field name contains unsafe characters
     */
    static String sanitizeFieldName(String field) {
        if (field == null || field.isEmpty()) throw new IllegalArgumentException("Field name must not be empty")
        if (!SAFE_FIELD_PATTERN.matcher(field).matches()) {
            throw new IllegalArgumentException("Unsafe field name rejected: '${field}' — only alphanumeric, underscore, dot, hyphen, and @ allowed")
        }
        if (field.contains("--")) {
            throw new IllegalArgumentException("Unsafe field name rejected: '${field}' — double-hyphen (SQL comment) not allowed")
        }
        if (field.length() > 256) {
            throw new IllegalArgumentException("Field name too long (max 256 chars): '${field}'")
        }
        return field
    }

    /** Holds the result of translating a query DSL fragment or full search request */
    static class TranslatedQuery {
        /** SQL WHERE clause fragment (without the "WHERE" keyword), or "TRUE" if no filter */
        String whereClause = "TRUE"
        /** JDBC bind parameters in order corresponding to ? placeholders in whereClause */
        List<Object> params = []
        /** SQL ORDER BY expression (without the "ORDER BY" keyword), or null */
        String orderBy = null
        /** The tsquery expression (as SQL expression string) for use in ts_rank_cd() and ts_headline() */
        String tsqueryExpr = null
        /** OFFSET value for pagination */
        int fromOffset = 0
        /** LIMIT value for pagination */
        int sizeLimit = 20
        /** Track total hits (adds no SQL change but reflects ES track_total_hits flag) */
        boolean trackTotal = true
        /** Fields to highlight, keyed by field name */
        Map<String, Map> highlightFields = [:]
    }

    /**
     * Translate a full ES searchMap (the body sent to /_search) into a TranslatedQuery.
     * @param searchMap Map as built by SearchServices.search#DataDocuments
     */
    static TranslatedQuery translateSearchMap(Map searchMap) {
        TranslatedQuery tq = new TranslatedQuery()

        // Pagination
        Object fromVal = searchMap.get("from")
        if (fromVal != null) tq.fromOffset = ((Number) fromVal).intValue()
        Object sizeVal = searchMap.get("size")
        if (sizeVal != null) tq.sizeLimit = ((Number) sizeVal).intValue()

        // Sort
        Object sortVal = searchMap.get("sort")
        if (sortVal instanceof List) {
            tq.orderBy = translateSort((List) sortVal)
        }

        // Highlight fields
        Object highlightVal = searchMap.get("highlight")
        if (highlightVal instanceof Map) {
            Object fieldsVal = ((Map) highlightVal).get("fields")
            if (fieldsVal instanceof Map) tq.highlightFields = (Map<String, Map>) fieldsVal
        }

        // track_total_hits
        Object tthVal = searchMap.get("track_total_hits")
        if (tthVal != null) tq.trackTotal = Boolean.TRUE == tthVal || "true".equals(tthVal.toString())

        // Query
        Object queryVal = searchMap.get("query")
        if (queryVal instanceof Map) {
            QueryResult qr = translateQuery((Map) queryVal)
            tq.whereClause = qr.clause ?: "TRUE"
            tq.params = qr.params
            tq.tsqueryExpr = qr.tsqueryExpr
        }

        return tq
    }

    /** Internal result holder for a single query fragment */
    static class QueryResult {
        String clause = "TRUE"
        List<Object> params = []
        /** If this query has a full-text component, the SQL tsquery expression for scoring/highlighting */
        String tsqueryExpr = null
    }

    static QueryResult translateQuery(Map queryMap) {
        if (queryMap == null || queryMap.isEmpty()) return new QueryResult()

        String queryType = (String) queryMap.keySet().iterator().next()
        Object queryVal = queryMap.get(queryType)

        switch (queryType) {
            case "match_all": return translateMatchAll()
            case "match_none":
                QueryResult qr = new QueryResult(); qr.clause = "FALSE"; return qr
            case "query_string": return translateQueryString((Map) queryVal)
            case "multi_match": return translateMultiMatch((Map) queryVal)
            case "bool": return translateBool((Map) queryVal)
            case "term": return translateTerm((Map) queryVal, false)
            case "terms": return translateTerms((Map) queryVal)
            case "range": return translateRange((Map) queryVal)
            case "exists": return translateExists((Map) queryVal)
            case "nested": return translateNested((Map) queryVal)
            case "ids": return translateIds((Map) queryVal)
            default:
                logger.warn("ElasticQueryTranslator: unsupported query type '${queryType}', using TRUE")
                return new QueryResult()
        }
    }

    private static QueryResult translateMatchAll() {
        QueryResult qr = new QueryResult()
        qr.clause = "TRUE"
        return qr
    }

    private static QueryResult translateQueryString(Map qsMap) {
        QueryResult qr = new QueryResult()
        if (qsMap == null) return qr

        String query = (String) qsMap.get("query")
        if (!query || query.trim().isEmpty()) return qr

        // Clean up the query string:
        // 1. Lucene field:value syntax → handle field-specific searches
        // 2. Strip unsupported operators, translate AND/OR/NOT
        // 3. Use websearch_to_tsquery which supports quoted phrases, AND, OR, -, +
        String cleanedQuery = cleanLuceneQuery(query)

        if (!cleanedQuery || cleanedQuery.trim().isEmpty()) return qr

        // Use websearch_to_tsquery for natural language queries
        // It handles: "exact phrase", AND/OR/NOT, +required, -exclude
        qr.tsqueryExpr = "websearch_to_tsquery('english', ?)"
        qr.params = [cleanedQuery]
        qr.clause = "content_tsv @@ websearch_to_tsquery('english', ?)"
        // Note: we add the param twice (once for where clause, once for tsquery expression used in scoring)
        // Callers who need separate tsquery expression access the tsqueryExpr field with their own param binding
        return qr
    }

    private static QueryResult translateMultiMatch(Map mmMap) {
        // Treat like query_string on all fields
        String query = (String) mmMap.get("query")
        if (!query) return new QueryResult()
        return translateQueryString([query: query])
    }

    private static QueryResult translateBool(Map boolMap) {
        QueryResult qr = new QueryResult()
        if (boolMap == null) return qr

        List<String> clauses = []
        List<Object> params = []
        String combinedTsquery = null

        // must (AND)
        Object mustVal = boolMap.get("must")
        if (mustVal instanceof List) {
            List<String> mustClauses = []
            for (Object item in (List) mustVal) {
                if (item instanceof Map) {
                    QueryResult itemQr = translateQuery((Map) item)
                    mustClauses.add(itemQr.clause)
                    params.addAll(itemQr.params)
                    if (itemQr.tsqueryExpr) combinedTsquery = combinedTsquery ? "(${combinedTsquery}) && (${itemQr.tsqueryExpr})" : itemQr.tsqueryExpr
                }
            }
            if (mustClauses) clauses.add("(" + mustClauses.join(" AND ") + ")")
        } else if (mustVal instanceof Map) {
            QueryResult itemQr = translateQuery((Map) mustVal)
            clauses.add(itemQr.clause)
            params.addAll(itemQr.params)
            if (itemQr.tsqueryExpr) combinedTsquery = itemQr.tsqueryExpr
        }

        // filter (same as must for our purposes)
        Object filterVal = boolMap.get("filter")
        if (filterVal instanceof List) {
            List<String> filterClauses = []
            for (Object item in (List) filterVal) {
                if (item instanceof Map) {
                    QueryResult itemQr = translateQuery((Map) item)
                    filterClauses.add(itemQr.clause)
                    params.addAll(itemQr.params)
                }
            }
            if (filterClauses) clauses.add("(" + filterClauses.join(" AND ") + ")")
        } else if (filterVal instanceof Map) {
            QueryResult itemQr = translateQuery((Map) filterVal)
            clauses.add(itemQr.clause)
            params.addAll(itemQr.params)
        }

        // should (OR)
        Object shouldVal = boolMap.get("should")
        if (shouldVal instanceof List) {
            List<String> shouldClauses = []
            for (Object item in (List) shouldVal) {
                if (item instanceof Map) {
                    QueryResult itemQr = translateQuery((Map) item)
                    shouldClauses.add(itemQr.clause)
                    params.addAll(itemQr.params)
                    if (itemQr.tsqueryExpr) combinedTsquery = combinedTsquery ? "(${combinedTsquery}) || (${itemQr.tsqueryExpr})" : itemQr.tsqueryExpr
                }
            }
            if (shouldClauses) {
                int minShouldMatch = 1
                Object msmVal = boolMap.get("minimum_should_match")
                if (msmVal != null) minShouldMatch = ((Number) msmVal).intValue()
                if (minShouldMatch == 1) {
                    clauses.add("(" + shouldClauses.join(" OR ") + ")")
                } else {
                    // For minimum_should_match > 1, use a CASE/SUM trick for simplicity just add as OR
                    clauses.add("(" + shouldClauses.join(" OR ") + ")")
                }
            }
        } else if (shouldVal instanceof Map) {
            QueryResult itemQr = translateQuery((Map) shouldVal)
            clauses.add(itemQr.clause)
            params.addAll(itemQr.params)
            if (itemQr.tsqueryExpr) combinedTsquery = itemQr.tsqueryExpr
        }

        // must_not (NOT)
        Object mustNotVal = boolMap.get("must_not")
        if (mustNotVal instanceof List) {
            List<String> mustNotClauses = []
            for (Object item in (List) mustNotVal) {
                if (item instanceof Map) {
                    QueryResult itemQr = translateQuery((Map) item)
                    mustNotClauses.add(itemQr.clause)
                    params.addAll(itemQr.params)
                }
            }
            if (mustNotClauses) clauses.add("NOT (" + mustNotClauses.join(" OR ") + ")")
        } else if (mustNotVal instanceof Map) {
            QueryResult itemQr = translateQuery((Map) mustNotVal)
            clauses.add("NOT (${itemQr.clause})")
            params.addAll(itemQr.params)
        }

        qr.clause = clauses ? "(" + clauses.join(" AND ") + ")" : "TRUE"
        qr.params = params
        qr.tsqueryExpr = combinedTsquery
        return qr
    }

    private static QueryResult translateTerm(Map termMap, boolean ignoreCase) {
        QueryResult qr = new QueryResult()
        if (termMap == null || termMap.isEmpty()) return qr

        String field = (String) termMap.keySet().iterator().next()
        Object valueHolder = termMap.get(field)
        Object value
        if (valueHolder instanceof Map) {
            value = ((Map) valueHolder).get("value")
        } else {
            value = valueHolder
        }
        if (value == null) { qr.clause = "TRUE"; return qr }

        // _id is a special ES field that maps to the doc_id column
        if (field == "_id") {
            qr.clause = "doc_id = ?"
            qr.params = [value.toString()]
            return qr
        }

        String jsonPath = fieldToJsonPath("document", field)
        if (ignoreCase && value instanceof String) {
            qr.clause = "LOWER(${jsonPath}) = LOWER(?)"
        } else {
            qr.clause = "${jsonPath} = ?"
        }
        qr.params = [value.toString()]
        return qr
    }

    private static QueryResult translateTerms(Map termsMap) {
        QueryResult qr = new QueryResult()
        if (termsMap == null || termsMap.isEmpty()) return qr

        // Remove boost key if present
        Map filteredMap = termsMap.findAll { k, v -> k != "boost" }
        if (filteredMap.isEmpty()) return qr

        String field = (String) filteredMap.keySet().iterator().next()
        Object valuesObj = filteredMap.get(field)
        if (!(valuesObj instanceof List)) { qr.clause = "TRUE"; return qr }
        List values = (List) valuesObj
        if (values.isEmpty()) { qr.clause = "FALSE"; return qr }

        String jsonPath = fieldToJsonPath("document", field)
        List<String> placeholders = values.collect { "?" }
        qr.clause = "${jsonPath} IN (${placeholders.join(', ')})"
        qr.params = values.collect { it?.toString() }
        return qr
    }

    private static QueryResult translateRange(Map rangeMap) {
        QueryResult qr = new QueryResult()
        if (rangeMap == null || rangeMap.isEmpty()) return qr

        String field = (String) rangeMap.keySet().iterator().next()
        Object rangeSpec = rangeMap.get(field)
        if (!(rangeSpec instanceof Map)) return qr

        Map rangeSpecMap = (Map) rangeSpec
        String jsonPath = fieldToJsonPath("document", field)
        List<String> conditions = []
        List<Object> params = []

        // Determine cast type based on common field name patterns
        String castType = guessCastType(field)

        Object gte = rangeSpecMap.get("gte")
        Object gt = rangeSpecMap.get("gt")
        Object lte = rangeSpecMap.get("lte")
        Object lt = rangeSpecMap.get("lt")

        if (gte != null) { conditions.add("(${jsonPath})${castType} >= ?"); params.add(gte.toString()) }
        if (gt != null) { conditions.add("(${jsonPath})${castType} > ?"); params.add(gt.toString()) }
        if (lte != null) { conditions.add("(${jsonPath})${castType} <= ?"); params.add(lte.toString()) }
        if (lt != null) { conditions.add("(${jsonPath})${castType} < ?"); params.add(lt.toString()) }

        if (conditions.isEmpty()) { qr.clause = "TRUE"; return qr }
        qr.clause = conditions.join(" AND ")
        qr.params = params
        return qr
    }

    private static QueryResult translateExists(Map existsMap) {
        QueryResult qr = new QueryResult()
        if (existsMap == null) return qr
        String field = (String) existsMap.get("field")
        if (!field) return qr

        // Validate field name to prevent SQL injection
        sanitizeFieldName(field)
        // For nested paths, check the nested path exists
        if (field.contains(".")) {
            List<String> parts = field.split("\\.") as List
            String topLevel = parts[0]
            qr.clause = "document ? '${topLevel}'"
        } else {
            qr.clause = "document ? '${field}'"
        }
        return qr
    }

    private static QueryResult translateNested(Map nestedMap) {
        QueryResult qr = new QueryResult()
        if (nestedMap == null) return qr

        String path = (String) nestedMap.get("path")
        Map innerQuery = (Map) nestedMap.get("query")
        if (!path || !innerQuery) return qr

        // Validate path to prevent SQL injection
        sanitizeFieldName(path)
        // Translate the inner query against jsonb_array_elements alias "elem"
        QueryResult innerQr = translateNestedQuery(innerQuery, path)
        qr.clause = "EXISTS (SELECT 1 FROM jsonb_array_elements(document->'${path}') AS elem WHERE ${innerQr.clause})"
        qr.params = innerQr.params
        return qr
    }

    /** Translate a query in the context of a nested jsonb_array_elements expression (uses "elem" alias) */
    private static QueryResult translateNestedQuery(Map queryMap, String parentPath) {
        QueryResult qr = new QueryResult()
        if (queryMap == null || queryMap.isEmpty()) return qr

        String queryType = (String) queryMap.keySet().iterator().next()
        Object queryVal = queryMap.get(queryType)

        if (queryType == "bool") {
            return translateNestedBool((Map) queryVal, parentPath)
        } else if (queryType == "term") {
            return translateNestedTerm((Map) queryVal, parentPath)
        } else if (queryType == "terms") {
            return translateNestedTerms((Map) queryVal, parentPath)
        } else if (queryType == "range") {
            return translateNestedRange((Map) queryVal, parentPath)
        } else if (queryType == "match_all") {
            return new QueryResult()
        } else {
            logger.warn("ElasticQueryTranslator.translateNestedQuery: unsupported nested query type '${queryType}', using TRUE")
            return new QueryResult()
        }
    }

    private static QueryResult translateNestedBool(Map boolMap, String parentPath) {
        QueryResult qr = new QueryResult()
        if (boolMap == null) return qr
        List<String> clauses = []
        List<Object> params = []

        for (String key in ["must", "filter", "should", "must_not"]) {
            Object val = boolMap.get(key)
            List<Map> items
            if (val instanceof List) items = (List<Map>) val
            else if (val instanceof Map) items = [(Map) val]
            else continue

            List<String> itemClauses = []
            for (Map item in items) {
                QueryResult ir = translateNestedQuery(item, parentPath)
                itemClauses.add(ir.clause)
                params.addAll(ir.params)
            }
            if (itemClauses) {
                String joined = "(" + itemClauses.join(" AND ") + ")"
                if (key == "must_not") joined = "NOT " + joined
                else if (key == "should") joined = "(" + itemClauses.join(" OR ") + ")"
                clauses.add(joined)
            }
        }
        qr.clause = clauses ? clauses.join(" AND ") : "TRUE"
        qr.params = params
        return qr
    }

    private static QueryResult translateNestedTerm(Map termMap, String parentPath) {
        QueryResult qr = new QueryResult()
        if (termMap == null || termMap.isEmpty()) return qr
        String field = (String) termMap.keySet().iterator().next()
        Object valueHolder = termMap.get(field)
        Object value = valueHolder instanceof Map ? ((Map) valueHolder).get("value") : valueHolder
        if (value == null) { qr.clause = "TRUE"; return qr }

        // For nested terms "parentPath.field", strip the parent path prefix
        String localField = field.startsWith(parentPath + ".") ? field.substring(parentPath.length() + 1) : field
        sanitizeFieldName(localField)
        qr.clause = "elem->>'${localField}' = ?"
        qr.params = [value.toString()]
        return qr
    }

    private static QueryResult translateNestedTerms(Map termsMap, String parentPath) {
        QueryResult qr = new QueryResult()
        Map filteredMap = termsMap.findAll { k, v -> k != "boost" }
        if (filteredMap.isEmpty()) return qr
        String field = (String) filteredMap.keySet().iterator().next()
        Object valuesObj = filteredMap.get(field)
        if (!(valuesObj instanceof List)) { qr.clause = "TRUE"; return qr }
        List values = (List) valuesObj
        if (values.isEmpty()) { qr.clause = "FALSE"; return qr }
        String localField = field.startsWith(parentPath + ".") ? field.substring(parentPath.length() + 1) : field
        sanitizeFieldName(localField)
        qr.clause = "elem->>'${localField}' IN (${values.collect { '?' }.join(', ')})"
        qr.params = values.collect { it?.toString() }
        return qr
    }

    private static QueryResult translateNestedRange(Map rangeMap, String parentPath) {
        QueryResult qr = new QueryResult()
        if (rangeMap == null || rangeMap.isEmpty()) return qr
        String field = (String) rangeMap.keySet().iterator().next()
        Object rangeSpec = rangeMap.get(field)
        if (!(rangeSpec instanceof Map)) return qr
        Map rangeSpecMap = (Map) rangeSpec
        String localField = field.startsWith(parentPath + ".") ? field.substring(parentPath.length() + 1) : field
        sanitizeFieldName(localField)
        String castType = guessCastType(localField)
        List<String> conditions = []
        List<Object> params = []
        Object gte = rangeSpecMap.get("gte"); if (gte != null) { conditions.add("(elem->>'${localField}')${castType} >= ?"); params.add(gte.toString()) }
        Object gt = rangeSpecMap.get("gt"); if (gt != null) { conditions.add("(elem->>'${localField}')${castType} > ?"); params.add(gt.toString()) }
        Object lte = rangeSpecMap.get("lte"); if (lte != null) { conditions.add("(elem->>'${localField}')${castType} <= ?"); params.add(lte.toString()) }
        Object lt = rangeSpecMap.get("lt"); if (lt != null) { conditions.add("(elem->>'${localField}')${castType} < ?"); params.add(lt.toString()) }
        qr.clause = conditions ? conditions.join(" AND ") : "TRUE"
        qr.params = params
        return qr
    }

    private static QueryResult translateIds(Map idsMap) {
        QueryResult qr = new QueryResult()
        Object vals = idsMap?.get("values")
        if (!(vals instanceof List) || ((List) vals).isEmpty()) { qr.clause = "FALSE"; return qr }
        List ids = (List) vals
        qr.clause = "doc_id IN (${ids.collect { '?' }.join(', ')})"
        qr.params = ids.collect { it?.toString() }
        return qr
    }

    /** Translate an ES sort spec (list of sort entries) to a SQL ORDER BY expression */
    static String translateSort(List sortList) {
        if (!sortList) return null
        List<String> parts = []
        for (Object sortEntry in sortList) {
            if (sortEntry instanceof Map) {
                Map sortMap = (Map) sortEntry
                for (Map.Entry entry in sortMap.entrySet()) {
                    String field = ((String) entry.key).replace(".keyword", "")
                    String dir = "ASC"
                    if (entry.value instanceof Map) {
                        String orderVal = (String) ((Map) entry.value).get("order")
                        if ("desc".equalsIgnoreCase(orderVal)) dir = "DESC"
                    } else if ("desc".equalsIgnoreCase(entry.value?.toString())) {
                        dir = "DESC"
                    }

                    if ("_score".equals(field)) {
                        parts.add("_score ${dir}")
                    } else {
                        String castType = guessCastType(field)
                        if (castType) {
                            parts.add("(${fieldToJsonPath("document", field)})${castType} ${dir}")
                        } else {
                            parts.add("${fieldToJsonPath("document", field)} ${dir}")
                        }
                    }
                }
            } else if (sortEntry instanceof String) {
                String field = ((String) sortEntry).replace(".keyword", "")
                if ("_score".equals(field)) {
                    parts.add("_score DESC")
                } else {
                    parts.add("${fieldToJsonPath("document", field)} ASC")
                }
            }
        }
        return parts ? parts.join(", ") : null
    }

    /**
     * Convert an ES field path to a PostgreSQL JSONB access expression.
     * E.g. "product.name" → "document->'product'->>'name'"
     *      "productId" → "document->>'productId'"
     */
    static String fieldToJsonPath(String docAlias, String field) {
        // Strip .keyword suffix (used in ES for exact/sortable text fields)
        if (field.endsWith(".keyword")) field = field.substring(0, field.length() - ".keyword".length())
        // Validate field name to prevent SQL injection
        sanitizeFieldName(field)
        List<String> parts = field.split("\\.") as List
        if (parts.size() == 1) return "${docAlias}->>'${field}'"
        // For nested paths: docAlias->'part1'->'part2'->>'lastPart'
        StringBuilder sb = new StringBuilder(docAlias)
        for (int i = 0; i < parts.size() - 1; i++) {
            sb.append("->'${parts[i]}'")
        }
        sb.append("->>'${parts[parts.size() - 1]}'")
        return sb.toString()
    }

    /**
     * Guess the appropriate PostgreSQL cast type for a field name to use in range/sort comparisons.
     * Returns empty string if no cast is needed (use text comparison).
     */
    private static String guessCastType(String field) {
        String lf = field.toLowerCase()
        if (lf.contains("date") || lf.contains("stamp") || lf.contains("time") || lf == "@timestamp") {
            return "::timestamptz"
        }
        if (lf.contains("amount") || lf.contains("price") || lf.contains("cost") || lf.contains("total") ||
                lf.contains("quantity") || lf.contains("qty") || lf.contains("score") || lf.contains("count") ||
                lf.contains("number") || lf.contains("num") || lf.contains("id") && lf.endsWith("num")) {
            return "::numeric"
        }
        return ""
    }

    /**
     * Clean up a Lucene query string to be safe for use with websearch_to_tsquery.
     * websearch_to_tsquery supports: "quoted phrases", AND, OR, -, +
     * This removes/translates Lucene-specific syntax that websearch_to_tsquery doesn't support:
     *   - field:value (extract field-specific as general text)
     *   - field:[range TO range] (drop or convert)
     *   - wildcard * ? (drop trailing wildcards, keep term)
     *   - boost ^ (strip)
     *   - fuzzy ~ (strip)
     *   - parentheses → use natural AND grouping
     */
    static String cleanLuceneQuery(String query) {
        if (!query) return query
        String q = query.trim()

        // Remove Lucene field:value prefixes (keep just the value part)
        q = q.replaceAll(/\w+:("(?:[^"\\]|\\.)*"|\S+)/, '$1')

        // Remove range queries [X TO Y]
        q = q.replaceAll(/\[[^\]]*\]/, '')
        q = q.replaceAll(/\{[^}]*\}/, '')

        // Remove boost operators (^number)
        q = q.replaceAll(/\^[\d.]+/, '')

        // Remove fuzzy operators (~number or just ~)
        q = q.replaceAll(/~[\d.]*/, '')

        // Normalize AND/OR/NOT to lowercase for websearch_to_tsquery
        // (websearch_to_tsquery actually uses lowercase and/or/not)
        q = q.replaceAll(/\bAND\b/, 'AND')
        q = q.replaceAll(/\bOR\b/, 'OR')
        q = q.replaceAll(/\bNOT\b/, '-')

        // Remove wildcards at end of terms (partial matching not directly supported; term will still match as prefix via FTS)
        q = q.replaceAll(/\*/, '')
        q = q.replaceAll(/\?/, '')

        // Remove empty parentheses, normalize spaces
        q = q.replaceAll(/\(\s*\)/, '')
        q = q.replaceAll(/\s+/, ' ').trim()

        return q ?: ''
    }

    /**
     * Build a ts_headline SQL expression for a given field with the given tsquery expression.
     * @param fieldJsonPath The SQL expression to extract the text field (e.g. "document->>'productName'")
     * @param tsqueryParam The SQL tsquery expression (e.g. "websearch_to_tsquery('english', ?)")
     */
    static String buildHighlightExpr(String fieldJsonPath, String tsqueryExpr) {
        return "ts_headline('english', coalesce(${fieldJsonPath}, ''), ${tsqueryExpr}, 'StartSel=<em>,StopSel=</em>,MaxWords=35,MinWords=15,ShortWord=3,HighlightAll=false,MaxFragments=3,FragmentDelimiter= ... ')"
    }
}
