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
import org.moqui.Moqui
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.service.ServiceCallScheduled
import org.moqui.service.ServiceException
import org.moqui.service.ServiceCallSync

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.Callable
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@CompileStatic
class ServiceCallScheduledImpl extends ServiceCallImpl implements ServiceCallScheduled {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceCallScheduledImpl.class)

    protected String taskName = null
    protected ScheduledServiceInfo scheduledServiceInfo = null
    protected ScheduledFuture scheduledFuture = null
    protected boolean distribute = false
    protected boolean isOneShot = true
    protected boolean isPeriodic = false
    protected TimeUnit unit = TimeUnit.MILLISECONDS
    protected Long initialDelay = 0L
    protected Integer transactionTimeout = null
    protected Long period
    protected Long delay
    protected Long duration

    ServiceCallScheduledImpl(ServiceFacadeImpl sfi) {
        super(sfi)
    }

    ServiceCallScheduledImpl(ServiceFacadeImpl sfi, String taskName) {
        super(sfi)
        this.taskName(taskName)
    }

    @Override
    ServiceCallScheduled name(String serviceName) { serviceNameInternal(serviceName); return this }
    @Override
    ServiceCallScheduled name(String v, String n) { serviceNameInternal(null, v, n); return this }
    @Override
    ServiceCallScheduled name(String p, String v, String n) { serviceNameInternal(p, v, n); return this }

    @Override
    ServiceCallScheduled parameters(Map<String, Object> map) { parameters.putAll(map); return this }
    @Override
    ServiceCallScheduled parameter(String name, Object value) { parameters.put(name, value); return this }

    @Override
    String getTaskName() { return this.taskName }
    
    @Override
    ServiceCallScheduled taskName(String taskName) {
        if (taskName == null) throw new IllegalArgumentException("The argument taskName is null.")
        this.taskName = taskName
        // try to get and restore existing scheduledFuture by taskName 
        this.scheduledFuture = sfi.getScheduledFuture(taskName)
        return this
    }

    @Override
    ServiceCallScheduled distribute(boolean dist) { this.distribute = dist; return this }

    @Override
    ServiceCallScheduled timeUnit(TimeUnit unit) { this.unit = unit; return this }

    @Override
    ServiceCallScheduled initialDelay(long delay) { this.initialDelay = delay; return this }

    @Override
    ServiceCallScheduled transactionTimeout(int transactionTimeout)  { this.transactionTimeout = transactionTimeout; return this }

    @Override
    ServiceCallScheduled atFixedRate(long period) { this.period = period; this.delay = null; this.isOneShot = false; this.isPeriodic = true; return this }
    
    @Override
    ServiceCallScheduled withFixedDelay(long delay) { this.delay = delay; this.period = null; this.isOneShot = false; this.isPeriodic = true; return this }

    @Override
    ServiceCallScheduled duration(long duration) { this.duration = duration; return this }
    
    @Override
    ServiceCallScheduled call() throws ServiceException {
        if (!taskName) throw new IllegalStateException("taskName is required for call()")

        // guard: if we already have a running future for this builder, return
        if (scheduledFuture != null && this.isRunning()) {
            logger.warn("A previous scheduled service call [${taskName}] is still running. To terminate the previous scheduling use the cancel() method.")
            return this
        }
        // guard: if another task with the same name is already scheduled, reuse it and return
        ScheduledFuture existing = sfi.getScheduledFuture(taskName)
        if (existing != null && !existing.isCancelled() && !existing.isDone()) {
            logger.warn("A previous scheduled service call [${taskName}] is still running. To terminate the previous scheduling use the cancel() method.")
            this.scheduledFuture = existing
            return this
        }

        ExecutionContextFactoryImpl ecfi = sfi.ecfi
        ExecutionContextImpl eci = ecfi.getEci()
        validateCall(eci)
        
        // runs the scheduled service and gets the scheduleFuture
        ScheduledServiceRunnable runnable
        if (transactionTimeout != null) {
            runnable = new ScheduledServiceRunnable(eci, serviceName, parameters, taskName, transactionTimeout)
        } else {
            runnable = new ScheduledServiceRunnable(eci, serviceName, parameters, taskName)
        }
        scheduledServiceInfo = runnable

        boolean useDistributed = (distribute && sfi.distributedScheduledExecutorService != null)

        if (isOneShot) {
            if (useDistributed) {
                scheduledFuture = sfi.distributedScheduledExecutorService.schedule(
                    sfi.distributedScheduledExecutorService.decorateTask(taskName, runnable), initialDelay, unit)
            } else {
                scheduledFuture = sfi.scheduledExecutor.schedule(runnable, initialDelay, unit)
            }
        } else if (isPeriodic) {
            if (period != null && period >= 0) {
                if (useDistributed) {
                    scheduledFuture = sfi.distributedScheduledExecutorService.scheduleAtFixedRate(
                        sfi.distributedScheduledExecutorService.decorateTask(taskName, runnable), initialDelay, period, unit)
                        if (duration > 0) cancel(false, duration, unit)
                } else {
                    scheduledFuture = sfi.scheduledExecutor.scheduleAtFixedRate(runnable, initialDelay, period, unit)
                    if (duration > 0) cancel(false, duration, unit)
                }
            } else if (delay != null && delay >= 0) {
                if (useDistributed) {
                    try {
                        scheduledFuture = sfi.distributedScheduledExecutorService.scheduleWithFixedDelay(
                                sfi.distributedScheduledExecutorService.decorateTask(taskName, runnable), initialDelay, delay, unit)
                        if (duration > 0) cancel(false, duration, unit)
                        // done on distributed scheduler
                        if (scheduledFuture != null) {
                            if (logger.traceEnabled) logger.trace("Scheduled distributed fixed-delay service [${taskName}]")
                            // for distributed scheduled services we rely on remote registry. However, the task is also saved locally.
                            sfi.putScheduledFuture(taskName, scheduledFuture)
                            return this
                        }
                    } catch (UnsupportedOperationException | AbstractMethodError e) {
                        logger.warn("Method scheduleWithFixedDelay is not supported by distributed scheduler ExecutorService. The service call [${taskName}] will be scheduled local only.")
                    }
                }
                scheduledFuture = sfi.scheduledExecutor.scheduleWithFixedDelay(runnable, initialDelay, delay, unit)
                if (duration > 0) cancel(false, duration, unit)
            } else {
                throw new IllegalStateException("Periodic scheduling requested but neither period nor delay was set for task [${taskName}].")
            }
        } else {
            throw new IllegalStateException("Schedule mode not set (one-shot/periodic) for task [${taskName}].")
        }

        if (scheduledFuture == null) throw new IllegalStateException("Scheduler returned null ScheduledFuture for task [${taskName}].")

        // for distributed scheduled services we rely on remote registry. However, the task is also saved locally.
        sfi.putScheduledFuture(taskName, scheduledFuture)
        return this
    }

    @Override
    ScheduledFuture<Map<String, Object>> callFuture() throws ServiceException {
        if (!taskName) throw new IllegalStateException("taskName is required for call()")

        // guard: if we already have a running future for this builder, return
        if (scheduledFuture != null && this.isRunning()) {
            logger.warn("A previous scheduled service call [${taskName}] is still running. To terminate the previous scheduling use the cancel() method.")
            return scheduledFuture
        }
        // guard: if another task with the same name is already scheduled, reuse it and return
        ScheduledFuture existing = sfi.getScheduledFuture(taskName)
        if (existing != null && !existing.isCancelled() && !existing.isDone()) {
            logger.warn("A previous scheduled service call [${taskName}] is still running. To terminate the previous scheduling use the cancel() method.")
            this.scheduledFuture = existing
            return (ScheduledFuture<Map<String, Object>>) existing
        }

        if (isPeriodic) throw new IllegalStateException("You cannot call the callFuture method for periodic service, with fixed delay or at fixed rate.")

        ExecutionContextFactoryImpl ecfi = sfi.ecfi
        ExecutionContextImpl eci = ecfi.getEci()
        validateCall(eci)
        
        boolean useDistributed = (distribute && sfi.distributedScheduledExecutorService != null)

        ScheduledServiceCallable callable
        if (transactionTimeout != null)
            callable = new ScheduledServiceCallable(eci, serviceName, parameters, taskName, transactionTimeout)
        else
            callable = new ScheduledServiceCallable(eci, serviceName, parameters, taskName)

        scheduledServiceInfo = callable

        if (useDistributed) {
            scheduledFuture = sfi.distributedScheduledExecutorService.schedule(
                sfi.distributedScheduledExecutorService.decorateTask(taskName, callable), initialDelay, unit)
            if (duration > 0) cancel(false, duration, unit) 
        } else {
            scheduledFuture = sfi.scheduledExecutor.schedule(callable, initialDelay, unit)
            if (duration > 0) cancel(false, duration, unit)
        }
        return scheduledFuture
    }

    boolean cancel(boolean mayInterruptIfRunning) {
        if (!taskName) throw new IllegalStateException("taskName is required for cancel()")

        // resolve future from this builder or by taskName (so cancel works from a new builder)
        ScheduledFuture sf = scheduledFuture
        if (sf == null) {
            // try to get the scheduledFuture instance from the distributed scheduled executor service
            if (sfi.distributedScheduledExecutorService != null) {
                sf = sfi.distributedScheduledExecutorService.getScheduledFuture(taskName)
            }
            // fallback to local registry
            if (sf == null) sf = sfi.getScheduledFuture(taskName)
            if (sf == null) {
                logger.error("No scheduled task found with name [${taskName}] to cancel")
                return false
            }
            // remember it locally so isRunning() etc keep working
            scheduledFuture = sf
        }

        boolean cancelled = sf.cancel(mayInterruptIfRunning)
        if (cancelled) sfi.removeScheduledFuture(taskName)
        return cancelled
    }

    ScheduledFuture cancel(boolean mayInterruptIfRunning, long cancelDelay, TimeUnit unit) {
        if (!taskName) throw new IllegalStateException("taskName is required for cancel(delay)")
        if (cancelDelay <= 0)  throw new IllegalArgumentException("You cannot call the cancel method with an invalid cancelDelay argument.")

        // Resolve the future eagerly so delayed cancellation does not depend on a later cluster lookup by task name.
        ScheduledFuture sf = scheduledFuture
        if (sf == null) {
            sf = sfi.getScheduledFuture(taskName)
            if (sf != null) scheduledFuture = sf
        }
        final ScheduledFuture sfFinal = sf
        final String tnFinal = taskName

        // Always schedule cancellation locally. The resolved future may still be a distributed proxy.
        Runnable runnable = new Runnable() {
            @Override
            void run() {
                try {
                    boolean cancelled = false
                    if (sfFinal != null) {
                        cancelled = sfFinal.cancel(mayInterruptIfRunning)
                        if (cancelled) sfi.removeScheduledFuture(tnFinal)
                    } else {
                        cancelled = cancel(mayInterruptIfRunning)
                    }
                    if (!cancelled) logger.error("No scheduled task found with name [${tnFinal}] to cancel")
                } catch (Throwable t) {
                    if ("com.hazelcast.scheduledexecutor.StaleTaskException".equals(t.getClass().getName())) {
                        // The distributed task may already be gone on the owner node by the time the local cancel timer fires.
                        sfi.removeScheduledFuture(tnFinal)
                        if (logger.infoEnabled) logger.info("Scheduled service [${tnFinal}] was already gone when delayed cancellation ran")
                    } else {
                        logger.warn("Error cancelling scheduled service [${tnFinal}] from delayed cancellation runnable: ${t.toString()}")
                    }
                }
            }
        }
        return sfi.scheduledExecutor.schedule(runnable, cancelDelay, unit)
    }

    @Override
    ScheduledFuture cancel(boolean mayInterruptIfRunning, long cancelDelay) {
        return cancel(mayInterruptIfRunning, cancelDelay, TimeUnit.MILLISECONDS)
    }

    @Override
    boolean isCancelled() {
        if (scheduledFuture == null) throw new IllegalStateException("Must call method call() or callFuture() before using ScheduledFuture interface methods")
        return scheduledFuture.isCancelled()
    }

    @Override
    boolean isDone() {
        if (scheduledFuture == null) throw new IllegalStateException("Must call method call() or callFuture() before using ScheduledFuture interface methods")
        return scheduledFuture.isDone()
    }
    
    @Override
    boolean isRunning() {
        if (scheduledFuture == null) throw new IllegalStateException("Must call call() or callFuture() before using ScheduledFuture interface methods")
        return (!scheduledFuture.isCancelled() && !scheduledFuture.isDone())
    }

    @Override
    Runnable getRunnable() {
        if (this.transactionTimeout != null)
            return new ScheduledServiceRunnable(sfi.ecfi.getEci(), serviceName, parameters, taskName, transactionTimeout)
        else
            return new ScheduledServiceRunnable(sfi.ecfi.getEci(), serviceName, parameters, taskName)
    }

    @Override
    Callable<Map<String, Object>> getCallable() {
        if (this.transactionTimeout != null)
            return new ScheduledServiceCallable(sfi.ecfi.getEci(), serviceName, parameters, taskName, transactionTimeout)
        else
            return new ScheduledServiceCallable(sfi.ecfi.getEci(), serviceName, parameters, taskName)
    }

    static class ScheduledServiceInfo implements Externalizable {
        transient ExecutionContextFactoryImpl ecfiLocal
        String threadUsername
        String serviceName, taskName
        Map<String, Object> parameters
        Integer transactionTimeout = null

        ScheduledServiceInfo() { }
        ScheduledServiceInfo(ExecutionContextImpl eci, String serviceName, Map<String, Object> parameters, String taskName) {
            ecfiLocal = eci.ecfi
            threadUsername = eci.userFacade.username
            this.serviceName = serviceName
            this.parameters = new HashMap<>(parameters)
            this.taskName = taskName
        }
        ScheduledServiceInfo(ExecutionContextImpl eci, String serviceName, Map<String, Object> parameters, String taskName, int transactionTimeout) {
            ecfiLocal = eci.ecfi
            threadUsername = eci.userFacade.username
            this.serviceName = serviceName
            this.parameters = new HashMap<>(parameters)
            this.taskName = taskName
            this.transactionTimeout = transactionTimeout
        }
        ScheduledServiceInfo(ExecutionContextFactoryImpl ecfi, String username, String serviceName, Map<String, Object> parameters, String taskName) {
            ecfiLocal = ecfi
            threadUsername = username
            this.serviceName = serviceName
            this.parameters = new HashMap<>(parameters)
            this.taskName = taskName
        }
        ScheduledServiceInfo(ExecutionContextFactoryImpl ecfi, String username, String serviceName, Map<String, Object> parameters, String taskName, int transactionTimeout) {
            ecfiLocal = ecfi
            threadUsername = username
            this.serviceName = serviceName
            this.parameters = new HashMap<>(parameters)
            this.taskName = taskName
            this.transactionTimeout = transactionTimeout
        }

        @Override
        void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(threadUsername) // might be null
            out.writeUTF(serviceName) // never null
            out.writeObject(parameters)
            out.writeUTF(taskName)
            out.writeInt(transactionTimeout != null ? transactionTimeout.intValue() : 0)
        }

        @Override
        void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
            threadUsername = (String) objectInput.readObject()
            serviceName = objectInput.readUTF()
            parameters = (Map<String, Object>) objectInput.readObject()
            taskName = objectInput.readUTF()
            int tx = objectInput.readInt()
            transactionTimeout = (tx == 0 ? null : Integer.valueOf(tx))
        }

        ExecutionContextFactoryImpl getEcfi() {
            if (ecfiLocal == null) ecfiLocal = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
            return ecfiLocal
        }

        Map<String, Object> runInternal() throws Exception {
            return runInternal(null, false)
        }
        Map<String, Object> runInternal(Map<String, Object> parameters, boolean skipEcCheck) throws Exception {
            ExecutionContextImpl threadEci = (ExecutionContextImpl) null
            try {
                // check for active Transaction
                if (getEcfi().transactionFacade.isTransactionInPlace()) {
                    logger.error("In ServiceCallScheduled service ${serviceName} a transaction is in place for thread ${Thread.currentThread().getName()}, trying to commit")
                    try {
                        getEcfi().transactionFacade.destroyAllInThread()
                    } catch (Exception e) {
                        logger.error("ServiceCallScheduled commit in place transaction failed for thread ${Thread.currentThread().getName()}", e)
                    }
                }
                // check for active ExecutionContext
                if (!skipEcCheck) {
                    ExecutionContextImpl activeEc = getEcfi().activeContext.get()
                    if (activeEc != null) {
                        logger.error("In ServiceCallScheduled service ${serviceName} there is already an ExecutionContext for user ${activeEc.user.username} (from ${activeEc.forThreadId}:${activeEc.forThreadName}) in this thread ${Thread.currentThread().id}:${Thread.currentThread().name}, destroying")
                        try {
                            activeEc.destroy()
                        } catch (Throwable t) {
                            logger.error("Error destroying ExecutionContext already in place in ServiceCallScheduled in thread ${Thread.currentThread().id}:${Thread.currentThread().name}", t)
                        }
                    }
                }

                threadEci = getEcfi().getEci()
                if (threadUsername != null && threadUsername.length() > 0) {
                    threadEci.userFacade.internalLoginUser(threadUsername, false)
                } else {
                    threadEci.userFacade.loginAnonymousIfNoUser()
                }
            
                Map<String, Object> parmsToUse = this.parameters
                if (parameters != null) {
                    parmsToUse = new HashMap<>(this.parameters)
                    parmsToUse.putAll(parameters)
                }

                // NOTE: authz is disabled because authz is checked before queueing 
                ServiceCallSync serviceCallSync = threadEci.serviceFacade.sync().name(serviceName).parameters(parmsToUse)
                if (this.transactionTimeout) serviceCallSync.transactionTimeout(transactionTimeout)
                Map<String, Object> results = serviceCallSync.disableAuthz().call()
                return results
            } catch (Throwable t) {
                logger.error("Error in scheduling service call", t)
                throw t
            } finally {
                if (threadEci != null) threadEci.destroy()
            }
        }
    }

    static class ScheduledServiceRunnable extends ScheduledServiceInfo implements Runnable, Externalizable {
        ScheduledServiceRunnable() { super() }
        ScheduledServiceRunnable(ExecutionContextImpl eci, String serviceName, Map<String, Object> parameters, String taskName) {
            super(eci, serviceName, parameters, taskName)
        }
        ScheduledServiceRunnable(ExecutionContextImpl eci, String serviceName, Map<String, Object> parameters, String taskName, int transactionTimeout) {
            super(eci, serviceName, parameters, taskName, transactionTimeout)
        }
        @Override void run() { runInternal() }
    }


    static class ScheduledServiceCallable extends ScheduledServiceInfo implements Callable<Map<String, Object>>, Externalizable {
        ScheduledServiceCallable() { super() }
        ScheduledServiceCallable(ExecutionContextImpl eci, String serviceName, Map<String, Object> parameters, String taskName) {
            super(eci, serviceName, parameters, taskName)
        }
        ScheduledServiceCallable(ExecutionContextImpl eci, String serviceName, Map<String, Object> parameters, String taskName, int transactionTimeout) {
            super(eci, serviceName, parameters, taskName, transactionTimeout)
        }
        @Override Map<String, Object> call() throws Exception { return runInternal() }
    }

}
