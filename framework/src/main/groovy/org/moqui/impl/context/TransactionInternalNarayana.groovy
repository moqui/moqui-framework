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

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.TransactionInternal
import org.moqui.entity.EntityFacade
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.sql.DataSource
import javax.sql.XADataSource
import jakarta.transaction.TransactionManager
import jakarta.transaction.UserTransaction
import java.sql.Connection

// Import Narayana standalone (arjunacore) implementations
import com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionManagerImple
import com.arjuna.ats.internal.jta.transaction.arjunacore.UserTransactionImple

@CompileStatic
class TransactionInternalNarayana implements TransactionInternal {
    protected final static Logger logger = LoggerFactory.getLogger(TransactionInternalNarayana.class)

    protected ExecutionContextFactoryImpl ecfi

    protected TransactionManager tm
    protected UserTransaction ut

    protected List<HikariDataSource> dataSourceList = []

    @Override
    TransactionInternal init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf

        // Configure Narayana transaction log directory
        String runtimePath = ecfi.runtimePath
        String txLogDir = runtimePath + "/txlog"

        // Create txlog directory if it doesn't exist
        File txLogDirFile = new File(txLogDir)
        if (!txLogDirFile.exists()) {
            txLogDirFile.mkdirs()
        }

        // Configure Narayana properties via system properties BEFORE initializing TM
        // These must be set before any Narayana classes are loaded
        System.setProperty("ObjectStoreEnvironmentBean.objectStoreDir", txLogDir)
        System.setProperty("com.arjuna.ats.arjuna.common.ObjectStoreEnvironmentBean.objectStoreDir", txLogDir)
        System.setProperty("com.arjuna.ats.arjuna.coordinator.defaultTimeout", "120")
        // Disable recovery - not needed for simple standalone usage
        System.setProperty("com.arjuna.ats.arjuna.recovery.recoveryBackoffPeriod", "0")

        // Initialize Transaction Manager and UserTransaction using direct instantiation
        // (standalone arjunacore implementations, no JNDI/JBoss server required)
        tm = new TransactionManagerImple()
        ut = new UserTransactionImple()

        logger.info("Initialized Narayana Transaction Manager with log directory: ${txLogDir}")

