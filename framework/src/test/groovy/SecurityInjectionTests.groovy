/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import spock.lang.Shared
import spock.lang.Specification

class SecurityInjectionTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
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
}
