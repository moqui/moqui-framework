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

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * Represents the conditions to be used to constrain a query.
 *
 * These can be used in various combinations using the different condition types.
 *
 */
@SuppressWarnings("unused")
public interface EntityConditionFactory {

    EntityCondition getTrueCondition();

    EntityCondition makeCondition(EntityCondition lhs, EntityCondition.JoinOperator operator, EntityCondition rhs);

    EntityCondition makeCondition(String fieldName, EntityCondition.ComparisonOperator operator, Object value);
    EntityCondition makeCondition(String fieldName, EntityCondition.ComparisonOperator operator, Object value, boolean orNull);

    EntityCondition makeConditionToField(String fieldName, EntityCondition.ComparisonOperator operator, String toFieldName);

    /** Default to JoinOperator of AND */
    EntityCondition makeCondition(List<EntityCondition> conditionList);
    EntityCondition makeCondition(List<EntityCondition> conditionList, EntityCondition.JoinOperator operator);

    /** More convenient for scripts, etc. The conditionList parameter may contain Map or EntityCondition objects. */
    EntityCondition makeCondition(List<Object> conditionList, String listOperator, String mapComparisonOperator, String mapJoinOperator);

    EntityCondition makeCondition(Map<String, Object> fieldMap, EntityCondition.ComparisonOperator comparisonOperator, EntityCondition.JoinOperator joinOperator);

    /** Default to ComparisonOperator of EQUALS and JoinOperator of AND */
    EntityCondition makeCondition(Map<String, Object> fieldMap);

    EntityCondition makeConditionDate(String fromFieldName, String thruFieldName, Timestamp compareStamp);

    EntityCondition makeConditionWhere(String sqlWhereClause);

    /** Get a ComparisonOperator using an enumId for enum type "ComparisonOperator" */
    EntityCondition.ComparisonOperator comparisonOperatorFromEnumId(String enumId);
}
