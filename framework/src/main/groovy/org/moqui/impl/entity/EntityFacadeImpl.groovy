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
package org.moqui.impl.entity

import groovy.transform.CompileStatic

import org.moqui.context.Cache
import org.moqui.context.ResourceReference
import org.moqui.context.TransactionException
import org.moqui.context.TransactionFacade
import org.moqui.impl.StupidUtilities
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.w3c.dom.Element

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import javax.sql.DataSource
import javax.sql.XADataSource

import org.moqui.entity.*
import org.moqui.BaseException

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

class EntityFacadeImpl implements EntityFacade {
    protected final static Logger logger = LoggerFactory.getLogger(EntityFacadeImpl.class)

    protected final ExecutionContextFactoryImpl ecfi
    protected final String tenantId

    protected final EntityConditionFactoryImpl entityConditionFactory

    protected final Map<String, EntityDatasourceFactory> datasourceFactoryByGroupMap = new HashMap()

    /** Cache with entity name as the key and an EntityDefinition as the value; clear this cache to reload entity def */
    final Cache entityDefinitionCache
    /** Cache with entity name as the key and List of file location Strings as the value, Map<String, List<String>> */
    final Cache entityLocationCache
    /** Map for framework entity definitions, avoid cache overhead and timeout issues */
    final Map<String, EntityDefinition> frameworkEntityDefinitions = new HashMap()

    /** Sequence name (often entity name) plus tenantId is the key and the value is an array of 2 Longs the first is the next
     * available value and the second is the highest value reserved/cached in the bank. */
    final Cache entitySequenceBankCache
    protected final ConcurrentMap<String, Lock> dbSequenceLocks = new ConcurrentHashMap<String, Lock>()
    protected final Lock locationLoadLock = new ReentrantLock()

    protected final Map<String, List<EntityEcaRule>> eecaRulesByEntityName = new HashMap()
    protected final Map<String, String> entityGroupNameMap = new HashMap()
    protected final Map<String, Node> databaseNodeByGroupName = new HashMap()
    protected final Map<String, Node> datasourceNodeByGroupName = new HashMap()
    protected final String defaultGroupName
    protected final TimeZone databaseTimeZone
    protected final Locale databaseLocale
    protected final Calendar databaseTzLcCalendar
    protected String sequencedIdPrefix = ""

    protected EntityDbMeta dbMeta = null
    protected final EntityCache entityCache
    protected final EntityDataFeed entityDataFeed
    protected final EntityDataDocument entityDataDocument

    EntityFacadeImpl(ExecutionContextFactoryImpl ecfi, String tenantId) {
        this.ecfi = ecfi
        this.tenantId = tenantId ?: "DEFAULT"
        this.entityConditionFactory = new EntityConditionFactoryImpl(this)
        this.defaultGroupName = this.ecfi.getConfXmlRoot()."entity-facade"[0]."@default-group-name"
        this.sequencedIdPrefix = this.ecfi.getConfXmlRoot()."entity-facade"[0]."@sequenced-id-prefix" ?: ""

        TimeZone theTimeZone = null
        if (this.ecfi.getConfXmlRoot()."entity-facade"[0]."@database-time-zone") {
            try {
                theTimeZone = TimeZone.getTimeZone(this.ecfi.getConfXmlRoot()."entity-facade"[0]."@database-time-zone")
            } catch (Exception e) { /* do nothing */ }
        }
        this.databaseTimeZone = theTimeZone ?: TimeZone.getDefault()
        Locale theLocale = null
        if (this.ecfi.getConfXmlRoot()."entity-facade"[0]."@database-locale") {
            try {
                String localeStr = this.ecfi.getConfXmlRoot()."entity-facade"[0]."@database-locale"
                if (localeStr) theLocale = localeStr.contains("_") ?
                        new Locale(localeStr.substring(0, localeStr.indexOf("_")), localeStr.substring(localeStr.indexOf("_")+1).toUpperCase()) :
                        new Locale(localeStr)
            } catch (Exception e) { /* do nothing */ }
        }
        this.databaseLocale = theLocale ?: Locale.getDefault()
        this.databaseTzLcCalendar = Calendar.getInstance(getDatabaseTimeZone(), getDatabaseLocale())

        // init entity meta-data
        entityDefinitionCache = ecfi.getCacheFacade().getCache("entity.definition")
        entityLocationCache = ecfi.getCacheFacade().getCache("entity.location")
        // NOTE: don't try to load entity locations before constructor is complete; this.loadAllEntityLocations()
        entitySequenceBankCache = ecfi.getCacheFacade().getCache("entity.sequence.bank.${this.tenantId}")

        // init connection pool (DataSource) for each group
        initAllDatasources()

        // EECA rule tables
        loadEecaRulesAll()

        entityCache = new EntityCache(this)
        entityDataFeed = new EntityDataFeed(this)
        entityDataDocument = new EntityDataDocument(this)
    }

    @CompileStatic
    ExecutionContextFactoryImpl getEcfi() { return ecfi }
    @CompileStatic
    EntityCache getEntityCache() { return entityCache }
    @CompileStatic
    EntityDataFeed getEntityDataFeed() { return entityDataFeed }
    @CompileStatic
    EntityDataDocument getEntityDataDocument() { return entityDataDocument }
    @CompileStatic
    String getDefaultGroupName() { return defaultGroupName }

    @CompileStatic
    TimeZone getDatabaseTimeZone() { return databaseTimeZone }
    @CompileStatic
    Locale getDatabaseLocale() { return databaseLocale }
    @CompileStatic
    Calendar getCalendarForTzLc() {
        // the OLD approach using user's TimeZone/Locale, bad idea because user may change for same record, getting different value, etc
        // return efi.getEcfi().getExecutionContext().getUser().getCalendarForTzLcOnly()

        return Calendar.getInstance(getDatabaseTimeZone(), getDatabaseLocale())
        // NOTE: this approach is faster but seems to cause errors with Derby (ERROR 22007: The string representation of a date/time value is out of range)
        // return databaseTzLcCalendar
    }

    void checkInitDatasourceTables() {
        // if startup-add-missing=true check tables now
        logger.info("Checking tables for all entities")
        long currentTime = System.currentTimeMillis()

        Map<String, Boolean> startupAddMissingByGroup = [:]
        Node entityFacadeNode = ecfi.getConfXmlRoot()."entity-facade"[0]
        for (Node datasourceNode in entityFacadeNode."datasource") {
            String groupName = datasourceNode."@group-name"
            if (datasourceNode."@startup-add-missing" == "true") {
                startupAddMissingByGroup.put(groupName, true)
                // checkAllEntityTables(groupName)
            } else {
                startupAddMissingByGroup.put(groupName, false)
            }
        }

        loadAllEntityLocations()
        for (String entityName in getAllEntityNames()) {
            String groupName = getEntityGroupName(entityName)
            boolean checkAndAdd = false
            if (startupAddMissingByGroup.get(groupName) != null) {
                checkAndAdd = startupAddMissingByGroup.get(groupName)
            } else {
                checkAndAdd = startupAddMissingByGroup.get(defaultGroupName)
            }
            if (checkAndAdd) {
                EntityDatasourceFactory edf = getDatasourceFactory(groupName)
                edf.checkAndAddTable(entityName)
            }
        }

        logger.info("Checked tables for all entities in ${(System.currentTimeMillis() - currentTime)/1000} seconds")
    }

    protected void initAllDatasources() {
        for (Node datasourceNode in ecfi.confXmlRoot."entity-facade"[0]."datasource") {
            String groupName = datasourceNode."@group-name"
            String objectFactoryClass = datasourceNode."@object-factory" ?: "org.moqui.impl.entity.EntityDatasourceFactoryImpl"
            EntityDatasourceFactory edf = (EntityDatasourceFactory) Thread.currentThread().getContextClassLoader().loadClass(objectFactoryClass).newInstance()
            datasourceFactoryByGroupMap.put(groupName, edf.init(this, datasourceNode, this.tenantId))
        }
    }

    static class DatasourceInfo {
        EntityFacadeImpl efi
        Node datasourceNode

        String uniqueName

        String jndiName
        Node serverJndi

        String jdbcDriver = null, jdbcUri = null, jdbcUsername = null, jdbcPassword = null

        String xaDsClass = null
        Properties xaProps = null

        Node inlineJdbc = null
        Node database = null

