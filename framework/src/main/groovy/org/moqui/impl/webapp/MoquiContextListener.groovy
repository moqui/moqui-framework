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

import javax.servlet.ServletContext
import javax.servlet.ServletContextEvent
import javax.servlet.ServletContextListener

import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextFactoryImpl.WebappInfo
import org.moqui.Moqui

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class MoquiContextListener implements ServletContextListener {

    protected static String getId(ServletContext sc) {
        String contextPath = sc.getContextPath()
        return contextPath.length() > 1 ? contextPath.substring(1) : "ROOT"
    }

    void contextInitialized(ServletContextEvent servletContextEvent) {
        try {
            ServletContext sc = servletContextEvent.servletContext
            String webappId = getId(sc)
            String moquiWebappName = sc.getInitParameter("moqui-name")
            String webappRealPath = sc.getRealPath("/")

            // before we init the ECF, see if there is a runtime directory in the webappRealPath, and if so set that as the moqui.runtime System property
            if (new File(webappRealPath + "/runtime").exists()) System.setProperty("moqui.runtime", webappRealPath + "/runtime")

            ExecutionContextFactory ecfi = new ExecutionContextFactoryImpl()
            sc.setAttribute("executionContextFactory", ecfi)
            // there should always be one ECF that is active for things like deserialize of EntityValue
            // for a servlet that has a factory separate from the rest of the system DON'T call this (ie to have multiple ECFs on a single system)
            Moqui.dynamicInit(ecfi)

            Logger logger = LoggerFactory.getLogger(MoquiContextListener.class)
            logger.info("Loading Moqui Webapp at [${webappId}], moqui webapp name [${moquiWebappName}], context name [${sc.getServletContextName()}], located at [${webappRealPath}]")

            // run after-startup actions
            WebappInfo wi = ecfi.getWebappInfo(moquiWebappName)
            if (wi.afterStartupActions) {
                ExecutionContext ec = ecfi.getExecutionContext()
                wi.afterStartupActions.run(ec)
                ec.destroy()
            }
        } catch (Throwable t) {
            System.out.println("Error initializing webapp context: ${t.toString()}")
            t.printStackTrace()
            throw t
        }
    }

    void contextDestroyed(ServletContextEvent servletContextEvent) {
        ServletContext sc = servletContextEvent.servletContext
        String webappId = getId(sc)
        String moquiWebappName = sc.getInitParameter("moqui-name")

        Logger logger = LoggerFactory.getLogger(MoquiContextListener.class)
        logger.info("Destroying Moqui Execution Context Factory for webapp [${webappId}]")
        if (sc.getAttribute("executionContextFactory")) {
            ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) sc.getAttribute("executionContextFactory")

            // run before-shutdown actions
            WebappInfo wi = ecfi.getWebappInfo(moquiWebappName)
            if (wi.beforeShutdownActions) {
                ExecutionContext ec = ecfi.getExecutionContext()
                wi.beforeShutdownActions.run(ec)
                ec.destroy()
            }

            sc.removeAttribute("executionContextFactory")
            ecfi.destroy()
        }
        logger.info("Destroyed Moqui Execution Context Factory for webapp [${webappId}]")
    }
}
