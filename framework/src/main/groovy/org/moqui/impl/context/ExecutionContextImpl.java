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
import org.moqui.context.*;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityFind;
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
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.cache.Cache;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

public class ExecutionContextImpl implements ExecutionContext {
    private static final Logger loggerDirect = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class);

    public final ExecutionContextFactoryImpl ecfi;
    public final ContextStack contextStack = new ContextStack();
    public final ContextBinding contextBindingInternal = new ContextBinding(contextStack);

    private EntityFacadeImpl activeEntityFacade;

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

    public final String forThreadName;
    public final long forThreadId;
    // public final Exception createLoc;

    public ExecutionContextImpl(ExecutionContextFactoryImpl ecfi, Thread forThread) {
        this.ecfi = ecfi;
        // NOTE: no WebFacade init here, wait for call in to do that
        // put reference to this in the context root
        contextStack.put("ec", this);
        forThreadName = forThread.getName();
        forThreadId = forThread.getId();
        // createLoc = new BaseException("ec create");

        activeEntityFacade = ecfi.entityFacade;
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

        if (cacheFacade == null) throw new IllegalStateException("cacheFacade was null");
        if (loggerFacade == null) throw new IllegalStateException("loggerFacade was null");
        if (resourceFacade == null) throw new IllegalStateException("resourceFacade was null");
        if (screenFacade == null) throw new IllegalStateException("screenFacade was null");
        if (serviceFacade == null) throw new IllegalStateException("serviceFacade was null");
        if (transactionFacade == null) throw new IllegalStateException("transactionFacade was null");

        initCaches();

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl initialized");
    }

    @SuppressWarnings("unchecked")
    private void initCaches() {
        tarpitHitCache = cacheFacade.getCache("artifact.tarpit.hits");
        l10nMessageCache = cacheFacade.getCache("l10n.message");
    }
    Cache<String, String> getL10nMessageCache() { return l10nMessageCache; }
    public Cache<String, ArrayList> getTarpitHitCache() { return tarpitHitCache; }

    @Override public @Nonnull ExecutionContextFactory getFactory() { return ecfi; }

    @Override public @Nonnull ContextStack getContext() { return contextStack; }
    @Override public @Nonnull Map<String, Object> getContextRoot() { return contextStack.getRootMap(); }
    @Override public @Nonnull ContextBinding getContextBinding() { return contextBindingInternal; }

    @Override
    public <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters) {
        return ecfi.getTool(toolName, instanceClass, parameters);
    }

    @Override public @Nullable WebFacade getWeb() { return webFacade; }
    public @Nullable WebFacadeImpl getWebImpl() { return webFacadeImpl; }

    @Override public @Nonnull UserFacade getUser() { return userFacade; }
    @Override public @Nonnull MessageFacade getMessage() { return messageFacade; }
    @Override public @Nonnull ArtifactExecutionFacade getArtifactExecution() { return artifactExecutionFacade; }
    @Override public @Nonnull L10nFacade getL10n() { return l10nFacade; }
    @Override public @Nonnull ResourceFacade getResource() { return resourceFacade; }
    @Override public @Nonnull LoggerFacade getLogger() { return loggerFacade; }
    @Override public @Nonnull CacheFacade getCache() { return cacheFacade; }
    @Override public @Nonnull TransactionFacade getTransaction() { return transactionFacade; }

    @Override public @Nonnull EntityFacade getEntity() { return activeEntityFacade; }
    public @Nonnull EntityFacadeImpl getEntityFacade() { return activeEntityFacade; }

    @Override public @Nonnull ElasticFacade getElastic() { return ecfi.elasticFacade; }
    @Override public @Nonnull ServiceFacade getService() { return serviceFacade; }
    @Override public @Nonnull ScreenFacade getScreen() { return screenFacade; }

    @Override public @Nonnull NotificationMessage makeNotificationMessage() { return new NotificationMessageImpl(ecfi); }

    @Override
    public @Nonnull List<NotificationMessage> getNotificationMessages(@Nullable String topic) {
        String userId = userFacade.getUserId();
        if (userId == null || userId.isEmpty()) return new ArrayList<>();

        List<NotificationMessage> nmList = new ArrayList<>();
        boolean alreadyDisabled = artifactExecutionFacade.disableAuthz();
        try {
            EntityFind nmbuFind = activeEntityFacade.find("moqui.security.user.NotificationMessageByUser").condition("userId", userId);
            if (topic != null && !topic.isEmpty()) nmbuFind.condition("topic", topic);
            EntityList nmbuList = nmbuFind.list();
            for (EntityValue nmbu : nmbuList) {
                NotificationMessageImpl nmi = new NotificationMessageImpl(ecfi);
                nmi.populateFromValue(nmbu);
                nmList.add(nmi);
            }
        } finally {
            if (!alreadyDisabled) artifactExecutionFacade.enableAuthz();
        }

        return nmList;
    }

    @Override
    public void initWebFacade(@Nonnull String webappMoquiName, @Nonnull HttpServletRequest request, @Nonnull HttpServletResponse response) {
        WebFacadeImpl wfi = new WebFacadeImpl(webappMoquiName, request, response, this);
        webFacade = wfi;
        webFacadeImpl = wfi;

        // now that we have the webFacade in place we can do init UserFacade
        userFacade.initFromHttpRequest(request, response);
        // for convenience (and more consistent code in screen actions, services, etc) add all requestParameters to the context
        contextStack.putAll(webFacade.getRequestParameters());
        // this is the beginning of a request, so trigger before-request actions
        wfi.runBeforeRequestActions();

        String userId = userFacade.getUserId();
        if (userId != null && !userId.isEmpty()) MDC.put("moqui_userId", userId);
        String visitorId = userFacade.getVisitorId();
        if (visitorId != null && !visitorId.isEmpty()) MDC.put("moqui_visitorId", visitorId);

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl WebFacade initialized");
    }

    /** Meant to be used to set a test stub that implements the WebFacade interface */
    public void setWebFacade(WebFacade wf) {
        webFacade = wf;
        if (wf instanceof WebFacadeImpl) webFacadeImpl = (WebFacadeImpl) wf;
        contextStack.putAll(webFacade.getRequestParameters());
    }

    public boolean getSkipStats() {
        if (skipStats != null) return skipStats;
        String skipStatsCond = ecfi.skipStatsCond;
        Map<String, Object> skipParms = new HashMap<>();
        if (webFacade != null) skipParms.put("pathInfo", webFacade.getPathInfo());
        skipStats = (skipStatsCond != null && !skipStatsCond.isEmpty()) && ecfi.resourceFacade.condition(skipStatsCond, null, skipParms);
        return skipStats;
    }

    @Override
    public void runAsync(@Nonnull Closure closure) {
        ThreadPoolRunnable runnable = new ThreadPoolRunnable(this, closure);
        ecfi.workerPool.submit(runnable);
    }
    /** Uses the ECFI constructor for ThreadPoolRunnable so does NOT use the current ECI in the separate thread */
    public void runInWorkerThread(@Nonnull Closure closure) {
        ThreadPoolRunnable runnable = new ThreadPoolRunnable(ecfi, closure);
        ecfi.workerPool.submit(runnable);
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
        ecfi.activeContextMap.remove(Thread.currentThread().getId());

        MDC.remove("moqui_userId");
        MDC.remove("moqui_visitorId");

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl destroyed");
    }

    @Override public String toString() { return "ExecutionContext"; }

    public static class ThreadPoolRunnable implements Runnable {
        private ExecutionContextImpl threadEci;
        private ExecutionContextFactoryImpl ecfi;
        private Closure closure;
        /** With this constructor (passing ECI) the ECI is used in the separate thread */
        public ThreadPoolRunnable(ExecutionContextImpl eci, Closure closure) {
            threadEci = eci;
            ecfi = eci.ecfi;
            this.closure = closure;
        }

        /** With this constructor (passing ECFI) a new ECI is created for the separate thread */
        public ThreadPoolRunnable(ExecutionContextFactoryImpl ecfi, Closure closure) {
            this.ecfi = ecfi;
            threadEci = null;
            this.closure = closure;
        }

        @Override
        public void run() {
            if (threadEci != null) ecfi.useExecutionContextInThread(threadEci);
            try {
                closure.call();
            } catch (Throwable t) {
                loggerDirect.error("Error in EC worker Runnable", t);
            } finally {
                if (threadEci == null) ecfi.destroyActiveExecutionContext();
            }
        }

        public ExecutionContextFactoryImpl getEcfi() { return ecfi; }
        public void setEcfi(ExecutionContextFactoryImpl ecfi) { this.ecfi = ecfi; }
        public Closure getClosure() { return closure; }
        public void setClosure(Closure closure) { this.closure = closure; }
    }
}
