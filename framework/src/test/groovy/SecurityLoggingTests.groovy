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
        String userId = SecurityTestSupport.userIdForUsername(ec, SecurityTestSupport.ALL_USERNAME)
        when:
        boolean ok = SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        EntityValue hist = SecurityTestSupport.waitForLoginHistory(ec, userId)
        then:
        ok
        hist != null
        hist.successfulLogin == "Y"
        hist.passwordUsed == null
    }

    def "failed login history does not store the password used"() {
        given:
        String userId = SecurityTestSupport.userIdForUsername(ec, SecurityTestSupport.LOCK_USERNAME)
        SecurityTestSupport.resetLockAccount(ec)
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.LOCK_USERNAME, "definitely-wrong-password")
        EntityValue hist = SecurityTestSupport.waitForLoginHistory(ec, userId)
        then:
        hist != null
        hist.successfulLogin == "N"
        hist.passwordUsed == null
        cleanup:
        SecurityTestSupport.resetLockAccount(ec)
    }
}
