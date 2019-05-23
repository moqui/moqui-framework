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

import com.cronutils.model.Cron
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinition
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import groovy.transform.CompileStatic
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.EntityFacadeImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.Instant
import java.time.ZonedDateTime

/**
 * Runs scheduled jobs as defined in ServiceJob records with a cronExpression. Cron expression uses Quartz flavored syntax.
 *
 * Uses cron-utils for cron processing, see:
 *     https://github.com/jmrozanec/cron-utils
 * For a Quartz cron reference see:
 *     http://www.quartz-scheduler.org/documentation/quartz-2.x/tutorials/crontrigger.html
 *     https://www.quartz-scheduler.org/api/2.2.1/org/quartz/CronExpression.html
 *
 * Handy cron strings: [0 0 2 * * ?] every night at 2:00 am, [0 0/15 * * * ?] every 15 minutes, [0 0/2 * * * ?] every 2 minutes
 */
@CompileStatic
class ScheduledJobRunner implements Runnable {
    private final static Logger logger = LoggerFactory.getLogger(ScheduledJobRunner.class)
    private final ExecutionContextFactoryImpl ecfi

    private final CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)
    private final CronParser parser = new CronParser(cronDefinition)
    private final Map<String, ExecutionTime> executionTimeByExpression = new HashMap<>()
    private long lastExecuteTime = 0
    private int executeCount = 0, totalJobsRun = 0, lastJobsActive = 0, lastJobsPaused = 0

    ScheduledJobRunner(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
    }

    // NOTE: these are called in the service job screens
    long getLastExecuteTime() { lastExecuteTime }
    int getExecuteCount() { executeCount }
    int getTotalJobsRun() { totalJobsRun }
    int getLastJobsActive() { lastJobsActive }
    int getLastJobsPaused() { lastJobsPaused }

    @Override
    synchronized void run() {
        ZonedDateTime now = ZonedDateTime.now()
        long nowMillis = now.toInstant().toEpochMilli()
        Timestamp nowTimestamp = new Timestamp(nowMillis)
        int jobsRun = 0
        int jobsActive = 0
        int jobsPaused = 0

        // Get ExecutionContext, just for disable authz
        ExecutionContextImpl eci = ecfi.getEci()
        eci.artifactExecution.disableAuthz()
        EntityFacadeImpl efi = ecfi.entityFacade
        try {
            // make sure no transaction is in place, shouldn't be any so try to commit if there is one
            if (ecfi.transactionFacade.isTransactionInPlace()) {
                logger.warn("Found transaction in place in ServiceJobRunner thread, trying to commit")
                ecfi.transactionFacade.commit()
            }

            // find scheduled jobs
            EntityList serviceJobList = efi.find("moqui.service.job.ServiceJob").useCache(true)
                    .condition("cronExpression", EntityCondition.ComparisonOperator.NOT_EQUAL, null).list()
            serviceJobList.filterByDate("fromDate", "thruDate", nowTimestamp)
            int serviceJobListSize = serviceJobList.size()
            for (int i = 0; i < serviceJobListSize; i++) {
                EntityValue serviceJob = (EntityValue) serviceJobList.get(i)
                String jobName = (String) serviceJob.jobName
                if ("Y".equals(serviceJob.paused)) {
                    jobsPaused++
                    continue
                }
                if (serviceJob.repeatCount != null) {
                    long repeatCount = ((Long) serviceJob.repeatCount).longValue()
                    long runCount = efi.find("moqui.service.job.ServiceJobRun").condition("jobName", jobName).useCache(false).count()
                    if (runCount >= repeatCount) {
                        // pause the job and set thruDate for faster future filtering
                        ecfi.service.sync().name("update", "moqui.service.job.ServiceJob")
                                .parameters([jobName: jobName, paused:'Y', thruDate:nowTimestamp] as Map<String, Object>)
                                .disableAuthz().call()
                        continue
                    }
                }
                jobsActive++

                String jobRunId
                EntityValue serviceJobRun
                EntityValue serviceJobRunLock
                Timestamp lastRunTime
                // get a lock, see if another instance is running the job
                // now we need to run in a transaction; note that this is running in a executor service thread, no tx should ever be in place
                boolean beganTransaction = ecfi.transaction.begin(null)
                try {
                    serviceJobRunLock = efi.find("moqui.service.job.ServiceJobRunLock")
                            .condition("jobName", jobName).forUpdate(true).one()
                    lastRunTime = (Timestamp) serviceJobRunLock?.lastRunTime
                    ZonedDateTime lastRunDt = (lastRunTime != (Timestamp) null) ?
                            ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastRunTime.getTime()), now.getZone()) : null
                    if (serviceJobRunLock != null && serviceJobRunLock.jobRunId != null && lastRunDt != null) {
                        // for failure with no lock reset: run recovery, based on expireLockTime (default to 1440 minutes)
                        Long expireLockTime = (Long) serviceJob.expireLockTime
                        if (expireLockTime == null) expireLockTime = 1440L
                        ZonedDateTime lockCheckTime = now.minusMinutes(expireLockTime.intValue())
                        if (lastRunDt.isBefore(lockCheckTime)) {
                            // recover failed job without lock reset, run it if schedule says to
                            logger.warn("Lock expired: found lock for job ${jobName} from ${lastRunDt}, more than ${expireLockTime} minutes old, ignoring lock")
                            serviceJobRunLock.set("jobRunId", null).update()
                        } else {
                            // normal lock, skip this job
                            logger.info("Lock found for job ${jobName} from ${lastRunDt} run ID ${serviceJobRunLock.jobRunId}, not running")
                            continue
                        }
                    }

                    // calculate time it should have run last
                    String cronExpression = (String) serviceJob.getNoCheckSimple("cronExpression")
                    ExecutionTime executionTime = getExecutionTime(cronExpression)
                    ZonedDateTime lastSchedule = executionTime.lastExecution(now).get()
                    if (lastSchedule != null && lastRunDt != null) {
                        // if the time it should have run last is before the time it ran last don't run it
                        if (lastSchedule.isBefore(lastRunDt)) continue
                    }

                    // if the last run had an error check the minRetryTime, don't run if hasn't been long enough
                    EntityValue lastJobRun = efi.find("moqui.service.job.ServiceJobRun").condition("jobName", jobName)
                            .orderBy("-startTime").limit(1).useCache(false).list().getFirst()
                    if (lastJobRun != null && "Y".equals(lastJobRun.hasError)) {
                        Timestamp lastErrorTime = (Timestamp) lastJobRun.endTime ?: (Timestamp) lastJobRun.startTime
                        if (lastErrorTime != (Timestamp) null) {
                            ZonedDateTime lastErrorDt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastErrorTime.getTime()), now.getZone())
                            Long minRetryTime = (Long) serviceJob.minRetryTime ?: 5L
                            ZonedDateTime retryCheckTime = now.minusMinutes(minRetryTime.intValue())
                            // if last error time after retry check time don't run the job
                            if (lastErrorDt.isAfter(retryCheckTime)) {
                                logger.info("Not retrying job ${jobName} after error, before ${minRetryTime} min retry minutes (error run at ${lastErrorDt})")
                                continue
                            }
                        }
                    }

                    // create a job run and lock it
                    serviceJobRun = efi.makeValue("moqui.service.job.ServiceJobRun")
                            .set("jobName", jobName).setSequencedIdPrimary().create()
                    jobRunId = (String) serviceJobRun.getNoCheckSimple("jobRunId")

                    if (serviceJobRunLock == null) {
                        serviceJobRunLock = efi.makeValue("moqui.service.job.ServiceJobRunLock").set("jobName", jobName)
                                .set("jobRunId", jobRunId).set("lastRunTime", nowTimestamp).create()
                    } else {
                        serviceJobRunLock.set("jobRunId", jobRunId).set("lastRunTime", nowTimestamp).update()
                    }

                    logger.info("Running job ${jobName} run ${jobRunId} (last run ${lastRunTime}, schedule ${lastSchedule})")
                    jobsRun++
                } catch (Throwable t) {
                    String errMsg = "Error getting and checking service job run lock"
                    ecfi.transaction.rollback(beganTransaction, errMsg, t)
                    logger.error(errMsg, t)
                    continue
                } finally {
                    ecfi.transaction.commit(beganTransaction)
                }

                // at this point jobRunId and serviceJobRunLock should not be null
                ServiceCallJobImpl serviceCallJob = new ServiceCallJobImpl(jobName, ecfi.serviceFacade)
                // use the job run we created
                serviceCallJob.withJobRunId(jobRunId)
                serviceCallJob.withLastRunTime(lastRunTime)
                // clear the lock when finished
                serviceCallJob.clearLock()
                // run it, will run async
                try {
                    serviceCallJob.run()
                } catch (Throwable t) {
                    logger.error("Error running scheduled job ${jobName}", t)
                    ecfi.transactionFacade.runUseOrBegin(null, "Error clearing lock and saving error on scheduled job run error", {
                        serviceJobRunLock.set("jobRunId", null).update()
                        serviceJobRun.set("hasError", "Y").set("errors", t.toString()).set("startTime", nowTimestamp)
                                .set("endTime", nowTimestamp).update()
                    })
                }
            }
        } catch (Throwable t) {
            logger.error("Uncaught error in scheduled job runner", t)
        } finally {
            // no need, we're destroying the eci: if (!authzDisabled) eci.artifactExecution.enableAuthz()
            eci.destroy()
        }

        // update job runner stats
        lastExecuteTime = nowMillis
        executeCount++
        totalJobsRun += jobsRun
        lastJobsActive = jobsActive
        lastJobsPaused = jobsPaused

        if (jobsRun > 0) {
            logger.info("Ran ${jobsRun} Service Jobs starting ${now} (active: ${jobsActive}, paused: ${jobsPaused})")
        } else if (logger.isTraceEnabled()) {
            logger.trace("Ran ${jobsRun} Service Jobs starting ${now} (active: ${jobsActive}, paused: ${jobsPaused})")
        }
    }

    // ExecutionTime appears to be reusable, so cache by cronExpression
    ExecutionTime getExecutionTime(String cronExpression) {
        ExecutionTime cachedEt = executionTimeByExpression.get(cronExpression)
        if (cachedEt != null) return cachedEt

        Cron cron = parser.parse(cronExpression)
        ExecutionTime executionTime = ExecutionTime.forCron(cron)

        executionTimeByExpression.put(cronExpression, executionTime)
        return executionTime
    }
}
