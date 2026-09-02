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

import org.moqui.impl.llm.SkillIndex
import spock.lang.Specification

class LlmSkillTests extends Specification {
    def "parseMarkdown reads YAML front matter"() {
        when:
        def doc = SkillIndex.parseMarkdown("""---
name: create-user-account
description: Create a UserAccount
risk: confirm
---
# Steps
Call run_service
""", "component://tools/skill/create-user-account.md")

        then:
        doc.name == "create-user-account"
        doc.description == "Create a UserAccount"
        doc.risk == "confirm"
        doc.body.contains("Call run_service")
        doc.sourceLocation.contains("create-user-account.md")
    }

    def "score prefers name match over body"() {
        given:
        def named = SkillIndex.parseMarkdown("---\nname: place-sales-order\ndescription: order\n---\nbody", null)
        def other = SkillIndex.parseMarkdown("---\nname: create-user-account\ndescription: user\n---\nplace sales order mentioned", null)

        expect:
        SkillIndex.score(named, "place sales order") > SkillIndex.score(other, "place sales order")
    }

    def "SkillInject prompt tells the agent to enter_sim on miss"() {
        when:
        def f = new File("../runtime/base-component/tools/prompt/SkillInject.ftl")
        then:
        f.exists()
        f.text.contains("enter_sim")
    }

    def "shipped create-user-account skill file parses"() {
        when:
        def f = new File("../runtime/base-component/tools/skill/create-user-account.md")
        def doc = SkillIndex.parseMarkdown(f.text, f.path)

        then:
        f.exists()
        doc.name == "create-user-account"
        doc.body.contains("create#UserAccount")
    }
}
