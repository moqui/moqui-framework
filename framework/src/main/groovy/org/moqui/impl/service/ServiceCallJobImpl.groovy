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
import org.moqui.Moqui
import org.moqui.context.NotificationMessage
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.service.ServiceCallJob
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
    protected final static Logger logger = LoggerFactory.getLogger(ServiceCallAsyncImpl.class)

    private String jobName
    private EntityValue serviceJob
    private Future<Map<String, Object>> runFuture = (Future) null
    private String withJobRunId = (String) null
    private boolean clearLock = false

    ServiceCallJobImpl(String jobName, ServiceFacadeImpl sfi) {
        super(sfi)
        ExecutionContextImpl eci = sfi.ecfi.getEci()

        // get ServiceJob, make sure exists
        this.jobName = jobName
        serviceJob = eci.entity.find("moqui.service.job.ServiceJob").condition("jobName", jobName)
                .useCache(true).disableAuthz().one()
        if (serviceJob == null) throw new IllegalArgumentException("No ServiceJob record found for jobName ${jobName}")

        // set ServiceJobParameter values
        EntityList serviceJobParameters = eci.entity.find("moqui.service.job.ServiceJobParameter")
                .condition("jobName", jobName).useCache(true).disableAuthz().list()
        for (EntityValue serviceJobParameter in serviceJobParameters)
            parameters.put((String) serviceJobParameter.parameterName, serviceJobParameter.parameterValue)

        // set the serviceName so rest of ServiceCallImpl works
        serviceNameInternal((String) serviceJob.serviceName)
    }

    @Override
    ServiceCallJob parameters(Map<String, ?> map) { parameters.putAll(map); return this }
    @Override
    ServiceCallJob parameter(String name, Object value) { parameters.put(name, value); return this }

    ServiceCallJob withJobRunId(String jobRunId) { withJobRunId = jobRunId; return this }
    ServiceCallJob clearLock() { clearLock = true; return this }

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
        ServiceJobCallable callable = new ServiceJobCallable(eci, serviceJob, jobRunId, clearLock, parameters)
        if (sfi.distributedExecutorService == null || serviceJob.localOnly == 'Y') {
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
        String threadTenantId, threadUsername, currentUserId
        String jobName, jobDescription, serviceName, topic, jobRunId
        Map<String, Object> parameters
        boolean clearLock
        int transactionTimeout

        // default constructor for deserialization only!
        ServiceJobCallable() { }

        ServiceJobCallable(ExecutionContextImpl eci, Map<String, Object> serviceJob, String jobRunId, boolean clearLock, Map<String, Object> parameters) {
            ecfi = eci.ecfi
            threadTenantId = eci.tenantId
            threadUsername = eci.userFacade.username
            currentUserId = eci.userFacade.userId
            jobName = (String) serviceJob.jobName
            jobDescription = (String) serviceJob.description
            serviceName = (String) serviceJob.serviceName
            topic = (String) serviceJob.topic
            transactionTimeout = (serviceJob.transactionTimeout ?: 1800) as int
            this.jobRunId = jobRunId
            this.clearLock = clearLock
            this.parameters = new HashMap<>(parameters)
        }

        @Override
        void writeExternal(ObjectOutput out) throws IOException {
            out.writeUTF(threadTenantId) // never null
            out.writeObject(threadUsername) // might be null
            out.writeObject(currentUserId) // might be null
            out.writeUTF(jobName) // never null
            out.writeObject(jobDescription) // might be null
            out.writeUTF(serviceName) // never null
            out.writeObject(topic) // might be null
            out.writeUTF(jobRunId) // never null
            out.writeBoolean(clearLock)
            out.writeInt(transactionTimeout)
            out.writeObject(parameters)
        }
        @Override
        void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
            threadTenantId = objectInput.readUTF()
            threadUsername = (String) objectInput.readObject()
            currentUserId = (String) objectInput.readObject()
            jobName = objectInput.readUTF()
            jobDescription = objectInput.readObject()
            serviceName = objectInput.readUTF()
            topic = (String) objectInput.readObject()
            jobRunId = objectInput.readUTF()
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
                threadEci = ecfi.getEci()
                threadEci.changeTenant(threadTenantId)
                if (threadUsername != null && threadUsername.length() > 0)
                    threadEci.userFacade.internalLoginUser(threadUsername, threadTenantId)

                // set hostAddress, hostName, runThread, startTime on ServiceJobRun
                InetAddress localHost = ecfi.getLocalhostAddress()
                // NOTE: no need to run async or separate thread, is in separate TX because no wrapping TX for these service calls
                ecfi.serviceFacade.sync().name("update", "moqui.service.job.ServiceJobRun")
                        .parameters([jobRunId:jobRunId, hostAddress:(localHost?.getHostAddress() ?: '127.0.0.1'),
                            hostName:(localHost?.getHostName() ?: 'localhost'), runThread:Thread.currentThread().getName(),
                            startTime:threadEci.user.nowTimestamp] as Map<String, Object>)
                        .disableAuthz().call()

                // NOTE: authz is disabled because authz is checked before queueing
                Map<String, Object> results = ecfi.serviceFacade.sync().name(serviceName).parameters(parameters)
                        .transactionTimeout(transactionTimeout).disableAuthz().call()

                // set endTime, results, messages, errors on ServiceJobRun
                String resultString = JsonOutput.toJson(results)
                boolean hasError = threadEci.messageFacade.hasError()
                String messages = threadEci.messageFacade.getMessagesString()
                String errors = hasError ? threadEci.messageFacade.getErrorsString() : null
                Timestamp nowTimestamp = threadEci.userFacade.nowTimestamp

                // before calling other services clear out errors or they won't run
                if (hasError) threadEci.messageFacade.clearErrors()

                // clear the ServiceJobRunLock if there is one
                if (clearLock) {
                    ecfi.serviceFacade.sync().name("update", "moqui.service.job.ServiceJobRunLock")
                            .parameter("jobName", jobName).parameter("jobRunId", null)
                            .disableAuthz().call()
                }

                // NOTE: no need to run async or separate thread, is in separate TX because no wrapping TX for these service calls
                ecfi.serviceFacade.sync().name("update", "moqui.service.job.ServiceJobRun")
                        .parameters([jobRunId:jobRunId, endTime:nowTimestamp, results:resultString,
                            messages:messages, hasError:(hasError ? 'Y' : 'N'), errors:errors] as Map<String, Object>)
                        .disableAuthz().call()

                // if topic send NotificationMessage
                if (topic) {
                    NotificationMessage nm = threadEci.makeNotificationMessage().topic(topic)
                    Map<String, Object> msgMap = new HashMap<>()
                    msgMap.put("serviceCallRun", [jobName:jobName, description:jobDescription, jobRunId:jobRunId,
                            endTime:nowTimestamp, messages:messages, hasError:hasError, errors:errors])
                    msgMap.put("parameters", parameters)
                    msgMap.put("results", results)
                    nm.message(msgMap)

                    if (currentUserId) nm.userId(currentUserId)
                    EntityList serviceJobUsers = threadEci.entity.find("moqui.service.job.ServiceJobUser")
                            .condition("jobName", jobName).useCache(true).disableAuthz().list()
                    for (EntityValue serviceJobUser in serviceJobUsers)
                        if (serviceJobUser.receiveNotifications != 'N')
                            nm.userId((String) serviceJobUser.userId)

                    nm.type(hasError ? NotificationMessage.danger : NotificationMessage.success)
                    nm.send()
                }

                return results
            } catch (Throwable t) {
                logger.error("Error in async service", t)
                throw t
            } finally {
                if (threadEci != null) threadEci.destroy()
            }
        }
    }
}
