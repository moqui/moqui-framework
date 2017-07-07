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
package org.moqui.impl.util

import groovy.json.JsonBuilder
import groovy.transform.CompileStatic
import org.moqui.entity.EntityList
import org.moqui.entity.EntityNotFoundException
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.WebFacadeImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityDefinition.MasterDefinition
import org.moqui.impl.entity.EntityDefinition.MasterDetail
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.entity.EntityJavaUtil.RelationshipInfo
import org.moqui.impl.entity.FieldInfo
import org.moqui.impl.service.RestApi
import org.moqui.impl.service.ServiceDefinition
import org.moqui.service.ServiceException
import org.moqui.util.MNode

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

import javax.servlet.http.HttpServletResponse

@CompileStatic
class RestSchemaUtil {
    protected final static Logger logger = LoggerFactory.getLogger(RestSchemaUtil.class)

    static final Map<String, String> fieldTypeJsonMap = [
            "id":"string", "id-long":"string", "text-indicator":"string", "text-short":"string", "text-medium":"string",
            "text-long":"string", "text-very-long":"string", "date-time":"string", "time":"string",
            "date":"string", "number-integer":"number", "number-float":"number",
            "number-decimal":"number", "currency-amount":"number", "currency-precise":"number",
            "binary-very-long":"string" ] // NOTE: binary-very-long may need hyper-schema stuff
    static final Map<String, String> fieldTypeJsonFormatMap = [
            "date-time":"date-time", "date":"date", "number-integer":"int64", "number-float":"double",
            "number-decimal":"", "currency-amount":"", "currency-precise":"", "binary-very-long":"" ]

    static final Map jsonPaginationProperties =
            [pageIndex:[type:'number', format:'int32', description:'Page number to return, starting with zero'],
             pageSize:[type:'number', format:'int32', description:'Number of records per page (default 100)'],
             orderByField:[type:'string', description:'Field name to order by (or comma separated names)'],
             pageNoLimit:[type:'string', description:'If true don\'t limit page size (no pagination)'],
             dependentLevels:[type:'number', format:'int32', description:'Levels of dependent child records to include']
            ]
    static final Map jsonPaginationParameters = [type:'object', properties: jsonPaginationProperties]
    static final Map jsonCountParameters = [type:'object', properties: [count:[type:'number', format:'int64', description:'Count of results']]]
    static final List<Map> swaggerPaginationParameters =
            [[name:'pageIndex', in:'query', required:false, type:'number', format:'int32', description:'Page number to return, starting with zero'],
             [name:'pageSize', in:'query', required:false, type:'number', format:'int32', description:'Number of records per page (default 100)'],
             [name:'orderByField', in:'query', required:false, type:'string', description:'Field name to order by (or comma separated names)'],
             [name:'pageNoLimit', in:'query', required:false, type:'string', description:'If true don\'t limit page size (no pagination)'],
             [name:'dependentLevels', in:'query', required:false, type:'number', format:'int32', description:'Levels of dependent child records to include']
            ] as List<Map>


    static final Map ramlPaginationParameters = [
             pageIndex:[type:'number', description:'Page number to return, starting with zero'],
             pageSize:[type:'number', default:100, description:'Number of records per page (default 100)'],
             orderByField:[type:'string', description:'Field name to order by (or comma separated names)'],
             pageNoLimit:[type:'string', description:'If true don\'t limit page size (no pagination)'],
             dependentLevels:[type:'number', description:'Levels of dependent child records to include']
            ]
    static final Map<String, String> fieldTypeRamlMap = [
            "id":"string", "id-long":"string", "text-indicator":"string", "text-short":"string", "text-medium":"string",
            "text-long":"string", "text-very-long":"string", "date-time":"date", "time":"string",
            "date":"string", "number-integer":"integer", "number-float":"number",
            "number-decimal":"number", "currency-amount":"number", "currency-precise":"number",
            "binary-very-long":"string" ] // NOTE: binary-very-long may need hyper-schema stuff

    // ===============================================
    // ========== Entity Definition Methods ==========
    // ===============================================

    static List<String> getFieldEnums(EntityDefinition ed, FieldInfo fi) {
        // populate enum values for Enumeration and StatusItem
        // find first relationship that has this field as the only key map and is not a many relationship
        RelationshipInfo oneRelInfo = null
        List<RelationshipInfo> allRelInfoList = ed.getRelationshipsInfo(false)
        for (RelationshipInfo relInfo in allRelInfoList) {
            Map km = relInfo.keyMap
            if (km.size() == 1 && km.containsKey(fi.name) && relInfo.type == "one" && relInfo.relNode.attribute("is-auto-reverse") != "true") {
                oneRelInfo = relInfo
                break;
            }
        }
        if (oneRelInfo != null && oneRelInfo.title != null) {
            if (oneRelInfo.relatedEd.getFullEntityName() == 'moqui.basic.Enumeration') {
                EntityList enumList = ed.efi.find("moqui.basic.Enumeration").condition("enumTypeId", oneRelInfo.title)
                        .orderBy("sequenceNum,enumId").disableAuthz().list()
                if (enumList) {
                    List<String> enumIdList = []
                    for (EntityValue ev in enumList) enumIdList.add((String) ev.enumId)
                    return enumIdList
                }
            } else if (oneRelInfo.relatedEd.getFullEntityName() == 'moqui.basic.StatusItem') {
                EntityList statusList = ed.efi.find("moqui.basic.StatusItem").condition("statusTypeId", oneRelInfo.title)
                        .orderBy("sequenceNum,statusId").disableAuthz().list()
                if (statusList) {
                    List<String> statusIdList = []
                    for (EntityValue ev in statusList) statusIdList.add((String) ev.statusId)
                    return statusIdList
                }
            }
        }
        return null
    }

