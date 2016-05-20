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
package org.moqui.impl.tools

import org.kie.api.KieServices
import org.kie.api.builder.KieBuilder
import org.kie.api.builder.Message
import org.kie.api.builder.ReleaseId
import org.kie.api.builder.Results
import org.kie.api.runtime.KieContainer
import org.kie.api.runtime.KieSession
import org.kie.api.runtime.StatelessKieSession

import org.moqui.BaseException
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ResourceReference
import org.moqui.context.ToolFactory

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache

/** ElasticSearch Client is used for indexing and searching documents */
class KieToolFactory implements ToolFactory<KieToolFactory> {
    protected final static Logger logger = LoggerFactory.getLogger(KieToolFactory.class)
    final static String TOOL_NAME = "KIE"

    protected ExecutionContextFactory ecf = null

    KieServices services = null
    /** KIE ReleaseId Cache */
    protected Cache<String, ReleaseId> kieComponentReleaseIdCache = null
    /** KIE Component Cache */
    protected Cache<String, String> kieSessionComponentCache = null

    /** Default empty constructor */
    KieToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }
    @Override
    void init(ExecutionContextFactory ecf) {
        this.ecf = ecf

        kieComponentReleaseIdCache = ecf.cache.getCache("kie.component.releaseId", String.class, ReleaseId.class)
        kieSessionComponentCache = ecf.cache.getCache("kie.session.component", String.class, String.class)

        // if (!System.getProperty("drools.dialect.java.compiler")) System.setProperty("drools.dialect.java.compiler", "JANINO")
        if (!System.getProperty("drools.dialect.java.compiler")) System.setProperty("drools.dialect.java.compiler", "ECLIPSE")

        logger.info("Starting KIE (Drools, jBPM, etc)")
        services = KieServices.Factory.get()
        for (String componentName in ecf.componentBaseLocations.keySet()) {
            try {
                buildKieModule(componentName, services)
            } catch (Throwable t) {
                logger.error("Error initializing KIE in component ${componentName}: ${t.toString()}", t)
            }
        }
    }
    @Override
    void preFacadeInit(ExecutionContextFactory ecf) { }

    @Override
    KieToolFactory getInstance() { return this }

    @Override
    void destroy() {
        // TODO: any destroy for KIE?
    }

    ExecutionContextFactory getEcf() { return ecf }

    /** Get a KIE Container for Drools, jBPM, OptaPlanner, etc from the KIE Module in the given component. */
    KieContainer getKieContainer(String componentName) {
        ReleaseId releaseId = (ReleaseId) kieComponentReleaseIdCache.get(componentName)
        if (releaseId == null) releaseId = buildKieModule(componentName, services)

        if (releaseId != null) return services.newKieContainer(releaseId)
        return null
    }

    /** Get a KIE Session by name from the last component KIE Module loaded with the given session name. */
    KieSession getKieSession(String ksessionName) {
        String componentName = kieSessionComponentCache.get(ksessionName)
        // try finding all component sessions
        if (!componentName) findAllComponentKieSessions()
        componentName = kieSessionComponentCache.get(ksessionName)
        // still nothing? blow up
        if (!componentName) throw new IllegalStateException("No component KIE module found for session [${ksessionName}]")
        KieSession newSession = getKieContainer(componentName).newKieSession(ksessionName)
        newSession.setGlobal("ec", ecf.getExecutionContext())
        return newSession
    }
    /** Get a KIE Stateless Session by name from the last component KIE Module loaded with the given session name. */
    StatelessKieSession getStatelessKieSession(String ksessionName) {
        String componentName = kieSessionComponentCache.get(ksessionName)
        // try finding all component sessions
        if (!componentName) findAllComponentKieSessions()
        componentName = kieSessionComponentCache.get(ksessionName)
        // still nothing? blow up
        if (!componentName) throw new IllegalStateException("No component KIE module found for session [${ksessionName}]")
        StatelessKieSession newSession = getKieContainer(componentName).newStatelessKieSession(ksessionName)
        newSession.setGlobal("ec", ecf.getExecutionContext())
        return newSession
    }

    protected synchronized ReleaseId buildKieModule(String componentName, KieServices services) {
        ReleaseId releaseId = (ReleaseId) kieComponentReleaseIdCache.get(componentName)
        if (releaseId != null) return releaseId

        ResourceReference kieRr = ecf.resource.getLocationReference("component://${componentName}/kie")
        if (!kieRr.getExists() || !kieRr.isDirectory()) {
            if (logger.isTraceEnabled()) logger.trace("No kie directory in component ${componentName}, not building KIE module.")
            return null
        }

        /*
        if (componentName == "mantle-usl") {
            SpreadsheetCompiler sc = new SpreadsheetCompiler()
            String drl = sc.compile(getResourceFacade().getLocationStream("component://mantle-usl/kie/src/main/resources/mantle/shipment/orderrate/OrderShippingDt.xls"), InputType.XLS)
            StringBuilder groovyWithLines = new StringBuilder()
            int lineNo = 1
            for (String line in drl.split("\n")) groovyWithLines.append(lineNo++).append(" : ").append(line).append("\n")
            logger.error("XLS DC as DRL: [\n${groovyWithLines}\n]")
        }
        */

        try {
            File kieDir = new File(kieRr.getUrl().getPath())
            KieBuilder builder = services.newKieBuilder(kieDir)

            // build the KIE module
            builder.buildAll()
            Results results = builder.getResults()
            if (results.hasMessages(Message.Level.ERROR)) {
                throw new BaseException("Error building KIE module in component ${componentName}: ${results.toString()}")
            } else if (results.hasMessages(Message.Level.WARNING)) {
                logger.warn("Warning building KIE module in component ${componentName}: ${results.toString()}")
            }

            findComponentKieSessions(componentName)

            // get the release ID and cache it
            releaseId = builder.getKieModule().getReleaseId()
            kieComponentReleaseIdCache.put(componentName, releaseId)

            return releaseId
        } catch (Throwable t) {
            logger.error("Error initializing KIE at ${kieRr.getLocation()}", t)
            return null
        }
    }

    protected void findAllComponentKieSessions() {
        for (String componentName in ecf.componentBaseLocations.keySet())
            findComponentKieSessions(componentName)
    }
    protected void findComponentKieSessions(String componentName) {
        ResourceReference kieRr = ecf.resource.getLocationReference("component://${componentName}/kie")
        if (!kieRr.getExists() || !kieRr.isDirectory()) return

        // get all KieBase and KieSession names and create reverse-reference Map so we know which component's
        //     module they are in, then add convenience methods to get any KieBase or KieSession by name
        ResourceReference kmoduleRr = kieRr.findChildFile("src/main/resources/META-INF/kmodule.xml")
        Node kmoduleNode = new XmlParser().parseText(kmoduleRr.getText())
        for (Object kbObj in kmoduleNode.get("kbase")) {
            Node kbaseNode = (Node) kbObj
            for (Object ksObj in kbaseNode.get("ksession")) {
                Node ksessionNode = (Node) ksObj
                String ksessionName = (String) ksessionNode.attribute("name")
                String existingComponentName = kieSessionComponentCache.get(ksessionName)
                if (existingComponentName) logger.warn("Found KIE session [${ksessionName}] in component [${existingComponentName}], replacing with session in component [${componentName}]")
                kieSessionComponentCache.put(ksessionName, componentName)
            }
        }

    }
}
