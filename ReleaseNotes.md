
# Moqui Framework Release Notes

## Release 2.0.0 - TBD

Moqui Framework 2.0.0 is a major new feature and bug fix release, with
various non backward compatible API and other changes.

This version is not yet released. The notes below are for the changes
currently in place in the source repository. If you plan to go into
production in the near future (before this version is released) it is
better to use the last released version (1.6.2) as 2.0.0 contains
significant changes.

### Non Backward Compatible Changes

- Java JDK 8 now required (Java 7 no longer supported)
- Now requires Servlet Container supporting the Servlet 3.1 specification
- No longer using Winstone embedded web server, now using Jetty
- Multi-Tenant Functionality Removed
  - ExecutionContext.getTenant() and getTenantId() removed
  - UserFacade.loginUser() third parameter (tenantId) removed
  - CacheFacade.getCache() with second parameter for tenantId removed
  - EntityFacade no longer per-tenant, getTenantId() removed
  - TransactionInternal and EntityDatasourceFactory methods no longer have tenantId parameter
  - Removed tenantcommon entity group and moqui.tenant entities
  - Removed tenant related MoquiStart command line options
  - Removed tenant related Moqui Conf XML, XML Screen, etc attributes
- Entity Definitions
  - XSDs updated for these changes, though old attributes still supported
  - changed entity.@package-name to entity.@package
  - changed entity.@group-name to entity.@group
  - changed relationship.@related-entity-name to relationship.@related
  - changed key-map.@related-field-name to key-map.@related
  - UserField no longer supported (UserField and UserFieldValue entities)
- Service Job Scheduling
  - Quartz Scheduler has been removed, use new ServiceJob instead with more relevant options, much cleaner and more manageable
  - Removed ServiceFacade.getScheduler() method
  - Removed ServiceCallSchedule interface, implementation, and ServiceFacade.schedule() factory method
  - Removed ServiceQuartzJob class (impl of Job interface)
  - Removed EntityJobStore class (impl of JobStore interface); this is a huge and complicated class to handle the various 
    complexities of Quartz and was never fully working, had some remaining issues in testing
  - Removed HistorySchedulerListener and HistoryTriggerListener classes
  - Removed all entities in the moqui.service.scheduler and moqui.service.quartz packages
  - Removed quartz.properties and quartz_data.xml configuration files
  - Removed Scheduler screens from System app in tools component
  - For all of these artifacts see moqui-framework commit #d42ede0 and moqui-runtime commit #6a9c61e
- Externalized Tools
  - ElasticSearch (and Apache Lucene)
    - libraries, classes and all related services, screens, etc are now in the moqui-elasticsearch component
    - System/DataDocument screens now in moqui-elasticsearch component and added to tools/System app through SubscreensItem record
    - all ElasticSearch services in org.moqui.impl.EntityServices moved to org.moqui.search.SearchServices including:
      index#DataDocuments, put#DataDocumentMappings, index#DataFeedDocuments, search#DataDocuments, search#CountBySource
    - Moved index#WikiSpacePages service from org.moqui.impl.WikiServices to org.moqui.search.SearchServices
    - ElasticSearch dependent REST API methods moved to the 'elasticsearch' REST API in the moqui-elasticsearch component
  - Apache FOP is now in the moqui-fop tool component; everything in the framework, including the now poorly named MoquiFopServlet, 
    use generic interfaces but XML-FO files will not transform to PDF/etc without this component in place
  - OrientDB and Entity Facade interface implementations are now in the moqui-orientdb component, see its README.md for usage
  - Apache Camel along with the CamelServiceRunner and MoquiServiceEndpoint are now in the moqui-camel component which has a 
    MoquiConf.xml file so no additional configuration is needed
  - JBoss KIE and Drools are now in tool component moqui-kie, an optional component for mantle-usl; has MoquiConf to add ToolFactory
  - Atomikos TM moved to moqui-atomikos tool component
- ExecutionContext and ExecutionContextFactory
  - Removed initComponent(), destroyComponent() methods; were never well supported (runtime component init/destroy caused issues)
  - Removed getCamelContext() from ExecutionContextFactory and ExecutionContext, use getTool("Camel", CamelContext.class)
  - Removed getElasticSearchClient() from ExecutionContextFactory and ExecutionContext, use getTool("ElasticSearch", Client.class)
  - Removed getKieContainer, getKieSession, and getStatelessKieSession methods from ExecutionContextFactory and ExecutionContext, 
    use getTool("KIE", KieToolFactory.class) and use the corresponding methods there
  - See new feature notes under Tool Factory
- Caching
  - Ehcache has been removed
  - The org.moqui.context.Cache interface is replaced by javax.cache.Cache
  - Configuration options for caches changed (moqui-conf.cache-list.cache)
- NotificationMessage
  - NotificationMessage, NotificationMessageListener interfaces have various changes for more features and to better support 
    serialized messages for notification through a distributed topic
