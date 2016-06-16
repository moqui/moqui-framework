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
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.service.ServiceCallSchedule
// NOTE: IDEs may say this import isn't necessary since it is from an implemented interface, but compiler blows up without it
import org.moqui.service.ServiceCall.TimeUnit

import org.quartz.Trigger
import org.quartz.SimpleScheduleBuilder
import org.quartz.JobDataMap
import org.quartz.TriggerBuilder
import org.quartz.JobBuilder
import org.quartz.JobDetail
import org.quartz.JobKey
import org.quartz.CalendarIntervalScheduleBuilder
import org.quartz.DateBuilder.IntervalUnit
import org.quartz.CronScheduleBuilder
import org.moqui.impl.context.ExecutionContextImpl

@CompileStatic
class ServiceCallScheduleImpl extends ServiceCallImpl implements ServiceCallSchedule {
    protected String jobName = null
    /* leaving this out for now, not easily supported by Quartz Scheduler: protected String poolName = null */
    protected Long startTime = null
    protected Integer count = null
    protected Long endTime = null
    protected Integer interval = null
    protected TimeUnit intervalUnit = null
    protected String cronString = null

    ServiceCallScheduleImpl(ServiceFacadeImpl sfi) {
        super(sfi)
    }

    @Override
    ServiceCallSchedule name(String serviceName) { this.setServiceName(serviceName); return this }

    @Override
    ServiceCallSchedule name(String v, String n) { path = null; verb = v; noun = n; return this }

    @Override
    ServiceCallSchedule name(String p, String v, String n) { path = p; verb = v; noun = n; return this }

    @Override
    ServiceCallSchedule parameters(Map<String, ?> map) { parameters.putAll(map); return this }

    @Override
    ServiceCallSchedule parameter(String name, Object value) { parameters.put(name, value); return this }

    @Override
    ServiceCallSchedule jobName(String jn) { jobName = jn; return this }

    /* leaving this out for now, not easily supported by Quartz Scheduler:
    @Override
    ServiceCallSchedule poolName(String pn) { poolName = pn; return this }
    */

    @Override
    ServiceCallSchedule startTime(long st) { startTime = st; return this }

    @Override
    ServiceCallSchedule count(int c) { count = c; return this }

    @Override
    ServiceCallSchedule endTime(long et) { endTime = et; return this }

    @Override
    ServiceCallSchedule interval(int i, TimeUnit iu) { interval = i; intervalUnit = iu; return this }

    @Override
    ServiceCallSchedule cron(String cs) { cronString = cs; return this }

    @Override
    void call() {
        // TODO maxRetry: any way to set and then track the number of retries?

        if (!jobName) jobName = "${this.verb}${this.noun ? '_' + this.noun : ''}"

        ExecutionContextImpl eci = sfi.getEcfi().getEci()

        // Before scheduling the service check a few basic things so they show up sooner than later:
        ServiceDefinition sd = sfi.getServiceDefinition(getServiceName())
        if (sd == null && !isEntityAutoPattern()) throw new IllegalArgumentException("Could not find service with name [${getServiceName()}]")

        if (sd != null) {
            String serviceType = sd.serviceNode.attribute("type") ?: "inline"
            if (serviceType == "interface") throw new IllegalArgumentException("Cannot run interface service [${getServiceName()}]")
            ServiceRunner sr = sfi.getServiceRunner(serviceType)
            if (sr == null) throw new IllegalArgumentException("Could not find service runner for type [${serviceType}] for service [${getServiceName()}]")
            // validation
            sd.convertValidateCleanParameters(this.parameters, eci)
            // if error(s) in parameters, return now with no results
            if (eci.getMessage().hasError()) return
        }

        // always do an authz before scheduling the job
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(getServiceName(),
                ArtifactExecutionInfo.AT_SERVICE, ServiceDefinition.getVerbAuthzActionEnum(verb))
        eci.getArtifactExecutionImpl().pushInternal(aei, (sd != null && sd.getAuthenticate() == "true"))

        parameters.authUsername = eci.getUser().getUsername()
        parameters.authTenantId = eci.getTenantId()

        // NOTE: get existing job based on jobName/serviceName pair IFF a jobName is specified
        JobKey jk = JobKey.jobKey(jobName, serviceName)
        JobDetail job
        if (jobName && sfi.scheduler.checkExists(jk)) {
            job = sfi.scheduler.getJobDetail(jk)
        } else {
            JobBuilder jobBuilder = JobBuilder.newJob(ServiceQuartzJob.class)
                    .withIdentity(jobName, serviceName)
                    .usingJobData(new JobDataMap(parameters))
                    .requestRecovery().storeDurably(jobName ? true : false)
            job = jobBuilder.build()
        }

        TriggerBuilder tb = TriggerBuilder.newTrigger()
                .withIdentity(jobName, serviceName) // for now JobKey = TriggerKey, may want something different...
                .withPriority(3)
                .forJob(job)
        if (startTime) tb.startAt(new Date(startTime))
        if (endTime) tb.endAt(new Date(endTime))

        // NOTE: this allows combinations of different schedules, which may not be allowed...
        if (interval != null) {
            IntervalUnit qiu
            switch (intervalUnit) {
                case TimeUnit.SECONDS: qiu = IntervalUnit.SECOND; break;
                case TimeUnit.MINUTES: qiu = IntervalUnit.MINUTE; break;
                case TimeUnit.HOURS: qiu = IntervalUnit.HOUR; break;
                case TimeUnit.DAYS: qiu = IntervalUnit.DAY; break;
                case TimeUnit.WEEKS: qiu = IntervalUnit.WEEK; break;
                case TimeUnit.MONTHS: qiu = IntervalUnit.MONTH; break;
                case TimeUnit.YEARS: qiu = IntervalUnit.YEAR; break;
            }
            tb.withSchedule(CalendarIntervalScheduleBuilder.calendarIntervalSchedule().withInterval(interval, qiu))
        }
        if (count != null) {
            tb.withSchedule(SimpleScheduleBuilder.simpleSchedule().withRepeatCount(count))
        }
        if (cronString != null) {
            tb.withSchedule(CronScheduleBuilder.cronSchedule(cronString))
        }

        Trigger trigger = tb.build()

        sfi.scheduler.scheduleJob(job, trigger)

        // we did an authz before scheduling, so pop it now
        eci.getArtifactExecution().pop(aei)
    }
}