    static Map getJsonSchema(EntityDefinition ed, boolean pkOnly, boolean standalone, Map<String, Object> definitionsMap, String schemaUri, String linkPrefix,
                      String schemaLinkPrefix, boolean nestRelationships, String masterName, MasterDetail masterDetail) {
        String name = ed.getShortOrFullEntityName()
        String prettyName = ed.getPrettyName(null, null)
        String refName = name
        if (masterName) {
            refName = "${name}.${masterName}".toString()
            prettyName = "${prettyName} (Master: ${masterName})".toString()
        }
        if (pkOnly) {
            name = name + ".PK"
            refName = refName + ".PK"
        }

        Map<String, Object> properties = [:]
        properties.put('_entity', [type:'string', default:name])
        // NOTE: Swagger validation doesn't like the id field, was: id:refName
        Map<String, Object> schema = [title:prettyName, type:'object', properties:properties] as Map<String, Object>

        // add all fields
        ArrayList<String> allFields = pkOnly ? ed.getPkFieldNames() : ed.getAllFieldNames()
        for (int i = 0; i < allFields.size(); i++) {
            FieldInfo fi = ed.getFieldInfo(allFields.get(i))
            Map<String, Object> propMap = [:]
            propMap.put('type', fieldTypeJsonMap.get(fi.type))
            String format = fieldTypeJsonFormatMap.get(fi.type)
            if (format) propMap.put('format', format)
            properties.put(fi.name, propMap)

            List enumList = getFieldEnums(ed, fi)
            if (enumList) propMap.put('enum', enumList)
        }


        // put current schema in Map before nesting for relationships, avoid infinite recursion with entity rel loops
        if (standalone && definitionsMap == null) {
            definitionsMap = [:]
            definitionsMap.put('paginationParameters', jsonPaginationParameters)
        }
        if (definitionsMap != null && !definitionsMap.containsKey(refName)) definitionsMap.put(refName, schema)

        if (!pkOnly && (masterName || masterDetail != null)) {
            // add only relationships from master definition or detail
            List<MasterDetail> detailList
            if (masterName) {
                MasterDefinition masterDef = ed.getMasterDefinition(masterName)
                if (masterDef == null) throw new IllegalArgumentException("Master name ${masterName} not valid for entity ${ed.getFullEntityName()}")
                detailList = masterDef.detailList
            } else {
                detailList = masterDetail.getDetailList()
            }
            for (MasterDetail childMasterDetail in detailList) {
                RelationshipInfo relInfo = childMasterDetail.relInfo
                String relationshipName = relInfo.relationshipName
                String entryName = relInfo.shortAlias ?: relationshipName
                String relatedRefName = relInfo.relatedEd.getShortOrFullEntityName()
                if (pkOnly) relatedRefName = relatedRefName + ".PK"

                // recurse, let it put itself in the definitionsMap
                // linkPrefix and schemaLinkPrefix are null so that no links are added for master dependents
                if (definitionsMap != null && !definitionsMap.containsKey(relatedRefName))
                    getJsonSchema(relInfo.relatedEd, pkOnly, false, definitionsMap, schemaUri, null, null, false, null, childMasterDetail)

                if (relInfo.type == "many") {
                    properties.put(entryName, [type:'array', items:['$ref':('#/definitions/' + relatedRefName)]])
                } else {
                    properties.put(entryName, ['$ref':('#/definitions/' + relatedRefName)])
                }
            }
        } else if (!pkOnly && nestRelationships) {
            // add all relationships, nest
            List<RelationshipInfo> relInfoList = ed.getRelationshipsInfo(true)
            for (RelationshipInfo relInfo in relInfoList) {
                String relationshipName = relInfo.relationshipName
                String entryName = relInfo.shortAlias ?: relationshipName
                String relatedRefName = relInfo.relatedEd.getShortOrFullEntityName()
                if (pkOnly) relatedRefName = relatedRefName + ".PK"

                // recurse, let it put itself in the definitionsMap
                if (definitionsMap != null && !definitionsMap.containsKey(relatedRefName))
                    getJsonSchema(relInfo.relatedEd, pkOnly, false, definitionsMap, schemaUri, linkPrefix, schemaLinkPrefix, nestRelationships, null, null)

                if (relInfo.type == "many") {
                    properties.put(entryName, [type:'array', items:['$ref':('#/definitions/' + relatedRefName)]])
                } else {
                    properties.put(entryName, ['$ref':('#/definitions/' + relatedRefName)])
                }
            }
        }

        // add links (for Entity REST API)
        if (linkPrefix || schemaLinkPrefix) {
            List<String> pkNameList = ed.getPkFieldNames()
            StringBuilder idSb = new StringBuilder()
            for (String pkName in pkNameList) idSb.append('/{').append(pkName).append('}')
            String idString = idSb.toString()

            List linkList
            if (linkPrefix) {
                linkList = [
                    [rel:'self', method:'GET', href:"${linkPrefix}/${refName}${idString}", title:"Get single ${prettyName}",
                        targetSchema:['$ref':"#/definitions/${name}"]],
                    [rel:'instances', method:'GET', href:"${linkPrefix}/${refName}", title:"Get list of ${prettyName}",
                        schema:[allOf:[['$ref':'#/definitions/paginationParameters'], ['$ref':"#/definitions/${name}"]]],
                        targetSchema:[type:'array', items:['$ref':"#/definitions/${name}"]]],
                    [rel:'create', method:'POST', href:"${linkPrefix}/${refName}", title:"Create ${prettyName}",
                        schema:['$ref':"#/definitions/${name}"]],
                    [rel:'update', method:'PATCH', href:"${linkPrefix}/${refName}${idString}", title:"Update ${prettyName}",
                        schema:['$ref':"#/definitions/${name}"]],
                    [rel:'store', method:'PUT', href:"${linkPrefix}/${refName}${idString}", title:"Create or Update ${prettyName}",
                        schema:['$ref':"#/definitions/${name}"]],
                    [rel:'destroy', method:'DELETE', href:"${linkPrefix}/${refName}${idString}", title:"Delete ${prettyName}",
                        schema:['$ref':"#/definitions/${name}"]]
                ]
            } else {
                linkList = []
            }
            if (schemaLinkPrefix) linkList.add([rel:'describedBy', method:'GET', href:"${schemaLinkPrefix}/${refName}", title:"Get schema for ${prettyName}"])

            schema.put('links', linkList)
        }

        if (standalone) {
            return ['$schema':'http://json-schema.org/draft-04/hyper-schema#', id:"${schemaUri}/${refName}",
                    '$ref':"#/definitions/${name}", definitions:definitionsMap]
        } else {
            return schema
        }
    }

