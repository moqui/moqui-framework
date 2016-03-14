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
package org.moqui.impl.entity

import groovy.transform.CompileStatic
import org.moqui.impl.StupidJavaUtilities
import org.moqui.impl.context.ExecutionContextImpl

import java.sql.Timestamp

import org.apache.commons.collections.set.ListOrderedSet

import org.moqui.BaseException
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityCondition.JoinOperator
import org.moqui.entity.EntityException
import org.moqui.entity.EntityValue
import org.moqui.entity.EntityList
import org.moqui.entity.EntityNotFoundException
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.impl.entity.condition.FieldValueCondition
import org.moqui.impl.entity.condition.FieldToFieldCondition
import org.moqui.impl.entity.EntityJavaUtil.FieldInfo
import org.moqui.impl.StupidUtilities
import org.moqui.util.MNode

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
public class EntityDefinition {
    protected final static Logger logger = LoggerFactory.getLogger(EntityDefinition.class)

    protected EntityFacadeImpl efi
    protected String internalEntityName
    protected String fullEntityName
    protected String shortAlias
    protected String groupName = null
    protected MNode internalEntityNode
    protected String tableNameAttr
    protected String schemaNameVal
    protected String fullTableNameVal

    protected final Map<String, MNode> fieldNodeMap = new HashMap<String, MNode>()
    protected final Map<String, FieldInfo> fieldInfoMap = new HashMap<String, FieldInfo>()
    // small lists, but very frequently accessed
    protected final ArrayList<String> pkFieldNameList = new ArrayList<String>()
    protected final ArrayList<String> nonPkFieldNameList = new ArrayList<String>()
    protected final ArrayList<String> allFieldNameList = new ArrayList<String>()
    protected final ArrayList<FieldInfo> pkFieldInfoList = new ArrayList<FieldInfo>()
    protected final ArrayList<FieldInfo> nonPkFieldInfoList = new ArrayList<FieldInfo>()
    protected final ArrayList<FieldInfo> allFieldInfoList = new ArrayList<FieldInfo>()
    protected Boolean hasUserFields = null
    protected boolean allowUserField = false
    protected Map<String, Map<String, String>> mePkFieldToAliasNameMapMap = null
    protected Map<String, Map<String, ArrayList<MNode>>> memberEntityFieldAliases = null
    protected Map<String, MNode> memberEntityAliasMap = null
    // these are used for every list find, so keep them here
    protected MNode entityConditionNode = null
    protected MNode entityHavingEconditions = null

    protected final boolean isView
    protected final boolean hasFunctionAliasVal
    protected final boolean createOnlyVal
    protected boolean createOnlyFields = false
    protected final boolean optimisticLockVal
    protected Boolean needsAuditLogVal = null
    protected Boolean needsEncryptVal = null
    protected final String useCache
    protected String sequencePrimaryPrefix = ""
    protected long sequencePrimaryStagger = 1
    protected long sequenceBankSize = EntityFacadeImpl.defaultBankSize
    protected final boolean sequencePrimaryUseUuid

    protected final boolean hasFieldDefaultsVal
    protected final String authorizeSkipStr
    protected final boolean authorizeSkipTrueVal
    protected final boolean authorizeSkipCreateVal
    protected final boolean authorizeSkipViewVal


    protected List<MNode> expandedRelationshipList = null
    // this is kept separately for quick access to relationships by name or short-alias
    protected Map<String, RelationshipInfo> relationshipInfoMap = null
    protected List<RelationshipInfo> relationshipInfoList = null
    protected boolean hasReverseRelationships = false
    protected Map<String, MasterDefinition> masterDefinitionMap = null

    EntityDefinition(EntityFacadeImpl efi, MNode entityNode) {
        this.efi = efi
        // copy the entityNode because we may be modifying it
        internalEntityNode = entityNode.deepCopy(null)
        isView = internalEntityNode.name == "view-entity"
        internalEntityName = internalEntityNode.attribute("entity-name")
        fullEntityName = internalEntityNode.attribute("package-name") + "." + internalEntityName
        shortAlias = internalEntityNode.attribute("short-alias") ?: null

        if (internalEntityNode.attribute("is-dynamic-view") == "true") {
            // use the name of the first member-entity
            String memberEntityName = internalEntityNode.children("member-entity")
                    .find({ !it.attribute("join-from-alias") })?.attribute("entity-name")
            groupName = efi.getEntityGroupName(memberEntityName)
        } else {
            groupName = internalEntityNode.attribute("group-name") ?: efi.getDefaultGroupName()
        }

        this.sequencePrimaryPrefix = internalEntityNode.attribute("sequence-primary-prefix") ?: ""
        if (internalEntityNode.attribute("sequence-primary-stagger"))
            sequencePrimaryStagger = internalEntityNode.attribute("sequence-primary-stagger") as long
        if (internalEntityNode.attribute("sequence-bank-size"))
            sequenceBankSize = internalEntityNode.attribute("sequence-bank-size") as long
        sequencePrimaryUseUuid = internalEntityNode.attribute('sequence-primary-use-uuid') == "true" ||
            "true".equals(efi.getDatasourceNode(groupName)?.attribute('sequence-primary-use-uuid'))

        createOnlyVal = "true".equals(internalEntityNode.attribute('create-only'))

        authorizeSkipStr = internalEntityNode.attribute('authorize-skip')
        authorizeSkipTrueVal = authorizeSkipStr == "true"
        authorizeSkipCreateVal = authorizeSkipTrueVal || authorizeSkipStr?.contains("create")
        authorizeSkipViewVal = authorizeSkipTrueVal || authorizeSkipStr?.contains("view")

        initFields()

        hasFunctionAliasVal = isView && internalEntityNode.children("alias").find({ it.attribute("function") })

        for (int i = 0; i < allFieldInfoList.size(); i++) if (allFieldInfoList.get(i).createOnly) createOnlyFields = true

        optimisticLockVal = "true".equals(internalEntityNode.attribute('optimistic-lock'))

        useCache = internalEntityNode.attribute('cache') ?: 'false'

        tableNameAttr = internalEntityNode.attribute("table-name")
        if (tableNameAttr == null || tableNameAttr.length() == 0) tableNameAttr = camelCaseToUnderscored(internalEntityName)
        schemaNameVal = efi.getDatasourceNode(getEntityGroupName())?.attribute("schema-name")
        if (schemaNameVal != null && schemaNameVal.length() == 0) schemaNameVal = null
        if (efi.getDatabaseNode(getEntityGroupName())?.attribute("use-schemas") != "false") {
            fullTableNameVal = schemaNameVal != null ? schemaName + "." + tableNameAttr : tableNameAttr
        } else {
            fullTableNameVal = tableNameAttr
        }

        hasFieldDefaultsVal = getPkFieldDefaults() || getNonPkFieldDefaults()
    }

    void initFields() {
        if (isViewEntity()) {
            memberEntityFieldAliases = [:]
            memberEntityAliasMap = [:]

            // get group-name, etc from member-entity
            for (MNode memberEntity in internalEntityNode.children("member-entity")) {
                String memberEntityName = memberEntity.attribute("entity-name")
                memberEntityAliasMap.put(memberEntity.attribute("entity-alias"), memberEntity)
                EntityDefinition memberEd = this.efi.getEntityDefinition(memberEntityName)
                MNode memberEntityNode = memberEd.getEntityNode()
                if (memberEntityNode.attribute("group-name")) internalEntityNode.attributes.put("group-name", memberEntityNode.attribute("group-name"))
            }
            // if this is a view-entity, expand the alias-all elements into alias elements here
            this.expandAliasAlls()
            // set @type, set is-pk on all alias Nodes if the related field is-pk
            for (MNode aliasNode in internalEntityNode.children("alias")) {
                MNode memberEntity = memberEntityAliasMap.get(aliasNode.attribute("entity-alias"))
                if (memberEntity == null) {
                    if (aliasNode.hasChild("complex-alias")) {
                        continue
                    } else {
                        throw new EntityException("Could not find member-entity with entity-alias [${aliasNode.attribute("entity-alias")}] in view-entity [${internalEntityName}]")
                    }
                }
                EntityDefinition memberEd = this.efi.getEntityDefinition(memberEntity.attribute("entity-name"))
                String fieldName = aliasNode.attribute("field") ?: aliasNode.attribute("name")
                MNode fieldNode = memberEd.getFieldNode(fieldName)
                if (fieldNode == null) throw new EntityException("In view-entity [${internalEntityName}] alias [${aliasNode.attribute("name")}] referred to field [${fieldName}] that does not exist on entity [${memberEd.internalEntityName}].")
                if (!aliasNode.attribute("type")) aliasNode.attributes.put("type", fieldNode.attribute("type"))
                if (fieldNode.attribute("is-pk") == "true") aliasNode.attributes.put("is-pk", "true")

                // add to aliases by field name by entity name
                if (!memberEntityFieldAliases.containsKey(memberEd.getFullEntityName())) memberEntityFieldAliases.put(memberEd.getFullEntityName(), [:])
                Map<String, ArrayList<MNode>> fieldInfoByEntity = memberEntityFieldAliases.get(memberEd.getFullEntityName())
                if (!fieldInfoByEntity.containsKey(fieldName)) fieldInfoByEntity.put(fieldName, new ArrayList())
                ArrayList<MNode> aliasByField = fieldInfoByEntity.get(fieldName)
                aliasByField.add(aliasNode)
            }
            for (MNode aliasNode in internalEntityNode.children("alias")) {
                FieldInfo fi = makeFieldInfo(this, aliasNode)
                addFieldInfo(fi)
            }

            entityConditionNode = entityNode.first("entity-condition")
            if (entityConditionNode != null) entityHavingEconditions = entityConditionNode.first("having-econditions")
        } else {
            if (internalEntityNode.attribute("no-update-stamp") != "true") {
                // automatically add the lastUpdatedStamp field
                internalEntityNode.append("field", [name:"lastUpdatedStamp", type:"date-time"])
            }
            if (internalEntityNode.attribute("allow-user-field") == "true") allowUserField = true

            for (MNode fieldNode in this.internalEntityNode.children("field")) {
                FieldInfo fi = makeFieldInfo(this, fieldNode)
                addFieldInfo(fi)
            }
        }

        // if (isViewEntity()) logger.warn("========== entity Node: ${internalEntityNode.toString()}")
    }

