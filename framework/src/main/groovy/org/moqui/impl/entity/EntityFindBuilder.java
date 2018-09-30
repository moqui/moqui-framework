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
package org.moqui.impl.entity;

import org.moqui.BaseArtifactException;
import org.moqui.entity.EntityException;
import org.moqui.impl.entity.condition.EntityConditionImplBase;
import org.moqui.impl.entity.EntityJavaUtil.FieldOrderOptions;
import org.moqui.util.MNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

public class EntityFindBuilder extends EntityQueryBuilder {
    protected static final Logger logger = LoggerFactory.getLogger(EntityFindBuilder.class);

    private EntityFindBase entityFindBase;
    private EntityConditionImplBase whereCondition;
    private FieldInfo[] fieldInfoArray;

    public EntityFindBuilder(EntityDefinition entityDefinition, EntityFindBase entityFindBase,
                             EntityConditionImplBase whereCondition, FieldInfo[] fieldInfoArray) {
        super(entityDefinition, entityFindBase.efi);
        this.entityFindBase = entityFindBase;
        this.whereCondition = whereCondition;
        this.fieldInfoArray = fieldInfoArray;

        // this is always going to start with "SELECT ", so just set it here
        sqlTopLevel.append("SELECT ");
    }

    public void makeDistinct() { sqlTopLevel.append("DISTINCT "); }

    public void makeCountFunction(FieldOrderOptions[] fieldOptionsArray, boolean isDistinct, boolean isGroupBy) {
        int fiaLength = fieldInfoArray.length;
        if (isGroupBy || (isDistinct && fiaLength > 0)) {
            sqlTopLevel.append("COUNT(*) FROM (SELECT ");
            if (isDistinct) sqlTopLevel.append("DISTINCT ");
            makeSqlSelectFields(fieldInfoArray, fieldOptionsArray, true);
            // NOTE: this will be closed by closeCountSubSelect()
        } else {
            if (isDistinct) {
                sqlTopLevel.append("COUNT(DISTINCT *) ");
            } else {
                // NOTE: on H2 COUNT(*) is faster than COUNT(1) (and perhaps other databases? docs hint may be faster in MySQL)
                sqlTopLevel.append("COUNT(*) ");
            }
        }
    }

    public void closeCountSubSelect(int fiaLength, boolean isDistinct, boolean isGroupBy) {
        if (isGroupBy || (isDistinct && fiaLength > 0)) sqlTopLevel.append(") TEMP_NAME");
    }

    public void expandJoinFromAlias(final MNode entityNode, final String searchEntityAlias, Set<String> entityAliasUsedSet,
                                    Set<String> entityAliasesJoinedInSet) {
        // first see if it needs expanding
        if (entityAliasesJoinedInSet.contains(searchEntityAlias)) return;

        // find the a link back one in the set
        MNode memberEntityNode = entityNode.first("member-entity", "entity-alias", searchEntityAlias);
        if (memberEntityNode == null) throw new EntityException("Could not find member-entity with entity-alias " +
                searchEntityAlias + " in view-entity " + entityNode.attribute("entity-name"));
        String joinFromAlias = memberEntityNode.attribute("join-from-alias");
        if (joinFromAlias == null || joinFromAlias.length() == 0) throw new EntityException("In view-entity " +
                entityNode.attribute("entity-name") + " the member-entity for entity-alias " + searchEntityAlias +
                " has no join-from-alias and is not the first member-entity");
        if (entityAliasesJoinedInSet.contains(joinFromAlias)) {
            entityAliasesJoinedInSet.add(searchEntityAlias);
            entityAliasUsedSet.add(joinFromAlias);
            entityAliasUsedSet.add(searchEntityAlias);
        } else {
            // recurse to find member-entity with joinFromAlias, add in its joinFromAlias until one is found that is already in the set
            expandJoinFromAlias(entityNode, joinFromAlias, entityAliasUsedSet, entityAliasesJoinedInSet);
            // if no exception from an alias not found or not joined in then we found a join path back so add in the current search alias
            entityAliasesJoinedInSet.add(searchEntityAlias);
            entityAliasUsedSet.add(searchEntityAlias);
        }
    }

    public void makeSqlFromClause() {
        whereCondition = makeSqlFromClause(mainEntityDefinition, sqlTopLevel, whereCondition,
                (EntityConditionImplBase) entityFindBase.getHavingEntityCondition(), null);
    }