- Async Services
  - Now uses more standard java.util.concurrent interfaces
  - Removed ServiceCallAsync.maxRetry() - was never supported
  - Removed ServiceCallAsync.persist() - was never supported well, used to simply call through Quartz Scheduler when set
  - Removed persist option from XML Actions service-call.@async attribute
  - Async services never called through Quartz Scheduler (only scheduled)
  - ServiceCallAsync.callWaiter() replaced by callFuture()
  - Removed ServiceCallAsync.resultReceiver()
  - Removed ServiceResultReceiver interface - use callFuture() instead
  - Removed ServiceResultWaiter class - use callFuture() instead
  - See related new features below
- Service parameter.subtype element removed, use the much more flexible nested parameter element
- JCR and Apache Jackrabbit
  - The repository.@type, @location, and @conf-location attributes have been removed and the repository.parameter sub-element 
    added for use with the javax.jcr.RepositoryFactory interface
  - See new configuration examples in MoquiDefaultConf.xml under the repository-list element
- OWASP ESAPI and AntiSamy
  - ESAPI removed, now using simple StringEscapeUtils from commons-lang
  - AntiSamy replaced by OWASP Java HTML Sanitizer
- Removed ServiceSemaphore entity, now using ServiceParameterSemaphore
- Deprecated methods
  - These methods were deprecated (by methods with shorter names) long ago and with other API changes now removing them
  - Removed getLocalizedMessage() and formatValue() from L10nFacade
  - Removed renderTemplateInCurrentContext(), runScriptInCurrentContext(), evaluateCondition(), evaluateContextField(), and 
    evaluateStringExpand() from ResourceFacade
  - Removed EntityFacade.makeFind()   
- ArtifactHit and ArtifactHitBin now use same artifact type enum as ArtifactAuthz, for efficiency and consistency; configuration of
  artifact-stats by sub-type no longer supported, had little value and caused performance overhead
- Removed ArtifactAuthzRecord/Cond entities and support for them; this was never all that useful and is replaced by the 
  ArtifactAuthzFilter and EntityFilter entities
- The ContextStack class has moved to the org.moqui.util package
- Replaced Apache HttpComponents client with jetty-client to get support for HTTP/2, cleaner API, better async support, etc
- When updating to this version recommend stopping all instances in a cluster before starting any instance with the new version

### New Features

- Now using Jetty embedded for the executable WAR instead of Winstone
  - using Jetty 9 which requires Java 8
  - now internally using Servlet API 3.1.0
- Various library updates, cleanup of classes found in multiple jar files (ElasticSearch JarHell checks pass; nice in general)
- Configuration
  - Added default-property element to set Java System properties from the configuration file
  - Added Groovy string expansion to various configuration attributes
    - looks for named fields in Java System properties and environment variables
    - used in default-property.@value and all xa-properties attributes
    - replaces the old explicit check for ${moqui.runtime}, which was a simple replacement hack
    - because these are Groovy expressions the typical dots used in property names cannot be used in these strings, use an 
      underscore instead of a dot, ie ${moqui_runtime} instead of ${moqui.runtime}; if a property name contains underscores and 
      no value is found with the literal name it replaces underscores with dots and looks again
- Deployment and Docker
  - The MoquiStart class can now run from an expanded WAR file, i.e. from a directory with the contents of a Moqui executable WAR
  - On startup DataSource (database) connections are retried 5 times, every 5 seconds, for situations where init of separate 
    containers is triggered at the same time like with Docker Compose
  - Added a MySQLConf.xml file where settings can come from Java system properties or system environment variables
  - The various webapp.@http* attributes can now be set as system properties or environment variables
  - Added a Dockerfile and docker-build.sh script to build a Docker image from moqui-plus-runtime.war or moqui.war and runtime
  - Added sample Docker Compose files for moqui+mysql, and for moqui, mysql, and nginx-proxy for reverse proxy that supports 
    virtual hosts for multiple Docker containers running Moqui
  - Added script to run a Docker Compose file after copying configuration and data persistence runtime directories if needed
- Tool Factory
  - Added org.moqui.context.ToolFactory interface used to initialize, destroy, and get instances of tools
  - Added tools.tool-factory element in Moqui Conf XML file; has default tools in MoquiDefaultConf.xml and can be populated or 
    modified in component and/or runtime conf XML files
  - Use new ExecutionContextFactory.getToolFactory(), ExecutionContextFactory.getTool(), and ExecutionContext.getTool() methods 
    to interact with tools
  - See non backward compatible change notes for ExecutionContextFactory
- WebSocket Support
  - Now looks for javax.websocket.server.ServerContainer in ServletContext during init, available from ECFI.getServerContainer()
  - If ServletContainer found adds endpoints defined in the webapp.endpoint element in the Moqui Conf XML file
  - Added MoquiAbstractEndpoint, extend this when implementing an Endpoint so that Moqui objects such as ExecutionContext/Factory 
    are available, UserFacade initialized from handshake (HTTP upgrade) request, etc
  - Added NotificationEndpoint which listens for NotificationMessage through ECFI and sends them over WebSocket to notify user
