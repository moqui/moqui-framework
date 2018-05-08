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
package org.moqui.context;

import java.util.Map;
import java.util.Set;

@SuppressWarnings("unused")
public interface NotificationMessage extends java.io.Serializable {
    enum NotificationType { info, success, warning, danger }
    NotificationType info = NotificationType.info;
    NotificationType success = NotificationType.success;
    NotificationType warning = NotificationType.warning;
    NotificationType danger = NotificationType.danger;

    NotificationMessage userId(String userId);
    NotificationMessage userIds(Set<String> userIds);
    Set<String> getUserIds();
    NotificationMessage userGroupId(String userGroupId);
    String getUserGroupId();

    /** Get userId for all users associated with this notification, directly or through the UserGroup, and who have
     * NotificationTopicUser.receiveNotifications=Y, which if not set (or there is no NotificationTopicUser record)
     * defaults to NotificationTopic.receiveNotifications (if not set defaults to Y) */
    Set<String> getNotifyUserIds();

    NotificationMessage topic(String topic);
    String getTopic();

    /** Set the message as a JSON String. The top-level should be a Map (JSON Object).
     * @param messageJson The message as a JSON string containing a Map (JSON Object)
     * @return Self-reference for convenience
     */
    NotificationMessage message(String messageJson);
    /** Set the message as a JSON String. The top-level should be a Map (JSON Object).
     * @param message The message as a Map (JSON Object), must be convertible to JSON String
     * @return Self-reference for convenience
     */
    NotificationMessage message(Map<String, Object> message);
    String getMessageJson();
    Map<String, Object> getMessageMap();

    /** Set the title to display, a GString (${} syntax) that will be expanded using the message Map; may be a localization template name */
    NotificationMessage title(String title);
    /** Get the title, expanded using the message Map; if not set and topic has a NotificationTopic record will default to value there */
    String getTitle();
    /** Set the link to get more detail about the notification or go to its source, a GString (${} syntax) expanded using the message Map */
    NotificationMessage link(String link);
    /** Get the link to detail/source, expanded using the message Map; if not set and topic has a NotificationTopic record will default to value there */
    String getLink();

    NotificationMessage type(NotificationType type);
    /** Must be a String for a valid NotificationType (ie info, success, warning, or danger) */
    NotificationMessage type(String type);
    /** Get the type as a String; if not set and topic has a NotificationTopic record will default to value there */
    String getType();

    NotificationMessage showAlert(boolean show);
    /** Show an alert for this notification? If not set and topic has a NotificationTopic record will default to value there */
    boolean isShowAlert();

    NotificationMessage emailTemplateId(String id);
    String getEmailTemplateId();

    NotificationMessage persistOnSend(boolean persist);
    boolean isPersistOnSend();

    /** Send this Notification Message.
     * @param persist If true this is persisted and message received is tracked. If false this is sent to active topic
     *                listeners only.
     * @return Self-reference for convenience
     */
    NotificationMessage send(boolean persist);
    /** Send this Notification Message using persistOnSend setting (defaults to false). */
    NotificationMessage send();

    String getNotificationMessageId();
    NotificationMessage markSent(String userId);
    NotificationMessage markViewed(String userId);

    /** Get a Map with: topic, sentDate, notificationMessageId, message, title, link, type, and showAlert using the get method for each */
    Map<String, Object> getWrappedMessageMap();
    /** Result of getWrappedMessageMap() as a JSON String */
    String getWrappedMessageJson();
}
