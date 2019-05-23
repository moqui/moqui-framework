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

class ToolsScreenRenderTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(ToolsScreenRenderTests.class)

    @Shared
    ExecutionContext ec
    @Shared
    ScreenTest screenTest

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui")
        screenTest = ec.screen.makeTest().baseScreenPath("apps/tools")
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

    @Unroll
    def "render tools screen #screenPath (#containsText1, #containsText2)"() {
        setup:
        ScreenTestRender str = screenTest.render(screenPath, [lastStandalone:"-2"], null)
        // logger.info("Rendered ${screenPath} in ${str.getRenderTime()}ms")
        boolean contains1 = containsText1 ? str.assertContains(containsText1) : true
        boolean contains2 = containsText2 ? str.assertContains(containsText2) : true
        if (!contains1) logger.info("In ${screenPath} text 1 [${containsText1}] not found:\n${str.output}")
        if (!contains2) logger.info("In ${screenPath} text 2 [${containsText2}] not found:\n${str.output}")

        expect:
        !str.errorMessages
        contains1
        contains2

        where:
        screenPath | containsText1 | containsText2
        "dashboard" | "" | ""

        // AutoScreen screens
        "AutoScreen/MainEntityList" | "" | ""
        "AutoScreen/AutoFind?aen=moqui.test.TestEntity&testMedium=Test&testMedium_op=begins" | "Test Name A" | ""
        "AutoScreen/AutoEdit/AutoEditMaster?testId=SVCTSTA&aen=moqui.test.TestEntity" | "Test Name A" | ""
        // TODO "AutoScreen/AutoEdit/AutoEditDetail?exampleId=TEST1&aen=moqui.example.Example&den=moqui.example.ExampleItem" | "Amount Uom ID" | "Test 1 Item 1"
        // test moqui.test.TestEntity create through transition, then view it
        "AutoScreen/AutoFind/create?aen=moqui.test.TestEntity&testId=TEST_SCR&testMedium=Screen Test Example" | "" | ""
        "AutoScreen/AutoEdit/AutoEditMaster?testId=TEST_SCR&aen=moqui.test.TestEntity" | "Screen Test Example" | ""

        // ArtifactStats screen
        // don't run, takes too long: "ArtifactStats" | "" | ""

        // DataView screens
        // see "render DataView screens"

        // Entity/DataEdit screens
        "Entity/DataEdit/EntityList?filterRegexp=basic" | "Enumeration" | "moqui.basic"
        "Entity/DataEdit/EntityDetail?selectedEntity=moqui.test.TestEntity" | "text-medium" | "date-time"
        "Entity/DataEdit/EntityDataFind?selectedEntity=moqui.test.TestEntity" | "Test Name A" | ""
        "Entity/DataEdit/EntityDataEdit?testId=SVCTSTA&selectedEntity=moqui.test.TestEntity" | "Test Name A" | ""

        // Other Entity screens
        "Entity/DataExport" | "moqui.test.TestEntity" | ""
        // test export JSON and XML for moqui.test.TestEntity
        "Entity/DataExport/EntityExport?entityNames=moqui.test.TestEntity&dependentLevels=1&fileType=JSON&output=browser" | "Test Name A" | "testMedium"
        "Entity/DataExport/EntityExport?entityNames=moqui.test.TestEntity&dependentLevels=1&fileType=XML&output=browser" | "Test Name A" | "testMedium"
        "Entity/DataImport" | "" | ""
        "Entity/SqlRunner?groupName=transactional&sql=SELECT * FROM TEST_ENTITY" | "Test Name A" | ""
        // run with very few baseCalls so it doesn't take too long
        "Entity/SpeedTest?baseCalls=10" | "" | ""

        // Service screens
        "Service/ServiceReference?serviceName=UserServices" |
                "org.moqui.impl.UserServices.create#UserAccount" | "Service Detail"
        "Service/ServiceDetail?serviceName=org.moqui.impl.UserServices.create#UserAccount" |
                "moqui.security.UserAccount.username" | """ec.service.sync().name(&quot;create#moqui.security.UserAccount&quot;)"""
        "Service/ServiceRun?serviceName=org.moqui.impl.UserServices.create#UserAccount" |
                "User Full Name" | "Cron String"
        // run the service, then make sure it ran
        "Service/ServiceRun/run?serviceName=org.moqui.impl.UserServices.create#UserAccount&username=ScreenTest&newPassword=moqui1!!&newPasswordVerify=moqui1!!&userFullName=Screen Test User&emailAddress=screen@test.com" | "" | ""
        "Entity/DataEdit/EntityDataFind?username=ScreenTest&selectedEntity=moqui.security.UserAccount" |
                "Screen Test User" | "screen@test.com"
    }

    def "render DataView screens"() {
        // create a DbViewEntity, set MASTER and fields, view it
        when:
        ScreenTestRender createStr = screenTest.render("DataView/FindDbView/create",
                [dbViewEntityName: 'UomDbView', packageName: 'test.basic', isDataView: 'Y'], null)
        logger.info("Called FindDbView/create in ${createStr.getRenderTime()}ms")

        ScreenTestRender fdvStr = screenTest.render("DataView/FindDbView", [lastStandalone:"-2"], null)
        logger.info("Rendered DataView/FindDbView in ${fdvStr.getRenderTime()}ms, ${fdvStr.output?.length()} characters")

        ScreenTestRender setMeStr = screenTest.render("DataView/EditDbView/setMasterEntity",
                [dbViewEntityName: 'UomDbView', entityAlias: 'MASTER', entityName: 'moqui.basic.Uom'], null)
        logger.info("Called EditDbView/setMasterEntity in ${setMeStr.getRenderTime()}ms")

        ScreenTestRender setMfStr = screenTest.render("DataView/EditDbView/setMasterFields",
                [dbViewEntityName_0: 'UomDbView', field_0: 'moqui.basic.Uom.description',
                 dbViewEntityName_1: 'UomDbView', field_1: 'UomType#moqui.basic.Enumeration.description',
                 dbViewEntityName_2: 'UomDbView', field_2: 'UomType#moqui.basic.Enumeration.enumTypeId'], null)
        logger.info("Called EditDbView/setMasterFields in ${setMfStr.getRenderTime()}ms")

        ScreenTestRender vdvStr = screenTest.render("DataView/ViewDbView?dbViewEntityName=UomDbView&orderByField=description", [lastStandalone:"-2"], null)
        logger.info("Rendered DataView/FindDbView in ${vdvStr.getRenderTime()}ms, ${vdvStr.output?.length()} characters")

        then:
        !createStr.errorMessages
        !fdvStr.errorMessages
        fdvStr.assertContains("UomDbView")
        !setMeStr.errorMessages
        !setMfStr.errorMessages
        !vdvStr.errorMessages
        vdvStr.assertContains("Afghani")
        vdvStr.assertContains("Area") // for Acre
    }
}
