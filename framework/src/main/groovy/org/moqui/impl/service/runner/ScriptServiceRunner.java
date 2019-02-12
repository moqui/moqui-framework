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

import groovy.transform.CompileStatic;
import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.impl.service.ServiceDefinition;
import org.moqui.impl.service.ServiceFacadeImpl;
import org.moqui.impl.service.ServiceRunner;
import org.moqui.util.ContextStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@CompileStatic
public class ScriptServiceRunner implements ServiceRunner {
    protected static final Logger logger = LoggerFactory.getLogger(ScriptServiceRunner.class);
    private ExecutionContextFactoryImpl ecfi = null;

    public ScriptServiceRunner() { }

    @Override
    public ServiceRunner init(ServiceFacadeImpl sfi) {
        ecfi = sfi.ecfi;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> runService(ServiceDefinition sd, Map<String, Object> parameters) {
        ExecutionContextImpl ec = ecfi.getEci();
        ContextStack cs = ec.contextStack;

        // push the entire context to isolate the context for the service call
        cs.pushContext();
        try {
            // now add the parameters to this service call; copy instead of pushing, faster with newer ContextStack
            cs.putAll(parameters);
            // we have an empty context so add the ec
            cs.put("ec", ec);
            // add a convenience Map to explicitly put results in
            Map<String, Object> autoResult = new HashMap<>();
            cs.put("result", autoResult);

            Object result = ec.getResource().script(sd.location, sd.method);

            if (result instanceof Map) {
                return (Map<String, Object>) result;
            } else {
                combineResults(sd, autoResult, cs.getCombinedMap());
                return autoResult;
            }
        } finally {
            // pop the entire context to get back to where we were before isolating the context with pushContext
            cs.popContext();
        }
    }
    static void combineResults(ServiceDefinition sd, Map<String, Object> autoResult, Map<String, Object> csMap) {
        // if there are fields in ec.context that match out-parameters but that aren't in the result, set them
        boolean autoResultUsed = autoResult.size() > 0;
        String[] outParameterNames = sd.outParameterNameArray;
        int outParameterNamesSize = outParameterNames.length;
        for (int i = 0; i < outParameterNamesSize; i++) {
            String outParameterName = outParameterNames[i];
            Object outValue = csMap.get(outParameterName);
            if ((!autoResultUsed || !autoResult.containsKey(outParameterName)) && outValue != null)
                autoResult.put(outParameterName, outValue);
        }
    }

    @Override
    public void destroy() { }
}
