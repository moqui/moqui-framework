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

import org.moqui.context.ExecutionContext;
import org.moqui.context.MessageFacadeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * ServiceFacade Result Waiter Class
 */
public class ServiceResultWaiter implements ServiceResultReceiver {

    protected final static Logger logger = LoggerFactory.getLogger(ServiceResultWaiter.class);

    /** Status code for a running service */
    public static final int SERVICE_RUNNING = -1;
    /** Status code for a failed service */
    public static final int SERVICE_FAILED = 0;
    /** Status code for a successful service */
    public static final int SERVICE_FINISHED = 1;

    private ExecutionContext ec;
    private volatile Map<String, Object> result = null;
    private volatile Throwable throwable = null;

    public ServiceResultWaiter(ExecutionContext ec) {
        this.ec = ec;
    }

    @Override
    public void receiveResult(Map<String, Object> result) {
        this.result = result;
        synchronized (this) {
            this.notify();
        }
    }

    @Override
    public void receiveThrowable(Throwable t) {
        this.throwable = t;
        synchronized (this) {
            this.notify();
        }
    }

    /**
     * Returns the status of the service.
     * @return int Status code
     */
    public int status() {
        if (this.result != null) return SERVICE_FINISHED;
        if (this.throwable != null) return SERVICE_FAILED;
        return SERVICE_RUNNING;
    }

    /**
     * If the service has completed return true
     * @return boolean
     */
    public boolean isCompleted() {
        return result != null || throwable != null;
    }

    /**
     * Returns the exception which was thrown or null if none
     * @return Exception
     */
    public Throwable getThrowable() {
        if (!isCompleted()) throw new java.lang.IllegalStateException("Cannot get exception, service has not completed.");
        return this.throwable;
    }

    /**
     * Gets the results of the service or null if none
     * @return Map
     */
    public Map<String, Object> getResult() {
        if (!isCompleted()) throw new java.lang.IllegalStateException("Cannot get result, service has not completed.");
        return result;
    }

    /**
     * Waits for the service to complete
     * @return Map
     */
    public Map<String, Object> waitForResult() { return this.waitForResult(10); }

    /**
     * Waits for the service to complete, check the status every n milliseconds
     * @param milliseconds Time in milliseconds to wait
     * @return Map
     */
    public Map<String, Object> waitForResult(long milliseconds) {
        while (!isCompleted()) {
            try {
                synchronized (this) {
                    this.wait(milliseconds);
                }
            } catch (java.lang.InterruptedException e) {
                logger.error("Error while waiting for result of async call to service", e);
            }
        }
        if (throwable != null) {
            if (throwable instanceof MessageFacadeException) {
                MessageFacadeException mfe = (MessageFacadeException) throwable;
                ec.getMessage().copyMessages(mfe.getMessageFacade());
            } else if (throwable instanceof RuntimeException) {
                throw (RuntimeException) throwable;
            } else {
                logger.warn("Async service call resulted in non-runtime exception: " + throwable.toString());
            }
        }
        return getResult();
    }
}
