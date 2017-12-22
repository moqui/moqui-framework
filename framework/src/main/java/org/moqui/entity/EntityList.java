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

import groovy.lang.Closure;

import java.io.Externalizable;
import java.io.Writer;
import java.sql.Timestamp;
import java.util.*;

/**
 * Contains a list of EntityValue objects.
 * Entity List that adds some additional operations like filtering to the basic List&lt;EntityValue&gt;.
 *
 * The various methods here modify the internal list for efficiency and return a reference to this for convenience.
 * If you want a new EntityList with the modifications, use clone() or cloneList() then modify it.
 */
@SuppressWarnings("unused")
public interface EntityList extends List<EntityValue>, Iterable<EntityValue>, Cloneable, RandomAccess, Externalizable {

    /** Get the first value in the list.
     *
     * @return EntityValue that is first in the list.
     */
    EntityValue getFirst();

    /** Modify this EntityList so that it contains only the values that are active for the moment passed in.
     * The results include values that match the fromDate, but exclude values that match the thruDate.
     *
     *@param fromDateName The name of the from/beginning date/time field. Defaults to "fromDate".
     *@param thruDateName The name of the thru/ending date/time field. Defaults to "thruDate".
     *@param moment The point in time to compare the values to; if null the current system date/time is used.
     *@return A reference to this for convenience.
     */
    EntityList filterByDate(String fromDateName, String thruDateName, Timestamp moment);
    EntityList filterByDate(String fromDateName, String thruDateName, Timestamp moment, boolean ignoreIfEmpty);

    /** Modify this EntityList so that it contains only the values that match the values in the fields parameter.
     *
     *@param fields The name/value pairs that must match for a value to be included in the output list.
     *@return List of EntityValue objects that match the values in the fields parameter.
     */
    EntityList filterByAnd(Map<String, Object> fields);
    EntityList filterByAnd(Map<String, Object> fields, Boolean include);

    /** Modify this EntityList so that it contains only the values that match the values in the namesAndValues parameter.
     *
     *@param namesAndValues Must be an even number of parameters as field name then value repeated as needed
     *@return List of EntityValue objects that match the values in the fields parameter.
     */
    EntityList filterByAnd(Object... namesAndValues);

    EntityList removeByAnd(Map<String, Object> fields);

    /** Modify this EntityList so that it includes (or excludes) values matching the condition.
     *
     * @param condition EntityCondition to filter by.
     * @param include If true include matching values, if false exclude matching values.
     *     Defaults to true (include, ie only include values that do meet condition).
     * @return List with filtered values.
     */
    EntityList filterByCondition(EntityCondition condition, Boolean include);

    /** Modify this EntityList so that it includes (or excludes) entity values where the closure evaluates to true.
     * The closure is called with a single argument, the current EntityValue in the list, and should evaluate to a Boolean. */
    EntityList filter(Closure<Boolean> closure, Boolean include);

    /** Find the first value in this EntityList where the closure evaluates to true. */
    EntityValue find(Closure<Boolean> closure);
    /** Different from filter* method semantics, does not modify this EntityList. Returns a new EntityList with just the
     * values where the closure evaluates to true. */
    EntityList findAll(Closure<Boolean> closure);

    /** Modify this EntityList to only contain up to limit values starting at the offset.
     *
     * @param offset Starting index to include
     * @param limit Include only this many values
     * @return List with filtered values.
     */
    EntityList filterByLimit(Integer offset, Integer limit);
    /** For limit filter in a cached entity-find with search-form-inputs, done after the query */
    EntityList filterByLimit(String inputFieldsMapName, boolean alwaysPaginate);

    /** The offset used to filter the list, if filterByLimit has been called. */
    Integer getOffset();
    /** The limit used to filter the list, if filterByLimit has been called. */
    Integer getLimit();
    /** For use with filterByLimit when paginated. Equals offset (default 0) divided by page size. */
    int getPageIndex();
    /** For use with filterByLimit when paginated. Equals limit (default 20; for use along with getPageIndex()). */
    int getPageSize();

    /** Modify this EntityList so that is ordered by the field names passed in.
     *
     *@param fieldNames The field names for the entity values to sort the list by. Optionally prefix each field name
     * with a plus sign (+) for ascending or a minus sign (-) for descending. Defaults to ascending.
     *@return List of EntityValue objects in the specified order.
     */
    EntityList orderByFields(List<String> fieldNames);

    int indexMatching(Map<String, Object> valueMap);
    void move(int fromIndex, int toIndex);

    /** Adds the value to this list if the value isn't already in it. Returns reference to this list. */
    EntityList addIfMissing(EntityValue value);
    /** Adds each value in the passed list to this list if the value isn't already in it. Returns reference to this list. */
    EntityList addAllIfMissing(EntityList el);

    /** Writes XML text with an attribute or CDATA element for each field of each record. If dependents is true also
     * writes all dependent (descendant) records.
     * @param writer A Writer object to write to
     * @param prefix A prefix to put in front of the entity name in the tag name
     * @param dependentLevels Write dependent (descendant) records this many levels deep, zero for no dependents
     * @return The number of records written
     */
    int writeXmlText(Writer writer, String prefix, int dependentLevels);

    /** Method to implement the Iterable interface to allow an EntityList to be used in a foreach loop.
     *
     * @return Iterator&lt;EntityValue&gt; to iterate over internal list.
     */
    @Override
    Iterator<EntityValue> iterator();

    /** Get a list of Map (not EntityValue) objects. If dependentLevels is greater than zero includes nested dependents
     * in the Map for each value. */
    List<Map<String, Object>> getPlainValueList(int dependentLevels);
    List<Map<String, Object>> getMasterValueList(String name);
    ArrayList<Map<String, Object>> getValueMapList();

    EntityList cloneList();

    void setFromCache();
    boolean isFromCache();
}
