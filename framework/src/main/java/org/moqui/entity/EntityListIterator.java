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

import java.io.Writer;
import java.util.ListIterator;

/**
 * Entity Cursor List Iterator for Handling Cursored Database Results
 */
@SuppressWarnings("unused")
public interface EntityListIterator extends ListIterator<EntityValue> {

    /** Close the underlying ResultSet and Connection. This must ALWAYS be called when done with an EntityListIterator. */
    void close() throws EntityException;

    /** Sets the cursor position to just after the last result so that previous() will return the last result */
    void afterLast() throws EntityException;

    /** Sets the cursor position to just before the first result so that next() will return the first result */
    void beforeFirst() throws EntityException;

    /** Sets the cursor position to last result; if result set is empty returns false */
    boolean last() throws EntityException;

    /** Sets the cursor position to last result; if result set is empty returns false */
    boolean first() throws EntityException;

    /** NOTE: Calling this method does return the current value, but so does calling next() or previous(), so calling
     * one of those AND this method will cause the value to be created twice
     */
    EntityValue currentEntityValue() throws EntityException;

    int currentIndex() throws EntityException;

    /** performs the same function as the ResultSet.absolute method;
     * if rowNum is positive, goes to that position relative to the beginning of the list;
     * if rowNum is negative, goes to that position relative to the end of the list;
     * a rowNum of 1 is the same as first(); a rowNum of -1 is the same as last()
     */
    boolean absolute(int rowNum) throws EntityException;

    /** performs the same function as the ResultSet.relative method;
     * if rows is positive, goes forward relative to the current position;
     * if rows is negative, goes backward relative to the current position;
     */
    boolean relative(int rows) throws EntityException;

    /**
     * PLEASE NOTE: Because of the nature of the JDBC ResultSet interface this method can be very inefficient; it is
     * much better to just use next() until it returns null.
     *
     * For example, you could use the following to iterate through the results in an EntityListIterator:
     *
     * <pre>
     * EntityValue nextValue;
     * while ((nextValue = eli.next()) != null) { ... }
     * </pre>
     */
    @Override boolean hasNext();

    /** PLEASE NOTE: Because of the nature of the JDBC ResultSet interface this method can be very inefficient; it is
     * much better to just use previous() until it returns null.
     */
    @Override boolean hasPrevious();

    /** Moves the cursor to the next position and returns the EntityValue object for that position; if there is no next,
     * returns null.
     *
     * For example, you could use the following to iterate through the results in an EntityListIterator:
     *
     * <pre>
     * EntityValue nextValue;
     * while ((nextValue = eli.next()) != null) { ... }
     * </pre>
     */
    @Override EntityValue next();

    /** Returns the index of the next result, but does not guarantee that there will be a next result */
    @Override int nextIndex();

    /** Moves the cursor to the previous position and returns the EntityValue object for that position; if there is no
     * previous, returns null.
     */
    @Override EntityValue previous();

    /** Returns the index of the previous result, but does not guarantee that there will be a previous result */
    @Override int previousIndex();

    void setFetchSize(int rows) throws EntityException;

    EntityList getCompleteList(boolean closeAfter) throws EntityException;

    /** Gets a partial list of results starting at start and containing at most number elements.
     * Start is a one based value, ie 1 is the first element.
     */
    EntityList getPartialList(int offset, int limit, boolean closeAfter) throws EntityException;

    /** Writes XML text with an attribute or CDATA element for each field of each record. If dependents is true also
     * writes all dependent (descendant) records.
     * @param writer A Writer object to write to
     * @param prefix A prefix to put in front of the entity name in the tag name
     * @param dependentLevels Write dependent (descendant) records this many levels deep, zero for no dependents
     * @return The number of records written
     */
    int writeXmlText(Writer writer, String prefix, int dependentLevels);
    int writeXmlTextMaster(Writer writer, String prefix, String masterName);
}
