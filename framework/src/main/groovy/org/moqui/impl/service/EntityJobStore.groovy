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

import org.moqui.Moqui
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityConditionFactory
import org.moqui.entity.EntityException
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextFactoryImpl

import org.quartz.Job
import org.quartz.JobDataMap
import org.quartz.JobDetail
import org.quartz.JobKey
import org.quartz.JobPersistenceException
import org.quartz.ObjectAlreadyExistsException
import org.quartz.Scheduler
import org.quartz.SchedulerConfigException
import org.quartz.SchedulerException
import org.quartz.SimpleTrigger
import org.quartz.Trigger
import org.quartz.Trigger.CompletedExecutionInstruction
import org.quartz.Trigger.TriggerState
import org.quartz.TriggerKey
import org.quartz.impl.JobDetailImpl
import org.quartz.impl.jdbcjobstore.Constants
import org.quartz.impl.jdbcjobstore.FiredTriggerRecord
import org.quartz.impl.jdbcjobstore.TriggerStatus
import org.quartz.impl.matchers.GroupMatcher
import org.quartz.impl.matchers.StringMatcher
import org.quartz.impl.triggers.SimpleTriggerImpl
import org.quartz.spi.ClassLoadHelper
import org.quartz.spi.JobStore
import org.quartz.spi.OperableTrigger
import org.quartz.spi.SchedulerSignaler
import org.quartz.spi.TriggerFiredBundle
import org.quartz.spi.TriggerFiredResult

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.sql.rowset.serial.SerialBlob
import java.util.concurrent.atomic.AtomicLong

// NOTE: Implementing a Quartz JobStore is a HUGE PITA, Quartz puts a lot of scheduler and state handling logic in the
//     JobStore. The code here is based mostly on the JobStoreSupport class to replicate that logic.

class EntityJobStore implements JobStore {
    protected final static Logger logger = LoggerFactory.getLogger(EntityJobStore.class)

    protected ClassLoadHelper classLoadHelper
    protected SchedulerSignaler schedulerSignaler

    protected boolean schedulerRunning = false
    protected boolean shutdown = false

    protected String instanceId, instanceName
    protected long misfireThreshold = 60000L // one minute
    protected int maxToRecoverAtATime = 20

    public long getMisfireThreshold() { return misfireThreshold }
    public void setMisfireThreshold(long misfireThreshold) {
        if (misfireThreshold < 1) throw new IllegalArgumentException("MisfireThreshold must be larger than 0")
        this.misfireThreshold = misfireThreshold
    }
    protected long getMisfireTime() {
        long misfireTime = System.currentTimeMillis()
        if (getMisfireThreshold() > 0) misfireTime -= getMisfireThreshold()
        return misfireTime > 0 ? misfireTime : 0
    }
    public void setMaxMisfiresToHandleAtATime(int maxToRecoverAtATime) { this.maxToRecoverAtATime = maxToRecoverAtATime }

    ExecutionContextFactoryImpl getEcfi() {
        ExecutionContextFactoryImpl executionContextFactory = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
        if (executionContextFactory == null) throw new IllegalStateException("ExecutionContextFactory not yet initialized")
        return executionContextFactory
    }

    @Override
    void initialize(ClassLoadHelper classLoadHelper, SchedulerSignaler schedulerSignaler) throws SchedulerConfigException {
        this.classLoadHelper = classLoadHelper
        this.schedulerSignaler = schedulerSignaler
    }

    @Override
    void schedulerStarted() throws SchedulerException {
        // recover jobs
        try {
            recoverJobs()
        } catch (Exception e) {
            throw new SchedulerConfigException("Failure occured during job recovery.", e)
        }

        // TODO: is MisfireHandler needed?

        schedulerRunning = true
    }

    protected void recoverJobs() throws JobPersistenceException {
        boolean beganTransaction = ecfi.transactionFacade.begin(0)
        try {
            // update inconsistent job states
            int rows = updateTriggerStatesFromOtherStates(Constants.STATE_WAITING, [Constants.STATE_ACQUIRED, Constants.STATE_BLOCKED])
            rows += updateTriggerStatesFromOtherStates(Constants.STATE_PAUSED, [Constants.STATE_PAUSED_BLOCKED])

            logger.info("Freed " + rows + " triggers from 'acquired' / 'blocked' state.")

            // clean up misfired jobs
            recoverMisfiredJobs(true)

            // recover jobs marked for recovery that were not fully executed
            List<OperableTrigger> recoveringJobTriggers = selectTriggersForRecoveringJobs()
            logger.info("Recovering " + recoveringJobTriggers.size() + " jobs that were in-progress at the time of the last shut-down.")

            for (OperableTrigger recoveringJobTrigger: recoveringJobTriggers) {
                if (checkExists(recoveringJobTrigger.getJobKey())) {
                    recoveringJobTrigger.computeFirstFireTime(null)
                    storeTrigger(recoveringJobTrigger, null, false, Constants.STATE_WAITING, false, true)
                }
            }
            logger.info("Quartz recovery complete")

            // remove lingering 'complete' triggers...
            EntityList triggerList = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers")
                    .condition([schedName:instanceName, triggerState:Constants.STATE_COMPLETE])
                    .disableAuthz().list()
            for (EntityValue triggerValue in triggerList) {
                TriggerKey ct = new TriggerKey((String) triggerValue.triggerName, (String) triggerValue.triggerGroup)
                removeTrigger(ct)
            }
            logger.info("Removed " + triggerList.size() + " 'complete' triggers")

            // clean up any fired trigger entries
            int n = ecfi.entityFacade.find("moqui.service.quartz.QrtzFiredTriggers")
                    .condition([schedName:instanceName]).disableAuthz().deleteAll()
            logger.info("Removed " + n + " stale fired job entries")
        } catch (JobPersistenceException e) {
            ecfi.transactionFacade.rollback(beganTransaction, "Error in recoverJobs", e)
            throw e
        } catch (Exception e) {
            ecfi.transactionFacade.rollback(beganTransaction, "Error in recoverJobs", e)
            throw new JobPersistenceException("Couldn't recover jobs: " + e.getMessage(), e)
        } catch (Throwable t) {
            ecfi.transactionFacade.rollback(beganTransaction, "Error in recoverJobs", t)
            throw t
        } finally {
            if (ecfi.transactionFacade.isTransactionInPlace()) ecfi.transactionFacade.commit(beganTransaction)
        }
    }

    protected static class RecoverMisfiredJobsResult {
        public static final RecoverMisfiredJobsResult NO_OP = new RecoverMisfiredJobsResult(false, 0, Long.MAX_VALUE)

        private boolean _hasMoreMisfiredTriggers
        private int _processedMisfiredTriggerCount
        private long _earliestNewTime

        public RecoverMisfiredJobsResult(boolean hasMoreMisfiredTriggers, int processedMisfiredTriggerCount, long earliestNewTime) {
            _hasMoreMisfiredTriggers = hasMoreMisfiredTriggers
            _processedMisfiredTriggerCount = processedMisfiredTriggerCount
            _earliestNewTime = earliestNewTime
        }

