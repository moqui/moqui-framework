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
package org.moqui.impl.service

import groovy.transform.CompileStatic
import org.moqui.Moqui
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.ArtifactExecutionInfoImpl.ArtifactTypeStats
import org.moqui.impl.context.ContextJavaUtil
import org.moqui.impl.context.ContextJavaUtil.CustomScheduledExecutor
import org.moqui.resource.ResourceReference
import org.moqui.context.ToolFactory
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.resource.ClasspathResourceReference
import org.moqui.impl.service.runner.EntityAutoServiceRunner
import org.moqui.impl.service.runner.RemoteJsonRpcServiceRunner
import org.moqui.service.*
import org.moqui.util.CollectionUtilities
import org.moqui.util.MNode
import org.moqui.util.ObjectUtilities
import org.moqui.util.RestClient
import org.moqui.util.StringUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache
import javax.mail.internet.MimeMessage
import java.sql.Timestamp
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

@CompileStatic
class ServiceFacadeImpl implements ServiceFacade {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceFacadeImpl.class)

    public final ExecutionContextFactoryImpl ecfi

    protected final Cache<String, ServiceDefinition> serviceLocationCache
    protected final ReentrantLock locationLoadLock = new ReentrantLock()

    protected Map<String, ArrayList<ServiceEcaRule>> secaRulesByServiceName = new HashMap<>()
    protected final List<EmailEcaRule> emecaRuleList = new ArrayList<>()
    public final RestApi restApi

    protected final Map<String, ServiceRunner> serviceRunners = new HashMap<>()

    private ScheduledJobRunner jobRunner = null
    public final ThreadPoolExecutor jobWorkerPool
    private LoadRunner loadRunner = null

    /** Distributed ExecutorService for async services, etc */
    protected ExecutorService distributedExecutorService = null

    protected final ConcurrentMap<String, List<ServiceCallback>> callbackRegistry = new ConcurrentHashMap<>()

    ServiceFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        serviceLocationCache = ecfi.cacheFacade.getCache("service.location", String.class, ServiceDefinition.class)

        MNode serviceFacadeNode = ecfi.confXmlRoot.first("service-facade")
        serviceFacadeNode.setSystemExpandAttributes(true)
        // load service runners from configuration
        for (MNode serviceType in serviceFacadeNode.children("service-type")) {
            ServiceRunner sr = (ServiceRunner) Thread.currentThread().getContextClassLoader()
                    .loadClass(serviceType.attribute("runner-class")).newInstance()
            serviceRunners.put(serviceType.attribute("name"), sr.init(this))
        }

        // load REST API
        restApi = new RestApi(ecfi)

        jobWorkerPool = makeWorkerPool()
    }

    private ThreadPoolExecutor makeWorkerPool() {
        MNode serviceFacadeNode = ecfi.confXmlRoot.first("service-facade")

        int jobQueueMax = (serviceFacadeNode.attribute("job-queue-max") ?: "0") as int
        int coreSize = (serviceFacadeNode.attribute("job-pool-core") ?: "2") as int
        int maxSize = (serviceFacadeNode.attribute("job-pool-max") ?: "8") as int
        int availableProcessorsSize = Runtime.getRuntime().availableProcessors() * 2
        if (availableProcessorsSize > maxSize) {
            logger.info("Setting Service Job worker pool size to ${availableProcessorsSize} based on available processors * 2")
            maxSize = availableProcessorsSize
        }
        long aliveTime = (serviceFacadeNode.attribute("worker-pool-alive") ?: "120") as long

        logger.info("Initializing Service Job ThreadPoolExecutor: queue limit ${jobQueueMax}, pool-core ${coreSize}, pool-max ${maxSize}, pool-alive ${aliveTime}s")
        // make the actual queue at least maxSize to allow for stuffing the queue to get it to add threads to the pool
        BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(jobQueueMax < maxSize ? maxSize : jobQueueMax)
        return new ContextJavaUtil.WorkerThreadPoolExecutor(ecfi, coreSize, maxSize, aliveTime, TimeUnit.SECONDS,
                workQueue, new ContextJavaUtil.JobThreadFactory())
    }

    void postFacadeInit() {
        // load Service ECA rules
        loadSecaRulesAll()
        // load Email ECA rules
        loadEmecaRulesAll()

        MNode serviceFacadeNode = ecfi.confXmlRoot.first("service-facade")

        // get distributed ExecutorService
        String distEsFactoryName = serviceFacadeNode.attribute("distributed-factory")
        if (distEsFactoryName) {
            logger.info("Getting Async Distributed Service ExecutorService (using ToolFactory ${distEsFactoryName})")
            ToolFactory<ExecutorService> esToolFactory = ecfi.getToolFactory(distEsFactoryName)
            if (esToolFactory == null) {
                logger.warn("Could not find ExecutorService ToolFactory with name ${distEsFactoryName}, distributed async service calls will be run local only")
                distributedExecutorService = null
            } else {
                distributedExecutorService = esToolFactory.getInstance()
            }
        } else {
            logger.info("No distributed-factory specified, distributed async service calls will be run local only")
            distributedExecutorService = null
        }

        // setup service job runner
        long jobRunnerRate = (serviceFacadeNode.attribute("scheduled-job-check-time") ?: "60") as long
        if (jobRunnerRate > 0L) {
            // wait before first run to make sure all is loaded and we're past an initial activity burst
            long initialDelay = 120L
            logger.info("Starting Scheduled Service Job Runner, checking for jobs every ${jobRunnerRate} seconds after a ${initialDelay} second initial delay")
            jobRunner = new ScheduledJobRunner(ecfi)
            ecfi.scheduleAtFixedRate(jobRunner, initialDelay, jobRunnerRate)
        } else {
            logger.warn("Not starting Scheduled Service Job Runner (config:${jobRunnerRate})")
            jobRunner = null
        }

    }

    void setDistributedExecutorService(ExecutorService executorService) {
        logger.info("Setting DistributedExecutorService to ${executorService.class.name}, was ${this.distributedExecutorService?.class?.name}")
        this.distributedExecutorService = executorService
    }

    void warmCache()  {
        logger.info("Warming cache for all service definitions")
        long startTime = System.currentTimeMillis()
        Set<String> serviceNames = getKnownServiceNames()
        for (String serviceName in serviceNames) {
            try { getServiceDefinition(serviceName) }
            catch (Throwable t) { logger.warn("Error warming service cache: ${t.toString()}") }
        }
        logger.info("Warmed service definition cache for ${serviceNames.size()} services in ${System.currentTimeMillis() - startTime}ms")
    }

    void destroy() {
        // destroy all service runners
        for (ServiceRunner sr in serviceRunners.values()) sr.destroy()
    }

    ServiceRunner getServiceRunner(String type) { serviceRunners.get(type) }
    // NOTE: this is used in the ServiceJobList screen
    ScheduledJobRunner getJobRunner() { jobRunner }

    boolean isServiceDefined(String serviceName) {
        ServiceDefinition sd = getServiceDefinition(serviceName)
        if (sd != null) return true

        String path = ServiceDefinition.getPathFromName(serviceName)
        String verb = ServiceDefinition.getVerbFromName(serviceName)
        String noun = ServiceDefinition.getNounFromName(serviceName)
        return isEntityAutoPattern(path, verb, noun)
    }

    boolean isEntityAutoPattern(String serviceName) {
        return isEntityAutoPattern(ServiceDefinition.getPathFromName(serviceName), ServiceDefinition.getVerbFromName(serviceName),
                ServiceDefinition.getNounFromName(serviceName))
    }

    boolean isEntityAutoPattern(String path, String verb, String noun) {
        // if no path, verb is create|update|delete and noun is a valid entity name, do an implicit entity-auto
        return (path == null || path.isEmpty()) && EntityAutoServiceRunner.verbSet.contains(verb) &&
                ecfi.entityFacade.isEntityDefined(noun)
    }

    ServiceDefinition getServiceDefinition(String serviceName) {
        if (serviceName == null) return null
        ServiceDefinition sd = (ServiceDefinition) serviceLocationCache.get(serviceName)
        if (sd != null) return sd


        // now try some acrobatics to find the service, these take longer to run hence trying to avoid
        String path = ServiceDefinition.getPathFromName(serviceName)
        String verb = ServiceDefinition.getVerbFromName(serviceName)
        String noun = ServiceDefinition.getNounFromName(serviceName)
        // logger.warn("Getting service definition for [${serviceName}], path=[${path}] verb=[${verb}] noun=[${noun}]")

        String cacheKey = makeCacheKey(path, verb, noun)
        boolean cacheKeySame = serviceName.equals(cacheKey)
        if (!cacheKeySame) {
            sd = (ServiceDefinition) serviceLocationCache.get(cacheKey)
            if (sd != null) return sd
        }

        // at this point sd is null (from serviceName and cacheKey), so if contains key we know the service doesn't exist; do in lock to avoid reload issues
        locationLoadLock.lock()
        try {
            if (serviceLocationCache.containsKey(serviceName)) return (ServiceDefinition) serviceLocationCache.get(serviceName)
            if (!cacheKeySame && serviceLocationCache.containsKey(cacheKey)) return (ServiceDefinition) serviceLocationCache.get(cacheKey)
        } finally {
            locationLoadLock.unlock()
        }

        return makeServiceDefinition(serviceName, path, verb, noun)
    }

    protected ServiceDefinition makeServiceDefinition(String origServiceName, String path, String verb, String noun) {
        locationLoadLock.lock()
        try {
            String cacheKey = makeCacheKey(path, verb, noun)
            if (serviceLocationCache.containsKey(cacheKey)) {
                // NOTE: this could be null if it's a known non-existing service
                return (ServiceDefinition) serviceLocationCache.get(cacheKey)
            }

            MNode serviceNode = findServiceNode(path, verb, noun)
            if (serviceNode == null) {
                // NOTE: don't throw an exception for service not found (this is where we know there is no def), let service caller handle that
                // Put null in the cache to remember the non-existing service
                serviceLocationCache.put(cacheKey, null)
                if (!origServiceName.equals(cacheKey)) serviceLocationCache.put(origServiceName, null)
                return null
            }

            ServiceDefinition sd = new ServiceDefinition(this, path, serviceNode)
            serviceLocationCache.put(cacheKey, sd)
            if (!origServiceName.equals(cacheKey)) serviceLocationCache.put(origServiceName, sd)
            return sd
        } finally {
            locationLoadLock.unlock()
        }
    }

    protected static String makeCacheKey(String path, String verb, String noun) {
        // use a consistent format as the key in the cache, keeping in mind that the verb and noun may be merged in the serviceName passed in
        // no # here so that it doesn't matter if the caller used one or not
        return (path != null && !path.isEmpty() ? path + '.' : '') + verb + (noun != null ? noun : '')
    }

    protected MNode findServiceNode(String path, String verb, String noun) {
        if (path == null || path.isEmpty()) return null

        // make a file location from the path
        String partialLocation = path.replace('.', '/') + '.xml'
        String servicePathLocation = 'service/' + partialLocation

        MNode serviceNode = (MNode) null
        ResourceReference foundRr = (ResourceReference) null

        // search for the service def XML file in the classpath LAST (allow components to override, same as in entity defs)
        ResourceReference serviceComponentRr = new ClasspathResourceReference().init(servicePathLocation)
        if (serviceComponentRr.supportsExists() && serviceComponentRr.exists) {
            serviceNode = findServiceNode(serviceComponentRr, verb, noun)
            if (serviceNode != null) foundRr == serviceComponentRr
        }

        // search for the service def XML file in the components
        for (String location in this.ecfi.getComponentBaseLocations().values()) {
            // logger.warn("Finding service node for location=[${location}], servicePathLocation=[${servicePathLocation}]")
            serviceComponentRr = this.ecfi.resourceFacade.getLocationReference(location + "/" + servicePathLocation)
            if (serviceComponentRr.supportsExists()) {
                if (serviceComponentRr.exists) {
                    MNode tempNode = findServiceNode(serviceComponentRr, verb, noun)
                    if (tempNode != null) {
                        if (foundRr != null) logger.info("Found service ${verb}#${noun} at ${serviceComponentRr.location} which overrides service at ${foundRr.location}")
                        serviceNode = tempNode
                        foundRr = serviceComponentRr
                    }
                }
            } else {
                // only way to see if it is a valid location is to try opening the stream, so no extra conditions here
                MNode tempNode = findServiceNode(serviceComponentRr, verb, noun)
                if (tempNode != null) {
                    if (foundRr != null) logger.info("Found service ${verb}#${noun} at ${serviceComponentRr.location} which overrides service at ${foundRr.location}")
                    serviceNode = tempNode
                    foundRr = serviceComponentRr
                }
            }
            // NOTE: don't quit on finding first, allow later components to override earlier: if (serviceNode != null) break
        }

        if (serviceNode == null) logger.warn("Service ${path}.${verb}#${noun} not found; used relative location [${servicePathLocation}]")

        return serviceNode
    }

    protected MNode findServiceNode(ResourceReference serviceComponentRr, String verb, String noun) {
        if (serviceComponentRr == null || (serviceComponentRr.supportsExists() && !serviceComponentRr.exists)) return null

        MNode serviceRoot = MNode.parse(serviceComponentRr)
        MNode serviceNode
        if (noun) {
            // only accept the separated names
            serviceNode = serviceRoot.first({ MNode it -> ("service".equals(it.name) || "service-include".equals(it.name)) &&
                    it.attribute("verb") == verb && it.attribute("noun") == noun })
        } else {
            // we just have a verb, this should work if the noun field is empty, or if noun + verb makes up the verb passed in
            serviceNode = serviceRoot.first({ MNode it -> ("service".equals(it.name) || "service-include".equals(it.name)) &&
                    (it.attribute("verb") + (it.attribute("noun") ?: "")) == verb })
        }

        // if we found a service-include look up the referenced service node
        if (serviceNode != null && "service-include".equals(serviceNode.name)) {
            String includeLocation = serviceNode.attribute("location")
            if (includeLocation == null || includeLocation.isEmpty()) {
                logger.error("Ignoring service-include with no location for verb ${verb} noun ${noun} in ${serviceComponentRr.location}")
                return null
            }

            ResourceReference includeRr = ecfi.resourceFacade.getLocationReference(includeLocation)
            // logger.warn("includeLocation: ${includeLocation}\nincludeRr: ${includeRr}")
            return findServiceNode(includeRr, verb, noun)
        }

        return serviceNode
    }

    Set<String> getKnownServiceNames() {
        Set<String> sns = new TreeSet<String>()

        // search declared service-file elements in Moqui Conf XML
        for (MNode serviceFile in ecfi.confXmlRoot.first("service-facade").children("service-file")) {
            String location = serviceFile.attribute("location")
            ResourceReference entryRr = ecfi.resourceFacade.getLocationReference(location)
            findServicesInFile("classpath://service", entryRr, sns)
        }

        // search for service def XML files in the components
        for (String location in this.ecfi.getComponentBaseLocations().values()) {
            //String location = "component://${componentName}/service"
            ResourceReference serviceRr = this.ecfi.resourceFacade.getLocationReference(location + "/service")
            if (serviceRr.supportsExists() && serviceRr.exists && serviceRr.supportsDirectory()) {
                findServicesInDir(serviceRr.location, serviceRr, sns)
            }
        }

        // TODO: how to search for service def XML files in the classpath? perhaps keep a list of service files that
        //     have been found on the classpath so we at least have those?

        return sns
    }

    List<Map> getAllServiceInfo(int levels) {
        Map<String, Map> serviceInfoMap = [:]
        for (String serviceName in getKnownServiceNames()) {
            int lastDotIndex = 0
            for (int i = 0; i < levels; i++) lastDotIndex = serviceName.indexOf(".", lastDotIndex+1)
            String name = lastDotIndex == -1 ? serviceName : serviceName.substring(0, lastDotIndex)
            Map curInfo = serviceInfoMap.get(name)
            if (curInfo) {
                CollectionUtilities.addToBigDecimalInMap("services", 1.0, curInfo)
            } else {
                serviceInfoMap.put(name, [name:name, services:1])
            }
        }
        TreeSet<String> nameSet = new TreeSet(serviceInfoMap.keySet())
        List<Map> serviceInfoList = []
        for (String name in nameSet) serviceInfoList.add(serviceInfoMap.get(name))
        return serviceInfoList
    }

    protected void findServicesInDir(String baseLocation, ResourceReference dir, Set<String> sns) {
        // logger.warn("Finding services in [${dir.location}]")
        for (ResourceReference entryRr in dir.directoryEntries) {
            if (entryRr.directory) {
                findServicesInDir(baseLocation, entryRr, sns)
            } else if (entryRr.fileName.endsWith(".xml")) {
                // logger.warn("Finding services in [${entryRr.location}], baseLocation=[${baseLocation}]")
                if (entryRr.fileName.endsWith(".secas.xml") || entryRr.fileName.endsWith(".emecas.xml") ||
                        entryRr.fileName.endsWith(".rest.xml")) continue
                findServicesInFile(baseLocation, entryRr, sns)
            }
        }
    }
    protected void findServicesInFile(String baseLocation, ResourceReference entryRr, Set<String> sns) {
        MNode serviceRoot = MNode.parse(entryRr)
        if ((serviceRoot.getName()) in ["secas", "emecas", "resource"]) return
        if (serviceRoot.getName() != "services") {
            logger.info("While finding service ignoring XML file [${entryRr.location}] in a services directory because the root element is ${serviceRoot.name} and not services")
            return
        }

        // get the service file location without the .xml and without everything up to the "service" directory
        String location = entryRr.location.substring(0, entryRr.location.lastIndexOf("."))
        if (location.startsWith(baseLocation)) location = location.substring(baseLocation.length())
        if (location.charAt(0) == '/' as char) location = location.substring(1)
        location = location.replace('/', '.')

        for (MNode serviceNode in serviceRoot.children) {
            String nodeName = serviceNode.name
            if (!"service".equals(nodeName) && !"service-include".equals(nodeName)) continue
            sns.add(location + "." + serviceNode.attribute("verb") +
                    (serviceNode.attribute("noun") ? "#" + serviceNode.attribute("noun") : ""))
        }
    }

    void loadSecaRulesAll() {
        int numLoaded = 0
        int numFiles = 0
        HashMap<String, ServiceEcaRule> ruleByIdMap = new HashMap<>()
        LinkedList<ServiceEcaRule> ruleNoIdList = new LinkedList<>()
        // search for the service def XML file in the components
        for (String location in this.ecfi.getComponentBaseLocations().values()) {
            ResourceReference serviceDirRr = this.ecfi.resourceFacade.getLocationReference(location + "/service")
            if (serviceDirRr.supportsAll()) {
                // if for some weird reason this isn't a directory, skip it
                if (!serviceDirRr.isDirectory()) continue
                for (ResourceReference rr in serviceDirRr.directoryEntries) {
                    if (!rr.fileName.endsWith(".secas.xml")) continue
                    numLoaded += loadSecaRulesFile(rr, ruleByIdMap, ruleNoIdList)
                    numFiles++
                }
            } else {
                logger.warn("Can't load SECA rules from component at [${serviceDirRr.location}] because it doesn't support exists/directory/etc")
            }
        }
        if (logger.infoEnabled) logger.info("Loaded ${numLoaded} Service ECA rules from ${numFiles} .secas.xml files, ${ruleNoIdList.size()} rules have no id, ${ruleNoIdList.size() + ruleByIdMap.size()} SECA rules active")

        Map<String, ArrayList<ServiceEcaRule>> ruleMap = new HashMap<>()
        ruleNoIdList.addAll(ruleByIdMap.values())
        for (ServiceEcaRule ecaRule in ruleNoIdList) {

            // find all matching services if the name is a pattern, otherwise just add the service name to the list
            boolean nameIsPattern = ecaRule.nameIsPattern
            List<String> serviceNameList = new ArrayList<>()
            if (nameIsPattern) {
                String serviceNamePattern = ecaRule.serviceName
                for (String ksn : knownServiceNames) {
                    if (ksn.matches(serviceNamePattern)) {
                        serviceNameList.add(ksn)
                    }
                }
            } else {
                serviceNameList.add(ecaRule.serviceName)
            }

            // add each of the services in the list to the rule map
            for (String serviceName in serviceNameList) {
                // remove the hash if there is one to more consistently match the service name
                serviceName = StringUtilities.removeChar(serviceName, (char) '#')
                ArrayList<ServiceEcaRule> lst = ruleMap.get(serviceName)
                if (lst == null) {
                    lst = new ArrayList<>()
                    ruleMap.put(serviceName, lst)
                }
                // insert by priority
                int insertIdx = 0
                for (int i = 0; i < lst.size(); i++) {
                    ServiceEcaRule lstSer = (ServiceEcaRule) lst.get(i)
                    if (lstSer.priority <= ecaRule.priority) { insertIdx++ } else { break }
                }
                lst.add(insertIdx, ecaRule)
            }
        }

        // replace entire SECA rules Map in one operation
        secaRulesByServiceName = ruleMap
    }
    protected int loadSecaRulesFile(ResourceReference rr, HashMap<String, ServiceEcaRule> ruleByIdMap, LinkedList<ServiceEcaRule> ruleNoIdList) {
        MNode serviceRoot = MNode.parse(rr)
        int numLoaded = 0
        for (MNode secaNode in serviceRoot.children("seca")) {
            // a service name is valid if it is not a pattern and represents a defined service or if it is a pattern and
            // matches at least one of the known service names
            String serviceName = secaNode.attribute("service")
            boolean nameIsPattern = secaNode.attribute("name-is-pattern") == "true"
            boolean serviceDefined = false
            if (nameIsPattern) {
                for (String ksn : knownServiceNames) {
                    serviceDefined = ksn.matches(serviceName)
                    if (serviceDefined) break
                }
            } else {
                serviceDefined = isServiceDefined(serviceName)
            }
            if (!serviceDefined) {
                logger.warn("Invalid service name ${serviceName} found in SECA file ${rr.location}, skipping")
                continue
            }

            ServiceEcaRule ecaRule = new ServiceEcaRule(ecfi, secaNode, rr.location)
            String ruleId = secaNode.attribute("id")
            if (ruleId != null && !ruleId.isEmpty()) ruleByIdMap.put(ruleId, ecaRule)
            else ruleNoIdList.add(ecaRule)

            numLoaded++
        }
        if (logger.isTraceEnabled()) logger.trace("Loaded ${numLoaded} Service ECA rules from [${rr.location}]")
        return numLoaded
    }

    ArrayList<ServiceEcaRule> secaRules(String serviceName) {
        // NOTE: no need to remove the hash, ServiceCallSyncImpl now passes a service name with no hash
        return (ArrayList<ServiceEcaRule>) secaRulesByServiceName.get(serviceName)
    }
    static void runSecaRules(String serviceName, Map<String, Object> parameters, Map<String, Object> results, String when,
                      ArrayList<ServiceEcaRule> lst, ExecutionContextImpl eci) {
        int lstSize = lst.size()
        for (int i = 0; i < lstSize; i++) {
            ServiceEcaRule ser = (ServiceEcaRule) lst.get(i)
            ser.runIfMatches(serviceName, parameters, results, when, eci)
        }
    }
    void registerTxSecaRules(String serviceName, Map<String, Object> parameters, Map<String, Object> results, ArrayList<ServiceEcaRule> lst) {
        int lstSize = lst.size()
        for (int i = 0; i < lstSize; i++) {
            ServiceEcaRule ser = (ServiceEcaRule) lst.get(i)
            if (ser.when.startsWith("tx-")) ser.registerTx(serviceName, parameters, results, ecfi)
        }
    }

    int getSecaRuleCount() {
        int count = 0
        for (List ruleList in secaRulesByServiceName.values()) count += ruleList.size()
        return count
    }


    protected void loadEmecaRulesAll() {
        if (emecaRuleList.size() > 0) emecaRuleList.clear()

        // search for the service def XML file in the components
        for (String location in this.ecfi.getComponentBaseLocations().values()) {
            ResourceReference serviceDirRr = this.ecfi.resourceFacade.getLocationReference(location + "/service")
            if (serviceDirRr.supportsAll()) {
                // if for some weird reason this isn't a directory, skip it
                if (!serviceDirRr.isDirectory()) continue
                for (ResourceReference rr in serviceDirRr.directoryEntries) {
                    if (!rr.fileName.endsWith(".emecas.xml")) continue
                    loadEmecaRulesFile(rr)
                }
            } else {
                logger.warn("Can't load Email ECA rules from component at [${serviceDirRr.location}] because it doesn't support exists/directory/etc")
            }
        }
    }
    protected void loadEmecaRulesFile(ResourceReference rr) {
        MNode emecasRoot = MNode.parse(rr)
        int numLoaded = 0
        for (MNode emecaNode in emecasRoot.children("emeca")) {
            EmailEcaRule eer = new EmailEcaRule(ecfi, emecaNode, rr.location)
            emecaRuleList.add(eer)
            numLoaded++
        }
        if (logger.infoEnabled) logger.info("Loaded [${numLoaded}] Email ECA rules from [${rr.location}]")
    }

    void runEmecaRules(MimeMessage message, String emailServerId) {
        ExecutionContextImpl eci = ecfi.getEci()
        for (EmailEcaRule eer in emecaRuleList) eer.runIfMatches(message, emailServerId, eci)
    }

    @Override
    ServiceCallSync sync() { return new ServiceCallSyncImpl(this) }
    @Override
    ServiceCallAsync async() { return new ServiceCallAsyncImpl(this) }
    @Override
    ServiceCallJob job(String jobName) { return new ServiceCallJobImpl(jobName, this) }

    @Override
    ServiceCallSpecial special() { return new ServiceCallSpecialImpl(this) }

    @Override
    Map<String, Object> callJsonRpc(String location, String method, Map<String, Object> parameters) {
        return RemoteJsonRpcServiceRunner.runJsonService(null, location, method, parameters, ecfi.getExecutionContext())
    }

    @Override
    RestClient rest() { return new RestClient() }

    @Override
    void registerCallback(String serviceName, ServiceCallback serviceCallback) {
        List<ServiceCallback> callbackList = callbackRegistry.get(serviceName)
        if (callbackList == null) {
            callbackList = new CopyOnWriteArrayList()
            callbackRegistry.putIfAbsent(serviceName, callbackList)
            callbackList = callbackRegistry.get(serviceName)
        }
        callbackList.add(serviceCallback)
    }

    void callRegisteredCallbacks(String serviceName, Map<String, Object> context, Map<String, Object> result) {
        List<ServiceCallback> callbackList = callbackRegistry.get(serviceName)
        if (callbackList != null && callbackList.size() > 0)
            for (ServiceCallback scb in callbackList) scb.receiveEvent(context, result)
    }

    void callRegisteredCallbacksThrowable(String serviceName, Map<String, Object> context, Throwable t) {
        List<ServiceCallback> callbackList = callbackRegistry.get(serviceName)
        if (callbackList != null && callbackList.size() > 0)
            for (ServiceCallback scb in callbackList) scb.receiveEvent(context, t)
    }

    // ==========================
    // Service LoadRunner Classes
    // ==========================

    synchronized LoadRunner getLoadRunner() {
        if (loadRunner == null) loadRunner = new LoadRunner(ecfi)
        return loadRunner
    }

    static class LoadRunnerServiceRunnable implements Runnable, Externalizable {
        volatile ExecutionContextFactoryImpl ecfi
        volatile LoadRunner loadRunner
        String serviceName, parametersExpr

        LoadRunnerServiceRunnable() {
            // init the other objects that can't be serialized
            ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
            loadRunner = ecfi.serviceFacade.getLoadRunner()
        }
        LoadRunnerServiceRunnable(String serviceName, String parametersExpr, LoadRunner loadRunner) {
            this.loadRunner = loadRunner
            this.ecfi = loadRunner.ecfi
            this.serviceName = serviceName
            this.parametersExpr = parametersExpr
        }

        @Override
        void writeExternal(ObjectOutput out) throws IOException {
            out.writeUTF(serviceName) // never null
            out.writeObject(parametersExpr) // may be null
        }

        @Override
        void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
            serviceName = objectInput.readUTF()
            parametersExpr = objectInput.readObject()
        }

        @Override void run() {
            try {
                runInternal()
            } catch (Throwable t) {
                logger.error("Error in LoadRunner service run", t)
            }
        }
        void runInternal() {
            // check for active Transaction
            if (getEcfi().transactionFacade.isTransactionInPlace()) {
                logger.error("In LoadRunner service ${serviceName} a transaction is in place for thread ${Thread.currentThread().getName()}, trying to commit")
                try {
                    getEcfi().transactionFacade.destroyAllInThread()
                } catch (Exception e) {
                    logger.error("LoadRunner commit in place transaction failed for thread ${Thread.currentThread().getName()}", e)
                }
            }
            // check for active ExecutionContext
            ExecutionContextImpl activeEc = getEcfi().activeContext.get()
            if (activeEc != null) {
                logger.error("In LoadRunner service ${serviceName} there is already an ExecutionContext for user ${activeEc.user.username} (from ${activeEc.forThreadId}:${activeEc.forThreadName}) in this thread ${Thread.currentThread().id}:${Thread.currentThread().name}, destroying")
                try {
                    activeEc.destroy()
                } catch (Throwable t) {
                    logger.error("Error destroying LoadRunner already in place in ServiceCallAsync in thread ${Thread.currentThread().id}:${Thread.currentThread().name}", t)
                }
            }

            LoadRunnerServiceInfo serviceInfo = loadRunner.getServiceInfo(serviceName, parametersExpr)
            if (serviceInfo == null) {
                logger.error("Service Info not found for ${serviceName} ${parametersExpr}, not running")
                return
            }
            String parametersExpr = serviceInfo.parametersExpr
            Map<String, Object> parameters = [index:loadRunner.execIndex.getAndIncrement()] as Map<String, Object>
            if (parametersExpr != null && !parametersExpr.isEmpty()) {
                try {
                    Map<String, Object> exprMap = (Map<String, Object>) loadRunner.ecfi.getEci().resourceFacade
                            .expression(parametersExpr, null, parameters)
                    if (exprMap != null) parameters.putAll(exprMap)
                } catch (Throwable t) {
                    logger.error("Error in Service LoadRunner parameter expression: ${parametersExpr}", t)
                }
            }

            // before starting, and tracking the startTime, do a small random delay for variation in run times
            if (serviceInfo.runDelayVaryMs != 0)
                Thread.sleep(ThreadLocalRandom.current().nextInt(serviceInfo.runDelayVaryMs))

            long startTime = System.currentTimeMillis()
            ExecutionContextImpl threadEci = ecfi.getEci()
            try {
                // always login anonymous, disable authz below
                threadEci.userFacade.loginAnonymousIfNoUser()

                // run the service
                try {
                    serviceInfo.lastResult = threadEci.serviceFacade.sync().name(serviceName)
                            .parameters(parameters).disableAuthz().call()
                } catch (Throwable t) {
                    // logged elsewhere, just count and swallow
                    serviceInfo.errorCount++
                }

                // count the run and accumulate stats
                serviceInfo.countRun(loadRunner, startTime, System.currentTimeMillis(),
                        threadEci.artifactExecutionFacade.getArtifactTypeStats())
            } finally {
                if (threadEci != null) threadEci.destroy()
            }
        }
    }
    static class LoadRunnerServiceStats {
        long lastRunTime = 0, beginTime = 0, totalTime = 0, totalSquaredTime = 0, minTime = Long.MAX_VALUE, maxTime = 0
        int runCount = 0, errorCount = 0
        ArtifactTypeStats artifactTypeStats = new ArtifactTypeStats()
        Map getMap() {
            Map newMap = [lastRunTime:lastRunTime, beginTime:beginTime, totalTime:totalTime,
                    totalSquaredTime:totalSquaredTime, minTime:minTime, maxTime:maxTime, runCount:runCount, errorCount:errorCount] as Map<String, Object>
            newMap.put("artifactTypeStats", ObjectUtilities.objectToMap(artifactTypeStats))
            return newMap
        }
    }
    static class LoadRunnerServiceInfo extends LoadRunnerServiceStats {
        String serviceName, parametersExpr
        int targetThreads, runDelayMs, runDelayVaryMs, rampDelayMs, timeBinLength, timeBinsKeep
        AtomicInteger currentThreads = new AtomicInteger(0)

        Map lastResult = null
        ConcurrentLinkedDeque<LoadRunnerServiceStats> timeBinList = new ConcurrentLinkedDeque<>()
        ArrayList<ScheduledFuture> runFutures = new ArrayList<>()
        ScheduledFuture rampFuture = null

        LoadRunnerServiceInfo(String serviceName, String parametersExpr, int targetThreads,
                int runDelayMs, int runDelayVaryMs, int rampDelayMs, int timeBinLength, int timeBinsKeep) {
            this.serviceName = serviceName; this.parametersExpr = parametersExpr
            this.targetThreads = targetThreads
            this.runDelayMs = runDelayMs; this.runDelayVaryMs = runDelayVaryMs; this.rampDelayMs = rampDelayMs
            this.timeBinLength = timeBinLength; this.timeBinsKeep = timeBinsKeep
        }

        void countRun(LoadRunner loadRunner, long startTime, long endTime, ArtifactTypeStats stats) {
            long runTime = endTime - startTime
            // logger.info("count run ${serviceName} ${runTime} ${Thread.currentThread().name}")
            LoadRunnerServiceStats curBin = null

            // find the current time bin in a semaphore locked section, the rest is increments and can be run multithreaded
            loadRunner.mutateLock.lock()
            // logger.info("count run after lock ${serviceName} ${runTime} ${Thread.currentThread().name}")
            try {
                if (beginTime == 0) beginTime = startTime

                curBin = timeBinList.isEmpty() ? null : timeBinList.getLast()
                // create and add a new bin if there are none or if this hit is after the bin's end time (need to advance the bin)
                if (curBin == null || curBin.beginTime + timeBinLength < startTime) {
                    curBin = new LoadRunnerServiceStats()
                    curBin.beginTime = startTime
                    timeBinList.add(curBin)
                    if (timeBinList.size() > timeBinsKeep) {
                        LoadRunnerServiceStats removeBin = timeBinList.removeFirst()
                        // logger.info("Removed time bin starting ${new Timestamp(removeBin.beginTime)} count ${removeBin.runCount}")
                    }
                }

                // some exceptions, these are multiple operations and need to be in the locked section to be accurate, maybe don't need to be...
                if (runTime < this.minTime) this.minTime = runTime
                if (runTime > this.maxTime) this.maxTime = runTime
                if (runTime < curBin.minTime) curBin.minTime = runTime
                if (runTime > curBin.maxTime) curBin.maxTime = runTime
            } finally {
                loadRunner.mutateLock.unlock()
                // logger.info("count run after unlock ${serviceName} ${runTime} ${Thread.currentThread().name}")
                // loadRunner.logFutures()
            }

            // for all runs
            this.runCount++
            this.lastRunTime = endTime
            this.totalTime += runTime
            this.totalSquaredTime += runTime * runTime
            this.artifactTypeStats.add(stats)

            // same thing for just this bin
            curBin.runCount++
            curBin.lastRunTime = endTime
            curBin.totalTime += runTime
            curBin.totalSquaredTime += runTime * runTime
            curBin.artifactTypeStats.add(stats)
        }

        void addThread(LoadRunner loadRunner) {
            LoadRunnerServiceRunnable runnable = new LoadRunnerServiceRunnable(serviceName, parametersExpr, loadRunner)
            // NOTE: use scheduleWithFixedDelay so delay is wait between terminate of one and start of another, better for both short and long running test runs
            ScheduledFuture future = loadRunner.scheduledExecutor.scheduleWithFixedDelay(runnable, 1, runDelayMs, TimeUnit.MILLISECONDS)
            // logger.info("Added run thread runDelayMs ${runDelayMs} ${runDelayMs?.class?.name} done ${future.done} ${future.toString()}")
            runFutures.add(future)
        }
        void addRampThread(LoadRunner loadRunner) {
            if (rampFuture != null && !rampFuture)
            beginTime = System.currentTimeMillis()
            LoadRunnerRamperRunnable runnable = new LoadRunnerRamperRunnable(loadRunner, this)
            // NOTE: use scheduleAtFixedRate so one is added each delay period regardless of how long it takes (generally not long)
            rampFuture = loadRunner.scheduledExecutor.scheduleAtFixedRate(runnable, 1, rampDelayMs, TimeUnit.MILLISECONDS)
        }
        void resetStats() {
            lastRunTime = 0; beginTime = 0; totalTime = 0; totalSquaredTime = 0; minTime = Long.MAX_VALUE; maxTime = 0
            runCount = 0; errorCount = 0
            artifactTypeStats = new ArtifactTypeStats()
            lastResult = null
            timeBinList = new ConcurrentLinkedDeque<>()
        }
    }
    static class LoadRunnerRamperRunnable implements Runnable {
        LoadRunner loadRunner
        LoadRunnerServiceInfo serviceInfo
        LoadRunnerRamperRunnable(LoadRunner loadRunner, LoadRunnerServiceInfo serviceInfo) {
            this.loadRunner = loadRunner
            this.serviceInfo = serviceInfo
        }
        @Override void run() {
            if (serviceInfo.currentThreads < serviceInfo.targetThreads) {
                serviceInfo.addThread(loadRunner)
                // may not actually need AtomicInteger here, but for ramp down will need a list of ScheduledFuture objects and might be useful there
                serviceInfo.currentThreads.incrementAndGet()
            }
            // TODO add delayed ramp-down, useful for some performance behavior patterns but usually redundant with delayed ramp up to look for elbows in the response time over time
        }
    }
    static class LoadRunnerThreadFactory implements ThreadFactory {
        private final ThreadGroup workerGroup = new ThreadGroup("LoadRunner")
        private final AtomicInteger threadNumber = new AtomicInteger(1)
        Thread newThread(Runnable r) { return new Thread(workerGroup, r, "LoadRunner-" + threadNumber.getAndIncrement()) }
    }

    static class LoadRunner {
        ExecutionContextFactoryImpl ecfi
        CustomScheduledExecutor scheduledExecutor = null
        ArrayList<LoadRunnerServiceInfo> serviceInfos = new ArrayList<>()
        Integer corePoolSize = 4, maxPoolSize = null
        AtomicInteger execIndex = new AtomicInteger(1)
        ReentrantLock mutateLock = new ReentrantLock()

        LoadRunner(ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi
        }

        LoadRunnerServiceInfo getServiceInfo(String serviceName, String parametersExpr) {
            for (int i = 0; i < serviceInfos.size(); i++) {
                LoadRunnerServiceInfo curInfo = (LoadRunnerServiceInfo) serviceInfos.get(i)
                if (curInfo.serviceName == serviceName && curInfo.parametersExpr == parametersExpr)
                    return curInfo
            }
            return null
        }
        void setServiceInfo(String serviceName, String parametersExpr, int targetThreads, int runDelayMs,
                int runDelayVaryMs, int rampDelayMs, int timeBinLength, int timeBinsKeep) {
            mutateLock.lock()
            try {
                LoadRunnerServiceInfo serviceInfo = getServiceInfo(serviceName, parametersExpr)
                if (serviceInfo == null) {
                    serviceInfo = new LoadRunnerServiceInfo(serviceName, parametersExpr, targetThreads,
                            runDelayMs, runDelayVaryMs, rampDelayMs, timeBinLength, timeBinsKeep)

                    serviceInfos.add(serviceInfo)

                    if (scheduledExecutor != null) {
                        // begin() already called, get this started
                        serviceInfo.addRampThread(this)
                    }
                } else {
                    serviceInfo.targetThreads = targetThreads
                    serviceInfo.runDelayMs = runDelayMs
                    serviceInfo.rampDelayMs = rampDelayMs
                    serviceInfo.timeBinLength = timeBinLength
                    serviceInfo.timeBinsKeep = timeBinsKeep
                }
            } finally {
                mutateLock.unlock()
            }
        }
        void begin() {
            mutateLock.lock()
            try {
                if (scheduledExecutor == null) {
                    // restart index
                    execIndex = new AtomicInteger(1)

                    for (int i = 0; i < serviceInfos.size(); i++) {
                        LoadRunnerServiceInfo curInfo = (LoadRunnerServiceInfo) serviceInfos.get(i)
                        // clear out stats before start
                        curInfo.currentThreads = new AtomicInteger(0)
                        curInfo.resetStats()
                    }

                    scheduledExecutor = new CustomScheduledExecutor(corePoolSize, new LoadRunnerThreadFactory())
                    if (maxPoolSize == null) maxPoolSize = Runtime.getRuntime().availableProcessors() * 4
                    scheduledExecutor.setMaximumPoolSize(maxPoolSize)

                    for (int i = 0; i < serviceInfos.size(); i++) {
                        LoadRunnerServiceInfo curInfo = (LoadRunnerServiceInfo) serviceInfos.get(i)
                        // get the ramp thread started
                        curInfo.addRampThread(this)
                    }
                }
            } finally {
                mutateLock.unlock()
            }
        }
        void stopNow() {
            mutateLock.lock()
            try {
                if (scheduledExecutor != null) {
                    logger.info("Shutting down LoadRunner ScheduledExecutorService now")
                    scheduledExecutor.shutdownNow()
                    scheduledExecutor = null
                }
            } finally {
                mutateLock.unlock()
            }
        }
        void stopWait() {
            mutateLock.lock()
            try {
                if (scheduledExecutor != null) {
                    logger.info("Shutting down LoadRunner ScheduledExecutorService")
                    scheduledExecutor.shutdown()
                }

                if (scheduledExecutor != null) {
                    scheduledExecutor.awaitTermination(30, TimeUnit.SECONDS)
                    if (scheduledExecutor.isTerminated()) logger.info("LoadRunner Scheduled executor shut down and terminated")
                    else logger.warn("LoadRunner Scheduled executor NOT YET terminated, waited 30 seconds")
                    scheduledExecutor = null
                }
            } finally {
                mutateLock.unlock()
            }
        }

        void logFutures() {
            for (int si = 0; si < serviceInfos.size(); si++) {
                LoadRunnerServiceInfo serviceInfo = serviceInfos.get(si)
                logger.info("LoadRunner RAMP Future done ${serviceInfo.rampFuture?.done} canceled ${serviceInfo.rampFuture?.cancelled} ${serviceInfo.rampFuture?.toString()}")
                for (int i = 0; i < serviceInfo.runFutures.size(); i++) {
                    ScheduledFuture future = serviceInfo.runFutures.get(i)
                    logger.info("LoadRunner RUN Future done ${future.done} canceled ${future.cancelled} ${future.toString()}")
                }
            }
        }
    }
}
