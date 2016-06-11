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
package org.moqui.impl.context

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.moqui.context.NotificationMessage
import org.moqui.context.NotificationMessage.NotificationType
import org.moqui.context.NotificationMessageListener
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

@CompileStatic
class NotificationMessageImpl implements NotificationMessage {
    private final static Logger logger = LoggerFactory.getLogger(NotificationMessageImpl.class)

    private final ExecutionContextImpl eci
    private Set<String> userIdSet = new HashSet()
    private String userGroupId = (String) null
    private String topic = (String) null
    private EntityValue notificationTopic = (EntityValue) null
    private String messageJson = (String) null
    private Map<String, Object> messageMap = (Map<String, Object>) null
    private String notificationMessageId = (String) null
    private Timestamp sentDate = (Timestamp) null
    private String titleTemplate = (String) null
    private String linkTemplate = (String) null
    private NotificationType type = (NotificationType) null
    private Boolean showAlert = (Boolean) null
    private Boolean persistOnSend = (Boolean) null

    NotificationMessageImpl(ExecutionContextImpl eci) {
        this.eci = eci
    }

    EntityValue getNotificationTopic() {
        if (notificationTopic == null && topic) notificationTopic = eci.entity.find("moqui.security.user.NotificationTopic")
                .condition("topic", topic).useCache(true).disableAuthz().one()
        return notificationTopic
    }

    @Override
    NotificationMessage userId(String userId) { userIdSet.add(userId); return this }
    @Override
    NotificationMessage userIds(Set<String> userIds) { userIdSet.addAll(userIds); return this }
    @Override
    Set<String> getUserIds() { userIdSet }

    @Override
    NotificationMessage userGroupId(String userGroupId) { this.userGroupId = userGroupId; return this }
    @Override
    String getUserGroupId() { userGroupId }

    @Override
    NotificationMessage topic(String topic) { this.topic = topic; notificationTopic = null; return this }
    @Override
    String getTopic() { topic }

    @Override
    NotificationMessage message(String messageJson) { this.messageJson = messageJson; messageMap = null; return this }
    @Override
    NotificationMessage message(Map message) { this.messageMap = Collections.unmodifiableMap(message); messageJson = null; return this }
    @Override
    String getMessageJson() {
        if (messageJson == null && messageMap != null)
            messageJson = JsonOutput.toJson(messageMap)
        return messageJson
    }
    @Override
    Map<String, Object> getMessageMap() {
        if (messageMap == null && messageJson != null)
            messageMap = Collections.unmodifiableMap((Map<String, Object>) new JsonSlurper().parseText(messageJson))
        return messageMap
    }

    @Override
    NotificationMessage title(String title) { titleTemplate = title; return this }
    @Override
    String getTitle() {
        if (titleTemplate) {
            return eci.resource.expand(titleTemplate, "", getMessageMap(), true)
        } else {
            EntityValue localNotTopic = getNotificationTopic()
            if (localNotTopic != null && localNotTopic.titleTemplate) {
                return eci.resource.expand((String) localNotTopic.titleTemplate, "", getMessageMap(), true)
            } else {
                return null
            }
        }
    }

    @Override
    NotificationMessage link(String link) { linkTemplate = link; return this }
    @Override
    String getLink() {
        if (linkTemplate) {
            return eci.resource.expand(linkTemplate, "", getMessageMap(), true)
        } else {
            EntityValue localNotTopic = getNotificationTopic()
            if (localNotTopic != null && localNotTopic.linkTemplate) {
                return eci.resource.expand((String) localNotTopic.linkTemplate, "", getMessageMap(), true)
            } else {
                return null
            }
        }
    }

    @Override
    NotificationMessage type(NotificationType type) { this.type = type; return this }
    @Override
    NotificationMessage type(String type) { this.type = NotificationType.valueOf(type); return this }
    @Override
    String getType() {
        if (type != null) {
            return type.name()
        } else {
            EntityValue localNotTopic = getNotificationTopic()
            if (localNotTopic != null && localNotTopic.typeString) {
                return localNotTopic.typeString
            } else {
                return info.name()
            }
        }
    }

    @Override
    NotificationMessage showAlert(boolean show) { showAlert = show; return this }
    @Override
    boolean isShowAlert() {
        if (showAlert != null) {
            return showAlert.booleanValue()
        } else {
            EntityValue localNotTopic = getNotificationTopic()
            if (localNotTopic != null && localNotTopic.showAlert) {
                return localNotTopic.showAlert == 'Y'
            } else {
                return false
            }
        }
    }

