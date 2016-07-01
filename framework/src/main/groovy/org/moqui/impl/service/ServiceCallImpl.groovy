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
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.ExecutionContextImpl
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

    void validateCall(ExecutionContextImpl eci) {
        // Before scheduling the service check a few basic things so they show up sooner than later:
        ServiceDefinition sd = sfi.getServiceDefinition(getServiceName())
        if (sd == null && !isEntityAutoPattern()) throw new IllegalArgumentException("Could not find service with name [${getServiceName()}]")

        if (sd != null) {
            String serviceType = (String) sd.serviceNode.attribute('type') ?: "inline"
            if (serviceType == "interface") throw new IllegalArgumentException("Cannot run interface service [${getServiceName()}]")
            ServiceRunner sr = sfi.getServiceRunner(serviceType)
            if (sr == null) throw new IllegalArgumentException("Could not find service runner for type [${serviceType}] for service [${getServiceName()}]")
            // validation
            sd.convertValidateCleanParameters(this.parameters, eci)
            // if error(s) in parameters, return now with no results
            if (eci.getMessage().hasError()) return
        }

        // always do an authz before scheduling the job
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(getServiceName(),
                ArtifactExecutionInfo.AT_SERVICE, ServiceDefinition.getVerbAuthzActionEnum(verb))
        eci.getArtifactExecutionImpl().pushInternal(aei, (sd != null && sd.getAuthenticate() == "true"))
        // pop immediately, just did the push to to an authz
        eci.getArtifactExecution().pop(aei)

        parameters.authUsername = eci.getUser().getUsername()
        parameters.authTenantId = eci.getTenantId()

        // logger.warn("=========== async call ${serviceName}, parameters: ${parameters}")
    }
}
