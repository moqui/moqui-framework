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
package org.moqui.impl.service.runner;

import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.impl.service.ServiceDefinition;
import org.moqui.impl.service.ServiceFacadeImpl;
import org.moqui.impl.service.ServiceRunner;
import org.moqui.service.ServiceException;
import org.moqui.util.ContextStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

public class InlineServiceRunner implements ServiceRunner {
    protected static final Logger logger = LoggerFactory.getLogger(InlineServiceRunner.class);
    private ExecutionContextFactoryImpl ecfi = null;

    public InlineServiceRunner() { }

    @Override
    public ServiceRunner init(ServiceFacadeImpl sfi) {
        ecfi = sfi.ecfi;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> runService(ServiceDefinition sd, Map<String, Object> parameters) {
        if (sd.xmlAction == null) throw new ServiceException("Service" + sd.serviceName + " run inline but has no actions");
        ExecutionContextImpl ec = ecfi.getEci();
        ContextStack cs = ec.contextStack;

        // push the entire context to isolate the context for the service call
        cs.pushContext();
        try {
            // add the parameters to this service call; copy instead of pushing, faster with newer ContextStack
            cs.putAll(parameters);
            // we have an empty context so add the ec
            cs.put("ec", ec);
            // add a convenience Map to explicitly put results in
            Map<String, Object> autoResult = new HashMap<>();
            cs.put("result", autoResult);

            Object result = sd.xmlAction.run(ec);

            if (result instanceof Map) {
                return (Map<String, Object>) result;
            } else {
                ScriptServiceRunner.combineResults(sd, autoResult, cs.getCombinedMap());
                return autoResult;
            }
        /* ServiceCallSyncImpl logs this anyway, no point logging it here: } catch (Throwable t) { logger.error("Error running inline XML Actions in service [${sd.serviceName}]: ", t); throw t */
        } finally {
            // pop the entire context to get back to where we were before isolating the context with pushContext
            cs.popContext();
        }
    }

    @Override
    public void destroy() { }
}
