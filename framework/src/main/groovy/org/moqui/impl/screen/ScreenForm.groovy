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
import org.moqui.BaseArtifactException
import org.moqui.context.ExecutionContext
import org.moqui.entity.*
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.*
import org.moqui.impl.entity.AggregationUtil.AggregateFunction
import org.moqui.impl.entity.AggregationUtil.AggregateField
import org.moqui.impl.entity.EntityJavaUtil.RelationshipInfo
import org.moqui.impl.screen.ScreenDefinition.TransitionItem
import org.moqui.impl.service.ServiceDefinition
import org.moqui.util.CollectionUtilities
import org.moqui.util.ContextStack
import org.moqui.util.MNode
import org.moqui.util.ObjectUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.math.RoundingMode
import java.sql.Timestamp

@CompileStatic
class ScreenForm {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenForm.class)

    protected static final Set<String> fieldAttributeNames = new HashSet<String>(["name", "from", "entry-name", "hide"])
    protected static final Set<String> subFieldAttributeNames = new HashSet<String>(["title", "tooltip", "red-when",
            "validate-service", "validate-parameter", "validate-entity", "validate-field"])

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

    protected MNode entityFindNode = null
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
            internalFormInstance = new FormInstance(this)
        }
    }

    boolean isDisplayOnly() {
        ContextStack cs = ecfi.getEci().contextStack
        return "true".equals(cs.getByString("formDisplayOnly")) || "true".equals(cs.getByString("formDisplayOnly_${formName}"))
    }
    boolean hasDataPrep() { return entityFindNode != null }

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
                } else if ("moqui.screen.form.DbForm".equals(screenLocation) || "DbForm".equals(screenLocation)) {
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

                        extendsScreenLocation = screenLocation
                    }
                }
            } else {
                ScreenForm esf = sd.getForm(extendsForm)
                formNode = esf?.getOrCreateFormNode()
            }
            if (formNode == null) throw new BaseArtifactException("Cound not find extends form [${extendsForm}] referred to in form [${newFormNode.attribute("name")}] of screen [${sd.location}]")
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
                throw new BaseArtifactException("Cound not find service [${serviceName}] or entity noun referred to in auto-fields-service of form [${newFormNode.attribute("name")}] of screen [${sd.location}]")
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
                throw new BaseArtifactException("Cound not find entity [${entityName}] referred to in auto-fields-entity of form [${newFormNode.attribute("name")}] of screen [${sd.location}]")
            }
        }

        // merge original formNode to override any applicable settings
        mergeFormNodes(newFormNode, baseFormNode, false, false)

        // populate validate-service and validate-entity attributes if the target transition calls a single service
        setSubFieldValidateAttrs(newFormNode, "transition", "default-field")
        setSubFieldValidateAttrs(newFormNode, "transition", "conditional-field")
        setSubFieldValidateAttrs(newFormNode, "transition-first-row", "first-row-field")
        setSubFieldValidateAttrs(newFormNode, "transition-second-row", "second-row-field")
        setSubFieldValidateAttrs(newFormNode, "transition-last-row", "last-row-field")

        // check form-single.field-layout and add ONLY hidden fields that are missing
        MNode fieldLayoutNode = newFormNode.first("field-layout")
        if (fieldLayoutNode && !fieldLayoutNode.depthFirst({ MNode it -> it.name == "fields-not-referenced" })) {
            for (MNode fieldNode in newFormNode.children("field")) {
                if (!fieldLayoutNode.depthFirst({ MNode it -> it.name == "field-ref" && it.attribute("name") == fieldNode.attribute("name") })
                        && fieldNode.depthFirst({ MNode it -> it.name == "hidden" }))
                    addFieldToFieldLayout(newFormNode, fieldNode)
            }
        }

        if (logger.traceEnabled) logger.trace("Form [${location}] resulted in expanded def: " + newFormNode.toString())
        // if (location.contains("FOO")) logger.warn("======== Form [${location}] resulted in expanded def: " + newFormNode.toString())

        entityFindNode = newFormNode.first("entity-find")
        // prep row-actions
        if (newFormNode.hasChild("row-actions")) rowActions = new XmlAction(ecfi, newFormNode.first("row-actions"), location + ".row_actions")
    }

    void setSubFieldValidateAttrs(MNode newFormNode, String transitionAttribute, String subFieldNodeName) {
        if (newFormNode.attribute(transitionAttribute)) {
            TransitionItem ti = this.sd.getTransitionItem(newFormNode.attribute(transitionAttribute), null)
            if (ti != null && ti.getSingleServiceName()) {
                String singleServiceName = ti.getSingleServiceName()
                ServiceDefinition sd = ecfi.serviceFacade.getServiceDefinition(singleServiceName)
                if (sd != null) {
                    ArrayList<String> inParamNames = sd.getInParameterNames()
                    for (MNode fieldNode in newFormNode.children("field")) {
                        // if the field matches an in-parameter name and does not already have a validate-service, then set it
                        // do it even if it has a validate-service since it might be from another form, in general we want the current service:  && !fieldNode."@validate-service"
                        if (inParamNames.contains(fieldNode.attribute("name"))) {
                            for (MNode subField in fieldNode.children(subFieldNodeName))
                                if (!subField.attribute("validate-service")) subField.attributes.put("validate-service", singleServiceName)
                        }
                    }
                } else if (ecfi.serviceFacade.isEntityAutoPattern(singleServiceName)) {
                    String entityName = ServiceDefinition.getNounFromName(singleServiceName)
                    EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(entityName)
                    ArrayList<String> fieldNames = ed.getAllFieldNames()
                    for (MNode fieldNode in newFormNode.children("field")) {
                        // if the field matches an in-parameter name and does not already have a validate-entity, then set it
                        if (fieldNames.contains(fieldNode.attribute("name"))) {
                            for (MNode subField in fieldNode.children(subFieldNodeName))
                                if (!subField.attribute("validate-entity")) subField.attributes.put("validate-entity", entityName)
                        }
                    }
                }
            }
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
                EntityValue dbForm = ecfi.entityFacade.fastFindOne("moqui.screen.form.DbForm", true, false, formId)
                if (dbForm == null) throw new BaseArtifactException("Could not find DbForm record with ID [${formId}]")
                dbFormNode = new MNode((dbForm.isListForm == "Y" ? "form-list" : "form-single"), null)

                EntityList dbFormFieldList = ecfi.entityFacade.find("moqui.screen.form.DbFormField").condition("formId", formId)
                        .orderBy("layoutSequenceNum").useCache(true).list()
                for (EntityValue dbFormField in dbFormFieldList) {
                    String fieldName = (String) dbFormField.fieldName
                    MNode newFieldNode = new MNode("field", [name:fieldName])
                    if (dbFormField.entryName) newFieldNode.attributes.put("from", (String) dbFormField.entryName)
                    MNode subFieldNode = newFieldNode.append("default-field", null)
                    if (dbFormField.title) subFieldNode.attributes.put("title", (String) dbFormField.title)
                    if (dbFormField.tooltip) subFieldNode.attributes.put("tooltip", (String) dbFormField.tooltip)

                    String fieldType = dbFormField.fieldTypeEnumId
                    if (!fieldType) throw new BaseArtifactException("DbFormField record with formId [${formId}] and fieldName [${fieldName}] has no fieldTypeEnumId")

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

    /** This is the main method for using an XML Form, the rendering is done based on the Node returned. */
    MNode getOrCreateFormNode() {
        // NOTE: this is cached in the ScreenRenderImpl as it may be called multiple times for a single form render
        List<MNode> dbFormNodeList = hasDbExtensions ? getDbFormNodeList() : null
        boolean displayOnly = isDisplayOnly()

        if (isDynamic) {
            MNode newFormNode = new MNode(internalFormNode.name, null)
            initForm(internalFormNode, newFormNode)
            if (dbFormNodeList != null) for (MNode dbFormNode in dbFormNodeList) mergeFormNodes(newFormNode, dbFormNode, false, true)
            return newFormNode
        } else if ((dbFormNodeList != null && dbFormNodeList.size() > 0) || displayOnly) {
            MNode newFormNode = new MNode(internalFormNode.name, null)
            // deep copy true to avoid bleed over of new fields and such
            mergeFormNodes(newFormNode, internalFormNode, true, true)
            // logger.warn("========== merging in dbFormNodeList: ${dbFormNodeList}", new BaseException("getOrCreateFormNode call location"))
            if (dbFormNodeList != null) for (MNode dbFormNode in dbFormNodeList) mergeFormNodes(newFormNode, dbFormNode, false, true)

            if (displayOnly) {
                // change all non-display fields to simple display elements
                for (MNode fieldNode in newFormNode.children("field")) {
                    // don't replace header form, should be just for searching: if (fieldNode."header-field") fieldSubNodeToDisplay(newFormNode, fieldNode, (Node) fieldNode."header-field"[0])
                    for (MNode conditionalFieldNode in fieldNode.children("conditional-field"))
                        fieldSubNodeToDisplay(conditionalFieldNode)
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
                for (MNode subFn in fn.getChildren()) {
                    subFn.attributes.remove("validate-entity")
                    subFn.attributes.remove("validate-field")
                }
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

        if ("reset".equalsIgnoreCase(widgetNode.name) || "submit".equalsIgnoreCase(widgetNode.name)) {
            fieldSubNode.children.remove(0)
            return
        }

        if ("link".equalsIgnoreCase(widgetNode.name)) {
            // if it goes to a transition with service-call or actions then remove it, otherwise leave it
            String urlType = widgetNode.attribute('url-type')
            if ((urlType == null || urlType.isEmpty() || "transition".equals(urlType)) &&
                    sd.getTransitionItem(widgetNode.attribute('url'), null)?.hasActionsOrSingleService()) {
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
            if ((excludes != null && excludes.contains(parameterName)) || "lastUpdatedStamp".equals(parameterName)) continue
            MNode newFieldNode = new MNode("field", [name:parameterName])
            MNode subFieldNode = newFieldNode.append("default-field", ["validate-service":sd.serviceName, "validate-parameter":parameterName])
            addAutoServiceField(nounEd, parameterNode, fieldType, serviceVerb, newFieldNode, subFieldNode, baseFormNode)
            mergeFieldNode(baseFormNode, newFieldNode, false)
        }
    }

    void addEntityFields(EntityDefinition ed, String include, String fieldType, Set<String> excludes, MNode baseFormNode) {
        for (String fieldName in ed.getFieldNames("all".equals(include) || "pk".equals(include), "all".equals(include) || "nonpk".equals(include))) {
            if ((excludes != null && excludes.contains(fieldName)) || "lastUpdatedStamp".equals(fieldName)) continue

            FieldInfo fi = ed.getFieldInfo(fieldName)
            String efType = fi.type ?: "text-long"
            boolean makeDefaultField = true
            if ("form-list".equals(baseFormNode.name)) {
                Boolean displayField = (Boolean) null
                String defaultDisplay = fi.fieldNode.attribute("default-display")
                if (defaultDisplay != null && !defaultDisplay.isEmpty()) displayField = "true".equals(defaultDisplay)
                if (displayField == null && efType in ['text-long', 'text-very-long', 'binary-very-long']) {
                    // allow find by and display text-long even if not the default, but in form-list never do anything with text-very-long or binary-very-long
                    if ("text-long".equals(efType)) { displayField = false } else { continue }
                }
                makeDefaultField = displayField == null || displayField.booleanValue()
            }

            MNode newFieldNode = new MNode("field", [name:fieldName])
            MNode subFieldNode = makeDefaultField ? newFieldNode.append("default-field", ["validate-entity":ed.getFullEntityName(), "validate-field":fieldName]) : null

            addAutoEntityField(ed, fieldName, fieldType, newFieldNode, subFieldNode, baseFormNode)

            // logger.info("Adding form auto entity field [${fieldName}] of type [${efType}], fieldType [${fieldType}] serviceVerb [${serviceVerb}], node: ${newFieldNode}")
            mergeFieldNode(baseFormNode, newFieldNode, false)
        }
        // separate handling for view-entity with aliases using pq-expression
        if (ed.isViewEntity) {
            Map<String, MNode> pqExpressionNodeMap = ed.getPqExpressionNodeMap()
            if (pqExpressionNodeMap != null) {
                for (MNode pqExprNode in pqExpressionNodeMap.values()) {
                    String defaultDisplay = pqExprNode.attribute("default-display")
                    if (!"true".equals(defaultDisplay)) continue

                    String fieldName = pqExprNode.attribute("name")
                    MNode newFieldNode = new MNode("field", [name:fieldName])
                    MNode subFieldNode = newFieldNode.append("default-field", ["validate-entity":ed.getFullEntityName(), "validate-field":fieldName])

                    addAutoEntityField(ed, fieldName, "display", newFieldNode, subFieldNode, baseFormNode)
                    mergeFieldNode(baseFormNode, newFieldNode, false)
                }
            }
        }

        // logger.info("TOREMOVE: after addEntityFields formNode is: ${baseFormNode}")
    }

    void addAutoEntityField(EntityDefinition ed, String fieldName, String fieldType, MNode newFieldNode, MNode subFieldNode, MNode baseFormNode) {
        // NOTE: in some cases this may be null
        FieldInfo fieldInfo = ed.getFieldInfo(fieldName)
        String efType = fieldInfo?.type ?: "text-long"

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
                break
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

            // handle header-field
            if (baseFormNode.name == "form-list" && !newFieldNode.hasChild("header-field"))
                newFieldNode.append("header-field", ["show-order-by":"true"])

            // handle sub field (default-field)
            if (subFieldNode == null) break
            /* NOTE: used to do this but doesn't make sense for main use of this in ServiceRun/etc screens; for app
                forms should separates pks and use display or hidden instead of edit:
            List<String> pkFieldNameSet = ed.getPkFieldNames()
            if (pkFieldNameSet.contains(fieldName) && serviceVerb == "update") {
                subFieldNode.append("hidden", null)
            } else {
            }
            */
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
                    addEntityFieldDropDown(oneRelNode, subFieldNode, relatedEd, relKeyField, "")
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
            // handle header-field
            if (baseFormNode.name == "form-list" && !newFieldNode.hasChild("header-field"))
                newFieldNode.append("header-field", ["show-order-by":"case-insensitive"])
            // handle sub field (default-field)
            if (subFieldNode == null) break
            if (efType.startsWith("date") || efType.startsWith("time")) {
                subFieldNode.append("date-find", [type:efType])
            } else if (efType.startsWith("number-") || efType.startsWith("currency-")) {
                subFieldNode.append("range-find", null)
            } else {
                if (oneRelNode != null) {
                    addEntityFieldDropDown(oneRelNode, subFieldNode, relatedEd, relKeyField, "")
                } else {
                    subFieldNode.append("text-find", null)
                }
            }
            break
        case "display":
            // handle header-field
            if (baseFormNode.name == "form-list" && !newFieldNode.hasChild("header-field"))
                newFieldNode.append("header-field", ["show-order-by":"case-insensitive"])
            // handle sub field (default-field)
            if (subFieldNode == null) break
            String textStr
            if (relDefaultDescriptionField) textStr = "\${" + relDefaultDescriptionField + " ?: ''} [\${" + relKeyField + "}]"
            else textStr = "[\${" + relKeyField + "}]"
            if (oneRelNode != null) {
                subFieldNode.append("display-entity",
                        ["entity-name":(oneRelNode.attribute("related") ?: oneRelNode.attribute("related-entity-name")), "text":textStr])
            } else {
                Map<String, String> attrs = (Map<String, String>) null
                if (efType.equals("currency-amount")) {
                    attrs = [format:"#,##0.00"]
                } else if (efType.equals("currency-precise")) {
                    attrs = [format:"#,##0.000"]
                }
                subFieldNode.append("display", attrs)
            }
            break
        case "find-display":
            // handle header-field
            if (baseFormNode.name == "form-list" && !newFieldNode.hasChild("header-field"))
                newFieldNode.append("header-field", ["show-order-by":"case-insensitive"])
            MNode headerFieldNode = newFieldNode.hasChild("header-field") ?
                newFieldNode.first("header-field") : newFieldNode.append("header-field", null)
            if (efType == "date" || efType == "time") {
                headerFieldNode.append("date-find", [type:efType])
            } else if (efType == "date-time") {
                headerFieldNode.append("date-period", [time:"true"])
            } else if (efType.startsWith("number-") || efType.startsWith("currency-")) {
                headerFieldNode.append("range-find", [size:'10'])
                newFieldNode.attributes.put("align", "right")
                String function = fieldInfo?.fieldNode?.attribute("function")
                if (function != null && function in ['min', 'max', 'avg']) {
                    newFieldNode.attributes.put("show-total", function)
                } else {
                    newFieldNode.attributes.put("show-total", "sum")
                }
            } else {
                if (oneRelNode != null) {
                    addEntityFieldDropDown(oneRelNode, headerFieldNode, relatedEd, relKeyField, "")
                } else {
                    headerFieldNode.append("text-find", [size:'30', "default-operator":"begins", "ignore-case":"false"])
                }
            }
            // handle sub field (default-field)
            if (subFieldNode == null) break
            if (oneRelNode != null) {
                String textStr
                if (relDefaultDescriptionField) textStr = "\${" + relDefaultDescriptionField + " ?: ''} [\${" + relKeyField + "}]"
                else textStr = "[\${" + relKeyField + "}]"
                subFieldNode.append("display-entity", ["text":textStr,
                        "entity-name":(oneRelNode.attribute("related") ?: oneRelNode.attribute("related-entity-name"))])
            } else {
                Map<String, String> attrs = (Map<String, String>) null
                if (efType.equals("currency-amount")) {
                    attrs = [format:"#,##0.00"]
                } else if (efType.equals("currency-precise")) {
                    attrs = [format:"#,##0.000"]
                }
                subFieldNode.append("display", attrs)
            }
            break
        case "hidden":
            subFieldNode.append("hidden", null)
            break
        }

        // NOTE: don't like where this is located, would be nice to have a generic way for forms to add this sort of thing
        if (oneRelNode != null && subFieldNode != null) {
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
        if (fieldNode.hasChild("first-row-field")) expandFieldSubNode(baseFormNode, fieldNode, fieldNode.first("first-row-field"))
        if (fieldNode.hasChild("second-row-field")) expandFieldSubNode(baseFormNode, fieldNode, fieldNode.first("second-row-field"))
        for (MNode conditionalFieldNode in fieldNode.children("conditional-field"))
            expandFieldSubNode(baseFormNode, fieldNode, conditionalFieldNode)
        if (fieldNode.hasChild("default-field")) expandFieldSubNode(baseFormNode, fieldNode, fieldNode.first("default-field"))
        if (fieldNode.hasChild("last-row-field")) expandFieldSubNode(baseFormNode, fieldNode, fieldNode.first("last-row-field"))
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
            if (!templateLocation) throw new BaseArtifactException("widget-template-include.@location cannot be empty")
            if (!templateLocation.contains("#")) throw new BaseArtifactException("widget-template-include.@location must contain a hash/pound sign to separate the file location and widget-template.@name: [${templateLocation}]")
            String fileLocation = templateLocation.substring(0, templateLocation.indexOf("#"))
            String widgetTemplateName = templateLocation.substring(templateLocation.indexOf("#") + 1)

            MNode widgetTemplatesNode = ecfi.screenFacade.getWidgetTemplatesNodeByLocation(fileLocation)
            MNode widgetTemplateNode = widgetTemplatesNode?.first({ MNode it -> it.attribute("name") == widgetTemplateName })
            if (widgetTemplateNode == null) throw new BaseArtifactException("Could not find widget-template [${widgetTemplateName}] in [${fileLocation}]")

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
        throw new BaseArtifactException("Cound not find service [${serviceName}] or entity noun referred to in auto-fields-service of form [${baseFormNode.attribute("name")}] of screen [${sd.location}]")
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

        if (parameterNode == null) throw new BaseArtifactException("Cound not find parameter [${parameterName}] in service [${sd.serviceName}] referred to in auto-widget-service of form [${baseFormNode.attribute("name")}] of screen [${sd.location}]")
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
        if (ed == null) throw new BaseArtifactException("Cound not find entity [${entityName}] referred to in auto-widget-entity of form [${baseFormNode.attribute("name")}] of screen [${sd.location}]")
        addAutoEntityField(ed, widgetNode.attribute("field-name")?:fieldNode.attribute("name"),
                widgetNode.attribute("field-type")?:"find-display", fieldNode, fieldSubNode, baseFormNode)
    }

    protected static void mergeFormNodes(MNode baseFormNode, MNode overrideFormNode, boolean deepCopy, boolean copyFields) {
        if (overrideFormNode.attributes) baseFormNode.attributes.putAll(overrideFormNode.attributes)

        if (overrideFormNode.hasChild("entity-find")) {
            int efIndex = baseFormNode.firstIndex("entity-find")
            if (efIndex >= 0) baseFormNode.replace(efIndex, overrideFormNode.first("entity-find"))
            else baseFormNode.append(overrideFormNode.first("entity-find"), 0)
        }
        // if overrideFormNode has any row-actions add them all to the ones of the baseFormNode, ie both will run
        if (overrideFormNode.hasChild("row-actions")) {
            if (!baseFormNode.hasChild("row-actions")) baseFormNode.append("row-actions", null)
            MNode baseRowActionsNode = baseFormNode.first("row-actions")
            for (MNode actionNode in overrideFormNode.first("row-actions").children) baseRowActionsNode.append(actionNode)
        }
        if (overrideFormNode.hasChild("hidden-parameters")) {
            int hpIndex = baseFormNode.firstIndex("hidden-parameters")
            if (hpIndex >= 0) baseFormNode.replace(hpIndex, overrideFormNode.first("hidden-parameters"))
            else baseFormNode.append(overrideFormNode.first("hidden-parameters"))
        }

        if (copyFields) {
            for (MNode overrideFieldNode in overrideFormNode.children("field"))
                mergeFieldNode(baseFormNode, overrideFieldNode, deepCopy)
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
            baseFieldNode.mergeSingleChild(overrideFieldNode, "first-row-field")
            baseFieldNode.mergeSingleChild(overrideFieldNode, "second-row-field")
            baseFieldNode.mergeChildrenByKey(overrideFieldNode, "conditional-field", "condition", null)
            baseFieldNode.mergeSingleChild(overrideFieldNode, "default-field")
            baseFieldNode.mergeSingleChild(overrideFieldNode, "last-row-field")

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
                EntityFind ef = ec.entity.find(entityFindNode)
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
                    String keyAttr = childNode.attribute("key")
                    String textAttr = childNode.attribute("text")
                    for (Object listOption in listObject) {
                        if (listOption instanceof Map) {
                            addFieldOption(options, fieldNode, childNode, (Map) listOption, ec)
                        } else {
                            if (keyAttr != null || textAttr != null) {
                                addFieldOption(options, fieldNode, childNode, [entry:listOption], ec)
                            } else {
                                String loString = ObjectUtilities.toPlainString(listOption)
                                if (loString != null) options.put(loString, ec.l10n.localize(loString))
                            }
                        }
                    }
                }
            } else if ("option".equals(childNode.name)) {
                String key = ec.resource.expandNoL10n(childNode.attribute('key'), null)
                String text = ec.resource.expand(childNode.attribute('text'), null)
                options.put(key, text ?: ec.l10n.localize(key))
            }
        }
        return options
    }

    static void addFieldOption(LinkedHashMap<String, String> options, MNode fieldNode, MNode childNode, Map listOption,
                               ExecutionContext ec) {
        EntityValueBase listOptionEvb = listOption instanceof EntityValueBase ? (EntityValueBase) listOption : (EntityValueBase) null
        if (listOptionEvb != null) {
            ec.context.push(listOptionEvb.getMap())
        } else {
            ec.context.push(listOption)
        }
        try {
            String key = null
            String keyAttr = childNode.attribute('key')
            if (keyAttr != null && keyAttr.length() > 0) {
                key = ec.resource.expandNoL10n(keyAttr, null)
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
                    options.put(key, desc != null ? (String) desc : ec.l10n.localize(key))
                } else {
                    options.put(key, ec.l10n.localize(key))
                }
            } else {
                String value = ec.resource.expand(text, null)
                if ("null".equals(value)) value = ec.l10n.localize(key)
                options.put(key, value)
            }
        } finally {
            ec.context.pop()
        }
    }


    // ========== FormInstance Class/etc ==========

    FormInstance getFormInstance() {
        if (isDynamic || hasDbExtensions || isDisplayOnly()) {
            return new FormInstance(this)
        } else {
            if (internalFormInstance == null) internalFormInstance = new FormInstance(this)
            return internalFormInstance
        }
    }

    @CompileStatic
    static class FormInstance {
        private ScreenForm screenForm
        private ExecutionContextFactoryImpl ecfi
        private MNode formNode
        private boolean isListForm = false
        protected Set<String> serverStatic = null

        private ArrayList<MNode> allFieldNodes
        private ArrayList<String> allFieldNames
        private Map<String, MNode> fieldNodeMap = new LinkedHashMap<>()

        private boolean isUploadForm = false
        private boolean isFormHeaderFormVal = false
        private boolean isFormFirstRowFormVal = false
        private boolean isFormSecondRowFormVal = false
        private boolean isFormLastRowFormVal = false
        private boolean hasFirstRow = false
        private boolean hasSecondRow = false
        private boolean hasLastRow = false
        private ArrayList<MNode> nonReferencedFieldList = (ArrayList<MNode>) null
        private ArrayList<MNode> hiddenFieldList = (ArrayList<MNode>) null
        private ArrayList<String> hiddenFieldNameList = (ArrayList<String>) null
        private ArrayList<MNode> hiddenHeaderFieldList = (ArrayList<MNode>) null
        private ArrayList<MNode> hiddenFirstRowFieldList = (ArrayList<MNode>) null
        private ArrayList<MNode> hiddenSecondRowFieldList = (ArrayList<MNode>) null
        private ArrayList<MNode> hiddenLastRowFieldList = (ArrayList<MNode>) null
        private ArrayList<ArrayList<MNode>> formListColInfoList = (ArrayList<ArrayList<MNode>>) null
        private boolean hasFieldHideAttrs = false

        boolean hasAggregate = false
        private String[] aggregateGroupFields = (String[]) null
        private AggregateField[] aggregateFields = (AggregateField[]) null
        private Map<String, AggregateField> aggregateFieldMap = new HashMap<>()
        private HashMap<String, String> showTotalFields = (HashMap<String, String>) null
        private AggregationUtil aggregationUtil = (AggregationUtil) null

        FormInstance(ScreenForm screenForm) {
            this.screenForm = screenForm
            ecfi = screenForm.ecfi
            formNode = screenForm.getOrCreateFormNode()
            isListForm = "form-list".equals(formNode.getName())

            String serverStaticStr = formNode.attribute("server-static")
            if (serverStaticStr) serverStatic = new HashSet(Arrays.asList(serverStaticStr.split(",")))
            else serverStatic = screenForm.sd.serverStatic

            allFieldNodes = formNode.children("field")
            int afnSize = allFieldNodes.size()
            allFieldNames = new ArrayList<>(afnSize)
            if (isListForm) {
                hiddenFieldList = new ArrayList<>()
                hiddenFieldNameList = new ArrayList<>()
                hiddenHeaderFieldList = new ArrayList<>()
                hiddenFirstRowFieldList = new ArrayList<>()
                hiddenSecondRowFieldList = new ArrayList<>()
                hiddenLastRowFieldList = new ArrayList<>()
            }

            // populate fieldNodeMap, get aggregation details
            ArrayList<String> aggregateGroupFieldList = (ArrayList<String>) null

            for (int i = 0; i < afnSize; i++) {
                MNode fieldNode = (MNode) allFieldNodes.get(i)
                String fieldName = fieldNode.attribute("name")
                fieldNodeMap.put(fieldName, fieldNode)
                allFieldNames.add(fieldName)

                if (isListForm) {
                    if (isListFieldHiddenWidget(fieldNode)) {
                        hiddenFieldList.add(fieldNode)
                        if (!hiddenFieldNameList.contains(fieldName)) hiddenFieldNameList.add(fieldName)
                    }
                    MNode headerField = fieldNode.first("header-field")
                    if (headerField != null && headerField.hasChild("hidden")) hiddenHeaderFieldList.add(fieldNode)
                    MNode firstRowField = fieldNode.first("first-row-field")
                    if (firstRowField != null && firstRowField.hasChild("hidden")) hiddenFirstRowFieldList.add(fieldNode)
                    MNode secondRowField = fieldNode.first("second-row-field")
                    if (secondRowField != null && secondRowField.hasChild("hidden")) hiddenSecondRowFieldList.add(fieldNode)
                    MNode lastRowField = fieldNode.first("last-row-field")
                    if (lastRowField != null && lastRowField.hasChild("hidden")) hiddenLastRowFieldList.add(fieldNode)

                    if (fieldNode.attribute("hide")) hasFieldHideAttrs = true

                    String showTotal = fieldNode.attribute("show-total")
                    if ("false".equals(showTotal)) { showTotal = null } else if ("true".equals(showTotal)) { showTotal = "sum" }
                    if (showTotal != null && !showTotal.isEmpty()) {
                        if (showTotalFields == null) showTotalFields = new HashMap<>()
                        showTotalFields.put(fieldName, showTotal)
                    }

                    String aggregate = fieldNode.attribute("aggregate")
                    if (aggregate != null && !aggregate.isEmpty()) {
                        hasAggregate = true

                        boolean isGroupBy = "group-by".equals(aggregate)
                        boolean isSubList = !isGroupBy && "sub-list".equals(aggregate)
                        AggregateFunction af = (AggregateFunction) null
                        if (!isGroupBy && !isSubList) {
                            af = AggregateFunction.valueOf(aggregate.toUpperCase())
                            if (af == null) logger.error("Ignoring aggregate ${aggregate} on field ${fieldName} in form ${formNode.attribute('name')}, not a valid function, group-by, or sub-list")
                        }

                        aggregateFieldMap.put(fieldName, new AggregateField(fieldName, af, isGroupBy, isSubList, showTotal,
                                ecfi.resourceFacade.getGroovyClass(fieldNode.attribute("from"))))
                        if (isGroupBy) {
                            if (aggregateGroupFieldList == null) aggregateGroupFieldList = new ArrayList<>()
                            aggregateGroupFieldList.add(fieldName)
                        }
                    } else {
                        aggregateFieldMap.put(fieldName, new AggregateField(fieldName, null, false, false, showTotal,
                                ecfi.resourceFacade.getGroovyClass(fieldNode.attribute("from"))))
                    }
                }
            }

            // check aggregate defs
            if (hasAggregate) {
                if (aggregateGroupFieldList == null) {
                    throw new BaseArtifactException("Form ${formNode.attribute('name')} has aggregate fields but no group-by field, must have at least one")
                } else {
                    // make group fields array
                    int groupFieldSize = aggregateGroupFieldList.size()
                    aggregateGroupFields = new String[groupFieldSize]
                    for (int i = 0; i < groupFieldSize; i++) aggregateGroupFields[i] = (String) aggregateGroupFieldList.get(i)
                }
            }
            // make AggregateField array for all fields
            aggregateFields = new AggregateField[afnSize]
            for (int i = 0; i < afnSize; i++) {
                String fieldName = (String) allFieldNames.get(i)
                AggregateField aggField = (AggregateField) aggregateFieldMap.get(fieldName)
                if (aggField == null) {
                    MNode fieldNode = fieldNodeMap.get(fieldName)
                    aggField = new AggregateField(fieldName, null, false, false, showTotalFields?.get(fieldName),
                            ecfi.resourceFacade.getGroovyClass(fieldNode.attribute("from")))
                }
                aggregateFields[i] = aggField
            }
            aggregationUtil = new AggregationUtil(formNode.attribute("list"), formNode.attribute("list-entry"),
                    aggregateFields, aggregateGroupFields, screenForm.rowActions)

            // determine isUploadForm and isFormHeaderFormVal
            isUploadForm = formNode.depthFirst({ MNode it -> "file".equals(it.name) }).size() > 0
            for (MNode hfNode in formNode.depthFirst({ MNode it -> "header-field".equals(it.name) })) {
                if (hfNode.children.size() > 0) { isFormHeaderFormVal = true; break } }
            // determine hasFirstRow, isFormFirstRowFormVal, hasLastRow, isFormLastRowFormVal
            for (MNode rfNode in formNode.depthFirst({ MNode it -> "first-row-field".equals(it.name) })) {
                if (rfNode.children.size() > 0) { hasFirstRow = true; break } }
            if (hasFirstRow && formNode.attribute("transition-first-row")) isFormFirstRowFormVal = true
            for (MNode rfNode in formNode.depthFirst({ MNode it -> "second-row-field".equals(it.name) })) {
                if (rfNode.children.size() > 0) { hasSecondRow = true; break } }
            if (hasSecondRow && formNode.attribute("transition-second-row")) isFormSecondRowFormVal = true
            for (MNode rfNode in formNode.depthFirst({ MNode it -> "last-row-field".equals(it.name) })) {
                if (rfNode.children.size() > 0) { hasLastRow = true; break } }
            if (hasLastRow && formNode.attribute("transition-last-row")) isFormLastRowFormVal = true

            // also populate fieldsInFormListColumns
            if (isListForm) formListColInfoList = makeFormListColumnInfo()
        }

        MNode getFormNode() { formNode }
        MNode getFieldNode(String fieldName) { fieldNodeMap.get(fieldName) }
        String getFormLocation() { screenForm.location }
        FormListRenderInfo makeFormListRenderInfo() { new FormListRenderInfo(this) }
        boolean isUpload() { isUploadForm }
        boolean isList() { isListForm }
        boolean isServerStatic(String renderMode) { return serverStatic != null && (serverStatic.contains('all') || serverStatic.contains(renderMode)) }

        MNode getFieldValidateNode(MNode subFieldNode) {
            MNode fieldNode = subFieldNode.getParent()
            String fieldName = fieldNode.attribute("name")
            String validateService = subFieldNode.attribute('validate-service')
            String validateEntity = subFieldNode.attribute('validate-entity')
            if (validateService) {
                ServiceDefinition sd = ecfi.serviceFacade.getServiceDefinition(validateService)
                if (sd == null) throw new BaseArtifactException("Invalid validate-service name [${validateService}] in field [${fieldName}] of form [${location}]")
                MNode parameterNode = sd.getInParameter((String) subFieldNode.attribute('validate-parameter') ?: fieldName)
                return parameterNode
            } else if (validateEntity) {
                EntityDefinition ed = ecfi.entityFacade.getEntityDefinition(validateEntity)
                if (ed == null) throw new BaseArtifactException("Invalid validate-entity name [${validateEntity}] in field [${fieldName}] of form [${location}]")
                MNode efNode = ed.getFieldNode((String) subFieldNode.attribute('validate-field') ?: fieldName)
                return efNode
            }
            return null
        }
        String getFieldValidationClasses(MNode subFieldNode) {
            MNode validateNode = getFieldValidateNode(subFieldNode)
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
        Map getFieldValidationRegexpInfo(MNode subFieldNode) {
            MNode validateNode = getFieldValidateNode(subFieldNode)
            if (validateNode?.hasChild("matches")) {
                MNode matchesNode = validateNode.first("matches")
                return [regexp:matchesNode.attribute('regexp'), message:matchesNode.attribute('message')]
            }
            return null
        }

        ArrayList<MNode> getFieldLayoutNonReferencedFieldList() {
            if (nonReferencedFieldList != null) return nonReferencedFieldList
            ArrayList<MNode> fieldList = new ArrayList<>()

            if (formNode.hasChild("field-layout")) for (MNode fieldNode in formNode.children("field")) {
                MNode fieldLayoutNode = formNode.first("field-layout")
                String fieldName = fieldNode.attribute("name")
                if (!fieldLayoutNode.depthFirst({ MNode it -> it.name == "field-ref" && it.attribute("name") == fieldName }))
                    fieldList.add(fieldNodeMap.get(fieldName))
            }

            nonReferencedFieldList = fieldList
            return fieldList
        }

        static boolean isHeaderSubmitField(MNode fieldNode) {
            MNode headerField = fieldNode.first("header-field")
            if (headerField == null) return false
            return headerField.hasChild("submit")
        }

        boolean isListFieldHiddenAttr(MNode fieldNode) {
            String hideAttr = fieldNode.attribute("hide")
            if (hideAttr != null && hideAttr.length() > 0) {
                return ecfi.getEci().resource.condition(hideAttr, "")
            }
            return false
        }
        static boolean isListFieldHiddenWidget(MNode fieldNode) {
            // if default-field or any conditional-field don't have hidden or ignored elements then it's not hidden
            MNode defaultField = fieldNode.first("default-field")
            if (defaultField != null && !defaultField.hasChild("hidden") && !defaultField.hasChild("ignored")) return false
            List<MNode> condFieldList = fieldNode.children("conditional-field")
            for (MNode condField in condFieldList) if (!condField.hasChild("hidden") && !condField.hasChild("ignored")) return false
            return true
        }

        ArrayList<MNode> getListHiddenFieldList() { return hiddenFieldList }
        ArrayList<String> getListHiddenFieldNameList() { return hiddenFieldNameList }
        boolean hasFormListColumns() { return formNode.children("form-list-column").size() > 0 }

        String getUserActiveFormConfigId(ExecutionContext ec) {
            EntityValue fcu = ecfi.entityFacade.fastFindOne("moqui.screen.form.FormConfigUser", true, false, screenForm.location, ec.user.userId)
            if (fcu != null) return (String) fcu.getNoCheckSimple("formConfigId")

            // Maybe not do this at all and let it be a future thing where the user selects an active one from options available through groups
            EntityList fcugvList = ecfi.entityFacade.find("moqui.screen.form.FormConfigUserGroupView")
                    .condition("userGroupId", EntityCondition.IN, ec.user.userGroupIdSet)
                    .condition("formLocation", screenForm.location).useCache(true).list()
            if (fcugvList.size() > 0) {
                // FUTURE: somehow make a better choice than just the first? see note above too...
                return (String) fcugvList.get(0).getNoCheckSimple("formConfigId")
            }

            return null
        }
        EntityValue getActiveFormListFind(ExecutionContextImpl ec) {
            if (ec.web == null) return null
            String formListFindId = ec.web.requestParameters.get("formListFindId")
            if (!formListFindId) return null
            EntityValue formListFind = ec.entityFacade.fastFindOne("moqui.screen.form.FormListFind", true, false, formListFindId)
            // see if this applies to this form-list, may be multiple on the screen
            if (screenForm.location != formListFind.getNoCheckSimple("formLocation")) formListFind = null
            return formListFind
        }

        ArrayList<ArrayList<MNode>> getFormListColumnInfo() {
            ExecutionContextImpl eci = ecfi.getEci()
            String formConfigId = (String) null
            EntityValue activeFormListFind = getActiveFormListFind(eci)
            if (activeFormListFind != null) formConfigId = activeFormListFind.getNoCheckSimple("formConfigId")
            if (formConfigId == null || formConfigId.isEmpty()) formConfigId = getUserActiveFormConfigId(eci)
            if (formConfigId != null && !formConfigId.isEmpty()) {
                // don't remember the results of this, is per-user so good only once (FormInstance is NOT per user!)
                return makeDbFormListColumnInfo(formConfigId, eci)
            }
            return formListColInfoList
        }
        /** convert form-list-column elements into a list, if there are no form-list-column elements uses fields limiting
         *    by logic about what actually gets rendered (so result can be used for display regardless of form def) */
        private ArrayList<ArrayList<MNode>> makeFormListColumnInfo() {
            ArrayList<MNode> formListColumnList = formNode.children("form-list-column")
            int flcListSize = formListColumnList != null ? formListColumnList.size() : 0

            ArrayList<ArrayList<MNode>> colInfoList = new ArrayList<>()

            if (flcListSize > 0) {
                // populate fields under columns
                for (int ci = 0; ci < flcListSize; ci++) {
                    MNode flcNode = (MNode) formListColumnList.get(ci)
                    ArrayList<MNode> colFieldNodes = new ArrayList<>()
                    ArrayList<MNode> fieldRefNodes = flcNode.children("field-ref")
                    int fieldRefSize = fieldRefNodes.size()
                    for (int fi = 0; fi < fieldRefSize; fi++) {
                        MNode frNode = (MNode) fieldRefNodes.get(fi)
                        String fieldName = frNode.attribute("name")
                        MNode fieldNode = (MNode) fieldNodeMap.get(fieldName)
                        if (fieldNode == null) throw new BaseArtifactException("Could not find field ${fieldName} referenced in form-list-column.field-ref in form at ${screenForm.location}")
                        // skip hidden fields, they are handled separately
                        if (isListFieldHiddenWidget(fieldNode)) continue

                        colFieldNodes.add(fieldNode)
                    }
                    if (colFieldNodes.size() > 0) colInfoList.add(colFieldNodes)
                }
            } else {
                // create a column for each displayed field
                int afnSize = allFieldNodes.size()
                for (int i = 0; i < afnSize; i++) {
                    MNode fieldNode = (MNode) allFieldNodes.get(i)
                    // skip hidden fields, they are handled separately
                    if (isListFieldHiddenWidget(fieldNode)) continue

                    ArrayList<MNode> singleFieldColList = new ArrayList<>()
                    singleFieldColList.add(fieldNode)
                    colInfoList.add(singleFieldColList)
                }
            }

            return colInfoList
        }
        private ArrayList<ArrayList<MNode>> makeDbFormListColumnInfo(String formConfigId, ExecutionContextImpl eci) {
            EntityList formConfigFieldList = ecfi.entityFacade.find("moqui.screen.form.FormConfigField")
                    .condition("formConfigId", formConfigId).orderBy("positionIndex").orderBy("positionSequence").useCache(true).list()

            // NOTE: calling code checks to see if this is not empty
            int fcfListSize = formConfigFieldList.size()

            ArrayList<ArrayList<MNode>> colInfoList = new ArrayList<>()
            Set<String> tempFieldsInFormListColumns = new HashSet()

            // populate fields under columns
            int curColIndex = -1;
            ArrayList<MNode> colFieldNodes = null
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
                if (fieldNode == null) {
                    //throw new BaseArtifactException("Could not find field ${fieldName} referenced in FormConfigField record for ID ${fcfValue.formConfigId} user ${eci.user.userId}, form at ${screenForm.location}")
                    logger.warn("Could not find field ${fieldName} referenced in FormConfigField record for ID ${fcfValue.formConfigId} user ${eci.user.userId}, form at ${screenForm.location}. removing it")
                    fcfValue.delete()
                    continue
                }
                // skip hidden fields, they are handled separately
                if (isListFieldHiddenWidget(fieldNode)) continue

                tempFieldsInFormListColumns.add(fieldName)
                colFieldNodes.add(fieldNode)
            }
            // Add the final field (if defined)
            if (colFieldNodes != null && colFieldNodes.size() > 0) colInfoList.add(colFieldNodes)

            return colInfoList
        }

        ArrayList<EntityValue> makeFormListFindFields(String formListFindId, ExecutionContext ec) {
            ContextStack cs = ec.context

            Set<String> skipSet = null
            MNode entityFindNode = screenForm.entityFindNode
            if (entityFindNode != null) {
                MNode sfiNode = entityFindNode.first("search-form-inputs")
                String skipFields = sfiNode?.attribute("skip-fields")
                if (skipFields != null && !skipFields.isEmpty())
                    skipSet = new HashSet<>(Arrays.asList(skipFields.split(",")).collect({ it.trim() }))
            }

            List<EntityValue> valueList = new ArrayList<>()
            for (MNode fieldNode in allFieldNodes) {
                // skip submit
                if (isHeaderSubmitField(fieldNode)) continue

                String fn = fieldNode.attribute("name")
                if (skipSet != null && skipSet.contains(fn)) continue

                if (cs.containsKey(fn) || cs.containsKey(fn + "_op")) {
                    // this will handle text-line, text-find, etc
                    Object value = cs.get(fn)
                    if (value != null && ObjectUtilities.isEmpty(value)) value = null
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
                    String fromValue = ObjectUtilities.toPlainString(cs.get(fn + "_from"))
                    String thruValue = ObjectUtilities.toPlainString(cs.get(fn + "_thru"))
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
    }
    @CompileStatic
    static class FormListRenderInfo {
        private final FormInstance formInstance
        private final ScreenForm screenForm
        private ExecutionContextFactoryImpl ecfi
        private ArrayList<ArrayList<MNode>> allColInfo
        private ArrayList<ArrayList<MNode>> mainColInfo = (ArrayList<ArrayList<MNode>>) null
        private ArrayList<ArrayList<MNode>> subColInfo = (ArrayList<ArrayList<MNode>>) null
        private LinkedHashSet<String> displayedFieldSet

        FormListRenderInfo(FormInstance formInstance) {
            this.formInstance = formInstance
            screenForm = formInstance.screenForm
            ecfi = formInstance.ecfi
            // NOTE: this can be different for each form rendering depending on user settings
            allColInfo = formInstance.getFormListColumnInfo()
            if (formInstance.hasFieldHideAttrs) {
                int tempAciSize = allColInfo.size()
                ArrayList<ArrayList<MNode>> newColInfo = new ArrayList<>(tempAciSize)
                for (int oi = 0; oi < tempAciSize; oi++) {
                    ArrayList<MNode> innerList = (ArrayList<MNode>) allColInfo.get(oi)
                    if (innerList == null) continue
                    int innerSize = innerList.size()
                    ArrayList<MNode> newInnerList = new ArrayList<>(innerSize)
                    for (int ii = 0; ii < innerSize; ii++) {
                        MNode fieldNode = (MNode) innerList.get(ii)
                        if (!formInstance.isListFieldHiddenAttr(fieldNode)) newInnerList.add(fieldNode)
                    }
                    if (newInnerList.size() > 0) newColInfo.add(newInnerList)
                }
                allColInfo = newColInfo
            }

            // make a set of fields actually displayed
            displayedFieldSet = new LinkedHashSet<>()
            int outerSize = allColInfo.size()
            for (int oi = 0; oi < outerSize; oi++) {
                ArrayList<MNode> innerList = (ArrayList<MNode>) allColInfo.get(oi)
                if (innerList == null) { logger.warn("Null column field list at index ${oi} in form ${screenForm.location}"); continue }
                int innerSize = innerList.size()
                for (int ii = 0; ii < innerSize; ii++) {
                    MNode fieldNode = (MNode) innerList.get(ii)
                    if (fieldNode != null) displayedFieldSet.add(fieldNode.attribute("name"))
                }
            }

            if (formInstance.hasAggregate) {
                subColInfo = new ArrayList<>()
                int flciSize = allColInfo.size()
                mainColInfo = new ArrayList<>(flciSize)
                for (int i = 0; i < flciSize; i++) {
                    ArrayList<MNode> fieldList = (ArrayList<MNode>) allColInfo.get(i)
                    ArrayList<MNode> newFieldList = new ArrayList<>()
                    ArrayList<MNode> subFieldList = (ArrayList<MNode>) null
                    int fieldListSize = fieldList.size()
                    for (int fi = 0; fi < fieldListSize; fi++) {
                        MNode fieldNode = (MNode) fieldList.get(fi)
                        String fieldName = fieldNode.attribute("name")
                        AggregateField aggField = formInstance.aggregateFieldMap.get(fieldName)
                        if (aggField != null && aggField.subList) {
                            if (subFieldList == null) subFieldList = new ArrayList<>()
                            subFieldList.add(fieldNode)
                        } else {
                            newFieldList.add(fieldNode)
                        }
                    }
                    // if fieldList is not empty add to tempFormListColInfo
                    if (newFieldList.size() > 0) mainColInfo.add(newFieldList)
                    if (subFieldList != null) subColInfo.add(subFieldList)
                }
            }
        }

        MNode getFormNode() { return formInstance.formNode }
        MNode getFieldNode(String fieldName) { return formInstance.fieldNodeMap.get(fieldName) }

        boolean isHeaderForm() { return formInstance.isFormHeaderFormVal }
        boolean isFirstRowForm() { return formInstance.isFormFirstRowFormVal }
        boolean isSecondRowForm() { return formInstance.isFormSecondRowFormVal }
        boolean isLastRowForm() { return formInstance.isFormLastRowFormVal }
        boolean hasFirstRow() { return formInstance.hasFirstRow }
        boolean hasSecondRow() { return formInstance.hasSecondRow }
        boolean hasLastRow() { return formInstance.hasLastRow }
        String getFormLocation() { return formInstance.screenForm.location }

        FormInstance getFormInstance() { return formInstance }
        ArrayList<ArrayList<MNode>> getAllColInfo() { return allColInfo }
        ArrayList<ArrayList<MNode>> getMainColInfo() { return mainColInfo ?: allColInfo }
        ArrayList<ArrayList<MNode>> getSubColInfo() { return subColInfo }
        ArrayList<MNode> getListHiddenFieldList() { return formInstance.getListHiddenFieldList() }
        ArrayList<MNode> getListHeaderHiddenFieldList() { return formInstance.hiddenHeaderFieldList }
        ArrayList<MNode> getListFirstRowHiddenFieldList() { return formInstance.hiddenFirstRowFieldList }
        ArrayList<MNode> getListSecondRowHiddenFieldList() { return formInstance.hiddenSecondRowFieldList }
        ArrayList<MNode> getListLastRowHiddenFieldList() { return formInstance.hiddenLastRowFieldList }
        LinkedHashSet<String> getDisplayedFields() { return displayedFieldSet }

        Object getListObject(boolean aggregateList) {
            Object listObject
            String listName = formInstance.formNode.attribute("list")
            Set<String> includeFields = new HashSet<>(displayedFieldSet)
            MNode entityFindNode = screenForm.entityFindNode
            if (entityFindNode != null) {
                EntityFindBase ef = (EntityFindBase) ecfi.entityFacade.find(entityFindNode)

                // don't do this, use explicit select-field fields plus display/hidden fields: if (ef.getSelectFields() == null || ef.getSelectFields().size() == 0) {
                // always do this even if there are some entity-find.select-field elements, support specifying some fields that are always selected
                for (String fieldName in displayedFieldSet) ef.selectField(fieldName)
                List<String> selFields = ef.getSelectFields()
                // don't order by fields not in displayedFieldSet
                ArrayList<String> orderByFields = ef.orderByFields
                if (orderByFields != null) for (int i = 0; i < orderByFields.size(); ) {
                    String obfString = (String) orderByFields.get(i)
                    EntityJavaUtil.FieldOrderOptions foo = EntityJavaUtil.makeFieldOrderOptions(obfString)
                    if (displayedFieldSet.contains(foo.fieldName) || selFields.contains(foo.fieldName)) {
                        i++
                    } else {
                        orderByFields.remove(i)
                    }
                }
                // always select hidden fields
                ArrayList<String> hiddenNames = formInstance.getListHiddenFieldNameList()
                int hiddenNamesSize = hiddenNames.size()
                for (int i = 0; i < hiddenNamesSize; i++) {
                    String fn = (String) hiddenNames.get(i)
                    MNode fieldNode = formInstance.getFieldNode(fn)
                    if (!fieldNode.hasChild("default-field")) continue
                    ef.selectField(fn)
                    includeFields.add(fn)
                }

                // logger.warn("TOREMOVE form-list.entity-find: ${ef.toString()}\ndisplayedFieldSet: ${displayedFieldSet}")

                // run the query
                EntityList efList = ef.list()
                // if cached do the date filter after query
                boolean useCache = ef.shouldCache()
                if (useCache) for (MNode df in entityFindNode.children("date-filter")) {
                    Timestamp validDate = (Timestamp) null
                    String validDateAttr = df.attribute("valid-date")
                    if (validDateAttr != null && !validDateAttr.isEmpty()) validDate = ecfi.resourceFacade.expression(validDateAttr, "") as Timestamp
                    efList.filterByDate(df.attribute("from-field-name") ?: "fromDate", df.attribute("thru-field-name") ?: "thruDate",
                            validDate, "true".equals(df.attribute("ignore-if-empty")))
                }

                // put in context for external use
                ContextStack context = ecfi.getEci().contextStack
                context.put(listName, efList)
                context.put(listName.concat("_xafind"), ef)

                // handle pagination, etc parameters like XML Actions entity-find
                MNode sfiNode = entityFindNode.first("search-form-inputs")
                boolean doPaginate = sfiNode != null && !"false".equals(sfiNode.attribute("paginate"))
                if (doPaginate) {
                    long count, pageSize, pageIndex
                    if (ef.getLimit() == null) {
                        count = efList.size()
                        pageSize = count > 20 ? count : 20
                        pageIndex = efList.getPageIndex()
                    } else if (useCache) {
                        count = efList.size()
                        efList.filterByLimit(sfiNode.attribute("input-fields-map"), true)
                        pageSize = efList.getPageSize()
                        pageIndex = efList.getPageIndex()
                    } else {
                        pageIndex = ef.pageIndex
                        pageSize = ef.pageSize
                        // this can be expensive, only get count if efList size is equal to pageSize (can skip if no paginate needed)
                        if (efList.size() < pageSize) count = efList.size() + pageSize * pageIndex
                        else count = ef.count()
                    }
                    long maxIndex = (new BigDecimal(count-1)).divide(new BigDecimal(pageSize), 0, RoundingMode.DOWN).longValue()
                    long pageRangeLow = (pageIndex * pageSize) + 1
                    long pageRangeHigh = (pageIndex * pageSize) + pageSize
                    if (pageRangeHigh > count) pageRangeHigh = count
                    // logger.info("count ${count} pageSize ${pageSize} maxIndex ${maxIndex} pageRangeLow ${pageRangeLow} pageRangeHigh ${pageRangeHigh}")

                    context.put(listName.concat("Count"), count)
                    context.put(listName.concat("PageIndex"), pageIndex)
                    context.put(listName.concat("PageSize"), pageSize)
                    context.put(listName.concat("PageMaxIndex"), maxIndex)
                    context.put(listName.concat("PageRangeLow"), pageRangeLow)
                    context.put(listName.concat("PageRangeHigh"), pageRangeHigh)
                }

                listObject = efList
            } else {
                listObject = ecfi.resourceFacade.expression(listName, "")
            }

            // NOTE: always call AggregationUtil.aggregateList, passing aggregateList to tell it to do sub-lists or not
            // this does the pre-processing for all form-list renders, handles row-actions, field.@from, etc
            return formInstance.aggregationUtil.aggregateList(listObject, includeFields, aggregateList, ecfi.getEci())
        }

        List<Map<String, Object>> getUserFormListFinds(ExecutionContextImpl ec) {
            EntityList flfuList = ec.entity.find("moqui.screen.form.FormListFindUserView")
                    .condition("userId", ec.user.userId)
                    .condition("formLocation", screenForm.location).useCache(true).list()
            EntityList flfugList = ec.entity.find("moqui.screen.form.FormListFindUserGroupView")
                    .condition("userGroupId", EntityCondition.IN, ec.user.userGroupIdSet)
                    .condition("formLocation", screenForm.location).useCache(true).list()
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
            CollectionUtilities.orderMapList(flfInfoList, ["description"])

            return flfInfoList
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
                MNode curFieldNode = formInstance.fieldNodeMap.get(foo.getFieldName())
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

        ArrayList<MNode> getFieldsNotReferencedInFormListColumn() {
            ArrayList<MNode> colFieldNodes = new ArrayList<>()
            ArrayList<MNode> allFieldNodes = formInstance.allFieldNodes
            int afnSize = allFieldNodes.size()
            for (int i = 0; i < afnSize; i++) {
                MNode fieldNode = (MNode) allFieldNodes.get(i)
                // skip hidden fields, they are handled separately
                if (formInstance.isListFieldHiddenWidget(fieldNode) ||
                        (formInstance.hasFieldHideAttrs && formInstance.isListFieldHiddenAttr(fieldNode))) continue
                String fieldName = fieldNode.attribute("name")
                if (!displayedFieldSet.contains(fieldName)) colFieldNodes.add(formInstance.fieldNodeMap.get(fieldName))
            }

            return colFieldNodes
        }

        ArrayList<Integer> getFormListColumnCharWidths(int originalLineWidth) {
            int numCols = allColInfo.size()
            ArrayList<Integer> charWidths = new ArrayList<>(numCols)
            for (int i = 0; i < numCols; i++) charWidths.add(null)
            if (originalLineWidth == 0) originalLineWidth = 132
            int lineWidth = originalLineWidth
            // leave room for 1 space between each column
            lineWidth -= (numCols - 1)

            // set fixed column widths and get a total of fixed columns, remaining characters to be split among percent width cols
            ArrayList<BigDecimal> percentWidths = new ArrayList<>(numCols)
            for (int i = 0; i < numCols; i++) percentWidths.add(null)
            int fixedColsWidth = 0
            int fixedColsCount = 0
            for (int i = 0; i < numCols; i++) {
                ArrayList<MNode> colNodes = (ArrayList<MNode>) allColInfo.get(i)
                int charWidth = -1
                BigDecimal percentWidth = null
                for (int j = 0; j < colNodes.size(); j++) {
                    MNode fieldNode = (MNode) colNodes.get(j)
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
            if (widthForPercentCols < 0) throw new BaseArtifactException("In form ${formName} fixed width columns exceeded total line characters ${originalLineWidth} by ${-widthForPercentCols} characters")
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
    }

    static Map<String, String> makeFormListFindParameters(String formListFindId, ExecutionContext ec) {
        EntityList flffList = ec.entity.find("moqui.screen.form.FormListFindField")
                .condition("formListFindId", formListFindId).useCache(true).list()

        Map<String, String> parmMap = new LinkedHashMap<>()
        parmMap.put("formListFindId", formListFindId)

        int flffSize = flffList.size()
        for (int i = 0; i < flffSize; i++) {
            EntityValue flff = (EntityValue) flffList.get(i)
            String fn = (String) flff.getNoCheckSimple("fieldName")
            String fieldValue = (String) flff.getNoCheckSimple("fieldValue")
            if (fieldValue != null && !fieldValue.isEmpty()) {
                parmMap.put(fn, fieldValue)
                String op = (String) flff.getNoCheckSimple("fieldOperator")
                if (op && !"equals".equals(op)) parmMap.put(fn + "_op", op)
                String not = (String) flff.getNoCheckSimple("fieldNot")
                if ("Y".equals(not)) parmMap.put(fn + "_not", "Y")
                String ic = (String) flff.getNoCheckSimple("fieldIgnoreCase")
                if ("Y".equals(ic)) parmMap.put(fn + "_ic", "Y")
            } else if (flff.getNoCheckSimple("fieldPeriod")) {
                parmMap.put(fn + "_period", (String) flff.getNoCheckSimple("fieldPeriod"))
                parmMap.put(fn + "_poffset", flff.getNoCheckSimple("fieldPerOffset") as String)
            } else if (flff.getNoCheckSimple("fieldFrom") || flff.getNoCheckSimple("fieldThru")) {
                if (flff.fieldFrom) parmMap.put(fn + "_from", (String) flff.getNoCheckSimple("fieldFrom"))
                if (flff.fieldThru) parmMap.put(fn + "_thru", (String) flff.getNoCheckSimple("fieldThru"))
            }
        }
        return parmMap
    }

    static Map<String, Object> getFormListFindInfo(String formListFindId, ExecutionContextImpl ec, Set<String> userOnlyFlfIdSet) {
        EntityValue formListFind = ec.entityFacade.fastFindOne("moqui.screen.form.FormListFind", true, false, formListFindId)
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
        EntityValue flf = formListFindId != null && !formListFindId.isEmpty() ? ec.entity.find("moqui.screen.form.FormListFind")
                .condition("formListFindId", formListFindId).useCache(false).one() : null

        boolean isDelete = cs.containsKey("DeleteFind")

        if (isDelete) {
            if (flf == null) { ec.messageFacade.addError("Saved find with ID ${formListFindId} not found, not deleting"); return null }

            // delete FormListFindUser record; if there are no other FormListFindUser records or FormListFindUserGroup
            //     records, delete the FormListFind
            EntityValue flfu = ec.entity.find("moqui.screen.form.FormListFindUser").condition("userId", userId)
                    .condition("formListFindId", formListFindId).useCache(false).one()
            // NOTE: if no FormListFindUser nothing to delete... consider removing form from all groups the user is in? best not to, affects other users especially for ALL_USERS
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

        String formConfigId = formInstance.getUserActiveFormConfigId(ec)
        if ((formConfigId == null || formConfigId.isEmpty()) && flf != null) formConfigId = flf.formConfigId
        EntityList formConfigFieldList = null
        if (formConfigId != null && !formConfigId.isEmpty()) {
            formConfigFieldList = ec.entityFacade.find("moqui.screen.form.FormConfigField")
                    .condition("formConfigId", formConfigId).useCache(true).list()
        }

        // see if there is an existing FormListFind record
        if (flf != null) {
            // make sure the FormListFind.formLocation matches the current formLocation
            if (!formLocation.equals(flf.getNoCheckSimple("formLocation"))) {
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
                // is associated with a group but we want to only update for a user, so treat this as if it is not based on existing
                flf = null
                formListFindId = null
            }
        }

        if (flf != null) {
            // save the FormConfig fields if needed, create a new FormConfig for the FormListFind or removing existing as needed
            if (formConfigFieldList != null && formConfigFieldList.size() > 0) {
                String flfFormConfigId = (String) flf.getNoCheckSimple("formConfigId")
                if (flfFormConfigId != null && !flfFormConfigId.isEmpty()) {
                    ec.entity.find("moqui.screen.form.FormConfigField").condition("formConfigId", flfFormConfigId).deleteAll()
                } else {
                    EntityValue formConfig = ec.entity.makeValue("moqui.screen.form.FormConfig").set("formLocation", formLocation)
                            .setSequencedIdPrimary().create()
                    flfFormConfigId = (String) formConfig.getNoCheckSimple("formConfigId")
                    flf.formConfigId = flfFormConfigId
                }
                for (EntityValue fcf in formConfigFieldList) fcf.cloneValue().set("formConfigId", flfFormConfigId).create()
            } else {
                // clear previous FormConfig
                String flfFormConfigId = (String) flf.getNoCheckSimple("formConfigId")
                flf.formConfigId = null
                if (flfFormConfigId != null && !flfFormConfigId.isEmpty())
                    ec.entity.find("moqui.screen.form.FormConfigField").condition("formConfigId", flfFormConfigId).deleteAll()
                ec.entity.find("moqui.screen.form.FormConfigField").condition("formConfigId", flfFormConfigId).deleteAll()
            }

            if (cs._findDescription) flf.description = cs._findDescription
            if (cs.orderByField) flf.orderByField = cs.orderByField
            if (flf.isModified()) flf.update()

            // remove all FormListFindField records and create new ones
            ec.entity.find("moqui.screen.form.FormListFindField").condition("formListFindId", formListFindId).deleteAll()
            ArrayList<EntityValue> flffList = formInstance.makeFormListFindFields(formListFindId, ec)
            for (EntityValue flff in flffList) flff.create()
        } else {
            // if there are FormConfig fields save in a new FormConfig first so we can set the formConfigId later
            EntityValue formConfig = null
            if (formConfigFieldList != null && formConfigFieldList.size() > 0) {
                formConfig = ec.entity.makeValue("moqui.screen.form.FormConfig").set("formLocation", formLocation)
                        .setSequencedIdPrimary().create()
                for (EntityValue fcf in formConfigFieldList) fcf.cloneValue().set("formConfigId", formConfig.formConfigId).create()
            }

            flf = ec.entity.makeValue("moqui.screen.form.FormListFind")
            flf.formLocation = formLocation
            flf.description = cs._findDescription ?: "${ec.user.username} - ${ec.l10n.format(ec.user.nowTimestamp, "yyyy-MM-dd HH:mm")}"
            if (cs.orderByField) flf.orderByField = cs.orderByField
            if (formConfig != null) flf.formConfigId = formConfig.formConfigId
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

        CollectionUtilities.orderMapList(columnsTree, ['order'])
        int columnIndex = 0
        for (Map columnMap in columnsTree) {
            if (columnMap.get("id") == "hidden") continue
            List<Map> children = (List<Map>) columnMap.get("children")
            CollectionUtilities.orderMapList(children, ['order'])
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