        DatasourceInfo(EntityFacadeImpl efi, Node datasourceNode) {
            this.efi = efi
            this.datasourceNode = datasourceNode

            String tenantId = efi.tenantId
            uniqueName = tenantId + '_' + datasourceNode."@group-name" + '_DS'

            EntityValue tenant = null
            EntityFacadeImpl defaultEfi = null
            if (tenantId != "DEFAULT" && datasourceNode."@group-name" != "tenantcommon") {
                defaultEfi = efi.ecfi.getEntityFacade("DEFAULT")
                tenant = defaultEfi.find("moqui.tenant.Tenant").condition("tenantId", tenantId).disableAuthz().one()
            }

            EntityValue tenantDataSource = null
            EntityList tenantDataSourceXaPropList = null
            if (tenant != null) {
                tenantDataSource = defaultEfi.find("moqui.tenant.TenantDataSource").condition("tenantId", tenantId)
                        .condition("entityGroupName", datasourceNode."@group-name").disableAuthz().one()
                if (tenantDataSource == null) {
                    // if there is no TenantDataSource for this group, look for one for the default-group-name
                    tenantDataSource = defaultEfi.find("moqui.tenant.TenantDataSource").condition("tenantId", tenantId)
                            .condition("entityGroupName", efi.getDefaultGroupName()).disableAuthz().one()
                }
                tenantDataSourceXaPropList = tenantDataSource != null ? defaultEfi.find("moqui.tenant.TenantDataSourceXaProp")
                        .condition("tenantId", tenantId) .condition("entityGroupName", tenantDataSource.entityGroupName)
                        .disableAuthz().list() : null
            }

            inlineJdbc = (Node) datasourceNode."inline-jdbc"[0]
            Node xaProperties = (Node) inlineJdbc."xa-properties"[0]
            database = efi.getDatabaseNode((String) datasourceNode."@group-name")

            if (datasourceNode."jndi-jdbc") {
                serverJndi = (Node) efi.ecfi.getConfXmlRoot()."entity-facade"[0]."server-jndi"[0]
                jndiName = tenantDataSource ? tenantDataSource.jndiName : datasourceNode."jndi-jdbc"[0]."@jndi-name"
            } else if (xaProperties || tenantDataSourceXaPropList) {
                xaDsClass = inlineJdbc."@xa-ds-class" ? inlineJdbc."@xa-ds-class" : database."@default-xa-ds-class"

                xaProps = new Properties()
                if (tenantDataSourceXaPropList) {
                    for (EntityValue tenantDataSourceXaProp in tenantDataSourceXaPropList) {
                        String propValue = tenantDataSourceXaProp.propValue
                        if (!propValue) {
                            logger.warn("TenantDataSourceXaProp value empty in ${tenantDataSourceXaProp}")
                            continue
                        }
                        // NOTE: consider changing this to expand for all system properties using groovy or something
                        if (propValue.contains("\${moqui.runtime}")) propValue = propValue.replace("\${moqui.runtime}", System.getProperty("moqui.runtime"))
                        xaProps.setProperty((String) tenantDataSourceXaProp.propName, propValue)
                    }
                }
                // always set default properties for the given data
                if (!tenantDataSourceXaPropList || tenantDataSource?.defaultToConfProps == "Y") {
                    for (Map.Entry<String, String> entry in xaProperties.attributes().entrySet()) {
                        // don't over write existing properties, from tenantDataSourceXaPropList or redundant attributes (shouldn't be allowed)
                        if (xaProps.containsKey(entry.getKey())) continue
                        // the Derby "databaseName" property has a ${moqui.runtime} which is a System property, others may have it too
                        String propValue = entry.getValue()
                        // NOTE: consider changing this to expand for all system properties using groovy or something
                        if (propValue.contains("\${moqui.runtime}")) propValue = propValue.replace("\${moqui.runtime}", System.getProperty("moqui.runtime"))
                        xaProps.setProperty(entry.getKey(), propValue)
                    }
                }
            } else {
                jdbcDriver = inlineJdbc."@jdbc-driver" ? inlineJdbc."@jdbc-driver" : database."@default-jdbc-driver"
                jdbcUri = tenantDataSource ? (String) tenantDataSource.jdbcUri : inlineJdbc."@jdbc-uri"
                if (jdbcUri.contains("\${moqui.runtime}")) jdbcUri = jdbcUri.replace("\${moqui.runtime}", System.getProperty("moqui.runtime"))
                jdbcUsername = tenantDataSource ? (String) tenantDataSource.jdbcUsername : inlineJdbc."@jdbc-username"
                jdbcPassword = tenantDataSource ? (String) tenantDataSource.jdbcPassword : inlineJdbc."@jdbc-password"
            }
        }
    }

    void loadFrameworkEntities() {
        // load framework entity definitions (moqui.*)
        long startTime = System.nanoTime()
        Set<String> entityNames = getAllEntityNames()
        int entityCount = 0
        for (String entityName in entityNames) {
            if (entityName.startsWith("moqui.")) {
                entityCount++
                try {
                    EntityDefinition ed = getEntityDefinition(entityName)
                    ed.getRelationshipInfoMap()
                    entityDbMeta.tableExists(ed)
                } catch (Throwable t) { logger.warn("Error loading framework entity definitions: ${t.toString()}") }
            }
        }
        logger.info("Loaded ${entityCount} framework entity definitions in ${(System.nanoTime() - startTime)/1E9} seconds")
    }

    void warmCache()  {
        logger.info("Warming cache for all entity definitions")
        long startTime = System.nanoTime()
        Set<String> entityNames = getAllEntityNames()
        for (String entityName in entityNames) {
            try {
                EntityDefinition ed = getEntityDefinition(entityName)
                ed.getRelationshipInfoMap()
                entityDbMeta.tableExists(ed)
            } catch (Throwable t) { logger.warn("Error warming entity cache: ${t.toString()}") }
        }

        // init a few framework entity caches
        entityCache.getCacheCount("moqui.basic.EnumerationType")

        entityCache.getCacheList("moqui.entity.UserField")
        entityCache.getCacheList("moqui.entity.document.DataDocument")
        entityCache.getCacheList("moqui.entity.document.DataDocumentCondition")
        entityCache.getCacheList("moqui.entity.document.DataDocumentField")
        entityCache.getCacheList("moqui.entity.feed.DataFeedAndDocument")
        entityCache.getCacheList("moqui.entity.view.DbViewEntity")
        entityCache.getCacheList("moqui.entity.view.DbViewEntityAlias")
        entityCache.getCacheList("moqui.entity.view.DbViewEntityKeyMap")
        entityCache.getCacheList("moqui.entity.view.DbViewEntityMember")

        entityCache.getCacheList("moqui.screen.ScreenThemeResource")
        entityCache.getCacheList("moqui.screen.SubscreensItem")
        entityCache.getCacheList("moqui.screen.form.DbFormField")
        entityCache.getCacheList("moqui.screen.form.DbFormFieldAttribute")
        entityCache.getCacheList("moqui.screen.form.DbFormFieldEntOpts")
        entityCache.getCacheList("moqui.screen.form.DbFormFieldEntOptsCond")
        entityCache.getCacheList("moqui.screen.form.DbFormFieldEntOptsOrder")
        entityCache.getCacheList("moqui.screen.form.DbFormFieldOption")
        entityCache.getCacheList("moqui.screen.form.DbFormLookup")

        entityCache.getCacheList("moqui.security.ArtifactAuthzCheckView")
        entityCache.getCacheList("moqui.security.ArtifactTarpitCheckView")
        entityCache.getCacheList("moqui.security.ArtifactTarpitLock")
        entityCache.getCacheList("moqui.security.UserGroupMember")
        entityCache.getCacheList("moqui.security.UserGroupPreference")

        entityCache.getCacheOne("moqui.basic.Enumeration")
        entityCache.getCacheOne("moqui.basic.LocalizedMessage")
        entityCache.getCacheOne("moqui.entity.document.DataDocument")
        entityCache.getCacheOne("moqui.entity.view.DbViewEntity")
        entityCache.getCacheOne("moqui.screen.form.DbForm")
        entityCache.getCacheOne("moqui.security.UserAccount")
        entityCache.getCacheOne("moqui.security.UserPreference")
        entityCache.getCacheOne("moqui.security.UserScreenTheme")
        entityCache.getCacheOne("moqui.server.Visit")
        entityCache.getCacheOne("moqui.tenant.Tenant")
        entityCache.getCacheOne("moqui.tenant.TenantHostDefault")

        logger.info("Warmed entity definition cache for ${entityNames.size()} entities in ${(System.nanoTime() - startTime)/1E9} seconds")
    }

    Set<String> getDatasourceGroupNames() {
        Set<String> groupNames = new TreeSet<String>()
        for (Node datasourceNode in this.ecfi.getConfXmlRoot()."entity-facade"[0]."datasource")
            groupNames.add((String) datasourceNode."@group-name")
        return groupNames
    }

    @CompileStatic
    static int getTxIsolationFromString(String isolationLevel) {
        if (!isolationLevel) return -1
        if ("Serializable".equals(isolationLevel)) {
            return Connection.TRANSACTION_SERIALIZABLE
        } else if ("RepeatableRead".equals(isolationLevel)) {
            return Connection.TRANSACTION_REPEATABLE_READ
        } else if ("ReadUncommitted".equals(isolationLevel)) {
            return Connection.TRANSACTION_READ_UNCOMMITTED
        } else if ("ReadCommitted".equals(isolationLevel)) {
            return Connection.TRANSACTION_READ_COMMITTED
        } else if ("None".equals(isolationLevel)) {
            return Connection.TRANSACTION_NONE
        } else {
            return -1
        }
    }

    List<ResourceReference> getAllEntityFileLocations() {
        List<ResourceReference> entityRrList = new LinkedList()
        entityRrList.addAll(getConfEntityFileLocations())
        entityRrList.addAll(getComponentEntityFileLocations(null))
        return entityRrList
    }
    List<ResourceReference> getConfEntityFileLocations() {
        List<ResourceReference> entityRrList = new LinkedList()

        // loop through all of the entity-facade.load-entity nodes, check each for "<entities>" root element
        for (Node loadEntity in this.ecfi.getConfXmlRoot()."entity-facade"[0]."load-entity") {
            entityRrList.add(this.ecfi.resourceFacade.getLocationReference((String) loadEntity."@location"))
        }

        return entityRrList
    }
    List<ResourceReference> getComponentEntityFileLocations(List<String> componentNameList) {
        List<ResourceReference> entityRrList = new LinkedList()

        List<String> componentBaseLocations
        if (componentNameList) {
            componentBaseLocations = []
            for (String cn in componentNameList)
                componentBaseLocations.add(ecfi.getComponentBaseLocations().get(cn))
        } else {
            componentBaseLocations = new ArrayList(ecfi.getComponentBaseLocations().values())
        }

        // loop through components look for XML files in the entity directory, check each for "<entities>" root element
        for (String location in componentBaseLocations) {
            ResourceReference entityDirRr = ecfi.resourceFacade.getLocationReference(location + "/entity")
            if (entityDirRr.supportsAll()) {
                // if directory doesn't exist skip it, component doesn't have an entity directory
                if (!entityDirRr.exists || !entityDirRr.isDirectory()) continue
                // get all files in the directory
                TreeMap<String, ResourceReference> entityDirEntries = new TreeMap<String, ResourceReference>()
                for (ResourceReference entityRr in entityDirRr.directoryEntries) {
                    if (!entityRr.isFile() || !entityRr.location.endsWith(".xml")) continue
                    entityDirEntries.put(entityRr.getFileName(), entityRr)
                }
                for (Map.Entry<String, ResourceReference> entityDirEntry in entityDirEntries) {
                    entityRrList.add(entityDirEntry.getValue())
                }
            } else {
                // just warn here, no exception because any non-file component location would blow everything up
                logger.warn("Cannot load entity directory in component location [${location}] because protocol [${entityDirRr.uri.scheme}] is not supported.")
            }
        }

        return entityRrList
    }

