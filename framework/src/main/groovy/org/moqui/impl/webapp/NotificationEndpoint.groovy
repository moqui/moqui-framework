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

import groovy.transform.CompileStatic
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.websocket.CloseReason
import javax.websocket.EndpointConfig
import javax.websocket.Session

@CompileStatic
class NotificationEndpoint extends MoquiAbstractEndpoint {
    private final static Logger logger = LoggerFactory.getLogger(NotificationEndpoint.class)

    NotificationEndpoint() { super() }

    @Override
    void onOpen(Session session, EndpointConfig config) {
        super.onOpen(session, config)

        getEcf().getNotificationWebSocketListener().registerEndpoint(this)
        session.getBasicRemote().sendText("Test text")
    }

    @Override
    void onMessage(String message) {
        // NOTE: this isn't used for notifications, ie meant for sending data only; just log the message for now
        logger.info("Message for WebSocket Session ${session?.id}: ${message}")
    }

    @Override
    void onClose(Session session, CloseReason closeReason) {
        getEcf().getNotificationWebSocketListener().deregisterEndpoint(this)
        super.onClose(session, closeReason)
    }
}
