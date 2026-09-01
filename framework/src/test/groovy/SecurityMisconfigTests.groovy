/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.util.MNode
import spock.lang.Shared
import spock.lang.Specification

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
        csp != null && csp.contains("frame-ancestors")
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

    def "log4j core is not a Log4Shell-era 2.14 release"() {
        when:
        String ver = org.apache.logging.log4j.LogManager.class.package.implementationVersion
        then:
        ver != null
        !ver.startsWith("2.14")
        ver.startsWith("2.")
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
        org.moqui.util.WebUtilities.isExecutable([(byte) 0x4d, (byte) 0x5a, 0, 0] as byte[])
        org.moqui.util.WebUtilities.isExecutable([(byte) 0x7f, (byte) 0x45, (byte) 0x4c, (byte) 0x46] as byte[])
        org.moqui.util.WebUtilities.isExecutable([(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe] as byte[])
        org.moqui.util.WebUtilities.isExecutable([(byte) 0xfe, (byte) 0xed, (byte) 0xfa, (byte) 0xce] as byte[])
        !org.moqui.util.WebUtilities.isExecutable([(byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47] as byte[])
    }
}
