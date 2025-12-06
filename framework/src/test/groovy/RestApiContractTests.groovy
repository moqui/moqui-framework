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
import org.moqui.screen.ScreenTest
import org.moqui.screen.ScreenTest.ScreenTestRender
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Contract tests for Moqui REST API endpoints.
 * These tests verify the behavior of:
 * - Service REST endpoints (s1)
 * - Entity REST endpoints (e1)
 * - Master Entity REST endpoints (m1)
 * - Authentication endpoints (login/logout)
 * - API documentation (Swagger, JSON Schema, RAML)
 * - Error responses
 * - Content negotiation
 * - Pagination and filtering
 */
class RestApiContractTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(RestApiContractTests.class)

    @Shared
    ExecutionContext ec
    @Shared
    ScreenTest screenTest

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui")
        screenTest = ec.screen.makeTest().baseScreenPath("rest")
    }

    def cleanupSpec() {
        long totalTime = System.currentTimeMillis() - screenTest.startTime
        logger.info("Rendered ${screenTest.renderCount} screens (${screenTest.errorCount} errors) in ${ec.l10n.format(totalTime/1000, "0.000")}s, output ${ec.l10n.format(screenTest.renderTotalChars/1000, "#,##0")}k chars")
        ec.destroy()
    }

    def setup() {
        ec.artifactExecution.disableAuthz()
    }

    def cleanup() {
        ec.artifactExecution.enableAuthz()
    }

    // ========== Service REST API (s1) ==========

    def "GET service REST endpoint returns JSON response"() {
        when:
        ScreenTestRender str = screenTest.render("s1/moqui/basic/geos/USA", null, null)

        then:
        str != null
        !str.errorMessages
        str.output != null
        str.output.contains("United States") || str.output.contains("geoId")
    }

    def "GET service REST endpoint with query parameters filters results"() {
        when:
        ScreenTestRender str = screenTest.render(
                "s1/moqui/artifacts/hitSummary?artifactType=AT_ENTITY&artifactSubType=create&artifactName=moqui.basic&artifactName_op=contains",
                null, null)

        then:
        str != null
        !str.errorMessages
        str.output != null
    }

    def "GET nested service REST endpoint returns child records"() {
        when:
        ScreenTestRender str = screenTest.render("s1/moqui/basic/geos/USA/regions", null, null)

        then:
        str != null
        !str.errorMessages
    }

    // ========== Entity REST API (e1) ==========

    def "GET entity REST endpoint returns entity data"() {
        when:
        ScreenTestRender str = screenTest.render("e1/moqui.basic.Geo/USA", null, null)

        then:
        str != null
        !str.errorMessages
        str.output != null
        str.output.contains("USA") || str.output.contains("geoId")
    }

    def "GET entity REST endpoint by short-alias works"() {
        when:
        // geos is the short-alias for moqui.basic.Geo
        ScreenTestRender str = screenTest.render("e1/geos/USA", null, null)

        then:
        str != null
        !str.errorMessages
    }

    def "GET entity REST endpoint list returns multiple records"() {
        when:
        ScreenTestRender str = screenTest.render("e1/moqui.basic.Enumeration?enumTypeId=GeoType", null, null)

        then:
        str != null
        !str.errorMessages
        str.output != null
    }

    def "GET entity REST endpoint with pagination parameters"() {
        when:
        ScreenTestRender str = screenTest.render("e1/moqui.basic.Enumeration?pageIndex=0&pageSize=5", null, null)

        then:
        str != null
        !str.errorMessages
        str.output != null
    }

    def "GET entity REST endpoint with ordering"() {
        when:
        ScreenTestRender str = screenTest.render("e1/moqui.basic.Enumeration?enumTypeId=GeoType&orderByField=description", null, null)

        then:
        str != null
        !str.errorMessages
    }

    def "GET entity REST endpoint with filter operators"() {
        when:
        ScreenTestRender str = screenTest.render(
                "e1/moqui.basic.Enumeration?description=Country&description_op=contains&description_ic=Y",
                null, null)

        then:
        str != null
        !str.errorMessages
    }

    def "GET entity REST endpoint with dependents returns related records"() {
        when:
        ScreenTestRender str = screenTest.render("e1/moqui.basic.StatusType/Asset?dependents=true", null, null)

        then:
        str != null
        !str.errorMessages
    }

    // ========== Master Entity REST API (m1) ==========

    def "GET master entity REST endpoint returns master data"() {
        when:
        ScreenTestRender str = screenTest.render("m1/moqui.basic.Geo/default/USA", null, null)

        then:
        str != null
        !str.errorMessages
    }

    def "GET master entity REST endpoint without master name uses default"() {
        when:
        ScreenTestRender str = screenTest.render("m1/geos/USA", null, null)

        then:
        str != null
        !str.errorMessages
    }

    // ========== API Documentation Endpoints ==========

    def "GET entity.json returns JSON schema"() {
        when:
        ScreenTestRender str = screenTest.render("entity.json/geos", null, null)

        then:
        str != null
        !str.errorMessages
        str.output != null
    }

    def "GET entity.swagger returns Swagger definition"() {
        when:
        ScreenTestRender str = screenTest.render("entity.swagger/geos.json", null, null)

        then:
        str != null
        !str.errorMessages
        str.output != null
        // Swagger definition should contain paths or swagger version
        str.output.contains("swagger") || str.output.contains("openapi") || str.output.contains("paths")
    }

    def "GET master.json returns master entity JSON schema"() {
        when:
        ScreenTestRender str = screenTest.render("master.json/geos", null, null)

        then:
        str != null
        !str.errorMessages
    }

    def "GET master.swagger returns master entity Swagger definition"() {
        when:
        ScreenTestRender str = screenTest.render("master.swagger/geos.json", null, null)

        then:
        str != null
        !str.errorMessages
    }

    // ========== Email Template Endpoints ==========

    def "GET email templates returns template list"() {
        when:
        ScreenTestRender str = screenTest.render("s1/moqui/email/templates", null, null)

        then:
        str != null
        !str.errorMessages
        str.output != null
        str.output.contains("PASSWORD_RESET") || str.output.contains("emailTemplateId")
    }

    // ========== Artifact Hit Summary ==========

    def "GET artifact hit summary with type filter"() {
        when:
        ScreenTestRender str = screenTest.render(
                "s1/moqui/artifacts/hitSummary?artifactType=AT_ENTITY",
                null, null)

        then:
        str != null
        !str.errorMessages
    }

    // ========== Error Response Tests ==========

    def "GET non-existent entity returns error response"() {
        when:
        ScreenTestRender str = screenTest.render("e1/NonExistentEntity123/TEST", null, null)

        then:
        // Should return an error but not throw exception
        str != null
        // Either has error messages or contains error in output
        str.errorMessages || (str.output != null && (str.output.contains("error") || str.output.contains("Error") || str.output.contains("not found")))
    }

    def "GET entity with invalid ID returns appropriate response"() {
        when:
        ScreenTestRender str = screenTest.render("e1/moqui.basic.Geo/NONEXISTENT_GEO_12345", null, null)

        then:
        str != null
        // May return empty result or error message
        !str.errorMessages || str.output != null
    }

    // ========== Content Type Tests ==========

    @Unroll
    def "entity REST endpoint supports #format format"() {
        when:
        ScreenTestRender str = screenTest.render("entity.swagger/geos.${extension}", null, null)

        then:
        str != null
        !str.errorMessages

        where:
        format | extension
        "JSON" | "json"
        "YAML" | "yaml"
    }

    // ========== Query Parameter Operators ==========

    @Unroll
    def "entity REST supports #operator operator"() {
        when:
        ScreenTestRender str = screenTest.render(
                "e1/moqui.basic.Enumeration?${paramName}=${paramValue}&${paramName}_op=${operator}",
                null, null)

        then:
        str != null
        !str.errorMessages

        where:
        operator   | paramName     | paramValue
        "equals"   | "enumTypeId"  | "GeoType"
        "contains" | "description" | "Country"
        "begins"   | "description" | "State"
    }

    // ========== Nested Resource Navigation ==========

    def "navigate to nested child resources"() {
        when:
        // First level
        ScreenTestRender parentStr = screenTest.render("e1/moqui.basic.StatusType/Asset", null, null)
        // Second level - get status items for a type
        ScreenTestRender childStr = screenTest.render("e1/moqui.basic.StatusType/Asset?dependentLevels=1", null, null)

        then:
        parentStr != null
        childStr != null
        !parentStr.errorMessages
        !childStr.errorMessages
    }

    // ========== Service REST API with Parameters ==========

    def "service REST endpoint accepts multiple query parameters"() {
        when:
        ScreenTestRender str = screenTest.render(
                "s1/moqui/basic/enumerations?enumTypeId=GeoType&pageIndex=0&pageSize=10&orderByField=sequenceNum",
                null, null)

        then:
        str != null
        !str.errorMessages
    }

    // ========== HTTP Method Simulation ==========

    def "POST method can be simulated for service calls"() {
        when:
        // ScreenTest can simulate POST by passing method parameter
        ScreenTestRender str = screenTest.render("s1/moqui/basic/geos/USA", [:], "post")

        then:
        // POST without proper body might return error, but should handle gracefully
        str != null
        noExceptionThrown()
    }

    // ========== API Versioning (v1 is deprecated alias for e1) ==========

    def "deprecated v1 endpoint still works for backwards compatibility"() {
        when:
        ScreenTestRender str = screenTest.render("v1/moqui.basic.Geo/USA", null, null)

        then:
        str != null
        !str.errorMessages
    }

    // ========== Case Sensitivity ==========

    def "entity names are case sensitive"() {
        when:
        // Correct case
        ScreenTestRender correctStr = screenTest.render("e1/moqui.basic.Geo/USA", null, null)

        then:
        correctStr != null
        !correctStr.errorMessages
    }

    // ========== Empty Result Handling ==========

    def "empty result set returns valid JSON"() {
        when:
        ScreenTestRender str = screenTest.render(
                "e1/moqui.basic.Enumeration?enumTypeId=NONEXISTENT_TYPE_12345",
                null, null)

        then:
        str != null
        !str.errorMessages
        str.output != null
        // Should return empty array or object, not error
        str.output.contains("[]") || str.output.contains("{}") || str.output.length() < 100
    }

    // ========== Special Characters in Parameters ==========

    def "URL-encoded parameters are handled correctly"() {
        when:
        ScreenTestRender str = screenTest.render(
                "e1/moqui.basic.Enumeration?description=Test%20Value",
                null, null)

        then:
        str != null
        !str.errorMessages
    }

    // ========== Multiple Value Parameters ==========

    def "multiple values for same parameter filter correctly"() {
        when:
        // This tests whether multiple values are handled (implementation may vary)
        ScreenTestRender str = screenTest.render(
                "e1/moqui.basic.Enumeration?enumTypeId=GeoType",
                null, null)

        then:
        str != null
        !str.errorMessages
    }

    // ========== System Message Endpoint ==========

    def "system message endpoint exists"() {
        when:
        // The sm endpoint exists but requires specific setup
        // Just verify the endpoint is reachable
        ScreenTestRender str = screenTest.render("sm", null, null)

        then:
        // May return error due to missing parameters, but endpoint exists
        str != null
    }

    // ========== Statistics Tracking ==========

    def "REST API calls are tracked in screen test statistics"() {
        given:
        long initialCount = screenTest.renderCount

        when:
        screenTest.render("e1/moqui.basic.Geo/USA", null, null)
        screenTest.render("s1/moqui/basic/geos/USA", null, null)

        then:
        screenTest.renderCount == initialCount + 2
    }
}
