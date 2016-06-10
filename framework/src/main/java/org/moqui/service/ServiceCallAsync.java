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
package org.moqui.service;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

@SuppressWarnings("unused")
public interface ServiceCallAsync extends ServiceCall {
    /** Name of the service to run. The combined service name, like: "${path}.${verb}${noun}". To explicitly separate
     * the verb and noun put a hash (#) between them, like: "${path}.${verb}#${noun}" (this is useful for calling the
     * implicit entity CrUD services where verb is create, update, or delete and noun is the name of the entity).
     */
    ServiceCallAsync name(String serviceName);

    ServiceCallAsync name(String verb, String noun);

    ServiceCallAsync name(String path, String verb, String noun);

    /** Map of name, value pairs that make up the context (in parameters) passed to the service. */
    ServiceCallAsync parameters(Map<String, ?> context);

    /** Single name, value pairs to put in the context (in parameters) passed to the service. */
    ServiceCallAsync parameter(String name, Object value);


    /** If true the service call will be run distributed and may run on a different member of the cluster. Parameter
     * entries MUST be java.io.Serializable (or java.io.Externalizable).
     *
     * If false it will be run local only (default).
     *
     * @return Reference to this for convenience.
     */
    ServiceCallAsync distribute(boolean dist);

    /**
     * Call the service asynchronously, ignoring the result.
     * This effectively calls the service through a java.lang.Runnable implementation.
     */
    void call() throws ServiceException;

    /**
     * Call the service asynchronously, and get a java.util.concurrent.Future object back so you can wait for the service to
     * complete and get the result.
     *
     * This is useful for running a number of service simultaneously and then getting
     * all of the results back which will reduce the total running time from the sum of the time to run each service
     * to just the time the longest service takes to run.
     *
     * This effectively calls the service through a java.util.concurrent.Callable implementation.
     */
    Future<Map<String, Object>> callFuture() throws ServiceException;

    /** Get a Runnable object to do this service call through an ExecutorService or other runner of your choice. */
    Runnable getRunnable();
    /** Get a Callable object to do this service call through an ExecutorService of your choice. */
    Callable<Map<String, Object>> getCallable();
}
