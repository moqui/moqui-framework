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

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import javax.servlet.http.HttpSessionListener
import javax.servlet.http.HttpSession
import javax.servlet.http.HttpSessionEvent

import org.moqui.impl.context.ExecutionContextFactoryImpl

class MoquiSessionListener implements HttpSessionListener {
    void sessionCreated(HttpSessionEvent event) {
        // NOTE: this method now does nothing because we only want to create the Visit on the first request, and in
        //     order to not create the Visit under certain conditions we need the HttpServletRequest object.
    }

    void sessionDestroyed(HttpSessionEvent event) {
        Logger logger = LoggerFactory.getLogger(MoquiSessionListener.class)

        HttpSession session = event.getSession()
        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) session.getServletContext().getAttribute("executionContextFactory")
        if (!ecfi) {
            logger.warn("Not updating (closing) visit for session [${session.id}], no executionContextFactory in ServletContext")
            return
        }

        if (ecfi.confXmlRoot."server-stats"[0]."@visit-enabled" == "false") return

        String visitId = session.getAttribute("moqui.visitId")
        if (!visitId) {
            logger.info("Not updating (closing) visit for session [${session.id}], no moqui.visitId attribute found")
            return
        }


        try {
            ecfi.executionContext.artifactExecution.disableAuthz()

            // set thruDate on Visit
            ecfi.serviceFacade.sync().name("update", "moqui.server.Visit")
                    .parameters((Map<String, Object>) [visitId:visitId, thruDate:new Timestamp(System.currentTimeMillis())])
                    .call()
        } finally {
            ecfi.executionContext.artifactExecution.enableAuthz()
        }
    }
}
