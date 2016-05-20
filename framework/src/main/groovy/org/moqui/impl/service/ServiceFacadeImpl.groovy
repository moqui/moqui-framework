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

import com.hazelcast.core.HazelcastInstance
import com.hazelcast.core.IExecutorService
import groovy.json.JsonBuilder
import groovy.transform.CompileStatic
import org.moqui.context.ResourceReference
import org.moqui.context.ToolFactory
import org.moqui.impl.StupidJavaUtilities
import org.moqui.impl.StupidUtilities
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.service.runner.EntityAutoServiceRunner
import org.moqui.impl.service.runner.RemoteJsonRpcServiceRunner
import org.moqui.impl.tools.HazelcastToolFactory
import org.moqui.service.RestClient
import org.moqui.service.ServiceFacade
import org.moqui.service.ServiceCallback
import org.moqui.service.ServiceCallSync
import org.moqui.service.ServiceCallAsync
import org.moqui.service.ServiceCallSchedule
import org.moqui.service.ServiceCallSpecial

import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.reference.ClasspathResourceReference
import org.moqui.util.MNode
import org.quartz.JobDetail
import org.quartz.JobExecutionContext
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.SchedulerException
import org.quartz.SchedulerListener
import org.quartz.Trigger
import org.quartz.TriggerKey
import org.quartz.TriggerListener
import org.quartz.impl.StdSchedulerFactory

