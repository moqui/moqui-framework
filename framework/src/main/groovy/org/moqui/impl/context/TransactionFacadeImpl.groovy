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
import org.moqui.impl.context.ContextJavaUtil.ConnectionWrapper
import org.moqui.impl.context.ContextJavaUtil.RollbackInfo
import org.moqui.impl.context.ContextJavaUtil.TxStackInfo
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

@CompileStatic
class TransactionFacadeImpl implements TransactionFacade {
    protected final static Logger logger = LoggerFactory.getLogger(TransactionFacadeImpl.class)
    protected final static boolean isTraceEnabled = logger.isTraceEnabled()

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

            logger.info("Internal transaction manager initialized: UserTransaction class ${ut?.class?.name}, TransactionManager class ${tm?.class?.name}")
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

        txStackInfoCurThread.remove()
        txStackInfoListThread.remove()
    }

    /** This is called to make sure all transactions, etc are closed for the thread.
     * It commits any active transactions, clears out internal data for the thread, etc.
     */
    void destroyAllInThread() {
        if (isTransactionInPlace()) {
            logger.warn("Thread ending with a transaction in place. Trying to commit.")
            commit()
        }

        LinkedList<TxStackInfo> txStackInfoList = txStackInfoListThread.get()
        if (txStackInfoList) {
            int numSuspended = 0
            for (TxStackInfo txStackInfo in txStackInfoList) {
                Transaction tx = txStackInfo.suspendedTx
                if (tx != null) {
                    resume()
                    commit()
                    numSuspended++
                }
            }
            if (numSuspended > 0) logger.warn("Cleaned up [" + numSuspended + "] suspended transactions.")
        }

        txStackInfoCurThread.remove()
        txStackInfoListThread.remove()
    }

    TransactionInternal getTransactionInternal() { return transactionInternal }
    TransactionManager getTransactionManager() { return tm }
    UserTransaction getUserTransaction() { return ut }
    Long getCurrentTransactionStartTime() {
        TxStackInfo txStackInfo = getTxStackInfo()
        Long time = txStackInfo != null ? (Long) txStackInfo.transactionBeginStartTime : (Long) null
        if (time == null && isTraceEnabled) logger.trace("No transaction begin start time, transaction in place? [${this.isTransactionInPlace()}]", new BaseException("Empty transactionBeginStackList location"))
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
        if (ut == null) return Status.STATUS_NO_TRANSACTION
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
                return "Active (${statusInt})"
            case Status.STATUS_COMMITTED:
                return "Committed (${statusInt})"
            case Status.STATUS_COMMITTING:
                return "Committing (${statusInt})"
            case Status.STATUS_MARKED_ROLLBACK:
                return "Marked Rollback-Only (${statusInt})"
            case Status.STATUS_NO_TRANSACTION:
                return "No Transaction (${statusInt})"
            case Status.STATUS_PREPARED:
                return "Prepared (${statusInt})"
            case Status.STATUS_PREPARING:
                return "Preparing (${statusInt})"
            case Status.STATUS_ROLLEDBACK:
                return "Rolledback (${statusInt})"
            case Status.STATUS_ROLLING_BACK:
                return "Rolling Back (${statusInt})"
            case Status.STATUS_UNKNOWN:
                return "Status Unknown (${statusInt})"
            default:
                return "Not a valid status code (${statusInt})"
        }
    }

    @Override
    boolean isTransactionInPlace() { getStatus() != Status.STATUS_NO_TRANSACTION }

    boolean isTransactionActive() { getStatus() == Status.STATUS_ACTIVE }
    boolean isTransactionOperable() {
        int curStatus = getStatus()
        return curStatus == Status.STATUS_ACTIVE || curStatus == Status.STATUS_NO_TRANSACTION
    }

    @Override
    boolean begin(Integer timeout) {
        int currentStatus = ut.getStatus()
        // logger.warn("================ begin TX, currentStatus=${currentStatus}", new BaseException("beginning transaction at"))

        if (currentStatus == Status.STATUS_ACTIVE) {
            // don't begin, and return false so caller knows we didn't
            return false
        } else if (currentStatus == Status.STATUS_MARKED_ROLLBACK) {
            TxStackInfo txStackInfo = getTxStackInfo()
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

        try {
            // NOTE: Since JTA 1.1 setTransactionTimeout() is local to the thread, so this doesn't need to be synchronized.
            if (timeout != null) ut.setTransactionTimeout(timeout)
            ut.begin()

            TxStackInfo txStackInfo = getTxStackInfo()
            txStackInfo.transactionBegin = new Exception("Tx Begin Placeholder")
            txStackInfo.transactionBeginStartTime = System.currentTimeMillis()
            // logger.warn("================ begin TX, getActiveSynchronizationStack()=${getActiveSynchronizationStack()}")

            if (txStackInfo.txCache != null) logger.warn("Begin TX, tx cache is not null!")
            /* FUTURE: this is an interesting possibility, always use tx cache in read only mode, but currently causes issues (needs more work with cache clear, etc)
            if (useTransactionCache) {
                txStackInfo.txCache = new TransactionCache(ecfi, true)
                registerSynchronization(txStackInfo.txCache)
            }
            */

            return true
        } catch (NotSupportedException e) {
            throw new TransactionException("Could not begin transaction (could be a nesting problem)", e)
        } catch (SystemException e) {
            throw new TransactionException("Could not begin transaction", e)
        } finally {
            // make sure the timeout always gets reset to the default
            if (timeout != null) ut.setTransactionTimeout(0)
        }
    }

    @Override
    void commit(boolean beganTransaction) { if (beganTransaction) this.commit() }

    @Override
    void commit() {
        TxStackInfo txStackInfo = getTxStackInfo()
        try {
            int status = ut.getStatus()
            // logger.warn("================ commit TX, currentStatus=${status}")

            txStackInfo.closeTxConnections()
            if (status == Status.STATUS_MARKED_ROLLBACK) {
                if (txStackInfo.rollbackOnlyInfo != null) {
                    logger.warn("Tried to commit transaction but marked rollback only, doing rollback instead; rollback-only was set here:", txStackInfo.rollbackOnlyInfo.rollbackLocation)
                } else {
                    logger.warn("Tried to commit transaction but marked rollback only, doing rollback instead; no rollback-only info, current location:", new BaseException("Rollback instead of commit location"))
                }
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
            int status = ut.getStatus()
            if (status != Status.STATUS_NO_TRANSACTION && status != Status.STATUS_COMMITTING &&
                    status != Status.STATUS_COMMITTED && status != Status.STATUS_ROLLING_BACK &&
                    status != Status.STATUS_ROLLEDBACK) {
                rollback("Commit failed, rolling back to clean up", null)
            }

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
            txStackInfo.closeTxConnections()

            // logger.warn("================ rollback TX, currentStatus=${getStatus()}")
            if (getStatus() == Status.STATUS_NO_TRANSACTION) {
                logger.warn("Transaction not rolled back, status is STATUS_NO_TRANSACTION")
                return
            }

            if (causeThrowable != null) {
                String causeString = causeThrowable.toString()
                if (causeString.contains("org.eclipse.jetty.io.EofException")) {
                    logger.warn("Transaction rollback. The rollback was originally caused by: ${causeMessage}\n${causeString}")
                } else {
                    logger.warn("Transaction rollback. The rollback was originally caused by: ${causeMessage}", causeThrowable)
                    logger.warn("Transaction rollback for [${causeMessage}]. Here is the current location: ", new BaseException("Rollback location"))
                }
            } else {
                logger.warn("Transaction rollback for [${causeMessage}]. Here is the current location: ", new BaseException("Rollback location"))
            }

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
                    Exception rbLocation = new BaseException("Set rollback only location")

                    if (causeThrowable != null) {
                        String causeString = causeThrowable.toString()
                        if (causeString.contains("org.eclipse.jetty.io.EofException")) {
                            logger.warn("Transaction set rollback only. The rollback was originally caused by: ${causeMessage}\n${causeString}")
                        } else {
                            logger.warn("Transaction set rollback only. The rollback was originally caused by: ${causeMessage}", causeThrowable)
                            logger.warn("Transaction set rollback only for [${causeMessage}]. Here is the current location: ", rbLocation)
                        }
                    } else {
                        logger.warn("Transaction rollback for [${causeMessage}]. Here is the current location: ", rbLocation)
                    }

                    ut.setRollbackOnly()
                    // do this after setRollbackOnly so it only tracks it if rollback-only was actually set
                    getTxStackInfo().rollbackOnlyInfo = new RollbackInfo(causeMessage, causeThrowable, rbLocation)
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
                logger.warn("No transaction in place so not suspending")
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
        if (isTransactionInPlace()) {
            logger.warn("Resume with transaction in place, trying commit to close")
            commit()
        }

        try {
            TxStackInfo txStackInfo = getTxStackInfo()
            if (txStackInfo.suspendedTx != null) {
                tm.resume(txStackInfo.suspendedTx)
                // only do this after successful resume
                popTxStackInfo()
            } else {
                logger.warn("No transaction suspended, so not resuming")
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
            if (tx != null) {
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
            if (tx != null) {
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
        if (!useTransactionCache) return
        TxStackInfo txStackInfo = getTxStackInfo()
        if (txStackInfo.txCache == null) {
            if (isTraceEnabled) {
                StringBuilder infoString = new StringBuilder()
                infoString.append("Initializing TX cache at:")
                for (infoAei in ecfi.getEci().artifactExecutionFacade.getStack()) infoString.append(infoAei.getName())
                logger.trace(infoString.toString())
            // } else if (logger.isInfoEnabled()) {
            //     logger.info("Initializing TX cache in ${ecfi.getEci().getArtifactExecutionImpl().peek()?.getName()}")
            }

            if (tm == null || tm.getStatus() != Status.STATUS_ACTIVE) throw new XAException("Cannot enlist: no transaction manager or transaction not active")

            TransactionCache txCache = new TransactionCache(this.ecfi, false)
            txStackInfo.txCache = txCache
            registerSynchronization(txCache)
        } else if (txStackInfo.txCache.isReadOnly()) {
            if (isTraceEnabled) logger.trace("Making TX cache write through in ${ecfi.getEci().artifactExecutionFacade.peek()?.getName()}")
            txStackInfo.txCache.makeWriteThrough()
            // doing on read only init: registerSynchronization(txStackInfo.txCache)
        }
    }
    @Override
    boolean isTransactionCacheActive() {
        TxStackInfo txStackInfo = getTxStackInfo()
        return txStackInfo.txCache != null && !txStackInfo.txCache.isReadOnly()
    }
    TransactionCache getTransactionCache() { return getTxStackInfo().txCache }
    @Override
    void flushAndDisableTransactionCache() {
        TxStackInfo txStackInfo = getTxStackInfo()
        if (txStackInfo.txCache != null) {
            txStackInfo.txCache.makeReadOnly()
            // would be safer to flush and remove it completely, but trying just switching to read only mode
            // txStackInfo.txCache.flushCache(true)
            // txStackInfo.txCache = null
        }
    }

    Connection getTxConnection(String groupName) {
        if (!useConnectionStash) return null

        String conKey = groupName
        TxStackInfo txStackInfo = getTxStackInfo()
        ConnectionWrapper con = (ConnectionWrapper) txStackInfo.txConByGroup.get(conKey)
        if (con == null) return null

        if (con.isClosed()) {
            txStackInfo.txConByGroup.remove(conKey)
            logger.info("Stashed connection closed elsewhere for group ${groupName}: ${con.toString()}")
            return null
        }
        if (!isTransactionActive()) {
            con.close()
            txStackInfo.txConByGroup.remove(conKey)
            logger.info("Stashed connection found but transaction is not active (${getStatusString()}) for group ${groupName}: ${con.toString()}")
            return null
        }
        return con
    }
    Connection stashTxConnection(String groupName, Connection con) {
        if (!useConnectionStash || !isTransactionActive()) return con

        TxStackInfo txStackInfo = getTxStackInfo()
        // if transactionBeginStartTime is null we didn't begin the transaction, so can't count on commit/rollback through this
        if (txStackInfo.transactionBeginStartTime == null) return con

        String conKey = groupName
        ConnectionWrapper existing = (ConnectionWrapper) txStackInfo.txConByGroup.get(conKey)
        try {
            if (existing != null && !existing.isClosed()) existing.closeInternal()
        } catch (Throwable t) {
            logger.error("Error closing previously stashed connection for group ${groupName}: ${existing.toString()}", t)
        }
        ConnectionWrapper newCw = new ConnectionWrapper(con, this, groupName)
        txStackInfo.txConByGroup.put(conKey, newCw)
        return newCw
    }


    // ========== Initialize/Populate Methods ==========

    void populateTransactionObjectsJndi() {
        MNode transactionJndiNode = this.ecfi.getConfXmlRoot().first("transaction-facade").first("transaction-jndi")
        String userTxJndiName = transactionJndiNode.attribute("user-transaction-jndi-name")
        String txMgrJndiName = transactionJndiNode.attribute("transaction-manager-jndi-name")

        MNode serverJndi = this.ecfi.getConfXmlRoot().first("transaction-facade").first("server-jndi")

        try {
            InitialContext ic
            if (serverJndi != null) {
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

        if (this.ut == null) logger.error("Could not find UserTransaction with name [${userTxJndiName}] in JNDI server [${serverJndi ? serverJndi.attribute("context-provider-url") : "default"}].")
        if (this.tm == null) logger.error("Could not find TransactionManager with name [${txMgrJndiName}] in JNDI server [${serverJndi ? serverJndi.attribute("context-provider-url") : "default"}].")
    }
}
