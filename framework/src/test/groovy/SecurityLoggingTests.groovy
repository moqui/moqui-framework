/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.util.MNode
import spock.lang.Shared
import spock.lang.Specification

class SecurityLoggingTests extends Specification {
    @Shared ExecutionContext ec
    @Shared ExecutionContextFactoryImpl ecfi

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ecfi = ((org.moqui.impl.context.ExecutionContextImpl) ec).ecfi
        SecurityTestSupport.ensureUsers(ec)
    }
    def cleanupSpec() { SecurityTestSupport.logout(ec); ec.destroy() }
    def setup() { SecurityTestSupport.logout(ec) }

    def "login history-store is true and incorrect passwords are not stored by default"() {
        when:
        MNode login = SecurityTestSupport.defaultConfRoot().first("user-facade")?.first("login")
        then:
        login.attribute("history-store") == "true"
        login.attribute("history-incorrect-password") == "false"
    }

    def "successful login writes UserLoginHistory without passwordUsed"() {
        given:
        // dedicated user: no other spec logs in as this, so the row below cannot be a de-duplicated leftover
        String userId = SecurityTestSupport.userIdForUsername(ec, SecurityTestSupport.HIST_USERNAME)
        long watermark = SecurityTestSupport.loginHistoryWatermark(ec, userId)
        when:
        boolean ok = SecurityTestSupport.login(ec, SecurityTestSupport.HIST_USERNAME, SecurityTestSupport.HIST_PASSWORD)
        EntityValue hist = SecurityTestSupport.waitForLoginHistoryAfter(ec, userId, watermark)
        then:
        ok
        // fails if no NEW row was written (history-store off, or swallowed by the 60s de-dup)
        hist != null
        hist.successfulLogin == "Y"
        hist.passwordUsed == null
    }

    def "failed login history does not store the password used"() {
        given:
        String userId = SecurityTestSupport.userIdForUsername(ec, SecurityTestSupport.HIST_FAIL_USERNAME)
        long watermark = SecurityTestSupport.loginHistoryWatermark(ec, userId)
        when:
        boolean ok = SecurityTestSupport.login(ec, SecurityTestSupport.HIST_FAIL_USERNAME, "definitely-wrong-password")
        EntityValue hist = SecurityTestSupport.waitForLoginHistoryAfter(ec, userId, watermark)
        then:
        !ok
        hist != null
        hist.successfulLogin == "N"
        hist.passwordUsed == null
    }
}
