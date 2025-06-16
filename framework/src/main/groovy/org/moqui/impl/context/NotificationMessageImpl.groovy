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
import org.moqui.BaseArtifactException
import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.context.NotificationMessage
import org.moqui.entity.EntityFacade
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

@CompileStatic
class NotificationMessageImpl implements NotificationMessage, Externalizable {
    private final static Logger logger = LoggerFactory.getLogger(NotificationMessageImpl.class)

    private Set<String> userIdSet = new HashSet()
    private String userGroupId = (String) null
    private String topic = (String) null
    private String subTopic = (String) null
    private transient EntityValue notificationTopic = (EntityValue) null
    private String messageJson = (String) null
    private transient Map<String, Object> messageMap = (Map<String, Object>) null
    private String notificationMessageId = (String) null
    private Timestamp sentDate = (Timestamp) null

    private String titleTemplate = (String) null
    private String linkTemplate = (String) null
    private String titleText = (String) null
    private String linkText = (String) null

    private NotificationType type = (NotificationType) null
    private Boolean showAlert = (Boolean) null
    private Boolean alertNoAutoHide = (Boolean) null
    private Boolean persistOnSend = (Boolean) null
    private String emailTemplateId = (String) null
    private Boolean emailMessageSave = (Boolean) null

    private Map<String, String> emailMessageIdByUserId = (Map<String, String>) null

    private transient ExecutionContextFactoryImpl ecfiTransient = (ExecutionContextFactoryImpl) null

    /** Default constructor for deserialization */
    NotificationMessageImpl() { }
    NotificationMessageImpl(ExecutionContextFactoryImpl ecfi) { ecfiTransient = ecfi }

    ExecutionContextFactoryImpl getEcfi() {
        if (ecfiTransient == null) ecfiTransient = (ExecutionContextFactoryImpl) Moqui.getExecutionContextFactory()
        return ecfiTransient
    }
    EntityValue getNotificationTopic() {
        if (notificationTopic == null && topic != null && !topic.isEmpty())
            notificationTopic = ecfi.entityFacade.fastFindOne("moqui.security.user.NotificationTopic", true, true, topic)
        return notificationTopic
    }

    @Override NotificationMessage userId(String userId) { userIdSet.add(userId); return this }
    @Override NotificationMessage userIds(Set<String> userIds) { userIdSet.addAll(userIds); return this }
    @Override Set<String> getUserIds() { userIdSet }

    @Override NotificationMessage userGroupId(String userGroupId) { this.userGroupId = userGroupId; return this }
    @Override String getUserGroupId() { userGroupId }

    @Override Set<String> getNotifyUserIds() {
        Set<String> notifyUserIds = new HashSet<>()
        Set<String> checkedUserIds = new HashSet<>()
        EntityFacade ef = ecfi.entityFacade

        for (String userId in userIdSet) {
            checkedUserIds.add(userId)
            if (checkUserNotify(userId, ef)) notifyUserIds.add(userId)
        }

        // notify by group, skipping users already notified
        if (userGroupId) {
            ef.find("moqui.security.UserGroupMember")
                    .conditionDate("fromDate", "thruDate", new Timestamp(System.currentTimeMillis()))
                    .condition("userGroupId", userGroupId).disableAuthz().iterator().withCloseable ({eli ->
                EntityValue nextValue
                while ((nextValue = (EntityValue) eli.next()) != null) {
                    String userId = (String) nextValue.userId
                    if (checkedUserIds.contains(userId)) continue
                    checkedUserIds.add(userId)
                    if (checkUserNotify(userId, ef)) notifyUserIds.add(userId)
                }
            })
        }

        // add all users subscribed to all messages on the topic
        EntityList allNotificationUsers = ef.find("moqui.security.user.NotificationTopicUser")
                .condition("topic", topic).condition("allNotifications", "Y").useCache(true).disableAuthz().list()
        int allNotificationUsersSize = allNotificationUsers.size()
        for (int i = 0; i < allNotificationUsersSize; i++) {
            EntityValue allNotificationUser = (EntityValue) allNotificationUsers.get(i)
            notifyUserIds.add((String) allNotificationUser.userId)
        }

        // check each user to see if account terminated (UserAccount.terminateDate != null && < now)
        long nowTime = System.currentTimeMillis()
        EntityList notifyUserAccountList = ef.find("moqui.security.UserAccount")
                .condition("userId", "in", notifyUserIds)
                .selectField("userId").selectField("terminateDate").disableAuthz().list()
        int notifyUaSize = notifyUserAccountList.size()
        for (int i = 0; i < notifyUaSize; i++) {
            EntityValue userAccount = (EntityValue) notifyUserAccountList.get(i)
            Timestamp terminateDate = (Timestamp) userAccount.getNoCheckSimple("terminateDate")
            if (terminateDate != (Timestamp) null && nowTime > terminateDate.getTime()) notifyUserIds.remove(userAccount.get("userId"))
        }

        return notifyUserIds
    }
    private boolean checkUserNotify(String userId, EntityFacade ef) {
        EntityValue notTopicUser = ef.find("moqui.security.user.NotificationTopicUser")
                .condition("topic", topic).condition("userId", userId).useCache(true).disableAuthz().one()
        boolean notifyUser = true
        if (notTopicUser != null && notTopicUser.receiveNotifications) {
            notifyUser = notTopicUser.receiveNotifications == 'Y'
        } else {
            EntityValue localNotTopic = getNotificationTopic()
            if (localNotTopic != null && localNotTopic.receiveNotifications)
                notifyUser = localNotTopic.receiveNotifications == 'Y'
        }
        return notifyUser
    }

