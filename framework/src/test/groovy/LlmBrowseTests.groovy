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
import org.moqui.impl.llm.ScreenUseTool
import org.moqui.impl.llm.ScreenSearchHints
import org.moqui.impl.llm.SessionFacts
import org.moqui.impl.llm.SkillIndex
import org.moqui.impl.llm.ToolResultTrim
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
        hit.method != null
        !hit.containsKey("inParameters")
        !hit.containsKey("formFields")
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
        trans.inParameters.contains("username")
        trans.formFields.contains("username")
        trans.formFields.contains("newPassword")
        ((List) out.children).find { it.name == "createUserAccount" } == null
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
        hit.serviceName.contains("create#UserAccount")
        !hit.containsKey("formFields")
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
        out.screenPath == "/qapps/system/Security/UserAccount/UserAccountList"
        out.hint.toString().contains("not a catalog")
        ((List) out.children).isEmpty()
    }

    def "actions path is a transition leaf and tells the model to browse the parent screen"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/qapps/system/Security/UserAccount/UserAccountList/actions", match: "Item|Part"], ec)

        then:
        out.kind == "transition"
        ((List) out.children).isEmpty()
        out.screenPath == "/qapps/system/Security/UserAccount/UserAccountList"
        out.hint.toString().contains("Do not retry match")
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
        !hit.containsKey("fields")
        !hit.containsKey("findFields")
        actions == null
    }

    def "q find order ranks Find Order and a limited user does not see it"() {
        when:
        Map out = (Map) new BrowseTool().execute([q: "find order"], ec)
        List hits = (List) out.hits
        Map top = hits ? (Map) hits[0] : null

        then:
        out.error == null
        top != null
        hits.size() <= 12
        top.title == "Find Order"
        top.path.toString().startsWith("/qapps/")
        top.path.toString().endsWith("/FindOrder")
        hits.every { !it.path.toString().startsWith("/apps/") && !it.path.toString().startsWith("/vapps/") }
        ((List) top.forms).any { Map f -> f.type == "form-list" && f.name }
        ((List) top.forms).every { Map f -> !f.containsKey("fields") && !f.containsKey("fieldTitles") }
        !top.containsKey("transitions")

        when:
        Map detail = (Map) new BrowseTool().execute([path: top.path, detail: true], ec)
        // CreateSalesOrder is a form-single and also titles a field Customer. The find list is OrderList.
        Map orderForm = ((List) detail.leaf.forms).find { Map f ->
            f.type == "form-list" && ((List) f.fieldTitles)?.any {
                String.valueOf(it).toLowerCase().contains("customer")
            }
        }

        then:
        orderForm != null
        orderForm.name == "OrderList"
        ((List) orderForm.fields).contains("customerPartyId")
        ((List) detail.children).find { it.kind == "form-list" || it.kind == "transition" } == null

        when:
        ec.user.logoutUser()
        assert ec.user.loginUser("example.ltd", "moqui")
        Map limited = (Map) new BrowseTool().execute([q: "find order"], ec)
        List limitedHits = limited.hits instanceof List ? (List) limited.hits : []

        then:
        !limitedHits.any { Map h -> String.valueOf(h.path).contains("FindOrder") }

        cleanup:
        ec.user.logoutUser()
        ec.user.loginUser("john.doe", "moqui")
    }

    def "ArtifactHitBins form-list exposes requireParameters and AT_SERVICE option"() {
        when:
        Map summary = (Map) new BrowseTool().execute(
                [path: "/qapps/system/ArtifactHitBins"], ec)
        Map listed = ((List) summary.children).find { it.name == "ArtifactHitBins" && it.kind == "form-list" }
        Map out = (Map) new BrowseTool().execute(
                [path: "/qapps/system/ArtifactHitBins", detail: true], ec)
        Map hit = ((List) out.leaf.forms).find { it.name == "ArtifactHitBins" }

        then:
        listed != null
        listed.method == "GET"
        listed.jsonPath.toString() == "/apps/system/ArtifactHitBins/actions/ArtifactHitBins"
        !listed.containsKey("findFields")
        hit != null
        hit.jsonPath.toString() == "/apps/system/ArtifactHitBins/actions/ArtifactHitBins"
        hit.requireParameters == true
        hit.defaultOrderBy.toString().contains("binStartDateTime")
        hit.jsonShape.toString().contains("rows")
        def types = hit.findFields.find { it.name == "artifactType" }
        types != null
        types.widget == "drop-down"
        types.options.contains("AT_SERVICE")
        types.options.contains("AT_XML_SCREEN")
        def binStart = hit.findFields.find { it.name == "binStartDateTime" }
        binStart != null
        binStart.widget == "date-period"
        binStart.params.contains("binStartDateTime_period")
        ((List) out.children).find { it.kind == "form-list" } == null
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
        form.fields.contains("productId")
        form.fields.contains("quantityOnHandTotal") || form.fields.contains("availableToPromiseTotal")
        actions == null
        ((List) out.children).find { it.kind == "form-list" } == null
    }

    def "match quantityOnHand finds ListAssets form-list under Asset"() {
        when:
        Map out = (Map) new BrowseTool().execute(
                [path: "/qapps/marble/Asset", match: "quantityOnHand", depth: 3], ec)
        Map hit = ((List) out.children).find { it.name == "ListAssets" && it.kind == "form-list" }

        then:
        hit != null
        hit.jsonPath.toString() == "/apps/marble/Asset/Asset/FindAsset/actions/ListAssets"
        !hit.containsKey("fields")
    }

    def "search hints name QuickSearch and mantle Search actions this user can view"() {
        when:
        String text = ScreenSearchHints.text(ec)
        List mounts = ScreenSearchHints.find(ec)
        List lines = text.split("\n") as List
        List searchMounts = mounts.findAll { bareSearchPath(it.actionsPath) }
        String searchOnly = ScreenSearchHints.render([
                new ScreenSearchHints.Mount(false, false, "/apps/hm/Search", "/apps/hm/Search/actions")])

        then:
        text != null
        text.contains("/apps/marble/QuickSearch/actions")
        text.contains("/apps/hm/Search/actions")
        text.contains("/apps/hmadmin/Search/actions")
        text.contains("/apps/PopcAdmin/Search/actions")
        text.contains("/apps/PopcAdmin/QuickSearch/actions")
        text.contains("queryString")
        text.contains("documentList")
        text.contains("orderHeader")
        !text.contains("lookup#ById")
        !text.contains("/Catalog/Search")
        mounts.every { it.actionsPath.startsWith("/apps/") && it.actionsPath.endsWith("/actions") }
        mounts.every { !it.screenPath.contains("/Catalog/Search") }
        searchMounts*.actionsPath.containsAll([
                "/apps/hm/Search/actions",
                "/apps/hmadmin/Search/actions",
                "/apps/PopcAdmin/Search/actions"])
        searchMounts.every { m ->
            String line = lines.find { it.contains(m.actionsPath) }
            line != null && line.contains("queryString") && !line.contains("lookupId") && line.startsWith("- Search:")
        }
        lines.find { it.contains("/apps/PopcAdmin/QuickLookup/actions") }?.contains("lookupId")

        searchOnly.contains("/apps/hm/Search/actions")
        searchOnly.contains("queryString")
        searchOnly.contains("documentList")
        !searchOnly.contains("lookupId")
        !searchOnly.contains("orderHeader")

        ScreenSearchHints.isMantleSearchActions(ec, ["apps", "hm", "Search", "actions"])
        ScreenSearchHints.isMantleSearchActions(ec, ["apps", "hmadmin", "Search", "actions"])
        ScreenSearchHints.isMantleSearchActions(ec, ["apps", "PopcAdmin", "Search", "actions"])
        !ScreenSearchHints.isMantleSearchActions(ec, ["apps", "PopcAdmin", "Catalog", "Search", "actions"])
        !ScreenSearchHints.isMantleSearchActions(ec, ["apps", "marble", "QuickSearch", "actions"])
        !ScreenSearchHints.isMantleSearchActions(ec, ["apps", "PopcAdmin", "Search", "actions", "SearchResults"])
        !ToolResultTrim.isSearchActionsPath(["apps", "hm", "Search", "actions"])
        !ToolResultTrim.isSearchActionsPath(["apps", "PopcAdmin", "Catalog", "Search", "actions"])
        ToolResultTrim.isSearchActionsPath(["apps", "marble", "QuickSearch", "actions"])
        ToolResultTrim.isSearchActionsPath(["apps", "PopcAdmin", "QuickLookup", "actions"])
    }

    private static boolean bareSearchPath(String path) {
        if (path == null) return false
        String[] parts = path.split("/")
        return parts.length >= 2 && parts[parts.length - 2] == "Search"
    }

    def "session facts are this user and do not list permissions"() {
        when:
        String text = SessionFacts.text(ec)

        then:
        text.contains("userId=")
        text.contains("partyId=")
        !text.toLowerCase().contains("permission")
        !text.contains("ADMIN")
    }

    def "AssistSystem documents screen-first ladder and find-form jsonPath"() {
        when:
        def f = new File("../runtime/base-component/webroot/prompt/AssistSystem.ftl")
        String text = f.exists() ? f.text : ""

        then:
        f.exists()
        text.contains("/actions/{formName}")
        text.contains("Catalog search order")
        text.contains("/rest/s1")
        text.contains("/rest/e1")
        text.contains("Find forms")
        text.contains("QuickSearch, Search, or QuickLookup")
        text.contains("kind=openui")
        text.contains("adjust: true")
        text.contains("Do not browse")
        text.contains("`kind` `transition`")
        text.contains("YYYY-MM-DD HH:mm")
        text.contains("writeMode")
        text.contains("*_display")
        text.contains("requireParameters")
        text.contains("AT_SERVICE")
        text.indexOf("/qapps") < text.indexOf("/rest/s1")
    }

    def "AssistSystem template includes generated OpenUI Lang prompt"() {
        when:
        String sys = LlmGateway.renderPrompt(ec, "component://webroot/prompt/AssistSystem.ftl", null)
        then:
        sys != null
        sys.contains("root = Stack")
        sys.contains("Lookup(")
        sys.contains("Link(")
        sys.contains("BarChart(")
        sys.contains("MarkDownRenderer(")
        sys.contains("Mermaid(")
        sys.contains("DatePeriod(")
        sys.contains("kind=openui")
        sys.contains("YYYY-MM-DD HH:mm")
        sys.contains("writeMode")
        !sys.contains("<#include")
        !sys.contains("kind=vue-sfc")
        !sys.contains("module.exports")
    }

    def "AssistSystem includes VueSfc prompt only when allowVueSfc"() {
        when:
        String off = LlmGateway.renderPrompt(ec, "component://webroot/prompt/AssistSystem.ftl", [allowVueSfc: false])
        String on = LlmGateway.renderPrompt(ec, "component://webroot/prompt/AssistSystem.ftl", [allowVueSfc: true])
        String openUi = new File("../runtime/base-component/webroot/prompt/OpenUiLang.prompt.txt").text

        then:
        off != null && !off.contains("kind=vue-sfc")
        on.contains("kind=vue-sfc")
        on.contains("module.exports")
        on.contains("moquiSessionToken")
        !openUi.contains("vue-sfc")
    }

    def "screen_use navigate rejects a transition, another origin, and a screen the user cannot view"() {
        when:
        ScreenUseTool tool = new ScreenUseTool()
        Map trans = tool.enrichForClient([action: "navigate", path: "/qapps/marble/Order/FindOrder/actions"], ec)
        Map off = tool.enrichForClient([action: "navigate", path: "https://evil.example/qapps/marble"], ec)
        Map proto = tool.enrichForClient([action: "navigate", path: "//evil.example/qapps"], ec)
        Map ok = tool.enrichForClient([action: "navigate", path: "/apps/marble/Order/FindOrder",
                parameters: [customerPartyId: "Cust", notAField: "no"],
                fields: [customerPartyId: "Cust"]], ec)

        then:
        trans.error != null
        off.error == "bad_path"
        proto.error == "bad_path"
        ok.error == null
        ok.action == "navigate"
        ok.path == "/qapps/marble/Order/FindOrder"
        ok.parameters.customerPartyId == "Cust"
        ok.parameters.notAField == null
        ((List) ok.ignored).contains("notAField")
        ok.fields.customerPartyId == "Cust"

        when:
        ec.user.logoutUser()
        assert ec.user.loginUser("example.ltd", "moqui")
        Map denied = tool.enrichForClient([action: "navigate", path: "/qapps/marble/Order/FindOrder"], ec)

        then:
        denied.error != null

        cleanup:
        ec.user.logoutUser()
        ec.user.loginUser("john.doe", "moqui")
    }

    def "Assist prompt navigates with screen_use before write_ui and links stay in this window"() {
        when:
        String sys = LlmGateway.renderPrompt(ec, "component://webroot/prompt/AssistSystem.ftl", null)
        String openUi = new File("../runtime/base-component/webroot/prompt/OpenUiLang.prompt.txt").text

        then:
        sys != null
        sys.contains("screen_use")
        sys.indexOf("screen_use") < sys.indexOf("write_ui")
        sys.contains("navigate")
        !sys.toLowerCase().contains("new tab")
        !sys.contains("Follow a matching skill before browse")
        !sys.contains("before `browse`")
        !sys.contains("Prefer `kind=openui`")
        int findAt = sys.indexOf("## Find forms")
        int afterFind = sys.indexOf("## When submitted", findAt)
        String findForms = sys.substring(findAt, afterFind)
        findForms.contains("screen_use")
        findForms.contains("submit_find")
        !findForms.contains("kind=openui")
        openUi.contains("this window")
        !openUi.toLowerCase().contains("new tab")
    }

    def "OpenUI spec component names appear in OpenUiLang prompt"() {
        when:
        File specFile = new File("../runtime/base-component/webroot/screen/webroot/js/assist/AssistOpenUiLibrary.spec.json")
        File promptFile = new File("../runtime/base-component/webroot/prompt/OpenUiLang.prompt.txt")
        def spec = new groovy.json.JsonSlurper().parse(specFile)
        def names = spec['$defs'].keySet()
        String prompt = promptFile.text
        def missing = names.findAll { !prompt.contains(it + "(") }

        then:
        specFile.exists()
        promptFile.exists()
        names.contains("Link")
        names.contains("BarChart")
        names.contains("MarkDownRenderer")
        missing.isEmpty()
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

    def "inject keeps one oversized skill whole and drops a later skill"() {
        given:
        SkillIndex.SkillDoc first = new SkillIndex.SkillDoc()
        first.name = "first-skill"
        first.title = "First"
        first.risk = "confirm"
        first.body = "BEGIN-FIRST " + ("a" * 40000) + " END-FIRST"
        SkillIndex.SkillDoc second = new SkillIndex.SkillDoc()
        second.name = "second-skill"
        second.title = "Second"
        second.risk = "confirm"
        second.body = "BEGIN-SECOND " + ("b" * 20000) + " END-SECOND"

        when:
        String one = SkillIndex.formatInject(ec, [first])
        String both = SkillIndex.formatInject(ec, [first, second])

        then:
        one.contains("BEGIN-FIRST")
        one.contains("END-FIRST")
        one.length() > SkillIndex.INJECT_CHARS
        both.contains("BEGIN-FIRST")
        both.contains("END-FIRST")
        !both.contains("second-skill")
        !both.contains("END-SECOND")
    }
}
