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

import org.moqui.context.ExecutionContextFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.websocket.CloseReason
import javax.websocket.Endpoint
import javax.websocket.EndpointConfig
import javax.websocket.MessageHandler
import javax.websocket.Session

class NotificationEndpoint extends Endpoint implements MessageHandler.Whole<String> {
    private final static Logger logger = LoggerFactory.getLogger(NotificationEndpoint.class)

    private ExecutionContextFactory ecf = null
    private Session session = null

    @Override
    void onOpen(Session session, EndpointConfig config) {
        this.session = session
        this.ecf = (ExecutionContextFactory) config.userProperties.get("executionContextFactory")
        logger.info("Opened WebSocket Session ${session.getId()}, parameters: ${session.getRequestParameterMap()}, user: ${session.userProperties}, ${config.userProperties}")
        // TODO: register with all sessions
        // TODO: some sort of auth, by requestParameterMap?
        // TODO: register with sessions per user
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
        logger.info("WebSocket Session ${session.getId()} closed ${closeReason.reasonPhrase}")
    }

    @Override
    void onError(Session session, Throwable thr) {
        logger.warn("WebSocket Session ${session.getId()} error", thr)
    }
}
