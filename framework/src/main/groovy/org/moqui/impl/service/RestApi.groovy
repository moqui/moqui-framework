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
import org.moqui.BaseException
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.AuthenticationRequiredException
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.resource.ResourceReference
import org.moqui.entity.EntityFind
import org.moqui.impl.context.ArtifactExecutionInfoImpl
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.FieldInfo
import org.moqui.impl.util.RestSchemaUtil
import org.moqui.jcache.MCache
import org.moqui.util.CollectionUtilities
import org.moqui.util.MNode
import org.moqui.util.SystemBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.Cache
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.math.RoundingMode

@CompileStatic
class RestApi {
    protected final static Logger logger = LoggerFactory.getLogger(RestApi.class)

    @SuppressWarnings("GrFinalVariableAccess") protected final ExecutionContextFactoryImpl ecfi
    @SuppressWarnings("GrFinalVariableAccess") final MCache<String, ResourceNode> rootResourceCache

    RestApi(ExecutionContextFactoryImpl ecfi) {
        this.ecfi = ecfi
        rootResourceCache = ecfi.cacheFacade.getLocalCache("service.rest.api")
        loadRootResourceNode(null)
    }

    ResourceNode getRootResourceNode(String name) {
        ResourceNode resourceNode = rootResourceCache.get(name)
        if (resourceNode != null) return resourceNode

        loadRootResourceNode(name)
        resourceNode = rootResourceCache.get(name)
        if (resourceNode != null) return resourceNode

        throw new ResourceNotFoundException("Service REST API Root resource not found with name ${name}")
    }

    synchronized void loadRootResourceNode(String name) {
        if (name != null) {
            ResourceNode resourceNode = rootResourceCache.get(name)
            if (resourceNode != null) return
        }

        long startTime = System.currentTimeMillis()
        // find *.rest.xml files in component/service directories, put in rootResourceMap
        for (String location in this.ecfi.getComponentBaseLocations().values()) {
            ResourceReference serviceDirRr = this.ecfi.resourceFacade.getLocationReference(location + "/service")
            if (serviceDirRr.supportsAll()) {
                // if for some weird reason this isn't a directory, skip it
                if (!serviceDirRr.isDirectory()) continue
                for (ResourceReference rr in serviceDirRr.directoryEntries) {
                    if (!rr.fileName.endsWith(".rest.xml")) continue
                    MNode rootNode = MNode.parse(rr)
                    if (name == null || name.equals(rootNode.attribute("name"))) {
                        ResourceNode rn = new ResourceNode(rootNode, null, ecfi)
                        rootResourceCache.put(rn.name, rn)
                        logger.info("Loaded REST API from ${rr.getFileName()} (${rn.childPaths} paths, ${rn.childMethods} methods)")
                        // logger.info(rn.toString())
                    }
                }
            } else {
                logger.warn("Can't load REST APIs from component at [${serviceDirRr.location}] because it doesn't support exists/directory/etc")
            }
        }
        logger.info("Loaded REST API files, ${rootResourceCache.size()} roots, in ${System.currentTimeMillis() - startTime}ms")
    }

    /** Used in tools dashboard screen */
    List<ResourceNode> getFreshRootResources() {
        loadRootResourceNode(null)
        List<ResourceNode> rootList = new ArrayList<>()
        for (Cache.Entry<String, ResourceNode> entry in rootResourceCache.getEntryList()) rootList.add(entry.getValue())
        return rootList
    }

    RestResult run(List<String> pathList, ExecutionContextImpl ec) {
        if (pathList == null || pathList.size() == 0) throw new ResourceNotFoundException("Cannot run REST service with no path")
        String firstPath = pathList[0]
        ResourceNode resourceNode = getRootResourceNode(firstPath)
        return resourceNode.visit(pathList, 0, ec)
    }

    Map<String, Object> getRamlMap(String rootResourceName, String linkPrefix) {
        ResourceNode resourceNode = getRootResourceNode(rootResourceName)

        Map<String, Object> typesMap = new TreeMap<String, Object>()

        Map<String, Object> rootMap = [title:(resourceNode.displayName ?: rootResourceName + ' REST API'),
                                       version:(resourceNode.version ?: '1.0'), baseUri:linkPrefix,
                                       mediaType:'application/json', types:typesMap] as Map<String, Object>
        Map<String, Object> headers = ['X-Total-Count':[type:'integer', description:"Count of all results (not just current page)"],
                                       'X-Page-Index':[type:'integer', description:"Index of current page"],
                                       'X-Page-Size':[type:'integer', description:"Number of results per page"],
                                       'X-Page-Max-Index':[type:'integer', description:"Highest page index given page size and count of results"],
                                       'X-Page-Range-Low':[type:'integer', description:"Index of first result in page"],
                                       'X-Page-Range-High':[type:'integer', description:"Index of last result in page"]] as Map<String, Object>
        rootMap.put('traits', [[paged:[queryParameters:RestSchemaUtil.ramlPaginationParameters, headers:headers]],
            [service:[responses:[401:[description:"Authentication required"], 403:[description:"Access Forbidden (no authz)"],
                                 429:[description:"Too Many Requests (tarpit)"], 500:[description:"General Error"]]]],
            [entity:[responses:[401:[description:"Authentication required"], 403:[description:"Access Forbidden (no authz)"],
                                404:[description:"Value Not Found"], 429:[description:"Too Many Requests (tarpit)"],
                                500:[description:"General Error"]]]]
        ])

        Map<String, Object> childrenMap = resourceNode.getRamlChildrenMap(typesMap)
        rootMap.put('/' + rootResourceName, childrenMap)

        return rootMap
    }

