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

import groovy.transform.CompileStatic
// import org.apache.xmlrpc.client.XmlRpcClientConfigImpl
// import org.apache.xmlrpc.client.XmlRpcClient
// import org.apache.xmlrpc.client.XmlRpcCommonsTransportFactory

import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.impl.service.ServiceRunner

@CompileStatic
public class RemoteXmlrpcServiceRunner implements ServiceRunner {
    protected ServiceFacadeImpl sfi = null

    RemoteXmlrpcServiceRunner() {}

    public ServiceRunner init(ServiceFacadeImpl sfi) { this.sfi = sfi; return this }

    public Map<String, Object> runService(ServiceDefinition sd, Map<String, Object> parameters) {
        throw new IllegalArgumentException("RemoteXmlrpcServiceRunner not currently supported")
        /*
        String location = sd.serviceNode.attribute("location")
        String method = sd.serviceNode.attribute("method")
        if (!location) throw new IllegalArgumentException("Cannot call remote service [${sd.serviceName}] because it has no location specified.")
        if (!method) throw new IllegalArgumentException("Cannot call remote service [${sd.serviceName}] because it has no method specified.")

        XmlRpcClientConfigImpl config = new XmlRpcClientConfigImpl()
        config.setServerURL(new URL(location))
        XmlRpcClient client = new XmlRpcClient()
        client.setTransportFactory(new XmlRpcCommonsTransportFactory(client))
        client.setConfig(config)

        Object result = client.execute(method, [parameters])

        if (!result) return null
        if (result instanceof Map<String, Object>) {
            return result
        } else if (result instanceof List && ((List) result).size() == 1 && ((List) result).get(0) instanceof Map<String, Object>) {
            return (Map<String, Object>) ((List) result).get(0)
        } else {
            return [response:result]
        }
        */
    }

    public void destroy() { }
}
