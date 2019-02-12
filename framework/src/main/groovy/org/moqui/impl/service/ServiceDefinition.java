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

import org.apache.commons.validator.routines.CreditCardValidator;
import org.apache.commons.validator.routines.EmailValidator;
import org.apache.commons.validator.routines.UrlValidator;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;

import org.moqui.context.ArtifactExecutionInfo;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.actions.XmlAction;
import org.moqui.impl.context.ExecutionContextImpl;
import org.moqui.impl.entity.EntityDefinition;
import org.moqui.service.ServiceException;
import org.moqui.util.CollectionUtilities;
import org.moqui.util.MNode;
import org.moqui.util.ObjectUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

public class ServiceDefinition {
    protected static final Logger logger = LoggerFactory.getLogger(ServiceDefinition.class);
    private static final EmailValidator emailValidator = EmailValidator.getInstance();
    private static final UrlValidator urlValidator = new UrlValidator(UrlValidator.ALLOW_ALL_SCHEMES);

    public final ServiceFacadeImpl sfi;
    public final MNode serviceNode;

    private final LinkedHashMap<String, ParameterInfo> inParameterInfoMap = new LinkedHashMap<>();
    private final ParameterInfo[] inParameterInfoArray;
    // private final boolean inParameterHasDefault;
    private final LinkedHashMap<String, ParameterInfo> outParameterInfoMap = new LinkedHashMap<>();
    public final ArrayList<String> inParameterNameList = new ArrayList<>();
    public final ArrayList<String> outParameterNameList = new ArrayList<>();
    public final String[] outParameterNameArray;

    public final String path;
    public final String verb;
    public final String noun;
    public final String serviceName;
    public final String serviceNameNoHash;

    public final String location;
    public final String method;
    public final XmlAction xmlAction;

    public final String authenticate;
    public final ArtifactExecutionInfo.AuthzAction authzAction;
    public final String serviceType;
    public final ServiceRunner serviceRunner;
    public final boolean txIgnore;
    public final boolean txForceNew;
    public final boolean txUseCache;
    public final boolean noTxCache;
    public final Integer txTimeout;
    public final boolean validate;
    public final boolean allowRemote;

    public final boolean hasSemaphore;
    public final String semaphore, semaphoreName, semaphoreParameter;
    public final long semaphoreIgnoreMillis, semaphoreSleepTime, semaphoreTimeoutTime;