    Map<String, Object> getSwaggerMap(List<String> rootPathList, List<String> schemes, String hostName, String basePath) {
        // TODO: support generate for all roots with empty path
        if (!rootPathList) throw new ResourceNotFoundException("No resource path specified")
        String rootResourceName = rootPathList[0]
        ResourceNode resourceNode = getRootResourceNode(rootResourceName)

        StringBuilder fullBasePath = new StringBuilder(basePath)
        for (String rootPath in rootPathList) fullBasePath.append('/').append(rootPath)
        Map<String, Map> paths = [:]
        // NOTE: using LinkedHashMap though TreeMap would be nice as saw odd behavior where TreeMap.put() did nothing
        Map<String, Map> definitions = new LinkedHashMap<String, Map>()
        Map<String, Object> swaggerMap = [swagger:'2.0',
            info:[title:(resourceNode.displayName ?: "Service REST API (${fullBasePath})"),
                  version:(resourceNode.version ?: '1.0'), description:(resourceNode.description ?: '')],
            host:hostName, basePath:fullBasePath.toString(), schemes:schemes,
            securityDefinitions:[basicAuth:[type:'basic', description:'HTTP Basic Authentication'],
                api_key:[type:"apiKey", name:"api_key", in:"header", description:'HTTP Header api_key']],
            consumes:['application/json', 'multipart/form-data'], produces:['application/json'],
        ]

        // add tags for 2nd level resources
        if (rootPathList.size() >= 1) {
            List<Map> tags = []
            for (ResourceNode childResource in resourceNode.getResourceMap().values())
                tags.add([name:childResource.name, description:(childResource.description ?: childResource.name)])
            swaggerMap.put("tags", tags)
        }

        swaggerMap.put("paths", paths)
        swaggerMap.put("definitions", definitions)

        resourceNode.addToSwaggerMap(swaggerMap, rootPathList)

        int methodsCount = 0
        for (Map rsMap in paths.values()) methodsCount += rsMap.size()
        logger.info("Generated Swagger for ${rootPathList}; ${paths.size()} (${resourceNode.childPaths}) paths with ${methodsCount} (${resourceNode.childMethods}) methods, ${definitions.size()} definitions")

        return swaggerMap
    }

    static abstract class MethodHandler {
        ExecutionContextFactoryImpl ecfi
        String method
        PathNode pathNode
        String requireAuthentication
        MethodHandler(MNode methodNode, PathNode pathNode, ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi
            method = methodNode.attribute("type")
            this.pathNode = pathNode
            requireAuthentication = methodNode.attribute("require-authentication") ?: pathNode.requireAuthentication ?: "true"
        }
        abstract RestResult run(List<String> pathList, ExecutionContext ec)
        abstract void addToSwaggerMap(Map<String, Object> swaggerMap, Map<String, Map<String, Object>> resourceMap)
        abstract Map<String, Object> getRamlMap(Map<String, Object> typesMap)
        abstract void toString(int level, StringBuilder sb)
    }

    protected static final Map<String, String> objectTypeJsonMap = [
            Integer:"integer", Long:"integer", Short:"integer", Float:"number", Double:"number",
            BigDecimal:"number", BigInteger:"integer", Boolean:"boolean", List:"array", Set:"array", Collection:"array",
            Map:"object", EntityValue:"object", EntityList:"array" ]
    static String getJsonType(String javaType) {
        if (!javaType) return "string"
        if (javaType.contains(".")) javaType = javaType.substring(javaType.lastIndexOf(".") + 1)
        return objectTypeJsonMap.get(javaType) ?: "string"
    }
    protected static final Map<String, String> objectJsonFormatMap = [
            Integer:"int32", Long:"int64", Short:"int32", Float:"float", Double:"double",
            BigDecimal:"", BigInteger:"int64", Date:"date", Timestamp:"date-time",
            Boolean:"", List:"", Set:"", Collection:"", Map:"" ]
    static String getJsonFormat(String javaType) {
        if (!javaType) return ""
        if (javaType.contains(".")) javaType = javaType.substring(javaType.lastIndexOf(".") + 1)
        return objectJsonFormatMap.get(javaType) ?: ""
    }

