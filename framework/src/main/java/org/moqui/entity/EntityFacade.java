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

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import org.moqui.etl.SimpleEtl;
import org.moqui.util.MNode;
import org.w3c.dom.Element;

/** The main interface for general database operations in Moqui. */
@SuppressWarnings("unused")
public interface EntityFacade {

    /** Get a EntityDatasourceFactory implementation for a group. This is most useful for non-SQL databases to get
     * access to underlying details. */
    EntityDatasourceFactory getDatasourceFactory(String groupName);

    /** Get a EntityConditionFactory object that can be used to create and assemble conditions used for finds.
     *
     * @return The facade's active EntityConditionFactory object.
     */
    EntityConditionFactory getConditionFactory();

    /** Creates a Entity in the form of a EntityValue without persisting it
     *
     * @param entityName The name of the entity to make a value object for.
     * @return EntityValue for the named entity. 
     */
    EntityValue makeValue(String entityName);
    
    /** Create an EntityFind object that can be used to specify additional options, and then to execute one or more
     * finds (queries).
     * 
     * @param entityName The Name of the Entity as defined in the entity XML file, can be null.
     * @return An EntityFind object.
     */
    EntityFind find(String entityName);
    EntityFind find(MNode entityFindNode);

    /** Meant for processing entity REST requests, but useful more generally as a simple way to perform entity operations.
     *
     * @param operation Can be get/find, post/create, put/store, patch/update, or delete/delete.
     * @param entityPath First element should be an entity name or short-alias, followed by (optionally depending on
     *                   operation) the PK fields for the entity in the order they appear in the entity definition
     *                   followed optionally by (one or more) relationship name or short-alias and then PK values for
     *                   the related entity, not including any PK fields defined in the relationship.
     * @param parameters A Map of extra parameters, used depending on the operation. For find operations these can be
     *                   any parameters handled by the EntityFind.searchFormInputs() method. For create, update, store,
     *                   and delete operations these parameters are for non-PK fields and as an alternative to the
     *                   entity path for PK field values. For find operations also supports a "dependents" parameter
     *                   that if true will get dependent values of the record referenced in the entity path.
     * @param masterNameInPath If true the second entityPath entry must be the name of a master entity definition
     */
    Object rest(String operation, List<String> entityPath, Map parameters, boolean masterNameInPath);

    /** Do a database query with the given SQL and return the results as an EntityList for the given entity and with
     * selected columns mapped to the listed fields.
     *
     * @param sql The actual SQL to run.
     * @param sqlParameterList Parameter values that correspond with any question marks (?) in the SQL.
     * @param entityName Name of the entity to map the results to.
     * @param fieldList List of entity field names in order that they match columns selected in the query.
     * @return EntityListIterator with results of query.
     */
    EntityListIterator sqlFind(String sql, List<Object> sqlParameterList, String entityName, List<String> fieldList);

    /** Find and assemble data documents represented by a Map that can be easily turned into a JSON document. This is
     * used for searching by the Data Search feature and for data feeds to other systems with the Data Feed feature.
     *
     * @param dataDocumentId Used to look up the DataDocument and related records (DataDocument* entities).
     * @param condition An optional condition to AND with from/thru updated timestamps and any DataDocumentCondition
     *                  records associated with the DataDocument.
     * @param fromUpdateStamp The lastUpdatedStamp on at least one entity selected must be after (&gt;=) this Timestamp.
     * @param thruUpdatedStamp The lastUpdatedStamp on at least one entity selected must be before (&lt;) this Timestamp.
     * @return List of Maps with these entries:
     *      - _index = DataDocument.indexName
     *      - _type = dataDocumentId
     *      - _id = pk field values from primary entity, underscore separated
     *      - _timestamp = timestamp when the document was created
     *      - Map for primary entity (with primaryEntityName as key)
     *      - nested List of Maps for each related entity from DataDocumentField records with aliased fields
     *          (with relationship name as key)
     */
    ArrayList<Map> getDataDocuments(String dataDocumentId, EntityCondition condition, Timestamp fromUpdateStamp,
                                    Timestamp thruUpdatedStamp);

    /** Find and assemble data documents represented by a Map that can be easily turned into a JSON document. This is
     * similar to the getDataDocuments() method except that the dataDocumentId(s) are looked up using the dataFeedId.
     *
     * @param dataFeedId Used to look up the DataFeed records to find the associated DataDocument records.
     * @param fromUpdateStamp The lastUpdatedStamp on at least one entity selected must be after (&gt;=) this Timestamp.
     * @param thruUpdatedStamp The lastUpdatedStamp on at least one entity selected must be before (&lt;) this Timestamp.
     * @return List of Maps with these entries:
     */
    ArrayList<Map> getDataFeedDocuments(String dataFeedId, Timestamp fromUpdateStamp, Timestamp thruUpdatedStamp);

    /** Get the next guaranteed unique seq id from the sequence with the given sequence name;
     * if the named sequence doesn't exist, it will be created.
     *
     * @param seqName The name of the sequence to get the next seq id from
     * @param staggerMax The maximum amount to stagger the sequenced ID, if 1 the sequence will be incremented by 1,
     *     otherwise the current sequence ID will be incremented by a value between 1 and staggerMax
     * @param bankSize The size of the "bank" of values to get from the database (defaults to 1)
     * @return Long with the next seq id for the given sequence name
     */
    String sequencedIdPrimary(String seqName, Long staggerMax, Long bankSize);

    /** Gets the group name for specified entityName
     * @param entityName The name of the entity to get the group name
     * @return String with the group name that corresponds to the entityName
     */
    String getEntityGroupName(String entityName);

    /** Use this to get a Connection if you want to do JDBC operations directly. This Connection will be enlisted in
     * the active Transaction.
     *
     * @param groupName The name of entity group to get a connection for.
     *     Corresponds to the entity.@group attribute and the moqui-conf datasource.@group-name attribute.
     * @return JDBC Connection object for the associated database
     * @throws EntityException if there is an error getting a Connection
     */
    Connection getConnection(String groupName) throws EntityException;
    Connection getConnection(String groupName, boolean useClone) throws EntityException;

    // ======= Import/Export (XML, CSV, etc) Related Methods ========

    /** Make an object used to load XML or CSV entity data into the database or into an EntityList. The files come from
     * a specific location, text already read from somewhere, or by searching all component data directories
     * and the entity-facade.load-data elements for entity data files that match a type in the Set of types
     * specified.
     *
     * An XML document should have a root element like <code>&lt;entity-facade-xml type=&quot;seed&quot;&gt;</code>. The
     * type attribute will be used to determine if the file should be loaded by whether or not it matches the values
     * specified for data types on the loader.
     *
     * @return EntityDataLoader instance
     */
    EntityDataLoader makeDataLoader();

    /** Used to write XML entity data from the database to a writer.
     *
     * The document will have a root element like <code>&lt;entity-facade-xml&gt;</code>.
     *
     * @return EntityDataWriter instance
     */
    EntityDataWriter makeDataWriter();

    SimpleEtl.Loader makeEtlLoader();

    /** Make an EntityValue and populate it with the data (attributes and sub-elements) from the given XML element.
     *
     * @param element A XML DOM element representing a single value/record for an entity.
     * @return EntityValue object populated with data from the element.
     */
    EntityValue makeValue(Element element);

    Calendar getCalendarForTzLc();
}
