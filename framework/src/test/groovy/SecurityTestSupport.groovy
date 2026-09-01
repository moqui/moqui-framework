/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.screen.WebFacadeStub
import org.moqui.screen.ScreenTest
import org.moqui.util.MNode

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
    static final String NONE_USERNAME = "sec.none.only"
    static final String NONE_PASSWORD = "SecNone1!!"
    static final String NONE_USER_ID = "SEC_NONE_ONLY"
    static final String NONE_GROUP_ID = "SEC_NONE_GROUP"
    static final String HIST_USERNAME = "sec.hist.only"
    static final String HIST_PASSWORD = "SecHist1!!"
    static final String HIST_USER_ID = "SEC_HIST_ONLY"
    static final String HIST_FAIL_USERNAME = "sec.hist.fail"
    static final String HIST_FAIL_PASSWORD = "SecHistFail1!!"
    static final String HIST_FAIL_USER_ID = "SEC_HIST_FAIL"
    // Narrow vs catch-all AT_ENTITY authz, for /rest/e1 proofs (HTTP only: WebFacadeStub has no entity REST).
    static final String ENT_VIEW_USERNAME = "sec.ent.view"
    static final String ENT_VIEW_PASSWORD = "SecEntView1!!"
    static final String ENT_VIEW_USER_ID = "SEC_ENT_VIEW"
    static final String ENT_VIEW_GROUP_ID = "SEC_ENT_VIEW_GROUP"
    static final String ENT_ALL_USERNAME = "sec.ent.all"
    static final String ENT_ALL_PASSWORD = "SecEntAll1!!"
    static final String ENT_ALL_USER_ID = "SEC_ENT_ALL"
    static final String ENT_ALL_GROUP_ID = "SEC_ENT_ALL_GROUP"
    // MOQUI_API (AT_REST_PATH) authz, for /rest/s1 proofs.
    static final String API_VIEW_USERNAME = "sec.api.view"
    static final String API_VIEW_PASSWORD = "SecApiView1!!"
    static final String API_VIEW_USER_ID = "SEC_API_VIEW"
    static final String API_VIEW_GROUP_ID = "SEC_API_VIEW_GROUP"
    static final String API_ALL_USERNAME = "sec.api.all"
    static final String API_ALL_PASSWORD = "SecApiAll1!!"
    static final String API_ALL_USER_ID = "SEC_API_ALL"
    static final String API_ALL_GROUP_ID = "SEC_API_ALL_GROUP"
    // EntitySyncServices authz, to prove VIEW-only blocks put#EntitySyncData.
    static final String ES_VIEW_USERNAME = "sec.es.view"
    static final String ES_VIEW_PASSWORD = "SecEsView1!!"
    static final String ES_VIEW_USER_ID = "SEC_ES_VIEW"
    static final String ES_VIEW_GROUP_ID = "SEC_ES_VIEW_GROUP"
    static final String ES_ALL_USERNAME = "sec.es.all"
    static final String ES_ALL_PASSWORD = "SecEsAll1!!"
    static final String ES_ALL_USER_ID = "SEC_ES_ALL"
    static final String ES_ALL_GROUP_ID = "SEC_ES_ALL_GROUP"
    static final String IP_V4_USERNAME = "sec.ip.v4"
    static final String IP_V4_PASSWORD = "SecIpV41!!"
    static final String IP_V4_USER_ID = "SEC_IP_V4"
    static final String IP_V4_ALLOWED = "10.99.99.99"
    static final String IP_LOOP_USERNAME = "sec.ip.loop"
    static final String IP_LOOP_PASSWORD = "SecIpLoop1!!"
    static final String IP_LOOP_USER_ID = "SEC_IP_LOOP"
    static final String XSS_SCRIPT = "<script>alert(1)</script>"
    static final String SQLI_OR = "' OR '1'='1"
    static final String SMT_ID = "SEC_SMT_TEST"
    static final String SMR_HMAC = "SEC_SMR_HMAC"
    static final String SMR_HMAC_TS = "SEC_SMR_HMAC_TS"
    static final String SMR_NONE = "SEC_SMR_NONE"
    static final String HMAC_SECRET = "sec-hmac-test-secret"
    static final String HMAC_HEADER = "X-Moqui-Signature"
    static final String EMAIL_PIXEL_ID = "SEC_EMAIL_PIXEL"

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
            ensureGroup(ec, NONE_GROUP_ID, "Security test no artifact authz")
            // AT_ENTITY: narrow (one entity) vs catch-all (.*) — /rest/e1 is generic entity authz
            ensureGroup(ec, ENT_VIEW_GROUP_ID, "Security test AT_ENTITY narrow VIEW")
            ensureGroup(ec, ENT_ALL_GROUP_ID, "Security test AT_ENTITY catch-all ALL")
            ensureArtifactGroup(ec, "SEC_ENT_NARROW", "Security test one entity")
            ensureArtifactGroupMember(ec, "SEC_ENT_NARROW", "moqui.basic.Enumeration", "AT_ENTITY", false)
            ensureArtifactGroup(ec, "SEC_ENT_CATCHALL", "Security test all entities")
            ensureArtifactGroupMember(ec, "SEC_ENT_CATCHALL", ".*", "AT_ENTITY", true)
            ensureAuthz(ec, "SEC_ENT_VIEW_AUTHZ", ENT_VIEW_GROUP_ID, "SEC_ENT_NARROW", "AUTHZA_VIEW")
            ensureAuthz(ec, "SEC_ENT_ALL_AUTHZ", ENT_ALL_GROUP_ID, "SEC_ENT_CATCHALL", "AUTHZA_ALL")
            // MOQUI_API is the seeded AT_REST_PATH group for /rest/s1/moqui/**
            ensureGroup(ec, API_VIEW_GROUP_ID, "Security test MOQUI_API VIEW")
            ensureGroup(ec, API_ALL_GROUP_ID, "Security test MOQUI_API ALL")
            ensureAuthz(ec, "SEC_API_VIEW_AUTHZ", API_VIEW_GROUP_ID, "MOQUI_API", "AUTHZA_VIEW")
            ensureAuthz(ec, "SEC_API_ALL_AUTHZ", API_ALL_GROUP_ID, "MOQUI_API", "AUTHZA_ALL")
            // EntitySyncServices is the seeded group over put#/get#EntitySyncData
            ensureGroup(ec, ES_VIEW_GROUP_ID, "Security test EntitySyncServices VIEW")
            ensureGroup(ec, ES_ALL_GROUP_ID, "Security test EntitySyncServices ALL")
            ensureAuthz(ec, "SEC_ES_VIEW_AUTHZ", ES_VIEW_GROUP_ID, "EntitySyncServices", "AUTHZA_VIEW")
            ensureAuthz(ec, "SEC_ES_ALL_AUTHZ", ES_ALL_GROUP_ID, "EntitySyncServices", "AUTHZA_ALL")
            ensureUser(ec, VIEW_USER_ID, VIEW_USERNAME, VIEW_PASSWORD, VIEW_GROUP_ID)
            ensureUser(ec, ALL_USER_ID, ALL_USERNAME, ALL_PASSWORD, ALL_GROUP_ID)
            ensureUser(ec, LOCK_USER_ID, LOCK_USERNAME, LOCK_PASSWORD, VIEW_GROUP_ID)
            ensureUser(ec, NONE_USER_ID, NONE_USERNAME, NONE_PASSWORD, NONE_GROUP_ID)
            // Only SecurityLoggingTests uses these. UserLoginHistory is de-duplicated to one row per user per
            // 60 seconds, so history proofs need users no other spec logs in as.
            ensureUser(ec, HIST_USER_ID, HIST_USERNAME, HIST_PASSWORD, NONE_GROUP_ID)
            ensureUser(ec, HIST_FAIL_USER_ID, HIST_FAIL_USERNAME, HIST_FAIL_PASSWORD, NONE_GROUP_ID)
            ensureUser(ec, ENT_VIEW_USER_ID, ENT_VIEW_USERNAME, ENT_VIEW_PASSWORD, ENT_VIEW_GROUP_ID)
            ensureUser(ec, ENT_ALL_USER_ID, ENT_ALL_USERNAME, ENT_ALL_PASSWORD, ENT_ALL_GROUP_ID)
            ensureUser(ec, API_VIEW_USER_ID, API_VIEW_USERNAME, API_VIEW_PASSWORD, API_VIEW_GROUP_ID)
            ensureUser(ec, API_ALL_USER_ID, API_ALL_USERNAME, API_ALL_PASSWORD, API_ALL_GROUP_ID)
            ensureUser(ec, ES_VIEW_USER_ID, ES_VIEW_USERNAME, ES_VIEW_PASSWORD, ES_VIEW_GROUP_ID)
            ensureUser(ec, ES_ALL_USER_ID, ES_ALL_USERNAME, ES_ALL_PASSWORD, ES_ALL_GROUP_ID)
            String ipV4Id = ensureUser(ec, IP_V4_USER_ID, IP_V4_USERNAME, IP_V4_PASSWORD, NONE_GROUP_ID)
            String ipLoopId = ensureUser(ec, IP_LOOP_USER_ID, IP_LOOP_USERNAME, IP_LOOP_PASSWORD, NONE_GROUP_ID)
            ensureIpAllowed(ec, ipV4Id, IP_V4_ALLOWED)
            ensureIpAllowed(ec, ipLoopId, "127.0.0.1")
            ensureSystemMessageTestRemotes(ec)
            ensureEmailPixel(ec)
            ensurePermission(ec, "REST_SCHEMA", "REST schema dumps")
            ensureGroupPermission(ec, "ADMIN", "REST_SCHEMA")
        }
    }

    static void ensureSystemMessageTestRemotes(ExecutionContext ec) {
        if (ec.entity.find("moqui.service.message.SystemMessageType").condition("systemMessageTypeId", SMT_ID).one() == null) {
            ec.entity.makeValue("moqui.service.message.SystemMessageType")
                    .setAll([systemMessageTypeId: SMT_ID, description: "Security test message type"]).create()
        }
        if (ec.entity.find("moqui.service.message.SystemMessageRemote").condition("systemMessageRemoteId", SMR_HMAC).one() == null) {
            ec.entity.makeValue("moqui.service.message.SystemMessageRemote").setAll([
                    systemMessageRemoteId: SMR_HMAC, description: "Security test HMAC",
                    systemMessageTypeId: SMT_ID, messageAuthEnumId: "SmatHmacSha256",
                    authHeaderName: HMAC_HEADER, sharedSecret: HMAC_SECRET]).create()
        }
        if (ec.entity.find("moqui.service.message.SystemMessageRemote").condition("systemMessageRemoteId", SMR_HMAC_TS).one() == null) {
            ec.entity.makeValue("moqui.service.message.SystemMessageRemote").setAll([
                    systemMessageRemoteId: SMR_HMAC_TS, description: "Security test HMAC timestamp",
                    systemMessageTypeId: SMT_ID, messageAuthEnumId: "SmatHmacSha256Timestamp",
                     authHeaderName: HMAC_HEADER, sharedSecret: HMAC_SECRET]).create()
        }
        if (ec.entity.find("moqui.service.message.SystemMessageRemote").condition("systemMessageRemoteId", SMR_NONE).one() == null) {
            ec.entity.makeValue("moqui.service.message.SystemMessageRemote").setAll([
                    systemMessageRemoteId: SMR_NONE, description: "Security test no auth",
                    systemMessageTypeId: SMT_ID, messageAuthEnumId: "SmatNone"]).create()
        }
    }

    static void ensureEmailPixel(ExecutionContext ec) {
        def existing = ec.entity.find("moqui.basic.email.EmailMessage").condition("emailMessageId", EMAIL_PIXEL_ID).one()
        if (existing == null) {
            ec.entity.makeValue("moqui.basic.email.EmailMessage").setAll([
                    emailMessageId: EMAIL_PIXEL_ID, statusId: "ES_SENT",
                    subject: "sec pixel", toAddresses: "sec@example.com", fromAddress: "noreply@example.com"]).create()
        } else if (existing.statusId == "ES_VIEWED") {
            existing.statusId = "ES_SENT"
            existing.update()
        }
    }

    /** Raw MoquiDefaultConf.xml, not the DevConf merge used by Gradle tests. */
    static MNode defaultConfRoot() {
        InputStream is = SecurityTestSupport.class.classLoader.getResourceAsStream("MoquiDefaultConf.xml")
        if (is == null) throw new IllegalStateException("MoquiDefaultConf.xml not on classpath")
        try {
            return MNode.parse("classpath://MoquiDefaultConf.xml", is)
        } finally {
            is.close()
        }
    }

    static String defaultProperty(String name) {
        MNode node = defaultConfRoot().children("default-property").find { it.attribute("name") == name }
        return node?.attribute("value")
    }

    static EntityValue waitForLoginHistory(ExecutionContext ec, String userId, long timeoutMs = 4000) {
        long start = System.currentTimeMillis()
        EntityValue found = null
        while (System.currentTimeMillis() - start < timeoutMs) {
            withAuthzDisabled(ec) {
                found = ec.entity.find("moqui.security.UserLoginHistory")
                        .condition("userId", userId).orderBy("-fromDate").limit(1).one()
            }
            if (found != null) return found
            Thread.sleep(50)
        }
        return found
    }

    /** Newest UserLoginHistory.fromDate for a user, or epoch. Used to prove a login wrote ITS OWN row:
     * loginSaveHistory() skips the create if any row exists for the user in the last 60 seconds, so a test that
     * only looks at the newest row can pass on a row written by an earlier test. */
    static long loginHistoryWatermark(ExecutionContext ec, String userId) {
        long watermark = 0L
        withAuthzDisabled(ec) {
            EntityValue newest = ec.entity.find("moqui.security.UserLoginHistory")
                    .condition("userId", userId).orderBy("-fromDate").limit(1).one()
            if (newest?.fromDate) watermark = ((java.sql.Timestamp) newest.fromDate).getTime()
        }
        return watermark
    }

    static EntityValue waitForLoginHistoryAfter(ExecutionContext ec, String userId, long afterMs, long timeoutMs = 4000) {
        long start = System.currentTimeMillis()
        EntityValue found = null
        while (System.currentTimeMillis() - start < timeoutMs) {
            withAuthzDisabled(ec) {
                found = ec.entity.find("moqui.security.UserLoginHistory").condition("userId", userId)
                        .condition("fromDate", org.moqui.entity.EntityCondition.ComparisonOperator.GREATER_THAN,
                                new java.sql.Timestamp(afterMs))
                        .orderBy("-fromDate").limit(1).one()
            }
            if (found != null) return found
            Thread.sleep(50)
        }
        return found
    }

    static String userIdForUsername(ExecutionContext ec, String username) {
        String id = null
        withAuthzDisabled(ec) {
            id = ec.entity.find("moqui.security.UserAccount").condition("username", username).one()?.userId
        }
        return id
    }

    static void ensurePermission(ExecutionContext ec, String permissionId, String description) {
        if (ec.entity.find("moqui.security.UserPermission").condition("userPermissionId", permissionId).one() == null) {
            ec.entity.makeValue("moqui.security.UserPermission")
                    .setAll([userPermissionId: permissionId, description: description]).create()
        }
    }
    static void ensureGroupPermission(ExecutionContext ec, String groupId, String permissionId) {
        def existing = ec.entity.find("moqui.security.UserGroupPermission")
                .condition("userGroupId", groupId).condition("userPermissionId", permissionId).one()
        if (existing == null) {
            ec.entity.makeValue("moqui.security.UserGroupPermission")
                    .setAll([userGroupId: groupId, userPermissionId: permissionId, fromDate: ec.user.nowTimestamp]).create()
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

    static void ensureArtifactGroup(ExecutionContext ec, String artifactGroupId, String description) {
        if (ec.entity.find("moqui.security.ArtifactGroup").condition("artifactGroupId", artifactGroupId).one() == null) {
            ec.entity.makeValue("moqui.security.ArtifactGroup")
                    .setAll([artifactGroupId: artifactGroupId, description: description]).create()
        }
    }

    static void ensureArtifactGroupMember(ExecutionContext ec, String artifactGroupId, String artifactName,
                                          String artifactTypeEnumId, boolean nameIsPattern) {
        def find = ec.entity.find("moqui.security.ArtifactGroupMember")
                .condition("artifactGroupId", artifactGroupId).condition("artifactName", artifactName)
                .condition("artifactTypeEnumId", artifactTypeEnumId)
        if (find.one() == null) {
            ec.entity.makeValue("moqui.security.ArtifactGroupMember").setAll([
                    artifactGroupId: artifactGroupId, artifactName: artifactName,
                    artifactTypeEnumId: artifactTypeEnumId, nameIsPattern: (nameIsPattern ? "Y" : "N")]).create()
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

    static void ensureIpAllowed(ExecutionContext ec, String userId, String ipAllowed) {
        if (!userId) return
        ec.service.sync().name("update", "moqui.security.UserAccount")
                .parameters([userId: userId, ipAllowed: ipAllowed])
                .disableAuthz().requireNewTransaction(true).call()
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

    /** The session token a screen render will compare against. WebFacadeStub returns a fixed value, so ask it. */
    static String sessionToken(ExecutionContext ec) {
        return new WebFacadeStub(eci(ec).ecfi, [:], [:], "get").sessionToken
    }

    static Map csrfParams(ExecutionContext ec, Map extra = [:]) {
        Map m = [moquiSessionToken: sessionToken(ec)]
        if (extra) m.putAll(extra)
        return m
    }
}