    public EntityConditionImplBase makeSqlFromClause(final EntityDefinition localEntityDefinition, StringBuilder localBuilder,
                EntityConditionImplBase localWhereCondition, EntityConditionImplBase localHavingCondition, Set<String> additionalFieldsUsed) {
        localBuilder.append(" FROM ");

        EntityConditionImplBase outWhereCondition = localWhereCondition;
        if (localEntityDefinition.isViewEntity) {
            final MNode entityNode = localEntityDefinition.getEntityNode();
            final MNode databaseNode = efi.getDatabaseNode(localEntityDefinition.getEntityGroupName());
            String jsAttr = databaseNode.attribute("join-style");
            final String joinStyle = jsAttr != null && jsAttr.length() > 0 ? jsAttr : "ansi";

            if (!"ansi".equals(joinStyle) && !"ansi-no-parenthesis".equals(joinStyle)) {
                throw new BaseArtifactException("The join-style " + joinStyle + " is not supported, found on database " +
                        databaseNode.attribute("name"));
            }

            boolean useParenthesis = "ansi".equals(joinStyle);

            ArrayList<MNode> memberEntityNodes = entityNode.children("member-entity");
            int memberEntityNodesSize = memberEntityNodes.size();

            // get a list of all aliased fields selected or ordered by and don't bother joining in a member-entity
            //     that is not selected or ordered by
            Set<String> entityAliasUsedSet = new HashSet<>();
            Set<String> fieldUsedSet = new HashSet<>();

            // add aliases used to fields used
            EntityConditionImplBase viewWhere = localEntityDefinition.makeViewWhereCondition();
            if (viewWhere != null) viewWhere.getAllAliases(entityAliasUsedSet, fieldUsedSet);
            if (localWhereCondition != null) localWhereCondition.getAllAliases(entityAliasUsedSet, fieldUsedSet);
            if (localHavingCondition != null) localHavingCondition.getAllAliases(entityAliasUsedSet, fieldUsedSet);

            // logger.warn("SQL from viewWhere " + viewWhere + " localWhereCondition " + localWhereCondition + " localHavingCondition " + localHavingCondition);
            // logger.warn("SQL from fieldUsedSet " + fieldUsedSet + " additionalFieldsUsed " + additionalFieldsUsed);
            if (additionalFieldsUsed == null) {
                // add selected fields
                for (int i = 0; i < fieldInfoArray.length; i++) {
                    FieldInfo fi = fieldInfoArray[i];
                    if (fi == null) break;
                    fieldUsedSet.add(fi.name);
                }
                // add order by fields
                ArrayList<String> orderByFields = entityFindBase.orderByFields;
                if (orderByFields != null) {
                    int orderByFieldsSize = orderByFields.size();
                    for (int i = 0; i < orderByFieldsSize; i++) {
                        String orderByField = orderByFields.get(i);
                        EntityJavaUtil.FieldOrderOptions foo = new EntityJavaUtil.FieldOrderOptions(orderByField);
                        fieldUsedSet.add(foo.getFieldName());
                    }
                }
            } else {
                // additional fields to look for, when this is a sub-select for a member-entity that is a view-entity
                fieldUsedSet.addAll(additionalFieldsUsed);
            }

            // get a list of entity aliases used
            for (String fieldName : fieldUsedSet) {
                FieldInfo fi = localEntityDefinition.getFieldInfo(fieldName);
                if (fi == null) throw new EntityException("Could not find field " + fieldName + " in entity " + localEntityDefinition.getFullEntityName());
                entityAliasUsedSet.addAll(fi.entityAliasUsedSet);
            }

            // if (localEntityDefinition.getFullEntityName().contains("Example"))
            //    logger.warn("============== entityAliasUsedSet=${entityAliasUsedSet} for entity ${localEntityDefinition.entityName}\n fieldUsedSet=${fieldUsedSet}\n fieldInfoList=${fieldInfoList}\n orderByFields=${entityFindBase.orderByFields}")

            // make sure each entityAlias in the entityAliasUsedSet links back to the main
            MNode memberEntityNode = null;
            for (int i = 0; i < memberEntityNodesSize; i++) {
                MNode curMeNode = memberEntityNodes.get(i);
                String jfa = curMeNode.attribute("join-from-alias");
                if (jfa == null || jfa.length() == 0) {
                    memberEntityNode = curMeNode;
                    break;
                }
            }

            String mainEntityAlias = memberEntityNode != null ? memberEntityNode.attribute("entity-alias") : null;

            Set<String> entityAliasesJoinedInSet = new HashSet<>();
            if (mainEntityAlias != null) entityAliasesJoinedInSet.add(mainEntityAlias);
            for (String entityAlias : new HashSet<>(entityAliasUsedSet)) {
                expandJoinFromAlias(entityNode, entityAlias, entityAliasUsedSet, entityAliasesJoinedInSet);
            }

            // logger.warn("============== entityAliasUsedSet=${entityAliasUsedSet} for entity ${localEntityDefinition.entityName}\nfieldUsedSet=${fieldUsedSet}\n fieldInfoList=${fieldInfoList}\n orderByFields=${entityFindBase.orderByFields}")

            // at this point entityAliasUsedSet is finalized so do authz filter if needed
            ArrayList<EntityConditionImplBase> filterCondList = efi.ecfi.getEci().artifactExecutionFacade.filterFindForUser(localEntityDefinition, entityAliasUsedSet);
            outWhereCondition = EntityConditionFactoryImpl.addAndListToCondition(outWhereCondition, filterCondList);

            // keep a set of all aliases in the join so far and if the left entity alias isn't there yet, and this
            // isn't the first one, throw an exception
            final Set<String> joinedAliasSet = new TreeSet<>();

            // on initial pass only add opening parenthesis since easier than going back and inserting them, then insert the rest
            boolean isFirst = true;
            boolean fromEmpty = true;
            for (int meInd = 0; meInd < memberEntityNodesSize; meInd++) {
                MNode relatedMemberEntityNode = memberEntityNodes.get(meInd);

                String entityAlias = relatedMemberEntityNode.attribute("entity-alias");
                final String joinFromAlias = relatedMemberEntityNode.attribute("join-from-alias");
                // logger.warn("=================== joining member-entity ${relatedMemberEntity}")

                // if this isn't joined in skip it (should be first one only); the first is handled below
                if (joinFromAlias == null || joinFromAlias.length() == 0) continue;

                // if entity alias not used don't join it in
                if (!entityAliasUsedSet.contains(entityAlias)) continue;
                if (!entityAliasUsedSet.contains(joinFromAlias)) continue;

                if (isFirst && useParenthesis) localBuilder.append("(");

                // adding to from, then it's not empty
                fromEmpty = false;

                MNode linkMemberNode = null;
                for (int i = 0; i < memberEntityNodesSize; i++) {
                    MNode curMeNode = memberEntityNodes.get(i);
                    if (joinFromAlias.equals(curMeNode.attribute("entity-alias"))) {
                        linkMemberNode = curMeNode;
                        break;
                    }
                }

                String linkEntityName = linkMemberNode != null ? linkMemberNode.attribute("entity-name") : null;
                EntityDefinition linkEntityDefinition = efi.getEntityDefinition(linkEntityName);
                String relatedLinkEntityName = relatedMemberEntityNode.attribute("entity-name");
                EntityDefinition relatedLinkEntityDefinition = efi.getEntityDefinition(relatedLinkEntityName);

                if (isFirst) {
                    // first link, add link entity for this one only, for others add related link entity
                    outWhereCondition = makeSqlViewTableName(linkEntityDefinition, localBuilder, outWhereCondition, localHavingCondition);
                    localBuilder.append(" ").append(joinFromAlias);

                    joinedAliasSet.add(joinFromAlias);
                } else {
                    // make sure the left entity alias is already in the join...
                    if (!joinedAliasSet.contains(joinFromAlias)) {
                        logger.error("For view-entity [" + localEntityDefinition.fullEntityName +
                                "] found member-entity with @join-from-alias [" + joinFromAlias +
                                "] that isn\'t in the joinedAliasSet: " + joinedAliasSet + "; view-entity Node: " + entityNode);
                        throw new EntityException("Tried to link the " + entityAlias + " alias to the " + joinFromAlias +
                                " alias of the " + localEntityDefinition.fullEntityName +
                                " view-entity, but it is not the first member-entity and has not been joined to a previous member-entity. In other words, the left/main alias isn't connected to the rest of the member-entities yet.");
                    }
                }
                // now put the rel (right) entity alias into the set that is in the join
                joinedAliasSet.add(entityAlias);

                if ("true".equals(relatedMemberEntityNode.attribute("join-optional"))) {
                    localBuilder.append(" LEFT OUTER JOIN ");
                } else {
                    localBuilder.append(" INNER JOIN ");
                }

                boolean subSelect = "true".equals(relatedMemberEntityNode.attribute("sub-select"));
                if (subSelect) {
                    makeSqlMemberSubSelect(entityAlias, relatedMemberEntityNode, relatedLinkEntityDefinition, localBuilder);
                } else {
                    outWhereCondition = makeSqlViewTableName(relatedLinkEntityDefinition, localBuilder, outWhereCondition, localHavingCondition);
                }
                localBuilder.append(" ").append(entityAlias).append(" ON ");

                ArrayList<MNode> keyMaps = relatedMemberEntityNode.children("key-map");
                ArrayList<MNode> entityConditionList = relatedMemberEntityNode.children("entity-condition");
                if ((keyMaps == null || keyMaps.size() == 0) && (entityConditionList == null || entityConditionList.size() == 0)) {
                    throw new EntityException("No member-entity/join key-maps found for the " + joinFromAlias +
                            " and the " + entityAlias + " member-entities of the " + localEntityDefinition.fullEntityName + " view-entity.");
                }

                int keyMapsSize = keyMaps != null ? keyMaps.size() : 0;
                for (int i = 0; i < keyMapsSize; i++) {
                    MNode keyMap = keyMaps.get(i);
                    if (i > 0) localBuilder.append(" AND ");

                    localBuilder.append(joinFromAlias).append(".");
                    // NOTE: sanitizeColumnName caused issues elsewhere, eliminate here too since we're not using AS clauses
                    localBuilder.append(linkEntityDefinition.getColumnName(keyMap.attribute("field-name")));
                    // localBuilder.append(sanitizeColumnName(linkEntityDefinition.getColumnName(keyMap.attribute("field-name"), false)))

                    localBuilder.append(" = ");

                    final String relatedAttr = keyMap.attribute("related");
                    String relatedFieldName = relatedAttr != null && !relatedAttr.isEmpty() ? relatedAttr : keyMap.attribute("related-field-name");
                    if (relatedFieldName == null || relatedFieldName.length() == 0)
                        relatedFieldName = keyMap.attribute("field-name");
                    if (!relatedLinkEntityDefinition.isField(relatedFieldName) &&
                            relatedLinkEntityDefinition.getPkFieldNames().size() == 1 && keyMaps.size() == 1) {
                        relatedFieldName = relatedLinkEntityDefinition.getPkFieldNames().get(0);
                        // if we don't match these constraints and get this default we'll get an error later...
                    }

                    localBuilder.append(entityAlias);
                    localBuilder.append(".");
                    FieldInfo relatedFieldInfo = relatedLinkEntityDefinition.getFieldInfo(relatedFieldName);
                    if (relatedFieldInfo == null) throw new EntityException("Invalid field name " + relatedFieldName + " for entity " + relatedLinkEntityDefinition.fullEntityName);
                    if (subSelect) {
                        localBuilder.append(EntityJavaUtil.camelCaseToUnderscored(relatedFieldInfo.name));
                    } else {
                        localBuilder.append(relatedFieldInfo.getFullColumnName());
                    }
                    // NOTE: sanitizeColumnName here breaks the generated SQL, in the case of a view within a view we want EAO.EAI.COL_NAME...
                    // localBuilder.append(sanitizeColumnName(relatedLinkEntityDefinition.getColumnName(relatedFieldName, false)))
                }

                if (entityConditionList != null && entityConditionList.size() > 0) {
                    // add any additional manual conditions for the member-entity view link here
                    MNode entityCondition = entityConditionList.get(0);
                    EntityConditionImplBase linkEcib = localEntityDefinition.makeViewListCondition(entityCondition);
                    if (keyMapsSize > 0) localBuilder.append(" AND ");
                    linkEcib.makeSqlWhere(this, null);
                }

                isFirst = false;
            }

            if (!fromEmpty && useParenthesis) localBuilder.append(")");

            // handle member-entities not referenced in any member-entity.@join-from-alias attribute
            for (int meInd = 0; meInd < memberEntityNodesSize; meInd++) {
                MNode memberEntity = memberEntityNodes.get(meInd);
                String memberEntityAlias = memberEntity.attribute("entity-alias");

                // if entity alias not used don't join it in
                if (!entityAliasUsedSet.contains(memberEntityAlias)) continue;
                if (joinedAliasSet.contains(memberEntityAlias)) continue;

                EntityDefinition fromEntityDefinition = efi.getEntityDefinition(memberEntity.attribute("entity-name"));
                if (fromEmpty) { fromEmpty = false; } else  { localBuilder.append(", "); }
                if ("true".equals(memberEntity.attribute("sub-select"))) {
                    makeSqlMemberSubSelect(memberEntityAlias, memberEntity, fromEntityDefinition, localBuilder);
                } else {
                    outWhereCondition = makeSqlViewTableName(fromEntityDefinition, localBuilder, outWhereCondition, localHavingCondition);
                }
                localBuilder.append(" ").append(memberEntityAlias);
            }
        } else {
            // not a view-entity so do authz filter now if needed
            ArrayList<EntityConditionImplBase> filterCondList = efi.ecfi.getEci().artifactExecutionFacade.filterFindForUser(localEntityDefinition, null);
            outWhereCondition = EntityConditionFactoryImpl.addAndListToCondition(outWhereCondition, filterCondList);

            localBuilder.append(localEntityDefinition.getFullTableName());
        }

        return outWhereCondition;
    }

