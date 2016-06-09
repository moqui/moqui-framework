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
package org.moqui.impl.webapp

import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.http.HttpSession
import javax.websocket.*
import javax.websocket.server.HandshakeRequest

/**
 * An abstract class for WebSocket Endpoint that does basic setup, including creating an ExecutionContext with the user
 * logged if they were logged in for the corresponding HttpSession (based on the WebSocket HandshakeRequest, ie the
 * HTTP upgrade request, tied to an existing HttpSession).
 *
 * The main method to implement is the onMessage(String) method.
 *
 * If you override the onOpen() method call the super method first.
 * If you override the onClose() method call the super method last (will clear out all internal fields).
 */
abstract class MoquiAbstractEndpoint extends Endpoint implements MessageHandler.Whole<String> {
    private final static Logger logger = LoggerFactory.getLogger(MoquiAbstractEndpoint.class)

    private ExecutionContextFactoryImpl ecfi = null
    private ExecutionContextImpl eci = null
    private Session session = null
    private HttpSession httpSession = null
    private HandshakeRequest handshakeRequest = null

    MoquiAbstractEndpoint() { super() }

    ExecutionContext getEc() { return eci }
    ExecutionContextFactory getEcf() { return ecfi }
    HttpSession getHttpSession() { return httpSession }
    Session getSession() { return session }

    @Override
    void onOpen(Session session, EndpointConfig config) {
        this.session = session
        ecfi = (ExecutionContextFactoryImpl) config.userProperties.get("executionContextFactory")
        handshakeRequest = (HandshakeRequest) config.userProperties.get("handshakeRequest")
        httpSession = handshakeRequest != null ? (HttpSession) handshakeRequest.getHttpSession() : (HttpSession) config.userProperties.get("httpSession")
        eci = ecfi.getEci()
        if (handshakeRequest != null) {
            eci.userFacade.initFromHandshakeRequest(handshakeRequest)
        } else if (httpSession != null) {
            eci.userFacade.initFromHttpSession(httpSession)
        } else {
            logger.warn("No HandshakeRequest or HttpSession found opening WebSocket Session ${session.id}, not logging in user")
        }

        Long timeout = (Long) config.userProperties.get("maxIdleTimeout")
        if (timeout != null && session.getMaxIdleTimeout() > 0 && session.getMaxIdleTimeout() < timeout)
            session.setMaxIdleTimeout(timeout)

        session.addMessageHandler(this)

        logger.info("Opened WebSocket Session ${session.getId()}, userId: ${eci.user.userId}, username: ${eci.user.username}, tenant: ${eci.tenantId}, timeout: ${session.getMaxIdleTimeout()}ms")

        // TODO: comment these out once basic stuff in place
        logger.info("Opened WebSocket Session ${session.getId()}, parameters: ${session.getRequestParameterMap()}, username: ${session.getUserPrincipal()?.getName()}, config props: ${config.userProperties}")
        for (String attrName in httpSession.getAttributeNames())
            logger.info("WebSocket Session ${session.getId()}, session attribute: ${attrName}=${httpSession.getAttribute(attrName)}")
    }

    @Override
    abstract void onMessage(String message)

    @Override
    void onClose(Session session, CloseReason closeReason) {
        this.session = null
        this.httpSession = null
        this.handshakeRequest = null
        this.ecfi = null
        if (eci != null) {
            eci.destroy()
            eci = null
        }
        logger.info("Closed WebSocket Session ${session.getId()}: ${closeReason.reasonPhrase}")
    }

    @Override
    void onError(Session session, Throwable thr) {
        logger.warn("Error in WebSocket Session ${session.getId()}", thr)
    }
}