    protected static final Map<String, String> objectTypeRamlMap = [
            Integer:"integer", Long:"integer", Short:"integer", Float:"number", Double:"number",
            BigDecimal:"number", BigInteger:"integer", Boolean:"boolean", List:"array", Set:"array", Collection:"array",
            Map:"object", EntityValue:"object", EntityList:"array" ]
    static String getRamlType(String javaType) {
        if (!javaType) return "string"
        if (javaType.contains(".")) javaType = javaType.substring(javaType.lastIndexOf(".") + 1)
        return objectTypeRamlMap.get(javaType) ?: "string"
    }

    static class MethodService extends MethodHandler {
        String serviceName
        MethodService(MNode methodNode, MNode serviceNode, PathNode pathNode, ExecutionContextFactoryImpl ecfi) {
            super(methodNode, pathNode, ecfi)
            serviceName = serviceNode.attribute("name")
        }
        RestResult run(List<String> pathList, ExecutionContext ec) {
            if ((requireAuthentication == null || requireAuthentication.length() == 0 || "true".equals(requireAuthentication)) &&
                    !ec.getUser().getUsername()) {
                throw new AuthenticationRequiredException("User must be logged in to call service ${serviceName}")
            }

            boolean loggedInAnonymous = false
            if ("anonymous-all".equals(requireAuthentication)) {
                ec.artifactExecution.setAnonymousAuthorizedAll()
                loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
            } else if ("anonymous-view".equals(requireAuthentication)) {
                ec.artifactExecution.setAnonymousAuthorizedView()
                loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
            }

            try {
                Map result = ec.getService().sync().name(serviceName).parameters(ec.context).call()
                ServiceDefinition.nestedRemoveNullsFromResultMap(result)
                return new RestResult(result, null)
            } finally {
                if (loggedInAnonymous) ((UserFacadeImpl) ec.getUser()).logoutAnonymousOnly()
            }
        }

        void addToSwaggerMap(Map<String, Object> swaggerMap, Map<String, Map<String, Object>> resourceMap) {
            ServiceDefinition sd = ecfi.serviceFacade.getServiceDefinition(serviceName)
            if (sd == null) throw new IllegalArgumentException("Service ${serviceName} not found")
            MNode serviceNode = sd.serviceNode
            Map definitionsMap = (Map) swaggerMap.definitions

            // add parameters, including path parameters
            List<Map> parameters = []
            Set<String> remainingInParmNames = new LinkedHashSet<String>(sd.getInParameterNames())
            for (String pathParm in pathNode.pathParameters) {
                MNode parmNode = sd.getInParameter(pathParm)
                if (parmNode == null) throw new IllegalArgumentException("No in parameter found for path parameter ${pathParm} in service ${sd.serviceName}")
                parameters.add([name:pathParm, in:'path', required:true, type:getJsonType((String) parmNode?.attribute('type')),
                                description:parmNode.first("description")?.text])
                remainingInParmNames.remove(pathParm)
            }
            if (remainingInParmNames) {
                if (method in ['post', 'put', 'patch']) {
                    parameters.add([name:'body', in:'body', required:true, schema:['$ref':"#/definitions/${sd.serviceName}.In".toString()]])
                    // add a definition for service in parameters
                    definitionsMap.put("${sd.serviceName}.In".toString(), RestSchemaUtil.getJsonSchemaMapIn(sd))
                } else {
                    for (String parmName in remainingInParmNames) {
                        MNode parmNode = sd.getInParameter(parmName)
                        String javaType = parmNode.attribute("type")
                        String jsonType = getJsonType(javaType)
                        // these are query parameters because method doesn't support body, so skip objects and arrays
                        //   (in many services they are not needed, pre-lookup sorts of objects; use post or something if needed)
                        if (jsonType == 'object' || jsonType == 'array') continue
                        Map<String, Object> propMap = [name:parmName, in:'query', required:false,
                                type:jsonType, format:getJsonFormat(javaType),
                                description:parmNode.first("description")?.text] as Map<String, Object>
                        parameters.add(propMap)
                        RestSchemaUtil.addParameterEnums(sd, parmNode, propMap)
                    }

                }
            }

            // add responses
            Map responses = ["401":[description:"Authentication required"], "403":[description:"Access Forbidden (no authz)"],
                             "429":[description:"Too Many Requests (tarpit)"], "500":[description:"General Error"]]
            if (sd.getOutParameterNames().size() > 0) {
                responses.put("200", [description:'Success', schema:['$ref':"#/definitions/${sd.serviceName}.Out".toString()]])
                definitionsMap.put("${sd.serviceName}.Out".toString(), RestSchemaUtil.getJsonSchemaMapOut(sd))
            }

            Map curMap = [:]
            if (swaggerMap.tags && pathNode.fullPathList.size() > 1) curMap.put("tags", [pathNode.fullPathList[1]])
            curMap.putAll([summary:(serviceNode.attribute("displayName") ?: "${sd.verb} ${sd.noun}".toString()),
                           description:serviceNode.first("description")?.text,
                           security:[[basicAuth:[]], [api_key:[]]], parameters:parameters, responses:responses])
            resourceMap.put(method, curMap)
        }

