/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.screen.WebFacadeStub
import org.moqui.util.MNode
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.util.WebUtilities
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import jakarta.servlet.ServletContext
import jakarta.servlet.http.HttpServletRequest

class SecurityMisconfigTests extends Specification {
    @Shared ExecutionContext ec
    @Shared ExecutionContextFactoryImpl ecfi
    @Shared MNode confRoot
    @Shared MNode webapp

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ecfi = ((org.moqui.impl.context.ExecutionContextImpl) ec).ecfi
        confRoot = ecfi.getConfXmlRoot()
        webapp = confRoot.first("webapp-list")?.children("webapp")?.find { it.attribute("name") == "webroot" }
    }
    def cleanupSpec() { ec.destroy() }

    def "SubEtha SMTP tool-factory is disabled by default"() {
        when:
        MNode subetha = confRoot.first("tools")?.children("tool-factory")?.find {
            it.attribute("class")?.contains("SubEthaSmtp")
        }
        then:
        subetha != null
        subetha.attribute("disabled") == "true"
    }

    def "upload-executable-allow is false by default"() {
        // Read the default-property, not the webapp attribute: the attribute is the literal
        // ${webapp_upload_executable_allow} until something expands the conf (a screen render in an earlier spec),
        // so asserting on it made this test pass or fail depending on what ran before it.
        expect:
        SecurityTestSupport.defaultProperty("webapp_upload_executable_allow") in [null, "", "false"]
    }

    def "default screen-render CSP and frame options are set"() {
        when:
        List<MNode> headers = webapp.children("response-header").findAll { it.attribute("type") == "screen-render" }
        String csp = headers.find { it.attribute("name") == "Content-Security-Policy" }?.attribute("value")
        String xfo = headers.find { it.attribute("name") == "X-Frame-Options" }?.attribute("value")
        String xcto = headers.find { it.attribute("name") == "X-Content-Type-Options" }?.attribute("value")
        then:
        csp != null && csp.contains("frame-ancestors") && csp.contains("form-action")
        xfo != null
        xcto != null && xcto.toLowerCase().contains("nosniff")
    }

    def "DefaultConf handle-cors is true and allow-origins is empty"() {
        expect:
        SecurityTestSupport.defaultProperty("webapp_handle_cors") == "true"
        SecurityTestSupport.defaultProperty("webapp_allow_origins") == ""
    }

    def "DefaultConf tarpit is on for screens transitions services and off for entities"() {
        when:
        MNode defRoot = SecurityTestSupport.defaultConfRoot()
        Map flags = [:]
        defRoot.first("artifact-execution-facade")?.children("artifact-execution")?.each {
            flags[it.attribute("type")] = it.attribute("tarpit-enabled")
        }
        then:
        flags["AT_XML_SCREEN"] == "true"
        flags["AT_XML_SCREEN_TRANS"] == "true"
        flags["AT_SERVICE"] == "true"
        flags["AT_ENTITY"] == "false"
    }

    def "Jackrabbit tool-factory is disabled by default"() {
        when:
        MNode jr = confRoot.first("tools")?.children("tool-factory")?.find {
            it.attribute("class")?.contains("Jackrabbit")
        }
        then:
        jr != null
        jr.attribute("disabled") == "true"
    }

    def "H2 default-start-server-args do not allow remote TCP connections"() {
        when:
        MNode db = SecurityTestSupport.defaultConfRoot().first("database-list")?.children("database")
                ?.find { it.attribute("name") == "h2" }
        String args = db?.attribute("default-start-server-args")
        then:
        db != null
        args != null
        args.contains("-tcpPort") && args.contains("-ifExists")
        !args.contains("tcpAllowOthers")
    }

    def "screen-secure HSTS response-header is set by default"() {
        when:
        MNode webappNode = SecurityTestSupport.defaultConfRoot().first("webapp-list")?.children("webapp")
                ?.find { it.attribute("name") == "webroot" }
        String hsts = webappNode?.children("response-header")
                ?.find { it.attribute("type") == "screen-secure" && it.attribute("name") == "Strict-Transport-Security" }
                ?.attribute("value")
        then:
        webappNode != null
        hsts != null
        hsts.contains("max-age=")
    }

    def "log4j core is not a Log4Shell-era 2.14 through 2.16 release"() {
        when:
        String ver = org.apache.logging.log4j.LogManager.class.package.implementationVersion
        then:
        ver != null
        ver.startsWith("2.")
        int minor = (ver.split("\\.")[1]) as int
        minor >= 17
    }

    def "shiro is 2.x"() {
        when:
        String ver = org.apache.shiro.SecurityUtils.class.package.implementationVersion
        then:
        ver != null
        ver.startsWith("2.")
    }

    def "executable magic bytes are detected for PE ELF class and Mach-O"() {
        expect:
        WebUtilities.isExecutable([(byte) 0x4d, (byte) 0x5a, 0, 0] as byte[])
        WebUtilities.isExecutable([(byte) 0x7f, (byte) 0x45, (byte) 0x4c, (byte) 0x46] as byte[])
        WebUtilities.isExecutable([(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe] as byte[])
        WebUtilities.isExecutable([(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xce] as byte[])
        WebUtilities.isExecutable([(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xcf] as byte[])
        WebUtilities.isExecutable([(byte) 0xce, (byte) 0xfa, (byte) 0xed, (byte) 0xfe] as byte[])
        WebUtilities.isExecutable([(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe] as byte[])
        !WebUtilities.isExecutable([(byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47] as byte[])
    }

    @Unroll
    def "isSameOriginRedirect #label"() {
        given:
        def stub = new WebFacadeStub(ecfi, [:], [:], "get")
        expect:
        WebUtilities.isSameOriginRedirect(url, stub.request, "localhost") == allowed
        where:
        label                         | url                                  | allowed
        "relative /apps"              | "/apps"                              | true
        "https localhost"             | "https://localhost/apps"             | true
        "https evil"                  | "https://evil.example/phish"         | false
        "protocol-relative"           | "//evil.example"                     | false
        "javascript"                  | "javascript:alert(1)"                | false
        "data"                        | "data:text/html,hi"                  | false
        "vbscript"                    | "vbscript:msg"                       | false
        "backslash"                   | "/\\evil.example"                    | false
        "userinfo"                    | "https://localhost@evil.example/"    | false
        "empty"                       | ""                                   | false
        "crlf path"                   | "/apps\r\nLocation: https://evil.example" | false
        "nul path"                    | "/apps\0evil"                        | false
    }

    def "isSameOriginRedirect rejects absolute URLs when configured host is empty"() {
        given:
        def stub = new WebFacadeStub(ecfi, [:], [:], "get")
        expect:
        WebUtilities.isSameOriginRedirect("/apps", stub.request, null)
        !WebUtilities.isSameOriginRedirect("https://localhost/apps", stub.request, null)
        !WebUtilities.isSameOriginRedirect("https://evil.example/phish", stub.request, "")
    }

    @Unroll
    def "restricted generic entity names: #label"() {
        expect:
        WebUtilities.isRestrictedGenericEntity(name) == restricted
        WebUtilities.isIdentityAdminEntity(name) == identity
        WebUtilities.isSecretConfigEntity(name) == secret
        where:
        label                    | name                                      | restricted | identity | secret
        "UserLoginKey full"      | "moqui.security.UserLoginKey"             | true       | true     | false
        "UserLoginKey simple"    | "UserLoginKey"                            | true       | true     | false
        "UserGroupPermission"    | "moqui.security.UserGroupPermission"      | true       | true     | false
        "alias users"            | "users"                                   | false      | false    | false
        "UserAccount"            | "moqui.security.UserAccount"              | false      | false    | false
        "EmailServer"            | "moqui.basic.email.EmailServer"           | true       | false    | true
        "SystemMessageRemote"    | "moqui.service.message.SystemMessageRemote"| true      | false    | true
        "Enumeration"            | "moqui.basic.Enumeration"                 | false      | false    | false
        "UserAuthcFactor"        | "moqui.security.UserAuthcFactor"          | true       | true     | false
        "UserPasswordHistory"    | "moqui.security.UserPasswordHistory"      | true       | true     | false
        "empty"                  | ""                                        | false      | false    | false
    }

    @Unroll
    def "hostAllowedByConf #label"() {
        expect:
        WebUtilities.hostAllowedByConf(host, allowed) == ok
        where:
        label              | host                  | allowed              | ok
        "empty list"       | "evil.example"        | ""                   | true
        "null list"        | "evil.example"        | null                 | true
        "exact"            | "smtp.example.com"    | "smtp.example.com"   | true
        "subdomain"        | "smtp.example.com"    | "example.com"        | true
        "not suffix"       | "notexample.com"      | "example.com"        | false
        "other host"       | "127.0.0.1"           | "example.com"        | false
        "ip exact"         | "127.0.0.1"           | "127.0.0.1"          | true
        "ip suffix 1"      | "127.0.0.1"           | "1"                  | false
        "ip suffix 0.1"    | "10.0.0.1"            | "0.1"                | false
        "blank host"       | ""                    | "example.com"        | false
        "case"             | "SMTP.Example.COM"    | "example.com"        | true
    }

    @Unroll
    def "isSafeSinglePathSegment #label"() {
        expect:
        WebUtilities.isSafeSinglePathSegment(name) == ok
        where:
        label            | name                                      | ok
        "zip"            | "MoquiSnapshot-20260101.zip"              | true
        "parent"         | "../../conf/MoquiProductionConf.xml"      | false
        "slash"          | "a/b.zip"                                 | false
        "backslash"      | "a\\b.zip"                                | false
        "colon"          | "C:foo.zip"                               | false
        "dotdot"         | ".."                                      | false
        "empty"          | ""                                        | false
        "crlf"           | "foo.zip\r\n"                             | false
    }

    def "hmac replay cache putIfAbsent rejects a second put"() {
        given:
        def cache = ec.cache.getCache("moqui.security.hmac.replay")
        String key = "test-replay-" + System.currentTimeMillis()
        expect:
        cache.putIfAbsent(key, Boolean.TRUE)
        !cache.putIfAbsent(key, Boolean.TRUE)
        cleanup:
        cache.remove(key)
    }

    def "default email_allowed_hosts is empty"() {
        expect:
        (SecurityTestSupport.defaultProperty("email_allowed_hosts") ?: "") == ""
    }

    def "default privileged groups and sealed permissions are set"() {
        when:
        String groups = SecurityTestSupport.defaultProperty("user_privileged_groups")
        String perms = SecurityTestSupport.defaultProperty("user_sealed_permissions")
        then:
        groups.contains("ADMIN")
        groups.contains("ADMIN_ADV")
        perms.contains("GROOVY_SHELL_WEB")
        perms.contains("REST_SCHEMA")
    }

    def "removeUserAccountIdentityFromMap drops control fields"() {
        when:
        Map src = [userId: "SEC_NONE_ONLY", disabled: "Y", emailAddress: "a@b.c",
                   userFullName: "keep", locale: "en"]
        WebUtilities.removeUserAccountIdentityFromMap(src)
        then:
        src.userId == "SEC_NONE_ONLY"
        src.userFullName == "keep"
        src.locale == "en"
        !src.containsKey("disabled")
        !src.containsKey("emailAddress")
    }

    def "stripUserAccountSecrets omits hash fields from nested maps"() {
        when:
        Map nested = [currentPassword: "hash", username: "sec.none.only",
                      child: [resetPassword: "r", passwordSalt: "s", emailAddress: "a@b.c"]]
        Object out = WebUtilities.stripUserAccountSecrets([users: [nested], passwordHashType: "SHA"])
        then:
        out instanceof Map
        !((Map) out).containsKey("passwordHashType")
        Map user = (Map) ((List) ((Map) out).users)[0]
        user.username == "sec.none.only"
        !user.containsKey("currentPassword")
        ((Map) user.child).emailAddress == "a@b.c"
        !((Map) user.child).containsKey("resetPassword")
        !((Map) user.child).containsKey("passwordSalt")
        nested.currentPassword == "hash"
    }

    def "stripCredentialParameters omits password keys case-insensitively"() {
        when:
        Map src = [username: "sec.none.only", password: "SecNone1!!", newPassword: "x",
                   NEWPASSWORDVERIFY: "x", authPassword: "y", api_key: "k", locale: "en"]
        Map out = WebUtilities.stripCredentialParameters(src)
        then:
        out.username == "sec.none.only"
        out.locale == "en"
        !out.containsKey("password")
        !out.containsKey("newPassword")
        !out.containsKey("NEWPASSWORDVERIFY")
        !out.containsKey("authPassword")
        !out.containsKey("api_key")
        src.password == "SecNone1!!"
    }

    def "session cookie is HttpOnly in web.xml"() {
        // NOTE: SameSite=Lax is NOT proven here. web.xml still carries the legacy __SAME_SITE_LAX__ comment but
        // Jetty 12 ignores it; the real control is MoquiContextListener calling
        // SessionCookieConfig.setAttribute("SameSite", "Lax"). That only shows up on a live container, so the
        // SameSite proof is test_a02_headers.py (HTTP).
        when:
        File webXml = new File("src/main/webapp/WEB-INF/web.xml")
        String text = webXml.getText("UTF-8")
        then:
        webXml.exists()
        text.contains("<http-only>true</http-only>")
    }

    def "ScreenTest WebFacadeStub session token matches the CSRF fixture token"() {
        // The CSRF positive controls build their token from this value; if WebFacadeStub.getSessionToken() changes
        // the positive control would quietly become a negative test. Fail here instead.
        given:
        def stub = new WebFacadeStub(ecfi, [:], [:], "get")
        expect:
        stub.sessionToken == SecurityTestSupport.sessionToken(ec)
    }

    def "demo data includes ALL_SCREENS tarpit example"() {
        when:
        File demo = new File("../runtime/base-component/webroot/data/WebrootSecurityDemoData.xml")
        String text = demo.getText("UTF-8")
        then:
        demo.exists()
        text.contains("artifactGroupId=\"ALL_SCREENS\"")
        text.contains("maxHitsCount=\"120\"")
        text.contains("userGroupId=\"ALL_USERS\"")
    }

    def "getClientIp ignores X-Forwarded-For when client-ip-header is empty"() {
        given:
        def sc = Stub(ServletContext) { getInitParameter("moqui-name") >> "webroot" }
        def req = Stub(HttpServletRequest) {
            getServletContext() >> sc
            getRemoteAddr() >> "203.0.113.9"
            getHeader("X-Forwarded-For") >> "127.0.0.1"
            getHeader("X-Real-IP") >> "198.51.100.7"
        }
        expect:
        UserFacadeImpl.getClientIp(req, null, ecfi) == "203.0.113.9"
    }

    /** Pins getClientIp address shapes (used for /status allow-lists, ipAllowed, visit, request log). */
    @Unroll
    def "getClientIp remoteAddr #label canonicalizes"() {
        given:
        def sc = Stub(ServletContext) { getInitParameter("moqui-name") >> "webroot" }
        def req = Stub(HttpServletRequest) {
            getServletContext() >> sc
            getRemoteAddr() >> remoteAddr
        }
        expect:
        UserFacadeImpl.getClientIp(req, null, ecfi) == WebUtilities.canonicalizeClientIp(remoteAddr)
        where:
        label                         | remoteAddr
        "IPv4"                        | "203.0.113.9"
        "IPv4 with port"              | "203.0.113.9:1234"
        "IPv6 loopback compressed"    | "::1"
        "IPv6 loopback full"          | "0:0:0:0:0:0:0:1"
        "IPv6 full"                   | "2001:db8::1"
        "IPv6 brackets with port"     | "[2001:db8::1]:443"
    }

    def "canonicalizeClientIp treats IPv6 loopback forms as equal"() {
        expect:
        WebUtilities.canonicalizeClientIp("::1") == WebUtilities.canonicalizeClientIp("0:0:0:0:0:0:0:1")
        WebUtilities.ipMatches("::1", "0:0:0:0:0:0:0:1")
        WebUtilities.ipMatches("127.0.0.1", "127.0.0.1")
        WebUtilities.ipMatches("10.99.99.99", "8.8.8.8") == false
        WebUtilities.ipMatches("10.0.0.*", "10.0.0.9")
        WebUtilities.ipMatches("::1", "8.8.8.8") == false
    }

    def "simplifyRequestParameters bodyOnly drops query-string keys"() {
        given:
        def req = Stub(HttpServletRequest) {
            getQueryString() >> "sql=SELECT+1"
            getParameterMap() >> [sql: ["SELECT 1"] as String[], groupName: ["transactional"] as String[]]
        }
        when:
        Map body = WebUtilities.simplifyRequestParameters(req, true)
        Map all = WebUtilities.simplifyRequestParameters(req, false)
        then:
        !body.containsKey("sql")
        body.groupName == "transactional"
        all.sql == "SELECT 1"
    }

    def "simplifyRequestParameters bodyOnly drops percent-encoded query names"() {
        given:
        def req = Stub(HttpServletRequest) {
            getQueryString() >> "sq%6c=SELECT+1"
            getParameterMap() >> [sql: ["SELECT 1"] as String[], groupName: ["transactional"] as String[]]
        }
        when:
        Map body = WebUtilities.simplifyRequestParameters(req, true)
        then:
        !body.containsKey("sql")
        body.groupName == "transactional"
    }

    def "shebang is executable and ZIP is not"() {
        expect:
        WebUtilities.isExecutable([(byte) 0x23, (byte) 0x21, (byte) 0x2f, (byte) 0x62] as byte[])
        !WebUtilities.isExecutable([(byte) 0x50, (byte) 0x4b, 0x03, 0x04] as byte[])
        !WebUtilities.isExecutable(new byte[0])
        !WebUtilities.isExecutable([(byte) 0x23] as byte[])
    }

    def "hmac helpers match Base64 and timestamp header algorithms"() {
        given:
        String secret = SecurityTestSupport.HMAC_SECRET
        String body = '{"probe":true}'
        expect:
        WebUtilities.hmacSha256Base64(secret, body) ==
                java.util.Base64.encoder.encodeToString(WebUtilities.hmacSha256(secret, body))
        WebUtilities.hmacSha256TimestampHeader(secret, body, 1492774577L) ==
                "t=1492774577,v1=" + WebUtilities.hmacSha256Hex(secret, "1492774577." + body)
    }

    def "webSocketOriginAllowed empty Origin and same host"() {
        expect:
        WebUtilities.webSocketOriginAllowed(null, "localhost:8080", [], null, null)
        WebUtilities.webSocketOriginAllowed("", "localhost:8080", [], null, null)
        WebUtilities.webSocketOriginAllowed("http://localhost:8080", "localhost:8080", [], null, null)
        !WebUtilities.webSocketOriginAllowed("https://evil.example", "localhost:8080", [], null, null)
        WebUtilities.webSocketOriginAllowed("https://evil.example", "localhost:8080", ["*"], null, null)
        WebUtilities.webSocketOriginAllowed("https://evil.example", "localhost:8080", ["https://evil.example"], null, null)
    }

    def "production remote data load is blocked only in production"() {
        given:
        String oldPurpose = System.getProperty("instance_purpose")
        when:
        System.setProperty("instance_purpose", "production")
        then:
        WebUtilities.isProductionRemoteDataLoadBlocked("http://127.0.0.1:9/sec-ssrf")
        WebUtilities.isProductionRemoteDataLoadBlocked("https://example.com/data.xml")
        !WebUtilities.isProductionRemoteDataLoadBlocked("component://webroot/data/Foo.xml")
        !WebUtilities.isProductionRemoteDataLoadBlocked("/tmp/local.xml")
        when:
        System.setProperty("instance_purpose", "dev")
        then:
        !WebUtilities.isProductionRemoteDataLoadBlocked("http://127.0.0.1:9/sec-ssrf")
        cleanup:
        if (oldPurpose != null) System.setProperty("instance_purpose", oldPurpose)
        else System.clearProperty("instance_purpose")
    }

    def "hmac replay cache is configured distributed"() {
        when:
        MNode cache = SecurityTestSupport.defaultConfRoot().first("cache-list")?.children("cache")
                ?.find { it.attribute("name") == "moqui.security.hmac.replay" }
        then:
        cache != null
        cache.attribute("type") == "distributed"
    }

    def "GroovyShell websocket endpoint is enabled by default"() {
        when:
        MNode ep = webapp.children("endpoint")?.find { it.attribute("path") == "/groovysh" }
        then:
        ep != null
        ep.attribute("enabled") == "true" || ep.attribute("enabled") == null
    }
}