        public boolean hasMoreMisfiredTriggers() { return _hasMoreMisfiredTriggers }
        public int getProcessedMisfiredTriggerCount() { return _processedMisfiredTriggerCount }
        public long getEarliestNewTime() { return _earliestNewTime }
    }
    protected RecoverMisfiredJobsResult recoverMisfiredJobs(boolean recovering) throws JobPersistenceException {
        // If recovering, we want to handle all of the misfired
        // triggers right away.
        int maxMisfiresToHandleAtATime = recovering ? -1 : maxToRecoverAtATime

        List<TriggerKey> misfiredTriggers = new LinkedList<TriggerKey>()
        long earliestNewTime = Long.MAX_VALUE;
        // We must still look for the MISFIRED state in case triggers were left
        // in this state when upgrading to this version that does not support it.
        boolean hasMoreMisfiredTriggers = hasMisfiredTriggersInState(Constants.STATE_WAITING, getMisfireTime(),
                maxMisfiresToHandleAtATime, misfiredTriggers)

        if (hasMoreMisfiredTriggers) {
            logger.info("Handling the first " + misfiredTriggers.size() +" triggers that missed their scheduled fire-time. More misfired triggers remain to be processed.")
        } else if (misfiredTriggers.size() > 0) {
            logger.info("Handling " + misfiredTriggers.size() + " trigger(s) that missed their scheduled fire-time.")
        } else {
            logger.debug("Found 0 triggers that missed their scheduled fire-time.")
            return RecoverMisfiredJobsResult.NO_OP;
        }

        for (TriggerKey triggerKey in misfiredTriggers) {
            OperableTrigger trig = retrieveTrigger(triggerKey)
            if (trig == null) continue

            doUpdateOfMisfiredTrigger(trig, false, Constants.STATE_WAITING, recovering)

            if(trig.getNextFireTime() != null && trig.getNextFireTime().getTime() < earliestNewTime)
                earliestNewTime = trig.getNextFireTime().getTime();
        }

        return new RecoverMisfiredJobsResult(hasMoreMisfiredTriggers, misfiredTriggers.size(), earliestNewTime)
    }
    public boolean hasMisfiredTriggersInState(String state1, long ts, int count, List<TriggerKey> resultList) {
        EntityList triggerList = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers")
                .condition([schedName:instanceName, triggerState:state1])
                .condition("misfireInstr", EntityCondition.NOT_EQUAL, Trigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY)
                .condition("nextFireTime", EntityCondition.LESS_THAN, ts)
                .disableAuthz().list()

        boolean hasReachedLimit = false
        for (EntityValue trigger in triggerList) {
            if (resultList.size() == count) {
                hasReachedLimit = true
                break
            } else {
                resultList.add(new TriggerKey((String) trigger.triggerName, (String) trigger.triggerGroup))
            }
        }

        return hasReachedLimit
    }

    public List<OperableTrigger> selectTriggersForRecoveringJobs() throws IOException, ClassNotFoundException {
        EntityList triggerList = ecfi.entityFacade.find("moqui.service.quartz.QrtzFiredTriggers")
                .condition([schedName:instanceName, instanceName:instanceId, requestsRecovery:"T"])
                .disableAuthz().list()

        long dumId = System.currentTimeMillis()
        LinkedList<OperableTrigger> list = new LinkedList<OperableTrigger>()
        for (EntityValue ev in triggerList) {
            SimpleTriggerImpl rcvryTrig = new SimpleTriggerImpl("recover_" + instanceId + "_" + String.valueOf(dumId++),
                    Scheduler.DEFAULT_RECOVERY_GROUP, new Date(ev.getLong("schedTime")))
            rcvryTrig.setJobName((String) ev.jobName)
            rcvryTrig.setJobGroup((String) ev.jobGroup)
            rcvryTrig.setPriority(ev.priority as int)
            rcvryTrig.setMisfireInstruction(SimpleTrigger.MISFIRE_INSTRUCTION_IGNORE_MISFIRE_POLICY)

            Map triggerMap = [schedName:instanceName, triggerName:ev.triggerName, triggerGroup:ev.triggerGroup]
            EntityValue triggerValue = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers").condition(triggerMap).disableAuthz().one()
            JobDataMap jd = new JobDataMap()
            if (triggerValue != null && triggerValue.jobData != null && triggerValue.getSerialBlob("jobData").length() > 0) {
                ObjectInputStream ois = new ObjectInputStream(triggerValue.getSerialBlob("jobData").binaryStream)
                try { jd = (JobDataMap) ois.readObject() } finally { ois.close() }
            }

            jd.put(Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_NAME, (String) ev.triggerName)
            jd.put(Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_GROUP, (String) ev.triggerGroup)
            jd.put(Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_FIRETIME_IN_MILLISECONDS, ev.firedTime as String)
            jd.put(Scheduler.FAILED_JOB_ORIGINAL_TRIGGER_SCHEDULED_FIRETIME_IN_MILLISECONDS, ev.schedTime as String)
            rcvryTrig.setJobDataMap(jd)

            list.add(rcvryTrig)
        }
        return list
    }

    @Override
    void schedulerPaused() { schedulerRunning = false }
    @Override
    void schedulerResumed() { schedulerRunning = true }
    @Override
    void shutdown() { shutdown = true }

    @Override
    boolean supportsPersistence() { return true }
    @Override
    long getEstimatedTimeToReleaseAndAcquireTrigger() { return 70 }
    @Override
    boolean isClustered() { return true }

    @Override
    void storeJobAndTrigger(JobDetail jobDetail, OperableTrigger operableTrigger) throws ObjectAlreadyExistsException, JobPersistenceException {
        storeJob(jobDetail, false)
        storeTrigger(operableTrigger, jobDetail, false, Constants.STATE_WAITING, false, false)
    }

