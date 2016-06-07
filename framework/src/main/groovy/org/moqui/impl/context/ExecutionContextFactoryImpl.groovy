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
package org.moqui.impl.context

import groovy.transform.CompileStatic
import org.apache.shiro.SecurityUtils
import org.apache.shiro.authc.credential.CredentialsMatcher
import org.apache.shiro.authc.credential.HashedCredentialsMatcher
import org.apache.shiro.config.IniSecurityManagerFactory
import org.apache.shiro.crypto.hash.SimpleHash

import org.moqui.BaseException
import org.moqui.context.*
import org.moqui.entity.EntityDataLoader
import org.moqui.entity.EntityFacade
import org.moqui.entity.EntityValue
import org.moqui.impl.StupidClassLoader
import org.moqui.impl.StupidJavaUtilities
import org.moqui.impl.StupidUtilities
import org.moqui.impl.StupidWebUtilities
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.reference.UrlResourceReference
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.EntityValueBase
import org.moqui.impl.screen.ScreenFacadeImpl
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.screen.ScreenFacade
import org.moqui.service.ServiceFacade
import org.moqui.util.MNode
import org.moqui.util.SystemBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarFile

@CompileStatic
class ExecutionContextFactoryImpl implements ExecutionContextFactory {
    protected final static Logger logger = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class)
    
    private boolean destroyed = false
    
    protected String runtimePath
    @SuppressWarnings("GrFinalVariableAccess")
    protected final String runtimeConfPath
    @SuppressWarnings("GrFinalVariableAccess")
    protected final MNode confXmlRoot
    protected MNode serverStatsNode

    protected StupidClassLoader cachedClassLoader
    protected InetAddress localhostAddress = null

    protected LinkedHashMap<String, ComponentInfo> componentInfoMap = new LinkedHashMap<>()
    protected final ThreadLocal<ExecutionContextImpl> activeContext = new ThreadLocal<>()
    protected final LinkedHashMap<String, ToolFactory> toolFactoryMap = new LinkedHashMap<>()

    protected final Map<String, EntityFacadeImpl> entityFacadeByTenantMap = new HashMap<>()
    protected final Map<String, WebappInfo> webappInfoMap = new HashMap<>()
    protected final List<NotificationMessageListener> registeredNotificationMessageListeners = []

    protected final Map<String, ArtifactStatsInfo> artifactStatsInfoByType = new HashMap<>()
    protected final Map<ArtifactExecutionInfo.ArtifactType, Boolean> artifactTypeAuthzEnabled =
            new EnumMap<>(ArtifactExecutionInfo.ArtifactType.class)
    protected final Map<ArtifactExecutionInfo.ArtifactType, Boolean> artifactTypeTarpitEnabled =
            new EnumMap<>(ArtifactExecutionInfo.ArtifactType.class)

    // Some direct-cached values for better performance
    protected String skipStatsCond
    protected Integer hitBinLengthMillis
    // protected Map<String, Boolean> artifactPersistHitByTypeAndSub = new HashMap<>()
    protected Map<ArtifactExecutionInfo.ArtifactType, Boolean> artifactPersistHitByTypeEnum =
            new EnumMap<>(ArtifactExecutionInfo.ArtifactType.class)
    // protected Map<String, Boolean> artifactPersistBinByTypeAndSub = new HashMap<>()
    protected Map<ArtifactExecutionInfo.ArtifactType, Boolean> artifactPersistBinByTypeEnum =
            new EnumMap<>(ArtifactExecutionInfo.ArtifactType.class)

    /** The SecurityManager for Apache Shiro */
    protected org.apache.shiro.mgt.SecurityManager internalSecurityManager

    // ======== Permanent Delegated Facades ========
    @SuppressWarnings("GrFinalVariableAccess")
    protected final CacheFacadeImpl cacheFacade
    @SuppressWarnings("GrFinalVariableAccess")
    protected final LoggerFacadeImpl loggerFacade
    @SuppressWarnings("GrFinalVariableAccess")
    protected final ResourceFacadeImpl resourceFacade
    @SuppressWarnings("GrFinalVariableAccess")
    protected final ScreenFacadeImpl screenFacade
    @SuppressWarnings("GrFinalVariableAccess")
    protected final ServiceFacadeImpl serviceFacade
    @SuppressWarnings("GrFinalVariableAccess")
    protected final TransactionFacadeImpl transactionFacade

    /** The main worker pool for services, running async closures and runnables, etc */
    @SuppressWarnings("GrFinalVariableAccess")
    final ExecutorService workerPool

    /**
     * This constructor gets runtime directory and conf file location from a properties file on the classpath so that
     * it can initialize on its own. This is the constructor to be used by the ServiceLoader in the Moqui.java file,
     * or by init methods in a servlet or context filter or OSGi component or Spring component or whatever.
     */
    ExecutionContextFactoryImpl() {
        long initStartTime = System.currentTimeMillis()

        // get the MoquiInit.properties file
        Properties moquiInitProperties = new Properties()
        URL initProps = this.class.getClassLoader().getResource("MoquiInit.properties")
        if (initProps != null) { InputStream is = initProps.openStream(); moquiInitProperties.load(is); is.close(); }

        // if there is a system property use that, otherwise from the properties file
        runtimePath = System.getProperty("moqui.runtime")
        if (!runtimePath) runtimePath = moquiInitProperties.getProperty("moqui.runtime")
        if (!runtimePath) throw new IllegalArgumentException("No moqui.runtime property found in MoquiInit.properties or in a system property (with: -Dmoqui.runtime=... on the command line)")
        if (runtimePath.endsWith("/")) runtimePath = runtimePath.substring(0, runtimePath.length()-1)

        // check the runtime directory via File
        File runtimeFile = new File(runtimePath)
        if (runtimeFile.exists()) { runtimePath = runtimeFile.getCanonicalPath() }
        else { throw new IllegalArgumentException("The moqui.runtime path [${runtimePath}] was not found.") }

        // get the moqui configuration file path
        String confPartialPath = System.getProperty("moqui.conf")
        if (!confPartialPath) confPartialPath = moquiInitProperties.getProperty("moqui.conf")
        if (!confPartialPath) throw new IllegalArgumentException("No moqui.conf property found in MoquiInit.properties or in a system property (with: -Dmoqui.conf=... on the command line)")

        String confFullPath
        if (confPartialPath.startsWith("/")) {
            confFullPath = confPartialPath
        } else {
            confFullPath = runtimePath + "/" + confPartialPath
        }
        // setup the confFile
        File confFile = new File(confFullPath)
        if (confFile.exists()) {
            runtimeConfPath = confFullPath
        } else {
            runtimeConfPath = null
            throw new IllegalArgumentException("The moqui.conf path [${confFullPath}] was not found.")
        }

        // initialize all configuration, get various conf files merged and load components
        MNode runtimeConfXmlRoot = MNode.parse(confFile)
        MNode baseConfigNode = initBaseConfig(runtimeConfXmlRoot)
        // init components before initConfig() so component configuration files can be incorporated
        initComponents(baseConfigNode)
        // init the configuration (merge from component and runtime conf files)
        confXmlRoot = initConfig(baseConfigNode, runtimeConfXmlRoot)

        workerPool = makeWorkerPool()
        preFacadeInit()

        // this init order is important as some facades will use others
        cacheFacade = new CacheFacadeImpl(this)
        logger.info("Cache Facade initialized")
        loggerFacade = new LoggerFacadeImpl(this)
        // logger.info("Logger Facade initialized")
        resourceFacade = new ResourceFacadeImpl(this)
        logger.info("Resource Facade initialized")

        transactionFacade = new TransactionFacadeImpl(this)
        logger.info("Transaction Facade initialized")
        // always init the EntityFacade for tenantId DEFAULT
        initEntityFacade("DEFAULT")
        serviceFacade = new ServiceFacadeImpl(this)
        logger.info("Service Facade initialized")
        screenFacade = new ScreenFacadeImpl(this)
        logger.info("Screen Facade initialized")

        postFacadeInit()

        logger.info("Execution Context Factory initialized in ${(System.currentTimeMillis() - initStartTime)/1000} seconds")
    }

    /** This constructor takes the runtime directory path and conf file path directly. */
    ExecutionContextFactoryImpl(String runtimePathParm, String confPathParm) {
        long initStartTime = System.currentTimeMillis()

        // setup the runtimeFile
        File runtimeFile = new File(runtimePathParm)
        if (!runtimeFile.exists()) throw new IllegalArgumentException("The moqui.runtime path [${runtimePathParm}] was not found.")

        // setup the confFile
        if (runtimePathParm.endsWith('/')) runtimePathParm = runtimePathParm.substring(0, runtimePathParm.length()-1)
        if (confPathParm.startsWith('/')) confPathParm = confPathParm.substring(1)
        String confFullPath = runtimePathParm + '/' + confPathParm
        File confFile = new File(confFullPath)
        if (!confFile.exists()) throw new IllegalArgumentException("The moqui.conf path [${confFullPath}] was not found.")

        runtimePath = runtimePathParm
        runtimeConfPath = confFullPath

        // initialize all configuration, get various conf files merged and load components
        MNode runtimeConfXmlRoot = MNode.parse(confFile)
        MNode baseConfigNode = initBaseConfig(runtimeConfXmlRoot)
        // init components before initConfig() so component configuration files can be incorporated
        initComponents(baseConfigNode)
        // init the configuration (merge from component and runtime conf files)
        confXmlRoot = initConfig(baseConfigNode, runtimeConfXmlRoot)

        workerPool = makeWorkerPool()
        preFacadeInit()

        // this init order is important as some facades will use others
        cacheFacade = new CacheFacadeImpl(this)
        logger.info("Cache Facade initialized")
        loggerFacade = new LoggerFacadeImpl(this)
        // logger.info("LoggerFacadeImpl initialized")
        resourceFacade = new ResourceFacadeImpl(this)
        logger.info("Resource Facade initialized")

        transactionFacade = new TransactionFacadeImpl(this)
        logger.info("Transaction Facade initialized")
        // always init the EntityFacade for tenantId DEFAULT
        initEntityFacade("DEFAULT")
        serviceFacade = new ServiceFacadeImpl(this)
        logger.info("Service Facade initialized")
        screenFacade = new ScreenFacadeImpl(this)
        logger.info("Screen Facade initialized")

        postFacadeInit()

        logger.info("Execution Context Factory initialized in ${(System.currentTimeMillis() - initStartTime)/1000} seconds")
    }

    @Override
    void postInit() {
        this.serviceFacade.postInit()
    }

    protected MNode initBaseConfig(MNode runtimeConfXmlRoot) {
        // always set the full moqui.runtime, moqui.conf system properties for use in various places
        System.setProperty("moqui.runtime", runtimePath)
        System.setProperty("moqui.conf", runtimeConfPath)

        logger.info("Initializing Moqui ExecutionContextFactoryImpl\n - runtime directory: ${this.runtimePath}\n - runtime config:    ${this.runtimeConfPath}")

        URL defaultConfUrl = this.class.getClassLoader().getResource("MoquiDefaultConf.xml")
        if (!defaultConfUrl) throw new IllegalArgumentException("Could not find MoquiDefaultConf.xml file on the classpath")
        MNode newConfigXmlRoot = MNode.parse(defaultConfUrl.toString(), defaultConfUrl.newInputStream())

        // just merge the component configuration, needed before component init is done
        mergeConfigComponentNodes(newConfigXmlRoot, runtimeConfXmlRoot)

        return newConfigXmlRoot
    }
    protected void initComponents(MNode baseConfigNode) {
        // init components referred to in component-list.component and component-dir elements in the conf file
        for (MNode childNode in baseConfigNode.first("component-list").children) {
            if ("component".equals(childNode.name)) {
                addComponent(new ComponentInfo(null, childNode, this))
            } else if ("component-dir".equals(childNode.name)) {
                addComponentDir(childNode.attribute("location"))
            }
        }
        checkSortDependentComponents()
    }
    protected MNode initConfig(MNode baseConfigNode, MNode runtimeConfXmlRoot) {
        // merge any config files in components
        for (ComponentInfo ci in componentInfoMap.values()) {
            ResourceReference compXmlRr = ci.componentRr.getChild("MoquiConf.xml")
            if (compXmlRr.getExists()) {
                logger.info("Merging MoquiConf.xml file from component ${ci.name}")
                MNode compXmlNode = MNode.parse(compXmlRr)
                mergeConfigNodes(baseConfigNode, compXmlNode)
            }
        }

        // merge the runtime conf file into the default one to override any settings (they both have the same root node, go from there)
        logger.info("Merging runtime configuration at ${runtimeConfPath}")
        mergeConfigNodes(baseConfigNode, runtimeConfXmlRoot)

        // TODO: add some conf or runtime option to log the full config after merge?
        // logger.info("Configuration after all merges:\n${baseConfigNode.toString()}")

        // set default System properties now that all is merged
        for (MNode defPropNode in baseConfigNode.children("default-property")) {
            String propName = defPropNode.attribute("name")
            if (!System.getProperty(propName)) {
                String propValue = SystemBinding.expand(defPropNode.attribute("value"))
                System.setProperty(propName, propValue)
            }
        }

        return baseConfigNode
    }

    // NOTE: using unbound LinkedBlockingQueue, so max pool size in ThreadPoolExecutor has no effect
    private static class WorkerThreadFactory implements ThreadFactory {
        private final ThreadGroup workerGroup = new ThreadGroup("MoquiWorkers")
        private final AtomicInteger threadNumber = new AtomicInteger(1)
        Thread newThread(Runnable r) { return new Thread(workerGroup, r, "MoquiWorker-" + threadNumber.getAndIncrement()) }
    }
    private ExecutorService makeWorkerPool() {
        MNode toolsNode = confXmlRoot.first('tools')

        int workerQueueSize = (toolsNode.attribute("worker-queue") ?: "65536") as int
        BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(workerQueueSize)

        int coreSize = (toolsNode.attribute("worker-pool-core") ?: "4") as int
        int maxSize = (toolsNode.attribute("worker-pool-max") ?: "16") as int
        long aliveTime = (toolsNode.attribute("worker-pool-alive") ?: "60") as long

        logger.info("Initializing worker ThreadPoolExecutor: queue limit ${workerQueueSize}, pool-core ${coreSize}, pool-max ${maxSize}, pool-alive ${aliveTime}s")
        return new ThreadPoolExecutor(coreSize, maxSize, aliveTime, TimeUnit.SECONDS, workQueue, new WorkerThreadFactory())
    }

    private void preFacadeInit() {
        // save the current configuration in a file for debugging/reference
        File confSaveFile = new File(runtimePath + "/log/MoquiActualConf.xml")
        try {
            if (confSaveFile.exists()) confSaveFile.delete()
            if (!confSaveFile.parentFile.exists()) confSaveFile.parentFile.mkdirs()
            FileWriter fw = new FileWriter(confSaveFile)
            fw.write(confXmlRoot.toString())
            fw.close()
        } catch (Exception e) {
            logger.warn("Could not save ${confSaveFile.absolutePath} file: ${e.toString()}")
        }

        try {
            localhostAddress = InetAddress.getLocalHost()
        } catch (UnknownHostException e) {
            logger.warn("Could not get localhost address", new BaseException("Could not get localhost address", e))
        }

        // init ClassLoader early so that classpath:// resources and framework interface impls will work
        initClassLoader()

        // do these after initComponents as that may override configuration
        serverStatsNode = confXmlRoot.first('server-stats')
        skipStatsCond = serverStatsNode.attribute("stats-skip-condition")
        hitBinLengthMillis = (serverStatsNode.attribute("bin-length-seconds") as Integer)*1000 ?: 900000

        // init ESAPI - NOTE: this should be the first call to anything related to ESAPI or StupidWebUtilities so config is in place
        if (!System.getProperty("org.owasp.esapi.resources")) System.setProperty("org.owasp.esapi.resources", runtimePath + "/conf/esapi")
        logger.info("Starting ESAPI, resources at ${System.getProperty("org.owasp.esapi.resources")}")
        StupidWebUtilities.canonicalizeValue("test")

        // Load ToolFactory implementations from tools.tool-factory elements, run preFacadeInit() methods
        ArrayList<Map<String, String>> toolFactoryAttrsList = new ArrayList<>()
        for (MNode toolFactoryNode in confXmlRoot.first("tools").children("tool-factory")) {
            if (toolFactoryNode.attribute("disabled") == "true") {
                logger.info("Not loading disabled ToolFactory with class: ${toolFactoryNode.attribute("class")}")
                continue
            }
            toolFactoryAttrsList.add(toolFactoryNode.getAttributes())
        }
        StupidUtilities.orderMapList(toolFactoryAttrsList as List<Map>, ["init-priority", "class"])
        for (Map<String, String> toolFactoryAttrs in toolFactoryAttrsList) {
            String tfClass = toolFactoryAttrs.get("class")
            logger.info("Loading ToolFactory with class: ${tfClass}")
            try {
                ToolFactory tf = (ToolFactory) Thread.currentThread().getContextClassLoader().loadClass(tfClass).newInstance()
                tf.preFacadeInit(this)
                toolFactoryMap.put(tf.getName(), tf)
            } catch (Throwable t) {
                logger.error("Error loading ToolFactory with class ${tfClass}", t)
            }
        }
    }

    private void postFacadeInit() {

        // ========== load a few things in advance so first page hit is faster in production (in dev mode will reload anyway as caches timeout)
        // load entity defs
        logger.info("Loading entity definitions")
        long entityStartTime = System.currentTimeMillis()
        EntityFacadeImpl defaultEfi = getEntityFacade("DEFAULT")
        defaultEfi.loadAllEntityLocations()
        List<Map<String, Object>> entityInfoList = this.entityFacade.getAllEntitiesInfo(null, null, false, false, false)
        // load/warm framework entities
        defaultEfi.loadFrameworkEntities()
        logger.info("Loaded ${entityInfoList.size()} entity definitions in ${System.currentTimeMillis() - entityStartTime}ms")

        // now that everything is started up, if configured check all entity tables
        defaultEfi.checkInitDatasourceTables()

        // Run init() in ToolFactory implementations from tools.tool-factory elements
        for (ToolFactory tf in toolFactoryMap.values()) {
            logger.info("Initializing ToolFactory: ${tf.getName()}")
            try {
                tf.init(this)
            } catch (Throwable t) {
                logger.error("Error initializing ToolFactory ${tf.getName()}", t)
            }
        }

        // Warm cache on start if configured to do so
        if (confXmlRoot.first("cache-list").attribute("warm-on-start") != "false") warmCache()
    }

    void warmCache() {
        this.entityFacade.warmCache()
        this.serviceFacade.warmCache()
        this.screenFacade.warmCache()
    }

    /** Setup the cached ClassLoader, this should init in the main thread so we can set it properly */
    private void initClassLoader() {
        ClassLoader pcl = (Thread.currentThread().getContextClassLoader() ?: this.class.classLoader) ?: System.classLoader
        cachedClassLoader = new StupidClassLoader(pcl)
        // add runtime/classes jar files to the class loader
        File runtimeClassesFile = new File(runtimePath + "/classes")
        if (runtimeClassesFile.exists()) {
            cachedClassLoader.addClassesDirectory(runtimeClassesFile)
        }
        // add runtime/lib jar files to the class loader
        File runtimeLibFile = new File(runtimePath + "/lib")
        if (runtimeLibFile.exists()) for (File jarFile: runtimeLibFile.listFiles()) {
            if (jarFile.getName().endsWith(".jar")) {
                cachedClassLoader.addJarFile(new JarFile(jarFile))
                logger.info("Added JAR from runtime/lib: ${jarFile.getName()}")
            }
        }

        // add <component>/classes and <component>/lib jar files to the class loader now that component locations loaded
        for (ComponentInfo ci in componentInfoMap.values()) {
            ResourceReference classesRr = ci.componentRr.getChild("classes")
            if (classesRr.exists && classesRr.supportsDirectory() && classesRr.isDirectory()) {
                cachedClassLoader.addClassesDirectory(new File(classesRr.getUri()))
            }

            ResourceReference libRr = ci.componentRr.getChild("lib")
            if (libRr.exists && libRr.supportsDirectory() && libRr.isDirectory()) {
                Set<String> jarsLoaded = new LinkedHashSet<>()
                for (ResourceReference jarRr: libRr.getDirectoryEntries()) {
                    if (jarRr.fileName.endsWith(".jar")) {
                        try {
                            cachedClassLoader.addJarFile(new JarFile(new File(jarRr.getUrl().getPath())))
                            jarsLoaded.add(jarRr.getFileName())
                        } catch (Exception e) {
                            logger.error("Could not load JAR from component ${ci.name}: ${jarRr.getLocation()}: ${e.toString()}")
                        }
                    }
                }
                logger.info("Added JARs from component ${ci.name}: ${jarsLoaded}")
            }
        }

        // clear not found info just in case anything was falsely added
        cachedClassLoader.clearNotFoundInfo()
        // set as context classloader
        Thread.currentThread().setContextClassLoader(cachedClassLoader)
    }

    /** Called from MoquiContextListener.contextInitialized after ECFI init */
    boolean checkEmptyDb() {
        String emptyDbLoad = confXmlRoot.first("tools").attribute("empty-db-load")
        if (!emptyDbLoad || emptyDbLoad == 'none') return false

        long enumCount = getEntity().find("moqui.basic.Enumeration").disableAuthz().count()
        if (enumCount == 0) {
            logger.info("Found ${enumCount} Enumeration records, loading empty-db-load data types (${emptyDbLoad})")

            ExecutionContext ec = getExecutionContext()
            try {
                ec.getArtifactExecution().disableAuthz()
                ec.getArtifactExecution().push("loadData", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
                ec.getArtifactExecution().setAnonymousAuthorizedAll()
                ec.getUser().loginAnonymousIfNoUser()

                EntityDataLoader edl = ec.getEntity().makeDataLoader()
                if (emptyDbLoad != 'all') edl.dataTypes(new HashSet(emptyDbLoad.split(",") as List))

                try {
                    long startTime = System.currentTimeMillis()
                    long records = edl.load()

                    logger.info("Loaded [${records}] records (with types: ${emptyDbLoad}) in ${(System.currentTimeMillis() - startTime)/1000} seconds.")
                } catch (Throwable t) {
                    logger.error("Error loading empty DB data (with types: ${emptyDbLoad})", t)
                }

            } finally {
                ec.destroy()
            }
            return true
        } else {
            logger.info("Found ${enumCount} Enumeration records, NOT loading empty-db-load data types (${emptyDbLoad})")
            return false
        }
    }

    @Override
    synchronized void destroy() {
        if (destroyed) {
            logger.warn("Not destroying ExecutionContextFactory, already destroyed (or destroying)")
            return
        }
        destroyed = true

        // persist any remaining bins in artifactHitBinByType
        Timestamp currentTimestamp = new Timestamp(System.currentTimeMillis())
        List<ArtifactStatsInfo> asiList = new ArrayList<>(artifactStatsInfoByType.values())
        artifactStatsInfoByType.clear()
        ArtifactExecutionFacadeImpl aefi = getEci().getArtifactExecutionImpl()
        boolean enableAuthz = !aefi.disableAuthz()
        try {
            for (ArtifactStatsInfo asi in asiList) {
                if (asi.curHitBin == null) continue
                EntityValue ahb = asi.curHitBin.makeAhbValue(this, currentTimestamp)
                ahb.setSequencedIdPrimary().create()
            }
        } finally { if (enableAuthz) aefi.enableAuthz() }
        logger.info("ArtifactHitBins stored")

        // shutdown worker pool
        try {
            workerPool.shutdown()
            logger.info("Worker pool shut down")
        } catch (Throwable t) { logger.error("Error in workerPool shutdown", t) }

        // stop NotificationMessageListeners
        for (NotificationMessageListener nml in registeredNotificationMessageListeners) nml.destroy()

        // Run destroy() in ToolFactory implementations from tools.tool-factory elements, in reverse order
        ArrayList<ToolFactory> toolFactoryList = new ArrayList<>(toolFactoryMap.values())
        Collections.reverse(toolFactoryList)
        for (ToolFactory tf in toolFactoryList) {
            logger.info("Destroying ToolFactory: ${tf.getName()}")
            try {
                tf.destroy()
            } catch (Throwable t) {
                logger.error("Error destroying ToolFactory ${tf.getName()}", t)
            }
        }

        // this destroy order is important as some use others so must be destroyed first
        if (this.serviceFacade != null) this.serviceFacade.destroy()
        if (this.entityFacade != null) this.entityFacade.destroy()
        if (this.transactionFacade != null) this.transactionFacade.destroy()
        if (this.cacheFacade != null) this.cacheFacade.destroy()
        logger.info("Facades destroyed")

        activeContext.remove()
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!this.destroyed) {
                this.destroy()
                logger.warn("ExecutionContextFactoryImpl not destroyed, caught in finalize.")
            }
        } catch (Exception e) {
            logger.warn("Error in destroy, called in finalize of ExecutionContextFactoryImpl", e)
        }
        super.finalize()
    }

    @Override
    String getRuntimePath() { return runtimePath }
    MNode getConfXmlRoot() { return confXmlRoot }
    MNode getServerStatsNode() { return serverStatsNode }
    MNode getArtifactExecutionNode(String artifactTypeEnumId) {
        return confXmlRoot.first("artifact-execution-facade")
                .first({ MNode it -> it.name == "artifact-execution" && it.attribute("type") == artifactTypeEnumId })
    }

    InetAddress getLocalhostAddress() { return localhostAddress }

    void registerNotificationMessageListener(NotificationMessageListener nml) {
        nml.init(this)
        registeredNotificationMessageListeners.add(nml)
    }
    List<NotificationMessageListener> getNotificationMessageListeners() { return registeredNotificationMessageListeners }

    org.apache.shiro.mgt.SecurityManager getSecurityManager() {
        if (internalSecurityManager != null) return internalSecurityManager

        // init Apache Shiro; NOTE: init must be done here so that ecfi will be fully initialized and in the static context
        org.apache.shiro.util.Factory<org.apache.shiro.mgt.SecurityManager> factory =
                new IniSecurityManagerFactory("classpath:shiro.ini")
        internalSecurityManager = factory.getInstance()
        // NOTE: setting this statically just in case something uses it, but for Moqui we'll be getting the SecurityManager from the ecfi
        SecurityUtils.setSecurityManager(internalSecurityManager)

        return internalSecurityManager
    }
    CredentialsMatcher getCredentialsMatcher(String hashType) {
        HashedCredentialsMatcher hcm = new HashedCredentialsMatcher()
        if (hashType) {
            hcm.setHashAlgorithmName(hashType)
        } else {
            hcm.setHashAlgorithmName(getPasswordHashType())
        }
        return hcm
    }
    static String getRandomSalt() { return StupidUtilities.getRandomString(8) }
    String getPasswordHashType() {
        MNode passwordNode = confXmlRoot.first("user-facade").first("password")
        return passwordNode.attribute("encrypt-hash-type") ?: "SHA-256"
    }
    String getSimpleHash(String source, String salt) { return getSimpleHash(source, salt, getPasswordHashType()) }
    String getSimpleHash(String source, String salt, String hashType) {
        return new SimpleHash(hashType ?: getPasswordHashType(), source, salt).toString()
    }

    String getLoginKeyHashType() {
        MNode loginKeyNode = confXmlRoot.first("user-facade").first("login-key")
        return loginKeyNode.attribute("encrypt-hash-type") ?: "SHA-256"
    }
    int getLoginKeyExpireHours() {
        MNode loginKeyNode = confXmlRoot.first("user-facade").first("login-key")
        return (loginKeyNode.attribute("expire-hours") ?: "144") as int
    }

    // ========== Getters ==========

    CacheFacadeImpl getCacheFacade() { return this.cacheFacade }

    EntityFacadeImpl getEntityFacade() { return getEntityFacade(getExecutionContext().getTenantId()) }
    EntityFacadeImpl getEntityFacade(String tenantId) {
        // this should never happen, may want to default to tenantId=DEFAULT, but to see if it happens anywhere throw for now
        if (tenantId == null) throw new IllegalArgumentException("For getEntityFacade tenantId cannot be null")
        EntityFacadeImpl efi = (EntityFacadeImpl) entityFacadeByTenantMap.get(tenantId)
        if (efi == null) efi = initEntityFacade(tenantId)

        return efi
    }
    synchronized EntityFacadeImpl initEntityFacade(String tenantId) {
        EntityFacadeImpl efi = this.entityFacadeByTenantMap.get(tenantId)
        if (efi != null) return efi

        efi = new EntityFacadeImpl(this, tenantId)
        this.entityFacadeByTenantMap.put(tenantId, efi)
        logger.info("Moqui EntityFacadeImpl for Tenant ${tenantId} Initialized")
        return efi
    }

    LoggerFacadeImpl getLoggerFacade() { return loggerFacade }
    ResourceFacadeImpl getResourceFacade() { return resourceFacade }
    ScreenFacadeImpl getScreenFacade() { return screenFacade }
    ServiceFacadeImpl getServiceFacade() { return serviceFacade }
    TransactionFacadeImpl getTransactionFacade() { return transactionFacade }
    L10nFacadeImpl getL10nFacade() { return getEci().getL10nFacade() }
    // TODO: find references, change to eci where more direct

    // ========== Interface Implementations ==========

    @Override
    ExecutionContext getExecutionContext() { return getEci() }
    ExecutionContextImpl getEci() {
        // the ExecutionContextImpl cast here looks funny, but avoids Groovy using a slow castToType call
        ExecutionContextImpl ec = (ExecutionContextImpl) activeContext.get()
        if (ec != null) return ec

        if (logger.traceEnabled) logger.trace("Creating new ExecutionContext in thread [${Thread.currentThread().id}:${Thread.currentThread().name}]")
        if (!(Thread.currentThread().getContextClassLoader() instanceof StupidClassLoader))
            Thread.currentThread().setContextClassLoader(cachedClassLoader)
        ec = new ExecutionContextImpl(this)
        this.activeContext.set(ec)
        return ec
    }

    void destroyActiveExecutionContext() {
        ExecutionContext ec = this.activeContext.get()
        if (ec) {
            ec.destroy()
            this.activeContext.remove()
        }
    }

    /** Using an EC in multiple threads is dangerous as much of the ECI is not designed to be thread safe. */
    void useExecutionContextInThread(ExecutionContextImpl eci) {
        ExecutionContextImpl curEc = activeContext.get()
        if (curEc != null) curEc.destroy()
        activeContext.set(eci)
    }

    @Override
    <V> ToolFactory<V> getToolFactory(String toolName) {
        ToolFactory<V> toolFactory = (ToolFactory<V>) toolFactoryMap.get(toolName)
        return toolFactory
    }
    @Override
    <V> V getTool(String toolName, Class<V> instanceClass, Object... parameters) {
        ToolFactory<V> toolFactory = (ToolFactory<V>) toolFactoryMap.get(toolName)
        if (toolFactory == null) throw new IllegalArgumentException("No ToolFactory found with name ${toolName}")
        return toolFactory.getInstance(parameters)
    }

    /*
    @Deprecated
    void initComponent(String location) {
        ComponentInfo componentInfo = new ComponentInfo(location, this)
        // check dependencies
        if (componentInfo.dependsOnNames) for (String dependsOnName in componentInfo.dependsOnNames) {
            if (!componentInfoMap.containsKey(dependsOnName))
                throw new IllegalArgumentException("Component [${componentInfo.name}] depends on component [${dependsOnName}] which is not initialized")
        }
        addComponent(componentInfo)
    }
    */

    protected void checkSortDependentComponents() {
        // we have an issue here where not all dependencies are declared, most are implied by component load order
        // because of this not doing a full topological sort, just a single pass with dependencies inserted as needed

        ArrayList<String> sortedNames = []
        for (ComponentInfo componentInfo in componentInfoMap.values()) {
            // for each dependsOn make sure component is valid, add to the list if not already there
            // given a close starting sort order this should get us to a pretty good list
            for (String dependsOnName in componentInfo.getRecursiveDependencies())
                if (!sortedNames.contains(dependsOnName)) sortedNames.add(dependsOnName)

            if (!sortedNames.contains(componentInfo.name)) sortedNames.add(componentInfo.name)
        }

        logger.info("Components after depends-on sort: ${sortedNames}")

        // see if all dependencies are met
        List<String> messages = []
        for (int i = 0; i < sortedNames.size(); i++) {
            String name = sortedNames.get(i)
            ComponentInfo componentInfo = componentInfoMap.get(name)
            for (String dependsOnName in componentInfo.dependsOnNames) {
                int dependsOnIndex = sortedNames.indexOf(dependsOnName)
                if (dependsOnIndex > i)
                    messages.add("Broken dependency order after initial pass: [${dependsOnName}] is after [${name}]".toString())
            }
        }

        if (messages) {
            StringBuilder sb = new StringBuilder()
            for (String message in messages) {
                logger.error(message)
                sb.append(message).append(" ")
            }
            throw new IllegalArgumentException(sb.toString())
        }

        // now create a new Map and replace the original
        Map<String, ComponentInfo> newMap = new LinkedHashMap<String, ComponentInfo>()
        for (String sortedName in sortedNames) newMap.put(sortedName, componentInfoMap.get(sortedName))
        componentInfoMap = newMap
    }

    protected void addComponent(ComponentInfo componentInfo) {
        if (componentInfoMap.containsKey(componentInfo.name))
            logger.warn("Overriding component [${componentInfo.name}] at [${componentInfoMap.get(componentInfo.name).location}] with location [${componentInfo.location}] because another component of the same name was initialized")
        // components registered later override those registered earlier by replacing the Map entry
        componentInfoMap.put(componentInfo.name, componentInfo)
        logger.info("Added component ${componentInfo.name.padRight(18)} at ${componentInfo.location}")
    }

    protected void addComponentDir(String location) {
        ResourceReference componentRr = getResourceReference(location)
        // if directory doesn't exist skip it, runtime doesn't always have an component directory
        if (componentRr.getExists() && componentRr.isDirectory()) {
            // see if there is a components.xml file, if so load according to it instead of all sub-directories
            ResourceReference cxmlRr = getResourceReference(location + "/components.xml")

            if (cxmlRr.getExists()) {
                MNode componentList = MNode.parse(cxmlRr)
                for (MNode childNode in componentList.children) {
                    if (childNode.name == 'component') {
                        ComponentInfo componentInfo = new ComponentInfo(location, childNode, this)
                        addComponent(componentInfo)
                    } else if (childNode.name == 'component-dir') {
                        String locAttr = childNode.attribute("location")
                        addComponentDir(location + "/" + locAttr)
                    }
                }
            } else {
                // get all files in the directory
                TreeMap<String, ResourceReference> componentDirEntries = new TreeMap<String, ResourceReference>()
                for (ResourceReference componentSubRr in componentRr.getDirectoryEntries()) {
                    // if it's a directory and doesn't start with a "." then add it as a component dir
                    if (!componentSubRr.isDirectory() || componentSubRr.getFileName().startsWith(".")) continue
                    componentDirEntries.put(componentSubRr.getFileName(), componentSubRr)
                }
                for (Map.Entry<String, ResourceReference> componentDirEntry in componentDirEntries) {
                    ComponentInfo componentInfo = new ComponentInfo(componentDirEntry.getValue().getLocation(), this)
                    this.addComponent(componentInfo)
                }
            }
        }
    }

    protected ResourceReference getResourceReference(String location) {
        // TODO: somehow support other resource location types
        // the ResourceFacade inits after components are loaded (so it is aware of initial components), so we can't get ResourceReferences from it
        ResourceReference rr = new UrlResourceReference()
        rr.init(location, this)
        return rr
    }

    static class ComponentInfo {
        ExecutionContextFactoryImpl ecfi
        String name
        String location
        ResourceReference componentRr
        Set<String> dependsOnNames = new LinkedHashSet<String>()
        ComponentInfo(String baseLocation, MNode componentNode, ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi
            String curLoc = null
            if (baseLocation) curLoc = baseLocation + "/" + componentNode.attribute("location")
            init(curLoc, componentNode)
        }
        ComponentInfo(String location, ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi
            init(location, null)
        }
        protected void init(String specLoc, MNode origNode) {
            location = specLoc ?: origNode?.attribute("location")
            if (!location) throw new IllegalArgumentException("Cannot init component with no location (not specified or found in component.@location)")

            // clean up the location
            if (location.endsWith('/')) location = location.substring(0, location.length()-1)
            int lastSlashIndex = location.lastIndexOf('/')
            if (lastSlashIndex < 0) {
                // if this happens the component directory is directly under the runtime directory, so prefix loc with that
                location = ecfi.runtimePath + '/' + location
            }
            // set the default component name
            name = location.substring(lastSlashIndex+1)

            // make sure directory exists
            componentRr = ecfi.getResourceReference(location)
            if (!componentRr.supportsExists()) throw new IllegalArgumentException("Could component location ${location} does not support exists, cannot use as a component location")
            if (!componentRr.getExists()) throw new IllegalArgumentException("Could not find component directory at: ${location}")
            if (!componentRr.isDirectory()) throw new IllegalArgumentException("Component location is not a directory: ${location}")

            // see if there is a component.xml file, if so use that as the componentNode instead of origNode
            ResourceReference compXmlRr = componentRr.getChild("component.xml")
            MNode componentNode
            if (compXmlRr.getExists()) {
                componentNode = MNode.parse(compXmlRr)
            } else {
                componentNode = origNode
            }

            if (componentNode != null) {
                String nameAttr = componentNode.attribute("name")
                if (nameAttr) name = nameAttr
                if (componentNode.hasChild("depends-on")) for (MNode dependsOnNode in componentNode.children("depends-on")) {
                    dependsOnNames.add(dependsOnNode.attribute("name"))
                }
            }
        }

        List<String> getRecursiveDependencies() {
            List<String> dependsOnList = []
            for (String dependsOnName in dependsOnNames) {
                ComponentInfo depCompInfo = ecfi.componentInfoMap.get(dependsOnName)
                if (depCompInfo == null)
                    throw new IllegalArgumentException("Component ${name} depends on component ${dependsOnName} which is not initialized")
                List<String> childDepList = depCompInfo.getRecursiveDependencies()
                for (String childDep in childDepList)
                    if (!dependsOnList.contains(childDep)) dependsOnList.add(childDep)

                if (!dependsOnList.contains(dependsOnName)) dependsOnList.add(dependsOnName)
            }
            return dependsOnList
        }
    }

    // void destroyComponent(String componentName) throws BaseException { componentInfoMap.remove(componentName) }

    @Override
    LinkedHashMap<String, String> getComponentBaseLocations() {
        LinkedHashMap<String, String> compLocMap = new LinkedHashMap<String, String>()
        for (ComponentInfo componentInfo in componentInfoMap.values()) {
            compLocMap.put(componentInfo.name, componentInfo.location)
        }
        return compLocMap
    }

    @Override
    L10nFacade getL10n() { getEci().getL10nFacade() }

    @Override
    ResourceFacade getResource() { return resourceFacade }

    @Override
    LoggerFacade getLogger() { return loggerFacade }

    @Override
    CacheFacade getCache() { return this.cacheFacade }

    @Override
    TransactionFacade getTransaction() { return transactionFacade }

    @Override
    EntityFacade getEntity() { getEntityFacade(getExecutionContext()?.getTenantId()) }

    @Override
    ServiceFacade getService() { return serviceFacade }

    @Override
    ScreenFacade getScreen() { return screenFacade }

    // ========== Server Stat Tracking ==========
    boolean getSkipStats() {
        // NOTE: the results of this condition eval can't be cached because the expression can use any data in the ec
        ExecutionContextImpl eci = getEci()
        return skipStatsCond ? eci.resource.condition(skipStatsCond, null, [pathInfo:eci.web?.request?.pathInfo]) : false
    }

    protected boolean artifactPersistHit(ArtifactExecutionInfo.ArtifactType artifactTypeEnum) {
        // now checked before calling this: if (ArtifactExecutionInfo.AT_ENTITY.is(artifactTypeEnum)) return false
        Boolean ph = (Boolean) artifactPersistHitByTypeEnum.get(artifactTypeEnum)
        if (ph == null) {
            MNode artifactStats = getArtifactStatsNode(artifactTypeEnum.name(), null)
            ph = 'true'.equals(artifactStats.attribute('persist-hit'))
            artifactPersistHitByTypeEnum.put(artifactTypeEnum, ph)
        }
        return ph.booleanValue()

        /* by sub-type no longer supported:
        String cacheKey = artifactTypeEnum.name() + artifactSubType
        Boolean ph = (Boolean) artifactPersistHitByTypeAndSub.get(cacheKey)
        if (ph == null) {
            MNode artifactStats = getArtifactStatsNode(artifactTypeEnum.name(), artifactSubType)
            ph = 'true'.equals(artifactStats.attribute('persist-hit'))
            artifactPersistHitByTypeAndSub.put(cacheKey, ph)
        }
        return ph.booleanValue()
        */
    }
    protected boolean artifactPersistBin(ArtifactExecutionInfo.ArtifactType artifactTypeEnum) {
        Boolean pb = (Boolean) artifactPersistBinByTypeEnum.get(artifactTypeEnum)
        if (pb == null) {
            MNode artifactStats = getArtifactStatsNode(artifactTypeEnum.name(), null)
            pb = 'true'.equals(artifactStats.attribute('persist-bin'))
            artifactPersistBinByTypeEnum.put(artifactTypeEnum, pb)
        }
        return pb.booleanValue()

        /* by sub-type no longer supported:
        String cacheKey = artifactTypeEnum.name().concat(artifactSubType)
        Boolean pb = (Boolean) artifactPersistBinByTypeAndSub.get(cacheKey)
        if (pb == null) {
            MNode artifactStats = getArtifactStatsNode(artifactTypeEnum.name(), artifactSubType)
            pb = 'true'.equals(artifactStats.attribute('persist-bin'))
            artifactPersistBinByTypeAndSub.put(cacheKey, pb)
        }
        return pb.booleanValue()
        */
    }

    boolean isAuthzEnabled(ArtifactExecutionInfo.ArtifactType artifactTypeEnum) {
        Boolean en = (Boolean) artifactTypeAuthzEnabled.get(artifactTypeEnum)
        if (en == null) {
            MNode aeNode = getArtifactExecutionNode(artifactTypeEnum.name())
            en = aeNode != null ? !(aeNode.attribute('authz-enabled') == "false") : true
            artifactTypeAuthzEnabled.put(artifactTypeEnum, en)
        }
        return en.booleanValue()
    }
    boolean isTarpitEnabled(ArtifactExecutionInfo.ArtifactType artifactTypeEnum) {
        Boolean en = (Boolean) artifactTypeTarpitEnabled.get(artifactTypeEnum)
        if (en == null) {
            MNode aeNode = getArtifactExecutionNode(artifactTypeEnum.name())
            en = aeNode != null ? !(aeNode.attribute('tarpit-enabled') == "false") : true
            artifactTypeTarpitEnabled.put(artifactTypeEnum, en)
        }
        return en.booleanValue()
    }

    protected MNode getArtifactStatsNode(String artifactType, String artifactSubType) {
        // find artifact-stats node by type AND sub-type, if not found find by just the type
        MNode artifactStats = null
        if (artifactSubType != null)
            artifactStats = confXmlRoot.first("server-stats").first({ MNode it -> it.name == "artifact-stats" &&
                it.attribute("type") == artifactType && it.attribute("sub-type") == artifactSubType })
        if (artifactStats == null)
            artifactStats = confXmlRoot.first("server-stats")
                    .first({ MNode it -> it.name == "artifact-stats" && it.attribute('type') == artifactType })
        return artifactStats
    }

    protected final Set<String> entitiesToSkipHitCount = new HashSet([
            'moqui.server.ArtifactHit', 'create#moqui.server.ArtifactHit',
            'moqui.server.ArtifactHitBin', 'create#moqui.server.ArtifactHitBin',
            'moqui.entity.SequenceValueItem', 'moqui.security.UserAccount', 'moqui.tenant.Tenant',
            'moqui.tenant.TenantDataSource', 'moqui.tenant.TenantDataSourceXaProp',
            'moqui.entity.document.DataDocument', 'moqui.entity.document.DataDocumentField',
            'moqui.entity.document.DataDocumentCondition', 'moqui.entity.feed.DataFeedAndDocument',
            'moqui.entity.view.DbViewEntity', 'moqui.entity.view.DbViewEntityMember',
            'moqui.entity.view.DbViewEntityKeyMap', 'moqui.entity.view.DbViewEntityAlias'])
    protected final long checkSlowThreshold = 20L
    protected final double userImpactMinMillis = 200

    @CompileStatic
    static class ArtifactStatsInfo {
        // put this here so we only have to do one Map lookup per countArtifactHit call
        ArtifactBinInfo curHitBin = null
        long hitCount = 0L
        long slowHitCount = 0L
        double totalTimeMillis = 0
        double totalSquaredTime = 0
        double getAverage() { return hitCount > 0 ? totalTimeMillis / hitCount : 0 }
        double getStdDev() {
            if (hitCount < 2) return 0
            return Math.sqrt(Math.abs(totalSquaredTime - ((totalTimeMillis*totalTimeMillis) / hitCount)) / (hitCount - 1L))
        }
        void incrementHitCount() { hitCount++ }
        void incrementSlowHitCount() { slowHitCount++ }
        void addRunningTime(double runningTime) {
            totalTimeMillis = totalTimeMillis + runningTime
            totalSquaredTime = totalSquaredTime + (runningTime * runningTime)
        }
    }
    @CompileStatic
    static class ArtifactBinInfo {
        ArtifactExecutionInfo.ArtifactType artifactTypeEnum
        String artifactSubType
        String artifactName
        long startTime

        long hitCount = 0L
        long slowHitCount = 0L
        double totalTimeMillis = 0
        double totalSquaredTime = 0
        double minTimeMillis = Long.MAX_VALUE
        double maxTimeMillis = 0

        ArtifactBinInfo(ArtifactExecutionInfo.ArtifactType artifactTypeEnum, String artifactSubType, String artifactName, long startTime) {
            this.artifactTypeEnum = artifactTypeEnum
            this.artifactSubType = artifactSubType
            this.artifactName = artifactName
            this.startTime = startTime
        }

        void incrementHitCount() { hitCount++ }
        void incrementSlowHitCount() { slowHitCount++ }
        void addRunningTime(double runningTime) {
            totalTimeMillis = totalTimeMillis + runningTime
            totalSquaredTime = totalSquaredTime + (runningTime * runningTime)
        }

        // NOTE: ArtifactHitBin always created in DEFAULT tenant since data is aggregated across all tenants, mostly used to monitor performance
        EntityValue makeAhbValue(ExecutionContextFactoryImpl ecfi, Timestamp binEndDateTime) {
            Map<String, Object> ahb = [artifactType:artifactTypeEnum.name(), artifactSubType:artifactSubType,
                                       artifactName:artifactName, binStartDateTime:new Timestamp(startTime), binEndDateTime:binEndDateTime,
                                       hitCount:hitCount, totalTimeMillis:new BigDecimal(totalTimeMillis),
                                       totalSquaredTime:new BigDecimal(totalSquaredTime), minTimeMillis:new BigDecimal(minTimeMillis),
                                       maxTimeMillis:new BigDecimal(maxTimeMillis), slowHitCount:slowHitCount] as Map<String, Object>
            ahb.serverIpAddress = ecfi.localhostAddress?.getHostAddress() ?: "127.0.0.1"
            ahb.serverHostName = ecfi.localhostAddress?.getHostName() ?: "localhost"
            EntityValue ahbValue = ecfi.getEntityFacade("DEFAULT").makeValue("moqui.server.ArtifactHitBin")
            ahbValue.setAll(ahb)
            return ahbValue
        }
    }

    void countArtifactHit(ArtifactExecutionInfo.ArtifactType artifactTypeEnum, String artifactSubType, String artifactName, Map<String, Object> parameters,
                          long startTime, double runningTimeMillis, Long outputSize) {
        boolean isEntity = ArtifactExecutionInfo.AT_ENTITY.is(artifactTypeEnum) || (artifactSubType != null && artifactSubType.startsWith('entity'))
        // don't count the ones this calls
        if (isEntity && entitiesToSkipHitCount.contains(artifactName)) return
        ExecutionContextImpl eci = getEci()
        // for screen, transition, screen-content check skip stats expression
        if ((ArtifactExecutionInfo.AT_XML_SCREEN.is(artifactTypeEnum) ||
                ArtifactExecutionInfo.AT_XML_SCREEN_CONTENT.is(artifactTypeEnum) ||
                ArtifactExecutionInfo.AT_XML_SCREEN_TRANS.is(artifactTypeEnum)) && eci.getSkipStats()) return

        boolean isSlowHit = false
        if (artifactPersistBin(artifactTypeEnum)) {
            String binKey = new StringBuilder(200).append(artifactTypeEnum.name()).append('.').append(artifactSubType).append(':').append(artifactName).toString()
            ArtifactStatsInfo statsInfo = (ArtifactStatsInfo) artifactStatsInfoByType.get(binKey)
            if (statsInfo == null) {
                // consider seeding this from the DB using ArtifactHitReport to get all past data, or maybe not to better handle different servers/etc over time, etc
                statsInfo = new ArtifactStatsInfo()
                artifactStatsInfoByType.put(binKey, statsInfo)
            }

            ArtifactBinInfo abi = statsInfo.curHitBin
            if (abi == null) {
                abi = new ArtifactBinInfo(artifactTypeEnum, artifactSubType, artifactName, startTime)
                statsInfo.curHitBin = abi
            }

            // has the current bin expired since the last hit record?
            long binStartTime = abi.startTime
            if (startTime > (binStartTime + hitBinLengthMillis.longValue())) {
                if (logger.isTraceEnabled()) logger.trace("Advancing ArtifactHitBin [${artifactTypeEnum.name()}.${artifactSubType}:${artifactName}] current hit start [${new Timestamp(startTime)}], bin start [${new Timestamp(abi.startTime)}] bin length ${hitBinLengthMillis/1000} seconds")
                advanceArtifactHitBin(eci, statsInfo, artifactTypeEnum, artifactSubType, artifactName, startTime, hitBinLengthMillis)
                abi = statsInfo.curHitBin
            }

            // handle current hit bin
            abi.incrementHitCount()
            // do something funny with these so we get a better avg and std dev, leave out the first result (count 2nd
            //     twice) if first hit is more than 2x the second because the first hit is almost always MUCH slower
            if (abi.hitCount == 2L && abi.totalTimeMillis > (runningTimeMillis * 2)) {
                abi.setTotalTimeMillis(runningTimeMillis * 2)
                abi.setTotalSquaredTime(runningTimeMillis * runningTimeMillis * 2)
            } else {
                abi.addRunningTime(runningTimeMillis)
            }
            if (runningTimeMillis < abi.minTimeMillis) abi.setMinTimeMillis(runningTimeMillis)
            if (runningTimeMillis > abi.maxTimeMillis) abi.setMaxTimeMillis(runningTimeMillis)

            // handle stats since start
            statsInfo.incrementHitCount()
            long statsHitCount = statsInfo.hitCount
            if (statsHitCount == 2L && (statsInfo.totalTimeMillis) > (runningTimeMillis * 2) ) {
                statsInfo.setTotalTimeMillis(runningTimeMillis * 2)
                statsInfo.setTotalSquaredTime(runningTimeMillis * runningTimeMillis * 2)
            } else {
                statsInfo.addRunningTime(runningTimeMillis)
            }
            // check for slow hits
            if (statsHitCount > checkSlowThreshold) {
                // calc new average and standard deviation
                double average = statsInfo.getAverage()
                double stdDev = statsInfo.getStdDev()

                // if runningTime is more than 2.6 std devs from the avg, count it and possibly log it
                // using 2.6 standard deviations because 2 would give us around 5% of hits (normal distro), shooting for more like 1%
                double slowTime = average + (stdDev * 2.6)
                if (slowTime != 0 && runningTimeMillis > slowTime) {
                    if (runningTimeMillis > userImpactMinMillis)
                        logger.warn("Slow hit to ${binKey} running time ${runningTimeMillis} is greater than average [${average}] plus 2 standard deviations [${stdDev}]")
                    abi.incrementSlowHitCount()
                    statsInfo.incrementSlowHitCount()
                    isSlowHit = true
                }
            }
        }
        // NOTE: never save individual hits for entity artifact hits, way too heavy and also avoids self-reference
        //     (could also be done by checking for ArtifactHit/etc of course)
        // Always save slow hits above userImpactMinMillis regardless of settings
        if (!isEntity && ((isSlowHit && runningTimeMillis > userImpactMinMillis) || artifactPersistHit(artifactTypeEnum))) {
            // NOTE: ArtifactHit saved in current tenant, ArtifactHitBin saved in DEFAULT tenant
            EntityValueBase ahp = (EntityValueBase) eci.entity.makeValue("moqui.server.ArtifactHit")
            ahp.putNoCheck("visitId", eci.user.visitId)
            ahp.putNoCheck("userId", eci.user.userId)
            ahp.putNoCheck("isSlowHit", isSlowHit ? 'Y' : 'N')
            ahp.putNoCheck("artifactType", artifactTypeEnum.name())
            ahp.putNoCheck("artifactSubType", artifactSubType)
            ahp.putNoCheck("artifactName", artifactName)
            ahp.putNoCheck("startDateTime", new Timestamp(startTime))
            ahp.putNoCheck("runningTimeMillis", runningTimeMillis)

            if (parameters != null && parameters.size() > 0) {
                StringBuilder ps = new StringBuilder()
                for (Map.Entry<String, Object> pme in parameters.entrySet()) {
                    if (StupidJavaUtilities.isEmpty(pme.value)) continue
                    if (pme.key?.contains("password")) continue
                    if (ps.length() > 0) ps.append(",")
                    ps.append(pme.key).append("=").append(pme.value)
                }
                if (ps.length() > 255) ps.delete(255, ps.length())
                ahp.putNoCheck("parameterString", ps.toString())
            }
            if (outputSize != null) ahp.putNoCheck("outputSize", outputSize)
            if (eci.getMessage().hasError()) {
                ahp.putNoCheck("wasError", "Y")
                StringBuilder errorMessage = new StringBuilder()
                for (String curErr in eci.message.errors) errorMessage.append(curErr).append(";")
                if (errorMessage.length() > 255) errorMessage.delete(255, errorMessage.length())
                ahp.putNoCheck("errorMessage", errorMessage.toString())
            } else {
                ahp.putNoCheck("wasError", "N")
            }
            if (eci.web != null) {
                String fullUrl = eci.web.getRequestUrl()
                fullUrl = (fullUrl.length() > 255) ? fullUrl.substring(0, 255) : fullUrl.toString()
                ahp.putNoCheck("requestUrl", fullUrl)
                String referrer = eci.web.request.getHeader("Referrer")
                if (referrer != null && referrer.length() > 0) ahp.putNoCheck("referrerUrl", referrer)
            }

            ahp.putNoCheck("serverIpAddress", localhostAddress != null ? localhostAddress.getHostAddress() : "127.0.0.1")
            ahp.putNoCheck("serverHostName", localhostAddress != null ? localhostAddress.getHostName() : "localhost")

            // NOTE: async service scheduling is slow enough that it is faster to just create the record now
            // eci.service.async().name("create", "moqui.server.ArtifactHit").parameters(ahp).call()
            // have an authorize-skip=create on the entity so don't need to disable authz here
            eci.runInWorkerThread({
                ArtifactExecutionFacadeImpl aefi = getEci().getArtifactExecutionImpl()
                boolean enableAuthz = !aefi.disableAuthz()
                try { ahp.setSequencedIdPrimary().create() }
                finally { if (enableAuthz) aefi.enableAuthz() }
            })
        }
    }

    protected synchronized void advanceArtifactHitBin(ExecutionContextImpl eci, ArtifactStatsInfo statsInfo,
            ArtifactExecutionInfo.ArtifactType artifactTypeEnum, String artifactSubType, String artifactName,
            long startTime, int hitBinLengthMillis) {
        ArtifactBinInfo abi = statsInfo.curHitBin
        if (abi == null) {
            statsInfo.curHitBin = new ArtifactBinInfo(artifactTypeEnum, artifactSubType, artifactName, startTime)
            return
        }

        // check the time again and return just in case something got in while waiting with the same type
        long binStartTime = abi.startTime
        if (startTime < (binStartTime + hitBinLengthMillis)) return

        // otherwise, persist the old and create a new one
        EntityValue ahb = abi.makeAhbValue(this, new Timestamp(binStartTime + hitBinLengthMillis))
        eci.runInWorkerThread({
            ArtifactExecutionFacadeImpl aefi = getEci().getArtifactExecutionImpl()
            boolean enableAuthz = !aefi.disableAuthz()
            try { ahb.setSequencedIdPrimary().create() }
            finally { if (enableAuthz) aefi.enableAuthz() }
        })

        statsInfo.curHitBin = new ArtifactBinInfo(artifactTypeEnum, artifactSubType, artifactName, startTime)
    }

    // ========== Configuration File Merging Methods ==========

    protected static void mergeConfigNodes(MNode baseNode, MNode overrideNode) {
        baseNode.mergeChildrenByKey(overrideNode, "default-property", "name", null)
        baseNode.mergeChildWithChildKey(overrideNode, "tools", "tool-factory", "class", null)
        baseNode.mergeChildWithChildKey(overrideNode, "cache-list", "cache", "name", null)

        if (overrideNode.hasChild("server-stats")) {
            // the artifact-stats nodes have 2 keys: type, sub-type; can't use the normal method
            MNode ssNode = baseNode.first("server-stats")
            MNode overrideSsNode = overrideNode.first("server-stats")
            // override attributes for this node
            ssNode.attributes.putAll(overrideSsNode.attributes)
            for (MNode childOverrideNode in overrideSsNode.children("artifact-stats")) {
                String type = childOverrideNode.attribute("type")
                String subType = childOverrideNode.attribute("sub-type")
                MNode childBaseNode = ssNode.first({ MNode it -> it.name == "artifact-stats" && it.attribute("type") == type &&
                        (it.attribute("sub-type") == subType || (!it.attribute("sub-type") && !subType)) })
                if (childBaseNode) {
                    // merge the node attributes
                    childBaseNode.attributes.putAll(childOverrideNode.attributes)
                } else {
                    // no matching child base node, so add a new one
                    ssNode.append(childOverrideNode)
                }
            }
        }

        baseNode.mergeChildWithChildKey(overrideNode, "webapp-list", "webapp", "name",
                { MNode childBaseNode, MNode childOverrideNode -> mergeWebappChildNodes(childBaseNode, childOverrideNode) })

        baseNode.mergeChildWithChildKey(overrideNode, "artifact-execution-facade", "artifact-execution", "type", null)

        if (overrideNode.hasChild("user-facade")) {
            MNode ufBaseNode = baseNode.first("user-facade")
            MNode ufOverrideNode = overrideNode.first("user-facade")
            ufBaseNode.mergeSingleChild(ufOverrideNode, "password")
            ufBaseNode.mergeSingleChild(ufOverrideNode, "login-key")
            ufBaseNode.mergeSingleChild(ufOverrideNode, "login")
        }

        if (overrideNode.hasChild("transaction-facade")) {
            MNode tfBaseNode = baseNode.first("transaction-facade")
            MNode tfOverrideNode = overrideNode.first("transaction-facade")
            tfBaseNode.attributes.putAll(tfOverrideNode.attributes)
            tfBaseNode.mergeSingleChild(tfOverrideNode, "server-jndi")
            tfBaseNode.mergeSingleChild(tfOverrideNode, "transaction-jndi")
            tfBaseNode.mergeSingleChild(tfOverrideNode, "transaction-internal")
        }

        if (overrideNode.hasChild("resource-facade")) {
            baseNode.mergeChildWithChildKey(overrideNode, "resource-facade", "resource-reference", "scheme", null)
            baseNode.mergeChildWithChildKey(overrideNode, "resource-facade", "template-renderer", "extension", null)
            baseNode.mergeChildWithChildKey(overrideNode, "resource-facade", "script-runner", "extension", null)
        }

        baseNode.mergeChildWithChildKey(overrideNode, "screen-facade", "screen-text-output", "type", null)

        if (overrideNode.hasChild("service-facade")) {
            MNode sfBaseNode = baseNode.first("service-facade")
            MNode sfOverrideNode = overrideNode.first("service-facade")
            sfBaseNode.mergeNodeWithChildKey(sfOverrideNode, "service-location", "name", null)
            sfBaseNode.mergeChildrenByKey(sfOverrideNode, "service-type", "name", null)
            sfBaseNode.mergeChildrenByKey(sfOverrideNode, "service-file", "location", null)
            sfBaseNode.mergeChildrenByKey(sfOverrideNode, "startup-service", "name", null)

            // handle thread-pool
            MNode tpOverrideNode = sfOverrideNode.first("thread-pool")
            if (tpOverrideNode) {
                MNode tpBaseNode = sfBaseNode.first("thread-pool")
                if (tpBaseNode) {
                    tpBaseNode.mergeNodeWithChildKey(tpOverrideNode, "run-from-pool", "name", null)
                } else {
                    sfBaseNode.append(tpOverrideNode)
                }
            }

            // handle jms-service, just copy all over
            for (MNode jsOverrideNode in sfOverrideNode.children("jms-service")) {
                sfBaseNode.append(jsOverrideNode)
            }
        }

        if (overrideNode.hasChild("entity-facade")) {
            MNode efBaseNode = baseNode.first("entity-facade")
            MNode efOverrideNode = overrideNode.first("entity-facade")
            efBaseNode.mergeNodeWithChildKey(efOverrideNode, "datasource", "group-name", { MNode childBaseNode, MNode childOverrideNode ->
                // handle the jndi-jdbc and inline-jdbc nodes: if either exist in override have it totally remove both from base, then copy over
                if (childOverrideNode.hasChild("jndi-jdbc") || childOverrideNode.hasChild("inline-jdbc")) {
                    childBaseNode.remove("jndi-jdbc")
                    childBaseNode.remove("inline-jdbc")

                    if (childOverrideNode.hasChild("inline-jdbc")) {
                        childBaseNode.append(childOverrideNode.first("inline-jdbc"))
                    } else if (childOverrideNode.hasChild("jndi-jdbc")) {
                        childBaseNode.append(childOverrideNode.first("jndi-jdbc"))
                    }
                }
            })
            efBaseNode.mergeSingleChild(efOverrideNode, "server-jndi")
            // for load-entity and load-data just copy over override nodes
            for (MNode copyNode in efOverrideNode.children("load-entity")) efBaseNode.append(copyNode)
            for (MNode copyNode in efOverrideNode.children("load-data")) efBaseNode.append(copyNode)
        }

        if (overrideNode.hasChild("database-list")) {
            baseNode.mergeChildWithChildKey(overrideNode, "database-list", "dictionary-type", "type", null)
            // handle database-list -> database, database -> database-type@type
            baseNode.mergeChildWithChildKey(overrideNode, "database-list", "database", "name",
                    { MNode childBaseNode, MNode childOverrideNode -> childBaseNode.mergeNodeWithChildKey(childOverrideNode, "database-type", "type", null) })
        }

        baseNode.mergeChildWithChildKey(overrideNode, "repository-list", "repository", "name", {
            MNode childBaseNode, MNode childOverrideNode -> childBaseNode.mergeChildrenByKey(childOverrideNode, "init-param", "name", null) })

        // NOTE: don't merge component-list node, done separately (for runtime config only, and before component config merges)
    }

    protected static void mergeConfigComponentNodes(MNode baseNode, MNode overrideNode) {
        if (overrideNode.hasChild("component-list")) {
            if (!baseNode.hasChild("component-list")) baseNode.append("component-list", null)
            MNode baseComponentNode = baseNode.first("component-list")
            for (MNode copyNode in overrideNode.first("component-list").children) baseComponentNode.append(copyNode)
        }
    }

    protected static void mergeWebappChildNodes(MNode baseNode, MNode overrideNode) {
        baseNode.mergeChildrenByKey(overrideNode, "root-screen", "host", null)
        baseNode.mergeChildrenByKey(overrideNode, "error-screen", "error", null)
        // handle webapp -> first-hit-in-visit[1], after-request[1], before-request[1], after-login[1], before-logout[1]
        mergeWebappActions(baseNode, overrideNode, "first-hit-in-visit")
        mergeWebappActions(baseNode, overrideNode, "after-request")
        mergeWebappActions(baseNode, overrideNode, "before-request")
        mergeWebappActions(baseNode, overrideNode, "after-login")
        mergeWebappActions(baseNode, overrideNode, "before-logout")
        mergeWebappActions(baseNode, overrideNode, "after-startup")
        mergeWebappActions(baseNode, overrideNode, "before-shutdown")

        baseNode.mergeChildrenByKey(overrideNode, "filter", "name", { MNode childBaseNode, MNode childOverrideNode ->
            childBaseNode.mergeChildrenByKey(childOverrideNode, "init-param", "name", null)
            for (MNode upNode in overrideNode.children("url-pattern")) childBaseNode.append(upNode.deepCopy(null))
            for (MNode upNode in overrideNode.children("dispatcher")) childBaseNode.append(upNode.deepCopy(null))
        })
        baseNode.mergeChildrenByKey(overrideNode, "listener", "class", null)
        baseNode.mergeChildrenByKey(overrideNode, "servlet", "name", { MNode childBaseNode, MNode childOverrideNode ->
            childBaseNode.mergeChildrenByKey(childOverrideNode, "init-param", "name", null)
            for (MNode upNode in overrideNode.children("url-pattern")) childBaseNode.append(upNode.deepCopy(null))
        })
        baseNode.mergeSingleChild(overrideNode, "session-config")
    }

    protected static void mergeWebappActions(MNode baseWebappNode, MNode overrideWebappNode, String childNodeName) {
        List<MNode> overrideActionNodes = overrideWebappNode.first(childNodeName)?.first("actions")?.children
        if (overrideActionNodes) {
            MNode childNode = baseWebappNode.first(childNodeName)
            if (childNode == null) childNode = baseWebappNode.append(childNodeName, null)
            MNode actionsNode = childNode.first("actions")
            if (actionsNode == null) actionsNode = childNode.append("actions", null)

            for (MNode overrideActionNode in overrideActionNodes) actionsNode.append(overrideActionNode)
        }
    }

    MNode getWebappNode(String webappName) { return confXmlRoot.first("webapp-list")
            .first({ MNode it -> it.name == "webapp" && it.attribute("name") == webappName }) }

    WebappInfo getWebappInfo(String webappName) {
        if (webappInfoMap.containsKey(webappName)) return webappInfoMap.get(webappName)
        return makeWebappInfo(webappName)
    }
    protected synchronized WebappInfo makeWebappInfo(String webappName) {
        WebappInfo wi = new WebappInfo(webappName, this)
        webappInfoMap.put(webappName, wi)
        return wi
    }

    static class WebappInfo {
        String webappName
        MNode webappNode
        XmlAction firstHitInVisitActions = null
        XmlAction beforeRequestActions = null
        XmlAction afterRequestActions = null
        XmlAction afterLoginActions = null
        XmlAction beforeLogoutActions = null
        XmlAction afterStartupActions = null
        XmlAction beforeShutdownActions = null
        Integer sessionTimeoutSeconds = null

        WebappInfo(String webappName, ExecutionContextFactoryImpl ecfi) {
            this.webappName = webappName
            webappNode = ecfi.getWebappNode(webappName)
            init(ecfi)
        }

        void init(ExecutionContextFactoryImpl ecfi) {
            // prep actions
            if (webappNode.hasChild("first-hit-in-visit"))
                this.firstHitInVisitActions = new XmlAction(ecfi, webappNode.first("first-hit-in-visit").first("actions"),
                        "webapp_${webappName}.first_hit_in_visit.actions")

            if (webappNode.hasChild("before-request"))
                this.beforeRequestActions = new XmlAction(ecfi, webappNode.first("before-request").first("actions"),
                        "webapp_${webappName}.before_request.actions")
            if (webappNode.hasChild("after-request"))
                this.afterRequestActions = new XmlAction(ecfi, webappNode.first("after-request").first("actions"),
                        "webapp_${webappName}.after_request.actions")

            if (webappNode.hasChild("after-login"))
                this.afterLoginActions = new XmlAction(ecfi, webappNode.first("after-login").first("actions"),
                        "webapp_${webappName}.after_login.actions")
            if (webappNode.hasChild("before-logout"))
                this.beforeLogoutActions = new XmlAction(ecfi, webappNode.first("before-logout").first("actions"),
                        "webapp_${webappName}.before_logout.actions")

            if (webappNode.hasChild("after-startup"))
                this.afterStartupActions = new XmlAction(ecfi, webappNode.first("after-startup").first("actions"),
                        "webapp_${webappName}.after_startup.actions")
            if (webappNode.hasChild("before-shutdown"))
                this.beforeShutdownActions = new XmlAction(ecfi, webappNode.first("before-shutdown").first("actions"),
                        "webapp_${webappName}.before_shutdown.actions")

            MNode sessionConfigNode = webappNode.first("session-config")
            if (sessionConfigNode != null && sessionConfigNode.attribute("timeout")) {
                sessionTimeoutSeconds = (sessionConfigNode.attribute("timeout") as int) * 60
            }
        }

        MNode getErrorScreenNode(String error) {
            return webappNode.first({ MNode it -> it.name == "error-screen" && it.attribute("error") == error })
        }
    }

    @Override
    String toString() { return "ExecutionContextFactory" }
}