    public EntityConditionImplBase makeSqlViewTableName(EntityDefinition localEntityDefinition, StringBuilder localBuilder,
                EntityConditionImplBase localWhereCondition, EntityConditionImplBase localHavingCondition) {
        EntityJavaUtil.EntityInfo entityInfo = localEntityDefinition.entityInfo;
        EntityConditionImplBase outWhereCondition = localWhereCondition;
        if (entityInfo.isView) {
            localBuilder.append("(SELECT ");

            // fields used for group by clause
            Set<String> localFieldsToSelect = new HashSet<>();
            // additional fields to consider when trimming the member-entities to join
            Set<String> additionalFieldsUsed = new HashSet<>();
            ArrayList<MNode> aliasList = localEntityDefinition.getEntityNode().children("alias");
            int aliasListSize = aliasList.size();
            for (int i = 0; i < aliasListSize; i++) {
                MNode aliasNode = aliasList.get(i);
                String aliasName = aliasNode.attribute("name");
                String aliasField = aliasNode.attribute("field");
                if (aliasField == null || aliasField.length() == 0) aliasField = aliasName;
                localFieldsToSelect.add(aliasName);
                additionalFieldsUsed.add(aliasField);
                if (i > 0) localBuilder.append(", ");
                localBuilder.append(localEntityDefinition.getColumnName(aliasName));
                // TODO: are the next two lines really needed? have removed AS stuff elsewhere since it is not commonly used and not needed
                //localBuilder.append(" AS ")
                //localBuilder.append(sanitizeColumnName(localEntityDefinition.getColumnName(aliasName), false)))
            }

            // pass through localWhereCondition in case changed
            outWhereCondition = makeSqlFromClause(localEntityDefinition, localBuilder, localWhereCondition, localHavingCondition, additionalFieldsUsed);

            // TODO: refactor this like below to do in the main loop; this is currently unused though (view-entity as member-entity for sub-select)
            StringBuilder gbClause = new StringBuilder();
            if (entityInfo.hasFunctionAlias) {
                // do a different approach to GROUP BY: add all fields that are selected and don't have a function
                for (int i = 0; i < aliasListSize; i++) {
                    MNode aliasNode = aliasList.get(i);
                    String nameAttr = aliasNode.attribute("name");
                    String functionAttr = aliasNode.attribute("function");
                    String isAggregateAttr = aliasNode.attribute("is-aggregate");
                    boolean isAggFunction = isAggregateAttr != null ? "true".equalsIgnoreCase(isAggregateAttr) :
                            FieldInfo.aggFunctions.contains(functionAttr);
                    if (localFieldsToSelect.contains(nameAttr) && !isAggFunction) {
                        if (gbClause.length() > 0) gbClause.append(", ");
                        gbClause.append(localEntityDefinition.getColumnName(nameAttr));
                    }
                }
            }

            if (gbClause.length() > 0) {
                localBuilder.append(" GROUP BY ");
                localBuilder.append(gbClause.toString());
            }

            localBuilder.append(")");
        } else {
            localBuilder.append(localEntityDefinition.getFullTableName());
        }
        return outWhereCondition;
    }

