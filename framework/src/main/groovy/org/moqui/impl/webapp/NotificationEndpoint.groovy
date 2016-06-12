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

    final static String subscribePrefix = "subscribe:"
    final static String unsubscribePrefix = "unsubscribe:"

    private Set<String> subscribedTopics = new HashSet<>()

    NotificationEndpoint() { super() }

    Set<String> getSubscribedTopics() { subscribedTopics }

    @Override
    void onOpen(Session session, EndpointConfig config) {
        super.onOpen(session, config)
        getEcf().getNotificationWebSocketListener().registerEndpoint(this)
    }

    @Override
    void onMessage(String message) {
        if (message.startsWith(subscribePrefix)) {
            String topics = message.substring(subscribePrefix.length(), message.length())
            for (String topic in topics.split(",")) {
                String trimmedTopic = topic.trim()
                if (trimmedTopic) subscribedTopics.add(trimmedTopic)
            }
            logger.info("Notification subscribe user ${getUserId()} topics ${subscribedTopics} session ${session?.id}")
        } else if (message.startsWith(unsubscribePrefix)) {
            String topics = message.substring(unsubscribePrefix.length(), message.length())
            for (String topic in topics.split(",")) {
                String trimmedTopic = topic.trim()
                if (trimmedTopic) subscribedTopics.remove(trimmedTopic)
            }
            logger.info("Notification unsubscribe for user ${getUserId()} in session ${session?.id}, current topics: ${subscribedTopics}")
        } else {
            logger.info("Unknown command prefix for message to NotificationEndpoint in session ${session?.id}: ${message}")
        }
    }

    @Override
    void onClose(Session session, CloseReason closeReason) {
        getEcf().getNotificationWebSocketListener().deregisterEndpoint(this)
        super.onClose(session, closeReason)
    }
}
