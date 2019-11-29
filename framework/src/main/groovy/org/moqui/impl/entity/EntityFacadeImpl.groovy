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
import org.codehaus.groovy.runtime.typehandling.GroovyCastException
import org.moqui.BaseArtifactException
import org.moqui.BaseException
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.etl.SimpleEtl
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.moqui.impl.entity.condition.FieldValueCondition
import org.moqui.impl.entity.condition.ListCondition
import org.moqui.impl.service.runner.EntityAutoServiceRunner
import org.moqui.resource.ResourceReference
import org.moqui.entity.*
import org.moqui.impl.context.ArtifactExecutionFacadeImpl
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.TransactionFacadeImpl
import org.moqui.impl.entity.EntityJavaUtil.RelationshipInfo
import org.moqui.util.CollectionUtilities
import org.moqui.util.MNode
import org.moqui.util.ObjectUtilities
import org.moqui.util.StringUtilities
import org.moqui.util.SystemBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Element

import javax.cache.Cache
import javax.sql.DataSource
import javax.sql.XAConnection
import javax.sql.XADataSource
import java.math.RoundingMode
import java.sql.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

@CompileStatic
class EntityFacadeImpl implements EntityFacade {
    protected final static Logger logger = LoggerFactory.getLogger(EntityFacadeImpl.class)
    protected final static boolean isTraceEnabled = logger.isTraceEnabled()

    public final ExecutionContextFactoryImpl ecfi
    public final EntityConditionFactoryImpl entityConditionFactory

    protected final HashMap<String, EntityDatasourceFactory> datasourceFactoryByGroupMap = new HashMap()

    /** Cache with entity name as the key and an EntityDefinition as the value; clear this cache to reload entity def */
    final Cache<String, EntityDefinition> entityDefinitionCache
    /** Cache with single entry so can be expired/cleared, contains Map with entity name as the key and List of file
     * location Strings as the value */
    final Cache<String, Map<String, List<String>>> entityLocationSingleCache
    static final String entityLocSingleEntryName = "ALL_ENTITIES"
    /** Map for framework entity definitions, avoid cache overhead and timeout issues */
    final HashMap<String, EntityDefinition> frameworkEntityDefinitions = new HashMap<>()

    /** Sequence name (often entity name) is the key and the value is an array of 2 Longs the first is the next
     * available value and the second is the highest value reserved/cached in the bank. */
    final Cache<String, long[]> entitySequenceBankCache
    protected final ConcurrentHashMap<String, Lock> dbSequenceLocks = new ConcurrentHashMap<String, Lock>()
    protected final ReentrantLock locationLoadLock = new ReentrantLock()

    protected HashMap<String, ArrayList<EntityEcaRule>> eecaRulesByEntityName = new HashMap<>()
    protected final HashMap<String, String> entityGroupNameMap = new HashMap<>()
    protected final HashMap<String, MNode> databaseNodeByGroupName = new HashMap<>()
    protected final HashMap<String, MNode> datasourceNodeByGroupName = new HashMap<>()
    protected final String defaultGroupName
    protected final TimeZone databaseTimeZone
    protected final Locale databaseLocale
    protected final ThreadLocal<Calendar> databaseTzLcCalendar = new ThreadLocal<>()
    protected final String sequencedIdPrefix
    boolean queryStats = false

    protected EntityDbMeta dbMeta = null
    protected final EntityCache entityCache
    protected final EntityDataFeed entityDataFeed
    protected final EntityDataDocument entityDataDocument

    protected final EntityListImpl emptyList

    EntityFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        entityConditionFactory = new EntityConditionFactoryImpl(this)

        MNode entityFacadeNode = getEntityFacadeNode()
        entityFacadeNode.setSystemExpandAttributes(true)
        defaultGroupName = entityFacadeNode.attribute("default-group-name")
        sequencedIdPrefix = entityFacadeNode.attribute("sequenced-id-prefix") ?: null
        queryStats = entityFacadeNode.attribute("query-stats") == "true"

        TimeZone theTimeZone = null
        if (entityFacadeNode.attribute("database-time-zone")) {
            try {
                theTimeZone = TimeZone.getTimeZone((String) entityFacadeNode.attribute("database-time-zone"))
            } catch (Exception e) { logger.warn("Error parsing database-time-zone: ${e.toString()}") }
        }
        databaseTimeZone = theTimeZone != null ? theTimeZone : TimeZone.getDefault()
        logger.info("Database time zone is ${databaseTimeZone}")
        Locale theLocale = null
        if (entityFacadeNode.attribute("database-locale")) {
            try {
                String localeStr = entityFacadeNode.attribute("database-locale")
                if (localeStr) theLocale = localeStr.contains("_") ?
                        new Locale(localeStr.substring(0, localeStr.indexOf("_")), localeStr.substring(localeStr.indexOf("_")+1).toUpperCase()) :
                        new Locale(localeStr)
            } catch (Exception e) { logger.warn("Error parsing database-locale: ${e.toString()}") }
        }
        databaseLocale = theLocale ?: Locale.getDefault()

        // init entity meta-data
        entityDefinitionCache = ecfi.cacheFacade.getCache("entity.definition")
        entityLocationSingleCache = ecfi.cacheFacade.getCache("entity.location")
        // NOTE: don't try to load entity locations before constructor is complete; this.loadAllEntityLocations()
        entitySequenceBankCache = ecfi.cacheFacade.getCache("entity.sequence.bank")

        // init connection pool (DataSource) for each group
        initAllDatasources()

        entityCache = new EntityCache(this)
        entityDataFeed = new EntityDataFeed(this)
        entityDataDocument = new EntityDataDocument(this)

