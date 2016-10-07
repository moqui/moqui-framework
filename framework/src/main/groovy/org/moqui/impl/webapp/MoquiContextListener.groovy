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
package org.moqui.impl.webapp

import groovy.transform.CompileStatic
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.util.MNode

import javax.servlet.DispatcherType
import javax.servlet.Filter
import javax.servlet.FilterConfig
import javax.servlet.FilterRegistration
import javax.servlet.Servlet
import javax.servlet.ServletConfig
import javax.servlet.ServletContext
import javax.servlet.ServletContextEvent
import javax.servlet.ServletContextListener

import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextFactoryImpl.WebappInfo
import org.moqui.Moqui

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.ServletRegistration
import javax.websocket.HandshakeResponse
import javax.websocket.server.HandshakeRequest
import javax.websocket.server.ServerContainer
import javax.websocket.server.ServerEndpointConfig

@CompileStatic
class MoquiContextListener implements ServletContextListener {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiContextListener.class)

    protected static String getId(ServletContext sc) {
        String contextPath = sc.getContextPath()
        return contextPath.length() > 1 ? contextPath.substring(1) : "ROOT"
    }

    protected ExecutionContextFactoryImpl ecfi = null

    void contextInitialized(ServletContextEvent servletContextEvent) {
        long initStartTime = System.currentTimeMillis()

        try {
            ServletContext sc = servletContextEvent.servletContext
            String webappId = getId(sc)
            String moquiWebappName = sc.getInitParameter("moqui-name")

            // before we init the ECF, see if there is a runtime directory in the webappRealPath, and if so set that as the moqui.runtime System property
            String webappRealPath = sc.getRealPath("/")
            String embeddedRuntimePath = webappRealPath + "/runtime"
            if (new File(embeddedRuntimePath).exists()) System.setProperty("moqui.runtime", embeddedRuntimePath)

            logger.info("Loading Webapp '${moquiWebappName}' (${sc.getServletContextName()}) on ${webappId}, located at: ${webappRealPath}")

            ecfi = Moqui.dynamicInit(ExecutionContextFactoryImpl.class, sc)

            WebappInfo wi = ecfi.getWebappInfo(moquiWebappName)

            // add webapp filters, listeners, servlets
            for (MNode filterNode in wi.webappNode.children("filter")) {
                if (filterNode.attribute("enabled") == "false") continue

                String filterName = filterNode.attribute("name")
                try {
                    Filter filter = (Filter) Thread.currentThread().getContextClassLoader().loadClass(filterNode.attribute("class")).newInstance()
                    MapFilterConfig filterConfig = new MapFilterConfig(filterName, sc)
                    for (MNode initParamNode in filterNode.children("init-param"))
                        filterConfig.setParameter(initParamNode.attribute("name"), initParamNode.attribute("value") ?: "")
                    filter.init(filterConfig)
                    FilterRegistration.Dynamic filterReg = sc.addFilter(filterName, filter)

                    EnumSet<DispatcherType> dispatcherTypes = EnumSet.noneOf(DispatcherType.class)
                    for (MNode dispatcherNode in filterNode.children("dispatcher"))
                        dispatcherTypes.add(DispatcherType.valueOf(dispatcherNode.getText()))

                    Set<String> urlPatternSet = new LinkedHashSet<>()
                    for (MNode urlPatternNode in filterNode.children("url-pattern")) urlPatternSet.add(urlPatternNode.getText())
                    String[] urlPatterns = urlPatternSet.toArray(new String[urlPatternSet.size()])

                    filterReg.addMappingForUrlPatterns(dispatcherTypes, false, urlPatterns)

                    logger.info("Added webapp filter ${filterName} on: ${urlPatterns}, ${dispatcherTypes}")
                } catch (Exception e) {
                    logger.error("Error adding filter ${filterName}", e)
                }
            }

            for (MNode listenerNode in wi.webappNode.children("listener")) {
                if (listenerNode.attribute("enabled") == "false") continue
                String className = listenerNode.attribute("class")
                try {
                    EventListener listener = (EventListener) Thread.currentThread().getContextClassLoader().loadClass(className).newInstance()
                    sc.addListener(listener)
                    logger.info("Added webapp listener ${className}")
                } catch (Exception e) {
                    logger.error("Error adding listener ${className}", e)
                }
            }

            for (MNode servletNode in wi.webappNode.children("servlet")) {
                if (servletNode.attribute("enabled") == "false") continue

                String servletName = servletNode.attribute("name")
                try {
                    Servlet servlet = (Servlet) Thread.currentThread().getContextClassLoader().loadClass(servletNode.attribute("class")).newInstance()
                    MapServletConfig servletConfig = new MapServletConfig(servletName, sc)
                    for (MNode initParamNode in servletNode.children("init-param"))
                        servletConfig.setParameter(initParamNode.attribute("name"), initParamNode.attribute("value") ?: "")
                    servlet.init(servletConfig)
                    ServletRegistration.Dynamic servletReg = sc.addServlet(servletName, servlet)

                    String loadOnStartupStr = servletNode.attribute("load-on-startup") ?: "1"
                    servletReg.setLoadOnStartup(loadOnStartupStr as int)

                    Set<String> urlPatternSet = new LinkedHashSet<>()
                    for (MNode urlPatternNode in servletNode.children("url-pattern")) urlPatternSet.add(urlPatternNode.getText())
                    String[] urlPatterns = urlPatternSet.toArray(new String[urlPatternSet.size()])

                    Set<String> alreadyMapped = servletReg.addMapping(urlPatterns)
                    if (alreadyMapped) logger.warn("For servlet ${servletName} to following URL patterns were already mapped: ${alreadyMapped}")

                    logger.info("Added servlet ${servletName} on: ${urlPatterns}")
                } catch (Exception e) {
                    logger.error("Error adding servlet ${servletName}", e)
                }
            }

            // NOTE: webapp.session-config.@timeout handled in MoquiSessionListener

            // WebSocket Endpoint Setup
            ServerContainer wsServer = ecfi.getServerContainer()
            if (wsServer != null) {
                logger.info("Found WebSocket ServerContainer ${wsServer.class.name}")
                if (wi.webappNode.attribute("websocket-timeout"))
                    wsServer.setDefaultMaxSessionIdleTimeout(Long.valueOf(wi.webappNode.attribute("websocket-timeout")))

                for (MNode endpointNode in wi.webappNode.children("endpoint")) {
                    if (endpointNode.attribute("enabled") == "false") continue

                    try {
                        Class<?> endpointClass = Thread.currentThread().getContextClassLoader().loadClass(endpointNode.attribute("class"))
                        String endpointPath = endpointNode.attribute("path")
                        if (!endpointPath.startsWith("/")) endpointPath = "/" + endpointPath

                        MoquiServerEndpointConfigurator configurator = new MoquiServerEndpointConfigurator(ecfi, endpointNode.attribute("timeout"))
                        ServerEndpointConfig sec = ServerEndpointConfig.Builder.create(endpointClass, endpointPath)
                                .configurator(configurator).build()
                        wsServer.addEndpoint(sec)

                        logger.info("Added WebSocket endpoint ${endpointPath} for class ${endpointClass.name}")
                    } catch (Exception e) {
                        logger.error("Error WebSocket endpoint on ${endpointNode.attribute("path")}", e)
                    }
                }
            } else {
                logger.info("No WebSocket ServerContainer found, web sockets disabled")
            }

            // run after-startup actions
            if (wi.afterStartupActions) {
                ExecutionContextImpl eci = ecfi.getEci()
                wi.afterStartupActions.run(eci)
                eci.destroy()
            }

            logger.info("Moqui Framework initialized in ${(System.currentTimeMillis() - initStartTime)/1000} seconds")
        } catch (Throwable t) {
            logger.error("Error initializing webapp context: ${t.toString()}", t)
            throw t
        }
    }

    void contextDestroyed(ServletContextEvent servletContextEvent) {
        ServletContext sc = servletContextEvent.servletContext
        String webappId = getId(sc)
        String moquiWebappName = sc.getInitParameter("moqui-name")

        logger.info("Context Destroyed for Moqui webapp [${webappId}]")
        if (ecfi != null) {
            // run before-shutdown actions
            WebappInfo wi = ecfi.getWebappInfo(moquiWebappName)
            if (wi.beforeShutdownActions) {
                ExecutionContextImpl eci = ecfi.getEci()
                wi.beforeShutdownActions.run(eci)
                eci.destroy()
            }

            ecfi.destroy()
            ecfi = null
        } else {
            logger.warn("No ExecutionContextFactoryImpl referenced, not destroying")
        }
        logger.info("Destroyed Moqui Execution Context Factory for webapp [${webappId}]")
    }

    static class MapFilterConfig implements FilterConfig {
        private String name
        private ServletContext sc
        private Map<String, String> parameters = new HashMap<>()
        MapFilterConfig(String name, ServletContext sc) {
            this.name = name
            this.sc = sc
        }
        void setParameter(String name, String value) { parameters.put(name, value) }
        @Override
        String getFilterName() { return name }
        @Override
        ServletContext getServletContext() { return sc }
        @Override
        String getInitParameter(String name) { return parameters.get(name) }
        @Override
        Enumeration<String> getInitParameterNames() { return Collections.enumeration(parameters.keySet()) }
    }
    static class MapServletConfig implements ServletConfig {
        private String name
        private ServletContext sc
        private Map<String, String> parameters = new HashMap<>()
        MapServletConfig(String name, ServletContext sc) {
            this.name = name
            this.sc = sc
        }
        void setParameter(String name, String value) { parameters.put(name, value) }
        @Override
        String getServletName() { return name }
        @Override
        ServletContext getServletContext() { return sc }
        @Override
        String getInitParameter(String name) { return parameters.get(name) }
        @Override
        Enumeration<String> getInitParameterNames() { return Collections.enumeration(parameters.keySet()) }
    }
    static class MoquiServerEndpointConfigurator extends ServerEndpointConfig.Configurator {
        // for a good explanation of javax.websocket details related to this see:
        // http://stackoverflow.com/questions/17936440/accessing-httpsession-from-httpservletrequest-in-a-web-socket-serverendpoint
        ExecutionContextFactoryImpl ecfi
        Long maxIdleTimeout = null
        MoquiServerEndpointConfigurator(ExecutionContextFactoryImpl ecfi, String timeoutStr) {
            this.ecfi = ecfi
            if (timeoutStr) maxIdleTimeout = Long.valueOf(timeoutStr)
        }
        @Override
        boolean checkOrigin(String originHeaderValue) {
            // logger.info("New ServerEndpoint Origin: ${originHeaderValue}")
            // TODO: check this against what? will be something like 'http://localhost:8080'
            return super.checkOrigin(originHeaderValue)
        }

        @Override
        public void modifyHandshake(ServerEndpointConfig config, HandshakeRequest request, HandshakeResponse response) {
            config.getUserProperties().put("handshakeRequest", request)
            config.getUserProperties().put("httpSession", request.getHttpSession())
            config.getUserProperties().put("executionContextFactory", ecfi)
            if (maxIdleTimeout != null) config.getUserProperties().put("maxIdleTimeout", maxIdleTimeout)
        }
    }
}
