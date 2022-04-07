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
import org.apache.groovy.groovysh.Groovysh
import org.codehaus.groovy.tools.shell.IO
import org.moqui.impl.context.ExecutionContextImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.swing.Timer
import javax.websocket.CloseReason
import javax.websocket.EndpointConfig
import javax.websocket.Session
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.util.concurrent.atomic.AtomicInteger

@CompileStatic
class GroovyShellEndpoint extends MoquiAbstractEndpoint implements ActionListener {
    private final static Logger logger = LoggerFactory.getLogger(GroovyShellEndpoint.class)
    private final static AtomicInteger threadExt = new AtomicInteger(1)

    ExecutionContextImpl eci = null
    Groovysh groovysh = null
    IO io = null
    PrintWriter inputWriter = null
    Thread groovyshThread = null
    Timer inactivityTimer = null
    StringBuilder commandLogBuffer = new StringBuilder()

    GroovyShellEndpoint() { super() }

    @Override
    void onOpen(Session session, EndpointConfig config) {
        this.destroyInitialEci = false
        super.onOpen(session, config)
        logger.info("Opening GroovyShellEndpoint session ${session.getId()} for user ${userId}:${username}")
        eci = ecf.getEci()

        // make sure user has special permission
        if (!eci.userFacade.hasPermission("GROOVY_SHELL_WEB"))
            throw new IllegalAccessException("User ${eci.userFacade.getUsername()} does not have permission to use Groovy Shell via WebSocket")

        PipedOutputStream pos = new PipedOutputStreamWatcher()
        PipedInputStream input = new PipedInputStreamWatcher(pos, 4096)
        inputWriter = new PrintWriter(pos, true)

        OutputStream output = new BufferedOutputStream(new WsSessionOutputStream(this), 8192)

        io = new IO(input, output, output)
        io.verbosity = IO.Verbosity.VERBOSE
        // maybe not a good idea: eci.contextStack.put("io", io)
        groovysh = new Groovysh(ecf.classLoader, eci.getContextBinding(), io)

        // init inactivity timer, 300 seconds (5 min)
        inactivityTimer = new Timer(300000, this)
        inactivityTimer.setRepeats(false)

        // run groovy shell in separate thread
        groovyshThread = Thread.start("GroovyShellWeb-" + threadExt.getAndIncrement(), {
            registerEci()
            // do this for convenience, since anything can be run here no point in authz security
            eci.artifactExecutionFacade.disableAuthz()
            inactivityTimer.start()
            groovysh.run(null)
        })

        deregisterEci()
    }

    void stopGroovyshThread() {
        if (groovyshThread != null) {
            if (inactivityTimer != null) {
                inactivityTimer.stop()
                inactivityTimer = null
            }
            if (inputWriter != null) {
                inputWriter.write(":exit" + System.lineSeparator())
                inputWriter.flush()
            }
            groovyshThread.join(50)
            if (groovyshThread.isAlive()) {
                logger.warn("groovysh Thread ${groovyshThread.getId()}:${groovyshThread.getName()} still alive")
                try {
                    // this is deprecated, but Groovysh doesn't seem to have other reliable ways to stop it
                    // TODO: find other ways to stop Groovysh
                    //     note that also tried interrupting run loop but didn't work: groovysh.runner.running = false
                    // NOTE: destroy() throws java.lang.NoSuchMethodError
                    groovyshThread.stop()
                } catch (Exception e) {
                    logger.error("Error destroying GroovyShell thread", e)
                }
            }
            groovyshThread = null
            groovysh = null
        }
    }

    void registerEci() {
        // register eci with thread, destroy active eci if one
        ExecutionContextImpl activeEc = ecfi.activeContext.get()
        if (activeEc != null) {
            logger.warn("In GroovyShellEndpoint there is already an ExecutionContext for user ${activeEc.user.username} (from ${activeEc.forThreadId}:${activeEc.forThreadName}) in this thread (${Thread.currentThread().id}:${Thread.currentThread().name}), destroying")
            try {
                activeEc.destroy()
            } catch (Throwable t) {
                logger.error("Error destroying ExecutionContext already in place in GroovyShellEndpoint", t)
            }
        }

        eci.forThreadId = Thread.currentThread().getId()
        eci.forThreadName = Thread.currentThread().getName()
        ecfi.activeContext.set(eci)
        ecfi.activeContextMap.put(Thread.currentThread().getId(), eci)
    }
    void deregisterEci() {
        // don't destroy eci, but remove references:
        ecfi.activeContext.remove()
        ecfi.activeContextMap.remove(Thread.currentThread().getId())
    }