    public ServiceDefinition(ServiceFacadeImpl sfi, String path, MNode sn) {
        this.sfi = sfi;
        this.serviceNode = sn.deepCopy(null);
        this.path = path;
        this.verb = serviceNode.attribute("verb");
        this.noun = serviceNode.attribute("noun");

        serviceName = makeServiceName(path, verb, noun);
        serviceNameNoHash = makeServiceNameNoHash(path, verb, noun);
        location = serviceNode.attribute("location");
        method = serviceNode.attribute("method");

        ArtifactExecutionInfo.AuthzAction tempAction = null;
        String authzActionAttr = serviceNode.attribute("authz-action");
        if (authzActionAttr != null && !authzActionAttr.isEmpty()) tempAction = ArtifactExecutionInfo.authzActionByName.get(authzActionAttr);
        if (tempAction == null) tempAction = verbAuthzActionEnumMap.get(verb);
        if (tempAction == null) tempAction = ArtifactExecutionInfo.AUTHZA_ALL;
        authzAction = tempAction;

        MNode inParameters = new MNode("in-parameters", null);
        MNode outParameters = new MNode("out-parameters", null);

        // handle implements elements
        if (serviceNode.hasChild("implements")) for (MNode implementsNode : serviceNode.children("implements")) {
            final String implServiceName = implementsNode.attribute("service");
            String implRequired = implementsNode.attribute("required");// no default here, only used if has a value
            if (implRequired != null && implRequired.isEmpty()) implRequired = null;
            ServiceDefinition sd = sfi.getServiceDefinition(implServiceName);
            if (sd == null) throw new ServiceException("Service " + implServiceName +
                    " not found, specified in service.implements in service " + serviceName);

            // these are the first params to be set, so just deep copy them over
            MNode implInParms = sd.serviceNode.first("in-parameters");
            if (implInParms != null && implInParms.hasChild("parameter")) {
                for (MNode parameter : implInParms.children("parameter")) {
                    MNode newParameter = parameter.deepCopy(null);
                    if (implRequired != null) newParameter.getAttributes().put("required", implRequired);
                    inParameters.append(newParameter);
                }
            }

            MNode implOutParms = sd.serviceNode.first("out-parameters");
            if (implOutParms != null && implOutParms.hasChild("parameter")) {
                for (MNode parameter : implOutParms.children("parameter")) {
                    MNode newParameter = parameter.deepCopy(null);
                    if (implRequired != null) newParameter.getAttributes().put("required", implRequired);
                    outParameters.append(newParameter);
                }
            }
        }

        // expand auto-parameters and merge parameter in in-parameters and out-parameters
        // if noun is a valid entity name set it on parameters with valid field names on it
        EntityDefinition ed = null;
        if (sfi.ecfi.entityFacade.isEntityDefined(this.noun))
            ed = sfi.ecfi.entityFacade.getEntityDefinition(this.noun);
        if (serviceNode.hasChild("in-parameters")) {
            for (MNode paramNode : serviceNode.first("in-parameters").getChildren()) {
                if ("auto-parameters".equals(paramNode.getName())) {
                    mergeAutoParameters(inParameters, paramNode);
                } else if (paramNode.getName().equals("parameter")) {
                    mergeParameter(inParameters, paramNode, ed);
                }
            }
        }

        if (serviceNode.hasChild("out-parameters")) {
            for (MNode paramNode : serviceNode.first("out-parameters").getChildren()) {
                if ("auto-parameters".equals(paramNode.getName())) {
                    mergeAutoParameters(outParameters, paramNode);
                } else if ("parameter".equals(paramNode.getName())) {
                    mergeParameter(outParameters, paramNode, ed);
                }
            }
        }

        // replace the in-parameters and out-parameters Nodes for the service
        if (serviceNode.hasChild("in-parameters")) serviceNode.remove("in-parameters");
        serviceNode.append(inParameters);
        if (serviceNode.hasChild("out-parameters")) serviceNode.remove("out-parameters");
        serviceNode.append(outParameters);

        if (logger.isTraceEnabled()) logger.trace("After merge for service " + serviceName + " node is:\n" + serviceNode.toString());

        // if this is an inline service, get that now
        if (serviceNode.hasChild("actions")) {
            xmlAction = new XmlAction(sfi.ecfi, serviceNode.first("actions"), serviceName);
        } else {
            xmlAction = null;
        }

        final String authenticateAttr = serviceNode.attribute("authenticate");
        authenticate = authenticateAttr != null && !authenticateAttr.isEmpty() ? authenticateAttr : "true";
        final String typeAttr = serviceNode.attribute("type");
        serviceType = typeAttr != null && !typeAttr.isEmpty() ? typeAttr : "inline";
        serviceRunner = sfi.getServiceRunner(serviceType);

        String transactionAttr = serviceNode.attribute("transaction");
        txIgnore = "ignore".equals(transactionAttr);
        txForceNew = "force-new".equals(transactionAttr) || "force-cache".equals(transactionAttr);
        txUseCache = "cache".equals(transactionAttr) || "force-cache".equals(transactionAttr);
        noTxCache = "true".equals(serviceNode.attribute("no-tx-cache"));
        String txTimeoutAttr = serviceNode.attribute("transaction-timeout");
        if (txTimeoutAttr != null && !txTimeoutAttr.isEmpty()) {
            txTimeout = Integer.valueOf(txTimeoutAttr);
        } else {
            txTimeout = null;
        }

        semaphore = serviceNode.attribute("semaphore");
        semaphoreName = serviceNode.attribute("semaphore-name");
        hasSemaphore = semaphore != null && semaphore.length() > 0 && !"none".equals(semaphore);
        semaphoreParameter = serviceNode.attribute("semaphore-parameter");
        String ignoreAttr = serviceNode.attribute("semaphore-ignore");
        if (ignoreAttr == null || ignoreAttr.isEmpty()) ignoreAttr = "3600";
        semaphoreIgnoreMillis = Long.parseLong(ignoreAttr) * 1000;
        String sleepAttr = serviceNode.attribute("semaphore-sleep");
        if (sleepAttr == null || sleepAttr.isEmpty()) sleepAttr = "5";
        semaphoreSleepTime = Long.parseLong(sleepAttr) * 1000;
        String timeoutAttr = serviceNode.attribute("semaphore-timeout");
        if (timeoutAttr == null || timeoutAttr.isEmpty()) timeoutAttr = "120";
        semaphoreTimeoutTime = Long.parseLong(timeoutAttr) * 1000;

        // validate defaults to true
        validate = !"false".equals(serviceNode.attribute("validate"));
        allowRemote = "true".equals(serviceNode.attribute("allow-remote"));

        MNode inParametersNode = serviceNode.first("in-parameters");
        MNode outParametersNode = serviceNode.first("out-parameters");

        if (inParametersNode != null) for (MNode parameter : inParametersNode.children("parameter")) {
            String parameterName = parameter.attribute("name");
            inParameterInfoMap.put(parameterName, new ParameterInfo(this, parameter));
            inParameterNameList.add(parameterName);
        }
        int inParameterNameListSize = inParameterNameList.size();
        inParameterInfoArray = new ParameterInfo[inParameterNameListSize];
        // boolean tempHasDefault = false;
        for (int i = 0; i < inParameterNameListSize; i++) {
            String parmName = inParameterNameList.get(i);
            ParameterInfo pi = inParameterInfoMap.get(parmName);
            inParameterInfoArray[i] = pi;
            // if (pi.thisOrChildHasDefault) tempHasDefault = true;
        }
        // inParameterHasDefault = tempHasDefault;

        if (outParametersNode != null) for (MNode parameter : outParametersNode.children("parameter")) {
            String parameterName = parameter.attribute("name");
            outParameterInfoMap.put(parameterName, new ParameterInfo(this, parameter));
            outParameterNameList.add(parameterName);
        }
        outParameterNameArray = new String[outParameterNameList.size()];
        outParameterNameList.toArray(outParameterNameArray);
    }

