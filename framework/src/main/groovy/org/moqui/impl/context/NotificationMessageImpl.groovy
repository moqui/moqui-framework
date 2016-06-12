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
import org.moqui.Moqui
import org.moqui.context.NotificationMessage
import org.moqui.context.NotificationMessage.NotificationType
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

@CompileStatic
class NotificationMessageImpl implements NotificationMessage, Externalizable {
    private final static Logger logger = LoggerFactory.getLogger(NotificationMessageImpl.class)

    private String tenantId = (String) null
    private Set<String> userIdSet = new HashSet()
    private String userGroupId = (String) null
    private String topic = (String) null
    private transient EntityValue notificationTopic = (EntityValue) null
    private String messageJson = (String) null
    private transient Map<String, Object> messageMap = (Map<String, Object>) null
    private String notificationMessageId = (String) null
    private Timestamp sentDate = (Timestamp) null
    private String titleTemplate = (String) null
    private String linkTemplate = (String) null
    private NotificationType type = (NotificationType) null
    private Boolean showAlert = (Boolean) null
    private Boolean persistOnSend = (Boolean) null

    private transient ExecutionContextFactoryImpl ecfiTransient = (ExecutionContextFactoryImpl) null

    /** Default constructor for deserialization */
    NotificationMessageImpl() { }
    NotificationMessageImpl(ExecutionContextFactoryImpl ecfi, String tenantId) {
        ecfiTransient = ecfi
        this.tenantId = tenantId
    }

    ExecutionContextFactoryImpl getEcfi() {
        if (ecfiTransient == null) ecfiTransient = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
        return ecfiTransient
    }
    EntityValue getNotificationTopic() {
        if (notificationTopic == null && topic) notificationTopic = ecfi.getEntityFacade(tenantId)
                .find("moqui.security.user.NotificationTopic").condition("topic", topic).useCache(true).disableAuthz().one()
        return notificationTopic
    }

    @Override
    String getTenantId() { tenantId }

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
            return ecfi.resource.expand(titleTemplate, "", getMessageMap(), true)
        } else {
            EntityValue localNotTopic = getNotificationTopic()
            if (localNotTopic != null && localNotTopic.titleTemplate) {
                return ecfi.resource.expand((String) localNotTopic.titleTemplate, "", getMessageMap(), true)
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
            return ecfi.resource.expand(linkTemplate, "", getMessageMap(), true)
        } else {
            EntityValue localNotTopic = getNotificationTopic()
            if (localNotTopic != null && localNotTopic.linkTemplate) {
                return ecfi.resource.expand((String) localNotTopic.linkTemplate, "", getMessageMap(), true)
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
        return send()
    }
    @Override
    NotificationMessage send() {
        if (isPersistOnSend()) {
            sentDate = new Timestamp(System.currentTimeMillis())
            Map createResult = ecfi.service.sync().name("create", "moqui.security.user.NotificationMessage")
                    .parameters([topic:topic, userGroupId:userGroupId, sentDate:sentDate, messageJson:messageJson,
                                 titleTemplate:titleTemplate, linkTemplate:linkTemplate, typeString:type?.name(),
                                 showAlert:(showAlert ? 'Y' : 'N')])
                    .disableAuthz().call()
            notificationMessageId = createResult.notificationMessageId

            /* don't set all UserGroupMembers, could be a bit of a data explosion; better to handle that by group:
            // get explicit and group userIds and create a moqui.security.user.NotificationMessageUser record for each
            if (userGroupId) for (EntityValue userGroupMember in eci.getEntity().find("moqui.security.UserGroupMember")
                    .condition("userGroupId", userGroupId).useCache(true).list().filterByDate(null, null, null)) {
                userIdSet.add(userGroupMember.getString("userId"))
            }
            */

            for (String userId in userIdSet)
                ecfi.service.sync().name("create", "moqui.security.user.NotificationMessageUser")
                        .parameters([notificationMessageId:notificationMessageId, userId:userId]).disableAuthz().call()
        }

        // now send it to the topic
        ecfi.sendNotificationMessageToTopic(this)

        return this
    }

    @Override
    String getNotificationMessageId() { return notificationMessageId }

    @Override
    NotificationMessage markSent(String userId) {
        // if no notificationMessageId there is nothing to do, this isn't persisted as far as we know
        if (!notificationMessageId) return this

        ExecutionContextImpl eci = ecfi.getEci()
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

        ExecutionContextImpl eci = ecfi.getEci()
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

        EntityList nmuList = nmbu.findRelated("moqui.security.user.NotificationMessageUser",
                [notificationMessageId:notificationMessageId] as Map<String, Object>, null, false, false)
        for (EntityValue nmu in nmuList) userIdSet.add((String) nmu.userId)
    }

    @Override
    void writeExternal(ObjectOutput out) throws IOException {
        // NOTE: lots of writeObject because values are nullable
        out.writeUTF(tenantId)
        out.writeObject(userIdSet)
        out.writeObject(userGroupId)
        out.writeUTF(topic)
        out.writeUTF(getMessageJson())
        out.writeObject(notificationMessageId)
        out.writeObject(sentDate)
        out.writeObject(titleTemplate)
        out.writeObject(linkTemplate)
        out.writeObject(type)
        out.writeObject(showAlert)
        out.writeObject(persistOnSend)
    }

    @Override
    void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
        tenantId = objectInput.readUTF()
        userIdSet = (Set<String>) objectInput.readObject()
        userGroupId = (String) objectInput.readObject()
        topic = objectInput.readUTF()
        messageJson = objectInput.readUTF()
        notificationMessageId = (String) objectInput.readObject()
        sentDate = (Timestamp) objectInput.readObject()
        titleTemplate = (String) objectInput.readObject()
        linkTemplate = (String) objectInput.readObject()
        type = (NotificationType) objectInput.readObject()
        showAlert = (Boolean) objectInput.readObject()
        persistOnSend = (Boolean) objectInput.readObject()
    }
}
