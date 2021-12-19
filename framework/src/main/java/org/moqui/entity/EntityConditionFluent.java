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
import java.util.Map;

@SuppressWarnings("unused")
public interface EntityConditionFluent extends EntityCondition {
    /** Create a nested (child) condition where if ANY of the specified conditions are true the result is true (OR).
     * @return a child EntityConditionFluent to add conditions to, call parent() to effectively 'close' the OR condition */
    EntityConditionFluent or();
    /** Create a nested (child) condition where if ALL the specified conditions are true the result is true (AND).
     * @return a child EntityConditionFluent to add conditions to, call parent() to effectively 'close' the AND condition */
    EntityConditionFluent and();
    /** For nested instances (from or() and and()) return the parent to effectively 'close' the child condition in a chain of method calls.
     * NOTE that the top-level instance will return null. */
    EntityConditionFluent parent();

    /** Add a condition where if ANY of the specified conditions are true the result is true (OR).
     * @return current EntityConditionFluent for call chaining */
    EntityConditionFluent or(EntityCondition... conditions);
    /** Add a condition where if ALL the specified conditions are true the result is true (AND).
     * @return current EntityConditionFluent for call chaining */
    EntityConditionFluent and(EntityCondition... conditions);

    /** Add a field to the find (where clause).
     * If a field has been set with the same name, this will replace that field's value.
     * If any other constraints are already in place this will be ANDed to them.
     *
     * @return Returns this for chaining of method calls.
     */
    EntityConditionFluent equals(String fieldName, Object value);

    /** Compare the named field to the value using the operator. */
    EntityConditionFluent compare(String fieldName, ComparisonOperator operator, Object value);
    EntityConditionFluent compare(String fieldName, ComparisonOperator operator, Object value, boolean ignoreCase, boolean orNull, boolean ignoreIfEmpty);
    /** Compare the named field to the value using the operator. */
    EntityConditionFluent compare(String fieldName, String operator, Object value);
    EntityConditionFluent compare(String fieldName, String operator, Object value, boolean ignoreCase, boolean orNull, boolean ignoreIfEmpty);

    /** Compare a field to another field using the operator. */
    EntityConditionFluent compareToField(String fieldName, ComparisonOperator operator, String toFieldName);
    EntityConditionFluent compareToField(String fieldName, ComparisonOperator operator, String toFieldName, boolean ignoreCase, boolean orNull);

    /** Add a Map of fields to the find (where clause).
     * If a field has been set with the same name and any of the Map keys, this will replace that field's value.
     * Fields set in this way will be combined with other conditions (if applicable) just before doing the query.
     *
     * This will do conversions if needed from Strings to field types as needed, and will only get keys that match
     * entity fields. In other words, it does the same thing as:
     * <code>EntityValue.setFields(fields, true, null, null)</code>
     *
     * @return Returns this for chaining of method calls.
     */
    EntityConditionFluent equals(Map<String, Object> fields);
    EntityConditionFluent compare(Map<String, Object> fieldMap, ComparisonOperator comparisonOperator, JoinOperator joinOperator);

    /** Add conditions for the standard effective date query pattern including from field is null or earlier than
     * or equal to compareStamp and thru field is null or later than or equal to compareStamp.
     */
    EntityConditionFluent effectiveDate(String fromFieldName, String thruFieldName, Timestamp compareStamp);

    /** Add a condition where fieldName is greater than or equal to fromInclusive, and less than or equal to toInclusive */
    EntityConditionFluent rangeInclusive(String fieldName, Object fromInclusive, Object toInclusive);
    /** Add a condition where fieldName is greater than or equal to fromInclusive, and less than toExclusive */
    EntityConditionFluent rangeExclusive(String fieldName, Object fromInclusive, Object toExclusive);
    /** Add a condition where fieldName is greater than from (or equal if fromInclusive), and less than to (or equal if toInclusive) */
    EntityConditionFluent range(String fieldName, Object from, boolean fromInclusive, Object to, boolean toInclusive);

    /** Add a WHERE string condition, will be included as-is so must be compatible with the target database */
    EntityConditionFluent where(String whereString);

    /** Add a EntityCondition to the find (where clause).
     * @return Returns this for chaining of method calls. */
    EntityConditionFluent condition(EntityCondition condition);
}
