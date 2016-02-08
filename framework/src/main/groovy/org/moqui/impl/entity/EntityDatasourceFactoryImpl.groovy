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
package org.moqui.impl.entity

import groovy.transform.CompileStatic
import org.h2.tools.Server
import org.moqui.context.TransactionInternal
import org.moqui.entity.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.naming.Context
import javax.naming.InitialContext
import javax.naming.NamingException
import javax.sql.DataSource

class EntityDatasourceFactoryImpl implements EntityDatasourceFactory {
    protected final static Logger logger = LoggerFactory.getLogger(EntityDatasourceFactoryImpl.class)

    protected EntityFacadeImpl efi
    protected Node datasourceNode
    protected String tenantId

    protected DataSource dataSource

    // for the embedded H2 server to allow remote access, used to stop server on destroy
    protected Server h2Server = null

    EntityDatasourceFactoryImpl() { }

    @Override
    EntityDatasourceFactory init(EntityFacade ef, Node datasourceNode, String tenantId) {
        // local fields
        this.efi = (EntityFacadeImpl) ef
        this.datasourceNode = datasourceNode
        this.tenantId = tenantId

        // init the DataSource

        if (datasourceNode."jndi-jdbc") {
            EntityFacadeImpl.DatasourceInfo dsi = new EntityFacadeImpl.DatasourceInfo(efi, datasourceNode)

            try {
                InitialContext ic;
                if (dsi.serverJndi) {
                    Hashtable<String, Object> h = new Hashtable<String, Object>()
                    h.put(Context.INITIAL_CONTEXT_FACTORY, dsi.serverJndi."@initial-context-factory")
                    h.put(Context.PROVIDER_URL, dsi.serverJndi."@context-provider-url")
                    if (dsi.serverJndi."@url-pkg-prefixes") h.put(Context.URL_PKG_PREFIXES, dsi.serverJndi."@url-pkg-prefixes")
                    if (dsi.serverJndi."@security-principal") h.put(Context.SECURITY_PRINCIPAL, dsi.serverJndi."@security-principal")
                    if (dsi.serverJndi."@security-credentials") h.put(Context.SECURITY_CREDENTIALS, dsi.serverJndi."@security-credentials")
                    ic = new InitialContext(h)
                } else {
                    ic = new InitialContext()
                }

                this.dataSource = (DataSource) ic.lookup(dsi.jndiName)
                if (this.dataSource == null) {
                    logger.error("Could not find DataSource with name [${datasourceNode."jndi-jdbc"[0]."@jndi-name"}] in JNDI server [${dsi.serverJndi ? dsi.serverJndi."@context-provider-url" : "default"}] for datasource with group-name [${datasourceNode."@group-name"}].")
                }
            } catch (NamingException ne) {
                logger.error("Error finding DataSource with name [${datasourceNode."jndi-jdbc"[0]."@jndi-name"}] in JNDI server [${dsi.serverJndi ? dsi.serverJndi."@context-provider-url" : "default"}] for datasource with group-name [${datasourceNode."@group-name"}].", ne)
            }
        } else if (datasourceNode."inline-jdbc") {
            // special thing for embedded derby, just set an system property; for derby.log, etc
            if (datasourceNode."@database-conf-name" == "derby") {
                System.setProperty("derby.system.home", System.getProperty("moqui.runtime") + "/db/derby")
                logger.info("Set property derby.system.home to [${System.getProperty("derby.system.home")}]")
            }
            if (datasourceNode."@database-conf-name" == "h2" && datasourceNode."@start-server-args") {
                String argsString = datasourceNode."@start-server-args"
                String[] args = argsString.split(" ")
                for (int i = 0; i < args.length; i++) {
                    if (args[i].contains('${moqui.runtime}')) args[i] = args[i].replace('${moqui.runtime}', System.getProperty("moqui.runtime"))
                }
                try {
                    h2Server = Server.createTcpServer(args).start();
                    logger.info("Started H2 remote server on port ${h2Server.getPort()} status [${h2Server.getStatus()}] from args ${args}")
                } catch (Throwable t) {
                    logger.warn("Error starting H2 server (may already be running): ${t.toString()}")
                }
            }

            TransactionInternal ti = efi.getEcfi().getTransactionFacade().getTransactionInternal()
            this.dataSource = ti.getDataSource(efi, datasourceNode, tenantId)
        } else {
            throw new EntityException("Found datasource with no jdbc sub-element (in datasource with group-name [${datasourceNode."@group-name"}])")
        }

        return this
    }

    @Override
    void destroy() {
        // NOTE: TransactionInternal DataSource will be destroyed when the TransactionFacade is destroyed
        if (h2Server != null && h2Server.isRunning(true)) h2Server.stop()
    }

    @Override
    @CompileStatic
    void checkAndAddTable(String entityName) { efi.getEntityDbMeta().checkTableStartup(efi.getEntityDefinition(entityName)) }

    @Override
    @CompileStatic
    EntityValue makeEntityValue(String entityName) {
        EntityDefinition entityDefinition = efi.getEntityDefinition(entityName)
        if (entityDefinition == null) throw new EntityException("Entity not found for name [${entityName}]")
        return new EntityValueImpl(entityDefinition, efi)
    }

    @Override
    @CompileStatic
    EntityFind makeEntityFind(String entityName) { return new EntityFindImpl(efi, entityName) }

    @Override
    @CompileStatic
    DataSource getDataSource() { return dataSource }
}