        emptyList = new EntityListImpl(this)
        emptyList.setFromCache()
    }
    void postFacadeInit() {
        // ========== load a few things in advance so first page hit is faster in production (in dev mode will reload anyway as caches timeout)
        // load entity definitions
        logger.info("Loading entity definitions")
        long entityStartTime = System.currentTimeMillis()
        loadAllEntityLocations()
        int entityCount = loadAllEntityDefinitions()
        // don't always load/warm framework entities, in production warms anyway and in dev not needed: entityFacade.loadFrameworkEntities()
        logger.info("Loaded ${entityCount} entity definitions in ${System.currentTimeMillis() - entityStartTime}ms")

        // now that everything is started up, if configured check all entity tables
        checkInitDatasourceTables()

        // EECA rule tables
        loadEecaRulesAll()
    }

    EntityCache getEntityCache() { return entityCache }
    EntityDataFeed getEntityDataFeed() { return entityDataFeed }
    EntityDataDocument getEntityDataDocument() { return entityDataDocument }
    String getDefaultGroupName() { return defaultGroupName }

    // NOTE: used in scripts, etc
    TimeZone getDatabaseTimeZone() { return databaseTimeZone }
    Locale getDatabaseLocale() { return databaseLocale }

    EntityListImpl getEmptyList() { return emptyList }

    @Override
    Calendar getCalendarForTzLc() {
        // the OLD approach using user's TimeZone/Locale, bad idea because user may change for same record, getting different value, etc
        // return efi.getEcfi().getExecutionContext().getUser().getCalendarForTzLcOnly()

        // the safest approach but from profiling tests this is VERY slow
        // return Calendar.getInstance(databaseTimeZone, databaseLocale)
        // NOTE: this approach is faster but seems to cause errors with Derby (ERROR 22007: The string representation of a date/time value is out of range)
        // return databaseTzLcCalendar // NOTE this field was a Calendar object, is now a ThreadLocal<Calendar>

        // latest approach to avoid creating a Calendar object for each use, use a ThreadLocal field
        Calendar dbCal = databaseTzLcCalendar.get()
        if (dbCal == null) {
            dbCal = Calendar.getInstance(databaseTimeZone, databaseLocale)
            dbCal.clear()
            databaseTzLcCalendar.set(dbCal)
        } else {
            dbCal.clear()
        }
        return dbCal
    }

    MNode getEntityFacadeNode() { return ecfi.getConfXmlRoot().first("entity-facade") }
    void checkInitDatasourceTables() {
        // if startup-add-missing=true check tables now
        long currentTime = System.currentTimeMillis()

        Set<String> startupAddMissingGroups = new TreeSet<>()
        Set<String> allConfiguredGroups = new TreeSet<>()
        for (MNode datasourceNode in getEntityFacadeNode().children("datasource")) {
            String groupName = datasourceNode.attribute("group-name")
            MNode databaseNode = getDatabaseNode(groupName)
            String startupAddMissing = datasourceNode.attribute("startup-add-missing")
            if ((!startupAddMissing && "true".equals(databaseNode.attribute("default-startup-add-missing"))) || "true".equals(startupAddMissing)) {
                startupAddMissingGroups.add(groupName)
            }
            allConfiguredGroups.add(groupName)
        }

        boolean defaultStartAddMissing = startupAddMissingGroups.contains(getEntityFacadeNode().attribute("default-group-name"))
        if (startupAddMissingGroups.size() > 0) {
            logger.info("Checking tables for entities in groups ${startupAddMissingGroups}")
            boolean createdTables = false
            for (String entityName in getAllEntityNames()) {
                String groupName = getEntityGroupName(entityName) ?: defaultGroupName
                if (startupAddMissingGroups.contains(groupName) ||
                        (!allConfiguredGroups.contains(groupName) && defaultStartAddMissing)) {
                    EntityDatasourceFactory edf = getDatasourceFactory(groupName)
                    if (edf.checkAndAddTable(entityName)) createdTables = true
                }
            }
            // do second pass to make sure all FKs created
            if (createdTables) {
                logger.info("Tables were created, checking FKs for all entities in groups ${startupAddMissingGroups}")
                for (String entityName in getAllEntityNames()) {
                    String groupName = getEntityGroupName(entityName) ?: defaultGroupName
                    if (startupAddMissingGroups.contains(groupName) ||
                            (!allConfiguredGroups.contains(groupName) && defaultStartAddMissing)) {
                        EntityDatasourceFactory edf = getDatasourceFactory(groupName)
                        if (edf instanceof EntityDatasourceFactoryImpl) {
                            EntityDefinition ed = getEntityDefinition(entityName)
                            if (ed.isViewEntity) continue
                            getEntityDbMeta().createForeignKeys(ed, true)

                        }
                    }
                }
            }
            logger.info("Checked tables for all entities in ${(System.currentTimeMillis() - currentTime)/1000} seconds")
        }
    }

    protected void initAllDatasources() {
        for (MNode datasourceNode in getEntityFacadeNode().children("datasource")) {
            datasourceNode.setSystemExpandAttributes(true)
            String groupName = datasourceNode.attribute("group-name")

            if ("true".equals(datasourceNode.attribute("disabled"))) {
                logger.info("Skipping disabled datasource ${groupName}")
                continue
            }

            String objectFactoryClass = datasourceNode.attribute("object-factory") ?: "org.moqui.impl.entity.EntityDatasourceFactoryImpl"
            EntityDatasourceFactory edf = (EntityDatasourceFactory) Thread.currentThread().getContextClassLoader().loadClass(objectFactoryClass).newInstance()
            datasourceFactoryByGroupMap.put(groupName, edf.init(this, datasourceNode))
        }
    }

    static class DatasourceInfo {
        EntityFacadeImpl efi
        MNode datasourceNode
        String uniqueName
        Map<String, String> dsDetails = new LinkedHashMap<>()

        String jndiName
        MNode serverJndi
        String jdbcDriver = null, jdbcUri = null, jdbcUsername = null, jdbcPassword = null
        String xaDsClass = null
        Properties xaProps = null

        MNode inlineJdbc = null
        MNode database = null

        DatasourceInfo(EntityFacadeImpl efi, MNode datasourceNode) {
            this.efi = efi
            this.datasourceNode = datasourceNode

            String groupName = datasourceNode.attribute("group-name")
            uniqueName =  groupName + "_DS"

            MNode jndiJdbcNode = datasourceNode.first("jndi-jdbc")
            inlineJdbc = datasourceNode.first("inline-jdbc")
            if (jndiJdbcNode == null && inlineJdbc == null) {
                MNode dbNode = efi.getDatabaseNode(groupName)
                inlineJdbc = dbNode.first("inline-jdbc")
            }
            MNode xaProperties = inlineJdbc?.first("xa-properties")
            database = efi.getDatabaseNode(groupName)

            if (jndiJdbcNode != null) {
                serverJndi = efi.getEntityFacadeNode().first("server-jndi")
                if (serverJndi != null) serverJndi.setSystemExpandAttributes(true)
                jndiName = jndiJdbcNode.attribute("jndi-name")
            } else if (xaProperties != null) {
                xaDsClass = inlineJdbc.attribute("xa-ds-class") ? inlineJdbc.attribute("xa-ds-class") : database.attribute("default-xa-ds-class")

                xaProps = new Properties()
                xaProperties.setSystemExpandAttributes(true)
                for (String key in xaProperties.attributes.keySet()) {
                    if (xaProps.containsKey(key)) continue
                    // various H2, Derby, etc properties have a ${moqui.runtime} which is a System property, others may have it too
                    String propValue = xaProperties.attribute(key)
                    if (propValue) xaProps.setProperty(key, propValue)
                }

                for (String propName in xaProps.stringPropertyNames()) {
                    if (propName.toLowerCase().contains("password")) continue
                    dsDetails.put(propName, xaProps.getProperty(propName))
                }
            } else if (inlineJdbc != null) {
                inlineJdbc.setSystemExpandAttributes(true)
                jdbcDriver = inlineJdbc.attribute("jdbc-driver") ? inlineJdbc.attribute("jdbc-driver") : database.attribute("default-jdbc-driver")
                jdbcUri = inlineJdbc.attribute("jdbc-uri")
                if (jdbcUri.contains('${')) jdbcUri = SystemBinding.expand(jdbcUri)
                jdbcUsername = inlineJdbc.attribute("jdbc-username")
                jdbcPassword = inlineJdbc.attribute("jdbc-password")

                dsDetails.put("uri", jdbcUri)
                dsDetails.put("user", jdbcUsername)
            } else {
                throw new EntityException("Data source for group ${groupName} has no inline-jdbc or jndi-jdbc configuration")
            }
        }
    }

    void loadFrameworkEntities() {
        // load framework entity definitions (moqui.*)
        long startTime = System.currentTimeMillis()
        Set<String> entityNames = getAllEntityNames()
        int entityCount = 0
        for (String entityName in entityNames) {
            if (entityName.startsWith("moqui.")) {
                entityCount++
                try {
                    EntityDefinition ed = getEntityDefinition(entityName)
                    ed.getRelationshipInfoMap()
                    // must use EntityDatasourceFactory.checkTableExists, NOT entityDbMeta.tableExists(ed)
                    ed.entityInfo.datasourceFactory.checkTableExists(ed.getFullEntityName())
                } catch (Throwable t) { logger.warn("Error loading framework entity ${entityName} definitions: ${t.toString()}", t) }
            }
        }
        logger.info("Loaded ${entityCount} framework entity definitions in ${System.currentTimeMillis() - startTime}ms")
    }

    final static Set<String> cachedCountEntities = new HashSet<>(["moqui.basic.EnumerationType"])
    final static Set<String> cachedListEntities = new HashSet<>([ "moqui.entity.document.DataDocument",
        "moqui.entity.document.DataDocumentCondition", "moqui.entity.document.DataDocumentField",
        "moqui.entity.feed.DataFeedAndDocument", "moqui.entity.view.DbViewEntity", "moqui.entity.view.DbViewEntityAlias",
        "moqui.entity.view.DbViewEntityKeyMap", "moqui.entity.view.DbViewEntityMember",

        "moqui.screen.ScreenThemeResource", "moqui.screen.SubscreensItem", "moqui.screen.form.DbFormField",
        "moqui.screen.form.DbFormFieldAttribute", "moqui.screen.form.DbFormFieldEntOpts", "moqui.screen.form.DbFormFieldEntOptsCond",
        "moqui.screen.form.DbFormFieldEntOptsOrder", "moqui.screen.form.DbFormFieldOption", "moqui.screen.form.DbFormLookup",

        "moqui.security.ArtifactAuthzCheckView", "moqui.security.ArtifactTarpitCheckView", "moqui.security.ArtifactTarpitLock",
        "moqui.security.UserGroupMember", "moqui.security.UserGroupPreference"
    ])
    final static Set<String> cachedOneEntities = new HashSet<>([ "moqui.basic.Enumeration", "moqui.basic.LocalizedMessage",
            "moqui.entity.document.DataDocument", "moqui.entity.view.DbViewEntity", "moqui.screen.form.DbForm",
            "moqui.security.UserAccount", "moqui.security.UserPreference", "moqui.security.UserScreenTheme", "moqui.server.Visit"
    ])
    void warmCache()  {
        logger.info("Warming cache for all entity definitions")
        long startTime = System.currentTimeMillis()
        Set<String> entityNames = getAllEntityNames()
        for (String entityName in entityNames) {
            try {
                EntityDefinition ed = getEntityDefinition(entityName)
                ed.getRelationshipInfoMap()
                // must use EntityDatasourceFactory.checkTableExists, NOT entityDbMeta.tableExists(ed)
                ed.entityInfo.datasourceFactory.checkTableExists(ed.getFullEntityName())

                if (cachedCountEntities.contains(entityName)) ed.getCacheCount(entityCache)
                if (cachedListEntities.contains(entityName)) {
                    ed.getCacheList(entityCache)
                    ed.getCacheListRa(entityCache)
                    ed.getCacheListViewRa(entityCache)
                }
                if (cachedOneEntities.contains(entityName)) {
                    ed.getCacheOne(entityCache)
                    ed.getCacheOneRa(entityCache)
                    ed.getCacheOneViewRa(entityCache)
                }
            } catch (Throwable t) { logger.warn("Error warming entity cache: ${t.toString()}") }
        }

        logger.info("Warmed entity definition cache for ${entityNames.size()} entities in ${System.currentTimeMillis() - startTime}ms")
    }

    Set<String> getDatasourceGroupNames() {
        Set<String> groupNames = new TreeSet<String>()
        for (MNode datasourceNode in getEntityFacadeNode().children("datasource")) {
            groupNames.add((String) datasourceNode.attribute("group-name"))
        }
        return groupNames
    }

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
        for (MNode loadEntity in getEntityFacadeNode().children("load-entity")) {
            entityRrList.add(this.ecfi.resourceFacade.getLocationReference((String) loadEntity.attribute("location")))
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

    Map<String, List<String>> loadAllEntityLocations() {
        // lock or wait for lock, this lock used here and for checking entity defined
        locationLoadLock.lock()

        try {
            // load all entity files based on ResourceReference
            long startTime = System.currentTimeMillis()

            Map<String, List<String>> entityLocationCache = entityLocationSingleCache.get(entityLocSingleEntryName)
            // when loading all entity locations we expect this to be null, if it isn't no need to load
            if (entityLocationCache != null) return entityLocationCache
            entityLocationCache = new HashMap<>()

            List<ResourceReference> allEntityFileLocations = getAllEntityFileLocations()
            for (ResourceReference entityRr in allEntityFileLocations) this.loadEntityFileLocations(entityRr, entityLocationCache)
            if (logger.isInfoEnabled()) logger.info("Found entities in ${allEntityFileLocations.size()} files in ${System.currentTimeMillis() - startTime}ms")

            // put in the cache for other code to use; needed before DbViewEntity load so DB queries work
            entityLocationSingleCache.put(entityLocSingleEntryName, entityLocationCache)

            // look for view-entity definitions in the database (moqui.entity.view.DbViewEntity)
            if (entityLocationCache.get("moqui.entity.view.DbViewEntity")) {
                int numDbViewEntities = 0
                for (EntityValue dbViewEntity in find("moqui.entity.view.DbViewEntity").list()) {
                    if (dbViewEntity.packageName) {
                        List<String> pkgList = (List<String>) entityLocationCache.get((String) dbViewEntity.packageName + "." + dbViewEntity.dbViewEntityName)
                        if (pkgList == null) {
                            pkgList = new LinkedList<>()
                            entityLocationCache.put((String) dbViewEntity.packageName + "." + dbViewEntity.dbViewEntityName, pkgList)
                        }
                        if (!pkgList.contains("_DB_VIEW_ENTITY_")) pkgList.add("_DB_VIEW_ENTITY_")
                    }

                    List<String> nameList = (List<String>) entityLocationCache.get((String) dbViewEntity.dbViewEntityName)
                    if (nameList == null) {
                        nameList = new LinkedList<>()
                        // put in cache under both plain entityName and fullEntityName
                        entityLocationCache.put((String) dbViewEntity.dbViewEntityName, nameList)
                    }
                    if (!nameList.contains("_DB_VIEW_ENTITY_")) nameList.add("_DB_VIEW_ENTITY_")

                    numDbViewEntities++
                }
                if (logger.infoEnabled) logger.info("Found ${numDbViewEntities} view-entity definitions in database (DbViewEntity records)")
            } else {
                logger.warn("Could not find view-entity definitions in database (moqui.entity.view.DbViewEntity), no location found for the moqui.entity.view.DbViewEntity entity.")
            }

            /* a little code to show all entities and their locations
            Set<String> enSet = new TreeSet(entityLocationCache.keySet())
            for (String en in enSet) {
                List lst = entityLocationCache.get(en)
                entityLocationCache.put(en, Collections.unmodifiableList(lst))
                logger.warn("TOREMOVE entity ${en}: ${lst}")
            }
            */

            return entityLocationCache
        } finally {
            locationLoadLock.unlock()
        }
    }

    // NOTE: only called by loadAllEntityLocations() which is synchronized/locked, so doesn't need to be
    protected void loadEntityFileLocations(ResourceReference entityRr, Map<String, List<String>> entityLocationCache) {
        MNode entityRoot = getEntityFileRoot(entityRr)
        if (entityRoot.name == "entities") {
            // loop through all entity, view-entity, and extend-entity and add file location to List for any entity named
            int numEntities = 0
            for (MNode entity in entityRoot.children) {
                String entityName = entity.attribute("entity-name")
                String packageName = entity.attribute("package")
                if (packageName == null || packageName.isEmpty()) packageName = entity.attribute("package-name")
                String shortAlias = entity.attribute("short-alias")

                if (entityName == null || entityName.length() == 0) {
                    logger.warn("Skipping entity XML file [${entityRr.getLocation()}] element with no @entity-name: ${entity}")
                    continue
                }

                List<String> locList = (List<String>) entityLocationCache.get(entityName)
                if (locList == null) {
                    locList = new LinkedList<>()
                    locList.add(entityRr.location)
                    entityLocationCache.put(entityName, locList)
                } else if (!locList.contains(entityRr.location)) {
                    locList.add(entityRr.location)
                }

                if (packageName != null && packageName.length() > 0) {
                    String fullEntityName = packageName.concat(".").concat(entityName)
                    if (!entityLocationCache.containsKey(fullEntityName)) entityLocationCache.put(fullEntityName, locList)
                }
                if (shortAlias != null && shortAlias.length() > 0) {
                    if (!entityLocationCache.containsKey(shortAlias)) entityLocationCache.put(shortAlias, locList)
                }

                numEntities++
            }
            if (isTraceEnabled) logger.trace("Found [${numEntities}] entity definitions in [${entityRr.location}]")
        }
    }

    protected static MNode getEntityFileRoot(ResourceReference entityRr) { return MNode.parse(entityRr) }

    int loadAllEntityDefinitions() {
        int entityCount = 0
        for (String en in getAllEntityNames()) {
            try {
                getEntityDefinition(en)
            } catch (EntityException e) {
                logger.warn("Problem finding entity definition", e)
                continue
            }
            entityCount++
        }
        return entityCount
    }


    protected EntityDefinition loadEntityDefinition(String entityName) {
        if (entityName.contains("#")) {
            // this is a relationship name, definitely not an entity name so just return null; this happens because we
            //    check if a name is an entity name or not in various places including where relationships are checked
            return null
        }

        EntityDefinition ed = (EntityDefinition) entityDefinitionCache.get(entityName)
        if (ed != null) return ed

        Map<String, List<String>> entityLocationCache = entityLocationSingleCache.get(entityLocSingleEntryName)
        if (entityLocationCache == null) entityLocationCache = loadAllEntityLocations()

        List<String> entityLocationList = (List<String>) entityLocationCache.get(entityName)
        if (entityLocationList == null) {
            if (logger.isWarnEnabled()) logger.warn("No location cache found for entity-name [${entityName}], reloading ALL entity file and DB locations")
            if (isTraceEnabled) logger.trace("Unknown entity name ${entityName} location", new BaseException("Unknown entity name location"))

            // remove the single cache entry
            entityLocationSingleCache.remove(entityLocSingleEntryName)
            // reload all locations
            entityLocationCache = this.loadAllEntityLocations()
            entityLocationList = (List<String>) entityLocationCache.get(entityName)
            // no locations found for this entity, entity probably doesn't exist
            if (entityLocationList == null || entityLocationList.size() == 0) {
                // TODO: while this is helpful, if another unknown non-existing entity is looked for this will be lost
                entityLocationCache.put(entityName, new LinkedList<String>())
                if (logger.isWarnEnabled()) logger.warn("No definition found for entity-name [${entityName}]")
                throw new EntityNotFoundException("No definition found for entity-name [${entityName}]")
            }
        }

        if (entityLocationList.size() == 0) {
            if (isTraceEnabled) logger.trace("Entity name [${entityName}] is a known non-entity, returning null for EntityDefinition.")
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
            EntityValue dbViewEntity = find("moqui.entity.view.DbViewEntity").condition("dbViewEntityName", entityName).one()
            if (dbViewEntity == null) {
                logger.warn("Could not find DbViewEntity with name ${entityName}")
                return null
            }
            MNode dbViewNode = new MNode("view-entity", ["entity-name":entityName, "package":(String) dbViewEntity.packageName])
            if (dbViewEntity.cache == "Y") dbViewNode.attributes.put("cache", "true")
            else if (dbViewEntity.cache == "N") dbViewNode.attributes.put("cache", "false")

            EntityList memberList = find("moqui.entity.view.DbViewEntityMember").condition("dbViewEntityName", entityName).list()
            for (EntityValue dbViewEntityMember in memberList) {
                MNode memberEntity = dbViewNode.append("member-entity",
                        ["entity-alias":dbViewEntityMember.getString("entityAlias"), "entity-name":dbViewEntityMember.getString("entityName")])
                if (dbViewEntityMember.joinFromAlias) {
                    memberEntity.attributes.put("join-from-alias", (String) dbViewEntityMember.joinFromAlias)
                    if (dbViewEntityMember.joinOptional == "Y") memberEntity.attributes.put("join-optional", "true")
                }

                EntityList dbViewEntityKeyMapList = find("moqui.entity.view.DbViewEntityKeyMap")
                        .condition(["dbViewEntityName":entityName, "joinFromAlias":dbViewEntityMember.joinFromAlias,
                            "entityAlias":dbViewEntityMember.getString("entityAlias")])
                        .list()
                for (EntityValue dbViewEntityKeyMap in dbViewEntityKeyMapList) {
                    MNode keyMapNode = memberEntity.append("key-map", ["field-name":(String) dbViewEntityKeyMap.fieldName])
                    if (dbViewEntityKeyMap.relatedFieldName)
                        keyMapNode.attributes.put("related", (String) dbViewEntityKeyMap.relatedFieldName)
                }
            }
            for (EntityValue dbViewEntityAlias in find("moqui.entity.view.DbViewEntityAlias").condition("dbViewEntityName", entityName).list()) {
                MNode aliasNode = dbViewNode.append("alias",
                        ["name":(String) dbViewEntityAlias.fieldAlias, "entity-alias":(String) dbViewEntityAlias.entityAlias])
                if (dbViewEntityAlias.fieldName) aliasNode.attributes.put("field", (String) dbViewEntityAlias.fieldName)
                if (dbViewEntityAlias.functionName) aliasNode.attributes.put("function", (String) dbViewEntityAlias.functionName)
            }

            // create the new EntityDefinition
            ed = new EntityDefinition(this, dbViewNode)

            // cache it under entityName, fullEntityName, and short-alias
            String fullEntityName = ed.fullEntityName
            if (fullEntityName.startsWith("moqui.")) {
                frameworkEntityDefinitions.put(ed.entityInfo.internalEntityName, ed)
                frameworkEntityDefinitions.put(fullEntityName, ed)
                if (ed.entityInfo.shortAlias) frameworkEntityDefinitions.put(ed.entityInfo.shortAlias, ed)
            } else {
                entityDefinitionCache.put(ed.entityInfo.internalEntityName, ed)
                entityDefinitionCache.put(fullEntityName, ed)
                if (ed.entityInfo.shortAlias) entityDefinitionCache.put(ed.entityInfo.shortAlias, ed)
            }
            // send it on its way
            return ed
        }

        // get entity, view-entity and extend-entity Nodes for entity from each location
        MNode entityNode = null
        List<MNode> extendEntityNodes = new ArrayList<MNode>()
        for (String location in entityLocationList) {
            MNode entityRoot = getEntityFileRoot(this.ecfi.resourceFacade.getLocationReference(location))
            // filter by package if specified, otherwise grab whatever
            List<MNode> packageChildren = entityRoot.children
                    .findAll({ (it.attribute("entity-name") == entityName || it.attribute("short-alias") == entityName) &&
                        (packageName ? (it.attribute("package") == packageName || it.attribute("package-name") == packageName) : true) })
            for (MNode childNode in packageChildren) {
                if (childNode.name == "extend-entity") {
                    extendEntityNodes.add(childNode)
                } else {
                    if (entityNode != null) logger.warn("Entity [${entityName}] was found again at [${location}], so overriding definition from previous location")
                    entityNode = childNode.deepCopy(null)
                }
            }
        }
        if (entityNode == null) throw new EntityNotFoundException("No definition found for entity [${entityName}]${packageName ? ' in package ['+packageName+']' : ''}")

        // if entityName is a short-alias extend-entity elements won't match it, so find them again now that we have the main entityNode
        if (entityName == entityNode.attribute("short-alias")) {
            entityName = entityNode.attribute("entity-name")
            packageName = entityNode.attribute("package") ?: entityNode.attribute("package-name")
            for (String location in entityLocationList) {
                MNode entityRoot = getEntityFileRoot(this.ecfi.resourceFacade.getLocationReference(location))
                List<MNode> packageChildren = entityRoot.children
                        .findAll({ it.attribute("entity-name") == entityName &&
                            (packageName ? (it.attribute("package") == packageName || it.attribute("package-name") == packageName) : true) })
                for (MNode childNode in packageChildren) {
                    if (childNode.name == "extend-entity") {
                        extendEntityNodes.add(childNode)
                    }
                }
            }
        }
        // if (entityName.endsWith("xample")) logger.warn("======== Creating Example ED entityNode=${entityNode}\nextendEntityNodes: ${extendEntityNodes}")

        // merge the extend-entity nodes
        for (MNode extendEntity in extendEntityNodes) {
            // if package attributes don't match, skip
            String entityPackage = entityNode.attribute("package") ?: entityNode.attribute("package-name")
            String extendPackage = extendEntity.attribute("package") ?: extendEntity.attribute("package-name")
            if (entityPackage != extendPackage) continue
            // merge attributes
            entityNode.attributes.putAll(extendEntity.attributes)
            // merge field nodes
            for (MNode childOverrideNode in extendEntity.children("field")) {
                String keyValue = childOverrideNode.attribute("name")
                MNode childBaseNode = entityNode.first({ MNode it -> it.name == "field" && it.attribute("name") == keyValue })
                if (childBaseNode) childBaseNode.attributes.putAll(childOverrideNode.attributes)
                else entityNode.append(childOverrideNode)
            }
            // add relationship, key-map (copy over, will get child nodes too
            ArrayList<MNode> relNodeList = extendEntity.children("relationship")
            for (int i = 0; i < relNodeList.size(); i++) {
                MNode copyNode = relNodeList.get(i)
                int curNodeIndex = entityNode.children
                        .findIndexOf({ MNode it ->
                            String itRelated = it.attribute('related') ?: it.attribute('related-entity-name');
                            String copyRelated = copyNode.attribute('related') ?: copyNode.attribute('related-entity-name');
                            return it.name == "relationship" && itRelated == copyRelated &&
                                    it.attribute('title') == copyNode.attribute('title'); })
                if (curNodeIndex >= 0) {
                    entityNode.children.set(curNodeIndex, copyNode)
                } else {
                    entityNode.append(copyNode)
                }
            }
            // add index, index-field
            for (MNode copyNode in extendEntity.children("index")) {
                int curNodeIndex = entityNode.children
                        .findIndexOf({ MNode it -> it.name == "index" && it.attribute('name') == copyNode.attribute('name') })
                if (curNodeIndex >= 0) {
                    entityNode.children.set(curNodeIndex, copyNode)
                } else {
                    entityNode.append(copyNode)
                }
            }
            // copy master nodes (will be merged on parse)
            // TODO: check master/detail existence before append it into entityNode
            for (MNode copyNode in extendEntity.children("master")) entityNode.append(copyNode)
        }

        // create the new EntityDefinition
        ed = new EntityDefinition(this, entityNode)
        // cache it under entityName, fullEntityName, and short-alias
        String fullEntityName = ed.fullEntityName
        if (fullEntityName.startsWith("moqui.")) {
            frameworkEntityDefinitions.put(ed.entityInfo.internalEntityName, ed)
            frameworkEntityDefinitions.put(fullEntityName, ed)
            if (ed.entityInfo.shortAlias) frameworkEntityDefinitions.put(ed.entityInfo.shortAlias, ed)
        } else {
            entityDefinitionCache.put(ed.entityInfo.internalEntityName, ed)
            entityDefinitionCache.put(fullEntityName, ed)
            if (ed.entityInfo.shortAlias) entityDefinitionCache.put(ed.entityInfo.shortAlias, ed)
        }
        // send it on its way
        return ed
    }

    synchronized void createAllAutoReverseManyRelationships() {
        int relationshipsCreated = 0
        Set<String> entityNameSet = getAllEntityNames()
        for (String entityName in entityNameSet) {
            EntityDefinition ed
            // for auto reverse relationships just ignore EntityException on getEntityDefinition
            try { ed = getEntityDefinition(entityName) } catch (EntityException e) { if (isTraceEnabled) logger.trace("Entity not found", e); continue; }
            // may happen if all entity names includes a DB view entity or other that doesn't really exist
            if (ed == null) continue
            String edEntityName = ed.entityInfo.internalEntityName
            String edFullEntityName = ed.fullEntityName
            List<String> pkSet = ed.getPkFieldNames()
            ArrayList<MNode> relationshipList = ed.entityNode.children("relationship")
            int relationshipListSize = relationshipList.size()
            for (int rlIndex = 0; rlIndex < relationshipListSize; rlIndex++) {
                MNode relNode = (MNode) relationshipList.get(rlIndex)
                // don't create reverse for auto reference relationships
                if ("true".equals(relNode.attribute("is-auto-reverse"))) continue
                String relatedEntityName = relNode.attribute("related")
                if (relatedEntityName == null || relatedEntityName.length() == 0) relatedEntityName = relNode.attribute("related-entity-name")
                // don't create reverse relationships coming back to the same entity, since it will have the same title
                //     it would create multiple relationships with the same name
                if (entityName.equals(relatedEntityName)) continue

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
                boolean hasTitle = title != null && title.length() > 0

                // does a relationship coming back already exist?
                boolean foundReverse = false
                ArrayList<MNode> reverseRelList = reverseEd.entityNode.children("relationship")
                int reverseRelListSize = reverseRelList.size()
                for (int i = 0; i < reverseRelListSize; i++) {
                    MNode reverseRelNode = (MNode) reverseRelList.get(i)
                    String related = reverseRelNode.attribute("related")
                    if (related == null || related.length() == 0) related = reverseRelNode.attribute("related-entity-name")
                    if (!edEntityName.equals(related) && !edFullEntityName.equals(related)) continue
                    // TODO: instead of checking title check reverse expanded key-map
                    String reverseTitle = reverseRelNode.attribute("title")
                    if (hasTitle) {
                        if (!title.equals(reverseTitle)) continue
                    } else {
                        if (reverseTitle != null && reverseTitle.length() > 0) continue
                    }
                    foundReverse = true
                }
                // NOTE: removed "it."@type" == relType && ", if there is already any relationship coming back don't create the reverse
                if (foundReverse) {
                    // NOTE DEJ 20150314 Just track auto-reverse, not one-reverse
                    // make sure has is-one-reverse="true"
                    // reverseRelNode.attributes().put("is-one-reverse", "true")
                    continue
                }

                // track the fact that the related entity has others pointing back to it, unless original relationship is type many (doesn't qualify)
                if (!ed.isViewEntity && !"many".equals(relNode.attribute("type"))) reverseEd.entityNode.attributes.put("has-dependents", "true")

                // create a new reverse-many relationship
                Map<String, String> keyMap = EntityDefinition.getRelationshipExpandedKeyMapInternal(relNode, reverseEd)

                MNode newRelNode = reverseEd.entityNode.append("relationship",
                        ["related":edFullEntityName, "type":relType, "is-auto-reverse":"true", "mutable":"true"])
                if (hasTitle) newRelNode.attributes.put("title", title)
                for (Map.Entry<String, String> keyEntry in keyMap) {
                    // add a key-map with the reverse fields
                    newRelNode.append("key-map", ["field-name":keyEntry.value, "related":keyEntry.key])
                }
                relationshipsCreated++
            }
        }
        // all EntityDefinition objects now have reverse relationships in place, remember that so this will only be
        //     called for new ones, not from cache
        for (String entityName in entityNameSet) {
            EntityDefinition ed
            try { ed = getEntityDefinition(entityName) } catch (EntityException e) { if (isTraceEnabled) logger.trace("Entity not found", e); continue; }
            if (ed == null) continue
            ed.setHasReverseRelationships()
        }

        if (logger.infoEnabled && relationshipsCreated > 0) logger.info("Created ${relationshipsCreated} automatic reverse relationships")
    }

    // used in tools screen
    int getEecaRuleCount() {
        int count = 0
        for (List ruleList in eecaRulesByEntityName.values()) count += ruleList.size()
        return count
    }

    void loadEecaRulesAll() {
        int numLoaded = 0
        int numFiles = 0
        HashMap<String, EntityEcaRule> ruleByIdMap = new HashMap<>()
        LinkedList<EntityEcaRule> ruleNoIdList = new LinkedList<>()
        // search for the service def XML file in the components
        for (String location in this.ecfi.getComponentBaseLocations().values()) {
            ResourceReference entityDirRr = this.ecfi.resourceFacade.getLocationReference(location + "/entity")
            if (entityDirRr.supportsAll()) {
                // if for some weird reason this isn't a directory, skip it
                if (!entityDirRr.isDirectory()) continue
                for (ResourceReference rr in entityDirRr.directoryEntries) {
                    if (!rr.fileName.endsWith(".eecas.xml")) continue
                    numLoaded += loadEecaRulesFile(rr, ruleByIdMap, ruleNoIdList)
                    numFiles++

                }
            } else {
                logger.warn("Can't load EECA rules from component at [${entityDirRr.location}] because it doesn't support exists/directory/etc")
            }
        }
        if (logger.infoEnabled) logger.info("Loaded ${numLoaded} Entity ECA rules from ${numFiles} .eecas.xml files, ${ruleNoIdList.size()} rules have no id, ${ruleNoIdList.size() + ruleByIdMap.size()} EECA rules active")

        HashMap<String, ArrayList<EntityEcaRule>> ruleMap = new HashMap<>()
        ruleNoIdList.addAll(ruleByIdMap.values())
        for (EntityEcaRule ecaRule in ruleNoIdList) {
            EntityDefinition ed = getEntityDefinition(ecaRule.entityName)
            String entityName = ed.getFullEntityName()

            ArrayList<EntityEcaRule> lst = ruleMap.get(entityName)
            if (lst == null) {
                lst = new ArrayList<EntityEcaRule>()
                ruleMap.put(entityName, lst)
            }
            lst.add(ecaRule)
        }

        // replace entire EECA rules Map in one operation
        eecaRulesByEntityName = ruleMap
    }
    int loadEecaRulesFile(ResourceReference rr, HashMap<String, EntityEcaRule> ruleByIdMap, LinkedList<EntityEcaRule> ruleNoIdList) {
        MNode eecasRoot = MNode.parse(rr)
        int numLoaded = 0
        for (MNode eecaNode in eecasRoot.children("eeca")) {
            String entityName = eecaNode.attribute("entity")
            if (!isEntityDefined(entityName)) {
                logger.warn("Invalid entity name ${entityName} found in EECA file ${rr.location}, skipping")
                continue
            }
            EntityEcaRule ecaRule = new EntityEcaRule(ecfi, eecaNode, rr.location)
            String ruleId = eecaNode.attribute("id")
            if (ruleId != null && !ruleId.isEmpty()) ruleByIdMap.put(ruleId, ecaRule)
            else ruleNoIdList.add(ecaRule)
            numLoaded++
        }
        if (logger.isTraceEnabled()) logger.trace("Loaded [${numLoaded}] Entity ECA rules from [${rr.location}]")
        return numLoaded
    }

    boolean hasEecaRules(String entityName) { return eecaRulesByEntityName.get(entityName) != null }
    void runEecaRules(String entityName, Map fieldValues, String operation, boolean before) {
        ArrayList<EntityEcaRule> lst = (ArrayList<EntityEcaRule>) eecaRulesByEntityName.get(entityName)
        if (lst != null && lst.size() > 0) {
            // if Entity ECA rules disabled in ArtifactExecutionFacade, just return immediately
            // do this only if there are EECA rules to run, small cost in getEci, etc
            if (ecfi.getEci().artifactExecutionFacade.entityEcaDisabled()) return

            for (int i = 0; i < lst.size(); i++) {
                EntityEcaRule eer = (EntityEcaRule) lst.get(i)
                eer.runIfMatches(entityName, fieldValues, operation, before, ecfi.getEci())
            }
        }
    }

    void destroy() {
        Set<String> groupNames = this.datasourceFactoryByGroupMap.keySet()
        for (String groupName in groupNames) {
            EntityDatasourceFactory edf = this.datasourceFactoryByGroupMap.get(groupName)
            this.datasourceFactoryByGroupMap.put(groupName, null)
            edf.destroy()
        }
    }

    // used in tools screen
    void checkAllEntityTables(String groupName) {
        // TODO: load framework entities first, then component/mantle/etc entities for better FKs on first pass
        EntityDatasourceFactory edf = getDatasourceFactory(groupName)
        for (String entityName in getAllEntityNamesInGroup(groupName)) edf.checkAndAddTable(entityName)
    }

    Set<String> getAllEntityNames() { return getAllEntityNames(null) }
    Set<String> getAllEntityNames(String filterRegexp) {
        Map<String, List<String>> entityLocationCache = entityLocationSingleCache.get(entityLocSingleEntryName)
        if (entityLocationCache == null) entityLocationCache = loadAllEntityLocations()

        TreeSet<String> allNames = new TreeSet()
        // only add full entity names (with package in it, will always have at least one dot)
        // only include entities that have a non-empty List of locations in the cache (otherwise are invalid entities)
        for (Map.Entry<String, List<String>> entry in entityLocationCache.entrySet()) {
            String en = entry.key
            List<String> locList = entry.value
            if (en.contains(".") && locList != null && locList.size() > 0) {
                // Added (?i) to ignore the case and '*' in the starting and at ending to match if searched string is sub-part of entity name
                if (filterRegexp != null && !en.matches("(?i).*" + filterRegexp + ".*")) continue
                allNames.add(en)
            }
        }
        return allNames
    }

    Set<String> getAllNonViewEntityNames() {
        Set<String> allNames = getAllEntityNames()
        Set<String> nonViewNames = new TreeSet<>()
        for (String name in allNames) {
            EntityDefinition ed = getEntityDefinition(name)
            if (ed != null && !ed.isViewEntity) nonViewNames.add(name)
        }
        return nonViewNames
    }
    Set<String> getAllEntityNamesWithMaster() {
        Set<String> allNames = getAllEntityNames()
        Set<String> masterNames = new TreeSet<>()
        for (String name in allNames) {
            EntityDefinition ed
            try { ed = getEntityDefinition(name) } catch (EntityException e) { if (isTraceEnabled) logger.trace("Entity not found", e); continue; }
            if (ed != null && !ed.isViewEntity && ed.masterDefinitionMap) masterNames.add(name)
        }
        return masterNames
    }

    // used in tools screens
    List<Map> getAllEntityInfo(int levels, boolean excludeViewEntities) {
        Map<String, Map> entityInfoMap = [:]
        for (String entityName in getAllEntityNames()) {
            EntityDefinition ed = getEntityDefinition(entityName)
            boolean isView = ed.isViewEntity
            if (excludeViewEntities && isView) continue
            int lastDotIndex = 0
            for (int i = 0; i < levels; i++) lastDotIndex = entityName.indexOf(".", lastDotIndex+1)
            String name = lastDotIndex == -1 ? entityName : entityName.substring(0, lastDotIndex)
            Map curInfo = entityInfoMap.get(name)
            if (curInfo) {
                if (isView) CollectionUtilities.addToBigDecimalInMap("viewEntities", 1.0, curInfo)
                else CollectionUtilities.addToBigDecimalInMap("entities", 1.0, curInfo)
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
    boolean isEntityDefined(String entityName) {
        if (entityName == null) return false

        // Special treatment for framework entities, quick Map lookup (also faster than Cache get)
        if (frameworkEntityDefinitions.containsKey(entityName)) return true

        Map<String, List<String>> entityLocationCache = (Map<String, List<String>>) entityLocationSingleCache.get(entityLocSingleEntryName)
        if (entityLocationCache == null) entityLocationCache = loadAllEntityLocations()

        List<String> locList = (List<String>) entityLocationCache.get(entityName)
        return locList != null && locList.size() > 0
    }

    EntityDefinition getEntityDefinition(String entityName) {
        if (entityName == null) return null
        EntityDefinition ed = (EntityDefinition) frameworkEntityDefinitions.get(entityName)
        if (ed != null) return ed
        ed = (EntityDefinition) entityDefinitionCache.get(entityName)
        if (ed != null) return ed
        if (entityName.isEmpty()) return null
        if (entityName.startsWith("DataDocument.")) {
            return entityDataDocument.makeEntityDefinition(entityName.substring(entityName.indexOf(".") + 1))
        } else {
            return loadEntityDefinition(entityName)
        }
    }

    // used in tools screens
    void clearEntityDefinitionFromCache(String entityName) {
        EntityDefinition ed = (EntityDefinition) this.entityDefinitionCache.get(entityName)
        if (ed != null) {
            this.entityDefinitionCache.remove(ed.entityInfo.internalEntityName)
            this.entityDefinitionCache.remove(ed.fullEntityName)
            if (ed.entityInfo.shortAlias) this.entityDefinitionCache.remove(ed.entityInfo.shortAlias)
        }
    }

    // used in tools screens
    ArrayList<Map<String, Object>> getAllEntitiesInfo(String orderByField, String filterRegexp, boolean masterEntitiesOnly,
                                                      boolean excludeViewEntities) {
        if (masterEntitiesOnly) createAllAutoReverseManyRelationships()

        ArrayList<Map<String, Object>> eil = new ArrayList<>()
        for (String en in getAllEntityNames(filterRegexp)) {
            EntityDefinition ed = null
            try { ed = getEntityDefinition(en) } catch (EntityException e) { logger.warn("Problem finding entity definition", e) }
            if (ed == null) continue
            if (excludeViewEntities && ed.isViewEntity) continue

            if (masterEntitiesOnly) {
                if (!(ed.entityNode.attribute("has-dependents") == "true") || en.endsWith("Type") ||
                        en == "moqui.basic.Enumeration" || en == "moqui.basic.StatusItem") continue
                if (ed.getPkFieldNames().size() > 1) continue
            }

            eil.add([entityName:ed.entityInfo.internalEntityName, "package":ed.entityNode.attribute("package"),
                    isView:(ed.isViewEntity ? "true" : "false"), fullEntityName:ed.fullEntityName] as Map<String, Object>)
        }

        if (orderByField != null && !orderByField.isEmpty()) CollectionUtilities.orderMapList(eil, [orderByField])
        return eil
    }

    // used in tools screen (EntityDbView)
    ArrayList<Map<String, Object>> getAllEntityRelatedFields(String en, String orderByField, String dbViewEntityName) {
        // make sure reverse-one many relationships exist
        createAllAutoReverseManyRelationships()

        EntityValue dbViewEntity = dbViewEntityName ? find("moqui.entity.view.DbViewEntity").condition("dbViewEntityName", dbViewEntityName).one() : null

        ArrayList<Map<String, Object>> efl = new ArrayList<>()
        EntityDefinition ed = null
        try { ed = getEntityDefinition(en) } catch (EntityException e) { logger.warn("Problem finding entity definition", e) }
        if (ed == null) return efl

        // first get fields of the main entity
        for (String fn in ed.getAllFieldNames()) {
            MNode fieldNode = ed.getFieldNode(fn)

            boolean inDbView = false
            String functionName = null
            EntityValue aliasVal = find("moqui.entity.view.DbViewEntityAlias")
                .condition([dbViewEntityName:dbViewEntityName, entityAlias:"MASTER", fieldName:fn] as Map<String, Object>).one()
            if (aliasVal) {
                inDbView = true
                functionName = aliasVal.functionName
            }

            efl.add([entityName:en, fieldName:fn, type:fieldNode.attribute("type"), cardinality:"one",
                    inDbView:inDbView, functionName:functionName] as Map<String, Object>)
        }

        // loop through all related entities and get their fields too
        for (RelationshipInfo relInfo in ed.getRelationshipsInfo(false)) {
            //[type:relNode."@type", title:(relNode."@title"?:""), relatedEntityName:relNode."@related-entity-name",
            //        keyMap:keyMap, targetParameterMap:targetParameterMap, prettyName:prettyName]
            EntityDefinition red = null
            try { red = getEntityDefinition((String) relInfo.relatedEntityName) } catch (EntityException e) { logger.warn("Problem finding entity definition", e) }
            if (red == null) continue

            EntityValue dbViewEntityMember = null
            if (dbViewEntity) dbViewEntityMember = find("moqui.entity.view.DbViewEntityMember")
                    .condition([dbViewEntityName:dbViewEntityName, entityName:red.getFullEntityName()] as Map<String, Object>).one()

            for (String fn in red.getAllFieldNames()) {
                MNode fieldNode = red.getFieldNode(fn)
                boolean inDbView = false
                String functionName = null
                if (dbViewEntityMember) {
                    EntityValue aliasVal = find("moqui.entity.view.DbViewEntityAlias")
                        .condition([dbViewEntityName:dbViewEntityName, entityAlias:dbViewEntityMember.entityAlias, fieldName:fn]).one()
                    if (aliasVal) {
                        inDbView = true
                        functionName = aliasVal.functionName
                    }
                }
                efl.add([entityName:relInfo.relatedEntityName, fieldName:fn, type:fieldNode.attribute("type"),
                        cardinality:relInfo.type, title:relInfo.title, inDbView:inDbView, functionName:functionName] as Map<String, Object>)
            }
        }

        if (orderByField) CollectionUtilities.orderMapList(efl, [orderByField])
        return efl
    }

    MNode getDatabaseNode(String groupName) {
        MNode node = databaseNodeByGroupName.get(groupName)
        if (node != null) return node
        return findDatabaseNode(groupName)
    }
    protected MNode findDatabaseNode(String groupName) {
        MNode datasourceNode = getDatasourceNode(groupName)
        String databaseConfName = datasourceNode.attribute("database-conf-name")
        MNode node = ecfi.confXmlRoot.first("database-list")
                .first({ MNode it -> it.name == 'database' && it.attribute("name") == databaseConfName })
        databaseNodeByGroupName.put(groupName, node)
        return node
    }
    protected MNode getDatabaseNodeByConf(String confName) {
        return ecfi.confXmlRoot.first("database-list")
                .first({ MNode it -> it.name == 'database' && it.attribute("name") == confName })
    }
    String getDatabaseConfName(String entityName) {
        MNode dsNode = getDatasourceNode(getEntityGroupName(entityName))
        if (dsNode == null) return null
        return dsNode.attribute("database-conf-name")
    }

    MNode getDatasourceNode(String groupName) {
        MNode node = datasourceNodeByGroupName.get(groupName)
        if (node != null) return node
        return findDatasourceNode(groupName)
    }
    protected MNode findDatasourceNode(String groupName) {
        MNode dsNode = getEntityFacadeNode().first({ MNode it -> it.name == 'datasource' && it.attribute("group-name") == groupName })
        if (dsNode == null) dsNode = getEntityFacadeNode()
                .first({ MNode it -> it.name == 'datasource' && it.attribute("group-name") == defaultGroupName })
        dsNode.setSystemExpandAttributes(true)
        datasourceNodeByGroupName.put(groupName, dsNode)
        return dsNode
    }

    EntityDbMeta getEntityDbMeta() { return dbMeta != null ? dbMeta : (dbMeta = new EntityDbMeta(this)) }

    /** Get a JDBC Connection based on xa-properties configuration. The Conf Map should contain the default entity_ds properties
     * including entity_ds_db_conf, entity_ds_host, entity_ds_port, entity_ds_database, entity_ds_user, entity_ds_password */
    XAConnection getConfConnection(Map<String, String> confMap) {
        String confName = confMap.entity_ds_db_conf
        MNode databaseNode = getDatabaseNodeByConf(confName)
        MNode xaPropsNode = databaseNode.first("inline-jdbc")?.first("xa-properties")
        if (xaPropsNode == null) throw new IllegalArgumentException("Could not find database.inline-jdbc.xa-properties element for conf name ${confName}")

        String xaDsClassName = databaseNode.attribute("default-xa-ds-class")
        if (!xaDsClassName) throw new IllegalArgumentException("Could database conf ${confName} has no default-xa-ds-class attribute")
        XADataSource xaDs = (XADataSource) ecfi.classLoader.loadClass(xaDsClassName).newInstance()
        for (Map.Entry<String, String> attrEntry in xaPropsNode.attributes.entrySet()) {
            String propValue = ecfi.resourceFacade.expand(attrEntry.value, "", confMap)
            try {
                xaDs.putAt(attrEntry.key, propValue)
            } catch (GroovyCastException e) {
                if (isTraceEnabled) logger.trace("Cast failed, trying int", e)
                xaDs.putAt(attrEntry.key, propValue as int)
            }
        }

        return xaDs.getXAConnection(confMap.entity_ds_user, confMap.entity_ds_password)
    }
    // used in services
    int runSqlUpdateConf(CharSequence sql, Map<String, String> confMap) {
        // only do one DB meta data operation at a time; may lock above before checking for existence of something to make sure it doesn't get created twice
        int records = 0
        ecfi.transactionFacade.runRequireNew(30, "Error in DB meta data change", false, true, {
            XAConnection xacon = null
            Connection con = null
            Statement stmt = null
            try {
                xacon = getConfConnection(confMap)
                con = xacon.getConnection()
                stmt = con.createStatement()
                records = stmt.executeUpdate(sql.toString())
            } finally {
                if (stmt != null) stmt.close()
                if (con != null) con.close()
                if (xacon != null) xacon.close()
            }
        })
        return records
    }
    /* this needs more work, can't pass back ResultSet with Connection closed so need to somehow return Connection and ResultSet so both can be closed...
    ResultSet runSqlQueryConf(CharSequence sql, Map<String, String> confMap) {
        Connection con = null
        Statement stmt = null
        ResultSet rs = null
        try {
            con = getConfConnection(confMap)
            stmt = con.createStatement()
            rs = stmt.executeQuery(sql.toString())
        } finally {
            if (stmt != null) stmt.close()
            if (con != null) con.close()
        }
        return rs
    }
    */
    // used in services
    long runSqlCountConf(CharSequence from, CharSequence where, Map<String, String> confMap) {
        StringBuilder sqlSb = new StringBuilder("SELECT COUNT(*) FROM ").append(from).append(" WHERE ").append(where)
        XAConnection xacon = null
        Connection con = null
        Statement stmt = null
        ResultSet rs = null
        try {
            xacon = getConfConnection(confMap)
            con = xacon.getConnection()
            stmt = con.createStatement()
            rs = stmt.executeQuery(sqlSb.toString())
            if (rs.next()) return rs.getLong(1)
            return 0
        } finally {
            if (stmt != null) stmt.close()
            if (rs != null) rs.close()
            if (con != null) con.close()
            if (xacon != null) xacon.close()
        }
    }

    /* ========================= */
    /* Interface Implementations */
    /* ========================= */

    @Override
    EntityDatasourceFactory getDatasourceFactory(String groupName) {
        EntityDatasourceFactory edf = (EntityDatasourceFactory) datasourceFactoryByGroupMap.get(groupName)
        if (edf == null) edf = (EntityDatasourceFactory) datasourceFactoryByGroupMap.get(defaultGroupName)
        if (edf == null) throw new EntityException("Could not find EntityDatasourceFactory for entity group ${groupName}")
        return edf
    }
    List<Map<String, Object>> getDataSourcesInfo() {
        List<Map<String, Object>> dsiList = new LinkedList<>()
        for (String groupName in datasourceFactoryByGroupMap.keySet()) {
            EntityDatasourceFactory edf = datasourceFactoryByGroupMap.get(groupName)
            if (edf instanceof EntityDatasourceFactoryImpl) {
                EntityDatasourceFactoryImpl edfi = (EntityDatasourceFactoryImpl) edf
                DatasourceInfo dsi = edfi.dsi
                dsiList.add([group:groupName, uniqueName:dsi.uniqueName, database:dsi.database.attribute('name'), detail:dsi.dsDetails] as Map<String, Object>)
            } else {
                dsiList.add([group:groupName] as Map<String, Object>)
            }
        }
        return dsiList
    }
    String getDatasourceCloneName(String groupName) {
        String baseGroupName = groupName == null || groupName.isEmpty() ? defaultGroupName : groupName
        String groupPrefix = baseGroupName.concat('#')

        ArrayList<String> cloneGroupNames = new ArrayList<>(5)
        for (String curGroup in datasourceFactoryByGroupMap.keySet())
            if (curGroup.startsWith(groupPrefix)) cloneGroupNames.add(curGroup)

        int cloneNamesSize = cloneGroupNames.size()
        if (cloneNamesSize == 0) {
            return baseGroupName
        } else if (cloneNamesSize == 1) {
            // logger.warn("Using DB clone ${cloneGroupNames.get(0)} instead of ${groupName}")
            return cloneGroupNames.get(0)
        } else {
            return cloneGroupNames.get(ThreadLocalRandom.current().nextInt(cloneNamesSize))
        }
    }

    @Override EntityConditionFactory getConditionFactory() { return this.entityConditionFactory }
    EntityConditionFactoryImpl getConditionFactoryImpl() { return this.entityConditionFactory }

    @Override
    EntityValue makeValue(String entityName) {
        // don't check entityName empty, getEntityDefinition() does it
        EntityDefinition ed = getEntityDefinition(entityName)
        if (ed == null) throw new EntityException("No entity found with name ${entityName}")
        return ed.makeEntityValue()
    }

    @Override
    EntityFind find(String entityName) {
        // don't check entityName empty, getEntityDefinition() does it
        EntityDefinition ed = getEntityDefinition(entityName)
        if (ed == null) throw new EntityException("No entity found with name ${entityName}")
        if (ed.isDynamicView && entityName.startsWith("DataDocument.")) {
            // see if it happens to be a DataDocument and if so make a special find that has its conditions too
            // TODO: consider addition condition methods to EntityDynamicView and handling this lower level instead of here
            return entityDataDocument.makeDataDocumentFind(entityName.substring(entityName.indexOf(".") + 1))
        }
        return ed.makeEntityFind()
    }
    @Override
    EntityFind find(MNode node) {
        String entityName = node.attribute("entity-name")
        if (entityName != null && entityName.contains("\${")) entityName = ecfi.resourceFacade.expand(entityName, null)
        // don't check entityName empty, getEntityDefinition() does it
        EntityDefinition ed = getEntityDefinition(entityName)
        if (ed == null) throw new EntityException("No entity found with name ${entityName}")
        EntityFind ef
        if (ed.isDynamicView && entityName.startsWith("DataDocument.")) {
            // see if it happens to be a DataDocument and if so make a special find that has its conditions too
            // TODO: consider addition condition methods to EntityDynamicView and handling this lower level instead of here
            ef = entityDataDocument.makeDataDocumentFind(entityName.substring(entityName.indexOf(".") + 1))
        } else {
            ef = ed.makeEntityFind()
        }

        String cache = node.attribute("cache")
        if (cache != null && !cache.isEmpty()) { ef.useCache("true".equals(cache)) }
        String forUpdate = node.attribute("for-update")
        if (forUpdate != null && !forUpdate.isEmpty()) ef.forUpdate("true".equals(forUpdate))
        String distinct = node.attribute("distinct")
        if (distinct != null && !distinct.isEmpty()) ef.distinct("true".equals(distinct))
        String useClone = node.attribute("use-clone")
        if (useClone != null && !useClone.isEmpty()) ef.useClone("true".equals(useClone))
        String offset = node.attribute("offset")
        if (offset != null && !offset.isEmpty()) ef.offset(Integer.valueOf(offset))
        String limit = node.attribute("limit")
        if (limit != null && !limit.isEmpty()) ef.limit(Integer.valueOf(limit))
        for (MNode sf in node.children("select-field")) {
            String fieldToSelect = sf.attribute("field-name")
            if (fieldToSelect == null || fieldToSelect.isEmpty()) continue
            if (fieldToSelect.contains('${')) fieldToSelect = ecfi.resourceFacade.expandNoL10n(fieldToSelect, null)
            ef.selectField(fieldToSelect)
        }
        for (MNode ob in node.children("order-by")) ef.orderBy(ob.attribute("field-name"))

        if (node.hasChild("search-form-inputs")) {
            MNode sfiNode = node.first("search-form-inputs")
            String requireParameters = ecfi.resourceFacade.expand(sfiNode.attribute("require-parameters"), null)
            if ("true".equals(requireParameters)) ef.requireSearchFormParameters(true)

            boolean paginate = !"false".equals(sfiNode.attribute("paginate"))
            MNode defaultParametersNode = sfiNode.first("default-parameters")
            String inputFieldsMapName = sfiNode.attribute("input-fields-map")

            Map<String, Object> inf = inputFieldsMapName ? (Map<String, Object>) ecfi.resourceFacade.expression(inputFieldsMapName, "") : ecfi.getEci().context
            ef.searchFormMap(inf, defaultParametersNode?.attributes as Map<String, Object>, sfiNode.attribute("skip-fields"), sfiNode.attribute("default-order-by"), paginate)
        }

        // logger.warn("=== shouldCache ${this.entityName} ${shouldCache()}, limit=${this.limit}, offset=${this.offset}, useCache=${this.useCache}, getEntityDef().getUseCache()=${this.getEntityDef().getUseCache()}")
        EntityCondition mainCond = getConditionFactoryImpl().makeActionConditions(node, ef.shouldCache())
        if (mainCond != null) ef.condition(mainCond)

        if (node.hasChild("having-econditions")) {
            for (MNode havingCond in node.children("having-econditions"))
                ef.havingCondition(getConditionFactoryImpl().makeActionConditions(havingCond, ef.shouldCache()))
        }

        return ef
    }

    /** Simple, fast find by primary key; doesn't filter find based on authz; doesn't use TransactionCache
     * For cached queries this is about 50% faster (6M/s vs 4M/s) for non-cached queries only about 10% faster (500K vs 450K) */
    EntityValue fastFindOne(String entityName, Boolean useCache, boolean disableAuthz, Object... values) {
        ExecutionContextImpl ec = ecfi.getEci()
        ArtifactExecutionFacadeImpl aefi = ec.artifactExecutionFacade
        boolean enableAuthz = disableAuthz ? !aefi.disableAuthz() : false
        try {
            EntityDefinition ed = getEntityDefinition(entityName)
            if (ed == null) throw new EntityException("Entity not found with name ${entityName}")
            EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo
            FieldInfo[] pkFieldInfoArray = entityInfo.pkFieldInfoArray

            if (ed.isViewEntity || !entityInfo.isEntityDatasourceFactoryImpl) {
                if (logger.infoEnabled) logger.info("fastFindOne used with entity ${entityName} which is view entity (${ed.isViewEntity}) or not from EntityDatasourceFactoryImpl (${entityInfo.isEntityDatasourceFactoryImpl})")
                EntityFind ef = find(entityName)
                if (useCache) ef.useCache(true)
                if (disableAuthz) ef.disableAuthz()
                for (int i = 0; i < pkFieldInfoArray.length; i++) {
                    FieldInfo fi = (FieldInfo) pkFieldInfoArray[i]
                    Object fieldValue = values[i]
                    ef.condition(fi.name, fieldValue)
                }
                return ef.one()
            }

            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(ed.getFullEntityName(),
                    ArtifactExecutionInfo.AT_ENTITY, ArtifactExecutionInfo.AUTHZA_VIEW, "one")
            // really worth the overhead? if so change to handle singleCondField: .setParameters(simpleAndMap)
            aefi.pushInternal(aei, !ed.entityInfo.authorizeSkipView, false)

            try {
                boolean doCache = useCache != null ? (useCache.booleanValue() ? !entityInfo.neverCache : false) : "true".equals(entityInfo.useCache)

                boolean hasEmptyPk = false
                int pkSize = pkFieldInfoArray.length
                if (values.length != pkSize) throw new EntityException("Cannot do fastFindOne for entity ${entityName} with ${pkSize} primary key fields and ${values.length} values")
                EntityConditionImplBase whereCondition = (EntityConditionImplBase) null
                if (pkSize == 1) {
                    Object fieldValue = values[0]
                    if (ObjectUtilities.isEmpty(fieldValue)) {
                        hasEmptyPk = true
                    } else if (doCache) {
                        FieldInfo fi = (FieldInfo) pkFieldInfoArray[0]
                        whereCondition = new FieldValueCondition(fi.conditionField, EntityCondition.EQUALS, fieldValue)
                    }
                } else {
                    ListCondition listCond = doCache ? new ListCondition(null, EntityCondition.AND) : (ListCondition) null
                    for (int i = 0; i < pkSize; i++) {
                        Object fieldValue = values[i]
                        if (ObjectUtilities.isEmpty(fieldValue)) {
                            hasEmptyPk = true
                            break
                        }
                        if (doCache) {
                            FieldInfo fi = (FieldInfo) pkFieldInfoArray[i]
                            listCond.addCondition(new FieldValueCondition(fi.conditionField, EntityCondition.EQUALS, fieldValue))
                        }
                    }
                    if (doCache) whereCondition = listCond
                }
                // if any PK fields are null, for whatever reason in calling code, the result is null so no need to send to DB or cache or anything
                if (hasEmptyPk) return (EntityValue) null

                Cache<EntityCondition, EntityValueBase> entityOneCache = doCache ?
                        ed.getCacheOne(entityCache) : (Cache<EntityCondition, EntityValueBase>) null
                EntityValueBase cacheHit = doCache ? (EntityValueBase) entityOneCache.get(whereCondition) : (EntityValueBase) null

                EntityValueBase newEntityValue
                if (cacheHit != null) {
                    if (cacheHit instanceof EntityCache.EmptyRecord) newEntityValue = (EntityValueBase) null
                    else newEntityValue = cacheHit
                } else {
                    newEntityValue = fastFindOneExtended(ed, values)
                    // put it in whether null or not (already know cacheHit is null)
                    if (doCache) entityCache.putInOneCache(ed, whereCondition, newEntityValue, entityOneCache)
                }

                return newEntityValue
            } finally {
                // pop the ArtifactExecutionInfo
                aefi.pop(aei)
            }
        } finally {
            if (enableAuthz) aefi.enableAuthz()
        }
    }
    public EntityValueBase fastFindOneExtended(EntityDefinition ed, Object... values) throws EntityException {
        // table doesn't exist, just return null
        if (!ed.tableExistsDbMetaOnly()) return null

        FieldInfo[] fieldInfoArray = ed.entityInfo.allFieldInfoArray
        FieldInfo[] pkFieldInfoArray = ed.entityInfo.pkFieldInfoArray
        int pkSize = pkFieldInfoArray.length

        final StringBuilder sqlTopLevel = new StringBuilder(500)
        sqlTopLevel.append("SELECT ").append(ed.entityInfo.allFieldsSqlSelect)

        // FROM Clause
        sqlTopLevel.append(" FROM ")
        sqlTopLevel.append(ed.getFullTableName())

        // WHERE clause; whereCondition will always be FieldValueCondition or ListCondition with FieldValueCondition
        sqlTopLevel.append(" WHERE ")
        for (int i = 0; i < pkSize; i++) {
            FieldInfo fi = (FieldInfo) pkFieldInfoArray[i]
            // Object fieldValue = values[i]
            if (i > 0) sqlTopLevel.append(" AND ")
            sqlTopLevel.append(fi.getFullColumnName()).append(" = ?")
        }

        String finalSql = sqlTopLevel.toString()

        // run the SQL now that it is built
        EntityValueBase newEntityValue = (EntityValueBase) null
        Connection connection = (Connection) null
        PreparedStatement ps = (PreparedStatement) null
        ResultSet rs = (ResultSet) null
        try {
            connection = getConnection(ed.getEntityGroupName())
            ps = connection.prepareStatement(finalSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)
            for (int i = 0; i < pkSize; i++) {
                FieldInfo fi = (FieldInfo) pkFieldInfoArray[i]
                Object fieldValue = values[i]
                fi.setPreparedStatementValue(ps, i + 1, fieldValue, ed, this);
            }

            boolean queryStats = getQueryStats()
            long beforeQuery = queryStats ? System.nanoTime() : 0
            rs = ps.executeQuery()
            if (queryStats) saveQueryStats(ed, finalSql, System.nanoTime() - beforeQuery, false)

            if (rs.next()) {
                newEntityValue = new EntityValueImpl(ed, this)
                HashMap<String, Object> valueMap = newEntityValue.getValueMap()
                int size = fieldInfoArray.length;
                for (int i = 0; i < size; i++) {
                    FieldInfo fi = fieldInfoArray[i];
                    if (fi == null) break;
                    fi.getResultSetValue(rs, i + 1, valueMap, this)
                }
            }
        } catch (SQLException e) {
            throw new EntityException("Error finding value", e);
        } finally {
            try {
                if (ps != null) ps.close()
                if (rs != null) rs.close()
                if (connection != null) connection.close();
            } catch (SQLException sqle) { throw new EntityException("Error finding value", sqle); }
        }

        return newEntityValue;
    }

    final static Map<String, String> operationByMethod = [get:'find', post:'create', put:'store', patch:'update', delete:'delete']
    @Override
    Object rest(String operation, List<String> entityPath, Map parameters, boolean masterNameInPath) {
        if (operation == null || operation.length() == 0) throw new EntityException("Operation (method) must be specified")
        operation = operationByMethod.get(operation.toLowerCase()) ?: operation
        if (!(operation in ['find', 'create', 'store', 'update', 'delete']))
            throw new EntityException("Operation [${operation}] not supported, must be one of: get, post, put, patch, or delete for HTTP request methods or find, create, store, update, or delete for direct entity operations")

        if (entityPath == null || entityPath.size() == 0) throw new EntityException("No entity name or alias specified in path")

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
            if (masterName == null || masterName.length() == 0) {
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
        if (localPath.size() > 0) {
            for (String pkFieldName in firstEd.getPkFieldNames()) {
                String pkValue = localPath.remove(0)
                if (!ObjectUtilities.isEmpty(pkValue)) parameters.put(pkFieldName, pkValue)
                if (localPath.size() == 0) break
            }
        }

        EntityDefinition lastEd = firstEd

        // if there is still more in the path the next should be a relationship name or alias
        while (localPath) {
            String relationshipName = localPath.remove(0)
            RelationshipInfo relInfo = lastEd.getRelationshipInfoMap().get(relationshipName)
            if (relInfo == null) throw new EntityNotFoundException("No relationship found with name or alias [${relationshipName}] on entity [${lastEd.getShortAlias()?:''}:${lastEd.getFullEntityName()}]")

            String relEntityName = relInfo.relatedEntityName
            EntityDefinition relEd = relInfo.relatedEd
            if (relEd == null) throw new EntityNotFoundException("No entity found with name [${relEntityName}], related to entity [${lastEd.getShortAlias()?:''}:${lastEd.getFullEntityName()}] by relationship [${relationshipName}]")

            // TODO: How to handle more exotic relationships where they are not a dependent record, ie join on a field
            // TODO:     other than a PK field? Should we lookup interim records to get field values to lookup the final
            // TODO:     one? This would assume that all records exist along the path... need any variation for different
            // TODO:     operations?

            // if there are more path elements use one for each PK field of the entity
            if (localPath.size() > 0) {
                for (String pkFieldName in relEd.getPkFieldNames()) {
                    // do we already have a value for this PK field? if so skip it...
                    if (parameters.containsKey(pkFieldName)) continue

                    String pkValue = localPath.remove(0)
                    if (!ObjectUtilities.isEmpty(pkValue)) parameters.put(pkFieldName, pkValue)
                    if (localPath.size() == 0) break
                }
            }

            lastEd = relEd
        }

        // at this point we should have the entity we actually want to operate on, and all PK field values from the path
        if (operation == 'find') {
            if (lastEd.containsPrimaryKey(parameters)) {
                // if we have a full PK lookup by PK and return the single value
                Map<String, Object> pkValues = [:]
                lastEd.entityInfo.setFields(parameters, pkValues, false, null, true)

                if (masterName != null && masterName.length() > 0) {
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
                EntityFind ef = find(lastEd.fullEntityName).searchFormMap(parameters, null, null, null, false)
                // we don't want to go overboard with these requests, never do an unlimited find, if no limit use 100
                if (!ef.getLimit()) ef.limit(100)

                // support pagination, at least "X-Total-Count" header if find is paginated
                long count = ef.count()
                long pageIndex = ef.getPageIndex()
                long pageSize = ef.getPageSize()
                long pageMaxIndex = ((count - 1) as BigDecimal).divide(pageSize as BigDecimal, 0, RoundingMode.DOWN).longValue()
                long pageRangeLow = pageIndex * pageSize + 1
                long pageRangeHigh = (pageIndex * pageSize) + pageSize
                if (pageRangeHigh > count) pageRangeHigh = count

                parameters.put('xTotalCount', count)
                parameters.put('xPageIndex', pageIndex)
                parameters.put('xPageSize', pageSize)
                parameters.put('xPageMaxIndex', pageMaxIndex)
                parameters.put('xPageRangeLow', pageRangeLow)
                parameters.put('xPageRangeHigh', pageRangeHigh)

                if (masterName != null && masterName.length() > 0) {
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
            Map result = ecfi.serviceFacade.sync().name(operation, lastEd.fullEntityName).parameters(parameters).call()
            return result
        }
    }

    EntityList getValueListFromPlainMap(Map value, String entityName) {
        if (entityName == null || entityName.length() == 0) entityName = value."_entity"
        if (entityName == null || entityName.length() == 0) throw new EntityException("No entityName passed and no _entity field in value Map")

        EntityDefinition ed = getEntityDefinition(entityName)
        if (ed == null) throw new EntityNotFoundException("Not entity found with name ${entityName}")

        EntityList valueList = new EntityListImpl(this)
        addValuesFromPlainMapRecursive(ed, value, valueList, null)
        return valueList
    }
    void addValuesFromPlainMapRecursive(EntityDefinition ed, Map value, EntityList valueList, Map<String, Object> parentPks) {
        // add in all of the main entity's primary key fields, this is necessary for auto-generated, and to
        //     allow them to be left out of related records
        if (parentPks != null) {
            for (Map.Entry<String, Object> entry in parentPks.entrySet())
                if (!value.containsKey(entry.key)) value.put(entry.key, entry.value)
        }

        EntityValue newEntityValue = makeValue(ed.getFullEntityName())
        newEntityValue.setFields(value, true, null, null)
        valueList.add(newEntityValue)

        Map<String, Object> sharedPkMap = newEntityValue.getPrimaryKeys()
        if (parentPks != null) {
            for (Map.Entry<String, Object> entry in parentPks.entrySet())
                if (!sharedPkMap.containsKey(entry.key)) sharedPkMap.put(entry.key, entry.value)
        }

        // check parameters Map for relationships and other entities
        Map nonFieldEntries = ed.entityInfo.cloneMapRemoveFields(value, null)
        for (Map.Entry entry in nonFieldEntries.entrySet()) {
            Object relParmObj = entry.getValue()
            if (relParmObj == null) continue
            // if the entry is not a Map or List ignore it, we're only looking for those
            if (!(relParmObj instanceof Map) && !(relParmObj instanceof List)) continue

            String entryName = (String) entry.getKey()
            if (parentPks != null && parentPks.containsKey(entryName)) continue
            if (EntityAutoServiceRunner.otherFieldsToSkip.contains(entryName)) continue

            EntityDefinition subEd = null
            Map<String, Object> pkMap = null
            RelationshipInfo relInfo = ed.getRelationshipInfo(entryName)
            if (relInfo != null) {
                if (!relInfo.mutable) continue
                subEd = relInfo.relatedEd
                // this is a relationship so add mapped key fields to the parentPks if any field names are different
                pkMap = new HashMap<>(sharedPkMap)
                pkMap.putAll(relInfo.getTargetParameterMap(sharedPkMap))
            } else if (isEntityDefined(entryName)) {
                subEd = getEntityDefinition(entryName)
                pkMap = sharedPkMap
            }
            if (subEd == null) continue

            boolean isEntityValue = relParmObj instanceof EntityValue
            if (relParmObj instanceof Map && !isEntityValue) {
                addValuesFromPlainMapRecursive(subEd, (Map) relParmObj, valueList, pkMap)
            } else if (relParmObj instanceof List) {
                for (Object relParmEntry in relParmObj) {
                    if (relParmEntry instanceof Map) {
                        addValuesFromPlainMapRecursive(subEd, (Map) relParmEntry, valueList, pkMap)
                    } else {
                        logger.warn("In entity values from plain map for entity ${ed.getFullEntityName()} found list for sub-object ${entryName} with a non-Map entry: ${relParmEntry}")
                    }
                }
            } else {
                if (isEntityValue) {
                    if (logger.isTraceEnabled()) logger.trace("In entity values from plain map for entity ${ed.getFullEntityName()} found sub-object ${entryName} which is not a Map or List: ${relParmObj}")
                } else {
                    logger.warn("In entity values from plain map for entity ${ed.getFullEntityName()} found sub-object ${entryName} which is not a Map or List: ${relParmObj}")
                }
            }
        }
    }


    @Override
    EntityListIterator sqlFind(String sql, List<Object> sqlParameterList, String entityName, List<String> fieldList) {
        if (sqlParameterList == null || fieldList == null || sqlParameterList.size() != fieldList.size())
            throw new BaseArtifactException("For sqlFind sqlParameterList and fieldList must not be null and must be the same size")
        EntityDefinition ed = this.getEntityDefinition(entityName)
        this.entityDbMeta.checkTableRuntime(ed)

        Connection con = getConnection(getEntityGroupName(entityName))
        PreparedStatement ps
        try {
            FieldInfo[] fiArray = new FieldInfo[fieldList.size()]
            int fiArrayIndex = 0
            for (String fieldName in fieldList) {
                FieldInfo fi = ed.getFieldInfo(fieldName)
                if (fi == null) throw new BaseArtifactException("Field ${fieldName} not found for entity ${entityName}")
                fiArray[fiArrayIndex] = fi
                fiArrayIndex++
            }

            // create the PreparedStatement
            ps = con.prepareStatement(sql, ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY)
            // set the parameter values
            int paramIndex = 1
            for (Object parameterValue in sqlParameterList) {
                FieldInfo fi = (FieldInfo) fiArray[paramIndex - 1]
                fi.setPreparedStatementValue(ps, paramIndex, parameterValue, ed, this)
                paramIndex++
            }
            // do the actual query
            long timeBefore = System.currentTimeMillis()
            ResultSet rs = ps.executeQuery()
            if (logger.traceEnabled) logger.trace("Executed query with SQL [${sql}] and parameters [${sqlParameterList}] in [${(System.currentTimeMillis()-timeBefore)/1000}] seconds")
            // make and return the eli
            EntityListIterator eli = new EntityListIteratorImpl(con, rs, ed, fiArray, this, null, null, null)
            return eli
        } catch (SQLException e) {
            throw new EntityException("SQL Exception with statement:" + sql + "; " + e.toString(), e)
        }
    }

    @Override
    ArrayList<Map> getDataDocuments(String dataDocumentId, EntityCondition condition, Timestamp fromUpdateStamp,
                                Timestamp thruUpdatedStamp) {
        return entityDataDocument.getDataDocuments(dataDocumentId, condition, fromUpdateStamp, thruUpdatedStamp)
    }

    @Override
    ArrayList<Map> getDataFeedDocuments(String dataFeedId, Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp) {
        return entityDataFeed.getFeedDocuments(dataFeedId, fromUpdateStamp, thruUpdatedStamp)
    }

    void tempSetSequencedIdPrimary(String seqName, long nextSeqNum, long bankSize) {
        long[] bank = new long[2]
        bank[0] = nextSeqNum
        bank[1] = nextSeqNum + bankSize
        entitySequenceBankCache.put(seqName, bank)
    }
    void tempResetSequencedIdPrimary(String seqName) {
        entitySequenceBankCache.put(seqName, null)
    }

    @Override
    String sequencedIdPrimary(String seqName, Long staggerMax, Long bankSize) {
        try {
            // is the seqName an entityName?
            if (isEntityDefined(seqName)) {
                EntityDefinition ed = getEntityDefinition(seqName)
                if (ed.entityInfo.sequencePrimaryUseUuid) return UUID.randomUUID().toString()
            }
        } catch (EntityException e) {
            // do nothing, just means seqName is not an entity name
            if (isTraceEnabled) logger.trace("Ignoring exception for entity not found: ${e.toString()}")
        }
        // fall through to default to the db sequenced ID
        long staggerMaxPrim = staggerMax != null ? staggerMax.longValue() : 0L
        long bankSizePrim = (bankSize != null && bankSize.longValue() > 0) ? bankSize.longValue() : defaultBankSize
        return dbSequencedIdPrimary(seqName, staggerMaxPrim, bankSizePrim)
    }

    String sequencedIdPrimaryEd(EntityDefinition ed) {
        EntityJavaUtil.EntityInfo entityInfo = ed.entityInfo
        try {
            // is the seqName an entityName?
            if (entityInfo.sequencePrimaryUseUuid) return UUID.randomUUID().toString()
        } catch (EntityException e) {
            // do nothing, just means seqName is not an entity name
            if (isTraceEnabled) logger.trace("Ignoring exception for entity not found: ${e.toString()}")
        }
        // fall through to default to the db sequenced ID
        return dbSequencedIdPrimary(ed.getFullEntityName(), entityInfo.sequencePrimaryStagger, entityInfo.sequenceBankSize)
    }

    protected final static long defaultBankSize = 50L
    protected Lock getDbSequenceLock(String seqName) {
        Lock oldLock, dbSequenceLock = dbSequenceLocks.get(seqName)
        if (dbSequenceLock == null) {
            dbSequenceLock = new ReentrantLock()
            oldLock = dbSequenceLocks.putIfAbsent(seqName, dbSequenceLock)
            if (oldLock != null) return oldLock
        }
        return dbSequenceLock
    }
    protected String dbSequencedIdPrimary(String seqName, long staggerMax, long bankSize) {

        // TODO: find some way to get this running non-synchronized for performance reasons (right now if not
        // TODO:     synchronized the forUpdate won't help if the record doesn't exist yet, causing errors in high
        // TODO:     traffic creates; is it creates only?)

        Lock dbSequenceLock = getDbSequenceLock(seqName)
        dbSequenceLock.lock()

        // NOTE: simple approach with forUpdate, not using the update/select "ethernet" approach used in OFBiz; consider
        // that in the future if there are issues with this approach

        try {
            // first get a bank if we don't have one already
            long[] bank = (long[]) entitySequenceBankCache.get(seqName)
            if (bank == null || bank[0] > bank[1]) {
                if (bank == null) {
                    bank = new long[2]
                    bank[0] = 0
                    bank[1] = -1
                    entitySequenceBankCache.put(seqName, bank)
                }

                ecfi.transactionFacade.runRequireNew(null, "Error getting primary sequenced ID", true, true, {
                    ArtifactExecutionFacadeImpl aefi = ecfi.getEci().artifactExecutionFacade
                    boolean enableAuthz = !aefi.disableAuthz()
                    try {
                        EntityValue svi = find("moqui.entity.SequenceValueItem").condition("seqName", seqName)
                                .useCache(false).forUpdate(true).one()
                        if (svi == null) {
                            svi = makeValue("moqui.entity.SequenceValueItem")
                            svi.set("seqName", seqName)
                            // a new tradition: start sequenced values at one hundred thousand instead of ten thousand
                            bank[0] = 100000L
                            bank[1] = bank[0] + bankSize
                            svi.set("seqNum", bank[1])
                            svi.create()
                        } else {
                            Long lastSeqNum = svi.getLong("seqNum")
                            bank[0] = (lastSeqNum > bank[0] ? lastSeqNum + 1L : bank[0])
                            bank[1] = bank[0] + bankSize
                            svi.set("seqNum", bank[1])
                            svi.update()
                        }
                    } finally {
                        if (enableAuthz) aefi.enableAuthz()
                    }
                })
            }

            long seqNum = bank[0]
            if (staggerMax > 1L) {
                long stagger = Math.round(Math.random() * staggerMax)
                bank[0] = seqNum + stagger
                // NOTE: if bank[0] > bank[1] because of this just leave it and the next time we try to get a sequence
                //     value we'll get one from a new bank
            } else {
                bank[0] = seqNum + 1L
            }

            return sequencedIdPrefix != null ? sequencedIdPrefix + seqNum : seqNum
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

    @Override
    String getEntityGroupName(String entityName) {
        String entityGroupName = (String) entityGroupNameMap.get(entityName)
        if (entityGroupName != null) return entityGroupName
        EntityDefinition ed
        // for entity group name just ignore EntityException on getEntityDefinition
        try { ed = getEntityDefinition(entityName) } catch (EntityException e) { return null }
        // may happen if all entity names includes a DB view entity or other that doesn't really exist
        if (ed == null) return null
        entityGroupName = ed.getEntityGroupName()
        entityGroupNameMap.put(entityName, entityGroupName)
        return entityGroupName
    }

    @Override Connection getConnection(String groupName) { return getConnection(groupName, false) }
    @Override Connection getConnection(String groupName, boolean useClone) {
        TransactionFacadeImpl tfi = ecfi.transactionFacade
        if (!tfi.isTransactionOperable()) throw new EntityException("Cannot get connection, transaction not in operable status (${tfi.getStatusString()})")

        String groupToUse = useClone ? getDatasourceCloneName(groupName) : groupName

        Connection stashed = tfi.getTxConnection(groupToUse)
        if (stashed != null) return stashed

        EntityDatasourceFactory edf = getDatasourceFactory(groupToUse)
        DataSource ds = edf.getDataSource()
        if (ds == null) throw new EntityException("Cannot get JDBC Connection for group-name [${groupToUse}] because it has no DataSource")
        Connection newCon
        if (ds instanceof XADataSource) {
            newCon = tfi.enlistConnection(((XADataSource) ds).getXAConnection())
        } else {
            newCon = ds.getConnection()
        }
        if (newCon != null) newCon = tfi.stashTxConnection(groupToUse, newCon)
        return newCon
    }

    @Override EntityDataLoader makeDataLoader() { return new EntityDataLoaderImpl(this) }
    @Override EntityDataWriter makeDataWriter() { return new EntityDataWriterImpl(this) }

    @Override SimpleEtl.Loader makeEtlLoader() { return new EtlLoader(this) }
    static class EtlLoader implements SimpleEtl.Loader {
        private boolean beganTransaction = false
        private EntityFacadeImpl efi
        private boolean useTryInsert = false, dummyFks = false
        EtlLoader(EntityFacadeImpl efi) { this.efi = efi }
        EtlLoader useTryInsert() { useTryInsert = true; return this }
        EtlLoader dummyFks() { dummyFks = true; return this }

        @Override void init(Integer timeout) {
            if (!efi.ecfi.transactionFacade.isTransactionActive()) beganTransaction = efi.ecfi.transactionFacade.begin(timeout)
        }
        @Override void load(SimpleEtl.Entry entry) throws Exception {
            String entityName = entry.getEtlType()
            if (!efi.isEntityDefined(entityName)) {
                logger.info("Tried to load ETL entry with invalid entity name " + entityName)
                return
            }
            EntityDefinition ed = efi.getEntityDefinition(entityName)
            if (ed == null) throw new BaseArtifactException("Could not find entity ${entityName}")
            // NOTE: the following uses the same pattern as EntityDataLoaderImpl.LoadValueHandler
            if (dummyFks || useTryInsert) {
                EntityValue curValue = ed.makeEntityValue()
                curValue.setAll(entry.getEtlValues())
                if (useTryInsert) {
                    try {
                        curValue.create()
                    } catch (EntityException ce) {
                        if (logger.isTraceEnabled()) logger.trace("Insert failed, trying update (${ce.toString()})")
                        boolean noFksMissing = true
                        if (dummyFks) noFksMissing = curValue.checkFks(true)
                        // retry, then if this fails we have a real error so let the exception fall through
                        // if there were no FKs missing then just do an update, if there were that may have been the error so createOrUpdate
                        if (noFksMissing) {
                            try {
                                curValue.update()
                            } catch (EntityException ue) {
                                logger.error("Error in update after attempt to create (tryInsert), here is the create error: ", ce)
                                throw ue
                            }
                        } else {
                            curValue.createOrUpdate()
                        }
                    }
                } else {
                    if (dummyFks) curValue.checkFks(true)
                    curValue.createOrUpdate()
                }
            } else {
                Map<String, Object> results = new HashMap()
                EntityAutoServiceRunner.storeEntity(efi.ecfi.getEci(), ed, entry.getEtlValues(), results, null)
                if (results.size() > 0) entry.getEtlValues().putAll(results)
            }
        }
        @Override void complete(SimpleEtl etl) {
            if (etl.hasError()) {
                efi.ecfi.transactionFacade.rollback(beganTransaction, "Error in ETL load", etl.getSingleErrorCause())
            } else if (beganTransaction) {
                efi.ecfi.transactionFacade.commit()
            }
        }
    }

    @Override
    EntityValue makeValue(Element element) {
        if (!element) return null

        String entityName = element.getTagName()
        if (entityName.indexOf('-') > 0) entityName = entityName.substring(entityName.indexOf('-') + 1)
        if (entityName.indexOf(':') > 0) entityName = entityName.substring(entityName.indexOf(':') + 1)

        EntityValueImpl newValue = (EntityValueImpl) makeValue(entityName)
        EntityDefinition ed = newValue.getEntityDefinition()

        for (String fieldName in ed.getAllFieldNames()) {
            String attrValue = element.getAttribute(fieldName)
            if (attrValue) {
                newValue.setString(fieldName, attrValue)
            } else {
                org.w3c.dom.NodeList seList = element.getElementsByTagName(fieldName)
                Element subElement = seList.getLength() > 0 ? (Element) seList.item(0) : null
                if (subElement) newValue.setString(fieldName, StringUtilities.elementValue(subElement))
            }
        }

        return newValue
    }

    /* =============== */
    /* Utility Methods */
    /* =============== */

    protected Map<String, Map<String, String>> javaTypeByGroup = [:]
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

        MNode databaseNode = this.getDatabaseNode(groupName)
        MNode databaseTypeNode = databaseNode ?
                databaseNode.first({ MNode it -> it.name == "database-type" && it.attribute('type') == fieldType }) : null
        String javaType = databaseTypeNode?.attribute("java-type")
        if (!javaType) {
            MNode databaseListNode = ecfi.confXmlRoot.first("database-list")
            MNode dictionaryTypeNode = databaseListNode.first({ MNode it -> it.name == "dictionary-type" && it.attribute('type') == fieldType })
            javaType = dictionaryTypeNode?.attribute("java-type")
            if (!javaType) throw new EntityException("Could not find Java type for field type [${fieldType}] on entity [${ed.getFullEntityName()}]")
        }
        javaTypeMap.put(fieldType, javaType)
        return javaType
    }

    protected Map<String, Map<String, String>> sqlTypeByGroup = [:]
    protected String getFieldSqlType(String fieldType, EntityDefinition ed) {
        String groupName = ed.getEntityGroupName()
        Map<String, String> sqlTypeMap = (Map<String, String>) sqlTypeByGroup.get(groupName)
        if (sqlTypeMap != null) {
            String st = (String) sqlTypeMap.get(fieldType)
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

        MNode databaseNode = this.getDatabaseNode(groupName)
        MNode databaseTypeNode = databaseNode ?
                databaseNode.first({ MNode it -> it.name == "database-type" && it.attribute('type') == fieldType }) : null
        String sqlType = databaseTypeNode?.attribute("sql-type")
        if (!sqlType) {
            MNode databaseListNode = ecfi.confXmlRoot.first("database-list")
            MNode dictionaryTypeNode = databaseListNode
                    .first({ MNode it -> it.name == "dictionary-type" && it.attribute('type') == fieldType })
            sqlType = dictionaryTypeNode?.attribute("default-sql-type")
            if (!sqlType) throw new EntityException("Could not find SQL type for field type [${fieldType}] on entity [${ed.getFullEntityName()}]")
        }
        sqlTypeMap.put(fieldType, sqlType)
        return sqlType

    }

    /** For pretty-print of field values based on field type */
    String formatFieldString(String entityName, String fieldName, String value) {
        if (value == null || value.isEmpty()) return ""
        EntityDefinition ed = getEntityDefinition(entityName)
        if (ed == null) return value
        FieldInfo fi = ed.getFieldInfo(fieldName)
        if (fi == null) return value
        String outVal = value
        if (fi.typeValue == 2) {
            if (value.matches("\\d*")) {
                // date-time with only digits, ms since epoch value
                outVal = ecfi.l10n.format(new Timestamp(Long.parseLong(value)), null)
            }
        } else if (fi.type.startsWith("currency-")) {
            outVal = ecfi.l10n.format(new BigDecimal(value), "#,##0.00#")
        }
        // logger.warn("formatFieldString ${entityName}:${fieldName} value ${value} outVal ${outVal}")
        return outVal
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
    static int getJavaTypeInt(String javaType) {
        Integer typeInt = (Integer) javaIntTypeMap.get(javaType)
        if (typeInt == null) throw new EntityException("Java type " + javaType + " not supported for entity fields")
        return typeInt
    }

    final Map<String, EntityJavaUtil.QueryStatsInfo> queryStatsInfoMap = new HashMap<>()
    void saveQueryStats(EntityDefinition ed, String sql, long queryTime, boolean isError) {
        EntityJavaUtil.QueryStatsInfo qsi = queryStatsInfoMap.get(sql)
        if (qsi == null) {
            qsi = new EntityJavaUtil.QueryStatsInfo(ed.getFullEntityName(), sql)
            queryStatsInfoMap.put(sql, qsi)
        }
        qsi.countHit(this, queryTime, isError)
    }
    ArrayList<Map<String, Object>> getQueryStatsList(String orderByField, String entityFilter, String sqlFilter) {
        ArrayList<Map<String, Object>> qsl = new ArrayList<>(queryStatsInfoMap.size())
        boolean hasEntityFilter = entityFilter != null && entityFilter.length() > 0
        boolean hasSqlFilter = sqlFilter != null && sqlFilter.length() > 0
        for (EntityJavaUtil.QueryStatsInfo qsi in queryStatsInfoMap.values()) {
            if (hasEntityFilter && !qsi.entityName.matches("(?i).*" + entityFilter + ".*")) continue
            if (hasSqlFilter && !qsi.sql.matches("(?i).*" + sqlFilter + ".*")) continue
            qsl.add(qsi.makeDisplayMap())
        }
        if (orderByField) CollectionUtilities.orderMapList(qsl, [orderByField])
        return qsl
    }
    void clearQueryStats() { queryStatsInfoMap.clear() }
}
