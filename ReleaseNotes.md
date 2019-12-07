
# Moqui Framework Release Notes

## Release 2.1.3 - 07 Dec 2019

Moqui Framework 2.1.3 is a patch level new feature and bug fix release.

There are only minor changes and fixes in this release. For a complete list of changes see:

https://github.com/moqui/moqui-framework/compare/v2.1.2...v2.1.3

This is the last release where the moqui-elasticsearch component for embedded ElasticSearch will be supported. It is
being replaced by the new ElasticFacade included in this release.

### New Features

- Java 11 now supported with some additional libraries (like javax.activation) included by default; some code changes
  to address deprecations in the Java 11 API but more needed to resolve all for better future compatibility
  (in other words expect deprecation warnings when building with Java 11) 
- Built-in ElasticSearch client in the new ElasticFacade that uses pooled HTTP connections with the Moqui RestClient
  for the ElasticSearch JSON REST API; this is most easily used with Groovy where you can use the inline Map and List
  syntax to build what becomes the JSON body for search and other requests; after this release it will replace the old
  moqui-elasticsearch component, now included in the framework because the large ES jar files are no longer required
- RestClient improvements to support an externally managed RequestFactory to maintain a HttpClient across requests
  for connection pooling, managing cookies, etc 
- Support for binary render modes for screen with new ScreenWidgetRender interface and screen-facade.screen-output
  element in the Moqui Conf XML file; this was initially implemented to support an xlsx render mode implemented in
  the new moqui-poi tool component
- Screen rendering to XLSX file with one sheet to form-list enabled with the form-list.@show-xlsx-button attribute,
  the XLS button will only show if the moqui-poi tool component is in place
- Support for binary rendered screen attachments to emails, and reusable emailScreenAsync transition and EmailScreenSection
  to easily add a form to screens to send the screen render as an attachment to an outgoing email, rendered in the background
- WikiServices to upload and delete attachments, and delete wiki pages; improvements to clone wiki page

## Release 2.1.2 - 23 July 2019

Moqui Framework 2.1.2 is a patch level new feature and bug fix release.

There are only minor changes and fixes in this release. For a complete list of changes see:

https://github.com/moqui/moqui-framework/compare/v2.1.1...v2.1.2

### New Features

- Service include for refactoring, etc with new services.service-include element
- RestClient now supports retry on timeout for call() and 429 (velocity) return for callFuture()
- The general worker thread pool now checks for an active ExecutionContext after each run to make sure destroyed
- CORS preflight OPTIONS request and CORS actual request handling in MoquiServlet
    - headers configured using cors-preflight and cors-actual types in webapp.response-header elements with default headers in MoquiDefaultConf.xml
    - allowed origins configured with the webapp.@allow-origins attribute which defaults the value of the 'webapp_allow_origins'
      property or env var for production configuration; default to empty which means only same origin is allowed
- Docker and instance management monitoring and configuration option improvements, Postgres support for database instances
- Entity field currency-amount now has 4 decimal digits in the DB and currency-precise has 5 decimal digits for more currency flexibility
- Added minRetryTime to ServiceJob to avoid immediate and excessive retries
- New Gradle tasks for managing git tags
- Support for read only clone datasource configuration and use (if available) in entity finds

### Bug Fixes

- Issue with DataFeed Runnable not destroying the ExecutionContext causing errors to bleed over
- Fix double content type header in RestClient in certain scenarios

## Release 2.1.1 - 29 Nov 2018

Moqui Framework 2.1.1 is a patch level new feature and bug fix release.

While this release has new features maybe significant enough to warrant a 2.2.0 version bump it is mostly refinements and 
improvements to existing functionality or to address design limitations and generally make things easier and cleaner. 

There are various bug fixes and security improvements in this release. There are no known backward compatibility issues since the 
last release but there are minor cases where default behavior has changed (see detailed notes). 

### New Features

- Various library updates (see framework/build.gradle for details)
- Updated to Gradle 4 along with changes to gradle files that require Gradle 4.0 or later
- In gradle addRuntime task create version.json files for framework/runtime and for each component, shown on System app dashboard
- New gradle gitCheckoutAll task to bulk checkout branches with option to create
- New default/example Procfile, include in moqui-plus-runtime.war

##### Web Facade and HTTP