    protected void addFieldInfo(FieldInfo fi) {
        fieldNodeMap.put(fi.name, fi.fieldNode)
        fieldInfoMap.put(fi.name, fi)
        allFieldNameList.add(fi.name)
        allFieldInfoList.add(fi)
        if (fi.isPk) {
            pkFieldNameList.add(fi.name)
            pkFieldInfoList.add(fi)
        } else {
            nonPkFieldNameList.add(fi.name)
            nonPkFieldInfoList.add(fi)
        }
    }

    String getEntityName() { return this.internalEntityName }
    String getFullEntityName() { return this.fullEntityName }
    String getShortAlias() { return this.shortAlias }
    MNode getEntityNode() { return this.internalEntityNode }

    boolean isViewEntity() { return isView }
    boolean hasFunctionAlias() { return hasFunctionAliasVal }
    Map<String, ArrayList<MNode>> getMemberFieldAliases(String memberEntityName) {
        return memberEntityFieldAliases?.get(memberEntityName)
    }
    MNode getEntityConditionNode() { return entityConditionNode }

    String getEntityGroupName() { return groupName }

    String getDefaultDescriptionField() {
        List<String> nonPkFields = getFieldNames(false, true, false)
        // find the first *Name
        for (String fn in nonPkFields)
            if (fn.endsWith("Name")) return fn

        // no name? try literal description
        if (isField("description")) return "description"

        // no description? just use the first non-pk field: nonPkFields.get(0)
        // not any more, can be confusing... just return empty String
        return ""
    }

    boolean createOnly() { return createOnlyVal }
    boolean createOnlyAny() { return createOnlyVal || createOnlyFields }
    boolean optimisticLock() { return optimisticLockVal }
    boolean authorizeSkipTrue() { return authorizeSkipTrueVal }
    boolean authorizeSkipCreate() { return authorizeSkipCreateVal }
    boolean authorizeSkipView() { return authorizeSkipViewVal }
    boolean needsAuditLog() {
        if (needsAuditLogVal != null) return needsAuditLogVal.booleanValue()

        boolean tempVal = false
        for (FieldInfo fi in getAllFieldInfoList()) {
            if (fi.enableAuditLog == "true" || fi.enableAuditLog == "update") {
                tempVal = true
                break
            }
        }

        needsAuditLogVal = tempVal
        return tempVal
    }
    boolean needsEncrypt() {
        if (needsEncryptVal != null) return needsEncryptVal.booleanValue()
        needsEncryptVal = false
        for (MNode fieldNode in getFieldNodes(true, true, false)) {
            if (fieldNode.attribute('encrypt') == "true") needsEncryptVal = true
        }
        if (needsEncryptVal) return true

        for (MNode fieldNode in getFieldNodes(false, false, true)) {
            if (fieldNode.attribute('encrypt') == "true") needsEncryptVal = true
        }

        return needsEncryptVal.booleanValue()
    }
    String getUseCache() { return useCache }

    boolean getSequencePrimaryUseUuid() { return sequencePrimaryUseUuid }

    MNode getFieldNode(String fieldName) {
        MNode fn = (MNode) fieldNodeMap.get(fieldName)
        if (fn != null) return fn

        if (allowUserField && !this.isViewEntity() && !fieldName.contains('.')) {
            // if fieldName has a dot it is likely a relationship name, so don't look for UserField

            boolean alreadyDisabled = efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz()
            try {
                EntityList userFieldList = efi.find("moqui.entity.UserField").condition("entityName", getFullEntityName()).useCache(true).list()
                if (userFieldList) {
                    Set<String> userGroupIdSet = efi.getEcfi().getExecutionContext().getUser().getUserGroupIdSet()
                    for (EntityValue userField in userFieldList) {
                        if (userField.fieldName != fieldName) continue
                        if (userGroupIdSet.contains(userField.userGroupId)) {
                            fn = makeUserFieldNode(userField)
                            break
                        }
                    }
                }
            } finally {
                if (!alreadyDisabled) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
            }
        }

        return fn
    }

    FieldInfo getFieldInfo(String fieldName) {
        // the FieldInfo cast here looks funny, but avoids Groovy using a slow castToType call
        FieldInfo fi = (FieldInfo) fieldInfoMap.get(fieldName)
        if (fi != null) return fi
        MNode fieldNode = getFieldNode(fieldName)
        if (fieldNode == null) return null
        fi = makeFieldInfo(this, fieldNode)
        fieldInfoMap.put(fieldName, fi)
        return fi
    }
    ArrayList<FieldInfo> getPkFieldInfoList() { return pkFieldInfoList }
    ArrayList<FieldInfo> getNonPkFieldInfoList() { return nonPkFieldInfoList }
    ArrayList<FieldInfo> getAllFieldInfoList() { return allFieldInfoList }

    /** Make a EntityJavaUtil.FieldInfo object, is in Java for most efficient access (values used a LOT), init here for
     * access to all EntityDefinition and other info */
    static FieldInfo makeFieldInfo(EntityDefinition ed, MNode fieldNode) {
        FieldInfo fi = new FieldInfo()

        fi.ed = ed
        fi.fieldNode = fieldNode
        Map<String, String> fnAttrs = fieldNode.attributes
        fi.name = fnAttrs.get('name')
        fi.entityName = ed.getFullEntityName()
        fi.type = fnAttrs.get('type')
        fi.columnName = fnAttrs.get('column-name') ?: camelCaseToUnderscored(fi.name)
        fi.defaultStr = fnAttrs.get('default')
        if (!fi.type && fieldNode.hasChild("complex-alias") && fnAttrs.get('function')) {
            // this is probably a calculated value, just default to number-decimal
            fi.type = 'number-decimal'
        }
        if (fi.type) {
            fi.javaType = ed.efi.getFieldJavaType(fi.type, ed) ?: 'String'
            fi.typeValue = EntityFacadeImpl.getJavaTypeInt(fi.javaType)
            fi.isTextVeryLong = "text-very-long".equals(fi.type)
        } else {
            throw new EntityException("No type specified or found for field ${fi.name} on entity ${ed.getFullEntityName()}")
        }
        fi.isPk = 'true'.equals(fnAttrs.get('is-pk'))
        fi.encrypt = 'true'.equals(fnAttrs.get('encrypt'))
        fi.enableLocalization = 'true'.equals(fnAttrs.get('enable-localization'))
        fi.isUserField = 'true'.equals(fnAttrs.get('is-user-field'))
        fi.isSimple = !fi.enableLocalization && !fi.isUserField
        fi.createOnly = fnAttrs.get('create-only') ? 'true'.equals(fnAttrs.get('create-only')) : ed.createOnly()
        fi.enableAuditLog = fieldNode.attribute('enable-audit-log') ?: ed.internalEntityNode.attribute('enable-audit-log')

        if (ed.isViewEntity()) {
            // NOTE: for view-entity the incoming fieldNode will actually be for an alias element
            StringBuilder colNameBuilder = new StringBuilder()
            /*
            if (includeFunctionAndComplex) {
                // column name for view-entity (prefix with "${entity-alias}.")
                //colName.append(fieldNode."@entity-alias").append('.')
                if (logger.isTraceEnabled()) logger.trace("For view-entity include function and complex not yet supported, for entity [${internalEntityName}], may get bad SQL...")
            }
            // else {
            */

            if (fieldNode.hasChild('complex-alias')) {
                String function = fieldNode.attribute('function')
                if (function) {
                    colNameBuilder.append(getFunctionPrefix(function))
                }
                ed.buildComplexAliasName(fieldNode, "+", colNameBuilder)
                if (function) colNameBuilder.append(')')
            } else {
                String function = fieldNode.attribute('function')
                if (function) {
                    colNameBuilder.append(getFunctionPrefix(function))
                }
                // column name for view-entity (prefix with "${entity-alias}.")
                colNameBuilder.append(fieldNode.attribute('entity-alias')).append('.')

                String memberFieldName = fieldNode.attribute('field') ?: fieldNode.attribute('name')
                colNameBuilder.append(ed.getBasicFieldColName(ed.internalEntityNode,
                        (String) fieldNode.attribute('entity-alias'), memberFieldName))

                if (function) colNameBuilder.append(')')
            }

            // }
            fi.fullColumnName = colNameBuilder.toString()
        } else {
            fi.fullColumnName = fi.columnName
        }

        return fi
    }

