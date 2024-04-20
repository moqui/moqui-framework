package dtq.rockycube.query

import groovy.json.JsonSlurper
import org.moqui.resource.ResourceReference
import org.slf4j.Logger
import java.sql.Connection
import java.sql.ResultSetMetaData

class SqlExecutor {
    protected static int maxLimit = 500

    static String calcPagedQuery(String query, Integer pageIndex, Integer pageSize)
    {
        String resQuery = query
        pageIndex = Math.max(0, pageIndex - 1)

        // limit by pageSize
        int limit = pageSize==0? maxLimit : pageSize
        resQuery = "${resQuery} limit ${limit}"

        // offset
        if (pageIndex > 0)
        {
            resQuery = "${resQuery} offset ${pageIndex * pageSize}"
        }

        return resQuery
    }

    static String calcTotalQuery(String query)
    {
        // first, calculate query
        String[] splitQuery = query.split(" from ")
        if (splitQuery.length != 2) return ""

        // we need to call `select count(*) ... ` to get the total count
        return "select count(*) as total from ${splitQuery[1]}"
    }

    static HashMap<String, Integer> paginationInfo(clLoadTotal, String query, Integer pageIndex, Integer pageSize)
    {
        HashMap<String, Integer> result = new HashMap<>()

        // fix index
        pageIndex = Math.max(0, pageIndex - 1)

        // calculate total count query
        String totalCountQuery = calcTotalQuery(query)
        if (!totalCountQuery) return result

        // load total using closure
        Integer viIdListCount = clLoadTotal(totalCountQuery)

        // when loading total returns -1, quit
        if (-1 == viIdListCount) return result

        // when total count is less than page size, no pagination
        if (pageSize > viIdListCount) return result

        Integer viIdListPageIndex = pageIndex
        Integer viIdListPageSize = pageSize
        Integer viIdListPageMaxIndex = ((BigDecimal) (viIdListCount - 1)).divide(viIdListPageSize as BigDecimal, 0, BigDecimal.ROUND_DOWN) as int
        Integer viIdListPageRangeLow = viIdListPageIndex * viIdListPageSize + 1
        Integer viIdListPageRangeHigh = (viIdListPageIndex * viIdListPageSize) + viIdListPageSize
        if (viIdListPageRangeHigh > viIdListCount) viIdListPageRangeHigh = viIdListCount

        //calculate 'to' item
        def maxTo = Math.min(viIdListPageIndex * viIdListPageSize + viIdListPageSize, viIdListCount)

        result.put('total', viIdListCount)
        result.put('per_page', viIdListPageSize)
        result.put('current_page', viIdListPageIndex + 1)
        result.put('last_page', viIdListPageMaxIndex + 1)
        //paginationData.put('next_page_url', searchUrl + '?pageIndex=2')
        //paginationData.put('prev_page_url', null)
        result.put('from', viIdListPageIndex * viIdListPageSize + 1)
        result.put('to', maxTo)

        return result
    }

    static HashMap<String, Object> executeQuery(
            Connection conn,
            Logger logger,
            String query,
            Integer pageIndex = 1,
            Integer pageSize = 20)
    {
        String queryMod = calcPagedQuery(query, pageIndex, pageSize)
        JsonSlurper slurper = new JsonSlurper()
        def rs = null
        def stmt = null
        def columns = new ArrayList<String>()
        def records = new ArrayList<ArrayList>()
        int limit = pageSize==0? maxLimit : pageSize

        if (queryMod.toUpperCase().startsWith("SELECT")) {
            stmt = conn.createStatement()
            rs = stmt.executeQuery(queryMod)
            if (rs != null) {
                def rsmd = rs.getMetaData()
                def columnCount = rsmd.getColumnCount()
                for (int i = 1; i <= columnCount; i++) columns.add(rsmd.getColumnName(i).toLowerCase())

                def limitReached = false
                while (rs.next()) {
                    if (limit > 0 && records.size() >= limit) {
                        limitReached = true
                        break
                    }
                    def record = new ArrayList<Object>(columnCount)
                    for (int i = 1; i <= columnCount; i++)
                    {
                        def obj = rs.getObject(i)
                        String tp = "unknown"
                        if (obj.hasProperty("type")) tp = obj["type"]
                        switch(tp)
                        {
                            case 'jsonb':
                                Object objVal = slurper.parseText(obj.value)
                                record.add(objVal)
                                break
                            default:
                                record.add(obj)
                        }
                    }
                    records.add(record)
                }
                rs.close()

                if (limitReached) {
                    logger.debug("Only showing first ${limit} rows")
                } else {
                    if (records)
                    {
                        logger.debug("Showing all ${records.size()}")
                    } else {
                        logger.debug("No records returned")
                    }
                }
            }
        } else {
            stmt = conn.createStatement()
            def rowsAffected = stmt.executeUpdate(query as String)
            logger.debug("Query altered ${rowsAffected} rows")
        }

        // close statement
        if (stmt != null) { try { stmt.close() } catch (Exception ignored) { /* Ignore */ } }

        // manipulate result into a map
        HashMap<String, Object> result = new HashMap<>()

        if (!records) return result

        def resultset = []
        for (r in records)
        {
            def rec = [:]
            columns.eachWithIndex{ String col, int i ->
                rec.put(col, r[i]?r[i]:'')
            }
            resultset.push(rec)
        }

        result.put('data', resultset)
        result.put('pagination', paginationInfo({totalQuery ->
            // default - nothing found
            Integer total = -1

            def clStmt = conn.createStatement()
            def clRs = clStmt.executeQuery(totalQuery)
            // if nothing, close and return default
            if (!clRs) return total

            while (clRs.next())
            {
                total = clRs.getObject(1).toString().toInteger()
            }

            // close all db related objects
            if (clRs != null) clRs.close()
            if (clStmt != null) clStmt.close()

            return total
        }, query, pageIndex, pageSize))

        // check pagination and remove if none provided
        if (result.pagination == [:]) result.remove('pagination')

        return result
    }

    /**
     * Same function as below, with the difference that it allows replacement of parameters inside
     * query.
     * @param conn
     * @param logger
     * @param queryFile
     * @param params
     * @return
     */
    static ArrayList execute(
            Connection conn,
            Logger logger,
            ResourceReference queryFile,
            HashMap params=[:]) {
        def isReader = queryFile.openStream().newReader("UTF-8")
        StringBuilder textBuilder = new StringBuilder();
        String line;
        while ((line = isReader.readLine()) != null) {
            textBuilder.append(line);
            textBuilder.append('\n');
        }

        String query = textBuilder.toString();
        return execute(conn, logger, (String) query)
    }

    /**
     * Execute SQL query and return JSON
     * @param conn
     * @param logger
     * @param query
     * @return
     */
    static ArrayList execute(
            Connection conn,
            Logger logger,
            String query)
    {
        def stmt = conn.createStatement()
        def queryResult = stmt.execute(query as String)
        logger.debug("Query result: ${queryResult}")
        if (!queryResult) return []
        def result = []
        def rs = stmt.resultSet
        ResultSetMetaData rsmd = rs.getMetaData()
        int numColumns = rsmd.getColumnCount()

        while (rs.next()) {
            def record = [:]
            for (int i = 1; i <= numColumns; i++) {
                String column_name = rsmd.getColumnName(i)
                record[column_name] = rs.getObject(column_name)
            }
            result.add(record)
        }
        rs.close()
        return result
    }
}
