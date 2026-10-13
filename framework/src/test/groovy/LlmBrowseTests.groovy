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
import org.moqui.entity.EntityValue
import org.moqui.impl.llm.LlmGateway
import org.moqui.impl.llm.ScreenSearchHints
import org.moqui.impl.llm.SessionFacts
import org.moqui.impl.llm.SkillIndex
import org.moqui.impl.llm.ToolResultTrim
import org.moqui.impl.llm.tools.BrowseTool
import org.moqui.impl.llm.tools.ScreenUseTool
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
        BrowseTool.requestScreenPath("/qapps/test/Asset") == "/apps/test/Asset"
        BrowseTool.requestScreenPath("/vapps/test/Asset") == "/apps/test/Asset"
        BrowseTool.requestScreenPath("/apps/test/Asset") == "/apps/test/Asset"
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
        String both = null
        List<String> dropWarns = WarnCapture.of(SkillIndex.class) {
            both = SkillIndex.formatInject(ec, [first, second])
        }

        then:
        one.contains("BEGIN-FIRST")
        one.contains("END-FIRST")
        one.length() > SkillIndex.INJECT_CHARS
        both.contains("BEGIN-FIRST")
        both.contains("END-FIRST")
        !both.contains("second-skill")
        !both.contains("END-SECOND")
        dropWarns.any { it.contains("dropped skill") && it.contains("second-skill") }
    }

    def "reference cards render beside procedures and are not sliced"() {
        given:
        SkillIndex.SkillDoc proc = new SkillIndex.SkillDoc()
        proc.name = "create-user-account"
        proc.title = "Create user"
        proc.risk = "confirm"
        proc.description = "Create a UserAccount"
        proc.summary = "Create the account with create#UserAccount."
        proc.body = "Call run_service create#UserAccount\n## Widgets\nwidget line"
        SkillIndex.SkillDoc card = new SkillIndex.SkillDoc()
        card.name = "marble-party-roles"
        card.kind = "reference"
        card.title = "Party roles"
        card.description = "How parties relate"
        String cardBody = "CARD-START " + ("c" * 500) + " CARD-END"
        card.body = cardBody + "\n## Widgets\nwidget hidden"

        when:
        String mixed = SkillIndex.formatInjectForDocs(ec, [card, proc], "create user account")
        String only = SkillIndex.formatInjectForDocs(ec, [card], "party roles")

        then:
        mixed.indexOf("create-user-account") < mixed.indexOf("marble-party-roles")
        mixed.contains("These are candidates")
        mixed.contains("Create the account with create#UserAccount.")
        !mixed.contains("widget line")
        mixed.contains("(reference card, not a procedure)")
        mixed.contains("CARD-END")
        mixed.contains(cardBody)
        mixed.contains("widget hidden")
        !mixed.contains("No procedure skill matched")
        only.contains("No procedure skill matched")
        only.contains("marble-party-roles")
        only.contains("CARD-END")
        !only.contains("No matching skill")
    }

    def "a long reference card is sent whole and logged"() {
        given:
        SkillIndex.SkillDoc card = new SkillIndex.SkillDoc()
        card.name = "marble-long-card"
        card.kind = "reference"
        card.title = "Long card"
        card.body = "LONG-START " + ("d" * SkillIndex.BODY_WARN_CHARS) + " LONG-END"

        when:
        String text = null
        List<String> warns = WarnCapture.of(SkillIndex.class) {
            text = SkillIndex.formatInjectForDocs(ec, [card], "long card")
        }

        then:
        text.contains("LONG-START")
        text.contains("LONG-END")
        warns.any { it.contains("marble-long-card") && it.contains("sent whole") }
    }

    def "a long lesson is injected whole"() {
        given:
        String stamp = Long.toString(System.currentTimeMillis())
        String skillName = "long-lesson-" + stamp
        String lessonBody = "LESSON-START " + ("q" * 500) + " LESSON-END"
        EntityValue sk = ec.entity.makeValue("moqui.llm.LlmSkill")
                .set("name", skillName)
                .set("title", "Long lesson")
                .set("body", "POST create")
                .set("riskId", "LskConfirm")
                .set("statusId", "LsksActive")
                .set("provenanceId", "LskpHuman")
                .set("version", 1)
        sk.setSequencedIdPrimary()
        sk.create()
        EntityValue lesson = ec.entity.makeValue("moqui.llm.LlmLesson")
                .set("skillId", sk.skillId)
                .set("title", "long failure")
                .set("body", lessonBody)
                .set("statusId", "LsksActive")
                .set("provenanceId", "LskpWorld")
        lesson.setSequencedIdPrimary()
        lesson.create()
        SkillIndex.SkillDoc doc = new SkillIndex.SkillDoc()
        doc.name = skillName
        doc.skillId = sk.skillId
        doc.risk = "confirm"
        doc.body = "POST create"

        when:
        String injected = SkillIndex.formatInject(ec, [doc])

        then:
        injected.contains("LESSON-START")
        injected.contains("LESSON-END")
        injected.contains(lessonBody)

        cleanup:
        boolean d = ec.artifactExecution.disableAuthz()
        boolean b = ec.transaction.begin(null)
        try {
            ec.entity.find("moqui.llm.LlmLesson").condition("skillId", sk.skillId).deleteAll()
            ec.entity.find("moqui.llm.LlmSkill").condition("skillId", sk.skillId).deleteAll()
            if (b) ec.transaction.commit()
        } catch (Throwable t) {
            if (b) ec.transaction.rollback("long lesson cleanup", t)
            throw t
        } finally {
            if (!d) ec.artifactExecution.enableAuthz()
        }
    }
}
