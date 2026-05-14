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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;


/** Example use: 
 *      ec.service.schedule().name("...").parameters([...]).distribute(true).initialDelay(50).atFixedRate(100).call()
 *      ec.service.schedule().name("...").parameters([...]).distribute(true).initialDelay(50).callFuture()
 *      ec.service.schedule().name("....").getScheduledFuture(urn).shutdown()  
 */
@SuppressWarnings("unused")
public interface ServiceCallScheduled extends ServiceCall {
    /** Name of the service to run. The combined service name, like: "${path}.${verb}${noun}". To explicitly separate
     * the verb and noun put a hash (#) between them, like: "${path}.${verb}#${noun}" (this is useful for calling the
     * implicit entity CrUD services where verb is create, update, or delete and noun is the name of the entity).
     */
    ServiceCallScheduled name(String serviceName);

    ServiceCallScheduled name(String verb, String noun);

    ServiceCallScheduled name(String path, String verb, String noun);

    /** Map of name, value pairs that make up the context (in parameters) passed to the service. */
    ServiceCallScheduled parameters(Map<String, Object> context);

    /** Single name, value pairs to put in the context (in parameters) passed to the service. */
    ServiceCallScheduled parameter(String name, Object value);

    /** If true the scheduled service call will be run distributed and may run on a different member of the cluster. Parameter
     * entries MUST be java.io.Serializable (or java.io.Externalizable).
     *
     * If false it will be run local only (default).
     *
     * @return Reference to this for convenience.
     */
    ServiceCallScheduled distribute(boolean dist);

    /** Returns the name of the task for the scheduled service. */
    String getTaskName();

    /** Set a unique task name for the scheduled service. */
    ServiceCallScheduled taskName(String taskName);

    /** The time unit for all time parameters. Defaults to milliseconds. */
    ServiceCallScheduled timeUnit(TimeUnit unit);

    /** The service execution becomes enabled after the given initial delay. Defaults to 0. */
    ServiceCallScheduled initialDelay(long delay) throws IllegalArgumentException;

    /** The maximum time to wait before timeout for each service call. Override the transaction-timeout attribute in the service definition. */
    ServiceCallScheduled transactionTimeout(int transactionTimeout);

    /** Creates a periodic service that becomes enabled first after the given initial delay, and subsequently with the given period; that is executions will commence after initialDelay then initialDelay+period, then initialDelay + 2 * period, and so on. */
    ServiceCallScheduled atFixedRate(long period) throws IllegalArgumentException;
    
    /** Creates a periodic service that becomes enabled first after the given initial delay, and subsequently with the given delay between the termination of one execution and the commencement of the next. */
    ServiceCallScheduled withFixedDelay(long delay) throws IllegalArgumentException;
    
    /** The duration of the execution of the periodic task. After this duration the cyclical task will be canceled. */
    ServiceCallScheduled duration(long duration);

    /**
     * Submits the service for the scheduled execution, ignoring the result.
     * This effectively calls the service through a java.lang.Runnable implementation.
     */
    ServiceCallScheduled call() throws ServiceException;

    /**
     * Submits the service for the scheduled execution and get a java.util.concurrent.ScheduledFuture object back so you can wait for the service to
     * complete and get the result.
     *
     * This effectively calls the service through a java.util.concurrent.Callable implementation.
     */
    ScheduledFuture<Map<String, Object>> callFuture() throws ServiceException;

    /** Attempts to cancel the scheduled execution of this service. */
    boolean cancel(boolean mayInterruptIfRunning);

    /** Attempts to cancel the scheduled execution of this service after the specified delay. 
     *  Returns the SheduledFuture of the cancel task.
     */
    <V> ScheduledFuture<V> cancel(boolean mayInterruptIfRunning, long cancelDelay, TimeUnit unit);

    /** Attempts to cancel the scheduled execution of this service after the specified delay. 
     *  Returns the SheduledFuture of the cancel task.
     */
    <V> ScheduledFuture<V> cancel(boolean mayInterruptIfRunning, long cancelDelay);

    /** Returns true if this scheduled service was cancelled before it completed normally. */
    boolean isCancelled();

    /** Returns true if this scheduled service completed. Completion may be due to normal termination, an exception, or cancellation -- in all of these cases, this method will return true. */
    boolean isDone();
    
    /** Returns true if this scheduled service has not completed. */
    boolean isRunning();

    /** Get a Runnable object to do this service call through an ExecutorService or other runner of your choice. */
    Runnable getRunnable();
    /** Get a Callable object to do this service call through an ExecutorService of your choice. */
    Callable<Map<String, Object>> getCallable();
}
