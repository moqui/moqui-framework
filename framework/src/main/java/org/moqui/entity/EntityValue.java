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

import org.moqui.etl.SimpleEtl;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.sql.rowset.serial.SerialBlob;
import java.io.Externalizable;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;


/** Entity Value Interface - Represents a single database record. */
@SuppressWarnings("unused")
public interface EntityValue extends Map<String, Object>, Externalizable, Comparable<EntityValue>, Cloneable, SimpleEtl.Entry {

    String getEntityName();
    String getEntityNamePretty();

    /** Returns true if any field has been modified */
    boolean isModified();
    /** Returns true if the field has been modified */
    boolean isFieldModified(String name);
    /** Returns true if a value for the field is set, even if it is null */
    boolean isFieldSet(String name);
    /** Returns true if the name is a valid field name for the entity this is a value of,
     * false otherwise (meaning get(), set(), etc calls with throw an exception with the field name) */
    boolean isField(String name);

    boolean isMutable();

    /** Gets a cloned, mutable Map with the field values that is independent of this value object. Can be augmented or
     * modified without modifying or being constrained by this entity value. */
    Map<String, Object> getMap();

    /** Get the named field.
     *
     * If there is a matching entry in the moqui.basic.LocalizedEntityField entity using the Locale in the current
     * ExecutionContext then that will be returned instead.
     *
     * This method also supports getting related entities using their relationship name, formatted as
     * "${title}${related-entity-name}". When doing so it is like calling
     * <code>findRelated(relationshipName, null, null, null, null)</code> for type many relationships, or
     * <code>findRelatedOne(relationshipName, null, null)</code> for type one relationships.
     *
     * @param name The field name to get, or the name of the relationship to get one or more related values from.
     * @return Object with the value of the field, or the related EntityValue or EntityList.
     */
    Object get(String name);

    /** Get simple fields only (no localization, no relationship) and don't check to see if it is a valid field; mostly
     * for performance reasons and for well tested code with known field names. If it is not a valid field name will
     * just return null and not throw an error, ie doesn't check for valid field names. */
    Object getNoCheckSimple(String name);

    /** Returns true if the entity contains all of the primary key fields. */
    boolean containsPrimaryKey();

    Map<String, Object> getPrimaryKeys();

    /** Sets the named field to the passed value, even if the value is null
     * @param name The field name to set
     * @param value The value to set
     * @return reference to this for convenience
     */
    EntityValue set(String name, Object value);

    /** Sets fields on this entity from the Map of fields passed in using the entity definition to only get valid
     * fields from the Map. For any String values passed in this will call setString to convert based on the field
     * definition, otherwise it sets the Object as-is.
     *
     * @param fields The fields Map to get the values from
     * @return reference to this for convenience
     */
    EntityValue setAll(Map<String, Object> fields);

    /** Sets the named field to the passed value, converting the value from a String to the corresponding type using 
     *   <code>Type.valueOf()</code>
     *
     * If the String "null" is passed in it will be treated the same as a null value. If you really want to set a
     * String of "null" then pass in "\null".
     *
     * @param name The field name to set
     * @param value The String value to convert and set
     * @return reference to this for convenience
     */
    EntityValue setString(String name, String value);

    Boolean getBoolean(String name);

    String getString(String name);

    java.sql.Timestamp getTimestamp(String name);
    java.sql.Time getTime(String name);
    java.sql.Date getDate(String name);

    Long getLong(String name);
    Double getDouble(String name);
    BigDecimal getBigDecimal(String name);

    byte[] getBytes(String name);
    EntityValue setBytes(String name, byte[] theBytes);
    SerialBlob getSerialBlob(String name);

    /** Sets fields on this entity from the Map of fields passed in using the entity definition to only get valid
     * fields from the Map. For any String values passed in this will call setString to convert based on the field
     * definition, otherwise it sets the Object as-is.
     *
     * @param fields The fields Map to get the values from
     * @param setIfEmpty Used to specify whether empty/null values in the field Map should be set
     * @param namePrefix If not null or empty will be pre-pended to each field name (upper-casing the first letter of
     *   the field name first), and that will be used as the fields Map lookup name instead of the field-name
     * @param pks If null, get all values, if TRUE just get PKs, if FALSE just get non-PKs
     * @return reference to this for convenience
     */
    EntityValue setFields(Map<String, Object> fields, boolean setIfEmpty, String namePrefix, Boolean pks);

    /** Get the next guaranteed unique seq id for this entity, and set it in the primary key field. This will set it in
     * the first primary key field in the entity definition, but it really should be used for entities with only one
     * primary key field.
     *
     * @return reference to this for convenience
     */
    EntityValue setSequencedIdPrimary();

    /** Look at existing values with the same primary sequenced ID (first PK field) and get the highest existing
     * value for the secondary sequenced ID (the second PK field), add 1 to it and set the result in this entity value.
     *
     * The current value object must have the primary sequenced field already populated.
     *
     * @return reference to this for convenience
     */
    EntityValue setSequencedIdSecondary();

    /** Compares this EntityValue to the passed object
     * @param that Object to compare this to
     * @return int representing the result of the comparison (-1,0, or 1)
     */
    @Override
    int compareTo(EntityValue that);

    /** Returns true if all entries in the Map match field values. */
    boolean mapMatches(Map<String, Object> theMap);

    EntityValue cloneValue();

    /** Creates a record for this entity value.
     * @return reference to this for convenience
     */
    EntityValue create() throws EntityException;