    static Map<String, Object> getRamlFieldMap(EntityDefinition ed, FieldInfo fi) {
        Map<String, Object> propMap = [:]
        String description = fi.fieldNode.first("description")?.text
        if (description) propMap.put("description", description)
        propMap.put('type', fieldTypeRamlMap.get(fi.type))

        List enumList = getFieldEnums(ed, fi)
        if (enumList) propMap.put('enum', enumList)
        return propMap
    }

    static Map<String, Object> getRamlTypeMap(EntityDefinition ed, boolean pkOnly, Map<String, Object> typesMap,
                                              String masterName, MasterDetail masterDetail) {
        String name = ed.getShortOrFullEntityName()
        String prettyName = ed.getPrettyName(null, null)
        String refName = name
        if (masterName) {
            refName = "${name}.${masterName}"
            prettyName = prettyName + " (Master: ${masterName})"
        }

        Map properties = [:]
        Map<String, Object> typeMap = [displayName:prettyName, type:'object', properties:properties] as Map<String, Object>

        if (typesMap != null && !typesMap.containsKey(name)) typesMap.put(refName, typeMap)

        // add field properties
        ArrayList<String> allFields = pkOnly ? ed.getPkFieldNames() : ed.getAllFieldNames()
        for (int i = 0; i < allFields.size(); i++) {
            FieldInfo fi = ed.getFieldInfo(allFields.get(i))
            properties.put(fi.name, getRamlFieldMap(ed, fi))
        }

        // for master add related properties
        if (!pkOnly && (masterName || masterDetail != null)) {
            // add only relationships from master definition or detail
            List<MasterDetail> detailList
            if (masterName) {
                MasterDefinition masterDef = ed.getMasterDefinition(masterName)
                if (masterDef == null) throw new IllegalArgumentException("Master name ${masterName} not valid for entity ${ed.getFullEntityName()}")
                detailList = masterDef.detailList
            } else {
                detailList = masterDetail.getDetailList()
            }
            for (MasterDetail childMasterDetail in detailList) {
                RelationshipInfo relInfo = childMasterDetail.relInfo
                String relationshipName = relInfo.relationshipName
                String entryName = relInfo.shortAlias ?: relationshipName
                String relatedRefName = relInfo.relatedEd.getShortOrFullEntityName()

                // recurse, let it put itself in the definitionsMap
                if (typesMap != null && !typesMap.containsKey(relatedRefName))
                    getRamlTypeMap(relInfo.relatedEd, pkOnly, typesMap, null, childMasterDetail)

                if (relInfo.type == "many") {
                    // properties.put(entryName, [type:'array', items:relatedRefName])
                    properties.put(entryName, [type:(relatedRefName + '[]')])
                } else {
                    properties.put(entryName, [type:relatedRefName])
                }
            }
        }

        return typeMap
    }