    @CompileStatic
    void loadAllEntityLocations() {
        // lock or wait for lock, this lock used here and for checking entity defined
        locationLoadLock.lock()

        try {
            // load all entity files based on ResourceReference
            long startTime = System.currentTimeMillis()
            List<ResourceReference> allEntityFileLocations = getAllEntityFileLocations()
            for (ResourceReference entityRr in allEntityFileLocations) this.loadEntityFileLocations(entityRr)
            if (logger.isInfoEnabled()) logger.info("Found entities in ${allEntityFileLocations.size()} files in ${System.currentTimeMillis() - startTime}ms")

            // look for view-entity definitions in the database (moqui.entity.view.DbViewEntity)
            if (entityLocationCache.get("moqui.entity.view.DbViewEntity")) {
                int numDbViewEntities = 0
                for (EntityValue dbViewEntity in makeFind("moqui.entity.view.DbViewEntity").list()) {
                    if (dbViewEntity.packageName) {
                        List pkgList = (List) this.entityLocationCache.get((String) dbViewEntity.packageName + "." + dbViewEntity.dbViewEntityName)
                        if (!pkgList) {
                            pkgList = new LinkedList()
                            this.entityLocationCache.put((String) dbViewEntity.packageName + "." + dbViewEntity.dbViewEntityName, pkgList)
                        }
                        if (!pkgList.contains("_DB_VIEW_ENTITY_")) pkgList.add("_DB_VIEW_ENTITY_")
                    }

                    List nameList = (List) this.entityLocationCache.get((String) dbViewEntity.dbViewEntityName)
                    if (!nameList) {
                        nameList = new LinkedList()
                        // put in cache under both plain entityName and fullEntityName
                        this.entityLocationCache.put((String) dbViewEntity.dbViewEntityName, nameList)
                    }
                    if (!nameList.contains("_DB_VIEW_ENTITY_")) nameList.add("_DB_VIEW_ENTITY_")

                    numDbViewEntities++
                }
                if (logger.infoEnabled) logger.info("Found [${numDbViewEntities}] view-entity definitions in database (moqui.entity.view.DbViewEntity)")
            } else {
                logger.warn("Could not find view-entity definitions in database (moqui.entity.view.DbViewEntity), no location found for the moqui.entity.view.DbViewEntity entity.")
            }
        } finally {
            locationLoadLock.unlock()
        }

        /* a little code to show all entities and their locations
        Set<String> enSet = new TreeSet(entityLocationCache.keySet())
        for (String en in enSet) {
            List lst = entityLocationCache.get(en)
            entityLocationCache.put(en, Collections.unmodifiableList(lst))
            logger.warn("TOREMOVE entity ${en}: ${lst}")
        }
        */
    }

    // NOTE: only called by loadAllEntityLocations() which is synchronized/locked, so doesn't need to be
    @CompileStatic
    protected void loadEntityFileLocations(ResourceReference entityRr) {
        Node entityRoot = getEntityFileRoot(entityRr)
        if (entityRoot.name() == "entities") {
            // loop through all entity, view-entity, and extend-entity and add file location to List for any entity named
            int numEntities = 0
            List<Node> entityRootChildren = (List<Node>) entityRoot.children()
            for (Node entity in entityRootChildren) {
                String entityName = (String) entity.attribute("entity-name")
                String packageName = (String) entity.attribute("package-name")
                String shortAlias = (String) entity.attribute("short-alias")

                if (!entityName) {
                    logger.warn("Skipping entity XML file [${entityRr.getLocation()}] element with no @entity-name: ${entity}")
                    continue
                }

                if (packageName) {
                    List pkgList = (List) this.entityLocationCache.get(packageName + "." + entityName)
                    if (!pkgList) {
                        pkgList = new LinkedList()
                        this.entityLocationCache.put(packageName + "." + entityName, pkgList)
                    }
                    if (!pkgList.contains(entityRr.location)) pkgList.add(entityRr.location)
                }

                if (shortAlias) {
                    List aliasList = (List) this.entityLocationCache.get(shortAlias)
                    if (!aliasList) {
                        aliasList = new LinkedList()
                        this.entityLocationCache.put(shortAlias, aliasList)
                    }
                    if (!aliasList.contains(entityRr.location)) aliasList.add(entityRr.location)
                }

                List nameList = (List) this.entityLocationCache.get(entityName)
                if (!nameList) {
                    nameList = new LinkedList()
                    // put in cache under both plain entityName and fullEntityName
                    this.entityLocationCache.put(entityName, nameList)
                }
                if (!nameList.contains(entityRr.location)) nameList.add(entityRr.location)

                numEntities++
            }
            if (logger.isTraceEnabled()) logger.trace("Found [${numEntities}] entity definitions in [${entityRr.location}]")
        }
    }

    protected Map<String, Node> entityFileRootMap = new HashMap<>()
    protected Node getEntityFileRoot(ResourceReference entityRr) {
        Node existingNode = entityFileRootMap.get(entityRr.getLocation())
        if (existingNode != null) {
            Long loadedTime = (Long) existingNode.attribute("_loadedTime")
            if (loadedTime != null) {
                long lastModified = entityRr.getLastModified()
                if (lastModified > loadedTime) existingNode = null
            }
        }
        if (existingNode == null) {
            InputStream entityStream = entityRr.openStream()
            if (entityStream == null) throw new BaseException("Could not open stream to entity file at [${entityRr.location}]")
            try {
                Node entityRoot = new XmlParser().parse(entityStream)
                entityRoot.attributes().put("_loadedTime", entityRr.getLastModified())
                entityFileRootMap.put(entityRr.getLocation(), entityRoot)
                return entityRoot
            } finally {
                entityStream.close()
            }
        } else {
            return existingNode
        }
    }

