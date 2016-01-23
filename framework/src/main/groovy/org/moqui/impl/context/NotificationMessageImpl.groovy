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
import org.moqui.context.NotificationMessage
import org.moqui.context.NotificationMessageListener
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue

import java.sql.Timestamp

class NotificationMessageImpl implements NotificationMessage {

    protected final ExecutionContextImpl eci
    protected Set<String> userIdSet = new HashSet()
    protected String userGroupId = null
    protected String topic = null
    protected String messageJson = null
    protected String notificationMessageId = null
    protected Timestamp sentDate = null

    NotificationMessageImpl(ExecutionContextImpl eci) {
        this.eci = eci
    }

    @Override
    NotificationMessage userId(String userId) { userIdSet.add(userId); return this }
    @Override
    NotificationMessage userIds(Set<String> userIds) { userIdSet.addAll(userIds); return this }
    @Override
    Set<String> getUserIds() { return userIdSet }

    @Override
    NotificationMessage userGroupId(String userGroupId) { this.userGroupId = userGroupId; return this }
    @Override
    String getUserGroupId() { return userGroupId }

    @Override
    NotificationMessage topic(String topic) { this.topic = topic; return this }
    @Override
    String getTopic() { return topic }

    @Override
    NotificationMessage message(String messageJson) { this.messageJson = messageJson; return this }
    @Override
    NotificationMessage message(Map message) { this.messageJson = JsonOutput.toJson(message); return this }
    @Override
    String getMessageJson() { return messageJson }
    @Override
    Map getMessageMap() { return (Map) new JsonSlurper().parseText(messageJson) }

    @Override
    NotificationMessage send(boolean persist) {
        if (persist) {
            boolean alreadyDisabled = eci.getArtifactExecution().disableAuthz()
            try {
                sentDate = eci.user.nowTimestamp
                Map createResult = eci.service.sync().name("create", "moqui.security.user.NotificationMessage")
                        .parameters([topic:topic, userGroupId:userGroupId, sentDate:sentDate, messageJson:messageJson])
                        .call()
                notificationMessageId = createResult.notificationMessageId

                // get explicit and group userIds and create a moqui.security.user.NotificationMessageUser record for each
                if (userGroupId) for (EntityValue userGroupMember in eci.getEntity().find("moqui.security.UserGroupMember")
                        .condition("userGroupId", userGroupId).useCache(true).list().filterByDate(null, null, null))
                    userIdSet.add(userGroupMember.getString("userId"))

                for (String userId in userIdSet) {
                    eci.service.sync().name("create", "moqui.security.user.NotificationMessageUser")
                            .parameters([notificationMessageId:notificationMessageId, userId:userId]).call()
                }
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
            EntityValue notificationMessageUser = eci.entity.find("moqui.security.user.NotificationMessageUser")
                    .condition([userId:userId?:eci.user.getUserId(), notificationMessageId:notificationMessageId])
                    .forUpdate(true).one()
            notificationMessageUser.sentDate = eci.user.nowTimestamp
            notificationMessageUser.update()
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
            EntityValue notificationMessageUser = eci.entity.find("moqui.security.user.NotificationMessageUser")
                    .condition([userId:userId?:eci.user.getUserId(), notificationMessageId:notificationMessageId])
                    .forUpdate(true).one()
            notificationMessageUser.receivedDate = eci.user.nowTimestamp
            notificationMessageUser.update()
        } finally {
            if (!alreadyDisabled) eci.getArtifactExecution().enableAuthz()
        }

        return this
    }

    void populateFromValue(EntityValue nmbu) {
        this.notificationMessageId = nmbu.notificationMessageId
        this.topic = nmbu.topic
        this.sentDate = nmbu.getTimestamp("sentDate")
        this.userGroupId = nmbu.userGroupId
        this.messageJson = nmbu.messageJson

        EntityList nmuList = eci.entity.find("moqui.security.user.NotificationMessageUser")
                .condition([notificationMessageId:notificationMessageId]).list()
        for (EntityValue nmu in nmuList) userIdSet.add((String) nmu.userId)
    }
}
