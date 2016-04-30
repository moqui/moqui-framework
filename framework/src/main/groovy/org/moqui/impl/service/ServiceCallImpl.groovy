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
package org.moqui.impl.service

import groovy.transform.CompileStatic
import org.moqui.service.ServiceCall
import org.moqui.service.ServiceException

@CompileStatic
class ServiceCallImpl implements ServiceCall {
    protected ServiceFacadeImpl sfi
    protected String path = (String) null
    protected String verb = (String) null
    protected String noun = (String) null
    protected ServiceDefinition sd = (ServiceDefinition) null

    protected String serviceName = (String) null
    protected String serviceNameNoHash = (String) null

    protected Map<String, Object> parameters = new HashMap<String, Object>()

    ServiceCallImpl(ServiceFacadeImpl sfi) { this.sfi = sfi }

    protected void setServiceName(String serviceName) {
        if (serviceName == null || serviceName.length() == 0) throw new ServiceException("Service name cannot be empty")
        sd = sfi.getServiceDefinition(serviceName)
        if (sd != null) {
            path = sd.getPath()
            verb = sd.getVerb()
            noun = sd.getNoun()
        } else {
            path = ServiceDefinition.getPathFromName(serviceName)
            verb = ServiceDefinition.getVerbFromName(serviceName)
            noun = ServiceDefinition.getNounFromName(serviceName)
        }
    }

    @Override
    String getServiceName() {
        if (serviceName == null) serviceName = (path != null && path.length() > 0 ? path + "." : "") + verb +
                (noun != null && noun.length() > 0 ? "#" + noun : "")
        return serviceName
    }
    String getServiceNameNoHash() {
        if (serviceNameNoHash == null) serviceNameNoHash = (path != null && path.length() > 0 ? path + "." : "") +
                verb + (noun != null ? noun : "")
        return serviceNameNoHash
    }

    @Override
    Map<String, Object> getCurrentParameters() { return parameters }

    ServiceDefinition getServiceDefinition() {
        if (sd == null) sd = sfi.getServiceDefinition(getServiceName())
        return sd
    }

    boolean isEntityAutoPattern() { return sfi.isEntityAutoPattern(path, verb, noun) }
}