    @Override NotificationMessage topic(String topic) { this.topic = topic; notificationTopic = null; return this }
    @Override String getTopic() { topic }

    @Override String getSubTopic() { subTopic }
    @Override NotificationMessage subTopic(String st) { subTopic = st; return this }

    @Override NotificationMessage message(String messageJson) { this.messageJson = messageJson; messageMap = null; return this }
    @Override NotificationMessage message(Map message) {
        this.messageMap = Collections.unmodifiableMap(message) as Map<String, Object>
        messageJson = null
        return this
    }
    @Override String getMessageJson() {
        if (messageJson == null && messageMap != null) {
            try {
                messageJson = JsonOutput.toJson(messageMap)
            } catch (Exception e) {
                logger.warn("Error writing JSON for Notification ${topic} message: ${e.toString()}\n${messageMap}")
            }
        }
        return messageJson
    }
    @Override Map<String, Object> getMessageMap() {
        if (messageMap == null && messageJson != null)
            messageMap = Collections.unmodifiableMap((Map<String, Object>) new JsonSlurper().parseText(messageJson))
        return messageMap
    }

    @Override NotificationMessage title(String title) { titleTemplate = title; return this }
    @Override String getTitle() {
        if (titleText == null) {
            if (titleTemplate != null && !titleTemplate.isEmpty())
                titleText = ecfi.resource.expand(titleTemplate, "", getMessageMap(), true)
            if (titleText == null || titleText.isEmpty()) {
                EntityValue localNotTopic = getNotificationTopic()
                if (localNotTopic != null) {
                    if (type == danger && localNotTopic.errorTitleTemplate) {
                        titleText = ecfi.resource.expand((String) localNotTopic.errorTitleTemplate, "", getMessageMap(), true)
                    } else if (localNotTopic.titleTemplate) {
                        titleText = ecfi.resource.expand((String) localNotTopic.titleTemplate, "", getMessageMap(), true)
                    }
                }
            }
        }
        return titleText
    }

    @Override NotificationMessage link(String link) { linkTemplate = link; return this }
    @Override String getLink() {
        if (linkText == null) {
            if (linkTemplate) {
                linkText = ecfi.resource.expand(linkTemplate, "", getMessageMap(), true)
            } else {
                EntityValue localNotTopic = getNotificationTopic()
                if (localNotTopic != null && localNotTopic.linkTemplate)
                    linkText = ecfi.resource.expand((String) localNotTopic.linkTemplate, "", getMessageMap(), true)
            }
        }
        return linkText
    }

