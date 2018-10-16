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
package org.moqui.context;

import org.moqui.entity.EntityValue;

import java.sql.Timestamp;
import java.util.*;

/** For information about the user and user preferences (including locale, time zone, currency, etc). */
@SuppressWarnings("unused")
public interface UserFacade {
    /** @return Locale The active Locale from user preference or system default. */
    Locale getLocale();

    /** Set the user's Locale. This is used in this context and saved to the database for future contexts.
     * @param locale The new Locale.
     */
    void setLocale(Locale locale);

    /** @return TimeZone The active TimeZone from user preference or system default. */
    TimeZone getTimeZone();

    /** Set the user's Time Zone. This is used in this context and saved to the database for future contexts.
     * @param tz The new TimeZone.
     */
    void setTimeZone(TimeZone tz);

    /** @return String The active ISO currency code from user preference or system default. */
    String getCurrencyUomId();

    /** Set the user's Time Zone. This is used in this context and saved to the database for future contexts.
     * @param uomId The new currency UOM ID (ISO currency code).
     */
    void setCurrencyUomId(String uomId);

    /** Get the value of a user preference.
     * @param preferenceKey The key for the preference, looked up on UserPreference.preferenceKey
     * @return The value of the preference from the UserPreference.preferenceValue field
     */
    String getPreference(String preferenceKey);

    /** Set the value of a user preference.
     * @param preferenceKey The key for the preference, used to create or update a record with UserPreference.preferenceKey
     * @param preferenceValue The value to set on the preference, set in UserPreference.preferenceValue
     */
    void setPreference(String preferenceKey, String preferenceValue);
    /** Get a Map with multiple preferences, optionally filtered by a regular expression matched against each key */
    Map<String, String> getPreferences(String keyRegexp);

    /** A per-user context like the execution context for but data specific to a user and maintained through service
     * calls, etc unlike ExecutionContext.getContext(). Used for security data, etc such as entity filter values. */
    Map<String, Object> getContext();

    /** Get the current date and time in a Timestamp object. This is either the current system time, or the Effective
     * Time if that has been set for this context (allowing testing of past and future system behavior).
     *
     * All internal tools and code built on the framework should treat this as the actual current time.
     *
     * @return Timestamp representing current date/time, or the values passed to setEffectiveTime().
     */
    Timestamp getNowTimestamp();
    /** Get a Calendar object with user's TimeZone and Locale set, and set to same time as returned by getNowTimestamp(). */
    Calendar getNowCalendar();

    /** Get a Timestamp range (from/thru) based on period (day, week, month, year; 7d, 30d, etc), offset, and anchor date (defaults to now)
     * @return ArrayList with 2 entries, entry 0 is the from Timestamp, entry 1 is the thru Timestamp
     */
    ArrayList<Timestamp> getPeriodRange(String period, String poffset, String pdate);
    ArrayList<Timestamp> getPeriodRange(String period, int poffset, java.sql.Date pdate);
    ArrayList<Timestamp> getPeriodRange(String period, String poffset);
    String getPeriodDescription(String period, String poffset, String pdate);
    ArrayList<Timestamp> getPeriodRange(String baseName, Map<String, Object> inputFieldsMap);

    /** Set an EffectiveTime for the current context which will then be returned from the getNowTimestamp() method.
     * This is used to test past and future behavior of applications.
     *
     * @param effectiveTime The new effective date/time. Pass in null to reset to the default of the current system time.
     */
    void setEffectiveTime(Timestamp effectiveTime);

    /** Authenticate a user and make active in this ExecutionContext (and session of WebExecutionContext if applicable).
     * @param username An ID to match the UserAccount.username field.
     * @param password The user's current password.
     * @return true if user was logged in, otherwise false
     */
    boolean loginUser(String username, String password);
    /** Remove (logout) active user. */
    void logoutUser();

    /** Authenticate a user and make active using a login key */
    boolean loginUserKey(String loginKey);
    /** Get a login key for the active user. */
    String getLoginKey();

    /** If no user is logged in consider an anonymous user logged in. For internal purposes to run things that require authentication. */
    boolean loginAnonymousIfNoUser();

    /** Check to see if current user has the given permission. To have a permission a user must be in a group
     * (UserGroupMember =&gt; UserGroup) that has the given permission (UserGroupPermission).
     *
     * @param userPermissionId Permission ID for record in UserPermission or any arbitrary permission name (does
     *     not have to be pre-configured, ie does not have to be in the UserPermission entity's table)
     * @return boolean set to true if user has permission, false if not. If no user is logged in, returns false.
     */
    boolean hasPermission(String userPermissionId);

    /** Check to see if current user is in the given group (UserGroup). The user group concept in Moqui is similar to
     * the "role" concept in many security contexts (including Apache Shiro which is used in Moqui) though that term is
     * avoided because of the use of the term "role" for the Party part of the Mantle Universal Data Model.
     *
     * @param userGroupId The user group ID to check against.
     * @return boolean set to true if user is a member of the group, false if not. If no user is logged in, returns false.
     */
    boolean isInGroup(String userGroupId);

    Set<String> getUserGroupIdSet();

    /** @return ID of the current active user (from the moqui.security.UserAccount entity). */
    String getUserId();

    /** @return Username of the current active user (NOT the UserAccount.userId, may even be a username from another system). */
    String getUsername();

    /** @return EntityValue for the current active user (the moqui.security.UserAccount entity). */
    EntityValue getUserAccount();

    /** @return ID of the user associated with the visit. May be different from the active user ID if a service or something is run explicitly as another user. */
    String getVisitUserId();

    /** @return ID for the current visit (aka session; from the Visit entity). Depending on the artifact being executed this may be null. */
    String getVisitId();
    /** @return The current visit (aka session; from the Visit entity). Depending on the artifact being executed this may be null. */
    EntityValue getVisit();
    String getVisitorId();
    /** @return Client IP address from HTTP request or X-Forwarded-For header */
    String getClientIp();
}