        Map<String, Object> getRamlMap(Map<String, Object> typesMap) {
            ServiceDefinition sd = ecfi.serviceFacade.getServiceDefinition(serviceName)
            if (sd == null) throw new IllegalArgumentException("Service ${serviceName} not found")
            MNode serviceNode = sd.serviceNode

            Map<String, Object> ramlMap =  [is:['service'],
                    displayName:(serviceNode.attribute("displayName") ?: "${sd.verb} ${sd.noun}".toString())] as Map<String, Object>

            // add parameters, including path parameters
            Set<String> remainingInParmNames = new LinkedHashSet<String>(sd.getInParameterNames())
            for (String pathParm in pathNode.pathParameters) remainingInParmNames.remove(pathParm)
            if (remainingInParmNames) {
                ramlMap.put("body", ['application/json': [type:"${sd.serviceName}.In".toString()]])
                // add a definition for service in parameters
                typesMap.put("${sd.serviceName}.In".toString(), RestSchemaUtil.getRamlMapIn(sd))
            }

            if (sd.getOutParameterNames().size() > 0) {
                ramlMap.put("responses", [200:[body:['application/json': [type:"${sd.serviceName}.Out".toString()]]]])
                typesMap.put("${sd.serviceName}.Out".toString(), RestSchemaUtil.getRamlMapOut(sd))
            }

            return ramlMap
        }

        void toString(int level, StringBuilder sb) {
            for (int i=0; i < (level * 4); i++) sb.append(" ")
            sb.append(method).append(": service - ").append(serviceName).append("\n")
        }
    }

    static class MethodEntity extends MethodHandler {
        String entityName, masterName, operation
        MethodEntity(MNode methodNode, MNode entityNode, PathNode pathNode, ExecutionContextFactoryImpl ecfi) {
            super(methodNode, pathNode, ecfi)
            entityName = entityNode.attribute("name")
            masterName = entityNode.attribute("masterName")
            operation = entityNode.attribute("operation")
        }
        RestResult run(List<String> pathList, ExecutionContext ec) {
            // for entity ops authc always required
            if ((requireAuthentication == null || requireAuthentication.length() == 0 || "true".equals(requireAuthentication)) &&
                    !ec.getUser().getUsername()) {
                throw new AuthenticationRequiredException("User must be logged in for operaton ${operation} on entity ${entityName}")
            }

            boolean loggedInAnonymous = false
            if ("anonymous-all".equals(requireAuthentication)) {
                ec.artifactExecution.setAnonymousAuthorizedAll()
                loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
            } else if ("anonymous-view".equals(requireAuthentication)) {
                ec.artifactExecution.setAnonymousAuthorizedView()
                loggedInAnonymous = ec.getUser().loginAnonymousIfNoUser()
            }

            try {
                if (operation == 'one') {
                    EntityFind ef = ec.entity.find(entityName).searchFormMap(ec.context, null, null, null, false)
                    if (masterName) {
                        return new RestResult(ef.oneMaster(masterName), null)
                    } else {
                        EntityValue val = ef.one()
                        return new RestResult(val != null ? CollectionUtilities.removeNullsFromMap(val.getMap()) : null, null)
                    }
                } else if (operation == 'list') {
                    EntityFind ef = ec.entity.find(entityName).searchFormMap(ec.context, null, null, null, false)
                    // we don't want to go overboard with these requests, never do an unlimited find, if no limit use 100
                    if (!ef.getLimit() && !"true".equals(ec.context.get("pageNoLimit"))) ef.limit(100)

                    int count = ef.count() as int
                    int pageIndex = ef.getPageIndex()
                    int pageSize = ef.getPageSize()
                    int pageMaxIndex = ((count - 1) as BigDecimal).divide(pageSize as BigDecimal, 0, RoundingMode.DOWN).intValue()
                    int pageRangeLow = pageIndex * pageSize + 1
                    int pageRangeHigh = (pageIndex * pageSize) + pageSize
                    if (pageRangeHigh > count) pageRangeHigh = count
                    Map<String, Object> headers = ['X-Total-Count':count, 'X-Page-Index':pageIndex, 'X-Page-Size':pageSize,
                        'X-Page-Max-Index':pageMaxIndex, 'X-Page-Range-Low':pageRangeLow, 'X-Page-Range-High':pageRangeHigh] as Map<String, Object>

                    if (masterName) {
                        return new RestResult(ef.listMaster(masterName), headers)
                    } else {
                        return new RestResult(ef.list().getValueMapList(), headers)
                    }
                } else if (operation == 'count') {
                    EntityFind ef = ec.entity.find(entityName).searchFormMap(ec.context, null, null, null, false)
                    long count = ef.count()
                    Map<String, Object> headers = ['X-Total-Count':count] as Map<String, Object>
                    return new RestResult([count:count], headers)
                } else if (operation in ['create', 'update', 'store', 'delete']) {
                    Map result = ec.getService().sync().name(operation, entityName).parameters(ec.context).call()
                    return new RestResult(result, null)
                } else {
                    throw new IllegalArgumentException("Entity operation ${operation} not supported, must be one of: one, list, count, create, update, store, delete")
                }
            } finally {
                if (loggedInAnonymous) ((UserFacadeImpl) ec.getUser()).logoutAnonymousOnly()
            }
        }

