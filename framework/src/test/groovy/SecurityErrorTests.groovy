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

class SecurityErrorTests extends Specification {
    @Shared ExecutionContext ec
    @Shared ScreenTest errorScreens

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        errorScreens = ec.screen.makeTest().baseScreenPath("error")
    }
    def cleanupSpec() { SecurityTestSupport.logout(ec); ec.destroy() }

    def "Unauthorized error page HTML-escapes the message"() {
        when:
        ScreenTestRender str = errorScreens.render("Unauthorized",
                [errorCode: 401, errorMessage: "<script>alert(1)</script>"], "get")
        String out = str.output ?: ""
        then:
        !out.contains("<script>alert(1)</script>")
        out.contains("&lt;script&gt;")
    }

    def "InternalError page does not include a Java stack trace when showErrorDetail is false"() {
        when:
        Exception boom = new RuntimeException("sec-error-secret")
        ScreenTestRender str = errorScreens.render("InternalError",
                [errorCode: 500, errorMessage: "internal", errorThrowable: boom, showErrorDetail: false], "get")
        String out = str.output ?: ""
        then:
        !out.contains("sec-error-secret")
        !out.contains("at org.moqui")
        !out.contains(".groovy:")
    }

    def "InternalError page includes stack when showErrorDetail is true"() {
        when:
        Exception boom = new RuntimeException("sec-error-secret")
        ScreenTestRender str = errorScreens.render("InternalError",
                [errorCode: 500, errorMessage: "internal", errorThrowable: boom, showErrorDetail: true], "get")
        String out = str.output ?: ""
        then:
        out.contains("sec-error-secret")
        out.contains("at ")
    }

}
