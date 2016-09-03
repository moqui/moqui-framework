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
package org.moqui.impl.context;

import groovy.lang.Closure;
import org.moqui.BaseException;
import org.moqui.context.*;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.entity.EntityFacadeImpl;
import org.moqui.impl.screen.ScreenFacadeImpl;
import org.moqui.impl.service.ServiceFacadeImpl;
import org.moqui.screen.ScreenFacade;
import org.moqui.service.ServiceFacade;
import org.moqui.util.ContextBinding;
import org.moqui.util.ContextStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.cache.Cache;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

public class ExecutionContextImpl implements ExecutionContext {
    private static final Logger loggerDirect = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class);

    public final ExecutionContextFactoryImpl ecfi;
    public final ContextStack contextStack = new ContextStack();
    public final ContextBinding contextBindingInternal = new ContextBinding(contextStack);

    private String activeTenantId = "DEFAULT";
    private EntityFacadeImpl activeEntityFacade;
    private LinkedList<String> tenantIdStack = (LinkedList<String>) null;

    private WebFacade webFacade = (WebFacade) null;
    private WebFacadeImpl webFacadeImpl = (WebFacadeImpl) null;

    public final UserFacadeImpl userFacade;
    public final MessageFacadeImpl messageFacade;
    public final ArtifactExecutionFacadeImpl artifactExecutionFacade;
    public final L10nFacadeImpl l10nFacade;

    // local references to ECFI fields
    public final CacheFacadeImpl cacheFacade;
    public final LoggerFacadeImpl loggerFacade;
    public final ResourceFacadeImpl resourceFacade;
    public final ScreenFacadeImpl screenFacade;
    public final ServiceFacadeImpl serviceFacade;
    public final TransactionFacadeImpl transactionFacade;

    private Boolean skipStats = null;
    private Cache<String, String> l10nMessageCache;
    private Cache<String, ArrayList> tarpitHitCache;

    public ExecutionContextImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi;
        // NOTE: no WebFacade init here, wait for call in to do that
        // put reference to this in the context root
        contextStack.put("ec", this);

        activeEntityFacade = ecfi.getEntityFacade(activeTenantId);
        userFacade = new UserFacadeImpl(this);
        messageFacade = new MessageFacadeImpl();
        artifactExecutionFacade = new ArtifactExecutionFacadeImpl(this);
        l10nFacade = new L10nFacadeImpl(this);

        cacheFacade = ecfi.cacheFacade;
        loggerFacade = ecfi.loggerFacade;
        resourceFacade = ecfi.resourceFacade;
        screenFacade = ecfi.screenFacade;
        serviceFacade = ecfi.serviceFacade;
        transactionFacade = ecfi.transactionFacade;

        initCaches();

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl initialized");
    }

    @SuppressWarnings("unchecked")
    private void initCaches() {
        tarpitHitCache = cacheFacade.getCache("artifact.tarpit.hits", activeTenantId);
        l10nMessageCache = cacheFacade.getCache("l10n.message", activeTenantId);
    }
    public Cache<String, String> getL10nMessageCache() { return l10nMessageCache; }
    public Cache<String, ArrayList> getTarpitHitCache() { return tarpitHitCache; }

    @Override
    public ExecutionContextFactory getFactory() { return ecfi; }

    @Override
    public ContextStack getContext() { return contextStack; }
    @Override
    public Map<String, Object> getContextRoot() { return contextStack.getRootMap(); }
    @Override
    public ContextBinding getContextBinding() { return contextBindingInternal; }

    @Override
    public <V> V getTool(String toolName, Class<V> instanceClass, Object... parameters) {
        return ecfi.getTool(toolName, instanceClass, parameters);
    }

    @Override
    public String getTenantId() { return activeTenantId; }
    @Override
    public EntityValue getTenant() {
        return getEntity().find("moqui.tenant.Tenant").condition("tenantId", getTenantId()).useCache(true).disableAuthz().one();
    }

    @Override
    public WebFacade getWeb() { return webFacade; }
    public WebFacadeImpl getWebImpl() { return webFacadeImpl; }

    @Override
    public UserFacade getUser() { return userFacade; }
    @Override
    public MessageFacade getMessage() { return messageFacade; }
    @Override
    public ArtifactExecutionFacade getArtifactExecution() { return artifactExecutionFacade; }
    @Override
    public L10nFacade getL10n() { return l10nFacade; }
    @Override
    public ResourceFacade getResource() { return resourceFacade; }
    @Override
    public LoggerFacade getLogger() { return loggerFacade; }
    @Override
    public CacheFacade getCache() { return cacheFacade; }
    @Override
    public TransactionFacade getTransaction() { return transactionFacade; }

    @Override
    public EntityFacade getEntity() { return activeEntityFacade; }
    public EntityFacadeImpl getEntityFacade() { return activeEntityFacade; }

    @Override
    public ServiceFacade getService() { return serviceFacade; }

    @Override
    public ScreenFacade getScreen() { return screenFacade; }

    @Override
    public NotificationMessage makeNotificationMessage() {
        return new NotificationMessageImpl(ecfi, getTenantId());
    }

    @Override
    public List<NotificationMessage> getNotificationMessages(String topic) {
        String userId = userFacade.getUserId();
        if (userId == null || userId.isEmpty()) return new ArrayList<>();

        List<NotificationMessage> nmList = new ArrayList<>();
        boolean alreadyDisabled = artifactExecutionFacade.disableAuthz();
        try {
            Map<String, Object> parameters = new HashMap<>();
            if (topic != null && !topic.isEmpty()) parameters.put("topic", topic);
            EntityList nmbuList = activeEntityFacade.find("moqui.security.user.NotificationMessageByUser").condition(parameters).list();
            for (EntityValue nmbu : nmbuList) {
                NotificationMessageImpl nmi = new NotificationMessageImpl(ecfi, getTenantId());
                nmi.populateFromValue(nmbu);
                nmList.add(nmi);
            }

        } finally {
            if (!alreadyDisabled) artifactExecutionFacade.enableAuthz();
        }

        return nmList;
    }

    @Override
    public void initWebFacade(String webappMoquiName, HttpServletRequest request, HttpServletResponse response) {
        WebFacadeImpl wfi = new WebFacadeImpl(webappMoquiName, request, response, this);
        webFacade = wfi;
        webFacadeImpl = wfi;

        String sessionTenantId = (String) request.getSession().getAttribute("moqui.tenantId");
        if (sessionTenantId == null || sessionTenantId.isEmpty()) {
            EntityValue tenantHostDefault = ecfi.defaultEntityFacade.find("moqui.tenant.TenantHostDefault").condition("hostName", wfi.getHostName(false)).useCache(true).disableAuthz().one();
            if (tenantHostDefault != null) {
                sessionTenantId = (String) tenantHostDefault.getNoCheckSimple("tenantId");
                request.getSession().setAttribute("moqui.tenantId", sessionTenantId);
                request.getSession().setAttribute("moqui.tenantHostName", tenantHostDefault.getNoCheckSimple("hostName"));
                if (tenantHostDefault.getNoCheckSimple("allowOverride") != null)
                    request.getSession().setAttribute("moqui.tenantAllowOverride", tenantHostDefault.getNoCheckSimple("allowOverride"));
            }
        }
        if (sessionTenantId != null && !sessionTenantId.isEmpty()) changeTenant(sessionTenantId);

        // now that we have the webFacade and tenantId in place we can do init UserFacade
        userFacade.initFromHttpRequest(request, response);
        // for convenience (and more consistent code in screen actions, services, etc) add all requestParameters to the context
        contextStack.putAll(webFacade.getRequestParameters());
        // this is the beginning of a request, so trigger before-request actions
        wfi.runBeforeRequestActions();

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl WebFacade initialized");
    }

    /**
     * Meant to be used to set a test stub that implements the WebFacade interface
     */
    public void setWebFacade(WebFacade wf) {
        webFacade = wf;
        if (wf instanceof WebFacadeImpl) webFacadeImpl = (WebFacadeImpl) wf;
        contextStack.putAll(webFacade.getRequestParameters());
    }

    public boolean getSkipStats() {
        if (skipStats != null) return skipStats;
        String skipStatsCond = ecfi.skipStatsCond;
        Map<String, Object> skipParms = new HashMap<>();
        if (webFacade != null)
            skipParms.put("pathInfo", webFacade.getRequest().getPathInfo());
        skipStats = (skipStatsCond != null && !skipStatsCond.isEmpty()) && ecfi.resourceFacade.condition(skipStatsCond, null, skipParms);
        return skipStats;
    }

    @Override
    public boolean changeTenant(final String tenantId) {
        final String fromTenantId = activeTenantId;
        if (tenantId.equals(fromTenantId)) return false;

        getLogger().info("Changing to tenant " + tenantId + " (from tenant " + fromTenantId + ")");
        EntityFacadeImpl defaultEfi = ecfi.defaultEntityFacade;
        final EntityValue tenant = defaultEfi.find("moqui.tenant.Tenant").condition("tenantId", tenantId).disableAuthz().useCache(true).one();
        if (tenant == null) throw new BaseException("Tenant not found with ID " + tenantId);
        if ("N".equals(tenant.get("isEnabled")))
            throw new BaseException("Tenant " + tenantId + " was disabled at " + l10nFacade.format(tenant.get("disabledDate"), null));

        // make sure an entity facade instance for the tenant exists
        ecfi.getEntityFacade(tenantId);

        // check for moqui.tenantAllowOverride flag set elsewhere
        if (webFacade != null && "N".equals(webFacade.getSession().getAttribute("moqui.tenantAllowOverride")) &&
                !tenantId.equals(webFacade.getSession().getAttribute("moqui.tenantId"))) {
            final String hostName = (String) webFacade.getSession().getAttribute("moqui.tenantHostName");
            throw new BaseException("Tenant override is not allowed for host [" + (hostName != null ? hostName : "Unknown") + "].");
        }


        activeTenantId = tenantId;
        activeEntityFacade = ecfi.getEntityFacade(activeTenantId);
        if (tenantIdStack == null) {
            tenantIdStack = new LinkedList<>();
            tenantIdStack.addFirst(fromTenantId);
        } else {
            if (tenantIdStack.size() == 0 || !tenantIdStack.getFirst().equals(tenantId))
                tenantIdStack.addFirst(fromTenantId);
        }

        if (webFacade != null) webFacade.getSession().setAttribute("moqui.tenantId", tenantId);

        // instead of logout the current user (won't be valid in other tenant) push empty user onto user stack
        // if (userFacade != null && !userFacade.getLoggedInAnonymous()) userFacade.logoutUser()
        if (userFacade != null) userFacade.pushTenant(tenantId);

        // logger.info("Tenant now ${activeTenantId}, username ${userFacade?.username}")

        // re-init caches for new tenantId
        initCaches();

        return true;
    }

    @Override
    public boolean popTenant() {
        String lastTenantId = tenantIdStack != null && tenantIdStack.size() > 0 ? tenantIdStack.removeFirst() : null;
        if (lastTenantId != null && !lastTenantId.isEmpty()) {
            // logger.info("Pop tenant, last was ${lastTenantId}")
            if (userFacade != null) userFacade.popTenant(activeTenantId);
            return changeTenant(lastTenantId);
        } else {
            return false;
        }

    }

    @Override
    public void runAsync(Closure closure) {
        runInWorkerThread(closure);
    }

    public void runInWorkerThread(Closure closure) {
        ThreadPoolRunnable runnable = new ThreadPoolRunnable(this, closure);
        ecfi.workerPool.execute(runnable);
    }

    @Override
    public void destroy() {
        // if webFacade exists this is the end of a request, so trigger after-request actions
        if (webFacadeImpl != null) webFacadeImpl.runAfterRequestActions();

        // make sure there are no transactions open, if any commit them all now
        ecfi.transactionFacade.destroyAllInThread();
        // clean up resources, like JCR session
        ecfi.resourceFacade.destroyAllInThread();
        // clear out the ECFI's reference to this as well
        ecfi.activeContext.remove();

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl destroyed");
    }

    @Override
    public String toString() {
        return "ExecutionContext in tenant " + activeTenantId;
    }

    public static class ThreadPoolRunnable implements Runnable {
        private ExecutionContextFactoryImpl ecfi;
        private String threadTenantId;
        private String threadUsername;
        private Closure closure;

        public ThreadPoolRunnable(ExecutionContextImpl eci, Closure closure) {
            ecfi = eci.ecfi;
            threadTenantId = eci.getTenantId();
            threadUsername = eci.userFacade.getUsername();
            this.closure = closure;
        }

        public ThreadPoolRunnable(ExecutionContextFactoryImpl ecfi, String tenantId, String username, Closure closure) {
            this.ecfi = ecfi;
            threadTenantId = tenantId;
            threadUsername = username;
            this.closure = closure;
        }

        @Override
        public void run() {
            ExecutionContextImpl threadEci = null;
            try {
                threadEci = ecfi.getEci();
                threadEci.changeTenant(threadTenantId);
                if (threadUsername != null && threadUsername.length() > 0)
                    threadEci.userFacade.internalLoginUser(threadUsername, threadTenantId);
                closure.call();
            } catch (Throwable t) {
                loggerDirect.error("Error in EC thread pool runner", t);
            } finally {
                if (threadEci != null) threadEci.destroy();
            }

        }

        public ExecutionContextFactoryImpl getEcfi() { return ecfi; }
        public void setEcfi(ExecutionContextFactoryImpl ecfi) { this.ecfi = ecfi; }

        public String getThreadTenantId() { return threadTenantId; }
        public void setThreadTenantId(String threadTenantId) { this.threadTenantId = threadTenantId; }
        public String getThreadUsername() { return threadUsername; }
        public void setThreadUsername(String threadUsername) { this.threadUsername = threadUsername; }

        public Closure getClosure() { return closure; }
        public void setClosure(Closure closure) { this.closure = closure; }
    }
}