        void addToSwaggerMap(Map<String, Object> swaggerMap, Map<String, Map<String, Object>> resourceMap) {
            EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(entityName)
            if (ed == null) throw new IllegalArgumentException("Entity ${entityName} not found")
            // Node entityNode = ed.getEntityNode()

            Map definitionsMap = ((Map) swaggerMap.definitions)
            String refDefName = ed.getShortOrFullEntityName()
            if (masterName) refDefName = refDefName + "." + masterName
            String refDefNamePk = refDefName + ".PK"

            // add path parameters
            List<Map> parameters = []
            ArrayList<String> remainingPkFields = new ArrayList<String>(ed.getPkFieldNames())
            for (String pathParm in pathNode.pathParameters) {
                FieldInfo fi = ed.getFieldInfo(pathParm)
                if (fi == null) throw new IllegalArgumentException("No field found for path parameter ${pathParm} in entity ${ed.getFullEntityName()}")
                parameters.add([name:pathParm, in:'path', required:true, type:(RestSchemaUtil.fieldTypeJsonMap.get(fi.type) ?: "string"),
                                description:fi.fieldNode.first("description")?.text])
                remainingPkFields.remove(pathParm)
            }

            // add responses
            Map responses = ["401":[description:"Authentication required"], "403":[description:"Access Forbidden (no authz)"],
                             "404":[description:"Value Not Found"], "429":[description:"Too Many Requests (tarpit)"],
                             "500":[description:"General Error"]]

            boolean addEntityDef = true
            boolean addPkDef = false
            if (operation  == 'one') {
                if (remainingPkFields) {
                    for (String fieldName in remainingPkFields) {
                        FieldInfo fi = ed.getFieldInfo(fieldName)
                        Map<String, Object> fieldMap = [name:fieldName, in:'query', required:false,
                                type:(RestSchemaUtil.fieldTypeJsonMap.get(fi.type) ?: "string"),
                                format:(RestSchemaUtil.fieldTypeJsonFormatMap.get(fi.type) ?: ""),
                                description:fi.fieldNode.first("description")?.text] as Map<String, Object>
                        parameters.add(fieldMap)
                        List enumList = RestSchemaUtil.getFieldEnums(ed, fi)
                        if (enumList) fieldMap.put('enum', enumList)
                    }
                }
                responses.put("200", [description:'Success', schema:['$ref':"#/definitions/${refDefName}".toString()]])
            } else if (operation == 'list') {
                parameters.addAll(RestSchemaUtil.swaggerPaginationParameters)
                for (String fieldName in ed.getAllFieldNames()) {
                    if (fieldName in pathNode.pathParameters) continue
                    FieldInfo fi = ed.getFieldInfo(fieldName)
                    parameters.add([name:fieldName, in:'query', required:false,
                                        type:(RestSchemaUtil.fieldTypeJsonMap.get(fi.type) ?: "string"),
                                        format:(RestSchemaUtil.fieldTypeJsonFormatMap.get(fi.type) ?: ""),
                                        description:fi.fieldNode.first("description")?.text])
                }
                // parameters.add([name:'body', in:'body', required:false, schema:[allOf:[['$ref':'#/definitions/paginationParameters'], ['$ref':"#/definitions/${refDefName}"]]]])
                responses.put("200", [description:'Success', schema:[type:"array", items:['$ref':"#/definitions/${refDefName}".toString()]]])
            } else if (operation == 'count') {
                parameters.add([name:'body', in:'body', required:false, schema:['$ref':"#/definitions/${refDefName}".toString()]])
                responses.put("200", [description:'Success', schema:RestSchemaUtil.jsonCountParameters])
            } else if (operation in ['create', 'update', 'store']) {
                parameters.add([name:'body', in:'body', required:false, schema:['$ref':"#/definitions/${refDefName}".toString()]])
                responses.put("200", [description:'Success', schema:['$ref':"#/definitions/${refDefNamePk}".toString()]])
                addPkDef = true
            } else if (operation == 'delete') {
                addEntityDef = false
                if (remainingPkFields) {
                    parameters.add([name:'body', in:'body', required:false, schema:['$ref':"#/definitions/${refDefNamePk}".toString()]])
                    addPkDef = true
                }
            }

            Map curMap = [:]
            String summary = "${operation} ${ed.entityInfo.internalEntityName}"
            if (masterName) summary = summary + " (master: " + masterName + ")"
            if (swaggerMap.tags && pathNode.fullPathList.size() > 1) curMap.put("tags", [pathNode.fullPathList[1]])
            curMap.putAll([summary:summary, description:ed.getEntityNode().first("description")?.text,
                           security:[[basicAuth:[]], [api_key:[]]], parameters:parameters, responses:responses])
            resourceMap.put(method, curMap)

            // add a definition for entity fields
            if (addEntityDef) definitionsMap.put(refDefName, RestSchemaUtil.getJsonSchema(ed, false, false, definitionsMap, null, null, null, false, masterName, null))
            if (addPkDef) definitionsMap.put(refDefNamePk, RestSchemaUtil.getJsonSchema(ed, true, false, null, null, null, null, false, masterName, null))
        }

