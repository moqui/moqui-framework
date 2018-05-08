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

import groovy.transform.CompileStatic
import org.h2.tools.Server
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.util.MNode
import org.moqui.util.SystemBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.lang.reflect.Field


/** Initializes H2 Database server if any datasource is configured to use H2. */
@CompileStatic
class H2ServerToolFactory implements ToolFactory<Server> {
    protected final static Logger logger = LoggerFactory.getLogger(H2ServerToolFactory.class)

    protected ExecutionContextFactoryImpl ecfi = null

    // for the embedded H2 server to allow remote access, used to stop server on destroy
    protected Server h2Server = null

    /** Default empty constructor */
    H2ServerToolFactory() { }

    @Override
    void init(ExecutionContextFactory ecf) {
        this.ecfi = (ExecutionContextFactoryImpl) ecf

        for (MNode datasourceNode in ecfi.getConfXmlRoot().first("entity-facade").children("datasource")) {
            String dbConfName = datasourceNode.attribute("database-conf-name")
            if (!"h2".equals(dbConfName)) continue

            String argsString = datasourceNode.attribute("start-server-args")
            if (argsString == null || argsString.isEmpty()) {
                MNode dbNode = ecfi.confXmlRoot.first("database-list")
                        .first({ MNode it -> "database".equals(it.name) && "h2".equals(it.attribute("name")) })
                argsString = dbNode.attribute("default-start-server-args")
            }
            if (argsString) {
                String[] args = argsString.split(" ")
                for (int i = 0; i < args.length; i++) while (args[i].contains('${')) args[i] = SystemBinding.expand(args[i])
                try {
                    h2Server = Server.createTcpServer(args).start();
                    logger.info("Started H2 remote server on port ${h2Server.getPort()} status: ${h2Server.getStatus()}")
                    logger.info("H2 args: ${args}")
                    // only start one server
                    break
                } catch (Throwable t) {
                    logger.warn("Error starting H2 server (may already be running): ${t.toString()}")
                }
            }
        }

        // a hack, disable the H2 shutdown hook (org.h2.engine.DatabaseCloser) so it doesn't shut down before the rest of framework
        if (h2Server != null) {
            Class clazz = Class.forName("java.lang.ApplicationShutdownHooks")
            Field field = clazz.getDeclaredField("hooks")
            field.setAccessible(true)
            IdentityHashMap<Thread, Thread> hooks = (IdentityHashMap<Thread, Thread>) field.get(null)
            List<Thread> hookList = new ArrayList<>(hooks.keySet())
            for (Thread hook in hookList) {
                String clazzName = hook.class.name
                logger.info("Found shutdown hook: ${clazzName} ${hook}")
                if ("org.h2.engine.DatabaseCloser".equals(clazzName)) Runtime.getRuntime().removeShutdownHook(hook)
            }
        }
    }

    @Override
    Server getInstance(Object... parameters) {
        if (h2Server == null) throw new IllegalStateException("H2ServerToolFactory not initialized")
        return h2Server
    }

    @Override
    void postFacadeDestroy() {
        // NOTE: using shutdown() instead of stop() so it shuts down the DB and stops the TCP server
        if (h2Server != null) {
            h2Server.shutdown()
            System.out.println("Shut down H2 Server")
        }
    }
}
