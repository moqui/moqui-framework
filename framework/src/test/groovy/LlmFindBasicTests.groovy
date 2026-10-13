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
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext
import org.moqui.impl.llm.tools.FindBasicTool
import org.moqui.impl.llm.LlmClientImpl
import org.moqui.impl.llm.LlmFacadeImpl
import org.moqui.impl.llm.LlmGateway
import org.moqui.impl.llm.SkillIndex
import org.moqui.llm.LlmClient
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
class LlmFindBasicTests extends Specification {
    @Shared ExecutionContext ec
    @Shared FindBasicTool tool

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        if (!ec.user.userId) ec.user.loginUser("john.doe", "moqui")
        tool = new FindBasicTool([new LlmFacadeImpl.BasicEntityAllow("moqui.basic", null)])
    }

    def "package moqui.basic does not match email or print"() {
        expect:
        new LlmFacadeImpl.BasicEntityAllow("moqui.basic", null).matches("moqui.basic", "Enumeration")
        !new LlmFacadeImpl.BasicEntityAllow("moqui.basic", null).matches("moqui.basic.email", "EmailServer")
        !new LlmFacadeImpl.BasicEntityAllow("moqui.basic", null).matches("moqui.basic.print", "NetworkPrinter")
        !new LlmFacadeImpl.BasicEntityAllow(null, null).matches("moqui.basic", "Enumeration")
        new LlmFacadeImpl.BasicEntityAllow("moqui.basic", "Geo").matches("moqui.basic", "Geo")
        !new LlmFacadeImpl.BasicEntityAllow("moqui.basic", "Geo").matches("moqui.basic", "Enumeration")
    }

    def "catalog lists moqui.basic and omits email and print"() {
        when:
        Map cat = (Map) tool.execute([:], ec)
        List<String> names = (List) cat.entities

        then:
        cat.usage
        names.contains("moqui.basic.Enumeration")
        names.contains("moqui.basic.StatusItem")
        names.contains("moqui.basic.Geo")
        !names.any { it.startsWith("moqui.basic.email.") }
        !names.any { it.startsWith("moqui.basic.print.") }
        !names.any { it.startsWith("mantle.") }
    }

    def "enumeration find returns key and text and rejects other entities"() {
        when:
        boolean denied = false
        try {
            ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "EnumerationType").disableAuthz().list()
        } catch (ArtifactAuthorizationException e) {
            denied = true
        }
        // A normal find with authz on, so a denied user proves the tool bypasses it.
        boolean authzDenied = false
        try {
            ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "EnumerationType").list()
        } catch (ArtifactAuthorizationException e) {
            authzDenied = true
        }
        Map hit = (Map) tool.execute([
                entityName: "moqui.basic.Enumeration", keyField: "enumId", text: '${description}',
                and: [enumTypeId: "DataSourceType"], limit: 5], ec)
        Map inn = (Map) tool.execute([
                entityName: "moqui.basic.Enumeration", keyField: "enumId", text: "description",
                and: [enumId: ["DST_PURCHASED_DATA", "DST_CUSTOMER_ENTRY"]]], ec)
        Map templ = (Map) tool.execute([
                entityName: "moqui.basic.StatusItem", keyField: "statusId", text: "StatusItemNameTemplate",
                and: [statusTypeId: "_NA_"]], ec)
        Map orNull = (Map) tool.execute([
                entityName: "moqui.basic.Enumeration", keyField: "enumId", text: '${description}',
                and: [enumTypeId: "DataSourceType", parentEnumId: "NO_SUCH"], orNull: ["parentEnumId"], limit: 5], ec)
        Map notEq = (Map) tool.execute([
                entityName: "moqui.basic.Enumeration", keyField: "enumId", text: '${description}',
                notEquals: [enumTypeId: "DataSourceType"], limit: 3], ec)
        Map dated = (Map) tool.execute([
                entityName: "moqui.basic.UomConversion", keyField: "uomConversionId", text: "uomConversionId",
                dateFilter: true, limit: 5], ec)
        Map one = (Map) tool.execute([
                entityName: "moqui.basic.Enumeration", keyField: "enumId", text: '${description}',
                and: [enumTypeId: "DataSourceType"], limit: 1], ec)
        Map email = (Map) tool.execute([
                entityName: "moqui.basic.email.EmailServer", keyField: "emailServerId", text: "description"], ec)
        Map badField = (Map) tool.execute([
                entityName: "moqui.basic.Enumeration", keyField: "enumId", text: '${description}',
                and: [notAField: "x"]], ec)

        then:
        !denied
        authzDenied
        hit.options
        hit.options.size() <= 5
        hit.options[0].keySet() == ["key", "text"] as Set
        inn.options.size() == 2
        templ.options.find { it.key == "_NA_" }?.text
        orNull.options
        notEq.options
        !notEq.error
        dated.options != null
        !dated.error
        one.truncated == true
        one.options.size() == 1
        email.error == "entity_not_allowed"
        badField.error == "unknown_field"
    }

    def "catalog inject uses the summary and otherwise the full skill text"() {
        when:
        String body = "Do the thing.\n\n## Widgets\nForm `x` `y`.\n- a text-line"
        SkillIndex.SkillDoc summarized = new SkillIndex.SkillDoc()
        summarized.name = "widget-tip-test"
        summarized.title = "Widget tip test"
        summarized.description = "test"
        summarized.risk = "confirm"
        summarized.summary = "  Short summary of the thing.  "
        summarized.body = body
        SkillIndex.SkillDoc full = new SkillIndex.SkillDoc()
        full.name = "widget-tip-full"
        full.title = "Widget tip full"
        full.description = "test"
        full.risk = "confirm"
        full.body = body
        String withSummary = SkillIndex.formatInject(ec, [summarized])
        String withoutSummary = SkillIndex.formatInject(ec, [full])

        then:
        SkillIndex.summaryText(summarized) == "Short summary of the thing."
        SkillIndex.summaryText(full) == body
        SkillIndex.summaryText(null) == null
        SkillIndex.activeDetailText(summarized) == body
        SkillIndex.activeDetailText(full) == null
        SkillIndex.activeDetailText(null) == null
        withSummary.contains("Short summary of the thing.")
        withSummary.contains("the skill summary when one is set")
        !withSummary.contains("Do the thing.")
        !withSummary.contains("Form `x` `y`.")
        !withSummary.contains("- a text-line")
        withoutSummary.contains("Do the thing.")
        withoutSummary.contains("\n## Widgets")
        withoutSummary.contains("Form `x` `y`.")
        withoutSummary.contains("- a text-line")
    }

    def "assist profile attaches find_basic and the default profile does not"() {
        when:
        LlmClient assist = ec.llm.getClient("assist")
        LlmClient plain = ec.llm.getClient("default")
        def profileField = LlmClientImpl.getDeclaredField("profile")
        profileField.accessible = true
        def toolsField = LlmClientImpl.getDeclaredField("tools")
        toolsField.accessible = true
        def assistProfile = profileField.get(assist)
        def plainProfile = profileField.get(plain)
        LlmGateway.attachServletTools(assist, assistProfile, ["find_basic", "browse"])
        LlmGateway.attachServletTools(plain, plainProfile, ["find_basic", "browse"])
        List assistNames = toolsField.get(assist).collect { it.name }
        List plainNames = toolsField.get(plain).collect { it.name }

        then:
        assistProfile.allowedBasicEntities.size() == 1
        assistNames.contains("find_basic")
        !plainNames.contains("find_basic")
    }
}