        Map<String, Object> getRamlMap(Map<String, Object> typesMap) {
            Map<String, Object> ramlMap = null

            EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(entityName)
            if (ed == null) throw new IllegalArgumentException("Entity ${entityName} not found")

            String refDefName = ed.getShortOrFullEntityName()
            if (masterName) refDefName = refDefName + "." + masterName

            String prettyName = ed.getPrettyName(null, null)

            // add path parameters
            ArrayList<String> remainingPkFields = new ArrayList<String>(ed.getPkFieldNames())
            for (String pathParm in pathNode.pathParameters) {
                remainingPkFields.remove(pathParm)
            }
            Map pkQpMap = [:]
            for (int i = 0; i < remainingPkFields.size(); i++) {
                FieldInfo fi = ed.getFieldInfo(remainingPkFields.get(i))
                pkQpMap.put(fi.name, RestSchemaUtil.getRamlFieldMap(ed, fi))
            }
            Map allQpMap = [:]
            ArrayList<String> allFields = ed.getAllFieldNames()
            for (int i = 0; i < allFields.size(); i++) {
                FieldInfo fi = ed.getFieldInfo(allFields.get(i))
                allQpMap.put(fi.name, RestSchemaUtil.getRamlFieldMap(ed, fi))
            }

            boolean addEntityDef = true
            if (operation  == 'one') {
                ramlMap = [is:['entity'], displayName:"Get single ${prettyName}".toString()] as Map<String, Object>
                if (pkQpMap) ramlMap.put('queryParameters', pkQpMap)
                ramlMap.put("responses", [200:[body:['application/json': [type:refDefName]]]])
            } else if (operation == 'list') {
                // TODO: add pagination headers
                ramlMap = [is:['paged', 'entity'], displayName:"Get list of ${prettyName}".toString(), body:['application/json': [type:refDefName]]]
                ramlMap.put("responses", [200:[body:['application/json': [type:"array", items:refDefName]]]])
            } else if (operation == 'count') {
                ramlMap = [is:['entity'], displayName:"Count ${prettyName}".toString(), body:['application/json': [type:refDefName]]] as Map<String, Object>
                ramlMap.put("responses", [200:[body:['application/json': RestSchemaUtil.jsonCountParameters]]])
            } else if (operation  == 'create') {
                ramlMap = [is:['entity'], displayName:"Create ${prettyName}".toString(), body:['application/json': [type:refDefName]]] as Map<String, Object>
                if (pkQpMap) ramlMap.put("responses", [200:[body:['application/json': [type:'object', properties:pkQpMap]]]])
            } else if (operation == 'update') {
                ramlMap = [is:['entity'], displayName:"Update ${prettyName}".toString(), body:['application/json': [type:refDefName]]] as Map<String, Object>
            } else if (operation == 'store') {
                ramlMap = [is:['entity'], displayName:"Create or Update ${prettyName}".toString(), body:['application/json': [type:refDefName]]] as Map<String, Object>
                if (pkQpMap) ramlMap.put("responses", [200:[body:['application/json': [type:'object', properties:pkQpMap]]]])
            } else if (operation == 'delete') {
                ramlMap = [is:['entity'], displayName:"Delete ${prettyName}".toString()] as Map<String, Object>
                if (pkQpMap) ramlMap.put('queryParameters', pkQpMap)
                addEntityDef = false
            }
            if (addEntityDef) RestSchemaUtil.getRamlTypeMap(ed, false, typesMap, masterName, null)

            return ramlMap
        }

        void toString(int level, StringBuilder sb) {
            for (int i=0; i < (level * 4); i++) sb.append(" ")
            sb.append(method).append(": entity - ").append(operation).append(" - ").append(entityName)
            if (masterName) sb.append(" (master: ").append(masterName).append(")")
            sb.append("\n")
        }
    }

    static abstract class PathNode {
        ExecutionContextFactoryImpl ecfi

        String displayName, description, version

        Map<String, MethodHandler> methodMap = [:]
        IdNode idNode = null
        Map<String, ResourceNode> resourceMap = [:]

        String name
        String requireAuthentication
        PathNode parent
        List<String> fullPathList = []
        Set<String> pathParameters = new LinkedHashSet<String>()

        int childPaths = 0
        int childMethods = 0

