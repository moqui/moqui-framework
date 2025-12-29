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

import java.io.PrintWriter
import java.io.Writer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

import javax.websocket.CloseReason
import javax.websocket.EndpointConfig
import javax.websocket.Session

import groovy.lang.GroovyShell
import groovy.transform.CompileStatic

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.moqui.impl.context.ExecutionContextImpl

@CompileStatic
class GroovyShellEndpoint extends MoquiAbstractEndpoint {
    private static final Logger logger = LoggerFactory.getLogger(GroovyShellEndpoint.class)
    private static final int idleSeconds = 300
    private static final int maxEvalSeconds = 900 // kill infinite loops

    ExecutionContextImpl eci
    GroovyShell groovyShell
    ExecutorService exec
    ScheduledExecutorService scheduler
    ScheduledFuture<?> idleTask
    ScheduledFuture<?> evalTimeoutTask
    volatile boolean closing = false

    GroovyShellEndpoint() { super() }

    @Override
    void onOpen(Session session, EndpointConfig config) {
        this.destroyInitialEci = false
        super.onOpen(session, config)
        logger.info("Opening GroovyShellEndpoint session ${session.getId()} for user ${userId}:${username}")
        eci = ecf.getEci()
        if (!eci.userFacade.hasPermission("GROOVY_SHELL_WEB")) {
            throw new IllegalAccessException("User ${username} does not have permission to use Groovy Shell")
        }
        exec = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("GroovyShell-" + session.getId(), 0).factory())
        scheduler = Executors.newSingleThreadScheduledExecutor()
        Writer wsWriter = new WsWriter(session)
        def binding = eci.getContextBinding()
        binding.setVariable("out", new PrintWriter(wsWriter, true))
        binding.setVariable("err", new PrintWriter(wsWriter, true))
        groovyShell = new GroovyShell(ecf.classLoader, binding)
        resetIdleTimer()
    }

    @Override
    void onMessage(String message) {
        if (closing || groovyShell == null) return
        String trimmed = message?.trim()
        if (trimmed == ':exit') {
            try {
                checkSend("Ending session.${System.lineSeparator()}")
                session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Client requested exit"))
            } catch (Throwable t) {
                logger.trace("Error in closing session", t)
            }
            return
        }
        suspendIdleTimer()
        try {
            exec.submit {
                scheduleEvalTimeout()
                registerEci()
                eci.artifactExecutionFacade.disableAuthz()
                try {
                    Object result = groovyShell.evaluate(message)
                    checkSend(result.toString() + System.lineSeparator())
                } catch (Throwable t) {
                    logger.error("GroovyShell evaluation error", t)
                    checkSend("ERROR: ${t.class.simpleName}: ${t.message}${System.lineSeparator()}")
                } finally {
                    try {
                        if (!closing) eci.artifactExecutionFacade.enableAuthz()
                    } finally {
                        if (!closing) deregisterEci()
                        cancelEvalTimeout()
                        resumeIdleTimer()
                    }
                }
            }
        } catch (Throwable t) {
            resumeIdleTimer()
        }
    }

    @Override
    void onClose(Session session, CloseReason closeReason) {
        if (closing) {
            super.onClose(session, closeReason)
            return
        }
        closing = true
        logger.info("Closing GroovyShellEndpoint session ${session.getId()} for user ${userId}:${username}")
        try {
            idleTask?.cancel(false)
            evalTimeoutTask?.cancel(false)
            scheduler?.shutdownNow()
            exec?.shutdownNow()
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
        } finally {
            super.onClose(session, closeReason)
        }
    }

    /**
     * Bind this session's ExecutionContext to the current executor thread
     * for the duration of a single Groovy evaluation.
     */
    void registerEci() {
        ExecutionContextImpl activeEc = ecfi.activeContext.get()
        if (activeEc != null && activeEc != eci) {
            logger.warn("Foreign ExecutionContext found on thread; clearing ThreadLocal only")
            ecfi.activeContext.remove()
            ecfi.activeContextMap.remove(Thread.currentThread().threadId())
        }
        eci.forThreadId = Thread.currentThread().threadId()
        eci.forThreadName = Thread.currentThread().getName()
        ecfi.activeContext.set(eci)
        ecfi.activeContextMap.put(Thread.currentThread().threadId(), eci)
    }

    /**
     * Remove the ExecutionContext from the executor thread after evaluation
     * to avoid leaking it to the next task on the same thread.
     */
    void deregisterEci() {
        ecfi.activeContext.remove()
        ecfi.activeContextMap.remove(Thread.currentThread().threadId())
    }

    void resetIdleTimer() {
        if (closing || scheduler == null || scheduler.isShutdown()) return
        idleTask?.cancel(false)
        try {
            idleTask = scheduler.schedule({
                try {
                   if (session?.isOpen()) {
                        session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Idle timeout"))
                    }
                } catch (Throwable t) {
                    logger.trace('Error in closing session', t)
                }
            }, idleSeconds, TimeUnit.SECONDS)
        } catch (Throwable ignored) { }
    }

    void suspendIdleTimer() {
        idleTask?.cancel(false)
        idleTask = null
    }

    void resumeIdleTimer() {
        resetIdleTimer()
    }

    void scheduleEvalTimeout() {
        if (closing || scheduler == null || scheduler.isShutdown()) return
        evalTimeoutTask?.cancel(false)
        try {
            evalTimeoutTask = scheduler.schedule({
                try {
                    if (session?.isOpen()) {
                        checkSend("ERROR: Evaluation exceeded ${maxEvalSeconds} seconds, closing session.${System.lineSeparator()}")
                        session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Evaluation timeout"))
                    }
                } catch (Throwable ignored) { }
            }, maxEvalSeconds, TimeUnit.SECONDS)
        } catch (Throwable ignored) { }
    }

    void cancelEvalTimeout() {
        evalTimeoutTask?.cancel(false)
        evalTimeoutTask = null
    }

    void checkSend(String text) {
        try {
            if (session?.isOpen()) {
                session.asyncRemote.sendText(text)
            }
        } catch (Throwable ignored) { }
    }

    static class WsWriter extends Writer {
        final Session session
        WsWriter(Session session) { this.session = session }
        @Override
        void write(char[] cbuf, int off, int len) {
            try {
                if (session?.isOpen()) {
                    session.asyncRemote.sendText(new String(cbuf, off, len))
                }
            } catch (Throwable ignored) { }
        }
        @Override void flush() { }
        @Override void close() { }
    }
}