    public void makeSqlMemberSubSelect(String entityAlias, MNode memberEntity, EntityDefinition localEntityDefinition,
                                       StringBuilder localBuilder) {
        localBuilder.append("(SELECT ");

        // add any fields needed to join this to another member-entity, even if not in the main set of selected fields
        TreeSet<String> joinFields = new TreeSet<>();
        ArrayList<MNode> keyMapList = memberEntity.children("key-map");
        for (int i = 0; i < keyMapList.size(); i++) {
            MNode keyMap = keyMapList.get(i);
            String relFn = keyMap.attribute("related");
            if (relFn == null || relFn.isEmpty()) relFn = keyMap.attribute("field-name");
            joinFields.add(relFn);
        }
        ArrayList<MNode> entityConditionList = memberEntity.children("entity-condition");
        if (entityConditionList != null && entityConditionList.size() > 0) {
            MNode entCondNode = entityConditionList.get(0);
            ArrayList<MNode> econdNodes = entCondNode.descendants("econdition");
            for (int i = 0; i < econdNodes.size(); i++) {
                MNode econd = econdNodes.get(i);
                if (entityAlias.equals(econd.attribute("entity-alias"))) joinFields.add(econd.attribute("field-name"));
                if (entityAlias.equals(econd.attribute("to-entity-alias"))) joinFields.add(econd.attribute("to-field-name"));
            }
        }

        // additional fields to consider when trimming the member-entities to join
        Set<String> additionalFieldsUsed = new HashSet<>();
        boolean hasAggregateFunction = false;
        boolean hasSelected = false;
        StringBuilder gbClause = new StringBuilder();
        for (int i = 0; i < fieldInfoArray.length; i++) {
            FieldInfo aliasFi = fieldInfoArray[i];
            if (!aliasFi.entityAliasUsedSet.contains(entityAlias)) continue;

            if (localEntityDefinition.isViewEntity) {
                // get the outer alias node
                String outerAliasField = aliasFi.aliasFieldName;
                // get the local entity (sub-select) field node (may be alias node if sub-select on view-entity)
                FieldInfo localFi = localEntityDefinition.getFieldInfo(outerAliasField);

                MNode aliasNode = aliasFi.fieldNode;
                MNode complexAliasNode = aliasNode.first("complex-alias");
                if (complexAliasNode != null) {
                    boolean foundOtherEntityAlias = false;
                    ArrayList<MNode> complexAliasFields = complexAliasNode.descendants("complex-alias-field");
                    for (int cafIdx = 0; cafIdx < complexAliasFields.size(); cafIdx++) {
                        MNode cafNode = complexAliasFields.get(cafIdx);
                        if (entityAlias.equals(cafNode.attribute("entity-alias"))) {
                            String cafField = cafNode.attribute("field");
                            additionalFieldsUsed.add(cafField);
                            joinFields.remove(cafField);
                        } else {
                            foundOtherEntityAlias = true;
                        }
                    }
                    if (!foundOtherEntityAlias) {
                        if (localFi == null) throw new EntityException("Could not find field " + outerAliasField + " on entity " + entityAlias + ":" + localEntityDefinition.fullEntityName);
                        String colName = localFi.getFullColumnName();
                        if (hasSelected) { localBuilder.append(", "); } else { hasSelected = true; }
                        localBuilder.append(colName).append(" AS ").append(EntityJavaUtil.camelCaseToUnderscored(localFi.name));
                        if (localFi.hasAggregateFunction) {
                            hasAggregateFunction = true;
                        } else {
                            if (gbClause.length() > 0) gbClause.append(", ");
                            gbClause.append(EntityJavaUtil.camelCaseToUnderscored(localFi.name));
                        }
                        // } else {
                        // if we found another entity alias not all on this sub-select entity (or view-entity)
                        // TODO only select part that is - IFF not already selected to make sure is selected for outer select
                    }
                } else {
                    if (localFi == null) throw new EntityException("Could not find field " + outerAliasField + " on entity " + entityAlias + ":" + localEntityDefinition.fullEntityName);
                    additionalFieldsUsed.add(localFi.name);
                    joinFields.remove(localFi.name);

                    if (hasSelected) { localBuilder.append(", "); } else { hasSelected = true; }
                    localBuilder.append(localFi.getFullColumnName()).append(" AS ").append(EntityJavaUtil.camelCaseToUnderscored(localFi.name));
                    if (localFi.hasAggregateFunction) {
                        hasAggregateFunction = true;
                    } else {
                        if (gbClause.length() > 0) gbClause.append(", ");
                        gbClause.append(EntityJavaUtil.camelCaseToUnderscored(localFi.name));
                    }
                }
            } else {
                MNode aliasNode = aliasFi.fieldNode;
                String aliasName = aliasFi.name;
                String aliasField = aliasNode.attribute("field");
                if (aliasField == null || aliasField.isEmpty()) aliasField = aliasName;
                additionalFieldsUsed.add(aliasField);
                joinFields.remove(aliasField);
                if (hasSelected) { localBuilder.append(", "); } else { hasSelected = true; }
                // NOTE: this doesn't support various things that EntityDefinition.makeFullColumnName() does like case/when, complex-alias, etc
                // those are difficult to pick out in nested XML elements where the 'alias' element has no entity-alias, and may not be needed at this level (try to handle at top level)
                String function = aliasNode.attribute("function");
                String isAggregateAttr = aliasNode.attribute("is-aggregate");
                boolean isAggFunction = isAggregateAttr != null ? "true".equalsIgnoreCase(isAggregateAttr) :
                        FieldInfo.aggFunctions.contains(function);
                hasAggregateFunction = hasAggregateFunction || isAggFunction;
                MNode complexAliasNode = aliasNode.first("complex-alias");
                if (complexAliasNode != null) {
                    String colName = mainEntityDefinition.makeFullColumnName(aliasNode, false);
                    localBuilder.append(colName).append(" AS ").append(EntityJavaUtil.camelCaseToUnderscored(aliasName));
                    if (!isAggFunction) {
                        if (gbClause.length() > 0) gbClause.append(", ");
                        gbClause.append(sanitizeColumnName(colName));
                    }
                } else if (function != null && !function.isEmpty()) {
                    String colName = EntityDefinition.getFunctionPrefix(function) + localEntityDefinition.getColumnName(aliasField) + ")";
                    localBuilder.append(colName).append(" AS ").append(EntityJavaUtil.camelCaseToUnderscored(aliasName));
                    if (!isAggFunction) {
                        if (gbClause.length() > 0) gbClause.append(", ");
                        gbClause.append(sanitizeColumnName(colName));
                    }
                } else {
                    String colName = localEntityDefinition.getColumnName(aliasField);
                    localBuilder.append(colName);
                    if (gbClause.length() > 0) gbClause.append(", ");
                    gbClause.append(colName);
                }
            }
        }
        // do the actual add of join field columns to select and group by
        for (String joinField : joinFields) {
            if (hasSelected) { localBuilder.append(", "); } else { hasSelected = true; }
            String asName = EntityJavaUtil.camelCaseToUnderscored(joinField);
            String colName = localEntityDefinition.getColumnName(joinField);
            localBuilder.append(colName).append(" AS ").append(asName);
            if (gbClause.length() > 0) gbClause.append(", ");
            gbClause.append(colName);
            if (localEntityDefinition.isViewEntity) additionalFieldsUsed.add(joinField);
        }

        // where condition to use for FROM clause (field filtering) and for sub-select WHERE clause
        EntityConditionImplBase condition = whereCondition != null ? whereCondition.filter(entityAlias, mainEntityDefinition) : null;

        // logger.warn("makeSqlMemberSubSelect SQL so far " + localBuilder.toString());
        // logger.warn("Calling makeSqlFromClause for " + entityAlias + ":" + localEntityDefinition.getEntityName() + " condition " + condition);
        // logger.warn("Calling makeSqlFromClause for " + entityAlias + ":" + localEntityDefinition.getEntityName() + " addtl fields " + additionalFieldsUsed);
        condition = makeSqlFromClause(localEntityDefinition, localBuilder, condition, null, additionalFieldsUsed);

        // add where clause, just for conditions on aliased fields on this entity-alias
        if (condition != null) {
            localBuilder.append(" WHERE ");
            condition.makeSqlWhere(this, localEntityDefinition);
        }

        if (hasAggregateFunction && gbClause.length() > 0) {
            localBuilder.append(" GROUP BY ");
            localBuilder.append(gbClause.toString());
        }

        localBuilder.append(")");
    }