        PathNode(MNode node, PathNode parent, ExecutionContextFactoryImpl ecfi, boolean isId) {
            this.ecfi = ecfi
            this.parent = parent

            displayName = node.attribute("displayName")
            description = node.attribute("description")
            version = node.attribute("version")
            if (version && version.contains('${')) version = SystemBinding.expand(version)

            if (parent != null) this.pathParameters.addAll(parent.pathParameters)
            name = node.attribute("name")
            if (parent != null) fullPathList.addAll(parent.fullPathList)
            fullPathList.add(isId ? "{${name}}".toString() : name)
            if (isId) pathParameters.add(name)
            requireAuthentication = node.attribute("require-authentication") ?: parent?.requireAuthentication ?: "true"

            for (MNode childNode in node.children) {
                if (childNode.name == "method") {
                    String method = childNode.attribute("type")

                    MNode methodNode = childNode.children[0]
                    if (methodNode.name == "service") {
                        methodMap.put(method, new MethodService(childNode, methodNode, this, ecfi))
                    } else if (methodNode.name == "entity") {
                        methodMap.put(method, new MethodEntity(childNode, methodNode, this, ecfi))
                    }
                } else if (childNode.name == "resource") {
                    ResourceNode resourceNode = new ResourceNode(childNode, this, ecfi)
                    resourceMap.put(resourceNode.name, resourceNode)
                } else if (childNode.name == "id") {
                    idNode = new IdNode(childNode, this, ecfi)
                }
            }

            childMethods += methodMap.size()
            for (ResourceNode rn in resourceMap.values()) {
                childPaths++
                childPaths += rn.childPaths
                childMethods += rn.childMethods
            }
            if (idNode != null) {
                childPaths++
                childPaths += idNode.childPaths
                childMethods += idNode.childMethods
            }
        }

        RestResult runByMethod(List<String> pathList, ExecutionContext ec) {
            HttpServletRequest request = ec.web.getRequest()
            String method = request.getMethod().toLowerCase()
            if ("post".equals(method)) {
                String ovdMethod = request.getHeader("X-HTTP-Method-Override")
                if (ovdMethod != null && !ovdMethod.isEmpty()) method = ovdMethod.toLowerCase()
            }
            MethodHandler mh = methodMap.get(method)
            if (mh == null) throw new MethodNotSupportedException("Method ${method} not supported at ${pathList}")
            return mh.run(pathList, ec)
        }

        RestResult visitChildOrRun(List<String> pathList, int pathIndex, ExecutionContextImpl ec) {
            // more in path? visit the next, otherwise run by request method
            int nextPathIndex = pathIndex + 1
            boolean moreInPath = pathList.size() > nextPathIndex

            // push onto artifact stack, check authz
            String curPath = getFullPathName([])
            ArtifactExecutionInfoImpl aei = new ArtifactExecutionInfoImpl(curPath, ArtifactExecutionInfo.AT_REST_PATH, getActionFromMethod(ec), null)
            // for now don't track/count artifact hits for REST path
            aei.setTrackArtifactHit(false)
            // NOTE: consider setting parameters on aei, but don't like setting entire context, currently used for entity/service calls
            ec.artifactExecutionFacade.pushInternal(aei, !moreInPath ?
                    (requireAuthentication == null || requireAuthentication.length() == 0 || "true".equals(requireAuthentication)) : false, true)

            boolean loggedInAnonymous = false
            if ("anonymous-all".equals(requireAuthentication)) {
                ec.artifactExecutionFacade.setAnonymousAuthorizedAll()
                loggedInAnonymous = ec.userFacade.loginAnonymousIfNoUser()
            } else if ("anonymous-view".equals(requireAuthentication)) {
                ec.artifactExecutionFacade.setAnonymousAuthorizedView()
                loggedInAnonymous = ec.userFacade.loginAnonymousIfNoUser()
            }

            try {
                if (moreInPath) {
                    String nextPath = pathList[nextPathIndex]
                    // first try resources
                    ResourceNode rn = resourceMap.get(nextPath)
                    if (rn != null) {
                        return rn.visit(pathList, nextPathIndex, ec)
                    } else if (idNode != null) {
                        // no resource? if there is an idNode treat as ID
                        return idNode.visit(pathList, nextPathIndex, ec)
                    } else {
                        // not a resource and no idNode, is a bad path
                        throw new ResourceNotFoundException("Resource ${nextPath} not valid, index ${pathIndex} in path ${pathList}; resources available are ${resourceMap.keySet()}")
                    }
                } else {
                    return runByMethod(pathList, ec)
                }
            } finally {
                ec.artifactExecutionFacade.pop(aei)
                if (loggedInAnonymous) ec.userFacade.logoutAnonymousOnly()
            }
        }

        void addToSwaggerMap(Map<String, Object> swaggerMap, List<String> rootPathList) {
            // see if we are in the root path specified
            int curIndex = fullPathList.size() - 1
            if (curIndex < rootPathList.size() && fullPathList[curIndex] != rootPathList[curIndex]) return

            // if we have method handlers add this, otherwise just do children
            if (rootPathList.size() - 1 <= curIndex && methodMap) {
                String curPath = getFullPathName(rootPathList)

                Map<String, Map<String, Object>> rsMap = [:]
                for (MethodHandler mh in methodMap.values()) mh.addToSwaggerMap(swaggerMap, rsMap)

                ((Map) swaggerMap.paths).put(curPath ?: '/', rsMap)
            }
            // add the id node if there is one
            if (idNode != null) idNode.addToSwaggerMap(swaggerMap, rootPathList)
            // add any resource nodes there might be
            for (ResourceNode rn in resourceMap.values()) rn.addToSwaggerMap(swaggerMap, rootPathList)
        }