    protected MNode makeUserFieldNode(EntityValue userField) {
        String fieldType = (String) userField.fieldType ?: "text-long"
        if (fieldType == "text-very-long" || fieldType == "binary-very-long")
            throw new EntityException("UserField for entityName ${getFullEntityName()}, fieldName ${userField.fieldName} and userGroupId ${userField.userGroupId} has a fieldType that is not allowed: ${fieldType}")

        MNode fieldNode = new MNode("field", [name: (String) userField.fieldName, type: fieldType, "is-user-field": "true"])

        fieldNode.attributes.put("user-group-id", (String) userField.userGroupId)
        if (userField.enableAuditLog == "Y") fieldNode.attributes.put("enable-audit-log", "true")
        if (userField.enableAuditLog == "U") fieldNode.attributes.put("enable-audit-log", "update")
        if (userField.enableLocalization == "Y") fieldNode.attributes.put("enable-localization", "true")
        if (userField.encrypt == "Y") fieldNode.attributes.put("encrypt", "true")

        return fieldNode
    }

    static Map<String, String> getRelationshipExpandedKeyMapInternal(MNode relationship, EntityDefinition relEd) {
        Map<String, String> eKeyMap = [:]
        List<MNode> keyMapList = relationship.children("key-map")
        if (!keyMapList && ((String) relationship.attribute('type')).startsWith('one')) {
            // go through pks of related entity, assume field names match
            for (String pkFieldName in relEd.getPkFieldNames()) eKeyMap.put(pkFieldName, pkFieldName)
        } else {
            for (MNode keyMap in keyMapList) {
                String fieldName = keyMap.attribute('field-name')
                String relFn = keyMap.attribute('related-field-name') ?: fieldName
                if (!relEd.isField(relFn) && ((String) relationship.attribute('type')).startsWith("one")) {
                    List<String> pks = relEd.getPkFieldNames()
                    if (pks.size() == 1) relFn = pks.get(0)
                    // if we don't match these constraints and get this default we'll get an error later...
                }
                eKeyMap.put(fieldName, relFn)
            }
        }
        return eKeyMap
    }

    RelationshipInfo getRelationshipInfo(String relationshipName) {
        if (!relationshipName) return null
        return getRelationshipInfoMap().get(relationshipName)
    }
    Map<String, RelationshipInfo> getRelationshipInfoMap() {
        if (relationshipInfoMap == null) makeRelInfoMap()
        return relationshipInfoMap
    }
    private synchronized void makeRelInfoMap() {
        if (relationshipInfoMap != null) return
        Map<String, RelationshipInfo> relInfoMap = new HashMap<String, RelationshipInfo>()
        List<RelationshipInfo> relInfoList = getRelationshipsInfo(false)
        for (RelationshipInfo relInfo in relInfoList) {
            // always use the full relationshipName
            relInfoMap.put(relInfo.relationshipName, relInfo)
            // if there is a shortAlias add it under that
            if (relInfo.shortAlias) relInfoMap.put(relInfo.shortAlias, relInfo)
            // if there is no title, allow referring to the relationship by just the simple entity name (no package)
            if (!relInfo.title) relInfoMap.put(relInfo.relatedEd.getEntityName(), relInfo)
        }
        relationshipInfoMap = relInfoMap
    }

    List<RelationshipInfo> getRelationshipsInfo(boolean dependentsOnly) {
        if (relationshipInfoList == null) makeRelInfoList()

        if (!dependentsOnly) return new ArrayList(relationshipInfoList)
        // just get dependents
        List<RelationshipInfo> infoListCopy = []
        for (RelationshipInfo info in relationshipInfoList) if (info.dependent) infoListCopy.add(info)
        return infoListCopy
    }
    private synchronized void makeRelInfoList() {
        if (relationshipInfoList != null) return

        if (!this.expandedRelationshipList) {
            // make sure this is done before as this isn't done by default
            if (!hasReverseRelationships) efi.createAllAutoReverseManyRelationships()
            this.expandedRelationshipList = this.internalEntityNode.children("relationship")
        }

        List<RelationshipInfo> infoList = []
        for (MNode relNode in this.expandedRelationshipList) {
            RelationshipInfo relInfo = new RelationshipInfo(relNode, this, efi)
            infoList.add(relInfo)
        }
        relationshipInfoList = infoList
    }

    @CompileStatic
    static class RelationshipInfo {
        String type
        boolean isTypeOne
        String title
        String relatedEntityName
        EntityDefinition fromEd
        EntityDefinition relatedEd
        MNode relNode

        String relationshipName
        String shortAlias
        String prettyName
        Map<String, String> keyMap
        boolean dependent
        boolean mutable

        RelationshipInfo(MNode relNode, EntityDefinition fromEd, EntityFacadeImpl efi) {
            this.relNode = relNode
            this.fromEd = fromEd
            postInit(efi)
        }

        // getting weird runtime errors if this is CompileStatic
        void postInit(EntityFacadeImpl efi) {
            type = relNode.attribute('type')
            isTypeOne = type.startsWith("one")
            title = relNode.attribute('title') ?: ''
            relatedEntityName = relNode.attribute('related-entity-name')
            relatedEd = efi.getEntityDefinition(relatedEntityName)
            if (relatedEd == null) throw new EntityNotFoundException("Invalid entity relationship, ${relatedEntityName} not found in definition for entity ${fromEd.getFullEntityName()}")
            relatedEntityName = relatedEd.getFullEntityName()

            relationshipName = (title ? title + '#' : '') + relatedEntityName
            shortAlias = relNode.attribute('short-alias') ?: ''
            prettyName = relatedEd.getPrettyName(title, fromEd.internalEntityName)
            keyMap = getRelationshipExpandedKeyMapInternal(relNode, relatedEd)
            dependent = hasReverse()
            String mutableAttr = relNode.attribute('mutable')
            if (mutableAttr) {
                mutable = relNode.attribute('mutable') == "true"
            } else {
                // by default type one not mutable, type many are mutable
                mutable = !isTypeOne
            }
        }

        private boolean hasReverse() {
            MNode reverseRelNode = relatedEd.internalEntityNode.children("relationship").find(
                    { ((it.attribute("related-entity-name") == fromEd.internalEntityName || it.attribute("related-entity-name") == fromEd.fullEntityName)
                            && (it.attribute("type") == "one" || it.attribute("type") == "one-nofk")
                            && ((!title && !it.attribute("title")) || it.attribute("title") == title)) })
            return reverseRelNode != null
        }

        Map<String, Object> getTargetParameterMap(Map valueSource) {
            if (!valueSource) return [:]
            Map<String, Object> targetParameterMap = new HashMap<String, Object>()
            for (Map.Entry<String, String> keyEntry in keyMap.entrySet()) {
                Object value = valueSource.get(keyEntry.key)
                if (!StupidJavaUtilities.isEmpty(value)) targetParameterMap.put(keyEntry.value, value)
            }
            return targetParameterMap
        }

        String toString() { return "${relationshipName}${shortAlias ? ' (' + shortAlias + ')' : ''}, type ${type}, one? ${isTypeOne}, dependent? ${dependent}" }
    }

    MasterDefinition getMasterDefinition(String name) {
        if (!name) name = "default"
        if (masterDefinitionMap == null) makeMasterDefinitionMap()
        return masterDefinitionMap.get(name)
    }
    Map<String, MasterDefinition> getMasterDefinitionMap() {
        if (masterDefinitionMap == null) makeMasterDefinitionMap()
        return masterDefinitionMap
    }
    private synchronized void makeMasterDefinitionMap() {
        Map<String, MasterDefinition> defMap = [:]
        for (MNode masterNode in internalEntityNode.children("master")) {
            MasterDefinition curDef = new MasterDefinition(this, masterNode)
            defMap.put(curDef.name, curDef)
        }
        masterDefinitionMap = defMap
    }

    @CompileStatic
    static class MasterDefinition {
        String name
        ArrayList<MasterDetail> detailList = new ArrayList<MasterDetail>()
        MasterDefinition(EntityDefinition ed, MNode masterNode) {
            name = masterNode.attribute("name") ?: "default"
            List<MNode> detailNodeList = masterNode.children("detail")
            for (MNode detailNode in detailNodeList) detailList.add(new MasterDetail(ed, detailNode))
        }
    }
    @CompileStatic
    static class MasterDetail {
        String relationshipName
        EntityDefinition parentEd
        RelationshipInfo relInfo
        String relatedMasterName
        ArrayList<MasterDetail> internalDetailList = []
        MasterDetail(EntityDefinition parentEd, MNode detailNode) {
            this.parentEd = parentEd
            relationshipName = detailNode.attribute("relationship")
            relInfo = parentEd.getRelationshipInfo(relationshipName)
            if (relInfo == null) throw new IllegalArgumentException("Invalid relationship name [${relationshipName}] for entity ${parentEd.getFullEntityName()}")
            // logger.warn("Following relationship ${relationshipName}")

            List<MNode> detailNodeList = detailNode.children("detail")
            for (MNode childNode in detailNodeList) internalDetailList.add(new MasterDetail(relInfo.relatedEd, childNode))

            relatedMasterName = (String) detailNode.attribute("use-master")
        }