- RestClient improvements for background requests with a Future, retry on 429 for velocity limited APIs, multipart requests, etc
- In user preferences support override by Java system property (or env var if default-property declared in Moqui Conf XML)
- Add WebFacade.getRequestBodyText() method, use to get body text more easily and now necessary as WebFacade reads the body for all 
  requests with a text content type instead of just application/json or text/json types as before
- Add email support for notifications with basic default template, enabled only per user for a specific NotificationTopic
- Add NotificationTopic for web (screen) critical errors
- Invalidate session before login (with attributes copy to new session) to mitigate session fixation attacks
- Add more secure defaults for Strict-Transport-Security, Content-Security-Policy, and X-Frame-Options

##### XML Screen and Form

- Support for Vue component based XML Screens using a .js file and a .vuet file that gets merged into the Vue component as the 
  template (template can be inline in the .js file); for an example see the DynamicExampleItems.xml screen in the example component
- XML Screen and WebFacade response headers now configurable with webapp.response-header element in Moqui Conf XML
- Add moqui-conf.screen-facade.screen and screen.subscreens-item elements that override screen.subscreens.subscreens-item elements 
  within a screen definition so that application root screens can be added under webroot and apps in a MoquiConf.xml file in a 
  component or in the active Moqui Conf XML file instead of using database records
- Add support for 'no sub-path' subscreens to extend or override screens, transitions, and resources under the parent screen by 
  looking first in each no sub-path subscreen for a given screen path and if not found then look under the parent screen; for 
  example this is used in the moqui-org component for the moqui.org web-site so that /index.html is found in the moqui-org 
  component and so that /Login resolves to the Login.xml screen in the moqui-org component instead of the default one under webroot
- Add screen path alias support configured with ScreenPathAlias entity records
- Now uses URLDecoder for all screen path segments to match use of URLEncoder as default for URL encoding in output
- In XML Screen transition both service-call and actions are now allowed, service-call runs first


- Changed Markdown rendering from Pegdown to flexmark-java to support CommonMark 0.28, some aspects of GitHub Flavored Markdown,
  and automatic table of contents
- Add form-single.@pass-through-parameters attribute to create hidden inputs for current request parameters
- Moved validate-* attributes from XML Form field element to sub-field elements so that in form-list different validation can be
  done for header, first-/second-/last-row, and default-/conditional-field; as part of this the automatic validate settings from
  transition.service-call are now set on the sub-field instead of the field element

##### Service Facade

- Add seca.@id and eeca.@id attributes to specify optional IDs that can be used to override or disable SECAs and EECAs
- SystemMessage improvements for security, HTTP receive endpoint, processing/etc timeouts, etc
- Service semaphore concurrency improvements, support for semaphore-name which defaults to prior behavior of service name

##### Entity Facade

- Add eeca.set-results attribute to set results of actions in the fields for rules run before entity operation
- Add entity.relationship.key-value element for constants on join conditions 
- Authorization based entity find filters are now applied after view entities are trimmed so constraints are only added for 
  entities actually used in the query
- EntityDataLoader now supports a create only mode (used in the improved Data Import screen in the Tools app, usable directly)
- Add mysql8 database conf for new MySQL 8 JDBC driver

### Bug Fixes

- Serious bug in MoquiAuthFilter where it did not destroy ExecutionContext leaving it in place for the next request using that 
  thread; also changed MoquiServlet to better protect against existing ExecutionContext for thread; also changed WebFacade init
  from HTTP request to remove current user if it doesn't match user authenticated in session with Shiro, or if no user is 
  authenticated in session
- MNode merge methods did not properly clear node by name internal cache when adding child nodes causing new children to show up
  in full child node list but not when getting first or all children by node name if they had been accessed by name before the merge
- Fix RestClient path and parameter encoding
- Fix RestClient basic authentication realm issue, now custom builds Authorization request header
- Fix issue in update#Password service with reset password when UserAccount has a resetPassword but no currentPassword
- Disable default geo IP lookup for Visit records because the freegeoip service has been discontinued
- Fix DataFeed trigger false positives for PK fields on related entities included in DataDocument definitions
- Fix transaction response type screen-last in vuet/vapps mode, history wasn't being maintained server side

## Release 2.1.0 - 22 Oct 2017

Moqui Framework 2.1.0 is a minor new feature and bug fix release.

Most of the effort in the Moqui Ecosystem since the last release has been on the business artifact and application levels. Most of
the framework changes have been for improved user interfaces but there have also been various lower level refinements and 
enhancements. 