        return this
    }

    @Override
    TransactionManager getTransactionManager() { return tm }

    @Override
    UserTransaction getUserTransaction() { return ut }

    @Override
    DataSource getDataSource(EntityFacade ef, MNode datasourceNode) {
        EntityFacadeImpl efi = (EntityFacadeImpl) ef

        EntityFacadeImpl.DatasourceInfo dsi = new EntityFacadeImpl.DatasourceInfo(efi, datasourceNode)

        HikariDataSource hikariDs = createHikariDataSource(dsi)
        dataSourceList.add(hikariDs)

        logger.info("Initializing HikariCP DataSource ${dsi.uniqueName} (${dsi.database.attribute('name')}) with properties: ${dsi.dsDetails}")

        return hikariDs
    }

    protected HikariDataSource createHikariDataSource(EntityFacadeImpl.DatasourceInfo dsi) {
        HikariConfig config = new HikariConfig()

        // Set pool name
        config.setPoolName(dsi.uniqueName ?: "MoquiPool")

        // Always use JDBC URL approach for HikariCP (simpler and more compatible)
        // The XA properties contain the same connection info as jdbcUri
        String jdbcUrl = dsi.jdbcUri
        String username = dsi.jdbcUsername
        String password = dsi.jdbcPassword
        String driverClass = dsi.jdbcDriver

        // If using XA config, extract connection details from XA properties
        if (dsi.xaDsClass && !jdbcUrl) {
            // Build JDBC URL from XA properties
            String serverName = dsi.xaProps.get("serverName")?.toString() ?: "localhost"
            String portNumber = dsi.xaProps.get("portNumber")?.toString() ?: "5432"
            String databaseName = dsi.xaProps.get("databaseName")?.toString() ?: "moqui"

            if (dsi.xaDsClass.contains("postgresql") || dsi.xaDsClass.contains("PG")) {
                jdbcUrl = "jdbc:postgresql://${serverName}:${portNumber}/${databaseName}"
                driverClass = "org.postgresql.Driver"
            } else if (dsi.xaDsClass.contains("h2")) {
                jdbcUrl = dsi.xaProps.get("URL")?.toString() ?: "jdbc:h2:mem:test"
                driverClass = "org.h2.Driver"
            } else if (dsi.xaDsClass.contains("mysql")) {
                jdbcUrl = "jdbc:mysql://${serverName}:${portNumber}/${databaseName}"
                driverClass = "com.mysql.cj.jdbc.Driver"
            }

            username = dsi.xaProps.get("user")?.toString() ?: username
            password = dsi.xaProps.get("password")?.toString() ?: password
        }

        if (driverClass) {
            config.setDriverClassName(driverClass)
        }
        if (jdbcUrl) {
            config.setJdbcUrl(jdbcUrl)
        }
        if (username) {
            config.setUsername(username)
        }
        if (password) {
            config.setPassword(password)
        }

        // Connection pool settings - use reasonable defaults
        // Get pool settings from datasource config or use defaults
        MNode inlineJdbc = dsi.datasourceNode.first("inline-jdbc")

        int minPoolSize = 5
        int maxPoolSize = 50
        long idleTimeout = 600000  // 10 minutes
        long maxLifetime = 1800000 // 30 minutes
        long connectionTimeout = 30000 // 30 seconds

        if (inlineJdbc != null) {
            String poolMinSize = inlineJdbc.attribute("pool-minsize")
            String poolMaxSize = inlineJdbc.attribute("pool-maxsize")
            String poolTimeIdle = inlineJdbc.attribute("pool-time-idle")
            String poolTimeLife = inlineJdbc.attribute("pool-time-life")
            String poolTimeWait = inlineJdbc.attribute("pool-time-wait")

            if (poolMinSize) minPoolSize = Integer.parseInt(poolMinSize)
            if (poolMaxSize) maxPoolSize = Integer.parseInt(poolMaxSize)
            if (poolTimeIdle) idleTimeout = Long.parseLong(poolTimeIdle) * 1000
            if (poolTimeLife) maxLifetime = Long.parseLong(poolTimeLife) * 1000
            if (poolTimeWait) connectionTimeout = Long.parseLong(poolTimeWait) * 1000
        }

        config.setMinimumIdle(minPoolSize)
        config.setMaximumPoolSize(maxPoolSize)
        config.setIdleTimeout(idleTimeout)
        config.setMaxLifetime(maxLifetime)
        config.setConnectionTimeout(connectionTimeout)

        // Enable auto-commit (Moqui manages transactions explicitly)
        config.setAutoCommit(true)

        // Validation query
        String testQuery = dsi.database?.attribute("default-test-query")
        if (testQuery) {
            config.setConnectionTestQuery(testQuery)
        }

        // Note: XA transaction enlistment is handled by Moqui's TransactionFacade
        // HikariCP handles connection pooling, Narayana handles transaction coordination
        if (dsi.xaDsClass) {
            logger.debug("Created HikariCP pool for XA datasource ${dsi.uniqueName}")
        }

        return new HikariDataSource(config)
    }

    protected void setProperty(Object target, String name, Object value) {
        try {
            String setterName = "set" + name.substring(0, 1).toUpperCase() + name.substring(1)
            java.lang.reflect.Method setter = null

            for (java.lang.reflect.Method m : target.getClass().getMethods()) {
                if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
                    setter = m
                    break
                }
            }

            if (setter != null) {
                Class<?> paramType = setter.getParameterTypes()[0]
                Object convertedValue = value

                if (paramType == int.class || paramType == Integer.class) {
                    convertedValue = Integer.parseInt(value.toString())
                } else if (paramType == boolean.class || paramType == Boolean.class) {
                    convertedValue = Boolean.parseBoolean(value.toString())
                }

                setter.invoke(target, convertedValue)
            } else {
                logger.warn("No setter found for property ${name} on ${target.getClass().getName()}")
            }
        } catch (Exception e) {
            logger.warn("Error setting property ${name}: ${e.message}")
        }
    }

    @Override
    void destroy() {
        logger.info("Shutting down Narayana Transaction Manager and HikariCP pools")

        // Close HikariCP DataSources
        for (HikariDataSource ds in dataSourceList) {
            try {
                if (ds != null && !ds.isClosed()) {
                    ds.close()
                    logger.debug("Closed HikariCP pool: ${ds.getPoolName()}")
                }
            } catch (Exception e) {
                logger.warn("Error closing HikariCP pool: ${e.message}")
            }
        }
        dataSourceList.clear()
    }
}