        ArrayList<MasterDetail> getDetailList() {
            if (relatedMasterName) {
                ArrayList<MasterDetail> combinedList = new ArrayList<MasterDetail>(internalDetailList)
                MasterDefinition relatedMaster = relInfo.relatedEd.getMasterDefinition(relatedMasterName)
                if (relatedMaster == null) throw new IllegalArgumentException("Invalid use-master value [${relatedMasterName}], master not found in entity ${relInfo.relatedEntityName}")
                // logger.warn("Including master ${relatedMasterName} on entity ${relInfo.relatedEd.getFullEntityName()}")

                combinedList.addAll(relatedMaster.detailList)

                return combinedList
            } else {
                return internalDetailList
            }
        }
    }

    EntityDependents getDependentsTree() {
        EntityDependents edp = new EntityDependents(this, null, null)
        return edp
    }

    static class EntityDependents {
        String entityName
        EntityDefinition ed
        Map<String, EntityDependents> dependentEntities = new TreeMap()
        Set<String> descendants = new TreeSet()
        Map<String, RelationshipInfo> relationshipInfos = new HashMap()

        EntityDependents(EntityDefinition ed, Deque<String> ancestorEntities, Map<String, EntityDependents> allDependents) {
            this.ed = ed
            entityName = ed.fullEntityName

            if (ancestorEntities == null) ancestorEntities = new LinkedList()
            ancestorEntities.addFirst(entityName)
            if (allDependents == null) allDependents = new HashMap<String, EntityDependents>()
            allDependents.put(entityName, this)

            List<RelationshipInfo> relInfoList = ed.getRelationshipsInfo(true)
            for (RelationshipInfo relInfo in relInfoList) {
                if (!relInfo.dependent) continue
                descendants.add(relInfo.relatedEntityName)
                String relName = relInfo.relationshipName
                relationshipInfos.put(relName, relInfo)
                // if (relInfo.shortAlias) edp.relationshipInfos.put((String) relInfo.shortAlias, relInfo)
                EntityDefinition relEd = ed.efi.getEntityDefinition((String) relInfo.relatedEntityName)
                if (!dependentEntities.containsKey(relName) && !ancestorEntities.contains(relEd.fullEntityName)) {
                    EntityDependents relEdp = allDependents.get(relEd.fullEntityName)
                    if (relEdp == null) relEdp = new EntityDependents(relEd, ancestorEntities, allDependents)
                    dependentEntities.put(relName, relEdp)
                }
            }

            ancestorEntities.removeFirst()
        }

        TreeSet<String> getAllDescendants() {
            TreeSet<String> allSet = new TreeSet()
            populateAllDescendants(allSet)
            return allSet
        }
        protected void populateAllDescendants(TreeSet<String> allSet) {
            allSet.addAll(descendants)
            for (EntityDependents edp in dependentEntities.values()) edp.populateAllDescendants(allSet)
        }

        String toString() {
            StringBuilder builder = new StringBuilder(10000)
            Set<String> entitiesVisited = new HashSet<>()
            buildString(builder, 0, entitiesVisited)
            return builder.toString()
        }
        static final String indentBase = '- '
        void buildString(StringBuilder builder, int level, Set<String> entitiesVisited) {
            StringBuilder ib = new StringBuilder()
            for (int i = 0; i <= level; i++) ib.append(indentBase)
            String indent = ib.toString()

            for (Map.Entry<String, EntityDependents> entry in dependentEntities) {
                RelationshipInfo relInfo = relationshipInfos.get(entry.getKey())
                builder.append(indent).append(relInfo.relationshipName).append(' ').append(relInfo.keyMap).append('\n')
                if (level < 8 && !entitiesVisited.contains(entry.getValue().entityName)) {
                    entry.getValue().buildString(builder, level+1, entitiesVisited)
                    entitiesVisited.add(entry.getValue().entityName)
                } else if (entitiesVisited.contains(entry.getValue().entityName)) {
                    builder.append(indent).append(indentBase).append('Dependants already displayed\n')
                } else if (level == 8) {
                    builder.append(indent).append(indentBase).append('Reached level limit\n')
                }
            }
        }
    }

    String getPrettyName(String title, String baseName) {
        StringBuilder prettyName = new StringBuilder()
        for (String part in internalEntityName.split("(?=[A-Z])")) {
            if (baseName && part == baseName) continue
            if (prettyName) prettyName.append(" ")
            prettyName.append(part)
        }
        if (title) {
            boolean addParens = prettyName as boolean
            if (addParens) prettyName.append(" (")
            for (String part in title.split("(?=[A-Z])")) prettyName.append(part).append(" ")
            prettyName.deleteCharAt(prettyName.length()-1)
            if (addParens) prettyName.append(")")
        }
        return prettyName.toString()
    }

    String getColumnName(String fieldName, boolean includeFunctionAndComplex) {
        FieldInfo fieldInfo = this.getFieldInfo(fieldName)
        if (fieldInfo == null) {
            throw new EntityException("Invalid field-name [${fieldName}] for the [${this.getFullEntityName()}] entity")
        }
        return fieldInfo.fullColumnName
    }

    protected String getBasicFieldColName(MNode entityNode, String entityAlias, String fieldName) {
        MNode memberEntity = memberEntityAliasMap.get(entityAlias)
        if (memberEntity == null) throw new EntityException("Could not find member-entity with entity-alias [${entityAlias}] in view-entity [${getFullEntityName()}]")
        EntityDefinition memberEd = this.efi.getEntityDefinition(memberEntity.attribute("entity-name"))
        return memberEd.getColumnName(fieldName, false)
    }

    protected void buildComplexAliasName(MNode parentNode, String operator, StringBuilder colNameBuilder) {
        //TODO expand expression against current context
        colNameBuilder.append(parentNode.attribute('expression') ?: '')
        if (parentNode.children.isEmpty()) {
            return
        }
        colNameBuilder.append('(')
        boolean isFirst = true
        for (MNode childNode in parentNode.children) {
            if (isFirst) isFirst=false else colNameBuilder.append(' ').append(operator).append(' ')

            if (childNode.name == "complex-alias") {
                buildComplexAliasName(childNode, (String) childNode.attribute("operator"), colNameBuilder)
            } else if (childNode.name == "complex-alias-field") {
                String entityAlias = childNode.attribute("entity-alias")
                String basicColName = getBasicFieldColName(internalEntityNode, entityAlias, (String) childNode.attribute("field"))
                String colName = entityAlias + "." + basicColName
                String defaultValue = childNode.attribute("default-value")
                String function = childNode.attribute("function")

                if (defaultValue) {
                    colName = "COALESCE(" + colName + "," + defaultValue + ")"
                }
                if (function) {
                    String prefix = getFunctionPrefix(function)
                    colName = prefix + colName + ")"
                }

                colNameBuilder.append(colName)
            }
        }
        colNameBuilder.append(')')
    }

    static protected String getFunctionPrefix(String function) {
        return (function == "count-distinct") ? "COUNT(DISTINCT " : function.toUpperCase() + '('
    }

    /** Returns the table name, ie table-name or converted entity-name */
    String getTableName() { return tableNameAttr }
    String getFullTableName() { return fullTableNameVal }
    String getSchemaName() { return schemaNameVal }

    boolean isField(String fieldName) { return getFieldInfo(fieldName) != null }
    boolean isPkField(String fieldName) {
        FieldInfo fieldInfo = getFieldInfo(fieldName)
        if (fieldInfo == null) return false
        return fieldInfo.isPk
    }
    boolean isSimpleField(String fieldName) {
        FieldInfo fieldInfo = getFieldInfo(fieldName)
        if (fieldInfo == null) return false
        return fieldInfo.isSimple
    }

    boolean containsPrimaryKey(Map fields) {
        // low level code, avoid Groovy booleanUnbox
        if (fields == null || fields.size() == 0) return false
        ArrayList<String> fieldNameList = this.getPkFieldNames()
        int size = fieldNameList.size()
        for (int i = 0; i < size; i++) {
            String fieldName = fieldNameList.get(i)
            Object fieldValue = fields.get(fieldName)
            if (StupidJavaUtilities.isEmpty(fieldValue)) return false
        }
        return true
    }
    Map<String, Object> getPrimaryKeys(Map fields) {
        Map<String, Object> pks = new HashMap()
        ArrayList<String> fieldNameList = this.getPkFieldNames()
        int size = fieldNameList.size()
        for (int i = 0; i < size; i++) {
            String fieldName = fieldNameList.get(i)
            pks.put(fieldName, fields.get(fieldName))
        }
        return pks
    }

