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

import com.cronutils.descriptor.CronDescriptor
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
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.ThreadPoolExecutor

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

    private final static CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)
    private final static CronParser parser = new CronParser(cronDefinition)
    private final static Map<String, Cron> cronByExpression = new HashMap<>()
    private long lastExecuteTime = 0
    private int jobQueueMax = 0, executeCount = 0, totalJobsRun = 0, lastJobsActive = 0, lastJobsPaused = 0

    ScheduledJobRunner(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi

        MNode serviceFacadeNode = ecfi.confXmlRoot.first("service-facade")
        jobQueueMax = (serviceFacadeNode.attribute("job-queue-max") ?: "0") as int
    }

    // NOTE: these are called in the service job screens
    long getLastExecuteTime() { lastExecuteTime }
    int getExecuteCount() { executeCount }
    int getTotalJobsRun() { totalJobsRun }
    int getLastJobsActive() { lastJobsActive }
    int getLastJobsPaused() { lastJobsPaused }

    @Override
    synchronized void run() {
        try {
            runInternal()
        } catch (Throwable t) {
            logger.error("Uncaught Throwable in ScheduledJobRunner, catching and suppressing to avoid removal from scheduler", t)
        }
    }
    void runInternal() {
        ZonedDateTime now = ZonedDateTime.now()
        long nowMillis = now.toInstant().toEpochMilli()
        Timestamp nowTimestamp = new Timestamp(nowMillis)
        int jobsRun = 0, jobsActive = 0, jobsPaused = 0, jobsReadyNotRun = 0

        // Get ExecutionContext, just for disable authz
        ExecutionContextImpl eci = ecfi.getEci()
        eci.artifactExecution.disableAuthz()
        EntityFacadeImpl efi = ecfi.entityFacade
        ThreadPoolExecutor jobWorkerPool = ecfi.serviceFacade.jobWorkerPool
        try {
            // make sure no transaction is in place, shouldn't be any so try to commit if there is one
            if (ecfi.transactionFacade.isTransactionInPlace()) {
                logger.error("Found transaction in place in ScheduledJobRunner thread ${Thread.currentThread().getName()}, trying to commit")
                try {
                    ecfi.transactionFacade.destroyAllInThread()
                } catch (Exception e) {
                    logger.error(" Commit of in-place transaction failed for ScheduledJobRunner thread ${Thread.currentThread().getName()}", e)
                }
            }

            // look at jobWorkerPool to see how many jobs we can run: (jobQueueMax + poolMax) - (active + queueSize)
            int jobSlots = jobQueueMax + jobWorkerPool.getMaximumPoolSize()
            int jobsRunning = jobWorkerPool.getActiveCount() + jobWorkerPool.queue.size()
            int jobSlotsAvailable = jobSlots - jobsRunning
            // if we can't handle any more jobs
            if (jobSlotsAvailable <= 0) {
                logger.info("ScheduledJobRunner doing nothing, already ${jobsRunning} of ${jobSlots} jobs running")
            }

            // find scheduled jobs
            EntityList serviceJobList = efi.find("moqui.service.job.ServiceJob").useCache(false)
                    .condition("cronExpression", EntityCondition.ComparisonOperator.NOT_EQUAL, null)
                    .orderBy("priority").orderBy("jobName").list()
            serviceJobList.filterByDate("fromDate", "thruDate", nowTimestamp)
            int serviceJobListSize = serviceJobList.size()
            for (int i = 0; i < serviceJobListSize; i++) {
                EntityValue serviceJob = (EntityValue) serviceJobList.get(i)
                String jobName = (String) serviceJob.jobName
                // a job is ACTIVE if the paused field is null or 'N', so skip for any other value for paused (Y, T, whatever)
                if (serviceJob.paused != null && !"N".equals(serviceJob.paused)) {
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

                    // if no more job slots available continue, don't break because we want to loop through all to get jobsPaused, jobsActive, etc
                    if (jobSlotsAvailable <= 0) {
                        jobsReadyNotRun++
                        continue
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
                } catch (Throwable t) {
                    String errMsg = "Error getting and checking service job run lock"
                    ecfi.transaction.rollback(beganTransaction, errMsg, t)
                    logger.error(errMsg, t)
                    continue
                } finally {
                    ecfi.transaction.commit(beganTransaction)
                }

                jobsRun++
                jobSlotsAvailable--
                if (jobSlotsAvailable <= 0) {
                    logger.info("ScheduledJobRunner out of job slots after running ${jobsRun} jobs, ${jobSlots} jobs running, evaluated ${i} of ${serviceJobListSize} ServiceJob records")
                }

                // at this point jobRunId and serviceJobRunLock should not be null
                ServiceCallJobImpl serviceCallJob = new ServiceCallJobImpl(jobName, ecfi.serviceFacade)
                // use the job run we created
                serviceCallJob.withJobRunId(jobRunId)
                serviceCallJob.withLastRunTime(lastRunTime)
                // clear the lock when finished
                serviceCallJob.clearLock()
                // always run locally to use service job's worker pool and keep queue of pending jobs in the database
                serviceCallJob.localOnly(true)
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

                // end of for loop
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

        int jobSlots = jobQueueMax + jobWorkerPool.getMaximumPoolSize()
        int jobsRunning = jobWorkerPool.getActiveCount() + jobWorkerPool.queue.size()

        if (jobsRun > 0 || logger.isTraceEnabled()) {
            String infoStr = "Ran ${jobsRun} Service Jobs starting ${now} - active: ${jobsActive}, paused: ${jobsPaused}; on this server using ${jobsRunning} of ${jobSlots} job slots"
            if (jobsReadyNotRun > 0) infoStr += ", ${jobsReadyNotRun} jobs ready but not run (insufficient job slots)"
            logger.info(infoStr)
        }
    }

    static Cron getCron(String cronExpression) {
        Cron cachedCron = cronByExpression.get(cronExpression)
        if (cachedCron != null) return cachedCron

        Cron cron = parser.parse(cronExpression)
        cronByExpression.put(cronExpression, cron)

        return cron
    }

    static ExecutionTime getExecutionTime(String cronExpression) { return ExecutionTime.forCron(getCron(cronExpression)) }

    /** Use to determine if it is time to run again, if returns true then run and if false don't run.
     * See if lastRun is before last scheduled run time based on cronExpression and nowTimestamp (defaults to current date/time) */
    static boolean isLastRunBeforeLastSchedule(String cronExpression, Timestamp lastRun, String description, Timestamp nowTimestamp) {
        try {
            if (lastRun == (Timestamp) null) return true
            ZonedDateTime now = nowTimestamp != (Timestamp) null ?
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowTimestamp.getTime()), ZoneId.systemDefault()) :
                    ZonedDateTime.now()
            def lastRunDt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(lastRun.getTime()), now.getZone())

            ExecutionTime executionTime = getExecutionTime(cronExpression)
            ZonedDateTime lastSchedule = executionTime.lastExecution(now).get()

            if (lastSchedule == null) return false
            if (lastRunDt == null) return true

            return lastRunDt.isBefore(lastSchedule)
        } catch (Throwable t) {
            logger.error("Error processing Cron Expression ${cronExpression} and Last Run ${lastRun} for ${description}, skipping", t)
            return false
        }
    }

    static String getCronDescription(String cronExpression, Locale locale, boolean handleInvalid) {
        if (cronExpression == null || cronExpression.isEmpty()) return null
        if (locale == null) locale = Locale.US
        try {
            return CronDescriptor.instance(locale).describe(getCron(cronExpression))
        } catch (Exception e) {
            if (handleInvalid) {
                return "Invalid cron '${cronExpression}': ${e.message}"
            } else {
                throw e
            }
        }
    }
}