    public void makeWhereClause() {
        if (whereCondition == null) return;
        EntityConditionImplBase condition = whereCondition;
        if (mainEntityDefinition.hasSubSelectMembers) {
            condition = condition.filter(null, mainEntityDefinition);
            if (condition == null) return;
        }
        sqlTopLevel.append(" WHERE ");
        condition.makeSqlWhere(this, null);
    }

    public void makeGroupByClause() {
        EntityJavaUtil.EntityInfo entityInfo = mainEntityDefinition.entityInfo;
        if (!entityInfo.isView) return;

        StringBuilder gbClause = new StringBuilder();
        if (entityInfo.hasFunctionAlias) {
            // do a different approach to GROUP BY: add all fields that are selected and don't have a function or that are in a sub-select
            for (int j = 0; j < fieldInfoArray.length; j++) {
                FieldInfo fi = fieldInfoArray[j];
                if (fi == null) continue;
                boolean doGroupBy = !fi.hasAggregateFunction;
                if (!doGroupBy && fi.memberEntityNode != null && "true".equals(fi.memberEntityNode.attribute("sub-select"))) {
                    // TODO we have a sub-select, if it is on a non-view entity we want to group by (on a view-entity would be only if no aggregate in wrapping alias)
                    EntityDefinition fromEntityDefinition = efi.getEntityDefinition(fi.memberEntityNode.attribute("entity-name"));
                    if (!fromEntityDefinition.isViewEntity) doGroupBy = true;
                }
                if (doGroupBy) {
                    if (gbClause.length() > 0) gbClause.append(", ");
                    gbClause.append(fi.getFullColumnName());
                }
            }
        }

        if (gbClause.length() > 0) {
            sqlTopLevel.append(" GROUP BY ");
            sqlTopLevel.append(gbClause.toString());
        }
    }