    ArrayList<String> getFieldNames(boolean includePk, boolean includeNonPk, boolean includeUserFields) {
        ArrayList<String> baseList
        // common case, do it fast
        if (includePk) {
            if (includeNonPk) {
                baseList = getAllFieldNames(false)
            } else {
                baseList = getPkFieldNames()
            }
        } else {
            if (includeNonPk) {
                baseList = getNonPkFieldNames()
            } else {
                // all false is weird, but okay
                baseList = new ArrayList<String>()
            }
        }
        if (!includeUserFields) return baseList

        ListOrderedSet userFieldNames = getUserFieldNames()
        if (userFieldNames != null && userFieldNames.size() > 0) {
            List<String> returnList = new ArrayList<String>()
            returnList.addAll(baseList)
            returnList.addAll(userFieldNames.asList())
            return returnList
        } else {
            return baseList
        }
    }
    protected ListOrderedSet getFieldNamesInternal(boolean includePk, boolean includeNonPk) {
        ListOrderedSet nameSet = new ListOrderedSet()
        String nodeName = this.isViewEntity() ? "alias" : "field"
        for (MNode node in this.internalEntityNode.children(nodeName)) {
            if ((includePk && 'true'.equals(node.attribute('is-pk'))) || (includeNonPk && !'true'.equals(node.attribute('is-pk')))) {
                nameSet.add(node.attribute('name'))
            }
        }
        return nameSet
    }
    protected ListOrderedSet getUserFieldNames() {
        ListOrderedSet userFieldNames = null
        if (allowUserField && !this.isViewEntity() && (hasUserFields == null || hasUserFields == Boolean.TRUE)) {
            boolean alreadyDisabled = efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz()
            try {
                EntityList userFieldList = efi.find("moqui.entity.UserField").condition("entityName", getFullEntityName()).useCache(true).list()
                if (userFieldList) {
                    hasUserFields = true
                    userFieldNames = new ListOrderedSet()

                    Set<String> userGroupIdSet = efi.getEcfi().getExecutionContext().getUser().getUserGroupIdSet()
                    for (EntityValue userField in userFieldList) {
                        if (userGroupIdSet.contains(userField.get('userGroupId'))) userFieldNames.add((String) userField.get('fieldName'))
                    }
                } else {
                    hasUserFields = false
                }
            } finally {
                if (!alreadyDisabled) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
            }
        }
        return userFieldNames
    }

    ArrayList<String> getPkFieldNames() { return pkFieldNameList }
    ArrayList<String> getNonPkFieldNames() { return nonPkFieldNameList }
    ArrayList<String> getAllFieldNames() { return getAllFieldNames(true) }
    ArrayList<String> getAllFieldNames(boolean includeUserFields) {
        if (!includeUserFields) return allFieldNameList

        ListOrderedSet userFieldNames = getUserFieldNames()
        if (userFieldNames != null && userFieldNames.size() > 0) {
            List<String> returnList = new ArrayList<>(allFieldNameList.size() + userFieldNames.size())
            returnList.addAll(allFieldNameList)
            returnList.addAll(userFieldNames.asList())
            return returnList
        } else {
            return allFieldNameList
        }
    }

    Map<String, String> pkFieldDefaults = null
    Map<String, String> nonPkFieldDefaults = null
    boolean hasFieldDefaults() { return hasFieldDefaultsVal }
    Map<String, String> getPkFieldDefaults() {
        if (pkFieldDefaults == null) {
            Map<String, String> newDefaults = [:]
            for (MNode fieldNode in getFieldNodes(true, false, false)) {
                String defaultStr = fieldNode.attribute('default')
                if (!defaultStr) continue
                newDefaults.put(fieldNode.attribute('name'), defaultStr)
            }
            pkFieldDefaults = newDefaults
        }
        return pkFieldDefaults
    }
    Map<String, String> getNonPkFieldDefaults() {
        if (nonPkFieldDefaults == null) {
            Map<String, String> newDefaults = [:]
            for (MNode fieldNode in getFieldNodes(false, true, false)) {
                String defaultStr = fieldNode.attribute('default')
                if (!defaultStr) continue
                newDefaults.put(fieldNode.attribute('name'), defaultStr)
            }
            nonPkFieldDefaults = newDefaults
        }
        return nonPkFieldDefaults
    }

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

