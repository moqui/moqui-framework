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

class SystemScreenRenderTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(SystemScreenRenderTests.class)

    @Shared
    ExecutionContext ec
    @Shared
    ScreenTest screenTest

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui")
        screenTest = ec.screen.makeTest().baseScreenPath("apps/system")
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
    def "render system screen #screenPath (#containsText1, #containsText2)"() {
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

        // NOTE: see AuditLog, DataDocument, EntitySync, SystemMessage, Visit screen tests in SystemScreenRenderTests in the example component

        // ArtifactHit screens
        "ArtifactHitSummary?artifactName=basic&artifactName_op=contains" | "moqui.basic.Enumeration" | "entity"
        "ArtifactHitBins?artifactName=basic&artifactName_op=contains" | "moqui.basic.Enumeration" | "create"
        // Cache screens
        "Cache/CacheList" | "entity.definition" | "artifact.tarpit.hits"
        "Cache/CacheElements?orderByField=key&cacheName=l10n.message" | '${artifactName}::en_US' | "evictionStrategy"

        // Localization screens
        "Localization/Messages" | "Add" | "Añadir"
        "Localization/EntityFields?entityName=moqui.basic.Enumeration&pkValue=GEOT_STATE" |
                "moqui.basic.Enumeration" | "GEOT_STATE"

        // Print screens
        // NOTE: without real printers setup and jobs sent through service calls not much to test in Print/* screens
        "Print/PrintJob/PrintJobList" | "" | ""
        "Print/Printer/PrinterList" | "" | ""

        // Resource screen
        // NOTE: without a real browser client not much to test in ElFinder
        "Resource/ElFinder" | "" | ""

        // Security screens
        "Security/UserAccount/UserAccountList?username=john.doe" | "john.doe" | "John Doe"
        "Security/UserAccount/UserAccountDetail?userId=EX_JOHN_DOE" |
                "john.doe@moqui.org" | "Administrators (full access)"
        "Security/UserGroup/UserGroupList" | "Administrators (full access)" | ""
        "Security/UserGroup/UserGroupDetail?userGroupId=ADMIN" |
                "" | "System App (via root screen)"
        "Security/UserGroup/GroupUsers?userGroupId=ADMIN" | "john.doe - John Doe" | ""
        "Security/ArtifactGroup/ArtifactGroupList" | "All Screens" | ""
        "Security/ArtifactGroup/ArtifactGroupDetail?artifactGroupId=SYSTEM_APP" |
                "component://tools/screen/System.xml" | "Administrators (full access)"
    }
}
