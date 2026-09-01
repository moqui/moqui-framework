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

    def "short password is rejected"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ec.message.clearErrors()
        def result = ec.service.sync().name("org.moqui.impl.UserServices.update#Password")
                .parameters([userId: SecurityTestSupport.userIdForUsername(ec, SecurityTestSupport.ALL_USERNAME),
                             oldPassword: SecurityTestSupport.ALL_PASSWORD,
                             newPassword: "short", newPasswordVerify: "short"]).call()
        then:
        result?.passwordIssues || ec.message.hasError() || (ec.message.messagesString ?: "").toLowerCase().contains("shorter")
        cleanup:
        ec.message.clearAll()
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
    }

    def "reset Password unknown user does not succeed"() {
        given:
        String common = "If an account exists for that username or email, a reset password was sent. It may only be used to change your password. Your current password is still valid."
        when:
        ec.message.clearAll()
        def result = ec.service.sync().name("org.moqui.impl.UserServices.reset#Password")
                .parameters([username: "sec.no.such.user.zzz"]).disableAuthz().call()
        then:
        !ec.message.hasError()
        ec.message.publicMessages.contains(common)
        cleanup:
        ec.message.clearAll()
    }

    def "reset Password unknown vs existing messages are not distinguishable"() {
        given:
        String common = "If an account exists for that username or email, a reset password was sent. It may only be used to change your password. Your current password is still valid."
        String beforeReset = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            beforeReset = ec.entity.find("moqui.security.UserAccount")
                    .condition("username", SecurityTestSupport.ALL_USERNAME).one()?.resetPassword
        }
        when:
        ec.message.clearAll()
        ec.service.sync().name("org.moqui.impl.UserServices.reset#Password")
                .parameters([username: "sec.no.such.user.zzz"]).disableAuthz().call()
        String unknownMsg = (ec.message.publicMessages ?: []).join("\n")
        boolean unknownError = ec.message.hasError()
        ec.message.clearAll()
        ec.service.sync().name("org.moqui.impl.UserServices.reset#Password")
                .parameters([username: SecurityTestSupport.ALL_USERNAME]).disableAuthz().call()
        String existingMsg = (ec.message.publicMessages ?: []).join("\n")
        boolean existingError = ec.message.hasError()
        String afterReset = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            afterReset = ec.entity.find("moqui.security.UserAccount")
                    .condition("username", SecurityTestSupport.ALL_USERNAME).one()?.resetPassword
        }
        then:
        unknownMsg == existingMsg
        unknownMsg.contains(common)
        !unknownError
        !existingError
        afterReset == beforeReset
        cleanup:
        ec.message.clearAll()
    }

    def "loginUserKey authenticates with a hashed key"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        String key = ec.user.getLoginKey(1)
        SecurityTestSupport.logout(ec)
        boolean ok = ec.user.loginUserKey(key)
        then:
        key
        ok
        ec.user.username == SecurityTestSupport.ALL_USERNAME
        cleanup:
        SecurityTestSupport.logout(ec)
    }

    def "login unknown vs wrong password messages are not distinguishable"() {
        given:
        String common = "The username or password is not valid"
        when:
        ec.message.clearAll()
        boolean unknownOk = ec.user.loginUser("sec.no.such.user.zzz", "definitely-wrong-password")
        String unknownMsg = (ec.message.errors ?: []).join("\n")
        ec.message.clearAll()
        boolean wrongOk = ec.user.loginUser(SecurityTestSupport.ALL_USERNAME, "definitely-wrong-password")
        String wrongMsg = (ec.message.errors ?: []).join("\n")
        then:
        !unknownOk
        !wrongOk
        unknownMsg == wrongMsg
        unknownMsg.contains(common)
        !unknownMsg.toLowerCase().contains("no account found")
        !wrongMsg.toLowerCase().contains("password incorrect")
        !unknownMsg.contains("sec.no.such.user.zzz")
        !wrongMsg.contains(SecurityTestSupport.ALL_USERNAME)
        cleanup:
        ec.message.clearAll()
    }

    def "send ExternalAuthcCode without pre-auth fails"() {
        when:
        ec.message.clearAll()
        ec.service.sync().name("org.moqui.impl.UserServices.send#ExternalAuthcCode")
                .parameters([factorId: "not-a-real-factor"]).call()
        then:
        ec.message.hasError()
        cleanup:
        ec.message.clearAll()
    }

}