    static Map getRamlApi(EntityDefinition ed, String masterName) {
        String name = ed.getShortOrFullEntityName()
        if (masterName) name = "${name}/${masterName}"
        String prettyName = ed.getPrettyName(null, null)

        Map<String, Object> ramlMap = [:]

        // setup field info
        Map qpMap = [:]
        ArrayList<String> allFields = ed.getAllFieldNames()
        for (int i = 0; i < allFields.size(); i++) {
            FieldInfo fi = ed.getFieldInfo(allFields.get(i))
            qpMap.put(fi.name, getRamlFieldMap(ed, fi))
        }

        // get list
        // TODO: make body array of schema
        ramlMap.put('get', [is:['paged'], description:"Get list of ${prettyName}".toString(), queryParameters:qpMap,
                            responses:[200:[body:['application/json': [schema:name]]]]])
        // create
        ramlMap.put('post', [description:"Create ${prettyName}".toString(), body:['application/json': [schema:name]]])

        // under IDs for single record operations
        List<String> pkNameList = ed.getPkFieldNames()
        Map recordMap = ramlMap
        for (String pkName in pkNameList) {
            Map childMap = [:]
            recordMap.put('/{' + pkName + '}', childMap)
            recordMap = childMap
        }

        // get single
        recordMap.put('get', [description:"Get single ${prettyName}".toString(),
                            responses:[200:[body:['application/json': [schema:name]]]]])
        // update
        recordMap.put('patch', [description:"Update ${prettyName}".toString(), body:['application/json': [schema:name]]])
        // store
        recordMap.put('put', [description:"Create or Update ${prettyName}".toString(), body:['application/json': [schema:name]]])
        // delete
        recordMap.put('delete', [description:"Delete ${prettyName}".toString()])

        return ramlMap
    }

    static void addToSwaggerMap(EntityDefinition ed, Map<String, Object> swaggerMap, String masterName) {
        Map definitionsMap = ((Map) swaggerMap.definitions)
        String refDefName = ed.getShortOrFullEntityName()
        if (masterName) refDefName = refDefName + "." + masterName
        String refDefNamePk = refDefName + ".PK"

        String entityDescription = ed.getEntityNode().first("description")?.text

        // add responses
        Map responses = ["401":[description:"Authentication required"], "403":[description:"Access Forbidden (no authz)"],
                         "404":[description:"Value Not Found"], "429":[description:"Too Many Requests (tarpit)"],
                         "500":[description:"General Error"]]

        // entity path (no ID)
        String entityPath = "/" + (ed.getShortOrFullEntityName())
        if (masterName) entityPath = entityPath + "/" + masterName
        Map<String, Map<String, Object>> entityResourceMap = [:]
        ((Map) swaggerMap.paths).put(entityPath, entityResourceMap)

        // get - list
        List<Map> listParameters = []
        listParameters.addAll(swaggerPaginationParameters)
        for (String fieldName in ed.getAllFieldNames()) {
            FieldInfo fi = ed.getFieldInfo(fieldName)
            listParameters.add([name:fieldName, in:'query', required:false, type:(fieldTypeJsonMap.get(fi.type) ?: "string"),
                                format:(fieldTypeJsonFormatMap.get(fi.type) ?: ""),
                                description:fi.fieldNode.first("description")?.text])
        }
        Map listResponses = ["200":[description:'Success', schema:[type:"array", items:['$ref':"#/definitions/${refDefName}".toString()]]]]
        listResponses.putAll(responses)
        entityResourceMap.put("get", [summary:("Get ${ed.getFullEntityName()}".toString()), description:entityDescription,
                parameters:listParameters, security:[[basicAuth:[]]], responses:listResponses])

        // post - create
        Map createResponses = ["200":[description:'Success', schema:['$ref':"#/definitions/${refDefNamePk}".toString()]]]
        createResponses.putAll(responses)
        entityResourceMap.put("post", [summary:("Create ${ed.getFullEntityName()}".toString()), description:entityDescription,
                parameters:[name:'body', in:'body', required:true, schema:['$ref':"#/definitions/${refDefName}".toString()]],
                security:[[basicAuth:[]]], responses:createResponses])

        // entity plus ID path
        StringBuilder entityIdPathSb = new StringBuilder(entityPath)
        List<Map> parameters = []
        for (String pkName in ed.getPkFieldNames()) {
            entityIdPathSb.append("/{").append(pkName).append("}")

            FieldInfo fi = ed.getFieldInfo(pkName)
            parameters.add([name:pkName, in:'path', required:true, type:(fieldTypeJsonMap.get(fi.type) ?: "string"),
                            description:fi.fieldNode.first("description")?.text])
        }
        String entityIdPath = entityIdPathSb.toString()
        Map<String, Map<String, Object>> entityIdResourceMap = [:]
        ((Map) swaggerMap.paths).put(entityIdPath, entityIdResourceMap)

        // under id: get - one
        Map oneResponses = ["200":[name:'body', in:'body', required:false, schema:['$ref':"#/definitions/${refDefName}".toString()]]]
        oneResponses.putAll(responses)
        entityIdResourceMap.put("get", [summary:("Create ${ed.getFullEntityName()}".toString()),
                description:entityDescription, security:[[basicAuth:[]], [api_key:[]]], parameters:parameters, responses:oneResponses])

        // under id: patch - update
        List<Map> updateParameters = new LinkedList<Map>(parameters)
        updateParameters.add([name:'body', in:'body', required:false, schema:['$ref':"#/definitions/${refDefName}".toString()]])
        entityIdResourceMap.put("patch", [summary:("Update ${ed.getFullEntityName()}".toString()),
                description:entityDescription, security:[[basicAuth:[]], [api_key:[]]], parameters:updateParameters, responses:responses])

        // under id: put - store
        entityIdResourceMap.put("put", [summary:("Create or Update ${ed.getFullEntityName()}".toString()),
                description:entityDescription, security:[[basicAuth:[]], [api_key:[]]], parameters:updateParameters, responses:responses])

        // under id: delete - delete
        entityIdResourceMap.put("delete", [summary:("Delete ${ed.getFullEntityName()}".toString()),
                description:entityDescription, security:[[basicAuth:[]], [api_key:[]]], parameters:parameters, responses:responses])

        // add a definition for entity fields
        definitionsMap.put(refDefName, getJsonSchema(ed, false, false, definitionsMap, null, null, null, false, masterName, null))
        definitionsMap.put(refDefNamePk, getJsonSchema(ed, true, false, null, null, null, null, false, masterName, null))
    }

