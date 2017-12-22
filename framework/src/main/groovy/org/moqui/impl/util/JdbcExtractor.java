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
package org.moqui.impl.util;

import org.moqui.BaseException;
import org.moqui.etl.SimpleEtl;
import org.moqui.impl.context.ExecutionContextImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.XAConnection;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

public class JdbcExtractor implements SimpleEtl.Extractor {
    protected final static Logger logger = LoggerFactory.getLogger(JdbcExtractor.class);

    SimpleEtl etl = null;
    private ExecutionContextImpl eci;
    private String recordType, selectSql;
    private Map<String, String> confMap;

    public JdbcExtractor(ExecutionContextImpl eci) { this.eci = eci; }

    public JdbcExtractor setSqlInfo(String recordType, String selectSql) {
        this.recordType = recordType;
        this.selectSql = selectSql;
        return this;
    }
    public JdbcExtractor setDbInfo(String dbType, String host, String port, String database, String user, String password) {
        confMap = new HashMap<>();
        confMap.put("entity_ds_db_conf", dbType);
        confMap.put("entity_ds_host", host);
        confMap.put("entity_ds_port", port);
        confMap.put("entity_ds_database", database);
        confMap.put("entity_ds_user", user);
        confMap.put("entity_ds_password", password);
        return this;
    }

    public String getRecordType() { return recordType; }

    @Override
    public void extract(SimpleEtl etl) throws Exception {
        this.etl = etl;

        XAConnection xacon = null;
        Connection con = null;
        Statement stmt = null;
        ResultSet rs = null;
        try {
            xacon = eci.getEntityFacade().getConfConnection(confMap);
            con = xacon.getConnection();
            stmt = con.createStatement();
            rs = stmt.executeQuery(selectSql);
            ResultSetMetaData rsmd = rs.getMetaData();
            int columnCount = rsmd.getColumnCount();
            String[] columnNames = new String[columnCount];
            for (int i = 1; i <= columnCount; i++) columnNames[i-1] = rsmd.getColumnName(i);

            while (rs.next()) {
                SimpleEtl.SimpleEntry curEntry = new SimpleEtl.SimpleEntry(recordType, new HashMap<>());
                for (int i = 1; i <= columnCount; i++) curEntry.values.put(columnNames[i-1], rs.getObject(i));

                try {
                    etl.processEntry(curEntry);
                } catch (SimpleEtl.StopException e) {
                    logger.warn("Got StopException", e);
                    break;
                }
            }
        } catch (Exception e) {
            throw new BaseException("Error in SQL query " + selectSql, e);
        } finally {
            if (rs != null) rs.close();
            if (stmt != null) stmt.close();
            if (con != null) con.close();
            if (xacon != null) xacon.close();
        }
    }
}