    protected EntityDefinition loadEntityDefinition(String entityName) {
        if (entityName.contains("#")) {
            // this is a relationship name, definitely not an entity name so just return null; this happens because we
            //    check if a name is an entity name or not in various places including where relationships are checked
            return null
        }

        EntityDefinition ed = (EntityDefinition) entityDefinitionCache.get(entityName)
        if (ed) return ed

        List entityLocationList = (List) entityLocationCache.get(entityName)
        if (entityLocationList == null) {
            if (logger.isWarnEnabled()) logger.warn("No location cache found for entity-name [${entityName}], reloading ALL entity file locations known.")
            if (logger.isTraceEnabled()) logger.trace("Unknown entity name ${entityName} location", new BaseException("Unknown entity name location"))

            this.loadAllEntityLocations()
            entityLocationList = (List) entityLocationCache.get(entityName)
            // no locations found for this entity, entity probably doesn't exist
            if (!entityLocationList) {
                entityLocationCache.put(entityName, [])
                if (logger.isWarnEnabled()) logger.warn("No definition found for entity-name [${entityName}]")
                throw new EntityNotFoundException("No definition found for entity-name [${entityName}]")
            }
        }

        if (entityLocationList.size() == 0) {
            if (logger.isTraceEnabled()) logger.trace("Entity name [${entityName}] is a known non-entity, returning null for EntityDefinition.")
            return null
        }

        String packageName = null
        if (entityName.contains('.')) {
            packageName = entityName.substring(0, entityName.lastIndexOf("."))
            entityName = entityName.substring(entityName.lastIndexOf(".")+1)
        }

        // if (!packageName) logger.warn("TOREMOVE finding entity def for [${entityName}] with no packageName, entityLocationList=${entityLocationList}")

        // If this is a moqui.entity.view.DbViewEntity, handle that in a special way (generate the Nodes from the DB records)
        if (entityLocationList.contains("_DB_VIEW_ENTITY_")) {
            EntityValue dbViewEntity = makeFind("moqui.entity.view.DbViewEntity").condition("dbViewEntityName", entityName).one()
            if (dbViewEntity == null) {
                logger.warn("Could not find DbViewEntity with name ${entityName}")
                return null
            }
            Node dbViewNode = new Node(null, "view-entity", ["entity-name":entityName, "package-name":dbViewEntity.packageName])
            if (dbViewEntity.cache == "Y") dbViewNode.attributes().put("cache", "true")
            else if (dbViewEntity.cache == "N") dbViewNode.attributes().put("cache", "false")

            EntityList memberList = makeFind("moqui.entity.view.DbViewEntityMember").condition("dbViewEntityName", entityName).list()
            for (EntityValue dbViewEntityMember in memberList) {
                Node memberEntity = dbViewNode.appendNode("member-entity",
                        ["entity-alias":dbViewEntityMember.entityAlias, "entity-name":dbViewEntityMember.entityName])
                if (dbViewEntityMember.joinFromAlias) {
                    memberEntity.attributes().put("join-from-alias", dbViewEntityMember.joinFromAlias)
                    if (dbViewEntityMember.joinOptional == "Y") memberEntity.attributes().put("join-optional", "true")
                }

                EntityList dbViewEntityKeyMapList = makeFind("moqui.entity.view.DbViewEntityKeyMap")
                        .condition(["dbViewEntityName":entityName, "joinFromAlias":dbViewEntityMember.joinFromAlias,
                            "entityAlias":dbViewEntityMember.entityAlias])
                        .list()
                for (EntityValue dbViewEntityKeyMap in dbViewEntityKeyMapList) {
                    Node keyMapNode = memberEntity.appendNode("key-map", ["field-name":dbViewEntityKeyMap.fieldName])
                    if (dbViewEntityKeyMap.relatedFieldName)
                        keyMapNode.attributes().put("related-field-name", dbViewEntityKeyMap.relatedFieldName)
                }
            }
            for (EntityValue dbViewEntityAlias in makeFind("moqui.entity.view.DbViewEntityAlias").condition("dbViewEntityName", entityName).list()) {
                Node aliasNode = dbViewNode.appendNode("alias",
                        ["name":dbViewEntityAlias.fieldAlias, "entity-alias":dbViewEntityAlias.entityAlias])
                if (dbViewEntityAlias.fieldName) aliasNode.attributes().put("field", dbViewEntityAlias.fieldName)
                if (dbViewEntityAlias.functionName) aliasNode.attributes().put("function", dbViewEntityAlias.functionName)
            }

            // create the new EntityDefinition
            ed = new EntityDefinition(this, dbViewNode)
            // cache it under entityName, fullEntityName, and short-alias
            String fullEntityName = ed.getFullEntityName()
            if (fullEntityName.startsWith("moqui.")) {
                frameworkEntityDefinitions.put(ed.getEntityName(), ed)
                frameworkEntityDefinitions.put(ed.getFullEntityName(), ed)
                if (ed.getShortAlias()) frameworkEntityDefinitions.put(ed.getShortAlias(), ed)
            } else {
                entityDefinitionCache.put(ed.getEntityName(), ed)
                entityDefinitionCache.put(ed.getFullEntityName(), ed)
                if (ed.getShortAlias()) entityDefinitionCache.put(ed.getShortAlias(), ed)
            }
            // send it on its way
            return ed
        }

        // get entity, view-entity and extend-entity Nodes for entity from each location
        Node entityNode = null
        List<Node> extendEntityNodes = new ArrayList<Node>()
        for (String location in entityLocationList) {
            Node entityRoot = getEntityFileRoot(this.ecfi.resourceFacade.getLocationReference(location))
            // filter by package-name if specified, otherwise grab whatever
            List<Node> packageChildren = (List<Node>) entityRoot.children()
                    .findAll({ (it."@entity-name" == entityName || it."@short-alias" == entityName) &&
                        (packageName ? it."@package-name" == packageName : true) })
            for (Node childNode in packageChildren) {
                if (childNode.name() == "extend-entity") {
                    extendEntityNodes.add(childNode)
                } else {
                    if (entityNode != null) logger.warn("Entity [${entityName}] was found again at [${location}], so overriding definition from previous location")
                    entityNode = StupidUtilities.deepCopyNode(childNode)
                }
            }
        }
        if (!entityNode) throw new EntityNotFoundException("No definition found for entity [${entityName}]${packageName ? ' in package ['+packageName+']' : ''}")

        // if entityName is a short-alias extend-entity elements won't match it, so find them again now that we have the main entityNode
        if (entityName == entityNode."@short-alias") {
            entityName = entityNode."@entity-name"
            packageName = entityNode."@package-name"
            for (String location in entityLocationList) {
                Node entityRoot = getEntityFileRoot(this.ecfi.resourceFacade.getLocationReference(location))
                List<Node> packageChildren = (List<Node>) entityRoot.children()
                        .findAll({ it."@entity-name" == entityName && (packageName ? it."@package-name" == packageName : true) })
                for (Node childNode in packageChildren) {
                    if (childNode.name() == "extend-entity") {
                        extendEntityNodes.add(childNode)
                    }
                }
            }
        }
        // if (entityName.endsWith("xample")) logger.warn("======== Creating Example ED entityNode=${entityNode}\nextendEntityNodes: ${extendEntityNodes}")

        // merge the extend-entity nodes
        for (Node extendEntity in extendEntityNodes) {
            // if package-name attributes don't match, skip
            if (entityNode."@package-name" != extendEntity."@package-name") continue
            // merge attributes
            entityNode.attributes().putAll(extendEntity.attributes())
            // merge field nodes
            for (Node childOverrideNode in extendEntity."field") {
                String keyValue = childOverrideNode."@name"
                Node childBaseNode = (Node) entityNode."field".find({ it."@name" == keyValue })
                if (childBaseNode) childBaseNode.attributes().putAll(childOverrideNode.attributes())
                else entityNode.append(childOverrideNode)
            }
            // add relationship, key-map (copy over, will get child nodes too
            for (Node copyNode in extendEntity."relationship") {
                Node currentNode = (Node) entityNode.get("relationship")
                        .find({ ((Node) it).attribute('title') == copyNode.attribute('title') &&
                            ((Node) it).attribute('related-entity-name') == copyNode.attribute('related-entity-name') })
                if (currentNode) {
                    currentNode.replaceNode(copyNode)
                } else {
                    entityNode.append(copyNode)
                }
            }
            // add index, index-field
            for (Node copyNode in extendEntity."index") {
                Node currentNode = (Node) entityNode.get("index")
                        .find({ ((Node) it).attribute('name') == copyNode.attribute('name') })
                if (currentNode) {
                    currentNode.replaceNode(copyNode)
                } else {
                    entityNode.append(copyNode)
                }
            }
            // copy master nodes (will be merged on parse)
            // TODO: check master/detail existance before append it into entityNode
            for (Node copyNode in extendEntity."master") entityNode.append(copyNode)
        }

        // create the new EntityDefinition
        ed = new EntityDefinition(this, entityNode)
        // cache it under entityName, fullEntityName, and short-alias
        String fullEntityName = ed.getFullEntityName()
        if (fullEntityName.startsWith("moqui.")) {
            frameworkEntityDefinitions.put(ed.getEntityName(), ed)
            frameworkEntityDefinitions.put(ed.getFullEntityName(), ed)
            if (ed.getShortAlias()) frameworkEntityDefinitions.put(ed.getShortAlias(), ed)
        } else {
            entityDefinitionCache.put(ed.getEntityName(), ed)
            entityDefinitionCache.put(ed.getFullEntityName(), ed)
            if (ed.getShortAlias()) entityDefinitionCache.put(ed.getShortAlias(), ed)
        }
        // send it on its way
        return ed
    }

    synchronized void createAllAutoReverseManyRelationships() {
        int relationshipsCreated = 0
        Set<String> entityNameSet = getAllEntityNames()
        for (String entityName in entityNameSet) {
            EntityDefinition ed = getEntityDefinition(entityName)
            // may happen if all entity names includes a DB view entity or other that doesn't really exist
            if (ed == null) continue
            List<String> pkSet = ed.getPkFieldNames()
            for (Node relNode in ed.entityNode."relationship") {
                // don't create reverse for auto reference relationships
                if (relNode.attribute('is-auto-reverse') == "true") continue
                String relatedEntityName = (String) relNode."@related-entity-name"
                // don't create reverse relationships coming back to the same entity, since it will have the same title
                //     it would create multiple relationships with the same name
                if (entityName == relatedEntityName) continue

                EntityDefinition reverseEd
                try {
                    reverseEd = getEntityDefinition(relatedEntityName)
                } catch (EntityException e) {
                    logger.warn("Error getting definition for entity [${relatedEntityName}] referred to in a relationship of entity [${entityName}]: ${e.toString()}")
                    continue
                }
                if (reverseEd == null) {
                    logger.warn("Could not find definition for entity [${relatedEntityName}] referred to in a relationship of entity [${entityName}]")
                    continue
                }

                List<String> reversePkSet = reverseEd.getPkFieldNames()
                String relType = reversePkSet.equals(pkSet) ? "one-nofk" : "many"
                String title = relNode.attribute('title')

                // does a relationship coming back already exist?
                Node reverseRelNode = (Node) reverseEd.entityNode."relationship".find(
                        { (it.attribute('related-entity-name') == ed.entityName || it.attribute('related-entity-name') == ed.fullEntityName) &&
                                ((!title && !it.attribute('title')) || it.attribute('title') == title) })
                // NOTE: removed "it."@type" == relType && ", if there is already any relationship coming back don't create the reverse
                if (reverseRelNode != null) {
                    // NOTE DEJ 20150314 Just track auto-reverse, not one-reverse
                    // make sure has is-one-reverse="true"
                    // reverseRelNode.attributes().put("is-one-reverse", "true")
                    continue
                }

                // track the fact that the related entity has others pointing back to it, unless original relationship is type many (doesn't qualify)
                if (!ed.isViewEntity() && relNode."@type" != "many") reverseEd.entityNode.attributes().put("has-dependents", "true")

                // create a new reverse-many relationship
                Map keyMap = EntityDefinition.getRelationshipExpandedKeyMapInternal(relNode, reverseEd)

                Node newRelNode = reverseEd.entityNode.appendNode("relationship",
                        ["related-entity-name":ed.fullEntityName, "type":relType, "is-auto-reverse":"true", "mutable":"true"])
                if (relNode.attribute('title')) newRelNode.attributes().title = title
                for (Map.Entry keyEntry in keyMap) {
                    // add a key-map with the reverse fields
                    newRelNode.appendNode("key-map", ["field-name":keyEntry.value, "related-field-name":keyEntry.key])
                }
                relationshipsCreated++
            }
        }
        // all EntityDefinition objects now have reverse relationships in place, remember that so this will only be
        //     called for new ones, not from cache
        for (String entityName in entityNameSet) {
            EntityDefinition ed = getEntityDefinition(entityName)
            if (ed == null) continue
            ed.hasReverseRelationships = true
        }

        if (logger.infoEnabled && relationshipsCreated > 0) logger.info("Created ${relationshipsCreated} automatic reverse relationships")
    }

    @CompileStatic
    int getEecaRuleCount() {
        int count = 0
        for (List ruleList in eecaRulesByEntityName.values()) count += ruleList.size()
        return count
    }

