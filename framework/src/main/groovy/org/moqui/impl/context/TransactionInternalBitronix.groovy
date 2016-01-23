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

import bitronix.tm.BitronixTransactionManager
import bitronix.tm.TransactionManagerServices
import bitronix.tm.resource.jdbc.PoolingDataSource

import org.moqui.context.ExecutionContextFactory
import org.moqui.context.TransactionInternal
import org.moqui.entity.EntityFacade
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.EntityFacadeImpl

import javax.sql.DataSource
import javax.transaction.TransactionManager
import javax.transaction.UserTransaction
import java.sql.Connection

class TransactionInternalBitronix implements TransactionInternal {

    protected ExecutionContextFactoryImpl ecfi

    protected BitronixTransactionManager btm
    protected UserTransaction ut
    protected TransactionManager tm

    protected List<PoolingDataSource> pdsList = []

    @Override
    TransactionInternal init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf

        // NOTE: see the bitronix-default-config.properties file for more config

        btm = TransactionManagerServices.getTransactionManager()
        this.ut = btm
        this.tm = btm

        return this
    }

    @Override
    TransactionManager getTransactionManager() { return tm }

    @Override
    UserTransaction getUserTransaction() { return ut }

    @Override
    DataSource getDataSource(EntityFacade ef, Node datasourceNode, String tenantId) {
        // NOTE: this is called during EFI init, so use the passed one and don't try to get from ECFI
        EntityFacadeImpl efi = (EntityFacadeImpl) ef

        EntityValue tenant = null
        EntityFacadeImpl defaultEfi = null
        if (tenantId != "DEFAULT" && datasourceNode."@group-name" != "tenantcommon") {
            defaultEfi = ecfi.getEntityFacade("DEFAULT")
            tenant = defaultEfi.find("moqui.tenant.Tenant").condition("tenantId", tenantId).disableAuthz().one()
        }

        EntityValue tenantDataSource = null
        EntityList tenantDataSourceXaPropList = null
        if (tenant != null) {
            boolean alreadyDisabled = ecfi.getExecutionContext().getArtifactExecution().disableAuthz()
            try {
                tenantDataSource = defaultEfi.find("moqui.tenant.TenantDataSource").condition("tenantId", tenantId)
                        .condition("entityGroupName", datasourceNode."@group-name").one()
                if (tenantDataSource == null) {
                    // if there is no TenantDataSource for this group, look for one for the default-group-name
                    tenantDataSource = defaultEfi.find("moqui.tenant.TenantDataSource").condition("tenantId", tenantId)
                            .condition("entityGroupName", efi.getDefaultGroupName()).one()
                }
                tenantDataSourceXaPropList = tenantDataSource?.findRelated("moqui.tenant.TenantDataSourceXaProp", null, null, false, false)
            } finally {
                if (!alreadyDisabled) ecfi.getExecutionContext().getArtifactExecution().enableAuthz()
            }
        }

        Node inlineJdbc = datasourceNode."inline-jdbc"[0]
        Node xaProperties = inlineJdbc."xa-properties"[0]
        Node database = efi.getDatabaseNode((String) datasourceNode."@group-name")

        PoolingDataSource pds = new PoolingDataSource()
        pds.setUniqueName(tenantId + '_' + datasourceNode."@group-name" + '_DS')
        if (xaProperties) {
            String xsDsClass = inlineJdbc."@xa-ds-class" ? inlineJdbc."@xa-ds-class" : database."@default-xa-ds-class"
            pds.setClassName(xsDsClass)

            Properties p = new Properties()
            if (tenantDataSourceXaPropList) {
                for (EntityValue tenantDataSourceXaProp in tenantDataSourceXaPropList) {
                    String propValue = tenantDataSourceXaProp.propValue
                    // NOTE: consider changing this to expand for all system properties using groovy or something
                    if (propValue.contains("\${moqui.runtime}")) propValue = propValue.replace("\${moqui.runtime}", System.getProperty("moqui.runtime"))
                    p.setProperty((String) tenantDataSourceXaProp.propName, propValue)
                }
            } else {
                for (Map.Entry<String, String> entry in xaProperties.attributes().entrySet()) {
                    // the Derby "databaseName" property has a ${moqui.runtime} which is a System property, others may have it too
                    String propValue = entry.getValue()
                    // NOTE: consider changing this to expand for all system properties using groovy or something
                    if (propValue.contains("\${moqui.runtime}")) propValue = propValue.replace("\${moqui.runtime}", System.getProperty("moqui.runtime"))
                    p.setProperty(entry.getKey(), propValue)
                }
            }
            pds.setDriverProperties(p)
        } else {
            String jdbcUri = tenantDataSource ? (String) tenantDataSource.jdbcUri : inlineJdbc."@jdbc-uri"
            if (jdbcUri.contains("\${moqui.runtime}")) jdbcUri = jdbcUri.replace("\${moqui.runtime}", System.getProperty("moqui.runtime"))
            pds.setClassName("bitronix.tm.resource.jdbc.lrc.LrcXADataSource")
            String driver = inlineJdbc."@jdbc-driver" ? inlineJdbc."@jdbc-driver" : database."@default-jdbc-driver"
            pds.getDriverProperties().setProperty("driverClassName", driver)
            pds.getDriverProperties().setProperty("url", jdbcUri)
            pds.getDriverProperties().setProperty("user", tenantDataSource ? (String) tenantDataSource.jdbcUsername : inlineJdbc."@jdbc-username")
            pds.getDriverProperties().setProperty("password", tenantDataSource ? (String) tenantDataSource.jdbcPassword : inlineJdbc."@jdbc-password")
        }

        String txIsolationLevel = inlineJdbc."@isolation-level" ? inlineJdbc."@isolation-level" : database."@default-isolation-level"
        int isolationInt = efi.getTxIsolationFromString(txIsolationLevel)
        if (txIsolationLevel && isolationInt != -1) {
            switch (isolationInt) {
                case Connection.TRANSACTION_SERIALIZABLE: pds.setIsolationLevel("SERIALIZABLE"); break
                case Connection.TRANSACTION_REPEATABLE_READ: pds.setIsolationLevel("REPEATABLE_READ"); break
                case Connection.TRANSACTION_READ_UNCOMMITTED: pds.setIsolationLevel("READ_UNCOMMITTED"); break
                case Connection.TRANSACTION_READ_COMMITTED: pds.setIsolationLevel("READ_COMMITTED"); break
                case Connection.TRANSACTION_NONE: pds.setIsolationLevel("NONE"); break
            }
        }

        // no need for this, just sets min and max sizes: ads.setPoolSize
        if (inlineJdbc."@pool-minsize") pds.setMinPoolSize(inlineJdbc."@pool-minsize" as int)
        if (inlineJdbc."@pool-maxsize") pds.setMaxPoolSize(inlineJdbc."@pool-maxsize" as int)

        if (inlineJdbc."@pool-time-idle") pds.setMaxIdleTime(inlineJdbc."@pool-time-idle" as int)
        // if (inlineJdbc."@pool-time-reap") ads.setReapTimeout(inlineJdbc."@pool-time-reap" as int)
        // if (inlineJdbc."@pool-time-maint") ads.setMaintenanceInterval(inlineJdbc."@pool-time-maint" as int)
        if (inlineJdbc."@pool-time-wait") pds.setAcquisitionTimeout(inlineJdbc."@pool-time-wait" as int)
        pds.setAllowLocalTransactions(true) // allow mixing XA and non-XA transactions
        pds.setAutomaticEnlistingEnabled(true) // automatically enlist/delist this resource in the tx
        pds.setShareTransactionConnections(true) // share connections within a transaction
        pds.setDeferConnectionRelease(true) // only one transaction per DB connection (can be false if supported by DB)
        // pds.setShareTransactionConnections(false) // don't share connections in the ACCESSIBLE, needed?
        // pds.setIgnoreRecoveryFailures(false) // something to consider for XA recovery errors, quarantines by default

        if (inlineJdbc."@pool-test-query") {
            pds.setTestQuery((String) inlineJdbc."@pool-test-query")
        } else if (database."@default-test-query") {
            pds.setTestQuery((String) database."@default-test-query")
        }

        // init the DataSource
        pds.init()

        pdsList.add(pds)

        return pds
    }

    @Override
    void destroy() {
        // close the DataSources
        for (PoolingDataSource pds in pdsList) pds.close()
        // shutdown Bitronix
        btm.shutdown()
    }
}
