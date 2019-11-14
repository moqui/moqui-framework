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

import groovy.lang.Closure;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityValue;
import org.moqui.screen.ScreenFacade;
import org.moqui.service.ServiceFacade;
import org.moqui.util.ContextBinding;
import org.moqui.util.ContextStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Interface definition for object used throughout the Moqui Framework to manage contextual execution information and
 * tool interfaces. One instance of this object will exist for each thread running code and will be applicable for that
 * thread only.
 */
@SuppressWarnings("unused")
public interface ExecutionContext {
    /** Get the ExecutionContextFactory this came from. */
    @Nonnull ExecutionContextFactory getFactory();

    /** Returns a Map that represents the current local variable space (context) in whatever is being run. */
    @Nonnull ContextStack getContext();
    @Nonnull ContextBinding getContextBinding();
    /** Returns a Map that represents the global/root variable space (context), ie the bottom of the context stack. */
    @Nonnull Map<String, Object> getContextRoot();

    /** Get an instance object from the named ToolFactory instance (loaded by configuration). Some tools return a
     * singleton instance, others a new instance each time it is used and that instance is saved with this
     * ExecutionContext to be reused. The instanceClass may be null in scripts or other contexts where static typing
     * is not needed */
    <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters);

    /** If running through a web (HTTP servlet) request offers access to the various web objects/information.
     * If not running in a web context will return null.
     */
    @Nullable WebFacade getWeb();

    /** For information about the user and user preferences (including locale, time zone, currency, etc). */
    @Nonnull UserFacade getUser();

    /** For user messages including general feedback, errors, and field-specific validation errors. */
    @Nonnull MessageFacade getMessage();

    /** For information about artifacts as they are being executed. */
    @Nonnull ArtifactExecutionFacade getArtifactExecution();

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

    @Nonnull NotificationMessage makeNotificationMessage();
    @Nonnull List<NotificationMessage> getNotificationMessages(@Nullable String topic);

    /** This should be called by a filter or servlet at the beginning of an HTTP request to initialize a web facade
     * for the current thread. */
    void initWebFacade(@Nonnull String webappMoquiName, @Nonnull HttpServletRequest request, @Nonnull HttpServletResponse response);

    /** A lightweight asynchronous executor. An alternative to Quartz, still ExecutionContext aware and uses
     * the current ExecutionContext in the separate thread (retaining user, authz context, etc). */
    void runAsync(@Nonnull Closure closure);

    /** This should be called when the ExecutionContext won't be used any more. Implementations should make sure
     * any active transactions, database connections, etc are closed.
     */
    void destroy();
}
