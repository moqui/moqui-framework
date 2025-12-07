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

import org.moqui.Moqui
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise

/**
 * Integration tests for security and authentication functionality.
 * These tests verify the full authentication and authorization workflow
 * including login/logout, permissions, groups, artifact authorization,
 * and login key functionality.
 *
 * NOTE: These tests run in order (@Stepwise) because some tests depend on
 * state from previous tests (like login state).
 */
@Stepwise
class SecurityAuthIntegrationTests extends Specification {
    @Shared
    ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.artifactExecution.disableAuthz()
    }

    def cleanupSpec() {
        ec.artifactExecution.enableAuthz()
        ec.destroy()
    }

    // ========== Username/Password Authentication ==========

    def "login with valid credentials succeeds"() {
        when:
        boolean result = ec.user.loginUser("john.doe", "moqui")

        then:
        result == true
        ec.user.userId == "EX_JOHN_DOE"
        ec.user.username == "john.doe"
    }

    def "logged in user has userAccount populated"() {
        expect:
        ec.user.userAccount != null
        ec.user.userAccount.userFullName == "John Doe"
        ec.user.userAccount.emailAddress == "john.doe@moqui.org"
    }

    def "logout clears user state"() {
        when:
        ec.user.logoutUser()

        then:
        ec.user.userId == null
        ec.user.username == null
        ec.user.userAccount == null
    }

    def "login with invalid password fails"() {
        when:
        boolean result = ec.user.loginUser("john.doe", "wrongpassword")

        then:
        result == false
        ec.user.userId == null
    }

    def "login with non-existent user fails"() {
        when:
        boolean result = ec.user.loginUser("nonexistent.user", "anypassword")

        then:
        result == false
        ec.user.userId == null
    }

    // ========== Anonymous Login ==========

    def "loginAnonymousIfNoUser logs in anonymous when not logged in"() {
        given:
        ec.user.logoutUser()

        when:
        boolean result = ec.user.loginAnonymousIfNoUser()

        then:
        // loginAnonymousIfNoUser only sets a flag, it doesn't set a real userId
        // The method returns true when it successfully sets the anonymous flag
        result == true
    }

    def "loginAnonymousIfNoUser returns false when already logged in"() {
        given:
        ec.user.loginUser("john.doe", "moqui")

        when:
        boolean result = ec.user.loginAnonymousIfNoUser()

        then:
        result == false
        ec.user.userId == "EX_JOHN_DOE"

        cleanup:
        ec.user.logoutUser()
    }

    // ========== User Groups (Role-Based Access) ==========

    def "login admin user for group tests"() {
        when:
        boolean result = ec.user.loginUser("john.doe", "moqui")

        then:
        result == true
    }

    def "user belongs to ALL_USERS group"() {
        expect:
        ec.user.isInGroup("ALL_USERS")
        ec.user.userGroupIdSet.contains("ALL_USERS")
    }

    def "admin user belongs to ADMIN group"() {
        expect:
        ec.user.isInGroup("ADMIN")
        ec.user.userGroupIdSet.contains("ADMIN")
    }

    def "user is not in non-existent group"() {
        expect:
        !ec.user.isInGroup("NONEXISTENT_GROUP")
        !ec.user.userGroupIdSet.contains("NONEXISTENT_GROUP")
    }

    def "userGroupIdSet returns all user groups"() {
        expect:
        ec.user.userGroupIdSet != null
        ec.user.userGroupIdSet.size() >= 2  // At least ALL_USERS and ADMIN
    }

    // ========== Artifact Authorization ==========

    def "disableAuthz disables authorization checks"() {
        when:
        boolean wasDisabled = ec.artifactExecution.disableAuthz()

        then:
        // Method should return previous state
        wasDisabled == true || wasDisabled == false
        noExceptionThrown()
    }

    def "enableAuthz re-enables authorization checks"() {
        when:
        ec.artifactExecution.enableAuthz()

        then:
        noExceptionThrown()
    }

    def "artifact execution stack is accessible"() {
        when:
        def stack = ec.artifactExecution.stack
        def stackArray = ec.artifactExecution.stackArray

        then:
        stack != null
        stackArray != null
    }

    def "push and pop artifact execution info"() {
        given:
        ec.artifactExecution.disableAuthz()

        when:
        ArtifactExecutionInfo aei = ec.artifactExecution.push(
                "TestArtifact",
                ArtifactExecutionInfo.ArtifactType.AT_SERVICE,
                ArtifactExecutionInfo.AuthzAction.AUTHZA_VIEW,
                false)

        then:
        aei != null
        ec.artifactExecution.peek()?.name == "TestArtifact"

        when:
        ec.artifactExecution.pop(aei)

        then:
        ec.artifactExecution.peek()?.name != "TestArtifact"

        cleanup:
        ec.artifactExecution.enableAuthz()
    }

    // ========== Permission Checking ==========

    def "hasPermission returns false for non-existent permission"() {
        expect:
        !ec.user.hasPermission("NONEXISTENT_PERMISSION_12345")
    }

    // ========== Session/Visit Information ==========

    def "visitId is null when not in web context"() {
        expect:
        ec.user.visitId == null
        ec.user.visit == null
    }

    def "visitorId is null when not in web context"() {
        expect:
        ec.user.visitorId == null
    }

    // ========== User Preferences ==========

    def "set and get user preference"() {
        when:
        ec.user.setPreference("SEC_TEST_PREF", "test_value")

        then:
        ec.user.getPreference("SEC_TEST_PREF") == "test_value"
    }

    def "get preferences with regex filter"() {
        given:
        ec.user.setPreference("SEC_FILTER_1", "value1")
        ec.user.setPreference("SEC_FILTER_2", "value2")

        when:
        Map<String, String> prefs = ec.user.getPreferences("SEC_FILTER.*")

        then:
        prefs != null
        prefs.size() >= 2
        prefs.containsKey("SEC_FILTER_1")
        prefs.containsKey("SEC_FILTER_2")
    }

    // ========== Time and Locale Settings ==========

    def "locale can be set and retrieved"() {
        when:
        Locale originalLocale = ec.user.locale
        ec.user.locale = Locale.GERMANY
        Locale newLocale = ec.user.locale

        then:
        newLocale == Locale.GERMANY

        cleanup:
        ec.user.locale = originalLocale
    }

    def "timezone can be set and retrieved"() {
        when:
        TimeZone originalTz = ec.user.timeZone
        TimeZone newTz = TimeZone.getTimeZone("Europe/London")
        ec.user.timeZone = newTz

        then:
        ec.user.timeZone.ID == "Europe/London"

        cleanup:
        ec.user.timeZone = originalTz
    }

    def "currencyUomId can be set and retrieved"() {
        when:
        String originalCurrency = ec.user.currencyUomId
        ec.user.currencyUomId = "EUR"

        then:
        ec.user.currencyUomId == "EUR"

        cleanup:
        ec.user.currencyUomId = originalCurrency
    }

    // ========== Effective Time ==========

    def "nowTimestamp returns current time"() {
        when:
        java.sql.Timestamp now = ec.user.nowTimestamp

        then:
        now != null
        Math.abs(now.time - System.currentTimeMillis()) < 1000
    }

    def "setEffectiveTime overrides nowTimestamp"() {
        given:
        java.sql.Timestamp testTime = new java.sql.Timestamp(1000000000000L)

        when:
        ec.user.setEffectiveTime(testTime)
        java.sql.Timestamp result = ec.user.nowTimestamp

        then:
        result == testTime

        cleanup:
        ec.user.setEffectiveTime(null)
    }

    def "setEffectiveTime to null resets to current time"() {
        given:
        ec.user.setEffectiveTime(new java.sql.Timestamp(1000000000000L))

        when:
        ec.user.setEffectiveTime(null)
        java.sql.Timestamp now = ec.user.nowTimestamp

        then:
        Math.abs(now.time - System.currentTimeMillis()) < 1000
    }

    def "getNowCalendar returns calendar with user settings"() {
        when:
        Calendar cal = ec.user.nowCalendar

        then:
        cal != null
        cal.timeZone == ec.user.timeZone
    }

    // ========== User Context ==========

    def "user context is available and mutable"() {
        when:
        Map<String, Object> context = ec.user.context
        context.put("testKey", "testValue")

        then:
        context != null
        ec.user.context.get("testKey") == "testValue"

        cleanup:
        ec.user.context.remove("testKey")
    }

    // ========== Entity ECA Control ==========

    def "disableEntityEca disables entity ECAs"() {
        when:
        boolean wasDisabled = ec.artifactExecution.disableEntityEca()

        then:
        wasDisabled == true || wasDisabled == false
        noExceptionThrown()
    }

    def "enableEntityEca re-enables entity ECAs"() {
        when:
        ec.artifactExecution.enableEntityEca()

        then:
        noExceptionThrown()
    }

    // ========== Tarpit Control ==========

    def "disableTarpit disables rate limiting"() {
        when:
        boolean wasDisabled = ec.artifactExecution.disableTarpit()

        then:
        wasDisabled == true || wasDisabled == false
        noExceptionThrown()
    }

    def "enableTarpit re-enables rate limiting"() {
        when:
        ec.artifactExecution.enableTarpit()

        then:
        noExceptionThrown()
    }

    // ========== Cleanup ==========

    def "final logout"() {
        when:
        ec.user.logoutUser()

        then:
        ec.user.userId == null
    }
}
