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

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.websocket.CloseReason
import javax.websocket.EndpointConfig
import javax.websocket.Session

class NotificationEndpoint extends MoquiAbstractEndpoint {
    private final static Logger logger = LoggerFactory.getLogger(NotificationEndpoint.class)

    NotificationEndpoint() { super() }

    @Override
    void onOpen(Session session, EndpointConfig config) {
        super.onOpen(session, config)

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
        super.onClose(session, closeReason)
    }
}
