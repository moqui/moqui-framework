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

import org.moqui.impl.llm.ToolResultTrim
import spock.lang.Specification

class ToolResultTrimTests extends Specification {
    def "rows stay whole when a form-list result is shortened"() {
        given:
        List rows = (1..40).collect { [orderId: "O" + it, name: "n" * 20] }
        Map result = [status: 200, json: [rows: rows, totalCount: 40], headers: [huge: "h" * 5000]]

        when:
        Map trimmed = (Map) ToolResultTrim.limit(result, 4000)

        then:
        trimmed.truncated == true
        trimmed.size > 4000
        !trimmed.containsKey("preview")
        trimmed.json.rows instanceof List
        trimmed.json.rows.every { it instanceof Map && (it.orderId || it.listTruncated == true) }
        trimmed.json.rows.find { it.orderId } != null
    }

    def "priority keys survive a long skill body"() {
        given:
        Map result = [content: "x" * 9000, hint: "NOT selected", select: "huge-skill",
                      proposedSkillId: "SID1", proposedSkillName: "huge-skill"]

        when:
        Map trimmed = (Map) ToolResultTrim.limit(result, 8000)

        then:
        trimmed.truncated == true
        trimmed.hint == "NOT selected"
        trimmed.select == "huge-skill"
        trimmed.proposedSkillId == "SID1"
        trimmed.proposedSkillName == "huge-skill"
        !trimmed.containsKey("preview")
        trimmed.content.toString().length() < 9000
    }

    def "search actions projection keeps lookup maps and drops other context"() {
        given:
        Map ctx = [documentList: [[_id: "100", _type: "MantleOrder", documentTitle: "Acme"]],
                   orderHeader: [orderId: "100", statusId: "OrderPlaced"], orderId: "100",
                   sri: "drop-me", userOrgList: [[partyId: "ORG"]]]

        when:
        Map kept = (Map) ToolResultTrim.projectSearchActions(ctx)

        then:
        kept.documentList[0]._id == "100"
        kept.orderId == "100"
        kept.orderHeader.statusId == "OrderPlaced"
        !kept.containsKey("sri")
        !kept.containsKey("userOrgList")
    }

    def "error messages block a clean success"() {
        expect:
        ToolResultTrim.hasErrorMessages([messages: [errors: ["not available"]]])
        ToolResultTrim.hasErrorMessages([messages: [validationErrors: [[field: "quantity", message: "required"]]]])
        !ToolResultTrim.hasErrorMessages([messages: [messages: [[type: "warning", message: "check price"]]]])
        !ToolResultTrim.hasErrorMessages([ok: true])
    }
}
