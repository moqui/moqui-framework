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

import org.moqui.entity.EntityDynamicView
import org.moqui.entity.EntityException

class EntityDynamicViewImpl implements EntityDynamicView {

    protected EntityFindImpl entityFind;

    protected String entityName = "DynamicView"
    protected Node entityNode = new Node(null, "view-entity", ["package-name":"moqui.internal", "entity-name":"DynamicView", "is-dynamic-view":"true"])

    EntityDynamicViewImpl(EntityFindImpl entityFind) {
        this.entityFind = entityFind
    }

    EntityDefinition makeEntityDefinition() {
        return new EntityDefinition(this.entityFind.efi, this.entityNode)
    }

    @Override
    EntityDynamicView setEntityName(String entityName) {
        this.entityNode."@entity-name" = entityName
        return this
    }

    @Override
    EntityDynamicView addMemberEntity(String entityAlias, String entityName, String joinFromAlias, Boolean joinOptional,
                                      Map<String, String> entityKeyMaps) {
        Node memberEntity = this.entityNode.appendNode("member-entity", ["entity-alias":entityAlias, "entity-name":entityName])
        if (joinFromAlias) {
            memberEntity.attributes().put("join-from-alias", joinFromAlias)
            memberEntity.attributes().put("join-optional", (joinOptional ? "true" : "false"))
        }
        if (entityKeyMaps) for (Map.Entry keyMapEntry in entityKeyMaps.entrySet()) {
            memberEntity.appendNode("key-map", ["field-name":keyMapEntry.getKey(), "related-field-name":keyMapEntry.getValue()])
        }
        return this
    }

    @Override
    EntityDynamicView addRelationshipMember(String entityAlias, String joinFromAlias, String relationshipName,
                                            Boolean joinOptional) {
        Node joinFromMemberEntityNode = (Node) this.entityNode."member-entity".find({ it."@entity-alias" == joinFromAlias })
        String entityName = joinFromMemberEntityNode."@entity-name"
        EntityDefinition joinFromEd = entityFind.getEfi().getEntityDefinition(entityName)
        EntityDefinition.RelationshipInfo relInfo = joinFromEd.getRelationshipInfo(relationshipName)
        if (relInfo == null) throw new EntityException("Relationship not found with name [${relationshipName}] on entity [${entityName}]")

        Map relationshipKeyMap = relInfo.keyMap
        Node memberEntity = this.entityNode.appendNode("member-entity", ["entity-alias":entityAlias, "entity-name":relInfo.relatedEntityName])
        memberEntity.attributes().put("join-from-alias", joinFromAlias)
        memberEntity.attributes().put("join-optional", (joinOptional ? "true" : "false"))
        for (Map.Entry keyMapEntry in relationshipKeyMap.entrySet()) {
            memberEntity.appendNode("key-map", ["field-name":keyMapEntry.getKey(), "related-field-name":keyMapEntry.getValue()])
        }
        return this
    }

    Node getViewEntityNode() { return entityNode }

    @Override
    List<Node> getMemberEntityNodes() { return this.entityNode."member-entity" }

    @Override
    EntityDynamicView addAliasAll(String entityAlias, String prefix) {
        this.entityNode.appendNode("alias-all", ["entity-alias":entityAlias, "prefix":prefix])
        return this
    }

    @Override
    EntityDynamicView addAlias(String entityAlias, String name) {
        this.entityNode.appendNode("alias", ["entity-alias":entityAlias, "name":name])
        return this
    }

    @Override
    EntityDynamicView addAlias(String entityAlias, String name, String field, String function) {
        this.entityNode.appendNode("alias", ["entity-alias":entityAlias, "name":name, "field":field, "function":function])
        return this
    }

    @Override
    EntityDynamicView addRelationship(String type, String title, String relatedEntityName, Map<String, String> entityKeyMaps) {
        Node viewLink = this.entityNode.appendNode("relationship", ["type":type, "title":title, "related-entity-name":relatedEntityName])
        for (Map.Entry keyMapEntry in entityKeyMaps.entrySet()) {
            viewLink.appendNode("key-map", ["field-name":keyMapEntry.getKey(), "related-field-name":keyMapEntry.getValue()])
        }
        return this
    }
}
