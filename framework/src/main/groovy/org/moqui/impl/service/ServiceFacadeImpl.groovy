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
import org.moqui.context.ResourceReference
import org.moqui.context.ToolFactory
import org.moqui.impl.StupidJavaUtilities
import org.moqui.impl.StupidUtilities
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.reference.ClasspathResourceReference
import org.moqui.impl.service.runner.EntityAutoServiceRunner
import org.moqui.impl.service.runner.RemoteJsonRpcServiceRunner
import org.moqui.service.*
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache
import javax.mail.internet.MimeMessage
import java.util.concurrent.*

@CompileStatic
class ServiceFacadeImpl implements ServiceFacade {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceFacadeImpl.class)

    public final ExecutionContextFactoryImpl ecfi

    protected final Cache<String, ServiceDefinition> serviceLocationCache

    protected final Map<String, ArrayList<ServiceEcaRule>> secaRulesByServiceName = new HashMap<>()
    protected final List<EmailEcaRule> emecaRuleList = new ArrayList()
    public final RestApi restApi

    protected final Map<String, ServiceRunner> serviceRunners = new HashMap()

    private final ScheduledJobRunner jobRunner

    /** Distributed ExecutorService for async services, etc */
    protected final ExecutorService distributedExecutorService

    protected final ConcurrentMap<String, List<ServiceCallback>> callbackRegistry = new ConcurrentHashMap<>()

    ServiceFacadeImpl(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        serviceLocationCache = ecfi.getCacheFacade().getCache("service.location", String.class, ServiceDefinition.class)

        // load Service ECA rules
        loadSecaRulesAll()
        // load Email ECA rules
        loadEmecaRulesAll()
        // load REST API
        restApi = new RestApi(ecfi)

        MNode serviceFacadeNode = ecfi.confXmlRoot.first("service-facade")

        // load service runners from configuration
        for (MNode serviceType in serviceFacadeNode.children("service-type")) {
            ServiceRunner sr = (ServiceRunner) Thread.currentThread().getContextClassLoader()
                    .loadClass(serviceType.attribute("runner-class")).newInstance()
            serviceRunners.put(serviceType.attribute("name"), sr.init(this))
        }

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
            jobRunner = new ScheduledJobRunner(ecfi)
            // wait 60 seconds before first run to make sure all is loaded and we're past an initial activity burst
            ecfi.scheduledExecutor.scheduleAtFixedRate(jobRunner, 60, jobRunnerRate, TimeUnit.SECONDS)
        } else {
            jobRunner = null
        }
    }

    void postInit() {
        // no longer used, was used to start Quartz Scheduler
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
        ServiceDefinition sd = (ServiceDefinition) serviceLocationCache.get(serviceName)
        if (sd != null) return sd

        // at this point sd is null, so if contains key we know the service doesn't exist
        if (serviceLocationCache.containsKey(serviceName)) return null

        // now try some acrobatics to find the service, these take longer to run hence trying to avoid
        String path = ServiceDefinition.getPathFromName(serviceName)
        String verb = ServiceDefinition.getVerbFromName(serviceName)
        String noun = ServiceDefinition.getNounFromName(serviceName)
        // logger.warn("Getting service definition for [${serviceName}], path=[${path}] verb=[${verb}] noun=[${noun}]")

        String cacheKey = makeCacheKey(path, verb, noun)
        sd = (ServiceDefinition) serviceLocationCache.get(cacheKey)
        if (sd != null) return sd
        if (serviceLocationCache.containsKey(cacheKey)) return null

        return makeServiceDefinition(serviceName, path, verb, noun)
    }

    protected synchronized ServiceDefinition makeServiceDefinition(String origServiceName, String path, String verb, String noun) {
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

        MNode serviceNode = null

        // search for the service def XML file in the components
        for (String location in this.ecfi.getComponentBaseLocations().values()) {
            // logger.warn("Finding service node for location=[${location}], servicePathLocation=[${servicePathLocation}]")
            ResourceReference serviceComponentRr = this.ecfi.resourceFacade.getLocationReference(location + "/" + servicePathLocation)
            if (serviceComponentRr.supportsExists()) {
                if (serviceComponentRr.exists) serviceNode = findServiceNode(serviceComponentRr, verb, noun)
            } else {
                // only way to see if it is a valid location is to try opening the stream, so no extra conditions here
                serviceNode = findServiceNode(serviceComponentRr, verb, noun)
            }
            if (serviceNode != null) break
        }

        // search for the service def XML file in the classpath LAST (allow components to override, same as in entity defs)
        if (serviceNode == null) {
            ResourceReference serviceComponentRr = new ClasspathResourceReference().init(servicePathLocation, ecfi)
            if (serviceComponentRr.supportsExists() && serviceComponentRr.exists)
                serviceNode = findServiceNode(serviceComponentRr, verb, noun)
        }

        if (serviceNode == null) logger.info("Service ${path}.${verb}#${noun} not found; used relative location [${servicePathLocation}]")

        return serviceNode
    }

    protected static MNode findServiceNode(ResourceReference serviceComponentRr, String verb, String noun) {
        if (serviceComponentRr == null || !serviceComponentRr.exists) return null

        MNode serviceRoot = MNode.parse(serviceComponentRr)
        MNode serviceNode
        if (noun) {
            // only accept the separated names
            serviceNode = serviceRoot.first({ MNode it -> it.name == "service" && it.attribute("verb") == verb && it.attribute("noun") == noun })
        } else {
            // we just have a verb, this should work if the noun field is empty, or if noun + verb makes up the verb passed in
            serviceNode = serviceRoot.first({ MNode it -> it.name == "service" && (it.attribute("verb") + (it.attribute("noun") ?: "")) == verb })
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
                StupidUtilities.addToBigDecimalInMap("services", 1.0, curInfo)
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
        if ((serviceRoot.name) in ["secas", "emecas", "resource"]) return
        if (serviceRoot.name != "services") {
            logger.info("While finding service ignoring XML file [${entryRr.location}] in a services directory because the root element is ${serviceRoot.name} and not services")
            return
        }

        // get the service file location without the .xml and without everything up to the "service" directory
        String location = entryRr.location.substring(0, entryRr.location.lastIndexOf("."))
        if (location.startsWith(baseLocation)) location = location.substring(baseLocation.length())
        if (location.charAt(0) == '/' as char) location = location.substring(1)
        location = location.replace('/', '.')

        for (MNode serviceNode in serviceRoot.children("service")) {
            sns.add(location + "." + serviceNode.attribute("verb") +
                    (serviceNode.attribute("noun") ? "#" + serviceNode.attribute("noun") : ""))
        }
    }

    protected void loadSecaRulesAll() {
        if (secaRulesByServiceName.size() > 0) secaRulesByServiceName.clear()

        int numLoaded = 0
        int numFiles = 0
        // search for the service def XML file in the components
        for (String location in this.ecfi.getComponentBaseLocations().values()) {
            ResourceReference serviceDirRr = this.ecfi.resourceFacade.getLocationReference(location + "/service")
            if (serviceDirRr.supportsAll()) {
                // if for some weird reason this isn't a directory, skip it
                if (!serviceDirRr.isDirectory()) continue
                for (ResourceReference rr in serviceDirRr.directoryEntries) {
                    if (!rr.fileName.endsWith(".secas.xml")) continue
                    numLoaded += loadSecaRulesFile(rr)
                    numFiles++
                }
            } else {
                logger.warn("Can't load SECA rules from component at [${serviceDirRr.location}] because it doesn't support exists/directory/etc")
            }
        }
        if (logger.infoEnabled) logger.info("Loaded ${numLoaded} Service ECA rules from ${numFiles} .secas.xml files")
    }
    protected int loadSecaRulesFile(ResourceReference rr) {
        MNode serviceRoot = MNode.parse(rr)
        int numLoaded = 0
        for (MNode secaNode in serviceRoot.children("seca")) {
            ServiceEcaRule ser = new ServiceEcaRule(ecfi, secaNode, rr.location)
            String serviceName = ser.serviceName
            // remove the hash if there is one to more consistently match the service name
            serviceName = StupidJavaUtilities.removeChar(serviceName, (char) '#')
            List<ServiceEcaRule> lst = secaRulesByServiceName.get(serviceName)
            if (lst == null) {
                lst = new ArrayList<>()
                secaRulesByServiceName.put(serviceName, lst)
            }
            lst.add(ser)
            numLoaded++
        }
        if (logger.isTraceEnabled()) logger.trace("Loaded [${numLoaded}] Service ECA rules from [${rr.location}]")
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
    RestClient rest() { return new RestClientImpl(ecfi) }

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
}
