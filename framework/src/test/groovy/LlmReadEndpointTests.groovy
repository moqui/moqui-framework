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
import org.moqui.impl.llm.LlmGateway
import spock.lang.Shared
import spock.lang.Specification

/** The read-only GET behind sandboxed generated screens: it must read, and must refuse to write. */
class LlmReadEndpointTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.loginUser("john.doe", "moqui")
    }
    def cleanupSpec() { ec.destroy() }
    def setup() {
        ec.artifactExecution.disableAuthz()
        ec.artifactExecution.disableTarpit()
    }
    def cleanup() {
        ec.artifactExecution.enableAuthz()
        ec.artifactExecution.enableTarpit()
    }

    def "the read route is a GET"() {
        expect:
        LlmGateway.parseRoute("/v1/read").op == LlmGateway.Route.Op.READ
        LlmGateway.parseRoute("/v1/read").isGet()
        !LlmGateway.parseRoute("/v1/read").isPost()
    }

    def "a data endpoint is read and returns its JSON"() {
        when:
        def r = LlmGateway.readData(ec, "assist", "/apps/system/dashboard/actions", null)
        then:
        r.status == 200
        r.json != null
        r.error == null
    }

    def "a path that would change data is refused before it runs"() {
        when:
        def r = LlmGateway.readData(ec, "assist", "/apps/system/dashboard/reInitElasticFacade", null)
        then:
        r.status == 403
        r.error.contains("changes data")
        r.json == null
    }

    def "a bad path, an unknown profile and a profile without request access are refused with a status"() {
        expect:
        LlmGateway.readData(ec, "assist", "//evil.example.com/x", null).status == 400
        LlmGateway.readData(ec, "assist", "/apps/../etc/passwd", null).status == 400
        LlmGateway.readData(ec, "assist", "http://evil.example.com/", null).status == 400
        LlmGateway.readData(ec, "no-such-profile", "/apps/system/dashboard/actions", null).status == 404
        LlmGateway.readData(ec, null, "/apps/system/dashboard/actions", null).status == 404
        // The default profile sets no allowed paths and no unprefixed access
        LlmGateway.readData(ec, "default", "/apps/system/dashboard/actions", null).status == 403
        LlmGateway.readData(ec, "assist", "/apps/system/dashboard/actions", "[1,2]").status == 400
    }

    def "query parameters reach the screen"() {
        when:
        def withQuery = LlmGateway.readData(ec, "assist", "/apps/system/Visit/VisitList/actions/VisitList", '{"pageSize":"2"}')
        then:
        withQuery.status == 200
    }
}