- NotificationMessage
  - Notifications can now be configured to send through a topic interface for distributed topics (implemented in the 
    moqui-hazelcast component); this handles the scenario where a notification is generated on one server but a user is connected 
    (by WebSocket, etc) to another
  - Various additional fields for display in the JavaScript NotificationClient including type, title and link templates, etc
- Caching
  - CacheFacade now supports separate local and distributed caches both using the javax.cache interfaces
  - Added new MCache class for faster local-only caches
    - implements the javax.cache.Cache interface
    - supports expire by create, access, update
    - supports custom expire on get
    - supports max entries, eviction done in separate thread
  - Support for distributed caches such as Hazelcast
  - New interfaces to plugin entity distributed cache invalidation through a SimpleTopic interface, supported in moqui-hazelcast
  - Set many entities to cache=never, avoid overhead of cache where read/write ratio doesn't justify it or cache could cause issues
- Async Services
  - ServiceCallAsync now using standard java.util.concurrent interfaces
  - Use callFuture() to get a Future object instead of callWaiter()
  - Can now get Runnable or Callable objects to run a service through a ExecutorService of your choice
  - Services can now be called local or distributed
    - Added ServiceCallAsync.distribute() method
    - Added distribute option to XML Actions service-call.@async attribute
    - Distributed executor is configurable, supported in moqui-hazelcast
  - Distributed services allow offloading service execution to worker nodes
- Service Jobs
  - Configure ad-hoc (explicitly executed) or scheduled jobs using the new ServiceJob and related entities
  - Tracks execution in ServiceJobRun records
  - Can send NotificationMessage, success or error, to configured topic
  - Run service job through ServiceCallJob interface, ec.service.job()
  - Replacement for Quartz Scheduler scheduled services
- Hazelcast Integration (moqui-hazelcast component)
  - These features are only enabled with this tool component in place
  - Added default Hazelcast web session replication config
  - Hazelcast can be used for distributed entity cache, web session replication, distributed execution, and OrientDB clustering
  - Implemented distributed entity cache invalidate using a Hazelcast Topic, enabled in Moqui Conf XML file with the
    @distributed-cache-invalidate attribute on the entity-facade element
- XSL-FO rendering now supports a generic ToolFactory to create a org.xml.sax.ContentHandler object, with an implementation 
  using Apache FOP now in the moqui-fop component
