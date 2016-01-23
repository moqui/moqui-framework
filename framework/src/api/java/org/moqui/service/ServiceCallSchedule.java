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

public interface ServiceCallSchedule extends ServiceCall {
    /** Name of the service to run. The combined service name, like: "${path}.${verb}${noun}". To explicitly separate
     * the verb and noun put a hash (#) between them, like: "${path}.${verb}#${noun}" (this is useful for calling the
     * implicit entity CrUD services where verb is create, update, or delete and noun is the name of the entity).
     */
    ServiceCallSchedule name(String serviceName);

    ServiceCallSchedule name(String verb, String noun);

    ServiceCallSchedule name(String path, String verb, String noun);

    /** Map of name, value pairs that make up the context (in parameters) passed to the service. */
    ServiceCallSchedule parameters(Map<String, ?> context);

    /** Single name, value pairs to put in the context (in parameters) passed to the service. */
    ServiceCallSchedule parameter(String name, Object value);


    /** Name of the job. If specified repeated schedules with the same jobName will use the same underlying job.
     * @return Reference to this for convenience.
     */
    ServiceCallSchedule jobName(String jobName);

    /* * Name of the service pool to send to (optional).
     * @return Reference to this for convenience.
     */
    /* leaving this out for now, not easily supported by Quartz Scheduler: ServiceCallSchedule poolName(String poolName); */

    /** Time to first run this service (in milliseconds from epoch).
     * @return Reference to this for convenience.
     */
    ServiceCallSchedule startTime(long startTime);

    /** Number of times to repeat.
     * @return Reference to this for convenience.
     */
    ServiceCallSchedule count(int count);

    /** Time that this service schedule should expire (in milliseconds from epoch).
     * @return Reference to this for convenience.
     */
    ServiceCallSchedule endTime(long endTime);

    /** A time interval specifying how often to run this service.
     *
     * @param interval Number of units that make up the interval.
     * @param intervalUnit One of ServiceCall.IntervalUnit { SECONDS, MINUTES, HOURS, DAYS, WEEKS, MONTHS, YEARS }
     * @return Reference to this for convenience.
     */
    ServiceCallSchedule interval(int interval, TimeUnit intervalUnit);

    /** A string in the same format used by cron to define a recurrence.
     *
     * dailyAtHourAndMinute(int hour, int minute): String.format("0 %d %d ? * *", minute, hour)
     * weeklyOnDayAndHourAndMinute(int dayOfWeek, int hour, int minute): String.format("0 %d %d ? * %d", minute, hour, dayOfWeek)
     * monthlyOnDayAndHourAndMinute(int dayOfMonth, int hour, int minute): String.format("0 %d %d %d * ?", minute, hour, dayOfMonth)
     *
     * @return Reference to this for convenience.
     */
    ServiceCallSchedule cron(String cronString);

    /** Maximum number of times to retry running this service.
     * @return Reference to this for convenience.
     */
    ServiceCallSchedule maxRetry(int maxRetry);

    /** Schedule the service call. */
    void call() throws ServiceException;
}