This release has a few bug fixes from the 2.0.0 release and has new features like DbResource and WikiPage version management, 
a simple tool for ETL, DataDocument based dynamic view entities, and various XML Screen and Form widget options and usability 
improvements. This release was originally planned to be a patch level release primarily for bug fixes but very soon after the 2.0.0 
release work start on the Vue based client rendering (SPA) functionality and various other new features that due to business deals 
progressed quickly.

The default moqui-runtime now has support for hybrid static/dynamic XML Screen rendering based on Vue JS. There are various changes 
for better server side handling but most changes are in moqui-runtime. See the moqui-runtime release notes for more details. 
Some of these changes may be useful for other client rendering purposes, ie for other client side tools and frameworks.

### Non Backward Compatible Changes

- New compile dependency on Log4J2 and not just SLF4J
- DataDocument JSON generation no longer automatically adds all primary key fields of the primary entity to allow for aggregation
  by function in DataDocument based queries (where DataDocument is used to create a dynamic view entity); for ElasticSearch indexing
  a unique ID is required so all primary key fields of the primary entity should be defined
- The DataDocumentField, DataDocumentCondition, and DataDocumentLink entities now have an artificial/sequenced secondary key instead 
  of using another field (fieldPath, fieldNameAlias, label); existing tables may work with some things but reloading seed data will
  fail if you have any DataDocument records in place; these are typically seed data records so the easiest way to update/migrate
  is to drop the tables for DataDocumentField/Link/Condition entities and then reload seed data as normal for a code update
- If using moqui-elasticsearch the index approach has changed to one index per DataDocument to prep for ES6 and improve the
  performance and index types by field name; to update an existing instance it is best to start with an empty ES instance or at
  least delete old indexes and re-index based on data feeds
- The default Dockerfile now runs the web server on port 80 instead of 8080 within the container

### New Features

- Various library updates
- SLF4J MDC now used to track moqui_userId and moqui_visitorId for logging
- New ExecutionContextFactory.registerLogEventSubscriber() method to register for Log4J2 LogEvent processing, initially used in the
  moqui-elasticsearch component to send log messages to ElasticSearch for use in the new LogViewer screen in the System app
- Improved Docker Compose samples with HTTPS and PostgreSQL, new file for Kibana behind transparent proxy servlet in Moqui
- Added MoquiAuthFilter that can be used to require authorization and specified permission for arbitrary paths such as servlets;
  this is used along with the Jetty ProxyServlet$Transparent to provide secure access to things server only accessible tools like
  ElasticSearch (on /elastic) and Kibana (on /kibana) in the moqui-elasticsearch component
- Multi service calls now pass results from previous calls to subsequent calls if parameter names match, and return results
- Service jobs may now have a lastRunTime parameter passed by the job scheduler; lastRunTime on lock and passed to service is now
  the last run time without an error
- view-entity now supports member-entity with entity-condition and no key-map for more flexible join expressions
- TransactionCache now handles more situations like using EntityListIterator.next() calls and not just getCompleteList(), and 
  deletes through the tx cache are more cleanly handled for records created through the tx cache
- ResourceReference support for versions in supported implementations (initially DbResourceReference)
- ResourceFacade locations now support a version suffix following a hash
- Improved wiki services to track version along with underlying ResourceReference
- New SimpleEtl class plus support for extract and load through EntityFacade
- Various improvements in send#EmailTemplate, email view tracking with transparent pixel image
- Improvements for form-list aggregations and show-total now supports avg, count, min, max, first, and last in addition to sum
- Improved SQLException handling with more useful messages and error codes from database
- Added view-entity.member-relationship element as a simpler alternative to member-entity using existing relationships
- DataDocumentField now has a functionName attribute for functions on fields in a DataDocument based query 
- Any DataDocument can now be treated as an entity using the name pattern DataDocument.${dataDocumentId}
- Sub-select (sub-query) is now supported for view-entity by a simple flag on member-entity (or member-relationship); this changes
  the query structure so the member entity is joined in a select clause with any conditions for fields on that member entity put
  in its where clause instead of the where clause for the top-level select; any fields selected are selected in the sub-select as
  are any fields used for the join ON conditions; the first example of this is the InvoicePaymentApplicationSummary view-entity in
  mantle-usl which also uses alias.@function and alias.complex-alias to use concat_ws for combined name aliases