- JCR and Apache Jackrabbit
  - JCR support (for content:// locations in the ResourceFacade) now uses javax.jcr interfaces only, no dependencies on Jackrabbit
  - JCR repository configuration now supports other JCR implementations by using RepositoryFactory parameters
- Added ADMIN_PASSWORD permission for administrative password change (in UserServices.update#Password service)
- Added UserServices.enable#UserAccount service to enable disabled account
- Added support for error screens rendered depending on type of error
  - configured in the webapp.error-screen element in Moqui Conf XML file
  - if error screen render fails sends original error response
  - this is custom content that avoids sending an error response
- A component may now have a MoquiConf.xml file that overrides the default configuration file (MoquiDefaultConf.xml from the 
  classpath) but is overridden by the runtime configuration file; the MoquiConf.xml file in each component is merged into the main 
  conf based on the component dependency order (logged on startup)
- Added ExecutionContext.runAsync method to run a closure in a worker thread with an ExecutionContext like the current (user, etc)
- Added configuration for worker thread pool parameters, used for local async services, EC.runAsync, etc
- Transaction Facade
  - The write-through transaction cache now supports a read only mode
  - Added service.@no-tx-cache attribute which flushes and disables write through transaction cache for the rest of the transaction
  - Added flushAndDisableTransactionCache() method to flush/disable the write through cache like service.@no-tx-cache
- Entity Facade
  - In view-entity.alias.complex-alias the expression attribute is now expanded so context fields may be inserted or other Groovy 
    expressions evaluated using dollar-sign curly-brace (${}) syntax
  - Added view-entity.alias.case element with when and else sub-elements that contain complex-alias elements; these can be used for 
    CASE, CASE WHEN, etc SQL expressions
  - EntityFind.searchFormMap() now has a defaultParameters argument, used when no conditions added from the input fields Map
  - EntityDataWriter now supports export with a entity master definition name, applied only to entities exported that have a master 
    definition with the given master name
- XML Screen and Form
  - form-list now supports @header-dialog to put header-field widgets in a dialog instead of in the header
  - form-list now supports @select-columns to allow users to select which fields are displayed in which columns, or not displayed
  - added search-form-inputs.default-parameters element whose attributes are used as defaultParameters in searchFormMap()
  - ArtifactAuthzFailure records are only created when a user tries to use an artifact, not when simply checking to see if use is 
    permitted (such as in menus, links, etc)
  - significant macro cleanups and improvements
  - csv render macros now improved to support more screen elements, more intelligently handle links (only include anchor/text), etc
  - text render macros now use fixed width output (number of characters) along with new field attributes to specify print settings

### Bug Fixes

- Fixed issue with REST and other requests using various HTTP request methods that were not handled, MoquiServlet now uses the
  HttpServlet.service() method instead of the various do*() methods
- Fixed issue with REST and other JSON request body parameters where single entry lists were unwrapped to just the entry
- Fixed NPE in EntityFind.oneMaster() when the master value isn't found, returns null with no error; fixes moqui-runtime issue #18
- Fixed ElFinder rm (moqui-runtime GitHub issue #23), response for upload
- Screen sub-content directories treated as not found so directory entries not listed (GitHub moqui-framework issue #47)
- In entity cache auto clear for list of view-entity fixed mapping of member entity fields to view entity alias, and partial match 
  when only some view entity fields are on a member entity
- Cache clear fix for view-entity list cache, fixes adding a permission on the fly
- Fixed issue with Entity/DataEdit screens in the Tools application where the parameter and form field name 'entityName' conflicted 
  with certain entities that have a field named entityName
- Concurrency Issues
  - Fixed concurrent update errors in EntityCache RA (reverse association) using Collections.synchronizedList()
  - Fixed per-entity DataFeed info rebuild to avoid multiple runs and rebuild before adding to cache in use to avoid partial data
  - Fixed attribute and child node wrapper caching in FtlNodeWrapper where in certain cases a false null would be returned

## Release 1.6.2 - 26 Mar 2016

Moqui Framework 1.6.2 is a minor new feature and bug fix release.

This release is all about performance improvements, bug fixes, library
updates and cleanups. There are a number of minor new features like better
multi-tenant handling (and security), optionally loading data on start if
the DB is empty, more flexible handling of runtime Moqui Conf XML location,
database support and transaction management, and so on.

### Non Backward Compatible Changes

- Entity field types are somewhat more strict for database operations; this
  is partly for performance reasons and partly to avoid database errors
  that happen only on certain databases (ie some allow passing a String for
  a Timestamp, others don't; now you have to use a Timestamp or other date
  object); use EntityValue.setString or similar methods to do data
  conversions higher up
- Removed the TenantCurrency, TenantLocale, TenantTimeZone, and
  TenantCountry entities; they aren't generally used and better not to have
  business settings in these restricted technical config entities

### New Features

- Many performance improvements based on profiling; cached entities finds
  around 6x faster, non cached around 3x; screen rendering also faster
- Added JDBC Connection stash by tenant, entity group, and transaction,
  can be disabled with transaction-facade.@use-connection-stash=false in
  the Moqui Conf XML file
- Many code cleanups and more CompileStatic with XML handling using new
  MNode class instead of Groovy Node; UserFacadeImpl and
  TransactionFacadeImpl much cleaner with internal classes for state
- Added tools.@empty-db-load attribute with data file types to load on
  startup (through webapp ContextListener init only) if the database is
  empty (no records for moqui.basic.Enumeration)
- If the moqui.conf property (system property, command line, or in
  MoquiInit.properties) starts with a forward slash ('/') it is now
  considered an absolute path instead of relative to the runtime directory
  allowing a conf file outside the runtime directory (an alternative
  to using ../)
- UserAccount.userId and various other ID fields changed from id-long to id
  as userId is only an internal/sequenced ID now, and for various others
  the 40 char length changed years ago is more than adequate; existing
  columns can be updated for the shorter length, but don't have to be
- Changes to run tests without example component in place (now a component
  separate from moqui-runtime), using the moqui.test and other entities
- Added run-jackrabbit option to run Apache Jackrabbit locally when Moqui
  starts and stop is when Moqui stops, with conf/etc in runtime/jackrabbit
- Added SubscreensDefault entity and supporting code to override default
  subscreens by tenant and/or condition with database records
- Now using the VERSION_2_3_23 version for FreeMarker instead of a
  previous release compatibility version
- Added methods to L10nFacade that accept a Locale when something other
  than the current user's locale is needed
- Added TransactionFacade runUseOrBegin() and runRequireNew() methods to
  run code (in a Groovy Closure) in a transaction
- ArtifactHit/Bin persistence now done in a worker thread instead of async
  service; uses new eci.runInWorkerThread() method, may be added
  ExecutionContext interface in the future
- Added XML Form text-line.depends-on element so autocomplete fields can
  get data on the client from other form fields and clear on change
- Improved encode/decode handling for URL path segments and parameters
- Service parameters with allow-html=safe are now accepted even with
  filtered elements and attributes, non-error messages are generated and
  the clean HTML from AntiSamy is used
- Now using PegDown for Markdown processing instead of Markdown4J
- Multi Tenant
  - Entity find and CrUD operations for entities in the tenantcommon group
    are restricted to the DEFAULT instance, protects REST API and so on
    regardless of admin permissions a tenant admin might assign
  - Added tenants allowed on SubscreensItem entity and subscreens-item
    element, makes more sense to filter apps by tenant than in screen
  - Improvements to tenant provisioning services, new MySQL provisioning,
    and enable/disable tenant services along with enable check on switch
  - Added ALL_TENANTS option for scheduled services, set on system
    maintenance services in quartz_data.xml by default; runs the service
    for each known tenant (by moqui.tenant.Tenant records)
- Entity Facade
  - DB meta data (create tables, etc) and primary sequenced ID queries now
    use a separate thread to run in a different transaction instead of
    suspend/resume as some databases have issues with that, especially
    nested which happens when service and framework code suspends
- Service Facade
  - Added separateThread option to sync service call as an alternative to
    requireNewTransaction which does a suspend/resume, runs service in a
    separate thread and waits for the service to complete
  - Added service.@semaphore-parameter attribute which creates a distinct
    semaphore per value of that parameter
  - Services called with a ServiceResultWaiter now get messages passed
    through from the service job in the current MessageFacade (through
    the MessageFacadeException), better handling for other Throwable
  - Async service calls now run through lighter weight worker thread pool
    if persist not set (if persist set still through Quartz Scheduler)
- Dynamic (SPA) browser features
  - Added screen element when render screen to support macros at the screen
    level, such as code for components and services in Angular 2
  - Added support for render mode extension (like .html, .js, etc) to
    last screen name in screen path (or URL), uses the specified
    render-mode and doesn't try to render additional subscreens
  - Added automatic actions.json transition for all screens, runs actions
    and returns results as JSON for use in client-side template rendering
  - Added support for .json extension to transitions, will run the
    transition and if the response goes to another screen returns path to
    that screen in a list and parameters for it, along with
    messages/errors/etc for client side routing between screens

### Bug Fixes

- DB operations for sequenced IDs, service semaphores, and DB meta data are
  now run in a separate thread instead of tx suspend/resume as some
  databases have issues with suspend/resume, especially multiple
  outstanding suspended transactions
- Fixed issue with conditional default subscreen URL caching
- Internal login from login/api key and async/scheduled services now checks
  for disabled accounts, expired passwords, etc just like normal login
- Fixed issue with entity lists in TransactionCache, were not cloned so
  new/updated records changed lists that calling code might use
- Fixed issue with cached entity lists not getting cleared when a record is
  updated that wasn't in a list already in the cache but that matches its
  condition
- Fixed issue with cached view-entity lists not getting cleared on new or
  updated records; fixes issues with new authz, tarpits and much more not
  applied immediately
- Fixed issue with cached view-entity one results not getting cleared when
  a member entity is updated (was never implemented)
- Entities in the tenantcommon group no longer available for find and CrUD
  operations outside the DEFAULT instance (protect tenant data)
- Fixed issue with find one when using a Map as a condition that may
  contain non-PK fields and having an artifact authz filter applied, was
  getting non-PK fields and constraining query when it shouldn't
  (inconsistent with previous behavior)
- Fixed ElasticSearch automatic mappings where sub-object mappings always
  had just the first property
- Fixed issues with Entity DataFeed where cached DataDocument mappings per
  entity were not consistent and no feed was done for creates
- Fixed safe HTML service parameters (allow-html=safe), was issue loading
  antisamy-esapi.xml though ESAPI so now using AntiSamy directly
- Fixed issues with DbResource reference move and other operations
- Fixed issues with ResourceReference operations and wiki page updates


## Release 1.6.1 - 24 Jan 2016

Moqui Framework 1.6.1 is a minor new feature and bug fix release.

This is the first release after the repository reorganization in Moqui 
Ecosystem. The runtime directory is now in a separate repository. The 
framework build now gets JAR files from Bintray JCenter instead of having
them in the framework/lib directory. Overall the result is a small
foundation with additional libraries, components, etc added as needed using
Gradle tasks.

### Build Changes

- Gradle tasks to help handle runtime directory in a separate repository
  from Moqui Framework
- Added component management features as Gradle tasks
  - Components available configured in addons.xml
  - Repositories components come from configured in addons.xml
  - Get component from current or release archive (getCurrent, getRelease)
  - Get component from git repositories (getGit)
  - When getting a component, automatically gets all components it depends
    on (must be configured in addons.xml so it knows where to get them)
  - Do a git pull for moqui, runtime, and all components
- Most JAR files removed, framework build now uses Bintray JCenter
- JAR files are downloaded as needed on build
- For convenience in IDEs to copy JAR files to the framework/dependencies
  directory use: gradle framework:copyDependencies; note that this is not
  necessary in IntelliJ IDEA (will import dependencies when creating a new
  project based on the gradle files, use the refresh button in the Gradle
  tool window to update after updating moqui)
- If your component builds source or runs Spock tests changes will be
  needed, see the runtime/base-component/example/build.gradle file

### New Features

- The makeCondition(Map) methods now support _comp entry for comparison
  operator, _join entry for join operator, and _list entry for a list of
  conditions that will be combined with other fields/values in the Map
- In FieldValueCondition if the value is a collection and operator is
  EQUALS set to IN, or if NOT_EQUAL then NOT_IN

### Bug Fixes

- Fixed issue with EntityFindBase.condition() where condition break down
  set ignore case to true
- Fixed issue with from/thru date where conversion from String was ignored
- Fixed MySQL date-time type for milliseconds; improved example conf for XA
- If there are errors in screen actions the error message is displayed
  instead of rendering the widgets (usually just resulting in more errors)


## Long Term To Do List - aka Informal Road Map

- Multi-Tenant Alternative: Multi-Instance Management
  - Moqui instances could be managed manually, this would be for some automation from a Moqui 'master' instance that runs something
    like a PopCommerce store for automated SaaS
  - for Moqui 'master' instance controlling other instances, would not run in managed/slave instances
  - separate component from moqui-framework with entity, service, screen
  - service interfaces, implementations in separate components  
  - provision, suspend/resume (disable/enable), remove: need service interfaces, like current TenantServices 
  - monitoring and other management: best with separate tools or something built into a Moqui master instance?
  - instance meta data
    - instance ID
    - app instance name (like docker container name); could be same as instance ID
    - app instance host
      - instance location (like docker host location for REST API, ie through docker-java)
      - admin credentials (docker client approach? jclouds ssh approach? something generic including user, password, certificate)
      - see https://docs.docker.com/engine/reference/api/docker_remote_api_v1.24/
    - app instance image 
      - name or digest (like docker image name or ID, could be like 'moqui', 'moqui/moqui:2.0' or 'myaccount/myimage:tag')
      - image repository to pull from
    - image repository
      - username, password, email, serverAddress
      - see https://docs.docker.com/engine/reference/api/docker_remote_api/
    - app access
      - webapp_http_host (ie virtual host, could be deployed with actual IP/host); could be same as instance ID
      - webapp_http_port (default to 80), webapp_https_port (default to 443), webapp_https_enabled
    - db server: entity_ds_db_conf, entity_ds_host, entity_ds_port, admin user/password
    - database: db server, entity_ds_database, entity_ds_schema, entity_ds_user, entity_ds_password
- Instance Provisioning and Management
  - external instance management
    - https://mist.io
      - Docker, AWS, vmware, etc
      - http://blog.mist.io/post/96542374356/one-ui-to-rule-them-all-manage-your-docker
    - https://shipyard-project.com (Docker only)
    - https://github.com/kevana/ui-for-docker (Docker only)
  - embedded and gradle docker client (for docker host or docker swarm)
    - https://github.com/docker-java/docker-java
    - https://github.com/bmuschko/gradle-docker-plugin
    - direct through Docker API
      - https://docs.docker.com/engine/reference/commandline/dockerd/#bind-docker-to-another-host-port-or-a-unix-socket
      - https://docs.docker.com/engine/reference/api/docker_remote_api/
  - Apache Stratos
    - http://stratos.apache.org/
    - feature rich but complex, uses ActiveMQ, Puppet, WSO2 CEP, WSO2 DAS
    - perhaps just Puppet directly is adequate? Stratos seems to handle many things only in commercial Puppet Enterprise
  - Apache jclouds
    - http://jclouds.apache.org
    - API and implementations for various IaaS options, including Docker (limited), AWS, openstack, etc
    - mainly use the Compute API (ComputeService)
    - http://jclouds.apache.org/guides/docker/

- Option for link element to only render if referenced transition/screen exists
- Option for transition to only mount if all response URLs for screen paths exist
- Perhaps never throw exception for ScreenUrlInfo or UrlInstance if screen or transition does not exist, ie just add
  method to see if exists and show disabled link if it doesn't?

- Support incremental (add/subtract) updates in EntityValue.update() or a variation of it; deterministic DB style
- Support seek for faster pagination like jOOQ: https://blog.jooq.org/2013/10/26/faster-sql-paging-with-jooq-using-the-seek-method/

- Improved Distributed Datasource Support
  - Put all framework, mantle entities in the 4 new groups: transactional, nontransactional, configuration, analytical
  - Review warnings about view-entities that have members in multiple groups (which may be in different databases)
  - Test with transactional in H2 and nontransactional, configuration, analytical in OrientDB
  - Known changes needed
    - Check distributed foreign keys in create, update, delete (make sure records exist or don't exist in other databases)
    - Add augment-member to view-entity that can be in a separate database
      - Make it easier to define view-entity so that caller can treat it mostly as a normal join-based view
      - Augment query results with optionally cached values from records in a separate database
      - For conditions on fields from augment-member do a pre-query to get set of PKs, use them in an IN condition on
        the main query (only support simple AND scenario, error otherwise); sort of like a sub-select
      - How to handle order by fields on augment-member? Might require separate query and some sort of fancy sorting...
    - Some sort of EntityDynamicView handling without joins possible? Maybe augment member methods?
    - DataDocument support across multiple databases, doing something other than one big dynamic join...
  - Possibly useful
    - Consider meta-data management features such as versioning and more complete history for nontransactional and
      configuration, preferably using some sort of more efficient underlying features in the datasource
      (like Jackrabbit/Oak; any support for this in OrientDB? ElasticSearch keeps version number for concurrency, but no history)
    - Write EntityFacade interface for ElasticSearch to use like OrientDB?
    - Support persistence through EntityFacade as nested documents, ie specify that detail/etc entities be included in parent/master document
    - SimpleFind interface as an alternative to EntityFind for datasources that don't support joins, etc (like OrientDB)
      and maybe add support for the internal record ID that can be used for faster graph traversal, etc

- Try Caffeine JCache at https://github.com/ben-manes/caffeine
  - do in moqui-caffeine tool component
  - add multiple threads to SpeedTest.xml?

- Saved form-list Finds
  - Save settings for a user or group to share (i.e. associate with userId or userGroupId). Allow for any group a user is in.

- WebSocket Notifications
  - Increment message, event, task count labels in header?
    - DataDocument add flag if new or updated
    - if new increment count with JS
    - Side note: DataDocument add info about what was updated somehow?
- User Notification
  - Add Moqui Conf XML elements to configure NotificationMessageListener classes
  - Listener to send Email with XML Screen to layout (and try out using JSON documents as nested Maps from a screen)
    - where to configure the email and screen to use? use EmailTemplate/emailTemplateId, but where to specify?
      - for notifications from DataFeeds can add DataFeed.emailTemplateId (or not, what about toAddresses, etc?)
      - maybe have a more general way to configure details of topics, including emailTemplateId and screenLocation...

- Angular 2 Apps
  - support by screen + subscreens, or enable/disable for all apps (with separate webroot or something)?
  - update bootstrap (maybe get from CDN instead of including in source...)
  - for screen generate
    - component.ts file
    - service.ts/js file with:
      - service for each transition
      - service to get data for screen actions
      - services for section actions
      - services for form row actions
    - component.html file (use 'templateUrl' in Component annotation instead of 'template')
    - or use single JS file with component, embedded template (use template: and not templateUrl:), transition services?
  - with system.js need to transpile in the browser?
    - can transpile into JS file using typescript4j; or generate component.js
    - https://github.com/martypitt/typescript4j
- Steps toward Angular, more similar client/server artifacts
  - Add Mustache TemplateRenderer
    - https://github.com/spullara/mustache.java
  - Add form-list.actions and form-single.actions elements
  - Add REST endpoint configuration for CrUD operations to form-list and form-single
    - call local when rendered server-side
    - set base URL, use common patterns for get single/multiple record, create, update, and delete
    - use internal data from RestApi class to determine valid services available

- Hazelcast based improvements
  - configuration for 'microservice' deployments, partitioning services to run on particular servers in a cluster and
    not others (partition groups or other partition feature?)
  - can use for reliable WAN service calls like needed for EntitySync?
    - ie to a remote cluster
    - different from commercial only WAN replication feature
    - would be nice for reliable message queue
  - Quartz Scheduler
    - can use Hazelcast for scheduled service execution in a cluster, perhaps something on top of, underneath, or instead of Quartz Scheduler?
    - consider using Hazelcast as a Quartz JobStore, ie: https://github.com/FlavioF/quartz-scheduler-hazelcast-jobstore
  - DB (or ElasticSearch?) MapStore for persisted (backed up) Hazelcast maps
    - use MapStore and MapLoader interfaces
    - see http://docs.hazelcast.org/docs/latest/manual/html-single/index.html#loading-and-storing-persistent-data
    - https://github.com/mozilla-metrics/bagheera-elasticsearch
      - older, useful only as a reference for implementing something like this in Moqui
    - best to implement something using the EntityFacade for easier configuration, etc
    - see JDBC, etc samples: https://github.com/hazelcast/hazelcast-code-samples/tree/master/distributed-map/mapstore/src/main/java
  - Persisted Queue for Async Services, etc
    - use QueueStore interface
    - see http://docs.hazelcast.org/docs/latest/manual/html-single/index.html#queueing-with-persistent-datastore
    - use DB?

- XML Screens
  - Screen section-iterate pagination
  - Screen form automatic client JS validation for more service in-parameters
    for: number-range, text-length, text-letters, time-range, credit-card.@types
  - Dynamic Screens (database-driven: DynamicScreen* entities)
- Entity Facade
  - LiquiBase integration (entity.change-set element?)
  - Add view log, like current change audit log (AuditLogView?)
  - Improve entity cache auto-clear performance using ehcache search
    http://ehcache.org/generated/2.9.0/html/ehc-all/#page/Ehcache_Documentation_Set%2Fto-srch_searching_a_cache.html%23
- Artifact Execution Facade
  - Call ArtifactExecutionFacade.push() (to track, check authz, etc) for
    other types of artifacts (if/as determined to be helpful), including:
    Component, Webapp, Screen Section, Screen Form, Screen Form Field,
    Template, Script, Entity Field
  - For record-level authz automatically add constraints to queries if
    the query follows an adequate pattern and authz requires it, or fail
    authz if can't add constraint
- Tools Screens
  - Auto Screen
    - Editable data grid, created by form-list, for detail and assoc related entities
  - Entity
    - Entity model internal check (relationship, view-link.key-map, ?)
    - Database meta-data check/report against entity definitions; NOTE: use LiquiBase for this
  - Script Run (or groovy shell?)
  - Service
    - Configure and run chain of services (dynamic wizard)
  - Artifact Info screens (with in/out references for all)
    - Screen tree and graph browse screen
    - Entity usage/reference section
    - Service usage/reference section on ServiceDetail screen
  - Screen to install a component (upload and register, load data from it; require special permission for this, not enabled on the demo server)

- Data Document and Feed
  - API (or service?) push outstanding data changes (registration/connection, time trigger; tie to SystemMessage)
  - API (or service?) receive/persist data change messages - going reverse of generation for DataDocuments... should be interesting
  - Consumer System Registry
    - feed transport (for each: supports confirmation?)
      - WebSocket (use Notification system, based on notificationName (and userId?))
  - Service to send email from DataFeed (ie receive#DataFeed implementation), use XML Screen for email content
    - don't do this directly, do through NotificationMessage, ie the next item... or maybe not, too many parameters for
      email from too many places related to a DataDocument, may not be flexible enough and may be quite messy
  - Service (receive#DataFeed impl) to send documents as User NotificationMessages (one message per DataDocument); this
    is probably the best way to tie a feed to WebSocket notifications for data updates
    - Use the dataFeedId as the NotificationMessage topic
    - Use this in HiveMind to send notifications of project, task, and wiki changes (maybe?)

- Integration
  - OData V4 (http://www.odata.org) compliant entity auto REST API
    - like current but use OData URL structure, query parameters, etc
    - mount on /odata4 as alternative to existing /rest
    - generate EDMX for all entities (and exported services?)
    - use Apache Olingo (http://olingo.apache.org)
    - see: https://templth.wordpress.com/2015/04/27/implementing-an-odata-service-with-olingo/
    - also add an ElasticSearch interface? https://templth.wordpress.com/2015/04/03/handling-odata-queries-with-elasticsearch/
  - Generate minimal Data Document based on changes (per TX possible, runs async so not really; from existing doc, like current ES doc)
  - Update database from Data Document
  - Data Document UI
    - show/edit field, rel alias, condition, link
    - special form for add (edit?) field with 5 drop-downs for relationships, one for field, all updated based on
      master entity and previous selections
  - Data Document REST interface
    - get single by dataDocumentId and PK values for primary entity
    - search through ElasticSearch for those with associated feed/index
    - json-schema, RAML, Swagger API defs
    - generic service for sending Data Document to REST (or other?) end point
  - Service REST API
    - allow mapping DataDocument operations as well
    - Add attribute for resource/method like screen for anonymous and no authz access
  - OAuth2 Support
    - Use Apache Oltu, see https://cwiki.apache.org/confluence/display/OLTU/OAuth+2.0+Authorization+Server
    - Spec at http://tools.ietf.org/html/rfc6749
    - http://oltu.apache.org/apidocs/oauth2/reference/org/apache/oltu/oauth2/as/request/package-summary.html
    - http://search.maven.org/#search|ga|1|org.apache.oltu
    - https://stormpath.com/blog/build-api-restify-stormpath/
    - https://github.com/PROCERGS/login-cidadao/blob/master/app/Resources/doc/en/examplejava.md
    - https://github.com/swagger-api/swagger-ui/issues/807
    - Add authz and token transitions in rest.xml
    - Support in Service REST API (and entity/master?)
    - Add examples of auth and service calls using OAuth2
    - Add OAuth2 details in Swagger and RAML files
    - More?

- AS2 Client and Server
  - use OpenAS2 (http://openas2.sourceforge.net, https://github.com/OpenAS2/OpenAs2App)?
  - tie into SystemMessage for send/receive (with AS2 service for send, code to receive SystemMessage from AS2 server)

- Email verification by random code on registration and email change
- Login through Google, Facebook, etc
  - OpenID, SAML, OAuth, ...
  - https://developers.facebook.com/docs/facebook-login/login-flow-for-web/v2.0

- Workflow that manages activity flow with screens and services attached to
  activities, and tasks based on them taking users to defined or automatic
  screen; see BonitaSoft.com Open Source BPM for similar concept; generally
  workflow without requiring implementation of an entire app once the
  workflow itself is defined

