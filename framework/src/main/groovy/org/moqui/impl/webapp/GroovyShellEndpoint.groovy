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
import org.codehaus.groovy.tools.shell.Groovysh
import org.codehaus.groovy.tools.shell.IO
import org.jetbrains.annotations.NotNull
import org.moqui.impl.context.ExecutionContextImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.websocket.CloseReason
import javax.websocket.EndpointConfig
import javax.websocket.Session
import java.nio.ByteBuffer

@CompileStatic
class GroovyShellEndpoint extends MoquiAbstractEndpoint {
    private final static Logger logger = LoggerFactory.getLogger(GroovyShellEndpoint.class)

    ExecutionContextImpl eci = null
    Groovysh groovysh = null
    IO io = null
    PrintWriter inputWriter = null
    Thread groovyshThread = null

    GroovyShellEndpoint() { super() }

    @Override
    void onOpen(Session session, EndpointConfig config) {
        this.destroyInitialEci = false
        super.onOpen(session, config)
        eci = ecf.getEci()

        // make sure user has special permission
        if (!eci.userFacade.hasPermission("GROOVY_SHELL_WEB"))
            throw new IllegalAccessException("User ${eci.userFacade.getUsername()} does not have permission to use Groovy Shell via WebSocket")

        PipedOutputStream pos = new PipedOutputStreamWatcher()
        PipedInputStream input = new PipedInputStreamWatcher(pos, 4096)
        inputWriter = new PrintWriter(pos, true)

        OutputStream output = new BufferedOutputStream(new WsSessionOutputStream(session), 8192)

        io = new IO(input, output, output)
        io.verbosity = IO.Verbosity.DEBUG
        groovysh = new Groovysh(ecf.classLoader, eci.getContextBinding(), io)

        // run in separate thread
        groovyshThread = Thread.start("GroovyShellWeb", {
            registerEci()
            groovysh.run(null)
        })

        deregisterEci()
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
    void onMessage(String message) {
        logger.warn("TOREMOVE received groovy ws message: ${message}")
        if (inputWriter != null) {
            inputWriter.write(message)
        } else {
            logger.warn("In GroovyShellEndpoint for user ${userId}:${username} groovysh inputWriter is null, cannot write message: ${message}")
        }
    }

    @Override
    void onClose(Session session, CloseReason closeReason) {
        if (groovysh != null) {
            // can't really quit from outside, so exec a command to quite from the inside!
            groovysh.execute(":exit")
            groovysh = null
        }
        if (io != null) {
            io.flush()
            io.close()
            io = null
        }
        if (eci != null) {
            eci.destroy()
            eci = null
        }
        if (groovyshThread != null) {
            // TODO: any way to make sure groovysh in thread terminates?
            groovyshThread.interrupt()
            groovyshThread.join(50)
            if (groovyshThread.isAlive()) logger.warn("groovysh Thread ${groovyshThread.getId()}:${groovyshThread.getName()} still alive")
            groovyshThread = null
        }
        super.onClose(session, closeReason)
    }

    static class PipedInputStreamWatcher extends PipedInputStream {
        PipedInputStreamWatcher() { super() }
        PipedInputStreamWatcher(PipedOutputStream src) throws IOException { super(src) }
        PipedInputStreamWatcher(PipedOutputStream src, int pipeSize) throws IOException { super(src, pipeSize) }
        @Override void close() throws IOException {
            logger.warn("Closing PipedInputStream at", new Exception("Close PIS loc"))
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
        Session session
        WsSessionOutputStream(Session session) { this.session = session }

        @Override void write(@NotNull byte[] b) throws IOException {
            // session.getAsyncRemote().sendBinary(ByteBuffer.wrap(b))
            session.getAsyncRemote().sendText(new String(b))
            logger.warn("writing bytes: ${new String(b)}")
        }
        @Override void write(@NotNull byte[] b, int off, int len) throws IOException {
            // session.getAsyncRemote().sendBinary(ByteBuffer.wrap(b, off, len))
            session.getAsyncRemote().sendText(new String(b, off, len))
            logger.warn("writing bytes: ${new String(b, off, len)}")
        }
        @Override void write(int b) throws IOException {
            byte[] bytes = new byte[1]
            bytes[0] = (byte) b
            // session.getAsyncRemote().sendBinary(ByteBuffer.wrap(bytes))
            session.getAsyncRemote().sendText(new String(bytes))
            logger.warn("writing byte: ${new String(bytes)}")
        }
        @Override void flush() throws IOException { }
        @Override void close() throws IOException { }
    }
}
