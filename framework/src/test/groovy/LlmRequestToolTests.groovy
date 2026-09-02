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
import org.moqui.context.WebFacade
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.context.UserFacadeImpl
import org.moqui.impl.llm.RequestTool
import org.moqui.impl.llm.ServiceCallTool
import org.moqui.impl.screen.WebFacadeStub
import org.moqui.llm.LlmTool
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

@IgnoreIf({
    String runtime = System.getProperty("moqui.runtime") ?: "../runtime"
    String conf = System.getProperty("moqui.conf") ?: "conf/MoquiDevConf.xml"
    File direct = new File(conf)
    File nested = new File(runtime, conf.startsWith("conf/") ? conf : "conf/" + new File(conf).name)
    !direct.exists() && !nested.exists() && !new File(runtime, "conf/MoquiDevConf.xml").exists()
})
class LlmRequestToolTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
    }
    def cleanupSpec() {
        ec.destroy()
    }
    def setup() {
        if (ec.transaction.isTransactionInPlace()) ec.transaction.commit()
        if (!ec.user.userId) ec.user.loginUser("john.doe", "moqui")
        // RequestTool must run with authz ON (boolean flag, not a counter).
        ec.artifactExecution.enableAuthz()
    }
    def cleanup() {
        if (ec.transaction.isTransactionInPlace()) ec.transaction.rollback("test cleanup", null)
        if (ec.user.userId && ec.user.username != "john.doe") {
            ec.user.logoutUser()
            ec.user.loginUser("john.doe", "moqui")
        }
    }

    def "GET actions returns JSON not HTML"() {
        when:
        def result = LlmTool.request().execute([method: "GET", path: "/apps/system/dashboard/actions"], ec)
        then:
        result.status == 200
        result.json != null
        result.json instanceof Map
        result.text == null || !result.text.toString().toLowerCase().contains("<html")
    }

    def "GET /rest/s1/moqui/basic/geos/USA as john.doe"() {
        when:
        def result = LlmTool.request().execute(
                [method: "GET", path: "/rest/s1/moqui/basic/geos/USA"], ec)
        String blob = (result.json != null ? result.json.toString() : "") + (result.text ?: "")
        then:
        result.status == 200
        result.json != null
        blob.contains("United States") || blob.contains("USA")
    }

    def "HTML screen without transition returns 400"() {
        when:
        def result = LlmTool.request().execute([method: "GET", path: "/apps/system/dashboard"], ec)
        then:
        result.status == 400
        result.text == RequestTool.HTML_ERROR
        result.text.contains("actions/{formListName}")
        result.text.contains("not /actions/{transition}")
        result.text.contains("not /qapps")
    }

    def "GET FindAsset form-list jsonPath returns JSON array"() {
        when:
        def result = LlmTool.request().execute(
                [method: "GET", path: "/apps/marble/Asset/Asset/FindAsset/actions/ListAssets",
                 query: [productId: "10297"]], ec)
        then:
        result.status == 200
        result.json instanceof List
        result.text == null || !result.text.toString().toLowerCase().contains("<html")
    }

    def "POST rest create as non-admin is 403 and does not write"() {
        given:
        String geoId = "LLM_DENY_" + System.currentTimeMillis()
        boolean disabled = ec.artifactExecution.disableAuthz()
        boolean began = ec.transaction.begin(null)
        try {
            def existing = ec.entity.find("moqui.security.UserAccount").condition("username", "llm.noauth").one()
            if (existing == null) {
                ec.entity.makeValue("moqui.security.UserAccount")
                        .set("userId", "LLMNOAUTH")
                        .set("username", "llm.noauth")
                        .set("userFullName", "LLM No Auth")
                        .set("currentPassword", "16ac58bbfa332c1c55bd98b53e60720bfa90d394")
                        .set("passwordHashType", "SHA")
                        .create()
            }
            if (began) ec.transaction.commit()
        } catch (Throwable t) {
            if (began) ec.transaction.rollback("create llm.noauth", t)
            throw t
        } finally {
            if (!disabled) ec.artifactExecution.enableAuthz()
        }
        ec.user.logoutUser()
        ((UserFacadeImpl) ec.user).internalLoginUser("llm.noauth")
        when:
        def result = LlmTool.request().execute(
                [method: "POST", path: "/rest/s1/moqui/basic/geos",
                 body: [geoId: geoId, geoName: "Deny Test", geoTypeEnumId: "GEOT_COUNTRY"]], ec)
        boolean stillDisabled = ec.artifactExecution.disableAuthz()
        def written = ec.entity.find("moqui.basic.Geo").condition("geoId", geoId).one()
        if (!stillDisabled) ec.artifactExecution.enableAuthz()
        then:
        result.status == 403
        written == null
    }

    def "restore ec.web after nested render"() {
        given:
        ExecutionContextImpl eci = (ExecutionContextImpl) ec
        WebFacade previous = new WebFacadeStub(eci.ecfi, [marker: "outer"], [:], "get")
        eci.setWebFacade(previous)
        when:
        def result = LlmTool.request().execute([method: "GET", path: "/apps/system/dashboard/actions"], ec)
        WebFacade after = ec.web
        then:
        result.status == 200
        after.is(previous)
        after.requestParameters.marker == "outer"
        cleanup:
        eci.clearWebFacade()
    }

    def "service tool does not switch user via authUsername authPassword"() {
        given:
        LlmTool tool = LlmTool.service("org.moqui.impl.LlmServices.clean#LlmData", "clean_llm")
        String before = ec.user.username
        when:
        tool.execute([daysToKeep: 36500, authUsername: "llm.noauth", authPassword: "moqui",
                authUserAccount: [username: "llm.noauth", currentPassword: "moqui"]], ec)
        then:
        ec.user.username == before
        !ServiceCallTool.sanitizeArguments([authUsername: "x", foo: 1]).containsKey("authUsername")
    }
}
