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
        expect:
        webapp.attribute("upload-executable-allow") == "false" ||
                webapp.attribute("upload-executable-allow") == "" ||
                webapp.attribute("upload-executable-allow") == null
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
        WebUtilities.isSameOriginRedirect(url, stub.request) == allowed
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
    }

    def "session cookie is HttpOnly and SameSite Lax in web.xml"() {
        when:
        File webXml = new File("src/main/webapp/WEB-INF/web.xml")
        String text = webXml.getText("UTF-8")
        then:
        webXml.exists()
        text.contains("<http-only>true</http-only>")
        text.contains("__SAME_SITE_LAX__")
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

    def "GroovyShell websocket endpoint is enabled by default"() {
        when:
        MNode ep = webapp.children("endpoint")?.find { it.attribute("path") == "/groovysh" }
        then:
        ep != null
        ep.attribute("enabled") == "true" || ep.attribute("enabled") == null
    }
}