    void loadEecaRulesAll() {
        if (eecaRulesByEntityName.size() > 0) eecaRulesByEntityName.clear()

        // search for the service def XML file in the components
        for (String location in this.ecfi.getComponentBaseLocations().values()) {
            ResourceReference entityDirRr = this.ecfi.resourceFacade.getLocationReference(location + "/entity")
            if (entityDirRr.supportsAll()) {
                // if for some weird reason this isn't a directory, skip it
                if (!entityDirRr.isDirectory()) continue
                for (ResourceReference rr in entityDirRr.directoryEntries) {
                    if (!rr.fileName.endsWith(".eecas.xml")) continue
                    loadEecaRulesFile(rr)
                }
            } else {
                logger.warn("Can't load EECA rules from component at [${entityDirRr.location}] because it doesn't support exists/directory/etc")
            }
        }
    }
    void loadEecaRulesFile(ResourceReference rr) {
        InputStream is = null
        try {
            is = rr.openStream()
            Node serviceRoot = new XmlParser().parse(is)
            int numLoaded = 0
            for (Node secaNode in serviceRoot."eeca") {
                EntityEcaRule ser = new EntityEcaRule(ecfi, secaNode, rr.location)
                String entityName = ser.entityName
                // remove the hash if there is one to more consistently match the service name
                if (entityName.contains("#")) entityName = entityName.replace("#", "")
                List<EntityEcaRule> lst = eecaRulesByEntityName.get(entityName)
                if (!lst) {
                    lst = new LinkedList()
                    eecaRulesByEntityName.put(entityName, lst)
                }
                lst.add(ser)
                numLoaded++
            }
            if (logger.infoEnabled) logger.info("Loaded [${numLoaded}] Entity ECA rules from [${rr.location}]")
        } catch (IOException e) {
            // probably because there is no resource at that location, so do nothing
            if (logger.traceEnabled) logger.trace("Error loading EECA rules from [${rr.location}]", e)
        } finally {
            if (is != null) is.close()
        }
    }

    @CompileStatic
    boolean hasEecaRules(String entityName) { return eecaRulesByEntityName.get(entityName) as boolean }
    @CompileStatic
    void runEecaRules(String entityName, Map fieldValues, String operation, boolean before) {
        List<EntityEcaRule> lst = eecaRulesByEntityName.get(entityName)
        if (lst) {
            // if Entity ECA rules disabled in ArtifactExecutionFacade, just return immediately
            // do this only if there are EECA rules to run, small cost in getEci, etc
            if (((ArtifactExecutionFacadeImpl) this.ecfi.getEci().getArtifactExecution()).entityEcaDisabled()) return

            for (EntityEcaRule eer in lst) {
                eer.runIfMatches(entityName, fieldValues, operation, before, ecfi.getExecutionContext())
            }
        }

        // deprecated: if (entityName == "moqui.entity.ServiceTrigger" && operation == "create" && !before) runServiceTrigger(fieldValues)
    }

    /* Deprecated:
    void runServiceTrigger(Map fieldValues) {
        ecfi.getServiceFacade().sync().name((String) fieldValues.serviceName)
                .parameters((Map) ecfi.resourceFacade.expression((String) fieldValues.mapString, ""))
                .call()
        if (ecfi.getExecutionContext().getMessage().hasError())
            logger.error("Error running ServiceTrigger service [${fieldValues.serviceName}]: ${ecfi.getExecutionContext().getMessage().getErrorsString()}")
        makeValue("moqui.entity.ServiceTrigger").set("serviceTriggerId", fieldValues.serviceTriggerId)
                .set("statusId", ecfi.getExecutionContext().getMessage().hasError() ? "SrtrRunError" : "SrtrRunSuccess")
                .update()
    }
    */

    void destroy() {
        Set<String> groupNames = this.datasourceFactoryByGroupMap.keySet()
        for (String groupName in groupNames) {
            EntityDatasourceFactory edf = this.datasourceFactoryByGroupMap.get(groupName)
            this.datasourceFactoryByGroupMap.put(groupName, null)
            edf.destroy()
        }
    }

    void checkAllEntityTables(String groupName) {
        // TODO: load framework entities first, then component/mantle/etc entities for better FKs on first pass
        EntityDatasourceFactory edf = getDatasourceFactory(groupName)
        for (String entityName in getAllEntityNamesInGroup(groupName)) edf.checkAndAddTable(entityName)
    }

    Set<String> getAllEntityNames() {
        locationLoadLock.lock()
        try {
            entityLocationCache.clearExpired()
            if (entityLocationCache.size() == 0) loadAllEntityLocations()
        } finally {
            locationLoadLock.unlock()
        }

        TreeSet<String> allNames = new TreeSet()
        // only add full entity names (with package-name in it, will always have at least one dot)
        // only include entities that have a non-empty List of locations in the cache (otherwise are invalid entities)
        for (String en in entityLocationCache.keySet())
            if (en.contains(".") && entityLocationCache.get(en)) allNames.add(en)
        return allNames
    }

    Set<String> getAllNonViewEntityNames() {
        Set<String> allNames = getAllEntityNames()
        Set<String> nonViewNames = new TreeSet<>()
        for (String name in allNames) {
            EntityDefinition ed = getEntityDefinition(name)
            if (ed != null && !ed.isViewEntity()) nonViewNames.add(name)
        }
        return nonViewNames
    }
    Set<String> getAllEntityNamesWithMaster() {
        Set<String> allNames = getAllEntityNames()
        Set<String> masterNames = new TreeSet<>()
        for (String name in allNames) {
            EntityDefinition ed = getEntityDefinition(name)
            if (ed != null && !ed.isViewEntity() && ed.masterDefinitionMap) masterNames.add(name)
        }
        return masterNames
    }

    List<Map> getAllEntityInfo(int levels, boolean excludeViewEntities) {
        Map<String, Map> entityInfoMap = [:]
        for (String entityName in getAllEntityNames()) {
            EntityDefinition ed = getEntityDefinition(entityName)
            boolean isView = ed.isViewEntity()
            if (excludeViewEntities && isView) continue
            int lastDotIndex = 0
            for (int i = 0; i < levels; i++) lastDotIndex = entityName.indexOf(".", lastDotIndex+1)
            String name = lastDotIndex == -1 ? entityName : entityName.substring(0, lastDotIndex)
            Map curInfo = entityInfoMap.get(name)
            if (curInfo) {
                if (isView) StupidUtilities.addToBigDecimalInMap("viewEntities", 1, curInfo)
                else StupidUtilities.addToBigDecimalInMap("entities", 1, curInfo)
            } else {
                entityInfoMap.put(name, [name:name, entities:(isView ? 0 : 1), viewEntities:(isView ? 1 : 0)])
            }
        }
        TreeSet<String> nameSet = new TreeSet(entityInfoMap.keySet())
        List<Map> entityInfoList = []
        for (String name in nameSet) entityInfoList.add(entityInfoMap.get(name))
        return entityInfoList
    }

    /** This is used mostly by the service engine to quickly determine whether a noun is an entity. Called for all
     * ServiceDefinition init to see if the noun is an entity name. Called by entity auto check if no path and verb is
     * one of the entity-auto supported verbs. */
    @CompileStatic
    boolean isEntityDefined(String entityName) {
        if (!entityName) return false

        // Special treatment for framework entities, quick Map lookup (also faster than Cache get)
        if (frameworkEntityDefinitions.containsKey(entityName)) return true

        // Optimization, common case: if it's in the location cache it is exists, even if expired; if it isn't there
        //     doesn't necessarily mean it isn't defined, so then do more
        List locationList = (List) entityLocationCache.get(entityName)
        if (locationList != null) return locationList.size() > 0

        // There is a concurrency issue here with clearExpired() and then checking for size() > 0 and assuming all
        //     entities will be there... if loadEntityFileLocations() is running then some may be loaded and others not
        //     yet, resulting in an error that is rare but has been observed
        // The reason for this is performance, reloading all entity locations is expensive, but an empty
        //     entityLocationCache only happens in dev mode with a timeout on cache entries, or in production when the
        //     cache is cleared
        // Solution: use a reentrant lock here and in loadEntityFileLocations()
        locationLoadLock.lock()
        try {
            entityLocationCache.clearExpired()
            if (entityLocationCache.size() > 0) {
                return entityLocationCache.containsKey(entityName)
            } else {
                // faster to not do this, causes reload of all entity files if not found (happens a lot for this method):
                long startTime = System.currentTimeMillis()
                try {
                    EntityDefinition ed = getEntityDefinition(entityName)
                    boolean isEntity = ed != null
                    // log the time it takes to do this to keep an eye on whether a better solution is needed
                    if (logger.isInfoEnabled()) logger.info("Got definition for uncached entity [${entityName}] in ${System.currentTimeMillis() - startTime}ms, isEntity? ${isEntity}")
                    return isEntity
                } catch (EntityNotFoundException enfe) {
                    // ignore the exception, just means entity not found
                    if (logger.isInfoEnabled()) logger.info("Exception (not found) for uncached entity [${entityName}] in ${System.currentTimeMillis() - startTime}ms")
                    return false
                }
            }
        } finally {
            locationLoadLock.unlock()
        }
    }

    @CompileStatic
    EntityDefinition getEntityDefinition(String entityName) {
        if (!entityName) return null
        EntityDefinition ed = (EntityDefinition) this.frameworkEntityDefinitions.get(entityName)
        if (ed != null) return ed
        ed = (EntityDefinition) this.entityDefinitionCache.get(entityName)
        if (ed != null) return ed
        return loadEntityDefinition(entityName)
    }

    @CompileStatic
    void clearEntityDefinitionFromCache(String entityName) {
        EntityDefinition ed = (EntityDefinition) this.entityDefinitionCache.get(entityName)
        if (ed != null) {
            this.entityDefinitionCache.remove(ed.getEntityName())
            this.entityDefinitionCache.remove(ed.getFullEntityName())
            if (ed.getShortAlias()) this.entityDefinitionCache.remove(ed.getShortAlias())
        }
    }

