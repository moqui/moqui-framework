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

import org.moqui.entity.EntityException;
import org.moqui.impl.entity.condition.EntityConditionImplBase;
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

    protected EntityFindBase entityFindBase;

    public EntityFindBuilder(EntityDefinition entityDefinition, EntityFindBase entityFindBase) {
        super(entityDefinition, entityFindBase.efi);
        this.entityFindBase = entityFindBase;

        // this is always going to start with "SELECT ", so just set it here
        sqlTopLevel.append("SELECT ");
    }

    public void addLimitOffset(Integer limit, Integer offset) {
        if (limit == null && offset == null) return;

        MNode databaseNode = this.efi.getDatabaseNode(getMainEntityDefinition().getEntityGroupName());
        // if no databaseNode do nothing, means it is not a standard SQL/JDBC database
        if (databaseNode != null) {
            String offsetStyle = databaseNode.attribute("offset-style");
            if ("limit".equals(offsetStyle)) {
                // use the LIMIT/OFFSET style
                sqlTopLevel.append(" LIMIT ").append(limit != null && limit > 0 ? limit : "ALL");
                sqlTopLevel.append(" OFFSET ").append(offset != null ? offset : 0);
            } else if ("fetch".equals(offsetStyle) || offsetStyle == null || offsetStyle.length() == 0) {
                // use SQL2008 OFFSET/FETCH style by default
                if (offset != null) sqlTopLevel.append(" OFFSET ").append(offset).append(" ROWS");
                if (limit != null) sqlTopLevel.append(" FETCH FIRST ").append(limit).append(" ROWS ONLY");
            }
            // do nothing here for offset-style=cursor, taken care of in EntityFindImpl
        }
    }

    /** Adds FOR UPDATE, should be added to end of query */
    public void makeForUpdate() {
        MNode databaseNode = efi.getDatabaseNode(getMainEntityDefinition().getEntityGroupName());
        String forUpdateStr = databaseNode.attribute("for-update");
        if (forUpdateStr != null && forUpdateStr.length() > 0) {
            sqlTopLevel.append(" ").append(forUpdateStr);
        } else {
            sqlTopLevel.append(" FOR UPDATE");
        }
    }

    public void makeDistinct() {
        sqlTopLevel.append("DISTINCT ");
    }

    public void makeCountFunction(FieldInfo[] fieldInfoArray) {
        EntityDefinition localEd = getMainEntityDefinition();
        ArrayList<MNode> entityConditionList = localEd.internalEntityNode.children("entity-condition");
        MNode entityConditionNode = entityConditionList != null && entityConditionList.size() > 0 ? entityConditionList.get(0) : null;
        boolean isDistinct = entityFindBase.getDistinct() || (localEd.isViewEntity && entityConditionNode != null &&
                "true".equals(entityConditionNode.attribute("distinct")));
        boolean isGroupBy = localEd.entityInfo.hasFunctionAlias;

        if (isGroupBy) sqlTopLevel.append("COUNT(*) FROM (SELECT ");

        if (isDistinct) {
            // old style, not sensitive to selecting limited columns: sql.append("DISTINCT COUNT(*) ")

            /* NOTE: the code below was causing problems so the line above may be used instead, in view-entities in
             * some cases it seems to cause the "COUNT(DISTINCT " to appear twice, causing an attempt to try to count
             * a count (function="count-distinct", distinct=true in find options)
             */
            if (fieldInfoArray.length > 0) {
                // TODO: possible to do all fields selected, or only one in SQL? if do all col names here it will blow up...
                FieldInfo fi = fieldInfoArray[0];
                MNode aliasNode = fi.fieldNode;
                String aliasFunction = aliasNode != null ? aliasNode.attribute("function") : null;
                if (aliasFunction != null && aliasFunction.length() > 0) {
                    // if the field has a function already we don't want to count just it, would be meaningless
                    sqlTopLevel.append("COUNT(DISTINCT *) ");
                } else {
                    sqlTopLevel.append("COUNT(DISTINCT ");
                    sqlTopLevel.append(fi.getFullColumnName());
                    sqlTopLevel.append(")");
                }
            } else {
                sqlTopLevel.append("COUNT(DISTINCT *) ");
            }
        } else {
            // NOTE: on H2 COUNT(*) is faster than COUNT(1) (and perhaps other databases? docs hint may be faster in MySQL)
            sqlTopLevel.append("COUNT(*) ");
        }
    }

    public void closeCountFunctionIfGroupBy() {
        if (getMainEntityDefinition().entityInfo.hasFunctionAlias) sqlTopLevel.append(") TEMP_NAME");
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
        }
    }

    public void makeSqlFromClause(FieldInfo[] fieldInfoArray) {
        makeSqlFromClause(getMainEntityDefinition(), fieldInfoArray, sqlTopLevel, null);
    }

    public void makeSqlFromClause(final EntityDefinition localEntityDefinition, FieldInfo[] fieldInfoArray,
                                  StringBuilder localBuilder, Set<String> additionalFieldsUsed) {
        localBuilder.append(" FROM ");

        if (localEntityDefinition.isViewEntity) {
            final MNode entityNode = localEntityDefinition.getEntityNode();
            final MNode databaseNode = efi.getDatabaseNode(localEntityDefinition.getEntityGroupName());
            String jsAttr = databaseNode.attribute("join-style");
            final String joinStyle = jsAttr != null && jsAttr.length() > 0 ? jsAttr : "ansi";

            if (!"ansi".equals(joinStyle) && !"ansi-no-parenthesis".equals(joinStyle)) {
                throw new IllegalArgumentException("The join-style " + joinStyle + " is not supported, found on database " +
                        databaseNode.attribute("name"));
            }

            boolean useParenthesis = ("ansi".equals(joinStyle));

            ArrayList<MNode> memberEntityNodes = entityNode.children("member-entity");
            int memberEntityNodesSize = memberEntityNodes.size();

            // get a list of all aliased fields selected or ordered by and don't bother joining in a member-entity
            //     that is not selected or ordered by
            Set<String> entityAliasUsedSet = new HashSet<>();
            Set<String> fieldUsedSet = new HashSet<>();
            EntityConditionImplBase viewWhere = localEntityDefinition.makeViewWhereCondition();
            if (viewWhere != null) viewWhere.getAllAliases(entityAliasUsedSet, fieldUsedSet);
            EntityConditionImplBase whereEntityCondition = entityFindBase.getWhereEntityConditionInternal(localEntityDefinition);
            if (whereEntityCondition != null) whereEntityCondition.getAllAliases(entityAliasUsedSet, fieldUsedSet);
            if (entityFindBase.getHavingEntityCondition() != null)
                ((EntityConditionImplBase) entityFindBase.getHavingEntityCondition()).getAllAliases(entityAliasUsedSet, fieldUsedSet);

            for (int i = 0; i < fieldInfoArray.length; i++) {
                FieldInfo fi = fieldInfoArray[i];
                if (fi == null) break;
                fieldUsedSet.add(fi.name);
            }

            ArrayList<String> orderByFields = entityFindBase.orderByFields;
            if (orderByFields != null) {
                int orderByFieldsSize = orderByFields.size();
                for (int i = 0; i < orderByFieldsSize; i++) {
                    String orderByField = orderByFields.get(i);
                    EntityJavaUtil.FieldOrderOptions foo = new EntityJavaUtil.FieldOrderOptions(orderByField);
                    fieldUsedSet.add(foo.getFieldName());
                }
            }

            // additional fields to look for, when this is a sub-select for a member-entity that is a view-entity
            if (additionalFieldsUsed != null) fieldUsedSet.addAll(additionalFieldsUsed);
            // get a list of entity aliases used
            for (String fieldName : fieldUsedSet) {
                FieldInfo fi = localEntityDefinition.getFieldInfo(fieldName);
                if (fi == null)
                    throw new EntityException("Could not find field " + fieldName + " in entity " + localEntityDefinition.getFullEntityName());
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
                    makeSqlViewTableName(linkEntityDefinition, fieldInfoArray, localBuilder);
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

                makeSqlViewTableName(relatedLinkEntityDefinition, fieldInfoArray, localBuilder);
                localBuilder.append(" ").append(entityAlias).append(" ON ");

                ArrayList<MNode> keyMaps = relatedMemberEntityNode.children("key-map");
                if (keyMaps == null || keyMaps.size() == 0) {
                    throw new IllegalArgumentException("No member-entity/join key-maps found for the " + joinFromAlias +
                            " and the " + entityAlias + " member-entities of the " + localEntityDefinition.fullEntityName + " view-entity.");
                }

                int keyMapsSize = keyMaps.size();
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
                    localBuilder.append(relatedLinkEntityDefinition.getColumnName(relatedFieldName));
                    // NOTE: sanitizeColumnName here breaks the generated SQL, in the case of a view within a view we want EAO.EAI.COL_NAME...
                    // localBuilder.append(sanitizeColumnName(relatedLinkEntityDefinition.getColumnName(relatedFieldName, false)))
                }

                ArrayList<MNode> entityConditionList = relatedMemberEntityNode.children("entity-condition");
                if (entityConditionList != null && entityConditionList.size() > 0) {
                    // add any additional manual conditions for the member-entity view link here
                    MNode entityCondition = entityConditionList.get(0);
                    EntityConditionImplBase linkEcib = localEntityDefinition.makeViewListCondition(entityCondition);
                    localBuilder.append(" AND ");
                    linkEcib.makeSqlWhere(this);
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
                if (fromEmpty) fromEmpty = false;
                else localBuilder.append(", ");
                makeSqlViewTableName(fromEntityDefinition, fieldInfoArray, localBuilder);
                localBuilder.append(" ").append(memberEntityAlias);
            }
        } else {
            localBuilder.append(localEntityDefinition.getFullTableName());
        }
    }

    public void makeSqlViewTableName(EntityDefinition localEntityDefinition, FieldInfo[] fieldInfoArray, StringBuilder localBuilder) {
        EntityJavaUtil.EntityInfo entityInfo = localEntityDefinition.entityInfo;
        if (entityInfo.isView) {
            localBuilder.append("(SELECT ");

            boolean isFirst = true;
            // fields used for group by clause
            Set<String> localFieldsToSelect = new HashSet<>();
            // additional fields to consider when trimming the member-entities to join
            Set<String> additionalFieldsUsed = new HashSet<>();
            for (MNode aliasNode : localEntityDefinition.getEntityNode().children("alias")) {
                String aliasName = aliasNode.attribute("name");
                String aliasField = aliasNode.attribute("field");
                if (aliasField == null || aliasField.length() == 0) aliasField = aliasName;
                localFieldsToSelect.add(aliasName);
                additionalFieldsUsed.add(aliasField);
                if (isFirst) isFirst = false;
                else localBuilder.append(", ");
                localBuilder.append(localEntityDefinition.getColumnName(aliasName));
                // TODO: are the next two lines really needed? have removed AS stuff elsewhere since it is not commonly used and not needed
                //localBuilder.append(" AS ")
                //localBuilder.append(sanitizeColumnName(localEntityDefinition.getColumnName(aliasName), false)))
            }

            makeSqlFromClause(localEntityDefinition, fieldInfoArray, localBuilder, additionalFieldsUsed);

            StringBuilder gbClause = new StringBuilder();
            if (entityInfo.hasFunctionAlias) {
                // do a different approach to GROUP BY: add all fields that are selected and don't have a function
                for (MNode aliasNode : localEntityDefinition.getEntityNode().children("alias")) {
                    String nameAttr = aliasNode.attribute("name");
                    String functionAttr = aliasNode.attribute("function");
                    if (localFieldsToSelect.contains(nameAttr) && (functionAttr == null || functionAttr.isEmpty())) {
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
    }

    public void startWhereClause() {
        sqlTopLevel.append(" WHERE ");
    }

    public void makeGroupByClause(FieldInfo[] fieldInfoArray) {
        EntityJavaUtil.EntityInfo entityInfo = getMainEntityDefinition().entityInfo;
        if (!entityInfo.isView) return;

        StringBuilder gbClause = new StringBuilder();
        if (entityInfo.hasFunctionAlias) {
            // do a different approach to GROUP BY: add all fields that are selected and don't have a function
            for (MNode aliasNode : this.getMainEntityDefinition().getEntityNode().children("alias")) {
                String functionAttr = aliasNode.attribute("function");
                if (functionAttr == null || functionAttr.isEmpty()) {
                    String aliasName = aliasNode.attribute("name");
                    FieldInfo foundFi = null;
                    for (int i = 0; i < fieldInfoArray.length; i++) {
                        FieldInfo fi = fieldInfoArray[i];
                        if (fi != null && fi.name.equals(aliasName)) {
                            foundFi = fi;
                            break;
                        }
                    }
                    if (foundFi != null) {
                        if (gbClause.length() > 0) gbClause.append(", ");
                        gbClause.append(foundFi.getFullColumnName());
                    }
                }
            }
        }

        if (gbClause.length() > 0) {
            sqlTopLevel.append(" GROUP BY ");
            sqlTopLevel.append(gbClause.toString());
        }
    }

    public void startHavingClause() {
        sqlTopLevel.append(" HAVING ");
    }

    public void makeOrderByClause(ArrayList<String> orderByFieldList) {
        int obflSize = orderByFieldList.size();
        if (obflSize == 0) return;

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
            if (foo.getNullsFirstLast() != null) sqlTopLevel.append(foo.getNullsFirstLast() ? " NULLS FIRST" : " NULLS LAST");
        }
    }

    @Override
    public PreparedStatement makePreparedStatement() {
        if (connection == null)
            throw new IllegalStateException("Cannot make PreparedStatement, no Connection in place");
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
