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

import org.moqui.Moqui
import org.moqui.context.MessageFacadeException
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.context.ExecutionContextFactory
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.quartz.JobDataMap

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ServiceQuartzJob implements Job {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceQuartzJob.class)

    void execute(JobExecutionContext jobExecutionContext) {
        String serviceName = jobExecutionContext.jobDetail.key.group

        JobDataMap jdm = jobExecutionContext.getJobDetail().getJobDataMap()
        JobDataMap tjdm = jobExecutionContext.getTrigger().getJobDataMap()
        Map parameters = new HashMap()
        for (String key in jdm.getKeys()) parameters.put(key, jdm.get(key))
        for (String key in tjdm.getKeys()) parameters.put(key, tjdm.get(key))

        if (parameters.ALL_TENANTS == "true") {
            ExecutionContextFactory ecf = Moqui.getExecutionContextFactory()
            EntityList tenantList = ecf.entity.find("moqui.tenant.Tenant").disableAuthz().list()
            for (EntityValue tenant in tenantList) {
                logger.info("Running scheduled service ${serviceName} for tenant ${tenant.tenantId}")
                executeSingle(serviceName, parameters, (String) tenant.tenantId)
            }
        } else {
            executeSingle(serviceName, parameters, null)
        }

    }

    static void executeSingle(String serviceName, Map parameters, String tenantId) {
        if (logger.traceEnabled) logger.trace("Calling async|scheduled service [${serviceName}] with parameters [${parameters}]")

        ExecutionContextImpl ec = (ExecutionContextImpl) Moqui.getExecutionContext()

        try {
            Map authUserAccount = (Map) parameters.authUserAccount
            String userId = authUserAccount?.userId ?: parameters.authUsername
            String password = authUserAccount?.currentPassword ?: parameters.authPassword
            if (!tenantId) tenantId = parameters.authTenantId

            // logger.warn("=========== running quartz job for ${serviceName}, userId=${userId}, parameters: ${parameters}")

            boolean needsAuthzEnable = false
            if (userId && password) {
                ec.user.loginUser(userId, password, tenantId)
            } else if (userId) {
                // debatable if this is the best idea, introduces a security hole with control over job scheduling,
                //     but that is true in general for execution of server-side code, there are various ways to get
                //     around authc that are just less convenient
                ec.userFacade.internalLoginUser(userId, tenantId)
                // authz check will be done when job is scheduled for this sort of case, so don't check authz here
                needsAuthzEnable = !ec.getArtifactExecution().disableAuthz()
                // logger.warn("=========== internalLoginUser in job for ${serviceName}, userId=${userId}, ec.user.username: ${ec.user.username}")
            } else if (tenantId) {
                ec.changeTenant(tenantId)
            }

            // logger.warn("=========== running quartz job for ${serviceName}, parameter tenantId=${tenantId}, active tenantId=${ec.getTenantId()}, parameters: ${parameters}")

            try {
                ec.service.sync().name(serviceName).parameters(parameters).call()
            } finally {
                if (needsAuthzEnable) ec.getArtifactExecution().enableAuthz()
            }
        } catch (Throwable t) {
            ec.message.addError(t.message)
            Throwable parent = t.cause
            while (parent != null) {
                ec.message.addError(parent.message)
                parent = parent.cause
            }
        }

        if (ec.getMessage().hasError()) {
            StringBuilder sb = new StringBuilder()
            sb.append("Error calling service [${serviceName}] in tenant ${tenantId} with parameters ${parameters}\n")
            sb.append(ec.getMessage().getErrorsString())
            logger.error(sb.toString())

            // TODO handle retry on error with max-retry?

            // throw an exception wrapping the MessageFacade, the only way to get error messages back through Quartz
            throw new MessageFacadeException(ec.getMessage(), null)
        }

        // all done, kill the ExecutionContext
        ec.destroy()
    }
}
