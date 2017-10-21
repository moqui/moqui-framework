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
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** ElasticSearch Client is used for indexing and searching documents */
@CompileStatic
class JackrabbitRunToolFactory implements ToolFactory<Process> {
    protected final static Logger logger = LoggerFactory.getLogger(JackrabbitRunToolFactory.class)
    final static String TOOL_NAME = "JackrabbitRun"

    protected ExecutionContextFactory ecf = null

    /** Jackrabbit Process */
    protected Process jackrabbitProcess = null

    /** Default empty constructor */
    JackrabbitRunToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }
    @Override
    void init(ExecutionContextFactory ecf) {
    }
    @Override
    void preFacadeInit(ExecutionContextFactory ecf) {
        this.ecf = ecf

        logger.info("Initializing Jackrabbit")
        Properties jackrabbitProperties = new Properties()
        URL jackrabbitProps = this.class.getClassLoader().getResource("jackrabbit_moqui.properties")
        if (jackrabbitProps != null) {
            InputStream is = jackrabbitProps.openStream(); jackrabbitProperties.load(is); is.close();
        }

        String jackrabbitWorkingDir = System.getProperty("moqui.jackrabbit_working_dir")
        if (!jackrabbitWorkingDir) jackrabbitWorkingDir = jackrabbitProperties.getProperty("moqui.jackrabbit_working_dir")
        if (!jackrabbitWorkingDir) jackrabbitWorkingDir = "jackrabbit"

        String jackrabbitJar = System.getProperty("moqui.jackrabbit_jar")
        if (!jackrabbitJar) jackrabbitJar = jackrabbitProperties.getProperty("moqui.jackrabbit_jar")
        if (!jackrabbitJar) throw new IllegalArgumentException(
                "No moqui.jackrabbit_jar property found in jackrabbit_moqui.ini or in a system property (with: -Dmoqui.jackrabbit_jar=... on the command line)")
        String jackrabbitJarFullPath = ecf.runtimePath + "/" + jackrabbitWorkingDir + "/" + jackrabbitJar

        String jackrabbitConfFile = System.getProperty("moqui.jackrabbit_configuration_file")
        if (!jackrabbitConfFile)
            jackrabbitConfFile = jackrabbitProperties.getProperty("moqui.jackrabbit_configuration_file")
        if (!jackrabbitConfFile) jackrabbitConfFile = "repository.xml"
        String jackrabbitConfFileFullPath = ecf.runtimePath + "/" + jackrabbitWorkingDir + "/" + jackrabbitConfFile

        String jackrabbitPort = System.getProperty("moqui.jackrabbit_port")
        if (!jackrabbitPort)
            jackrabbitPort = jackrabbitProperties.getProperty("moqui.jackrabbit_port")
        if (!jackrabbitPort) jackrabbitPort = "8081"

        logger.info("Starting Jackrabbit")

        ProcessBuilder pb = new ProcessBuilder("java", "-jar", jackrabbitJarFullPath, "-p", jackrabbitPort, "-c", jackrabbitConfFileFullPath)
        pb.directory(new File(ecf.runtimePath + "/" + jackrabbitWorkingDir))
        jackrabbitProcess = pb.start();

        while(!hostAvailabilityCheck("localhost", jackrabbitPort.toInteger())) {
            sleep(500)
        }
    }

    @Override
    Process getInstance(Object... parameters) {
        if (jackrabbitProcess == null) throw new IllegalStateException("JackrabbitRunToolFactory not initialized")
        return jackrabbitProcess
    }

    @Override
    void destroy() {
        // Stop Jackrabbit process
        if (jackrabbitProcess != null) try {
            jackrabbitProcess.destroy()
            logger.info("Jackrabbit process destroyed")
        } catch (Throwable t) { logger.error("Error in JackRabbit process destroy", t) }
    }

    ExecutionContextFactory getEcf() { return ecf }

    private static boolean hostAvailabilityCheck(String hostname, int port) {
        Socket s = null
        try {
            s = new Socket(hostname, port)
            return true
        } catch (IOException e ) {
            /* ignore */
        } finally {
            if (s != null) try { s.close() } catch (Exception e) {}
        }
        return false
    }
}
