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
package org.moqui.impl.service

import groovy.transform.CompileStatic
import org.moqui.BaseArtifactException
import org.moqui.service.ServiceException

import javax.transaction.Synchronization
import javax.transaction.Transaction
import javax.transaction.xa.XAException
import javax.transaction.TransactionManager
import javax.transaction.Status

import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.service.ServiceCallSpecial

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ServiceCallSpecialImpl extends ServiceCallImpl implements ServiceCallSpecial {

    ServiceCallSpecialImpl(ServiceFacadeImpl sfi) {
        super(sfi)
    }

    @Override
    ServiceCallSpecial name(String serviceName) { serviceNameInternal(serviceName); return this }
    @Override
    ServiceCallSpecial name(String v, String n) { serviceNameInternal(null, v, n); return this }
    @Override
    ServiceCallSpecial name(String p, String v, String n) { serviceNameInternal(p, v, n); return this }

    @Override
    ServiceCallSpecial parameters(Map<String, ?> map) { parameters.putAll(map); return this }
    @Override
    ServiceCallSpecial parameter(String name, Object value) { parameters.put(name, value); return this }

    @Override
    void registerOnCommit() {
        if (getServiceDefinition() == null && !isEntityAutoPattern()) throw new ServiceException("Could not find service with name [${getServiceName()}]")

        ServiceSynchronization sxr = new ServiceSynchronization(this, sfi.ecfi, true)
        sxr.enlist()
    }

    @Override
    void registerOnRollback() {
        if (getServiceDefinition() == null && !isEntityAutoPattern()) throw new ServiceException("Could not find service with name [${getServiceName()}]")

        ServiceSynchronization sxr = new ServiceSynchronization(this, sfi.ecfi, false)
        sxr.enlist()
    }

    static class ServiceSynchronization implements Synchronization {
        protected final static Logger logger = LoggerFactory.getLogger(ServiceSynchronization.class)

        protected ExecutionContextFactoryImpl ecfi
        protected String serviceName
        protected Map<String, Object> parameters
        protected boolean runOnCommit

        protected Transaction tx = null

        ServiceSynchronization(ServiceCallSpecialImpl scsi, ExecutionContextFactoryImpl ecfi, boolean runOnCommit) {
            this.ecfi = ecfi
            this.serviceName = scsi.getServiceName()
            this.parameters = new HashMap(scsi.parameters)
            this.runOnCommit = runOnCommit
        }

        void enlist() {
            TransactionManager tm = ecfi.transactionFacade.getTransactionManager()
            if (tm == null && tm.getStatus() != Status.STATUS_ACTIVE) throw new XAException("Cannot enlist: no transaction manager or transaction not active")

            Transaction tx = tm.getTransaction();
            if (tx == null) throw new XAException(XAException.XAER_NOTA)

            this.tx = tx
            tx.registerSynchronization(this)
        }

        @Override
        void beforeCompletion() { }

        @Override
        void afterCompletion(int status) {
            if (status == Status.STATUS_COMMITTED) {
                if (runOnCommit) ecfi.serviceFacade.async().name(this.serviceName).parameters(this.parameters).call()
            } else {
                if (!runOnCommit) ecfi.serviceFacade.async().name(this.serviceName).parameters(this.parameters).call()
            }
        }

        /* Old XAResource (and Thread) approach:

        protected Xid xid = null
        protected Integer timeout = null
        protected boolean active = false
        protected boolean suspended = false

        @Override
        void start(Xid xid, int flag) throws XAException {
            if (this.active) {
                if (this.xid != null && this.xid.equals(xid)) {
                    throw new XAException(XAException.XAER_DUPID);
                } else {
                    throw new XAException(XAException.XAER_PROTO);
                }
            }
            if (this.xid != null && !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

            this.active = true
            this.suspended = false
            this.xid = xid

            // start a thread with this object to do something on timeout
            this.setName("ServiceSynchronizationThread")
            this.setDaemon(true)
            this.start()
        }

        @Override
        void end(Xid xid, int flag) throws XAException {
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
            if (flag == TMSUSPEND) {
                if (!this.active) throw new XAException(XAException.XAER_PROTO)
                this.suspended = true
            }
            if (flag == TMSUCCESS || flag == TMFAIL) {
                // allow a success/fail end if TX is suspended without a resume flagged start first
                if (!this.active && !this.suspended) throw new XAException(XAException.XAER_PROTO)
            }
            this.active = false
        }

        @Override
        void forget(Xid xid) throws XAException {
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
            this.xid = null
            if (active) logger.warn("forget() called without end()")
        }

        @Override
        int prepare(Xid xid) throws XAException {
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)
            return XA_OK
        }

        @Override
        Xid[] recover(int flag) throws XAException { return this.xid != null ? [this.xid] : [] }
        @Override
        boolean isSameRM(XAResource xaResource) throws XAException { return xaResource == this }
        @Override
        int getTransactionTimeout() throws XAException { return this.timeout == null ? 0 : this.timeout }
        @Override
        boolean setTransactionTimeout(int seconds) throws XAException {
            this.timeout = (seconds == 0 ? null : seconds)
            return true
        }

        @Override
        void commit(Xid xid, boolean onePhase) throws XAException {
            if (this.active) logger.warn("commit() called without end()")
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

            if (runOnCommit) ecfi.serviceFacade.async().name(this.serviceName).parameters(this.parameters).call()

            this.xid = null
            this.active = false
        }

        @Override
        void rollback(Xid xid) throws XAException {
            if (this.active) logger.warn("rollback() called without end()")
            if (this.xid == null || !this.xid.equals(xid)) throw new XAException(XAException.XAER_NOTA)

            if (!runOnCommit) ecfi.serviceFacade.async().name(this.serviceName).parameters(this.parameters).call()

            this.xid = null
            this.active = false
        }

        @Override
        void run() {
            try {
                if (timeout != null) {
                    // sleep until the transaction times out
                    sleep(timeout.intValue() * 1000)

                    if (active) {
                        String statusString = ecfi.transactionFacade.getStatusString()
                        logger.warn("Transaction timeout [${timeout}] status [${statusString}] xid [${this.xid}], service [${serviceName}] did NOT run")

                        // NOTE: what to do, if anything, when the we timeout and the service hasn't been run?
                    }
                }
            } catch (InterruptedException e) {
                logger.warn("Service Call Special Interrupted", e)
            } catch (Throwable t) {
                logger.warn("Service Call Special Error", t)
            }
        }
        */
    }
}
