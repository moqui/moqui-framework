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

import org.quartz.JobListener
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException

import org.moqui.service.ServiceResultReceiver
import org.quartz.TriggerListener
import org.quartz.Trigger
import org.quartz.Trigger.CompletedExecutionInstruction

class ServiceRequesterListener implements JobListener, TriggerListener {
    protected ServiceResultReceiver resultReceiver

    ServiceRequesterListener(ServiceResultReceiver resultReceiver) {
        this.resultReceiver = resultReceiver
    }

    String getName() {
        // TODO: does this have to be unique?
        return "MoquiServiceRequesterListener"
    }

    void jobToBeExecuted(JobExecutionContext jobExecutionContext) {
    }

    void jobExecutionVetoed(JobExecutionContext jobExecutionContext) {
    }

    void jobWasExecuted(JobExecutionContext jobExecutionContext, JobExecutionException jobExecutionException) {
        if (jobExecutionException) {
            resultReceiver.receiveThrowable(jobExecutionException.getUnderlyingException())
        } else {
            // TODO: need to clean up Map based on out-parameter defs?
            resultReceiver.receiveResult(jobExecutionContext.getMergedJobDataMap())
        }
    }

    void triggerFired(Trigger trigger, JobExecutionContext jobExecutionContext) { }

    boolean vetoJobExecution(Trigger trigger, JobExecutionContext jobExecutionContext) {
        // for now, never veto
        return false
    }

    void triggerMisfired(Trigger trigger) { }

    void triggerComplete(Trigger trigger, JobExecutionContext jobExecutionContext,
                         CompletedExecutionInstruction completedExecutionInstruction) {
    }
}
