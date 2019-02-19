package org.moqui.impl.service;

import groovy.lang.Closure;
import org.moqui.BaseException;
import org.moqui.context.*;
import org.moqui.entity.EntityValue;
import org.moqui.impl.context.*;
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.impl.entity.EntitySqlException;
import org.moqui.impl.service.runner.EntityAutoServiceRunner;
import org.moqui.service.ServiceCallSync;
import org.moqui.service.ServiceException;
import org.moqui.util.ObjectUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.Status;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServiceCallSyncImpl extends ServiceCallImpl implements ServiceCallSync {
    private static final Logger logger = LoggerFactory.getLogger(ServiceCallSyncImpl.class);
    private static final boolean traceEnabled = logger.isTraceEnabled();

    private boolean ignoreTransaction = false;
    private boolean requireNewTransaction = false;
    private Boolean useTransactionCache = null;
    private Integer transactionTimeout = null;
    private boolean ignorePreviousError = false;
    private boolean softValidate = false;
    private boolean multi = false;
    protected boolean disableAuthz = false;

    public ServiceCallSyncImpl(ServiceFacadeImpl sfi) { super(sfi); }

    @Override public ServiceCallSync name(String serviceName) { serviceNameInternal(serviceName); return this; }
    @Override public ServiceCallSync name(String v, String n) { serviceNameInternal(null, v, n); return this; }
    @Override public ServiceCallSync name(String p, String v, String n) { serviceNameInternal(p, v, n); return this; }

    @Override public ServiceCallSync parameters(Map<String, ?> map) { if (map != null) parameters.putAll(map); return this; }
    @Override public ServiceCallSync parameter(String name, Object value) { parameters.put(name, value); return this; }

    @Override public ServiceCallSync ignoreTransaction(boolean it) { this.ignoreTransaction = it; return this; }
    @Override public ServiceCallSync requireNewTransaction(boolean rnt) { this.requireNewTransaction = rnt; return this; }
    @Override public ServiceCallSync useTransactionCache(boolean utc) { this.useTransactionCache = utc; return this; }
    @Override public ServiceCallSync transactionTimeout(int timeout) { this.transactionTimeout = timeout; return this; }

    @Override public ServiceCallSync ignorePreviousError(boolean ipe) { this.ignorePreviousError = ipe; return this; }
    @Override public ServiceCallSync softValidate(boolean sv) { this.softValidate = sv; return this; }
    @Override public ServiceCallSync multi(boolean mlt) { this.multi = mlt; return this; }
    @Override public ServiceCallSync disableAuthz() { disableAuthz = true; return this; }

    @Override
    public Map<String, Object> call() {
        ExecutionContextFactoryImpl ecfi = sfi.ecfi;
        ExecutionContextImpl eci = ecfi.getEci();

        boolean enableAuthz = disableAuthz && !eci.artifactExecutionFacade.disableAuthz();
        try {
            if (multi) {
                ArrayList<String> inParameterNames = null;
                if (sd != null) {
                    inParameterNames = sd.getInParameterNames();
                } else if (isEntityAutoPattern()) {
                    EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(noun);
                    if (ed != null) inParameterNames = ed.getAllFieldNames();
                }

                int inParameterNamesSize = inParameterNames != null ? inParameterNames.size() : 0;
                // run all service calls in a single transaction for multi form submits, ie all succeed or fail together
                boolean beganTransaction = eci.transactionFacade.begin(null);
                try {
                    Map<String, Object> result = new HashMap<>();
                    for (int i = 0; ; i++) {
                        if (("true".equals(parameters.get("_useRowSubmit")) || "true".equals(parameters.get("_useRowSubmit_" + i)))
                                && !"true".equals(parameters.get("_rowSubmit_" + i))) continue;
                        Map<String, Object> currentParms = new HashMap<>();
                        for (int paramIndex = 0; paramIndex < inParameterNamesSize; paramIndex++) {
                            String ipn = inParameterNames.get(paramIndex);
                            String key = ipn + "_" + i;
                            if (parameters.containsKey(key)) currentParms.put(ipn, parameters.get(key));
                        }

                        // if the map stayed empty we have no parms, so we're done
                        if (currentParms.size() == 0) break;
                        // now that we have checked the per-row parameters, add in others available
                        for (int paramIndex = 0; paramIndex < inParameterNamesSize; paramIndex++) {
                            String ipn = inParameterNames.get(paramIndex);
                            if (!ObjectUtilities.isEmpty(currentParms.get(ipn))) continue;
                            if (!ObjectUtilities.isEmpty(parameters.get(ipn))) {
                                currentParms.put(ipn, parameters.get(ipn));
                            } else if (!ObjectUtilities.isEmpty(result.get(ipn))) {
                                currentParms.put(ipn, result.get(ipn));
                            }
                        }

                        // call the service
                        Map<String, Object> singleResult = callSingle(currentParms, sd, eci);
                        if (singleResult != null) result.putAll(singleResult);
                        // ... and break if there are any errors
                        if (eci.messageFacade.hasError()) break;
                    }

                    return result;
                } catch (Throwable t) {
                    eci.transactionFacade.rollback(beganTransaction, "Uncaught error running service " + serviceName + " in multi mode", t);
                    throw t;
                } finally {
                    if (eci.transactionFacade.isTransactionInPlace()) {
                        if (eci.messageFacade.hasError()) {
                            eci.transactionFacade.rollback(beganTransaction, "Error message found running service " + serviceName + " in multi mode", null);
                        } else {
                            eci.transactionFacade.commit(beganTransaction);
                        }
                    }
                }
            } else {
                return callSingle(parameters, sd, eci);
            }
        } finally {
            if (enableAuthz) eci.artifactExecutionFacade.enableAuthz();
        }
    }

    private Map<String, Object> callSingle(Map<String, Object> currentParameters, ServiceDefinition sd, final ExecutionContextImpl eci) {
        if (ignorePreviousError) eci.messageFacade.pushErrors();
        // NOTE: checking this here because service won't generally run after input validation, etc anyway
        if (eci.messageFacade.hasError()) {
            logger.warn("Found error(s) before service " + serviceName + ", so not running service. Errors: " + eci.messageFacade.getErrorsString());
            return null;
        }

        TransactionFacadeImpl tf = eci.transactionFacade;
        int transactionStatus = tf.getStatus();
        if (!requireNewTransaction && transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
            logger.warn("Transaction marked for rollback, not running service " + serviceName + ". Errors: [" + eci.messageFacade.getErrorsString() + "] Artifact stack: " + eci.artifactExecutionFacade.getStackNameString());
            if (ignorePreviousError) {
                eci.messageFacade.popErrors();
            } else if (!eci.messageFacade.hasError()) {
                eci.messageFacade.addError("Transaction marked for rollback, not running service " + serviceName);
            }
            return null;
        }

        if (traceEnabled) logger.trace("Calling service " + serviceName + " initial input: " + currentParameters);

        // get these before cleaning up the parameters otherwise will be removed
        String userId = null;
        String password = null;
        if (currentParameters.containsKey("authUsername")) {
            userId = (String) currentParameters.get("authUsername");
            password = (String) currentParameters.get("authPassword");
        } else if (currentParameters.containsKey("authUserAccount")) {
            Map authUserAccount = (Map) currentParameters.get("authUserAccount");
            userId = (String) authUserAccount.get("userId");
            if (userId == null || userId.isEmpty()) userId = (String) currentParameters.get("authUsername");
            password = (String) authUserAccount.get("currentPassword");
            if (password == null || password.isEmpty()) password = (String) currentParameters.get("authPassword");
        }

        final String serviceType = sd != null ? sd.serviceType : "entity-implicit";
        ArrayList<ServiceEcaRule> secaRules = sfi.secaRules(serviceNameNoHash);
        boolean hasSecaRules = secaRules != null && secaRules.size() > 0;

        // in-parameter validation
        if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, null, "pre-validate", secaRules, eci);
        if (sd != null) {
            if (softValidate) eci.messageFacade.pushErrors();
            currentParameters = sd.convertValidateCleanParameters(currentParameters, eci);
            if (softValidate) {
                if (eci.messageFacade.hasError()) {
                    eci.messageFacade.moveErrorsToDangerMessages();
                    eci.messageFacade.popErrors();
                    return null;
                }
                eci.messageFacade.popErrors();
            }
        }
        // if error(s) in parameters, return now with no results
        if (eci.messageFacade.hasError()) {
            StringBuilder errMsg = new StringBuilder("Found error(s) when validating input parameters for service " + serviceName + ", so not running service. Errors: " + eci.messageFacade.getErrorsString() + "; the artifact stack is:\n");
            for (ArtifactExecutionInfo stackItem : eci.artifactExecutionFacade.getStack()) {
                errMsg.append(stackItem.toString()).append("\n");
            }

            logger.warn(errMsg.toString());
            if (ignorePreviousError) eci.messageFacade.popErrors();
            return null;
        }

        boolean userLoggedIn = false;

        // always try to login the user if parameters are specified
        if (userId != null && password != null && userId.length() > 0 && password.length() > 0) {
            userLoggedIn = eci.getUser().loginUser(userId, password);
            // if user was not logged in we should already have an error message in place so just return
            if (!userLoggedIn) return null;
        }

        if (sd != null && "true".equals(sd.authenticate) && eci.userFacade.getUsername() == null && !eci.userFacade.getLoggedInAnonymous()) {
            if (ignorePreviousError) eci.messageFacade.popErrors();
            throw new AuthenticationRequiredException("User must be logged in to call service " + serviceName);
        }

        if (sd == null) {
            if (sfi.isEntityAutoPattern(path, verb, noun)) {
                try {
                    return runImplicitEntityAuto(currentParameters, secaRules, eci);
                } finally {
                    if (ignorePreviousError) eci.messageFacade.popErrors();
                }
            } else {
                logger.info("No service with name " + serviceName + ", isEntityAutoPattern=" + isEntityAutoPattern() +
                        ", path=" + path + ", verb=" + verb + ", noun=" + noun + ", noun is entity? " + eci.getEntityFacade().isEntityDefined(noun));
                if (ignorePreviousError) eci.messageFacade.popErrors();
                throw new ServiceException("Could not find service with name " + serviceName);
            }
        }

        if ("interface".equals(serviceType)) {
            if (ignorePreviousError) eci.messageFacade.popErrors();
            throw new ServiceException("Service " + serviceName + " is an interface and cannot be run");
        }

        ServiceRunner serviceRunner = sd.serviceRunner;
        if (serviceRunner == null) {
            if (ignorePreviousError) eci.messageFacade.popErrors();
            throw new ServiceException("Could not find service runner for type " + serviceType + " for service " + serviceName);
        }

        // pre authentication and authorization SECA rules
        if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, null, "pre-auth", secaRules, eci);

        // push service call artifact execution, checks authz too
        // NOTE: don't require authz if the service def doesn't authenticate
        // NOTE: if no sd then requiresAuthz is false, ie let the authz get handled at the entity level (but still put
        //     the service on the stack)
        ArtifactExecutionInfo.AuthzAction authzAction = sd != null ? sd.authzAction : ServiceDefinition.verbAuthzActionEnumMap.get(verb);
        if (authzAction == null) authzAction = ArtifactExecutionInfo.AUTHZA_ALL;
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(serviceName, ArtifactExecutionInfo.AT_SERVICE,
                authzAction, serviceType).setParameters(currentParameters);
        eci.artifactExecutionFacade.pushInternal(aei, (sd != null && "true".equals(sd.authenticate)), true);

        // if error in auth or for other reasons, return now with no results
        if (eci.messageFacade.hasError()) {
            eci.artifactExecutionFacade.pop(aei);
            if (ignorePreviousError) eci.messageFacade.popErrors();
            logger.warn("Found error(s) when checking authc for service " + serviceName + ", so not running service. Errors: " +
                    eci.messageFacade.getErrorsString() + "; the artifact stack is:\n " + eci.getArtifactExecution().getStack());
            return null;
        }

        // must be done after the artifact execution push so that AEII object to set anonymous authorized is in place
        boolean loggedInAnonymous = false;
        if (sd != null && "anonymous-all".equals(sd.authenticate)) {
            eci.artifactExecutionFacade.setAnonymousAuthorizedAll();
            loggedInAnonymous = eci.userFacade.loginAnonymousIfNoUser();
        } else if (sd != null && "anonymous-view".equals(sd.authenticate)) {
            eci.artifactExecutionFacade.setAnonymousAuthorizedView();
            loggedInAnonymous = eci.userFacade.loginAnonymousIfNoUser();
        }

        // handle sd.serviceNode."@semaphore"; do this BEFORE local transaction created, etc so waiting for this doesn't cause TX timeout
        if (sd.hasSemaphore) {
            try {
                checkAddSemaphore(eci, currentParameters, true);
            } catch (Throwable t) {
                eci.artifactExecutionFacade.pop(aei);
                throw t;
            }
        }

        // start with the settings for the default: use-or-begin
        boolean pauseResumeIfNeeded = false;
        boolean beginTransactionIfNeeded = true;
        if (ignoreTransaction || sd.txIgnore) beginTransactionIfNeeded = false;
        if (requireNewTransaction || sd.txForceNew) pauseResumeIfNeeded = true;

        boolean suspendedTransaction = false;
        Map<String, Object> result = new HashMap<>();
        try {
            if (pauseResumeIfNeeded && transactionStatus != Status.STATUS_NO_TRANSACTION) {
                suspendedTransaction = tf.suspend();
                transactionStatus = tf.getStatus();
            }
            boolean beganTransaction = false;
            if (beginTransactionIfNeeded && transactionStatus != Status.STATUS_ACTIVE) {
                // logger.warn("Service " + serviceName + " begin TX timeout " + transactionTimeout + " SD txTimeout " + sd.txTimeout);
                beganTransaction = tf.begin(transactionTimeout != null ? transactionTimeout : sd.txTimeout);
                transactionStatus = tf.getStatus();
            }
            if (sd.noTxCache) {
                tf.flushAndDisableTransactionCache();
            } else {
                if (useTransactionCache != null ? useTransactionCache : sd.txUseCache) tf.initTransactionCache();
            }

            try {
                if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, null, "pre-service", secaRules, eci);
                if (traceEnabled) logger.trace("Calling service " + serviceName + " pre-call input: " + currentParameters);

                try {
                    // run the service through the ServiceRunner
                    result = serviceRunner.runService(sd, currentParameters);
                } finally {
                    if (hasSecaRules) sfi.registerTxSecaRules(serviceNameNoHash, currentParameters, result, secaRules);
                }
                // logger.warn("Called " + serviceName + " has error message " + eci.messageFacade.hasError() + " began TX " + beganTransaction + " TX status " + tf.getStatusString());

                // post-service SECA rules
                if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, result, "post-service", secaRules, eci);
                // registered callbacks, no Throwable
                sfi.callRegisteredCallbacks(serviceName, currentParameters, result);
                // if we got any errors added to the message list in the service, rollback for that too
                if (eci.messageFacade.hasError()) {
                    tf.rollback(beganTransaction, "Error running service " + serviceName + " (message): " + eci.messageFacade.getErrorsString(), null);
                    transactionStatus = tf.getStatus();
                }

                if (traceEnabled) logger.trace("Calling service " + serviceName + " result: " + result);
            } catch (ArtifactAuthorizationException e) {
                // this is a local call, pass certain exceptions through
                throw e;
            } catch (Throwable t) {
                BaseException.filterStackTrace(t);
                // registered callbacks with Throwable
                sfi.callRegisteredCallbacksThrowable(serviceName, currentParameters, t);
                // rollback the transaction
                tf.rollback(beganTransaction, "Error running service " + serviceName + " (Throwable)", t);
                transactionStatus = tf.getStatus();
                logger.warn("Error running service " + serviceName + " (Throwable) Artifact stack: " + eci.artifactExecutionFacade.getStackNameString(), t);
                // add all exception messages to the error messages list
                eci.messageFacade.addError(t.getMessage());
                Throwable parent = t.getCause();
                while (parent != null) {
                    eci.messageFacade.addError(parent.getMessage());
                    parent = parent.getCause();
                }
            } finally {
                try {
                    if (beganTransaction) {
                        transactionStatus = tf.getStatus();
                        if (transactionStatus == Status.STATUS_ACTIVE) {
                            tf.commit();
                        } else if (transactionStatus == Status.STATUS_MARKED_ROLLBACK) {
                            if (!eci.messageFacade.hasError())
                                eci.messageFacade.addError("Cannot commit transaction for service " + serviceName + ", marked rollback-only");
                            // will rollback based on marked rollback only
                            tf.commit();
                        }
                        /* most likely in this case is no transaction in place, already rolled back above, do nothing:
                        else {
                            logger.warn("In call to service " + serviceName + " transaction not Active or Marked Rollback-Only (" + tf.getStatusString() + "), doing commit to make sure TX closed");
                            tf.commit();
                        }
                        */
                    }
                } catch (Throwable t) {
                    logger.warn("Error committing transaction for service " + serviceName, t);
                    // add all exception messages to the error messages list
                    eci.messageFacade.addError(t.getMessage());
                    Throwable parent = t.getCause();
                    while (parent != null) {
                        eci.messageFacade.addError(parent.getMessage());
                        parent = parent.getCause();
                    }

                }

                if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, result, "post-commit", secaRules, eci);
            }

            return result;
        } finally {
            // clear the semaphore
            if (sd.hasSemaphore) clearSemaphore(eci, currentParameters);

            try {
                if (suspendedTransaction) tf.resume();
            } catch (Throwable t) {
                logger.error("Error resuming parent transaction after call to service " + serviceName, t);
            }

            try {
                if (userLoggedIn) eci.getUser().logoutUser();
            } catch (Throwable t) {
                logger.error("Error logging out user after call to service " + serviceName, t);
            }

            if (loggedInAnonymous) eci.userFacade.logoutAnonymousOnly();

            // all done so pop the artifact info
            eci.artifactExecutionFacade.pop(aei);
            // restore error messages if needed
            if (ignorePreviousError) eci.messageFacade.popErrors();

            if (traceEnabled) logger.trace("Finished call to service " + serviceName +
                    (eci.messageFacade.hasError() ? " with " + (eci.messageFacade.getErrors().size() +
                            eci.messageFacade.getValidationErrors().size()) + " error messages" : ", was successful"));
        }

    }

    @SuppressWarnings("unused")
    private void clearSemaphore(final ExecutionContextImpl eci, Map<String, Object> currentParameters) {
        final String semaphoreName = sd.semaphoreName != null && !sd.semaphoreName.isEmpty() ? sd.semaphoreName : serviceName;
        String semParameter = sd.semaphoreParameter;
        String parameterValue;
        if (semParameter == null || semParameter.isEmpty()) {
            parameterValue = "_NA_";
        } else {
            Object parmObj = currentParameters.get(semParameter);
            parameterValue = parmObj != null ? parmObj.toString() : "_NULL_";
        }

        eci.transactionFacade.runRequireNew(null, "Error in clear service semaphore", new Closure<EntityValue>(this, this) {
            EntityValue doCall(Object it) {
                boolean authzDisabled = eci.artifactExecutionFacade.disableAuthz();
                try {
                    return eci.getEntity().makeValue("moqui.service.semaphore.ServiceParameterSemaphore")
                            .set("serviceName", semaphoreName).set("parameterValue", parameterValue)
                            .set("lockThread", null).set("lockTime", null).update();
                } finally {
                    if (!authzDisabled) eci.artifactExecutionFacade.enableAuthz();
                }
            }
            public EntityValue doCall() { return doCall(null); }
        });
    }

    /* A good test case is the place#Order service which is used in the AssetReservationMultipleThreads.groovy tests:
        conflicting lock:
            <service verb="place" noun="Order" semaphore="wait" semaphore-name="TestOrder">
        segemented lock (bad in practice, good test with transacitonal ID):
            <service verb="place" noun="Order" semaphore="wait" semaphore-name="TestOrder" semaphore-parameter="orderId">
     */
    @SuppressWarnings("unused")
    private void checkAddSemaphore(final ExecutionContextImpl eci, Map<String, Object> currentParameters, boolean allowRetry) {
        final String semaphore = sd.semaphore;
        final String semaphoreName = sd.semaphoreName != null && !sd.semaphoreName.isEmpty() ? sd.semaphoreName : serviceName;
        String semaphoreParameter = sd.semaphoreParameter;
        final String parameterValue;
        if (semaphoreParameter == null || semaphoreParameter.isEmpty()) {
            parameterValue = "_NA_";
        } else {
            Object parmObj = currentParameters.get(semaphoreParameter);
            parameterValue = parmObj != null ? parmObj.toString() : "_NULL_";
        }

        final long semaphoreIgnoreMillis = sd.semaphoreIgnoreMillis;
        final long semaphoreSleepTime = sd.semaphoreSleepTime;
        final long semaphoreTimeoutTime = sd.semaphoreTimeoutTime;
        final int txTimeout = Math.toIntExact(sd.semaphoreTimeoutTime / 1000) * 2;

        // NOTE: get Thread name outside runRequireNew otherwise will always be RequireNewTx
        final String lockThreadName = Thread.currentThread().getName();
        // support a single wait/retry on error creating semaphore record
        AtomicBoolean retrySemaphore = new AtomicBoolean(false);

        eci.transactionFacade.runRequireNew(txTimeout, "Error in check/add service semaphore", new Closure<EntityValue>(this, this) {
            EntityValue doCall(Object it) {
                boolean authzDisabled = eci.artifactExecutionFacade.disableAuthz();
                try {
                    final long startTime = System.currentTimeMillis();

                    // look up semaphore, note that is no forUpdate, we want to loop wait below instead of doing a database lock wait
                    EntityValue serviceSemaphore = eci.getEntity().find("moqui.service.semaphore.ServiceParameterSemaphore")
                            .condition("serviceName", semaphoreName).condition("parameterValue", parameterValue).useCache(false).one();
                    // if there is an active semaphore but lockTime is too old reset and ignore it
                    if (serviceSemaphore != null && (serviceSemaphore.getNoCheckSimple("lockThread") != null || serviceSemaphore.getNoCheckSimple("lockTime") != null)) {
                        Timestamp lockTime = serviceSemaphore.getTimestamp("lockTime");
                        if (startTime > (lockTime.getTime() + semaphoreIgnoreMillis)) {
                            serviceSemaphore.set("lockThread", null).set("lockTime", null).update();
                        }
                    }

                    if (serviceSemaphore != null && (serviceSemaphore.getNoCheckSimple("lockThread") != null || serviceSemaphore.getNoCheckSimple("lockTime") != null)) {
                        if ("fail".equals(semaphore)) {
                            throw new ServiceException("An instance of service semaphore " + semaphoreName + " with parameter value " +
                                    "[" + parameterValue + "] is already running (thread [" + serviceSemaphore.get("lockThread") +
                                    "], locked at " + serviceSemaphore.get("lockTime") + ") and it is setup to fail on semaphore conflict.");
                        } else {
                            boolean semaphoreCleared = false;
                            while (System.currentTimeMillis() < (startTime + semaphoreTimeoutTime)) {
                                // sleep, watch for interrupt
                                try { Thread.sleep(semaphoreSleepTime); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                                // get updated semaphore and see if it has been cleared
                                serviceSemaphore = eci.getEntity().find("moqui.service.semaphore.ServiceParameterSemaphore")
                                        .condition("serviceName", semaphoreName).condition("parameterValue", parameterValue).useCache(false).one();
                                if (serviceSemaphore == null || (serviceSemaphore.getNoCheckSimple("lockThread") == null && serviceSemaphore.getNoCheckSimple("lockTime") == null)) {
                                    semaphoreCleared = true;
                                    break;
                                }
                            }
                            if (!semaphoreCleared) {
                                throw new ServiceException("An instance of service semaphore " + semaphoreName + " with parameter value [" +
                                        parameterValue + "] is already running (thread [" + serviceSemaphore.get("lockThread") +
                                        "], locked at " + serviceSemaphore.get("lockTime") + ") and it is setup to wait on semaphore conflict, but the semaphore did not clear in " +
                                        (semaphoreTimeoutTime / 1000) + " seconds.");
                            }
                        }
                    }

                    // if we got to here the semaphore didn't exist or has cleared, so update existing or create new
                    // do a for-update find now to make sure we own the record if one exists
                    serviceSemaphore = eci.getEntity().find("moqui.service.semaphore.ServiceParameterSemaphore")
                            .condition("serviceName", semaphoreName).condition("parameterValue", parameterValue)
                            .useCache(false).forUpdate(true).one();

                    final Timestamp lockTime = new Timestamp(System.currentTimeMillis());
                    if (serviceSemaphore != null) {
                        return serviceSemaphore.set("lockThread", lockThreadName).set("lockTime", lockTime).update();
                    } else {
                        try {
                            return eci.getEntity().makeValue("moqui.service.semaphore.ServiceParameterSemaphore")
                                    .set("serviceName", semaphoreName).set("parameterValue", parameterValue)
                                    .set("lockThread", lockThreadName).set("lockTime", lockTime).create();
                        } catch (EntitySqlException e) {
                            if ("23505".equals(e.getSQLState())) {
                                logger.warn("Record exists error creating semaphore " + semaphoreName + " parameter " + parameterValue + ", retrying: " + e.toString());
                                retrySemaphore.set(true);
                                return null;
                            } else {
                                throw new ServiceException("Error creating semaphore " + semaphoreName + " with parameter value [" + parameterValue + "]", e);
                            }
                        }
                    }
                } finally {
                    if (!authzDisabled) eci.artifactExecutionFacade.enableAuthz();
                }
            }
            public EntityValue doCall() { return doCall(null); }
        });

        if (allowRetry && retrySemaphore.get()) {
            checkAddSemaphore(eci, currentParameters, false);
        }
    }

    private Map<String, Object> runImplicitEntityAuto(Map<String, Object> currentParameters, ArrayList<ServiceEcaRule> secaRules, ExecutionContextImpl eci) {
        // NOTE: no authentication, assume not required for this; security settings can override this and require
        //     permissions, which will require authentication
        // done in calling method: sfi.runSecaRules(serviceName, currentParameters, null, "pre-auth")

        boolean hasSecaRules = secaRules != null && secaRules.size() > 0;
        if (hasSecaRules)
            ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, null, "pre-validate", secaRules, eci);

        // start with the settings for the default: use-or-begin
        boolean pauseResumeIfNeeded = false;
        boolean beginTransactionIfNeeded = true;
        if (ignoreTransaction) beginTransactionIfNeeded = false;
        if (requireNewTransaction) pauseResumeIfNeeded = true;

        TransactionFacadeImpl tf = eci.transactionFacade;
        boolean suspendedTransaction = false;
        Map<String, Object> result = new HashMap<>();
        try {
            if (pauseResumeIfNeeded && tf.isTransactionInPlace()) suspendedTransaction = tf.suspend();
            boolean beganTransaction = beginTransactionIfNeeded && tf.begin(null);
            if (useTransactionCache != null && useTransactionCache) tf.initTransactionCache();
            try {
                if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, null, "pre-service", secaRules, eci);

                try {
                    EntityDefinition ed = eci.getEntityFacade().getEntityDefinition(noun);
                    if ("create".equals(verb)) {
                        EntityAutoServiceRunner.createEntity(eci, ed, currentParameters, result, null);
                    } else if ("update".equals(verb)) {
                        EntityAutoServiceRunner.updateEntity(eci, ed, currentParameters, result, null, null);
                    } else if ("delete".equals(verb)) {
                        EntityAutoServiceRunner.deleteEntity(eci, ed, currentParameters);
                    } else if ("store".equals(verb)) {
                        EntityAutoServiceRunner.storeEntity(eci, ed, currentParameters, result, null);
                    }

                    // NOTE: no need to throw exception for other verbs, checked in advance when looking for valid service name by entity auto pattern
                } finally {
                    if (hasSecaRules) sfi.registerTxSecaRules(serviceNameNoHash, currentParameters, result, secaRules);
                }

                if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, result, "post-service", secaRules, eci);
            } catch (ArtifactAuthorizationException e) {
                tf.rollback(beganTransaction, "Authorization error running service " + serviceName, e);
                // this is a local call, pass certain exceptions through
                throw e;
            } catch (Throwable t) {
                logger.error("Error running service " + serviceName, t);
                tf.rollback(beganTransaction, "Error running service " + serviceName + " (Throwable)", t);
                // add all exception messages to the error messages list
                eci.messageFacade.addError(t.getMessage());
                Throwable parent = t.getCause();
                while (parent != null) {
                    eci.messageFacade.addError(parent.getMessage());
                    parent = parent.getCause();
                }
            } finally {
                try {
                    if (beganTransaction && tf.isTransactionActive()) tf.commit();
                } catch (Throwable t) {
                    logger.warn("Error committing transaction for entity-auto service " + serviceName, t);
                    // add all exception messages to the error messages list
                    eci.messageFacade.addError(t.getMessage());
                    Throwable parent = t.getCause();
                    while (parent != null) {
                        eci.messageFacade.addError(parent.getMessage());
                        parent = parent.getCause();
                    }
                }

                if (hasSecaRules) ServiceFacadeImpl.runSecaRules(serviceNameNoHash, currentParameters, result, "post-commit", secaRules, eci);
            }
        } finally {
            if (suspendedTransaction) tf.resume();
        }

        return result;
    }
}