    @Override NotificationMessage type(NotificationType type) { this.type = type; return this }
    @Override NotificationMessage type(String type) { this.type = NotificationType.valueOf(type); return this }
    @Override String getType() {
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

    @Override NotificationMessage showAlert(boolean show) { showAlert = show; return this }
    @Override boolean isShowAlert() {
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

    @Override NotificationMessage alertNoAutoHide(boolean noAutoHide) { alertNoAutoHide = noAutoHide; return this }
    @Override boolean isAlertNoAutoHide() {
        if (alertNoAutoHide != null) {
            return alertNoAutoHide.booleanValue()
        } else {
            EntityValue localNotTopic = getNotificationTopic()
            if (localNotTopic != null && localNotTopic.alertNoAutoHide) {
                return localNotTopic.alertNoAutoHide == 'Y'
            } else {
                return false
            }
        }
    }

    @Override NotificationMessage emailTemplateId(String id) {
        emailTemplateId = id
        if (emailTemplateId != null && emailTemplateId.isEmpty()) emailTemplateId = null
        return this
    }
    @Override String getEmailTemplateId() {
        if (emailTemplateId != null) {
            return emailTemplateId
        } else {
            EntityValue localNotTopic = getNotificationTopic()
            if (localNotTopic != null && localNotTopic.emailTemplateId) {
                return localNotTopic.emailTemplateId
            } else {
                return null
            }
        }
    }
    @Override NotificationMessage emailMessageSave(Boolean save) { emailMessageSave = save; return this }
    @Override boolean isEmailMessageSave() {
        if (emailMessageSave != null) {
            return emailMessageSave.booleanValue()
        } else {
            EntityValue localNotTopic = getNotificationTopic()
            if (localNotTopic != null && localNotTopic.emailMessageSave) {
                return localNotTopic.emailMessageSave == 'Y'
            } else {
                return false
            }
        }
    }

    @Override Map<String, String> getEmailMessageIdByUserId() { return emailMessageIdByUserId }

    @Override NotificationMessage persistOnSend(Boolean persist) { persistOnSend = persist; return this }
    @Override boolean isPersistOnSend() {
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

    @Override NotificationMessage send(boolean persist) {
        persistOnSend = persist
        return send()
    }
    @Override NotificationMessage send() {
        // persist if is persistOnSend
        if (isPersistOnSend()) {
            sentDate = new Timestamp(System.currentTimeMillis())
            TransactionFacadeImpl tfi = ecfi.transactionFacade

            // run in separate transaction so that it is saved immediately, NotificationMessage listeners running async are
            //     outside of this transaction and may use these records (like markSent() before the current tx is complete)
            boolean suspendedTransaction = false
            try {
                if (tfi.isTransactionInPlace()) suspendedTransaction = tfi.suspend()
                boolean beganTransaction = tfi.begin(null)
                try {
                    Map createResult = ecfi.service.sync().name("create", "moqui.security.user.NotificationMessage")
                            .parameters([topic:this.topic, subTopic:this.subTopic, userGroupId:this.userGroupId, sentDate:this.sentDate,
                                    messageJson:this.getMessageJson(), titleText:this.getTitle(), linkText:this.getLink(),
                                    typeString:this.getType(), showAlert:(this.showAlert ? 'Y' : 'N')])
                            .disableAuthz().call()
                    // if it's null we got an error so return from closure
                    if (createResult == null) return

                    this.setNotificationMessageId((String) createResult.notificationMessageId)
                    for (String userId in this.getNotifyUserIds())
                        ecfi.service.sync().name("create", "moqui.security.user.NotificationMessageUser")
                                .parameters([notificationMessageId:createResult.notificationMessageId, userId:userId])
                                .disableAuthz().call()
                } catch (Throwable t) {
                    tfi.rollback(beganTransaction, "Error saving NotificationMessage", t)
                    throw t
                } finally {
                    tfi.commit(beganTransaction)
                }
            } finally {
                if (suspendedTransaction) tfi.resume()
            }

            /* old approach, cleaner and simpler but blows up under Groovy 2.5.13 and later
             *  java.lang.VerifyError: Bad type on operand stack
             *  Exception Details:
             *  Location: org/moqui/impl/context/NotificationMessageImpl$_send_closure1.doCall(Ljava/lang/Object;)Ljava/lang/Object; @223: ifnonnull
             *  Reason: Type integer (current frame, stack[5]) is not assignable to reference type

            // a little trick so that this is available in the closure
            NotificationMessageImpl nmi = this
            // run in runRequireNew so that it is saved immediately, NotificationMessage listeners running async are
            //     outside of this transaction and may use these records (like markSent() before the current tx is complete)
            ecfi.transactionFacade.runRequireNew(null, "Error saving NotificationMessage", {
                Map createResult = ecfi.service.sync().name("create", "moqui.security.user.NotificationMessage")
                        .parameters([topic:nmi.topic, userGroupId:nmi.userGroupId, sentDate:nmi.sentDate,
                                     messageJson:nmi.getMessageJson(), titleText:nmi.getTitle(), linkText:nmi.getLink(),
                                     typeString:nmi.getType(), showAlert:(nmi.showAlert ? 'Y' : 'N')])
                        .disableAuthz().call()
                // if it's null we got an error so return from closure
                if (createResult == null) return

                nmi.setNotificationMessageId((String) createResult.notificationMessageId)
                for (String userId in nmi.getNotifyUserIds())
                    ecfi.service.sync().name("create", "moqui.security.user.NotificationMessageUser")
                            .parameters([notificationMessageId:createResult.notificationMessageId, userId:userId])
                            .disableAuthz().call()
            })
             */
        }

        // now send it to the topic
        ecfi.sendNotificationMessageToTopic(this)

        // send emails if emailTemplateId
        String localEmailTemplateId = getEmailTemplateId()
        if (localEmailTemplateId != null && !localEmailTemplateId.isEmpty()) {
            Map<String, Object> wrappedMessageMap = getWrappedMessageMap()
            EntityValue notificationTopic = getNotificationTopic()

            Set<String> curNotifyUserIds = getNotifyUserIds()
            EntityList notificationTopicUsers = ecfi.entityFacade.find("moqui.security.user.NotificationTopicUser")
                    .condition("topic", topic).condition("userId", "in", curNotifyUserIds).disableAuthz().list()

            for (String userId in curNotifyUserIds) {
                EntityValue notificationUser = (EntityValue) notificationTopicUsers.findByAnd("userId", userId)

                if ("N".equals(notificationUser?.emailNotifications)) continue
                if (!("Y".equals(notificationUser?.emailNotifications) || "Y".equals(notificationTopic?.emailNotifications))) continue

                EntityValue userAccount = ecfi.entityFacade.find("moqui.security.UserAccount")
                        .condition("userId", userId).disableAuthz().one()
                String emailAddress = userAccount?.emailAddress
                if (emailAddress) {
                    // FUTURE: if there is an option to create EmailMessage record also configure emailTypeEnumId (maybe if emailTypeEnumId is set create EmailMessage)
                    Map<String, Object> sendOut = ecfi.serviceFacade.sync().name("org.moqui.impl.EmailServices.send#EmailTemplate")
                            .parameters([emailTemplateId:localEmailTemplateId, toAddresses:emailAddress,
                                    bodyParameters:wrappedMessageMap, toUserId:userId, createEmailMessage:isEmailMessageSave()]).call()
                    String emailMessageId = (String) sendOut.emailMessageId
                    if (emailMessageId) {
                        if (emailMessageIdByUserId == null) emailMessageIdByUserId = new HashMap<String, String>()
                        emailMessageIdByUserId.put(userId, emailMessageId)
                        String notificationMessageId = getNotificationMessageId()
                        if (notificationMessageId) {
                            // use store to update if was created above or create if not
                            ecfi.service.sync().name("store", "moqui.security.user.NotificationMessageUser")
                                    .parameters([notificationMessageId:notificationMessageId, userId:userId,
                                            emailMessageId:emailMessageId, sentDate:new Timestamp(System.currentTimeMillis())])
                                    .disableAuthz().call()
                        }
                    }
                }
            }
        }

        return this
    }

    @Override String getNotificationMessageId() { return notificationMessageId }
    void setNotificationMessageId(String id) { notificationMessageId = id }

    @Override NotificationMessage markSent(String userId) {
        // if no notificationMessageId there is nothing to do, this isn't persisted as far as we know
        if (!notificationMessageId) return this
        if (!userId) throw new BaseArtifactException("Must specify userId to mark notification message sent")

        ExecutionContextImpl eci = ecfi.getEci()
        boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
        try {
            ecfi.entityFacade.makeValue("moqui.security.user.NotificationMessageUser")
                    .set("userId", userId).set("notificationMessageId", notificationMessageId)
                    .set("sentDate", new Timestamp(System.currentTimeMillis())).update()
        } catch (Throwable t) {
            logger.error("Error marking notification message ${notificationMessageId} sent", t)
        } finally {
            if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz()
        }

        return this
    }
    @Override NotificationMessage markViewed(String userId) {
        // if no notificationMessageId there is nothing to do, this isn't persisted as far as we know
        if (!notificationMessageId) return this
        if (!userId) throw new BaseArtifactException("Must specify userId to mark notification message received")

        markViewed(notificationMessageId, userId, ecfi.getEci())
        return this
    }
    static Timestamp markViewed(String notificationMessageId, String userId, ExecutionContext ec) {
        boolean alreadyDisabled = ec.getArtifactExecution().disableAuthz()
        try {
            Timestamp recStamp = new Timestamp(System.currentTimeMillis())
            ec.factory.entity.makeValue("moqui.security.user.NotificationMessageUser")
                    .set("userId", userId).set("notificationMessageId", notificationMessageId)
                    .set("viewedDate", recStamp).update()
            return recStamp
        } catch (Throwable t) {
            logger.error("Error marking notification message ${notificationMessageId} sent", t)
            return null
        } finally {
            if (!alreadyDisabled) ec.getArtifactExecution().enableAuthz()
        }
    }

    @Override Map<String, Object> getWrappedMessageMap() {
        EntityValue localNotTopic = getNotificationTopic()
        return [topic:topic, subTopic:subTopic, sentDate:sentDate, notificationMessageId:notificationMessageId, topicDescription:localNotTopic?.description,
                message:getMessageMap(), title:getTitle(), link:getLink(), type:getType(), persistOnSend:isPersistOnSend(),
                showAlert:isShowAlert(), alertNoAutoHide:isAlertNoAutoHide()]
    }
    @Override String getWrappedMessageJson() {
        Map<String, Object> wrappedMap = getWrappedMessageMap()
        try {
            return JsonOutput.toJson(wrappedMap)
        } catch (Exception e) {
            logger.warn("Error writing JSON for Notification ${topic} message: ${e.toString()}\n${wrappedMap}")
            return null
        }
    }

    void populateFromValue(EntityValue nmbu) {
        this.notificationMessageId = nmbu.notificationMessageId
        this.topic = nmbu.topic
        this.subTopic = nmbu.subTopic
        this.sentDate = nmbu.getTimestamp("sentDate")
        this.userGroupId = nmbu.userGroupId
        this.messageJson = nmbu.messageJson
        this.titleText = nmbu.titleText
        this.linkText = nmbu.linkText
        if (nmbu.typeString) this.type = NotificationType.valueOf((String) nmbu.typeString)
        this.showAlert = nmbu.showAlert == 'Y'
        this.alertNoAutoHide = nmbu.alertNoAutoHide == 'Y'

        EntityList nmuList = nmbu.findRelated("moqui.security.user.NotificationMessageUser",
                [notificationMessageId:notificationMessageId] as Map<String, Object>, null, false, false)
        for (EntityValue nmu in nmuList) userIdSet.add((String) nmu.userId)
    }

    @Override void writeExternal(ObjectOutput out) throws IOException {
        // NOTE: lots of writeObject because values are nullable
        out.writeObject(userIdSet)
        out.writeObject(userGroupId)
        out.writeUTF(topic)
        out.writeObject(subTopic)
        out.writeUTF(getMessageJson())
        out.writeObject(notificationMessageId)
        out.writeObject(sentDate)
        out.writeObject(getTitle())
        out.writeObject(getLink())
        out.writeObject(type)
        out.writeObject(showAlert)
        out.writeObject(alertNoAutoHide)
        out.writeObject(persistOnSend)
    }
    @Override void readExternal(ObjectInput objectInput) throws IOException, ClassNotFoundException {
        userIdSet = (Set<String>) objectInput.readObject()
        userGroupId = (String) objectInput.readObject()
        topic = objectInput.readUTF()
        subTopic = objectInput.readObject()
        messageJson = objectInput.readUTF()
        notificationMessageId = (String) objectInput.readObject()
        sentDate = (Timestamp) objectInput.readObject()
        titleText = (String) objectInput.readObject()
        linkText = (String) objectInput.readObject()
        type = (NotificationType) objectInput.readObject()
        showAlert = (Boolean) objectInput.readObject()
        alertNoAutoHide = (Boolean) objectInput.readObject()
        persistOnSend = (Boolean) objectInput.readObject()
    }
}
