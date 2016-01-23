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
package org.moqui.impl.entity.orientdb

import com.orientechnologies.orient.core.db.document.ODatabaseDocumentTx
import groovy.transform.CompileStatic
import org.moqui.context.TransactionException
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.transaction.Status
import javax.transaction.Synchronization
import javax.transaction.Transaction
import javax.transaction.TransactionManager
import javax.transaction.xa.XAException

@CompileStatic
class OrientSynchronization implements Synchronization {
    protected final static Logger logger = LoggerFactory.getLogger(OrientSynchronization.class)

    protected ExecutionContextFactoryImpl ecfi
    protected OrientDatasourceFactory odf
    protected ODatabaseDocumentTx database

    protected Transaction tx = null


    OrientSynchronization(ExecutionContextFactoryImpl ecfi, OrientDatasourceFactory odf) {
        this.ecfi = ecfi
        this.odf = odf
    }

    OrientSynchronization enlistOrGet() {
        // logger.warn("========= Enlisting new OrientSynchronization")
        TransactionManager tm = ecfi.getTransactionFacade().getTransactionManager()
        if (tm == null || tm.getStatus() != Status.STATUS_ACTIVE) throw new XAException("Cannot enlist: no transaction manager or transaction not active")
        Transaction tx = tm.getTransaction()
        if (tx == null) throw new XAException(XAException.XAER_NOTA)
        this.tx = tx

        OrientSynchronization existingOxr = (OrientSynchronization) ecfi.getTransactionFacade().getActiveSynchronization("OrientSynchronization")
        if (existingOxr != null) {
            logger.warn("Tried to enlist OrientSynchronization in current transaction but one is already in place, not enlisting", new TransactionException("OrientSynchronization already in place"))
            return existingOxr
        }
        // logger.warn("================= putting and enlisting new OrientSynchronization")
        ecfi.getTransactionFacade().putAndEnlistActiveSynchronization("OrientSynchronization", this)

        this.database = odf.getDatabase()
        this.database.begin()

        return this
    }

    ODatabaseDocumentTx getDatabase() { return database }

    @Override
    void beforeCompletion() { }

    @Override
    void afterCompletion(int status) {
        if (status == Status.STATUS_COMMITTED) {
            try {
                database.commit()
            } catch (Exception e) {
                logger.error("Error in OrientDB commit: ${e.toString()}", e)
                throw new XAException("Error in OrientDB commit: ${e.toString()}")
            } finally {
                database.close()
            }
        } else {
            try {
                database.rollback()
            } catch (Exception e) {
                logger.error("Error in OrientDB rollback: ${e.toString()}", e)
                throw new XAException("Error in OrientDB rollback: ${e.toString()}")
            } finally {
                database.close()
            }
        }
    }

    /* old XAResource stuff:
    protected Xid xid = null
    protected Integer timeout = null
    protected boolean active = false
    protected boolean suspended = false

    @Override
    void start(Xid xid, int flag) throws XAException {
        // logger.warn("========== OrientSynchronization.start(${xid}, ${flag})")
        if (this.active) {
            if (this.xid != null && this.xid.equals(xid)) {
                throw new XAException(XAException.XAER_DUPID);
            } else {
                throw new XAException(XAException.XAER_PROTO);
            }
        }
        if (this.xid != null && !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

        // logger.warn("================= starting OrientSynchronization with xid=${xid}; suspended=${suspended}")
        this.active = true
        this.suspended = false
        this.xid = xid
    }

    @Override
    void end(Xid xid, int flag) throws XAException {
        // logger.warn("========== OrientSynchronization.end(${xid}, ${flag})")
        if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
        if (flag == TMSUSPEND) {
            if (!this.active) throw new XAException(XAException.XAER_PROTO)
            this.suspended = true
            // logger.warn("================= suspending OrientSynchronization with xid=${xid}")
        }
        if (flag == TMSUCCESS || flag == TMFAIL) {
            // allow a success/fail end if TX is suspended without a resume flagged start first
            if (!this.active && !this.suspended) throw new XAException(XAException.XAER_PROTO)
        }

        this.active = false
    }

    @Override
    void forget(Xid xid) throws XAException {
        // logger.warn("========== OrientSynchronization.forget(${xid})")
        if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
        this.xid = null
        if (active) logger.warn("forget() called without end()")
    }

    @Override
    int prepare(Xid xid) throws XAException {
        // logger.warn("========== OrientSynchronization.prepare(${xid}); this.xid=${this.xid}")
        if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
        return XA_OK
    }

    @Override
    Xid[] recover(int flag) throws XAException {
        // logger.warn("========== OrientSynchronization.recover(${flag}); this.xid=${this.xid}")
        return this.xid != null ? [this.xid] : []
    }
    @Override
    boolean isSameRM(XAResource xaResource) throws XAException {
        return xaResource instanceof OrientSynchronization && ((OrientSynchronization) xaResource).xid == this.xid
    }
    @Override
    int getTransactionTimeout() throws XAException { return this.timeout == null ? 0 : this.timeout }
    @Override
    boolean setTransactionTimeout(int seconds) throws XAException {
        this.timeout = (seconds == 0 ? null : seconds)
        return true
    }

    @Override
    void commit(Xid xid, boolean onePhase) throws XAException {
        // logger.warn("========== OrientSynchronization.commit(${xid}, ${onePhase}); this.xid=${this.xid}; this.active=${this.active}")
        if (this.active) logger.warn("commit() called without end()")
        if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

        try {
            database.commit()
        } catch (Exception e) {
            logger.error("Error in OrientDB commit: ${e.toString()}", e)
            throw new XAException("Error in OrientDB commit: ${e.toString()}")
        } finally {
            database.close()
        }

        this.xid = null
        this.active = false
    }

    @Override
    void rollback(Xid xid) throws XAException {
        // logger.warn("========== OrientSynchronization.rollback(${xid}); this.xid=${this.xid}; this.active=${this.active}")
        if (this.active) logger.warn("rollback() called without end()")
        if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

        try {
            database.rollback()
        } catch (Exception e) {
            logger.error("Error in OrientDB rollback: ${e.toString()}", e)
            throw new XAException("Error in OrientDB rollback: ${e.toString()}")
        } finally {
            database.close()
        }

        this.xid = null
        this.active = false
    }
    */
}
