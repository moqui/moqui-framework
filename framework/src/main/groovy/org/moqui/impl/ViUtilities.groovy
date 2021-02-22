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
package org.moqui.impl

import groovy.json.JsonSlurper
import java.sql.Connection
import org.slf4j.Logger

/** These are utilities that should exist elsewhere, but I can't find a good simple library for them, and they are
 * stupid but necessary for certain things.
 */
class ViUtilities {
    static final String removeNonumericCharacters(String inputValue) {
        return inputValue.replaceAll("[^\\d]", "")
    }

    static final String removeCommas(String inputValue) {
        return inputValue.replaceAll(",", ".")
    }

    static String calcPagedQuery(String query, Integer pageIndex, Integer pageSize, Integer limit)
    {
        String resQuery = query
        pageIndex = Math.max(0, pageIndex - 1)

        // limit
        if (limit > 0 && pageSize == 0)
        {
            resQuery = "${resQuery} limit ${limit}"
        } else if (pageSize > 0)
        {
            resQuery = "${resQuery} limit ${pageSize}"
        }

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
            Integer pageSize = 20,
            Integer limit = 500)
    {
        String queryMod = calcPagedQuery(query, pageIndex, pageSize, limit)
        JsonSlurper slurper = new JsonSlurper()
        def rs = null
        def stmt = null
        def columns = new ArrayList<String>()
        def records = new ArrayList<ArrayList>()

        if (queryMod.toUpperCase().startsWith("SELECT")) {
            stmt = conn.createStatement()
            rs = stmt.executeQuery(queryMod)
            if (rs != null) {
                def rsmd = rs.getMetaData()
                def columnCount = rsmd.getColumnCount()
                for (int i = 1; i <= columnCount; i++) columns.add(rsmd.getColumnName(i))

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
            def rowsAffected = stmt.executeUpdate(queryMod as String)
            logger.debug("Query altered ${rowsAffected} rows")
        }

        // close statement
        if (stmt != null) { try { stmt.close() } catch (Exception e) { /* Ignore */ } }

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
    };
}