    List<Map<String, Object>> getAllEntitiesInfo(String orderByField, String filterRegexp, boolean masterEntitiesOnly,
                                                 boolean excludeViewEntities, boolean excludeTenantCommon) {
        if (masterEntitiesOnly) createAllAutoReverseManyRelationships()

        List<Map<String, Object>> eil = new LinkedList()
        for (String en in getAllEntityNames()) {
            // Added (?i) to ignore the case and '*' in the starting and at ending to match if searched string is sub-part of entity name
            if (filterRegexp && !en.matches("(?i).*" + filterRegexp + ".*")) continue
            EntityDefinition ed = null
            try { ed = getEntityDefinition(en) } catch (EntityException e) { logger.warn("Problem finding entity definition", e) }
            if (ed == null) continue
            if (excludeViewEntities && ed.isViewEntity()) continue
            if (excludeTenantCommon && ed.getEntityGroupName() == "tenantcommon") continue

            if (masterEntitiesOnly) {
                if (!(ed.entityNode.attribute("has-dependents") == "true") || en.endsWith("Type") ||
                        en == "moqui.basic.Enumeration" || en == "moqui.basic.StatusItem") continue
                if (ed.getPkFieldNames().size() > 1) continue
            }

            eil.add([entityName:ed.entityName, "package":ed.entityNode.attribute("package-name"),
                    isView:(ed.isViewEntity() ? "true" : "false"), fullEntityName:ed.fullEntityName])
        }

        if (orderByField) StupidUtilities.orderMapList(eil, [orderByField])
        return eil
    }

    List<Map<String, Object>> getAllEntityRelatedFields(String en, String orderByField, String dbViewEntityName) {
        // make sure reverse-one many relationships exist
        createAllAutoReverseManyRelationships()

        EntityValue dbViewEntity = dbViewEntityName ? makeFind("moqui.entity.view.DbViewEntity").condition("dbViewEntityName", dbViewEntityName).one() : null

        List<Map<String, Object>> efl = new LinkedList()
        EntityDefinition ed = null
        try { ed = getEntityDefinition(en) } catch (EntityException e) { logger.warn("Problem finding entity definition", e) }
        if (ed == null) return efl

        // first get fields of the main entity
        for (String fn in ed.getAllFieldNames()) {
            Node fieldNode = ed.getFieldNode(fn)

            boolean inDbView = false
            String functionName = null
            EntityValue aliasVal = makeFind("moqui.entity.view.DbViewEntityAlias")
                .condition([dbViewEntityName:dbViewEntityName, entityAlias:"MASTER", fieldName:fn]).one()
            if (aliasVal) {
                inDbView = true
                functionName = aliasVal.functionName
            }

            efl.add([entityName:en, fieldName:fn, type:fieldNode."@type", cardinality:"one",
                    inDbView:inDbView, functionName:functionName])
        }

        // loop through all related entities and get their fields too
        for (EntityDefinition.RelationshipInfo relInfo in ed.getRelationshipsInfo(false)) {
            //[type:relNode."@type", title:(relNode."@title"?:""), relatedEntityName:relNode."@related-entity-name",
            //        keyMap:keyMap, targetParameterMap:targetParameterMap, prettyName:prettyName]
            EntityDefinition red = null
            try { red = getEntityDefinition((String) relInfo.relatedEntityName) } catch (EntityException e) { logger.warn("Problem finding entity definition", e) }
            if (red == null) continue

            EntityValue dbViewEntityMember = null
            if (dbViewEntity) dbViewEntityMember = makeFind("moqui.entity.view.DbViewEntityMember")
                    .condition([dbViewEntityName:dbViewEntityName, entityName:red.getFullEntityName()]).one()

            for (String fn in red.getAllFieldNames()) {
                Node fieldNode = red.getFieldNode(fn)
                boolean inDbView = false
                String functionName = null
                if (dbViewEntityMember) {
                    EntityValue aliasVal = makeFind("moqui.entity.view.DbViewEntityAlias")
                        .condition([dbViewEntityName:dbViewEntityName, entityAlias:dbViewEntityMember.entityAlias, fieldName:fn]).one()
                    if (aliasVal) {
                        inDbView = true
                        functionName = aliasVal.functionName
                    }
                }
                efl.add([entityName:relInfo.relatedEntityName, fieldName:fn, type:fieldNode."@type",
                        cardinality:relInfo.type, title:relInfo.title, inDbView:inDbView, functionName:functionName])
            }
        }

        if (orderByField) StupidUtilities.orderMapList((List<Map>) efl, [orderByField])
        return efl
    }

    @CompileStatic
    Node getDatabaseNode(String groupName) {
        Node node = databaseNodeByGroupName.get(groupName)
        if (node != null) return node
        return findDatabaseNode(groupName)
    }
    protected Node findDatabaseNode(String groupName) {
        String databaseConfName = getDatabaseConfName(groupName)
        Node node = (Node) ecfi.confXmlRoot."database-list"[0].database.find({ it."@name" == databaseConfName })
        databaseNodeByGroupName.put(groupName, node)
        return node
    }
    String getDatabaseConfName(String groupName) {
        Node datasourceNode = getDatasourceNode(groupName)
        return datasourceNode."@database-conf-name"
    }

    @CompileStatic
    Node getDatasourceNode(String groupName) {
        Node node = datasourceNodeByGroupName.get(groupName)
        if (node != null) return node
        return findDatasourceNode(groupName)
    }
    protected Node findDatasourceNode(String groupName) {
        Node dsNode = (Node) ecfi.confXmlRoot."entity-facade"[0].datasource.find({ it."@group-name" == groupName })
        if (dsNode == null) dsNode = (Node) ecfi.confXmlRoot."entity-facade"[0].datasource.find({ it."@group-name" == defaultGroupName })
        datasourceNodeByGroupName.put(groupName, dsNode)
        return dsNode
    }

    @CompileStatic
    EntityDbMeta getEntityDbMeta() { return dbMeta ? dbMeta : (dbMeta = new EntityDbMeta(this)) }

    /* ========================= */
    /* Interface Implementations */
    /* ========================= */

    @Override
    @CompileStatic
    EntityDatasourceFactory getDatasourceFactory(String groupName) {
        EntityDatasourceFactory edf = datasourceFactoryByGroupMap.get(groupName)
        if (edf == null) edf = datasourceFactoryByGroupMap.get(defaultGroupName)
        if (edf == null) throw new EntityException("Could not find EntityDatasourceFactory for entity group ${groupName}")
        return edf
    }

    @Override
    @CompileStatic
    EntityConditionFactory getConditionFactory() { return this.entityConditionFactory }
    @CompileStatic
    EntityConditionFactoryImpl getConditionFactoryImpl() { return this.entityConditionFactory }

    @Override
    @CompileStatic
    EntityValue makeValue(String entityName) {
        if (!entityName) throw new EntityException("No entityName passed to EntityFacade.makeValue")
        EntityDatasourceFactory edf = getDatasourceFactory(getEntityGroupName(entityName))
        return edf.makeEntityValue(entityName)
    }

    @Override
    @CompileStatic
    EntityFind makeFind(String entityName) { return find(entityName) }
    @Override
    @CompileStatic
    EntityFind find(String entityName) {
        if (!entityName) throw new EntityException("No entityName passed to EntityFacade.makeFind")
        EntityDatasourceFactory edf = getDatasourceFactory(getEntityGroupName(entityName))
        return edf.makeEntityFind(entityName)
    }

