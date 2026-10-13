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

import org.moqui.impl.llm.tools.FindSkillTool
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

    def "a reference card is marked by type, not by name"() {
        expect:
        SkillIndex.isReference(SkillIndex.parseMarkdown("---\nname: acme-status-meanings\ntype: reference\n---\nbody", null))
        SkillIndex.isReference(SkillIndex.parseMarkdown("---\nname: acme-status-meanings\nkind: Reference\n---\nbody", null))
        // The old rule treated every name starting with marble- as a card
        !SkillIndex.isReference(SkillIndex.parseMarkdown("---\nname: marble-create-thing\n---\nbody", null))
        !SkillIndex.isReference(SkillIndex.parseMarkdown("---\nname: create-thing\ntype: procedure\n---\nbody", null))
        !SkillIndex.isReference(null)
    }

    def "a database row is a reference card by its kind field"() {
        given:
        def doc = new SkillIndex.SkillDoc()
        doc.name = "marble-anything"
        expect:
        !SkillIndex.isReference(doc)
        when:
        doc.kind = "reference"
        then:
        SkillIndex.isReference(doc)
    }

    def "score prefers name match over body"() {
        given:
        def named = SkillIndex.parseMarkdown("---\nname: place-sales-order\ndescription: order\n---\nbody", null)
        def other = SkillIndex.parseMarkdown("---\nname: create-user-account\ndescription: user\n---\nplace sales order mentioned", null)

        expect:
        SkillIndex.score(named, "place sales order") > SkillIndex.score(other, "place sales order")
    }

    def "show open orders does not treat my-open-work as the find screen"() {
        given:
        def dash = SkillIndex.parseMarkdown("""---
name: my-open-work
title: My open work
description: Counts and a table of open orders, shipments, and invoices
---
GET each Find form-list
""", null)
        def find = SkillIndex.parseMarkdown("""---
name: find-order
title: Find order
description: Search sales orders
---
Open Find Order
""", null)

        expect:
        !SkillIndex.strongMatch(dash, "show open orders")
        !SkillIndex.includeProcedure(dash, "show open orders")
        SkillIndex.strongMatch(find, "show open orders")
        SkillIndex.includeProcedure(find, "show open orders")
        SkillIndex.score(find, "show open orders") > SkillIndex.score(dash, "show open orders")
        SkillIndex.strongMatch(dash, "my open work")
        SkillIndex.score(dash, "my open work") >= 100
        SkillIndex.includeProcedure(dash, "")
        SkillIndex.includeProcedure(dash, null)
        SkillIndex.score(dash, "") == 1

        when:
        def weak = FindSkillTool.toMap(null, dash, false, SkillIndex.scoreDetail(dash, "show open orders"), true)
        def strong = FindSkillTool.toMap(null, find, false, SkillIndex.scoreDetail(find, "show open orders"), true)
        def selected = FindSkillTool.toMap(null, dash, true, SkillIndex.scoreDetail(dash, "my open work"), false)

        then:
        weak.match == "weak"
        weak.score > 0
        weak.name == "my-open-work"
        weak.description.contains("orders")
        !weak.containsKey("body")
        strong.match == "strong"
        strong.body.contains("Find Order")
        selected.body.contains("GET each Find form-list")
        selected.match == "strong"
        !new FindSkillTool().description.toLowerCase().contains("before browse")
        new FindSkillTool().description.contains("screen_use")
    }

}
