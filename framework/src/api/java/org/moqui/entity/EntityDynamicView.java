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
package org.moqui.entity;

import org.moqui.util.MNode;

import java.util.List;
import java.util.Map;

/** This class is used for declaring Dynamic View Entities, to be used and thrown away.
 * A special method exists on the EntityFind to accept a EntityDynamicView instead of an entityName.
 * The methods here return a reference to itself (this) for convenience.
 */
@SuppressWarnings("unused")
public interface EntityDynamicView {
    /** This optionally sets a name for the dynamic view entity. If not used will default to "DynamicView" */
    EntityDynamicView setEntityName(String entityName);

    EntityDynamicView addMemberEntity(String entityAlias, String entityName, String joinFromAlias,
                                             Boolean joinOptional, Map<String, String> entityKeyMaps);

    EntityDynamicView addRelationshipMember(String entityAlias, String joinFromAlias, String relationshipName,
                                                   Boolean joinOptional);

    List<MNode> getMemberEntityNodes();

    EntityDynamicView addAliasAll(String entityAlias, String prefix);

    EntityDynamicView addAlias(String entityAlias, String name);

    /** Add an alias, full detail. All parameters can be null except entityAlias and name. */
    EntityDynamicView addAlias(String entityAlias, String name, String field, String function);

    EntityDynamicView addRelationship(String type, String title, String relatedEntityName,
                                             Map<String, String> entityKeyMaps);
}
