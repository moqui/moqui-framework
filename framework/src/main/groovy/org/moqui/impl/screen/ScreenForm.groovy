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
package org.moqui.impl.screen

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.moqui.BaseException
import org.moqui.context.ExecutionContext
import org.moqui.entity.*
import org.moqui.impl.StupidJavaUtilities
import org.moqui.impl.StupidUtilities
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.*
import org.moqui.impl.entity.EntityJavaUtil.RelationshipInfo
import org.moqui.impl.screen.ScreenDefinition.TransitionItem
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.util.FtlNodeWrapper
import org.moqui.util.ContextStack
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.math.RoundingMode
import java.sql.Timestamp

@CompileStatic
class ScreenForm {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenForm.class)

    protected static final Set<String> fieldAttributeNames = new HashSet<String>(["name", "entry-name", "hide",
            "validate-service", "validate-parameter", "validate-entity", "validate-field"])
    protected static final Set<String> subFieldAttributeNames = new HashSet<String>(["title", "tooltip", "red-when"])

    protected ExecutionContextFactoryImpl ecfi
    protected ScreenDefinition sd
    protected MNode internalFormNode
    protected FormInstance internalFormInstance
    protected String location
    protected String formName
    protected String fullFormName
    protected boolean hasDbExtensions = false
    protected boolean isDynamic = false
    protected String extendsScreenLocation = null

    protected XmlAction rowActions = null

    ScreenForm(ExecutionContextFactoryImpl ecfi, ScreenDefinition sd, MNode baseFormNode, String location) {
        this.ecfi = ecfi
        this.sd = sd
        this.location = location
        this.formName = baseFormNode.attribute("name")
        this.fullFormName = sd.getLocation() + "#" + formName

        // is this a dynamic form?
        isDynamic = (baseFormNode.attribute("dynamic") == "true")

        // does this form have DbForm extensions?
        boolean alreadyDisabled = ecfi.getExecutionContext().getArtifactExecution().disableAuthz()
        try {
            EntityList dbFormLookupList = ecfi.entityFacade.find("DbFormLookup")
                    .condition("modifyXmlScreenForm", fullFormName).useCache(true).list()
            if (dbFormLookupList) hasDbExtensions = true
        } finally {
            if (!alreadyDisabled) ecfi.getExecutionContext().getArtifactExecution().enableAuthz()
        }

        if (isDynamic) {
            internalFormNode = baseFormNode
        } else {
            // setting parent to null so that this isn't found in addition to the literal form-* element
            internalFormNode = new MNode(baseFormNode.name, null)
            initForm(baseFormNode, internalFormNode)
            internalFormInstance = new FormInstance()
        }
    }

    void initForm(MNode baseFormNode, MNode newFormNode) {
        // if there is an extends, put that in first (everything else overrides it)
        if (baseFormNode.attribute("extends")) {
            String extendsForm = baseFormNode.attribute("extends")
            if (isDynamic) extendsForm = ecfi.resourceFacade.expand(extendsForm, "")

            MNode formNode
            if (extendsForm.contains("#")) {
                String screenLocation = extendsForm.substring(0, extendsForm.indexOf("#"))
                String formName = extendsForm.substring(extendsForm.indexOf("#")+1)
                if (screenLocation == sd.getLocation()) {
                    ScreenForm esf = sd.getForm(formName)
                    formNode = esf?.getOrCreateFormNode()
                } else if (screenLocation == "moqui.screen.form.DbForm" || screenLocation == "DbForm") {
                    formNode = getDbFormNode(formName, ecfi)
                } else {
                    ScreenDefinition esd = ecfi.screenFacade.getScreenDefinition(screenLocation)
                    ScreenForm esf = esd ? esd.getForm(formName) : null
                    formNode = esf?.getOrCreateFormNode()

                    if (formNode != null) {
                        // see if the included section contains any SECTIONS, need to reference those here too!
                        Map<String, ArrayList<MNode>> descMap = formNode.descendants(new HashSet<String>(['section', 'section-iterate']))
                        for (MNode inclRefNode in descMap.get("section"))
                            this.sd.sectionByName.put(inclRefNode.attribute("name"), esd.getSection(inclRefNode.attribute("name")))
                        for (MNode inclRefNode in descMap.get("section-iterate"))
                            this.sd.sectionByName.put(inclRefNode.attribute("name"), esd.getSection(inclRefNode.attribute("name")))
                    }
                }
                extendsScreenLocation = screenLocation
            } else {
                ScreenForm esf = sd.getForm(extendsForm)
                formNode = esf?.getOrCreateFormNode()
            }
            if (formNode == null) throw new IllegalArgumentException("Cound not find extends form [${extendsForm}] referred to in form [${newFormNode.attribute("name")}] of screen [${sd.location}]")
            mergeFormNodes(newFormNode, formNode, true, true)
        }

        for (MNode formSubNode in baseFormNode.children) {
            if (formSubNode.name == "field") {
                MNode nodeCopy = formSubNode.deepCopy(null)
                expandFieldNode(newFormNode, nodeCopy)
                mergeFieldNode(newFormNode, nodeCopy, false)
            } else if (formSubNode.name == "auto-fields-service") {
                String serviceName = formSubNode.attribute("service-name")
                ArrayList<MNode> excludeList = formSubNode.children("exclude")
                int excludeListSize = excludeList.size()
                Set<String> excludes = excludeListSize > 0 ? new HashSet<String>() : (Set<String>) null
                for (int i = 0; i < excludeListSize; i++) {
                    MNode excludeNode = (MNode) excludeList.get(i)
                    excludes.add(excludeNode.attribute("parameter-name"))
                }
                if (isDynamic) serviceName = ecfi.resourceFacade.expand(serviceName, "")
                ServiceDefinition serviceDef = ecfi.serviceFacade.getServiceDefinition(serviceName)
                if (serviceDef != null) {
                    addServiceFields(serviceDef, formSubNode.attribute("include")?:"in", formSubNode.attribute("field-type")?:"edit",
                            excludes, newFormNode, ecfi)
                    continue
                }
                if (ecfi.serviceFacade.isEntityAutoPattern(serviceName)) {
                    EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(ServiceDefinition.getNounFromName(serviceName))
                    if (ed != null) {
                        addEntityFields(ed, "all", formSubNode.attribute("field-type")?:"edit", null, newFormNode)
                        continue
                    }
                }
                throw new IllegalArgumentException("Cound not find service [${serviceName}] or entity noun referred to in auto-fields-service of form [${newFormNode.attribute("name")}] of screen [${sd.location}]")
            } else if (formSubNode.name == "auto-fields-entity") {
                String entityName = formSubNode.attribute("entity-name")
                if (isDynamic) entityName = ecfi.resourceFacade.expand(entityName, "")
                EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(entityName)
                if (ed != null) {
                    ArrayList<MNode> excludeList = formSubNode.children("exclude")
                    int excludeListSize = excludeList.size()
                    Set<String> excludes = excludeListSize > 0 ? new HashSet<String>() : (Set<String>) null
                    for (int i = 0; i < excludeListSize; i++) {
                        MNode excludeNode = (MNode) excludeList.get(i)
                        excludes.add(excludeNode.attribute("field-name"))
                    }
                    addEntityFields(ed, formSubNode.attribute("include")?:"all", formSubNode.attribute("field-type")?:"find-display",
                            excludes, newFormNode)
                    continue
                }
                throw new IllegalArgumentException("Cound not find entity [${entityName}] referred to in auto-fields-entity of form [${newFormNode.attribute("name")}] of screen [${sd.location}]")
            }
        }

        // merge original formNode to override any applicable settings
        mergeFormNodes(newFormNode, baseFormNode, false, false)

        // populate validate-service and validate-entity attributes if the target transition calls a single service
        if (newFormNode.attribute("transition")) {
            TransitionItem ti = this.sd.getTransitionItem(newFormNode.attribute("transition"), null)
            if (ti != null && ti.getSingleServiceName()) {
                String singleServiceName = ti.getSingleServiceName()
                ServiceDefinition sd = ecfi.serviceFacade.getServiceDefinition(singleServiceName)
                if (sd != null) {
                    ArrayList<String> inParamNames = sd.getInParameterNames()
                    for (MNode fieldNode in newFormNode.children("field")) {
                        // if the field matches an in-parameter name and does not already have a validate-service, then set it
                        // do it even if it has a validate-service since it might be from another form, in general we want the current service:  && !fieldNode."@validate-service"
                        if (inParamNames.contains(fieldNode.attribute("name"))) {
                            fieldNode.attributes.put("validate-service", singleServiceName)
                        }
                    }
                } else if (ecfi.serviceFacade.isEntityAutoPattern(singleServiceName)) {
                    String entityName = ServiceDefinition.getNounFromName(singleServiceName)
                    EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(entityName)
                    ArrayList<String> fieldNames = ed.getAllFieldNames()
                    for (MNode fieldNode in newFormNode.children("field")) {
                        // if the field matches an in-parameter name and does not already have a validate-entity, then set it
                        if (fieldNames.contains(fieldNode.attribute("name")) && !fieldNode.attribute("validate-entity")) {
                            fieldNode.attributes.put("validate-entity", entityName)
                        }
                    }
                }
            }
        }

        // check form-single.field-layout and add ONLY hidden fields that are missing
        MNode fieldLayoutNode = newFormNode.first("field-layout")
        if (fieldLayoutNode && !fieldLayoutNode.depthFirst({ MNode it -> it.name == "fields-not-referenced" })) {
            for (MNode fieldNode in newFormNode.children("field")) {
                if (!fieldLayoutNode.depthFirst({ MNode it -> it.name == "field-ref" && it.attribute("name") == fieldNode.attribute("name") })
                        && fieldNode.depthFirst({ MNode it -> it.name == "hidden" }))
                    addFieldToFieldLayout(newFormNode, fieldNode)
            }
        }

        if (logger.traceEnabled) logger.trace("Form [${location}] resulted in expanded def: " + FtlNodeWrapper.wrapNode(newFormNode).toString())
        // if (location.contains("FOO")) logger.warn("======== Form [${location}] resulted in expanded def: " + FtlNodeWrapper.wrapNode(newFormNode).toString())

        // prep row-actions
        if (newFormNode.hasChild("row-actions")) {
            rowActions = new XmlAction(ecfi, newFormNode.first("row-actions"), location + ".row_actions")
        }
    }

    List<MNode> getDbFormNodeList() {
        if (!hasDbExtensions) return null

        boolean alreadyDisabled = ecfi.getExecutionContext().getArtifactExecution().disableAuthz()
        try {
            // find DbForm records and merge them in as well
            String formName = sd.getLocation() + "#" + internalFormNode.attribute("name")
            EntityList dbFormLookupList = this.ecfi.entityFacade.find("DbFormLookup")
                    .condition("userGroupId", EntityCondition.IN, ecfi.getExecutionContext().getUser().getUserGroupIdSet())
                    .condition("modifyXmlScreenForm", formName)
                    .useCache(true).list()
            // logger.warn("TOREMOVE: looking up DbForms for form [${formName}], found: ${dbFormLookupList}")

            if (!dbFormLookupList) return null

            List<MNode> formNodeList = new ArrayList<MNode>()
            for (EntityValue dbFormLookup in dbFormLookupList) formNodeList.add(getDbFormNode(dbFormLookup.getString("formId"), ecfi))

            return formNodeList
        } finally {
            if (!alreadyDisabled) ecfi.getExecutionContext().getArtifactExecution().enableAuthz()
        }
    }

    static MNode getDbFormNode(String formId, ExecutionContextFactoryImpl ecfi) {
        MNode dbFormNode = (MNode) ecfi.screenFacade.dbFormNodeByIdCache.get(formId)

        if (dbFormNode == null) {

            boolean alreadyDisabled = ecfi.getEci().artifactExecutionFacade.disableAuthz()
            try {
                EntityValue dbForm = ecfi.entityFacade.find("moqui.screen.form.DbForm").condition("formId", formId).useCache(true).one()
                if (dbForm == null) throw new BaseException("Could not find DbForm record with ID [${formId}]")
                dbFormNode = new MNode((dbForm.isListForm == "Y" ? "form-list" : "form-single"), null)

                EntityList dbFormFieldList = ecfi.entityFacade.find("moqui.screen.form.DbFormField").condition("formId", formId)
                        .orderBy("layoutSequenceNum").useCache(true).list()
                for (EntityValue dbFormField in dbFormFieldList) {
                    String fieldName = (String) dbFormField.fieldName
                    MNode newFieldNode = new MNode("field", [name:fieldName])
                    if (dbFormField.entryName) newFieldNode.attributes.put("entry-name", (String) dbFormField.entryName)
                    MNode subFieldNode = newFieldNode.append("default-field", null)
                    if (dbFormField.title) subFieldNode.attributes.put("title", (String) dbFormField.title)
                    if (dbFormField.tooltip) subFieldNode.attributes.put("tooltip", (String) dbFormField.tooltip)

                    String fieldType = dbFormField.fieldTypeEnumId
                    if (!fieldType) throw new IllegalArgumentException("DbFormField record with formId [${formId}] and fieldName [${fieldName}] has no fieldTypeEnumId")

                    String widgetName = fieldType.substring(6)
                    MNode widgetNode = subFieldNode.append(widgetName, null)

                    EntityList dbFormFieldAttributeList = ecfi.entityFacade.find("moqui.screen.form.DbFormFieldAttribute")
                            .condition([formId:formId, fieldName:fieldName] as Map<String, Object>).useCache(true).list()
                    for (EntityValue dbFormFieldAttribute in dbFormFieldAttributeList) {
                        String attributeName = dbFormFieldAttribute.attributeName
                        if (fieldAttributeNames.contains(attributeName)) {
                            newFieldNode.attributes.put(attributeName, (String) dbFormFieldAttribute.value)
                        } else if (subFieldAttributeNames.contains(attributeName)) {
                            subFieldNode.attributes.put(attributeName, (String) dbFormFieldAttribute.value)
                        } else {
                            widgetNode.attributes.put(attributeName, (String) dbFormFieldAttribute.value)
                        }
                    }

                    // add option settings when applicable
                    EntityList dbFormFieldOptionList = ecfi.entityFacade.find("moqui.screen.form.DbFormFieldOption")
                            .condition([formId:formId, fieldName:fieldName] as Map<String, Object>).useCache(true).list()
                    EntityList dbFormFieldEntOptsList = ecfi.entityFacade.find("moqui.screen.form.DbFormFieldEntOpts")
                            .condition([formId:formId, fieldName:fieldName] as Map<String, Object>).useCache(true).list()
                    EntityList combinedOptionList = new EntityListImpl(ecfi.entityFacade)
                    combinedOptionList.addAll(dbFormFieldOptionList)
                    combinedOptionList.addAll(dbFormFieldEntOptsList)
                    combinedOptionList.orderByFields(["sequenceNum"])

                    for (EntityValue optionValue in combinedOptionList) {
                        if (optionValue.getEntityName() == "moqui.screen.form.DbFormFieldOption") {
                            widgetNode.append("option", [key:(String) optionValue.keyValue, text:(String) optionValue.text])
                        } else {
                            MNode entityOptionsNode = widgetNode.append("entity-options", [text:((String) optionValue.text ?: "\${description}")])
                            MNode entityFindNode = entityOptionsNode.append("entity-find", ["entity-name":optionValue.getString("entityName")])

                            EntityList dbFormFieldEntOptsCondList = ecfi.entityFacade.find("moqui.screen.form.DbFormFieldEntOptsCond")
                                    .condition([formId:formId, fieldName:fieldName, sequenceNum:optionValue.sequenceNum])
                                    .useCache(true).list()
                            for (EntityValue dbFormFieldEntOptsCond in dbFormFieldEntOptsCondList) {
                                entityFindNode.append("econdition", ["field-name":(String) dbFormFieldEntOptsCond.entityFieldName, value:(String) dbFormFieldEntOptsCond.value])
                            }

                            EntityList dbFormFieldEntOptsOrderList = ecfi.entityFacade.find("moqui.screen.form.DbFormFieldEntOptsOrder")
                                    .condition([formId:formId, fieldName:fieldName, sequenceNum:optionValue.sequenceNum])
                                    .orderBy("orderSequenceNum").useCache(true).list()
                            for (EntityValue dbFormFieldEntOptsOrder in dbFormFieldEntOptsOrderList) {
                                entityFindNode.append("order-by", ["field-name":(String) dbFormFieldEntOptsOrder.entityFieldName])
                            }
                        }
                    }

                    // logger.warn("TOREMOVE Adding DbForm field [${fieldName}] widgetName [${widgetName}] at layout sequence [${dbFormField.getLong("layoutSequenceNum")}], node: ${newFieldNode}")
                    if (dbFormField.getLong("layoutSequenceNum") != null) {
                        newFieldNode.attributes.put("layoutSequenceNum", dbFormField.getString("layoutSequenceNum"))
                    }
                    mergeFieldNode(dbFormNode, newFieldNode, false)
                }

                ecfi.screenFacade.dbFormNodeByIdCache.put(formId, dbFormNode)
            } finally {
                if (!alreadyDisabled) ecfi.getEci().artifactExecutionFacade.enableAuthz()
            }
        }

        return dbFormNode
    }

    boolean isDisplayOnly() {
        ContextStack cs = ecfi.getEci().contextStack
        return cs.getByString("formDisplayOnly") == "true" || cs.getByString("formDisplayOnly_${formName}") == "true"
    }

    /** This is the main method for using an XML Form, the rendering is done based on the Node returned. */
    MNode getOrCreateFormNode() {
        // NOTE: this is cached in the ScreenRenderImpl as it may be called multiple times for a single form render
        List<MNode> dbFormNodeList = hasDbExtensions ? getDbFormNodeList() : null
        boolean displayOnly = isDisplayOnly()

        if (isDynamic) {
            MNode newFormNode = new MNode(internalFormNode.name, null)
            initForm(internalFormNode, newFormNode)
            if (dbFormNodeList) {
                for (MNode dbFormNode in dbFormNodeList) mergeFormNodes(newFormNode, dbFormNode, false, true)
            }
            return newFormNode
        } else if (dbFormNodeList || displayOnly) {
            MNode newFormNode = new MNode(internalFormNode.name, null)
            // deep copy true to avoid bleed over of new fields and such
            mergeFormNodes(newFormNode, internalFormNode, true, true)
            // logger.warn("========== merging in dbFormNodeList: ${dbFormNodeList}", new BaseException("getOrCreateFormNode call location"))
            for (MNode dbFormNode in dbFormNodeList) mergeFormNodes(newFormNode, dbFormNode, false, true)

            if (displayOnly) {
                // change all non-display fields to simple display elements
                for (MNode fieldNode in newFormNode.children("field")) {
                    // don't replace header form, should be just for searching: if (fieldNode."header-field") fieldSubNodeToDisplay(newFormNode, fieldNode, (Node) fieldNode."header-field"[0])
                    for (MNode conditionalFieldNode in fieldNode.children("conditional-field")) {
                        fieldSubNodeToDisplay(conditionalFieldNode)
                    }
                    if (fieldNode.hasChild("default-field")) fieldSubNodeToDisplay(fieldNode.first("default-field"))
                }
            }

            return newFormNode
        } else {
            return internalFormNode
        }
    }

    MNode getAutoCleanedNode() {
        MNode outNode = getOrCreateFormNode().deepCopy(null)
        outNode.attributes.remove("dynamic")
        outNode.attributes.remove("multi")
        for (int i = 0; i < outNode.children.size(); ) {
            MNode fn = outNode.children.get(i)
            if (fn.attribute("name") in ["aen", "den", "lastUpdatedStamp"]) {
                outNode.children.remove(i)
            } else {
                fn.attributes.remove("validate-entity")
                fn.attributes.remove("validate-field")
                i++
            }
        }

        return outNode
    }

    static Set displayOnlyIgnoreNodeNames = ["hidden", "ignored", "label", "image"] as Set
    protected void fieldSubNodeToDisplay(MNode fieldSubNode) {
        MNode widgetNode = fieldSubNode.children ? fieldSubNode.children.first() : null
        if (widgetNode == null) return
        if (widgetNode.name.contains("display") || displayOnlyIgnoreNodeNames.contains(widgetNode.name)) return

        if (widgetNode.name == "reset" || widgetNode.name == "submit") {
            fieldSubNode.children.remove(0)
            return
        }

        if (widgetNode.name == "link") {
            // if it goes to a transition with service-call or actions then remove it, otherwise leave it
            String urlType = widgetNode.attribute('url-type')
            if ((!urlType || urlType == "transition") &&
                    sd.getTransitionItem(widgetNode.attribute('url'), null).hasActionsOrSingleService()) {
                fieldSubNode.children.remove(0)
            }
            return
        }

        // otherwise change it to a display Node
        fieldSubNode.replace(0, "display", null)
        // not as good, puts it after other child Nodes: fieldSubNode.remove(widgetNode); fieldSubNode.appendNode("display")
    }

    void addAutoServiceField(EntityDefinition nounEd, MNode parameterNode, String fieldType,
                             String serviceVerb, MNode newFieldNode, MNode subFieldNode, MNode baseFormNode) {
        // if the parameter corresponds to an entity field, we can do better with that
        EntityDefinition fieldEd = nounEd
        if (parameterNode.attribute("entity-name")) fieldEd = ecfi.entityFacade.getEntityDefinition(parameterNode.attribute("entity-name"))
        String fieldName = parameterNode.attribute("field-name") ?: parameterNode.attribute("name")
        if (fieldEd != null && fieldEd.getFieldNode(fieldName) != null) {
            addAutoEntityField(fieldEd, fieldName, fieldType, newFieldNode, subFieldNode, baseFormNode)
            return
        }

        // otherwise use the old approach and do what we can with the service def
        String spType = parameterNode.attribute("type") ?: "String"
        String efType = fieldEd != null ? fieldEd.getFieldInfo(parameterNode.attribute("name"))?.type : null

        switch (fieldType) {
            case "edit":
                // lastUpdatedStamp is always hidden for edit (needed for optimistic lock)
                if (parameterNode.attribute("name") == "lastUpdatedStamp") {
                    subFieldNode.append("hidden", null)
                    break
                }

                /* NOTE: used to do this but doesn't make sense for main use of this in ServiceRun/etc screens; for app
                    forms should separates pks and use display or hidden instead of edit:
                if (parameterNode.attribute("required") == "true" && serviceVerb.startsWith("update")) {
                    subFieldNode.append("hidden", null)
                } else {
                }
                */
                if (spType.endsWith("Date") && spType != "java.util.Date") {
                    subFieldNode.append("date-time", [type:"date", format:parameterNode.attribute("format")])
                } else if (spType.endsWith("Time")) {
                    subFieldNode.append("date-time", [type:"time", format:parameterNode.attribute("format")])
                } else if (spType.endsWith("Timestamp") || spType == "java.util.Date") {
                    subFieldNode.append("date-time", [type:"date-time", format:parameterNode.attribute("format")])
                } else {
                    if (efType == "text-long" || efType == "text-very-long") {
                        subFieldNode.append("text-area", null)
                    } else {
                        subFieldNode.append("text-line", ['default-value':parameterNode.attribute("default-value")])
                    }
                }
                break
            case "find":
                if (spType.endsWith("Date") && spType != "java.util.Date") {
                    subFieldNode.append("date-find", [type:"date", format:parameterNode.attribute("format")])
                } else if (spType.endsWith("Time")) {
                    subFieldNode.append("date-find", [type:"time", format:parameterNode.attribute("format")])
                } else if (spType.endsWith("Timestamp") || spType == "java.util.Date") {
                    subFieldNode.append("date-find", [type:"date-time", format:parameterNode.attribute("format")])
                } else if (spType.endsWith("BigDecimal") || spType.endsWith("BigInteger") || spType.endsWith("Long") ||
                        spType.endsWith("Integer") || spType.endsWith("Double") || spType.endsWith("Float") ||
                        spType.endsWith("Number")) {
                    subFieldNode.append("range-find", null)
                } else {
                    subFieldNode.append("text-find", null)
                }
                break
            case "display":
                subFieldNode.append("display", [format:parameterNode.attribute("format")])
                break
            case "find-display":
                MNode headerFieldNode = newFieldNode.append("header-field", null)
                if (spType.endsWith("Date") && spType != "java.util.Date") {
                    headerFieldNode.append("date-find", [type:"date", format:parameterNode.attribute("format")])
                } else if (spType.endsWith("Time")) {
                    headerFieldNode.append("date-find", [type:"time", format:parameterNode.attribute("format")])
                } else if (spType.endsWith("Timestamp") || spType == "java.util.Date") {
                    headerFieldNode.append("date-find", [type:"date-time", format:parameterNode.attribute("format")])
                } else if (spType.endsWith("BigDecimal") || spType.endsWith("BigInteger") || spType.endsWith("Long") ||
                        spType.endsWith("Integer") || spType.endsWith("Double") || spType.endsWith("Float") ||
                        spType.endsWith("Number")) {
                    headerFieldNode.append("range-find", null)
                } else {
                    headerFieldNode.append("text-find", null)
                }
                subFieldNode.append("display", [format:parameterNode.attribute("format")])
                break
            case "hidden":
                subFieldNode.append("hidden", null)
                break
        }
    }
    void addServiceFields(ServiceDefinition sd, String include, String fieldType, Set<String> excludes, MNode baseFormNode,
                          ExecutionContextFactoryImpl ecfi) {
        String serviceVerb = sd.verb
        //String serviceType = sd.serviceNode."@type"
        EntityDefinition nounEd = null
        try {
            nounEd = ecfi.entityFacade.getEntityDefinition(sd.noun)
        } catch (EntityException e) {
            if (logger.isTraceEnabled()) logger.trace("Ignoring entity exception, may not be real entity name: ${e.toString()}")
        }

        List<MNode> parameterNodes = []
        if (include == "in" || include == "all") parameterNodes.addAll(sd.serviceNode.first("in-parameters").children("parameter"))
        if (include == "out" || include == "all") parameterNodes.addAll(sd.serviceNode.first("out-parameters").children("parameter"))

        for (MNode parameterNode in parameterNodes) {
            String parameterName = parameterNode.attribute("name")
            if (excludes != null && excludes.contains(parameterName)) continue
            MNode newFieldNode = new MNode("field", [name:parameterName, "validate-service":sd.serviceName,
                                                     "validate-parameter":parameterName])
            MNode subFieldNode = newFieldNode.append("default-field", null)
            addAutoServiceField(nounEd, parameterNode, fieldType, serviceVerb, newFieldNode, subFieldNode, baseFormNode)
            mergeFieldNode(baseFormNode, newFieldNode, false)
        }
    }

    void addEntityFields(EntityDefinition ed, String include, String fieldType, Set<String> excludes, MNode baseFormNode) {
        for (String fieldName in ed.getFieldNames("all".equals(include) || "pk".equals(include), "all".equals(include) || "nonpk".equals(include))) {
            if (excludes != null && excludes.contains(fieldName)) continue
            String efType = ed.getFieldInfo(fieldName).type ?: "text-long"
            if (baseFormNode.name == "form-list" && efType in ['text-long', 'text-very-long', 'binary-very-long']) continue

            MNode newFieldNode = new MNode("field", [name:fieldName, "validate-entity":ed.getFullEntityName(), "validate-field":fieldName])
            MNode subFieldNode = newFieldNode.append("default-field", null)

            addAutoEntityField(ed, fieldName, fieldType, newFieldNode, subFieldNode, baseFormNode)

            // logger.info("Adding form auto entity field [${fieldName}] of type [${efType}], fieldType [${fieldType}] serviceVerb [${serviceVerb}], node: ${newFieldNode}")
            mergeFieldNode(baseFormNode, newFieldNode, false)
        }
        // logger.info("TOREMOVE: after addEntityFields formNode is: ${baseFormNode}")
    }

    void addAutoEntityField(EntityDefinition ed, String fieldName, String fieldType,
                            MNode newFieldNode, MNode subFieldNode, MNode baseFormNode) {
        String efType = ed.getFieldInfo(fieldName).type ?: "text-long"

        // to see if this should be a drop-down with data from another entity,
        // find first relationship that has this field as the only key map and is not a many relationship
        MNode oneRelNode = null
        Map oneRelKeyMap = null
        String relatedEntityName = null
        EntityDefinition relatedEd = null
        for (RelationshipInfo relInfo in ed.getRelationshipsInfo(false)) {
            String relEntityName = relInfo.relatedEntityName
            EntityDefinition relEd = relInfo.relatedEd
            Map km = relInfo.keyMap
            if (km.size() == 1 && km.containsKey(fieldName) && relInfo.type == "one" && relInfo.relNode.attribute("is-auto-reverse") != "true") {
                oneRelNode = relInfo.relNode
                oneRelKeyMap = km
                relatedEntityName = relEntityName
                relatedEd = relEd
            }
        }
        String keyField = (String) oneRelKeyMap?.keySet()?.iterator()?.next()
        String relKeyField = (String) oneRelKeyMap?.values()?.iterator()?.next()
        String relDefaultDescriptionField = relatedEd?.getDefaultDescriptionField()

        switch (fieldType) {
        case "edit":
            // lastUpdatedStamp is always hidden for edit (needed for optimistic lock)
            if (fieldName == "lastUpdatedStamp") {
                subFieldNode.append("hidden", null)
                break
            }

            /* NOTE: used to do this but doesn't make sense for main use of this in ServiceRun/etc screens; for app
                forms should separates pks and use display or hidden instead of edit:
            List<String> pkFieldNameSet = ed.getPkFieldNames()
            if (pkFieldNameSet.contains(fieldName) && serviceVerb == "update") {
                subFieldNode.append("hidden", null)
            } else {
            }
            */
            if (baseFormNode.name == "form-list" && !newFieldNode.hasChild("header-field"))
                newFieldNode.append("header-field", ["show-order-by":"case-insensitive"])
            if (efType.startsWith("date") || efType.startsWith("time")) {
                MNode dateTimeNode = subFieldNode.append("date-time", [type:efType])
                if (fieldName == "fromDate") dateTimeNode.attributes.put("default-value", "\${ec.l10n.format(ec.user.nowTimestamp, 'yyyy-MM-dd HH:mm')}")
            } else if (efType == "text-long" || efType == "text-very-long") {
                subFieldNode.append("text-area", null)
            } else if (efType == "text-indicator") {
                MNode dropDownNode = subFieldNode.append("drop-down", ["allow-empty":"true"])
                dropDownNode.append("option", ["key":"Y"])
                dropDownNode.append("option", ["key":"N"])
            } else if (efType == "binary-very-long") {
                // would be nice to have something better for this, like a download somehow
                subFieldNode.append("display", null)
            } else {
                if (oneRelNode != null) {
                    addEntityFieldDropDown(oneRelNode, subFieldNode, relatedEd, relKeyField, "chosen-wider")
                } else {
                    if (efType.startsWith("number-") || efType.startsWith("currency-")) {
                        subFieldNode.append("text-line", [size:"10"])
                    } else {
                        subFieldNode.append("text-line", [size:"30"])
                    }
                }
            }
            break
        case "find":
            if (baseFormNode.name == "form-list" && !newFieldNode.hasChild("header-field"))
                newFieldNode.append("header-field", ["show-order-by":"case-insensitive"])
            if (efType.startsWith("date") || efType.startsWith("time")) {
                subFieldNode.append("date-find", [type:efType])
            } else if (efType.startsWith("number-") || efType.startsWith("currency-")) {
                subFieldNode.append("range-find", null)
            } else {
                if (oneRelNode != null) {
                    addEntityFieldDropDown(oneRelNode, subFieldNode, relatedEd, relKeyField, "chosen-wider")
                } else {
                    subFieldNode.append("text-find", null)
                }
            }
            break
        case "display":
            if (baseFormNode.name == "form-list" && !newFieldNode.hasChild("header-field"))
                newFieldNode.append("header-field", ["show-order-by":"case-insensitive"])
            String textStr
            if (relDefaultDescriptionField) textStr = "\${" + relDefaultDescriptionField + " ?: ''} [\${" + relKeyField + "}]"
            else textStr = "[\${" + relKeyField + "}]"
            if (oneRelNode != null) subFieldNode.append("display-entity",
                    ["entity-name":(oneRelNode.attribute("related") ?: oneRelNode.attribute("related-entity-name")), "text":textStr])
            else subFieldNode.append("display", null)
            break
        case "find-display":
            if (baseFormNode.name == "form-list" && !newFieldNode.hasChild("header-field"))
                newFieldNode.append("header-field", ["show-order-by":"case-insensitive"])
            MNode headerFieldNode = newFieldNode.hasChild("header-field") ?
                newFieldNode.first("header-field") : newFieldNode.append("header-field", null)
            if (efType == "date" || efType == "time") {
                headerFieldNode.append("date-find", [type:efType])
            } else if (efType == "date-time") {
                headerFieldNode.append("date-period", null)
            } else if (efType.startsWith("number-") || efType.startsWith("currency-")) {
                headerFieldNode.append("range-find", [size:'4'])
            } else {
                if (oneRelNode != null) {
                    addEntityFieldDropDown(oneRelNode, headerFieldNode, relatedEd, relKeyField, "")
                } else {
                    headerFieldNode.append("text-find", ['hide-options':'true', size:'15'])
                }
            }
            if (oneRelNode != null) {
                String textStr
                if (relDefaultDescriptionField) textStr = "\${" + relDefaultDescriptionField + " ?: ''} [\${" + relKeyField + "}]"
                else textStr = "[\${" + relKeyField + "}]"
                subFieldNode.append("display-entity", ["text":textStr,
                        "entity-name":(oneRelNode.attribute("related") ?: oneRelNode.attribute("related-entity-name"))])
            } else {
                subFieldNode.append("display", null)
            }
            break
        case "hidden":
            subFieldNode.append("hidden", null)
            break
        }

        // NOTE: don't like where this is located, would be nice to have a generic way for forms to add this sort of thing
        if (oneRelNode != null) {
            if (internalFormNode.attribute("name") == "UpdateMasterEntityValue") {
                MNode linkNode = subFieldNode.append("link", [url:"edit",
                        text:("Edit ${relatedEd.getPrettyName(null, null)} [\${fieldValues." + keyField + "}]").toString(),
                        condition:keyField, 'link-type':'anchor'] as Map<String, String>)
                linkNode.append("parameter", [name:"aen", value:relatedEntityName])
                linkNode.append("parameter", [name:relKeyField, from:"fieldValues.${keyField}".toString()])
            }
        }
    }

    protected void addEntityFieldDropDown(MNode oneRelNode, MNode subFieldNode, EntityDefinition relatedEd,
                                          String relKeyField, String dropDownStyle) {
        String title = oneRelNode.attribute("title")

        if (relatedEd == null) {
            subFieldNode.append("text-line", null)
            return
        }
        String relatedEntityName = relatedEd.getFullEntityName()
        String relDefaultDescriptionField = relatedEd.getDefaultDescriptionField()

        // NOTE: combo-box not currently supported, so only show drop-down if less than 200 records
        long recordCount
        if (relatedEntityName == "moqui.basic.Enumeration") {
            recordCount = ecfi.entityFacade.find("moqui.basic.Enumeration").condition("enumTypeId", title).disableAuthz().count()
        } else if (relatedEntityName == "moqui.basic.StatusItem") {
            recordCount = ecfi.entityFacade.find("moqui.basic.StatusItem").condition("statusTypeId", title).disableAuthz().count()
        } else {
            recordCount = ecfi.entityFacade.find(relatedEntityName).disableAuthz().count()
        }
        if (recordCount > 0 && recordCount <= 200) {
            // FOR FUTURE: use the combo-box just in case the drop-down as a default is over-constrained
            MNode dropDownNode = subFieldNode.append("drop-down", ["allow-empty":"true", style:(dropDownStyle ?: "")])
            MNode entityOptionsNode = dropDownNode.append("entity-options", null)
            MNode entityFindNode = entityOptionsNode.append("entity-find",
                    ["entity-name":relatedEntityName, "offset":"0", "limit":"200"])

            if (relatedEntityName == "moqui.basic.Enumeration") {
                // recordCount will be > 0 so we know there are records with this type
                entityFindNode.append("econdition", ["field-name":"enumTypeId", "value":title])
            } else if (relatedEntityName == "moqui.basic.StatusItem") {
                // recordCount will be > 0 so we know there are records with this type
                entityFindNode.append("econdition", ["field-name":"statusTypeId", "value":title])
            }

            if (relDefaultDescriptionField) {
                entityOptionsNode.attributes.put("text", "\${" + relDefaultDescriptionField + " ?: ''} [\${" + relKeyField + "}]")
                entityFindNode.append("order-by", ["field-name":relDefaultDescriptionField])
            }
        } else {
            subFieldNode.append("text-line", null)
        }
    }

    protected void expandFieldNode(MNode baseFormNode, MNode fieldNode) {
        if (fieldNode.hasChild("header-field")) expandFieldSubNode(baseFormNode, fieldNode, fieldNode.first("header-field"))
        for (MNode conditionalFieldNode in fieldNode.children("conditional-field"))
            expandFieldSubNode(baseFormNode, fieldNode, conditionalFieldNode)
        if (fieldNode.hasChild("default-field")) expandFieldSubNode(baseFormNode, fieldNode, fieldNode.first("default-field"))
    }

    protected void expandFieldSubNode(MNode baseFormNode, MNode fieldNode, MNode fieldSubNode) {
        MNode widgetNode = fieldSubNode.children ? fieldSubNode.children.get(0) : null
        if (widgetNode == null) return
        if (widgetNode.name == "auto-widget-service") {
            fieldSubNode.children.remove(0)
            addAutoWidgetServiceNode(baseFormNode, fieldNode, fieldSubNode, widgetNode)
        } else if (widgetNode.name == "auto-widget-entity") {
            fieldSubNode.children.remove(0)
            addAutoWidgetEntityNode(baseFormNode, fieldNode, fieldSubNode, widgetNode)
        } else if (widgetNode.name == "widget-template-include") {
            List<MNode> setNodeList = widgetNode.children("set")

            String templateLocation = widgetNode.attribute("location")
            if (!templateLocation) throw new IllegalArgumentException("widget-template-include.@location cannot be empty")
            if (!templateLocation.contains("#")) throw new IllegalArgumentException("widget-template-include.@location must contain a hash/pound sign to separate the file location and widget-template.@name: [${templateLocation}]")
            String fileLocation = templateLocation.substring(0, templateLocation.indexOf("#"))
            String widgetTemplateName = templateLocation.substring(templateLocation.indexOf("#") + 1)

            MNode widgetTemplatesNode = ecfi.screenFacade.getWidgetTemplatesNodeByLocation(fileLocation)
            MNode widgetTemplateNode = widgetTemplatesNode?.first({ MNode it -> it.attribute("name") == widgetTemplateName })
            if (widgetTemplateNode == null) throw new IllegalArgumentException("Could not find widget-template [${widgetTemplateName}] in [${fileLocation}]")

            // remove the widget-template-include node
            fieldSubNode.children.remove(0)
            // remove other nodes and append them back so they are after (we allow arbitrary other widget nodes as field sub-nodes)
            List<MNode> otherNodes = []
            otherNodes.addAll(fieldSubNode.children)
            fieldSubNode.children.clear()

            for (MNode widgetChildNode in widgetTemplateNode.children)
                fieldSubNode.append(widgetChildNode.deepCopy(null))
            for (MNode otherNode in otherNodes) fieldSubNode.append(otherNode)

            for (MNode setNode in setNodeList) fieldSubNode.append(setNode.deepCopy(null))
        }
    }

    protected void addAutoWidgetServiceNode(MNode baseFormNode, MNode fieldNode, MNode fieldSubNode, MNode widgetNode) {
        String serviceName = widgetNode.attribute("service-name")
        if (isDynamic) serviceName = ecfi.resourceFacade.expand(serviceName, "")
        ServiceDefinition serviceDef = ecfi.serviceFacade.getServiceDefinition(serviceName)
        if (serviceDef != null) {
            addAutoServiceField(serviceDef, widgetNode.attribute("parameter-name") ?: fieldNode.attribute("name"),
                    widgetNode.attribute("field-type") ?: "edit", fieldNode, fieldSubNode, baseFormNode)
            return
        }
        if (serviceName.contains("#")) {
            EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(serviceName.substring(serviceName.indexOf("#")+1))
            if (ed != null) {
                addAutoEntityField(ed, widgetNode.attribute("parameter-name")?:fieldNode.attribute("name"),
                        widgetNode.attribute("field-type")?:"edit", fieldNode, fieldSubNode, baseFormNode)
                return
            }
        }
        throw new IllegalArgumentException("Cound not find service [${serviceName}] or entity noun referred to in auto-fields-service of form [${baseFormNode.attribute("name")}] of screen [${sd.location}]")
    }
    void addAutoServiceField(ServiceDefinition sd, String parameterName, String fieldType,
                             MNode newFieldNode, MNode subFieldNode, MNode baseFormNode) {
        EntityDefinition nounEd = null
        try {
            nounEd = ecfi.entityFacade.getEntityDefinition(sd.noun)
        } catch (EntityException e) {
            // ignore, anticipating there may be no entity def
            if (logger.isTraceEnabled()) logger.trace("Ignoring entity exception, not necessarily an entity name: ${e.toString()}")
        }
        MNode parameterNode = sd.serviceNode.first({ MNode it -> it.name == "in-parameters" && it.attribute("name") == parameterName })

        if (parameterNode == null) throw new IllegalArgumentException("Cound not find parameter [${parameterName}] in service [${sd.serviceName}] referred to in auto-widget-service of form [${baseFormNode.attribute("name")}] of screen [${sd.location}]")
        addAutoServiceField(nounEd, parameterNode, fieldType, sd.verb, newFieldNode, subFieldNode, baseFormNode)
    }

    protected void addAutoWidgetEntityNode(MNode baseFormNode, MNode fieldNode, MNode fieldSubNode, MNode widgetNode) {
        String entityName = widgetNode.attribute("entity-name")
        if (isDynamic) entityName = ecfi.resourceFacade.expand(entityName, "")
        EntityDefinition ed = null
        try {
            ed = ecfi.entityFacade.getEntityDefinition(entityName)
        } catch (EntityException e) {
            // ignore, anticipating there may be no entity def
            if (logger.isTraceEnabled()) logger.trace("Ignoring entity exception, not necessarily an entity name: ${e.toString()}")
        }
        if (ed == null) throw new IllegalArgumentException("Cound not find entity [${entityName}] referred to in auto-widget-entity of form [${baseFormNode.attribute("name")}] of screen [${sd.location}]")
        addAutoEntityField(ed, widgetNode.attribute("field-name")?:fieldNode.attribute("name"),
                widgetNode.attribute("field-type")?:"find-display", fieldNode, fieldSubNode, baseFormNode)
    }

    protected static void mergeFormNodes(MNode baseFormNode, MNode overrideFormNode, boolean deepCopy, boolean copyFields) {
        if (overrideFormNode.attributes) baseFormNode.attributes.putAll(overrideFormNode.attributes)

        // if overrideFormNode has any row-actions add them all to the ones of the baseFormNode, ie both will run
        if (overrideFormNode.hasChild("row-actions")) {
            if (!baseFormNode.hasChild("row-actions")) baseFormNode.append("row-actions", null)
            MNode baseRowActionsNode = baseFormNode.first("row-actions")
            for (MNode actionNode in overrideFormNode.first("row-actions").children) baseRowActionsNode.append(actionNode)
        }

        if (copyFields) {
            for (MNode overrideFieldNode in overrideFormNode.children("field")) {
                mergeFieldNode(baseFormNode, overrideFieldNode, deepCopy)
            }
        }

        if (overrideFormNode.hasChild("field-layout")) {
            // just use entire override field-layout, don't try to merge
            baseFormNode.remove("field-layout")
            baseFormNode.append(overrideFormNode.first("field-layout").deepCopy(null))
        }
        if (overrideFormNode.hasChild("form-list-column")) {
            // if there are any form-list-column remove all from base and copy all from override
            baseFormNode.remove("form-list-column")
            for (MNode flcNode in overrideFormNode.children("form-list-column")) baseFormNode.append(flcNode.deepCopy(null))
        }
    }

    protected static void mergeFieldNode(MNode baseFormNode, MNode overrideFieldNode, boolean deepCopy) {
        int baseFieldIndex = baseFormNode.firstIndex({ MNode it -> "field".equals(it.name) && it.attribute("name") == overrideFieldNode.attribute("name") })
        if (baseFieldIndex >= 0) {
            MNode baseFieldNode = baseFormNode.child(baseFieldIndex)
            baseFieldNode.attributes.putAll(overrideFieldNode.attributes)

            baseFieldNode.mergeSingleChild(overrideFieldNode, "header-field")
            baseFieldNode.mergeChildrenByKey(overrideFieldNode, "conditional-field", "condition", null)
            baseFieldNode.mergeSingleChild(overrideFieldNode, "default-field")

            // put new node where old was
            baseFormNode.remove(baseFieldIndex)
            baseFormNode.append(baseFieldNode, baseFieldIndex)
        } else {
            baseFormNode.append(deepCopy ? overrideFieldNode.deepCopy(null) : overrideFieldNode)
            // this is a new field... if the form has a field-layout element add a reference under that too
            if (baseFormNode.hasChild("field-layout")) addFieldToFieldLayout(baseFormNode, overrideFieldNode)
        }
    }

    static void addFieldToFieldLayout(MNode formNode, MNode fieldNode) {
        MNode fieldLayoutNode = formNode.first("field-layout")
        Integer layoutSequenceNum = fieldNode.attribute("layoutSequenceNum") as Integer
        if (layoutSequenceNum == null) {
            fieldLayoutNode.append("field-ref", [name:fieldNode.attribute("name")])
        } else {
            formNode.remove("field-layout")
            MNode newFieldLayoutNode = formNode.append("field-layout", fieldLayoutNode.attributes)
            int index = 0
            boolean addedNode = false
            for (MNode child in fieldLayoutNode.children) {
                if (index == layoutSequenceNum) {
                    newFieldLayoutNode.append("field-ref", [name:fieldNode.attribute("name")])
                    addedNode = true
                }
                newFieldLayoutNode.append(child)
                index++
            }
            if (!addedNode) {
                newFieldLayoutNode.append("field-ref", [name:fieldNode.attribute("name")])
            }
        }
    }

    static LinkedHashMap<String, String> getFieldOptions(MNode widgetNode, ExecutionContext ec) {
        MNode fieldNode = widgetNode.parent.parent
        LinkedHashMap<String, String> options = new LinkedHashMap<>()
        ArrayList<MNode> widgetChildren = widgetNode.children
        int widgetChildrenSize = widgetChildren.size()
        for (int wci = 0; wci < widgetChildrenSize; wci++) {
            MNode childNode = (MNode) widgetChildren.get(wci)
            if ("entity-options".equals(childNode.name)) {
                MNode entityFindNode = childNode.first("entity-find")
                EntityFindImpl ef = (EntityFindImpl) ec.entity.find(entityFindNode.attribute('entity-name'))
                ef.findNode(entityFindNode)

                EntityList eli = ef.list()

                if (ef.shouldCache()) {
                    // do the date filtering after the query
                    ArrayList<MNode> dateFilterList = entityFindNode.children("date-filter")
                    int dateFilterListSize = dateFilterList.size()
                    for (int k = 0; k < dateFilterListSize; k++) {
                        MNode df = (MNode) dateFilterList.get(k)
                        EntityCondition dateEc = ec.entity.conditionFactory.makeConditionDate(df.attribute("from-field-name") ?: "fromDate",
                                df.attribute("thru-field-name") ?: "thruDate",
                                (df.attribute("valid-date") ? ec.resource.expression(df.attribute("valid-date"), null) as Timestamp : ec.user.nowTimestamp))
                        // logger.warn("TOREMOVE getFieldOptions cache=${ef.getUseCache()}, dateEc=${dateEc} list before=${eli}")
                        eli = eli.filterByCondition(dateEc, true)
                    }
                }

                int eliSize = eli.size()
                for (int i = 0; i < eliSize; i++) {
                    EntityValue ev = (EntityValue) eli.get(i)
                    addFieldOption(options, fieldNode, childNode, ev, ec)
                }
            } else if ("list-options".equals(childNode.name)) {
                Object listObject = ec.resource.expression(childNode.attribute('list'), null)
                if (listObject instanceof EntityListIterator) {
                    EntityListIterator eli
                    try {
                        eli = (EntityListIterator) listObject
                        EntityValue ev
                        while ((ev = eli.next()) != null) addFieldOption(options, fieldNode, childNode, ev, ec)
                    } finally {
                        eli.close()
                    }
                } else {
                    for (Object listOption in listObject) {
                        if (listOption instanceof Map) {
                            addFieldOption(options, fieldNode, childNode, (Map) listOption, ec)
                        } else {
                            String loString = StupidJavaUtilities.toPlainString(listOption)
                            if (loString != null) options.put(loString, loString)
                            // addFieldOption(options, fieldNode, childNode, [entry:listOption], ec)
                        }
                    }
                }
            } else if ("option".equals(childNode.name)) {
                String key = ec.resource.expand(childNode.attribute('key'), null)
                String text = ec.resource.expand(childNode.attribute('text'), null)
                options.put(key, text ?: key)
            }
        }
        return options
    }

    static void addFieldOption(LinkedHashMap<String, String> options, MNode fieldNode, MNode childNode, Map listOption,
                               ExecutionContext ec) {
        EntityValueBase listOptionEvb = listOption instanceof EntityValueBase ? listOption : null
        if (listOptionEvb != null) {
            ec.context.push(listOptionEvb.getMap())
        } else {
            ec.context.push(listOption)
        }
        try {
            String key = null
            String keyAttr = childNode.attribute('key')
            if (keyAttr != null && keyAttr.length() > 0) {
                key = ec.resource.expand(keyAttr, null)
                // we just did a string expand, if it evaluates to a literal "null" then there was no value
                if (key == "null") key = null
            } else if (listOptionEvb != null) {
                String keyFieldName = listOptionEvb.getEntityDefinition().getPkFieldNames().get(0)
                if (keyFieldName != null && keyFieldName.length() > 0) key = ec.context.getByString(keyFieldName)
            }
            if (key == null) key = ec.context.getByString(fieldNode.attribute('name'))
            if (key == null) return

            String text = childNode.attribute('text')
            if (text == null || text.length() == 0) {
                if (listOptionEvb == null || listOptionEvb.getEntityDefinition().isField("description")) {
                    Object desc = listOption.get("description")
                    options.put(key, desc != null ? (String) desc : key)
                } else {
                    options.put(key, key)
                }
            } else {
                String value = ec.resource.expand(text, null)
                if ("null".equals(value)) value = key
                options.put(key, value)
            }
        } finally {
            ec.context.pop()
        }
    }


    // ========== FormInstance Class/etc ==========

    FormInstance getFormInstance() {
        if (isDynamic || hasDbExtensions || isDisplayOnly()) {
            return new FormInstance()
        } else {
            if (internalFormInstance == null) internalFormInstance = new FormInstance()
            return internalFormInstance
        }
    }

    class FormInstance {
        private MNode formNode
        private FtlNodeWrapper ftlFormNode
        private boolean isListForm

        private ArrayList<MNode> allFieldNodes
        private Map<String, MNode> fieldNodeMap = new LinkedHashMap<>()
        private Map<String, FtlNodeWrapper> fieldFtlNodeMap = new LinkedHashMap<>()

        private boolean isUploadForm = false
        private boolean isFormHeaderFormVal = false
        private ArrayList<FtlNodeWrapper> nonReferencedFieldList = (ArrayList<FtlNodeWrapper>) null
        private ArrayList<FtlNodeWrapper> hiddenFieldList = (ArrayList<FtlNodeWrapper>) null
        private ArrayList<ArrayList<FtlNodeWrapper>> formListColInfoList = (ArrayList<ArrayList<FtlNodeWrapper>>) null
        private Set<String> fieldsInFormListColumns = (Set<String>) null

        FormInstance() {
            formNode = getOrCreateFormNode()
            ftlFormNode = FtlNodeWrapper.wrapNode(formNode)
            isListForm = formNode.getName() == "form-list"

            // populate fieldNodeMap
            allFieldNodes = formNode.children("field")
            int afnSize = allFieldNodes.size()
            for (int i = 0; i < afnSize; i++) {
                MNode fieldNode = (MNode) allFieldNodes.get(i)
                String fieldName = fieldNode.attribute("name")
                fieldNodeMap.put(fieldName, fieldNode)
                fieldFtlNodeMap.put(fieldName, FtlNodeWrapper.wrapNode(fieldNode))
            }

            isUploadForm = formNode.depthFirst({ MNode it -> it.name == "file" }).size() > 0
            for (MNode hfNode in formNode.depthFirst({ MNode it -> it.name == "header-field" })) {
                if (hfNode.children.size() > 0) {
                    isFormHeaderFormVal = true
                    break
                }
            }

            if (isListForm) {
                // also populates fieldsInFormListColumns
                formListColInfoList = makeFormListColumnInfo()
            }
        }
        // MNode getFormMNode() { return formNode }
        FtlNodeWrapper getFtlFormNode() { return ftlFormNode }
        FtlNodeWrapper getFtlFieldNode(String fieldName) { return fieldFtlNodeMap.get(fieldName) }

        boolean isUpload() { return isUploadForm }
        boolean isHeaderForm() { return isFormHeaderFormVal }
        String getFormLocation() { return location }

        MNode getFieldValidateNode(String fieldName) {
            MNode fieldNode = (MNode) fieldNodeMap.get(fieldName)
            if (fieldNode == null) throw new IllegalArgumentException("Tried to get in-parameter node for field [${fieldName}] that doesn't exist in form [${location}]")
            String validateService = fieldNode.attribute('validate-service')
            String validateEntity = fieldNode.attribute('validate-entity')
            if (validateService) {
                ServiceDefinition sd = ecfi.serviceFacade.getServiceDefinition(validateService)
                if (sd == null) throw new IllegalArgumentException("Invalid validate-service name [${validateService}] in field [${fieldName}] of form [${location}]")
                MNode parameterNode = sd.getInParameter((String) fieldNode.attribute('validate-parameter') ?: fieldName)
                return parameterNode
            } else if (validateEntity) {
                EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(validateEntity)
                if (ed == null) throw new IllegalArgumentException("Invalid validate-entity name [${validateEntity}] in field [${fieldName}] of form [${location}]")
                MNode efNode = ed.getFieldNode((String) fieldNode.attribute('validate-field') ?: fieldName)
                return efNode
            }
            return null
        }

        ArrayList<FtlNodeWrapper> getFieldLayoutNonReferencedFieldList() {
            if (nonReferencedFieldList != null) return nonReferencedFieldList
            ArrayList<FtlNodeWrapper> fieldList = new ArrayList<>()

            if (formNode.hasChild("field-layout")) for (MNode fieldNode in formNode.children("field")) {
                MNode fieldLayoutNode = formNode.first("field-layout")
                String fieldName = fieldNode.attribute("name")
                if (!fieldLayoutNode.depthFirst({ MNode it -> it.name == "field-ref" && it.attribute("name") == fieldName }))
                    fieldList.add(fieldFtlNodeMap.get(fieldName))
            }

            nonReferencedFieldList = fieldList
            return fieldList
        }

        boolean isHeaderSubmitField(MNode fieldNode) {
            MNode headerField = fieldNode.first("header-field")
            if (headerField == null) return false
            return headerField.hasChild("submit")
        }
        boolean isListFieldHidden(MNode fieldNode) {
            if (isListFieldHiddenAttr(fieldNode)) return true
            return isListFieldHiddenWidget(fieldNode)
        }

        private boolean isListFieldHiddenAttr(MNode fieldNode) {
            String hideAttr = fieldNode.attribute("hide")
            if (hideAttr != null && hideAttr.length() > 0) {
                return ecfi.getEci().resource.condition(hideAttr, "")
            }
            return false
        }

        private boolean isListFieldHiddenWidget(MNode fieldNode) {
            // if default-field or any conditional-field don't have hidden or ignored elements then it's not hidden
            MNode defaultField = fieldNode.first("default-field")
            if (defaultField != null && !defaultField.hasChild("hidden") && !defaultField.hasChild("ignored")) return false
            List<MNode> condFieldList = fieldNode.children("conditional-field")
            for (MNode condField in condFieldList) if (!condField.hasChild("hidden") && !condField.hasChild("ignored")) return false
            return true
        }

        ArrayList<FtlNodeWrapper> getListHiddenFieldList() {
            if (hiddenFieldList != null) return hiddenFieldList

            ArrayList<FtlNodeWrapper> fieldList = new ArrayList<>()
            int afnSize = allFieldNodes.size()
            for (int i = 0; i < afnSize; i++) {
                MNode fieldNode = (MNode) allFieldNodes.get(i)
                if (isListFieldHiddenWidget(fieldNode) && !isListFieldHiddenAttr(fieldNode)) fieldList.add(FtlNodeWrapper.wrapNode(fieldNode))
            }

            hiddenFieldList = fieldList
            return fieldList
        }

        boolean hasFormListColumns() { return formNode.children("form-list-column").size() > 0 }

        String getUserActiveFormConfigId(ExecutionContext ec) {
            EntityValue fcu = ecfi.entityFacade.find("moqui.screen.form.FormConfigUser")
                    .condition("userId", ec.user.userId).condition("formLocation", location).useCache(true).one()
            if (fcu != null) return (String) fcu.getNoCheckSimple("formConfigId")

            // Maybe not do this at all and let it be a future thing where the user selects an active one from options available through groups
            EntityList fcugvList = ecfi.entityFacade.find("moqui.screen.form.FormConfigUserGroupView")
                    .condition("userGroupId", EntityCondition.IN, ec.user.userGroupIdSet)
                    .condition("formLocation", location).useCache(true).list()
            if (fcugvList.size() > 0) {
                // FUTURE: somehow make a better choice than just the first? see note above too...
                return (String) fcugvList.get(0).getNoCheckSimple("formConfigId")
            }

            return null
        }

        ArrayList<Integer> getFormListColumnCharWidths(ArrayList<ArrayList<FtlNodeWrapper>> formListColumnInfo, int originalLineWidth) {
            int numCols = formListColumnInfo.size()
            ArrayList<Integer> charWidths = new ArrayList<>(numCols)
            for (int i = 0; i < numCols; i++) charWidths.add(null)
            if (originalLineWidth == 0) originalLineWidth = 132
            int lineWidth = originalLineWidth;
            // leave room for 1 space between each column
            lineWidth -= (numCols - 1)

            // set fixed column widths and get a total of fixed columns, remaining characters to be split among percent width cols
            ArrayList<BigDecimal> percentWidths = new ArrayList<>(numCols)
            for (int i = 0; i < numCols; i++) percentWidths.add(null)
            int fixedColsWidth = 0
            int fixedColsCount = 0
            for (int i = 0; i < numCols; i++) {
                ArrayList<FtlNodeWrapper> colNodes = (ArrayList<FtlNodeWrapper>) formListColumnInfo.get(i)
                int charWidth = -1
                BigDecimal percentWidth = null
                for (int j = 0; j < colNodes.size(); j++) {
                    FtlNodeWrapper fieldFtlNode = (FtlNodeWrapper) colNodes.get(j)
                    MNode fieldNode = fieldFtlNode.getMNode()
                    String pwAttr = fieldNode.attribute("print-width")
                    if (pwAttr == null || pwAttr.isEmpty()) continue
                    BigDecimal curWidth = new BigDecimal(pwAttr)
                    if (curWidth == BigDecimal.ZERO) {
                        charWidth = 0
                        // no separator char needed for columns not displayed so add back to lineWidth
                        lineWidth++
                        continue
                    }
                    if ("characters".equals(fieldNode.attribute("print-width-type"))) {
                        if (curWidth.intValue() > charWidth) charWidth = curWidth.intValue()
                    } else {
                        if (percentWidth == null || curWidth > percentWidth) percentWidth = curWidth
                    }
                }
                if (charWidth >= 0) {
                    if (percentWidth != null) {
                        // if we have char and percent widths, calculate effective chars of percent width and if greater use that
                        int percentChars = ((percentWidth / 100) * lineWidth).intValue()
                        if (percentChars < charWidth) {
                            charWidths.set(i, charWidth)
                            fixedColsWidth += charWidth
                            fixedColsCount++
                        } else {
                            percentWidths.set(i, percentWidth)
                        }
                    } else {
                        charWidths.set(i, charWidth)
                        fixedColsWidth += charWidth
                        fixedColsCount++
                    }
                } else {
                    if (percentWidth != null) percentWidths.set(i, percentWidth)
                }
            }

            // now we have all fixed widths, calculate and set percent widths
            int widthForPercentCols = lineWidth - fixedColsWidth
            if (widthForPercentCols < 0) throw new IllegalArgumentException("In form ${formName} fixed width columns exceeded total line characters ${originalLineWidth} by ${-widthForPercentCols} characters")
            int percentColsCount = numCols - fixedColsCount

            // scale column percents to 100, fill in missing
            BigDecimal percentTotal = 0
            for (int i = 0; i < numCols; i++) {
                BigDecimal colPercent = (BigDecimal) percentWidths.get(i)
                if (colPercent == null) {
                    if (charWidths.get(i) != null) continue
                    BigDecimal percentWidth = (1 / percentColsCount) * 100
                    percentWidths.set(i, percentWidth)
                    percentTotal += percentWidth
                } else {
                    percentTotal += colPercent
                }
            }
            int percentColsUsed = 0
            BigDecimal percentScale = 100 / percentTotal
            for (int i = 0; i < numCols; i++) {
                BigDecimal colPercent = (BigDecimal) percentWidths.get(i)
                if (colPercent == null) continue
                BigDecimal actualPercent = colPercent * percentScale
                percentWidths.set(i, actualPercent)
                int percentChars = ((actualPercent / 100.0) * widthForPercentCols).setScale(0, RoundingMode.HALF_EVEN).intValue()
                charWidths.set(i, percentChars)
                percentColsUsed += percentChars
            }

            // adjust for over/underflow
            if (percentColsUsed != widthForPercentCols) {
                int diffRemaining = widthForPercentCols - percentColsUsed
                int diffPerCol = (diffRemaining / percentColsCount).setScale(0, RoundingMode.UP).intValue()
                for (int i = 0; i < numCols; i++) {
                    if (percentWidths.get(i) == null) continue
                    Integer curChars = charWidths.get(i)
                    int adjustAmount = Math.abs(diffRemaining) > Math.abs(diffPerCol) ? diffPerCol : diffRemaining
                    int newChars = curChars + adjustAmount
                    if (newChars > 0) {
                        charWidths.set(i, newChars)
                        diffRemaining -= adjustAmount
                        if (diffRemaining == 0) break
                    }
                }
            }

            logger.info("Text mode form-list: numCols=${numCols}, percentColsUsed=${percentColsUsed}, widthForPercentCols=${widthForPercentCols}, percentColsCount=${percentColsCount}\npercentWidths: ${percentWidths}\ncharWidths: ${charWidths}")
            return charWidths
        }

        ArrayList<ArrayList<FtlNodeWrapper>> getFormListColumnInfo() {
            ExecutionContextImpl eci = ecfi.getEci()
            String formConfigId = getUserActiveFormConfigId(eci)
            if (formConfigId) {
                // don't remember the results of this, is per-user so good only once (FormInstance is NOT per user!)
                return makeDbFormListColumnInfo(formConfigId, eci)
            }
            return formListColInfoList
        }
        /** convert form-list-column elements into a list, if there are no form-list-column elements uses fields limiting
         *    by logic about what actually gets rendered (so result can be used for display regardless of form def) */
        private ArrayList<ArrayList<FtlNodeWrapper>> makeFormListColumnInfo() {
            ArrayList<MNode> formListColumnList = formNode.children("form-list-column")
            int flcListSize = formListColumnList != null ? formListColumnList.size() : 0

            ArrayList<ArrayList<FtlNodeWrapper>> colInfoList = new ArrayList<>()
            Set<String> tempFieldsInFormListColumns = new HashSet()

            if (flcListSize > 0) {
                // populate fields under columns
                for (int ci = 0; ci < flcListSize; ci++) {
                    MNode flcNode = (MNode) formListColumnList.get(ci)
                    ArrayList<FtlNodeWrapper> colFieldNodes = new ArrayList<>()
                    ArrayList<MNode> fieldRefNodes = flcNode.children("field-ref")
                    int fieldRefSize = fieldRefNodes.size()
                    for (int fi = 0; fi < fieldRefSize; fi++) {
                        MNode frNode = (MNode) fieldRefNodes.get(fi)
                        String fieldName = frNode.attribute("name")
                        MNode fieldNode = (MNode) fieldNodeMap.get(fieldName)
                        if (fieldNode == null) throw new IllegalArgumentException("Could not find field ${fieldName} referenced in form-list-column.field-ref in form at ${location}")
                        // skip hidden fields, they are handled separately
                        if (isListFieldHidden(fieldNode)) continue

                        tempFieldsInFormListColumns.add(fieldName)
                        colFieldNodes.add(FtlNodeWrapper.wrapNode(fieldNode))
                    }
                    if (colFieldNodes.size() > 0) colInfoList.add(colFieldNodes)
                }
            } else {
                // create a column for each displayed field
                int afnSize = allFieldNodes.size()
                for (int i = 0; i < afnSize; i++) {
                    MNode fieldNode = (MNode) allFieldNodes.get(i)
                    // skip hidden fields, they are handled separately
                    if (isListFieldHidden(fieldNode)) continue

                    ArrayList<FtlNodeWrapper> singleFieldColList = new ArrayList<>()
                    tempFieldsInFormListColumns.add(fieldNode.attribute("name"))
                    singleFieldColList.add(FtlNodeWrapper.wrapNode(fieldNode))
                    colInfoList.add(singleFieldColList)
                }
            }

            fieldsInFormListColumns = tempFieldsInFormListColumns
            return colInfoList
        }
        private ArrayList<ArrayList<FtlNodeWrapper>> makeDbFormListColumnInfo(String formConfigId, ExecutionContextImpl eci) {
            EntityList formConfigFieldList = ecfi.entityFacade.find("moqui.screen.form.FormConfigField")
                    .condition("formConfigId", formConfigId).orderBy("positionIndex").orderBy("positionSequence").useCache(true).list()

            // NOTE: calling code checks to see if this is not empty
            int fcfListSize = formConfigFieldList.size()

            ArrayList<ArrayList<FtlNodeWrapper>> colInfoList = new ArrayList<>()
            Set<String> tempFieldsInFormListColumns = new HashSet()

            // populate fields under columns
            int curColIndex = -1;
            ArrayList<FtlNodeWrapper> colFieldNodes = null
            for (int ci = 0; ci < fcfListSize; ci++) {
                EntityValue fcfValue = (EntityValue) formConfigFieldList.get(ci)
                int columnIndex = fcfValue.getNoCheckSimple("positionIndex") as int
                if (columnIndex > curColIndex) {
                    if (colFieldNodes != null && colFieldNodes.size() > 0) colInfoList.add(colFieldNodes)
                    curColIndex = columnIndex
                    colFieldNodes = new ArrayList<>()
                }
                String fieldName = (String) fcfValue.getNoCheckSimple("fieldName")
                MNode fieldNode = (MNode) fieldNodeMap.get(fieldName)
                if (fieldNode == null) throw new IllegalArgumentException("Could not find field ${fieldName} referenced in FormConfigField record for ID ${fcfValue.formConfigId} user ${eci.user.userId}, form at ${location}")
                // skip hidden fields, they are handled separately
                if (isListFieldHidden(fieldNode)) continue

                tempFieldsInFormListColumns.add(fieldName)
                colFieldNodes.add(FtlNodeWrapper.wrapNode(fieldNode))
            }
            // Add the final field (if defined)
            if (colFieldNodes != null && colFieldNodes.size() > 0) colInfoList.add(colFieldNodes)

            fieldsInFormListColumns = tempFieldsInFormListColumns
            return colInfoList
        }

        /** Call this after getFormListColumnInfo() so fieldsInFormListColumns will be populated */
        ArrayList<FtlNodeWrapper> getFieldsNotReferencedInFormListColumn() {
            if (fieldsInFormListColumns == null) makeFormListColumnInfo()

            ArrayList<FtlNodeWrapper> colFieldNodes = new ArrayList<>()
            int afnSize = allFieldNodes.size()
            for (int i = 0; i < afnSize; i++) {
                MNode fieldNode = (MNode) allFieldNodes.get(i)
                // skip hidden fields, they are handled separately
                if (isListFieldHidden(fieldNode)) continue
                String fieldName = fieldNode.attribute("name")
                if (!fieldsInFormListColumns.contains(fieldName))
                    colFieldNodes.add(fieldFtlNodeMap.get(fieldName))
            }

            return colFieldNodes
        }

        String getFieldValidationClasses(String fieldName) {
            MNode validateNode = getFieldValidateNode(fieldName)
            if (validateNode == null) return ""

            Set<String> vcs = new HashSet()
            if (validateNode.name == "parameter") {
                MNode parameterNode = validateNode
                if (parameterNode.attribute('required') == "true") vcs.add("required")
                if (parameterNode.hasChild("number-integer")) vcs.add("number")
                if (parameterNode.hasChild("number-decimal")) vcs.add("number")
                if (parameterNode.hasChild("text-email")) vcs.add("email")
                if (parameterNode.hasChild("text-url")) vcs.add("url")
                if (parameterNode.hasChild("text-digits")) vcs.add("digits")
                if (parameterNode.hasChild("credit-card")) vcs.add("creditcard")

                String type = parameterNode.attribute('type')
                if (type && (type.endsWith("BigDecimal") || type.endsWith("BigInteger") || type.endsWith("Long") ||
                        type.endsWith("Integer") || type.endsWith("Double") || type.endsWith("Float") ||
                        type.endsWith("Number"))) vcs.add("number")
            } else if (validateNode.name == "field") {
                MNode fieldNode = validateNode
                String type = fieldNode.attribute('type')
                if (type && (type.startsWith("number-") || type.startsWith("currency-"))) vcs.add("number")
                // bad idea, for create forms with optional PK messes it up: if (fieldNode."@is-pk" == "true") vcs.add("required")
            }

            StringBuilder sb = new StringBuilder()
            for (String vc in vcs) { if (sb) sb.append(" "); sb.append(vc); }
            return sb.toString()
        }

        Map getFieldValidationRegexpInfo(String fieldName) {
            MNode validateNode = getFieldValidateNode(fieldName)
            if (validateNode?.hasChild("matches")) {
                MNode matchesNode = validateNode.first("matches")
                return [regexp:matchesNode.attribute('regexp'), message:matchesNode.attribute('message')]
            }
            return null
        }

        String getOrderByActualJsString(String originalOrderBy) {
            if (originalOrderBy == null || originalOrderBy.length() == 0) return "";
            // strip square braces if there are any
            if (originalOrderBy.startsWith("[")) originalOrderBy = originalOrderBy.substring(1, originalOrderBy.length() - 1)
            originalOrderBy = originalOrderBy.replace(" ", "")
            List<String> orderByList = Arrays.asList(originalOrderBy.split(","))
            StringBuilder sb = new StringBuilder()
            for (String obf in orderByList) {
                if (sb.length() > 0) sb.append(",")
                EntityJavaUtil.FieldOrderOptions foo = EntityJavaUtil.makeFieldOrderOptions(obf)
                MNode curFieldNode = fieldNodeMap.get(foo.getFieldName())
                if (curFieldNode == null) continue
                MNode headerFieldNode = curFieldNode.first("header-field")
                if (headerFieldNode == null) continue
                String showOrderBy = headerFieldNode.attribute("show-order-by")
                sb.append("'").append(foo.descending ? "-" : "+")
                if ("case-insensitive".equals(showOrderBy)) sb.append("^")
                sb.append(foo.getFieldName()).append("'")
            }
            if (sb.length() == 0) return ""
            return "[" + sb.toString() + "]"
        }

        void runFormListRowActions(ScreenRenderImpl sri, Object listEntry, int index, boolean hasNext) {
            // NOTE: this runs in a pushed-/sub-context, so just drop it in and it'll get cleaned up automatically
            String listEntryStr = formNode.attribute('list-entry')
            ExecutionContextImpl eci = sri.ec
            ContextStack context = eci.contextStack
            if (listEntryStr) {
                context.put(listEntryStr, listEntry)
                context.put(listEntryStr + "_index", index)
                context.put(listEntryStr + "_has_next", hasNext)
            } else {
                if (listEntry instanceof EntityValueBase) {
                    context.putAll(((EntityValueBase) listEntry).getValueMap())
                } else if (listEntry instanceof Map) {
                    context.putAll((Map) listEntry)
                } else {
                    context.put("listEntry", listEntry)
                }
                String listStr = formNode.attribute('list')
                context.put(listStr + "_index", index)
                context.put(listStr + "_has_next", hasNext)
                context.put(listStr + "_entry", listEntry)
            }
            if (rowActions != null) rowActions.run(eci)
        }

        ArrayList<EntityValue> makeFormListFindFields(String formListFindId, ExecutionContext ec) {
            ContextStack cs = ec.context
            List<EntityValue> valueList = new ArrayList<>()
            for (MNode fieldNode in allFieldNodes) {
                // skip submit
                if (isHeaderSubmitField(fieldNode)) continue

                String fn = fieldNode.attribute("name")

                if (cs.containsKey(fn) || cs.containsKey(fn + "_op")) {
                    // this will handle text-line, text-find, etc
                    Object value = cs.get(fn)
                    if (value != null && StupidJavaUtilities.isEmpty(value)) value = null
                    String op = cs.get(fn + "_op") ?: "equals"
                    boolean not = (cs.get(fn + "_not") == "Y" || cs.get(fn + "_not") == "true")
                    boolean ic = (cs.get(fn + "_ic") == "Y" || cs.get(fn + "_ic") == "true")

                    // for all operators other than empty skip this if there is no value
                    if (value == null && op != "empty") continue

                    EntityValue ev = ec.entity.makeValue("moqui.screen.form.FormListFindField")
                    ev.formListFindId = formListFindId
                    ev.fieldName = fn
                    ev.fieldValue = value
                    ev.fieldOperator = op
                    ev.fieldNot = not ? "Y" : "N"
                    ev.fieldIgnoreCase = ic ? "Y" : "N"
                    valueList.add(ev)
                } else if (cs.get(fn + "_period")) {
                    EntityValue ev = ec.entity.makeValue("moqui.screen.form.FormListFindField")
                    ev.formListFindId = formListFindId
                    ev.fieldName = fn
                    ev.fieldPeriod = cs.get(fn + "_period")
                    ev.fieldPerOffset = (cs.get(fn + "_poffset") ?: "0") as Long
                    valueList.add(ev)
                } else {
                    // these will handle range-find and date-find
                    String fromValue = StupidJavaUtilities.toPlainString(cs.get(fn + "_from"))
                    String thruValue = StupidJavaUtilities.toPlainString(cs.get(fn + "_thru"))
                    if (fromValue || thruValue) {
                        EntityValue ev = ec.entity.makeValue("moqui.screen.form.FormListFindField")
                        ev.formListFindId = formListFindId
                        ev.fieldName = fn
                        ev.fieldFrom = fromValue
                        ev.fieldThru = thruValue
                        valueList.add(ev)
                    }
                }
            }
            /* always look for an orderByField parameter too
            String orderByString = cs?.get("orderByField") ?: defaultOrderBy
            if (orderByString != null && orderByString.length() > 0) {
                ec.context.put("orderByField", orderByString)
                this.orderBy(orderByString)
            }
            */
            return valueList
        }

        List<Map<String, Object>> getUserFormListFinds(ExecutionContext ec) {
            EntityList flfuList = ec.entity.find("moqui.screen.form.FormListFindUser")
                    .condition("userId", ec.user.userId).useCache(true).list()
            EntityList flfugList = ec.entity.find("moqui.screen.form.FormListFindUserGroup")
                    .condition("userGroupId", EntityCondition.IN, ec.user.userGroupIdSet).useCache(true).list()
            Set<String> userOnlyFlfIdSet = new HashSet<>()
            Set<String> formListFindIdSet = new HashSet<>()
            for (EntityValue ev in flfuList) {
                userOnlyFlfIdSet.add((String) ev.formListFindId)
                formListFindIdSet.add((String) ev.formListFindId)
            }
            for (EntityValue ev in flfugList) formListFindIdSet.add((String) ev.formListFindId)


            // get info for each formListFindId
            List<Map<String, Object>> flfInfoList = new LinkedList<>()
            for (String formListFindId in formListFindIdSet)
                flfInfoList.add(getFormListFindInfo(formListFindId, ec, userOnlyFlfIdSet))

            // sort by description
            StupidUtilities.orderMapList(flfInfoList, ["description"])

            return flfInfoList
        }

        EntityValue getActiveFormListFind(ExecutionContextImpl ec) {
            if (ec.web == null) return null
            String formListFindId = ec.web.requestParameters.get("formListFindId")
            if (!formListFindId) return null
            return ec.entity.find("moqui.screen.form.FormListFind").condition("formListFindId", formListFindId).useCache(true).one()
        }
    }

    static Map<String, String> makeFormListFindParameters(String formListFindId, ExecutionContext ec) {
        EntityList flffList = ec.entity.find("moqui.screen.form.FormListFindField")
                .condition("formListFindId", formListFindId).useCache(true).list()

        Map<String, String> parmMap = new LinkedHashMap<>()
        parmMap.put("formListFindId", formListFindId)

        int flffSize = flffList.size()
        for (int i = 0; i < flffSize; i++) {
            EntityValue flff = (EntityValue) flffList.get(i)
            String fn = flff.fieldName
            if (flff.fieldValue) {
                parmMap.put(fn, (String) flff.fieldValue)
                String op = (String) flff.fieldOperator
                if (op && !"equals".equals(op)) parmMap.put(fn + "_op", op)
                String not = (String) flff.fieldNot
                if ("Y".equals(not)) parmMap.put(fn + "_not", "Y")
                String ic = (String) flff.fieldIgnoreCase
                if ("Y".equals(ic)) parmMap.put(fn + "_ic", "Y")
            } else if (flff.fieldPeriod) {
                parmMap.put(fn + "_period", (String) flff.fieldPeriod)
                parmMap.put(fn + "_poffset", flff.fieldPerOffset as String)
            } else if (flff.fieldFrom || flff.fieldThru) {
                if (flff.fieldFrom) parmMap.put(fn + "_from", (String) flff.fieldFrom)
                if (flff.fieldThru) parmMap.put(fn + "_thru", (String) flff.fieldThru)
            }
        }
        return parmMap
    }

    static Map<String, Object> getFormListFindInfo(String formListFindId, ExecutionContext ec, Set<String> userOnlyFlfIdSet) {
        EntityValue formListFind = ec.entity.find("moqui.screen.form.FormListFind")
                .condition("formListFindId", formListFindId).useCache(true).one()
        Map<String, String> flfParameters = makeFormListFindParameters(formListFindId, ec)
        flfParameters.put("formListFindId", formListFindId)
        if (formListFind.orderByField) flfParameters.put("orderByField", (String) formListFind.orderByField)
        return [description:formListFind.description, formListFind:formListFind, findParameters:flfParameters,
                isByUserId:userOnlyFlfIdSet?.contains(formListFindId) ? "true" : "false"]
    }

    static String processFormSavedFind(ExecutionContextImpl ec) {
        String userId = ec.userFacade.userId
        ContextStack cs = ec.contextStack

        String formListFindId = (String) cs.formListFindId
        EntityValue flf = formListFindId ? ec.entity.find("moqui.screen.form.FormListFind")
                .condition("formListFindId", formListFindId).useCache(false).one() : null

        boolean isDelete = cs.containsKey("DeleteFind")

        if (isDelete) {
            if (flf == null) { ec.messageFacade.addError("Saved find with ID ${formListFindId} not found, not deleting"); return null; }

            // delete FormListFindUser record; if there are no other FormListFindUser records or FormListFindUserGroup
            //     records, delete the FormListFind
            EntityValue flfu = ec.entity.find("moqui.screen.form.FormListFindUser").condition("userId", userId)
                    .condition("formListFindId", formListFindId).useCache(false).one()
            // NOTE: if no FormListFindUser nothing to delete... consider removing form from all groups the user is in?
            if (flfu == null) return null
            flfu.delete()

            long userCount = ec.entity.find("moqui.screen.form.FormListFindUser")
                    .condition("formListFindId", formListFindId).useCache(false).count()
            if (userCount == 0L) {
                long groupCount = ec.entity.find("moqui.screen.form.FormListFindUserGroup")
                        .condition("formListFindId", formListFindId).useCache(false).count()
                if (groupCount == 0L) {
                    ec.entity.find("moqui.screen.form.FormListFindField")
                            .condition("formListFindId", formListFindId).deleteAll()
                    ec.entity.find("moqui.screen.form.FormListFind")
                            .condition("formListFindId", formListFindId).deleteAll()
                }
            }
            return null
        }

        String formLocation = cs.formLocation
        if (!formLocation) { ec.message.addError("No form location specified, cannot process saved find"); return null; }
        int lastDotIndex = formLocation.lastIndexOf(".")
        if (lastDotIndex < 0) { ec.message.addError("Form location invalid, cannot process saved find"); return null; }
        String screenLocation = formLocation.substring(0, lastDotIndex)
        int lastDollarIndex = formLocation.lastIndexOf('$')
        if (lastDollarIndex < 0) { ec.message.addError("Form location invalid, cannot process saved find"); return null; }
        String formName = formLocation.substring(lastDollarIndex + 1)

        ScreenDefinition screenDef = ec.screenFacade.getScreenDefinition(screenLocation)
        if (screenDef == null) { ec.message.addError("Screen not found at ${screenLocation}, cannot process saved find"); return null; }
        ScreenForm screenForm = screenDef.getForm(formName)
        if (screenForm == null) { ec.message.addError("Form ${formName} not found in screen at ${screenLocation}, cannot process saved find"); return null; }
        FormInstance formInstance = screenForm.getFormInstance()

        // see if there is an existing FormConfig record
        if (flf != null) {
            // make sure the FormListFind.formLocation matches the current formLocation
            if (formLocation != flf.formLocation) {
                ec.message.addError("Specified form location did not match form on Saved Find ${formListFindId}, not updating")
                return null
            }

            // make sure the user or group the user is in is associated with the FormListFind
            EntityValue flfu = ec.entity.find("moqui.screen.form.FormListFindUser").condition("userId", userId)
                    .condition("formListFindId", formListFindId).useCache(false).one()
            if (flfu == null) {
                long groupCount = ec.entity.find("moqui.screen.form.FormListFindUserGroup")
                        .condition("userGroupId", EntityCondition.IN, ec.user.userGroupIdSet)
                        .condition("formListFindId", formListFindId).useCache(false).count()
                if (groupCount == 0L) {
                    ec.message.addError("You are not associated with Saved Find ${formListFindId}, cannot update")
                    return formListFindId
                }
            }

            if (cs.description) flf.description = cs.description
            if (cs.orderByField) flf.orderByField = cs.orderByField
            if (flf.isModified()) flf.update()

            // remove all FormListFindField records and create new ones
            ec.entity.find("moqui.screen.form.FormListFindField")
                    .condition("formListFindId", formListFindId).deleteAll()

            ArrayList<EntityValue> flffList = formInstance.makeFormListFindFields(formListFindId, ec)
            for (EntityValue flff in flffList) flff.create()
        } else {
            flf = ec.entity.makeValue("moqui.screen.form.FormListFind")
            flf.formLocation = formLocation
            flf.description = cs.description ?: "${ec.user.username} - ${ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd HH:mm")}"
            if (cs.orderByField) flf.orderByField = cs.orderByField
            flf.setSequencedIdPrimary()
            flf.create()

            formListFindId = (String) flf.formListFindId

            EntityValue flfu = ec.entity.makeValue("moqui.screen.form.FormListFindUser")
            flfu.formListFindId = formListFindId
            flfu.userId = userId
            flfu.create()

            ArrayList<EntityValue> flffList = formInstance.makeFormListFindFields(formListFindId, ec)
            for (EntityValue flff in flffList) flff.create()
        }

        return formListFindId
    }

    static void saveFormConfig(ExecutionContextImpl ec) {
        String userId = ec.userFacade.userId
        ContextStack cs = ec.contextStack
        String formLocation = cs.get("formLocation")
        if (!formLocation) { ec.messageFacade.addError("No form location specified, cannot save form configuration"); return; }

        // see if there is an existing FormConfig record
        String formConfigId = cs.get("formConfigId")
        if (!formConfigId) {
            EntityValue fcu = ec.entity.find("moqui.screen.form.FormConfigUser")
                    .condition("userId", userId).condition("formLocation", formLocation).useCache(false).one()
            formConfigId = fcu != null ? fcu.formConfigId : null
        }
        String userCurrentFormConfigId = formConfigId

        // if FormConfig associated with this user but no other users or groups delete its FormConfigField
        //     records and remember its ID for create FormConfigField
        if (formConfigId) {
            long userCount = ec.entity.find("moqui.screen.form.FormConfigUser")
                    .condition("formConfigId", formConfigId).useCache(false).count()
            if (userCount > 1) {
                formConfigId = null
            } else {
                long groupCount = ec.entity.find("moqui.screen.form.FormConfigUserGroup")
                        .condition("formConfigId", formConfigId).useCache(false).count()
                if (groupCount > 0) formConfigId = null
            }
        }

        // clear out existing records
        if (formConfigId) {
            ec.entity.find("moqui.screen.form.FormConfigField").condition("formConfigId", formConfigId).deleteAll()
        }

        // are we resetting columns?
        if (cs.get("ResetColumns")) {
            if (formConfigId) {
                // no other users on this form, and now being reset, so delete FormConfig
                ec.entity.find("moqui.screen.form.FormConfigUser").condition("formConfigId", formConfigId).deleteAll()
                ec.entity.find("moqui.screen.form.FormConfig").condition("formConfigId", formConfigId).deleteAll()
            } else if (userCurrentFormConfigId) {
                // there is a FormConfig but other users are using it, so just remove this user
                ec.entity.find("moqui.screen.form.FormConfigUser").condition("formConfigId", userCurrentFormConfigId)
                        .condition("userId", userId).deleteAll()
            }
            // to reset columns don't save new ones, just return after clearing out existing records
            return
        }

        // if there is no FormConfig or found record is associated with other users or groups
        //     create a new FormConfig record to use
        if (!formConfigId) {
            Map createResult = ec.service.sync().name("create#moqui.screen.form.FormConfig")
                    .parameters([userId:userId, formLocation:formLocation, description:"For user ${userId}"]).call()
            formConfigId = createResult.formConfigId
            ec.service.sync().name("create#moqui.screen.form.FormConfigUser")
                    .parameters([formConfigId:formConfigId, userId:userId, formLocation:formLocation]).call()
        }

        // save changes to DB
        String columnsTreeStr = cs.get("columnsTree") as String
        // logger.info("columnsTreeStr: ${columnsTreeStr}")
        // if columnsTree empty there were no changes
        if (!columnsTreeStr) return

        JsonSlurper slurper = new JsonSlurper()
        List<Map> columnsTree = (List<Map>) slurper.parseText(columnsTreeStr)

        StupidUtilities.orderMapList(columnsTree, ['order'])
        int columnIndex = 0
        for (Map columnMap in columnsTree) {
            if (columnMap.get("id") == "hidden") continue
            List<Map> children = (List<Map>) columnMap.get("children")
            StupidUtilities.orderMapList(children, ['order'])
            int columnSequence = 0
            for (Map fieldMap in children) {
                String fieldName = (String) fieldMap.get("id")
                // logger.info("Adding field ${fieldName} to column ${columnIndex} at sequence ${columnSequence}")
                ec.service.sync().name("create#moqui.screen.form.FormConfigField")
                        .parameters([formConfigId:formConfigId, fieldName:fieldName,
                                     positionIndex:columnIndex, positionSequence:columnSequence]).call()
                columnSequence++
            }
            columnIndex++
        }
    }
}
