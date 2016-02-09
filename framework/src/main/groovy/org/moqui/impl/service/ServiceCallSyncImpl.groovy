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
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.service.runner.EntityAutoServiceRunner
import org.moqui.service.ServiceCallSync
import org.moqui.service.ServiceException

import java.sql.Timestamp

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ServiceCallSyncImpl extends ServiceCallImpl implements ServiceCallSync {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceCallSyncImpl.class)

    protected boolean ignoreTransaction = false
    protected boolean requireNewTransaction = false
    protected boolean separateThread = false
    protected boolean useTransactionCache = false
    /* not supported by Atomikos/etc right now, consider for later: protected int transactionIsolation = -1 */

    protected boolean ignorePreviousError = false

    protected boolean multi = false
    protected boolean disableAuthz = false

    ServiceCallSyncImpl(ServiceFacadeImpl sfi) {
        super(sfi)
    }

    @Override
    ServiceCallSync name(String serviceName) { this.setServiceName(serviceName); return this }

    @Override
    ServiceCallSync name(String v, String n) { path = null; verb = v; noun = n; return this }

    @Override
    ServiceCallSync name(String p, String v, String n) { path = p; verb = v; noun = n; return this }

    @Override
    ServiceCallSync parameters(Map<String, ?> map) { if (map) { parameters.putAll(map) }; return this }

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
        ExecutionContextFactoryImpl ecfi = sfi.getEcfi()
        ExecutionContextImpl eci = (ExecutionContextImpl) ecfi.getExecutionContext()

        boolean enableAuthz = disableAuthz ? !eci.getArtifactExecution().disableAuthz() : false
        try {
            if (multi) {
                Collection<String> inParameterNames = null
                if (sd != null) {
                    inParameterNames = sd.getInParameterNames()
                } else if (isEntityAutoPattern()) {
                    EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(noun)
                    if (ed != null) inParameterNames = ed.getAllFieldNames()
                }
                // run all service calls in a single transaction for multi form submits, ie all succeed or fail together
                boolean beganTransaction = eci.getTransaction().begin(null)
                try {
                    for (int i = 0; ; i++) {
                        if ((parameters.get("_useRowSubmit") == "true" || parameters.get("_useRowSubmit_" + i) == "true")
                                && parameters.get("_rowSubmit_" + i) != "true") continue
                        Map<String, Object> currentParms = new HashMap()
                        for (String ipn in inParameterNames) {
                            String key = ipn + "_" + i
                            if (parameters.containsKey(key)) currentParms.put(ipn, parameters.get(key))
                        }
                        // if the map stayed empty we have no parms, so we're done
                        if (currentParms.size() == 0) break
                        // now that we have checked the per-row parameters, add in others available
                        for (String ipn in inParameterNames) {
                            if (!currentParms.get(ipn) && parameters.get(ipn)) currentParms.put(ipn, parameters.get(ipn))
                        }
                        // call the service, ignore the result...
                        callSingle(currentParms, sd, eci)
                        // ... and break if there are any errors
                        if (eci.getMessage().hasError()) break
                    }
                } catch (Throwable t) {
                    eci.getTransaction().rollback(beganTransaction, "Uncaught error running service [${sd.getServiceName()}] in multi mode", t)
                    throw t
                } finally {
                    if (eci.getTransaction().isTransactionInPlace()) {
                        if (eci.getMessage().hasError()) {
                            eci.getTransaction().rollback(beganTransaction, "Error message found running service [${sd.getServiceName()}] in multi mode", null)
                        } else {
                            eci.getTransaction().commit(beganTransaction)
                        }
                    }
                }
            } else {
                if (this.separateThread) {
                    Thread serviceThread = null
                    String threadUsername = eci.user.username
                    String threadTenantId = eci.tenantId
                    Map<String, Object> resultMap = null
                    try {
                        serviceThread = Thread.start('ServiceSeparateThread', {
                            ExecutionContextImpl threadEci = ecfi.getEci()
                            threadEci.changeTenant(threadTenantId)
                            if (threadUsername) threadEci.getUserFacade().internalLoginUser(threadUsername, threadTenantId)
                            // if authz disabled need to do it here as well since we'll have a different ExecutionContext
                            boolean threadEnableAuthz = disableAuthz ? !threadEci.getArtifactExecution().disableAuthz() : false
                            try {
                                resultMap = callSingle(this.parameters, sd, threadEci)
                            } finally {
                                if (threadEnableAuthz) threadEci.getArtifactExecution().enableAuthz()
                                threadEci.destroy()
                            }
                        } )
                    } finally {
                        if (serviceThread != null) serviceThread.join()
                    }
                    return resultMap
                } else {
                    return callSingle(this.parameters, sd, eci)
                }
            }
        } finally {
            if (enableAuthz) eci.getArtifactExecution().enableAuthz()
        }
    }

    Map<String, Object> callSingle(Map<String, Object> currentParameters, ServiceDefinition sd, ExecutionContextImpl eci) {
        if (ignorePreviousError) eci.getMessage().pushErrors()
        // NOTE: checking this here because service won't generally run after input validation, etc anyway
        if (eci.getMessage().hasError()) {
            logger.warn("Found error(s) before service [${getServiceName()}], so not running service. Errors: ${eci.getMessage().getErrorsString()}")
            return null
        }
        if (eci.getTransaction().getStatus() == 1 && !requireNewTransaction) {
            logger.warn("Transaction marked for rollback, not running service [${getServiceName()}]. Errors: ${eci.getMessage().getErrorsString()}")
            if (ignorePreviousError) eci.getMessage().popErrors()
            return null
        }

        if (logger.traceEnabled) logger.trace("Calling service [${getServiceName()}] initial input: ${currentParameters}")

        long callStartTime = System.currentTimeMillis()
        long startTimeNanos = System.nanoTime()

        // get these before cleaning up the parameters otherwise will be removed
        String userId = ((Map) currentParameters.authUserAccount)?.userId ?: currentParameters.authUsername
        String password = ((Map) currentParameters.authUserAccount)?.currentPassword ?: currentParameters.authPassword
        String tenantId = currentParameters.authTenantId

        // in-parameter validation
        sfi.runSecaRules(getServiceNameNoHash(), currentParameters, null, "pre-validate")
        if (sd != null) sd.convertValidateCleanParameters(currentParameters, eci)
        // if error(s) in parameters, return now with no results
        if (eci.getMessage().hasError()) {
            StringBuilder errMsg = new StringBuilder("Found error(s) when validating input parameters for service [${getServiceName()}], so not running service. Errors: ${eci.getMessage().getErrorsString()}; the artifact stack is:\n")
            for (ArtifactExecutionInfo stackItem in eci.artifactExecution.stack) {
                errMsg.append(stackItem.toString()).append('\n')
            }
            logger.warn(errMsg.toString())
            if (ignorePreviousError) eci.getMessage().popErrors()
            return null
        }

        boolean userLoggedIn = false

        // always try to login the user if parameters are specified
        if (userId && password) {
            userLoggedIn = eci.getUser().loginUser(userId, password, tenantId)
            // if user was not logged in we should already have an error message in place so just return
            if (!userLoggedIn) return null
        }
        if (sd != null && sd.getAuthenticate() == "true" && !eci.getUser().getUsername() && !eci.getUserFacade().loggedInAnonymous) {
            if (ignorePreviousError) eci.getMessage().popErrors()
            throw new AuthenticationRequiredException("User must be logged in to call service ${getServiceName()}")
        }

        // pre authentication and authorization SECA rules
        sfi.runSecaRules(getServiceNameNoHash(), currentParameters, null, "pre-auth")

        // push service call artifact execution, checks authz too
        // NOTE: don't require authz if the service def doesn't authenticate
        // NOTE: if no sd then requiresAuthz is false, ie let the authz get handled at the entity level (but still put
        //     the service on the stack)
        ArtifactExecutionInfo aei = new ArtifactExecutionInfoImpl(getServiceName(), "AT_SERVICE",
                            ServiceDefinition.getVerbAuthzActionId(verb)).setParameters(currentParameters)
        eci.getArtifactExecution().push(aei, (sd != null && sd.getAuthenticate() == "true"))

        // must be done after the artifact execution push so that AEII object to set anonymous authorized is in place
        boolean loggedInAnonymous = false
        if (sd != null && sd.getAuthenticate() == "anonymous-all") {
            eci.getArtifactExecution().setAnonymousAuthorizedAll()
            loggedInAnonymous = ((UserFacadeImpl) eci.getUser()).loginAnonymousIfNoUser()
        } else if (sd != null && sd.getAuthenticate() == "anonymous-view") {
            eci.getArtifactExecution().setAnonymousAuthorizedView()
            loggedInAnonymous = ((UserFacadeImpl) eci.getUser()).loginAnonymousIfNoUser()
        }

        if (sd == null) {
            if (isEntityAutoPattern()) {
                try {
                    Map result = runImplicitEntityAuto(currentParameters, eci)

                    double runningTimeMillis = (System.nanoTime() - startTimeNanos)/1E6
                    if (logger.traceEnabled) logger.trace("Finished call to service [${getServiceName()}] in ${(runningTimeMillis)/1000} seconds")
                    sfi.getEcfi().countArtifactHit("service", "entity-implicit", getServiceName(), currentParameters,
                            callStartTime, runningTimeMillis, null)

                    return result
                } finally {
                    eci.artifactExecution.pop(aei)
                    if (ignorePreviousError) eci.getMessage().popErrors()
                }
            } else {
                logger.info("No service with name ${getServiceName()}, isEntityAutoPattern=${isEntityAutoPattern()}, path=${path}, verb=${verb}, noun=${noun}, noun is entity? ${((EntityFacadeImpl) eci.getEntity()).isEntityDefined(noun)}")
                eci.artifactExecution.pop(aei)
                if (ignorePreviousError) eci.getMessage().popErrors()
                throw new ServiceException("Could not find service with name [${getServiceName()}]")
            }
        }

        String serviceType = sd.getServiceType()
        if ("interface".equals(serviceType)) {
            eci.artifactExecution.pop(aei)
            if (ignorePreviousError) eci.getMessage().popErrors()
            throw new ServiceException("Cannot run interface service [${getServiceName()}]")
        }

        ServiceRunner sr = sfi.getServiceRunner(serviceType)
        if (sr == null) {
            eci.artifactExecution.pop(aei)
            if (ignorePreviousError) eci.getMessage().popErrors()
            throw new ServiceException("Could not find service runner for type [${serviceType}] for service [${getServiceName()}]")
        }

        // start with the settings for the default: use-or-begin
        boolean pauseResumeIfNeeded = false
        boolean beginTransactionIfNeeded = true
        if (ignoreTransaction || sd.getTxIgnore()) beginTransactionIfNeeded = false
        if (requireNewTransaction || sd.getTxForceNew()) pauseResumeIfNeeded = true

        TransactionFacade tf = sfi.getEcfi().getTransactionFacade()
        boolean suspendedTransaction = false
        Map<String, Object> result = null
        try {
            // if error in auth or for other reasons, return now with no results
            if (eci.getMessage().hasError()) {
                logger.warn("Found error(s) when checking authc for service [${getServiceName()}], so not running service. Errors: ${eci.getMessage().getErrorsString()}; the artifact stack is:\n ${eci.artifactExecution.stack}")
                return null
            }

            if (pauseResumeIfNeeded && tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = beginTransactionIfNeeded ? tf.begin(sd.getTxTimeout()) : false
            if (useTransactionCache || sd.getTxUseCache()) tf.initTransactionCache()
            try {
                // handle sd.serviceNode."@semaphore"; do this after local transaction created, etc.
                checkAddSemaphore(sfi.ecfi, currentParameters)

                sfi.runSecaRules(getServiceNameNoHash(), currentParameters, null, "pre-service")

                if (logger.traceEnabled) logger.trace("Calling service [${getServiceName()}] pre-call input: ${currentParameters}")

                try {
                    // run the service through the ServiceRunner
                    result = sr.runService(sd, currentParameters)
                } finally {
                    sfi.registerTxSecaRules(getServiceNameNoHash(), currentParameters, result)
                }

                // post-service SECA rules
                sfi.runSecaRules(getServiceNameNoHash(), currentParameters, result, "post-service")
                // registered callbacks, no Throwable
                sfi.callRegisteredCallbacks(getServiceName(), currentParameters, result)
                // if we got any errors added to the message list in the service, rollback for that too
                if (eci.getMessage().hasError()) {
                    tf.rollback(beganTransaction, "Error running service [${getServiceName()}] (message): " + eci.getMessage().getErrorsString(), null)
                }

                if (logger.traceEnabled) logger.trace("Calling service [${getServiceName()}] result: ${result}")
            } catch (ArtifactAuthorizationException e) {
                // this is a local call, pass certain exceptions through
                throw e
            } catch (Throwable t) {
                BaseException.filterStackTrace(t)
                // registered callbacks with Throwable
                sfi.callRegisteredCallbacksThrowable(getServiceName(), currentParameters, t)
                // rollback the transaction
                tf.rollback(beganTransaction, "Error running service [${getServiceName()}] (Throwable)", t)
                logger.warn("Error running service [${getServiceName()}] (Throwable)", t)
                // add all exception messages to the error messages list
                eci.getMessage().addError(t.getMessage())
                Throwable parent = t.getCause()
                while (parent != null) {
                    eci.getMessage().addError(parent.getMessage())
                    parent = parent.getCause()
                }
            } finally {
                // clear the semaphore
                clearSemaphore(sfi.ecfi, currentParameters)

                try {
                    if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
                } catch (Throwable t) {
                    logger.warn("Error committing transaction for service [${getServiceName()}]", t)
                    // add all exception messages to the error messages list
                    eci.getMessage().addError(t.getMessage())
                    Throwable parent = t.getCause()
                    while (parent != null) {
                        eci.getMessage().addError(parent.getMessage())
                        parent = parent.getCause()
                    }
                }
                sfi.runSecaRules(getServiceNameNoHash(), currentParameters, result, "post-commit")
            }

            return result
        } catch (TransactionException e) {
            throw e
        } finally {
            try {
                if (suspendedTransaction) tf.resume()
            } catch (Throwable t) {
                logger.error("Error resuming parent transaction after call to service [${getServiceName()}]", t)
            }
            try {
                if (userLoggedIn) eci.getUser().logoutUser()
            } catch (Throwable t) {
                logger.error("Error logging out user after call to service [${getServiceName()}]", t)
            }
            if (loggedInAnonymous) ((UserFacadeImpl) eci.getUser()).logoutAnonymousOnly()

            double runningTimeMillis = (System.nanoTime() - startTimeNanos)/1E6
            sfi.getEcfi().countArtifactHit("service", serviceType, getServiceName(), currentParameters, callStartTime,
                    runningTimeMillis, null)

            // all done so pop the artifact info
            eci.getArtifactExecution().pop(aei)
            // restore error messages if needed
            if (ignorePreviousError) eci.getMessage().popErrors()

            if (logger.traceEnabled) logger.trace("Finished call to service [${getServiceName()}] in ${(runningTimeMillis)/1000} seconds" + (eci.getMessage().hasError() ? " with ${eci.getMessage().getErrors().size() + eci.getMessage().getValidationErrors().size()} error messages" : ", was successful"))
        }
    }

    protected void clearSemaphore(ExecutionContextFactoryImpl ecfi, Map<String, Object> currentParameters) {
        String semaphore = sd.getServiceNode().attribute('semaphore')
        if (!semaphore || semaphore == "none") return

        String semParameter = sd.getServiceNode().attribute('semaphore-parameter')
        String parameterValue = semParameter ? currentParameters.get(semParameter) ?: '_NULL_' : null

        Thread sqlThread = Thread.start('ClearSemaphore', {
            boolean authzDisabled = ecfi.eci.artifactExecution.disableAuthz()
            try {
                if (semParameter) {
                    ecfi.entity.makeValue("moqui.service.semaphore.ServiceParameterSemaphore")
                            .set('serviceName', getServiceName()).set('parameterValue', parameterValue).delete()
                } else {
                    ecfi.entity.makeValue("moqui.service.semaphore.ServiceSemaphore")
                            .set('serviceName', getServiceName()).delete()
                }
            } finally {
                if (authzDisabled) ecfi.eci.artifactExecution.enableAuthz()
            }
        } )
        sqlThread.join(10000)
    }

    protected void checkAddSemaphore(ExecutionContextFactoryImpl ecfi, Map<String, Object> currentParameters) {
        String semaphore = sd.getServiceNode().attribute('semaphore')
        if (!semaphore || semaphore == "none") return

        String semParameter = sd.getServiceNode().attribute('semaphore-parameter')

        long ignoreMillis = ((sd.getServiceNode().attribute('semaphore-ignore') ?: "3600") as Long) * 1000
        long sleepTime = ((sd.getServiceNode().attribute('semaphore-sleep') ?: "5") as Long) * 1000
        long timeoutTime = ((sd.getServiceNode().attribute('semaphore-timeout') ?: "120") as Long) * 1000
        long currentTime = System.currentTimeMillis()
        String lockThreadName = Thread.currentThread().getName()

        if (semParameter) {
            String parameterValue = currentParameters.get(semParameter) ?: '_NULL_'
            Thread sqlThread = Thread.start('CheckAddSemaphore', {
                boolean authzDisabled = ecfi.eci.artifactExecution.disableAuthz()
                try {
                    EntityValue serviceSemaphore = ecfi.entity.find("moqui.service.semaphore.ServiceParameterSemaphore")
                            .condition("serviceName", getServiceName()).condition("parameterValue", parameterValue).useCache(false).one()

                    if (serviceSemaphore) {
                        Timestamp lockTime = serviceSemaphore.getTimestamp("lockTime")
                        if (currentTime > (lockTime.getTime() + ignoreMillis)) {
                            ecfi.entity.makeValue("moqui.service.semaphore.ServiceParameterSemaphore")
                                    .set('serviceName', getServiceName()).set('parameterValue', parameterValue).delete()
                            serviceSemaphore = null
                        }
                    }
                    if (serviceSemaphore) {
                        if (semaphore == "fail") {
                            throw new ServiceException("An instance of service [${getServiceName()}] with parameter value [${parameterValue}] is already running (thread [${serviceSemaphore.lockThread}], locked at ${serviceSemaphore.lockTime}) and it is setup to fail on semaphore conflict.")
                        } else {
                            boolean semaphoreCleared = false
                            while (System.currentTimeMillis() < (currentTime + timeoutTime)) {
                                Thread.sleep(sleepTime)
                                if (ecfi.entity.find("moqui.service.semaphore.ServiceParameterSemaphore")
                                        .condition("serviceName", getServiceName()).condition("parameterValue", parameterValue)
                                        .useCache(false).one() == null) {
                                    semaphoreCleared = true
                                    break
                                }
                            }
                            if (!semaphoreCleared) {
                                throw new ServiceException("An instance of service [${getServiceName()}] with parameter value [${parameterValue}] is already running (thread [${serviceSemaphore.lockThread}], locked at ${serviceSemaphore.lockTime}) and it is setup to wait on semaphore conflict, but the semaphore did not clear in ${timeoutTime/1000} seconds.")
                            }
                        }
                    }

                    // if we got to here the semaphore didn't exist or has cleared, so create one
                    ecfi.entity.makeValue("moqui.service.semaphore.ServiceParameterSemaphore")
                            .set('serviceName', getServiceName()).set('parameterValue', parameterValue)
                            .set('lockThread', lockThreadName).set('lockTime', new Timestamp(currentTime)).create()
                } finally { if (authzDisabled) ecfi.eci.artifactExecution.enableAuthz() }
            } )
            sqlThread.join(10000)
        } else {
            Thread sqlThread = Thread.start('CheckAddSemaphore', {
                boolean authzDisabled = ecfi.eci.artifactExecution.disableAuthz()
                try {
                    EntityValue serviceSemaphore = ecfi.entity.find("moqui.service.semaphore.ServiceSemaphore")
                            .condition("serviceName", getServiceName()).useCache(false).one()

                    if (serviceSemaphore) {
                        Timestamp lockTime = serviceSemaphore.getTimestamp("lockTime")
                        if (currentTime > (lockTime.getTime() + ignoreMillis)) {
                            ecfi.entity.makeValue("moqui.service.semaphore.ServiceSemaphore")
                                    .set('serviceName', getServiceName()).delete()
                            serviceSemaphore = null
                        }
                    }
                    if (serviceSemaphore) {
                        if (semaphore == "fail") {
                            throw new ServiceException("An instance of service [${getServiceName()}] is already running (thread [${serviceSemaphore.lockThread}], locked at ${serviceSemaphore.lockTime}) and it is setup to fail on semaphore conflict.")
                        } else {
                            boolean semaphoreCleared = false
                            while (System.currentTimeMillis() < (currentTime + timeoutTime)) {
                                Thread.sleep(sleepTime)
                                if (ecfi.entity.find("moqui.service.semaphore.ServiceSemaphore")
                                        .condition("serviceName", getServiceName()).useCache(false).one() == null) {
                                    semaphoreCleared = true
                                    break
                                }
                            }
                            if (!semaphoreCleared) {
                                throw new ServiceException("An instance of service [${getServiceName()}] is already running (thread [${serviceSemaphore.lockThread}], locked at ${serviceSemaphore.lockTime}) and it is setup to wait on semaphore conflict, but the semaphore did not clear in ${timeoutTime/1000} seconds.")
                            }
                        }
                    }

                    // if we got to here the semaphore didn't exist or has cleared, so create one
                    ecfi.entity.makeValue("moqui.service.semaphore.ServiceSemaphore")
                            .set('serviceName', getServiceName()).set('lockThread', lockThreadName)
                            .set('lockTime', new Timestamp(currentTime)).create()
                } finally { if (authzDisabled) ecfi.eci.artifactExecution.enableAuthz() }
            } )
            sqlThread.join(10000)
        }
    }

    protected Map<String, Object> runImplicitEntityAuto(Map<String, Object> currentParameters, ExecutionContextImpl eci) {
        // NOTE: no authentication, assume not required for this; security settings can override this and require
        //     permissions, which will require authentication
        // done in calling method: sfi.runSecaRules(getServiceName(), currentParameters, null, "pre-auth")

        sfi.runSecaRules(getServiceNameNoHash(), currentParameters, null, "pre-validate")

        // start with the settings for the default: use-or-begin
        boolean pauseResumeIfNeeded = false
        boolean beginTransactionIfNeeded = true
        if (ignoreTransaction) beginTransactionIfNeeded = false
        if (requireNewTransaction) pauseResumeIfNeeded = true

        TransactionFacade tf = sfi.getEcfi().getTransactionFacade()
        boolean suspendedTransaction = false
        Map<String, Object> result = new HashMap()
        try {
            if (pauseResumeIfNeeded && tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
            boolean beganTransaction = beginTransactionIfNeeded ? tf.begin(null) : false
            if (useTransactionCache) tf.initTransactionCache()
            try {
                sfi.runSecaRules(getServiceNameNoHash(), currentParameters, null, "pre-service")

                try {
                    EntityDefinition ed = sfi.getEcfi().getEntityFacade().getEntityDefinition(noun)
                    switch (verb) {
                        case "create": EntityAutoServiceRunner.createEntity(sfi, ed, currentParameters, result, null); break
                        case "update": EntityAutoServiceRunner.updateEntity(sfi, ed, currentParameters, result, null, null); break
                        case "delete": EntityAutoServiceRunner.deleteEntity(sfi, ed, currentParameters); break
                        case "store": EntityAutoServiceRunner.storeEntity(sfi, ed, currentParameters, result, null); break
                        // NOTE: no need to throw exception for other verbs, checked in advance when looking for valid service name by entity auto pattern
                    }
                } finally {
                    sfi.registerTxSecaRules(getServiceNameNoHash(), currentParameters, result)
                }

                sfi.runSecaRules(getServiceNameNoHash(), currentParameters, result, "post-service")
            } catch (ArtifactAuthorizationException e) {
                tf.rollback(beganTransaction, "Authorization error running service [${getServiceName()}] ", e)
                // this is a local call, pass certain exceptions through
                throw e
            } catch (Throwable t) {
                logger.error("Error running service [${getServiceName()}]", t)
                tf.rollback(beganTransaction, "Error running service [${getServiceName()}] (Throwable)", t)
                // add all exception messages to the error messages list
                eci.getMessage().addError(t.getMessage())
                Throwable parent = t.getCause()
                while (parent != null) {
                    eci.getMessage().addError(parent.getMessage())
                    parent = parent.getCause()
                }
            } finally {
                try {
                    if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
                } catch (Throwable t) {
                    logger.warn("Error committing transaction for entity-auto service [${getServiceName()}]", t)
                    // add all exception messages to the error messages list
                    eci.getMessage().addError(t.getMessage())
                    Throwable parent = t.getCause()
                    while (parent != null) {
                        eci.getMessage().addError(parent.getMessage())
                        parent = parent.getCause()
                    }
                }
                sfi.runSecaRules(getServiceNameNoHash(), currentParameters, result, "post-commit")
            }
        } catch (TransactionException e) {
            throw e
        } finally {
            if (suspendedTransaction) tf.resume()
        }
        return result
    }
}