    private void mergeAutoParameters(MNode parametersNode, MNode autoParameters) {
        String entityName = autoParameters.attribute("entity-name");
        if (entityName == null || entityName.isEmpty()) entityName = noun;
        if (entityName == null || entityName.isEmpty()) throw new ServiceException("Error in auto-parameters in service " +
                serviceName + ", no auto-parameters.@entity-name and no service.@noun for a default");
        EntityDefinition ed = sfi.ecfi.entityFacade.getEntityDefinition(entityName);
        if (ed == null) throw new ServiceException("Error in auto-parameters in service " + serviceName + ", the entity-name or noun [" + entityName + "] is not a valid entity name");

        Set<String> fieldsToExclude = new HashSet<>();
        for (MNode excludeNode : autoParameters.children("exclude")) {
            fieldsToExclude.add(excludeNode.attribute("field-name"));
        }


        String includeStr = autoParameters.attribute("include");
        if (includeStr == null || includeStr.isEmpty()) includeStr = "all";
        String requiredStr = autoParameters.attribute("required");
        if (requiredStr == null || requiredStr.isEmpty()) requiredStr = "false";
        String allowHtmlStr = autoParameters.attribute("allow-html");
        if (allowHtmlStr == null || allowHtmlStr.isEmpty()) allowHtmlStr = "none";
        for (String fieldName : ed.getFieldNames("all".equals(includeStr) || "pk".equals(includeStr), "all".equals(includeStr) || "nonpk".equals(includeStr))) {
            if (fieldsToExclude.contains(fieldName)) continue;

            String javaType = sfi.ecfi.entityFacade.getFieldJavaType(ed.getFieldInfo(fieldName).type, ed);
            Map<String, String> map = new LinkedHashMap<>(5);
            map.put("type", javaType);
            map.put("required", requiredStr);
            map.put("allow-html", allowHtmlStr);
            map.put("entity-name", ed.fullEntityName);
            map.put("field-name", fieldName);
            mergeParameter(parametersNode, fieldName, map);
        }
    }

    private void mergeParameter(MNode parametersNode, MNode overrideParameterNode, EntityDefinition ed) {
        MNode baseParameterNode = mergeParameter(parametersNode, overrideParameterNode.attribute("name"), overrideParameterNode.getAttributes());
        // merge description, ParameterValidations
        for (MNode childNode : overrideParameterNode.getChildren()) {
            if ("description".equals(childNode.getName())) {
                if (baseParameterNode.hasChild(childNode.getName())) baseParameterNode.remove(childNode.getName());
            }

            if ("auto-parameters".equals(childNode.getName())) {
                mergeAutoParameters(baseParameterNode, childNode);
            } else if ("parameter".equals(childNode.getName())) {
                mergeParameter(baseParameterNode, childNode, ed);
            } else {
                // is a validation, just add it in, or the original has been removed so add the new one
                baseParameterNode.append(childNode);
            }

        }

        String entityNameAttr = baseParameterNode.attribute("entity-name");
        if (entityNameAttr != null && !entityNameAttr.isEmpty()) {
            String fieldNameAttr = baseParameterNode.attribute("field-name");
            if (fieldNameAttr == null || fieldNameAttr.isEmpty())
                baseParameterNode.getAttributes().put("field-name", baseParameterNode.attribute("name"));
        } else if (ed != null && ed.isField(baseParameterNode.attribute("name"))) {
            baseParameterNode.getAttributes().put("entity-name", ed.fullEntityName);
            baseParameterNode.getAttributes().put("field-name", baseParameterNode.attribute("name"));
        }

    }

