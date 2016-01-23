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
package org.moqui;

import org.moqui.context.ExecutionContext;
import org.moqui.context.ExecutionContextFactory;
import org.moqui.entity.EntityDataLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * This is a base class that implements a simple way to access the Moqui framework for use in simple deployments where
 * there is nothing available like a webapp or an OSGi component.
 *
 * In deployments where a static reference to the ExecutionContextFactory is not helpful, or not possible, this does
 * not need to be used and the ExecutionContextFactory instance should be referenced and used from somewhere else.
 */

public class Moqui {
    protected final static Logger logger = LoggerFactory.getLogger(Moqui.class);

    private static ExecutionContextFactory activeExecutionContextFactory = null;

    private static final ServiceLoader<ExecutionContextFactory> executionContextFactoryLoader =
            ServiceLoader.load(ExecutionContextFactory.class);
    static {
        // only do this if the moqui.init.static System property is true
        if ("true".equals(System.getProperty("moqui.init.static"))) {
            // initialize the activeExecutionContextFactory from configuration using java.util.ServiceLoader
            // the implementation class name should be in: "META-INF/services/org.moqui.context.ExecutionContextFactory"
            activeExecutionContextFactory = executionContextFactoryLoader.iterator().next();
            activeExecutionContextFactory.postInit();
        }
    }

    public static void dynamicInit(ExecutionContextFactory executionContextFactory) {
        if (activeExecutionContextFactory == null) {
            activeExecutionContextFactory = executionContextFactory;
            activeExecutionContextFactory.postInit();
        } else {
            throw new IllegalStateException("Active ExecutionContextFactory already in place, cannot set one dynamically.");
        }

    }

    public static ExecutionContextFactory getExecutionContextFactory() { return activeExecutionContextFactory; }
    
    public static ExecutionContext getExecutionContext() {
        return activeExecutionContextFactory.getExecutionContext();
    }

    /** This should be called when it is known a context won't be used any more, such as at the end of a web request or service execution. */
    public static void destroyActiveExecutionContext() { activeExecutionContextFactory.destroyActiveExecutionContext(); }

    /** This should be called when the process is terminating to clean up framework and tool operations and resources. */
    public static void destroyActiveExecutionContextFactory() { activeExecutionContextFactory.destroy(); }

    /** This method is meant to be run from a command-line interface and handle data loading in a generic way.
     * @param argMap Arguments, generally from command line, to configure this data load.
     */
    public static void loadData(Map<String, String> argMap) {
        // make sure we have a factory, even if moqui.init.static != true
        if (activeExecutionContextFactory == null)
            activeExecutionContextFactory = executionContextFactoryLoader.iterator().next();

        ExecutionContext ec = activeExecutionContextFactory.getExecutionContext();
        ec.getArtifactExecution().disableAuthz();
        ec.getArtifactExecution().push("loadData", "AT_OTHER", "AUTHZA_ALL", false);
        ec.getArtifactExecution().setAnonymousAuthorizedAll();
        ec.getUser().loginAnonymousIfNoUser();

        String tenantId = argMap.get("tenantId");
        if (tenantId != null && tenantId.length() > 0) ec.changeTenant(tenantId);

        EntityDataLoader edl = ec.getEntity().makeDataLoader();
        if (argMap.containsKey("types"))
            edl.dataTypes(new HashSet<String>(Arrays.asList(argMap.get("types").split(","))));
        if (argMap.containsKey("components"))
            edl.componentNameList(Arrays.asList(argMap.get("components").split(",")));
        if (argMap.containsKey("location")) edl.location(argMap.get("location"));
        if (argMap.containsKey("timeout")) edl.transactionTimeout(Integer.valueOf(argMap.get("timeout")));
        if (argMap.containsKey("dummy-fks")) edl.dummyFks(true);
        if (argMap.containsKey("use-try-insert")) edl.useTryInsert(true);

        long startTime = System.currentTimeMillis();

        try {
            long records = edl.load();

            long totalSeconds = (System.currentTimeMillis() - startTime)/1000;
            logger.info("Loaded [" + records + "] records in " + totalSeconds + " seconds.");
        } catch (Throwable t) {
            System.out.println("Error loading data: " + t.toString());
            t.printStackTrace();
        }

        // cleanup and quit
        activeExecutionContextFactory.destroyActiveExecutionContext();
        activeExecutionContextFactory.destroy();
    }
}
