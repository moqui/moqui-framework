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

    Properties getXaProperties() {

    }

    @Override
    DataSource getDataSource(EntityFacade ef, Node datasourceNode, String tenantId) {
        // NOTE: this is called during EFI init, so use the passed one and don't try to get from ECFI
        EntityFacadeImpl efi = (EntityFacadeImpl) ef

        EntityFacadeImpl.DatasourceInfo dsi = new EntityFacadeImpl.DatasourceInfo(efi, datasourceNode)

        PoolingDataSource pds = new PoolingDataSource()
        pds.setUniqueName(dsi.uniqueName)
        if (dsi.xaDsClass) {
            pds.setClassName(dsi.xaDsClass)
            pds.setDriverProperties(dsi.xaProps)
        } else {
            pds.setClassName("bitronix.tm.resource.jdbc.lrc.LrcXADataSource")
            pds.getDriverProperties().setProperty("driverClassName", dsi.jdbcDriver)
            pds.getDriverProperties().setProperty("url", dsi.jdbcUri)
            pds.getDriverProperties().setProperty("user", dsi.jdbcUsername)
            pds.getDriverProperties().setProperty("password", dsi.jdbcPassword)
        }

        String txIsolationLevel = dsi.inlineJdbc."@isolation-level" ? dsi.inlineJdbc."@isolation-level" : dsi.database."@default-isolation-level"
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
        if (dsi.inlineJdbc."@pool-minsize") pds.setMinPoolSize(dsi.inlineJdbc."@pool-minsize" as int)
        if (dsi.inlineJdbc."@pool-maxsize") pds.setMaxPoolSize(dsi.inlineJdbc."@pool-maxsize" as int)

        if (dsi.inlineJdbc."@pool-time-idle") pds.setMaxIdleTime(dsi.inlineJdbc."@pool-time-idle" as int)
        // if (dsi.inlineJdbc."@pool-time-reap") ads.setReapTimeout(dsi.inlineJdbc."@pool-time-reap" as int)
        // if (dsi.inlineJdbc."@pool-time-maint") ads.setMaintenanceInterval(dsi.inlineJdbc."@pool-time-maint" as int)
        if (dsi.inlineJdbc."@pool-time-wait") pds.setAcquisitionTimeout(dsi.inlineJdbc."@pool-time-wait" as int)
        pds.setAllowLocalTransactions(true) // allow mixing XA and non-XA transactions
        pds.setAutomaticEnlistingEnabled(true) // automatically enlist/delist this resource in the tx
        pds.setShareTransactionConnections(true) // share connections within a transaction
        pds.setDeferConnectionRelease(true) // only one transaction per DB connection (can be false if supported by DB)
        // pds.setShareTransactionConnections(false) // don't share connections in the ACCESSIBLE, needed?
        // pds.setIgnoreRecoveryFailures(false) // something to consider for XA recovery errors, quarantines by default

        if (dsi.inlineJdbc."@pool-test-query") {
            pds.setTestQuery((String) dsi.inlineJdbc."@pool-test-query")
        } else if (dsi.database."@default-test-query") {
            pds.setTestQuery((String) dsi.database."@default-test-query")
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