    private static MNode mergeParameter(MNode parametersNode, final String parameterName, Map<String, String> attributeMap) {
        MNode baseParameterNode = parametersNode.first("parameter", "name", parameterName);
        if (baseParameterNode == null) {
            Map<String, String> map = new HashMap<>(1); map.put("name", parameterName);
            baseParameterNode = parametersNode.append("parameter", map);
        }
        baseParameterNode.getAttributes().putAll(attributeMap);
        return baseParameterNode;
    }

    public static String makeServiceName(String path, String verb, String noun) {
        return (path != null && !path.isEmpty() ? path + "." : "") + verb + (noun != null && !noun.isEmpty() ? "#" + noun : "");
    }

    public static String makeServiceNameNoHash(String path, String verb, String noun) {
        return (path != null && !path.isEmpty() ? path + "." : "") + verb + (noun != null ? noun : "");
    }

    public static String getPathFromName(String serviceName) {
        String p = serviceName;
        // do hash first since a noun following hash may have dots in it
        int hashIndex = p.indexOf('#');
        if (hashIndex > 0) p = p.substring(0, hashIndex);
        int lastDotIndex = p.lastIndexOf('.');
        if (lastDotIndex <= 0) return null;
        return p.substring(0, lastDotIndex);
    }

    public static String getVerbFromName(String serviceName) {
        String v = serviceName;
        // do hash first since a noun following hash may have dots in it
        int hashIndex = v.indexOf('#');
        if (hashIndex > 0) v = v.substring(0, hashIndex);
        int lastDotIndex = v.lastIndexOf('.');
        if (lastDotIndex > 0) v = v.substring(lastDotIndex + 1);
        return v;
    }

    public static String getNounFromName(String serviceName) {
        int hashIndex = serviceName.lastIndexOf('#');
        if (hashIndex < 0) return null;
        return serviceName.substring(hashIndex + 1);
    }

    public static ArtifactExecutionInfo.AuthzAction getVerbAuthzActionEnum(String theVerb) {
        // default to require the "All" authz action, and for special verbs default to something more appropriate
        ArtifactExecutionInfo.AuthzAction authzAction = verbAuthzActionEnumMap.get(theVerb);
        if (authzAction == null) authzAction = ArtifactExecutionInfo.AUTHZA_ALL;
        return authzAction;
    }

    public MNode getInParameter(String name) {
        ParameterInfo pi = inParameterInfoMap.get(name);
        if (pi == null) return null;
        return pi.parameterNode;
    }

    public ArrayList<String> getInParameterNames() {
        return inParameterNameList;
    }

    public MNode getOutParameter(String name) {
        ParameterInfo pi = outParameterInfoMap.get(name);
        if (pi == null) return null;
        return pi.parameterNode;
    }

    public ArrayList<String> getOutParameterNames() {
        return outParameterNameList;
    }

