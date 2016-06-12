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
package org.moqui.impl.context

import groovy.transform.CompileStatic
import org.moqui.BaseException
import org.moqui.context.*
import org.moqui.entity.EntityFacade
import org.moqui.entity.EntityList
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.screen.ScreenFacade
import org.moqui.service.ServiceFacade
import org.moqui.util.ContextBinding
import org.moqui.util.ContextStack
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache
import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpServletRequest
import org.moqui.entity.EntityValue

@CompileStatic
class ExecutionContextImpl implements ExecutionContext {
    protected final static Logger loggerDirect = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class)

    protected ExecutionContextFactoryImpl ecfi

    protected final ContextStack context = new ContextStack()
    protected final ContextBinding contextBinding = new ContextBinding(context)
    protected String activeTenantId = "DEFAULT"
    protected LinkedList<String> tenantIdStack = (LinkedList<String>) null

    protected WebFacade webFacade = (WebFacade) null
    protected WebFacadeImpl webFacadeImpl = (WebFacadeImpl) null
    protected final UserFacadeImpl userFacade
    protected final MessageFacadeImpl messageFacade
    protected final ArtifactExecutionFacadeImpl artifactExecutionFacade
    protected final L10nFacadeImpl l10nFacade

    protected Boolean skipStats = null

    // Caches from EC level facades that are per-tenant so managed here
    protected Cache<String, String> l10nMessageCache
    // NOTE: there is no code to clean out old entries in tarpitHitCache, using the cache idle expire time for that
    protected Cache<String, ArrayList> tarpitHitCache


    ExecutionContextImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        // NOTE: no WebFacade init here, wait for call in to do that
        // NOTE: don't init userFacade, messageFacade, artifactExecutionFacade here, lazy init when first used instead
        // put reference to this in the context root
        getContextRoot().put("ec", this)

        userFacade = new UserFacadeImpl(this)
        messageFacade = new MessageFacadeImpl()
        artifactExecutionFacade = new ArtifactExecutionFacadeImpl(this)
        l10nFacade = new L10nFacadeImpl(this)

        initCaches()

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl initialized")
    }

    ExecutionContextFactoryImpl getEcfi() { return ecfi }

    void initCaches() {
        tarpitHitCache = ecfi.getCacheFacade().getCache("artifact.tarpit.hits", activeTenantId)
        l10nMessageCache = ecfi.getCacheFacade().getCache("l10n.message", activeTenantId)
    }
    Cache<String, String> getL10nMessageCache() { return l10nMessageCache }
    Cache<String, ArrayList> getTarpitHitCache() { return tarpitHitCache }

    @Override
    ExecutionContextFactory getFactory() { return ecfi }

    @Override
    ContextStack getContext() { return context }
    @Override
    Map<String, Object> getContextRoot() { return context.getRootMap() }
    @Override
    ContextBinding getContextBinding() { return contextBinding }

    @Override
    <V> V getTool(String toolName, Class<V> instanceClass, Object... parameters) {
        return ecfi.getTool(toolName, instanceClass, parameters)
    }

    @Override
    String getTenantId() { return activeTenantId }
    @Override
    EntityValue getTenant() {
        return getEntity().find("moqui.tenant.Tenant").condition("tenantId", getTenantId()).useCache(true).disableAuthz().one()
    }

    @Override
    WebFacade getWeb() { return webFacade }
    WebFacadeImpl getWebImpl() { return webFacadeImpl }

    @Override
    UserFacade getUser() { return userFacade }
    UserFacadeImpl getUserFacade() { return userFacade }

    @Override
    MessageFacade getMessage() { return messageFacade }

    @Override
    ArtifactExecutionFacade getArtifactExecution() { return artifactExecutionFacade }
    ArtifactExecutionFacadeImpl getArtifactExecutionImpl() { return artifactExecutionFacade }

    @Override
    L10nFacade getL10n() { return l10nFacade }
    L10nFacadeImpl getL10nFacade() { return l10nFacade }


    // ==== More Permanent Objects (get from the factory instead of locally) ===

    @Override
    ResourceFacade getResource() { ecfi.getResourceFacade() }

    @Override
    LoggerFacade getLogger() { ecfi.getLoggerFacade() }

    @Override
    CacheFacade getCache() { ecfi.getCacheFacade() }

    @Override
    TransactionFacade getTransaction() { ecfi.getTransactionFacade() }

    @Override
    EntityFacade getEntity() { ecfi.getEntityFacade(getTenantId()) }

    @Override
    ServiceFacade getService() { ecfi.getServiceFacade() }

    @Override
    ScreenFacade getScreen() { ecfi.getScreenFacade() }

    @Override
    NotificationMessage makeNotificationMessage() { return new NotificationMessageImpl(ecfi, tenantId) }
    @Override
    List<NotificationMessage> getNotificationMessages(String topic) {
        String userId = userFacade.userId
        if (!userId) return []

        List<NotificationMessage> nmList = []
        boolean alreadyDisabled = getArtifactExecution().disableAuthz()
        try {
            Map<String, Object> parameters = [userId:userId, receivedDate:null] as Map<String, Object>
            if (topic) parameters.topic = topic
            EntityList nmbuList = entity.find("moqui.security.user.NotificationMessageByUser").condition(parameters).list()
            for (EntityValue nmbu in nmbuList) {
                NotificationMessageImpl nmi = new NotificationMessageImpl(ecfi, tenantId)
                nmi.populateFromValue(nmbu)
                nmList.add(nmi)
            }
        } finally {
            if (!alreadyDisabled) getArtifactExecution().enableAuthz()
        }
        return nmList
    }

    @Override
    void initWebFacade(String webappMoquiName, HttpServletRequest request, HttpServletResponse response) {
        WebFacadeImpl wfi = new WebFacadeImpl(webappMoquiName, request, response, this)
        webFacade = wfi
        webFacadeImpl = wfi

        String sessionTenantId = request.session.getAttribute("moqui.tenantId")
        if (!sessionTenantId) {
            EntityValue tenantHostDefault = ecfi.getEntityFacade("DEFAULT").find("moqui.tenant.TenantHostDefault")
                    .condition("hostName", wfi.getHostName(false)).useCache(true).disableAuthz().one()
            if (tenantHostDefault) {
                sessionTenantId = tenantHostDefault.tenantId
                request.session.setAttribute("moqui.tenantId", sessionTenantId)
                request.session.setAttribute("moqui.tenantHostName", tenantHostDefault.hostName)
                if (tenantHostDefault.allowOverride)
                    request.session.setAttribute("moqui.tenantAllowOverride", tenantHostDefault.allowOverride)
            }
        }
        if (sessionTenantId) changeTenant(sessionTenantId)

        // now that we have the webFacade and tenantId in place we can do init UserFacade
        ((UserFacadeImpl) getUser()).initFromHttpRequest(request, response)

        // for convenience (and more consistent code in screen actions, services, etc) add all requestParameters to the context
        context.putAll(webFacade.requestParameters)

        // this is the beginning of a request, so trigger before-request actions
        wfi.runBeforeRequestActions()

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl WebFacade initialized")
    }

    /** Meant to be used to set a test stub that implements the WebFacade interface */
    void setWebFacade(WebFacade wf) {
        webFacade = wf
        if (wf instanceof WebFacadeImpl) webFacadeImpl = (WebFacadeImpl) wf
        context.putAll(webFacade.requestParameters)
    }

    boolean getSkipStats() {
        if (skipStats != null) return skipStats.booleanValue()
        skipStats = ecfi.getSkipStats()
        return skipStats.booleanValue()
    }

    @Override
    boolean changeTenant(String tenantId) {
        String fromTenantId = activeTenantId
        if (tenantId == fromTenantId) return false

        logger.info("Changing to tenant ${tenantId} (from tenant ${fromTenantId})")
        EntityFacadeImpl defaultEfi = ecfi.getEntityFacade("DEFAULT")
        EntityValue tenant = defaultEfi.find("moqui.tenant.Tenant").condition("tenantId", tenantId).disableAuthz().useCache(true).one()
        if (tenant == null) throw new BaseException("Tenant not found with ID ${tenantId}")
        if (tenant.isEnabled == 'N') throw new BaseException("Tenant ${tenantId} was disabled at ${l10n.format(tenant.disabledDate, null)}")

        // make sure an entity facade instance for the tenant exists
        ecfi.getEntityFacade(tenantId)

        // check for moqui.tenantAllowOverride flag set elsewhere
        if (webFacade != null && webFacade.session.getAttribute("moqui.tenantAllowOverride") == "N" &&
                webFacade.session.getAttribute("moqui.tenantId") != tenantId)
            throw new BaseException("Tenant override is not allowed for host [${webFacade.session.getAttribute("moqui.tenantHostName")?:"Unknown"}].")

        activeTenantId = tenantId
        if (tenantIdStack == null) {
            tenantIdStack = new LinkedList<>()
            tenantIdStack.addFirst(fromTenantId)
        } else {
            if (tenantIdStack.size() == 0 || tenantIdStack.getFirst() != tenantId) tenantIdStack.addFirst(fromTenantId)
        }
        if (webFacade != null) webFacade.session.setAttribute("moqui.tenantId", tenantId)

        // instead of logout the current user (won't be valid in other tenant) push empty user onto user stack
        // if (userFacade != null && !userFacade.getLoggedInAnonymous()) userFacade.logoutUser()
        if (userFacade != null) userFacade.pushTenant(tenantId)

        // logger.info("Tenant now ${activeTenantId}, username ${userFacade?.username}")

        // re-init caches for new tenantId
        initCaches()

        return true
    }
    @Override
    boolean popTenant() {
        String lastTenantId = tenantIdStack ? tenantIdStack.removeFirst() : null
        if (lastTenantId) {
            // logger.info("Pop tenant, last was ${lastTenantId}")
            if (userFacade != null) userFacade.popTenant(activeTenantId)
            return changeTenant(lastTenantId)
        } else {
            return false
        }
    }

    static class ThreadPoolRunnable implements Runnable {
        ExecutionContextFactoryImpl ecfi
        String threadTenantId
        String threadUsername
        Closure closure

        ThreadPoolRunnable(ExecutionContextImpl eci, Closure closure) {
            ecfi = eci.ecfi
            threadTenantId = eci.tenantId
            threadUsername = eci.user.username
            this.closure = closure
        }
        ThreadPoolRunnable(ExecutionContextFactoryImpl ecfi, String tenantId, String username, Closure closure) {
            this.ecfi = ecfi
            threadTenantId = tenantId
            threadUsername = username
            this.closure = closure
        }

        @Override
        void run() {
            ExecutionContextImpl threadEci = (ExecutionContextImpl) null
            try {
                threadEci = ecfi.getEci()
                threadEci.changeTenant(threadTenantId)
                if (threadUsername != null && threadUsername.length() > 0)
                    threadEci.userFacade.internalLoginUser(threadUsername, threadTenantId)
                closure.call()
            } catch (Throwable t) {
                loggerDirect.error("Error in EC thread pool runner", t)
            } finally {
                if (threadEci != null) threadEci.destroy()
            }
        }
    }

    @Override
    void runAsync(Closure closure) { runInWorkerThread(closure) }
    void runInWorkerThread(Closure closure) {
        ThreadPoolRunnable runnable = new ThreadPoolRunnable(this, closure)
        ecfi.workerPool.execute(runnable)
    }

    @Override
    void destroy() {
        // if webFacade exists this is the end of a request, so trigger after-request actions
        if (webFacadeImpl != null) webFacadeImpl.runAfterRequestActions()

        // make sure there are no transactions open, if any commit them all now
        ecfi.transactionFacade.destroyAllInThread()
        // clean up resources, like JCR session
        ecfi.resourceFacade.destroyAllInThread()
        // clear out the ECFI's reference to this as well
        ecfi.activeContext.remove()

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl destroyed")
    }

    @Override
    String toString() { return "ExecutionContext in tenant ${activeTenantId}" }
}
