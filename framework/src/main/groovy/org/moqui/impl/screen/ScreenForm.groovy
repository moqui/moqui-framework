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
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityDefinition.RelationshipInfo
import org.moqui.impl.entity.EntityFindImpl
import org.moqui.impl.service.ServiceDefinition
import org.moqui.impl.util.FtlNodeWrapper
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityListIterator
import org.moqui.entity.EntityValue
import org.moqui.impl.StupidUtilities
import org.moqui.impl.entity.EntityValueImpl
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import org.moqui.entity.EntityList
import org.moqui.entity.EntityException
import org.moqui.impl.screen.ScreenDefinition.TransitionItem
import org.moqui.entity.EntityCondition
import java.sql.Timestamp
import org.moqui.impl.entity.EntityListImpl
import org.moqui.impl.entity.EntityValueBase

class ScreenForm {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenForm.class)

    protected static final Set<String> fieldAttributeNames = new HashSet<String>(["name", "entry-name", "hide",
            "validate-service", "validate-parameter", "validate-entity", "validate-field"])
    protected static final Set<String> subFieldAttributeNames = new HashSet<String>(["title", "tooltip", "red-when"])

    protected ExecutionContextFactoryImpl ecfi
    protected ScreenDefinition sd
    protected Node internalFormNode
    protected String location
    protected String formName
    protected String fullFormName
    protected Boolean isUploadForm = null
    protected Boolean isFormHeaderFormVal = null
    protected boolean hasDbExtensions = false
    protected boolean isDynamic = false

    protected XmlAction rowActions = null

    protected List<Node> nonReferencedFieldList = null

    ScreenForm(ExecutionContextFactoryImpl ecfi, ScreenDefinition sd, Node baseFormNode, String location) {
        this.ecfi = ecfi
        this.sd = sd
        this.location = location
        this.formName = baseFormNode."@name"
        this.fullFormName = sd.getLocation() + "#" + formName

        // is this a dynamic form?
        isDynamic = (baseFormNode."@dynamic" == "true")

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
            internalFormNode = new Node(null, baseFormNode.name())
            initForm(baseFormNode, internalFormNode)
        }
    }

    void initForm(Node baseFormNode, Node newFormNode) {
        // if there is an extends, put that in first (everything else overrides it)
        if (baseFormNode."@extends") {
            String extendsForm = baseFormNode."@extends"
            if (isDynamic) extendsForm = ecfi.resourceFacade.expand(extendsForm, "")

            Node formNode
            if (extendsForm.contains("#")) {
                String screenLocation = extendsForm.substring(0, extendsForm.indexOf("#"))
                String formName = extendsForm.substring(extendsForm.indexOf("#")+1)
                if (screenLocation == sd.getLocation()) {
                    ScreenForm esf = sd.getForm(formName)
                    formNode = esf?.formNode
                } else if (screenLocation == "moqui.screen.form.DbForm" || screenLocation == "DbForm") {
                    formNode = getDbFormNode(formName, ecfi)
                } else {
                    ScreenDefinition esd = ecfi.screenFacade.getScreenDefinition(screenLocation)
                    ScreenForm esf = esd ? esd.getForm(formName) : null
                    formNode = esf?.formNode

                    // see if the included section contains any SECTIONS, need to reference those here too!
                    for (Node inclRefNode in (Collection<Node>) formNode.depthFirst()
                            .findAll({ it instanceof Node && (it.name() == "section" || it.name() == "section-iterate") })) {
                        this.sd.sectionByName.put((String) inclRefNode["@name"], esd.getSection((String) inclRefNode["@name"]))
                    }
                }
            } else {
                ScreenForm esf = sd.getForm(extendsForm)
                formNode = esf?.formNode
            }
            if (formNode == null) throw new IllegalArgumentException("Cound not find extends form [${extendsForm}] referred to in form [${newFormNode."@name"}] of screen [${sd.location}]")
            mergeFormNodes(newFormNode, formNode, true, true)
        }

        for (Node formSubNode in (Collection<Node>) baseFormNode.children()) {
            if (formSubNode.name() == "field") {
                Node nodeCopy = StupidUtilities.deepCopyNode(formSubNode)
                expandFieldNode(newFormNode, nodeCopy)
                mergeFieldNode(newFormNode, nodeCopy, false)
            } else if (formSubNode.name() == "auto-fields-service") {
                String serviceName = formSubNode."@service-name"
                if (isDynamic) serviceName = ecfi.resourceFacade.expand(serviceName, "")
                ServiceDefinition serviceDef = ecfi.serviceFacade.getServiceDefinition(serviceName)
                if (serviceDef != null) {
                    addServiceFields(serviceDef, formSubNode."@include"?:"in", formSubNode."@field-type"?:"edit", newFormNode, ecfi)
                    continue
                }
                if (ecfi.getServiceFacade().isEntityAutoPattern(serviceName)) {
                    EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(ServiceDefinition.getNounFromName(serviceName))
                    if (ed != null) {
                        addEntityFields(ed, "all", formSubNode."@field-type"?:"edit", ServiceDefinition.getVerbFromName(serviceName), newFormNode)
                        continue
                    }
                }
                throw new IllegalArgumentException("Cound not find service [${serviceName}] or entity noun referred to in auto-fields-service of form [${newFormNode."@name"}] of screen [${sd.location}]")
            } else if (formSubNode.name() == "auto-fields-entity") {
                String entityName = formSubNode."@entity-name"
                if (isDynamic) entityName = ecfi.resourceFacade.expand(entityName, "")
                EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(entityName)
                if (ed != null) {
                    addEntityFields(ed, formSubNode."@include"?:"all", formSubNode."@field-type"?:"find-display", null, newFormNode)
                    continue
                }
                throw new IllegalArgumentException("Cound not find entity [${entityName}] referred to in auto-fields-entity of form [${newFormNode."@name"}] of screen [${sd.location}]")
            }
        }

        // merge original formNode to override any applicable settings
        mergeFormNodes(newFormNode, baseFormNode, false, false)

        // populate validate-service and validate-entity attributes if the target transition calls a single service
        if (newFormNode."@transition") {
            TransitionItem ti = this.sd.getTransitionItem((String) newFormNode."@transition", null)
            if (ti != null && ti.getSingleServiceName()) {
                String singleServiceName = ti.getSingleServiceName()
                ServiceDefinition sd = ecfi.getServiceFacade().getServiceDefinition(singleServiceName)
                if (sd != null) {
                    Set<String> inParamNames = sd.getInParameterNames()
                    for (Node fieldNode in newFormNode."field") {
                        // if the field matches an in-parameter name and does not already have a validate-service, then set it
                        // do it even if it has a validate-service since it might be from another form, in general we want the current service:  && !fieldNode."@validate-service"
                        if (inParamNames.contains(fieldNode."@name")) {
                            fieldNode.attributes().put("validate-service", singleServiceName)
                        }
                    }
                } else if (ecfi.getServiceFacade().isEntityAutoPattern(singleServiceName)) {
                    String entityName = ServiceDefinition.getNounFromName(singleServiceName)
                    EntityDefinition ed = ecfi.getEntityFacade().getEntityDefinition(entityName)
                    List<String> fieldNames = ed.getAllFieldNames(false)
                    for (Node fieldNode in newFormNode."field") {
                        // if the field matches an in-parameter name and does not already have a validate-entity, then set it
                        if (fieldNames.contains(fieldNode."@name") && !fieldNode."@validate-entity") {
                            fieldNode.attributes().put("validate-entity", entityName)
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
        Node fieldLayoutNode = newFormNode."field-layout"[0]
        if (fieldLayoutNode && !fieldLayoutNode.depthFirst().find({ it.name() == "fields-not-referenced" })) {
            for (Node fieldNode in newFormNode."field") {
                if (!fieldLayoutNode.depthFirst().find({ it.name() == "field-ref" && it."@name" == fieldNode."@name" })
                        && fieldNode.depthFirst().find({ it.name() == "hidden" }))
                    addFieldToFieldLayout(newFormNode, fieldNode)
            }
        }

        if (logger.traceEnabled) logger.trace("Form [${location}] resulted in expanded def: " + FtlNodeWrapper.wrapNode(newFormNode).toString())
        // if (location.contains("FOO")) logger.warn("======== Form [${location}] resulted in expanded def: " + FtlNodeWrapper.wrapNode(newFormNode).toString())

        // prep row-actions
        if (newFormNode."row-actions") {
            rowActions = new XmlAction(ecfi, (Node) newFormNode."row-actions"[0], location + ".row_actions")
        }
    }

    List<Node> getFieldLayoutNonReferencedFieldList() {
        if (nonReferencedFieldList != null) return nonReferencedFieldList
        List<Node> fieldList = []

        if (getFormNode()."field-layout") for (Node fieldNode in getFormNode()."field") {
            Node fieldLayoutNode = getFormNode()."field-layout"[0]
            if (!fieldLayoutNode.depthFirst().find({ it.name() == "field-ref" && it."@name" == fieldNode."@name" }))
                fieldList.add(fieldNode)
        }

        nonReferencedFieldList = fieldList
        return fieldList
    }

    List<Node> getColumnNonReferencedHiddenFieldList() {
        if (nonReferencedFieldList != null) return nonReferencedFieldList
        List<Node> fieldList = []

        if (getFormNode()."form-list-column") for (Node fieldNode in getFormNode()."field") {
            if (!fieldNode.depthFirst().find({ it.name() == "hidden" })) continue
            List<Node> formListColumnNodeList = getFormNode()."form-list-column"
            boolean foundReference = false
            for (Node formListColumnNode in formListColumnNodeList)
                if (formListColumnNode.depthFirst().find({ it.name() == "field-ref" && it."@name" == fieldNode."@name" }))
                    foundReference = true
            if (!foundReference) fieldList.add(fieldNode)
        }

        nonReferencedFieldList = fieldList
        return fieldList
    }

    List<Node> getDbFormNodeList() {
        if (!hasDbExtensions) return null

        boolean alreadyDisabled = ecfi.getExecutionContext().getArtifactExecution().disableAuthz()
        try {
            // find DbForm records and merge them in as well
            String formName = sd.getLocation() + "#" + ((String) internalFormNode."@name")
            EntityList dbFormLookupList = this.ecfi.getEntityFacade().find("DbFormLookup")
                    .condition("userGroupId", EntityCondition.IN, ecfi.getExecutionContext().getUser().getUserGroupIdSet())
                    .condition("modifyXmlScreenForm", formName)
                    .useCache(true).list()
            // logger.warn("TOREMOVE: looking up DbForms for form [${formName}], found: ${dbFormLookupList}")

            if (!dbFormLookupList) return null

            List<Node> formNodeList = new ArrayList<Node>()
            for (EntityValue dbFormLookup in dbFormLookupList) formNodeList.add(getDbFormNode(dbFormLookup.getString("formId"), ecfi))

            return formNodeList
        } finally {
            if (!alreadyDisabled) ecfi.getExecutionContext().getArtifactExecution().enableAuthz()
        }
    }

    static Node getDbFormNode(String formId, ExecutionContextFactoryImpl ecfi) {
        Node dbFormNode = (Node) ecfi.getScreenFacade().dbFormNodeByIdCache.get(formId)

        if (dbFormNode == null) {

            boolean alreadyDisabled = ecfi.getExecutionContext().getArtifactExecution().disableAuthz()
            try {
                EntityValue dbForm = ecfi.getEntityFacade().find("moqui.screen.form.DbForm").condition("formId", formId).useCache(true).one()
                if (dbForm == null) throw new BaseException("Could not find DbForm record with ID [${formId}]")
                dbFormNode = new Node(null, (dbForm.isListForm == "Y" ? "form-list" : "form-single"), null)

                EntityList dbFormFieldList = ecfi.getEntityFacade().find("moqui.screen.form.DbFormField").condition("formId", formId)
                        .orderBy("layoutSequenceNum").useCache(true).list()
                for (EntityValue dbFormField in dbFormFieldList) {
                    String fieldName = dbFormField.fieldName
                    Node newFieldNode = new Node(null, "field", [name:fieldName])
                    if (dbFormField.entryName) newFieldNode.attributes().put("entry-name", dbFormField.entryName)
                    Node subFieldNode = newFieldNode.appendNode("default-field", [:])
                    if (dbFormField.title) subFieldNode.attributes().put("title", dbFormField.title)
                    if (dbFormField.tooltip) subFieldNode.attributes().put("tooltip", dbFormField.tooltip)

                    String fieldType = dbFormField.fieldTypeEnumId
                    if (!fieldType) throw new IllegalArgumentException("DbFormField record with formId [${formId}] and fieldName [${fieldName}] has no fieldTypeEnumId")

                    String widgetName = fieldType.substring(6)
                    Node widgetNode = subFieldNode.appendNode(widgetName, [:])

                    EntityList dbFormFieldAttributeList = ecfi.getEntityFacade().find("moqui.screen.form.DbFormFieldAttribute")
                            .condition([formId:formId, fieldName:fieldName]).useCache(true).list()
                    for (EntityValue dbFormFieldAttribute in dbFormFieldAttributeList) {
                        String attributeName = dbFormFieldAttribute.attributeName
                        if (fieldAttributeNames.contains(attributeName)) {
                            newFieldNode.attributes().put(attributeName, dbFormFieldAttribute.value)
                        } else if (subFieldAttributeNames.contains(attributeName)) {
                            subFieldNode.attributes().put(attributeName, dbFormFieldAttribute.value)
                        } else {
                            widgetNode.attributes().put(attributeName, dbFormFieldAttribute.value)
                        }
                    }

                    // add option settings when applicable
                    EntityList dbFormFieldOptionList = ecfi.getEntityFacade().find("moqui.screen.form.DbFormFieldOption")
                            .condition([formId:formId, fieldName:fieldName]).useCache(true).list()
                    EntityList dbFormFieldEntOptsList = ecfi.getEntityFacade().find("moqui.screen.form.DbFormFieldEntOpts")
                            .condition([formId:formId, fieldName:fieldName]).useCache(true).list()
                    EntityList combinedOptionList = new EntityListImpl(ecfi.getEntityFacade())
                    combinedOptionList.addAll(dbFormFieldOptionList)
                    combinedOptionList.addAll(dbFormFieldEntOptsList)
                    combinedOptionList.orderByFields(["sequenceNum"])

                    for (EntityValue optionValue in combinedOptionList) {
                        if (optionValue.getEntityName() == "moqui.screen.form.DbFormFieldOption") {
                            widgetNode.appendNode("option", [key:optionValue.keyValue, text:optionValue.text])
                        } else {
                            Node entityOptionsNode = widgetNode.appendNode("entity-options", [text:(optionValue.text ?: "\${description}")])
                            Node entityFindNode = entityOptionsNode.appendNode("entity-find", ["entity-name":optionValue.entityName])

                            EntityList dbFormFieldEntOptsCondList = ecfi.getEntityFacade().find("moqui.screen.form.DbFormFieldEntOptsCond")
                                    .condition([formId:formId, fieldName:fieldName, sequenceNum:optionValue.sequenceNum])
                                    .useCache(true).list()
                            for (EntityValue dbFormFieldEntOptsCond in dbFormFieldEntOptsCondList) {
                                entityFindNode.appendNode("econdition", ["field-name":dbFormFieldEntOptsCond.entityFieldName, value:dbFormFieldEntOptsCond.value])
                            }

                            EntityList dbFormFieldEntOptsOrderList = ecfi.getEntityFacade().find("moqui.screen.form.DbFormFieldEntOptsOrder")
                                    .condition([formId:formId, fieldName:fieldName, sequenceNum:optionValue.sequenceNum])
                                    .orderBy("orderSequenceNum").useCache(true).list()
                            for (EntityValue dbFormFieldEntOptsOrder in dbFormFieldEntOptsOrderList) {
                                entityFindNode.appendNode("order-by", ["field-name":dbFormFieldEntOptsOrder.entityFieldName])
                            }
                        }
                    }

                    // logger.warn("TOREMOVE Adding DbForm field [${fieldName}] widgetName [${widgetName}] at layout sequence [${dbFormField.getLong("layoutSequenceNum")}], node: ${newFieldNode}")
                    if (dbFormField.getLong("layoutSequenceNum") != null) {
                        newFieldNode.attributes().put("layoutSequenceNum", dbFormField.getLong("layoutSequenceNum"))
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

    /** This is the main method for using an XML Form, the rendering is done based on the Node returned. */
    @CompileStatic
    Node getFormNode() {
        // NOTE: this is cached in the ScreenRenderImpl as it may be called multiple times for a single form render
        List<Node> dbFormNodeList = getDbFormNodeList()
        ExecutionContext ec = ecfi.getExecutionContext()
        boolean isDisplayOnly = ec.getContext().get("formDisplayOnly") == "true" || ec.getContext().get("formDisplayOnly_${formName}") == "true"

        if (isDynamic) {
            Node newFormNode = new Node(null, internalFormNode.name())
            initForm(internalFormNode, newFormNode)
            if (dbFormNodeList) {
                for (Node dbFormNode in dbFormNodeList) mergeFormNodes(newFormNode, dbFormNode, false, true)
            }
            return newFormNode
        } else if (dbFormNodeList || isDisplayOnly) {
            Node newFormNode = new Node(null, internalFormNode.name(), [:])
            // deep copy true to avoid bleed over of new fields and such
            mergeFormNodes(newFormNode, internalFormNode, true, true)
            // logger.warn("========== merging in dbFormNodeList: ${dbFormNodeList}", new BaseException("getFormNode call location"))
            for (Node dbFormNode in dbFormNodeList) mergeFormNodes(newFormNode, dbFormNode, false, true)

            if (isDisplayOnly) {
                // change all non-display fields to simple display elements
                for (Object fieldObj in newFormNode.get("field")) {
                    Node fieldNode = (Node) fieldObj
                    // don't replace header form, should be just for searching: if (fieldNode."header-field") fieldSubNodeToDisplay(newFormNode, fieldNode, (Node) fieldNode."header-field"[0])
                    for (Object conditionalFieldObj in fieldNode.get("conditional-field")) {
                        Node conditionalFieldNode = (Node) conditionalFieldObj
                        fieldSubNodeToDisplay(newFormNode, fieldNode, conditionalFieldNode)
                    }
                    if (fieldNode.get("default-field")) fieldSubNodeToDisplay(newFormNode, fieldNode, (Node) ((NodeList) fieldNode.get("default-field"))[0])
                }
            }

            return newFormNode
        } else {
            return internalFormNode
        }
    }

    Node getAutoCleanedNode() {
        Node outNode = StupidUtilities.deepCopyNode(getFormNode())
        outNode.attributes().remove("dynamic")
        outNode.attributes().remove("multi")
        for (Node fn in new ArrayList(outNode.children())) {
            if (fn."@name" in ["aen", "den", "lastUpdatedStamp"]) {
                outNode.remove(fn)
            } else {
                fn.attributes().remove("validate-entity")
                fn.attributes().remove("validate-field")
            }
        }

        return outNode
    }

    static Set displayOnlyIgnoreNodeNames = ["hidden", "ignored", "label", "image"] as Set
    @CompileStatic
    protected void fieldSubNodeToDisplay(Node baseFormNode, Node fieldNode, Node fieldSubNode) {
        Node widgetNode = fieldSubNode.children() ? (Node) fieldSubNode.children().first() : null
        if (widgetNode == null) return
        if (widgetNode.name().toString().contains("display") || displayOnlyIgnoreNodeNames.contains(widgetNode.name())) return

        if (widgetNode.name() == "reset" || widgetNode.name() == "submit") {
            fieldSubNode.remove(widgetNode)
            return
        }

        if (widgetNode.name() == "link") {
            // if it goes to a transition with service-call or actions then remove it, otherwise leave it
            String urlType = (String) widgetNode.attribute('url-type')
            if ((!urlType || urlType == "transition") &&
                    sd.getTransitionItem((String) widgetNode.attribute('url'), null).hasActionsOrSingleService()) {
                fieldSubNode.remove(widgetNode)
            }
            return
        }

        // otherwise change it to a display Node
        widgetNode.replaceNode { node -> new Node(fieldSubNode, "display") }
        // not as good, puts it after other child Nodes: fieldSubNode.remove(widgetNode); fieldSubNode.appendNode("display")
    }

    @CompileStatic
    FtlNodeWrapper getFtlFormNode() { return FtlNodeWrapper.wrapNode(getFormNode()) }

    @CompileStatic
    boolean isUpload(Node cachedFormNode) {
        if (isUploadForm != null) return isUploadForm

        // if there is a "file" element, then it's an upload form
        boolean internalFileNode = internalFormNode.depthFirst().find({ it instanceof Node && ((Node) it).name() == "file" }) as boolean
        if (internalFileNode) {
            isUploadForm = true
            return true
        } else {
            if (isDynamic || hasDbExtensions) {
                Node testNode = cachedFormNode ?: getFormNode()
                return testNode.depthFirst().find({ it instanceof Node && ((Node) it).name() == "file" }) as boolean
            } else {
                return false
            }
        }
    }
    @CompileStatic
    boolean isFormHeaderForm(Node cachedFormNode) {
        if (isFormHeaderFormVal != null) return isFormHeaderFormVal

        // if there is a "header-field" element, then it needs a header form
        boolean internalFormHeaderFormVal = false
        for (Node hfNode in (Collection<Node>) internalFormNode.depthFirst().findAll({ it instanceof Node && ((Node) it).name() == "header-field" })) {
            if (hfNode.children()) {
                internalFormHeaderFormVal = true
                break
            }
        }
        if (internalFormHeaderFormVal) {
            isFormHeaderFormVal = true
            return true
        } else {
            if (isDynamic || hasDbExtensions) {
                boolean extFormHeaderFormVal = false
                Node testNode = cachedFormNode ?: getFormNode()
                for (Node hfNode in (Collection<Node>) testNode.depthFirst().findAll({ it instanceof Node && ((Node) it).name() == "header-field" })) {
                    if (hfNode.children()) {
                        extFormHeaderFormVal = true
                        break
                    }
                }
                return extFormHeaderFormVal
            } else {
                return false
            }
        }
    }

    @CompileStatic
    Node getFieldValidateNode(String fieldName, Node cachedFormNode) {
        Node formNodeToUse = cachedFormNode ?: getFormNode()
        Node fieldNode = (Node) formNodeToUse.get("field").find({ ((Node) it).attribute('name') == fieldName })
        if (fieldNode == null) throw new IllegalArgumentException("Tried to get in-parameter node for field [${fieldName}] that doesn't exist in form [${location}]")
        String validateService = (String) fieldNode.attribute('validate-service')
        String validateEntity = (String) fieldNode.attribute('validate-entity')
        if (validateService) {
            ServiceDefinition sd = ecfi.getServiceFacade().getServiceDefinition(validateService)
            if (sd == null) throw new IllegalArgumentException("Invalid validate-service name [${validateService}] in field [${fieldName}] of form [${location}]")
            Node parameterNode = sd.getInParameter((String) fieldNode.attribute('validate-parameter') ?: fieldName)
            return parameterNode
        } else if (validateEntity) {
            EntityDefinition ed = ecfi.getEntityFacade().getEntityDefinition(validateEntity)
            if (ed == null) throw new IllegalArgumentException("Invalid validate-entity name [${validateEntity}] in field [${fieldName}] of form [${location}]")
            Node efNode = ed.getFieldNode((String) fieldNode.attribute('validate-field') ?: fieldName)
            return efNode
        }
        return null
    }

    void addAutoServiceField(ServiceDefinition sd, EntityDefinition nounEd, Node parameterNode, String fieldType,
                             String serviceVerb, Node newFieldNode, Node subFieldNode, Node baseFormNode) {
        // if the parameter corresponds to an entity field, we can do better with that
        EntityDefinition fieldEd = nounEd
        if (parameterNode."@entity-name") fieldEd = ecfi.entityFacade.getEntityDefinition((String) parameterNode."@entity-name")
        String fieldName = parameterNode."@field-name" ?: parameterNode."@name"
        if (fieldEd != null && fieldEd.getFieldNode(fieldName) != null) {
            addAutoEntityField(fieldEd, fieldName, fieldType, serviceVerb, newFieldNode, subFieldNode, baseFormNode)
            return
        }

        // otherwise use the old approach and do what we can with the service def
        String spType = parameterNode."@type" ?: "String"
        String efType = fieldEd != null ? fieldEd.getFieldInfo((String) parameterNode."@name")?.type : null

        switch (fieldType) {
            case "edit":
                // lastUpdatedStamp is always hidden for edit (needed for optimistic lock)
                if (parameterNode."@name" == "lastUpdatedStamp") {
                    subFieldNode.appendNode("hidden")
                    break
                }

                if (parameterNode."@required" == "true" && serviceVerb.startsWith("update")) {
                    subFieldNode.appendNode("hidden")
                } else {
                    if (spType.endsWith("Date") && spType != "java.util.Date") {
                        subFieldNode.appendNode("date-time", [type:"date", format:parameterNode."@format"])
                    } else if (spType.endsWith("Time")) {
                        subFieldNode.appendNode("date-time", [type:"time", format:parameterNode."@format"])
                    } else if (spType.endsWith("Timestamp") || spType == "java.util.Date") {
                        subFieldNode.appendNode("date-time", [type:"date-time", format:parameterNode."@format"])
                    } else {
                        if (efType == "text-long" || efType == "text-very-long") {
                            subFieldNode.appendNode("text-area")
                        } else {
                            subFieldNode.appendNode("text-line", ['default-value':parameterNode."@default-value"])
                        }
                    }
                }
                break
            case "find":
                if (spType.endsWith("Date") && spType != "java.util.Date") {
                    subFieldNode.appendNode("date-find", [type:"date", format:parameterNode."@format"])
                } else if (spType.endsWith("Time")) {
                    subFieldNode.appendNode("date-find", [type:"time", format:parameterNode."@format"])
                } else if (spType.endsWith("Timestamp") || spType == "java.util.Date") {
                    subFieldNode.appendNode("date-find", [type:"date-time", format:parameterNode."@format"])
                } else if (spType.endsWith("BigDecimal") || spType.endsWith("BigInteger") || spType.endsWith("Long") ||
                        spType.endsWith("Integer") || spType.endsWith("Double") || spType.endsWith("Float") ||
                        spType.endsWith("Number")) {
                    subFieldNode.appendNode("range-find")
                } else {
                    subFieldNode.appendNode("text-find")
                }
                break
            case "display":
                subFieldNode.appendNode("display", [format:parameterNode."@format"])
                break
            case "find-display":
                Node headerFieldNode = newFieldNode.appendNode("header-field")
                if (spType.endsWith("Date") && spType != "java.util.Date") {
                    headerFieldNode.appendNode("date-find", [type:"date", format:parameterNode."@format"])
                } else if (spType.endsWith("Time")) {
                    headerFieldNode.appendNode("date-find", [type:"time", format:parameterNode."@format"])
                } else if (spType.endsWith("Timestamp") || spType == "java.util.Date") {
                    headerFieldNode.appendNode("date-find", [type:"date-time", format:parameterNode."@format"])
                } else if (spType.endsWith("BigDecimal") || spType.endsWith("BigInteger") || spType.endsWith("Long") ||
                        spType.endsWith("Integer") || spType.endsWith("Double") || spType.endsWith("Float") ||
                        spType.endsWith("Number")) {
                    headerFieldNode.appendNode("range-find")
                } else {
                    headerFieldNode.appendNode("text-find")
                }
                subFieldNode.appendNode("display", [format:parameterNode."@format"])
                break
            case "hidden":
                subFieldNode.appendNode("hidden")
                break
        }
    }
    void addServiceFields(ServiceDefinition sd, String include, String fieldType, Node baseFormNode,
                          ExecutionContextFactoryImpl ecfi) {
        String serviceVerb = sd.verb
        //String serviceType = sd.serviceNode."@type"
        EntityDefinition nounEd = null
        try {
            nounEd = ecfi.entityFacade.getEntityDefinition(sd.noun)
        } catch (EntityException e) {
            if (logger.isTraceEnabled()) logger.trace("Ignoring entity exception, may not be real entity name: ${e.toString()}")
        }

        List<Node> parameterNodes = []
        if (include == "in" || include == "all") parameterNodes.addAll((Collection<Node>) sd.serviceNode."in-parameters"[0]."parameter")
        if (include == "out" || include == "all") parameterNodes.addAll((Collection<Node>) sd.serviceNode."out-parameters"[0]."parameter")

        for (Node parameterNode in parameterNodes) {
            Node newFieldNode = new Node(null, "field", [name:parameterNode."@name",
                    "validate-service":sd.serviceName, "validate-parameter":parameterNode."@name"])
            Node subFieldNode = newFieldNode.appendNode("default-field")
            addAutoServiceField(sd, nounEd, parameterNode, fieldType, serviceVerb, newFieldNode, subFieldNode, baseFormNode)
            mergeFieldNode(baseFormNode, newFieldNode, false)
        }
    }

    void addEntityFields(EntityDefinition ed, String include, String fieldType, String serviceVerb, Node baseFormNode) {
        for (String fieldName in ed.getFieldNames(include == "all" || include == "pk", include == "all" || include == "nonpk", include == "all" || include == "nonpk")) {
            String efType = ed.getFieldInfo(fieldName).type ?: "text-long"
            if (baseFormNode.name() == "form-list" && efType in ['text-long', 'text-very-long', 'binary-very-long']) continue

            Node newFieldNode = new Node(null, "field", [name:fieldName, "validate-entity":ed.getFullEntityName(),
                                                         "validate-field":fieldName])
            Node subFieldNode = newFieldNode.appendNode("default-field")

            addAutoEntityField(ed, fieldName, fieldType, serviceVerb, newFieldNode, subFieldNode, baseFormNode)

            // logger.info("Adding form auto entity field [${fieldName}] of type [${efType}], fieldType [${fieldType}] serviceVerb [${serviceVerb}], node: ${newFieldNode}")
            mergeFieldNode(baseFormNode, newFieldNode, false)
        }
        // logger.info("TOREMOVE: after addEntityFields formNode is: ${baseFormNode}")
    }

    void addAutoEntityField(EntityDefinition ed, String fieldName, String fieldType, String serviceVerb,
                            Node newFieldNode, Node subFieldNode, Node baseFormNode) {
        List<String> pkFieldNameSet = ed.getPkFieldNames()

        String efType = ed.getFieldInfo(fieldName).type ?: "text-long"

        // to see if this should be a drop-down with data from another entity,
        // find first relationship that has this field as the only key map and is not a many relationship
        Node oneRelNode = null
        Map oneRelKeyMap = null
        String relatedEntityName = null
        EntityDefinition relatedEd = null
        for (RelationshipInfo relInfo in ed.getRelationshipsInfo(false)) {
            String relEntityName = relInfo.relatedEntityName
            EntityDefinition relEd = relInfo.relatedEd
            Map km = relInfo.keyMap
            if (km.size() == 1 && km.containsKey(fieldName) && relInfo.type == "one" && relInfo.relNode."@is-auto-reverse" != "true") {
                oneRelNode = relInfo.relNode
                oneRelKeyMap = km
                relatedEntityName = relEntityName
                relatedEd = relEd
            }
        }
        String keyField = oneRelKeyMap?.keySet()?.iterator()?.next()
        String relKeyField = oneRelKeyMap?.values()?.iterator()?.next()
        String relDefaultDescriptionField = relatedEd?.getDefaultDescriptionField()

        switch (fieldType) {
        case "edit":
            // lastUpdatedStamp is always hidden for edit (needed for optimistic lock)
            if (fieldName == "lastUpdatedStamp") {
                subFieldNode.appendNode("hidden")
                break
            }

            if (pkFieldNameSet.contains(fieldName) && serviceVerb == "update") {
                subFieldNode.appendNode("hidden")
            } else {
                if (baseFormNode.name() == "form-list" && !newFieldNode."header-field")
                    newFieldNode.appendNode("header-field", ["show-order-by":"case-insensitive"])
                if (efType.startsWith("date") || efType.startsWith("time")) {
                    Node dateTimeNode = subFieldNode.appendNode("date-time", [type:efType])
                    if (fieldName == "fromDate") dateTimeNode.attributes().put("default-value", "\${ec.l10n.format(ec.user.nowTimestamp, 'yyyy-MM-dd HH:mm')}")
                } else if (efType == "text-long" || efType == "text-very-long") {
                    subFieldNode.appendNode("text-area")
                } else if (efType == "text-indicator") {
                    Node dropDownNode = subFieldNode.appendNode("drop-down", ["allow-empty":"true"])
                    dropDownNode.appendNode("option", ["key":"Y"])
                    dropDownNode.appendNode("option", ["key":"N"])
                } else if (efType == "binary-very-long") {
                    // would be nice to have something better for this, like a download somehow
                    subFieldNode.appendNode("display")
                } else {
                    if (oneRelNode != null) {
                        addEntityFieldDropDown(oneRelNode, subFieldNode, relatedEd, relKeyField, "chosen-wider")
                    } else {
                        if (efType.startsWith("number-") || efType.startsWith("currency-")) {
                            subFieldNode.appendNode("text-line", [size:"10"])
                        } else {
                            subFieldNode.appendNode("text-line", [size:"30"])
                        }
                    }
                }
            }
            break
        case "find":
            if (baseFormNode.name() == "form-list" && !newFieldNode."header-field")
                newFieldNode.appendNode("header-field", ["show-order-by":"case-insensitive"])
            if (efType.startsWith("date") || efType.startsWith("time")) {
                subFieldNode.appendNode("date-find", [type:efType])
            } else if (efType.startsWith("number-") || efType.startsWith("currency-")) {
                subFieldNode.appendNode("range-find")
            } else {
                if (oneRelNode != null) {
                    addEntityFieldDropDown(oneRelNode, subFieldNode, relatedEd, relKeyField, "chosen-wider")
                } else {
                    subFieldNode.appendNode("text-find")
                }
            }
            break
        case "display":
            if (baseFormNode.name() == "form-list" && !newFieldNode."header-field")
                newFieldNode.appendNode("header-field", ["show-order-by":"case-insensitive"])
            String textStr
            if (relDefaultDescriptionField) textStr = "\${" + relDefaultDescriptionField + " ?: ''} [\${" + relKeyField + "}]"
            else textStr = "[\${" + relKeyField + "}]"
            if (oneRelNode != null) subFieldNode.appendNode("display-entity",
                    ["entity-name":oneRelNode."@related-entity-name", "text":textStr])
            else subFieldNode.appendNode("display")
            break
        case "find-display":
            if (baseFormNode.name() == "form-list" && !newFieldNode."header-field")
                newFieldNode.appendNode("header-field", ["show-order-by":"case-insensitive"])
            Node headerFieldNode = newFieldNode."header-field" ?
                newFieldNode."header-field"[0] : newFieldNode.appendNode("header-field")
            if (efType == "date" || efType == "time") {
                headerFieldNode.appendNode("date-find", [type:efType])
            } else if (efType == "date-time") {
                headerFieldNode.appendNode("date-period")
            } else if (efType.startsWith("number-") || efType.startsWith("currency-")) {
                headerFieldNode.appendNode("range-find", [size:'4'])
            } else {
                if (oneRelNode != null) {
                    addEntityFieldDropDown(oneRelNode, headerFieldNode, relatedEd, relKeyField, "")
                } else {
                    headerFieldNode.appendNode("text-find", ['hide-options':'true', size:'15'])
                }
            }
            if (oneRelNode != null) {
                String textStr
                if (relDefaultDescriptionField) textStr = "\${" + relDefaultDescriptionField + " ?: ''} [\${" + relKeyField + "}]"
                else textStr = "[\${" + relKeyField + "}]"
                subFieldNode.appendNode("display-entity", ["entity-name":oneRelNode."@related-entity-name", "text":textStr])
            } else {
                subFieldNode.appendNode("display")
            }
            break
        case "hidden":
            subFieldNode.appendNode("hidden")
            break
        }

        // NOTE: don't like where this is located, would be nice to have a generic way for forms to add this sort of thing
        if (oneRelNode != null) {
            if (internalFormNode."@name" == "UpdateMasterEntityValue") {
                Node linkNode = subFieldNode.appendNode("link",
                        [url:"edit", text:"Edit ${relatedEd.getPrettyName(null, null)} [\${fieldValues." + keyField + "}]",
                         condition:"fieldValues.${keyField}", 'link-type':'anchor'])
                linkNode.appendNode("parameter", [name:"aen", value:relatedEntityName])
                linkNode.appendNode("parameter", [name:relKeyField, from:"fieldValues.${keyField}"])
            }
        }
    }

    @CompileStatic
    protected void addEntityFieldDropDown(Node oneRelNode, Node subFieldNode, EntityDefinition relatedEd,
                                          String relKeyField, String dropDownStyle) {
        String title = oneRelNode.attribute("title")

        if (relatedEd == null) {
            subFieldNode.appendNode("text-line")
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
            Node dropDownNode = subFieldNode.appendNode("drop-down", ["allow-empty":"true", style:(dropDownStyle ?: "")])
            Node entityOptionsNode = dropDownNode.appendNode("entity-options")
            Node entityFindNode = entityOptionsNode.appendNode("entity-find",
                    ["entity-name":relatedEntityName, "offset":0, "limit":200])

            if (relatedEntityName == "moqui.basic.Enumeration") {
                // recordCount will be > 0 so we know there are records with this type
                entityFindNode.appendNode("econdition", ["field-name":"enumTypeId", "value":title])
            } else if (relatedEntityName == "moqui.basic.StatusItem") {
                // recordCount will be > 0 so we know there are records with this type
                entityFindNode.appendNode("econdition", ["field-name":"statusTypeId", "value":title])
            }

            if (relDefaultDescriptionField) {
                entityOptionsNode.attributes().put("text", "\${" + relDefaultDescriptionField + " ?: ''} [\${" + relKeyField + "}]")
                entityFindNode.appendNode("order-by", ["field-name":relDefaultDescriptionField])
            }
        } else {
            subFieldNode.appendNode("text-line")
        }
    }

    protected void expandFieldNode(Node baseFormNode, Node fieldNode) {
        if (fieldNode."header-field") expandFieldSubNode(baseFormNode, fieldNode, (Node) fieldNode."header-field"[0])
        for (Node conditionalFieldNode in fieldNode."conditional-field") expandFieldSubNode(baseFormNode, fieldNode, conditionalFieldNode)
        if (fieldNode."default-field") expandFieldSubNode(baseFormNode, fieldNode, (Node) fieldNode."default-field"[0])
    }

    protected void expandFieldSubNode(Node baseFormNode, Node fieldNode, Node fieldSubNode) {
        Node widgetNode = fieldSubNode.children() ? (Node) fieldSubNode.children().first() : null
        if (widgetNode == null) return
        if (widgetNode.name() == "auto-widget-service") {
            fieldSubNode.remove(widgetNode)
            addAutoWidgetServiceNode(baseFormNode, fieldNode, fieldSubNode, widgetNode)
        } else if (widgetNode.name() == "auto-widget-entity") {
            fieldSubNode.remove(widgetNode)
            addAutoWidgetEntityNode(baseFormNode, fieldNode, fieldSubNode, widgetNode)
        } else if (widgetNode.name() == "widget-template-include") {
            List<Node> setNodeList = widgetNode."set"

            String templateLocation = widgetNode."@location"
            if (!templateLocation) throw new IllegalArgumentException("widget-template-include.@location cannot be empty")
            if (!templateLocation.contains("#")) throw new IllegalArgumentException("widget-template-include.@location must contain a hash/pound sign to separate the file location and widget-template.@name: [${templateLocation}]")
            String fileLocation = templateLocation.substring(0, templateLocation.indexOf("#"))
            String widgetTemplateName = templateLocation.substring(templateLocation.indexOf("#") + 1)

            Node widgetTemplatesNode = ecfi.getScreenFacade().getWidgetTemplatesNodeByLocation(fileLocation)
            Node widgetTemplateNode = (Node) widgetTemplatesNode?.find({ it."@name" == widgetTemplateName })
            if (widgetTemplateNode == null) throw new IllegalArgumentException("Could not find widget-template [${widgetTemplateName}] in [${fileLocation}]")

            // remove the widget-template-include node
            fieldSubNode.remove(widgetNode)
            // remove other nodes and append them back so they are after (we allow arbitrary other widget nodes as field sub-nodes)
            List<Node> otherNodes = []
            otherNodes.addAll(fieldSubNode.children())
            for (Node otherNode in otherNodes) fieldSubNode.remove(otherNode)

            for (Node widgetChildNode in (Collection<Node>) widgetTemplateNode.children())
                fieldSubNode.append(StupidUtilities.deepCopyNode(widgetChildNode))
            for (Node otherNode in otherNodes) fieldSubNode.append(otherNode)

            for (Node setNode in setNodeList) fieldSubNode.append(StupidUtilities.deepCopyNode(setNode))
        }
    }

    protected void addAutoWidgetServiceNode(Node baseFormNode, Node fieldNode, Node fieldSubNode, Node widgetNode) {
        String serviceName = widgetNode."@service-name"
        if (isDynamic) serviceName = ecfi.resourceFacade.expand(serviceName, "")
        ServiceDefinition serviceDef = ecfi.serviceFacade.getServiceDefinition(serviceName)
        if (serviceDef != null) {
            addAutoServiceField(serviceDef, (String) widgetNode."@parameter-name"?:fieldNode."@name",
                    widgetNode."@field-type"?:"edit", fieldNode, fieldSubNode, baseFormNode)
            return
        }
        if (serviceName.contains("#")) {
            EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(serviceName.substring(serviceName.indexOf("#")+1))
            if (ed != null) {
                addAutoEntityField(ed, (String) widgetNode."@parameter-name"?:fieldNode."@name", widgetNode."@field-type"?:"edit",
                        serviceName.substring(0, serviceName.indexOf("#")), fieldNode, fieldSubNode, baseFormNode)
                return
            }
        }
        throw new IllegalArgumentException("Cound not find service [${serviceName}] or entity noun referred to in auto-fields-service of form [${newFormNode."@name"}] of screen [${sd.location}]")
    }
    void addAutoServiceField(ServiceDefinition sd, String parameterName, String fieldType,
                             Node newFieldNode, Node subFieldNode, Node baseFormNode) {
        EntityDefinition nounEd = null
        try {
            nounEd = ecfi.entityFacade.getEntityDefinition(sd.noun)
        } catch (EntityException e) {
            // ignore, anticipating there may be no entity def
            if (logger.isTraceEnabled()) logger.trace("Ignoring entity exception, not necessarily an entity name: ${e.toString()}")
        }
        Node parameterNode = sd.serviceNode."in-parameters"[0].find({ it."@name" == parameterName })

        if (parameterNode == null) throw new IllegalArgumentException("Cound not find parameter [${parameterName}] in service [${sd.serviceName}] referred to in auto-widget-service of form [${baseFormNode."@name"}] of screen [${sd.location}]")
        addAutoServiceField(sd, nounEd, parameterNode, fieldType, sd.verb, newFieldNode, subFieldNode, baseFormNode)
    }

    protected void addAutoWidgetEntityNode(Node baseFormNode, Node fieldNode, Node fieldSubNode, Node widgetNode) {
        String entityName = widgetNode."@entity-name"
        if (isDynamic) entityName = ecfi.resourceFacade.expand(entityName, "")
        EntityDefinition ed = null
        try {
            ed = ecfi.entityFacade.getEntityDefinition(entityName)
        } catch (EntityException e) {
            // ignore, anticipating there may be no entity def
            if (logger.isTraceEnabled()) logger.trace("Ignoring entity exception, not necessarily an entity name: ${e.toString()}")
        }
        if (ed == null) throw new IllegalArgumentException("Cound not find entity [${entityName}] referred to in auto-widget-entity of form [${baseFormNode."@name"}] of screen [${sd.location}]")
        addAutoEntityField(ed, (String) widgetNode."@field-name"?:fieldNode."@name", widgetNode."@field-type"?:"find-display",
                null, fieldNode, fieldSubNode, baseFormNode)

    }

    protected static void mergeFormNodes(Node baseFormNode, Node overrideFormNode, boolean deepCopy, boolean copyFields) {
        if (overrideFormNode.attributes()) baseFormNode.attributes().putAll(overrideFormNode.attributes())

        // if overrideFormNode has any row-actions add them all to the ones of the baseFormNode, ie both will run
        if (overrideFormNode."row-actions") {
            if (!baseFormNode."row-actions") baseFormNode.appendNode("row-actions")
            Node baseRowActionsNode = baseFormNode."row-actions"[0]
            for (Node actionNode in overrideFormNode."row-actions"[0].children()) baseRowActionsNode.append(actionNode)
        }

        if (copyFields) {
            for (Node overrideFieldNode in overrideFormNode."field") {
                mergeFieldNode(baseFormNode, overrideFieldNode, deepCopy)
            }
        }

        if (overrideFormNode."field-layout") {
            // just use entire override field-layout, don't try to merge
            if (baseFormNode."field-layout") baseFormNode.remove((Node) baseFormNode."field-layout"[0])
            baseFormNode.append(StupidUtilities.deepCopyNode((Node) overrideFormNode."field-layout"[0]))
        }
        if (overrideFormNode."form-list-column") {
            // if there are any form-list-column remove all from base and copy all from override
            if (baseFormNode."form-list-column") {
                for (Node flcNode in baseFormNode."form-list-column") baseFormNode.remove(flcNode)
            }
            for (Node flcNode in overrideFormNode."form-list-column") baseFormNode.append(StupidUtilities.deepCopyNode(flcNode))
        }
    }

    protected static void mergeFieldNode(Node baseFormNode, Node overrideFieldNode, boolean deepCopy) {
        Node baseFieldNode = (Node) baseFormNode."field".find({ it."@name" == overrideFieldNode."@name" })
        if (baseFieldNode != null) {
            baseFieldNode.attributes().putAll(overrideFieldNode.attributes())

            if (overrideFieldNode."header-field") {
                if (baseFieldNode."header-field") baseFieldNode.remove((Node) baseFieldNode."header-field"[0])
                baseFieldNode.append((Node) overrideFieldNode."header-field"[0])
            }
            for (Node overrideConditionalFieldNode in overrideFieldNode."conditional-field") {
                Node baseConditionalFieldNode = (Node) baseFieldNode."conditional-field"
                        .find({ it."@condition" == overrideConditionalFieldNode."@condition" })
                if (baseConditionalFieldNode != null) baseFieldNode.remove(baseConditionalFieldNode)
                baseFieldNode.append(overrideConditionalFieldNode)
            }
            if (overrideFieldNode."default-field") {
                if (baseFieldNode."default-field") baseFieldNode.remove((Node) baseFieldNode."default-field"[0])
                baseFieldNode.append((Node) overrideFieldNode."default-field"[0])
            }
        } else {
            baseFormNode.append(deepCopy ? StupidUtilities.deepCopyNode(overrideFieldNode) : overrideFieldNode)
            // this is a new field... if the form has a field-layout element add a reference under that too
            if (baseFormNode."field-layout") addFieldToFieldLayout(baseFormNode, overrideFieldNode)
        }
    }

    static void addFieldToFieldLayout(Node formNode, Node fieldNode) {
        Node fieldLayoutNode = formNode."field-layout"[0]
        Long layoutSequenceNum = fieldNode.attribute("layoutSequenceNum") as Long
        if (layoutSequenceNum == null) {
            fieldLayoutNode.appendNode("field-ref", [name:fieldNode."@name"])
        } else {
            formNode.remove(fieldLayoutNode)
            Node newFieldLayoutNode = formNode.appendNode("field-layout", fieldLayoutNode.attributes())
            int index = 0
            boolean addedNode = false
            for (Node child in (Collection<Node>) fieldLayoutNode.children()) {
                if (index == layoutSequenceNum) {
                    newFieldLayoutNode.appendNode("field-ref", [name:fieldNode."@name"])
                    addedNode = true
                }
                newFieldLayoutNode.append(child)
                index++
            }
            if (!addedNode) {
                newFieldLayoutNode.appendNode("field-ref", [name:fieldNode."@name"])
            }
        }
    }

    @CompileStatic
    void runFormListRowActions(ScreenRenderImpl sri, Object listEntry, int index, boolean hasNext) {
        // NOTE: this runs in a pushed-/sub-context, so just drop it in and it'll get cleaned up automatically
        Node localFormNode = getFormNode()
        String listEntryStr = (String) localFormNode.attribute('list-entry')
        if (listEntryStr) {
            sri.ec.context.put(listEntryStr, listEntry)
            sri.ec.context.put(listEntryStr + "_index", index)
            sri.ec.context.put(listEntryStr + "_has_next", hasNext)
        } else {
            if (listEntry instanceof Map) {
                sri.ec.context.putAll((Map) listEntry)
            } else {
                sri.ec.context.put("listEntry", listEntry)
            }
            String listStr = (String) localFormNode.attribute('list')
            sri.ec.context.put(listStr + "_index", index)
            sri.ec.context.put(listStr + "_has_next", hasNext)
            sri.ec.context.put(listStr + "_entry", listEntry)
        }
        if (rowActions) rowActions.run(sri.ec)
    }

    @CompileStatic
    static ListOrderedMap getFieldOptions(Node widgetNode, ExecutionContext ec) {
        Node fieldNode = widgetNode.parent().parent()
        ListOrderedMap options = new ListOrderedMap()
        for (Node childNode in (Collection<Node>) widgetNode.children()) {
            if (childNode.name() == "entity-options") {
                Node entityFindNode = (Node) ((NodeList) childNode.get("entity-find"))[0]
                EntityFindImpl ef = (EntityFindImpl) ec.entity.find((String) entityFindNode.attribute('entity-name'))
                ef.findNode(entityFindNode)

                EntityList eli = ef.list()

                if (ef.shouldCache()) {
                    // do the date filtering after the query
                    for (Node df in (Collection<Node>) entityFindNode["date-filter"]) {
                        EntityCondition dateEc = ec.entity.conditionFactory.makeConditionDate((String) df["@from-field-name"] ?: "fromDate",
                                (String) df["@thru-field-name"] ?: "thruDate",
                                (df["@valid-date"] ? ec.resource.expression((String) df["@valid-date"], null) as Timestamp : ec.user.nowTimestamp))
                        // logger.warn("TOREMOVE getFieldOptions cache=${ef.getUseCache()}, dateEc=${dateEc} list before=${eli}")
                        eli = eli.filterByCondition(dateEc, true)
                    }
                }

                for (EntityValue ev in eli) addFieldOption(options, fieldNode, childNode, ev, ec)
            } else if (childNode.name() == "list-options") {
                Object listObject = ec.resource.expression((String) childNode.attribute('list'), null)
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
            } else if (childNode.name() == "option") {
                String key = ec.resource.expand((String) childNode.attribute('key'), null)
                String text = ec.resource.expand((String) childNode.attribute('text'), null)
                options.put(key, text ?: key)
            }
        }
        return options
    }

    @CompileStatic
    static void addFieldOption(ListOrderedMap options, Node fieldNode, Node childNode, Map listOption,
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
                if (keyFieldName) key = ec.context.get(keyFieldName)
            }
            if (key == null) key = ec.context.get(fieldNode.attribute('name'))
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
}