    public Map<String, Object>  convertValidateCleanParameters(Map<String, Object> parameters, ExecutionContextImpl eci) {
        // logger.warn("BEFORE ${serviceName} convertValidateCleanParameters: ${parameters.toString()}")

        // checkParameterMap("", parameters, parameters, inParameterInfoMap, eci);
        return nestedParameterClean("", parameters, inParameterInfoArray, eci);

        // logger.warn("AFTER ${serviceName} convertValidateCleanParameters: ${parameters.toString()}")
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> nestedParameterClean(String namePrefix, Map<String, Object> parameters,
                                      ParameterInfo[] parameterInfoArray, ExecutionContextImpl eci) {
        HashMap<String, Object> newMap = new HashMap<>();
        for (int i = 0; i < parameterInfoArray.length; i++) {
            ParameterInfo parameterInfo = parameterInfoArray[i];
            String parameterName = parameterInfo.name;

            boolean hasParameter = parameters.containsKey(parameterName);
            Object parameterValue = hasParameter ? parameters.remove(parameterName) : null;

            boolean parameterIsEmpty;
            boolean isString = false;
            boolean isCollection = false;
            boolean isMap = false;
            Class parameterClass = null;
            if (parameterValue != null) {
                if (parameterValue instanceof CharSequence) {
                    String stringValue = parameterValue.toString();
                    parameterValue = stringValue;
                    isString = true;
                    parameterClass = String.class;
                    parameterIsEmpty = stringValue.isEmpty();
                } else {
                    parameterClass = parameterValue.getClass();
                    if (parameterValue instanceof Map) {
                        isMap = true;
                        parameterIsEmpty = ((Map) parameterValue).isEmpty();
                    } else if (parameterValue instanceof Collection) {
                        isCollection = true;
                        parameterIsEmpty = ((Collection) parameterValue).isEmpty();
                    } else {
                        parameterIsEmpty = false;
                    }
                }
            } else {
                parameterIsEmpty = true;
            }

            // set the default if applicable
            if (parameterIsEmpty) {
                if (parameterInfo.hasDefault) {
                    // TODO: consider doing this as a second pass so newMap has all parameters
                    if (parameterInfo.defaultStr != null) {
                        Map<String, Object> combinedMap = new HashMap<>(parameters);
                        combinedMap.putAll(newMap);
                        parameterValue = eci.resourceFacade.expression(parameterInfo.defaultStr, null, combinedMap);
                        if (parameterValue != null) {
                            hasParameter = true;
                            isString = false;
                            isCollection = false;
                            isMap = false;
                            if (parameterValue instanceof CharSequence) {
                                String stringValue = parameterValue.toString();
                                parameterValue = stringValue;
                                isString = true;
                                parameterClass = String.class;
                                parameterIsEmpty = stringValue.isEmpty();
                            } else {
                                parameterClass = parameterValue.getClass();
                                if (parameterValue instanceof Map) {
                                    isMap = true;
                                    parameterIsEmpty = ((Map) parameterValue).isEmpty();
                                } else if (parameterValue instanceof Collection) {
                                    isCollection = true;
                                    parameterIsEmpty = ((Collection) parameterValue).isEmpty();
                                } else {
                                    parameterIsEmpty = false;
                                }
                            }
                        }
                    } else if (parameterInfo.defaultValue != null) {
                        String stringValue;
                        if (parameterInfo.defaultValueNeedsExpand) {
                            Map<String, Object> combinedMap = new HashMap<>(parameters);
                            combinedMap.putAll(newMap);
                            stringValue = eci.resourceFacade.expand(parameterInfo.defaultValue, null, combinedMap, false);
                        } else {
                            stringValue = parameterInfo.defaultValue;
                        }
                        hasParameter = true;
                        parameterValue = stringValue;
                        isString = true;
                        parameterClass = String.class;
                        parameterIsEmpty = stringValue.isEmpty();
                    }
                } else {
                    // if empty but not null and types don't match set to null instead of trying to convert
                    if (parameterValue != null) {
                        boolean typeMatches;
                        if (parameterInfo.parmClass != null) {
                            typeMatches = parameterClass == parameterInfo.parmClass || parameterInfo.parmClass.isInstance(parameterValue);
                        } else {
                            typeMatches = ObjectUtilities.isInstanceOf(parameterValue, parameterInfo.type);
                        }
                        if (!typeMatches) parameterValue = null;
                    }
                }
                // if required and still empty (nothing from default), complain
                if (parameterIsEmpty && validate && parameterInfo.required)
                    eci.messageFacade.addValidationError(null, namePrefix + parameterName, serviceName, eci.getL10n().localize("Field cannot be empty"), null);
            }
            // NOTE: not else because parameterIsEmpty may be changed
            if (!parameterIsEmpty) {
                boolean typeMatches;
                if (parameterInfo.parmClass != null) {
                    typeMatches = parameterClass == parameterInfo.parmClass || parameterInfo.parmClass.isInstance(parameterValue);
                } else {
                    typeMatches = ObjectUtilities.isInstanceOf(parameterValue, parameterInfo.type);
                }
                if (!typeMatches) {
                    // convert type, at this point parameterValue is not empty and doesn't match parameter type
                    parameterValue = parameterInfo.convertType(namePrefix, parameterValue, isString, eci);
                    isString = false;
                    isCollection = false;
                    isMap = false;
                    if (parameterValue instanceof CharSequence) {
                        parameterValue = parameterValue.toString();
                        isString = true;
                    } else if (parameterValue instanceof Map) {
                            isMap = true;
                    } else if (parameterValue instanceof Collection) {
                        isCollection = true;
                    }
                }

                if (validate) {
                    if ((isString || isCollection) && ParameterInfo.ParameterAllowHtml.ANY != parameterInfo.allowHtml) {
                        Object htmlValidated = parameterInfo.validateParameterHtml(namePrefix, parameterValue, isString, eci);
                        // put the final parameterValue back into the parameters Map
                        if (htmlValidated != null) {
                            parameterValue = htmlValidated;
                        }
                    }

                    // check against validation sub-elements (do this after the convert so we can deal with objects when needed)
                    if (parameterInfo.validationNodeList != null) {
                        int valListSize = parameterInfo.validationNodeList.size();
                        for (int valIdx = 0; valIdx < valListSize; valIdx++) {
                            MNode valNode = parameterInfo.validationNodeList.get(valIdx);
                            // NOTE don't break on fail, we want to get a list of all failures for the user to see
                            try {
                                // validateParameterSingle calls eci.message.addValidationError as needed so nothing else to do here
                                validateParameterSingle(valNode, parameterName, parameterValue, eci);
                            } catch (Throwable t) {
                                logger.error("Error in validation", t);
                                Map<String, Object> map = new HashMap<>(3);
                                map.put("parameterValue", parameterValue); map.put("valNode", valNode); map.put("t", t);
                                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered failed ${valNode.name} validation: ${t.message}", "", map), null);
                            }
                        }
                    }
                }
                if (isMap && parameterInfo.childParameterInfoArray != null && parameterInfo.childParameterInfoArray.length > 0) {
                    parameterValue = nestedParameterClean(namePrefix + parameterName + ".",
                            (Map<String, Object>) parameterValue, parameterInfo.childParameterInfoArray, eci);
                }
            }

            if (hasParameter) newMap.put(parameterName, parameterValue);
        }

        // if we are not validating and there are parameters remaining, add them to the newMap
        if (!validate && parameters.size() > 0) {
            newMap.putAll(parameters);
        }

        return newMap;
    }

    private boolean validateParameterSingle(MNode valNode, String parameterName, Object pv, ExecutionContextImpl eci) {
        // should never be null (caller checks) but check just in case
        if (pv == null) return true;

        String validateName = valNode.getName();
        if ("val-or".equals(validateName)) {
            boolean anyPass = false;
            for (MNode child : valNode.getChildren()) if (validateParameterSingle(child, parameterName, pv, eci)) anyPass = true;
            return anyPass;
        } else if ("val-and".equals(validateName)) {
            boolean allPass = true;
            for (MNode child : valNode.getChildren()) if (!validateParameterSingle(child, parameterName, pv, eci)) allPass = false;
            return allPass;
        } else if ("val-not".equals(validateName)) {
            boolean allPass = true;
            for (MNode child : valNode.getChildren()) if (!validateParameterSingle(child, parameterName, pv, eci)) allPass = false;
            return !allPass;
        } else if ("matches".equals(validateName)) {
            if (!(pv instanceof CharSequence)) {
                Map<String, Object> map = new HashMap<>(1); map.put("pv", pv);
                eci.getMessage().addValidationError(null, parameterName, serviceName,
                        eci.getResource().expand("Value entered (${pv}) is not a string, cannot do matches validation.", "", map), null);
                return false;
            }

            String pvString = pv.toString();
            String regexp = valNode.attribute("regexp");
            if (regexp != null && !regexp.isEmpty() && !pvString.matches(regexp)) {
                // a message attribute should always be there, but just in case we'll have a default
                final String message = valNode.attribute("message");
                Map<String, Object> map = new HashMap<>(2); map.put("pv", pv); map.put("regexp", regexp);
                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand(message != null && !message.isEmpty() ? message : "Value entered (${pv}) did not match expression: ${regexp}", "", map), null);
                return false;
            }

            return true;
        } else if ("number-range".equals(validateName)) {
            BigDecimal bdVal = new BigDecimal(pv.toString());
            String minStr = valNode.attribute("min");
            if (minStr != null && !minStr.isEmpty()) {
                BigDecimal min = new BigDecimal(minStr);
                if ("false".equals(valNode.attribute("min-include-equals"))) {
                    if (bdVal.compareTo(min) <= 0) {
                        Map<String, Object> map = new HashMap<>(2); map.put("pv", pv); map.put("min", min);
                        eci.getMessage().addValidationError(null, parameterName, serviceName,
                                eci.getResource().expand("Value entered (${pv}) is less than or equal to ${min} must be greater than.", "", map), null);
                        return false;
                    }
                } else {
                    if (bdVal.compareTo(min) < 0) {
                        Map<String, Object> map = new HashMap<>(2); map.put("pv", pv); map.put("min", min);
                        eci.getMessage().addValidationError(null, parameterName, serviceName,
                                eci.getResource().expand("Value entered (${pv}) is less than ${min} and must be greater than or equal to.", "", map), null);
                        return false;
                    }
                }
            }

            String maxStr = valNode.attribute("max");
            if (maxStr != null && !maxStr.isEmpty()) {
                BigDecimal max = new BigDecimal(maxStr);
                if ("true".equals(valNode.attribute("max-include-equals"))) {
                    if (bdVal.compareTo(max) > 0) {
                        Map<String, Object> map = new HashMap<>(2); map.put("pv", pv); map.put("max", max);
                        eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${pv}) is greater than ${max} and must be less than or equal to.", "", map), null);
                        return false;
                    }

                } else {
                    if (bdVal.compareTo(max) >= 0) {
                        Map<String, Object> map = new HashMap<>(2); map.put("pv", pv); map.put("max", max);
                        eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${pv}) is greater than or equal to ${max} and must be less than.", "", map), null);
                        return false;
                    }
                }
            }

