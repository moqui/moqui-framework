/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 */
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.impl.webapp.NotificationEndpoint
import org.moqui.impl.webapp.NotificationWebSocketListener
import spock.lang.Shared
import spock.lang.Specification

import jakarta.websocket.server.HandshakeRequest

class SecurityHandshakeTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        SecurityTestSupport.ensureUsers(ec)
    }
    def cleanupSpec() { SecurityTestSupport.logout(ec); ec.destroy() }
    def setup() { SecurityTestSupport.logout(ec); ec.message.clearAll() }

    def "handshake api_key header logs in, query string does not"() {
        when:
        SecurityTestSupport.login(ec, SecurityTestSupport.ALL_USERNAME, SecurityTestSupport.ALL_PASSWORD)
        String key = ec.user.getLoginKey(1)
        SecurityTestSupport.logout(ec)
        HandshakeRequest headerReq = Stub(HandshakeRequest) {
            getHttpSession() >> { throw new RuntimeException("no session") }
            getHeaders() >> [api_key: [key]]
            getParameterMap() >> [:]
        }
        SecurityTestSupport.eci(ec).userFacade.initFromHandshakeRequest(headerReq)
        String fromHeader = ec.user.username
        SecurityTestSupport.logout(ec)
        HandshakeRequest queryReq = Stub(HandshakeRequest) {
            getHttpSession() >> { throw new RuntimeException("no session") }
            getHeaders() >> [:]
            getParameterMap() >> [api_key: [key]]
        }
        SecurityTestSupport.eci(ec).userFacade.initFromHandshakeRequest(queryReq)
        String fromQuery = ec.user.username
        then:
        key
        fromHeader == SecurityTestSupport.ALL_USERNAME
        fromQuery == null
        cleanup:
        SecurityTestSupport.logout(ec)
    }

    def "handshake authUsername in query string does not log in"() {
        when:
        HandshakeRequest queryReq = Stub(HandshakeRequest) {
            getHttpSession() >> { throw new RuntimeException("no session") }
            getHeaders() >> [:]
            getParameterMap() >> [authUsername: [SecurityTestSupport.ALL_USERNAME],
                                  authPassword: [SecurityTestSupport.ALL_PASSWORD]]
        }
        SecurityTestSupport.eci(ec).userFacade.initFromHandshakeRequest(queryReq)
        then:
        ec.user.username == null
        cleanup:
        SecurityTestSupport.logout(ec)
    }

    def "anonymous user does not have GROOVY_SHELL_WEB"() {
        expect:
        !ec.user.userId
        !ec.user.hasPermission("GROOVY_SHELL_WEB")
    }

    def "notification listener ignores endpoints with no userId"() {
        // registerEndpoint is void, so Spock would skip it as a condition; assert the endpoint was not stored.
        when:
        def listener = new NotificationWebSocketListener()
        def ep = new NotificationEndpoint()
        listener.registerEndpoint(ep)
        then:
        ep.userId == null
        listener.@endpointsByUser.isEmpty()
    }

    def "notification listener registers an endpoint that has a userId"() {
        // positive control for the case above; without it, "never registers anything" would pass
        given:
        def listener = new NotificationWebSocketListener()
        def ep = new NotificationEndpoint()
        ep.@userId = SecurityTestSupport.ALL_USER_ID
        ep.@session = Stub(jakarta.websocket.Session) { getId() >> "sec-test-session" }
        when:
        listener.registerEndpoint(ep)
        then:
        listener.@endpointsByUser.size() == 1
        listener.@endpointsByUser.get(SecurityTestSupport.ALL_USER_ID)?.get("sec-test-session")?.is(ep)
        when:
        listener.deregisterEndpoint(ep)
        then:
        listener.@endpointsByUser.isEmpty()
    }
}
