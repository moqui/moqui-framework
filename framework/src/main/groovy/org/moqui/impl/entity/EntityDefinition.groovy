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
import org.moqui.BaseArtifactException
import org.moqui.entity.EntityFind
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.condition.ConditionAlias
import org.moqui.util.ObjectUtilities
import org.moqui.util.StringUtilities

import javax.cache.Cache
import java.sql.Timestamp

import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityCondition.JoinOperator
import org.moqui.entity.EntityException
import org.moqui.entity.EntityValue
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.moqui.impl.entity.condition.ConditionField
import org.moqui.impl.entity.condition.FieldValueCondition
import org.moqui.impl.entity.condition.FieldToFieldCondition
import org.moqui.impl.entity.EntityJavaUtil.RelationshipInfo
import org.moqui.util.MNode

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class EntityDefinition {
    protected final static Logger logger = LoggerFactory.getLogger(EntityDefinition.class)

    protected final EntityFacadeImpl efi
    public final MNode internalEntityNode
    public final String fullEntityName
    public final boolean isViewEntity
    public final boolean isDynamicView
    public final String groupName
    public final EntityJavaUtil.EntityInfo entityInfo

    protected final HashMap<String, MNode> fieldNodeMap = new HashMap<>()
    protected final HashMap<String, FieldInfo> fieldInfoMap = new HashMap<>()
    // small lists, but very frequently accessed
    protected final ArrayList<String> pkFieldNameList = new ArrayList<>()
    protected final ArrayList<String> nonPkFieldNameList = new ArrayList<>()
    protected final ArrayList<String> allFieldNameList = new ArrayList<>()
    protected final ArrayList<FieldInfo> allFieldInfoList = new ArrayList<>()
    protected Map<String, MNode> pqExpressionNodeMap = null
    protected Map<String, Map<String, String>> mePkFieldToAliasNameMapMap = null
    protected Map<String, Map<String, ArrayList<MNode>>> memberEntityFieldAliases = null
    protected Map<String, MNode> memberEntityAliasMap = null
    protected boolean hasSubSelectMembers = false
    // these are used for every list find, so keep them here
    public final MNode entityConditionNode
    public final MNode entityHavingEconditions

    protected boolean tableExistVerified = false

    private List<MNode> expandedRelationshipList = null
    // this is kept separately for quick access to relationships by name or short-alias
    private Map<String, RelationshipInfo> relationshipInfoMap = null
    private ArrayList<RelationshipInfo> relationshipInfoList = null
    private boolean hasReverseRelationships = false
    private Map<String, MasterDefinition> masterDefinitionMap = null

    EntityDefinition(EntityFacadeImpl efi, MNode entityNode) {
        this.efi = efi
        // copy the entityNode because we may be modifying it
        internalEntityNode = entityNode.deepCopy(null)

        // prepare a few things needed by initFields() before calling it

        String packageName = internalEntityNode.attribute("package")
        if (packageName == null || packageName.isEmpty()) packageName = internalEntityNode.attribute("package-name")
        fullEntityName = packageName + "." + internalEntityNode.attribute("entity-name")

        isViewEntity = "view-entity".equals(internalEntityNode.getName())
        isDynamicView = "true".equals(internalEntityNode.attribute("is-dynamic-view"))

        if (isDynamicView) {
            // use the group of the primary member-entity
            String memberEntityName = null
            ArrayList<MNode> meList = internalEntityNode.children("member-entity")
            for (MNode meNode : meList) {
                String jfaAttr = meNode.attribute("join-from-alias")
                if (jfaAttr == null || jfaAttr.isEmpty()) {
                    memberEntityName = meNode.attribute("entity-name")
                    break
                }
            }
            if (memberEntityName != null) {
                groupName = efi.getEntityGroupName(memberEntityName)
            } else {
                throw new EntityException("Could not find group for dynamic view entity")
            }
        } else {
            String groupAttr = internalEntityNode.attribute("group")
            if (groupAttr == null || groupAttr.isEmpty()) groupAttr = internalEntityNode.attribute("group-name")
            if (groupAttr == null || groupAttr.isEmpty()) groupAttr = efi.getDefaultGroupName()
            groupName = groupAttr
        }

        // now initFields() and create EntityInfo
        boolean neverCache = false
        if (isViewEntity) {
            memberEntityFieldAliases = [:]
            memberEntityAliasMap = [:]

            // expand member-relationship into member-entity
            if (internalEntityNode.hasChild("member-relationship")) for (MNode memberRel in internalEntityNode.children("member-relationship")) {
                String joinFromAlias = memberRel.attribute("join-from-alias")
                String relName = memberRel.attribute("relationship")
                MNode jfme = internalEntityNode.first("member-entity", "entity-alias", joinFromAlias)
                if (jfme == null) throw new EntityException("Could not find member-entity ${joinFromAlias} referenced in member-relationship ${memberRel.attribute("entity-alias")} of view-entity ${fullEntityName}")
                String fromEntityName = jfme.attribute("entity-name")
                EntityDefinition jfed = efi.getEntityDefinition(fromEntityName)
                if (jfed == null) throw new EntityException("No definition found for member-entity ${jfme.attribute("entity-alias")} name ${fromEntityName} in view-entity ${fullEntityName}")

                // can't use getRelationshipInfo as not all entities loaded: RelationshipInfo relInfo = jfed.getRelationshipInfo(relName)
                MNode relNode = jfed.internalEntityNode.first({ MNode it -> "relationship".equals(it.name) &&
                        (relName.equals(it.attribute("short-alias")) || relName.equals(it.attribute("related")) ||
                                relName.equals(it.attribute("related") + '#' + it.attribute("related"))) })
                if (relNode == null) throw new EntityException("Could not find relationship ${relName} from member-entity ${joinFromAlias} referenced in member-relationship ${memberRel.attribute("entity-alias")} of view-entity ${fullEntityName}")

                // mutate the current MNode
                memberRel.setName("member-entity")
                memberRel.attributes.put("entity-name", relNode.attribute("related"))
                ArrayList<MNode> kmList = relNode.children("key-map")
                if (kmList) {
                    for (MNode keyMap in relNode.children("key-map"))
                        memberRel.append("key-map", ["field-name":keyMap.attribute("field-name"), "related":keyMap.attribute("related")])
                } else {
                    EntityDefinition relEd = efi.getEntityDefinition(relNode.attribute("related"))
                    for (String pkName in relEd.getPkFieldNames()) memberRel.append("key-map", ["field-name":pkName, "related":pkName])
                }
            }

            if (internalEntityNode.hasChild("member-relationship"))
                logger.warn("view-entity ${fullEntityName} members: ${internalEntityNode.children("member-entity")}")

            // get group, etc from member-entity
            Set<String> allGroupNames = new TreeSet<>()
            for (MNode memberEntity in internalEntityNode.children("member-entity")) {
                String memberEntityName = memberEntity.attribute("entity-name")
                memberEntityAliasMap.put(memberEntity.attribute("entity-alias"), memberEntity)
                if ("true".equals(memberEntity.attribute("sub-select"))) hasSubSelectMembers = true
                EntityDefinition memberEd = efi.getEntityDefinition(memberEntityName)
                if (memberEd == null) throw new EntityException("No definition found for member-entity ${memberEntity.attribute("entity-alias")} name ${memberEntityName} in view-entity ${fullEntityName}")
                MNode memberEntityNode = memberEd.getEntityNode()
                String groupNameAttr = memberEntityNode.attribute("group") ?: memberEntityNode.attribute("group-name")
                if (groupNameAttr == null || groupNameAttr.length() == 0) {
                    // use the default group
                    groupNameAttr = efi.getDefaultGroupName()
                }
                // only set on view-entity for the first one
                if (allGroupNames.size() == 0) internalEntityNode.attributes.put("group", groupNameAttr)
                // remember all group names applicable to the view entity
                allGroupNames.add(groupNameAttr)

                // if is view entity and any member entities set to never cache set this to never cache
                if ("never".equals(memberEntityNode.attribute("cache"))) neverCache = true
            }
            // warn if view-entity has members in more than one group (join will fail if deployed in different DBs)
            // TODO enable this again to check view-entities for groups: if (allGroupNames.size() > 1) logger.warn("view-entity ${getFullEntityName()} has members in more than one group: ${allGroupNames}")

            // if this is a view-entity, expand the alias-all elements into alias elements here
            this.expandAliasAlls()
            // set @type, set is-pk on all alias Nodes if the related field is-pk
            for (MNode aliasNode in internalEntityNode.children("alias")) {
                if (aliasNode.hasChild("complex-alias") || aliasNode.hasChild("case")) continue
                if (aliasNode.attribute("pq-expression")) continue

                String entityAlias = aliasNode.attribute("entity-alias")
                MNode memberEntity = memberEntityAliasMap.get(entityAlias)
                if (memberEntity == null) throw new EntityException("Could not find member-entity with entity-alias ${entityAlias} in view-entity ${fullEntityName}")

                EntityDefinition memberEd = efi.getEntityDefinition(memberEntity.attribute("entity-name"))
                String fieldName = aliasNode.attribute("field") ?: aliasNode.attribute("name")
                MNode fieldNode = memberEd.getFieldNode(fieldName)
                if (fieldNode == null) throw new EntityException("In view-entity ${fullEntityName} alias ${aliasNode.attribute("name")} referred to field ${fieldName} that does not exist on entity ${memberEd.fullEntityName}.")
                if (!aliasNode.attribute("type")) aliasNode.attributes.put("type", fieldNode.attribute("type"))
                if ("true".equals(fieldNode.attribute("is-pk"))) aliasNode.attributes.put("is-pk", "true")
                if ("true".equals(fieldNode.attribute("enable-localization"))) aliasNode.attributes.put("enable-localization", "true")
                if ("true".equals(fieldNode.attribute("encrypt"))) aliasNode.attributes.put("encrypt", "true")

                // add to aliases by field name by entity name
                if (!memberEntityFieldAliases.containsKey(memberEd.getFullEntityName())) memberEntityFieldAliases.put(memberEd.getFullEntityName(), [:])
                Map<String, ArrayList<MNode>> fieldInfoByEntity = memberEntityFieldAliases.get(memberEd.getFullEntityName())
                if (!fieldInfoByEntity.containsKey(fieldName)) fieldInfoByEntity.put(fieldName, new ArrayList())
                ArrayList<MNode> aliasByField = fieldInfoByEntity.get(fieldName)
                aliasByField.add(aliasNode)
            }
            for (MNode aliasNode in internalEntityNode.children("alias")) {
                if (aliasNode.attribute("pq-expression")) {
                    if (pqExpressionNodeMap == null) pqExpressionNodeMap = new HashMap<>()
                    String pqFieldName = aliasNode.attribute("name")
                    pqExpressionNodeMap.put(pqFieldName, aliasNode)
                    continue
                }

                FieldInfo fi = new FieldInfo(this, aliasNode)
                addFieldInfo(fi)
            }

            entityConditionNode = internalEntityNode.first("entity-condition")
            if (entityConditionNode != null) entityHavingEconditions = entityConditionNode.first("having-econditions")
            else entityHavingEconditions = null
        } else {
            if (internalEntityNode.attribute("no-update-stamp") != "true") {
                // automatically add the lastUpdatedStamp field
                internalEntityNode.append("field", [name:"lastUpdatedStamp", type:"date-time"])
            }

            for (MNode fieldNode in internalEntityNode.children("field")) {
                FieldInfo fi = new FieldInfo(this, fieldNode)
                addFieldInfo(fi)
            }

            entityConditionNode = null
            entityHavingEconditions = null
        }

        // finally create the EntityInfo object
        entityInfo = new EntityJavaUtil.EntityInfo(this, neverCache)
    }

    private void addFieldInfo(FieldInfo fi) {
        fieldNodeMap.put(fi.name, fi.fieldNode)
        fieldInfoMap.put(fi.name, fi)
        allFieldNameList.add(fi.name)
        allFieldInfoList.add(fi)
        if (fi.isPk) {
            pkFieldNameList.add(fi.name)
        } else {
            nonPkFieldNameList.add(fi.name)
        }
    }
    private String getBasicFieldColName(String entityAlias, String fieldName) {
        MNode memberEntity = memberEntityAliasMap.get(entityAlias)
        if (memberEntity == null) throw new EntityException("Could not find member-entity with entity-alias [${entityAlias}] in view-entity [${getFullEntityName()}]")
        EntityDefinition memberEd = this.efi.getEntityDefinition(memberEntity.attribute("entity-name"))
        FieldInfo fieldInfo = memberEd.getFieldInfo(fieldName)
        if (fieldInfo == null) throw new EntityException("Invalid field name ${fieldName} for entity ${memberEd.getFullEntityName()}")
        if ("true".equals(memberEntity.attribute("sub-select"))) {
            // sub-select uses alias field name changed to underscored
            return EntityJavaUtil.camelCaseToUnderscored(fieldInfo.name)
        } else {
            return fieldInfo.getFullColumnName()
        }
    }
    String makeFullColumnName(MNode fieldNode, boolean includeEntityAlias) {
        if (!isViewEntity) return null

        String memberAliasName = fieldNode.attribute("name")
        String memberFieldName = fieldNode.attribute("field")
        if (memberFieldName == null || memberFieldName.isEmpty()) memberFieldName = memberAliasName

        String entityAlias = fieldNode.attribute("entity-alias")
        if (includeEntityAlias) {
            if (entityAlias == null || entityAlias.isEmpty()) {
                Set<String> entityAliasUsedSet = new HashSet<>()
                ArrayList<MNode> cafList = fieldNode.descendants("complex-alias-field")
                int cafListSize = cafList.size()
                for (int i = 0; i < cafListSize; i++) {
                    MNode cafNode = (MNode) cafList.get(i)
                    String cafEntityAlias = cafNode.attribute("entity-alias")
                    if (cafEntityAlias != null && cafEntityAlias.length() > 0) entityAliasUsedSet.add(cafEntityAlias)
                }
                if (entityAliasUsedSet.size() == 1) entityAlias = entityAliasUsedSet.iterator().next()
            }
            // might have added entityAlias so check again
            if (entityAlias != null && !entityAlias.isEmpty()) {
                // special case for member-entity with sub-select=true, use alias underscored
                MNode memberEntity = (MNode) memberEntityAliasMap.get(entityAlias)
                EntityDefinition memberEd = this.efi.getEntityDefinition(memberEntity.attribute("entity-name"))
                if (!memberEd.isViewEntity && "true".equals(memberEntity.attribute("sub-select"))) {
                    return entityAlias + '.' + EntityJavaUtil.camelCaseToUnderscored(memberAliasName)
                }
            }
        }

        // NOTE: for view-entity the incoming fieldNode will actually be for an alias element
        StringBuilder colNameBuilder = new StringBuilder()

        MNode caseNode = fieldNode.first("case")
        MNode complexAliasNode = fieldNode.first("complex-alias")
        String function = fieldNode.attribute("function")
        boolean hasFunction = function != null && !function.isEmpty()

        if (hasFunction) colNameBuilder.append(getFunctionPrefix(function))
        if (caseNode != null) {
            colNameBuilder.append("CASE")
            String caseExpr = caseNode.attribute("expression")
            if (caseExpr != null) colNameBuilder.append(" ").append(caseExpr)

            ArrayList<MNode> whenNodeList = caseNode.children("when")
            int whenNodeListSize = whenNodeList.size()
            if (whenNodeListSize == 0) throw new EntityException("No when element under case in alias ${fieldNode.attribute("name")} in view-entity ${getFullEntityName()}")
            for (int i = 0; i < whenNodeListSize; i++) {
                MNode whenNode = (MNode) whenNodeList.get(i)
                colNameBuilder.append(" WHEN ").append(whenNode.attribute("expression")).append(" THEN ")
                MNode whenComplexAliasNode = whenNode.first("complex-alias")
                if (whenComplexAliasNode == null) throw new EntityException("No complex-alias element under case.when in alias ${fieldNode.attribute("name")} in view-entity ${getFullEntityName()}")
                buildComplexAliasName(whenComplexAliasNode, colNameBuilder, true, includeEntityAlias)
            }

            MNode elseNode = caseNode.first("else")
            if (elseNode != null) {
                colNameBuilder.append(" ELSE ")
                MNode elseComplexAliasNode = elseNode.first("complex-alias")
                if (elseComplexAliasNode == null) throw new EntityException("No complex-alias element under case.else in alias ${fieldNode.attribute("name")} in view-entity ${getFullEntityName()}")
                buildComplexAliasName(elseComplexAliasNode, colNameBuilder, true, includeEntityAlias)
            }

            colNameBuilder.append(" END")
        } else if (complexAliasNode != null) {
            buildComplexAliasName(complexAliasNode, colNameBuilder, !hasFunction, includeEntityAlias)
        } else {
            // column name for view-entity (prefix with "${entity-alias}.")
            if (includeEntityAlias) colNameBuilder.append(entityAlias).append('.')
            colNameBuilder.append(getBasicFieldColName(entityAlias, memberFieldName))
        }
        if (hasFunction) colNameBuilder.append(')')

        return colNameBuilder.toString()
    }
    private void buildComplexAliasName(MNode parentNode, StringBuilder colNameBuilder, boolean addParens, boolean includeEntityAlias) {
        String expression = parentNode.attribute("expression")
        // NOTE: this is expanded in FieldInfo.getFullColumnName() if needed
        if (expression != null && expression.length() > 0) colNameBuilder.append(expression)

        ArrayList<MNode> childList = parentNode.children
        int childListSize = childList.size()
        if (childListSize == 0) return

        String caFunction = parentNode.attribute("function")
        if (caFunction != null && !caFunction.isEmpty()) {
            colNameBuilder.append(caFunction).append('(')
            for (int i = 0; i < childListSize; i++) {
                MNode childNode = (MNode) childList.get(i)
                if (i > 0) colNameBuilder.append(", ")

                if ("complex-alias".equals(childNode.name)) {
                    buildComplexAliasName(childNode, colNameBuilder, true, includeEntityAlias)
                } else if ("complex-alias-field".equals(childNode.name)) {
                    appenComplexAliasField(childNode, colNameBuilder, includeEntityAlias)
                }
            }
            colNameBuilder.append(')')
        } else {
            String operator = parentNode.attribute("operator")
            if (operator == null || operator.isEmpty()) operator = "+"

            if (addParens && childListSize > 1) colNameBuilder.append('(')
            for (int i = 0; i < childListSize; i++) {
                MNode childNode = (MNode) childList.get(i)
                if (i > 0) colNameBuilder.append(' ').append(operator).append(' ')

                if ("complex-alias".equals(childNode.name)) {
                    buildComplexAliasName(childNode, colNameBuilder, true, includeEntityAlias)
                } else if ("complex-alias-field".equals(childNode.name)) {
                    appenComplexAliasField(childNode, colNameBuilder, includeEntityAlias)
                }
            }
            if (addParens && childListSize > 1) colNameBuilder.append(')')
        }
    }
    private void appenComplexAliasField(MNode childNode, StringBuilder colNameBuilder, boolean includeEntityAlias) {
        String entityAlias = childNode.attribute("entity-alias")
        String basicColName = getBasicFieldColName(entityAlias, childNode.attribute("field"))
        String colName = includeEntityAlias ? entityAlias + "." + basicColName : basicColName
        String defaultValue = childNode.attribute("default-value")
        String function = childNode.attribute("function")

        if (function) colNameBuilder.append(getFunctionPrefix(function))
        if (defaultValue) colNameBuilder.append("COALESCE(")
        colNameBuilder.append(colName)
        if (defaultValue) colNameBuilder.append(',').append(defaultValue).append(')')
        if (function) colNameBuilder.append(')')
    }
    protected static String getFunctionPrefix(String function) {
        return (function == "count-distinct") ? "COUNT(DISTINCT " : function.toUpperCase() + '('
    }
    private void expandAliasAlls() {
        if (!isViewEntity) return
        Set<String> existingAliasNames = new HashSet<>()
        ArrayList<MNode> aliasList = internalEntityNode.children("alias")
        int aliasListSize = aliasList.size()
        for (int i = 0; i < aliasListSize; i++) {
            MNode aliasNode = (MNode) aliasList.get(i)
            existingAliasNames.add(aliasNode.attribute("name"))
        }

        ArrayList<MNode> aliasAllList = internalEntityNode.children("alias-all")
        ArrayList<MNode> memberEntityList = internalEntityNode.children("member-entity")
        int memberEntityListSize = memberEntityList.size()
        for (int aInd = 0; aInd < aliasAllList.size(); aInd++) {
            MNode aliasAll = (MNode) aliasAllList.get(aInd)
            String aliasAllEntityAlias = aliasAll.attribute("entity-alias")
            MNode memberEntity = memberEntityAliasMap.get(aliasAllEntityAlias)
            if (memberEntity == null) {
                logger.error("In view-entity ${getFullEntityName()} in alias-all with entity-alias [${aliasAllEntityAlias}], member-entity with same entity-alias not found, ignoring")
                continue
            }

            EntityDefinition aliasedEntityDefinition = efi.getEntityDefinition(memberEntity.attribute("entity-name"))
            if (aliasedEntityDefinition == null) {
                logger.error("Entity [${memberEntity.attribute("entity-name")}] referred to in member-entity with entity-alias [${aliasAllEntityAlias}] not found, ignoring")
                continue
            }

            FieldInfo[] aliasFieldInfos = aliasedEntityDefinition.entityInfo.allFieldInfoArray
            for (int i = 0; i < aliasFieldInfos.length; i++) {
                FieldInfo fi = (FieldInfo) aliasFieldInfos[i]
                String aliasName = fi.name
                // never auto-alias these
                if ("lastUpdatedStamp".equals(aliasName)) continue
                // if specified as excluded, leave it out
                ArrayList<MNode> excludeList = aliasAll.children("exclude")
                int excludeListSize = excludeList.size()
                boolean foundExclude = false
                for (int j = 0; j < excludeListSize; j++) {
                    MNode excludeNode = (MNode) excludeList.get(j)
                    if (aliasName.equals(excludeNode.attribute("field"))) {
                        foundExclude = true
                        break
                    }
                }
                if (foundExclude) continue


                if (aliasAll.attribute("prefix")) {
                    StringBuilder newAliasName = new StringBuilder(aliasAll.attribute("prefix"))
                    newAliasName.append(Character.toUpperCase(aliasName.charAt(0)))
                    newAliasName.append(aliasName.substring(1))
                    aliasName = newAliasName.toString()
                }

                // see if there is already an alias with this name
                if (existingAliasNames.contains(aliasName)) {
                    //log differently if this is part of a member-entity view link key-map because that is a common case when a field will be auto-expanded multiple times
                    boolean isInViewLink = false
                    for (int j = 0; j < memberEntityListSize; j++) {
                        MNode viewMeNode = (MNode) memberEntityList.get(j)
                        boolean isRel = false
                        if (viewMeNode.attribute("entity-alias") == aliasAllEntityAlias) {
                            isRel = true
                        } else if (viewMeNode.attribute("join-from-alias") != aliasAllEntityAlias) {
                            // not the rel-entity-alias or the entity-alias, so move along
                            continue;
                        }
                        for (MNode keyMap in viewMeNode.children("key-map")) {
                            if (!isRel && keyMap.attribute("field-name") == fi.name) {
                                isInViewLink = true
                                break
                            } else if (isRel && ((keyMap.attribute("related") ?: keyMap.attribute("related-field-name") ?: keyMap.attribute("field-name"))) == fi.name) {
                                isInViewLink = true
                                break
                            }
                        }
                        if (isInViewLink) break
                    }

                    MNode existingAliasNode = internalEntityNode.children("alias").find({ aliasName.equals(it.attribute("name")) })
                    // already exists... probably an override, but log just in case
                    String warnMsg = "Throwing out field alias in view entity " + this.getFullEntityName() +
                            " because one already exists with the alias name [" + aliasName + "] and field name [" +
                            memberEntity.attribute("entity-alias") + "(" + aliasedEntityDefinition.getFullEntityName() + ")." +
                            fi.name + "], existing field name is [" + existingAliasNode.attribute("entity-alias") + "." +
                            existingAliasNode.attribute("field") + "]"
                    if (isInViewLink) { if (logger.isTraceEnabled()) logger.trace(warnMsg) } else { logger.info(warnMsg) }

                    // ship adding the new alias
                    continue
                }

                existingAliasNames.add(aliasName)
                MNode newAlias = this.internalEntityNode.append("alias",
                        [name:aliasName, field:fi.name, "entity-alias":aliasAllEntityAlias, "is-from-alias-all":"true"])
                if (fi.fieldNode.hasChild("description")) newAlias.append(fi.fieldNode.first("description"))
            }
        }
    }

    EntityFacadeImpl getEfi() { return efi }
    String getEntityName() { return entityInfo.internalEntityName }
    String getFullEntityName() { return fullEntityName }
    String getShortAlias() { return entityInfo.shortAlias }
    String getShortOrFullEntityName() { return entityInfo.shortAlias != null ? entityInfo.shortAlias : entityInfo.fullEntityName }
    MNode getEntityNode() { return internalEntityNode }

    Map<String, ArrayList<MNode>> getMemberFieldAliases(String memberEntityName) {
        return memberEntityFieldAliases?.get(memberEntityName)
    }
    String getEntityGroupName() { return groupName }

    /** Returns the table name, ie table-name or converted entity-name */
    String getTableName() { return entityInfo.tableName }
    String getFullTableName() { return entityInfo.fullTableName }
    String getSchemaName() { return entityInfo.schemaName }

    String getColumnName(String fieldName) {
        FieldInfo fieldInfo = getFieldInfo(fieldName)
        if (fieldInfo == null) throw new EntityException("Invalid field name ${fieldName} for entity ${this.getFullEntityName()}")
        return fieldInfo.getFullColumnName()
    }

    ArrayList<String> getPkFieldNames() { return pkFieldNameList }
    ArrayList<String> getNonPkFieldNames() { return nonPkFieldNameList }
    ArrayList<String> getAllFieldNames() { return allFieldNameList }
    boolean isField(String fieldName) { return fieldInfoMap.containsKey(fieldName) }
    boolean isPkField(String fieldName) {
        FieldInfo fieldInfo = fieldInfoMap.get(fieldName)
        if (fieldInfo == null) return false
        return fieldInfo.isPk
    }

    boolean containsPrimaryKey(Map<String, Object> fields) {
        if (fields == null || fields.size() == 0) return false
        ArrayList<String> fieldNameList = this.getPkFieldNames()
        int size = fieldNameList.size()
        for (int i = 0; i < size; i++) {
            String fieldName = (String) fieldNameList.get(i)
            Object fieldValue = fields.get(fieldName)
            if (ObjectUtilities.isEmpty(fieldValue)) return false
        }
        return true
    }
    Map<String, Object> getPrimaryKeys(Map<String, Object> fields) {
        Map<String, Object> pks = new HashMap()
        ArrayList<String> fieldNameList = this.getPkFieldNames()
        int size = fieldNameList.size()
        for (int i = 0; i < size; i++) {
            String fieldName = (String) fieldNameList.get(i)
            pks.put(fieldName, fields.get(fieldName))
        }
        return pks
    }

    ArrayList<String> getFieldNames(boolean includePk, boolean includeNonPk) {
        ArrayList<String> baseList
        if (includePk) {
            if (includeNonPk) baseList = getAllFieldNames()
            else baseList = getPkFieldNames()
        } else {
            if (includeNonPk) baseList = getNonPkFieldNames()
            // all false is weird, but okay
            else baseList = new ArrayList<String>()
        }
        return baseList
    }

    String getDefaultDescriptionField() {
        ArrayList<String> nonPkFields = nonPkFieldNameList
        // find the first *Name
        for (String fn in nonPkFields)
            if (fn.endsWith("Name")) return fn

        // no name? try literal description
        if (isField("description")) return "description"

        // no description? just use the first non-pk field: nonPkFields.get(0)
        // not any more, can be confusing... just return empty String
        return ""
    }

    MNode getMemberEntityNode(String entityAlias) { return memberEntityAliasMap.get(entityAlias) }
    String getMemberEntityName(String entityAlias) {
        MNode memberEntityNode = memberEntityAliasMap.get(entityAlias)
        return memberEntityNode?.attribute("entity-name")
    }

    MNode getFieldNode(String fieldName) { return (MNode) fieldNodeMap.get(fieldName) }
    FieldInfo getFieldInfo(String fieldName) { return (FieldInfo) fieldInfoMap.get(fieldName) }

    static Map<String, String> getRelationshipExpandedKeyMapInternal(MNode relationship, EntityDefinition relEd) {
        Map<String, String> eKeyMap = [:]
        ArrayList<MNode> keyMapList = relationship.children("key-map")
        if (!keyMapList && ((String) relationship.attribute("type")).startsWith("one")) {
            // go through pks of related entity, assume field names match
            ArrayList<String> relPkFields = relEd.getPkFieldNames()
            int relPkFieldSize = relPkFields.size()
            for (int i = 0; i < relPkFieldSize; i++) {
                String pkFieldName = (String) relPkFields.get(i)
                eKeyMap.put(pkFieldName, pkFieldName)
            }
        } else {
            int keyMapListSize = keyMapList.size()
            if (keyMapListSize == 1) {
                MNode keyMap = (MNode) keyMapList.get(0)
                String fieldName = keyMap.attribute("field-name")
                String relFn = keyMap.attribute("related") ?: keyMap.attribute("related-field-name")
                if (relFn == null || relFn.isEmpty()) {
                    ArrayList<String> relPks = relEd.getPkFieldNames()
                    if (relationship.attribute("type").startsWith("one") && relPks.size() == 1) {
                        relFn = (String) relPks.get(0)
                    } else {
                        relFn = fieldName
                    }
                }
                eKeyMap.put(fieldName, relFn)
            } else {
                for (int i = 0; i < keyMapListSize; i++) {
                    MNode keyMap = (MNode) keyMapList.get(i)
                    String fieldName = keyMap.attribute("field-name")
                    String relFn = keyMap.attribute("related") ?: keyMap.attribute("related-field-name") ?: fieldName
                    if (!relEd.isField(relFn) && relationship.attribute("type").startsWith("one")) {
                        ArrayList<String> pks = relEd.getPkFieldNames()
                        if (pks.size() == 1) relFn = (String) pks.get(0)
                        // if we don't match these constraints and get this default we'll get an error later...
                    }
                    eKeyMap.put(fieldName, relFn)
                }
            }
        }
        return eKeyMap
    }
    static Map<String, String> getRelationshipKeyValueMapInternal(MNode relationship) {
        ArrayList<MNode> keyValueList = relationship.children("key-value")
        int keyValueListSize = keyValueList.size()
        if (keyValueListSize == 0) return null
        Map<String, String> eKeyMap = [:]
        for (int i = 0; i < keyValueListSize; i++) {
            MNode keyValue = (MNode) keyValueList.get(i)
            eKeyMap.put(keyValue.attribute("related"), keyValue.attribute("value"))
        }
        return eKeyMap
    }

    RelationshipInfo getRelationshipInfo(String relationshipName) {
        if (relationshipName == null || relationshipName.isEmpty()) return null
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
            if (!relInfo.title) relInfoMap.put(relInfo.relatedEd.entityInfo.internalEntityName, relInfo)
        }
        relationshipInfoMap = relInfoMap
    }

    ArrayList<RelationshipInfo> getRelationshipsInfo(boolean dependentsOnly) {
        if (relationshipInfoList == null) makeRelInfoList()

        if (!dependentsOnly) return new ArrayList(relationshipInfoList)
        // just get dependents
        ArrayList<RelationshipInfo> infoListCopy = new ArrayList<>()
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

        ArrayList<RelationshipInfo> infoList = new ArrayList<>()
        for (MNode relNode in this.expandedRelationshipList) {
            RelationshipInfo relInfo = new RelationshipInfo(relNode, this, efi)
            infoList.add(relInfo)
        }
        relationshipInfoList = infoList
    }
    void setHasReverseRelationships() { hasReverseRelationships = true }

    MasterDefinition getMasterDefinition(String name) {
        if (name == null || name.length() == 0) name = "default"
        if (masterDefinitionMap == null) makeMasterDefinitionMap()
        return masterDefinitionMap.get(name)
    }
    Map<String, MasterDefinition> getMasterDefinitionMap() {
        if (masterDefinitionMap == null) makeMasterDefinitionMap()
        return masterDefinitionMap
    }
    private synchronized void makeMasterDefinitionMap() {
        if (masterDefinitionMap != null) return
        Map<String, MasterDefinition> defMap = [:]
        for (MNode masterNode in internalEntityNode.children("master")) {
            MasterDefinition curDef = new MasterDefinition(this, masterNode)
            defMap.put(curDef.name, curDef)
        }
        masterDefinitionMap = defMap
    }

    Map<String, MNode> getPqExpressionNodeMap() { return pqExpressionNodeMap }
    MNode getPqExpressionNode(String name) {
        if (pqExpressionNodeMap == null) return null
        return pqExpressionNodeMap.get(name)
    }

    @CompileStatic
    static class MasterDefinition {
        String name
        ArrayList<MasterDetail> detailList = new ArrayList<MasterDetail>()
        MasterDefinition(EntityDefinition ed, MNode masterNode) {
            name = masterNode.attribute("name") ?: "default"
            List<MNode> detailNodeList = masterNode.children("detail")
            for (MNode detailNode in detailNodeList) {
                try {
                    detailList.add(new MasterDetail(ed, detailNode))
                } catch (Exception e) {
                    logger.error("Error adding detail ${detailNode.attribute("relationship")} to master ${name} of entity ${ed.getFullEntityName()}: ${e.toString()}")
                }
            }
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
            if (relInfo == null) throw new BaseArtifactException("Invalid relationship name [${relationshipName}] for entity ${parentEd.getFullEntityName()}")
            // logger.warn("Following relationship ${relationshipName}")

            List<MNode> detailNodeList = detailNode.children("detail")
            for (MNode childNode in detailNodeList) internalDetailList.add(new MasterDetail(relInfo.relatedEd, childNode))

            relatedMasterName = (String) detailNode.attribute("use-master")
        }

        ArrayList<MasterDetail> getDetailList() {
            if (relatedMasterName) {
                ArrayList<MasterDetail> combinedList = new ArrayList<MasterDetail>(internalDetailList)
                MasterDefinition relatedMaster = relInfo.relatedEd.getMasterDefinition(relatedMasterName)
                if (relatedMaster == null) throw new BaseArtifactException("Invalid use-master value [${relatedMasterName}], master not found in entity ${relInfo.relatedEntityName}")
                // logger.warn("Including master ${relatedMasterName} on entity ${relInfo.relatedEd.getFullEntityName()}")

                combinedList.addAll(relatedMaster.detailList)

                return combinedList
            } else {
                return internalDetailList
            }
        }
    }

    // NOTE: used in the DataEdit screen
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

        // used in EntityDetail screen
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
        static final String indentBase = "- "
        void buildString(StringBuilder builder, int level, Set<String> entitiesVisited) {
            StringBuilder ib = new StringBuilder()
            for (int i = 0; i <= level; i++) ib.append(indentBase)
            String indent = ib.toString()

            for (Map.Entry<String, EntityDependents> entry in dependentEntities) {
                RelationshipInfo relInfo = relationshipInfos.get(entry.getKey())
                builder.append(indent).append(relInfo.relationshipName).append(" ").append(relInfo.keyMap).append("\n")
                if (level < 8 && !entitiesVisited.contains(entry.getValue().entityName)) {
                    entry.getValue().buildString(builder, level + 1I, entitiesVisited)
                    entitiesVisited.add(entry.getValue().entityName)
                } else if (entitiesVisited.contains(entry.getValue().entityName)) {
                    builder.append(indent).append(indentBase).append("Dependants already displayed\n")
                } else if (level == 8) {
                    builder.append(indent).append(indentBase).append("Reached level limit\n")
                }
            }
        }
    }

    String getPrettyName(String title, String baseName) {
        Set<String> baseNameParts = baseName != null ? new HashSet<>(Arrays.asList(baseName.split("(?=[A-Z])"))) : null;
        StringBuilder prettyName = new StringBuilder()
        for (String part in entityInfo.internalEntityName.split("(?=[A-Z])")) {
            if (baseNameParts != null && baseNameParts.contains(part)) continue
            if (prettyName.length() > 0) prettyName.append(" ")
            prettyName.append(part)
        }
        if (title) {
            boolean addParens = prettyName.length() > 0
            if (addParens) prettyName.append(" (")
            for (String part in title.split("(?=[A-Z])")) prettyName.append(part).append(" ")
            prettyName.deleteCharAt(prettyName.length()-1)
            if (addParens) prettyName.append(")")
        }
        // make sure pretty name isn't empty, happens when baseName is a superset of entity name
        if (prettyName.length() == 0) return StringUtilities.camelCaseToPretty(entityInfo.internalEntityName)
        return prettyName.toString()
    }

    // used in EntityCache for view entities
    Map<String, String> getMePkFieldToAliasNameMap(String entityAlias) {
        if (mePkFieldToAliasNameMapMap == null) mePkFieldToAliasNameMapMap = new HashMap<String, Map<String, String>>()
        Map<String, String> mePkFieldToAliasNameMap = (Map<String, String>) mePkFieldToAliasNameMapMap.get(entityAlias)

        if (mePkFieldToAliasNameMap != null) return mePkFieldToAliasNameMap

        mePkFieldToAliasNameMap = new HashMap<String, String>()

        // do a reverse map on member-entity pk fields to view-entity aliases
        MNode memberEntityNode = memberEntityAliasMap.get(entityAlias)
        EntityDefinition med = this.efi.getEntityDefinition(memberEntityNode.attribute("entity-name"))
        ArrayList<String> pkFieldNames = med.getPkFieldNames()
        int pkFieldNamesSize = pkFieldNames.size()
        for (int pkIdx = 0; pkIdx < pkFieldNamesSize; pkIdx++) {
            String pkName = (String) pkFieldNames.get(pkIdx)

            MNode matchingAliasNode = entityNode.children("alias").find({
                it.attribute("entity-alias") == memberEntityNode.attribute("entity-alias") &&
                (it.attribute("field") == pkName || (!it.attribute("field") && it.attribute("name") == pkName)) })
            if (matchingAliasNode != null) {
                // found an alias Node
                mePkFieldToAliasNameMap.put(pkName, matchingAliasNode.attribute("name"))
                continue
            }

            // no alias, try to find in join key-maps that map to other aliased fields

            // first try the current member-entity
            if (memberEntityNode.attribute("join-from-alias") && memberEntityNode.hasChild("key-map")) {
                boolean foundOne = false
                ArrayList<MNode> keyMapList = memberEntityNode.children("key-map")
                for (MNode keyMapNode in keyMapList) {
                    String relatedField = keyMapNode.attribute("related") ?: keyMapNode.attribute("related-field-name")
                    if (relatedField == null || relatedField.isEmpty()) {
                        if (keyMapList.size() == 1 && pkFieldNamesSize == 1) {
                            relatedField = pkName
                        } else {
                            relatedField = keyMapNode.attribute("field-name")
                        }
                    }
                    if (pkName.equals(relatedField)) {
                        String relatedPkName = keyMapNode.attribute("field-name")
                        MNode relatedMatchingAliasNode = entityNode.children("alias").find({
                            it.attribute("entity-alias") == memberEntityNode.attribute("join-from-alias") &&
                            (it.attribute("field") == relatedPkName || (!it.attribute("field") && it.attribute("name") == relatedPkName)) })
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
                if (relatedMeNode.attribute("join-from-alias") == entityAlias && relatedMeNode.hasChild("key-map")) {
                    boolean foundOne = false
                    for (MNode keyMapNode in relatedMeNode.children("key-map")) {
                        if (keyMapNode.attribute("field-name") == pkName) {
                            String relatedPkName = keyMapNode.attribute("related") ?:
                                    keyMapNode.attribute("related-field-name") ?: keyMapNode.attribute("field-name")
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
            logger.warn("Not all primary-key fields in view-entity [${fullEntityName}] for member-entity [${entityAlias}:${memberEntityNode.attribute("entity-name")}], skipping cache reverse-association, and note that if this record is updated the cache won't automatically clear; pkFieldNames=${pkFieldNames}; partial mePkFieldToAliasNameMap=${mePkFieldToAliasNameMap}")
        }

        mePkFieldToAliasNameMapMap.put(entityAlias, mePkFieldToAliasNameMap)

        return mePkFieldToAliasNameMap
    }

    Object convertFieldString(String name, String value, ExecutionContextImpl eci) {
        if (value == null) return null
        FieldInfo fieldInfo = getFieldInfo(name)
        if (fieldInfo == null) throw new EntityException("Invalid field name ${name} for entity ${fullEntityName}")
        return fieldInfo.convertFromString(value, eci.l10nFacade)
    }

    static String getFieldStringForFile(FieldInfo fieldInfo, Object value) {
        if (value == null) return null

        String outValue
        if (value instanceof Timestamp) {
            // use a Long number, no TZ issues
            outValue = ((Timestamp) value).getTime() as String
        } else if (value instanceof BigDecimal) {
            outValue = ((BigDecimal) value).toPlainString()
        } else {
            outValue = fieldInfo.convertToString(value)
        }

        return outValue
    }

    EntityConditionImplBase makeViewWhereCondition() {
        if (!isViewEntity || entityConditionNode == null) return (EntityConditionImplBase) null
        // add the view-entity.entity-condition.econdition(s)
        return makeViewListCondition(entityConditionNode)
    }
    EntityConditionImplBase makeViewHavingCondition() {
        if (!isViewEntity || entityHavingEconditions == null) return (EntityConditionImplBase) null
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
                    dateFilter.attribute("valid-date") ? efi.ecfi.resourceFacade.expand(dateFilter.attribute("valid-date"), "") as Timestamp : null))
        }
        for (MNode econdition in conditionsParent.children("econdition")) {
            EntityConditionImplBase cond;
            ConditionField field
            EntityDefinition condEd;
            String entityAliasAttr = econdition.attribute("entity-alias")
            if (entityAliasAttr) {
                MNode memberEntity = memberEntityAliasMap.get(entityAliasAttr)
                if (!memberEntity) throw new EntityException("The entity-alias [${entityAliasAttr}] was not found in view-entity [${entityInfo.internalEntityName}]")
                EntityDefinition aliasEntityDef = this.efi.getEntityDefinition(memberEntity.attribute("entity-name"))
                field = new ConditionAlias(entityAliasAttr, econdition.attribute("field-name"), aliasEntityDef)
                condEd = aliasEntityDef;
            } else {
                FieldInfo fi = getFieldInfo(econdition.attribute("field-name"))
                if (fi == null) throw new EntityException("Field ${econdition.attribute("field-name")} not found in entity ${fullEntityName}")
                field = fi.conditionField
                condEd = this;
            }
            String toFieldNameAttr = econdition.attribute("to-field-name")
            if (toFieldNameAttr != null) {
                ConditionField toField
                if (econdition.attribute("to-entity-alias")) {
                    MNode memberEntity = memberEntityAliasMap.get(econdition.attribute("to-entity-alias"))
                    if (!memberEntity) throw new EntityException("The entity-alias [${econdition.attribute("to-entity-alias")}] was not found in view-entity [${entityInfo.internalEntityName}]")
                    EntityDefinition aliasEntityDef = this.efi.getEntityDefinition(memberEntity.attribute("entity-name"))
                    toField = new ConditionAlias(econdition.attribute("to-entity-alias"), toFieldNameAttr, aliasEntityDef)
                } else {
                    FieldInfo fi = getFieldInfo(toFieldNameAttr)
                    if (fi == null) throw new EntityException("Field ${toFieldNameAttr} not found in entity ${fullEntityName}")
                    toField = fi.conditionField
                }
                cond = new FieldToFieldCondition(field, EntityConditionFactoryImpl.getComparisonOperator(econdition.attribute("operator")), toField)
            } else {
                // NOTE: may need to convert value from String to object for field
                String condValue = econdition.attribute("value") ?: null
                // NOTE: only expand if contains "${", expanding normal strings does l10n and messes up key values; hopefully this won't result in a similar issue
                if (condValue && condValue.contains("\${")) condValue = efi.ecfi.resourceFacade.expand(condValue, "") as String
                Object condValueObj = condEd.convertFieldString(field.fieldName, condValue, eci);
                cond = new FieldValueCondition(field, EntityConditionFactoryImpl.getComparisonOperator(econdition.attribute("operator")), condValueObj)
            }
            if (cond != null) {
                if ("true".equals(econdition.attribute("ignore-case"))) cond.ignoreCase()

                if ("true".equals(econdition.attribute("or-null"))) {
                    cond = (EntityConditionImplBase) this.efi.conditionFactory.makeCondition(cond, JoinOperator.OR,
                            new FieldValueCondition(field, EntityCondition.EQUALS, null))
                }

                condList.add(cond)
            }
        }
        for (MNode econditions in conditionsParent.children("econditions")) {
            EntityConditionImplBase cond = this.makeViewListCondition(econditions)
            if (cond) condList.add(cond)
        }
        if (condList == null || condList.size() == 0) return null
        if (condList.size() == 1) return (EntityConditionImplBase) condList.get(0)
        JoinOperator op = "or".equals(conditionsParent.attribute("combine")) ? JoinOperator.OR : JoinOperator.AND
        EntityConditionImplBase entityCondition = (EntityConditionImplBase) this.efi.conditionFactory.makeCondition(condList, op)
        // logger.info("============== In makeViewListCondition for entity [${entityName}] resulting entityCondition: ${entityCondition}")
        return entityCondition
    }

    Cache<EntityCondition, EntityValueBase> internalCacheOne = null
    Cache<EntityCondition, Set<EntityCondition>> internalCacheOneRa = null
    Cache<EntityCondition, Set<EntityCache.ViewRaKey>> getCacheOneViewRa = null
    Cache<EntityCondition, EntityListImpl> internalCacheList = null
    Cache<EntityCondition, Set<EntityCondition>> internalCacheListRa = null
    Cache<EntityCondition, Set<EntityCache.ViewRaKey>> internalCacheListViewRa = null
    Cache<EntityCondition, Long> internalCacheCount = null

    Cache<EntityCondition, EntityValueBase> getCacheOne(EntityCache ec) {
        if (internalCacheOne == null) internalCacheOne = ec.cfi.getCache(ec.oneKeyBase.concat(fullEntityName))
        return internalCacheOne
    }
    Cache<EntityCondition, Set<EntityCondition>> getCacheOneRa(EntityCache ec) {
        if (internalCacheOneRa == null) internalCacheOneRa = ec.cfi.getCache(ec.oneRaKeyBase.concat(fullEntityName))
        return internalCacheOneRa
    }
    Cache<EntityCondition, Set<EntityCache.ViewRaKey>> getCacheOneViewRa(EntityCache ec) {
        if (getCacheOneViewRa == null) getCacheOneViewRa = ec.cfi.getCache(ec.oneViewRaKeyBase.concat(fullEntityName))
        return getCacheOneViewRa
    }

    Cache<EntityCondition, EntityListImpl> getCacheList(EntityCache ec) {
        if (internalCacheList == null) internalCacheList = ec.cfi.getCache(ec.listKeyBase.concat(fullEntityName))
        return internalCacheList
    }
    Cache<EntityCondition, Set<EntityCondition>> getCacheListRa(EntityCache ec) {
        if (internalCacheListRa == null) internalCacheListRa = ec.cfi.getCache(ec.listRaKeyBase.concat(fullEntityName))
        return internalCacheListRa
    }
    Cache<EntityCondition, Set<EntityCache.ViewRaKey>> getCacheListViewRa(EntityCache ec) {
        if (internalCacheListViewRa == null) internalCacheListViewRa = ec.cfi.getCache(ec.listViewRaKeyBase.concat(fullEntityName))
        return internalCacheListViewRa
    }

    Cache<EntityCondition, Long> getCacheCount(EntityCache ec) {
        if (internalCacheCount == null) internalCacheCount = ec.cfi.getCache(ec.countKeyBase.concat(fullEntityName))
        return internalCacheCount
    }

    boolean tableExistsDbMetaOnly() {
        if (tableExistVerified) return true
        tableExistVerified = efi.getEntityDbMeta().tableExists(this)
        return tableExistVerified
    }

    // these methods used by EntityFacadeImpl to avoid redundant lookups of entity info
    EntityFind makeEntityFind() {
        if (entityInfo.isEntityDatasourceFactoryImpl) {
            return new EntityFindImpl(efi, this)
        } else {
            return entityInfo.datasourceFactory.makeEntityFind(fullEntityName)
        }
    }
    EntityValue makeEntityValue() {
        if (entityInfo.isEntityDatasourceFactoryImpl) {
            return new EntityValueImpl(this, efi)
        } else {
            return entityInfo.datasourceFactory.makeEntityValue(fullEntityName)
        }
    }

    @Override
    int hashCode() { return this.fullEntityName.hashCode() }

    @Override
    boolean equals(Object o) {
        if (o == null || o.getClass() != this.getClass()) return false
        EntityDefinition that = (EntityDefinition) o
        if (!this.fullEntityName.equals(that.fullEntityName)) return false
        return true
    }
}
