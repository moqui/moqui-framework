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

import groovy.lang.GroovyShell
import groovy.transform.CompileStatic
import org.moqui.impl.context.ExecutionContextImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.websocket.CloseReason
import javax.websocket.EndpointConfig
import javax.websocket.Session

@CompileStatic
class GroovyShellEndpoint extends MoquiAbstractEndpoint {
    private static final Logger logger = LoggerFactory.getLogger(GroovyShellEndpoint.class)

    ExecutionContextImpl eci
    GroovyShell groovyShell

    GroovyShellEndpoint() { super() }

    @Override
    void onOpen(Session session, EndpointConfig config) {
        this.destroyInitialEci = false
        super.onOpen(session, config)
        logger.info("Opening GroovyShellEndpoint session ${session.getId()} for user ${userId}:${username}")
        eci = ecf.getEci()

        if (!eci.userFacade.hasPermission("GROOVY_SHELL_WEB"))
            throw new IllegalAccessException("User ${eci.userFacade.getUsername()} does not have permission to use Groovy Shell via WebSocket")

        groovyShell = new GroovyShell(
            ecf.classLoader,
            eci.getContextBinding()
        )
    }

    @Override
    synchronized void onMessage(String message) {
        if (groovyShell == null) return
        String trimmed = message?.trim()
        if (trimmed == ':exit') {
            session.asyncRemote.sendText("Ending Session.${System.lineSeparator()}")
            session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Client requested exit"))
            return
        }
        registerEci()
        eci.artifactExecutionFacade.disableAuthz()
        try {
            Object result = groovyShell.evaluate(message)
            if (result != null) {
                session.asyncRemote.sendText(result.toString() + System.lineSeparator())
            }
        } catch (Throwable t) {
            logger.error("GroovyShell evaluation error", t)
            session.asyncRemote.sendText(
                "ERROR: ${t.class.simpleName}: ${t.message}${System.lineSeparator()}"
            )
        } finally {
            try {
                eci.artifactExecutionFacade.enableAuthz()
            } finally {
                deregisterEci()
            }
        }
    }

    @Override
    void onClose(Session session, CloseReason closeReason) {
        logger.info("Closing GroovyShellEndpoint session ${session.getId()} for user ${userId}:${username}")
        groovyShell = null
        if (eci != null) {
            try {
                eci.destroy()
            } catch (Throwable t) {
                logger.error("Error destroying ExecutionContext in GroovyShellEndpoint", t)
            } finally {
                eci = null
            }
        }
        super.onClose(session, closeReason)
    }

    void registerEci() {
        ExecutionContextImpl activeEc = ecfi.activeContext.get()
        if (activeEc != null && activeEc != eci) {
            logger.warn("In GroovyShellEndpoint there is already an ExecutionContext for user ${activeEc.user.username} (from ${activeEc.forThreadId}:${activeEc.forThreadName}) in this thread (${Thread.currentThread().threadId()}:${Thread.currentThread().getName()}), destroying")
            try {
                activeEc.destroy()
            } catch (Throwable t) {
                logger.error("Error destroying ExecutionContext already in place in GroovyShellEndpoint", t)
            }
        }
        eci.forThreadId = Thread.currentThread().threadId()
        eci.forThreadName = Thread.currentThread().getName()
        ecfi.activeContext.set(eci)
        ecfi.activeContextMap.put(Thread.currentThread().threadId(), eci)
    }

    void deregisterEci() {
        ecfi.activeContext.remove()
        ecfi.activeContextMap.remove(Thread.currentThread().threadId())
    }
}
