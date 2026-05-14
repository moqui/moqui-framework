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
import spock.lang.*

class AiFacadeTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
    }

    def cleanupSpec() {
        ec.destroy()
    }

    def "generate returns non-empty String"() {
        given:
        def messages = [[role: "user", content: "Say hello in one word."]]

        when:
        String response = ec.ai.getDefault().generate(messages)
        ec.logger.info("AiFacadeTests generate response: ${response}")

        then:
        response != null
        !response.isEmpty()
    }

    def "generateStructured returns Map with expected keys"() {
        given:
        def messages = [[role: "user", content: "Return a greeting with a single word in a field called 'word'."]]
        def schema = [word: [type: "string"]]

        when:
        Map result = ec.ai.getDefault().generateStructured(messages, schema)
        ec.logger.info("AiFacadeTests generateStructured result: ${result}")

        then:
        result != null
        result instanceof Map
        result.containsKey("word")
        result.word != null
        !((String) result.word).isEmpty()
    }
}
