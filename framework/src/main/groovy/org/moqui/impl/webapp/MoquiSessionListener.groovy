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
import org.moqui.Moqui
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.http.HttpSessionAttributeListener
import javax.servlet.http.HttpSessionBindingEvent
import java.sql.Timestamp
import javax.servlet.http.HttpSessionListener
import javax.servlet.http.HttpSession
import javax.servlet.http.HttpSessionEvent

import org.moqui.impl.context.ExecutionContextFactoryImpl

@CompileStatic
class MoquiSessionListener implements HttpSessionListener, HttpSessionAttributeListener {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiSessionListener.class)
    private HashMap<String, String> visitIdBySession = new HashMap<>()

    @Override void sessionCreated(HttpSessionEvent event) {
        HttpSession session = event.session

        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
        String moquiWebappName = session.servletContext.getInitParameter("moqui-name")
        ExecutionContextFactoryImpl.WebappInfo wi = ecfi?.getWebappInfo(moquiWebappName)
        if (wi?.sessionTimeoutSeconds != null) session.setMaxInactiveInterval(wi.sessionTimeoutSeconds)
    }

    @Override void sessionDestroyed(HttpSessionEvent event) {
        String sessionId = event.session.id
        String visitId = visitIdBySession.remove(sessionId)
        if (!visitId) {
            try { visitId = event.session.getAttribute("moqui.visitId") }
            catch (Throwable t) { logger.warn("No saved visitId for session ${sessionId} and error getting moqui.visitId session attribute: " + t.toString()) }
        }
        if (!visitId) {
            if (logger.traceEnabled) logger.trace("Not closing visit for session ${sessionId}, no value for visitId session attribute")
            return
        }
        closeVisit(visitId, sessionId)
    }
    @Override void attributeAdded(HttpSessionBindingEvent event) {
        if ("moqui.visitId".equals(event.name)) visitIdBySession.put(event.session.id, event.value.toString())
    }
    @Override void attributeReplaced(HttpSessionBindingEvent event) {
        if ("moqui.visitId".equals(event.name)) {
            String sessionId = event.session.id
            String oldValue = event.value.toString()
            if (!oldValue) oldValue = visitIdBySession.get(sessionId)
            String newValue = event.session.getAttribute("moqui.visitId")
            if (newValue) visitIdBySession.put(sessionId, newValue)
            if (oldValue) closeVisit(oldValue, sessionId)
        }
    }

    @Override void attributeRemoved(HttpSessionBindingEvent event) {
        if ("moqui.visitId".equals(event.name)) {
            String sessionId = event.session.id
            String visitId = event.value
            if (!visitId) {
                if (logger.traceEnabled) logger.trace("Not closing visit for session ${sessionId}, no value for removed moqui.visitId session attribute")
                return
            }
            closeVisit(visitId, sessionId)
        }
    }
    static void closeVisit(String visitId, String sessionId) {
        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
        if (ecfi.confXmlRoot.first("server-stats").attribute("visit-enabled") == "false") return

        // set thruDate on Visit
        Timestamp thruDate = new Timestamp(System.currentTimeMillis())
        ecfi.serviceFacade.sync().name("update", "moqui.server.Visit").parameter("visitId", visitId).parameter("thruDate", thruDate)
                .disableAuthz().call()
        if (logger.traceEnabled) logger.trace("Closed visit ${visitId} at ${thruDate} for session ${sessionId}")
    }
}