        String getFullPathName(List<String> rootPathList) {
            StringBuilder curPath = new StringBuilder()
            for (int i = rootPathList.size(); i < fullPathList.size(); i++) {
                String pathItem = fullPathList.get(i)
                curPath.append('/').append(pathItem)
            }
            return curPath.toString()
        }
        static final Map<String, ArtifactExecutionInfo.AuthzAction> actionByMethodMap = [get:ArtifactExecutionInfo.AUTHZA_VIEW,
                patch:ArtifactExecutionInfo.AUTHZA_UPDATE, put:ArtifactExecutionInfo.AUTHZA_UPDATE,
                post:ArtifactExecutionInfo.AUTHZA_CREATE, delete:ArtifactExecutionInfo.AUTHZA_DELETE,
                options:ArtifactExecutionInfo.AUTHZA_VIEW, head:ArtifactExecutionInfo.AUTHZA_VIEW]
        static ArtifactExecutionInfo.AuthzAction getActionFromMethod(ExecutionContext ec) {
            String method = ec.web.getRequest().getMethod().toLowerCase()
            return actionByMethodMap.get(method)
        }

        Map getRamlChildrenMap(Map<String, Object> typesMap) {
            Map<String, Object> childrenMap = [:]

            // add displayName, description
            if (displayName) childrenMap.put('displayName', displayName)
            if (description) childrenMap.put('description', description)

            // if we have method handlers add this, otherwise just do children
            if (methodMap) for (MethodHandler mh in methodMap.values()) childrenMap.put(mh.method, mh.getRamlMap(typesMap))
            // add the id node if there is one
            if (idNode != null) childrenMap.put('/{' + idNode.name + '}', idNode.getRamlChildrenMap(typesMap))
            // add any resource nodes there might be
            for (ResourceNode rn in resourceMap.values()) childrenMap.put('/' + rn.name, rn.getRamlChildrenMap(typesMap))

            return childrenMap
        }

        void toStringChildren(int level, StringBuilder sb) {
            for (MethodHandler mh in methodMap.values()) mh.toString(level + 1, sb)
            for (ResourceNode rn in resourceMap.values()) rn.toString(level + 1, sb)
            if (idNode != null) idNode.toString(level + 1, sb)
        }

        abstract Object visit(List<String> pathList, int pathIndex, ExecutionContextImpl ec)
    }
    static class ResourceNode extends PathNode {
        ResourceNode(MNode node, PathNode parent, ExecutionContextFactoryImpl ecfi) {
            super(node, parent, ecfi, false)
        }
        RestResult visit(List<String> pathList, int pathIndex, ExecutionContextImpl ec) {
            // logger.info("Visit resource ${name}")
            // visit child or run here
            return visitChildOrRun(pathList, pathIndex, ec)
        }
        String toString() {
            StringBuilder sb = new StringBuilder()
            toString(0, sb)
            return sb.toString()
        }
        void toString(int level, StringBuilder sb) {
            for (int i=0; i < (level * 4); i++) sb.append(" ")
            sb.append("/").append(name)
            if (displayName) sb.append(" - ").append(displayName)
            sb.append("\n")
            toStringChildren(level, sb)
        }
    }
    static class IdNode extends PathNode {
        IdNode(MNode node, PathNode parent, ExecutionContextFactoryImpl ecfi) {
            super(node, parent, ecfi, true)
        }
        RestResult visit(List<String> pathList, int pathIndex, ExecutionContextImpl ec) {
            // logger.info("Visit id ${name}")
            // set ID value in context
            ec.context.put(name, pathList[pathIndex])
            // visit child or run here
            return visitChildOrRun(pathList, pathIndex, ec)
        }
        void toString(int level, StringBuilder sb) {
            for (int i=0; i < (level * 4); i++) sb.append(" ")
            sb.append("/{").append(name).append("}\n")
            toStringChildren(level, sb)
        }
    }

    static class RestResult {
        Object responseObj
        Map<String, Object> headers = [:]
        RestResult(Object responseObj, Map<String, Object> headers) {
            this.responseObj = responseObj
            if (headers) this.headers.putAll(headers)
        }
        void setHeaders(HttpServletResponse response) {
            for (Map.Entry<String, Object> entry in headers) {
                Object value = entry.value
                if (value == null) continue
                if (value instanceof Integer) {
                    response.setIntHeader(entry.key, (int) value)
                } else if (value instanceof Date) {
                    response.setDateHeader(entry.key, ((Date) value).getTime())
                } else {
                    response.setHeader(entry.key, value.toString())
                }
            }
        }
    }

    static class ResourceNotFoundException extends BaseException {
        ResourceNotFoundException(String str) { super(str) }
        // ResourceNotFoundException(String str, Throwable nested) { super(str, nested) }
    }
    static class MethodNotSupportedException extends BaseException {
        MethodNotSupportedException(String str) { super(str) }
        // MethodNotSupportedException(String str, Throwable nested) { super(str, nested) }
    }
}
