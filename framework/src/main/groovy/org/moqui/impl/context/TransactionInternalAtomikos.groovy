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

import org.moqui.context.ExecutionContextFactory
import org.moqui.context.TransactionInternal
import org.moqui.entity.EntityFacade
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.EntityFacadeImpl

import javax.sql.DataSource
import javax.transaction.TransactionManager
import javax.transaction.UserTransaction

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
    DataSource getDataSource(EntityFacade ef, Node datasourceNode, String tenantId) {
        // NOTE: this is called during EFI init, so use the passed one and don't try to get from ECFI
        EntityFacadeImpl efi = (EntityFacadeImpl) ef

        EntityValue tenant = null
        EntityFacadeImpl defaultEfi = null
        if (tenantId != "DEFAULT" && datasourceNode."@group-name" != "tenantcommon") {
            defaultEfi = ecfi.getEntityFacade("DEFAULT")
            tenant = defaultEfi.find("moqui.tenant.Tenant").condition("tenantId", tenantId).one()
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

        AbstractDataSourceBean ads
        if (xaProperties) {
            AtomikosDataSourceBean ds = new AtomikosDataSourceBean()
            ds.setUniqueResourceName(tenantId + '_' + datasourceNode."@group-name" + '_DS')
            String xsDsClass = inlineJdbc."@xa-ds-class" ? inlineJdbc."@xa-ds-class" : database."@default-xa-ds-class"
            ds.setXaDataSourceClassName(xsDsClass)

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
            ds.setXaProperties(p)

            ads = ds
        } else {
            AtomikosNonXADataSourceBean ds = new AtomikosNonXADataSourceBean()
            ds.setUniqueResourceName(tenantId + '_' + datasourceNode."@group-name" + '_DS')
            String driver = inlineJdbc."@jdbc-driver" ? inlineJdbc."@jdbc-driver" : database."@default-jdbc-driver"
            ds.setDriverClassName(driver)
            ds.setUrl(tenantDataSource ? (String) tenantDataSource.jdbcUri : inlineJdbc."@jdbc-uri")
            ds.setUser(tenantDataSource ? (String) tenantDataSource.jdbcUsername : inlineJdbc."@jdbc-username")
            ds.setPassword(tenantDataSource ? (String) tenantDataSource.jdbcPassword : inlineJdbc."@jdbc-password")

            ads = ds
        }

        String txIsolationLevel = inlineJdbc."@isolation-level" ? inlineJdbc."@isolation-level" : database."@default-isolation-level"
        if (txIsolationLevel && efi.getTxIsolationFromString(txIsolationLevel) != -1) {
            ads.setDefaultIsolationLevel(efi.getTxIsolationFromString(txIsolationLevel))
        }

        // no need for this, just sets min and max sizes: ads.setPoolSize
        if (inlineJdbc."@pool-minsize") ads.setMinPoolSize(inlineJdbc."@pool-minsize" as int)
        if (inlineJdbc."@pool-maxsize") ads.setMaxPoolSize(inlineJdbc."@pool-maxsize" as int)

        if (inlineJdbc."@pool-time-idle") ads.setMaxIdleTime(inlineJdbc."@pool-time-idle" as int)
        if (inlineJdbc."@pool-time-reap") ads.setReapTimeout(inlineJdbc."@pool-time-reap" as int)
        if (inlineJdbc."@pool-time-maint") ads.setMaintenanceInterval(inlineJdbc."@pool-time-maint" as int)
        if (inlineJdbc."@pool-time-wait") ads.setBorrowConnectionTimeout(inlineJdbc."@pool-time-wait" as int)

        if (inlineJdbc."@pool-test-query") {
            ads.setTestQuery((String) inlineJdbc."@pool-test-query")
        } else if (database."@default-test-query") {
            ads.setTestQuery((String) database."@default-test-query")
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
