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
import java.io.Serializable;

/**
 * Service Result Receiver Interface
 */
public interface ServiceResultReceiver extends Serializable {
    /**
     * Receive the result of an asynchronous service call
     * @param result Map of name, value pairs composing the result
     */
    void receiveResult(Map<String, Object> result);

    /**
     * Receive an exception (Throwable) from an asynchronous service cell
     * @param t The Throwable which was received
     */
    void receiveThrowable(Throwable t);
}
