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
import org.elasticsearch.client.Client
import org.kie.api.runtime.KieContainer
import org.kie.api.runtime.KieSession
import org.kie.api.runtime.StatelessKieSession
import org.moqui.BaseException
import org.moqui.context.*
import org.moqui.entity.EntityFacade
import org.moqui.entity.EntityList
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.screen.ScreenFacade
import org.moqui.service.ServiceFacade
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.http.HttpServletResponse
import javax.servlet.http.HttpServletRequest
import org.apache.camel.CamelContext
import org.moqui.entity.EntityValue

@CompileStatic
class ExecutionContextImpl implements ExecutionContext {
    protected final static Logger loggerDirect = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class)

    protected ExecutionContextFactoryImpl ecfi

    protected ContextStack context = new ContextStack()
    protected String activeTenantId = "DEFAULT"
    protected LinkedList<String> tenantIdStack = null

    protected WebFacade webFacade = null
    protected UserFacadeImpl userFacade = null
    protected MessageFacadeImpl messageFacade = null
    protected ArtifactExecutionFacadeImpl artifactExecutionFacade = null

    protected Boolean skipStats = null

    ExecutionContextImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        // NOTE: no WebFacade init here, wait for call in to do that
        // NOTE: don't init userFacade, messageFacade, artifactExecutionFacade here, lazy init when first used instead
        // put reference to this in the context root
        getContextRoot().put("ec", this)

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl initialized")
    }

    ExecutionContextFactoryImpl getEcfi() { return ecfi }

    @Override
    ContextStack getContext() { return context }

    @Override
    Map<String, Object> getContextRoot() { return context.getRootMap() }

    @Override
    String getTenantId() { return activeTenantId }
    @Override
    EntityValue getTenant() {
        return getEntity().find("moqui.tenant.Tenant").condition("tenantId", getTenantId()).useCache(true).disableAuthz().one()
    }

    @Override
    WebFacade getWeb() { webFacade }
    WebFacadeImpl getWebImpl() {
        if (webFacade instanceof WebFacadeImpl) {
            return (WebFacadeImpl) webFacade
        } else {
            return null
        }
    }

    @Override
    UserFacade getUser() { return getUserFacade() }
    UserFacadeImpl getUserFacade() { if (userFacade != null) return userFacade else return (userFacade = new UserFacadeImpl(this)) }

    @Override
    MessageFacade getMessage() { if (messageFacade != null) return messageFacade else return (messageFacade = new MessageFacadeImpl()) }

    @Override
    ArtifactExecutionFacade getArtifactExecution() { return getArtifactExecutionImpl() }
    ArtifactExecutionFacadeImpl getArtifactExecutionImpl() {
        if (artifactExecutionFacade != null) return artifactExecutionFacade
        else return (artifactExecutionFacade = new ArtifactExecutionFacadeImpl(this))
    }

    // ==== More Permanent Objects (get from the factory instead of locally) ===

    @Override
    L10nFacade getL10n() { ecfi.getL10nFacade() }

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
    NotificationMessage makeNotificationMessage() { return new NotificationMessageImpl(this) }
    @Override
    List<NotificationMessage> getNotificationMessages(String userId, String topic) {
        if (!userId && !topic) return []

        List<NotificationMessage> nmList = []
        boolean alreadyDisabled = getArtifactExecution().disableAuthz()
        try {
            Map<String, Object> parameters = [receivedDate:null] as Map<String, Object>
            if (userId) parameters.userId = userId
            if (topic) parameters.topic = topic
            EntityList nmbuList = entity.find("moqui.security.user.NotificationMessageByUser").condition(parameters).list()
            for (EntityValue nmbu in nmbuList) {
                NotificationMessageImpl nmi = new NotificationMessageImpl(this)
                nmi.populateFromValue(nmbu)
                nmList.add(nmi)
            }
        } finally {
            if (!alreadyDisabled) getArtifactExecution().enableAuthz()
        }
        return nmList
    }
    @Override
    void registerNotificationMessageListener(NotificationMessageListener nml) {
        ecfi.registerNotificationMessageListener(nml)
    }


    @Override
    CamelContext getCamelContext() { ecfi.getCamelContext() }
    @Override
    Client getElasticSearchClient() { ecfi.getElasticSearchClient() }
    @Override
    KieContainer getKieContainer(String componentName) { ecfi.getKieContainer(componentName) }
    @Override
    KieSession getKieSession(String ksessionName) {
        KieSession session = ecfi.getKieSession(ksessionName)
        session.setGlobal("ec", this)
        return session
    }
    @Override
    StatelessKieSession getStatelessKieSession(String ksessionName) {
        StatelessKieSession session = ecfi.getStatelessKieSession(ksessionName)
        session.setGlobal("ec", this)
        return session
    }


    @Override
    void initWebFacade(String webappMoquiName, HttpServletRequest request, HttpServletResponse response) {
        WebFacadeImpl wfi = new WebFacadeImpl(webappMoquiName, request, response, this)
        webFacade = wfi

        String sessionTenantId = request.session.getAttribute("moqui.tenantId")
        if (!sessionTenantId) {
            EntityValue tenantHostDefault = ecfi.getEntityFacade("DEFAULT").find("moqui.tenant.TenantHostDefault")
                    .condition("hostName", request.getServerName()).useCache(true).disableAuthz().one()
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
        context.putAll(webFacade.requestParameters)
    }

    boolean getSkipStats() {
        if (skipStats != null) return skipStats.booleanValue()
        skipStats = ecfi.getSkipStats()
        return skipStats.booleanValue()
    }

    @Override
    boolean changeTenant(String tenantId) {
        if (tenantId == activeTenantId) return false

        logger.info("Changing to tenant ${tenantId} (from tenant ${activeTenantId})")
        EntityFacadeImpl defaultEfi = ecfi.getEntityFacade("DEFAULT")
        EntityValue tenant = defaultEfi.find("moqui.tenant.Tenant").condition("tenantId", tenantId).disableAuthz().useCache(true).one()
        if (tenant == null) throw new BaseException("Tenant not found with ID ${tenantId}")
        if (tenant.isEnabled == 'N') throw new BaseException("Tenant ${tenantId} was disabled at ${l10n.format(tenant.disabledDate, null)}")

        // make sure an entity facade instance for the tenant exists
        ecfi.getEntityFacade(tenantId)

        // check for moqui.tenantAllowOverride flag set elsewhere
        if (webFacade != null && webFacade.session.getAttribute("moqui.tenantAllowOverride") == "N")
            throw new BaseException("Tenant override is not allowed for host [${webFacade.session.getAttribute("moqui.tenantHostName")?:"Unknown"}].")

        // logout the current user, won't be valid in other tenant
        if (userFacade != null && !userFacade.getLoggedInAnonymous()) userFacade.logoutUser()

        activeTenantId = tenantId
        if (tenantIdStack == null) {
            tenantIdStack = new LinkedList<>()
            tenantIdStack.addFirst(tenantId)
        } else {
            if (tenantIdStack.size() > 0 && tenantIdStack.getFirst() != tenantId) tenantIdStack.addFirst(tenantId)
        }
        if (webFacade != null) webFacade.session.setAttribute("moqui.tenantId", tenantId)
        return true
    }
    @Override
    boolean popTenant() {
        String lastTenantId = tenantIdStack ? tenantIdStack.removeFirst() : null
        if (lastTenantId) {
            return changeTenant(lastTenantId)
        } else {
            return false
        }
    }

    @Override
    void destroy() {
        // if webFacade exists this is the end of a request, so trigger after-request actions
        WebFacadeImpl wfi = getWebImpl()
        if (wfi != null) wfi.runAfterRequestActions()

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
