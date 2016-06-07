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

import com.atomikos.icatch.config.UserTransactionService
import com.atomikos.icatch.config.UserTransactionServiceImp
import com.atomikos.icatch.jta.UserTransactionManager
import com.atomikos.jdbc.AbstractDataSourceBean
import com.atomikos.jdbc.AtomikosDataSourceBean
import com.atomikos.jdbc.nonxa.AtomikosNonXADataSourceBean
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.TransactionInternal
import org.moqui.entity.EntityFacade
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.util.MNode

import javax.sql.DataSource
import javax.transaction.TransactionManager
import javax.transaction.UserTransaction

@CompileStatic
class TransactionInternalAtomikos implements TransactionInternal {

    protected ExecutionContextFactoryImpl ecfi

    protected UserTransactionService atomikosUts = null
    protected UserTransaction ut
    protected TransactionManager tm

    protected List<AbstractDataSourceBean> adsList = []

    @Override
    TransactionInternal init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf
        atomikosUts = new UserTransactionServiceImp()
        atomikosUts.init()

        UserTransactionManager utm = new UserTransactionManager()
        this.ut = utm
        this.tm = utm

        return this
    }

    @Override
    TransactionManager getTransactionManager() { return tm }

    @Override
    UserTransaction getUserTransaction() { return ut }

    @Override
    DataSource getDataSource(EntityFacade ef, MNode datasourceNode, String tenantId) {
        // NOTE: this is called during EFI init, so use the passed one and don't try to get from ECFI
        EntityFacadeImpl efi = (EntityFacadeImpl) ef

        EntityFacadeImpl.DatasourceInfo dsi = new EntityFacadeImpl.DatasourceInfo(efi, datasourceNode)

        AbstractDataSourceBean ads
        if (dsi.xaDsClass) {
            AtomikosDataSourceBean ds = new AtomikosDataSourceBean()
            ds.setUniqueResourceName(dsi.uniqueName)
            ds.setXaDataSourceClassName(dsi.xaDsClass)
            ds.setXaProperties(dsi.xaProps)

            ads = ds
        } else {
            AtomikosNonXADataSourceBean ds = new AtomikosNonXADataSourceBean()
            ds.setUniqueResourceName(dsi.uniqueName)
            ds.setDriverClassName(dsi.jdbcDriver)
            ds.setUrl(dsi.jdbcUri)
            ds.setUser(dsi.jdbcUsername)
            ds.setPassword(dsi.jdbcPassword)

            ads = ds
        }

        String txIsolationLevel = dsi.inlineJdbc.attribute("isolation-level") ?
                dsi.inlineJdbc.attribute("isolation-level") : dsi.database.attribute("default-isolation-level")
        if (txIsolationLevel && efi.getTxIsolationFromString(txIsolationLevel) != -1)
            ads.setDefaultIsolationLevel(efi.getTxIsolationFromString(txIsolationLevel))

        ads.setMinPoolSize((dsi.inlineJdbc.attribute("pool-minsize") ?: "5") as int)
        ads.setMaxPoolSize((dsi.inlineJdbc.attribute("pool-maxsize") ?: "50") as int)

        if (dsi.inlineJdbc.attribute("pool-time-idle")) ads.setMaxIdleTime(dsi.inlineJdbc.attribute("pool-time-idle") as int)
        if (dsi.inlineJdbc.attribute("pool-time-reap")) ads.setReapTimeout(dsi.inlineJdbc.attribute("pool-time-reap") as int)
        if (dsi.inlineJdbc.attribute("pool-time-maint")) ads.setMaintenanceInterval(dsi.inlineJdbc.attribute("pool-time-maint") as int)
        if (dsi.inlineJdbc.attribute("pool-time-wait")) ads.setBorrowConnectionTimeout(dsi.inlineJdbc.attribute("pool-time-wait") as int)

        if (dsi.inlineJdbc.attribute("pool-test-query")) {
            ads.setTestQuery(dsi.inlineJdbc.attribute("pool-test-query"))
        } else if (dsi.database.attribute("default-test-query")) {
            ads.setTestQuery(dsi.database.attribute("default-test-query"))
        }

        adsList.add(ads)

        return ads
    }

    @Override
    void destroy() {
        // close the DataSources
        for (AbstractDataSourceBean ads in adsList) ads.close()
        // shutdown Atomikos
        atomikosUts.shutdown(false)
    }
}