import javax.cache.Cache
import javax.mail.internet.MimeMessage

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@CompileStatic
class ServiceFacadeImpl implements ServiceFacade {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceFacadeImpl.class)

    protected final ExecutionContextFactoryImpl ecfi

    protected final Cache<String, ServiceDefinition> serviceLocationCache

    protected final Map<String, ArrayList<ServiceEcaRule>> secaRulesByServiceName = new HashMap<>()
    protected final List<EmailEcaRule> emecaRuleList = new ArrayList()
    protected RestApi restApi

    protected final Map<String, ServiceRunner> serviceRunners = new HashMap()

    /** The Quartz Scheduler object */
    protected final Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler()
    protected final Map<String, Object> schedulerInfoMap

    /** Hazelcast Distributed ExecutorService for async services, etc */
    protected final IExecutorService hazelcastExecutorService

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

        // load service runners from configuration
        for (MNode serviceType in ecfi.confXmlRoot.first("service-facade").children("service-type")) {
            ServiceRunner sr = (ServiceRunner) Thread.currentThread().getContextClassLoader()
                    .loadClass(serviceType.attribute("runner-class")).newInstance()
            serviceRunners.put(serviceType.attribute("name"), sr.init(this))
        }

        // get Hazelcast ExecutorService
        logger.info("Getting Async Service Hazelcast ExecutorService")
        ToolFactory<HazelcastInstance> hzToolFactory = ecfi.getToolFactory(HazelcastToolFactory.TOOL_NAME)
        if (hzToolFactory == null) {
            logger.warn("Could not find Hazelcast ToolFactory, distributed async service calls will be run local only")
            hazelcastExecutorService = null
        } else {
            HazelcastInstance hazelcastInstance = hzToolFactory.getInstance()
            hazelcastExecutorService = hazelcastInstance.getExecutorService("service-executor")
        }

        // prep data for scheduler history listeners
        InetAddress localHost = ecfi.getLocalhostAddress()
        schedulerInfoMap = [hostAddress:(localHost?.getHostAddress() ?: '127.0.0.1'),
                hostName:(localHost?.getHostName() ?: 'localhost'), schedulerId:scheduler.getSchedulerInstanceId(),
                schedulerName:scheduler.getSchedulerName()] as Map<String, Object>

        scheduler.getListenerManager().addTriggerListener(new HistoryTriggerListener());
        scheduler.getListenerManager().addSchedulerListener(new HistorySchedulerListener());
    }

    void postInit() {
        // init quartz scheduler (do last just in case it gets any jobs going right away)
        scheduler.start()
        // TODO: add a job to delete scheduler history
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

        // destroy quartz scheduler, after allowing currently executing jobs to complete
        scheduler.shutdown(true)
    }

    @CompileStatic
    ExecutionContextFactoryImpl getEcfi() { return ecfi }

    @CompileStatic
    ServiceRunner getServiceRunner(String type) { return serviceRunners.get(type) }
    @CompileStatic
    RestApi getRestApi() { return restApi }

    @CompileStatic
    boolean isServiceDefined(String serviceName) {
        ServiceDefinition sd = getServiceDefinition(serviceName)
        if (sd != null) return true

        String path = ServiceDefinition.getPathFromName(serviceName)
        String verb = ServiceDefinition.getVerbFromName(serviceName)
        String noun = ServiceDefinition.getNounFromName(serviceName)
        return isEntityAutoPattern(path, verb, noun)
    }

    @CompileStatic
    boolean isEntityAutoPattern(String serviceName) {
        return isEntityAutoPattern(ServiceDefinition.getPathFromName(serviceName), ServiceDefinition.getVerbFromName(serviceName),
                ServiceDefinition.getNounFromName(serviceName))
    }

    @CompileStatic
    boolean isEntityAutoPattern(String path, String verb, String noun) {
        // if no path, verb is create|update|delete and noun is a valid entity name, do an implicit entity-auto
        return !path && EntityAutoServiceRunner.verbSet.contains(verb) && getEcfi().getEntityFacade("DEFAULT").isEntityDefined(noun)
    }


    @CompileStatic
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

    @CompileStatic
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
            if (origServiceName != cacheKey) serviceLocationCache.put(origServiceName, null)
            return null
        }

        ServiceDefinition sd = new ServiceDefinition(this, path, serviceNode)
        serviceLocationCache.put(cacheKey, sd)
        if (origServiceName != cacheKey) serviceLocationCache.put(origServiceName, sd)
        return sd
    }

    @CompileStatic
    protected static String makeCacheKey(String path, String verb, String noun) {
        // use a consistent format as the key in the cache, keeping in mind that the verb and noun may be merged in the serviceName passed in
        // no # here so that it doesn't matter if the caller used one or not
        return (path ? path + '.' : '') + verb + (noun ? noun : '')
    }

    protected MNode findServiceNode(String path, String verb, String noun) {
        if (!path) return null

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
            if (serviceNode) break
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

    @CompileStatic
    void runSecaRules(String serviceName, Map<String, Object> parameters, Map<String, Object> results, String when) {
        // NOTE: no need to remove the hash, ServiceCallSyncImpl now passes a service name with no hash
        // remove the hash if there is one to more consistently match the service name
        // serviceName = StupidJavaUtilities.removeChar(serviceName, (char) '#')
        ArrayList<ServiceEcaRule> lst = (ArrayList<ServiceEcaRule>) secaRulesByServiceName.get(serviceName)
        if (lst != null && lst.size() > 0) {
            ExecutionContextImpl eci = ecfi.getEci()
            for (int i = 0; i < lst.size(); i++) {
                ServiceEcaRule ser = (ServiceEcaRule) lst.get(i)
                ser.runIfMatches(serviceName, parameters, results, when, eci)
            }
        }
    }

    @CompileStatic
    void registerTxSecaRules(String serviceName, Map<String, Object> parameters, Map<String, Object> results) {
        // NOTE: no need to remove the hash, ServiceCallSyncImpl now passes a service name with no hash
        // remove the hash if there is one to more consistently match the service name
        // serviceName = StupidJavaUtilities.removeChar(serviceName, (char) '#')
        ArrayList<ServiceEcaRule> lst = secaRulesByServiceName.get(serviceName)
        if (lst != null && lst.size() > 0) for (ServiceEcaRule ser in lst) {
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

    @CompileStatic
    void runEmecaRules(MimeMessage message, String emailServerId) {
        ExecutionContextImpl eci = ecfi.getEci()
        for (EmailEcaRule eer in emecaRuleList) eer.runIfMatches(message, emailServerId, eci)
    }

    @Override
    @CompileStatic
    ServiceCallSync sync() { return new ServiceCallSyncImpl(this) }

    @Override
    @CompileStatic
    ServiceCallAsync async() { return new ServiceCallAsyncImpl(this) }

    @Override
    @CompileStatic
    ServiceCallSchedule schedule() { return new ServiceCallScheduleImpl(this) }

    @Override
    @CompileStatic
    ServiceCallSpecial special() { return new ServiceCallSpecialImpl(this) }

    @Override
    @CompileStatic
    Map<String, Object> callJsonRpc(String location, String method, Map<String, Object> parameters) {
        return RemoteJsonRpcServiceRunner.runJsonService(null, location, method, parameters, ecfi.getExecutionContext())
    }

    @Override
    @CompileStatic
    RestClient rest() { return new RestClientImpl(ecfi) }

    @Override
    @CompileStatic
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

    @Override
    @CompileStatic
    Scheduler getScheduler() { return scheduler }

    // ========== Quartz Listeners ==========

    @CompileStatic
    static boolean shouldSkipScheduleHistory(TriggerKey triggerKey) {
        // filter out high-frequency, temporary jobs (these are mostly async service calls)
        return triggerKey.getGroup() == "NowTrigger"
    }

    protected class HistorySchedulerListener implements SchedulerListener {
        @Override
        void jobScheduled(Trigger trigger) {
            if (shouldSkipScheduleHistory(trigger.getKey())) return
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvJobScheduled",
                        eventDate:new Timestamp(System.currentTimeMillis()), triggerGroup:trigger.getKey().getGroup(),
                        triggerName:trigger.getKey().getName(), jobGroup:trigger.getJobKey().getGroup(),
                        jobName:trigger.getJobKey().getName()] as Map<String, Object>)).disableAuthz().call()
        }
        @Override
        void jobUnscheduled(TriggerKey triggerKey) {
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvJobUnscheduled",
                        eventDate:new Timestamp(System.currentTimeMillis()), triggerGroup:triggerKey.getGroup(),
                        triggerName:triggerKey.getName()] as Map<String, Object>)).disableAuthz().call()
        }

        @Override
        void triggerPaused(TriggerKey triggerKey) {
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvTriggerPaused",
                        eventDate:new Timestamp(System.currentTimeMillis()), triggerGroup:triggerKey.getGroup(),
                        triggerName:triggerKey.getName()] as Map<String, Object>)).disableAuthz().call()
        }
        @Override
        void triggersPaused(String triggerGroup) {
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvTriggersPaused",
                            eventDate:new Timestamp(System.currentTimeMillis()),
                            triggerGroup:triggerGroup] as Map<String, Object>)).disableAuthz().call()
        }

        @Override
        void triggerResumed(TriggerKey triggerKey) {
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvTriggerResumed",
                        eventDate:new Timestamp(System.currentTimeMillis()), triggerGroup:triggerKey.getGroup(),
                        triggerName:triggerKey.getName()] as Map<String, Object>)).disableAuthz().call()
        }

        @Override
        void triggersResumed(String triggerGroup) {
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvTriggersResumed",
                        eventDate:new Timestamp(System.currentTimeMillis()),
                        triggerGroup:triggerGroup] as Map<String, Object>)).disableAuthz().call()
        }

        @Override
        void schedulerError(String msg, SchedulerException cause) {
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvSchedulerError",
                        eventDate:new Timestamp(System.currentTimeMillis()), message:msg] as Map<String, Object>))
                    .disableAuthz().call()
            // TODO: do anything with the cause?
        }

        @Override
        void schedulerInStandbyMode() { }
        @Override
        void schedulerStarting() { }
        @Override
        void schedulerStarted() { }
        @Override
        void schedulerShutdown() { }
        @Override
        void schedulerShuttingdown() { }

        @Override
        void schedulingDataCleared() { }

        @Override
        void jobAdded(JobDetail jobDetail) { }
        @Override
        void jobDeleted(JobKey jobKey) {
            /* do nothing, no easy way to filter the high-frequency jobs:
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + [eventTypeEnumId:"SchEvJobDeleted",
                    eventDate:new Timestamp(System.currentTimeMillis()),
                    jobGroup:jobKey.getGroup(), jobName:jobKey.getName()]).disableAuthz().call()
             */
        }

        @Override
        void jobPaused(JobKey jobKey) {
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvJobPaused",
                        eventDate:new Timestamp(System.currentTimeMillis()),
                        jobGroup:jobKey.getGroup(), jobName:jobKey.getName()] as Map<String, Object>)).disableAuthz().call()
        }
        @Override
        void jobResumed(JobKey jobKey) {
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvJobResumed",
                        eventDate:new Timestamp(System.currentTimeMillis()),
                        jobGroup:jobKey.getGroup(), jobName:jobKey.getName()] as Map<String, Object>)).disableAuthz().call()
        }
        @Override
        void jobsPaused(String jobGroup) {
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvJobsPaused",
                        eventDate:new Timestamp(System.currentTimeMillis()), jobGroup:jobGroup] as Map<String, Object>))
                    .disableAuthz().call()
        }
        @Override
        void jobsResumed(String jobGroup) {
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvJobsResumed",
                        eventDate:new Timestamp(System.currentTimeMillis()), jobGroup:jobGroup] as Map<String, Object>))
                    .disableAuthz().call()
        }

        @Override
        void triggerFinalized(Trigger trigger) {
            if (shouldSkipScheduleHistory(trigger.getKey())) return
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvTriggerFinalized",
                        eventDate:new Timestamp(System.currentTimeMillis()), triggerGroup:trigger.getKey().getGroup(),
                        triggerName:trigger.getKey().getName(), jobGroup:trigger.getJobKey().getGroup(),
                        jobName:trigger.getJobKey().getName()] as Map<String, Object>)).disableAuthz().call()
        }
    }

    protected class HistoryTriggerListener implements TriggerListener {
        @Override
        String getName() { return "Moqui.Service.HistoryTriggerListener" }

        @Override
        void triggerFired(Trigger trigger, JobExecutionContext context) {
            if (shouldSkipScheduleHistory(trigger.getKey())) return
            JsonBuilder jb = new JsonBuilder()
            jb.call(context.getMergedJobDataMap())
            String paramString = jb.toString()
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvTriggerFired",
                        eventDate:new Timestamp(context.getFireTime().getTime()),
                        triggerGroup:trigger.getKey().getGroup(), triggerName:trigger.getKey().getName(),
                        jobGroup:trigger.getJobKey().getGroup(), jobName:trigger.getJobKey().getName(),
                        fireInstanceId:context.getFireInstanceId(), paramString:paramString] as Map<String, Object>))
                    .disableAuthz().call()
        }

        @Override
        boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) { return false }

        @Override
        void triggerMisfired(Trigger trigger) {
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvTriggerMisfired",
                        eventDate:new Timestamp(System.currentTimeMillis()), triggerGroup:trigger.getKey().getGroup(),
                        triggerName:trigger.getKey().getName(), jobGroup:trigger.getJobKey().getGroup(),
                        jobName:trigger.getJobKey().getName()] as Map<String, Object>)).disableAuthz().call()
        }

        @Override
        void triggerComplete(Trigger trigger, JobExecutionContext context,
                                    Trigger.CompletedExecutionInstruction triggerInstructionCode) {
            if (shouldSkipScheduleHistory(trigger.getKey())) return
            JsonBuilder jb = new JsonBuilder()
            jb.call(context.getMergedJobDataMap())
            String paramString = jb.toString()
            sync().name("create#moqui.service.scheduler.SchedulerHistory")
                    .parameters(schedulerInfoMap + ([eventTypeEnumId:"SchEvTriggerComplete",
                        eventDate:new Timestamp(context.getFireTime().getTime()),
                        triggerGroup:trigger.getKey().getGroup(), triggerName:trigger.getKey().getName(),
                        jobGroup:trigger.getJobKey().getGroup(), jobName:trigger.getJobKey().getName(),
                        fireInstanceId:context.getFireInstanceId(), paramString:paramString,
                        triggerInstructionCode:triggerInstructionCode.toString()] as Map<String, Object>))
                    .disableAuthz().call()
        }
    }
}