    public void makeHavingClause(EntityConditionImplBase condition) {
        if (condition == null) return;
        sqlTopLevel.append(" HAVING ");
        condition.makeSqlWhere(this, null);
    }

    public void makeOrderByClause(ArrayList<String> orderByFieldList, boolean hasLimitOffset) {
        int obflSize = orderByFieldList.size();
        if (obflSize == 0) {
            if (hasLimitOffset) sqlTopLevel.append(" ORDER BY 1");
            return;
        }

        MNode databaseNode = efi.getDatabaseNode(mainEntityDefinition.getEntityGroupName());
        sqlTopLevel.append(" ORDER BY ");
        for (int i = 0; i < obflSize; i++) {
            String fieldName = orderByFieldList.get(i);
            if (fieldName == null || fieldName.length() == 0) continue;
            if (i > 0) sqlTopLevel.append(", ");

            // Parse the fieldName (can have other stuff in it, need to tear down to just the field name)
            EntityJavaUtil.FieldOrderOptions foo = new EntityJavaUtil.FieldOrderOptions(fieldName);
            fieldName = foo.getFieldName();

            FieldInfo fieldInfo = getMainEd().getFieldInfo(fieldName);
            if (fieldInfo == null) throw new EntityException("Making ORDER BY clause, could not find field " +
                    fieldName + " in entity " + getMainEd().fullEntityName);
            int typeValue = fieldInfo.typeValue;

            // now that it's all torn down, build it back up using the column name
            if (foo.getCaseUpperLower() != null && typeValue == 1) sqlTopLevel.append(foo.getCaseUpperLower() ? "UPPER(" : "LOWER(");
            sqlTopLevel.append(fieldInfo.getFullColumnName());
            if (foo.getCaseUpperLower() != null && typeValue == 1) sqlTopLevel.append(")");
            sqlTopLevel.append(foo.getDescending() ? " DESC" : " ASC");
            if (!"true".equals(databaseNode.attribute("never-nulls"))) {
                if (foo.getNullsFirstLast() != null) sqlTopLevel.append(foo.getNullsFirstLast() ? " NULLS FIRST" : " NULLS LAST");
                else sqlTopLevel.append(" NULLS LAST");
            }
        }
    }
    public void addLimitOffset(Integer limit, Integer offset) {
        if (limit == null && offset == null) return;

        MNode databaseNode = efi.getDatabaseNode(mainEntityDefinition.getEntityGroupName());
        // if no databaseNode do nothing, means it is not a standard SQL/JDBC database
        if (databaseNode != null) {
            String offsetStyle = databaseNode.attribute("offset-style");
            if ("limit".equals(offsetStyle)) {
                // use the LIMIT/OFFSET style
                sqlTopLevel.append(" LIMIT ").append(limit != null && limit > 0 ? limit : "ALL");
                sqlTopLevel.append(" OFFSET ").append(offset != null ? offset : 0);
            } else if (offsetStyle == null || offsetStyle.length() == 0 || "fetch".equals(offsetStyle)) {
                // use SQL2008 OFFSET/FETCH style by default
                sqlTopLevel.append(" OFFSET ").append(offset != null ? offset.toString() : '0').append(" ROWS");
                if (limit != null) sqlTopLevel.append(" FETCH FIRST ").append(limit).append(" ROWS ONLY");
            }
            // do nothing here for offset-style=cursor, taken care of in EntityFindImpl
        }
    }

