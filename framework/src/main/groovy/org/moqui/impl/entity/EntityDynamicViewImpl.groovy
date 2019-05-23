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
import org.moqui.entity.EntityDynamicView
import org.moqui.entity.EntityException
import org.moqui.util.MNode

@CompileStatic
class EntityDynamicViewImpl implements EntityDynamicView {

    protected EntityFacadeImpl efi

    protected String entityName = "DynamicView"
    protected MNode entityNode = new MNode("view-entity", ["package":"dynamic", "entity-name":"DynamicView", "is-dynamic-view":"true"])

    EntityDynamicViewImpl(EntityFindImpl entityFind) { this.efi = entityFind.efi }
    EntityDynamicViewImpl(EntityFacadeImpl efi) { this.efi = efi }

    EntityDefinition makeEntityDefinition() {
        // System.out.println("========= MNode:\n${entityNode.toString()}")
        return new EntityDefinition(efi, entityNode)
    }

    @Override
    EntityDynamicView setEntityName(String entityName) {
        entityNode.attributes.put("entity-name", entityName)
        return this
    }

    @Override
    EntityDynamicView addMemberEntity(String entityAlias, String entityName, String joinFromAlias, Boolean joinOptional,
                                      Map<String, String> entityKeyMaps) {
        MNode memberEntity = entityNode.append("member-entity", ["entity-alias":entityAlias, "entity-name":entityName])
        if (joinFromAlias) {
            memberEntity.attributes.put("join-from-alias", joinFromAlias)
            memberEntity.attributes.put("join-optional", (joinOptional ? "true" : "false"))
        }
        if (entityKeyMaps) for (Map.Entry<String, String> keyMapEntry in entityKeyMaps.entrySet()) {
            memberEntity.append("key-map", ["field-name":keyMapEntry.getKey(), "related":keyMapEntry.getValue()])
        }
        return this
    }

    @Override
    EntityDynamicView addRelationshipMember(String entityAlias, String joinFromAlias, String relationshipName,
                                            Boolean joinOptional) {
        MNode joinFromMemberEntityNode =
                entityNode.first({ MNode it -> it.name == "member-entity" && it.attribute("entity-alias") == joinFromAlias })
        String entityName = joinFromMemberEntityNode.attribute("entity-name")
        EntityDefinition joinFromEd = efi.getEntityDefinition(entityName)
        EntityJavaUtil.RelationshipInfo relInfo = joinFromEd.getRelationshipInfo(relationshipName)
        if (relInfo == null) throw new EntityException("Relationship not found with name [${relationshipName}] on entity [${entityName}]")

        Map<String, String> relationshipKeyMap = relInfo.keyMap
        MNode memberEntity = entityNode.append("member-entity", ["entity-alias":entityAlias, "entity-name":relInfo.relatedEntityName])
        memberEntity.attributes.put("join-from-alias", joinFromAlias)
        memberEntity.attributes.put("join-optional", (joinOptional ? "true" : "false"))
        for (Map.Entry<String, String> keyMapEntry in relationshipKeyMap.entrySet()) {
            memberEntity.append("key-map", ["field-name":keyMapEntry.getKey(), "related":keyMapEntry.getValue()])
        }
        if (relInfo.keyValueMap != null && relInfo.keyValueMap.size() > 0) {
            Map<String, String> keyValueMap = relInfo.keyValueMap
            MNode entityCondition = memberEntity.append("entity-condition", null)
            for (Map.Entry<String, String> keyValueEntry: keyValueMap.entrySet()) {
                entityCondition.append("econdition",
                        ['entity-alias': entityAlias, 'field-name': keyValueEntry.getKey(), 'value': keyValueEntry.getValue()])
            }
        }
        return this
    }

    MNode getViewEntityNode() { return entityNode }

    @Override List<MNode> getMemberEntityNodes() { return entityNode.children("member-entity") }

    @Override
    EntityDynamicView addAliasAll(String entityAlias, String prefix) {
        entityNode.append("alias-all", ["entity-alias":entityAlias, "prefix":prefix])
        return this
    }

    @Override
    EntityDynamicView addAlias(String entityAlias, String name) {
        entityNode.append("alias", ["entity-alias":entityAlias, "name":name])
        return this
    }
    @Override
    EntityDynamicView addAlias(String entityAlias, String name, String field, String function) {
        return addAlias(entityAlias, name, field, function, null)
    }
    EntityDynamicView addAlias(String entityAlias, String name, String field, String function, String defaultDisplay) {
        MNode aNode = entityNode.append("alias", ["entity-alias":entityAlias, name:name])
        if (field != null && !field.isEmpty()) aNode.attributes.put("field", field)
        if (function != null && !function.isEmpty()) aNode.attributes.put("function", function)
        if (defaultDisplay != null && !defaultDisplay.isEmpty()) aNode.attributes.put("default-display", defaultDisplay)
        return this
    }
    EntityDynamicView addPqExprAlias(String name, String pqExpression, String type, String defaultDisplay) {
        MNode aNode = entityNode.append("alias", [name:name, "pq-expression":pqExpression, type:(type ?: "text-long")])
        if (defaultDisplay != null && !defaultDisplay.isEmpty()) aNode.attributes.put("default-display", defaultDisplay)
        return this
    }
    MNode getAlias(String name) { return entityNode.first("alias", "name", name) }

    @Override
    EntityDynamicView addRelationship(String type, String title, String relatedEntityName, Map<String, String> entityKeyMaps) {
        MNode viewLink = entityNode.append("relationship", ["type":type, "title":title, "related":relatedEntityName])
        for (Map.Entry<String, String> keyMapEntry in entityKeyMaps.entrySet()) {
            viewLink.append("key-map", ["field-name":keyMapEntry.getKey(), "related":keyMapEntry.getValue()])
        }
        return this
    }
}
