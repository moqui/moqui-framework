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

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import org.moqui.BaseArtifactException
import org.moqui.Moqui
import org.moqui.context.NotificationMessage
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.service.ServiceCallJob
import org.moqui.service.ServiceCallSync
import org.moqui.service.ServiceException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@CompileStatic
class ServiceCallJobImpl extends ServiceCallImpl implements ServiceCallJob {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceCallJobImpl.class)

    private String jobName
    private EntityValue serviceJob
    private Future<Map<String, Object>> runFuture = (Future) null
    private String withJobRunId = (String) null
    private Timestamp lastRunTime = (Timestamp) null
    private boolean clearLock = false
    private boolean localOnly = false

    ServiceCallJobImpl(String jobName, ServiceFacadeImpl sfi) {
        super(sfi)
        ExecutionContextImpl eci = sfi.ecfi.getEci()

        // get ServiceJob, make sure exists
        this.jobName = jobName
        serviceJob = eci.entityFacade.fastFindOne("moqui.service.job.ServiceJob", true, true, jobName)
        if (serviceJob == null) throw new BaseArtifactException("No ServiceJob record found for jobName ${jobName}")

        // set ServiceJobParameter values
        EntityList serviceJobParameters = eci.entity.find("moqui.service.job.ServiceJobParameter")
                .condition("jobName", jobName).useCache(true).disableAuthz().list()
        for (EntityValue serviceJobParameter in serviceJobParameters)
            parameters.put((String) serviceJobParameter.parameterName, serviceJobParameter.parameterValue)

        // set the serviceName so rest of ServiceCallImpl works
        serviceNameInternal((String) serviceJob.serviceName)
    }

    @Override ServiceCallJob parameters(Map<String, ?> map) { parameters.putAll(map); return this }
    @Override ServiceCallJob parameter(String name, Object value) { parameters.put(name, value); return this }
    @Override ServiceCallJob localOnly(boolean local) { localOnly = local; return this }

    ServiceCallJobImpl withJobRunId(String jobRunId) { withJobRunId = jobRunId; return this }
    ServiceCallJobImpl withLastRunTime(Timestamp lastRunTime) { this.lastRunTime = lastRunTime; return this }
    ServiceCallJobImpl clearLock() { clearLock = true; return this }

    @Override
    String run() throws ServiceException {
        ExecutionContextFactoryImpl ecfi = sfi.ecfi
        ExecutionContextImpl eci = ecfi.getEci()
        validateCall(eci)

        String jobRunId
        if (withJobRunId == null) {
            // create the ServiceJobRun record
            String parametersString = JsonOutput.toJson(parameters)
            Map jobRunResult = ecfi.service.sync().name("create", "moqui.service.job.ServiceJobRun")
                    .parameters([jobName:jobName, userId:eci.user.userId, parameters:parametersString] as Map<String, Object>)
                    .disableAuthz().requireNewTransaction(true).call()
            jobRunId = jobRunResult.jobRunId
        } else {
            jobRunId = withJobRunId
        }

        // run it
        ServiceJobCallable callable = new ServiceJobCallable(eci, serviceJob, jobRunId, lastRunTime, clearLock, parameters)
        if (sfi.distributedExecutorService == null || localOnly || "Y".equals(serviceJob.localOnly)) {
            runFuture = ecfi.workerPool.submit(callable)
        } else {
            runFuture = sfi.distributedExecutorService.submit(callable)
        }

        return jobRunId
    }

    @Override
    boolean cancel(boolean mayInterruptIfRunning) {
        if (runFuture == null) throw new IllegalStateException("Must call run() before using Future interface methods")
        return runFuture.cancel(mayInterruptIfRunning)
    }
    @Override
    boolean isCancelled() {
        if (runFuture == null) throw new IllegalStateException("Must call run() before using Future interface methods")
        return runFuture.isCancelled()
    }
    @Override
    boolean isDone() {
        if (runFuture == null) throw new IllegalStateException("Must call run() before using Future interface methods")
        return runFuture.isDone()
    }
    @Override
    Map<String, Object> get() throws InterruptedException, ExecutionException {
        if (runFuture == null) throw new IllegalStateException("Must call run() before using Future interface methods")
        return runFuture.get()
    }
    @Override
    Map<String, Object> get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (runFuture == null) throw new IllegalStateException("Must call run() before using Future interface methods")
        return runFuture.get(timeout, unit)
    }

    static class ServiceJobCallable implements Callable<Map<String, Object>>, Externalizable {
        transient ExecutionContextFactoryImpl ecfi
        String threadUsername, currentUserId
        String jobName, jobDescription, serviceName, topic, jobRunId
        Map<String, Object> parameters
        Timestamp lastRunTime = (Timestamp) null
        boolean clearLock
        int transactionTimeout

        // default constructor for deserialization only!
        ServiceJobCallable() { }

        ServiceJobCallable(ExecutionContextImpl eci, Map<String, Object> serviceJob, String jobRunId, Timestamp lastRunTime,
                           boolean clearLock, Map<String, Object> parameters) {
            ecfi = eci.ecfi
            threadUsername = eci.userFacade.username
            currentUserId = eci.userFacade.userId
            jobName = (String) serviceJob.jobName
            jobDescription = (String) serviceJob.description
            serviceName = (String) serviceJob.serviceName
            topic = (String) serviceJob.topic
            transactionTimeout = (serviceJob.transactionTimeout ?: 1800) as int
            this.jobRunId = jobRunId
            this.lastRunTime = lastRunTime
            this.clearLock = clearLock
            this.parameters = new HashMap<>(parameters)
        }

        @Override
        void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(threadUsername) // might be null
            out.writeObject(currentUserId) // might be null
            out.writeUTF(jobName) // never null
            out.writeObject(jobDescription) // might be null
            out.writeUTF(serviceName) // never null
            out.writeObject(topic) // might be null
            out.writeUTF(jobRunId) // never null
            out.writeObject(lastRunTime) // might be null
            out.writeBoolean(clearLock)
            out.writeInt(transactionTimeout)
            out.writeObject(parameters)
        }
        @Override
        void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
            threadUsername = (String) objectInput.readObject()
            currentUserId = (String) objectInput.readObject()
            jobName = objectInput.readUTF()
            jobDescription = objectInput.readObject()
            serviceName = objectInput.readUTF()
            topic = (String) objectInput.readObject()
            jobRunId = objectInput.readUTF()
            lastRunTime = (Timestamp) objectInput.readObject()
            clearLock = objectInput.readBoolean()
            transactionTimeout = objectInput.readInt()
            parameters = (Map<String, Object>) objectInput.readObject()
        }

        ExecutionContextFactoryImpl getEcfi() {
            if (ecfi == null) ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
            return ecfi
        }

        @Override
        Map<String, Object> call() throws Exception {
            ExecutionContextImpl threadEci = (ExecutionContextImpl) null
            try {
                ExecutionContextFactoryImpl ecfi = getEcfi()
                if (ecfi == null) {
                    String errMsg = "ExecutionContextFactory not initialized, cannot call service job ${jobName} with run ID ${jobRunId}"
                    logger.error(errMsg)
                    throw new IllegalStateException(errMsg)
                }
                threadEci = ecfi.getEci()
                if (threadUsername != null && threadUsername.length() > 0)
                    threadEci.userFacade.internalLoginUser(threadUsername, false)

                // set hostAddress, hostName, runThread, startTime on ServiceJobRun
                InetAddress localHost = ecfi.getLocalhostAddress()
                // NOTE: no need to run async or separate thread, is in separate TX because no wrapping TX for these service calls
                ecfi.serviceFacade.sync().name("update", "moqui.service.job.ServiceJobRun")
                        .parameters([jobRunId:jobRunId, hostAddress:(localHost?.getHostAddress() ?: '127.0.0.1'),
                            hostName:(localHost?.getHostName() ?: 'localhost'), runThread:Thread.currentThread().getName(),
                            startTime:threadEci.user.nowTimestamp] as Map<String, Object>)
                        .disableAuthz().call()

                if (lastRunTime != (Object) null) parameters.put("lastRunTime", lastRunTime)

                // NOTE: authz is disabled because authz is checked before queueing
                Map<String, Object> results = new HashMap<>()
                try {
                    results = ecfi.serviceFacade.sync().name(serviceName).parameters(parameters)
                            .transactionTimeout(transactionTimeout).disableAuthz().call()
                } catch (Throwable t) {
                    logger.error("Error in service job call", t)
                    threadEci.messageFacade.addError(t.toString())
                }

                // set endTime, results, messages, errors on ServiceJobRun
                if (results.containsKey(null)) {
                    logger.warn("Service Job ${jobName} results has a null key with value ${results.get(null)}, removing")
                    results.remove(null)
                }
                String resultString = (String) null
                try {
                    resultString = JsonOutput.toJson(results)
                } catch (Exception e) {
                    logger.warn("Error writing JSON for Service Job ${jobName} results: ${e.toString()}\n${results}")
                }
                boolean hasError = threadEci.messageFacade.hasError()
                String messages = threadEci.messageFacade.getMessagesString()
                if (messages != null && messages.length() > 4000) messages = messages.substring(0, 4000)
                String errors = hasError ? threadEci.messageFacade.getErrorsString() : null
                if (errors != null && errors.length() > 4000) errors = errors.substring(0, 4000)
                Timestamp nowTimestamp = threadEci.userFacade.nowTimestamp

                // before calling other services clear out errors or they won't run
                if (hasError) threadEci.messageFacade.clearErrors()

                // clear the ServiceJobRunLock if there is one
                if (clearLock) {
                    ServiceCallSync scs = ecfi.serviceFacade.sync().name("update", "moqui.service.job.ServiceJobRunLock")
                            .parameter("jobName", jobName).parameter("jobRunId", null)
                            .disableAuthz()
                    // if there was an error set lastRunTime to previous
                    if (hasError) scs.parameter("lastRunTime", lastRunTime)
                    scs.call()
                }

                // NOTE: no need to run async or separate thread, is in separate TX because no wrapping TX for these service calls
                ecfi.serviceFacade.sync().name("update", "moqui.service.job.ServiceJobRun")
                        .parameters([jobRunId:jobRunId, endTime:nowTimestamp, results:resultString,
                            messages:messages, hasError:(hasError ? 'Y' : 'N'), errors:errors] as Map<String, Object>)
                        .disableAuthz().call()

                // notifications
                Map<String, Object> msgMap = (Map<String, Object>) null
                EntityList serviceJobUsers = (EntityList) null
                if (topic || hasError) {
                    msgMap = new HashMap<>()
                    msgMap.put("serviceCallRun", [jobName:jobName, description:jobDescription, jobRunId:jobRunId,
                          endTime:nowTimestamp, messages:messages, hasError:hasError, errors:errors])
                    msgMap.put("parameters", parameters)
                    msgMap.put("results", results)

                    serviceJobUsers = threadEci.entityFacade.find("moqui.service.job.ServiceJobUser")
                            .condition("jobName", jobName).useCache(true).disableAuthz().list()
                }

                // if topic send NotificationMessage
                if (topic) {
                    NotificationMessage nm = threadEci.makeNotificationMessage().topic(topic)
                    nm.message(msgMap)

                    if (currentUserId) nm.userId(currentUserId)
                    for (EntityValue serviceJobUser in serviceJobUsers)
                        if (serviceJobUser.receiveNotifications != 'N') nm.userId((String) serviceJobUser.userId)

                    nm.type(hasError ? NotificationMessage.danger : NotificationMessage.success)
                    nm.send()
                }

                // if hasError send general error notification
                if (hasError) {
                    NotificationMessage nm = threadEci.makeNotificationMessage().topic("ServiceJobError")
                            .type(NotificationMessage.danger)
                            .title('''Job Error ${serviceCallRun.jobName?:''} [${serviceCallRun.jobRunId?:''}] ${serviceCallRun.errors?:'N/A'}''')
                            .message(msgMap)

                    if (currentUserId) nm.userId(currentUserId)
                    for (EntityValue serviceJobUser in serviceJobUsers)
                        if (serviceJobUser.receiveNotifications != 'N') nm.userId((String) serviceJobUser.userId)

                    nm.send()
                }

                return results
            } catch (Throwable t) {
                logger.error("Error in service job handling", t)
                // better to not throw? seems to cause issue with scheduler: throw t
                return null
            } finally {
                if (threadEci != null) threadEci.destroy()
            }
        }
    }
}
