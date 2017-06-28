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
    protected boolean noSd = false;
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
            // if the service is not found must be an entity auto, but if there is a path then error
            if (path == null || path.isEmpty()) {
                noSd = true;
            } else {
                throw new ServiceException("Service not found with name " + serviceName);
            }
            this.serviceName = serviceName;
            serviceNameNoHash = serviceName.replace("#", "");
        }
    }

    protected void serviceNameInternal(String path, String verb, String noun) {
        if (path == null || path.isEmpty()) {
            noSd = true;
        } else {
            this.path = path;
        }
        this.verb = verb;
        this.noun = noun;
        StringBuilder sb = new StringBuilder();
        if (!noSd) sb.append(path).append('.');
        sb.append(verb);
        if (noun != null && !noun.isEmpty()) sb.append('#').append(noun);
        serviceName = sb.toString();
        if (noSd) {
            serviceNameNoHash = serviceName.replace("#", "");
        } else {
            sd = sfi.getServiceDefinition(serviceName);
            if (sd == null) throw new ServiceException("Service not found with name " + serviceName + " (path: " + path + ", verb: " + verb + ", noun: " + noun + ")");
            serviceNameNoHash = sd.serviceNameNoHash;
        }
    }

    @Override
    public String getServiceName() { return serviceName; }

    @Override
    public Map<String, Object> getCurrentParameters() {
        return parameters;
    }

    public ServiceDefinition getServiceDefinition() {
        // this should now never happen, sd now always set on name set
        // if (sd == null && !noSd) sd = sfi.getServiceDefinition(serviceName);
        return sd;
    }

    public boolean isEntityAutoPattern() {
        return noSd;
        // return sfi.isEntityAutoPattern(path, verb, noun);
    }

    public void validateCall(ExecutionContextImpl eci) {
        // Before scheduling the service check a few basic things so they show up sooner than later:
        ServiceDefinition sd = sfi.getServiceDefinition(getServiceName());
        if (sd == null && !isEntityAutoPattern())
            throw new ServiceException("Could not find service with name [" + getServiceName() + "]");

        if (sd != null) {
            String serviceType = sd.serviceType;
            if (serviceType == null || serviceType.isEmpty()) serviceType = "inline";
            if ("interface".equals(serviceType)) throw new ServiceException("Cannot run interface service [" + getServiceName() + "]");
            ServiceRunner sr = sfi.getServiceRunner(serviceType);
            if (sr == null) throw new ServiceException("Could not find service runner for type [" + serviceType + "] for service [" + getServiceName() + "]");
            // validation
            parameters = sd.convertValidateCleanParameters(parameters, eci);
            // if error(s) in parameters, return now with no results
            if (eci.getMessage().hasError()) return;
        }


        // always do an authz before scheduling the job
        ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(getServiceName(), ArtifactExecutionInfo.AT_SERVICE, ServiceDefinition.getVerbAuthzActionEnum(verb), null);
        aei.setTrackArtifactHit(false);
        eci.artifactExecutionFacade.pushInternal(aei, (sd != null && "true".equals(sd.authenticate)), true);
        // pop immediately, just did the push to to an authz
        eci.artifactExecutionFacade.pop(aei);

        parameters.put("authUsername", eci.userFacade.getUsername());

        // logger.warn("=========== async call ${serviceName}, parameters: ${parameters}")
    }
}
