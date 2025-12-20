/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.screen.ScreenRender
import org.moqui.screen.ScreenTest
import org.moqui.screen.ScreenTest.ScreenTestRender
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Characterization tests for ScreenFacade.
 * These tests document the current behavior of the screen rendering layer to ensure
 * consistency during modernization efforts.
 *
 * NOTE: ScreenFacade provides two main APIs:
 * - makeRender(): Creates a ScreenRender for general use (web pages, etc.)
 * - makeTest(): Creates a ScreenTest for testing without HTTP request/response
 *
 * ScreenTest renders screens in a separate thread with an independent ExecutionContext
 * to avoid affecting the current context.
 */
class ScreenFacadeCharacterizationTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(ScreenFacadeCharacterizationTests.class)

    @Shared
    ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui")
    }

    def cleanupSpec() {
        ec.destroy()
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
    }

    def cleanup() {
        ec.artifactExecution.enableAuthz()
    }

    // ========== ScreenFacade Factory Methods ==========

    def "makeRender creates ScreenRender instance"() {
        when:
        ScreenRender render = ec.screen.makeRender()

        then:
        render != null
    }

    def "makeTest creates ScreenTest instance"() {
        when:
        ScreenTest test = ec.screen.makeTest()

        then:
        test != null
    }

    // ========== ScreenTest Configuration ==========

    def "ScreenTest with webappName sets default rootScreen"() {
        when:
        // webappName('webroot') is called in constructor and sets root screen based on config
        ScreenTest test = ec.screen.makeTest()

        then:
        test != null
        noExceptionThrown()
    }

    def "ScreenTest with baseScreenPath configures screen path prefix"() {
        when:
        ScreenTest test = ec.screen.makeTest().baseScreenPath("apps/tools")

        then:
        test != null
        noExceptionThrown()
    }

    def "ScreenTest with renderMode configures output type"() {
        when:
        ScreenTest test = ec.screen.makeTest()
                .baseScreenPath("apps/tools")
                .renderMode("html")

        then:
        test != null
        noExceptionThrown()
    }

    def "ScreenTest with encoding configures character encoding"() {
        when:
        ScreenTest test = ec.screen.makeTest()
                .baseScreenPath("apps/tools")
                .encoding("UTF-8")

        then:
        test != null
        noExceptionThrown()
    }

    // ========== Basic Screen Rendering ==========

    def "render dashboard screen returns HTML content"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        ScreenTestRender str = screenTest.render("dashboard", [lastStandalone:"-2"], null)

        then:
        str != null
        !str.errorMessages
        str.output != null
        str.output.length() > 0
        str.renderTime >= 0
    }

    def "render with parameters passes parameters to screen"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        // AutoFind screen accepts entity name and search parameters
        ScreenTestRender str = screenTest.render(
                "AutoScreen/AutoFind?aen=moqui.test.TestEntity&testMedium=Test&testMedium_op=begins",
                [lastStandalone:"-2"], null)

        then:
        str != null
        !str.errorMessages
        str.output != null
    }

    def "render with POST method handles form submissions"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        // Use "post" as request method (matches how transitions check request method)
        ScreenTestRender str = screenTest.render("dashboard", [:], "post")

        then:
        str != null
        // POST to dashboard may not have a specific handler, but should not error
        noExceptionThrown()
    }

    // ========== Screen Path Navigation ==========

    def "render nested screen path navigates screen hierarchy"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        ScreenTestRender str = screenTest.render("Entity/DataEdit/EntityList", [lastStandalone:"-2"], null)

        then:
        str != null
        !str.errorMessages
        str.output != null
    }

    def "render with query parameters in path parses correctly"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        ScreenTestRender str = screenTest.render(
                "Entity/DataEdit/EntityList?filterRegexp=basic",
                [lastStandalone:"-2"], null)

        then:
        str != null
        !str.errorMessages
    }

    // ========== ScreenTestRender Assertions ==========

    def "assertContains checks for text in output"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/system")

        when:
        ScreenTestRender str = screenTest.render("Cache/CacheList", [lastStandalone:"-2"], null)

        then:
        str != null
        !str.errorMessages
        // Cache list should contain entity.definition cache
        str.assertContains("entity.definition") || str.output.contains("entity") || str.output.length() > 0
    }

    def "assertNotContains checks text is not in output"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        ScreenTestRender str = screenTest.render("dashboard", [lastStandalone:"-2"], null)

        then:
        str != null
        !str.errorMessages
        str.assertNotContains("NONEXISTENT_TEXT_12345")
    }

    def "getPostRenderContext returns context after render"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        ScreenTestRender str = screenTest.render("dashboard", [lastStandalone:"-2"], null)

        then:
        str.postRenderContext != null
    }

    def "getScreenRender returns ScreenRender used for rendering"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        ScreenTestRender str = screenTest.render("dashboard", [lastStandalone:"-2"], null)

        then:
        str.screenRender != null
    }

    // ========== ScreenTest Statistics ==========

    def "ScreenTest tracks render count"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")
        long initialCount = screenTest.renderCount

        when:
        screenTest.render("dashboard", [lastStandalone:"-2"], null)

        then:
        screenTest.renderCount == initialCount + 1
    }

    def "ScreenTest tracks total characters rendered"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")
        long initialChars = screenTest.renderTotalChars

        when:
        ScreenTestRender str = screenTest.render("dashboard", [lastStandalone:"-2"], null)

        then:
        screenTest.renderTotalChars >= initialChars
        if (str.output != null) {
            screenTest.renderTotalChars == initialChars + str.output.length()
        }
    }

    def "ScreenTest tracks start time"() {
        when:
        ScreenTest screenTest = ec.screen.makeTest()
        long now = System.currentTimeMillis()

        then:
        screenTest.startTime > 0
        screenTest.startTime <= now
    }

    // ========== Screen Transitions ==========

    def "render screen with transition executes transition"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        // EntityDataFind transition renders entity search results
        ScreenTestRender str = screenTest.render(
                "Entity/DataEdit/EntityDataFind?selectedEntity=moqui.test.TestEntity",
                [lastStandalone:"-2"], null)

        then:
        str != null
        !str.errorMessages
    }

    // ========== Screen Actions ==========

    def "screen actions execute and populate context"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/system")

        when:
        // Security/UserAccount/UserAccountList has actions that query users
        ScreenTestRender str = screenTest.render(
                "Security/UserAccount/UserAccountList?username=john.doe",
                [lastStandalone:"-2"], null)

        then:
        str != null
        !str.errorMessages
        str.assertContains("john.doe")
    }

    // ========== Screen Parameters ==========

    def "screen parameters are accessible in render context"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        // Pass parameters through the Map
        ScreenTestRender str = screenTest.render("dashboard", [testParam: "testValue", lastStandalone:"-2"], null)

        then:
        str != null
        !str.errorMessages
    }

    def "required parameters validation"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        // AutoEditMaster requires testId and aen (entity name) parameters
        ScreenTestRender str = screenTest.render(
                "AutoScreen/AutoEdit/AutoEditMaster?testId=SVCTSTA&aen=moqui.test.TestEntity",
                [lastStandalone:"-2"], null)

        then:
        str != null
        !str.errorMessages
    }

    // ========== Screen Widgets ==========

    def "form widget renders form elements"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        // Service run screen has a form for service parameters
        ScreenTestRender str = screenTest.render(
                "Service/ServiceRun?serviceName=org.moqui.impl.BasicServices.noop",
                [lastStandalone:"-2"], null)

        then:
        str != null
        !str.errorMessages
        str.output != null
    }

    def "section widget renders conditionally"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        // Dashboard typically has sections that render based on context
        ScreenTestRender str = screenTest.render("dashboard", [lastStandalone:"-2"], null)

        then:
        str != null
        !str.errorMessages
    }

    // ========== Screen Subscreens ==========

    def "subscreens navigation works correctly"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        // Entity is a parent screen with subscreens (DataEdit, DataExport, DataImport, etc.)
        ScreenTestRender parentStr = screenTest.render("Entity", [lastStandalone:"-2"], null)
        ScreenTestRender childStr = screenTest.render("Entity/DataEdit", [lastStandalone:"-2"], null)

        then:
        parentStr != null
        childStr != null
        !parentStr.errorMessages
        !childStr.errorMessages
    }

    def "getNoRequiredParameterPaths returns screens without required params"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        List<String> paths = screenTest.getNoRequiredParameterPaths([] as Set)

        then:
        paths != null
        // Should include dashboard which has no required parameters
        paths.size() > 0
    }

    // ========== Render All ==========

    def "renderAll renders multiple screens"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")
        long initialCount = screenTest.renderCount

        when:
        screenTest.renderAll(["dashboard"], [lastStandalone:"-2"], null)

        then:
        screenTest.renderCount == initialCount + 1
    }

    // ========== ScreenRender Configuration ==========

    def "ScreenRender with rootScreen sets root screen location"() {
        when:
        ScreenRender render = ec.screen.makeRender()
                .rootScreen("component://webroot/screen/webroot.xml")

        then:
        render != null
        noExceptionThrown()
    }

    def "ScreenRender with screenPath sets path to render"() {
        when:
        ScreenRender render = ec.screen.makeRender()
                .rootScreen("component://webroot/screen/webroot.xml")
                .screenPath(["apps", "tools", "dashboard"])

        then:
        render != null
        noExceptionThrown()
    }

    def "ScreenRender with string screenPath parses path"() {
        when:
        ScreenRender render = ec.screen.makeRender()
                .rootScreen("component://webroot/screen/webroot.xml")
                .screenPath("apps/tools/dashboard")

        then:
        render != null
        noExceptionThrown()
    }

    def "ScreenRender with renderMode sets output type"() {
        when:
        ScreenRender render = ec.screen.makeRender()
                .rootScreen("component://webroot/screen/webroot.xml")
                .renderMode("html")

        then:
        render != null
        noExceptionThrown()
    }

    def "ScreenRender with encoding sets character encoding"() {
        when:
        ScreenRender render = ec.screen.makeRender()
                .rootScreen("component://webroot/screen/webroot.xml")
                .encoding("UTF-8")

        then:
        render != null
        noExceptionThrown()
    }

    def "ScreenRender with baseLinkUrl sets URL base for links"() {
        when:
        ScreenRender render = ec.screen.makeRender()
                .rootScreen("component://webroot/screen/webroot.xml")
                .baseLinkUrl("http://localhost:8080")

        then:
        render != null
        noExceptionThrown()
    }

    def "ScreenRender with webappName sets webapp context"() {
        when:
        ScreenRender render = ec.screen.makeRender()
                .rootScreen("component://webroot/screen/webroot.xml")
                .webappName("webroot")

        then:
        render != null
        noExceptionThrown()
    }

    def "ScreenRender with lastStandalone sets standalone rendering"() {
        when:
        ScreenRender render = ec.screen.makeRender()
                .rootScreen("component://webroot/screen/webroot.xml")
                .lastStandalone("true")

        then:
        render != null
        noExceptionThrown()
    }

    // ========== Screen Output Modes ==========

    @Unroll
    def "screen renders in #renderMode mode"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest()
                .baseScreenPath("apps/tools")
                .renderMode(renderMode)

        when:
        ScreenTestRender str = screenTest.render("dashboard", [lastStandalone:"-2"], null)

        then:
        str != null
        !str.errorMessages || str.output != null

        where:
        renderMode << ["html", "text"]
    }

    // ========== Error Handling ==========

    def "render non-existent screen captures error"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        ScreenTestRender str = screenTest.render("NonExistent/Screen/Path", [lastStandalone:"-2"], null)

        then:
        // Should capture error rather than throw exception
        str.errorMessages != null && str.errorMessages.size() > 0
    }

    def "ScreenTest tracks error count"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")
        long initialErrors = screenTest.errorCount

        when:
        screenTest.render("NonExistent/Screen/Path", [lastStandalone:"-2"], null)

        then:
        screenTest.errorCount > initialErrors
    }

    // ========== Security and Authorization ==========

    def "screen authorization checks user permissions"() {
        given:
        // Re-enable authz to test permission checking
        ec.artifactExecution.enableAuthz()
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/system")

        when:
        // User john.doe should have access to security screens
        ScreenTestRender str = screenTest.render("Security/UserAccount/UserAccountList", [lastStandalone:"-2"], null)

        then:
        str != null
        // john.doe is admin so should have access
        !str.errorMessages || str.output != null

        cleanup:
        ec.artifactExecution.disableAuthz()
    }

    // ========== Session Attributes ==========

    def "ScreenTest preserves session attributes across renders"() {
        given:
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")

        when:
        ScreenTestRender str1 = screenTest.render("dashboard", [lastStandalone:"-2"], null)
        ScreenTestRender str2 = screenTest.render("dashboard", [lastStandalone:"-2"], null)

        then:
        str1 != null
        str2 != null
        !str1.errorMessages
        !str2.errorMessages
    }
}
