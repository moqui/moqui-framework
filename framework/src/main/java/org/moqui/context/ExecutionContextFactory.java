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
package org.moqui.context;

import groovy.lang.GroovyClassLoader;
import org.moqui.context.ArtifactExecutionInfo.ArtifactType;
import org.moqui.entity.EntityFacade;
import org.moqui.screen.ScreenFacade;
import org.moqui.service.ServiceFacade;
import org.moqui.util.MNode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Interface for the object that will be used to get an ExecutionContext object and manage framework life cycle.
 */
public interface ExecutionContextFactory {
    /** Get the ExecutionContext associated with the current thread or initialize one and associate it with the thread. */
    @Nonnull ExecutionContext getExecutionContext();

    /** Destroy the active Execution Context. When another is requested in this thread a new one will be created. */
    void destroyActiveExecutionContext();

    /** Called after construction but before registration with Moqui/Servlet, check for empty database and load configured data.
     * If empty-db-load is not done and on-start-load-types has a value handles that as well.
     * Also loads type 'test' data if instance_purpose=test. */
    boolean checkEmptyDb();
    /** Destroy this ExecutionContextFactory and all resources it uses (all facades, tools, etc) */
    void destroy();
    boolean isDestroyed();

    /** Get the path of the runtime directory */
    @Nonnull String getRuntimePath();
    @Nonnull String getMoquiVersion();

    /** Get the root configuration XML node (MoquiConf merged from all sources) */
    @Nonnull MNode getConfXmlRoot();

    /** Get the named ToolFactory instance (loaded by configuration) */
    <V> ToolFactory<V> getToolFactory(@Nonnull String toolName);
    /** Get an instance object from the named ToolFactory instance (loaded by configuration); the instanceClass may be
     * null in scripts or other contexts where static typing is not needed */
    <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters);

    /** Get a Map where each key is a component name and each value is the component's base location. */
    @Nonnull LinkedHashMap<String, String> getComponentBaseLocations();

    /** For localization (l10n) functionality, like localizing messages. */
    @Nonnull L10nFacade getL10n();

    /** For accessing resources by location string (http://, jar://, component://, content://, classpath://, etc). */
    @Nonnull ResourceFacade getResource();

    /** For trace, error, etc logging to the console, files, etc. */
    @Nonnull LoggerFacade getLogger();

    /** For managing and accessing caches. */
    @Nonnull CacheFacade getCache();

    /** For transaction operations use this facade instead of the JTA UserTransaction and TransactionManager. See javadoc comments there for examples of code usage. */
    @Nonnull TransactionFacade getTransaction();

    /** For interactions with a relational database. */
    @Nonnull EntityFacade getEntity();

    /** For interactions with ElasticSearch using the built in HTTP REST client. */
    @Nonnull ElasticFacade getElastic();

    /** For calling services (local or remote, sync or async or scheduled). */
    @Nonnull ServiceFacade getService();

    /** For rendering screens for general use (mostly for things other than web pages or web page snippets). */
    @Nonnull ScreenFacade getScreen();

    /** Get the framework ClassLoader, aware of all additional classes in runtime and in components. */
    @Nonnull ClassLoader getClassLoader();
    /** Get a GroovyClassLoader for runtime compilation, etc. */
    @Nonnull GroovyClassLoader getGroovyClassLoader();

    /** The ServletContext, if Moqui was initialized in a webapp (generally through MoquiContextListener) */
    @Nonnull ServletContext getServletContext();
    /** The WebSocket ServerContainer, if found in 'jakarta.websocket.server.ServerContainer' ServletContext attribute */
    @Nonnull ServerContainer getServerContainer();
    /** For starting initialization only, tell the ECF about the ServletContext for getServletContext() and getServerContainer() */
    void initServletContext(ServletContext sc);

    void registerNotificationMessageListener(@Nonnull NotificationMessageListener nml);

    void registerLogEventSubscriber(@Nonnull LogEventSubscriber subscriber);
    List<LogEventSubscriber> getLogEventSubscribers();

    // ========== ARCH-001: Configuration Access Methods ==========

    /** Get the server-stats configuration node */
    @Nullable MNode getServerStatsNode();

    /** Get the webapp configuration node for the given webapp name */
    @Nullable MNode getWebappNode(String webappName);

    /** Get the artifact execution configuration node for the given artifact type */
    @Nullable MNode getArtifactExecutionNode(String artifactTypeEnumId);

    // ========== ARCH-001: Web/Network Methods ==========

    /** Get the localhost address */
    @Nullable InetAddress getLocalhostAddress();

    // ========== ARCH-001: Worker Pool and Security ==========

    /** Get the main worker thread pool for async operations, service calls, etc. */
    @Nonnull ThreadPoolExecutor getWorkerPool();

    /** Get the Shiro SecurityManager for authentication and authorization. */
    @Nonnull org.apache.shiro.mgt.SecurityManager getSecurityManager();

    /** Get the time this factory was initialized (start time in milliseconds). */
    long getInitStartTime();

    // ========== ARCH-001: Artifact Statistics ==========

    /**
     * Get map indicating which artifact types have authorization enabled.
     * @return Map of ArtifactType to Boolean (true if authz enabled)
     */
    @Nonnull Map<ArtifactExecutionInfo.ArtifactType, Boolean> getArtifactTypeAuthzEnabled();

    /**
     * Get map indicating which artifact types have tarpit (rate limiting) enabled.
     * @return Map of ArtifactType to Boolean (true if tarpit enabled)
     */
    @Nonnull Map<ArtifactExecutionInfo.ArtifactType, Boolean> getArtifactTypeTarpitEnabled();

    /** Count an artifact hit for statistics tracking */
    void countArtifactHit(@Nonnull ArtifactType artifactTypeEnum, String artifactSubType, String artifactName,
                         Map<String, Object> parameters, long startTime, double runningTimeMillis, Long outputSize);

    // ========== ARCH-001: Scheduled Execution ==========

    /** Schedule a runnable to execute at a fixed rate */
    void scheduleAtFixedRate(@Nonnull Runnable command, long initialDelaySeconds, long periodSeconds);

    // ========== ARCH-001: Groovy Compilation ==========

    /** Compile Groovy source code at runtime */
    Class<?> compileGroovy(String script, String className);

    // ========== ARCH-001: Status/Monitoring ==========

    /** Get the framework status map */
    @Nonnull Map<String, Object> getStatusMap();

    /** Get the framework status map, optionally including sensitive information */
    @Nonnull Map<String, Object> getStatusMap(boolean includeSensitive);

    /** Get the list of loaded component information */
    @Nonnull List<Map<String, Object>> getComponentInfoList();

    /** Get the version map from version.json */
    @Nullable Map<?, ?> getVersionMap();

    // ========== ARCH-001: Security/Password Methods ==========

    /** Get the configured password hash type */
    @Nonnull String getPasswordHashType();

    /** Hash a password using the configured hash type */
    @Nonnull String getSimpleHash(String source, String salt);

    /** Hash a password using the specified hash type */
    @Nonnull String getSimpleHash(String source, String salt, String hashType, boolean isBase64);

    /** Get the login key hash type */
    @Nonnull String getLoginKeyHashType();

    /** Get the login key expiration hours */
    float getLoginKeyExpireHours();

    /** Check if a password hash should be upgraded to a newer algorithm */
    boolean shouldUpgradePasswordHash(String currentHashType);
}