    @Override
    NotificationMessage persistOnSend(boolean persist) { persistOnSend = persist; return this }
    @Override
    boolean isPersistOnSend() {
        if (persistOnSend != null) {
            return persistOnSend.booleanValue()
        } else {
            EntityValue localNotTopic = getNotificationTopic()
            if (localNotTopic != null && localNotTopic.persistOnSend) {
                return localNotTopic.persistOnSend == 'Y'
            } else {
                return false
            }
        }
    }

    @Override
    NotificationMessage send(boolean persist) {
        persistOnSend = persist
        send()
        return this
    }
    @Override
    NotificationMessage send() {
        if (isPersistOnSend()) {
            boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
            try {
                sentDate = eci.user.nowTimestamp
                Map createResult = eci.service.sync().name("create", "moqui.security.user.NotificationMessage")
                        .parameters([topic:topic, userGroupId:userGroupId, sentDate:sentDate, messageJson:messageJson,
                                     titleTemplate:titleTemplate, linkTemplate:linkTemplate, typeString:type?.name(),
                                     showAlert:(showAlert ? 'Y' : 'N')])
                        .call()
                notificationMessageId = createResult.notificationMessageId

                /* don't set all UserGroupMembers, could be a bit of a data explosion; better to handle that by group:
                // get explicit and group userIds and create a moqui.security.user.NotificationMessageUser record for each
                if (userGroupId) for (EntityValue userGroupMember in eci.getEntity().find("moqui.security.UserGroupMember")
                        .condition("userGroupId", userGroupId).useCache(true).list().filterByDate(null, null, null)) {
                    userIdSet.add(userGroupMember.getString("userId"))
                }
                */

                for (String userId in userIdSet)
                    eci.service.sync().name("create", "moqui.security.user.NotificationMessageUser")
                            .parameters([notificationMessageId:notificationMessageId, userId:userId]).call()
            } finally {
                if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz()
            }
        }

        // now send it to all listeners
        List<NotificationMessageListener> nmlList = eci.getEcfi().getNotificationMessageListeners()
        for (NotificationMessageListener nml in nmlList) {
            nml.onMessage(this, eci)
        }

        return this
    }

    @Override
    String getNotificationMessageId() { return notificationMessageId }

    @Override
    NotificationMessage markSent(String userId) {
        // if no notificationMessageId there is nothing to do, this isn't persisted as far as we know
        if (!notificationMessageId) return this

        boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
        try {
            eci.entity.makeValue("moqui.security.user.NotificationMessageUser")
                    .set("userId", userId ?: eci.user.userId).set("notificationMessageId", notificationMessageId)
                    .set("sentDate", eci.user.nowTimestamp).update()
        } catch (Throwable t) {
            logger.error("Error marking notification message ${notificationMessageId} sent", t)
        } finally {
            if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz()
        }

        return this
    }
    @Override
    NotificationMessage markReceived(String userId) {
        // if no notificationMessageId there is nothing to do, this isn't persisted as far as we know
        if (!notificationMessageId) return this

        boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
        try {
            eci.entity.makeValue("moqui.security.user.NotificationMessageUser")
                    .set("userId", userId?:eci.user.userId).set("notificationMessageId", notificationMessageId)
                    .set("receivedDate", eci.user.nowTimestamp).update()
        } catch (Throwable t) {
            logger.error("Error marking notification message ${notificationMessageId} sent", t)
        } finally {
            if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz()
        }

        return this
    }

    @Override
    Map<String, Object> getWrappedMessageMap() { [topic:topic, sentDate:sentDate, notificationMessageId:notificationMessageId,
            message:getMessageMap(), title:getTitle(), link:getLink(), type:getType(), showAlert:isShowAlert()] }
    @Override
    String getWrappedMessageJson() { JsonOutput.toJson(getWrappedMessageMap()) }

    void populateFromValue(EntityValue nmbu) {
        this.notificationMessageId = nmbu.notificationMessageId
        this.topic = nmbu.topic
        this.sentDate = nmbu.getTimestamp("sentDate")
        this.userGroupId = nmbu.userGroupId
        this.messageJson = nmbu.messageJson
        this.titleTemplate = nmbu.titleTemplate
        this.linkTemplate = nmbu.linkTemplate
        if (nmbu.typeString) this.type = NotificationType.valueOf((String) nmbu.typeString)
        this.showAlert = nmbu.showAlert == 'Y'

        EntityList nmuList = eci.entity.find("moqui.security.user.NotificationMessageUser")
                .condition("notificationMessageId", notificationMessageId).disableAuthz().list()
        for (EntityValue nmu in nmuList) userIdSet.add((String) nmu.userId)
    }
}
