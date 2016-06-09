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

import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.context.ExecutionContextImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.http.HttpSession
import javax.websocket.CloseReason
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.MessageHandler
import javax.websocket.Session
import javax.websocket.server.HandshakeRequest

class NotificationEndpoint extends Endpoint implements MessageHandler.Whole<String> {
    private final static Logger logger = LoggerFactory.getLogger(NotificationEndpoint.class)

    private ExecutionContextFactoryImpl ecfi = null
    private ExecutionContextImpl eci = null
    private Session session = null
    private HttpSession httpSession = null
    private HandshakeRequest handshakeRequest = null

    NotificationEndpoint() { super() }

    @Override
    void onOpen(Session session, EndpointConfig config) {
        this.session = session
        ecfi = (ExecutionContextFactoryImpl) config.userProperties.get("executionContextFactory")
        handshakeRequest = (HandshakeRequest) config.userProperties.get("handshakeRequest")
        httpSession = (HttpSession) config.userProperties.get("httpSession")
        eci = ecfi.getEci()
        eci.userFacade.initFromHandshakeRequest(handshakeRequest)

        session.addMessageHandler(this)

        logger.info("Opened WebSocket Session ${session.getId()}, userId: ${eci.user.userId}, username: ${eci.user.username}, tenant: ${eci.tenantId}")

        logger.info("Opened WebSocket Session ${session.getId()}, parameters: ${session.getRequestParameterMap()}, username: ${session.getUserPrincipal()?.getName()}, config props: ${config.userProperties}")
        for (String attrName in httpSession.getAttributeNames())
            logger.info("WebSocket Session ${session.getId()}, session attribute: ${attrName}=${httpSession.getAttribute(attrName)}")

        // TODO: register with all sessions
        // TODO: some sort of auth, by requestParameterMap?
        // TODO: register with sessions per user (get user from httpSession via Shiro using UserFacadeImpl)
        session.getBasicRemote().sendText("Test text")
    }

    @Override
    void onMessage(String message) {
        logger.info("Message for WebSocket Session ${session?.id}: ${message}")
        // TODO
    }
    @Override
    void onClose(Session session, CloseReason closeReason) {
        // TODO: deregister from sessions per user
        this.session = null
        if (eci != null) eci.destroy()
        logger.info("Closed WebSocket Session ${session.getId()}: ${closeReason.reasonPhrase}")
    }

    @Override
    void onError(Session session, Throwable thr) {
        logger.warn("Error in WebSocket Session ${session.getId()}", thr)
    }
}
