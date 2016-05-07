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

import com.hazelcast.core.HazelcastInstance;
import org.apache.camel.CamelContext;
import org.elasticsearch.client.Client;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.StatelessKieSession;
import org.moqui.BaseException;
import org.moqui.entity.EntityFacade;
import org.moqui.screen.ScreenFacade;
import org.moqui.service.ServiceFacade;

import java.util.LinkedHashMap;

/**
 * Interface for the object that will be used to get an ExecutionContext object and manage framework life cycle.
 */
public interface ExecutionContextFactory {
    /** Initialize a simple ExecutionContext for use in a non-webapp context, like a remote service call or similar. */
    ExecutionContext getExecutionContext();

    /** Destroy the active Execution Context. When another is requested in this thread a new one will be created. */
    void destroyActiveExecutionContext();

    /** Run after construction is complete and object is active in the Moqui class (called by Moqui.java) */
    void postInit();
    /** Destroy this Execution Context Factory. */
    void destroy();

    String getRuntimePath();

    /**
     * Register a component with the framework. The component name will be the last directory in the location path
     * unless there is a component.xml file in the directory and the component.@name attribute is specified.
     *
     * @param location A file system directory or a content repository location (the component base location).
     */
    void initComponent(String location) throws BaseException;

    /**
     * Destroy a component that has been initialized.
     *
     * All initialized components will be destroyed when the framework is destroyed, but this can be used to destroy
     * a component while the framework is still running.
     *
     * @param componentName A component name.
     */
    void destroyComponent(String componentName) throws BaseException;

    /** Get a Map where each key is a component name and each value is the component's base location. */
    LinkedHashMap<String, String> getComponentBaseLocations();

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

    /** Apache Camel is used for integration message routing. To interact directly with Camel get the context here. */
    CamelContext getCamelContext();

    /** ElasticSearch Client is used for indexing and searching documents */
    Client getElasticSearchClient();

    /** Hazelcast Instance, used for clustered data sharing and execution including web session replication and distributed cache */
    HazelcastInstance getHazelcastInstance();

    /** Get a KIE Container for Drools, jBPM, OptaPlanner, etc from the KIE Module in the given component. */
    KieContainer getKieContainer(String componentName);
    /** Get a KIE Session by name from the last component KIE Module loaded with the given session name. */
    KieSession getKieSession(String ksessionName);
    /** Get a KIE Stateless Session by name from the last component KIE Module loaded with the given session name. */
    StatelessKieSession getStatelessKieSession(String ksessionName);
}