- Sub-select also now supported for view-entity members of other view entities; this provides much more flexibility for functions
  and complex-aliases in the sub-select queries; there are also examples of this in mantle-usl
- Now uses Jackson Databind for JSON serialization and deserialization; date/time values are in millis since epoch

### Bug Fixes

- Improved exception (throwable) handling for service jobs, now handled like other errors and don't break the scheduler
- Fixed field.@hide attribute not working with runtime conditions, now evaluated each time a form-list is rendered
- Fixed long standing issue with distinct counts and limited selected fields, now uses a distinct sub-select under a count select
- Fixed long standing issue where view-entity aliased fields were not decrypted
- Fixed issue with XML entity data loading using sub-elements for related entities and under those sub-elements for field data
- Fixed regression in EntityFind where cache was used even if forUpdate was set
- Fixed concurrency issue with screen history (symptom was NPE on iterator.next() call)

## Release 2.0.0 - 24 Nov 2016

Moqui Framework 2.0.0 is a major new feature and bug fix release, with various non backward compatible API and other changes.

This is the first release since 1.0.0 with significant and non backwards compatible changes to the framework API. Various deprecated
methods have been removed. The Cache Facade now uses the standard javax.cache interfaces and the Service Facade now uses standard 
java.util.concurrent interfaces for async and scheduled services. Ehcache and Quartz Scheduler have been replaced by direct, 
efficient interfaces implementations.

This release includes significant improvements in configuration and with the new ToolFactory functionality is more modular with
more internals exposed through interfaces and extendable through components. Larger and less universally used tool are now in 
separate components including Apache Camel, Apache FOP, ElasticSearch, JBoss KIE and Drools, and OrientDB.

Multi-server instances are far better supported by using Hazelcast for distributed entity cache invalidation, notifications,
caching, background service execution, and for web session replication. The moqui-hazelcast component is pre-configured to enable
all of this functionality in its MoquiConf.xml file. To use add the component and add a hazelcast.xml file to the classpath with
settings for your cluster (auto-discover details, etc).

Moqui now scales up better with performance improvements, concurrency fixes, and Hazelcast support (through interfaces other 
distributed system libraries like Apache Ignite could also be used). Moqui also now scales down better with improved memory 
efficiency and through more modular tools much smaller runtime footprints are possible.

The multi-tenant functionality has been removed and replaced with the multi-instance approach. There is now a Dockerfile included
with the recommended approach to run Moqui in Docker containers and Docker Compose files for various scenarios including an
automatic reverse proxy using nginx-proxy. There are now service interfaces and screens in the System application for managing
multiple Moqui instances from a master instance. Instances with their own database can be automatically provisioned using 
configurable services, with initial support for Docker containers and MySQL databases. Provisioning services will be added over time
to support other instance hosts and databases, and you can write your own for whatever infrastructure you prefer to use.

To support WebSocket a more recent Servlet API the embedded servlet container is now Jetty 9 instead of Winstone. When running 
behind a proxy such as nginx or httpd running in the embedded mode (executable WAR file) is now adequate for production use.

If you are upgrading from an earlier version of Moqui Framework please read all notes about Non Backward Compatible Changes. Code,
configuration, and database meta data changes may be necessary depending on which features of the framework you are using.

In this version Moqui Framework starts and runs faster, uses less memory, is more flexible, configuration is easier, and there are
new and better ways to deploy and manage multiple instances. A decent machine ($1800 USD Linux workstation, i7-6800K 6 core CPU) 
generated around 350 screens per second with an average response time under 200ms. This was running Moqui and MySQL on the same 
machine with a JMeter script running on a separate machine doing a 23 step order to ship/bill process that included 2 reports 
(one MySQL based, one ElasticSearch based) and all the GL posting, etc. The load simulated entering and shipping (by internal users) 
around 1000 orders/minute which would support thousands of concurrent internal or ecommerce users. On larger server hardware and 
with some lower level tuning (this was on stock/default Linux, Java 8, and MySQL 5.7 settings) a single machine could handle 
significantly more traffic.  

With the latest framework code and the new Hazelcast plugin Moqui supports high performance clusters to handle massive loads. The 
most significant limit is now database performance as we need a transactional SQL database for this sort of business process 
(with locking on inventory reservations and issuances, GL posting, etc as currently implemented in Mantle USL).