    final static Map<String, String> operationByMethod = [get:'find', post:'create', put:'store', patch:'update', delete:'delete']
    @Override
    @CompileStatic
    Object rest(String operation, List<String> entityPath, Map parameters, boolean masterNameInPath) {
        if (!operation) throw new EntityException("Operation (method) must be specified")
        operation = operationByMethod.get(operation.toLowerCase()) ?: operation
        if (!(operation in ['find', 'create', 'store', 'update', 'delete']))
            throw new EntityException("Operation [${operation}] not supported, must be one of: get, post, put, patch, or delete for HTTP request methods or find, create, store, update, or delete for direct entity operations")

        if (!entityPath) throw new EntityException("No entity name or alias specified in path")

        boolean dependents = (parameters.dependents == 'true' || parameters.dependents == 'Y')
        int dependentLevels = (parameters.dependentLevels ?: (dependents ? '2' : '0')) as int
        String masterName = parameters.master

        List<String> localPath = new ArrayList<String>(entityPath)

        String firstEntityName = localPath.remove(0)
        EntityDefinition firstEd = getEntityDefinition(firstEntityName)
        // this exception will be thrown at lower levels, but just in case check it again here
        if (firstEd == null) throw new EntityNotFoundException("No entity found with name or alias [${firstEntityName}]")

        // look for a master definition name as the next path element
        if (masterNameInPath) {
            if (!masterName) {
                if (localPath.size() > 0 && firstEd.getMasterDefinition(localPath.get(0)) != null) {
                    masterName = localPath.remove(0)
                } else {
                    masterName = "default"
                }
            }
            if (firstEd.getMasterDefinition(masterName) == null)
                throw new EntityException("Master definition not found for entity [${firstEd.getFullEntityName()}], tried master name [${masterName}]")
        }

        // if there are more path elements use one for each PK field of the entity
        if (localPath) {
            for (String pkFieldName in firstEd.getPkFieldNames()) {
                String pkValue = localPath.remove(0)
                if (!StupidUtilities.isEmpty(pkValue)) parameters.put(pkFieldName, pkValue)
                if (!localPath) break
            }
        }

        EntityDefinition lastEd = firstEd

        // if there is still more in the path the next should be a relationship name or alias
        while (localPath) {
            String relationshipName = localPath.remove(0)
            EntityDefinition.RelationshipInfo relInfo = lastEd.getRelationshipInfoMap().get(relationshipName)
            if (relInfo == null) throw new EntityNotFoundException("No relationship found with name or alias [${relationshipName}] on entity [${lastEd.getShortAlias()?:''}:${lastEd.getFullEntityName()}]")

            String relEntityName = relInfo.relatedEntityName
            EntityDefinition relEd = relInfo.relatedEd
            if (relEd == null) throw new EntityNotFoundException("No entity found with name [${relEntityName}], related to entity [${lastEd.getShortAlias()?:''}:${lastEd.getFullEntityName()}] by relationship [${relationshipName}]")

            // TODO: How to handle more exotic relationships where they are not a dependent record, ie join on a field
            // TODO:     other than a PK field? Should we lookup interim records to get field values to lookup the final
            // TODO:     one? This would assume that all records exist along the path... need any variation for different
            // TODO:     operations?

            // if there are more path elements use one for each PK field of the entity
            if (localPath) {
                for (String pkFieldName in relEd.getPkFieldNames()) {
                    // do we already have a value for this PK field? if so skip it...
                    if (parameters.containsKey(pkFieldName)) continue

                    String pkValue = localPath.remove(0)
                    if (!StupidUtilities.isEmpty(pkValue)) parameters.put(pkFieldName, pkValue)
                    if (!localPath) break
                }
            }

            lastEd = relEd
        }

        // at this point we should have the entity we actually want to operate on, and all PK field values from the path
        if (operation == 'find') {
            if (lastEd.containsPrimaryKey(parameters)) {
                // if we have a full PK lookup by PK and return the single value
                Map pkValues = [:]
                lastEd.setFields(parameters, pkValues, false, null, true)

                if (masterName) {
                    Map resultMap = find(lastEd.getFullEntityName()).condition(pkValues).oneMaster(masterName)
                    if (resultMap == null) throw new EntityValueNotFoundException("No value found for entity [${lastEd.getShortAlias()?:''}:${lastEd.getFullEntityName()}] with key ${pkValues}")
                    return resultMap
                } else {
                    EntityValueBase evb = (EntityValueBase) find(lastEd.getFullEntityName()).condition(pkValues).one()
                    if (evb == null) throw new EntityValueNotFoundException("No value found for entity [${lastEd.getShortAlias()?:''}:${lastEd.getFullEntityName()}] with key ${pkValues}")
                    Map resultMap = evb.getPlainValueMap(dependentLevels)
                    return resultMap
                }
            } else {
                // otherwise do a list find
                EntityFind ef = find(lastEd.getFullEntityName()).searchFormMap(parameters, null, false)
                // we don't want to go overboard with these requests, never do an unlimited find, if no limit use 100
                if (!ef.getLimit()) ef.limit(100)

                // support pagination, at least "X-Total-Count" header if find is paginated
                long count = ef.count()
                long pageIndex = ef.getPageIndex()
                long pageSize = ef.getPageSize()
                long pageMaxIndex = ((count - 1) as BigDecimal).divide(pageSize as BigDecimal, 0, BigDecimal.ROUND_DOWN).longValue()
                long pageRangeLow = pageIndex * pageSize + 1
                long pageRangeHigh = (pageIndex * pageSize) + pageSize
                if (pageRangeHigh > count) pageRangeHigh = count

                parameters.put('xTotalCount', count)
                parameters.put('xPageIndex', pageIndex)
                parameters.put('xPageSize', pageSize)
                parameters.put('xPageMaxIndex', pageMaxIndex)
                parameters.put('xPageRangeLow', pageRangeLow)
                parameters.put('xPageRangeHigh', pageRangeHigh)

                if (masterName) {
                    List resultList = ef.listMaster(masterName)
                    return resultList
                } else {
                    EntityList el = ef.list()
                    List resultList = el.getPlainValueList(dependentLevels)
                    return resultList
                }
            }
        } else {
            // use the entity auto service runner for other operations (create, store, update, delete)
            Map result = ecfi.getServiceFacade().sync().name(operation, lastEd.getFullEntityName()).parameters(parameters).call()
            return result
        }
    }

    @CompileStatic
    EntityList getValueListFromPlainMap(Map value, String entityName) {
        if (!entityName) entityName = value."_entity"
        if (!entityName) throw new EntityException("No entityName passed and no _entity field in value Map")

        EntityDefinition ed = getEntityDefinition(entityName)
        if (ed == null) throw new EntityNotFoundException("Not entity found with name ${entityName}")

        EntityList valueList = new EntityListImpl(this)
        addValuesFromPlainMapRecursive(ed, value, valueList)
        return valueList
    }
    @CompileStatic
    void addValuesFromPlainMapRecursive(EntityDefinition ed, Map value, EntityList valueList) {
        EntityValue newEntityValue = makeValue(ed.getFullEntityName())
        newEntityValue.setFields(value, true, null, null)
        valueList.add(newEntityValue)

        Map pkMap = newEntityValue.getPrimaryKeys()

        // check parameters Map for relationships
        for (EntityDefinition.RelationshipInfo relInfo in ed.getRelationshipsInfo(false)) {
            Object relParmObj = value.get(relInfo.shortAlias)
            String relKey = null
            if (relParmObj) {
                relKey = relInfo.shortAlias
            } else {
                relParmObj = value.get(relInfo.relationshipName)
                if (relParmObj) relKey = relInfo.relationshipName
            }
            if (relParmObj) {
                if (relParmObj instanceof Map) {
                    // add in all of the main entity's primary key fields, this is necessary for auto-generated, and to
                    //     allow them to be left out of related records
                    relParmObj.putAll(pkMap)
                    addValuesFromPlainMapRecursive(relInfo.relatedEd, relParmObj, valueList)
                } else if (relParmObj instanceof List) {
                    for (Object relParmEntry in relParmObj) {
                        if (relParmEntry instanceof Map) {
                            Map relParmEntryMap = (Map) relParmEntry
                            relParmEntryMap.putAll(pkMap)
                            addValuesFromPlainMapRecursive(relInfo.relatedEd, relParmEntryMap, valueList)
                        } else {
                            logger.warn("In entity auto create for entity ${ed.getFullEntityName()} found list for relationship ${relKey} with a non-Map entry: ${relParmEntry}")
                        }

                    }
                }
            }
        }
    }


    @Override
    @CompileStatic
    EntityListIterator sqlFind(String sql, List<Object> sqlParameterList, String entityName, List<String> fieldList) {
        EntityDefinition ed = this.getEntityDefinition(entityName)
        this.entityDbMeta.checkTableRuntime(ed)

        Connection con = getConnection(getEntityGroupName(entityName))
        PreparedStatement ps
        try {
            // create the PreparedStatement
            ps = con.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
            // set the parameter values
            int paramIndex = 1
            for (Object parameterValue in sqlParameterList) {
                EntityQueryBuilder.setPreparedStatementValue(ps, paramIndex, parameterValue, ed, this)
                paramIndex++
            }
            // do the actual query
            long timeBefore = System.currentTimeMillis()
            ResultSet rs = ps.executeQuery()
            if (logger.traceEnabled) logger.trace("Executed query with SQL [${sql}] and parameters [${sqlParameterList}] in [${(System.currentTimeMillis()-timeBefore)/1000}] seconds")
            // make and return the eli
            ArrayList<String> fieldLos = new ArrayList<String>(fieldList)
            EntityListIterator eli = new EntityListIteratorImpl(con, rs, ed, fieldLos, this)
            return eli
        } catch (SQLException e) {
            throw new EntityException("SQL Exception with statement:" + sql + "; " + e.toString(), e)
        }
    }

    @Override
    List<Map> getDataDocuments(String dataDocumentId, EntityCondition condition, Timestamp fromUpdateStamp,
                                Timestamp thruUpdatedStamp) {
        return entityDataDocument.getDataDocuments(dataDocumentId, condition, fromUpdateStamp, thruUpdatedStamp)
    }

    @Override
    List<Map> getDataFeedDocuments(String dataFeedId, Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp) {
        return entityDataFeed.getFeedDocuments(dataFeedId, fromUpdateStamp, thruUpdatedStamp)
    }

    void tempSetSequencedIdPrimary(String seqName, long nextSeqNum, long bankSize) {
        ArrayList<Long> bank = new ArrayList<Long>(2)
        bank[0] = nextSeqNum
        bank[1] = nextSeqNum + bankSize
        this.entitySequenceBankCache.put(seqName, bank)
    }
    void tempResetSequencedIdPrimary(String seqName) {
        this.entitySequenceBankCache.put(seqName, null)
    }

    @Override
    @CompileStatic
    String sequencedIdPrimary(String seqName, Long staggerMax, Long bankSize) {
        try {
            // is the seqName an entityName?
            EntityDefinition ed = getEntityDefinition(seqName)
            if (ed != null) {
                String groupName = ed.getEntityGroupName()
                if (ed.getEntityNode()?.attribute('@sequence-primary-use-uuid') == "true" ||
                        getDatasourceNode(groupName)?.attribute('sequence-primary-use-uuid') == "true")
                    return UUID.randomUUID().toString()
            }
        } catch (EntityException e) {
            // do nothing, just means seqName is not an entity name
            if (logger.isTraceEnabled()) logger.trace("Ignoring exception for entity not found: ${e.toString()}")
        }
        // fall through to default to the db sequenced ID
        return dbSequencedIdPrimary(seqName, staggerMax, bankSize)
    }

