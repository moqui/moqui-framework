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
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.util.MNode

import javax.transaction.Synchronization
import javax.transaction.xa.XAException
import javax.transaction.Transaction
import javax.transaction.Status
import javax.transaction.TransactionManager

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ServiceEcaRule {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceEcaRule.class)

    protected MNode secaNode
    protected String location

    protected XmlAction condition = null
    protected XmlAction actions = null

    ServiceEcaRule(ExecutionContextFactoryImpl ecfi, MNode secaNode, String location) {
        this.secaNode = secaNode
        this.location = location

        // prep condition
        if (secaNode.hasChild("condition") && secaNode.first("condition").children) {
            // the script is effectively the first child of the condition element
            condition = new XmlAction(ecfi, secaNode.first("condition").children.get(0), location + ".condition")
        }
        // prep actions
        if (secaNode.hasChild("actions")) {
            actions = new XmlAction(ecfi, secaNode.first("actions"), location + ".actions")
        }
    }

    String getServiceName() { return secaNode.attribute("service") }
    String getWhen() { return secaNode.attribute("when") }
    // Node getSecaNode() { return secaNode }

    void runIfMatches(String serviceName, Map<String, Object> parameters, Map<String, Object> results, String when, ExecutionContextImpl ec) {
        // see if we match this event and should run
        if (serviceName != (secaNode.attribute("service")?.replace("#", ""))) return
        if (when != secaNode.attribute("when")) return
        if (ec.getMessage().hasError() && secaNode.attribute("run-on-error") != "true") return

        standaloneRun(parameters, results, ec)
    }

    void standaloneRun(Map<String, Object> parameters, Map<String, Object> results, ExecutionContextImpl ec) {
        try {
            ec.context.push()
            ec.context.putAll(parameters)
            ec.context.put("parameters", parameters)
            if (results != null) {
                ec.context.putAll(results)
                ec.context.put("results", results)
            }

            // run the condition and if passes run the actions
            boolean conditionPassed = true
            if (condition) conditionPassed = condition.checkCondition(ec)
            if (conditionPassed) {
                if (actions) actions.run(ec)
            }
        } finally {
            ec.context.pop()
        }
    }

    void registerTx(String serviceName, Map<String, Object> parameters, Map<String, Object> results, ExecutionContextFactoryImpl ecfi) {
        if (serviceName != (secaNode.attribute("service")?.replace("#", ""))) return
        def sxr = new SecaSynchronization(this, parameters, results, ecfi)
        sxr.enlist()
    }

    @Override
    String toString() { return secaNode.toString() }

    static class SecaSynchronization implements Synchronization {
        protected final static Logger logger = LoggerFactory.getLogger(SecaSynchronization.class)

        protected ExecutionContextFactoryImpl ecfi
        protected ServiceEcaRule sec
        protected Map<String, Object> parameters
        protected Map<String, Object> results

        protected Transaction tx = null

        SecaSynchronization(ServiceEcaRule sec, Map<String, Object> parameters, Map<String, Object> results, ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi
            this.sec = sec
            this.parameters = new HashMap(parameters)
            this.results = new HashMap(results)
        }

        void enlist() {
            TransactionManager tm = ecfi.transactionFacade.getTransactionManager()
            if (tm == null || tm.getStatus() != Status.STATUS_ACTIVE) throw new XAException("Cannot enlist: no transaction manager or transaction not active")

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
                if (sec.secaNode.attribute("when") == "tx-commit") runInThreadAndTx()
            } else {
                if (sec.secaNode.attribute("when") == "tx-rollback") runInThreadAndTx()
            }
        }

        void runInThreadAndTx() {
            Thread thread = new Thread() {
                @Override
                public void run() {
                    boolean beganTransaction = ecfi.transactionFacade.begin(null)
                    try {
                        sec.standaloneRun(parameters, results, ecfi.getEci())
                    } catch (Throwable t) {
                        logger.error("Error running Service TX ECA rule", t)
                        ecfi.transactionFacade.rollback(beganTransaction, "Error running Service TX ECA rule", t)
                    } finally {
                        if (beganTransaction && ecfi.transactionFacade.isTransactionInPlace())
                            ecfi.transactionFacade.commit()
                    }
                }
            };
            thread.start();
        }
    }
}