Enjoy!

### Non Backward Compatible Changes

- Java JDK 8 now required (Java 7 no longer supported)
- Now requires Servlet Container supporting the Servlet 3.1 specification
- No longer using Winstone embedded web server, now using Jetty 9
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
- XML Screen and Form
  - field.@entry-name attribute replaced by field.@from attribute (more meaningful, matches attribute used on set element); the old
    entry-name attribute is still supported, but removed from XSD
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
  - AntiSamy replaced by Jsoup.clean()
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
- Many library updates, cleanup of classes found in multiple jar files (ElasticSearch JarHell checks pass; nice in general)
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
- Multi-Instance Management
  - New services (InstanceServices.xml) and screens in the System app for Moqui instance management
  - This replaces the removed multi-tenant functionality
  - Initially supports Docker for the instance hosting environment via Docker REST API
  - Initially supports MySQL for instance databases (one DB per instance, just like in the past)
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
- Added SubEtha SMTP server which receives email messages and calls EMECA rules, an alternative to polling IMAP and POP3 servers
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
  - screen path URLs that don't exist are now by default disabled instead of throwing an exception
  - form-list now supports @header-dialog to put header-field widgets in a dialog instead of in the header
  - form-list now supports @select-columns to allow users to select which fields are displayed in which columns, or not displayed
  - added search-form-inputs.default-parameters element whose attributes are used as defaultParameters in searchFormMap()
  - ArtifactAuthzFailure records are only created when a user tries to use an artifact, not when simply checking to see if use is 
    permitted (such as in menus, links, etc)
  - significant macro cleanups and improvements
  - csv render macros now improved to support more screen elements, more intelligently handle links (only include anchor/text), etc
  - text render macros now use fixed width output (number of characters) along with new field attributes to specify print settings
  - added field.@aggregate attribute for use in form-list with options to aggregate field values across multiple results or
    display fields in a sub-list under a row with the common fields for the group of rows
  - added form-single.@owner-form attribute to skip HTML form element and add the HTML form attribute to fields so they are owned
    by a different form elsewhere in the web page
- The /status path now a transition instead of a screen and returns JSON with more server status information
- XML Actions now statically import all the old StupidUtilities methods so 'StupidUtilities.' is no longer needed, shouldn't be used
- StupidUtilities and StupidJavaUtilities reorganized into the new ObjectUtilities, CollectionUtilities, and StringUtilities
  classes in the moqui.util package (in the moqui-util project)

### Bug Fixes

- Fixed issues with clean shutdown running with the embedded Servlet container and with gradle test
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

- Support local printers, scales, etc in web-based apps using https://qz.io/

- PDF, Office, etc document indexing for wiki attachments (using Apache Tika)
- Wiki page version history with full content history diff, etc; store just differences, lib for that?
  - https://code.google.com/archive/p/java-diff-utils/
    - compile group: 'com.googlecode.java-diff-utils', name: 'diffutils', version: '1.3.0'
  - https://bitbucket.org/cowwoc/google-diff-match-patch/
    - compile group: 'org.bitbucket.cowwoc', name: 'diff-match-patch', version: '1.1'

- Option for transition to only mount if all response URLs for screen paths exist

- Saved form-list Finds
  - Save settings for a user or group to share (i.e. associate with userId or userGroupId). Allow for any group a user is in.
  - allow different aggregate/show-total/etc options in select-columns, more complex but makes sense?
  - add form-list presets in xml file, like saved finds but perhaps more options? allow different aggregate settings in presets?

- form-list data prep, more self-contained
  - X form-list.entity-find element support instead of form-list.@list attribute
  - _ form-list.service-call
  - _ also more general form-list.actions element?
- form-single.entity-find-one element support, maybe form-single.actions too

- Instance Provisioning and Management
  - embedded and gradle docker client (for docker host or docker swarm)
    - direct through Docker API
      - https://docs.docker.com/engine/reference/commandline/dockerd/#bind-docker-to-another-host-port-or-a-unix-socket
      - https://docs.docker.com/engine/security/https/
      - https://docs.docker.com/engine/reference/api/docker_remote_api/

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
    - Simple OAuth2 for authentication only
      - https://tools.ietf.org/html/draft-ietf-oauth-v2-27#section-4.4
      - use current api key functionality, or expand for limiting tokens to a particular client by registered client ID
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

