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

import groovy.transform.CompileStatic
import org.moqui.BaseException
import org.moqui.context.TransactionException
import org.moqui.context.TransactionFacade
import org.moqui.context.TransactionInternal
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.naming.Context
import javax.naming.InitialContext
import javax.naming.NamingException
import javax.sql.XAConnection
import javax.transaction.*
import javax.transaction.xa.XAException
import javax.transaction.xa.XAResource
import java.sql.*
import java.util.concurrent.Executor

@CompileStatic
class TransactionFacadeImpl implements TransactionFacade {
    protected final static Logger logger = LoggerFactory.getLogger(TransactionFacadeImpl.class)

    protected final ExecutionContextFactoryImpl ecfi

    protected TransactionInternal transactionInternal = null

    protected UserTransaction ut
    protected TransactionManager tm

    protected boolean useTransactionCache = true
    protected boolean useConnectionStash = true

    private ThreadLocal<TxStackInfo> txStackInfoCurThread = new ThreadLocal<TxStackInfo>()
    private ThreadLocal<LinkedList<TxStackInfo>> txStackInfoListThread = new ThreadLocal<LinkedList<TxStackInfo>>()

    TransactionFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        initTransactionInternal()
    }

    void initTransactionInternal() {
        MNode transactionFacadeNode = ecfi.getConfXmlRoot().first("transaction-facade")
        if (transactionFacadeNode.hasChild("transaction-jndi")) {
            this.populateTransactionObjectsJndi()
        } else if (transactionFacadeNode.hasChild("transaction-internal")) {
            // initialize internal
            MNode transactionInternalNode = transactionFacadeNode.first("transaction-internal")
            String tiClassName = transactionInternalNode.attribute("class")
            transactionInternal = (TransactionInternal) Thread.currentThread().getContextClassLoader()
                    .loadClass(tiClassName).newInstance()
            transactionInternal.init(ecfi)

            ut = transactionInternal.getUserTransaction()
            tm = transactionInternal.getTransactionManager()
        } else {
            throw new IllegalArgumentException("No transaction-jndi or transaction-internal elements found in Moqui Conf XML file")
        }

        if (transactionFacadeNode.attribute("use-transaction-cache") == "false") useTransactionCache = false
        if (transactionFacadeNode.attribute("use-connection-stash") == "false") useConnectionStash = false
    }

    void destroy() {
        // set to null first to avoid additional operations
        this.tm = null
        this.ut = null

        // destroy internal if applicable; nothing for JNDI
        if (transactionInternal != null) transactionInternal.destroy()

        txStackInfoListThread.remove()
    }

    /** This is called to make sure all transactions, etc are closed for the thread.
     * It commits any active transactions, clears out internal data for the thread, etc.
     */
    void destroyAllInThread() {
        if (this.isTransactionInPlace()) {
            logger.warn("Thread ending with a transaction in place. Trying to commit.")
            this.commit()
        }

        LinkedList<TxStackInfo> txStackInfoList = txStackInfoListThread.get()
        if (txStackInfoList) {
            int numSuspended = 0;
            for (TxStackInfo txStackInfo in txStackInfoList) {
                Transaction tx = txStackInfo.suspendedTx
                if (tx != null) {
                    this.resume()
                    this.commit()
                    numSuspended++
                }
            }
            if (numSuspended > 0) logger.warn("Cleaned up [" + numSuspended + "] suspended transactions.")
        }

        txStackInfoListThread.remove()
    }

    TransactionInternal getTransactionInternal() { return transactionInternal }
    TransactionManager getTransactionManager() { return tm }
    UserTransaction getUserTransaction() { return ut }
    Long getCurrentTransactionStartTime() {
        TxStackInfo txStackInfo = getTxStackInfo()
        Long time = txStackInfo != null ? (Long) txStackInfo.transactionBeginStartTime : (Long) null
        if (time == null && logger.isTraceEnabled()) logger.trace("No transaction begin start time, transaction in place? [${this.isTransactionInPlace()}]", new BaseException("Empty transactionBeginStackList location"))
        return time
    }

    protected LinkedList<TxStackInfo> getTxStackInfoList() {
        LinkedList<TxStackInfo> list = (LinkedList<TxStackInfo>) txStackInfoListThread.get()
        if (list == null) {
            list = new LinkedList<TxStackInfo>()
            txStackInfoListThread.set(list)
            TxStackInfo txStackInfo = new TxStackInfo()
            list.add(txStackInfo)
            txStackInfoCurThread.set(txStackInfo)
        }
        return list
    }
    protected TxStackInfo getTxStackInfo() {
        TxStackInfo txStackInfo = (TxStackInfo) txStackInfoCurThread.get()
        if (txStackInfo == null) {
            LinkedList<TxStackInfo> list = getTxStackInfoList()
            txStackInfo = list.getFirst()
        }
        return txStackInfo
    }
    protected void pushTxStackInfo(Transaction tx, Exception txLocation) {
        TxStackInfo txStackInfo = new TxStackInfo()
        txStackInfo.suspendedTx = tx
        txStackInfo.suspendedTxLocation = txLocation
        getTxStackInfoList().addFirst(txStackInfo)
        txStackInfoCurThread.set(txStackInfo)
    }
    protected void popTxStackInfo() {
        LinkedList<TxStackInfo> list = getTxStackInfoList()
        list.removeFirst()
        txStackInfoCurThread.set(list.getFirst())
    }


    @Override
    Object runUseOrBegin(Integer timeout, String rollbackMessage, Closure closure) {
        if (rollbackMessage == null) rollbackMessage = ""
        boolean beganTransaction = begin(timeout)
        try {
            return closure.call()
        } catch (Throwable t) {
            rollback(beganTransaction, rollbackMessage, t)
            throw t
        } finally {
            commit(beganTransaction)
        }
    }
    @Override
    Object runRequireNew(Integer timeout, String rollbackMessage, Closure closure) {
        return runRequireNew(timeout, rollbackMessage, true, true, closure)
    }
    protected final static boolean requireNewThread = true
    Object runRequireNew(Integer timeout, String rollbackMessage, boolean beginTx, boolean threadReuseEci, Closure closure) {
        Object result = null
        if (requireNewThread) {
            // if there is a timeout for this thread wait 10x the timeout (so multiple seconds by 10k instead of 1k)
            long threadWait = timeout != null ? timeout * 10000 : 60000

            Thread txThread = null
            ExecutionContextImpl eci = ecfi.getEci()
            Throwable threadThrown = null

            try {
                txThread = Thread.start('RequireNewTx', {
                    if (threadReuseEci) ecfi.useExecutionContextInThread(eci)
                    try {
                        if (beginTx) {
                            result = runUseOrBegin(timeout, rollbackMessage, closure)
                        } else {
                            result = closure.call()
                        }
                    } catch (Throwable t) {
                        threadThrown = t
                    }
                })
            } finally {
                if (txThread != null) {
                    txThread.join(threadWait)
                    if (txThread.state != Thread.State.TERMINATED) {
                        // TODO: do more than this?
                        logger.warn("New transaction thread not terminated, in state ${txThread.state}")
                    }
                }
            }
            if (threadThrown != null) throw threadThrown
        } else {
            boolean suspendedTransaction = false
            try {
                if (isTransactionInPlace()) suspendedTransaction = suspend()
                if (beginTx) {
                    result = runUseOrBegin(timeout, rollbackMessage, closure)
                } else {
                    result = closure.call()
                }
            } finally {
                if (suspendedTransaction) resume()
            }
        }
        return result
    }

    @Override
    XAResource getActiveXaResource(String resourceName) {
        return getTxStackInfo().getActiveXaResourceMap().get(resourceName)
    }
    @Override
    void putAndEnlistActiveXaResource(String resourceName, XAResource xar) {
        enlistResource(xar)
        getTxStackInfo().getActiveXaResourceMap().put(resourceName, xar)
    }

    @Override
    Synchronization getActiveSynchronization(String syncName) {
        return getTxStackInfo().getActiveSynchronizationMap().get(syncName)
    }
    @Override
    void putAndEnlistActiveSynchronization(String syncName, Synchronization sync) {
        registerSynchronization(sync)
        getTxStackInfo().getActiveSynchronizationMap().put(syncName, sync)
    }


    @Override
    int getStatus() {
        try {
            return ut.getStatus()
        } catch (SystemException e) {
            throw new TransactionException("System error, could not get transaction status", e)
        }
    }

    @Override
    String getStatusString() {
        int statusInt = getStatus()
        /*
         * javax.transaction.Status
         * STATUS_ACTIVE           0
         * STATUS_MARKED_ROLLBACK  1
         * STATUS_PREPARED         2
         * STATUS_COMMITTED        3
         * STATUS_ROLLEDBACK       4
         * STATUS_UNKNOWN          5
         * STATUS_NO_TRANSACTION   6
         * STATUS_PREPARING        7
         * STATUS_COMMITTING       8
         * STATUS_ROLLING_BACK     9
         */
        switch (statusInt) {
            case Status.STATUS_ACTIVE:
                return "Active (${statusInt})";
            case Status.STATUS_COMMITTED:
                return "Committed (${statusInt})";
            case Status.STATUS_COMMITTING:
                return "Committing (${statusInt})";
            case Status.STATUS_MARKED_ROLLBACK:
                return "Marked Rollback-Only (${statusInt})";
            case Status.STATUS_NO_TRANSACTION:
                return "No Transaction (${statusInt})";
            case Status.STATUS_PREPARED:
                return "Prepared (${statusInt})";
            case Status.STATUS_PREPARING:
                return "Preparing (${statusInt})";
            case Status.STATUS_ROLLEDBACK:
                return "Rolledback (${statusInt})";
            case Status.STATUS_ROLLING_BACK:
                return "Rolling Back (${statusInt})";
            case Status.STATUS_UNKNOWN:
                return "Status Unknown (${statusInt})";
            default:
                return "Not a valid status code (${statusInt})";
        }
    }

    @Override
    boolean isTransactionInPlace() { return getStatus() != Status.STATUS_NO_TRANSACTION }

    @Override
    boolean begin(Integer timeout) {
        try {
            int currentStatus = ut.getStatus()
            // logger.warn("================ begin TX, currentStatus=${currentStatus}", new BaseException("beginning transaction at"))

            TxStackInfo txStackInfo = getTxStackInfo()
            if (currentStatus == Status.STATUS_ACTIVE) {
                // don't begin, and return false so caller knows we didn't
                return false
            } else if (currentStatus == Status.STATUS_MARKED_ROLLBACK) {
                if (txStackInfo.transactionBegin != null) {
                    logger.warn("Current transaction marked for rollback, so no transaction begun. This stack trace shows where the transaction began: ", txStackInfo.transactionBegin)
                } else {
                    logger.warn("Current transaction marked for rollback, so no transaction begun (NOTE: No stack trace to show where transaction began).")
                }

                if (txStackInfo.rollbackOnlyInfo != null) {
                    logger.warn("Current transaction marked for rollback, not beginning a new transaction. The rollback-only was set here: ", txStackInfo.rollbackOnlyInfo.rollbackLocation)
                    throw new TransactionException((String) "Current transaction marked for rollback, so no transaction begun. The rollback was originally caused by: " + txStackInfo.rollbackOnlyInfo.causeMessage, txStackInfo.rollbackOnlyInfo.causeThrowable)
                } else {
                    return false
                }
            }

            // NOTE: Since JTA 1.1 setTransactionTimeout() is local to the thread, so this doesn't need to be synchronized.
            if (timeout) ut.setTransactionTimeout(timeout)
            ut.begin()

            txStackInfo.transactionBegin = new Exception("Tx Begin Placeholder")
            txStackInfo.transactionBeginStartTime = System.currentTimeMillis()
            // logger.warn("================ begin TX, getActiveSynchronizationStack()=${getActiveSynchronizationStack()}")

            return true
        } catch (NotSupportedException e) {
            throw new TransactionException("Could not begin transaction (could be a nesting problem)", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not begin transaction", e)
        } finally {
            // make sure the timeout always gets reset to the default
            if (timeout) ut.setTransactionTimeout(0)
        }
    }

    @Override
    void commit(boolean beganTransaction) { if (beganTransaction) this.commit() }

    @Override
    void commit() {
        TxStackInfo txStackInfo = getTxStackInfo()
        try {
            int status = ut.getStatus();
            // logger.warn("================ commit TX, currentStatus=${status}")

            txStackInfo.closeTxConnections()
            if (status == Status.STATUS_MARKED_ROLLBACK) {
                ut.rollback()
            } else if (status != Status.STATUS_NO_TRANSACTION && status != Status.STATUS_COMMITTING &&
                    status != Status.STATUS_COMMITTED && status != Status.STATUS_ROLLING_BACK &&
                    status != Status.STATUS_ROLLEDBACK) {
                ut.commit()
            } else {
                if (status != Status.STATUS_NO_TRANSACTION)
                    logger.warn((String) "Not committing transaction because status is " + getStatusString(), new Exception("Bad TX status location"))
            }
        } catch (RollbackException e) {
            if (txStackInfo.rollbackOnlyInfo != null) {
                logger.warn("Could not commit transaction, was marked rollback-only. The rollback-only was set here: ", txStackInfo.rollbackOnlyInfo.rollbackLocation)
                throw new TransactionException("Could not commit transaction, was marked rollback-only. The rollback was originally caused by: " + txStackInfo.rollbackOnlyInfo.causeMessage, txStackInfo.rollbackOnlyInfo.causeThrowable)
            } else {
                throw new TransactionException("Could not commit transaction, was rolled back instead (and we don't have a rollback-only cause)", e)
            }
        } catch (IllegalStateException e) {
            throw new TransactionException("Could not commit transaction", e)
        } catch (HeuristicMixedException e) {
            throw new TransactionException("Could not commit transaction", e)
        } catch (HeuristicRollbackException e) {
            throw new TransactionException("Could not commit transaction", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not commit transaction", e)
        } finally {
            // there shouldn't be a TX around now, but if there is the commit may have failed so rollback to clean things up
            if (isTransactionInPlace()) rollback("Commit failed, rolling back to clean up", null)

            txStackInfo.clearCurrent()
        }
    }

    @Override
    void rollback(boolean beganTransaction, String causeMessage, Throwable causeThrowable) {
        if (beganTransaction) {
            this.rollback(causeMessage, causeThrowable)
        } else {
            this.setRollbackOnly(causeMessage, causeThrowable)
        }
    }

    @Override
    void rollback(String causeMessage, Throwable causeThrowable) {
        TxStackInfo txStackInfo = getTxStackInfo()
        try {
            // logger.warn("================ rollback TX, currentStatus=${getStatus()}")
            if (getStatus() == Status.STATUS_NO_TRANSACTION) {
                logger.warn("Transaction not rolled back, status is STATUS_NO_TRANSACTION")
                return
            }

            logger.warn("Transaction rollback. The rollback was originally caused by: ${causeMessage}", causeThrowable)
            logger.warn("Transaction rollback for [${causeMessage}]. Here is the current location: ", new BaseException("Rollback location"))

            txStackInfo.closeTxConnections()
            ut.rollback()
        } catch (IllegalStateException e) {
            throw new TransactionException("Could not rollback transaction", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not rollback transaction", e)
        } finally {
            // NOTE: should this really be in finally? maybe we only want to do this if there is a successful rollback
            // to avoid removing things that should still be there, or maybe here in finally it will match up the adds
            // and removes better
            txStackInfo.clearCurrent()
        }
    }

    @Override
    void setRollbackOnly(String causeMessage, Throwable causeThrowable) {
        try {
            int status = getStatus()
            if (status != Status.STATUS_NO_TRANSACTION) {
                if (status != Status.STATUS_MARKED_ROLLBACK) {
                    logger.warn("Transaction set rollback only. The rollback was originally caused by: ${causeMessage}", causeThrowable)
                    logger.warn("Transaction set rollback only for [${causeMessage}]. Here is the current location: ", new BaseException("Set rollback only location"))

                    ut.setRollbackOnly()
                    // do this after setRollbackOnly so it only tracks it if rollback-only was actually set
                    getTxStackInfo().rollbackOnlyInfo = new RollbackInfo(causeMessage, causeThrowable, new Exception("Set rollback-only location"))
                }
            } else {
                logger.warn("Rollback only not set on current transaction, status is STATUS_NO_TRANSACTION")
            }
        } catch (IllegalStateException e) {
            throw new TransactionException("Could not set rollback only on current transaction", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not set rollback only on current transaction", e)
        }
    }

    @Override
    boolean suspend() {
        try {
            if (getStatus() == Status.STATUS_NO_TRANSACTION) {
                logger.warn("No transaction in place, so not suspending.")
                return false
            }

            // close connections before suspend, let the pool reuse them
            TxStackInfo txStackInfo = getTxStackInfo()
            txStackInfo.closeTxConnections()

            Transaction tx = tm.suspend()
            // only do these after successful suspend
            pushTxStackInfo(tx, new Exception("Transaction Suspend Location"))

            return true
        } catch (SystemException e) {
            throw new TransactionException("Could not suspend transaction", e)
        }
    }

    @Override
    void resume() {
        try {
            TxStackInfo txStackInfo = getTxStackInfo()
            if (txStackInfo.suspendedTx != null) {
                tm.resume(txStackInfo.suspendedTx)
                // only do this after successful resume
                popTxStackInfo()
            } else {
                logger.warn("No transaction suspended, so not resuming.")
            }
        } catch (InvalidTransactionException e) {
            throw new TransactionException("Could not resume transaction", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not resume transaction", e)
        }
    }

    @Override
    Connection enlistConnection(XAConnection con) {
        if (con == null) return null
        try {
            XAResource resource = con.getXAResource()
            this.enlistResource(resource)
            return con.getConnection()
        } catch (SQLException e) {
            throw new TransactionException("Could not enlist connection in transaction", e)
        }
    }

    @Override
    void enlistResource(XAResource resource) {
        if (resource == null) return
        if (getStatus() != Status.STATUS_ACTIVE) {
            logger.warn("Not enlisting XAResource: transaction not ACTIVE", new Exception("Warning Location"))
            return
        }
        try {
            Transaction tx = tm.getTransaction()
            if (tx) {
                 tx.enlistResource(resource)
            } else {
                logger.warn("Not enlisting XAResource: transaction was null", new Exception("Warning Location"))
            }
        } catch (RollbackException e) {
            throw new TransactionException("Could not enlist XAResource in transaction", e)
        } catch (SystemException e) {
            // This is deprecated, hopefully errors are adequate without, but leaving here for future reference
            // if (e instanceof ExtendedSystemException) {
            //     for (Throwable se in e.errors) logger.error("Extended Atomikos error: ${se.toString()}", se)
            // }
            throw new TransactionException("Could not enlist XAResource in transaction", e)
        }
    }

    @Override
    void registerSynchronization(Synchronization sync) {
        if (sync == null) return
        if (getStatus() != Status.STATUS_ACTIVE) {
            logger.warn("Not registering Synchronization: transaction not ACTIVE", new Exception("Warning Location"))
            return
        }
        try {
            Transaction tx = tm.getTransaction()
            if (tx) {
                 tx.registerSynchronization(sync)
            } else {
                logger.warn("Not registering Synchronization: transaction was null", new Exception("Warning Location"))
            }
        } catch (RollbackException e) {
            throw new TransactionException("Could not register Synchronization in transaction", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not register Synchronization in transaction", e)
        }
    }

    @Override
    void initTransactionCache() {
        TxStackInfo txStackInfo = getTxStackInfo()
        if (useTransactionCache && txStackInfo.txCache == null) {
            if (logger.isInfoEnabled()) {
                StringBuilder infoString = new StringBuilder()
                infoString.append("Initializing TX cache at:")
                for (def infoAei in ecfi.getExecutionContext().getArtifactExecution().getStack()) infoString.append("\n").append(infoAei)
                logger.info(infoString.toString())
            }

            TransactionManager tm = ecfi.getTransactionFacade().getTransactionManager()
            if (tm == null || tm.getStatus() != Status.STATUS_ACTIVE) throw new XAException("Cannot enlist: no transaction manager or transaction not active")
            Transaction tx = tm.getTransaction()
            if (tx == null) throw new XAException(XAException.XAER_NOTA)

            TransactionCache txCache = new TransactionCache(this.ecfi, tx)
            txStackInfo.txCache = txCache
            registerSynchronization(txCache)
        }
    }
    @Override
    boolean isTransactionCacheActive() { return getTxStackInfo().txCache != null }
    TransactionCache getTransactionCache() { return getTxStackInfo().txCache }

    Connection getTxConnection(String tenantId, String groupName) {
        if (!useConnectionStash) return null

        String conKey = tenantId.concat(groupName)
        TxStackInfo txStackInfo = getTxStackInfo()
        ConnectionWrapper con = (ConnectionWrapper) txStackInfo.txConByGroup.get(conKey)
        if (con != null && con.isClosed()) {
            txStackInfo.txConByGroup.remove(conKey)
            logger.info("Stashed connection closed elsewhere for tenant ${tenantId} group ${groupName}: ${con.toString()}")
            return null
        } else {
            return con
        }
    }
    Connection stashTxConnection(String tenantId, String groupName, Connection con) {
        if (!useConnectionStash || !isTransactionInPlace()) return con

        TxStackInfo txStackInfo = getTxStackInfo()
        // if transactionBeginStartTime is null we didn't begin the transaction, so can't count on commit/rollback through this
        if (txStackInfo.transactionBeginStartTime == null) return con

        String conKey = tenantId.concat(groupName)
        ConnectionWrapper existing = (ConnectionWrapper) txStackInfo.txConByGroup.get(conKey)
        try {
            if (existing != null && !existing.isClosed()) existing.closeInternal()
        } catch (Throwable t) {
            logger.error("Error closing previously stashed connection for tenant ${tenantId} group ${groupName}: ${existing.toString()}", t)
        }
        ConnectionWrapper newCw = new ConnectionWrapper(con, this, tenantId, groupName)
        txStackInfo.txConByGroup.put(conKey, newCw)
        return newCw
    }

    @CompileStatic
    static class RollbackInfo {
        String causeMessage
        /** A rollback is often done because of another error, this represents that error. */
        Throwable causeThrowable
        /** This is for a stack trace for where the rollback was actually called to help track it down more easily. */
        Exception rollbackLocation

        RollbackInfo(String causeMessage, Throwable causeThrowable, Exception rollbackLocation) {
            this.causeMessage = causeMessage
            this.causeThrowable = causeThrowable
            this.rollbackLocation = rollbackLocation
        }
    }

    @CompileStatic
    static class TxStackInfo {
        Exception transactionBegin = (Exception) null
        Long transactionBeginStartTime = (Long) null
        RollbackInfo rollbackOnlyInfo = (RollbackInfo) null

        Transaction suspendedTx = (Transaction) null
        Exception suspendedTxLocation = (Exception) null

        protected Map<String, XAResource> activeXaResourceMap = new LinkedHashMap<>()
        protected Map<String, Synchronization> activeSynchronizationMap = new LinkedHashMap<>()
        protected Map<String, ConnectionWrapper> txConByGroup = new HashMap<>()
        TransactionCache txCache = (TransactionCache) null

        Map<String, XAResource> getActiveXaResourceMap() { return activeXaResourceMap }
        Map<String, Synchronization> getActiveSynchronizationMap() { return activeSynchronizationMap }
        Map<String, ConnectionWrapper> getTxConByGroup() { return txConByGroup }


        void clearCurrent() {
            rollbackOnlyInfo = (RollbackInfo) null
            transactionBegin = (Exception) null
            transactionBeginStartTime = (Long) null
            activeXaResourceMap.clear()
            activeSynchronizationMap.clear()
            txCache = (TransactionCache) null
            // this should already be done, but make sure
            closeTxConnections()
        }

        void closeTxConnections() {
            for (Map.Entry<String, ConnectionWrapper> entry in txConByGroup.entrySet()) {
                try {
                    ConnectionWrapper con = entry.value
                    if (con != null && !con.isClosed()) con.closeInternal()
                } catch (Throwable t) {
                    logger.error("Error closing connection for tenant/group ${entry.key}", t)
                }
            }
            txConByGroup.clear()
        }
    }

    // ========== Initialize/Populate Methods ==========

    void populateTransactionObjectsJndi() {
        MNode transactionJndiNode = this.ecfi.getConfXmlRoot().first("transaction-facade").first("transaction-jndi")
        String userTxJndiName = transactionJndiNode.attribute("user-transaction-jndi-name")
        String txMgrJndiName = transactionJndiNode.attribute("transaction-manager-jndi-name")

        MNode serverJndi = this.ecfi.getConfXmlRoot().first("transaction-facade").first("server-jndi")

        try {
            InitialContext ic;
            if (serverJndi) {
                Hashtable<String, Object> h = new Hashtable<String, Object>()
                h.put(Context.INITIAL_CONTEXT_FACTORY, serverJndi.attribute("initial-context-factory"))
                h.put(Context.PROVIDER_URL, serverJndi.attribute("context-provider-url"))
                if (serverJndi.attribute("url-pkg-prefixes")) h.put(Context.URL_PKG_PREFIXES, serverJndi.attribute("url-pkg-prefixes"))
                if (serverJndi.attribute("security-principal")) h.put(Context.SECURITY_PRINCIPAL, serverJndi.attribute("security-principal"))
                if (serverJndi.attribute("security-credentials")) h.put(Context.SECURITY_CREDENTIALS, serverJndi.attribute("security-credentials"))
                ic = new InitialContext(h)
            } else {
                ic = new InitialContext()
            }

            this.ut = (UserTransaction) ic.lookup(userTxJndiName)
            this.tm = (TransactionManager) ic.lookup(txMgrJndiName)
        } catch (NamingException ne) {
            logger.error("Error while finding JNDI Transaction objects [${userTxJndiName}] and [${txMgrJndiName}] from server [${serverJndi ? serverJndi.attribute("context-provider-url") : "default"}].", ne)
        }

        if (!this.ut) logger.error("Could not find UserTransaction with name [${userTxJndiName}] in JNDI server [${serverJndi ? serverJndi.attribute("context-provider-url") : "default"}].")
        if (!this.tm) logger.error("Could not find TransactionManager with name [${txMgrJndiName}] in JNDI server [${serverJndi ? serverJndi.attribute("context-provider-url") : "default"}].")
    }

    /** A simple delegating wrapper for java.sql.Connection.
     *
     * The close() method does nothing, only closed when closeInternal() called by TransactionFacade on commit,
     * rollback, or destroy (when transactions are also cleaned up as a last resort).
     *
     * Connections are attached to 3 things: entity group, tenant, and transaction.
     */
    static class ConnectionWrapper implements Connection {
        protected Connection con
        protected TransactionFacadeImpl tfi
        protected String tenantId, groupName

        ConnectionWrapper(Connection con, TransactionFacadeImpl tfi, String tenantId, String groupName) {
            this.con = con
            this.tfi = tfi
            this.tenantId = tenantId
            this.groupName = groupName
        }

        String getTenantId() { return tenantId }
        String getGroupName() { return groupName }

        void closeInternal() {
            con.close()
        }


        Statement createStatement() throws SQLException { return con.createStatement() }
        PreparedStatement prepareStatement(String sql) throws SQLException { return con.prepareStatement(sql) }
        CallableStatement prepareCall(String sql) throws SQLException { return con.prepareCall(sql) }
        String nativeSQL(String sql) throws SQLException { return con.nativeSQL(sql) }
        void setAutoCommit(boolean autoCommit) throws SQLException { con.setAutoCommit(autoCommit) }
        boolean getAutoCommit() throws SQLException { return con.getAutoCommit() }
        void commit() throws SQLException { con.commit() }
        void rollback() throws SQLException { con.rollback() }

        void close() throws SQLException {
            // do nothing! see closeInternal
        }

        boolean isClosed() throws SQLException { return con.isClosed() }
        DatabaseMetaData getMetaData() throws SQLException { return con.getMetaData() }
        void setReadOnly(boolean readOnly) throws SQLException { con.setReadOnly(readOnly) }
        boolean isReadOnly() throws SQLException { return con.isReadOnly() }
        void setCatalog(String catalog) throws SQLException { con.setCatalog(catalog) }
        String getCatalog() throws SQLException { return con.getCatalog() }
        void setTransactionIsolation(int level) throws SQLException { con.setTransactionIsolation(level) }
        int getTransactionIsolation() throws SQLException { return con.getTransactionIsolation() }
        SQLWarning getWarnings() throws SQLException { return con.getWarnings() }
        void clearWarnings() throws SQLException { con.clearWarnings() }

        Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
            return con.createStatement(resultSetType, resultSetConcurrency) }
        PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return con.prepareStatement(sql, resultSetType, resultSetConcurrency) }
        CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return con.prepareCall(sql, resultSetType, resultSetConcurrency) }

        Map<String, Class<?>> getTypeMap() throws SQLException { return con.getTypeMap() }
        void setTypeMap(Map<String, Class<?>> map) throws SQLException { con.setTypeMap(map) }
        void setHoldability(int holdability) throws SQLException { con.setHoldability(holdability) }
        int getHoldability() throws SQLException { return con.getHoldability() }
        Savepoint setSavepoint() throws SQLException { return con.setSavepoint() }
        Savepoint setSavepoint(String name) throws SQLException { return con.setSavepoint(name) }
        void rollback(Savepoint savepoint) throws SQLException { con.rollback(savepoint) }
        void releaseSavepoint(Savepoint savepoint) throws SQLException { con.releaseSavepoint(savepoint) }

        Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return con.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability) }
        PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return con.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability) }
        CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return con.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability) }
        PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
            return con.prepareStatement(sql, autoGeneratedKeys) }
        PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
            return con.prepareStatement(sql, columnIndexes) }
        PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
            return con.prepareStatement(sql, columnNames) }

        Clob createClob() throws SQLException { return con.createClob() }
        Blob createBlob() throws SQLException { return con.createBlob() }
        NClob createNClob() throws SQLException { return con.createNClob() }
        SQLXML createSQLXML() throws SQLException { return con.createSQLXML() }
        boolean isValid(int timeout) throws SQLException { return con.isValid(timeout) }
        void setClientInfo(String name, String value) throws SQLClientInfoException { con.setClientInfo(name, value) }
        void setClientInfo(Properties properties) throws SQLClientInfoException { con.setClientInfo(properties) }
        String getClientInfo(String name) throws SQLException { return con.getClientInfo(name) }
        Properties getClientInfo() throws SQLException { return con.getClientInfo() }
        Array createArrayOf(String typeName, Object[] elements) throws SQLException {
            return con.createArrayOf(typeName, elements) }
        Struct createStruct(String typeName, Object[] attributes) throws SQLException {
            return con.createStruct(typeName, attributes) }

        void setSchema(String schema) throws SQLException { con.setSchema(schema) }
        String getSchema() throws SQLException { return con.getSchema() }

        void abort(Executor executor) throws SQLException { con.abort(executor) }
        void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
            con.setNetworkTimeout(executor, milliseconds) }
        int getNetworkTimeout() throws SQLException { return con.getNetworkTimeout() }

        def <T> T unwrap(Class<T> iface) throws SQLException { return con.unwrap(iface) }
        boolean isWrapperFor(Class<?> iface) throws SQLException { return con.isWrapperFor(iface) }

        // Object overrides
        int hashCode() { return con.hashCode() }
        boolean equals(Object obj) { return con.equals(obj) }
        String toString() {
            return new StringBuilder().append("Tenant: ").append(tenantId).append(", Group: ").append(groupName)
                    .append(", Con: ").append(con.toString()).toString()
        }
        /* these don't work, don't think we need them anyway:
        protected Object clone() throws CloneNotSupportedException {
            return new ConnectionWrapper((Connection) con.clone(), tfi, tenantId, groupName) }
        protected void finalize() throws Throwable { con.finalize() }
        */
    }
}