            return true;
        } else if ("number-integer".equals(validateName)) {
            try {
                new BigInteger(pv.toString());
            } catch (NumberFormatException e) {
                if (logger.isTraceEnabled())
                    logger.trace("Adding error message for NumberFormatException for BigInteger parse: " + e.toString());
                Map<String, Object> map = new HashMap<>(1); map.put("pv", pv);
                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value [${pv}] is not a whole (integer) number.", "", map), null);
                return false;
            }

            return true;
        } else if ("number-decimal".equals(validateName)) {
            try {
                new BigDecimal(pv.toString());
            } catch (NumberFormatException e) {
                if (logger.isTraceEnabled())
                    logger.trace("Adding error message for NumberFormatException for BigDecimal parse: " + e.toString());
                Map<String, Object> map = new HashMap<>(1);
                map.put("pv", pv);
                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value [${pv}] is not a decimal number.", "", map), null);
                return false;
            }

            return true;
        } else if ("text-length".equals(validateName)) {
            String str = pv.toString();
            String minStr = valNode.attribute("min");
            if (minStr != null && !minStr.isEmpty()) {
                int min = Integer.parseInt(minStr);
                if (str.length() < min) {
                    Map<String, Object> map = new HashMap<>(3); map.put("pv", pv); map.put("str", str); map.put("minStr", minStr);
                    eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${pv}), length ${str.length()}, is shorter than ${minStr} characters.", "", map), null);
                    return false;
                }

            }

            String maxStr = valNode.attribute("max");
            if (maxStr != null && !maxStr.isEmpty()) {
                int max = Integer.parseInt(maxStr);
                if (str.length() > max) {
                    Map<String, Object> map = new HashMap<>(3); map.put("pv", pv); map.put("str", str); map.put("maxStr", maxStr);
                    eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${pv}), length ${str.length()}, is longer than ${maxStr} characters.", "", map), null);
                    return false;
                }
            }

            return true;
        } else if ("text-email".equals(validateName)) {
            String str = pv.toString();
            if (!emailValidator.isValid(str)) {
                Map<String, Object> map = new HashMap<>(1); map.put("str", str);
                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${str}) is not a valid email address.", "", map), null);
                return false;
            }

            return true;
        } else if ("text-url".equals(validateName)) {
            String str = pv.toString();
            if (!urlValidator.isValid(str)) {
                Map<String, Object> map = new HashMap<>(1); map.put("str", str);
                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${str}) is not a valid URL.", "", map), null);
                return false;
            }

            return true;
        } else if ("text-letters".equals(validateName)) {
            String str = pv.toString();
            for (char c : str.toCharArray()) {
                if (!Character.isLetter(c)) {
                    Map<String, Object> map = new HashMap<>(1); map.put("str", str);
                    eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${str}) must have only letters.", "", map), null);
                    return false;
                }
            }

            return true;
        } else if ("text-digits".equals(validateName)) {
            String str = pv.toString();
            for (char c : str.toCharArray()) {
                if (!Character.isDigit(c)) {
                    Map<String, Object> map = new HashMap<>(1); map.put("str", str);
                    eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value [${str}] must have only digits.", "", map), null);
                    return false;
                }
            }

            return true;
        } else if ("time-range".equals(validateName)) {
            Calendar cal;
            String format = valNode.attribute("format");
            if (pv instanceof CharSequence) {
                cal = eci.getL10n().parseDateTime(pv.toString(), format);
            } else {
                // try letting groovy convert it
                cal = Calendar.getInstance();
                // TODO: not sure if this will work: ((pv as java.util.Date).getTime())
                cal.setTimeInMillis((DefaultGroovyMethods.asType(pv, Date.class)).getTime());
            }

            String after = valNode.attribute("after");
            if (after != null && !after.isEmpty()) {
                // handle after date/time/date-time depending on type of parameter, support "now" too
                Calendar compareCal;
                if ("now".equals(after)) {
                    compareCal = eci.getL10n().parseDateTime(eci.getL10n().format(eci.getUser().getNowTimestamp(), format), format);
                } else {
                    compareCal = eci.getL10n().parseDateTime(after, format);
                }
                if (cal != null && cal.compareTo(compareCal) < 0) {
                    Map<String, Object> map = new HashMap<>(2); map.put("pv", pv); map.put("after", after);
                    eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${pv}) is before ${after}.", "", map), null);
                    return false;
                }
            }

            String before = valNode.attribute("before");
            if (before != null && !before.isEmpty()) {
                // handle after date/time/date-time depending on type of parameter, support "now" too
                Calendar compareCal;
                if ("now".equals(before)) {
                    compareCal = eci.getL10n().parseDateTime(eci.getL10n().format(eci.getUser().getNowTimestamp(), format), format);
                } else {
                    compareCal = eci.getL10n().parseDateTime(before, format);
                }
                if (cal != null && cal.compareTo(compareCal) > 0) {
                    Map<String, Object> map = new HashMap<>(1); map.put("pv", pv);
                    eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered (${pv}) is after ${before}.", "", map), null);
                    return false;
                }
            }

            return true;
        } else if ("credit-card".equals(validateName)) {
            long creditCardTypes = 0;
            String types = valNode.attribute("types");
            if (types != null && !types.isEmpty()) {
                for (String cts : types.split(",")) creditCardTypes += creditCardTypeMap.get(cts.trim());
            } else {
                creditCardTypes = allCreditCards;
            }

            CreditCardValidator ccv = new CreditCardValidator(creditCardTypes);
            String str = pv.toString();
            if (!ccv.isValid(str)) {
                Map<String, Object> map = new HashMap<>(1); map.put("str", str);
                eci.getMessage().addValidationError(null, parameterName, serviceName, eci.getResource().expand("Value entered is not a valid credit card number.", "", map), null);
                return false;
            }

            return true;
        }
        // shouldn't get here, but just in case
        return true;
    }

    private static final HashMap<String, Long> creditCardTypeMap;
    static {
        HashMap<String, Long> map = new HashMap<>(5);
        map.put("visa", CreditCardValidator.VISA);
        map.put("mastercard", CreditCardValidator.MASTERCARD);
        map.put("amex", CreditCardValidator.AMEX);
        map.put("discover", CreditCardValidator.DISCOVER);
        map.put("dinersclub", CreditCardValidator.DINERS);
        creditCardTypeMap = map;
    }
    private static final long allCreditCards = CreditCardValidator.VISA + CreditCardValidator.MASTERCARD +
            CreditCardValidator.AMEX + CreditCardValidator.DISCOVER + CreditCardValidator.DINERS;

    public static final HashMap<String, ArtifactExecutionInfo.AuthzAction> verbAuthzActionEnumMap;
    static {
        HashMap<String, ArtifactExecutionInfo.AuthzAction> map = new HashMap<>(6);
        map.put("create", ArtifactExecutionInfo.AUTHZA_CREATE);
        map.put("update", ArtifactExecutionInfo.AUTHZA_UPDATE);
        map.put("store", ArtifactExecutionInfo.AUTHZA_UPDATE);
        map.put("delete", ArtifactExecutionInfo.AUTHZA_DELETE);
        map.put("view", ArtifactExecutionInfo.AUTHZA_VIEW);
        map.put("find", ArtifactExecutionInfo.AUTHZA_VIEW);
        map.put("get", ArtifactExecutionInfo.AUTHZA_VIEW);
        map.put("search", ArtifactExecutionInfo.AUTHZA_VIEW);
        verbAuthzActionEnumMap = map;
    }

    @SuppressWarnings("unchecked")
    public static void nestedRemoveNullsFromResultMap(Map<String, Object> result) {
        if (result == null) return;
        Iterator<Map.Entry<String, Object>> iter = result.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, Object> entry = iter.next();
            Object value = entry.getValue();
            if (value == null) { iter.remove(); continue; }
            if (value instanceof EntityValue) {
                entry.setValue(CollectionUtilities.removeNullsFromMap(((EntityValue) value).getMap()));
            } else if (value instanceof EntityList) {
                entry.setValue(((EntityList) value).getValueMapList());
            } else if (value instanceof Collection) {
                boolean foundEv = false;
                Collection valCol = (Collection) value;
                for (Object colEntry : valCol) {
                    if (colEntry instanceof EntityValue) {
                        foundEv = true;
                    } else if (colEntry instanceof Map) {
                        CollectionUtilities.removeNullsFromMap((Map) colEntry);
                    } else {
                        break;
                    }
                }
                if (foundEv) {
                    ArrayList newCol = new ArrayList(valCol.size());
                    for (Object colEntry : valCol) {
                        if (colEntry instanceof EntityValue) {
                            newCol.add(CollectionUtilities.removeNullsFromMap(((EntityValue) colEntry).getMap()));
                        } else {
                            newCol.add(colEntry);
                        }
                    }
                    entry.setValue(newCol);
                }
            }
        }
    }
}