    // ================================================
    // ========== Service Definition Methods ==========
    // ================================================

    static Map<String, Object> getJsonSchemaMapIn(ServiceDefinition sd) {
        // add a definition for service in parameters
        List<String> requiredParms = []
        Map<String, Object> properties = [:]
        Map<String, Object> defMap = [type:'object', properties:properties] as Map<String, Object>
        for (String parmName in sd.getInParameterNames()) {
            MNode parmNode = sd.getInParameter(parmName)
            if (parmNode.attribute("required") == "true") requiredParms.add(parmName)
            properties.put(parmName, getJsonSchemaPropMap(sd, parmNode))
        }
        if (requiredParms) defMap.put("required", requiredParms)
        return defMap
    }
    static Map<String, Object> getJsonSchemaMapOut(ServiceDefinition sd) {
        List<String> requiredParms = []
        Map<String, Object> properties = [:]
        Map<String, Object> defMap = [type:'object', properties:properties] as Map<String, Object>
        for (String parmName in sd.getOutParameterNames()) {
            MNode parmNode = sd.getOutParameter(parmName)
            if (parmNode.attribute("required") == "true") requiredParms.add(parmName)
            properties.put(parmName, getJsonSchemaPropMap(sd, parmNode))
        }
        if (requiredParms) defMap.put("required", requiredParms)
        return defMap
    }
    static protected Map<String, Object> getJsonSchemaPropMap(ServiceDefinition sd, MNode parmNode) {
        String objectType = (String) parmNode?.attribute('type')
        String jsonType = RestApi.getJsonType(objectType)
        Map<String, Object> propMap = [type:jsonType] as Map<String, Object>
        String format = RestApi.getJsonFormat(objectType)
        if (format) propMap.put("format", format)
        String description = parmNode.first("description")?.text
        if (description) propMap.put("description", description)
        if (parmNode.attribute("default-value")) propMap.put("default", (String) parmNode.attribute("default-value"))
        if (parmNode.attribute("default")) propMap.put("default", "{${parmNode.attribute("default")}}".toString())

        List<MNode> childList = parmNode.children("parameter")
        if (jsonType == 'array') {
            if (childList) {
                propMap.put("items", getJsonSchemaPropMap(sd, childList[0]))
            } else {
                logger.warn("Parameter ${parmNode.attribute('name')} of service ${sd.serviceName} is an array type but has no child parameter (should have one, name ignored), may cause error in Swagger, etc")
            }
        } else if (jsonType == 'object') {
            if (childList) {
                Map properties = [:]
                propMap.put("properties", properties)
                for (MNode childNode in childList) {
                    properties.put(childNode.attribute("name"), getJsonSchemaPropMap(sd, childNode))
                }
            } else {
                // Swagger UI is okay with empty maps (works, just less detail), so don't warn about this
                // logger.warn("Parameter ${parmNode.attribute('name')} of service ${getServiceName()} is an object type but has no child parameters, may cause error in Swagger, etc")
            }
        } else {
            addParameterEnums(sd, parmNode, propMap)
        }

        return propMap
    }

    static void addParameterEnums(ServiceDefinition sd, MNode parmNode, Map<String, Object> propMap) {
        String entityName = parmNode.attribute("entity-name")
        String fieldName = parmNode.attribute("field-name")
        if (entityName && fieldName) {
            EntityDefinition ed = sd.sfi.ecfi.entityFacade.getEntityDefinition(entityName)
            if (ed == null) throw new ServiceException("Entity ${entityName} not found, from parameter ${parmNode.attribute('name')} of service ${sd.serviceName}")
            FieldInfo fi = ed.getFieldInfo(fieldName)
            if (fi == null) throw new ServiceException("Field ${fieldName} not found for entity ${entityName}, from parameter ${parmNode.attribute('name')} of service ${sd.serviceName}")
            List enumList = getFieldEnums(ed, fi)
            if (enumList) propMap.put('enum', enumList)
        }
    }

    static Map<String, Object> getRamlMapIn(ServiceDefinition sd) {
        Map<String, Object> properties = [:]
        Map<String, Object> defMap = [type:'object', properties:properties] as Map<String, Object>
        for (String parmName in sd.getInParameterNames()) {
            MNode parmNode = sd.getInParameter(parmName)
            properties.put(parmName, getRamlPropMap(parmNode))
        }
        return defMap
    }
    static Map<String, Object> getRamlMapOut(ServiceDefinition sd) {
        Map<String, Object> properties = [:]
        Map<String, Object> defMap = [type:'object', properties:properties] as Map<String, Object>
        for (String parmName in sd.getOutParameterNames()) {
            MNode parmNode = sd.getOutParameter(parmName)
            properties.put(parmName, getRamlPropMap(parmNode))
        }
        return defMap
    }
    protected static Map<String, Object> getRamlPropMap(MNode parmNode) {
        String objectType = parmNode?.attribute('type')
        String ramlType = RestApi.getRamlType(objectType)
        Map<String, Object> propMap = [type:ramlType] as Map<String, Object>
        String description = parmNode.first("description")?.text
        if (description) propMap.put("description", description)
        if (parmNode.attribute("required") == "true") propMap.put("required", true)
        if (parmNode.attribute("default-value")) propMap.put("default", (String) parmNode.attribute("default-value"))
        if (parmNode.attribute("default")) propMap.put("default", "{${parmNode.attribute("default")}}".toString())

        List<MNode> childList = parmNode.children("parameter")
        if (childList) {
            if (ramlType == 'array') {
                propMap.put("items", getRamlPropMap(childList[0]))
            } else if (ramlType == 'object') {
                Map properties = [:]
                propMap.put("properties", properties)
                for (MNode childNode in childList) {
                    properties.put(childNode.attribute("name"), getRamlPropMap(childNode))
                }
            }
        }

        return propMap
    }

