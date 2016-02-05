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

import java.util.List;
import java.util.Map;

import org.apache.camel.CamelContext;
import org.elasticsearch.client.Client;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.StatelessKieSession;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityValue;
import org.moqui.screen.ScreenFacade;
import org.moqui.service.ServiceFacade;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Interface definition for object used throughout the Moqui Framework to manage contextual execution information and
 * tool interfaces. One instance of this object will exist for each thread running code and will be applicable for that
 * thread only.
 */
public interface ExecutionContext {
    /** Returns a Map that represents the current local variable space (context) in whatever is being run. */
    ContextStack getContext();

    /** Returns a Map that represents the global/root variable space (context), ie the bottom of the context stack. */
    Map<String, Object> getContextRoot();

    /** Get current Tenant ID. A single application may be run in multiple virtual instances, one for each Tenant, and
     * each will have its own set of databases (except for the tenant database which is shared among all Tenants).
     */
    String getTenantId();
    EntityValue getTenant();

    /** If running through a web (HTTP servlet) request offers access to the various web objects/information.
     * If not running in a web context will return null.
     */
    WebFacade getWeb();

    /** For information about the user and user preferences (including locale, time zone, currency, etc). */
    UserFacade getUser();

    /** For user messages including general feedback, errors, and field-specific validation errors. */
    MessageFacade getMessage();

    /** For information about artifacts as they are being executed. */
    ArtifactExecutionFacade getArtifactExecution();

    /** For localization (l10n) functionality, like localizing messages. */
    L10nFacade getL10n();

    /** For accessing resources by location string (http://, jar://, component://, content://, classpath://, etc). */
    ResourceFacade getResource();

    /** For trace, error, etc logging to the console, files, etc. */
    LoggerFacade getLogger();

    /** For managing and accessing caches. */
    CacheFacade getCache();

    /** For transaction operations use this facade instead of the JTA UserTransaction and TransactionManager. See javadoc comments there for examples of code usage. */
    TransactionFacade getTransaction();

    /** For interactions with a relational database. */
    EntityFacade getEntity();

    /** For calling services (local or remote, sync or async or scheduled). */
    ServiceFacade getService();

    /** For rendering screens for general use (mostly for things other than web pages or web page snippets). */
    ScreenFacade getScreen();

    NotificationMessage makeNotificationMessage();
    List<NotificationMessage> getNotificationMessages(String userId, String topic);
    void registerNotificationMessageListener(NotificationMessageListener nml);

    /** Apache Camel is used for integration message routing. To interact directly with Camel get the context here. */
    CamelContext getCamelContext();

    /** ElasticSearch Client is used for indexing and searching documents */
    Client getElasticSearchClient();

    /** Get a KIE Container for Drools, jBPM, OptaPlanner, etc from the KIE Module in the given component. */
    KieContainer getKieContainer(String componentName);
    /** Get a KIE Session by name from the last component KIE Module loaded with the given session name. */
    KieSession getKieSession(String ksessionName);
    /** Get a KIE Stateless Session by name from the last component KIE Module loaded with the given session name. */
    StatelessKieSession getStatelessKieSession(String ksessionName);

    /** This should be called by a filter or servlet at the beginning of an HTTP request to initialize a web facade
     * for the current thread.
     */
    void initWebFacade(String webappMoquiName, HttpServletRequest request, HttpServletResponse response);

    /** Change the active tenant and push the tenantId on a stack. Does nothing if tenantId is the current.
     *  @return True if tenant changed, false otherwise (tenantId matches the current tenantId) */
    boolean changeTenant(String tenantId);
    /** Change the tenant to the last tenantId on the stack. Returns false if the tenantId stack is empty.
     * @return True if tenant changed, false otherwise (tenantId stack is empty or tenantId matches the current tenantId) */
    boolean popTenant();

    /** This should be called when the ExecutionContext won't be used any more. Implementations should make sure
     * any active transactions, database connections, etc are closed.
     */
    void destroy();
}
