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

import groovy.transform.CompileStatic
import org.apache.commons.collections.map.ListOrderedMap
import org.moqui.BaseException
import org.moqui.context.ContextStack
import org.moqui.context.ExecutionContext
import org.moqui.entity.*
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.entity.*
import org.moqui.impl.entity.EntityDefinition.RelationshipInfo
import org.moqui.impl.screen.ScreenDefinition.TransitionItem
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.util.FtlNodeWrapper
import org.moqui.util.MNode

import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
            EntityList dbFormLookupList = this.ecfi.getEntityFacade().find("DbFormLookup")
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
                if (isDynamic) serviceName = ecfi.resourceFacade.expand(serviceName, "")
                ServiceDefinition serviceDef = ecfi.serviceFacade.getServiceDefinition(serviceName)
                if (serviceDef != null) {
                    addServiceFields(serviceDef, formSubNode.attribute("include")?:"in", formSubNode.attribute("field-type")?:"edit", newFormNode, ecfi)
                    continue
                }
                if (ecfi.getServiceFacade().isEntityAutoPattern(serviceName)) {
                    EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(ServiceDefinition.getNounFromName(serviceName))
                    if (ed != null) {
                        addEntityFields(ed, "all", formSubNode.attribute("field-type")?:"edit", ServiceDefinition.getVerbFromName(serviceName), newFormNode)
                        continue
                    }
                }
                throw new IllegalArgumentException("Cound not find service [${serviceName}] or entity noun referred to in auto-fields-service of form [${newFormNode.attribute("name")}] of screen [${sd.location}]")
            } else if (formSubNode.name == "auto-fields-entity") {
                String entityName = formSubNode.attribute("entity-name")
                if (isDynamic) entityName = ecfi.resourceFacade.expand(entityName, "")
                EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(entityName)
                if (ed != null) {
                    addEntityFields(ed, formSubNode.attribute("include")?:"all", formSubNode.attribute("field-type")?:"find-display", null, newFormNode)
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
                ServiceDefinition sd = ecfi.getServiceFacade().getServiceDefinition(singleServiceName)
                if (sd != null) {
                    ArrayList<String> inParamNames = sd.getInParameterNames()
                    for (MNode fieldNode in newFormNode.children("field")) {
                        // if the field matches an in-parameter name and does not already have a validate-service, then set it
                        // do it even if it has a validate-service since it might be from another form, in general we want the current service:  && !fieldNode."@validate-service"
                        if (inParamNames.contains(fieldNode.attribute("name"))) {
                            fieldNode.attributes.put("validate-service", singleServiceName)
                        }
                    }
                } else if (ecfi.getServiceFacade().isEntityAutoPattern(singleServiceName)) {
                    String entityName = ServiceDefinition.getNounFromName(singleServiceName)
                    EntityDefinition ed = ecfi.getEntityFacade().getEntityDefinition(entityName)
                    List<String> fieldNames = ed.getAllFieldNames(false)
                    for (MNode fieldNode in newFormNode.children("field")) {
                        // if the field matches an in-parameter name and does not already have a validate-entity, then set it
                        if (fieldNames.contains(fieldNode.attribute("name")) && !fieldNode.attribute("validate-entity")) {
                            fieldNode.attributes.put("validate-entity", entityName)
                        }
                    }
                }
            }
        }

        /*
        // add a moquiFormId field to all forms (also: maybe handle in macro ftl file to avoid issue with field-layout
        //     not including this field), and TODO: save in the session
        Node newFieldNode = new Node(null, "field", [name:"moquiFormId"])
        Node subFieldNode = newFieldNode.appendNode("default-field")
        subFieldNode.appendNode("hidden", ["default-value":"((Math.random() * 9999999999) as Long) as String"])
        mergeFieldNode(newFormNode, newFieldNode, false)
         */

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
            EntityList dbFormLookupList = this.ecfi.getEntityFacade().find("DbFormLookup")
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
        MNode dbFormNode = (MNode) ecfi.getScreenFacade().dbFormNodeByIdCache.get(formId)

        if (dbFormNode == null) {

            boolean alreadyDisabled = ecfi.getExecutionContext().getArtifactExecution().disableAuthz()
            try {
                EntityValue dbForm = ecfi.getEntityFacade().find("moqui.screen.form.DbForm").condition("formId", formId).useCache(true).one()
                if (dbForm == null) throw new BaseException("Could not find DbForm record with ID [${formId}]")
                dbFormNode = new MNode((dbForm.isListForm == "Y" ? "form-list" : "form-single"), null)

                EntityList dbFormFieldList = ecfi.getEntityFacade().find("moqui.screen.form.DbFormField").condition("formId", formId)
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

                    EntityList dbFormFieldAttributeList = ecfi.getEntityFacade().find("moqui.screen.form.DbFormFieldAttribute")
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
                    EntityList dbFormFieldOptionList = ecfi.getEntityFacade().find("moqui.screen.form.DbFormFieldOption")
                            .condition([formId:formId, fieldName:fieldName] as Map<String, Object>).useCache(true).list()
                    EntityList dbFormFieldEntOptsList = ecfi.getEntityFacade().find("moqui.screen.form.DbFormFieldEntOpts")
                            .condition([formId:formId, fieldName:fieldName] as Map<String, Object>).useCache(true).list()
                    EntityList combinedOptionList = new EntityListImpl(ecfi.getEntityFacade())
                    combinedOptionList.addAll(dbFormFieldOptionList)
                    combinedOptionList.addAll(dbFormFieldEntOptsList)
                    combinedOptionList.orderByFields(["sequenceNum"])

                    for (EntityValue optionValue in combinedOptionList) {
                        if (optionValue.getEntityName() == "moqui.screen.form.DbFormFieldOption") {
                            widgetNode.append("option", [key:(String) optionValue.keyValue, text:(String) optionValue.text])
                        } else {
                            MNode entityOptionsNode = widgetNode.append("entity-options", [text:((String) optionValue.text ?: "\${description}")])
                            MNode entityFindNode = entityOptionsNode.append("entity-find", ["entity-name":optionValue.getString("entityName")])

                            EntityList dbFormFieldEntOptsCondList = ecfi.getEntityFacade().find("moqui.screen.form.DbFormFieldEntOptsCond")
                                    .condition([formId:formId, fieldName:fieldName, sequenceNum:optionValue.sequenceNum])
                                    .useCache(true).list()
                            for (EntityValue dbFormFieldEntOptsCond in dbFormFieldEntOptsCondList) {
                                entityFindNode.append("econdition", ["field-name":(String) dbFormFieldEntOptsCond.entityFieldName, value:(String) dbFormFieldEntOptsCond.value])
                            }

                            EntityList dbFormFieldEntOptsOrderList = ecfi.getEntityFacade().find("moqui.screen.form.DbFormFieldEntOptsOrder")
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

                ecfi.getScreenFacade().dbFormNodeByIdCache.put(formId, dbFormNode)
            } finally {
                if (!alreadyDisabled) ecfi.getExecutionContext().getArtifactExecution().enableAuthz()
            }
        }

        return dbFormNode
    }

    boolean isDisplayOnly() {
        ContextStack cs = ecfi.getEci().getContext()
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
                        fieldSubNodeToDisplay(newFormNode, fieldNode, conditionalFieldNode)
                    }
                    if (fieldNode.hasChild("default-field")) fieldSubNodeToDisplay(newFormNode, fieldNode, fieldNode.first("default-field"))
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
    protected void fieldSubNodeToDisplay(MNode baseFormNode, MNode fieldNode, MNode fieldSubNode) {
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

    void addAutoServiceField(ServiceDefinition sd, EntityDefinition nounEd, MNode parameterNode, String fieldType,
                             String serviceVerb, MNode newFieldNode, MNode subFieldNode, MNode baseFormNode) {
        // if the parameter corresponds to an entity field, we can do better with that
        EntityDefinition fieldEd = nounEd
        if (parameterNode.attribute("entity-name")) fieldEd = ecfi.entityFacade.getEntityDefinition(parameterNode.attribute("entity-name"))
        String fieldName = parameterNode.attribute("field-name") ?: parameterNode.attribute("name")
        if (fieldEd != null && fieldEd.getFieldNode(fieldName) != null) {
            addAutoEntityField(fieldEd, fieldName, fieldType, serviceVerb, newFieldNode, subFieldNode, baseFormNode)
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
    void addServiceFields(ServiceDefinition sd, String include, String fieldType, MNode baseFormNode,
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
            MNode newFieldNode = new MNode("field", [name:parameterNode.attribute("name"),
                    "validate-service":sd.serviceName, "validate-parameter":parameterNode.attribute("name")])
            MNode subFieldNode = newFieldNode.append("default-field", null)
            addAutoServiceField(sd, nounEd, parameterNode, fieldType, serviceVerb, newFieldNode, subFieldNode, baseFormNode)
            mergeFieldNode(baseFormNode, newFieldNode, false)
        }
    }

    void addEntityFields(EntityDefinition ed, String include, String fieldType, String serviceVerb, MNode baseFormNode) {
        for (String fieldName in ed.getFieldNames(include == "all" || include == "pk", include == "all" || include == "nonpk", include == "all" || include == "nonpk")) {
            String efType = ed.getFieldInfo(fieldName).type ?: "text-long"
            if (baseFormNode.name == "form-list" && efType in ['text-long', 'text-very-long', 'binary-very-long']) continue

            MNode newFieldNode = new MNode("field", [name:fieldName, "validate-entity":ed.getFullEntityName(), "validate-field":fieldName])
            MNode subFieldNode = newFieldNode.append("default-field", null)

            addAutoEntityField(ed, fieldName, fieldType, serviceVerb, newFieldNode, subFieldNode, baseFormNode)

            // logger.info("Adding form auto entity field [${fieldName}] of type [${efType}], fieldType [${fieldType}] serviceVerb [${serviceVerb}], node: ${newFieldNode}")
            mergeFieldNode(baseFormNode, newFieldNode, false)
        }
        // logger.info("TOREMOVE: after addEntityFields formNode is: ${baseFormNode}")
    }

    void addAutoEntityField(EntityDefinition ed, String fieldName, String fieldType, String serviceVerb,
                            MNode newFieldNode, MNode subFieldNode, MNode baseFormNode) {
        List<String> pkFieldNameSet = ed.getPkFieldNames()

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
                    ["entity-name":oneRelNode.attribute("related-entity-name"), "text":textStr])
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
                subFieldNode.append("display-entity", ["entity-name":oneRelNode.attribute("related-entity-name"), "text":textStr])
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

            MNode widgetTemplatesNode = ecfi.getScreenFacade().getWidgetTemplatesNodeByLocation(fileLocation)
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
                addAutoEntityField(ed, widgetNode.attribute("parameter-name")?:fieldNode.attribute("name"), widgetNode.attribute("field-type")?:"edit",
                        serviceName.substring(0, serviceName.indexOf("#")), fieldNode, fieldSubNode, baseFormNode)
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
        addAutoServiceField(sd, nounEd, parameterNode, fieldType, sd.verb, newFieldNode, subFieldNode, baseFormNode)
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
        addAutoEntityField(ed, widgetNode.attribute("field-name")?:fieldNode.attribute("name"), widgetNode.attribute("field-type")?:"find-display",
                null, fieldNode, fieldSubNode, baseFormNode)
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
        MNode baseFieldNode = baseFormNode.first({ MNode it -> it.name == "field" && it.attribute("name") == overrideFieldNode.attribute("name") })
        if (baseFieldNode != null) {
            baseFieldNode.attributes.putAll(overrideFieldNode.attributes)

            baseFieldNode.mergeSingleChild(overrideFieldNode, "header-field")
            baseFieldNode.mergeChildrenByKey(overrideFieldNode, "conditional-field", "condition", null)
            baseFieldNode.mergeSingleChild(overrideFieldNode, "default-field")
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

    static ListOrderedMap getFieldOptions(MNode widgetNode, ExecutionContext ec) {
        MNode fieldNode = widgetNode.parent.parent
        ListOrderedMap options = new ListOrderedMap()
        for (MNode childNode in widgetNode.children) {
            if (childNode.name == "entity-options") {
                MNode entityFindNode = childNode.first("entity-find")
                EntityFindImpl ef = (EntityFindImpl) ec.entity.find(entityFindNode.attribute('entity-name'))
                ef.findNode(entityFindNode)

                EntityList eli = ef.list()

                if (ef.shouldCache()) {
                    // do the date filtering after the query
                    for (MNode df in entityFindNode.children("date-filter")) {
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
            } else if (childNode.name == "list-options") {
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
                            options.put(listOption, listOption)
                            // addFieldOption(options, fieldNode, childNode, [entry:listOption], ec)
                        }
                    }
                }
            } else if (childNode.name == "option") {
                String key = ec.resource.expand((String) childNode.attribute('key'), null)
                String text = ec.resource.expand((String) childNode.attribute('text'), null)
                options.put(key, text ?: key)
            }
        }
        return options
    }

    static void addFieldOption(ListOrderedMap options, MNode fieldNode, MNode childNode, Map listOption,
                               ExecutionContext ec) {
        EntityValueBase listOptionEvb = listOption instanceof EntityValueImpl ? listOption : null
        if (listOptionEvb != null) {
            ec.context.push(listOptionEvb.getMap())
        } else {
            ec.context.push(listOption)
        }
        try {
            String key = null
            String keyAttr = (String) childNode.attribute('key')
            if (keyAttr) {
                key = ec.resource.expand(keyAttr, null)
                // we just did a string expand, if it evaluates to a literal "null" then there was no value
                if (key == "null") key = null
            } else if (listOptionEvb != null) {
                String keyFieldName = listOptionEvb.getEntityDefinition().getPkFieldNames().get(0)
                if (keyFieldName) key = ec.context.getByString(keyFieldName)
            }
            if (key == null) key = ec.context.getByString(fieldNode.attribute('name'))
            if (key == null) return

            String text = childNode.attribute('text')
            if (!text) {
                if ((listOptionEvb == null || listOptionEvb.getEntityDefinition().isField("description"))
                        && listOption["description"]) {
                    options.put(key, listOption["description"])
                } else {
                    options.put(key, key)
                }

            } else {
                String value = ec.resource.expand(text, null)
                if (value == "null") value = key
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
                fieldNodeMap.put(fieldNode.attribute("name"), fieldNode)
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

        boolean isUpload() { return isUploadForm }
        boolean isHeaderForm() { return isFormHeaderFormVal }

        MNode getFieldValidateNode(String fieldName) {
            MNode fieldNode = (MNode) fieldNodeMap.get(fieldName)
            if (fieldNode == null) throw new IllegalArgumentException("Tried to get in-parameter node for field [${fieldName}] that doesn't exist in form [${location}]")
            String validateService = fieldNode.attribute('validate-service')
            String validateEntity = fieldNode.attribute('validate-entity')
            if (validateService) {
                ServiceDefinition sd = ecfi.getServiceFacade().getServiceDefinition(validateService)
                if (sd == null) throw new IllegalArgumentException("Invalid validate-service name [${validateService}] in field [${fieldName}] of form [${location}]")
                MNode parameterNode = sd.getInParameter((String) fieldNode.attribute('validate-parameter') ?: fieldName)
                return parameterNode
            } else if (validateEntity) {
                EntityDefinition ed = ecfi.getEntityFacade().getEntityDefinition(validateEntity)
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
                if (!fieldLayoutNode.depthFirst({ MNode it -> it.name == "field-ref" && it.attribute("name") == fieldNode.attribute("name") }))
                    fieldList.add(FtlNodeWrapper.wrapNode(fieldNode))
            }

            nonReferencedFieldList = fieldList
            return fieldList
        }


        boolean isListFieldHidden(MNode fieldNode) {
            MNode defaultField = fieldNode.first("default-field")
            if (defaultField == null) return false
            return defaultField.hasChild("hidden")
        }
        ArrayList<FtlNodeWrapper> getListHiddenFieldList() {
            if (hiddenFieldList != null) return hiddenFieldList
            ArrayList<FtlNodeWrapper> fieldList = new ArrayList<>()

            ArrayList<FtlNodeWrapper> colFieldNodes = new ArrayList<>()
            int afnSize = allFieldNodes.size()
            for (int i = 0; i < afnSize; i++) {
                MNode fieldNode = (MNode) allFieldNodes.get(i)
                if (isListFieldHidden(fieldNode)) colFieldNodes.add(FtlNodeWrapper.wrapNode(fieldNode))
            }

            hiddenFieldList = fieldList
            return fieldList
        }

        boolean hasFormListColumns() { return formNode.children("form-list-column").size() > 0 }

        ArrayList<ArrayList<FtlNodeWrapper>> getFormListColumnInfo() { return formListColInfoList }
        /** convert form-list-column elements into a list, if there are no form-list-column elements uses fields limiting
         *    by logic about what actually gets rendered (so result can be used for display regardless of form def) */
        private ArrayList<ArrayList<FtlNodeWrapper>> makeFormListColumnInfo() {
            ArrayList<MNode> formListColumnList = formNode.children("form-list-column")
            int flcListSize = formListColumnList != null ? formListColumnList.size() : 0

            ArrayList<ArrayList<FtlNodeWrapper>> colInfoList = new ArrayList<>()

            fieldsInFormListColumns = new HashSet()

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

                        fieldsInFormListColumns.add(fieldName)
                        colFieldNodes.add(FtlNodeWrapper.wrapNode(fieldNode))
                    }
                    colInfoList.add(colFieldNodes)
                }
            } else {
                // create a column for each displayed field
                int afnSize = allFieldNodes.size()
                for (int i = 0; i < afnSize; i++) {
                    MNode fieldNode = (MNode) allFieldNodes.get(i)
                    // skip hidden fields, they are handled separately
                    if (isListFieldHidden(fieldNode)) continue

                    ArrayList<FtlNodeWrapper> singleFieldColList = new ArrayList<>()
                    singleFieldColList.add(FtlNodeWrapper.wrapNode(fieldNode))
                    colInfoList.add(singleFieldColList)
                }
            }

            return colInfoList
        }
        /** Call this after getFormListColumnInfo() so fieldsInFormListColumns will be populated */
        ArrayList<FtlNodeWrapper> getFieldsNotReferencedInFormListColumn() {
            if (fieldsInFormListColumns == null) getFormListColumnInfo()

            ArrayList<FtlNodeWrapper> colFieldNodes = new ArrayList<>()
            int afnSize = allFieldNodes.size()
            for (int i = 0; i < afnSize; i++) {
                MNode fieldNode = (MNode) allFieldNodes.get(i)
                if (!fieldsInFormListColumns.contains(fieldNode.attribute("name")))
                    colFieldNodes.add(FtlNodeWrapper.wrapNode(fieldNode))
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

        void runFormListRowActions(ScreenRenderImpl sri, Object listEntry, int index, boolean hasNext) {
            // NOTE: this runs in a pushed-/sub-context, so just drop it in and it'll get cleaned up automatically
            String listEntryStr = formNode.attribute('list-entry')
            if (listEntryStr) {
                sri.ec.context.put(listEntryStr, listEntry)
                sri.ec.context.put(listEntryStr + "_index", index)
                sri.ec.context.put(listEntryStr + "_has_next", hasNext)
            } else {
                if (listEntry instanceof EntityValueBase) {
                    sri.ec.context.putAll(((EntityValueBase) listEntry).getValueMap())
                } else if (listEntry instanceof Map) {
                    sri.ec.context.putAll((Map) listEntry)
                } else {
                    sri.ec.context.put("listEntry", listEntry)
                }
                String listStr = formNode.attribute('list')
                sri.ec.context.put(listStr + "_index", index)
                sri.ec.context.put(listStr + "_has_next", hasNext)
                sri.ec.context.put(listStr + "_entry", listEntry)
            }
            if (rowActions) rowActions.run(sri.ec)
        }
    }
}