    // ================================================
    // ========== Web Request Schema Methods ==========
    // ================================================

    static void handleEntityRestSchema(ExecutionContextImpl eci, List<String> extraPathNameList, String schemaUri, String linkPrefix,
                                       String schemaLinkPrefix, boolean getMaster) {
        // make sure a user is logged in, screen/etc that calls will generally be configured to not require auth
        if (!eci.getUser().getUsername()) {
            // if there was a login error there will be a MessageFacade error message
            String errorMessage = eci.message.errorsString
            if (!errorMessage) errorMessage = "Authentication required for entity REST schema"
            eci.webImpl.sendJsonError(HttpServletResponse.SC_UNAUTHORIZED, errorMessage)
            return
        }

        EntityFacadeImpl efi = eci.entityFacade

        if (extraPathNameList.size() == 0) {
            List allRefList = []
            Map definitionsMap = [:]
            definitionsMap.put('paginationParameters', jsonPaginationParameters)
            Map rootMap = ['$schema':'http://json-schema.org/draft-04/hyper-schema#', title:'Moqui Entity REST API',
                    anyOf:allRefList, definitions:definitionsMap]
            if (schemaUri) rootMap.put('id', schemaUri)

            Set<String> entityNameSet
            if (getMaster) {
                // if getMaster and no entity name in path, just get entities with master definitions
                entityNameSet = efi.getAllEntityNamesWithMaster()
            } else {
                entityNameSet = efi.getAllNonViewEntityNames()
            }
            for (String entityName in entityNameSet) {
                EntityDefinition ed = efi.getEntityDefinition(entityName)
                String refName = ed.getShortOrFullEntityName()
                if (getMaster) {
                    Map<String, MasterDefinition> masterDefMap = ed.getMasterDefinitionMap()
                    Map entityPathMap = [:]
                    for (String masterName in masterDefMap.keySet()) {
                        allRefList.add(['$ref':"#/definitions/${refName}/${masterName}"])

                        Map schema = getJsonSchema(ed, false, false, definitionsMap, schemaUri, linkPrefix, schemaLinkPrefix, false, masterName, null)
                        entityPathMap.put(masterName, schema)
                    }
                    definitionsMap.put(refName, entityPathMap)
                } else {
                    allRefList.add(['$ref':"#/definitions/${refName}"])

                    Map schema = getJsonSchema(ed, false, false, null, schemaUri, linkPrefix, schemaLinkPrefix, true, null, null)
                    definitionsMap.put(refName, schema)
                }
            }

            JsonBuilder jb = new JsonBuilder()
            jb.call(rootMap)
            String jsonStr = jb.toPrettyString()

            eci.webImpl.sendTextResponse(jsonStr, "application/schema+json", "MoquiEntities.schema.json")
        } else {
            String entityName = extraPathNameList.get(0)
            if (entityName.endsWith(".json")) entityName = entityName.substring(0, entityName.length() - 5)

            String masterName = null
            if (extraPathNameList.size() > 1) {
                masterName = extraPathNameList.get(1)
                if (masterName.endsWith(".json")) masterName = masterName.substring(0, masterName.length() - 5)
            }
            if (getMaster && !masterName) masterName = "default"

            try {
                EntityDefinition ed = efi.getEntityDefinition(entityName)
                if (ed == null) {
                    eci.webImpl.sendJsonError(HttpServletResponse.SC_BAD_REQUEST, "No entity found with name or alias [${entityName}]")
                    return
                }

                Map schema = getJsonSchema(ed, false, true, null, schemaUri, linkPrefix, schemaLinkPrefix, !getMaster, masterName, null)
                // TODO: support array wrapper (different URL? suffix?) with [type:'array', items:schema]

                // sendJsonResponse(schema)
                JsonBuilder jb = new JsonBuilder()
                jb.call(schema)
                String jsonStr = jb.toPrettyString()

                eci.webImpl.sendTextResponse(jsonStr, "application/schema+json", "${entityName}.schema.json")
            } catch (EntityNotFoundException e) {
                if (logger.isTraceEnabled()) logger.trace("In entity REST schema entity not found: " + e.toString())
                eci.webImpl.sendJsonError(HttpServletResponse.SC_BAD_REQUEST, "No entity found with name or alias [${entityName}]")
            }
        }
    }

