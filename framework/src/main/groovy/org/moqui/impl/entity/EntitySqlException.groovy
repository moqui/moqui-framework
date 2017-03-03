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

import org.moqui.entity.EntityException
import org.moqui.impl.context.ExecutionContextImpl

import java.sql.SQLException

/** Wrap an SqlException for more user friendly error messages */
class EntitySqlException extends EntityException {
    // NOTE these are the messages to localize with LocalizedMessage
    // NOTE: don't change these unless there is a really good reason, will break localization
    private static Map<String, String> messageBySqlCode = [
            '22':'invalid data', // data exception
            '22001':'text value too long', // VALUE_TOO_LONG, char/varchar/etc (aka right truncation)
            '22003':'number too big', // NUMERIC_VALUE_OUT_OF_RANGE
            '22004':'empty value not allowed', // null value not allowed
            '22018':'text value could not be converted', // DATA_CONVERSION_ERROR, invalid character value for cast
            '23':'record already exists or related record does not exist', // integrity constraint violation, most likely problems
            '23502':'empty value not allowed', // NULL_NOT_ALLOWED
            '23503':'another record depends on this record', // REFERENTIAL_INTEGRITY_VIOLATED_CHILD_EXISTS (in update or delete would orphan FK)
            '23505':'record already exists', // DUPLICATE_KEY
            '23506':'tried to refer to record that does not exist', // REFERENTIAL_INTEGRITY_VIOLATED_PARENT_MISSING (in insert or update invalid FK reference)
            '40':'record lock conflict found', // transaction rollback
            '40001':'record lock conflict found', // DEADLOCK - serialization failure
            '40002':'record lock conflict found', // integrity constraint violation
            '40P01':'record lock conflict found', // postgres deadlock_detected
            '50200':'timeout waiting for record lock', // LOCK_TIMEOUT H2
            '57033':'record lock conflict found', // DB2 deadlock without automatic rollback
            'HY':'timeout waiting for database', // lock or other timeout; is this really correct for this 2 letter code?
            'HY000':'timeout waiting for record lock', // lock or other timeout
            'HYT00':'timeout waiting for record lock', // lock or other timeout (H2)
            // NOTE MySQL uses HY000 for a LOT of stuff, lock timeout distinguished by error code 1205
    ]

    /* see:
        https://www.h2database.com/javadoc/org/h2/api/ErrorCode.html
        https://dev.mysql.com/doc/refman/5.7/en/error-messages-server.html
        https://www.postgresql.org/docs/current/static/errcodes-appendix.html
        https://www.ibm.com/support/knowledgecenter/SSEPEK_12.0.0/codes/src/tpc/db2z_sqlstatevalues.html
     */

    private String sqlState = null
    private String overrideMessage = null

    EntitySqlException(String str, SQLException nested, ExecutionContextImpl eci) {
        super(str, nested)
        overrideMessage = str
        getSQLState(nested)
        if (sqlState != null) {
            // try full string
            String msg = messageBySqlCode.get(sqlState)
            // try first 2 chars
            if (msg == null && sqlState.length() >= 2) msg = messageBySqlCode.get(sqlState.substring(0,2))
            // localize and append
            if (msg != null) overrideMessage += ': ' + eci.l10nFacade.localize(msg)
        }
        overrideMessage += ' [' + sqlState + ']'
    }

    @Override String getMessage() { return overrideMessage }
    @Override String toString() { return overrideMessage }

    String getSQLState(SQLException ex) {
        if (sqlState != null) return sqlState
        sqlState = ex.getSQLState()
        if (sqlState == null) {
            SQLException nestedEx = ex.getNextException()
            if (nestedEx != null) sqlState = nestedEx.getSQLState()
        }
        return sqlState
    }
}
