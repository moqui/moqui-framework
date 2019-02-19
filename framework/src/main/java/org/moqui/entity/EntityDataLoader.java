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

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Used to load XML entity data into the database or into an EntityList. The XML can come from
 * a specific location, XML text already read from somewhere, or by searching all component data directories
 * and the entity-facade.load-data elements for XML entity data files that match a type in the Set of types
 * specified.
 *
 * The document should have a root element like <code>&lt;entity-facade-xml type=&quot;seed&quot;&gt;</code>. The
 * type attribute will be used to determine if the file should be loaded by whether or not it matches the values
 * specified for data types on the loader.
 */
@SuppressWarnings("unused")
public interface EntityDataLoader {

    /** Location of the data file to load. Can be called multiple times to load multiple files.
     * @return Reference to this for convenience.
     */
    EntityDataLoader location(String location);
    /** List of locations of files to load. Will be added to running list, so can be called multiple times and along
     * with the location() method too.
     * @return Reference to this for convenience.
     */
    EntityDataLoader locationList(List<String> locationList);

    /** String with XML text in it, ready to be parsed.
     * @return Reference to this for convenience.
     */
    EntityDataLoader xmlText(String xmlText);
    EntityDataLoader csvText(String csvText);
    EntityDataLoader jsonText(String jsonText);

    /** A Set of data types to match against the candidate files from the component data directories and the
     * entity-facade.load-data elements.
     * @return Reference to this for convenience.
     */
    EntityDataLoader dataTypes(Set<String> dataTypes);
    /** Used along with dataTypes; a list of component names to load data from. If none specified will load from all components. */
    EntityDataLoader componentNameList(List<String> componentNames);

    /** The transaction timeout for this data load in seconds. Defaults to 3600 which is 1 hour.
     * @return Reference to this for convenience.
     */
    EntityDataLoader transactionTimeout(int tt);

    /** If true instead of doing a query for each value from the file it will just try to insert it and if it fails then
     * it will try to update the existing record. Good for situations where most of the values will be new in the db.
     * @return Reference to this for convenience.
     */
    EntityDataLoader useTryInsert(boolean useTryInsert);

    /** If true only creates records that don't exist, does not update existing records.
     * @return Reference to this for convenience.
     */
    EntityDataLoader onlyCreate(boolean onlyInsert);

    /** If true will check all foreign key relationships for each value and if any of them are missing create a new
     * record with primary key only to avoid foreign key constraint errors.
     *
     * This should only be used when you are confident that the rest of the data for these new fk records will be loaded
     * from somewhere else to avoid having orphaned records.
     *
     * @return Reference to this for convenience.
     */
    EntityDataLoader dummyFks(boolean dummyFks);

    /** Set to true to disable Entity Facade ECA rules (for this import only, does not affect other things happening
     * in the system).
     * @return Reference to this for convenience.
     */
    EntityDataLoader disableEntityEca(boolean disable);
    EntityDataLoader disableAuditLog(boolean disable);
    EntityDataLoader disableFkCreate(boolean disable);
    EntityDataLoader disableDataFeed(boolean disable);

    EntityDataLoader csvDelimiter(char delimiter);
    EntityDataLoader csvCommentStart(char commentStart);
    EntityDataLoader csvQuoteChar(char quoteChar);

    /** For CSV files use this name (entity or service name) instead of looking for it on line one in the file */
    EntityDataLoader csvEntityName(String entityName);
    /** For CSV files use these field names instead of looking for them on line two in the file */
    EntityDataLoader csvFieldNames(List<String> fieldNames);
    /** Default values for all records to load, if applicable for given entity or service */
    EntityDataLoader defaultValues(Map<String, Object> defaultValues);

    /** Only check the data against matching records in the database. Report on records that don't exist in the database
     * and any differences with records that have matching primary keys.
     *
     * @return List of messages about each comparison between data in the file(s) and data in the database.
     */
    List<String> check();
    long check(List<String> messageList);

    /** Load the values into the database(s). */
    long load();
    long load(List<String> messageList);

    /** Create an EntityList with all of the values from the data file(s).
     *
     * @return EntityList representing a List of EntityValue objects for the values in the XML document(s).
     */
    EntityList list();
}