    /** Creates a record for this entity value, or updates the record if one exists that matches the primary key.
     * @return reference to this for convenience
     */
    EntityValue createOrUpdate() throws EntityException;
    /** Alias for createOrUpdate() */
    EntityValue store() throws EntityException;

    /** Updates the record that matches the primary key.
     * @return reference to this for convenience
     */
    EntityValue update() throws EntityException;

    /** Deletes the record that matches the primary key.
     * @return reference to this for convenience
     */
    EntityValue delete() throws EntityException;

    /** Refreshes this value based on the record that matches the primary key.
     * @return true if a record was found, otherwise false also meaning no refresh was done
     */
    boolean refresh() throws EntityException;

    Object getOriginalDbValue(String name);

    /** Get the named Related Entity for the EntityValue from the persistent store
     * @param relationshipName String containing the relationship name which is the combination of relationship.title
     *   and relationship.related-entity-name as specified in the entity XML definition file
     * @param byAndFields the fields that must equal in order to keep; may be null
     * @param orderBy The fields of the named entity to order the query by; may be null;
     *      optionally add a " ASC" for ascending or " DESC" for descending
     * @param useCache Look in the cache before finding in the datasource. Defaults to setting on entity definition.
     * @return List of EntityValue instances as specified in the relation definition
     */
    EntityList findRelated(String relationshipName, Map<String, Object> byAndFields, List<String> orderBy,
                                  Boolean useCache, Boolean forUpdate) throws EntityException;

    /** Get the named Related Entity for the EntityValue from the persistent store
     * @param relationshipName String containing the relationship name which is the combination of relationship.title
     *   and relationship.related-entity-name as specified in the entity XML definition file
     * @param useCache Look in the cache before finding in the datasource. Defaults to setting on entity definition.
     * @return List of EntityValue instances as specified in the relation definition
     */
    EntityValue findRelatedOne(String relationshipName, Boolean useCache, Boolean forUpdate) throws EntityException;

    long findRelatedCount(final String relationshipName, Boolean useCache);

    /** Find all records with a foreign key reference to this record. Operates on relationship definitions for any related entity
     * that has a type one relationship to this entity.
     *
     * Does not recurse, finds directly related (dependant) records only.
     *
     * Will skip any related records whose entity name is in skipEntities.
     *
     * Useful as a validation before calling deleteWithCascade().
     */
    EntityList findRelatedFk(Set<String> skipEntities);

    /** Remove the named Related Entity for the EntityValue from the persistent store
     * @param relationshipName String containing the relationship name which is the combination of relationship.title
     *   and relationship.related-entity-name as specified in the entity XML definition file
     */
    void deleteRelated(String relationshipName) throws EntityException;

    /** Delete this record plus records for all relationships specified. If any records exist for other relationships not specified
     * that depend on this record returns false and does not delete anything.
     *
     * Returns true if this and related records were deleted.
     */
    boolean deleteWithRelated(Set<String> relationshipsToDelete);

    /** Deletes this record and all records that depend on it, doing the same for each (cascading delete).
     * Deletes related records that depend on this record (records with a foreign key reference to this record).
     *
     * To clear the reference (set fields to null) instead of deleting records specify the entity names, related to this or any
     * related entity, in the clearRefEntities parameter.
     *
     * To check for records that should prevent a delete you can optionally pass a Set of entities names in the
     * validateAllowDeleteEntities parameter. If this is not null an exception will be thrown instead of deleting
     * any record for an entity NOT in that Set.
     *
     * WARNING: this may delete records you don't want to. Look at the nested relationships in the Entity Reference in the
     * Tools app to see what might might get deleted (anything with a type one relationship to this entity, or recursing
     * anything with a type one relationship to those).
     */
    void deleteWithCascade(Set<String> clearRefEntities, Set<String> validateAllowDeleteEntities);

    /**
     * Checks to see if all foreign key records exist in the database (records this record refers to).
     * Will attempt to create a dummy value (PK only) for those missing when specified insertDummy is true.
     *
     * @param insertDummy Create a dummy record using the provided fields
     * @return true if all FKs exist (or when all missing are created)
     */
    boolean checkFks(boolean insertDummy) throws EntityException;
    /** Compare this value to the database, adding messages about fields that differ or if the record doesn't exist to messages. */
    long checkAgainstDatabase(List<String> messages);

    /** Makes an XML Element object with an attribute for each field of the entity
     * @param document The XML Document that the new Element will be part of
     * @param prefix A prefix to put in front of the entity name in the tag name
     * @return org.w3c.dom.Element object representing this entity value
     */
    Element makeXmlElement(Document document, String prefix);

    /** Writes XML text with an attribute or CDATA element for each field of the entity. If dependents is true also
     * writes all dependent (descendant) records.
     * @param writer A Writer object to write to
     * @param prefix A prefix to put in front of the entity name in the tag name
     * @param dependentLevels Write dependent (descendant) records this many levels deep, zero for no dependents
     * @return The number of records written
     */
    int writeXmlText(Writer writer, String prefix, int dependentLevels);
    int writeXmlTextMaster(Writer pw, String prefix, String masterName);

    /** Get a Map with all non-null field values. If dependentLevels is greater than zero includes nested dependents
     * in the Map as an entry with key of the dependent relationship's short-alias or if no short-alias then the
     * relationship name (title + related-entity-name). Each dependent entity's Map may have its own dependent records
     * up to dependentLevels levels deep.*/
    Map<String, Object> getPlainValueMap(int dependentLevels);

    /** List getPlainValueMap() but uses a master definition to determine which dependent/related records to get. */
    Map<String, Object> getMasterValueMap(String name);
}
