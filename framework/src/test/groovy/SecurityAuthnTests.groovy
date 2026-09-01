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
    @Shared int disableMinutes

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        SecurityTestSupport.ensureUsers(ec)
        def loginNode = ((org.moqui.impl.context.ExecutionContextImpl) ec).ecfi.getConfXmlRoot()
                .first("user-facade")?.first("login")
        maxFailures = (loginNode?.attribute("max-failures") ?: "3") as int
        disableMinutes = (loginNode?.attribute("disable-minutes") ?: "5") as int
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
        then:
        unknownMsg == existingMsg
        unknownMsg.contains(common)
        !unknownError
        !existingError
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

    static void lockLockUser(ExecutionContext ec, int maxFailures) {
        SecurityTestSupport.resetLockAccount(ec)
        for (int i = 0; i < maxFailures + 1; i++) {
            ec.message.clearErrors()
            ec.user.loginUser(SecurityTestSupport.LOCK_USERNAME, "definitely-wrong-password")
            Thread.sleep(25)
        }
    }

    static void setDisabledDateTime(ExecutionContext ec, java.sql.Timestamp when) {
        String userId = SecurityTestSupport.userIdForUsername(ec, SecurityTestSupport.LOCK_USERNAME)
        SecurityTestSupport.withAuthzDisabled(ec) {
            ec.message.clearErrors()
            ec.service.sync().name("update", "moqui.security.UserAccount")
                    .parameters([userId: userId, disabled: "Y", disabledDateTime: when])
                    .disableAuthz().requireNewTransaction(true).call()
        }
    }

    def "locked account re-enables after the disable window"() {
        when:
        lockLockUser(ec, maxFailures)
        // move disabledDateTime back so disabledDateTime + disable-minutes is in the past
        long pastMs = System.currentTimeMillis() - ((disableMinutes + 1L) * 60L * 1000L)
        setDisabledDateTime(ec, new java.sql.Timestamp(pastMs))
        ec.message.clearErrors()
        boolean reEnabled = ec.user.loginUser(SecurityTestSupport.LOCK_USERNAME, SecurityTestSupport.LOCK_PASSWORD)
        then:
        reEnabled
        ec.user.username == SecurityTestSupport.LOCK_USERNAME
        cleanup:
        SecurityTestSupport.resetLockAccount(ec)
    }

    def "failed logins while disabled refresh disabledDateTime"() {
        when:
        lockLockUser(ec, maxFailures)
        long pastMs = System.currentTimeMillis() - ((disableMinutes + 1L) * 60L * 1000L)
        setDisabledDateTime(ec, new java.sql.Timestamp(pastMs))
        java.sql.Timestamp before = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            before = (java.sql.Timestamp) ec.entity.find("moqui.security.UserAccount")
                    .condition("username", SecurityTestSupport.LOCK_USERNAME).one()?.disabledDateTime
        }
        ec.message.clearErrors()
        ec.user.loginUser(SecurityTestSupport.LOCK_USERNAME, "definitely-wrong-password")
        def afterRow = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            afterRow = ec.entity.find("moqui.security.UserAccount")
                    .condition("username", SecurityTestSupport.LOCK_USERNAME).one()
        }
        ec.message.clearErrors()
        boolean stillLocked = ec.user.loginUser(SecurityTestSupport.LOCK_USERNAME, SecurityTestSupport.LOCK_PASSWORD)
        then:
        afterRow?.disabled == "Y"
        afterRow?.disabledDateTime != null
        before == null || afterRow.disabledDateTime.after(before)
        !stillLocked
        cleanup:
        SecurityTestSupport.resetLockAccount(ec)
    }

    def "update Password public messages do not include the username"() {
        given:
        String shared = "Password could not be updated with the information provided."
        when:
        ec.message.clearAll()
        ec.service.sync().name("org.moqui.impl.UserServices.update#Password")
                .parameters([username: "sec.no.such.user.zzz", oldPassword: "x",
                             newPassword: "SecNew1!!", newPasswordVerify: "SecNew1!!"]).call()
        String unknownMsg = ((ec.message.publicMessages ?: []) + (ec.message.messages ?: [])).join("\n")
        ec.message.clearAll()
        ec.service.sync().name("org.moqui.impl.UserServices.update#Password")
                .parameters([username: SecurityTestSupport.ALL_USERNAME, oldPassword: "definitely-wrong-password",
                             newPassword: "SecNew1!!", newPasswordVerify: "SecNew1!!"]).call()
        String knownMsg = ((ec.message.publicMessages ?: []) + (ec.message.messages ?: [])).join("\n")
        then:
        unknownMsg.contains(shared)
        knownMsg.contains(shared)
        !unknownMsg.contains("sec.no.such.user.zzz")
        !knownMsg.contains(SecurityTestSupport.ALL_USERNAME)
        cleanup:
        ec.message.clearAll()
    }

    def "update Password wrong old password increments successiveFailedLogins"() {
        given:
        String uname = "sec.upw." + System.currentTimeMillis()
        ec.message.clearAll()
        def created = ec.service.sync().name("org.moqui.impl.UserServices.create#UserAccount")
                .parameters([username: uname, newPassword: "SecUpw1!!", newPasswordVerify: "SecUpw1!!",
                             userFullName: uname]).disableAuthz().call()
        Integer before = 0
        SecurityTestSupport.withAuthzDisabled(ec) {
            before = (ec.entity.find("moqui.security.UserAccount").condition("username", uname).one()
                    ?.successiveFailedLogins ?: 0) as Integer
        }
        when:
        ec.message.clearAll()
        ec.service.sync().name("org.moqui.impl.UserServices.update#Password")
                .parameters([username: uname, oldPassword: "wrong-old",
                             newPassword: "SecUpw2!!", newPasswordVerify: "SecUpw2!!"]).call()
        Integer after = before
        SecurityTestSupport.withAuthzDisabled(ec) {
            after = (ec.entity.find("moqui.security.UserAccount").condition("username", uname).one()
                    ?.successiveFailedLogins ?: 0) as Integer
        }
        then:
        created?.userId
        after > before
        cleanup:
        ec.message.clearAll()
    }

    def "reset Password in-parameters do not include a caller template id"() {
        when:
        def sd = ((org.moqui.impl.context.ExecutionContextImpl) ec).ecfi.serviceFacade
                .getServiceDefinition("org.moqui.impl.UserServices.reset#Password")
        then:
        sd != null
        !sd.getInParameterNames().contains("emailTemplateId")
        !sd.getInParameterNames().contains("bodyParameters")
    }

    def "reset Password second call inside the window does not change resetPassword"() {
        given:
        String uname = "sec.rst." + System.currentTimeMillis()
        ec.message.clearAll()
        ec.service.sync().name("org.moqui.impl.UserServices.create#UserAccount")
                .parameters([username: uname, newPassword: "SecRst1!!", newPasswordVerify: "SecRst1!!",
                             userFullName: uname, emailAddress: uname + "@example.com"]).disableAuthz().call()
        when:
        ec.message.clearAll()
        ec.service.sync().name("org.moqui.impl.UserServices.reset#Password")
                .parameters([username: uname]).disableAuthz().call()
        String firstHash = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            firstHash = ec.entity.find("moqui.security.UserAccount").condition("username", uname).one()?.resetPassword
        }
        ec.message.clearAll()
        ec.service.sync().name("org.moqui.impl.UserServices.reset#Password")
                .parameters([username: uname]).disableAuthz().call()
        String secondHash = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            secondHash = ec.entity.find("moqui.security.UserAccount").condition("username", uname).one()?.resetPassword
        }
        then:
        firstHash
        firstHash == secondHash
        cleanup:
        ec.message.clearAll()
    }

    def "logoutUser sets hasLoggedOut on the account"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        String userId = ec.user.userId
        ec.user.logoutUser()
        def ua = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            ua = ec.entity.find("moqui.security.UserAccount").condition("userId", userId).one()
        }
        then:
        ua != null
        ua.hasLoggedOut == "Y"
    }

    def "locked account stays locked inside the disable window"() {
        when:
        lockLockUser(ec, maxFailures)
        // disabledDateTime in the recent past: re-enable time is still in the future
        setDisabledDateTime(ec, new java.sql.Timestamp(System.currentTimeMillis() - 1000L))
        ec.message.clearErrors()
        boolean stillLocked = ec.user.loginUser(SecurityTestSupport.LOCK_USERNAME, SecurityTestSupport.LOCK_PASSWORD)
        then:
        !stillLocked
        cleanup:
        SecurityTestSupport.resetLockAccount(ec)
    }

}