    List<String> getFieldEnums(FieldInfo fi) {
        // populate enum values for Enumeration and StatusItem
        // find first relationship that has this field as the only key map and is not a many relationship
        RelationshipInfo oneRelInfo = null
        List<RelationshipInfo> allRelInfoList = getRelationshipsInfo(false)
        for (RelationshipInfo relInfo in allRelInfoList) {
            Map km = relInfo.keyMap
            if (km.size() == 1 && km.containsKey(fi.name) && relInfo.type == "one" && relInfo.relNode.attribute("is-auto-reverse") != "true") {
                oneRelInfo = relInfo
                break;
            }
        }
        if (oneRelInfo != null && oneRelInfo.title) {
            if (oneRelInfo.relatedEd.getFullEntityName() == 'moqui.basic.Enumeration') {
                EntityList enumList = efi.find("moqui.basic.Enumeration").condition("enumTypeId", oneRelInfo.title)
                        .orderBy("sequenceNum,enumId").disableAuthz().list()
                if (enumList) {
                    List<String> enumIdList = []
                    for (EntityValue ev in enumList) enumIdList.add((String) ev.enumId)
                    return enumIdList
                }
            } else if (oneRelInfo.relatedEd.getFullEntityName() == 'moqui.basic.StatusItem') {
                EntityList statusList = efi.find("moqui.basic.StatusItem").condition("statusTypeId", oneRelInfo.title)
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

    Map getJsonSchema(boolean pkOnly, boolean standalone, Map<String, Object> definitionsMap, String schemaUri, String linkPrefix,
                      String schemaLinkPrefix, boolean nestRelationships, String masterName, MasterDetail masterDetail) {
        String name = getShortAlias() ?: getFullEntityName()
        String prettyName = getPrettyName(null, null)
        String refName = name
        if (masterName) {
            refName = "${name}.${masterName}"
            prettyName = prettyName + " (Master: ${masterName})"
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
        ArrayList<String> allFields = pkOnly ? getPkFieldNames() : getAllFieldNames(true)
        for (int i = 0; i < allFields.size(); i++) {
            FieldInfo fi = getFieldInfo(allFields.get(i))
            Map<String, Object> propMap = [:]
            propMap.put('type', fieldTypeJsonMap.get(fi.type))
            String format = fieldTypeJsonFormatMap.get(fi.type)
            if (format) propMap.put('format', format)
            properties.put(fi.name, propMap)

            List enumList = getFieldEnums(fi)
            if (enumList) propMap.put('enum', enumList)
        }


        // put current schema in Map before nesting for relationships, avoid infinite recursion with entity rel loops
        if (standalone && definitionsMap == null) {
            definitionsMap = [:]
            definitionsMap.put('paginationParameters', jsonPaginationParameters)
        }
        if (definitionsMap != null && !definitionsMap.containsKey(name))
            definitionsMap.put(refName, schema)

        if (!pkOnly && (masterName || masterDetail != null)) {
            // add only relationships from master definition or detail
            List<MasterDetail> detailList
            if (masterName) {
                MasterDefinition masterDef = getMasterDefinition(masterName)
                if (masterDef == null) throw new IllegalArgumentException("Master name ${masterName} not valid for entity ${getFullEntityName()}")
                detailList = masterDef.detailList
            } else {
                detailList = masterDetail.getDetailList()
            }
            for (MasterDetail childMasterDetail in detailList) {
                RelationshipInfo relInfo = childMasterDetail.relInfo
                String relationshipName = relInfo.relationshipName
                String entryName = relInfo.shortAlias ?: relationshipName
                String relatedRefName = relInfo.relatedEd.shortAlias ?: relInfo.relatedEd.getFullEntityName()
                if (pkOnly) relatedRefName = relatedRefName + ".PK"

                // recurse, let it put itself in the definitionsMap
                // linkPrefix and schemaLinkPrefix are null so that no links are added for master dependents
                if (definitionsMap != null && !definitionsMap.containsKey(relatedRefName))
                    relInfo.relatedEd.getJsonSchema(pkOnly, false, definitionsMap, schemaUri, null, null, false, null, childMasterDetail)

                if (relInfo.type == "many") {
                    properties.put(entryName, [type:'array', items:['$ref':('#/definitions/' + relatedRefName)]])
                } else {
                    properties.put(entryName, ['$ref':('#/definitions/' + relatedRefName)])
                }
            }
        } else if (!pkOnly && nestRelationships) {
            // add all relationships, nest
            List<RelationshipInfo> relInfoList = getRelationshipsInfo(true)
            for (RelationshipInfo relInfo in relInfoList) {
                String relationshipName = relInfo.relationshipName
                String entryName = relInfo.shortAlias ?: relationshipName
                String relatedRefName = relInfo.relatedEd.shortAlias ?: relInfo.relatedEd.getFullEntityName()
                if (pkOnly) relatedRefName = relatedRefName + ".PK"

                // recurse, let it put itself in the definitionsMap
                if (definitionsMap != null && !definitionsMap.containsKey(relatedRefName))
                    relInfo.relatedEd.getJsonSchema(pkOnly, false, definitionsMap, schemaUri, linkPrefix, schemaLinkPrefix, nestRelationships, null, null)

                if (relInfo.type == "many") {
                    properties.put(entryName, [type:'array', items:['$ref':('#/definitions/' + relatedRefName)]])
                } else {
                    properties.put(entryName, ['$ref':('#/definitions/' + relatedRefName)])
                }
            }
        }

        // add links (for Entity REST API)
        if (linkPrefix || schemaLinkPrefix) {
            List<String> pkNameList = getPkFieldNames()
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

    Map<String, Object> getRamlFieldMap(FieldInfo fi) {
        Map<String, Object> propMap = [:]
        String description = fi.fieldNode.first("description")?.text
        if (description) propMap.put("description", description)
        propMap.put('type', fieldTypeRamlMap.get(fi.type))

        List enumList = getFieldEnums(fi)
        if (enumList) propMap.put('enum', enumList)
        return propMap
    }

    Map<String, Object> getRamlTypeMap(boolean pkOnly, Map<String, Object> typesMap, String masterName, MasterDetail masterDetail) {
        String name = getShortAlias() ?: getFullEntityName()
        String prettyName = getPrettyName(null, null)
        String refName = name
        if (masterName) {
            refName = "${name}.${masterName}"
            prettyName = prettyName + " (Master: ${masterName})"
        }

        Map properties = [:]
        Map<String, Object> typeMap = [displayName:prettyName, type:'object', properties:properties] as Map<String, Object>

        if (typesMap != null && !typesMap.containsKey(name)) typesMap.put(refName, typeMap)

        // add field properties
        ArrayList<String> allFields = pkOnly ? getPkFieldNames() : getAllFieldNames(true)
        for (int i = 0; i < allFields.size(); i++) {
            FieldInfo fi = getFieldInfo(allFields.get(i))
            properties.put(fi.name, getRamlFieldMap(fi))
        }

        // for master add related properties
        if (!pkOnly && (masterName || masterDetail != null)) {
            // add only relationships from master definition or detail
            List<MasterDetail> detailList
            if (masterName) {
                MasterDefinition masterDef = getMasterDefinition(masterName)
                if (masterDef == null) throw new IllegalArgumentException("Master name ${masterName} not valid for entity ${getFullEntityName()}")
                detailList = masterDef.detailList
            } else {
                detailList = masterDetail.getDetailList()
            }
            for (MasterDetail childMasterDetail in detailList) {
                RelationshipInfo relInfo = childMasterDetail.relInfo
                String relationshipName = relInfo.relationshipName
                String entryName = relInfo.shortAlias ?: relationshipName
                String relatedRefName = relInfo.relatedEd.shortAlias ?: relInfo.relatedEd.getFullEntityName()

                // recurse, let it put itself in the definitionsMap
                if (typesMap != null && !typesMap.containsKey(relatedRefName))
                    relInfo.relatedEd.getRamlTypeMap(pkOnly, typesMap, null, childMasterDetail)

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

    Map getRamlApi(String masterName) {
        String name = getShortAlias() ?: getFullEntityName()
        if (masterName) name = "${name}/${masterName}"
        String prettyName = getPrettyName(null, null)

        Map<String, Object> ramlMap = [:]

        // setup field info
        Map qpMap = [:]
        ArrayList<String> allFields = getAllFieldNames(true)
        for (int i = 0; i < allFields.size(); i++) {
            FieldInfo fi = getFieldInfo(allFields.get(i))
            qpMap.put(fi.name, getRamlFieldMap(fi))
        }

        // get list
        // TODO: make body array of schema
        ramlMap.put('get', [is:['paged'], description:"Get list of ${prettyName}".toString(), queryParameters:qpMap,
                            responses:[200:[body:['application/json': [schema:name]]]]])
        // create
        ramlMap.put('post', [description:"Create ${prettyName}".toString(), body:['application/json': [schema:name]]])

        // under IDs for single record operations
        List<String> pkNameList = getPkFieldNames()
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

    void addToSwaggerMap(Map<String, Object> swaggerMap, String masterName) {
        EntityDefinition ed = efi.getEntityDefinition(entityName)
        if (ed == null) throw new IllegalArgumentException("Entity ${entityName} not found")
        // Node entityNode = ed.getEntityNode()

        Map definitionsMap = ((Map) swaggerMap.definitions)
        String refDefName = ed.getShortAlias() ?: ed.getFullEntityName()
        if (masterName) refDefName = refDefName + "." + masterName
        String refDefNamePk = refDefName + ".PK"

        String entityDescription = ed.getEntityNode().first("description")?.text

        // add responses
        Map responses = ["401":[description:"Authentication required"], "403":[description:"Access Forbidden (no authz)"],
                         "404":[description:"Value Not Found"], "429":[description:"Too Many Requests (tarpit)"],
                         "500":[description:"General Error"]]

        // entity path (no ID)
        String entityPath = "/" + (ed.getShortAlias() ?: ed.getFullEntityName())
        if (masterName) entityPath = entityPath + "/" + masterName
        Map<String, Map<String, Object>> entityResourceMap = [:]
        ((Map) swaggerMap.paths).put(entityPath, entityResourceMap)

        // get - list
        List<Map> listParameters = []
        listParameters.addAll(swaggerPaginationParameters)
        for (String fieldName in getAllFieldNames(false)) {
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
        for (String pkName in getPkFieldNames()) {
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
        definitionsMap.put(refDefName, ed.getJsonSchema(false, false, definitionsMap, null, null, null, false, masterName, null))
        definitionsMap.put(refDefNamePk, ed.getJsonSchema(true, false, null, null, null, null, false, masterName, null))
    }

    List<MNode> getFieldNodes(boolean includePk, boolean includeNonPk, boolean includeUserFields) {
        // NOTE: this is not necessarily the fastest way to do this, if it becomes a performance problem replace it with a local List of field Nodes
        List<MNode> nodeList = new ArrayList<MNode>()
        String nodeName = this.isViewEntity() ? "alias" : "field"
        for (MNode node in this.internalEntityNode.children(nodeName)) {
            if ((includePk && node.attribute("is-pk") == "true") || (includeNonPk && node.attribute("is-pk") != "true")) {
                nodeList.add(node)
            }
        }

        if (includeUserFields && allowUserField && !this.isViewEntity()) {
            boolean alreadyDisabled = efi.getEcfi().getExecutionContext().getArtifactExecution().disableAuthz()
            try {
                EntityList userFieldList = efi.find("moqui.entity.UserField").condition("entityName", getFullEntityName()).useCache(true).list()
                if (userFieldList) {
                    Set<String> userGroupIdSet = efi.getEcfi().getExecutionContext().getUser().getUserGroupIdSet()
                    for (EntityValue userField in userFieldList) {
                        if (userGroupIdSet.contains(userField.userGroupId)) {
                            nodeList.add(makeUserFieldNode(userField))
                            break
                        }
                    }
                }
            } finally {
                if (!alreadyDisabled) efi.getEcfi().getExecutionContext().getArtifactExecution().enableAuthz()
            }
        }

        return nodeList
    }

    // used in EntityCache for view entities
    Map<String, String> getMePkFieldToAliasNameMap(String entityAlias) {
        if (mePkFieldToAliasNameMapMap == null) mePkFieldToAliasNameMapMap = new HashMap<String, Map>()
        Map<String, String> mePkFieldToAliasNameMap = (Map<String, String>) mePkFieldToAliasNameMapMap.get(entityAlias)

        //logger.warn("TOREMOVE 1 getMePkFieldToAliasNameMap entityAlias=${entityAlias} cached value=${mePkFieldToAliasNameMap}; entityNode=${entityNode}")
        if (mePkFieldToAliasNameMap != null) return mePkFieldToAliasNameMap

        mePkFieldToAliasNameMap = new HashMap<String, String>()

        // do a reverse map on member-entity pk fields to view-entity aliases
        MNode memberEntityNode = memberEntityAliasMap.get(entityAlias)
        //logger.warn("TOREMOVE 2 getMePkFieldToAliasNameMap entityAlias=${entityAlias} memberEntityNode=${memberEntityNode}")
        EntityDefinition med = this.efi.getEntityDefinition(memberEntityNode.attribute("entity-name"))
        List<String> pkFieldNames = med.getPkFieldNames()
        for (String pkName in pkFieldNames) {
            MNode matchingAliasNode = entityNode.children("alias").find({
                it.attribute("entity-alias") == memberEntityNode.attribute("entity-alias") &&
                (it.attribute("field") == pkName || (!it.attribute("field") && it.attribute("name") == pkName)) })
            //logger.warn("TOREMOVE 3 getMePkFieldToAliasNameMap entityAlias=${entityAlias} for pkName=${pkName}, matchingAliasNode=${matchingAliasNode}")
            if (matchingAliasNode) {
                // found an alias Node
                mePkFieldToAliasNameMap.put(pkName, matchingAliasNode.attribute("name"))
                continue
            }

            // no alias, try to find in join key-maps that map to other aliased fields

            // first try the current member-entity
            if (memberEntityNode.attribute("join-from-alias") && memberEntityNode.hasChild("key-map")) {
                boolean foundOne = false
                for (MNode keyMapNode in memberEntityNode.children("key-map")) {
                    //logger.warn("TOREMOVE 4 getMePkFieldToAliasNameMap entityAlias=${entityAlias} for pkName=${pkName}, keyMapNode=${keyMapNode}")
                    if (keyMapNode.attribute("related-field-name") == pkName ||
                            (!keyMapNode.attribute("related-field-name") && keyMapNode.attribute("field-name") == pkName)) {
                        String relatedPkName = keyMapNode.attribute("field-name")
                        MNode relatedMatchingAliasNode = entityNode.children("alias").find({
                            it.attribute("entity-alias") == memberEntityNode.attribute("join-from-alias") &&
                            (it.attribute("field") == relatedPkName || (!it.attribute("field") && it.attribute("name") == relatedPkName)) })
                        //logger.warn("TOREMOVE 5 getMePkFieldToAliasNameMap entityAlias=${entityAlias} for pkName=${pkName}, relatedAlias=${memberEntityNode.'@join-from-alias'}, relatedPkName=${relatedPkName}, relatedMatchingAliasNode=${relatedMatchingAliasNode}")
                        if (relatedMatchingAliasNode) {
                            mePkFieldToAliasNameMap.put(pkName, relatedMatchingAliasNode.attribute("name"))
                            foundOne = true
                            break
                        }
                    }
                }
                if (foundOne) continue
            }

            // then go through all other member-entity that might relate back to this one
            for (MNode relatedMeNode in entityNode.children("member-entity")) {
                if (relatedMeNode.attribute("join-from-alias") == memberEntityNode.attribute("entity-alias") && relatedMeNode.hasChild("key-map")) {
                    boolean foundOne = false
                    for (MNode keyMapNode in relatedMeNode.children("key-map")) {
                        if (keyMapNode.attribute("field-name") == pkName) {
                            String relatedPkName = keyMapNode.attribute("related-field-name") ?: keyMapNode.attribute("field-name")
                            MNode relatedMatchingAliasNode = entityNode.children("alias").find({
                                it.attribute("entity-alias") == relatedMeNode.attribute("entity-alias") &&
                                (it.attribute("field") == relatedPkName || (!it.attribute("field") && it.attribute("name") == relatedPkName)) })
                            if (relatedMatchingAliasNode) {
                                mePkFieldToAliasNameMap.put(pkName, relatedMatchingAliasNode.attribute("name"))
                                foundOne = true
                                break
                            }
                        }
                    }
                    if (foundOne) break
                }
            }
        }

        if (pkFieldNames.size() != mePkFieldToAliasNameMap.size()) {
            logger.warn("Not all primary-key fields in view-entity [${fullEntityName}] for member-entity [${memberEntityNode.attribute("entity-name")}], skipping cache reverse-association, and note that if this record is updated the cache won't automatically clear; pkFieldNames=${pkFieldNames}; partial mePkFieldToAliasNameMap=${mePkFieldToAliasNameMap}")
        }

        return mePkFieldToAliasNameMap
    }

    Map cloneMapRemoveFields(Map theMap, Boolean pks) {
        Map newMap = new LinkedHashMap(theMap)
        ArrayList<String> fieldNameList = (pks != null ? this.getFieldNames(pks, !pks, !pks) : this.getAllFieldNames())
        int size = fieldNameList.size()
        for (int i = 0; i < size; i++) {
            String fieldName = fieldNameList.get(i)
            if (newMap.containsKey(fieldName)) newMap.remove(fieldName)
        }
        return newMap
    }

    void setFields(Map<String, Object> src, Map<String, Object> dest, boolean setIfEmpty, String namePrefix, Boolean pks) {
        if (src == null || dest == null) return

        ExecutionContextImpl eci = efi.ecfi.getEci()
        boolean destIsEntityValueBase = dest instanceof EntityValueBase
        EntityValueBase destEvb = destIsEntityValueBase ? (EntityValueBase) dest : null

        boolean hasNamePrefix = namePrefix != null && namePrefix.length() > 0
        boolean srcIsEntityValueBase = src instanceof EntityValueBase
        EntityValueBase evb = srcIsEntityValueBase ? (EntityValueBase) src : null
        ArrayList<String> fieldNameList = pks != null ? this.getFieldNames(pks, !pks, !pks) : this.getAllFieldNames()
        // use integer iterator, saves quite a bit of time, improves time for this method by about 20% with this alone
        int size = fieldNameList.size()
        for (int i = 0; i < size; i++) {
            String fieldName = (String) fieldNameList.get(i)
            String sourceFieldName
            if (hasNamePrefix) {
                sourceFieldName = namePrefix + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1)
            } else {
                sourceFieldName = fieldName
            }

            Object value = srcIsEntityValueBase? evb.getValueMap().get(sourceFieldName) : src.get(sourceFieldName)
            if (value != null || (srcIsEntityValueBase ? evb.isFieldSet(sourceFieldName) : src.containsKey(sourceFieldName))) {
                boolean isCharSequence = false
                boolean isEmpty = false
                if (value == null) {
                    isEmpty = true
                } else if (value instanceof CharSequence) {
                    isCharSequence = true
                    if (value.length() == 0) isEmpty = true
                }

                if (!isEmpty) {
                    if (isCharSequence) {
                        try {
                            Object converted = convertFieldString(fieldName, value.toString(), eci)
                            if (destIsEntityValueBase) destEvb.putNoCheck(fieldName, converted) else dest.put(fieldName, converted)
                        } catch (BaseException be) {
                            eci.message.addValidationError(null, fieldName, null, be.getMessage(), be)
                        }
                    } else {
                        if (destIsEntityValueBase) destEvb.putNoCheck(fieldName, value) else dest.put(fieldName, value)
                    }
                } else if (setIfEmpty && src.containsKey(sourceFieldName)) {
                    // treat empty String as null, otherwise set as whatever null or empty type it is
                    if (value != null && isCharSequence) {
                        if (destIsEntityValueBase) destEvb.putNoCheck(fieldName, null) else dest.put(fieldName, null)
                    } else {
                        if (destIsEntityValueBase) destEvb.putNoCheck(fieldName, value) else dest.put(fieldName, value)
                    }
                }
            }
        }
    }
    void setFieldsEv(Map<String, Object> src, EntityValueBase dest, Boolean pks) {
        // like above with setIfEmpty=true, namePrefix=null, pks=null
        if (src == null || dest == null) return

        ExecutionContextImpl eci = efi.ecfi.getEci()
        boolean srcIsEntityValueBase = src instanceof EntityValueBase
        EntityValueBase evb = srcIsEntityValueBase ? (EntityValueBase) src : null
        // ArrayList<String> fieldNameList = pks != null ? this.getFieldNames(pks, !pks, !pks) : this.getAllFieldNames()
        ArrayList<FieldInfo> fieldInfoList = pks == null ? getAllFieldInfoList() :
                (pks == Boolean.TRUE ? getPkFieldInfoList() : getNonPkFieldInfoList())
        // use integer iterator, saves quite a bit of time, improves time for this method by about 20% with this alone
        int size = fieldInfoList.size()
        for (int i = 0; i < size; i++) {
            FieldInfo fi = (FieldInfo) fieldInfoList.get(i)
            String fieldName = fi.name

            Object value = srcIsEntityValueBase? evb.getValueMap().get(fieldName) : src.get(fieldName)
            if (value != null || (srcIsEntityValueBase ? evb.isFieldSet(fieldName) : src.containsKey(fieldName))) {
                boolean isCharSequence = false
                boolean isEmpty = false
                if (value == null) {
                    isEmpty = true
                } else if (value instanceof CharSequence) {
                    isCharSequence = true
                    if (value.length() == 0) isEmpty = true
                }

                if (!isEmpty) {
                    if (isCharSequence) {
                        try {
                            Object converted = convertFieldInfoString(fi, value.toString(), eci)
                            dest.putNoCheck(fieldName, converted)
                        } catch (BaseException be) {
                            eci.message.addValidationError(null, fieldName, null, be.getMessage(), be)
                        }
                    } else {
                        dest.putNoCheck(fieldName, value)
                    }
                } else if (src.containsKey(fieldName)) {
                    // treat empty String as null, otherwise set as whatever null or empty type it is
                    if (value != null && isCharSequence) {
                        dest.putNoCheck(fieldName, null)
                    } else {
                        dest.putNoCheck(fieldName, value)
                    }
                }
            }
        }
    }

    Object convertFieldString(String name, String value, ExecutionContextImpl eci) {
        if (value == null) return null
        FieldInfo fieldInfo = getFieldInfo(name)
        if (fieldInfo == null) throw new EntityException("The name [${name}] is not a valid field name for entity [${entityName}]")
        return convertFieldInfoString(fieldInfo, value, eci)
    }

    Object convertFieldInfoString(FieldInfo fi, String value, ExecutionContextImpl eci) {
        if (value == null) return null
        if ('null'.equals(value)) return null
        return EntityJavaUtil.convertFromString(value, fi, eci.getL10nFacade())
    }

    String getFieldString(String name, Object value) {
        if (value == null) return null
        FieldInfo fi = getFieldInfo(name)
        return getFieldInfoString(fi, value)
    }
    String getFieldInfoString(FieldInfo fi, Object value) {
        if (value == null) return null
        return EntityJavaUtil.convertToString(value, fi, efi)
    }

    String getFieldStringForFile(String name, Object value) {
        if (value == null) return null

        String outValue
        if (value instanceof Timestamp) {
            // use a Long number, no TZ issues
            outValue = value.getTime() as String
        } else if (value instanceof BigDecimal) {
            outValue = value.toPlainString()
        } else {
            outValue = getFieldString(name, value)
        }

        return outValue
    }

    protected void expandAliasAlls() {
        if (!isViewEntity()) return
        for (MNode aliasAll in this.internalEntityNode.children("alias-all")) {
            MNode memberEntity = memberEntityAliasMap.get(aliasAll.attribute("entity-alias"))
            if (memberEntity == null) {
                logger.error("In view-entity ${getFullEntityName()} in alias-all with entity-alias [${aliasAll.attribute("entity-alias")}], member-entity with same entity-alias not found, ignoring")
                continue
            }

            EntityDefinition aliasedEntityDefinition = this.efi.getEntityDefinition(memberEntity.attribute("entity-name"))
            if (aliasedEntityDefinition == null) {
                logger.error("Entity [${memberEntity.attribute("entity-name")}] referred to in member-entity with entity-alias [${aliasAll.attribute("entity-alias")}] not found, ignoring")
                continue
            }

            for (MNode fieldNode in aliasedEntityDefinition.getFieldNodes(true, true, false)) {
                // never auto-alias these
                if (fieldNode.attribute("name") == "lastUpdatedStamp") continue
                // if specified as excluded, leave it out
                if (aliasAll.children("exclude").find({ it.attribute("field") == fieldNode.attribute("name")})) continue

                String aliasName = fieldNode.attribute("name")
                if (aliasAll.attribute("prefix")) {
                    StringBuilder newAliasName = new StringBuilder(aliasAll.attribute("prefix"))
                    newAliasName.append(Character.toUpperCase(aliasName.charAt(0)))
                    newAliasName.append(aliasName.substring(1))
                    aliasName = newAliasName.toString()
                }

                MNode existingAliasNode = this.internalEntityNode.children('alias').find({ it.attribute("name") == aliasName })
                if (existingAliasNode) {
                    //log differently if this is part of a member-entity view link key-map because that is a common case when a field will be auto-expanded multiple times
                    boolean isInViewLink = false
                    for (MNode viewMeNode in this.internalEntityNode.children("member-entity")) {
                        boolean isRel = false
                        if (viewMeNode.attribute("entity-alias") == aliasAll.attribute("entity-alias")) {
                            isRel = true
                        } else if (viewMeNode.attribute("join-from-alias") != aliasAll.attribute("entity-alias")) {
                            // not the rel-entity-alias or the entity-alias, so move along
                            continue;
                        }
                        for (MNode keyMap in viewMeNode.children("key-map")) {
                            if (!isRel && keyMap.attribute("field-name") == fieldNode.attribute("name")) {
                                isInViewLink = true
                                break
                            } else if (isRel && (keyMap.attribute("related-field-name") ?: keyMap.attribute("field-name")) == fieldNode.attribute("name")) {
                                isInViewLink = true
                                break
                            }
                        }
                        if (isInViewLink) break
                    }

                    // already exists... probably an override, but log just in case
                    String warnMsg = "Throwing out field alias in view entity " + this.getFullEntityName() +
                            " because one already exists with the alias name [" + aliasName + "] and field name [" +
                            memberEntity.attribute("entity-alias") + "(" + aliasedEntityDefinition.getFullEntityName() + ")." +
                            fieldNode.attribute("name") + "], existing field name is [" + existingAliasNode.attribute("entity-alias") + "." +
                            existingAliasNode.attribute("field") + "]"
                    if (isInViewLink) {if (logger.isTraceEnabled()) logger.trace(warnMsg)} else {logger.info(warnMsg)}

                    // ship adding the new alias
                    continue
                }

                MNode newAlias = this.internalEntityNode.append("alias",
                        [name:aliasName, field:fieldNode.attribute("name"), "entity-alias":aliasAll.attribute("entity-alias"),
                        "is-from-alias-all":"true"])
                if (fieldNode.hasChild("description")) newAlias.append(fieldNode.first("description"))
            }
        }
    }

    EntityConditionImplBase makeViewWhereCondition() {
        if (!this.isViewEntity() || entityConditionNode == null) return null
        // add the view-entity.entity-condition.econdition(s)
        return makeViewListCondition(entityConditionNode)
    }
    EntityConditionImplBase makeViewHavingCondition() {
        if (!this.isViewEntity() || entityHavingEconditions == null) return null
        // add the view-entity.entity-condition.having-econditions
        return makeViewListCondition(entityHavingEconditions)
    }

    protected EntityConditionImplBase makeViewListCondition(MNode conditionsParent) {
        if (conditionsParent == null) return null
        ExecutionContextImpl eci = efi.ecfi.getEci()
        List<EntityCondition> condList = new ArrayList()
        for (MNode dateFilter in conditionsParent.children("date-filter")) {
            // NOTE: this doesn't do context expansion of the valid-date as it doesn't make sense for an entity def to depend on something being in the context
            condList.add((EntityConditionImplBase) this.efi.conditionFactory.makeConditionDate(
                    dateFilter.attribute("from-field-name"), dateFilter.attribute("thru-field-name"),
                    dateFilter.attribute("valid-date") ? efi.getEcfi().getResourceFacade().expand(dateFilter.attribute("valid-date"), "") as Timestamp : null))
        }
        for (MNode econdition in conditionsParent.children("econdition")) {
            EntityConditionImplBase cond;
            ConditionField field
            EntityDefinition condEd;
            if (econdition.attribute("entity-alias")) {
                MNode memberEntity = memberEntityAliasMap.get(econdition.attribute("entity-alias"))
                if (!memberEntity) throw new EntityException("The entity-alias [${econdition.attribute("entity-alias")}] was not found in view-entity [${this.internalEntityName}]")
                EntityDefinition aliasEntityDef = this.efi.getEntityDefinition(memberEntity.attribute("entity-name"))
                field = new ConditionField(econdition.attribute("entity-alias"), econdition.attribute("field-name"), aliasEntityDef)
                condEd = aliasEntityDef;
            } else {
                field = new ConditionField(econdition.attribute("field-name"))
                condEd = this;
            }
            if (econdition.attribute("to-field-name") != null) {
                ConditionField toField
                if (econdition.attribute("to-entity-alias")) {
                    MNode memberEntity = memberEntityAliasMap.get(econdition.attribute("to-entity-alias"))
                    if (!memberEntity) throw new EntityException("The entity-alias [${econdition.attribute("to-entity-alias")}] was not found in view-entity [${this.internalEntityName}]")
                    EntityDefinition aliasEntityDef = this.efi.getEntityDefinition(memberEntity.attribute("entity-name"))
                    toField = new ConditionField(econdition.attribute("to-entity-alias"), econdition.attribute("to-field-name"), aliasEntityDef)
                } else {
                    toField = new ConditionField(econdition.attribute("to-field-name"))
                }
                cond = new FieldToFieldCondition((EntityConditionFactoryImpl) this.efi.conditionFactory, field,
                        EntityConditionFactoryImpl.getComparisonOperator(econdition.attribute("operator")), toField)
            } else {
                // NOTE: may need to convert value from String to object for field
                String condValue = econdition.attribute("value") ?: null
                // NOTE: only expand if contains "${", expanding normal strings does l10n and messes up key values; hopefully this won't result in a similar issue
                if (condValue && condValue.contains("\${")) condValue = efi.getEcfi().getResourceFacade().expand(condValue, "") as String
                Object condValueObj = condEd.convertFieldString(field.fieldName, condValue, eci);
                cond = new FieldValueCondition((EntityConditionFactoryImpl) this.efi.conditionFactory, field,
                        EntityConditionFactoryImpl.getComparisonOperator(econdition.attribute("operator")), condValueObj)
            }
            if (cond && econdition.attribute("ignore-case") == "true") cond.ignoreCase()

            if (cond && econdition.attribute("or-null") == "true") {
                cond = (EntityConditionImplBase) this.efi.conditionFactory.makeCondition(cond, JoinOperator.OR,
                        new FieldValueCondition((EntityConditionFactoryImpl) this.efi.conditionFactory, field, EntityCondition.EQUALS, null))
            }

            if (cond) condList.add(cond)
        }
        for (MNode econditions in conditionsParent.children("econditions")) {
            EntityConditionImplBase cond = this.makeViewListCondition(econditions)
            if (cond) condList.add(cond)
        }
        if (!condList) return null
        if (condList.size() == 1) return (EntityConditionImplBase) condList.get(0)
        JoinOperator op = (conditionsParent.attribute("combine") == "or" ? JoinOperator.OR : JoinOperator.AND)
        EntityConditionImplBase entityCondition = (EntityConditionImplBase) this.efi.conditionFactory.makeCondition(condList, op)
        // logger.info("============== In makeViewListCondition for entity [${entityName}] resulting entityCondition: ${entityCondition}")
        return entityCondition
    }

    @Override
    int hashCode() {
        return this.internalEntityName.hashCode()
    }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != this.getClass()) return false
        EntityDefinition that = (EntityDefinition) o
        if (!this.internalEntityName.equals(that.internalEntityName)) return false
        return true
    }

    protected static Map<String, String> camelToUnderscoreMap = new HashMap()
    @CompileStatic
    static String camelCaseToUnderscored(String camelCase) {
        if (camelCase == null || camelCase.length() == 0) return ""
        String usv = camelToUnderscoreMap.get(camelCase)
        if (usv != null) return usv

        StringBuilder underscored = new StringBuilder()
        underscored.append(Character.toUpperCase(camelCase.charAt(0)))
        int inPos = 1
        while (inPos < camelCase.length()) {
            char curChar = camelCase.charAt(inPos)
            if (Character.isUpperCase(curChar)) underscored.append('_')
            underscored.append(Character.toUpperCase(curChar))
            inPos++
        }

        usv = underscored.toString()
        camelToUnderscoreMap.put(camelCase, usv)
        return usv
    }
}
