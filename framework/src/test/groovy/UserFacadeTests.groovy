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

import spock.lang.*

import org.moqui.context.ExecutionContext
import org.moqui.Moqui

class UserFacadeTests extends Specification {
    @Shared
    ExecutionContext ec

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
    }

    def cleanupSpec() {
        ec.destroy()
    }

    def "login user john.doe"() {
        expect:
        ec.user.loginUser("john.doe", "moqui")
    }

    def "check userId username currencyUomId locale userAccount.userFullName defaults"() {
        expect:
        ec.user.userId == "EX_JOHN_DOE"
        ec.user.username == "john.doe"
        ec.user.locale.toString() == "en_US"
        ec.user.timeZone.ID == "US/Central"
        ec.user.currencyUomId == "USD"
        ec.user.userAccount.userFullName == "John Doe"
    }

    def "set and get Locale UK"() {
        when:
        ec.user.setLocale(Locale.UK)
        then:
        ec.user.getLocale() == Locale.UK
        ec.user.getLocale().toString() == "en_GB"
    }
    def "set and get Locale US"() {
        when:
        // set back to en_us
        ec.user.setLocale(Locale.US)
        then:
        ec.user.locale.toString() == "en_US"
    }

    def "set and get TimeZone US/Pacific"() {
        when:
        ec.user.setTimeZone(TimeZone.getTimeZone("US/Pacific"))
        then:
        ec.user.getTimeZone() == TimeZone.getTimeZone("US/Pacific")
        ec.user.getTimeZone().getID() == "US/Pacific"
        ec.user.getTimeZone().getRawOffset() == -28800000
    }

    def "set and get TimeZone US/Central"() {
        when:
        // set TimeZone back to default US/Central
        ec.user.setTimeZone(TimeZone.getTimeZone("US/Central"))
        then:
        ec.user.getTimeZone().getID() == "US/Central"
    }

    def "set and get currencyUomId GBP"() {
        when:
        ec.user.setCurrencyUomId("GBP")
        then:
        ec.user.getCurrencyUomId() == "GBP"
    }

    def "set and get currencyUomId USD"() {
        when:
        // reset to the default USD
        ec.user.setCurrencyUomId("USD")
        then:
        ec.user.getCurrencyUomId() == "USD"
    }

    def "check userGroupIdSet and isInGroup for ALL_USERS and ADMIN"() {
        expect:
        ec.user.userGroupIdSet.contains("ALL_USERS")
        ec.user.isInGroup("ALL_USERS")
        ec.user.userGroupIdSet.contains("ADMIN")
        ec.user.isInGroup("ADMIN")
    }

    /* TODO replacement for this
    def "check default admin group permission ExamplePerm"() {
        expect:
        ec.user.hasPermission("ExamplePerm")
        !ec.user.hasPermission("BogusPerm")
    }
    */

    def "not in web context so no visit"() {
        expect:
        ec.user.visitId == null
    }

    def "set and get Preference"() {
        when:
        ec.user.setPreference("testPref1", "prefValue1")
        then:
        ec.user.getPreference("testPref1") == "prefValue1"
    }

    def "logout user"() {
        expect:
        ec.user.logoutUser()
    }

    // Tests for getLoginKeyAndResetLogoutStatus - Fix for hunterino/moqui#5
    def "getLoginKeyAndResetLogoutStatus creates login key and resets logout status"() {
        when:
        // Login as john.doe
        ec.user.loginUser("john.doe", "moqui")
        String userId = ec.user.userId

        // Set hasLoggedOut to Y to simulate a logged out state
        ec.service.sync().name("update", "moqui.security.UserAccount")
                .parameters([userId: userId, hasLoggedOut: "Y"])
                .disableAuthz().call()

        // Call the new deadlock-safe method
        String loginKey = ec.user.getLoginKeyAndResetLogoutStatus()

        // Verify the login key was created
        def userLoginKey = ec.entity.find("moqui.security.UserLoginKey")
                .condition("userId", userId)
                .orderBy("-fromDate")
                .disableAuthz().one()

        // Verify hasLoggedOut was reset to N
        def userAccount = ec.entity.find("moqui.security.UserAccount")
                .condition("userId", userId)
                .disableAuthz().one()

        then:
        loginKey != null
        loginKey.length() == 40
        userLoginKey != null
        userLoginKey.userId == userId
        userAccount.hasLoggedOut == "N"

        cleanup:
        ec.user.logoutUser()
    }

    def "getLoginKeyAndResetLogoutStatus with custom expireHours"() {
        when:
        ec.user.loginUser("john.doe", "moqui")
        String loginKey = ec.user.getLoginKeyAndResetLogoutStatus(2.0f)
        String userId = ec.user.userId

        def userLoginKey = ec.entity.find("moqui.security.UserLoginKey")
                .condition("userId", userId)
                .orderBy("-fromDate")
                .disableAuthz().one()

        // Calculate expected expiry (approximately 2 hours from now)
        long expectedThruTime = System.currentTimeMillis() + (2 * 60 * 60 * 1000)
        long actualThruTime = userLoginKey.thruDate.time
        long timeDiff = Math.abs(expectedThruTime - actualThruTime)

        then:
        loginKey != null
        // Allow 5 second tolerance for test execution time
        timeDiff < 5000

        cleanup:
        ec.user.logoutUser()
    }

    def "getLoginKeyAndResetLogoutStatus concurrent execution does not deadlock"() {
        when:
        ec.user.loginUser("john.doe", "moqui")
        String userId = ec.user.userId

        // Run multiple concurrent operations to verify no deadlock
        def results = Collections.synchronizedList([])
        def threads = []
        int numThreads = 5

        for (int i = 0; i < numThreads; i++) {
            threads << Thread.start {
                try {
                    def threadEc = Moqui.getExecutionContext()
                    threadEc.user.loginUser("john.doe", "moqui")
                    String key = threadEc.user.getLoginKeyAndResetLogoutStatus()
                    results << [success: true, key: key]
                    threadEc.user.logoutUser()
                    threadEc.destroy()
                } catch (Exception e) {
                    results << [success: false, error: e.message]
                }
            }
        }

        // Wait for all threads with timeout (30 seconds to detect deadlock)
        threads.each { it.join(30000) }

        then:
        // All threads should complete successfully
        results.size() == numThreads
        results.every { it.success }

        cleanup:
        ec.user.logoutUser()
    }
}
