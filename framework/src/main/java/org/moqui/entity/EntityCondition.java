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

import java.io.Externalizable;
import java.util.Map;

/** Represents the conditions to be used to constrain a query.
 *
 * These can be used in various combinations using the different condition types.
 *
 * This class is mostly empty because it is a placeholder for use in the EntityConditionFactory and most functionality
 * is internal only.
 */
public interface EntityCondition extends Externalizable {

    ComparisonOperator EQUALS = ComparisonOperator.EQUALS;
    ComparisonOperator NOT_EQUAL = ComparisonOperator.NOT_EQUAL;
    ComparisonOperator LESS_THAN = ComparisonOperator.LESS_THAN;
    ComparisonOperator GREATER_THAN = ComparisonOperator.GREATER_THAN;
    ComparisonOperator LESS_THAN_EQUAL_TO = ComparisonOperator.LESS_THAN_EQUAL_TO;
    ComparisonOperator GREATER_THAN_EQUAL_TO = ComparisonOperator.GREATER_THAN_EQUAL_TO;
    ComparisonOperator IN = ComparisonOperator.IN;
    ComparisonOperator NOT_IN = ComparisonOperator.NOT_IN;
    ComparisonOperator BETWEEN = ComparisonOperator.BETWEEN;
    ComparisonOperator NOT_BETWEEN = ComparisonOperator.NOT_BETWEEN;
    ComparisonOperator LIKE = ComparisonOperator.LIKE;
    ComparisonOperator NOT_LIKE = ComparisonOperator.NOT_LIKE;
    ComparisonOperator IS_NULL = ComparisonOperator.IS_NULL;
    ComparisonOperator IS_NOT_NULL = ComparisonOperator.IS_NOT_NULL;

    JoinOperator AND = JoinOperator.AND;
    JoinOperator OR = JoinOperator.OR;

    enum ComparisonOperator { EQUALS, NOT_EQUAL,
        LESS_THAN, GREATER_THAN, LESS_THAN_EQUAL_TO, GREATER_THAN_EQUAL_TO,
        IN, NOT_IN, BETWEEN, NOT_BETWEEN, LIKE, NOT_LIKE, IS_NULL, IS_NOT_NULL }

    enum JoinOperator { AND, OR }

    /** Evaluate the condition in memory. */
    boolean mapMatches(Map<String, Object> map);
    /** Used for EntityCache view-entity clearing by member-entity change */
    boolean mapMatchesAny(Map<String, Object> map);
    /** Used for EntityCache view-entity clearing by member-entity change */
    boolean mapKeysNotContained(Map<String, Object> map);
    /** Create a map of name/value pairs representing the condition. Returns false if the condition can't be
     * represented as simple name/value pairs ANDed together. */
    boolean populateMap(Map<String, Object> map);

    /** Set this condition to ignore case in the query.
     * This may not have an effect for all types of conditions.
     *
     * @return Returns reference to the query for convenience.
     */
    EntityCondition ignoreCase();
}
