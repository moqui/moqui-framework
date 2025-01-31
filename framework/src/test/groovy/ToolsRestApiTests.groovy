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

class ToolsRestApiTests extends Specification {
    protected final static Logger logger = LoggerFactory.getLogger(ToolsRestApiTests.class)

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

    @Unroll
    def "call Moqui Tools REST API #screenPath (#containsText1, #containsText2)"() {
        expect:
        ScreenTestRender str = screenTest.render(screenPath, null, null)
        // logger.info("Rendered ${screenPath} in ${str.getRenderTime()}ms")
        boolean contains1 = containsText1 ? str.assertContains(containsText1) : true
        boolean contains2 = containsText2 ? str.assertContains(containsText2) : true
        if (!contains1) logger.info("In ${screenPath} text 1 [${containsText1}] not found:\n${str.output}")
        if (!contains2) logger.info("In ${screenPath} text 2 [${containsText2}] not found:\n${str.output}")
        // assertions
        !str.errorMessages
        contains1
        contains2

        where:
        screenPath | containsText1 | containsText2
        "s1/moqui/artifacts/hitSummary?artifactType=AT_ENTITY&artifactSubType=create&artifactName=moqui.basic&artifactName_op=contains" |
                "moqui.basic.StatusType" | '"artifactSubType" : "create"'
        "s1/moqui/basic/geos/USA" | "United States" | "Country"
        "s1/moqui/basic/geos/USA/regions" | "" | ""
        "s1/moqui/email/templates" | "PASSWORD_RESET" | "Default Password Reset"
        // TODO add more... current are enough to make sure Service REST API working generally, but more would be nice
    }
}
