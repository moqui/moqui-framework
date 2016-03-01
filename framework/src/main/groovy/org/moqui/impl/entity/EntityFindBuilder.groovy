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
import org.moqui.util.MNode

import java.sql.PreparedStatement
import java.sql.SQLException
import org.moqui.impl.entity.condition.EntityConditionImplBase
import org.moqui.impl.entity.EntityJavaUtil.FieldInfo
import org.moqui.impl.entity.EntityJavaUtil.FieldOrderOptions
import org.moqui.entity.EntityException

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class EntityFindBuilder extends EntityQueryBuilder {
    protected final static Logger logger = LoggerFactory.getLogger(EntityFindBuilder.class)

    protected EntityFindBase entityFindBase

    EntityFindBuilder(EntityDefinition entityDefinition, EntityFindBase entityFindBase) {
        super(entityDefinition, entityFindBase.efi)
        this.entityFindBase = entityFindBase

        // this is always going to start with "SELECT ", so just set it here
        sqlTopLevelInternal.append("SELECT ")
    }

    void addLimitOffset(Integer limit, Integer offset) {
        if (limit == null && offset == null) return
        MNode databaseNode = this.efi.getDatabaseNode(mainEntityDefinition.getEntityGroupName())
        // if no databaseNode do nothing, means it is not a standard SQL/JDBC database
        if (databaseNode != null) {
            if (databaseNode.attribute('offset-style') == "limit") {
                // use the LIMIT/OFFSET style
                sqlTopLevelInternal.append(" LIMIT ").append(limit ?: "ALL")
                sqlTopLevelInternal.append(" OFFSET ").append(offset ?: 0)
            } else if (databaseNode.attribute('offset-style') == "fetch" || !databaseNode.attribute('offset-style')) {
                // use SQL2008 OFFSET/FETCH style by default
                if (offset != null) sqlTopLevelInternal.append(" OFFSET ").append(offset).append(" ROWS")
                if (limit != null) sqlTopLevelInternal.append(" FETCH FIRST ").append(limit).append(" ROWS ONLY")
            }
            // do nothing here for offset-style=cursor, taken care of in EntityFindImpl
        }
    }

    /** Adds FOR UPDATE, should be added to end of query */
    void makeForUpdate() { sqlTopLevelInternal.append(" FOR UPDATE") }

    void makeDistinct() { sqlTopLevelInternal.append("DISTINCT ") }

    void makeCountFunction(ArrayList<FieldInfo> fieldInfoList) {
        ArrayList<MNode> entityConditionList = this.mainEntityDefinition.getEntityNode().children("entity-condition")
        MNode entityConditionNode = entityConditionList ? entityConditionList.get(0) : null
        boolean isDistinct = this.entityFindBase.getDistinct() || (this.mainEntityDefinition.isViewEntity() &&
                "true" == entityConditionNode?.attribute('distinct'))
        boolean isGroupBy = this.mainEntityDefinition.hasFunctionAlias()

        if (isGroupBy) {
            sqlTopLevelInternal.append("COUNT(1) FROM (SELECT ")
        }

        if (isDistinct) {
            // old style, not sensitive to selecting limited columns: sql.append("DISTINCT COUNT(*) ")

            /* NOTE: the code below was causing problems so the line above may be used instead, in view-entities in
             * some cases it seems to cause the "COUNT(DISTINCT " to appear twice, causing an attempt to try to count
             * a count (function="count-distinct", distinct=true in find options)
             */
            if (fieldInfoList.size() > 0) {
                // TODO: possible to do all fields selected, or only one in SQL? if do all col names here it will blow up...
                MNode aliasNode = fieldInfoList.get(0).fieldNode
                if (aliasNode != null && aliasNode.attribute('function')) {
                    // if the field has a function already we don't want to count just it, would be meaningless
                    sqlTopLevelInternal.append("COUNT(DISTINCT *) ")
                } else {
                    sqlTopLevelInternal.append("COUNT(DISTINCT ")
                    sqlTopLevelInternal.append(fieldInfoList.get(0).fullColumnName)
                    sqlTopLevelInternal.append(")")
                }
            } else {
                sqlTopLevelInternal.append("COUNT(DISTINCT *) ")
            }
        } else {
            // This is COUNT(1) instead of COUNT(*) for better performance, and should get the same results at least
            // when there is no DISTINCT
            sqlTopLevelInternal.append("COUNT(1) ")
        }
    }

    void closeCountFunctionIfGroupBy() {
        if (mainEntityDefinition.hasFunctionAlias()) { sqlTopLevelInternal.append(") TEMP_NAME") }
    }

    void makeSqlSelectFields(ArrayList<FieldInfo> fieldInfoList, ArrayList<FieldOrderOptions> fieldOptionsList) {
        boolean checkUserFields = mainEntityDefinition.allowUserField
        int size = fieldInfoList.size()
        if (size > 0) {
            for (int i = 0; i < size; i++) {
                FieldInfo fi = (FieldInfo) fieldInfoList.get(i)
                if (checkUserFields && fi.isUserField) continue

                if (i > 0) sqlTopLevelInternal.append(", ")

                boolean appendCloseParen = false
                if (fieldOptionsList != null) {
                    FieldOrderOptions foo = (FieldOrderOptions) fieldOptionsList.get(i)
                    if (foo != null && foo.caseUpperLower != null && fi.typeValue == 1) {
                        sqlTopLevelInternal.append(foo.caseUpperLower ? "UPPER(" : "LOWER(")
                        appendCloseParen = true
                    }
                }

                sqlTopLevelInternal.append(fi.fullColumnName)

                if (appendCloseParen) sqlTopLevelInternal.append(")")
            }
        } else {
            sqlTopLevelInternal.append("*")
        }
    }

    void expandJoinFromAlias(MNode entityNode, String searchEntityAlias, Set<String> entityAliasUsedSet, Set<String> entityAliasesJoinedInSet) {
        // first see if it needs expanding
        if (entityAliasesJoinedInSet.contains(searchEntityAlias)) return
        // find the a link back one in the set
        MNode memberEntityNode = entityNode.children("member-entity").find({ it.attribute('entity-alias') == searchEntityAlias })
        if (memberEntityNode == null)
            throw new EntityException("Could not find member-entity with entity-alias [${searchEntityAlias}] in view-entity [${entityNode.attribute('entity-name')}]")
        String joinFromAlias = memberEntityNode.attribute('join-from-alias')
        if (!joinFromAlias) throw new EntityException("In view-entity [${entityNode.attribute('entity-name')}] the member-entity for entity-alias [${searchEntityAlias}] has no join-from-alias and is not the first member-entity")
        if (entityAliasesJoinedInSet.contains(joinFromAlias)) {
            entityAliasesJoinedInSet.add(searchEntityAlias)
            entityAliasUsedSet.add(joinFromAlias)
            entityAliasUsedSet.add(searchEntityAlias)
        } else {
            // recurse to find member-entity with joinFromAlias, add in its joinFromAlias until one is found that is already in the set
            expandJoinFromAlias(entityNode, joinFromAlias, entityAliasUsedSet, entityAliasesJoinedInSet)
        }
    }

    void makeSqlFromClause(ArrayList<FieldInfo> fieldInfoList) {
        makeSqlFromClause(mainEntityDefinition, fieldInfoList, sqlTopLevelInternal, null)
    }
    void makeSqlFromClause(EntityDefinition localEntityDefinition, ArrayList<FieldInfo> fieldInfoList,
                           StringBuilder localBuilder, Set<String> additionalFieldsUsed) {
        localBuilder.append(" FROM ")

        MNode entityNode = localEntityDefinition.getEntityNode()

        if (localEntityDefinition.isViewEntity()) {
            MNode databaseNode = efi.getDatabaseNode(localEntityDefinition.getEntityGroupName())
            String jsAttr = databaseNode?.attribute('join-style')
            String joinStyle = jsAttr != null && jsAttr.length() > 0 ? jsAttr : "ansi"

            if (!"ansi".equals(joinStyle) && !"ansi-no-parenthesis".equals(joinStyle)) {
                throw new IllegalArgumentException("The join-style [${joinStyle}] is not supported, found on database [${databaseNode?.attribute('name')}]")
            }

            boolean useParenthesis = ("ansi" == joinStyle)

            ArrayList<MNode> memberEntityNodes = entityNode.children("member-entity")
            int memberEntityNodesSize = memberEntityNodes.size()

            // get a list of all aliased fields selected or ordered by and don't bother joining in a member-entity
            //     that is not selected or ordered by
            Set<String> entityAliasUsedSet = new HashSet<String>()
            Set<String> fieldUsedSet = new HashSet<String>()
            EntityConditionImplBase viewWhere = localEntityDefinition.makeViewWhereCondition()
            if (viewWhere != null) viewWhere.getAllAliases(entityAliasUsedSet, fieldUsedSet)
            if (entityFindBase.whereEntityCondition != null)
                entityFindBase.whereEntityConditionInternal.getAllAliases(entityAliasUsedSet, fieldUsedSet)
            if (entityFindBase.havingEntityCondition != null)
                ((EntityConditionImplBase) entityFindBase.havingEntityCondition).getAllAliases(entityAliasUsedSet, fieldUsedSet)

            for (int i = 0; i < fieldInfoList.size(); i++) {
                FieldInfo fi = (FieldInfo) fieldInfoList.get(i)
                if (!fi.isUserField) fieldUsedSet.add(fi.name)
            }

            ArrayList<String> orderByFields = entityFindBase.orderByFields
            if (orderByFields != null) {
                int orderByFieldsSize = orderByFields.size()
                for (int i = 0; i < orderByFieldsSize; i++) {
                    String orderByField = (String) orderByFields.get(i)
                    FieldOrderOptions foo = new FieldOrderOptions(orderByField)
                    fieldUsedSet.add(foo.fieldName)
                }
            }
            // additional fields to look for, when this is a sub-select for a member-entity that is a view-entity
            if (additionalFieldsUsed != null) fieldUsedSet.addAll(additionalFieldsUsed)
            // get a list of entity aliases used
            for (String fieldName in fieldUsedSet) {
                MNode aliasNode = localEntityDefinition.getFieldNode(fieldName)
                String entityAlias = aliasNode != null ? aliasNode.attribute('entity-alias') : null
                if (entityAlias != null && entityAlias.length() > 0) entityAliasUsedSet.add(entityAlias)

                MNode complexAliasNode = aliasNode.first("complex-alias")
                if (aliasNode != null && complexAliasNode != null) {
                    ArrayList<MNode> cafList = complexAliasNode.children("complex-alias-field")
                    int cafListSize = cafList.size()
                    for (int i = 0; i < cafListSize; i++) {
                        MNode cafNode = (MNode) cafList.get(i)
                        String cafEntityAlias = cafNode.attribute('entity-alias')
                        if (cafEntityAlias != null && cafEntityAlias.length() > 0) entityAliasUsedSet.add(cafEntityAlias)
                    }
                }
            }
            // if (localEntityDefinition.getFullEntityName().contains("Example"))
            //    logger.warn("============== entityAliasUsedSet=${entityAliasUsedSet} for entity ${localEntityDefinition.entityName}\n fieldUsedSet=${fieldUsedSet}\n fieldInfoList=${fieldInfoList}\n orderByFields=${entityFindBase.orderByFields}")

            // make sure each entityAlias in the entityAliasUsedSet links back to the main
            MNode memberEntityNode = (MNode) null
            for (int i = 0; i < memberEntityNodesSize; i++) {
                MNode curMeNode = (MNode) memberEntityNodes.get(i)
                String jfa = curMeNode.attribute('join-from-alias')
                if (jfa == null || jfa.length() == 0) { memberEntityNode = curMeNode; break }
            }
            String mainEntityAlias = memberEntityNode != null ? memberEntityNode.attribute('entity-alias') : null

            Set<String> entityAliasesJoinedInSet = new HashSet<String>()
            if (mainEntityAlias != null) entityAliasesJoinedInSet.add(mainEntityAlias)
            for (String entityAlias in new HashSet(entityAliasUsedSet)) {
                expandJoinFromAlias(entityNode, entityAlias, entityAliasUsedSet, entityAliasesJoinedInSet)
            }

            // logger.warn("============== entityAliasUsedSet=${entityAliasUsedSet} for entity ${localEntityDefinition.entityName}\nfieldUsedSet=${fieldUsedSet}\n fieldInfoList=${fieldInfoList}\n orderByFields=${entityFindBase.orderByFields}")

            // keep a set of all aliases in the join so far and if the left entity alias isn't there yet, and this
            // isn't the first one, throw an exception
            Set<String> joinedAliasSet = new TreeSet<String>()

            // on initial pass only add opening parenthesis since easier than going back and inserting them, then insert the rest
            boolean isFirst = true
            boolean fromEmpty = true
            for (int meInd = 0; meInd < memberEntityNodesSize; meInd++) {
                MNode relatedMemberEntityNode = (MNode) memberEntityNodes.get(meInd)

                String entityAlias = relatedMemberEntityNode.attribute('entity-alias')
                String joinFromAlias = relatedMemberEntityNode.attribute('join-from-alias')
                // logger.warn("=================== joining member-entity ${relatedMemberEntity}")

                // if this isn't joined in skip it (should be first one only); the first is handled below
                if (joinFromAlias == null || joinFromAlias.length() == 0) continue

                // if entity alias not used don't join it in
                if (!entityAliasUsedSet.contains(entityAlias)) continue
                if (!entityAliasUsedSet.contains(joinFromAlias)) continue

                if (isFirst && useParenthesis) localBuilder.append('(')

                // adding to from, then it's not empty
                fromEmpty = false

                MNode linkMemberNode = memberEntityNodes.find({ it.attribute('entity-alias') == joinFromAlias })
                String linkEntityName = linkMemberNode != null ? linkMemberNode.attribute('entity-name') : null
                EntityDefinition linkEntityDefinition = efi.getEntityDefinition(linkEntityName)
                String relatedLinkEntityName = relatedMemberEntityNode.attribute('entity-name')
                EntityDefinition relatedLinkEntityDefinition = efi.getEntityDefinition(relatedLinkEntityName)

                if (isFirst) {
                    // first link, add link entity for this one only, for others add related link entity
                    makeSqlViewTableName(linkEntityDefinition, fieldInfoList, localBuilder)
                    localBuilder.append(" ").append(joinFromAlias)

                    joinedAliasSet.add(joinFromAlias)
                } else {
                    // make sure the left entity alias is already in the join...
                    if (!joinedAliasSet.contains(joinFromAlias)) {
                        logger.error("For view-entity [${localEntityDefinition.getFullEntityName()}] found member-entity with @join-from-alias [${joinFromAlias}] that isn't in the joinedAliasSet: ${joinedAliasSet}; view-entity Node: ${entityNode}")
                        throw new EntityException("Tried to link the " + entityAlias + " alias to the " + joinFromAlias +
                                " alias of the " + localEntityDefinition.getFullEntityName() +
                                " view-entity, but it is not the first member-entity and has not been joined to a previous member-entity. In other words, the left/main alias isn't connected to the rest of the member-entities yet.".toString())
                    }
                }
                // now put the rel (right) entity alias into the set that is in the join
                joinedAliasSet.add(entityAlias)

                if (relatedMemberEntityNode.attribute('join-optional') == "true") {
                    localBuilder.append(" LEFT OUTER JOIN ")
                } else {
                    localBuilder.append(" INNER JOIN ")
                }

                makeSqlViewTableName(relatedLinkEntityDefinition, fieldInfoList, localBuilder)
                localBuilder.append(" ").append(entityAlias).append(" ON ")

                ArrayList<MNode> keyMaps = relatedMemberEntityNode.children("key-map")
                if (keyMaps == null || keyMaps.size() == 0) {
                    throw new IllegalArgumentException((String) "No member-entity/join key-maps found for the " +
                            joinFromAlias + " and the " + entityAlias +
                            " member-entities of the " + localEntityDefinition.getFullEntityName() + " view-entity.")
                }

                for (int i = 0; i < keyMaps.size(); i++) {
                    MNode keyMap = keyMaps.get(i)
                    if (i > 0) localBuilder.append(" AND ")

                    localBuilder.append(joinFromAlias).append(".")
                    // NOTE: sanitizeColumnName caused issues elsewhere, eliminate here too since we're not using AS clauses
                    localBuilder.append(linkEntityDefinition.getColumnName(keyMap.attribute('field-name'), false))
                    // localBuilder.append(sanitizeColumnName(linkEntityDefinition.getColumnName((String) keyMap.attribute('field-name'), false)))

                    localBuilder.append(" = ")

                    String relatedFieldName = keyMap.attribute('related-field-name')
                    if (relatedFieldName == null || relatedFieldName.length() == 0) relatedFieldName = keyMap.attribute('field-name')
                    if (!relatedLinkEntityDefinition.isField(relatedFieldName) &&
                            relatedLinkEntityDefinition.pkFieldNames.size() == 1 && keyMaps.size() == 1) {
                        relatedFieldName = relatedLinkEntityDefinition.pkFieldNames.get(0)
                        // if we don't match these constraints and get this default we'll get an error later...
                    }
                    localBuilder.append(entityAlias)
                    localBuilder.append(".")
                    localBuilder.append(relatedLinkEntityDefinition.getColumnName(relatedFieldName, false))
                    // NOTE: sanitizeColumnName here breaks the generated SQL, in the case of a view within a view we want EAO.EAI.COL_NAME...
                    // localBuilder.append(sanitizeColumnName(relatedLinkEntityDefinition.getColumnName(relatedFieldName, false)))
                }

                ArrayList<MNode> entityConditionList = relatedMemberEntityNode.children("entity-condition")
                if (entityConditionList != null && entityConditionList.size() > 0) {
                    // add any additional manual conditions for the member-entity view link here
                    MNode entityCondition = entityConditionList.get(0)
                    EntityConditionImplBase linkEcib = localEntityDefinition.makeViewListCondition(entityCondition)
                    localBuilder.append(" AND ")
                    linkEcib.makeSqlWhere(this)
                }

                isFirst = false
            }
            if (!fromEmpty && useParenthesis) localBuilder.append(')')

            // handle member-entities not referenced in any member-entity.@join-from-alias attribute
            for (int meInd = 0; meInd < memberEntityNodesSize; meInd++) {
                MNode memberEntity = (MNode) memberEntityNodes.get(meInd)
                String memberEntityAlias = memberEntity.attribute('entity-alias')

                // if entity alias not used don't join it in
                if (!entityAliasUsedSet.contains(memberEntityAlias)) continue

                if (joinedAliasSet.contains(memberEntityAlias)) continue

                EntityDefinition fromEntityDefinition = efi.getEntityDefinition(memberEntity.attribute('entity-name'))
                if (fromEmpty) fromEmpty = false else localBuilder.append(", ")
                makeSqlViewTableName(fromEntityDefinition, fieldInfoList, localBuilder)
                localBuilder.append(" ").append(memberEntityAlias)
            }
        } else {
            localBuilder.append(localEntityDefinition.getFullTableName())
        }
    }

    /* void makeSqlViewTableName(StringBuilder localBuilder) {
        makeSqlViewTableName(this.mainEntityDefinition, localBuilder)
    } */
    void makeSqlViewTableName(EntityDefinition localEntityDefinition, ArrayList<FieldInfo> fieldInfoList, StringBuilder localBuilder) {
        if (localEntityDefinition.isViewEntity()) {
            localBuilder.append("(SELECT ")

            boolean isFirst = true
            // fields used for group by clause
            Set<String> localFieldsToSelect = new HashSet<>()
            // additional fields to consider when trimming the member-entities to join
            Set<String> additionalFieldsUsed = new HashSet<>()
            for (MNode aliasNode in localEntityDefinition.getEntityNode().children("alias")) {
                localFieldsToSelect.add((String) aliasNode.attribute('name'))
                additionalFieldsUsed.add((String) aliasNode.attribute('field') ?: (String) aliasNode.attribute('name'))
                if (isFirst) isFirst = false else localBuilder.append(", ")
                localBuilder.append(localEntityDefinition.getColumnName((String) aliasNode.attribute('name'), true))
                // TODO: are the next two lines really needed? have removed AS stuff elsewhere since it is not commonly used and not needed
                //localBuilder.append(" AS ")
                //localBuilder.append(sanitizeColumnName(localEntityDefinition.getColumnName((String) aliasNode.attribute('name'), false)))
            }

            makeSqlFromClause(localEntityDefinition, fieldInfoList, localBuilder, additionalFieldsUsed)


            StringBuilder gbClause = new StringBuilder()
            if (localEntityDefinition.hasFunctionAlias()) {
                // do a different approach to GROUP BY: add all fields that are selected and don't have a function
                for (MNode aliasNode in localEntityDefinition.getEntityNode().children("alias")) {
                    if (localFieldsToSelect.contains(aliasNode.attribute('name')) && !aliasNode.attribute('function')) {
                        if (gbClause) gbClause.append(", ")
                        gbClause.append(localEntityDefinition.getColumnName((String) aliasNode.attribute('name'), false))
                    }
                }
            }
            if (gbClause) {
                localBuilder.append(" GROUP BY ")
                localBuilder.append(gbClause.toString())
            }

            localBuilder.append(")");
        } else {
            localBuilder.append(localEntityDefinition.getFullTableName())
        }
    }

    void startWhereClause() {
        sqlTopLevelInternal.append(" WHERE ")
    }

    void makeGroupByClause(ArrayList<FieldInfo> fieldInfoList) {
        if (this.mainEntityDefinition.isViewEntity()) {
            StringBuilder gbClause = new StringBuilder()
            if (this.mainEntityDefinition.hasFunctionAlias()) {
                // do a different approach to GROUP BY: add all fields that are selected and don't have a function
                for (MNode aliasNode in this.mainEntityDefinition.getEntityNode().children("alias")) {
                    if (!aliasNode.attribute('function')) {
                        String aliasName = aliasNode.attribute('name')
                        boolean foundField = false
                        for (int i = 0; i < fieldInfoList.size(); i++) if (fieldInfoList.get(i).name == aliasName) foundField = true
                        if (foundField) {
                            if (gbClause) gbClause.append(", ")
                            gbClause.append(this.mainEntityDefinition.getColumnName((String) aliasNode.attribute('name'), false))
                        }
                    }
                }
            }
            if (gbClause) {
                sqlTopLevelInternal.append(" GROUP BY ")
                sqlTopLevelInternal.append(gbClause.toString())
            }
        }
    }

    void startHavingClause() {
        sqlTopLevelInternal.append(" HAVING ")
    }

    void makeOrderByClause(ArrayList<String> orderByFieldList) {
        if (orderByFieldList) {
            sqlTopLevelInternal.append(" ORDER BY ")
        }
        int obflSize = orderByFieldList.size()
        for (int i = 0; i < obflSize; i++) {
            String fieldName = (String) orderByFieldList.get(i)
            if (fieldName == null || fieldName.length() == 0) continue

            if (i > 0) sqlTopLevelInternal.append(", ")

            // Parse the fieldName (can have other stuff in it, need to tear down to just the field name)
            FieldOrderOptions foo = new FieldOrderOptions(fieldName)
            fieldName = foo.fieldName

            int typeValue = 1
            FieldInfo fieldInfo = getMainEd().getFieldInfo(fieldName)
            if (fieldInfo != null) {
                typeValue = fieldInfo.typeValue
            } else {
                logger.warn("Making ORDER BY clause, could not find field [${fieldName}] in entity [${getMainEd().getFullEntityName()}]")
            }

            // now that it's all torn down, build it back up using the column name
            if (foo.caseUpperLower != null && typeValue == 1) sqlTopLevelInternal.append(foo.caseUpperLower ? "UPPER(" : "LOWER(")
            sqlTopLevelInternal.append(fieldInfo.fullColumnName)
            if (foo.caseUpperLower != null && typeValue == 1) sqlTopLevelInternal.append(")")

            sqlTopLevelInternal.append(foo.descending ? " DESC" : " ASC")

            if (foo.nullsFirstLast != null) sqlTopLevelInternal.append(foo.nullsFirstLast ? " NULLS FIRST" : " NULLS LAST")
        }
    }

    @Override
    PreparedStatement makePreparedStatement() {
        if (connection == null) throw new IllegalStateException("Cannot make PreparedStatement, no Connection in place")
        String sql = sqlTopLevelInternal.toString()
        // if (this.mainEntityDefinition.getFullEntityName().contains("Example")) logger.warn("========= making find PreparedStatement for SQL: ${sql}; parameters: ${getParameters()}")
        if (logger.isDebugEnabled()) logger.debug("making find PreparedStatement for SQL: ${sql}")
        try {
            ps = connection.prepareStatement(sql, entityFindBase.resultSetType, entityFindBase.resultSetConcurrency)
            if (entityFindBase.maxRows > 0) ps.setMaxRows(entityFindBase.maxRows)
            if (entityFindBase.fetchSize > 0) ps.setFetchSize(entityFindBase.fetchSize)
        } catch (SQLException e) {
            handleSqlException(e, sql)
        }
        return ps
    }
}
