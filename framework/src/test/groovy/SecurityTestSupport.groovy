/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.screen.ScreenTest

/** Fixtures for public security proof tests. Authz is disabled only while inserting rows. */
class SecurityTestSupport {
    static final String VIEW_USERNAME = "sec.view.only"
    static final String VIEW_PASSWORD = "SecView1!!"
    static final String VIEW_USER_ID = "SEC_VIEW_ONLY"
    static final String VIEW_GROUP_ID = "SEC_VIEW_GROUP"
    static final String ALL_USERNAME = "sec.all.only"
    static final String ALL_PASSWORD = "SecAll1!!"
    static final String ALL_USER_ID = "SEC_ALL_ONLY"
    static final String ALL_GROUP_ID = "SEC_ALL_GROUP"
    static final String LOCK_USERNAME = "sec.lock.test"
    static final String LOCK_PASSWORD = "SecLock1!!"
    static final String LOCK_USER_ID = "SEC_LOCK_TEST"
    static final String SESSION_TOKEN = "TestSessionToken"

    static void logout(ExecutionContext ec) {
        if (ec.user.userId) ec.user.logoutUser()
    }

    static boolean login(ExecutionContext ec, String username, String password) {
        logout(ec)
        return ec.user.loginUser(username, password)
    }

    static ExecutionContextImpl eci(ExecutionContext ec) {
        return (ExecutionContextImpl) ec
    }

    static void withAuthzDisabled(ExecutionContext ec, Closure c) {
        boolean already = ec.artifactExecution.disableAuthz()
        try { c.call() } finally { if (!already) ec.artifactExecution.enableAuthz() }
    }

    static void ensureUsers(ExecutionContext ec) {
        withAuthzDisabled(ec) {
            ensureGroup(ec, VIEW_GROUP_ID, "Security test VIEW-only")
            ensureGroup(ec, ALL_GROUP_ID, "Security test AUTHZA_ALL")
            ensureAuthz(ec, "SEC_VIEW_TOOLS", VIEW_GROUP_ID, "TOOLS_APP", "AUTHZA_VIEW")
            ensureAuthz(ec, "SEC_VIEW_SYS", VIEW_GROUP_ID, "SYSTEM_APP", "AUTHZA_VIEW")
            ensureAuthz(ec, "SEC_ALL_TOOLS", ALL_GROUP_ID, "TOOLS_APP", "AUTHZA_ALL")
            ensureAuthz(ec, "SEC_ALL_SYS", ALL_GROUP_ID, "SYSTEM_APP", "AUTHZA_ALL")
            ensureUser(ec, VIEW_USER_ID, VIEW_USERNAME, VIEW_PASSWORD, VIEW_GROUP_ID)
            ensureUser(ec, ALL_USER_ID, ALL_USERNAME, ALL_PASSWORD, ALL_GROUP_ID)
            ensureUser(ec, LOCK_USER_ID, LOCK_USERNAME, LOCK_PASSWORD, VIEW_GROUP_ID)
        }
    }

    static void ensureGroup(ExecutionContext ec, String groupId, String description) {
        def existing = ec.entity.find("moqui.security.UserGroup").condition("userGroupId", groupId).one()
        if (existing == null) {
            ec.entity.makeValue("moqui.security.UserGroup")
                    .setAll([userGroupId: groupId, description: description]).create()
        }
    }

    static void ensureAuthz(ExecutionContext ec, String authzId, String groupId, String artifactGroupId, String action) {
        def existing = ec.entity.find("moqui.security.ArtifactAuthz").condition("artifactAuthzId", authzId).one()
        if (existing == null) {
            ec.entity.makeValue("moqui.security.ArtifactAuthz").setAll([
                    artifactAuthzId: authzId, userGroupId: groupId, artifactGroupId: artifactGroupId,
                    authzTypeEnumId: "AUTHZT_ALWAYS", authzActionEnumId: action]).create()
        }
    }

    static String ensureUser(ExecutionContext ec, String userId, String username, String password, String groupId) {
        def existing = ec.entity.find("moqui.security.UserAccount").condition("username", username).one()
        if (existing == null) {
            def created = ec.service.sync().name("org.moqui.impl.UserServices.create#UserAccount")
                    .parameters([username: username, newPassword: password, newPasswordVerify: password,
                                 userFullName: username]).call()
            userId = (created?.userId ?: userId) as String
        } else {
            userId = existing.userId
        }
        def ugm = ec.entity.find("moqui.security.UserGroupMember")
                .condition("userId", userId).condition("userGroupId", groupId).one()
        if (ugm == null) {
            ec.entity.makeValue("moqui.security.UserGroupMember")
                    .setAll([userId: userId, userGroupId: groupId, fromDate: ec.user.nowTimestamp]).create()
        }
        if (username == LOCK_USERNAME) resetLockAccount(ec)
        return userId
    }

    /** UserAccount is in a DataFeed; updates must run in an active TX. Failed-login tests often leave none. */
    static void resetLockAccount(ExecutionContext ec) {
        ec.message.clearErrors()
        String userId = null
        withAuthzDisabled(ec) {
            userId = ec.entity.find("moqui.security.UserAccount")
                    .condition("username", LOCK_USERNAME).one()?.userId
        }
        if (!userId) return
        ec.service.sync().name("update", "moqui.security.UserAccount")
                .parameters([userId: userId, disabled: "N", successiveFailedLogins: 0, disabledDateTime: null])
                .disableAuthz().requireNewTransaction(true).call()
        ec.message.clearErrors()
    }

    static ScreenTest toolsScreenTest(ExecutionContext ec) {
        return ec.screen.makeTest().baseScreenPath("apps/tools")
    }
    static ScreenTest systemScreenTest(ExecutionContext ec) {
        return ec.screen.makeTest().baseScreenPath("apps/system")
    }
    static ScreenTest restScreenTest(ExecutionContext ec) {
        return ec.screen.makeTest().baseScreenPath("rest")
    }

    static int responseStatus(ScreenTest.ScreenTestRender str) {
        def sri = str.screenRender
        if (sri instanceof org.moqui.impl.screen.ScreenRenderImpl) {
            def web = sri.ec?.web
            if (web instanceof org.moqui.impl.screen.WebFacadeStub) {
                return web.httpServletResponseStub.status
            }
        }
        // ScreenTest destroys the render ECI; status may still be on the stub via request/response used during render
        return -1
    }

    static boolean looksLikeAuthnFailure(ScreenTest.ScreenTestRender str) {
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase()
        return all.contains("authentication required") ||
                all.contains("authenticationrequiredexception") ||
                all.contains("unauthorized") ||
                all.contains("must be logged in") ||
                all.contains("401")
    }

    static boolean looksLikeAuthzFailure(ScreenTest.ScreenTestRender str) {
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n")
        return all.toLowerCase().contains("not authorized") ||
                all.toLowerCase().contains("forbidden") ||
                all.contains("403") ||
                all.contains("ArtifactAuthorizationException")
    }

    static boolean looksLikeNotFound(ScreenTest.ScreenTestRender str) {
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n")
        return all.toLowerCase().contains("not found") || all.contains("404") ||
                all.contains("ScreenResourceNotFoundException")
    }

    static Map csrfParams(Map extra = [:]) {
        Map m = [moquiSessionToken: SESSION_TOKEN]
        if (extra) m.putAll(extra)
        return m
    }
}
