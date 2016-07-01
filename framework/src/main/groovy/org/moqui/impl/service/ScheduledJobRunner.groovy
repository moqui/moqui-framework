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
import org.joda.time.DateTime
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.EntityFacadeImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

@CompileStatic
class ScheduledJobRunner implements Runnable {
    private final static Logger logger = LoggerFactory.getLogger(ScheduledJobRunner.class)
    private final ExecutionContextFactoryImpl ecfi

    private final CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ)
    private final CronParser parser = new CronParser(cronDefinition)
    private final Map<String, ExecutionTime> executionTimeByExpression = new HashMap<>()
    private long lastExecuteTime = 0

    ScheduledJobRunner(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
    }

    long getLastExecuteTime() { lastExecuteTime }

    @Override
    synchronized void run() {
        DateTime now = DateTime.now()
        lastExecuteTime = now.getMillis()
        Timestamp nowTimestamp = new Timestamp(now.getMillis())
        int jobsRun = 0
        int jobsActive = 0
        int jobsPaused = 0

        // Get ExecutionContext, just for disable authz
        ExecutionContextImpl eci = ecfi.getEci()
        eci.artifactExecution.disableAuthz()
        Collection<EntityFacadeImpl> allEntityFacades = ecfi.getAllEntityFacades()
        try {
            // run for each active tenant
            for (EntityFacadeImpl efi in allEntityFacades) {
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
                    // get a lock, see if another instance is running the job
                    // now we need to run in a transaction; note that this is running in a executor service thread, no tx should ever be in place
                    boolean beganTransaction = ecfi.transaction.begin(null)
                    try {
                        serviceJobRunLock = efi.find("moqui.service.job.ServiceJobRunLock")
                                .condition("jobName", jobName).forUpdate(true).one()
                        Timestamp lastRunTime = (Timestamp) serviceJobRunLock?.lastRunTime
                        DateTime lastRunDt = (lastRunTime != (Timestamp) null) ? new DateTime(lastRunTime.getTime()) : null
                        if (serviceJobRunLock != null && serviceJobRunLock.jobRunId != null && lastRunDt != null) {
                            // for failure with no lock reset: run recovery, based on expireLockTime (default to 1440 minutes)
                            Long expireLockTime = (Long) serviceJob.expireLockTime
                            if (expireLockTime == null) expireLockTime = 1440L
                            DateTime lockCheckTime = now.minusMinutes(expireLockTime.intValue())
                            if (lastRunDt.isBefore(lockCheckTime)) {
                                // recover failed job without lock reset, run it if schedule says to
                                logger.warn("Lock expired: found lock for job ${jobName} from ${lastRunDt}, more than ${expireLockTime} minutes old, ignoring lock")
                                serviceJobRunLock.set("jobRunId", null).update()
                            } else {
                                // normal lock, skip this job
                                continue
                            }
                        }

                        // calculate time it should have run last
                        String cronExpression = serviceJob.cronExpression
                        ExecutionTime executionTime = getExecutionTime(cronExpression)
                        DateTime lastSchedule = executionTime.lastExecution(now)
                        if (lastRunDt != null) {
                            // if the time it should have run last is before the time it ran last don't run it
                            if (lastSchedule.isBefore(lastRunDt)) continue
                        }

                        // create a job run and lock it
                        serviceJobRun = efi.makeValue("moqui.service.job.ServiceJobRun")
                                .set("jobName", jobName).setSequencedIdPrimary().create()
                        jobRunId = (String) serviceJobRun.get("jobRunId")

                        if (serviceJobRunLock == null) {
                            serviceJobRunLock = efi.makeValue("moqui.service.job.ServiceJobRunLock")
                                    .set("jobName", jobName).set("jobRunId", jobRunId)
                                    .set("lastRunTime", nowTimestamp).create()
                        } else {
                            serviceJobRunLock.set("jobRunId", jobRunId)
                                    .set("lastRunTime", nowTimestamp).update()
                        }

                        logger.info("Running job ${jobName} run ${jobRunId} in tenant ${efi.tenantId} (last run ${lastRunTime}, schedule ${lastSchedule})")
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
                    ServiceCallJobImpl serviceCallJob = new ServiceCallJobImpl(jobName, ecfi.getServiceFacade())
                    // use the job run we created
                    serviceCallJob.withJobRunId(jobRunId)
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
            }
        } catch (Throwable t) {
            logger.error("Uncaught error in scheduled job runner", t)
        } finally {
            // no need, we're destroying the eci: if (!authzDisabled) eci.artifactExecution.enableAuthz()
            eci.destroy()
        }

        // TODO: change this to trace at some point once more tested
        logger.info("Ran ${jobsRun} Service Jobs starting ${now} (active: ${jobsActive}, paused: ${jobsPaused}, tenants: ${allEntityFacades.size()})")
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