    @Override
    void storeJob(JobDetail job, boolean replaceExisting) throws ObjectAlreadyExistsException, JobPersistenceException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        if (job.jobDataMap) {
            ObjectOutputStream out = new ObjectOutputStream(baos)
            out.writeObject(job.jobDataMap)
            out.flush()
        }
        byte[] jobData = baos.toByteArray()
        Map jobMap = [schedName:instanceName, jobName:job.key.name, jobGroup:job.key.group,
                description:job.description, jobClassName:job.jobClass.name,
                isDurable:(job.isDurable() ? "T" : "F"),
                isNonconcurrent:(job.isConcurrentExectionDisallowed() ? "T" : "F"),
                isUpdateData:(job.isPersistJobDataAfterExecution() ? "T" : "F"),
                requestsRecovery:(job.requestsRecovery() ? "T" : "F"), jobData:new SerialBlob(jobData)]
        if (checkExists(job.getKey())) {
            if (replaceExisting) {
                ecfi.serviceFacade.sync().name("update#moqui.service.quartz.QrtzJobDetails").parameters(jobMap).disableAuthz().call()
            } else {
                throw new ObjectAlreadyExistsException(job)
            }
        } else {
            ecfi.serviceFacade.sync().name("create#moqui.service.quartz.QrtzJobDetails").parameters(jobMap).disableAuthz().call()
        }
    }

    @Override
    void storeTrigger(OperableTrigger trigger, boolean replaceExisting) throws ObjectAlreadyExistsException, JobPersistenceException {
        storeTrigger(trigger, null, replaceExisting, Constants.STATE_WAITING, false, false)
    }
    protected void storeTrigger(OperableTrigger trigger, JobDetail job, boolean replaceExisting, String state,
                                boolean forceState, boolean recovering)
            throws ObjectAlreadyExistsException, JobPersistenceException {

        boolean triggerExists = checkExists(trigger.getKey())
        if (triggerExists && !replaceExisting) throw new ObjectAlreadyExistsException(trigger)

        state = state ?: Constants.STATE_WAITING

        try {
            boolean shouldBePaused
            if (!forceState) {
                shouldBePaused = isTriggerGroupPaused(trigger.getKey().getGroup())
                if(!shouldBePaused) {
                    shouldBePaused = isTriggerGroupPaused(Constants.ALL_GROUPS_PAUSED)
                    if (shouldBePaused) insertPausedTriggerGroup(trigger.getKey().getGroup());
                }
                if (shouldBePaused && (state == Constants.STATE_WAITING || state == Constants.STATE_ACQUIRED))
                    state = Constants.STATE_PAUSED
            }

            if (job == null) job = retrieveJob(trigger.getJobKey())
            if (job == null) throw new JobPersistenceException("The job (${trigger.getJobKey()}) referenced by the trigger does not exist.")

            if (job.isConcurrentExectionDisallowed() && !recovering) state = checkBlockedState(job.getKey(), state)

            ByteArrayOutputStream baos = new ByteArrayOutputStream()
            if (trigger.jobDataMap) {
                ObjectOutputStream out = new ObjectOutputStream(baos)
                out.writeObject(trigger.jobDataMap)
                out.flush()
            }
            byte[] jobData = baos.toByteArray()
            Map triggerMap = [schedName:instanceName, triggerName:trigger.key.name, triggerGroup:trigger.key.group,
                    jobName:trigger.jobKey.name, jobGroup:trigger.jobKey.group, description:trigger.description,
                    nextFireTime:trigger.nextFireTime?.time, prevFireTime:trigger.previousFireTime?.time,
                    priority:trigger.priority, triggerState:state, triggerType:Constants.TTYPE_BLOB,
                    startTime:trigger.startTime?.time, endTime:trigger.endTime?.time, calendarName:trigger.calendarName,
                    misfireInstr:trigger.misfireInstruction, jobData:new SerialBlob(jobData)]

            ByteArrayOutputStream baosTrig = new ByteArrayOutputStream()
            if (trigger.jobDataMap != null) {
                ObjectOutputStream out = new ObjectOutputStream(baosTrig)
                out.writeObject(trigger)
                out.flush()
            }
            byte[] triggerData = baosTrig.toByteArray()
            Map triggerBlobMap = [schedName:instanceName, triggerName:trigger.key.name, triggerGroup:trigger.key.group,
                    blobData:new SerialBlob(triggerData)]

            if (triggerExists) {
                ecfi.serviceFacade.sync().name("update#moqui.service.quartz.QrtzTriggers").parameters(triggerMap)
                        .disableAuthz().call()
                // TODO handle TriggerPersistenceDelegate (for create and update)?
                // uses QrtzSimpleTriggers, QrtzCronTriggers, QrtzSimpropTriggers
                // TriggerPersistenceDelegate tDel = findTriggerPersistenceDelegate(trigger)
                // tDel.updateExtendedTriggerProperties(conn, trigger, state, jobDetail)
                ecfi.serviceFacade.sync().name("update#moqui.service.quartz.QrtzBlobTriggers").parameters(triggerBlobMap)
                        .disableAuthz().call()
            } else {
                ecfi.serviceFacade.sync().name("create#moqui.service.quartz.QrtzTriggers").parameters(triggerMap)
                        .disableAuthz().call()
                ecfi.serviceFacade.sync().name("create#moqui.service.quartz.QrtzBlobTriggers").parameters(triggerBlobMap)
                        .disableAuthz().call()
            }
        } catch (Exception e) {
            throw new JobPersistenceException("Couldn't store trigger '${trigger.getKey()}' for '${trigger.getJobKey()}' job: ${e.getMessage()}", e)
        }
    }

    protected String checkBlockedState(JobKey jobKey, String currentState) throws JobPersistenceException {
        // State can only transition to BLOCKED from PAUSED or WAITING.
        if (currentState != Constants.STATE_WAITING && currentState != Constants.STATE_PAUSED) return currentState

        List<FiredTriggerRecord> lst = selectFiredTriggerRecordsByJob(jobKey.getName(), jobKey.getGroup())
        if (lst.size() > 0) {
            FiredTriggerRecord rec = lst.get(0)
            if (rec.isJobDisallowsConcurrentExecution()) {
                return (Constants.STATE_PAUSED == currentState) ? Constants.STATE_PAUSED_BLOCKED : Constants.STATE_BLOCKED
            }
        }

        return currentState;
    }
    protected List<FiredTriggerRecord> selectFiredTriggerRecordsByJob(String jobName, String jobGroup) {
        List<FiredTriggerRecord> lst = new LinkedList<FiredTriggerRecord>()

        Map ftMap = [schedName:instanceName, jobGroup:jobGroup]
        if (jobName) ftMap.put("jobName", jobName)
        EntityList qftList = ecfi.entityFacade.find("moqui.service.quartz.QrtzFiredTriggers").condition(ftMap).disableAuthz().list()

        for (EntityValue qft in qftList) {
            FiredTriggerRecord rec = new FiredTriggerRecord()
            rec.setFireInstanceId((String) qft.entryId)
            rec.setFireInstanceState((String) qft.state)
            rec.setFireTimestamp((long) qft.firedTime)
            rec.setScheduleTimestamp((long) qft.schedTime)
            rec.setPriority((int) qft.priority)
            rec.setSchedulerInstanceId((String) qft.instanceName)
            rec.setTriggerKey(new TriggerKey((String) qft.triggerName, (String) qft.triggerGroup))
            if (!rec.getFireInstanceState().equals(Constants.STATE_ACQUIRED)) {
                rec.setJobDisallowsConcurrentExecution(qft.isNonconcurrent == "T")
                rec.setJobRequestsRecovery(qft.requestsRecovery == "T")
                rec.setJobKey(new JobKey((String) qft.jobName, (String) qft.jobGroup))
            }
            lst.add(rec);
        }

        return lst
    }

    protected boolean isTriggerGroupPaused(String triggerGroup) {
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzPausedTriggerGrps")
                .condition([schedName:instanceName, triggerGroup:triggerGroup]).disableAuthz().count() > 0
    }
    protected void insertPausedTriggerGroup(String triggerGroup) {
        ecfi.serviceFacade.sync().name("create#moqui.service.quartz.QrtzPausedTriggerGrps")
                .parameters([schedName:instanceName, triggerGroup:triggerGroup]).disableAuthz().call()
    }
    protected void deletePausedTriggerGroup(String triggerGroup) {
        ecfi.serviceFacade.sync().name("delete#moqui.service.quartz.QrtzPausedTriggerGrps")
                .parameters([schedName:instanceName, triggerGroup:triggerGroup]).disableAuthz().call()
    }
    protected int deletePausedTriggerGroup(GroupMatcher<TriggerKey> matcher) {
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzPausedTriggerGrps")
                .condition([schedName:instanceName]).condition("triggerGroup", EntityCondition.LIKE, toSqlLikeClause(matcher))
                .disableAuthz().deleteAll()
    }

    protected boolean updateMisfiredTrigger(TriggerKey triggerKey, String newStateIfNotComplete, boolean forceState)
            throws JobPersistenceException {
        OperableTrigger trig = retrieveTrigger(triggerKey)

        long misfireTime = System.currentTimeMillis()
        if (getMisfireThreshold() > 0) misfireTime -= getMisfireThreshold()
        if (trig.getNextFireTime().getTime() > misfireTime) return false

        doUpdateOfMisfiredTrigger(trig, forceState, newStateIfNotComplete, false)
        return true
    }
    private void doUpdateOfMisfiredTrigger(OperableTrigger trig, boolean forceState, String newStateIfNotComplete,
                                           boolean recovering) throws JobPersistenceException {
        org.quartz.Calendar cal = null
        if (trig.getCalendarName() != null) cal = retrieveCalendar(trig.getCalendarName())

        this.schedulerSignaler.notifyTriggerListenersMisfired(trig)
        trig.updateAfterMisfire(cal)

        if (trig.getNextFireTime() == null) {
            storeTrigger(trig, null, true, Constants.STATE_COMPLETE, forceState, recovering)
            this.schedulerSignaler.notifySchedulerListenersFinalized(trig)
        } else {
            storeTrigger(trig, null, true, newStateIfNotComplete, forceState, false)
        }
    }

    @Override
    void storeJobsAndTriggers(Map<JobDetail, Set<? extends Trigger>> jobDetailSetMap, boolean replaceExisting) throws ObjectAlreadyExistsException, JobPersistenceException {
        for (Map.Entry<JobDetail, Set<? extends Trigger>> e: jobDetailSetMap.entrySet()) {
            storeJob(e.getKey(), replaceExisting)
            for (Trigger trigger: e.getValue()) storeTrigger((OperableTrigger) trigger, replaceExisting)
        }
    }

    @Override
    boolean removeJob(JobKey jobKey) throws JobPersistenceException {
        Map jobMap = [schedName:instanceName, jobName:jobKey.name, jobGroup:jobKey.group]
        // remove all job triggers
        ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers").condition(jobMap).disableAuthz().deleteAll()
        // remove job
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzJobDetails").condition(jobMap).disableAuthz().deleteAll() as boolean
    }

    @Override
    boolean removeJobs(List<JobKey> jobKeys) throws JobPersistenceException {
        boolean allFound = true
        for (JobKey jobKey in jobKeys) allFound = removeJob(jobKey) && allFound
        return allFound
    }

    @Override
    JobDetail retrieveJob(JobKey jobKey) throws JobPersistenceException {
        try {
            Map jobMap = [schedName:instanceName, jobName:jobKey.name, jobGroup:jobKey.group]
            EntityValue jobValue = ecfi.entityFacade.find("moqui.service.quartz.QrtzJobDetails").condition(jobMap).disableAuthz().one()
            if (!jobValue) return null
            JobDetailImpl job = new JobDetailImpl()

            job.setName((String) jobValue.jobName)
            job.setGroup((String) jobValue.jobGroup)
            job.setDescription((String) jobValue.description)
            job.setJobClass(classLoadHelper.loadClass((String) jobValue.jobClassName, Job.class));
            job.setDurability(jobValue.isDurable == "T")
            job.setRequestsRecovery(jobValue.requestsRecovery == "T")
            // NOTE: StdJDBCDelegate doesn't set these, but do we need them? isNonconcurrent, isUpdateData

            Map jobDataMap = null
            if (jobValue.jobData != null && jobValue.getSerialBlob("jobData").length() > 0) {
                ObjectInputStream ois = new ObjectInputStream(jobValue.getSerialBlob("jobData").binaryStream)
                try { jobDataMap = (Map) ois.readObject() } finally { ois.close() }
            }
            // NOTE: need this? if (canUseProperties()) map = getMapFromProperties(rs);
            if (jobDataMap) job.setJobDataMap(new JobDataMap(jobDataMap))

            return job
        } catch (ClassNotFoundException e) {
            throw new JobPersistenceException("Couldn't retrieve job because a required class was not found: ${e.getMessage()}", e)
        } catch (IOException e) {
            throw new JobPersistenceException("Couldn't retrieve job because the BLOB couldn't be deserialized: ${e.getMessage()}", e)
        } catch (EntityException e) {
            throw new JobPersistenceException("Couldn't retrieve job: ${e.getMessage()}", e)
        }
    }

    @Override
    boolean removeTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        // delete the trigger
        Map triggerMap = [schedName:instanceName, triggerName:triggerKey.name, triggerGroup:triggerKey.group]
        EntityValue triggerValue = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers").condition(triggerMap).disableAuthz().one()
        if (triggerValue == null) return false
        ecfi.serviceFacade.sync().name("delete#moqui.service.quartz.QrtzBlobTriggers").parameters(triggerMap).disableAuthz().call()
        ecfi.serviceFacade.sync().name("delete#moqui.service.quartz.QrtzTriggers").parameters(triggerMap).disableAuthz().call()

        // if there are no other triggers for the job, delete the job
        Map jobMap = [schedName:instanceName, jobName:triggerValue.jobName, jobGroup:triggerValue.jobGroup]
        EntityList jobTriggers = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers").condition(jobMap).disableAuthz().list()
        if (!jobTriggers) ecfi.entityFacade.find("moqui.service.quartz.QrtzJobDetails").condition(jobMap).disableAuthz().deleteAll()

        return true
    }

    @Override
    boolean removeTriggers(List<TriggerKey> triggerKeys) throws JobPersistenceException {
        boolean allFound = true
        for (TriggerKey triggerKey in triggerKeys) allFound = removeTrigger(triggerKey) && allFound
        return allFound
    }

    @Override
    boolean replaceTrigger(TriggerKey triggerKey, OperableTrigger operableTrigger) throws JobPersistenceException {
        // get the existing trigger and job
        Map triggerMap = [schedName:instanceName, triggerName:triggerKey.name, triggerGroup:triggerKey.group]
        EntityValue triggerValue = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers").condition(triggerMap).disableAuthz().one()
        if (triggerValue == null) return false
        JobDetail job = retrieveJob(new JobKey((String) triggerValue.jobName, (String) triggerValue.jobGroup))

        // delete the old trigger
        removeTrigger(triggerKey)

        // create the new one
        storeTrigger(operableTrigger, job, false, Constants.STATE_WAITING, false, false)

        return true
    }

    @Override
    OperableTrigger retrieveTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        Map triggerMap = [schedName:instanceName, triggerName:triggerKey.name, triggerGroup:triggerKey.group]
        EntityValue triggerValue = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers").condition(triggerMap).disableAuthz().one()
        if (triggerValue == null) return null

        if (triggerValue.triggerType != Constants.TTYPE_BLOB)
            throw new JobPersistenceException("Trigger ${triggerValue.triggerName}:${triggerValue.triggerGroup} with type ${triggerValue.triggerType} cannot be retrieved, only blob type triggers currently supported.")
        return getTriggerFromBlob(triggerKey.name, triggerKey.group)
    }
    OperableTrigger getTriggerFromBlob(String triggerName, String triggerGroup) {
        Map triggerMap = [schedName:instanceName, triggerName:triggerName, triggerGroup:triggerGroup]

        EntityValue blobTriggerValue = ecfi.entityFacade.find("moqui.service.quartz.QrtzBlobTriggers").condition(triggerMap).disableAuthz().one()
        if (!blobTriggerValue) {
            logger.warn("Count not find blob for trigger ${triggerName}:${triggerGroup}")
            return null
            // throw new JobPersistenceException("Count not find blob for trigger ${triggerName}:${triggerGroup}")
        }

        OperableTrigger trigger = null
        ObjectInputStream ois = new ObjectInputStream(blobTriggerValue.getSerialBlob("blobData").binaryStream)
        try { trigger = (OperableTrigger) ois.readObject() } finally { ois.close() }
        return trigger
    }

    @Override
    boolean checkExists(JobKey jobKey) throws JobPersistenceException {
        return ecfi.getEntityFacade().find("moqui.service.quartz.QrtzJobDetails")
                .condition([schedName:instanceName, jobName:jobKey.name, jobGroup:jobKey.group]).disableAuthz().count() > 0
    }

    @Override
    boolean checkExists(TriggerKey triggerKey) throws JobPersistenceException {
        return ecfi.getEntityFacade().find("moqui.service.quartz.QrtzTriggers")
                .condition([schedName:instanceName, triggerName:triggerKey.name, triggerGroup:triggerKey.group])
                .disableAuthz().count() > 0
    }

    @Override
    void clearAllSchedulingData() throws JobPersistenceException {
        ecfi.getEntityFacade().find("moqui.service.quartz.QrtzSimpleTriggers").condition([schedName:instanceName]).deleteAll()
        ecfi.getEntityFacade().find("moqui.service.quartz.QrtzCronTriggers").condition([schedName:instanceName]).deleteAll()
        ecfi.getEntityFacade().find("moqui.service.quartz.QrtzSimpropTriggers").condition([schedName:instanceName]).deleteAll()
        ecfi.getEntityFacade().find("moqui.service.quartz.QrtzBlobTriggers").condition([schedName:instanceName]).deleteAll()
        ecfi.getEntityFacade().find("moqui.service.quartz.QrtzTriggers").condition([schedName:instanceName]).deleteAll()

        ecfi.getEntityFacade().find("moqui.service.quartz.QrtzJobDetails").condition([schedName:instanceName]).deleteAll()
        ecfi.getEntityFacade().find("moqui.service.quartz.QrtzCalendars").condition([schedName:instanceName]).deleteAll()
        ecfi.getEntityFacade().find("moqui.service.quartz.QrtzPausedTriggerGrps").condition([schedName:instanceName]).deleteAll()
    }

    @Override
    void storeCalendar(final String calName, final org.quartz.Calendar calendar, final boolean replaceExisting,
                       final boolean updateTriggers) throws ObjectAlreadyExistsException, JobPersistenceException {
        try {
            boolean existingCal = calendarExists(calName)
            if (existingCal && !replaceExisting) throw new ObjectAlreadyExistsException("Calendar with name '" + calName + "' already exists.")

            ByteArrayOutputStream baos = new ByteArrayOutputStream()
            ObjectOutputStream out = new ObjectOutputStream(baos)
            out.writeObject(calendar)
            out.flush()
            byte[] calendarData = baos.toByteArray()

            if (existingCal) {
                ecfi.service.sync().name("update#moqui.service.quartz.QrtzCalendars")
                        .parameters([schedName:instanceName, calendarName:calName, calendar:new SerialBlob(calendarData)])
                        .disableAuthz().call()

                if(updateTriggers) {
                    List<OperableTrigger> trigs = getTriggersForCalendar(calName)
                    for(OperableTrigger trigger: trigs) {
                        trigger.updateWithNewCalendar(calendar, getMisfireThreshold())
                        storeTrigger(trigger, null, true, Constants.STATE_WAITING, false, false)
                    }
                }
            } else {
                ecfi.service.sync().name("create#moqui.service.quartz.QrtzCalendars")
                        .parameters([schedName:instanceName, calendarName:calName, calendar:new SerialBlob(calendarData)])
                        .disableAuthz().call()
            }
        } catch (IOException e) {
            throw new JobPersistenceException("Couldn't store calendar because the BLOB couldn't be serialized: " + e.getMessage(), e)
        } catch (ClassNotFoundException e) {
            throw new JobPersistenceException("Couldn't store calendar: " + e.getMessage(), e)
        }
    }

    boolean calendarExists(String calendarName) throws JobPersistenceException {
        return ecfi.getEntityFacade().find("moqui.service.quartz.QrtzCalendars")
                .condition([schedName:instanceName, calendarName:calendarName]).disableAuthz().count() > 0
    }

    List<OperableTrigger> getTriggersForCalendar(String calendarName) throws JobPersistenceException {
        Map calMap = [schedName:instanceName, calendarName:calendarName]
        EntityList triggerList = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers").condition(calMap).disableAuthz().list()
        List<OperableTrigger> resultList = []
        for (EntityValue triggerValue in triggerList)
            resultList.add(getTriggerFromBlob((String) triggerValue.triggerName, (String) triggerValue.triggerGroup))
        return resultList
    }

    @Override
    boolean removeCalendar(String calendarName) throws JobPersistenceException {
        Map calMap = [schedName:instanceName, calendarName:calendarName]
        if (ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers").condition(calMap)
                .disableAuthz().count() > 0) throw new JobPersistenceException("Calender cannot be removed if it referenced by a trigger!")
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzCalendars").condition(calMap).disableAuthz().deleteAll() as boolean
    }

    @Override
    org.quartz.Calendar retrieveCalendar(String calendarName) throws JobPersistenceException {
        try {
            Map calMap = [schedName:instanceName, calendarName:calendarName]

            EntityValue calValue = ecfi.entityFacade.find("moqui.service.quartz.QrtzCalendars").condition(calMap).disableAuthz().one()
            if (!calValue) return null

            org.quartz.Calendar cal = null
            ObjectInputStream ois = new ObjectInputStream(calValue.getSerialBlob("calendar").binaryStream)
            try { cal = (org.quartz.Calendar) ois.readObject() } finally { ois.close() }
            return cal
        } catch (ClassNotFoundException e) {
            throw new JobPersistenceException("Couldn't retrieve calendar because a required class was not found: " + e.getMessage(), e)
        } catch (IOException e) {
            throw new JobPersistenceException("Couldn't retrieve calendar because the BLOB couldn't be deserialized: " + e.getMessage(), e)
        }
    }

    @Override
    List<String> getCalendarNames() throws JobPersistenceException {
        EntityList calendarList = ecfi.entityFacade.find("moqui.service.quartz.QrtzCalendars")
                .condition([schedName:instanceName]).selectField("calendarName").distinct(true).disableAuthz().list()
        List<String> resultList = new ArrayList<>(calendarList.size())
        for (EntityValue calendarValue in calendarList) resultList.add((String) calendarValue.calendarName)
        return resultList
    }

    @Override
    int getNumberOfJobs() throws JobPersistenceException {
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzJobDetails").condition([schedName:instanceName])
                .disableAuthz().count()
    }
    @Override
    int getNumberOfTriggers() throws JobPersistenceException {
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers").condition([schedName:instanceName])
                .disableAuthz().count()
    }
    @Override
    int getNumberOfCalendars() throws JobPersistenceException {
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzCalendars").condition([schedName:instanceName])
                .disableAuthz().count()
    }

    @Override
    Set<JobKey> getJobKeys(GroupMatcher<JobKey> matcher) throws JobPersistenceException {
        EntityList jobList = ecfi.entityFacade.find("moqui.service.quartz.QrtzJobDetails")
                .condition([schedName:instanceName]).condition("jobGroup", EntityCondition.LIKE, toSqlLikeClause(matcher))
                .selectField("jobName").selectField("jobGroup").disableAuthz().list()
        Set<JobKey> jobKeySet = new ArrayList<>(jobList.size())
        for (EntityValue jobValue in jobList) jobKeySet.add(new JobKey((String) jobValue.jobName, (String) jobValue.jobGroup))
        return jobKeySet
    }

    @Override
    Set<TriggerKey> getTriggerKeys(GroupMatcher<TriggerKey> matcher) throws JobPersistenceException {
        EntityList triggerList = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers")
                .condition([schedName:instanceName]).condition("triggerGroup", EntityCondition.LIKE, toSqlLikeClause(matcher))
                .selectField("triggerName").selectField("triggerGroup").disableAuthz().list()
        Set<TriggerKey> triggerKeySet = new ArrayList<>(triggerList.size())
        for (EntityValue triggerValue in triggerList)
            triggerKeySet.add(new TriggerKey((String) triggerValue.triggerName, (String) triggerValue.triggerGroup))
        return triggerKeySet
    }

    @Override
    List<String> getJobGroupNames() throws JobPersistenceException {
        EntityList jobList = ecfi.entityFacade.find("moqui.service.quartz.QrtzJobDetails")
                .condition([schedName:instanceName]).selectField("jobGroup").distinct(true).disableAuthz().list()
        List<String> jobGroupList = new ArrayList<>(jobList.size())
        for (EntityValue jobValue in jobList) jobGroupList.add((String) jobValue.jobGroup)
        return jobGroupList
    }

    @Override
    List<String> getTriggerGroupNames() throws JobPersistenceException {
        EntityList triggerList = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers")
                .condition([schedName:instanceName]).selectField("triggerGroup").distinct(true).disableAuthz().list()
        List<String> triggerGroupList = new ArrayList<>(triggerList.size())
        for (EntityValue triggerValue in triggerList) triggerGroupList.add((String) triggerValue.triggerGroup)
        return triggerGroupList
    }

    protected List<String> getTriggerGroupNames(GroupMatcher<TriggerKey> matcher) throws JobPersistenceException {
        EntityList triggerList = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers")
                .condition([schedName:instanceName]).condition("triggerGroup", EntityCondition.LIKE, toSqlLikeClause(matcher))
                .selectField("triggerGroup").distinct(true).disableAuthz().list()
        List<String> triggerGroupList = new ArrayList<>(triggerList.size())
        for (EntityValue triggerValue in triggerList) triggerGroupList.add((String) triggerValue.triggerGroup)
        return triggerGroupList
    }

    @Override
    List<OperableTrigger> getTriggersForJob(JobKey jobKey) throws JobPersistenceException {
        Map jobMap = [schedName:instanceName, jobName:jobKey.name, jobGroup:jobKey.group]
        EntityList triggerList = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers").condition(jobMap).disableAuthz().list()
        List<OperableTrigger> resultList = []
        for (EntityValue triggerValue in triggerList)
            resultList.add(getTriggerFromBlob((String) triggerValue.triggerName, (String) triggerValue.triggerGroup))
        return resultList
    }

    @Override
    TriggerState getTriggerState(TriggerKey triggerKey) throws JobPersistenceException {
        Map triggerMap = [schedName:instanceName, triggerName:triggerKey.name, triggerGroup:triggerKey.group]
        EntityValue triggerValue = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers").condition(triggerMap).disableAuthz().one()
        String ts = triggerValue?.triggerState

        if (ts == null) return TriggerState.NONE
        if (ts.equals(Constants.STATE_DELETED)) return TriggerState.NONE
        if (ts.equals(Constants.STATE_COMPLETE)) return TriggerState.COMPLETE
        if (ts.equals(Constants.STATE_PAUSED)) return TriggerState.PAUSED
        if (ts.equals(Constants.STATE_PAUSED_BLOCKED)) return TriggerState.PAUSED
        if (ts.equals(Constants.STATE_ERROR)) return TriggerState.ERROR
        if (ts.equals(Constants.STATE_BLOCKED)) return TriggerState.BLOCKED

        return TriggerState.NORMAL
    }
    protected TriggerStatus selectTriggerStatus(TriggerKey triggerKey) {
        Map triggerMap = [schedName:instanceName, triggerName:triggerKey.name, triggerGroup:triggerKey.group]
        EntityValue triggerValue = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers").condition(triggerMap).disableAuthz().one()
        if (!triggerValue) return null

        Long nextFireTime = triggerValue.getLong("nextFireTime")
        Date nft = null
        if (nextFireTime) nft = new Date(nextFireTime)
        TriggerStatus status = new TriggerStatus((String) triggerValue.triggerState, nft)
        status.setKey(triggerKey)
        status.setJobKey(new JobKey((String) triggerValue.jobName, (String) triggerValue.jobGroup))

        return status
    }

    @Override
    void pauseTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        String oldState = getTriggerState(triggerKey)
        if (oldState.equals(Constants.STATE_WAITING) || oldState.equals(Constants.STATE_ACQUIRED)) {
            updateTriggerState(triggerKey, Constants.STATE_PAUSED)
        } else if (oldState.equals(Constants.STATE_BLOCKED)) {
            updateTriggerState(triggerKey, Constants.STATE_PAUSED_BLOCKED)
        }
    }

    @Override
    Collection<String> pauseTriggers(GroupMatcher<TriggerKey> matcher) throws JobPersistenceException {
        updateTriggerGroupStateFromOtherStates(matcher, Constants.STATE_PAUSED,
                [Constants.STATE_ACQUIRED, Constants.STATE_WAITING, Constants.STATE_WAITING])
        updateTriggerGroupStateFromOtherStates(matcher, Constants.STATE_PAUSED_BLOCKED, [Constants.STATE_BLOCKED])

        List<String> groups = getTriggerGroupNames(matcher)

        // make sure to account for an exact group match for a group that doesn't yet exist
        StringMatcher.StringOperatorName operator = matcher.getCompareWithOperator()
        if (operator.equals(StringMatcher.StringOperatorName.EQUALS) && !groups.contains(matcher.getCompareToValue()))
            groups.add(matcher.getCompareToValue())

        for (String group : groups) if (!isTriggerGroupPaused(group)) insertPausedTriggerGroup(group)

        return new HashSet<String>(groups)
    }

    @Override
    void resumeTrigger(TriggerKey triggerKey) throws JobPersistenceException {
        TriggerStatus status = selectTriggerStatus(triggerKey)
        if (status == null || status.getNextFireTime() == null) return

        boolean blocked = false
        if (Constants.STATE_PAUSED_BLOCKED == status.getStatus()) blocked = true

        String newState = checkBlockedState(status.getJobKey(), Constants.STATE_WAITING)

        boolean misfired = false
        if (schedulerRunning && status.getNextFireTime().before(new Date()))
            misfired = updateMisfiredTrigger(triggerKey, newState, true)

        if (!misfired) {
            if (blocked) updateTriggerStateFromOtherState(triggerKey, newState, Constants.STATE_PAUSED_BLOCKED)
            else updateTriggerStateFromOtherState(triggerKey, newState, Constants.STATE_PAUSED)
        }
    }

    @Override
    Collection<String> resumeTriggers(GroupMatcher<TriggerKey> matcher) throws JobPersistenceException {
        deletePausedTriggerGroup(matcher)

        HashSet<String> groups = new HashSet<String>()
        Set<TriggerKey> keys = getTriggerKeys(matcher)
        for (TriggerKey key in keys) {
            resumeTrigger(key)
            groups.add(key.getGroup())
        }

        return groups
    }

    @Override
    Set<String> getPausedTriggerGroups() throws JobPersistenceException {
        EntityList triggerList = ecfi.entityFacade.find("moqui.service.quartz.QrtzPausedTriggerGrps")
                .condition([schedName:instanceName]).selectField("triggerGroup").disableAuthz().list()
        Set<String> triggerGroupSet = new HashSet<>(triggerList.size())
        for (EntityValue triggerValue in triggerList) triggerGroupSet.add((String) triggerValue.triggerGroup)
        return triggerGroupSet
    }

    @Override
    void pauseJob(JobKey jobKey) throws JobPersistenceException {
        List<OperableTrigger> triggers = getTriggersForJob(jobKey)
        for (OperableTrigger trigger in triggers) pauseTrigger(trigger.getKey())
    }

    @Override
    Collection<String> pauseJobs(GroupMatcher<JobKey> matcher) throws JobPersistenceException {
        Set<String> groupNames = new HashSet<String>()
        Set<JobKey> jobNames = getJobKeys(matcher)
        for (JobKey jobKey : jobNames) {
            List<OperableTrigger> triggers = getTriggersForJob(jobKey)
            for (OperableTrigger trigger : triggers) pauseTrigger(trigger.getKey())
            groupNames.add(jobKey.getGroup())
        }
        return groupNames
    }

    @Override
    void resumeJob(JobKey jobKey) throws JobPersistenceException {
        List<OperableTrigger> triggers = getTriggersForJob(jobKey)
        for (OperableTrigger trigger in triggers) resumeTrigger(trigger.getKey())
    }

    @Override
    Collection<String> resumeJobs(GroupMatcher<JobKey> matcher) throws JobPersistenceException {
        Set<String> groupNames = new HashSet<String>()
        Set<JobKey> jobNames = getJobKeys(matcher)
        for (JobKey jobKey : jobNames) {
            List<OperableTrigger> triggers = getTriggersForJob(jobKey)
            for (OperableTrigger trigger : triggers) resumeTrigger(trigger.getKey())
            groupNames.add(jobKey.getGroup())
        }
        return groupNames
    }

    @Override
    void pauseAll() throws JobPersistenceException {
        List<String> names = getTriggerGroupNames()
        for (String name: names) pauseTriggers(GroupMatcher.triggerGroupEquals(name))
        if (!isTriggerGroupPaused(Constants.ALL_GROUPS_PAUSED)) insertPausedTriggerGroup(Constants.ALL_GROUPS_PAUSED)
    }

    @Override
    void resumeAll() throws JobPersistenceException {
        List<String> names = getTriggerGroupNames()
        for (String name: names) resumeTriggers(GroupMatcher.triggerGroupEquals(name))
        deletePausedTriggerGroup(Constants.ALL_GROUPS_PAUSED)
    }

    @Override
    List<OperableTrigger> acquireNextTriggers(final long noLaterThan, final int maxCount, final long timeWindow) throws JobPersistenceException {
        // this will happen during init because Quartz is initialized before ECFI init is final, so don't blow up
        try { getEcfi() } catch (Exception e) { return new ArrayList<OperableTrigger>() }

        boolean beganTransaction = ecfi.transactionFacade.begin(0)
        try {
            return acquireNextTriggersInternal(noLaterThan, maxCount, timeWindow)
        } catch (Throwable t) {
            ecfi.transactionFacade.rollback(beganTransaction, "Error in acquireNextTriggers", t)
            throw t
        } finally {
            if (ecfi.transactionFacade.isTransactionInPlace()) ecfi.transactionFacade.commit(beganTransaction)
        }
    }
    protected List<OperableTrigger> acquireNextTriggersInternal(final long noLaterThan, final int maxCount, final long timeWindow) throws JobPersistenceException {
        if (timeWindow < 0) throw new IllegalArgumentException("timeWindow cannot be less than 0")

        List<OperableTrigger> acquiredTriggers = new ArrayList<OperableTrigger>()

        Set<JobKey> acquiredJobKeysForNoConcurrentExec = new HashSet<JobKey>()
        final int MAX_DO_LOOP_RETRY = 3
        int currentLoopCount = 0
        long firstAcquiredTriggerFireTime = 0

        while (true) {
            currentLoopCount ++
            try {
                List<TriggerKey> keys = selectTriggerToAcquire(noLaterThan + timeWindow, getMisfireTime(), maxCount)

                // No trigger is ready to fire yet.
                if (keys == null || keys.size() == 0) return acquiredTriggers

                for (TriggerKey triggerKey in keys) {
                    // If our trigger is no longer available, try a new one.
                    OperableTrigger nextTrigger = retrieveTrigger(triggerKey)
                    if (nextTrigger == null) continue // next trigger

                    // If trigger's job is set as @DisallowConcurrentExecution, and it has already been added to result, then
                    // put it back into the timeTriggers set and continue to search for next trigger.
                    JobKey jobKey = nextTrigger.getJobKey()
                    JobDetail job = retrieveJob(jobKey)
                    if (job.isConcurrentExectionDisallowed()) {
                        if (acquiredJobKeysForNoConcurrentExec.contains(jobKey)) {
                            continue // next trigger
                        } else {
                            acquiredJobKeysForNoConcurrentExec.add(jobKey)
                        }
                    }

                    // We now have a acquired trigger, let's add to return list.
                    // If our trigger was no longer in the expected state, try a new one.
                    int rowsUpdated = updateTriggerStateFromOtherState(triggerKey, Constants.STATE_ACQUIRED, Constants.STATE_WAITING)
                    if (rowsUpdated <= 0) continue // next trigger

                    nextTrigger.setFireInstanceId(getFiredTriggerRecordId())

                    ecfi.serviceFacade.sync().name("create#moqui.service.quartz.QrtzFiredTriggers")
                            .parameters([schedName:instanceName, entryId:nextTrigger.getFireInstanceId(),
                                triggerName:nextTrigger.key.name, triggerGroup:nextTrigger.key.group,
                                instanceName:instanceId, firedTime:System.currentTimeMillis(),
                                schedTime:nextTrigger.getNextFireTime().getTime(), priority:nextTrigger.priority,
                                state:Constants.STATE_ACQUIRED, jobName:nextTrigger.jobKey?.name,
                                jobGroup:nextTrigger.jobKey?.group, isNonconcurrent:"F", requestsRecovery:"F"])
                            .disableAuthz().call()

                    acquiredTriggers.add(nextTrigger)
                    if (firstAcquiredTriggerFireTime == 0) firstAcquiredTriggerFireTime = nextTrigger.getNextFireTime().getTime()
                }

                // if we didn't end up with any trigger to fire from that first
                // batch, try again for another batch. We allow with a max retry count.
                if (acquiredTriggers.size() != 0 || currentLoopCount >= MAX_DO_LOOP_RETRY) break
            } catch (Exception e) {
                throw new JobPersistenceException("Couldn't acquire next trigger: " + e.getMessage(), e)
            }
        }

        // Return the acquired trigger list
        return acquiredTriggers
    }

    /**
     * @param noLaterThan highest value of <code>getNextFireTime()</code> of the triggers (exclusive)
     * @param noEarlierThan highest value of <code>getNextFireTime()</code> of the triggers (inclusive)
     * @param maxCount maximum number of trigger keys allow to acquired in the returning list.
     */
    protected List<TriggerKey> selectTriggerToAcquire(long noLaterThan, long noEarlierThan, int maxCount) {
        EntityConditionFactory ecf = ecfi.entityFacade.getConditionFactory()
        List<TriggerKey> nextTriggers = new LinkedList<TriggerKey>()

        EntityList triggerList = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers")
                .selectFields(['triggerName', 'triggerGroup', 'nextFireTime', 'priority'])
                .condition([schedName:instanceName, triggerState:Constants.STATE_WAITING])
                .condition(ecf.makeCondition("nextFireTime", EntityCondition.LESS_THAN_EQUAL_TO, noLaterThan))
                .condition(ecf.makeCondition(ecf.makeCondition("misfireInstr", EntityCondition.EQUALS, -1), EntityCondition.OR,
                    ecf.makeCondition(ecf.makeCondition("misfireInstr", EntityCondition.NOT_EQUAL, -1), EntityCondition.AND,
                            ecf.makeCondition("nextFireTime", EntityCondition.GREATER_THAN_EQUAL_TO, noEarlierThan))))
                .orderBy(['nextFireTime', '-priority'])
                .maxRows(maxCount).fetchSize(maxCount).disableAuthz().list()

        for (EntityValue triggerValue in triggerList) {
            if (nextTriggers.size() >= maxCount) break
            nextTriggers.add(new TriggerKey((String) triggerValue.triggerName, (String) triggerValue.triggerGroup))
        }

        return nextTriggers
    }

    protected static AtomicLong ftrCtr = new AtomicLong(System.currentTimeMillis())
    protected String getFiredTriggerRecordId() { return instanceId + ftrCtr.getAndIncrement() }

    protected int updateTriggerState(TriggerKey triggerKey, String state) {
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers")
                .condition([schedName:instanceName, triggerName:triggerKey.name, triggerGroup:triggerKey.group])
                .disableAuthz().updateAll([triggerState:state])
    }
    protected int updateTriggerStateFromOtherState(TriggerKey triggerKey, String newState, String oldState) {
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers")
                .condition([schedName:instanceName, triggerName:triggerKey.name, triggerGroup:triggerKey.group, triggerState:oldState])
                .disableAuthz().updateAll([triggerState:newState])
    }
    protected int updateTriggerStatesForJob(JobKey jobKey, String newState) {
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers")
                .condition([schedName:instanceName, jobName:jobKey.name, jobGroup:jobKey.group])
                .disableAuthz().updateAll([triggerState:newState])
    }
    protected int updateTriggerStatesForJobFromOtherState(JobKey jobKey, String newState, String oldState) {
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers")
                .condition([schedName:instanceName, jobName:jobKey.name, jobGroup:jobKey.group, triggerState:oldState])
                .disableAuthz().updateAll([triggerState:newState])
    }
    protected int updateTriggerGroupStateFromOtherStates(GroupMatcher<TriggerKey> matcher, String newState, List<String> oldStates) {
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers")
                .condition([schedName:instanceName])
                .condition("triggerGroup", EntityCondition.LIKE, toSqlLikeClause(matcher))
                .condition("triggerState", EntityCondition.IN, oldStates)
                .disableAuthz().updateAll([triggerState:newState])
    }
    protected int updateTriggerStatesFromOtherStates(String newState, List<String> oldStates) {
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers")
                .condition([schedName:instanceName])
                .condition("triggerState", EntityCondition.IN, oldStates)
                .disableAuthz().updateAll([triggerState:newState])
    }
    protected static String toSqlLikeClause(final GroupMatcher<?> matcher) {
        String groupName;
        switch(matcher.getCompareWithOperator()) {
            case StringMatcher.StringOperatorName.EQUALS:
                groupName = matcher.getCompareToValue();
                break;
            case StringMatcher.StringOperatorName.CONTAINS:
                groupName = "%" + matcher.getCompareToValue() + "%";
                break;
            case StringMatcher.StringOperatorName.ENDS_WITH:
                groupName = "%" + matcher.getCompareToValue();
                break;
            case StringMatcher.StringOperatorName.STARTS_WITH:
                groupName = matcher.getCompareToValue() + "%";
                break;
            case StringMatcher.StringOperatorName.ANYTHING:
                groupName = "%";
                break;
            default:
                throw new UnsupportedOperationException("Don't know how to translate " + matcher.getCompareWithOperator() + " into SQL");
        }
        return groupName;
    }


    @Override
    void releaseAcquiredTrigger(OperableTrigger operableTrigger) {
        boolean beganTransaction = ecfi.transactionFacade.begin(0)
        try {
            updateTriggerStateFromOtherState(operableTrigger.getKey(), Constants.STATE_WAITING, Constants.STATE_ACQUIRED)
            deleteFiredTrigger(operableTrigger.getFireInstanceId());
        } catch (Throwable t) {
            ecfi.transactionFacade.rollback(beganTransaction, "Error in releaseAcquiredTrigger", t)
            throw t
        } finally {
            if (ecfi.transactionFacade.isTransactionInPlace()) ecfi.transactionFacade.commit(beganTransaction)
        }
    }
    protected int deleteFiredTrigger(String entryId) {
        return ecfi.entityFacade.find("moqui.service.quartz.QrtzFiredTriggers")
                .condition([schedName:instanceName, entryId:entryId]).disableAuthz().deleteAll()
    }

    @Override
    List<TriggerFiredResult> triggersFired(List<OperableTrigger> operableTriggers) throws JobPersistenceException {
        List<TriggerFiredResult> results = new ArrayList<TriggerFiredResult>()

        boolean beganTransaction = ecfi.transactionFacade.begin(0)
        try {
            TriggerFiredResult result
            for (OperableTrigger trigger : operableTriggers) {
                try {
                    TriggerFiredBundle bundle = triggerFired(trigger)
                    result = new TriggerFiredResult(bundle)
                } catch (JobPersistenceException jpe) {
                    result = new TriggerFiredResult(jpe)
                } catch(RuntimeException re) {
                    result = new TriggerFiredResult(re)
                }
                results.add(result)
            }
        } catch (Throwable t) {
            ecfi.transactionFacade.rollback(beganTransaction, "Error in triggersFired", t)
            throw t
        } finally {
            if (ecfi.transactionFacade.isTransactionInPlace()) ecfi.transactionFacade.commit(beganTransaction)
        }

        return results
    }
    protected TriggerFiredBundle triggerFired(OperableTrigger trigger) throws JobPersistenceException {
        JobDetail job
        org.quartz.Calendar cal = null

        // Make sure trigger wasn't deleted, paused, or completed...
        // if trigger was deleted, state will be STATE_DELETED
        String state = selectTriggerState(trigger.getKey())
        if (!state.equals(Constants.STATE_ACQUIRED)) return null

        try {
            job = retrieveJob(trigger.getJobKey())
            if (job == null) return null
        } catch (JobPersistenceException jpe) {
            try {
                logger.error("Error retrieving job, setting trigger state to ERROR.", jpe)
                updateTriggerState(trigger.getKey(), Constants.STATE_ERROR)
            } catch (Exception sqle) {
                logger.error("Unable to set trigger state to ERROR.", sqle)
            }
            throw jpe
        }

        if (trigger.getCalendarName() != null) {
            cal = retrieveCalendar(trigger.getCalendarName())
            if (cal == null) return null
        }

        ecfi.serviceFacade.sync().name("update#moqui.service.quartz.QrtzFiredTriggers")
                .parameters([schedName:instanceName, entryId:trigger.getFireInstanceId(),
                    triggerName:trigger.key.name, triggerGroup:trigger.key.group,
                    instanceName:instanceId, firedTime:System.currentTimeMillis(),
                    schedTime:trigger.getNextFireTime().getTime(), priority:trigger.priority,
                    state:Constants.STATE_EXECUTING, jobName:job.key.name,
                    jobGroup:job.key.group, isNonconcurrent:(job.isConcurrentExectionDisallowed() ? "T" : "F"),
                    requestsRecovery:(job.requestsRecovery() ? "T" : "F")])
                .disableAuthz().call()

        Date prevFireTime = trigger.getPreviousFireTime()

        // call triggered - to update the trigger's next-fire-time state...
        trigger.triggered(cal)

        state = Constants.STATE_WAITING
        boolean force = true

        if (job.isConcurrentExectionDisallowed()) {
            state = Constants.STATE_BLOCKED;
            force = false;
            updateTriggerStatesForJobFromOtherState(job.getKey(), Constants.STATE_BLOCKED, Constants.STATE_WAITING)
            updateTriggerStatesForJobFromOtherState(job.getKey(), Constants.STATE_BLOCKED, Constants.STATE_ACQUIRED)
            updateTriggerStatesForJobFromOtherState(job.getKey(), Constants.STATE_PAUSED_BLOCKED, Constants.STATE_PAUSED)
        }

        if (trigger.getNextFireTime() == null) {
            state = Constants.STATE_COMPLETE
            force = true
        }

        storeTrigger(trigger, job, true, state, force, false)

        job.getJobDataMap().clearDirtyFlag()

        return new TriggerFiredBundle(job, trigger, cal, trigger.getKey().getGroup()
                .equals(Scheduler.DEFAULT_RECOVERY_GROUP), new Date(), trigger
                .getPreviousFireTime(), prevFireTime, trigger.getNextFireTime())
    }
    protected String selectTriggerState(TriggerKey triggerKey) {
        Map triggerMap = [schedName:instanceName, triggerName:triggerKey.name, triggerGroup:triggerKey.group]
        EntityValue triggerValue = ecfi.entityFacade.find("moqui.service.quartz.QrtzTriggers").condition(triggerMap).disableAuthz().one()
        if (!triggerValue) return Constants.STATE_DELETED
        return triggerValue.triggerState
    }

    @Override
    void triggeredJobComplete(OperableTrigger trigger, JobDetail jobDetail, CompletedExecutionInstruction triggerInstCode) {
        boolean beganTransaction = ecfi.transactionFacade.begin(0)
        try {
            triggeredJobCompleteInternal(trigger, jobDetail, triggerInstCode)
        } catch (Throwable t) {
            ecfi.transactionFacade.rollback(beganTransaction, "Error in triggeredJobComplete", t)
            throw t
        } finally {
            if (ecfi.transactionFacade.isTransactionInPlace()) ecfi.transactionFacade.commit(beganTransaction)
        }
    }
    void triggeredJobCompleteInternal(OperableTrigger trigger, JobDetail jobDetail, CompletedExecutionInstruction triggerInstCode) {
        if (triggerInstCode == CompletedExecutionInstruction.DELETE_TRIGGER) {
            if(trigger.getNextFireTime() == null) {
                // double check for possible reschedule within job
                // execution, which would cancel the need to delete...
                TriggerStatus stat = selectTriggerStatus(trigger.getKey())
                if(stat != null && stat.getNextFireTime() == null) removeTrigger(trigger.getKey())
            } else{
                removeTrigger(trigger.getKey())
                signalSchedulingChangeOnTxCompletion(0L)
            }
        } else if (triggerInstCode == CompletedExecutionInstruction.SET_TRIGGER_COMPLETE) {
            updateTriggerState(trigger.getKey(), Constants.STATE_COMPLETE)
            signalSchedulingChangeOnTxCompletion(0L)
        } else if (triggerInstCode == CompletedExecutionInstruction.SET_TRIGGER_ERROR) {
            logger.info("Trigger " + trigger.getKey() + " set to ERROR state.");
            updateTriggerState(trigger.getKey(), Constants.STATE_ERROR)
            signalSchedulingChangeOnTxCompletion(0L)
        } else if (triggerInstCode == CompletedExecutionInstruction.SET_ALL_JOB_TRIGGERS_COMPLETE) {
            updateTriggerStatesForJob(trigger.getJobKey(), Constants.STATE_COMPLETE)
            signalSchedulingChangeOnTxCompletion(0L)
        } else if (triggerInstCode == CompletedExecutionInstruction.SET_ALL_JOB_TRIGGERS_ERROR) {
            logger.info("All triggers of Job " + trigger.getKey() + " set to ERROR state.")
            updateTriggerStatesForJob(trigger.getJobKey(), Constants.STATE_ERROR)
            signalSchedulingChangeOnTxCompletion(0L)
        }

        if (jobDetail.isConcurrentExectionDisallowed()) {
            updateTriggerStatesForJobFromOtherState(jobDetail.getKey(), Constants.STATE_WAITING, Constants.STATE_BLOCKED)
            updateTriggerStatesForJobFromOtherState(jobDetail.getKey(), Constants.STATE_PAUSED, Constants.STATE_PAUSED_BLOCKED)

            signalSchedulingChangeOnTxCompletion(0L)
        }
        if (jobDetail.isPersistJobDataAfterExecution()) {
            try {
                if (jobDetail.getJobDataMap().isDirty()) updateJobData(jobDetail)
            } catch (IOException e) {
                throw new JobPersistenceException("Couldn't serialize job data: " + e.getMessage(), e)
            }
        }
    }

    void updateJobData(JobDetail job) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        if (job.jobDataMap) {
            ObjectOutputStream out = new ObjectOutputStream(baos)
            out.writeObject(job.jobDataMap)
            out.flush()
        }
        byte[] jobData = baos.toByteArray()
        Map jobMap = [schedName:instanceName, jobName:job.key.name, jobGroup:job.key.group, jobData:new SerialBlob(jobData)]
        ecfi.serviceFacade.sync().name("update#moqui.service.quartz.QrtzJobDetails").parameters(jobMap).disableAuthz().call()
    }

    protected ThreadLocal<Long> sigChangeForTxCompletion = new ThreadLocal<Long>()
    protected void signalSchedulingChangeOnTxCompletion(long candidateNewNextFireTime) {
        Long sigTime = sigChangeForTxCompletion.get()
        if (sigTime == null && candidateNewNextFireTime >= 0L) {
            sigChangeForTxCompletion.set(candidateNewNextFireTime)
        } else {
            if (sigTime == null || candidateNewNextFireTime < sigTime) sigChangeForTxCompletion.set(candidateNewNextFireTime)
        }
    }
    protected Long clearAndGetSignalSchedulingChangeOnTxCompletion() {
        Long t = sigChangeForTxCompletion.get()
        sigChangeForTxCompletion.set(null)
        return t
    }

    @Override
    void setInstanceId(String s) { instanceId = s }
    @Override
    void setInstanceName(String s) { instanceName = s }
    @Override
    void setThreadPoolSize(int i) { }
}
