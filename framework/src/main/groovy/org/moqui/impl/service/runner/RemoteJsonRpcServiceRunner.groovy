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

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContext
import org.moqui.util.WebUtilities
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.service.ServiceRunner

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
public class RemoteJsonRpcServiceRunner implements ServiceRunner {
    protected final static Logger logger = LoggerFactory.getLogger(RemoteJsonRpcServiceRunner.class)

    protected ServiceFacadeImpl sfi = null

    RemoteJsonRpcServiceRunner() {}

    public ServiceRunner init(ServiceFacadeImpl sfi) { this.sfi = sfi; return this }

    public Map<String, Object> runService(ServiceDefinition sd, Map<String, Object> parameters) {
        ExecutionContext ec = sfi.ecfi.getExecutionContext()

        String location = sd.location
        String method = sd.method
        if (!location) throw new IllegalArgumentException("Cannot call remote service [${sd.serviceName}] because it has no location specified.")
        if (!method) throw new IllegalArgumentException("Cannot call remote service [${sd.serviceName}] because it has no method specified.")

        return runJsonService(sd.serviceNameNoHash, location, method, parameters, ec)
    }

    static Map<String, Object> runJsonService(String serviceName, String location, String method,
                                              Map<String, Object> parameters, ExecutionContext ec) {
        Map jsonRequestMap = [jsonrpc:"2.0", id:1, method:method, params:parameters]
        JsonBuilder jb = new JsonBuilder()
        jb.call(jsonRequestMap)
        String jsonRequest = jb.toString()

        // logger.warn("======== JSON-RPC remote service request to location [${location}]: ${jsonRequest}")

        String jsonResponse = WebUtilities.simpleHttpStringRequest(location, jsonRequest, "application/json")

        // logger.info("JSON-RPC remote service [${sd.getServiceName()}] request: ${httpPost.getRequestLine()}, ${httpPost.getAllHeaders()}, ${httpPost.getEntity().contentLength} bytes")
        // logger.warn("======== JSON-RPC remote service request entity [length:${httpPost.getEntity().contentLength}]: ${EntityUtils.toString(httpPost.getEntity())}")

        // logger.warn("======== JSON-RPC remote service response from location [${location}]: ${jsonResponse}")

        // parse and return the results
        JsonSlurper slurper = new JsonSlurper()
        Object jsonObj
        try {
            // logger.warn("========== JSON-RPC response: ${jsonResponse}")
            jsonObj = slurper.parseText(jsonResponse)
        } catch (Throwable t) {
            String errMsg = ec.resource.expand('Error parsing JSON-RPC response for service [${serviceName ?: method}]: ${t.toString()}','',[serviceName:serviceName, method:method, t:t])
            logger.error(errMsg, t)
            ec.message.addError(errMsg)
            return null
        }

        if (jsonObj instanceof Map) {
            Map responseMap = (Map) jsonObj
            if (responseMap.error) {
                logger.error("JSON-RPC service [${serviceName ?: method}] returned an error: ${responseMap.error}")
                ec.message.addError((String) ((Map) responseMap.error)?.message ?: ec.resource.expand('JSON-RPC error with no message, code [${responseMap.error?.code}]','',[responseMap:responseMap]))
                return null
            } else {
                Object jr = responseMap.result
                if (jr instanceof Map) {
                    return (Map) jr
                } else {
                    return [response:jr]
                }
            }
        } else {
            String errMsg = ec.resource.expand('JSON-RPC response was not a object/Map for service [${serviceName ?: method}]: ${jsonObj}','',[serviceName:serviceName,method:method,jsonObj:jsonObj])
            logger.error(errMsg)
            ec.message.addError(errMsg)
            return null
        }
    }

    public void destroy() { }
}
