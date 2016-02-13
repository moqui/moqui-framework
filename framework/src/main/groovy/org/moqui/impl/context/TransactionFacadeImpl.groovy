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
import java.sql.Connection
import java.sql.SQLException

@CompileStatic
class TransactionFacadeImpl implements TransactionFacade {
    protected final static Logger logger = LoggerFactory.getLogger(TransactionFacadeImpl.class)

    protected final ExecutionContextFactoryImpl ecfi

    protected TransactionInternal transactionInternal = null

    protected UserTransaction ut
    protected TransactionManager tm

    protected boolean useTransactionCache = true

    private ThreadLocal<ArrayList<TxStackInfo>> txStackInfoListThread = new ThreadLocal<ArrayList<TxStackInfo>>()

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

        ArrayList<TxStackInfo> txStackInfoList = txStackInfoListThread.get()
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
        Long time = getTxStackInfoList() ? getTxStackInfoList().get(0).transactionBeginStartTime : null
        if (time == null && logger.isTraceEnabled()) logger.trace("The txStackInfoList is empty, transaction in place? [${this.isTransactionInPlace()}]", new BaseException("Empty transactionBeginStackList location"))
        return time
    }

    protected ArrayList<TxStackInfo> getTxStackInfoList() {
        ArrayList<TxStackInfo> list = txStackInfoListThread.get()
        if (list == null) {
            list = new ArrayList<TxStackInfo>(10)
            list.add(new TxStackInfo())
            txStackInfoListThread.set(list)
        }
        return list
    }
    protected TxStackInfo getTxStackInfo() { return getTxStackInfoList().get(0) }


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
        try {
            // logger.warn("================ rollback TX, currentStatus=${getStatus()}")
            if (getStatus() == Status.STATUS_NO_TRANSACTION) {
                logger.warn("Transaction not rolled back, status is STATUS_NO_TRANSACTION")
                return
            }

            logger.warn("Transaction rollback. The rollback was originally caused by: ${causeMessage}", causeThrowable)
            logger.warn("Transaction rollback for [${causeMessage}]. Here is the current location: ", new BaseException("Rollback location"))

            ut.rollback()
        } catch (IllegalStateException e) {
            throw new TransactionException("Could not rollback transaction", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not rollback transaction", e)
        } finally {
            // NOTE: should this really be in finally? maybe we only want to do this if there is a successful rollback
            // to avoid removing things that should still be there, or maybe here in finally it will match up the adds
            // and removes better
            getTxStackInfo().clearCurrent()
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

            Transaction tx = tm.suspend()
            // only do these after successful suspend
            TxStackInfo txStackInfo = new TxStackInfo()
            txStackInfo.suspendedTx = tx
            txStackInfo.suspendedTxLocation = new Exception("Transaction Suspend Location")
            getTxStackInfoList().add(0, txStackInfo)

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
                getTxStackInfoList().remove(0)
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
        Exception transactionBegin = null
        Long transactionBeginStartTime = null
        RollbackInfo rollbackOnlyInfo = null
        Transaction suspendedTx = null
        Exception suspendedTxLocation = null
        protected Map<String, XAResource> activeXaResourceMap = [:]
        protected Map<String, Synchronization> activeSynchronizationMap = [:]
        TransactionCache txCache = null
        Map<String, XAResource> getActiveXaResourceMap() { return activeXaResourceMap }
        Map<String, Synchronization> getActiveSynchronizationMap() { return activeSynchronizationMap }
        void clearCurrent() {
            rollbackOnlyInfo = null
            transactionBegin = null
            transactionBeginStartTime = null
            activeXaResourceMap.clear()
            activeSynchronizationMap.clear()
            txCache = null
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
}
