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

/*
    Jakarta Mail API Documentation at: https://jakarta.ee/specifications/mail/
    Implementation (Eclipse Angus Mail) at: https://eclipse-ee4j.github.io/angus-mail/
 */

import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.util.MailUtil

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage

import org.slf4j.Logger
import org.slf4j.LoggerFactory


Logger logger = LoggerFactory.getLogger("org.moqui.impl.sendEmailMessage")
ExecutionContextImpl ec = context.ec

try {

    EntityValue emailMessage = ec.entity.find("moqui.basic.email.EmailMessage").condition("emailMessageId", emailMessageId).one()
    if (emailMessage == null) { ec.message.addError(ec.resource.expand('No EmailMessage record found for ID ${emailMessageId}','')); return }
    String statusId = emailMessage.statusId
    if (statusId == 'ES_DRAFT') ec.message.addError(ec.resource.expand('Email Message ${emailMessageId} is in Draft status',''))
    if (statusId == 'ES_CANCELLED') ec.message.addError(ec.resource.expand('Email Message ${emailMessageId} is Cancelled',''))

    String bodyHtml = emailMessage.body
    String bodyText = emailMessage.bodyText
    String fromAddress = emailMessage.fromAddress
    String toAddresses = emailMessage.toAddresses
    String ccAddresses = emailMessage.ccAddresses
    String bccAddresses = emailMessage.bccAddresses

    if (!bodyHtml && !bodyText) ec.message.addError(ec.resource.expand('Email Message ${emailMessageId} has no body',''))
    if (!fromAddress) ec.message.addError(ec.resource.expand('Email Message ${emailMessageId} has no from address',''))
    if (!toAddresses) ec.message.addError(ec.resource.expand('Email Message ${emailMessageId} has no to address',''))
    if (ec.message.hasError()) return

    EntityValue emailTemplate = (EntityValue) emailMessage.template

    EntityValue emailServer = (EntityValue) emailMessage.server
    if (emailServer == null) { ec.message.addError(ec.resource.expand('No Email Server record found for Email Message ${emailMessageId}','')); return }
    if (!emailServer.smtpHost) {
        logger.warn("SMTP Host is empty for EmailServer ${emailServer.emailServerId}, not sending email message ${emailMessageId}")
        // logger.warn("SMTP Host is empty for EmailServer ${emailServer.emailServerId}, not sending email:\nbodyHtml:\n${bodyHtml}\nbodyText:\n${bodyText}")
        return
    }

    String host = emailServer.smtpHost
    int port = (emailServer.smtpPort ?: "25") as int
    if (!org.moqui.util.WebUtilities.hostAllowedByConf(host, org.moqui.util.SystemBinding.getPropOrEnv("email_allowed_hosts"))) {
        ec.message.addError(ec.resource.expand('Email host ${host} is not in email_allowed_hosts', '', [host:host]))
        return
    }

    // reply to, bounce addresses
    List<String> replyToList = []
    if (emailTemplate?.replyToAddresses) {
        def rtList = ((String) emailTemplate.replyToAddresses).split(",")
        for (address in rtList) replyToList.add(address.trim())
    }
    String bounceAddress = (String) emailTemplate?.bounceAddress

    // prep list of allowed to domains, if configured
    String allowedToDomains = emailServer.allowedToDomains
    ArrayList<String> toDomainList = null
    List<String> skippedToAddresses = null
    if (allowedToDomains) {
        toDomainList = new ArrayList<>(allowedToDomains.split(",").collect({ it.trim() }))
        skippedToAddresses = []
    }

    // set to, cc, bcc addresses
    List<String> toAddressList = [], ccAddressList = [], bccAddressList = []
    def toList = ((String) toAddresses).split(",")
    for (toAddress in toList) {
        if (MailUtil.isDomainAllowed(toAddress, toDomainList)) toAddressList.add(toAddress.trim())
        else skippedToAddresses.add(toAddress)
    }
    if (ccAddresses) {
        def ccList = ((String) ccAddresses).split(",")
        for (ccAddress in ccList) {
            if (MailUtil.isDomainAllowed(ccAddress, toDomainList)) ccAddressList.add(ccAddress.trim())
            else skippedToAddresses.add(ccAddress)
        }
    }
    if (bccAddresses) {
        def bccList = ((String) bccAddresses).split(",")
        for (def bccAddress in bccList) {
            if (MailUtil.isDomainAllowed(bccAddress, toDomainList)) bccAddressList.add(bccAddress.trim())
            else skippedToAddresses.add(bccAddress)
        }
    }

    if (!toAddressList) {
        logger.warn("Not sending EmailMessage ${emailMessageId} with no To Addresses; To, CC, BCC addresses skipped because domain not allowed: ${skippedToAddresses} allowed domains: ${toDomainList}")
        ec.message.addMessage("Not sending email message with no To Address; address(es) skipped because domain not allowed: ${skippedToAddresses}", "warning")
        return
    } else if (skippedToAddresses) {
        logger.warn("Sending EmailMessage ${emailMessageId} to remaining To Address(es) ${toAddressList}; some To, CC, BCC addresses skipped because domain not allowed: ${skippedToAddresses} allowed domains: ${toDomainList}")
    }

    Session session = MailUtil.makeSmtpSession(emailServer, bounceAddress)
    // session.setDebug(true)
    MimeMessage message = MailUtil.buildMessage(session, (String) emailMessage.subject, fromAddress, (String) emailMessage.fromName,
            toAddressList, ccAddressList, bccAddressList, replyToList, bodyHtml, bodyText, null)

    if (logger.infoEnabled) logger.info("Sending email [${emailMessage.subject}] from ${fromAddress} to ${toAddressList} cc ${ccAddressList} bcc ${bccAddressList} via ${emailServer.mailUsername}@${host}:${port} SSL? ${emailServer.smtpSsl == 'Y'} StartTLS? ${emailServer.smtpStartTls == 'Y'} OAuth2? ${MailUtil.isOAuth(emailServer)}")
    if (logger.traceEnabled) logger.trace("Sending email [${emailMessage.subject}] to ${toAddressList} with bodyHtml:\n${bodyHtml}\nbodyText:\n${bodyText}")

    // send the email
    try {
        messageId = MailUtil.send(message, emailServer)
        if (statusId in ['ES_READY', 'ES_BOUNCED']) {
            ec.service.sync().name("update", "moqui.basic.email.EmailMessage").requireNewTransaction(true)
                    .parameters([emailMessageId:emailMessageId, sentDate:ec.user.nowTimestamp, statusId:"ES_SENT", messageId:messageId])
                    .disableAuthz().call()
        }
    } catch (Throwable t) {
        logger.error("Error in sendEmailTemplate", t)
        ec.message.addMessage("Error sending email: ${t.toString()}")
    }

    return
} catch (Throwable t) {
    logger.error("Error in sendEmailTemplate", t)
    ec.message.addMessage("Error sending email: ${t.toString()}")
    // don't rethrow: throw new BaseArtifactException("Error in sendEmailTemplate", t)
}
