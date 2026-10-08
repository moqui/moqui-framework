/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.screen.ScreenTest
import org.moqui.screen.ScreenTest.ScreenTestRender
import spock.lang.Shared
import spock.lang.Specification

class SecurityAccessControlTests extends Specification {
    @Shared ExecutionContext ec
    @Shared ScreenTest tools
    @Shared ScreenTest system
    @Shared ScreenTest rest

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        SecurityTestSupport.ensureUsers(ec)
        tools = SecurityTestSupport.toolsScreenTest(ec)
        system = SecurityTestSupport.systemScreenTest(ec)
        rest = SecurityTestSupport.restScreenTest(ec)
    }
    def cleanupSpec() { SecurityTestSupport.logout(ec); ec.destroy() }
    def setup() {
        SecurityTestSupport.logout(ec)
        ec.message.clearAll()
    }

    def "unauthenticated request does not render Tools dashboard"() {
        when:
        ScreenTestRender str = tools.render("dashboard", null, "get")
        String out = (str.output ?: "").toLowerCase()
        then:
        !str.assertContains("Auto Screens")
        SecurityTestSupport.looksLikeAuthnFailure(str) || out.contains("login") || out.isEmpty()
    }

    def "VIEW-only user cannot run Service Run"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.VIEW_USERNAME, SecurityTestSupport.VIEW_PASSWORD)
        ScreenTestRender str = tools.render("Service/ServiceRun/run",
                SecurityTestSupport.csrfParams(ec, [serviceName: "org.moqui.impl.BasicServices.get#GeoRegionsForDropDown"]),
                "post")
        then:
        SecurityTestSupport.looksLikeAuthzFailure(str)
    }

    def "AUTHZA_ALL test user can open Service Run screen"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ScreenTestRender str = tools.render("Service/ServiceRun",
                [serviceName: "org.moqui.impl.BasicServices.get#GeoRegionsForDropDown"], "get")
        then:
        !SecurityTestSupport.looksLikeAuthzFailure(str)
        !SecurityTestSupport.looksLikeAuthnFailure(str)
    }

    def "VIEW-only user cannot clear all caches"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.VIEW_USERNAME, SecurityTestSupport.VIEW_PASSWORD)
        ScreenTestRender str = system.render("Cache/CacheList/clearAllCaches",
                SecurityTestSupport.csrfParams(ec), "post")
        then:
        SecurityTestSupport.looksLikeAuthzFailure(str)
    }

    def "VIEW-only user cannot reload ECFI"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.VIEW_USERNAME, SecurityTestSupport.VIEW_PASSWORD)
        ScreenTestRender str = tools.render("dashboard/reloadEcfi",
                SecurityTestSupport.csrfParams(ec), "post")
        then:
        SecurityTestSupport.looksLikeAuthzFailure(str)
    }

    def "POST Service Run without session token is rejected"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ScreenTestRender str = tools.render("Service/ServiceRun/run",
                [serviceName: "org.moqui.impl.BasicServices.get#GeoRegionsForDropDown"], "post")
        then:
        str.errorMessages || SecurityTestSupport.looksLikeAuthnFailure(str)
        ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase().contains("token") ||
                SecurityTestSupport.looksLikeAuthnFailure(str)
    }

    def "service REST s1 without login is unauthorized"() {
        when:
        // e1 uses handleEntityRestCall, which WebFacadeStub does not implement; cover that on HTTP (framework/test).
        // Unauthenticated s1 hits AT_REST_PATH authz (ArtifactAuthorizationException) before RestApi's
        // AuthenticationRequiredException; ScreenTest records that in errorMessages and leaves output null.
        ScreenTestRender str = rest.render("s1/moqui/basic/enums", null, "get")
        then:
        !str.assertContains("enumId")
        SecurityTestSupport.looksLikeAuthnFailure(str) || SecurityTestSupport.looksLikeAuthzFailure(str)
    }

    def "removed rest api_key transition is not found"() {
        when:
        ScreenTestRender str = rest.render("api_key", null, "get")
        then:
        SecurityTestSupport.looksLikeNotFound(str)
    }

    def "removed rest moquiSessionToken transition is not found"() {
        when:
        ScreenTestRender str = rest.render("moquiSessionToken", null, "get")
        then:
        SecurityTestSupport.looksLikeNotFound(str)
    }

    def "VIEW-only user cannot use SQL Runner"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.VIEW_USERNAME, SecurityTestSupport.VIEW_PASSWORD)
        ScreenTestRender str = tools.render("Entity/SqlRunner", [groupName: "transactional", sql: "SELECT 1"], "get")
        then:
        str.errorMessages || SecurityTestSupport.looksLikeAuthzFailure(str) ||
                (str.output != null && str.output.toLowerCase().contains("permission"))
    }

    def "VIEW-only user cannot run Data Import load"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.VIEW_USERNAME, SecurityTestSupport.VIEW_PASSWORD)
        ScreenTestRender str = tools.render("Entity/DataImport/load",
                SecurityTestSupport.csrfParams(ec, [location: "http://127.0.0.1:9/"]), "post")
        then:
        SecurityTestSupport.looksLikeAuthzFailure(str)
    }

    def "VIEW-only user cannot run Data Export"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.VIEW_USERNAME, SecurityTestSupport.VIEW_PASSWORD)
        ScreenTestRender str = tools.render("Entity/DataExport/EntityExport",
                SecurityTestSupport.csrfParams(ec, [entityNames: "moqui.basic.Enumeration"]), "post")
        then:
        SecurityTestSupport.looksLikeAuthzFailure(str)
    }

    def "VIEW-only user cannot run ElFinder command"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.VIEW_USERNAME, SecurityTestSupport.VIEW_PASSWORD)
        ScreenTestRender str = system.render("Resource/ElFinder/command",
                SecurityTestSupport.csrfParams(ec, [cmd: "open"]), "post")
        then:
        SecurityTestSupport.looksLikeAuthzFailure(str)
    }

    def "VIEW-only user cannot open Groovy Shell"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.VIEW_USERNAME, SecurityTestSupport.VIEW_PASSWORD)
        ScreenTestRender str = tools.render("GroovyShell", null, "get")
        then:
        str.errorMessages || SecurityTestSupport.looksLikeAuthzFailure(str) ||
                (str.output != null && str.output.toLowerCase().contains("permission"))
    }

    def "AUTHZA_ALL without SQL_RUNNER_WEB cannot use SQL Runner"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ScreenTestRender str = tools.render("Entity/SqlRunner", [groupName: "transactional", sql: "SELECT 1"], "get")
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase()
        then:
        all.contains("permission") || SecurityTestSupport.looksLikeAuthzFailure(str)
        !all.contains("columnname")
    }

    def "AUTHZA_ALL without GROOVY_SHELL_WEB cannot open Groovy Shell"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ScreenTestRender str = tools.render("GroovyShell", null, "get")
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase()
        then:
        all.contains("permission") || SecurityTestSupport.looksLikeAuthzFailure(str)
        !all.contains("xterm")
    }

    def "user with no artifact authz cannot open Tools dashboard"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.NONE_USERNAME, SecurityTestSupport.NONE_PASSWORD)
        ScreenTestRender str = tools.render("dashboard", null, "get")
        then:
        SecurityTestSupport.looksLikeAuthzFailure(str) || SecurityTestSupport.looksLikeAuthnFailure(str)
    }

    def "AutoFind of UserAuthcFactor is refused"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.VIEW_USERNAME, SecurityTestSupport.VIEW_PASSWORD)
        ScreenTestRender str = tools.render("AutoScreen/AutoFind",
                [aen: "moqui.security.UserAuthcFactor"], "get")
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase()
        then:
        all.contains("not available through this tool") || SecurityTestSupport.looksLikeAuthzFailure(str)
        !all.contains("factoroption")
    }

    def "AutoFind of SystemMessageRemote is refused"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.VIEW_USERNAME, SecurityTestSupport.VIEW_PASSWORD)
        ScreenTestRender str = tools.render("AutoScreen/AutoFind",
                [aen: "moqui.service.message.SystemMessageRemote"], "get")
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase()
        then:
        all.contains("not available through this tool") || SecurityTestSupport.looksLikeAuthzFailure(str)
        !all.contains("sec-hmac-test-secret")
    }

    def "AutoScreen create of UserGroupPermission is refused"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ScreenTestRender str = tools.render("AutoScreen/AutoEdit/AutoEditDetail/create",
                SecurityTestSupport.csrfParams(ec, [
                        aen: "moqui.security.UserGroup",
                        den: "moqui.security.UserGroupPermission",
                        userGroupId: SecurityTestSupport.ALL_GROUP_ID,
                        userPermissionId: "REST_SCHEMA",
                        fromDate: "2026-09-01 12:00:00.000"]), "post")
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase()
        def row = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            row = ec.entity.find("moqui.security.UserGroupPermission")
                    .condition("userGroupId", SecurityTestSupport.ALL_GROUP_ID)
                    .condition("userPermissionId", "REST_SCHEMA").one()
        }
        then:
        all.contains("not available through this tool") || SecurityTestSupport.looksLikeAuthzFailure(str)
        row == null
    }

    def "AutoFind of Enumeration still renders"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ScreenTestRender str = tools.render("AutoScreen/AutoFind",
                [aen: "moqui.basic.Enumeration"], "get")
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase()
        then:
        // Do not scan the HTML dump with looksLikeAuthzFailure / looksLikeAuthnFailure: those
        // match a bare "403"/"401" in timestamps and ids on a 50-row Enumeration page.
        !all.contains("not available through this tool")
        (str.errorMessages ?: []).isEmpty()
        all.contains("find enumeration") || all.contains("enumid")
    }

    def "SYSTEM_APP ALL cannot add ADMIN group membership"() {
        given:
        String uid = SecurityTestSupport.userIdForUsername(ec, SecurityTestSupport.ALL_USERNAME)
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ScreenTestRender str = system.render("Security/UserGroup/GroupUsers/createUserGroupMember",
                SecurityTestSupport.csrfParams(ec, [userGroupId: "ADMIN", userId: uid,
                        fromDate: ec.user.nowTimestamp.toString()]), "post")
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase()
        def row = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            row = ec.entity.find("moqui.security.UserGroupMember")
                    .condition("userGroupId", "ADMIN").condition("userId", uid).one()
        }
        then:
        all.contains("not authorized") || SecurityTestSupport.looksLikeAuthzFailure(str)
        row == null
    }

    def "SYSTEM_APP ALL cannot add ADMIN_ADV group membership"() {
        given:
        String uid = SecurityTestSupport.userIdForUsername(ec, SecurityTestSupport.ALL_USERNAME)
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ScreenTestRender str = system.render("Security/UserGroup/GroupUsers/createUserGroupMember",
                SecurityTestSupport.csrfParams(ec, [userGroupId: "ADMIN_ADV", userId: uid,
                        fromDate: ec.user.nowTimestamp.toString()]), "post")
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase()
        def row = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            row = ec.entity.find("moqui.security.UserGroupMember")
                    .condition("userGroupId", "ADMIN_ADV").condition("userId", uid).one()
        }
        then:
        all.contains("not authorized") || SecurityTestSupport.looksLikeAuthzFailure(str)
        row == null
    }

    def "SYSTEM_APP ALL cannot grant GROOVY_SHELL_WEB"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ScreenTestRender str = system.render("Security/UserGroup/UserGroupDetail/createUserGroupPermission",
                SecurityTestSupport.csrfParams(ec, [userGroupId: SecurityTestSupport.ALL_GROUP_ID,
                        userPermissionId: "GROOVY_SHELL_WEB", fromDate: ec.user.nowTimestamp.toString()]), "post")
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase()
        def row = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            row = ec.entity.find("moqui.security.UserGroupPermission")
                    .condition("userGroupId", SecurityTestSupport.ALL_GROUP_ID)
                    .condition("userPermissionId", "GROOVY_SHELL_WEB").one()
        }
        then:
        all.contains("not authorized") || SecurityTestSupport.looksLikeAuthzFailure(str)
        row == null
    }

    def "SYSTEM_APP ALL can add a member to its own group"() {
        given:
        String uid = SecurityTestSupport.userIdForUsername(ec, SecurityTestSupport.NONE_USERNAME)
        java.sql.Timestamp fromDate = null
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        fromDate = ec.user.nowTimestamp
        ScreenTestRender str = system.render("Security/UserGroup/GroupUsers/createUserGroupMember",
                SecurityTestSupport.csrfParams(ec, [userGroupId: SecurityTestSupport.ALL_GROUP_ID, userId: uid,
                        fromDate: fromDate.toString()]), "post")
        def row = null
        SecurityTestSupport.withAuthzDisabled(ec) {
            row = ec.entity.find("moqui.security.UserGroupMember")
                    .condition("userGroupId", SecurityTestSupport.ALL_GROUP_ID).condition("userId", uid).one()
        }
        then:
        !SecurityTestSupport.looksLikeAuthzFailure(str)
        row != null
        cleanup:
        SecurityTestSupport.withAuthzDisabled(ec) { row?.delete() }
    }

    def "REST login succeeds without a session token"() {
        when:
        ScreenTestRender str = rest.render("login",
                [username: SecurityTestSupport.ALL_USERNAME, password: SecurityTestSupport.ALL_PASSWORD], "post")
        String out = ((str.output ?: "") + (str.jsonObject ?: "")).toLowerCase()
        then:
        !out.contains("session token required")
        // Must actually log in. An empty or error render is a failure, not a pass.
        out.contains("loggedin")
        out.contains("true")
    }

    def "setPreference with a valid session token executes and stores the preference"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ScreenTest apps = ec.screen.makeTest().baseScreenPath("apps")
        ScreenTestRender str = apps.render("setPreference",
                SecurityTestSupport.csrfParams(ec, [preferenceKey: "secCsrfPositive", preferenceValue: "csrf-ran-ok"]), "post")
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase()
        ScreenTestRender getStr = apps.render("getPreferences", [keyRegexp: "secCsrfPositive"], "get")
        String getOut = (getStr.output ?: "") + (getStr.jsonObject ?: "")
        then:
        !SecurityTestSupport.looksLikeAuthnFailure(str)
        !SecurityTestSupport.looksLikeAuthzFailure(str)
        !all.contains("session token required")
        !all.contains("token does not match")
        (getStr.errorMessages ?: []).isEmpty()
        getOut.contains("csrf-ran-ok")
        ec.user.getPreference("secCsrfPositive") == "csrf-ran-ok"
        cleanup:
        SecurityTestSupport.withAuthzDisabled(ec) { ec.user.setPreference("secCsrfPositive", "") }
    }

    def "setPreference without a session token is rejected and not stored"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ScreenTest apps = ec.screen.makeTest().baseScreenPath("apps")
        ScreenTestRender str = apps.render("setPreference",
                [preferenceKey: "secCsrfNegative", preferenceValue: "must-not-store"], "post")
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase()
        ScreenTestRender getStr = apps.render("getPreferences", [keyRegexp: "secCsrfNegative"], "get")
        String getOut = (getStr.output ?: "") + (getStr.jsonObject ?: "")
        then:
        all.contains("session token required") || SecurityTestSupport.looksLikeAuthnFailure(str)
        (getStr.errorMessages ?: []).isEmpty()
        !getOut.contains("must-not-store")
        !(ec.user.getPreference("secCsrfNegative"))
        cleanup:
        SecurityTestSupport.withAuthzDisabled(ec) { ec.user.setPreference("secCsrfNegative", "") }
    }
}
