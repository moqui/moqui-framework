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


import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.service.ServiceCallback
import spock.lang.*

import org.moqui.context.ExecutionContext
import org.moqui.Moqui

class ServiceFacadeTests extends Specification {
    @Shared
    ExecutionContext ec

    def setupSpec() {
        // init the framework, get the ec
        ec = Moqui.getExecutionContext()
    }

    def cleanupSpec() {
        ec.destroy()
    }

    def "register callback concurrently"() {
        def sfi = (ServiceFacadeImpl)ec.service
        ServiceCallback scb = Mock(ServiceCallback)

        when:
        ConcurrentExecution.executeConcurrently(10, { sfi.registerCallback("foo", scb) })
        sfi.callRegisteredCallbacks("foo", null, null)

        then:
        10 * scb.receiveEvent(null, null)
    }
}
