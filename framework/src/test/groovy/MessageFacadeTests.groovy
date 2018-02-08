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

import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityValue
import java.sql.Timestamp

class MessageFacadeTests extends Specification {
    @Shared
    ExecutionContext ec

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
    }

    def cleanupSpec() {
        ec.destroy()
    }

    def "add non-error message"() {
        when:
        String testMessage = "This is a test message"
        ec.message.addMessage(testMessage)

        then:
        ec.message.messages.contains(testMessage)
        ec.message.messagesString.contains(testMessage)
        !ec.message.hasError()

        cleanup:
        ec.message.clearAll()
    }

    def "add public message"() {
        when:
        String testMessage = "This is a test public message"
        ec.message.addPublic(testMessage, 'warning')

        then:
        ec.message.messages.contains(testMessage)
        ec.message.messageInfos[0].typeString == 'warning'
        ec.message.messagesString.contains(testMessage)
        ec.message.publicMessages.contains(testMessage)
        ec.message.publicMessageInfos[0].typeString == 'warning'
        !ec.message.hasError()

        cleanup:
        ec.message.clearAll()
    }

    def "add error message"() {
        when:
        String testMessage = "This is a test error message"
        ec.message.addError(testMessage)

        then:
        ec.message.errors.contains(testMessage)
        ec.message.errorsString.contains(testMessage)
        ec.message.hasError()

        cleanup:
        ec.message.clearErrors()
    }

    def "add validation error"() {
        when:
        String errorMessage = "This is a test validation error"
        ec.message.addValidationError("form", "field", "service", errorMessage, new Exception("validation error location"))

        then:
        ec.message.validationErrors[0].message == errorMessage
        ec.message.validationErrors[0].form == "form"
        ec.message.validationErrors[0].field == "field"
        ec.message.validationErrors[0].serviceName == "service"
        ec.message.errorsString.contains(errorMessage)
        ec.message.hasError()

        cleanup:
        ec.message.clearErrors()
    }
}
