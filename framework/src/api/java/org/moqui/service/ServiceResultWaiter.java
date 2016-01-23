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

    private volatile Map<String, Object> result = null;
    private volatile Throwable t = null;

    /**
     * @see ServiceResultReceiver#receiveResult(java.util.Map)
     */
    public void receiveResult(Map<String, Object> result) {
        this.result = result;
        synchronized (this) {
            this.notify();
        }
    }

    /**
     * @see ServiceResultReceiver#receiveThrowable(java.lang.Throwable)
     */
    public void receiveThrowable(Throwable t) {
        this.t = t;
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
        if (this.t != null) return SERVICE_FAILED;
        return SERVICE_RUNNING;
    }

    /**
     * If the service has completed return true
     * @return boolean
     */
    public boolean isCompleted() {
        return this.result != null || this.t != null;
    }

    /**
     * Returns the exception which was thrown or null if none
     * @return Exception
     */
    public Throwable getThrowable() {
        if (!isCompleted()) {
            throw new java.lang.IllegalStateException("Cannot return exception, service has not completed.");
        }
        return this.t;
    }

    /**
     * Gets the results of the service or null if none
     * @return Map
     */
    public Map<String, Object> getResult() {
        if (!isCompleted()) {
            throw new java.lang.IllegalStateException("Cannot return result, service has not completed.");
        }
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
        return this.getResult();
    }
}

