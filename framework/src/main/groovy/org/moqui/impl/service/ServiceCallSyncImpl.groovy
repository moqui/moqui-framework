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
import org.moqui.BaseException
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.AuthenticationRequiredException
import org.moqui.context.TransactionException
import org.moqui.context.TransactionFacade
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.TransactionFacadeImpl
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.service.runner.EntityAutoServiceRunner
import org.moqui.service.ServiceCallSync
import org.moqui.service.ServiceException
import org.moqui.util.MNode

import java.sql.Timestamp

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ServiceCallSyncImpl extends ServiceCallImpl implements ServiceCallSync {
    private final static Logger logger = LoggerFactory.getLogger(ServiceCallSyncImpl.class)
    private final static boolean traceEnabled = logger.isTraceEnabled()

    protected boolean ignoreTransaction = false
    protected boolean requireNewTransaction = false
    protected boolean separateThread = false
    protected Boolean useTransactionCache = (Boolean) null
    /* not supported by Atomikos/etc right now, consider for later: protected int transactionIsolation = -1 */
    protected Integer transactionTimeout = (Integer) null

    protected boolean ignorePreviousError = false
    protected boolean multi = false
    protected boolean disableAuthz = false

    ServiceCallSyncImpl(ServiceFacadeImpl sfi) {
        super(sfi)
    }

    @Override
    ServiceCallSync name(String serviceName) { serviceNameInternal(serviceName); return this }
    @Override
    ServiceCallSync name(String v, String n) { serviceNameInternal(null, v, n); return this }
    @Override
    ServiceCallSync name(String p, String v, String n) { serviceNameInternal(p, v, n); return this }

    @Override
    ServiceCallSync parameters(Map<String, ?> map) { if (map != null) { parameters.putAll(map) }; return this }
    @Override
    ServiceCallSync parameter(String name, Object value) { parameters.put(name, value); return this }

    @Override
    ServiceCallSync ignoreTransaction(boolean it) { this.ignoreTransaction = it; return this }
    @Override
    ServiceCallSync requireNewTransaction(boolean rnt) { this.requireNewTransaction = rnt; return this }
    @Override
    ServiceCallSync separateThread(boolean st) { this.separateThread = st; return this }
    @Override
    ServiceCallSync useTransactionCache(boolean utc) { this.useTransactionCache = utc; return this }
    @Override
    ServiceCallSync transactionTimeout(int timeout) { this.transactionTimeout = timeout; return this }

    @Override
    ServiceCallSync ignorePreviousError(boolean ipe) { this.ignorePreviousError = ipe; return this }
    @Override
    ServiceCallSync multi(boolean mlt) { this.multi = mlt; return this }
    @Override
    ServiceCallSync disableAuthz() { disableAuthz = true; return this }

    /* not supported by Atomikos/etc right now, consider for later:
    @Override
    ServiceCallSync transactionIsolation(int ti) { this.transactionIsolation = ti; return this }
    */

    @Override
    Map<String, Object> call() {
        ServiceDefinition sd = getServiceDefinition()
        ExecutionContextFactoryImpl ecfi = sfi.ecfi
        ExecutionContextImpl eci = ecfi.getEci()

        boolean enableAuthz = disableAuthz ? !eci.artifactExecutionFacade.disableAuthz() : false
        try {
            if (multi) {
                ArrayList<String> inParameterNames = null
                if (sd != null) {
                    inParameterNames = sd.getInParameterNames()
                } else if (isEntityAutoPattern()) {
                    EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(noun)
                    if (ed != null) inParameterNames = ed.getAllFieldNames()
                }
                int inParameterNamesSize = inParameterNames.size()
                // run all service calls in a single transaction for multi form submits, ie all succeed or fail together
                boolean beganTransaction = eci.transactionFacade.begin(null)
                try {
                    for (int i = 0; ; i++) {
                        if (("true".equals(parameters.get("_useRowSubmit")) || "true".equals(parameters.get("_useRowSubmit_" + i)))
                                && !"true".equals(parameters.get("_rowSubmit_" + i))) continue
                        Map<String, Object> currentParms = new HashMap()
                        for (int paramIndex = 0; paramIndex < inParameterNamesSize; paramIndex++) {
                            String ipn = (String) inParameterNames.get(paramIndex)
                            String key = ipn + "_" + i
                            if (parameters.containsKey(key)) currentParms.put(ipn, parameters.get(key))
                        }
                        // if the map stayed empty we have no parms, so we're done
                        if (currentParms.size() == 0) break
                        // now that we have checked the per-row parameters, add in others available
                        for (int paramIndex = 0; paramIndex < inParameterNamesSize; paramIndex++) {
                            String ipn = (String) inParameterNames.get(paramIndex)
                            if (!currentParms.get(ipn) && parameters.get(ipn)) currentParms.put(ipn, parameters.get(ipn))
                        }
                        // call the service, ignore the result...
                        callSingle(currentParms, sd, eci)
                        // ... and break if there are any errors
                        if (eci.messageFacade.hasError()) break
                    }
                } catch (Throwable t) {
                    eci.transactionFacade.rollback(beganTransaction, "Uncaught error running service [${sd.serviceName}] in multi mode", t)
                    throw t
                } finally {
                    if (eci.transactionFacade.isTransactionInPlace()) {
                        if (eci.messageFacade.hasError()) {
                            eci.transactionFacade.rollback(beganTransaction, "Error message found running service [${sd.serviceName}] in multi mode", null)
                        } else {
                            eci.transactionFacade.commit(beganTransaction)
                        }
                    }
                }
            } else {
                if (separateThread) {
                    Thread serviceThread = null
                    Map<String, Object> resultMap = null
                    try {
                        serviceThread = Thread.start('ServiceSeparateThread', {
                            ecfi.useExecutionContextInThread(eci)
                            resultMap = callSingle(parameters, sd, eci)
                        })
                    } finally {
                        if (serviceThread != null) serviceThread.join()
                    }
                    return resultMap
                } else {
                    return callSingle(parameters, sd, eci)
                }
            }
        } finally {
            if (enableAuthz) eci.artifactExecutionFacade.enableAuthz()
        }
    }

    Map<String, Object> callSingle(Map<String, Object> currentParameters, ServiceDefinition sd, ExecutionContextImpl eci) {
        if (ignorePreviousError) eci.messageFacade.pushErrors()
        // NOTE: checking this here because service won't generally run after input validation, etc anyway
        if (eci.messageFacade.hasError()) {
            logger.warn("Found error(s) before service [${serviceName}], so not running service. Errors: ${eci.messageFacade.getErrorsString()}")
            return (Map<String, Object>) null
        }
        if (!requireNewTransaction && eci.transactionFacade.getStatus() == 1) {
            logger.warn("Transaction marked for rollback, not running service [${serviceName}]. Errors: ${eci.messageFacade.getErrorsString()}")
            if (ignorePreviousError) eci.messageFacade.popErrors()
            return (Map<String, Object>) null
        }

        if (traceEnabled) logger.trace("Calling service [${serviceName}] initial input: ${currentParameters}")

        // get these before cleaning up the parameters otherwise will be removed
        boolean hasAuthUsername = currentParameters.containsKey("authUsername")
        String userId = (String) null
        String password = (String) null
        String tenantId = (String) null
        if (hasAuthUsername) {
            userId = (String) currentParameters.authUsername
            password = (String) currentParameters.authPassword
            tenantId = (String) currentParameters.authTenantId
        } else if (currentParameters.containsKey("authUserAccount")) {
            Map authUserAccount = (Map) currentParameters.authUserAccount
            userId = authUserAccount.userId ?: currentParameters.authUsername
            password = authUserAccount.currentPassword ?: currentParameters.authPassword
            tenantId = (String) currentParameters.authTenantId
        }

        String serviceType = sd != null ? sd.serviceType : "entity-implicit"
        ArrayList<ServiceEcaRule> secaRules = sfi.secaRules(serviceNameNoHash)
        boolean hasSecaRules = secaRules != null && secaRules.size() > 0

        // in-parameter validation
        if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, null, "pre-validate", secaRules, eci)
        if (sd != null) sd.convertValidateCleanParameters(currentParameters, eci)
        // if error(s) in parameters, return now with no results
        if (eci.messageFacade.hasError()) {
            StringBuilder errMsg = new StringBuilder("Found error(s) when validating input parameters for service [${serviceName}], so not running service. Errors: ${eci.messageFacade.getErrorsString()}; the artifact stack is:\n")
            for (ArtifactExecutionInfo stackItem in eci.artifactExecutionFacade.stack) {
                errMsg.append(stackItem.toString()).append('\n')
            }
            logger.warn(errMsg.toString())
            if (ignorePreviousError) eci.messageFacade.popErrors()
            return (Map<String, Object>) null
        }

        boolean userLoggedIn = false

        // always try to login the user if parameters are specified
        if (userId != null && password != null && userId.length() > 0 && password.length() > 0) {
            userLoggedIn = eci.getUser().loginUser(userId, password, tenantId)
            // if user was not logged in we should already have an error message in place so just return
            if (!userLoggedIn) return (Map<String, Object>) null
        }
        if (sd != null && "true".equals(sd.authenticate) && eci.userFacade.getUsername() == null && !eci.userFacade.getLoggedInAnonymous()) {
            if (ignorePreviousError) eci.messageFacade.popErrors()
            throw new AuthenticationRequiredException("User must be logged in to call service ${serviceName}")
        }

        // pre authentication and authorization SECA rules
        if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, null, "pre-auth", secaRules, eci)

        // push service call artifact execution, checks authz too
        // NOTE: don't require authz if the service def doesn't authenticate
        // NOTE: if no sd then requiresAuthz is false, ie let the authz get handled at the entity level (but still put
        //     the service on the stack)
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(serviceName, ArtifactExecutionInfo.AT_SERVICE,
                            ServiceDefinition.getVerbAuthzActionEnum(verb), serviceType).setParameters(currentParameters)
        eci.artifactExecutionFacade.pushInternal(aei, (sd != null && "true".equals(sd.authenticate)))

        // must be done after the artifact execution push so that AEII object to set anonymous authorized is in place
        boolean loggedInAnonymous = false
        if (sd != null && "anonymous-all".equals(sd.authenticate)) {
            eci.artifactExecutionFacade.setAnonymousAuthorizedAll()
            loggedInAnonymous = ((UserFacadeImpl) eci.getUser()).loginAnonymousIfNoUser()
        } else if (sd != null && "anonymous-view".equals(sd.authenticate)) {
            eci.artifactExecutionFacade.setAnonymousAuthorizedView()
            loggedInAnonymous = ((UserFacadeImpl) eci.getUser()).loginAnonymousIfNoUser()
        }

        if (sd == null) {
            if (sfi.isEntityAutoPattern(path, verb, noun)) {
                try {
                    Map result = runImplicitEntityAuto(currentParameters, secaRules, eci)

                    // double runningTimeMillis = (System.nanoTime() - startTimeNanos)/1E6
                    // if (traceEnabled) logger.trace("Finished call to service [${serviceName}] in ${(runningTimeMillis)/1000} seconds")

                    return result
                } finally {
                    eci.artifactExecutionFacade.pop(aei)
                    if (ignorePreviousError) eci.messageFacade.popErrors()
                }
            } else {
                logger.info("No service with name ${serviceName}, isEntityAutoPattern=${isEntityAutoPattern()}, path=${path}, verb=${verb}, noun=${noun}, noun is entity? ${((EntityFacadeImpl) eci.getEntity()).isEntityDefined(noun)}")
                eci.artifactExecutionFacade.pop(aei)
                if (ignorePreviousError) eci.messageFacade.popErrors()
                throw new ServiceException("Could not find service with name [${serviceName}]")
            }
        }

        if ("interface".equals(serviceType)) {
            eci.artifactExecutionFacade.pop(aei)
            if (ignorePreviousError) eci.messageFacade.popErrors()
            throw new ServiceException("Cannot run interface service [${serviceName}]")
        }

        ServiceRunner sr = sfi.getServiceRunner(serviceType)
        if (sr == null) {
            eci.artifactExecutionFacade.pop(aei)
            if (ignorePreviousError) eci.messageFacade.popErrors()
            throw new ServiceException("Could not find service runner for type [${serviceType}] for service [${serviceName}]")
        }

        // start with the settings for the default: use-or-begin
        boolean pauseResumeIfNeeded = false
        boolean beginTransactionIfNeeded = true
        if (ignoreTransaction || sd.txIgnore) beginTransactionIfNeeded = false
        if (requireNewTransaction || sd.txForceNew) pauseResumeIfNeeded = true

        TransactionFacade tf = eci.transactionFacade
        boolean suspendedTransaction = false
        Map<String, Object> result = (Map<String, Object>) null
        try {
            // if error in auth or for other reasons, return now with no results
            if (eci.messageFacade.hasError()) {
                logger.warn("Found error(s) when checking authc for service [${serviceName}], so not running service. Errors: ${eci.messageFacade.getErrorsString()}; the artifact stack is:\n ${eci.artifactExecution.stack}")
                return null
            }

            if (pauseResumeIfNeeded && tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = beginTransactionIfNeeded ?
                    tf.begin(transactionTimeout != null ? transactionTimeout : sd.txTimeout) : false
            if (sd.noTxCache) {
                tf.flushAndDisableTransactionCache()
            } else {
                if (useTransactionCache != null ? useTransactionCache.booleanValue() : sd.txUseCache) tf.initTransactionCache()
            }
            try {
                // handle sd.serviceNode."@semaphore"; do this after local transaction created, etc.
                if (sd.hasSemaphore) checkAddSemaphore(eci, currentParameters)

                if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, null, "pre-service", secaRules, eci)

                if (traceEnabled) logger.trace("Calling service [${serviceName}] pre-call input: ${currentParameters}")

                try {
                    // run the service through the ServiceRunner
                    result = sr.runService(sd, currentParameters)
                } finally {
                    if (hasSecaRules) sfi.registerTxSecaRules(serviceNameNoHash, currentParameters, result, secaRules)
                }

                // post-service SECA rules
                if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, result, "post-service", secaRules, eci)
                // registered callbacks, no Throwable
                sfi.callRegisteredCallbacks(serviceName, currentParameters, result)
                // if we got any errors added to the message list in the service, rollback for that too
                if (eci.messageFacade.hasError()) {
                    tf.rollback(beganTransaction, "Error running service [${serviceName}] (message): " + eci.messageFacade.getErrorsString(), null)
                }

                if (traceEnabled) logger.trace("Calling service [${serviceName}] result: ${result}")
            } catch (ArtifactAuthorizationException e) {
                // this is a local call, pass certain exceptions through
                throw e
            } catch (Throwable t) {
                BaseException.filterStackTrace(t)
                // registered callbacks with Throwable
                sfi.callRegisteredCallbacksThrowable(serviceName, currentParameters, t)
                // rollback the transaction
                tf.rollback(beganTransaction, "Error running service [${serviceName}] (Throwable)", t)
                logger.warn("Error running service [${serviceName}] (Throwable)", t)
                // add all exception messages to the error messages list
                eci.messageFacade.addError(t.getMessage())
                Throwable parent = t.getCause()
                while (parent != null) {
                    eci.messageFacade.addError(parent.getMessage())
                    parent = parent.getCause()
                }
            } finally {
                // clear the semaphore
                if (sd.hasSemaphore) clearSemaphore(eci, currentParameters)

                try {
                    if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
                } catch (Throwable t) {
                    logger.warn("Error committing transaction for service [${serviceName}]", t)
                    // add all exception messages to the error messages list
                    eci.messageFacade.addError(t.getMessage())
                    Throwable parent = t.getCause()
                    while (parent != null) {
                        eci.messageFacade.addError(parent.getMessage())
                        parent = parent.getCause()
                    }
                }
                if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, result, "post-commit", secaRules, eci)
            }

            return result
        } catch (TransactionException e) {
            throw e
        } finally {
            try {
                if (suspendedTransaction) tf.resume()
            } catch (Throwable t) {
                logger.error("Error resuming parent transaction after call to service [${serviceName}]", t)
            }
            try {
                if (userLoggedIn) eci.getUser().logoutUser()
            } catch (Throwable t) {
                logger.error("Error logging out user after call to service [${serviceName}]", t)
            }
            if (loggedInAnonymous) ((UserFacadeImpl) eci.getUser()).logoutAnonymousOnly()

            // all done so pop the artifact info
            eci.artifactExecutionFacade.pop(aei)
            // restore error messages if needed
            if (ignorePreviousError) eci.messageFacade.popErrors()

            if (traceEnabled) logger.trace("Finished call to service ${serviceName}" + (eci.messageFacade.hasError() ? " with ${eci.messageFacade.getErrors().size() + eci.messageFacade.getValidationErrors().size()} error messages" : ", was successful"))
        }
    }

    protected void clearSemaphore(ExecutionContextImpl eci, Map<String, Object> currentParameters) {
        if (!sd.hasSemaphore) return

        String semParameter = sd.serviceNode.attribute('semaphore-parameter')
        String parameterValue = semParameter ? currentParameters.get(semParameter) as String ?: '_NULL_' : '_NA_'

        eci.transactionFacade.runRequireNew(null, "Error in clear service semaphore", {
            boolean authzDisabled = eci.artifactExecutionFacade.disableAuthz()
            try {
                eci.entity.makeValue("moqui.service.semaphore.ServiceParameterSemaphore")
                        .set('serviceName', serviceName).set('parameterValue', parameterValue).delete()
            } finally {
                if (!authzDisabled) eci.artifactExecutionFacade.enableAuthz()
            }
        })
    }

    protected void checkAddSemaphore(ExecutionContextImpl eci, Map<String, Object> currentParameters) {
        if (!sd.hasSemaphore) return

        MNode serviceNode = sd.serviceNode
        String semaphore = serviceNode.attribute('semaphore')

        String semParameter = serviceNode.attribute('semaphore-parameter')
        String parameterValue = semParameter ? (currentParameters.get(semParameter) ?: '_NULL_') : null

        long ignoreMillis = ((serviceNode.attribute('semaphore-ignore') ?: "3600") as Long) * 1000
        long sleepTime = ((serviceNode.attribute('semaphore-sleep') ?: "5") as Long) * 1000
        long timeoutTime = ((serviceNode.attribute('semaphore-timeout') ?: "120") as Long) * 1000
        long currentTime = System.currentTimeMillis()
        String lockThreadName = Thread.currentThread().getName()

        eci.transactionFacade.runRequireNew(null, "Error in check/add service semaphore", {
            boolean authzDisabled = eci.artifactExecutionFacade.disableAuthz()
            try {
                EntityValue serviceSemaphore = eci.entity.find("moqui.service.semaphore.ServiceParameterSemaphore")
                        .condition("serviceName", serviceName).condition("parameterValue", parameterValue).useCache(false).one()

                if (serviceSemaphore) {
                    Timestamp lockTime = serviceSemaphore.getTimestamp("lockTime")
                    if (currentTime > (lockTime.getTime() + ignoreMillis)) {
                        eci.entity.makeValue("moqui.service.semaphore.ServiceParameterSemaphore")
                                .set('serviceName', serviceName).set('parameterValue', parameterValue).delete()
                        serviceSemaphore = null
                    }
                }
                if (serviceSemaphore) {
                    if (semaphore == "fail") {
                        throw new ServiceException("An instance of service [${serviceName}] with parameter value [${parameterValue}] is already running (thread [${serviceSemaphore.lockThread}], locked at ${serviceSemaphore.lockTime}) and it is setup to fail on semaphore conflict.")
                    } else {
                        boolean semaphoreCleared = false
                        while (System.currentTimeMillis() < (currentTime + timeoutTime)) {
                            Thread.sleep(sleepTime)
                            if (eci.entity.find("moqui.service.semaphore.ServiceParameterSemaphore")
                                    .condition("serviceName", serviceName).condition("parameterValue", parameterValue)
                                    .useCache(false).one() == null) {
                                semaphoreCleared = true
                                break
                            }
                        }
                        if (!semaphoreCleared) {
                            throw new ServiceException("An instance of service [${serviceName}] with parameter value [${parameterValue}] is already running (thread [${serviceSemaphore.lockThread}], locked at ${serviceSemaphore.lockTime}) and it is setup to wait on semaphore conflict, but the semaphore did not clear in ${timeoutTime/1000} seconds.")
                        }
                    }
                }

                // if we got to here the semaphore didn't exist or has cleared, so create one
                eci.entity.makeValue("moqui.service.semaphore.ServiceParameterSemaphore")
                        .set('serviceName', serviceName).set('parameterValue', parameterValue)
                        .set('lockThread', lockThreadName).set('lockTime', new Timestamp(currentTime)).create()
            } finally {
                if (!authzDisabled) eci.artifactExecutionFacade.enableAuthz()
            }
        })
    }

    protected Map<String, Object> runImplicitEntityAuto(Map<String, Object> currentParameters, ArrayList<ServiceEcaRule> secaRules, ExecutionContextImpl eci) {
        // NOTE: no authentication, assume not required for this; security settings can override this and require
        //     permissions, which will require authentication
        // done in calling method: sfi.runSecaRules(serviceName, currentParameters, null, "pre-auth")

        boolean hasSecaRules = secaRules != null && secaRules.size() > 0
        if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, null, "pre-validate", secaRules, eci)

        // start with the settings for the default: use-or-begin
        boolean pauseResumeIfNeeded = false
        boolean beginTransactionIfNeeded = true
        if (ignoreTransaction) beginTransactionIfNeeded = false
        if (requireNewTransaction) pauseResumeIfNeeded = true

        TransactionFacadeImpl tf = eci.transactionFacade
        boolean suspendedTransaction = false
        Map<String, Object> result = new HashMap()
        try {
            if (pauseResumeIfNeeded && tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = beginTransactionIfNeeded ? tf.begin(null) : false
            if (useTransactionCache) tf.initTransactionCache()
            try {
                if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, null, "pre-service", secaRules, eci)

                try {
                    EntityDefinition ed = eci.entityFacade.getEntityDefinition(noun)
                    if ("create".equals(verb)) {
                        EntityAutoServiceRunner.createEntity(eci, ed, currentParameters, result, null)
                    } else if ("update".equals(verb)) {
                        EntityAutoServiceRunner.updateEntity(eci, ed, currentParameters, result, null, null)
                    } else if ("delete".equals(verb)) {
                        EntityAutoServiceRunner.deleteEntity(eci, ed, currentParameters)
                    } else if ("store".equals(verb)) {
                        EntityAutoServiceRunner.storeEntity(eci, ed, currentParameters, result, null)
                    }
                    // NOTE: no need to throw exception for other verbs, checked in advance when looking for valid service name by entity auto pattern
                } finally {
                    if (hasSecaRules) sfi.registerTxSecaRules(serviceNameNoHash, currentParameters, result, secaRules)
                }

                if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, result, "post-service", secaRules, eci)
            } catch (ArtifactAuthorizationException e) {
                tf.rollback(beganTransaction, "Authorization error running service ${serviceName}", e)
                // this is a local call, pass certain exceptions through
                throw e
            } catch (Throwable t) {
                logger.error("Error running service ${serviceName}", t)
                tf.rollback(beganTransaction, "Error running service ${serviceName} (Throwable)", t)
                // add all exception messages to the error messages list
                eci.messageFacade.addError(t.getMessage())
                Throwable parent = t.getCause()
                while (parent != null) {
                    eci.messageFacade.addError(parent.getMessage())
                    parent = parent.getCause()
                }
            } finally {
                try {
                    if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
                } catch (Throwable t) {
                    logger.warn("Error committing transaction for entity-auto service ${serviceName}", t)
                    // add all exception messages to the error messages list
                    eci.messageFacade.addError(t.getMessage())
                    Throwable parent = t.getCause()
                    while (parent != null) {
                        eci.messageFacade.addError(parent.getMessage())
                        parent = parent.getCause()
                    }
                }
                if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, result, "post-commit", secaRules, eci)
            }
        } catch (TransactionException e) {
            throw e
        } finally {
            if (suspendedTransaction) tf.resume()
        }
        return result
    }
}
