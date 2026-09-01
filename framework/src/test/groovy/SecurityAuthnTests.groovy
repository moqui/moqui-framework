/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification

class SecurityAuthnTests extends Specification {
    @Shared ExecutionContext ec
    @Shared int maxFailures

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        SecurityTestSupport.ensureUsers(ec)
        def loginNode = ((org.moqui.impl.context.ExecutionContextImpl) ec).ecfi.getConfXmlRoot()
                .first("user-facade")?.first("login")
        maxFailures = (loginNode?.attribute("max-failures") ?: "3") as int
    }
    def cleanupSpec() { SecurityTestSupport.logout(ec); ec.destroy() }
    def setup() { SecurityTestSupport.logout(ec) }

    def "failed logins lock the account after max-failures"() {
        when:
        SecurityTestSupport.resetLockAccount(ec)
        boolean lastWrong = true
        // increment#UserAccountFailedLogins disables when successiveFailedLogins is *greater than* max-failures
        for (int i = 0; i < maxFailures + 1; i++) {
            ec.message.clearErrors()
            lastWrong = ec.user.loginUser(SecurityTestSupport.LOCK_USERNAME, "definitely-wrong-password")
            Thread.sleep(25)
        }
        boolean afterLock = ec.user.loginUser(SecurityTestSupport.LOCK_USERNAME, SecurityTestSupport.LOCK_PASSWORD)
        def locked = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            locked = ec.entity.find("moqui.security.UserAccount")
                    .condition("username", SecurityTestSupport.LOCK_USERNAME).one()
        }
        then:
        !lastWrong
        !afterLock
        locked?.disabled == "Y"
        cleanup:
        SecurityTestSupport.resetLockAccount(ec)
    }

    def "create InitialAdminAccount fails when users already exist"() {
        when:
        def result = ec.service.sync().name("org.moqui.impl.UserServices.create#InitialAdminAccount")
                .parameters([username: "sec.should.not.exist", newPassword: "SecAdmin1!!",
                             newPasswordVerify: "SecAdmin1!!"]).call()
        then:
        ec.message.hasError()
        cleanup:
        ec.message.clearErrors()
    }
}
