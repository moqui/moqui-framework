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
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.service.ServiceCallJob
import org.moqui.service.ServiceException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeoutException

@CompileStatic
class ServiceCallJobImpl extends ServiceCallImpl implements ServiceCallJob {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceCallAsyncImpl.class)

    private String jobName
    private EntityValue serviceJob
    private Future<Map<String, Object>> runFuture = (Future) null

    ServiceCallJobImpl(String jobName, ServiceFacadeImpl sfi) {
        super(sfi)
        ExecutionContextImpl eci = sfi.ecfi.eci

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
        setServiceName((String) serviceJob.serviceName)
    }

    @Override
    ServiceCallJob parameters(Map<String, ?> map) { parameters.putAll(map); return this }
    @Override
    ServiceCallJob parameter(String name, Object value) { parameters.put(name, value); return this }

    @Override
    String run() throws ServiceException {
        ExecutionContextFactoryImpl ecfi = sfi.getEcfi()
        ExecutionContextImpl eci = ecfi.getEci()
        validateCall(eci)

        // create the ServiceJobRun record
        String parametersString = JsonOutput.toJson(parameters)
        Map jobRunResult = ecfi.service.sync().name("create", "moqui.service.job.ServiceJobRun")
                .parameters([jobName:jobName, userId:eci.user.userId, parameters:parametersString] as Map<String, Object>)
                .disableAuthz().call()
        String jobRunId = jobRunResult.jobRunId

        // run it
        ServiceJobCallable callable = new ServiceJobCallable(eci, serviceJob, jobRunId, parameters)
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
    Map<String, Object> get(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        if (runFuture == null) throw new IllegalStateException("Must call run() before using Future interface methods")
        return runFuture.get(timeout, unit)
    }

    static class ServiceJobCallable implements Callable<Map<String, Object>>, Externalizable {
        transient ExecutionContextFactoryImpl ecfi
        String threadTenantId, threadUsername, currentUserId
        String jobName, serviceName, topic, jobRunId
        Map<String, Object> parameters

        ServiceJobCallable(ExecutionContextImpl eci, Map<String, Object> serviceJob, String jobRunId, Map<String, Object> parameters) {
            ecfi = eci.ecfi
            threadTenantId = eci.tenantId
            threadUsername = eci.user.username
            currentUserId = eci.user.userId
            jobName = (String) serviceJob.jobName
            serviceName = (String) serviceJob.serviceName
            topic = (String) serviceJob.topic
            this.jobRunId = jobRunId
            this.parameters = new HashMap<>(parameters)
        }

        @Override
        void writeExternal(ObjectOutput out) throws IOException {
            out.writeUTF(threadTenantId) // never null
            out.writeObject(threadUsername) // might be null
            out.writeObject(currentUserId) // might be null
            out.writeUTF(jobName) // never null
            out.writeUTF(serviceName) // never null
            out.writeObject(topic) // might be null
            out.writeUTF(jobRunId) // never null
            out.writeObject(parameters)
        }

        @Override
        void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
            threadTenantId = objectInput.readUTF()
            threadUsername = (String) objectInput.readObject()
            currentUserId = (String) objectInput.readObject()
            jobName = objectInput.readUTF()
            serviceName = objectInput.readUTF()
            topic = (String) objectInput.readObject()
            jobRunId = objectInput.readUTF()
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
                threadEci = getEcfi().getEci()
                threadEci.changeTenant(threadTenantId)
                if (threadUsername != null && threadUsername.length() > 0)
                    threadEci.userFacade.internalLoginUser(threadUsername, threadTenantId)

                // set hostAddress, hostName, runThread, startTime on ServiceJobRun
                InetAddress localHost = getEcfi().getLocalhostAddress()
                ecfi.service.sync().name("update", "moqui.service.job.ServiceJobRun")
                        .parameters([jobRunId:jobRunId, hostAddress:(localHost?.getHostAddress() ?: '127.0.0.1'),
                            hostName:(localHost?.getHostName() ?: 'localhost'), runThread:Thread.currentThread().getName(),
                            startTime:threadEci.user.nowTimestamp] as Map<String, Object>)
                        .disableAuthz().call()

                // NOTE: authz is disabled because authz is checked before queueing
                Map<String, Object> result = threadEci.service.sync().name(serviceName).parameters(parameters).disableAuthz().call()

                // set endTime, results, messages, errors on ServiceJobRun
                String resultString = JsonOutput.toJson(result)
                ecfi.service.sync().name("update", "moqui.service.job.ServiceJobRun")
                        .parameters([jobRunId:jobRunId, endTime:threadEci.user.nowTimestamp, results:resultString,
                            messages:threadEci.message.getMessagesString(), errors:threadEci.message.getErrorsString()] as Map<String, Object>)
                        .disableAuthz().call()

                // TODO if topic send NotificationMessage

                return result
            } catch (Throwable t) {
                logger.error("Error in async service", t)
                throw t
            } finally {
                if (threadEci != null) threadEci.destroy()
            }
        }
    }
}