    static void handleEntityRestRaml(ExecutionContextImpl eci, List<String> extraPathNameList, String linkPrefix, String schemaLinkPrefix, boolean getMaster) {
        // make sure a user is logged in, screen/etc that calls will generally be configured to not require auth
        if (!eci.getUser().getUsername()) {
            // if there was a login error there will be a MessageFacade error message
            String errorMessage = eci.message.errorsString
            if (!errorMessage) errorMessage = "Authentication required for entity REST schema"
            eci.webImpl.sendJsonError(HttpServletResponse.SC_UNAUTHORIZED, errorMessage)
            return
        }

        EntityFacadeImpl efi = eci.entityFacade

        List<Map> schemasList = []
        Map<String, Object> rootMap = [title:'Moqui Entity REST API', version:eci.factory.moquiVersion, baseUri:linkPrefix,
                                       mediaType:'application/json', schemas:schemasList] as Map<String, Object>
        rootMap.put('traits', [[paged:[queryParameters:ramlPaginationParameters]]])

        Set<String> entityNameSet
        String masterName = null
        if (extraPathNameList.size() > 0) {
            String entityName = extraPathNameList.get(0)
            if (entityName.endsWith(".raml")) entityName = entityName.substring(0, entityName.length() - 5)

            if (extraPathNameList.size() > 1) {
                masterName = extraPathNameList.get(1)
                if (masterName.endsWith(".raml")) masterName = masterName.substring(0, masterName.length() - 5)
            }

            entityNameSet = new TreeSet<String>()
            entityNameSet.add(entityName)
        } else if (getMaster) {
            // if getMaster and no entity name in path, just get entities with master definitions
            entityNameSet = efi.getAllEntityNamesWithMaster()
        } else {
            entityNameSet = efi.getAllNonViewEntityNames()
        }
        for (String entityName in entityNameSet) {
            EntityDefinition ed = efi.getEntityDefinition(entityName)
            String refName = ed.getShortOrFullEntityName()
            if (getMaster) {
                Set<String> masterNameSet = new LinkedHashSet<String>()
                if (masterName) {
                    masterNameSet.add(masterName)
                } else {
                    Map<String, MasterDefinition> masterDefMap = ed.getMasterDefinitionMap()
                    masterNameSet.addAll(masterDefMap.keySet())
                }
                Map entityPathMap = [:]
                for (String curMasterName in masterNameSet) {
                    schemasList.add([("${refName}/${curMasterName}".toString()):"!include ${schemaLinkPrefix}/${refName}/${curMasterName}.json".toString()])

                    Map ramlApi = getRamlApi(ed, masterName)
                    entityPathMap.put("/" + curMasterName, ramlApi)
                }
                rootMap.put("/" + refName, entityPathMap)
            } else {
                schemasList.add([(refName):"!include ${schemaLinkPrefix}/${refName}.json".toString()])

                Map ramlApi = getRamlApi(ed, null)
                rootMap.put('/' + refName, ramlApi)
            }
        }

        DumperOptions options = new DumperOptions()
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
        // default: options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN)
        options.setPrettyFlow(true)
        Yaml yaml = new Yaml(options)
        String yamlString = yaml.dump(rootMap)
        // add beginning line "#%RAML 0.8", more efficient way to do this?
        yamlString = "#%RAML 0.8\n" + yamlString

        eci.webImpl.sendTextResponse(yamlString, "application/raml+yaml", "MoquiEntities.raml")
    }

    static void handleEntityRestSwagger(ExecutionContextImpl eci, List<String> extraPathNameList, String basePath, boolean getMaster) {
        if (extraPathNameList.size() == 0) {
            eci.webImpl.sendJsonError(HttpServletResponse.SC_BAD_REQUEST, "No entity name specified in path (for all entities use 'all')")
            return
        }

        EntityFacadeImpl efi = eci.entityFacade

        String entityName = extraPathNameList.get(0)
        String outputType = "application/json"
        if (entityName.endsWith(".yaml")) outputType = "application/yaml"
        if (entityName.endsWith(".json") || entityName.endsWith(".yaml"))
            entityName = entityName.substring(0, entityName.length() - 5)
        if (entityName == 'all') entityName = null

        String masterName = null
        if (extraPathNameList.size() > 1) {
            masterName = extraPathNameList.get(1)
            if (masterName.endsWith(".json") || masterName.endsWith(".yaml"))
                masterName = masterName.substring(0, masterName.length() - 5)
        }

        String filename = entityName ?: "Entities"
        if (masterName) filename = filename + "." + masterName

        eci.webImpl.response.addHeader("Access-Control-Allow-Origin", "*")
        eci.webImpl.response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT, PATCH, OPTIONS")
        eci.webImpl.response.addHeader("Access-Control-Allow-Headers", "Content-Type, api_key, Authorization")

        String fullHost = WebFacadeImpl.makeWebappHost(eci.webImpl.webappMoquiName, eci, eci.webImpl, true)
        String scheme = fullHost.substring(0, fullHost.indexOf("://"))
        String hostName = fullHost.substring(fullHost.indexOf("://") + 3)
        Map definitionsMap = new TreeMap()
        Map<String, Object> swaggerMap = [swagger:'2.0',
            info:[title:("${filename} REST API"), version:eci.factory.moquiVersion], host:hostName, basePath:basePath,
            schemes:[scheme], consumes:['application/json', 'multipart/form-data'], produces:['application/json'],
            securityDefinitions:[basicAuth:[type:'basic', description:'HTTP Basic Authentication'],
                api_key:[type:"apiKey", name:"api_key", in:"header", description:'HTTP Header api_key']],
            paths:[:], definitions:definitionsMap
        ]

        Set<String> entityNameSet
        if (entityName) {
            entityNameSet = new TreeSet<String>()
            entityNameSet.add(entityName)
        } else if (getMaster) {
            // if getMaster and no entity name in path, just get entities with master definitions
            entityNameSet = efi.getAllEntityNamesWithMaster()
        } else {
            entityNameSet = efi.getAllNonViewEntityNames()
        }

        for (String curEntityName in entityNameSet) {
            EntityDefinition ed = efi.getEntityDefinition(curEntityName)
            if (getMaster) {
                Set<String> masterNameSet = new LinkedHashSet<String>()
                if (masterName) {
                    masterNameSet.add(masterName)
                } else {
                    Map<String, MasterDefinition> masterDefMap = ed.getMasterDefinitionMap()
                    masterNameSet.addAll(masterDefMap.keySet())
                }
                for (String curMasterName in masterNameSet) {
                    addToSwaggerMap(ed, swaggerMap, curMasterName)
                }
            } else {
                addToSwaggerMap(ed, swaggerMap, null)
            }
        }

        if (outputType == "application/json") {
            JsonBuilder jb = new JsonBuilder()
            jb.call(swaggerMap)
            String jsonStr = jb.toPrettyString()
            eci.webImpl.sendTextResponse(jsonStr, "application/json", "${filename}.swagger.json")
        } else if (outputType == "application/yaml") {
            DumperOptions options = new DumperOptions()
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
            // default: options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN)
            options.setPrettyFlow(true)
            Yaml yaml = new Yaml(options)
            String yamlString = yaml.dump(swaggerMap)

            eci.webImpl.sendTextResponse(yamlString, "application/yaml", "${filename}.swagger.yaml")
        } else {
            eci.webImpl.sendJsonError(HttpServletResponse.SC_BAD_REQUEST, "Output type ${outputType} not supported")
        }
    }

