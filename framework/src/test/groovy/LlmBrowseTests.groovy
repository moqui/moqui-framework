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
import org.moqui.impl.llm.BrowseTool
import org.moqui.impl.llm.LlmGateway
import org.moqui.impl.llm.SkillIndex
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification

import java.util.regex.Pattern

@IgnoreIf({
    String runtime = System.getProperty("moqui.runtime") ?: "../runtime"
    String conf = System.getProperty("moqui.conf") ?: "conf/MoquiDevConf.xml"
    File direct = new File(conf)
    File nested = new File(runtime, conf.startsWith("conf/") ? conf : "conf/" + new File(conf).name)
    !direct.exists() && !nested.exists() && !new File(runtime, "conf/MoquiDevConf.xml").exists()
})
class LlmBrowseTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
    }
    def cleanupSpec() {
        ec.destroy()
    }
    def setup() {
        if (!ec.user.userId) assert ec.user.loginUser("john.doe", "moqui")
    }

    def "requestScreenPath maps qapps and vapps to apps"() {
        expect:
        BrowseTool.requestScreenPath("/qapps/marble/Asset") == "/apps/marble/Asset"
        BrowseTool.requestScreenPath("/vapps/marble/Asset") == "/apps/marble/Asset"
        BrowseTool.requestScreenPath("/apps/marble/Asset") == "/apps/marble/Asset"
        BrowseTool.requestScreenPath("/qapps") == "/apps"
    }

    def "matches searches serviceName and parameter strings"() {
        given:
        Pattern pat = Pattern.compile("create#UserAccount", Pattern.CASE_INSENSITIVE)
        expect:
        BrowseTool.matches(pat, "createUserAccount", "org.moqui.impl.UserServices.create#UserAccount")
        !BrowseTool.matches(pat, "createUserAccount", "username")
        BrowseTool.matchesAny(pat, ["createUserAccount", "org.moqui.impl.UserServices.create#UserAccount"])
    }

    def "UserAccountList lists createUserAccount with single serviceName and form fields"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/qapps/system/Security/UserAccount/UserAccountList"], ec)
        Map hit = ((List) out.children).find { it.name == "createUserAccount" }

        then:
        hit != null
        hit.kind == "transition"
        hit.serviceName == "org.moqui.impl.UserServices.create#UserAccount"
        hit.inParameters.contains("username")
        hit.form == "CreateUserAccount"
        hit.formFields.contains("username")
        hit.formFields.contains("newPassword")
    }

    def "UserAccountList screen detail includes parameters forms and transitions"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/qapps/system/Security/UserAccount/UserAccountList", detail: true], ec)
        Map form = ((List) out.leaf.forms).find { it.name == "CreateUserAccount" }
        Map trans = ((List) out.leaf.transitions).find { it.name == "createUserAccount" }

        then:
        out.kind == "screens"
        form != null
        form.transition == "createUserAccount"
        form.fields.contains("emailAddress")
        trans != null
        trans.serviceName == "org.moqui.impl.UserServices.create#UserAccount"
    }

    def "match on service name finds createUserAccount under UserAccount at depth 1"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/qapps/system/Security/UserAccount", match: "create#UserAccount"], ec)
        Map hit = ((List) out.children).find { it.name == "createUserAccount" }

        then:
        hit != null
        hit.kind == "transition"
        hit.path.toString().endsWith("/createUserAccount")
        hit.serviceName.contains("create#UserAccount")
    }

    def "match on form field emailAddress finds createUserAccount"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/qapps/system/Security/UserAccount/UserAccountList", match: "emailAddress"], ec)
        Map hit = ((List) out.children).find { it.name == "createUserAccount" }

        then:
        hit != null
        hit.kind == "transition"
        hit.formFields.contains("emailAddress")
    }

    def "EntityDataEdit listing includes required screen parameter"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/qapps/tools/Entity/DataEdit/EntityDataEdit"], ec)

        then:
        out.parameters != null
        out.parameters.contains("selectedEntity")
    }

    def "entity browse includes createService for TestEntity"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/entities/moqui/test", match: "TestEntity"], ec)
        Map hit = ((List) out.children).find { it.name == "TestEntity" || it.entityName == "moqui.test.TestEntity" }

        then:
        hit != null
        hit.createService == "create#moqui.test.TestEntity"
        hit.httpPath == "/rest/e1/moqui.test.TestEntity"
        // createService is listed before httpPath so write-via-service is the first hint
        new ArrayList(hit.keySet()).indexOf("createService") < new ArrayList(hit.keySet()).indexOf("httpPath")
    }

    def "match create#TestEntity finds TestEntity"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/entities/moqui/test", match: "create#TestEntity"], ec)
        Map hit = ((List) out.children).find { it.name == "TestEntity" || it.entityName == "moqui.test.TestEntity" }

        then:
        hit != null
        hit.createService == "create#moqui.test.TestEntity"
    }

    def "match on entity field testMedium finds TestEntity"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/entities/moqui/test", match: "testMedium"], ec)
        Map hit = ((List) out.children).find { it.name == "TestEntity" || it.entityName == "moqui.test.TestEntity" }

        then:
        hit != null
        hit.entityName == "moqui.test.TestEntity"
    }

    def "transition detail includes service inParameters and form fields"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/qapps/system/Security/UserAccount/UserAccountList/createUserAccount", detail: true], ec)

        then:
        out.kind == "transition"
        out.leaf.serviceName == "org.moqui.impl.UserServices.create#UserAccount"
        out.leaf.inParameters.contains("username")
        out.leaf.inParameters.contains("newPassword")
        out.leaf.form == "CreateUserAccount"
        out.leaf.formFields.contains("username")
    }

    def "UserAccountList does not list the automatic actions transition"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/qapps/system/Security/UserAccount/UserAccountList"], ec)
        then:
        ((List) out.children).find { it.name == "actions" } == null
    }

    def "FindAsset lists ListAssets form-list with jsonPath"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/qapps/marble/Asset/Asset/FindAsset"], ec)
        Map hit = ((List) out.children).find { it.name == "ListAssets" }
        Map actions = ((List) out.children).find { it.name == "actions" }

        then:
        hit != null
        hit.kind == "form-list"
        hit.method == "GET"
        hit.jsonPath.toString() == "/apps/marble/Asset/Asset/FindAsset/actions/ListAssets"
        hit.path.toString() == "/apps/marble/Asset/Asset/FindAsset/actions/ListAssets"
        hit.entityName == "mantle.product.asset.AssetFindView"
        hit.fields.contains("productId")
        hit.fields.contains("quantityOnHandTotal") || hit.fields.contains("availableToPromiseTotal")
        actions == null
    }

    def "FindAsset detail forms include jsonPath and skip actions transition"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/qapps/marble/Asset/Asset/FindAsset", detail: true], ec)
        Map form = ((List) out.leaf.forms).find { it.name == "ListAssets" }
        Map actions = ((List) out.leaf.transitions).find { it.name == "actions" }

        then:
        form != null
        form.type == "form-list"
        form.jsonPath.toString() == "/apps/marble/Asset/Asset/FindAsset/actions/ListAssets"
        form.method == "GET"
        form.entityName == "mantle.product.asset.AssetFindView"
        actions == null
    }

    def "match quantityOnHand finds ListAssets form-list under Asset"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/qapps/marble/Asset", match: "quantityOnHand", depth: 3], ec)
        Map hit = ((List) out.children).find { it.name == "ListAssets" && it.kind == "form-list" }

        then:
        hit != null
        hit.jsonPath.toString() == "/apps/marble/Asset/Asset/FindAsset/actions/ListAssets"
    }

    def "AssistSystem documents screen-first ladder and find-form jsonPath"() {
        when:
        def f = new File("../runtime/base-component/tools/prompt/AssistSystem.ftl")
        String text = f.exists() ? f.text : ""

        then:
        f.exists()
        text.contains("/actions/{formName}")
        text.contains("Catalog search order")
        text.contains("/rest/s1")
        text.contains("/rest/e1")
        text.contains("Find forms")
        text.indexOf("/qapps") < text.indexOf("/rest/s1")
    }

    def "SkillInject FTL miss and hit render through ResourceFacade"() {
        when:
        String miss = SkillIndex.formatInject(ec, [])
        SkillIndex.SkillDoc doc = new SkillIndex.SkillDoc()
        doc.name = "create-user-account"
        doc.title = "Create user"
        doc.risk = "confirm"
        doc.description = "Create a UserAccount"
        doc.body = "Call run_service create#UserAccount"
        String hit = SkillIndex.formatInject(ec, [doc])
        String sim = LlmGateway.renderPrompt(ec, LlmGateway.PROMPT_SIM, [goal: "place order", successCriteria: "orderId"])

        then:
        miss.contains("enter_sim")
        hit.contains("create-user-account")
        hit.contains("create#UserAccount")
        !hit.contains("No matching skill")
        sim.contains("You are in sim")
        sim.contains("place order")
        sim.contains("orderId")
    }
}