    @Override
    synchronized void onMessage(String message) {
        // logger.warn("received groovy ws message: ${message}")
        if (inputWriter != null) {
            inactivityTimer.restart()
            commandLogBuffer.append(message)
            if (message.contains("\n") || message.contains("\r")) {
                logger.info("groovysh (${eci.userFacade.username}): ${commandLogBuffer}")
                commandLogBuffer.delete(0, commandLogBuffer.length())
            }
            inputWriter.write(message)
            inputWriter.flush()
        } else {
            logger.warn("In GroovyShellEndpoint for user ${userId}:${username} groovysh inputWriter is null, cannot write message: ${message}")
        }
    }

    @Override
    void onClose(Session session, CloseReason closeReason) {
        logger.info("Closing GroovyShellEndpoint session ${session.getId()} for user ${userId}:${username}")
        stopGroovyshThread()
        if (io != null) {
            try {
                io.flush()
                io.close()
            } catch (Throwable t) {
                logger.error("Error in close GroovyShellEndpoint groovysh :exit", t)
            } finally {
                io = null
            }
        }
        if (eci != null) {
            try {
                eci.destroy()
            } catch (Throwable t) {
                logger.error("Error in close GroovyShellEndpoint groovysh :exit", t)
            } finally {
                eci = null
            }
        }
        super.onClose(session, closeReason)
    }

    @Override
    void actionPerformed(ActionEvent e) {
        if (e.getSource() == inactivityTimer) {
            stopGroovyshThread()
        }
    }

    static class PipedInputStreamWatcher extends PipedInputStream {
        PipedInputStreamWatcher() { super() }
        PipedInputStreamWatcher(PipedOutputStream src) throws IOException { super(src) }
        PipedInputStreamWatcher(PipedOutputStream src, int pipeSize) throws IOException { super(src, pipeSize) }
        @Override void close() throws IOException {
            // logger.warn("Closing PipedInputStream at", new Exception("Close PIS loc"))
            super.close()
        }
    }
    static class PipedOutputStreamWatcher extends PipedOutputStream {
        PipedOutputStreamWatcher() { super() }
        PipedOutputStreamWatcher(PipedInputStream snk) throws IOException { super(snk) }
        @Override void close() throws IOException {
            logger.warn("Closing PipedOutputStream at", new Exception("Close POS loc"))
            super.close()
        }
    }

    static class WsSessionOutputStream extends OutputStream {
        GroovyShellEndpoint groovyShellEndpoint
        Session session
        WsSessionOutputStream(GroovyShellEndpoint groovyShellEndpoint) {
            this.groovyShellEndpoint = groovyShellEndpoint
            this.session = groovyShellEndpoint.session
        }

        // Sometimes the pipe dies, exit from groovysh instead of infinite errors
        boolean checkDeadPipe(String bytesStr) {
            if (bytesStr != null && bytesStr.contains("Write end dead")) {
                logger.warn("Got Write end dead, exiting from groovysh")
                if (groovyShellEndpoint.groovysh != null) {
                    // can't really quit from outside, so exec a command to quite from the inside!
                    groovyShellEndpoint.groovysh.execute(":exit")
                    // alternate, possible, but doesn't stop the Thread like :exit does: groovyShellEndpoint.groovysh.runner.running = false
                    groovyShellEndpoint.groovysh = null
                }
                return true
            }
            return false
        }

        @Override void write(byte[] b) throws IOException {
            String bytesStr = new String(b)
            if (checkDeadPipe(bytesStr)) return
            session.getAsyncRemote().sendText(bytesStr)
            // logger.warn("writing bytes: ${bytesStr}")
            // old approach that didn't work: session.getAsyncRemote().sendBinary(ByteBuffer.wrap(b))
        }
        @Override void write(byte[] b, int off, int len) throws IOException {
            String bytesStr = new String(b, off, len)
            if (checkDeadPipe(bytesStr)) return
            session.getAsyncRemote().sendText(bytesStr)
            // logger.warn("writing bytes: ${new String(b, off, len)}")
        }
        @Override void write(int b) throws IOException {
            byte[] bytes = new byte[1]
            bytes[0] = (byte) b
            session.getAsyncRemote().sendText(new String(bytes))
            // logger.warn("writing byte: ${new String(bytes)}")
        }
        @Override void flush() throws IOException { }
        @Override void close() throws IOException { }
    }
}