    static void handleServiceRestSwagger(ExecutionContextImpl eci, List<String> extraPathNameList, String basePath) {
        if (extraPathNameList.size() == 0) {
            eci.webImpl.sendJsonError(HttpServletResponse.SC_BAD_REQUEST, "No root resource name specified in path")
            return
        }

        String outputType = "application/json"
        List<String> rootPathList = []
        StringBuilder filenameBase = new StringBuilder()
        for (String pathName in extraPathNameList) {
            if (pathName.endsWith(".yaml")) outputType = "application/yaml"
            if (pathName.endsWith(".json") || pathName.endsWith(".yaml"))
                pathName = pathName.substring(0, pathName.length() - 5)
            rootPathList.add(pathName)
            filenameBase.append(pathName).append('.')
        }

        eci.webImpl.response.addHeader("Access-Control-Allow-Origin", "*")
        eci.webImpl.response.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT, PATCH, OPTIONS")
        eci.webImpl.response.addHeader("Access-Control-Allow-Headers", "Content-Type, api_key, Authorization")

        String fullHost = WebFacadeImpl.makeWebappHost(eci.webImpl.webappMoquiName, eci, eci.webImpl, true)
        String scheme = fullHost.substring(0, fullHost.indexOf("://"))
        String hostName = fullHost.substring(fullHost.indexOf("://") + 3)
        Map swaggerMap = eci.serviceFacade.restApi.getSwaggerMap(rootPathList, [scheme], hostName, basePath)
        if (outputType == "application/json") {
            JsonBuilder jb = new JsonBuilder()
            jb.call(swaggerMap)
            String jsonStr = jb.toPrettyString()
            eci.webImpl.sendTextResponse(jsonStr, "application/json", "${filenameBase}swagger.json")
        } else if (outputType == "application/yaml") {
            DumperOptions options = new DumperOptions()
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
            // default: options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN)
            options.setPrettyFlow(true)
            Yaml yaml = new Yaml(options)
            String yamlString = yaml.dump(swaggerMap)

            eci.webImpl.sendTextResponse(yamlString, "application/yaml", "${filenameBase}swagger.yaml")
        } else {
            eci.webImpl.sendJsonError(HttpServletResponse.SC_BAD_REQUEST, "Output type ${outputType} not supported")
        }
    }

    static void handleServiceRestRaml(ExecutionContextImpl eci, List<String> extraPathNameList, String linkPrefix) {
        if (extraPathNameList.size() == 0) {
            eci.webImpl.sendJsonError(HttpServletResponse.SC_BAD_REQUEST, "No root resource name specified in path")
            return
        }
        String rootResourceName = extraPathNameList.get(0)
        if (rootResourceName.endsWith(".raml")) rootResourceName = rootResourceName.substring(0, rootResourceName.length() - 5)

        Map swaggerMap = eci.serviceFacade.restApi.getRamlMap(rootResourceName, linkPrefix)
        DumperOptions options = new DumperOptions()
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK)
        // default: options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN)
        options.setPrettyFlow(true)
        Yaml yaml = new Yaml(options)
        String yamlString = yaml.dump(swaggerMap)
        // add beginning line "#%RAML 1.0", more efficient way to do this?
        yamlString = "#%RAML 1.0\n" + yamlString

        eci.webImpl.sendTextResponse(yamlString, "application/raml+yaml", "${rootResourceName}.raml")
    }
}
