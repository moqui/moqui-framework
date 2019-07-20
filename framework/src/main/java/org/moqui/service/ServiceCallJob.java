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
import java.util.concurrent.Future;

/**
 * An interface for ad-hoc (explicit) run of configured service jobs (in the moqui.service.job.ServiceJob entity).
 *
 * This interface has minimal options as most should be configured using ServiceJob entity fields.
 */
@SuppressWarnings("unused")
public interface ServiceCallJob extends ServiceCall, Future<Map<String, Object>> {
    /** Map of name, value pairs that make up the context (in parameters) passed to the service. */
    ServiceCallJob parameters(Map<String, ?> context);
    /** Single name, value pairs to put in the context (in parameters) passed to the service. */
    ServiceCallJob parameter(String name, Object value);
    /** Set to true to run local even if a distributed executor service is configured (defaults to false) */
    ServiceCallJob localOnly(boolean local);

    /**
     * Run a service job.
     *
     * The job will always run asynchronously. To get the results of the service call without looking at the
     * ServiceJobRun.results field keep a reference to this object and use the methods on the
     * java.util.concurrent.Future interface.
     *
     * If the ServiceJob.topic field has a value a notification will be sent to the current user and all users
     * configured using ServiceJobUser records. The NotificationMessage.message field will be the results of this
     * service call.
     *
     * @return The jobRunId for the corresponding moqui.service.job.ServiceJobRun record
     */
    String run() throws ServiceException;
}
