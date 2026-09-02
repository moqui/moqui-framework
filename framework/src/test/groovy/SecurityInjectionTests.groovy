/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.service.ParameterInfo
import org.moqui.impl.service.ServiceDefinition
import org.moqui.util.MNode
import spock.lang.Shared
import spock.lang.Specification

class SecurityInjectionTests extends Specification {
    @Shared ExecutionContext ec
    @Shared ExecutionContextFactoryImpl ecfi

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ecfi = ((org.moqui.impl.context.ExecutionContextImpl) ec).ecfi
        SecurityTestSupport.ensureUsers(ec)
    }
    def cleanupSpec() { SecurityTestSupport.logout(ec); ec.destroy() }

    def "HTML in service parameter is rejected by default allow-html none"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ec.message.clearErrors()
        ec.service.sync().name("org.moqui.impl.UserServices.set#Preference")
                .parameters([preferenceKey: "secHtmlTest", preferenceValue: "<script>alert(1)</script>"]).call()
        then:
        ec.message.hasError() || ec.message.validationErrors
        cleanup:
        ec.message.clearErrors()
    }

    def "SQL-looking preference value is stored as data not executed"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        ec.message.clearAll()
        String key = "secSqliTest"
        ec.user.setPreference(key, SecurityTestSupport.SQLI_OR)
        String stored = ec.user.getPreference(key)
        then:
        stored == SecurityTestSupport.SQLI_OR
        cleanup:
        ec.message.clearAll()
    }

    /** Drives the same ParameterInfo path service validation uses, for each allow-html setting. */
    private String checkHtml(String allowHtml, String value) {
        ServiceDefinition sd = ecfi.serviceFacade.getServiceDefinition("org.moqui.impl.UserServices.set#Preference")
        MNode parmNode = new MNode("parameter", [name: "secTestValue", "allow-html": allowHtml])
        ParameterInfo pi = new ParameterInfo(sd, parmNode)
        ec.message.clearAll()
        String result = pi.validateParameterHtml("", value, true, SecurityTestSupport.eci(ec)) as String
        return result
    }

    def "allow-html safe keeps benign markup"() {
        when:
        String cleaned = checkHtml("safe", "<b>bold</b> and <i>italic</i>")
        then:
        !ec.message.hasError()
        cleaned != null
        cleaned.contains("<b>")
        cleaned.contains("<i>")
    }

    def "allow-html safe strips script and event handlers"() {
        when:
        String cleaned = checkHtml("safe",
                '<b>ok</b><script>alert(1)</script><img src=x onerror=alert(1)><a href="javascript:alert(1)">x</a>')
        then:
        !ec.message.hasError()
        cleaned != null
        cleaned.contains("<b>ok</b>")
        !cleaned.toLowerCase().contains("<script")
        !cleaned.toLowerCase().contains("onerror")
        !cleaned.toLowerCase().contains("javascript:")
    }

    def "allow-html any skips HTML validation entirely"() {
        // ServiceDefinition only calls validateParameterHtml when ANY != allowHtml
        // (ServiceDefinition.java:518), so ANY is a pass-through. Calling validateParameterHtml directly with
        // ANY is not representative: it would take the reject branch, which is unreachable in real validation.
        given:
        ServiceDefinition sd = ecfi.serviceFacade.getServiceDefinition("org.moqui.impl.UserServices.set#Preference")
        expect:
        new ParameterInfo(sd, new MNode("parameter", [name: "a", "allow-html": "any"])).allowHtml ==
                ParameterInfo.ParameterAllowHtml.ANY
        new ParameterInfo(sd, new MNode("parameter", [name: "b", "allow-html": "safe"])).allowSafe
        !new ParameterInfo(sd, new MNode("parameter", [name: "c", "allow-html": "none"])).allowSafe
        !new ParameterInfo(sd, new MNode("parameter", [name: "d"])).allowSafe
    }

    def "allow-html none rejects any less-than and allow-html safe accepts it"() {
        when:
        String noneResult = checkHtml("none", "<b>bold</b>")
        boolean noneErrored = ec.message.hasError()
        ec.message.clearAll()
        String safeResult = checkHtml("safe", "<b>bold</b>")
        boolean safeErrored = ec.message.hasError()
        then:
        noneErrored
        noneResult == null
        !safeErrored
        safeResult != null
        cleanup:
        ec.message.clearAll()
    }
}
