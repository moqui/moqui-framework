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
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.service.ServiceCallAsync
import org.moqui.service.ServiceResultReceiver
import org.moqui.service.ServiceResultWaiter
import org.moqui.impl.context.ExecutionContextImpl
import org.quartz.impl.matchers.NameMatcher
import org.quartz.TriggerBuilder
import org.quartz.Trigger
import org.quartz.JobDetail
import org.quartz.JobDataMap
import org.quartz.JobBuilder

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ServiceCallAsyncImpl extends ServiceCallImpl implements ServiceCallAsync {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceCallAsyncImpl.class)

    protected boolean persist = false
    /* not supported by Atomikos/etc right now, consider for later: protected int transactionIsolation = -1 */
    protected ServiceResultReceiver resultReceiver = null
    protected int maxRetry = 1

    ServiceCallAsyncImpl(ServiceFacadeImpl sfi) {
        super(sfi)
    }

    @Override
    ServiceCallAsync name(String serviceName) { this.setServiceName(serviceName); return this }

    @Override
    ServiceCallAsync name(String v, String n) { path = null; verb = v; noun = n; return this }

    @Override
    ServiceCallAsync name(String p, String v, String n) { path = p; verb = v; noun = n; return this }

    @Override
    ServiceCallAsync parameters(Map<String, ?> map) { parameters.putAll(map); return this }

    @Override
    ServiceCallAsync parameter(String name, Object value) { parameters.put(name, value); return this }

    @Override
    ServiceCallAsync persist(boolean p) { this.persist = p; return this }

    /* not supported by Atomikos/etc right now, consider for later:
    @Override
    ServiceCallAsync transactionIsolation(int ti) { this.transactionIsolation = ti; return this }
    */

    @Override
    ServiceCallAsync resultReceiver(ServiceResultReceiver rr) { this.resultReceiver = rr; return this }

    @Override
    ServiceCallAsync maxRetry(int mr) { this.maxRetry = mr; return this }

    @Override
    void call() {
        // TODO: how to handle persist on a per-job bases? seems like the volatile Job concept matched this, but that is deprecated in 2.0
        // TODO: how to handle maxRetry
        if (logger.traceEnabled) logger.trace("Setting up call to async service [${serviceName}] with parameters [${parameters}]")

        ExecutionContextFactoryImpl ecfi = sfi.getEcfi()
        ExecutionContextImpl eci = ecfi.getEci()
        // Before scheduling the service check a few basic things so they show up sooner than later:
        ServiceDefinition sd = sfi.getServiceDefinition(getServiceName())
        if (sd == null && !isEntityAutoPattern()) throw new IllegalArgumentException("Could not find service with name [${getServiceName()}]")

        if (sd != null) {
            String serviceType = (String) sd.serviceNode.attribute('type') ?: "inline"
            if (serviceType == "interface") throw new IllegalArgumentException("Cannot run interface service [${getServiceName()}]")
            ServiceRunner sr = sfi.getServiceRunner(serviceType)
            if (sr == null) throw new IllegalArgumentException("Could not find service runner for type [${serviceType}] for service [${getServiceName()}]")
            // validation
            sd.convertValidateCleanParameters(this.parameters, eci)
            // if error(s) in parameters, return now with no results
            if (eci.getMessage().hasError()) return
        }

        // always do an authz before scheduling the job
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(getServiceName(), "AT_SERVICE", ServiceDefinition.getVerbAuthzActionId(verb))
        eci.getArtifactExecutionImpl().pushInternal(aei, (sd != null && sd.getAuthenticate() == "true"))

        parameters.authUsername = eci.getUser().getUsername()
        parameters.authTenantId = eci.getTenantId()

        // logger.warn("=========== async call ${serviceName}, parameters: ${parameters}")

        if (persist) {
            // NOTE: is this the best way to get a unique job name? (needed to register a listener below)
            String uniqueJobName = UUID.randomUUID()
            // NOTE: don't store durably, ie tell it to get rid of it after it is run
            JobBuilder jobBuilder = JobBuilder.newJob(ServiceQuartzJob.class)
                    .withIdentity(uniqueJobName, serviceName)
                    .usingJobData(new JobDataMap(parameters))
                    .requestRecovery().storeDurably(false)
            JobDetail job = jobBuilder.build()

            Trigger nowTrigger = TriggerBuilder.newTrigger()
                    .withIdentity(uniqueJobName, "NowTrigger").startNow().withPriority(5)
                    .forJob(job).build()

            if (resultReceiver != null) {
                ServiceRequesterListener sqjl = new ServiceRequesterListener(resultReceiver)
                // NOTE: is this the best way to get this to run for ONLY this job?
                sfi.scheduler.getListenerManager().addJobListener(sqjl, NameMatcher.nameEquals(uniqueJobName))
            }

            sfi.scheduler.scheduleJob(job, nowTrigger)
        } else {
            AsyncServiceRunnable runnable = new AsyncServiceRunnable(eci, serviceName, parameters, resultReceiver)
            ecfi.workerPool.execute(runnable)
        }

        // we did an authz before scheduling, so pop it now
        eci.getArtifactExecution().pop(aei)
    }

    @Override
    ServiceResultWaiter callWaiter() {
        ServiceResultWaiter resultWaiter = new ServiceResultWaiter(sfi.getEcfi().getEci())
        this.resultReceiver(resultWaiter)
        this.call()
        return resultWaiter
    }

    static class AsyncServiceRunnable implements Runnable {
        ExecutionContextFactoryImpl ecfi
        String threadTenantId
        String threadUsername
        String serviceName
        Map<String, Object> parameters
        ServiceResultReceiver resultReceiver

        AsyncServiceRunnable(ExecutionContextImpl eci, String serviceName, Map<String, Object> parameters, ServiceResultReceiver resultReceiver) {
            ecfi = eci.ecfi
            threadTenantId = eci.tenantId
            threadUsername = eci.user.username
            this.serviceName = serviceName
            this.parameters = new HashMap<>(parameters)
            this.resultReceiver = resultReceiver
        }

        @Override
        void run() {
            ExecutionContextImpl threadEci = (ExecutionContextImpl) null
            try {
                threadEci = ecfi.getEci()
                threadEci.changeTenant(threadTenantId)
                if (threadUsername != null && threadUsername.length() > 0)
                    threadEci.userFacade.internalLoginUser(threadUsername, threadTenantId)

                // NOTE: authz is disabled because authz is checked before queueing
                Map<String, Object> result = threadEci.service.sync().name(serviceName).parameters(parameters).disableAuthz().call()

                if (resultReceiver != null) resultReceiver.receiveResult(result)
            } catch (Throwable t) {
                logger.error("Error in async service", t)
                if (resultReceiver != null) resultReceiver.receiveThrowable(t)
            } finally {
                if (threadEci != null) threadEci.destroy()
            }
        }
    }
}
