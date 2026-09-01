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
}