    /** Adds FOR UPDATE, should be added to end of query */
    public void makeForUpdate() {
        MNode databaseNode = efi.getDatabaseNode(mainEntityDefinition.getEntityGroupName());
        String forUpdateStr = databaseNode.attribute("for-update");
        if (forUpdateStr != null && forUpdateStr.length() > 0) {
            sqlTopLevel.append(" ").append(forUpdateStr);
        } else {
            sqlTopLevel.append(" FOR UPDATE");
        }
    }

    @Override
    public PreparedStatement makePreparedStatement() {
        if (connection == null) throw new IllegalStateException("Cannot make PreparedStatement, no Connection in place");
        finalSql = sqlTopLevel.toString();
        // if (this.mainEntityDefinition.entityName.equals("Foo")) logger.warn("========= making find PreparedStatement for SQL: ${sql}; parameters: ${getParameters()}")
        if (isDebugEnabled) logger.debug("making find PreparedStatement for SQL: " + finalSql);
        try {
            ps = connection.prepareStatement(finalSql, entityFindBase.getResultSetType(), entityFindBase.getResultSetConcurrency());
            Integer maxRows = entityFindBase.getMaxRows();
            Integer fetchSize = entityFindBase.getFetchSize();
            if (maxRows != null && maxRows > 0) ps.setMaxRows(maxRows);
            if (fetchSize != null && fetchSize > 0) ps.setFetchSize(fetchSize);
        } catch (SQLException e) {
            EntityQueryBuilder.handleSqlException(e, finalSql);
        }

        return ps;
    }
}