    protected final static long defaultBankSize = 50L
    @CompileStatic
    protected Lock getDbSequenceLock(String seqName) {
        Lock oldLock, dbSequenceLock = dbSequenceLocks.get(seqName)
        if (dbSequenceLock == null) {
            dbSequenceLock = new ReentrantLock()
            oldLock = dbSequenceLocks.putIfAbsent(seqName, dbSequenceLock)
            if(oldLock != null) {
                return oldLock
            }
        }
        return dbSequenceLock
    }
    @CompileStatic
    protected String dbSequencedIdPrimary(String seqName, Long staggerMax, Long bankSize) {

        // TODO: find some way to get this running non-synchronized for performance reasons (right now if not
        // TODO:     synchronized the forUpdate won't help if the record doesn't exist yet, causing errors in high
        // TODO:     traffic creates; is it creates only?)

        Lock dbSequenceLock = getDbSequenceLock(seqName)
        dbSequenceLock.lock()

        // NOTE: simple approach with forUpdate, not using the update/select "ethernet" approach used in OFBiz; consider
        // that in the future if there are issues with this approach

        try {
            // first get a bank if we don't have one already
            String bankCacheKey = seqName
            ArrayList<Long> bank = (ArrayList<Long>) this.entitySequenceBankCache.get(bankCacheKey)
            if (bank == null || bank[0] == null || bank[0] > bank[1]) {
                if (bank == null) {
                    bank = new ArrayList<Long>(2)
                    this.entitySequenceBankCache.put(bankCacheKey, bank)
                }

                TransactionFacade tf = this.ecfi.getTransactionFacade()
                boolean suspendedTransaction = false
                try {
                    if (tf.isTransactionInPlace()) suspendedTransaction = tf.suspend()
                    boolean beganTransaction = tf.begin(null)
                    try {
                        EntityValue svi = makeFind("moqui.entity.SequenceValueItem").condition("seqName", seqName)
                                .useCache(false).forUpdate(true).one()
                        if (svi == null) {
                            svi = makeValue("moqui.entity.SequenceValueItem")
                            svi.set("seqName", seqName)
                            // a new tradition: start sequenced values at one hundred thousand instead of ten thousand
                            bank[0] = 100000L
                            bank[1] = bank[0] + ((bankSize ?: defaultBankSize) - 1L)
                            svi.set("seqNum", bank[1])
                            svi.create()
                        } else {
                            Long lastSeqNum = svi.getLong("seqNum")
                            bank[0] = (lastSeqNum > bank[0] ? lastSeqNum + 1L : bank[0])
                            bank[1] = bank[0] + ((bankSize ?: defaultBankSize) - 1L)
                            svi.set("seqNum", bank[1])
                            svi.update()
                        }
                    } catch (Throwable t) {
                        tf.rollback(beganTransaction, "Error getting primary sequenced ID", t)
                    } finally {
                        if (beganTransaction && tf.isTransactionInPlace()) tf.commit()
                    }
                } catch (TransactionException e) {
                    throw e
                } finally {
                    if (suspendedTransaction) tf.resume()
                }
            }

            long seqNum = bank[0]
            if (staggerMax != null && staggerMax > 1L) {
                long stagger = Math.round(Math.random() * staggerMax)
                if (stagger == 0L) stagger = 1L
                bank[0] = seqNum + stagger
                // NOTE: if bank[0] > bank[1] because of this just leave it and the next time we try to get a sequence
                //     value we'll get one from a new bank
            } else {
                bank[0] = seqNum + 1L
            }

            return sequencedIdPrefix + seqNum
        } finally {
            dbSequenceLock.unlock()
        }
    }

    Set<String> getAllEntityNamesInGroup(String groupName) {
        Set<String> groupEntityNames = new TreeSet<String>()
        for (String entityName in getAllEntityNames()) {
            // use the entity/group cache handled by getEntityGroupName()
            if (getEntityGroupName(entityName) == groupName) groupEntityNames.add(entityName)
        }
        return groupEntityNames
    }

    @CompileStatic
    @Override
    String getEntityGroupName(String entityName) {
        String entityGroupName = entityGroupNameMap.get(entityName)
        if (entityGroupName != null) return entityGroupName
        EntityDefinition ed = this.getEntityDefinition(entityName)
        if (!ed) return null
        entityGroupName = ed.getEntityGroupName()
        entityGroupNameMap.put(entityName, entityGroupName)
        return entityGroupName
    }

    @CompileStatic
    @Override
    Connection getConnection(String groupName) {
        EntityDatasourceFactory edf = getDatasourceFactory(groupName)
        DataSource ds = edf.getDataSource()
        if (ds == null) throw new EntityException("Cannot get JDBC Connection for group-name [${groupName}] because it has no DataSource")
        if (ds instanceof XADataSource) {
            return this.ecfi.transactionFacade.enlistConnection(ds.getXAConnection())
        } else {
            return ds.getConnection()
        }
    }

    @Override
    EntityDataLoader makeDataLoader() { return new EntityDataLoaderImpl(this) }

    @Override
    EntityDataWriter makeDataWriter() { return new EntityDataWriterImpl(this) }

    @Override
    EntityValue makeValue(Element element) {
        if (!element) return null

        String entityName = element.getTagName()
        if (entityName.indexOf('-') > 0) entityName = entityName.substring(entityName.indexOf('-') + 1)
        if (entityName.indexOf(':') > 0) entityName = entityName.substring(entityName.indexOf(':') + 1)

        EntityValue newValue = makeValue(entityName)
        EntityDefinition ed = newValue.getEntityDefinition()

        for (String fieldName in ed.getAllFieldNames()) {
            String attrValue = element.getAttribute(fieldName)
            if (attrValue) {
                newValue.setString(fieldName, attrValue)
            } else {
                org.w3c.dom.NodeList seList = element.getElementsByTagName(fieldName)
                Element subElement = seList.getLength() > 0 ? (Element) seList.item(0) : null
                if (subElement) newValue.setString(fieldName, StupidUtilities.elementValue(subElement))
            }
        }

        return newValue
    }

    protected Map<String, Map<String, String>> javaTypeByGroup = [:]
    @CompileStatic
    String getFieldJavaType(String fieldType, EntityDefinition ed) {
        String groupName = ed.getEntityGroupName()
        Map<String, String> javaTypeMap = javaTypeByGroup.get(groupName)
        if (javaTypeMap != null) {
            String ft = javaTypeMap.get(fieldType)
            if (ft != null) return ft
        }
        return getFieldJavaTypeFromDbNode(groupName, fieldType, ed)
    }
    protected getFieldJavaTypeFromDbNode(String groupName, String fieldType, EntityDefinition ed) {
        Map<String, String> javaTypeMap = javaTypeByGroup.get(groupName)
        if (javaTypeMap == null) {
            javaTypeMap = new HashMap()
            javaTypeByGroup.put(groupName, javaTypeMap)
        }

        Node databaseNode = this.getDatabaseNode(groupName)
        String javaType = databaseNode ? databaseNode."database-type".find({ it.@type == fieldType })?."@java-type" : null
        if (!javaType) {
            Node databaseListNode = (Node) this.ecfi.confXmlRoot."database-list"[0]
            javaType = databaseListNode ? databaseListNode."dictionary-type".find({ it.@type == fieldType })?."@java-type" : null
            if (!javaType) throw new EntityException("Could not find Java type for field type [${fieldType}] on entity [${ed.getFullEntityName()}]")
        }
        javaTypeMap.put(fieldType, javaType)
        return javaType
    }

    protected Map<String, Map<String, String>> sqlTypeByGroup = [:]
    @CompileStatic
    protected String getFieldSqlType(String fieldType, EntityDefinition ed) {
        String groupName = ed.getEntityGroupName()
        Map<String, String> sqlTypeMap = sqlTypeByGroup.get(groupName)
        if (sqlTypeMap != null) {
            String st = sqlTypeMap.get(fieldType)
            if (st != null) return st
        }
        return getFieldSqlTypeFromDbNode(groupName, fieldType, ed)
    }
    protected getFieldSqlTypeFromDbNode(String groupName, String fieldType, EntityDefinition ed) {
        Map<String, String> sqlTypeMap = sqlTypeByGroup.get(groupName)
        if (sqlTypeMap == null) {
            sqlTypeMap = new HashMap()
            sqlTypeByGroup.put(groupName, sqlTypeMap)
        }

        Node databaseNode = this.getDatabaseNode(groupName)
        String sqlType = databaseNode ? databaseNode."database-type".find({ it.@type == fieldType })?."@sql-type" : null
        if (!sqlType) {
            Node databaseListNode = (Node) this.ecfi.confXmlRoot."database-list"[0]
            sqlType = databaseListNode ? databaseListNode."dictionary-type".find({ it.@type == fieldType })?."@default-sql-type" : null
            if (!sqlType) throw new EntityException("Could not find SQL type for field type [${fieldType}] on entity [${ed.getFullEntityName()}]")
        }
        sqlTypeMap.put(fieldType, sqlType)
        return sqlType

    }

    protected static final Map<String, Integer> fieldTypeIntMap = [
            "id":1, "id-long":1, "text-indicator":1, "text-short":1, "text-medium":1, "text-long":1, "text-very-long":1,
            "date-time":2, "time":3, "date":4,
            "number-integer":6, "number-float":8,
            "number-decimal":9, "currency-amount":9, "currency-precise":9,
            "binary-very-long":12 ]
    protected static final Map<String, String> fieldTypeJavaMap = [
            "id":"java.lang.String", "id-long":"java.lang.String",
            "text-indicator":"java.lang.String", "text-short":"java.lang.String", "text-medium":"java.lang.String",
            "text-long":"java.lang.String", "text-very-long":"java.lang.String",
            "date-time":"java.sql.Timestamp", "time":"java.sql.Time", "date":"java.sql.Date",
            "number-integer":"java.lang.Long", "number-float":"java.lang.Double",
            "number-decimal":"java.math.BigDecimal", "currency-amount":"java.math.BigDecimal", "currency-precise":"java.math.BigDecimal",
            "binary-very-long":"java.sql.Blob" ]
    protected static final Map<String, Integer> javaIntTypeMap = [
            "java.lang.String":1, "String":1, "org.codehaus.groovy.runtime.GStringImpl":1, "char[]":1,
            "java.sql.Timestamp":2, "Timestamp":2,
            "java.sql.Time":3, "Time":3,
            "java.sql.Date":4, "Date":4,
            "java.lang.Integer":5, "Integer":5,
            "java.lang.Long":6,"Long":6,
            "java.lang.Float":7, "Float":7,
            "java.lang.Double":8, "Double":8,
            "java.math.BigDecimal":9, "BigDecimal":9,
            "java.lang.Boolean":10, "Boolean":10,
            "java.lang.Object":11, "Object":11,
            "java.sql.Blob":12, "Blob":12, "byte[]":12, "java.nio.ByteBuffer":12, "java.nio.HeapByteBuffer":12,
            "java.sql.Clob":13, "Clob":13,
            "java.util.Date":14,
            "java.util.ArrayList":15, "java.util.HashSet":15, "java.util.LinkedHashSet":15, "java.util.LinkedList":15]
    @CompileStatic
    public static int getJavaTypeInt(String javaType) {
        Integer typeInt = javaIntTypeMap.get(javaType)
        if (!typeInt) throw new EntityException("Java type " + javaType + " not supported for entity fields")
        return typeInt
    }
}
