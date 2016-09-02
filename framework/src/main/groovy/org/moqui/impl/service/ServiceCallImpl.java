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
package org.moqui.impl.service;

import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.impl.context.ArtifactExecutionInfoImpl;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.service.ServiceCall;
import org.moqui.service.ServiceException;

import java.util.HashMap;
import java.util.Map;

public class ServiceCallImpl implements ServiceCall {
    protected final ServiceFacadeImpl sfi;
    protected String path = null;
    protected String verb = null;
    protected String noun = null;
    protected ServiceDefinition sd = null;
    protected String serviceName = null;
    protected String serviceNameNoHash = null;
    protected Map<String, Object> parameters = new HashMap<>();

    public ServiceCallImpl(ServiceFacadeImpl sfi) { this.sfi = sfi; }

    protected void serviceNameInternal(String serviceName) {
        if (serviceName == null || serviceName.isEmpty()) throw new ServiceException("Service name cannot be empty");
        sd = sfi.getServiceDefinition(serviceName);
        if (sd != null) {
            path = sd.verb;
            verb = sd.verb;
            noun = sd.verb;
            this.serviceName = sd.serviceName;
            serviceNameNoHash = sd.serviceNameNoHash;
        } else {
            path = ServiceDefinition.getPathFromName(serviceName);
            verb = ServiceDefinition.getVerbFromName(serviceName);
            noun = ServiceDefinition.getNounFromName(serviceName);
            this.serviceName = serviceName;
            if (serviceName.contains("#")) serviceNameNoHash = serviceName.replace("#", "");
            else serviceNameNoHash = serviceName;
        }
    }

    protected void serviceNameInternal(String path, String verb, String noun) {
        this.path = path;
        this.verb = verb;
        this.noun = noun;
        serviceName = ServiceDefinition.makeServiceName(path, verb, noun);
        serviceNameNoHash = serviceName.replace("#", "");
    }

    @Override
    public String getServiceName() {
        if (serviceName == null) serviceName = ServiceDefinition.makeServiceName(path, verb, noun);
        return serviceName;
    }
    public String getServiceNameNoHash() {
        if (serviceNameNoHash == null) serviceNameNoHash = ServiceDefinition.makeServiceNameNoHash(path, verb, noun);
        return serviceNameNoHash;
    }

    @Override
    public Map<String, Object> getCurrentParameters() {
        return parameters;
    }

    public ServiceDefinition getServiceDefinition() {
        if (sd == null) sd = sfi.getServiceDefinition(serviceName);
        return sd;
    }

    public boolean isEntityAutoPattern() {
        return sfi.isEntityAutoPattern(path, verb, noun);
    }

    public void validateCall(ExecutionContextImpl eci) {
        // Before scheduling the service check a few basic things so they show up sooner than later:
        ServiceDefinition sd = sfi.getServiceDefinition(getServiceName());
        if (sd == null && !isEntityAutoPattern())
            throw new IllegalArgumentException("Could not find service with name [" + getServiceName() + "]");

        if (sd != null) {
            String serviceType = sd.serviceNode.attribute("type");
            if (serviceType == null || serviceType.isEmpty()) serviceType = "inline";
            if ("interface".equals(serviceType)) throw new IllegalArgumentException("Cannot run interface service [" + getServiceName() + "]");
            ServiceRunner sr = sfi.getServiceRunner(serviceType);
            if (sr == null) throw new IllegalArgumentException("Could not find service runner for type [" + serviceType + "] for service [" + getServiceName() + "]");
            // validation
            sd.convertValidateCleanParameters(this.parameters, eci);
            // if error(s) in parameters, return now with no results
            if (eci.getMessage().hasError()) return;
        }


        // always do an authz before scheduling the job
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(getServiceName(), ArtifactExecutionInfo.AT_SERVICE, ServiceDefinition.getVerbAuthzActionEnum(verb), null);
        aei.setTrackArtifactHit(false);
        eci.getArtifactExecutionImpl().pushInternal(aei, (sd != null && "true".equals(sd.authenticate)));
        // pop immediately, just did the push to to an authz
        eci.getArtifactExecution().pop(aei);

        parameters.put("authUsername", eci.getUser().getUsername());
        parameters.put("authTenantId", eci.getTenantId());

        // logger.warn("=========== async call ${serviceName}, parameters: ${parameters}")
    }
}
