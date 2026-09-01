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
    def setup() { SecurityTestSupport.logout(ec) }

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
                SecurityTestSupport.csrfParams([serviceName: "org.moqui.impl.BasicServices.get#GeoRegionsForDropDown"]),
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
                SecurityTestSupport.csrfParams(), "post")
        then:
        SecurityTestSupport.looksLikeAuthzFailure(str)
    }

    def "VIEW-only user cannot reload ECFI"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.VIEW_USERNAME, SecurityTestSupport.VIEW_PASSWORD)
        ScreenTestRender str = tools.render("dashboard/reloadEcfi",
                SecurityTestSupport.csrfParams(), "post")
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
                SecurityTestSupport.csrfParams([location: "http://127.0.0.1:9/"]), "post")
        then:
        SecurityTestSupport.looksLikeAuthzFailure(str)
    }

    def "VIEW-only user cannot run Data Export"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.VIEW_USERNAME, SecurityTestSupport.VIEW_PASSWORD)
        ScreenTestRender str = tools.render("Entity/DataExport/EntityExport",
                SecurityTestSupport.csrfParams([entityNames: "moqui.basic.Enumeration"]), "post")
        then:
        SecurityTestSupport.looksLikeAuthzFailure(str)
    }

    def "VIEW-only user cannot run ElFinder command"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.VIEW_USERNAME, SecurityTestSupport.VIEW_PASSWORD)
        ScreenTestRender str = system.render("Resource/ElFinder/command",
                SecurityTestSupport.csrfParams([cmd: "open"]), "post")
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

    def "REST login succeeds without a session token"() {
        when:
        ScreenTestRender str = rest.render("login",
                [username: SecurityTestSupport.ALL_USERNAME, password: SecurityTestSupport.ALL_PASSWORD], "post")
        String all = ((str.errorMessages ?: []) + [str.output ?: ""]).join("\n").toLowerCase()
        then:
        !all.contains("session token required")
        (str.output ?: "").contains("loggedIn") || !SecurityTestSupport.looksLikeAuthnFailure(str)
    }

    def "setPreference with a valid session token executes and stores the preference"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ScreenTest apps = ec.screen.makeTest().baseScreenPath("apps")
        ScreenTestRender str = apps.render("setPreference",
                SecurityTestSupport.csrfParams([preferenceKey: "secCsrfPositive", preferenceValue: "csrf-ran-ok"]), "post")
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
