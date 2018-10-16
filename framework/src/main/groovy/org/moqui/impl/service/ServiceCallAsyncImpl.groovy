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
import org.moqui.service.ServiceCallAsync
import org.moqui.service.ServiceException
import org.moqui.impl.context.ExecutionContextImpl

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.Callable
import java.util.concurrent.Future

@CompileStatic
class ServiceCallAsyncImpl extends ServiceCallImpl implements ServiceCallAsync {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceCallAsyncImpl.class)

    protected boolean distribute = false

    ServiceCallAsyncImpl(ServiceFacadeImpl sfi) {
        super(sfi)
    }

    @Override
    ServiceCallAsync name(String serviceName) { serviceNameInternal(serviceName); return this }
    @Override
    ServiceCallAsync name(String v, String n) { serviceNameInternal(null, v, n); return this }
    @Override
    ServiceCallAsync name(String p, String v, String n) { serviceNameInternal(p, v, n); return this }

    @Override
    ServiceCallAsync parameters(Map<String, ?> map) { parameters.putAll(map); return this }
    @Override
    ServiceCallAsync parameter(String name, Object value) { parameters.put(name, value); return this }

    @Override
    ServiceCallAsync distribute(boolean dist) { this.distribute = dist; return this }

    @Override
    void call() {
        ExecutionContextFactoryImpl ecfi = sfi.ecfi
        ExecutionContextImpl eci = ecfi.getEci()
        validateCall(eci)

        AsyncServiceRunnable runnable = new AsyncServiceRunnable(eci, serviceName, parameters)
        if (distribute && sfi.distributedExecutorService != null) {
            sfi.distributedExecutorService.execute(runnable)
        } else {
            ecfi.workerPool.execute(runnable)
        }
    }

    @Override
    Future<Map<String, Object>> callFuture() throws ServiceException {
        ExecutionContextFactoryImpl ecfi = sfi.ecfi
        ExecutionContextImpl eci = ecfi.getEci()
        validateCall(eci)

        AsyncServiceCallable callable = new AsyncServiceCallable(eci, serviceName, parameters)
        if (distribute && sfi.distributedExecutorService != null) {
            return sfi.distributedExecutorService.submit(callable)
        } else {
            return ecfi.workerPool.submit(callable)
        }
    }

    @Override
    Runnable getRunnable() {
        return new AsyncServiceRunnable(sfi.ecfi.getEci(), serviceName, parameters)
    }

    @Override
    Callable<Map<String, Object>> getCallable() {
        return new AsyncServiceCallable(sfi.ecfi.getEci(), serviceName, parameters)
    }

    static class AsyncServiceInfo implements Externalizable {
        transient ExecutionContextFactoryImpl ecfi
        String threadUsername
        String serviceName
        Map<String, Object> parameters

        AsyncServiceInfo(ExecutionContextImpl eci, String serviceName, Map<String, Object> parameters) {
            ecfi = eci.ecfi
            threadUsername = eci.userFacade.username
            this.serviceName = serviceName
            this.parameters = new HashMap<>(parameters)
        }

        @Override
        void writeExternal(ObjectOutput out) throws IOException {
            out.writeObject(threadUsername) // might be null
            out.writeUTF(serviceName) // never null
            out.writeObject(parameters)
        }

        @Override
        void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
            threadUsername = (String) objectInput.readObject()
            serviceName = objectInput.readUTF()
            parameters = (Map<String, Object>) objectInput.readObject()
        }

        ExecutionContextFactoryImpl getEcfi() {
            if (ecfi == null) ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
            return ecfi
        }

        Map<String, Object> runInternal() throws Exception {
            ExecutionContextImpl threadEci = (ExecutionContextImpl) null
            try {
                threadEci = getEcfi().getEci()
                if (threadUsername != null && threadUsername.length() > 0)
                    threadEci.userFacade.internalLoginUser(threadUsername, false)

                // NOTE: authz is disabled because authz is checked before queueing
                Map<String, Object> result = threadEci.serviceFacade.sync().name(serviceName).parameters(parameters).disableAuthz().call()
                return result
            } catch (Throwable t) {
                logger.error("Error in async service", t)
                throw t
            } finally {
                if (threadEci != null) threadEci.destroy()
            }
        }
    }

    static class AsyncServiceRunnable extends AsyncServiceInfo implements Runnable, Externalizable {
        AsyncServiceRunnable(ExecutionContextImpl eci, String serviceName, Map<String, Object> parameters) {
            super(eci, serviceName, parameters)
        }
        @Override void run() { runInternal() }
    }


    static class AsyncServiceCallable extends AsyncServiceInfo implements Callable<Map<String, Object>>, Externalizable {
        AsyncServiceCallable(ExecutionContextImpl eci, String serviceName, Map<String, Object> parameters) {
            super(eci, serviceName, parameters)
        }
        @Override Map<String, Object> call() throws Exception { return runInternal() }
    }
}
