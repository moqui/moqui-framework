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
package org.moqui.impl.service.runner

import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.service.ServiceRunner
import org.moqui.context.ExecutionContext
import org.moqui.context.ContextStack

import org.slf4j.Logger
import org.slf4j.LoggerFactory

public class ScriptServiceRunner implements ServiceRunner {
    protected final static Logger logger = LoggerFactory.getLogger(ScriptServiceRunner.class)

    protected ServiceFacadeImpl sfi = null

    ScriptServiceRunner() { }

    public ServiceRunner init(ServiceFacadeImpl sfi) { this.sfi = sfi; return this }

    public Map<String, Object> runService(ServiceDefinition sd, Map<String, Object> parameters) {
        ExecutionContext ec = sfi.ecfi.getExecutionContext()
        ContextStack cs = (ContextStack) ec.context
        try {
            // push the entire context to isolate the context for the service call
            cs.pushContext()
            // we have an empty context so add the ec
            cs.put("ec", ec)
            // now add the parameters to this service call
            cs.push(parameters)
            // push again to get a new Map that will protect the parameters Map passed in
            cs.push()
            // add a convenience Map to explicitly put results in
            Map<String, Object> autoResult = new HashMap()
            ec.context.put("result", autoResult)

            Object result = ec.resource.script(sd.serviceNode.attribute("location"), sd.serviceNode.attribute("method"))

            if (result instanceof Map) {
                return (Map<String, Object>) result
            } else {
                // if there are fields in ec.context that match out-parameters but that aren't in the result, set them
                for (String outParameterName in sd.getOutParameterNames()) {
                    if (!autoResult.containsKey(outParameterName) && cs.getByString(outParameterName) != null)
                        autoResult.put(outParameterName, cs.getByString(outParameterName))
                }
                return autoResult
            }
        } finally {
            // pop the entire context to get back to where we were before isolating the context with pushContext
            cs.popContext()
        }
    }

    public void destroy() { }
}
