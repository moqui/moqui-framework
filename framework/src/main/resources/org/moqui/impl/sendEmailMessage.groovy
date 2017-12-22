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
    JavaMail API Documentation at: https://java.net/projects/javamail/pages/Home
    For JavaMail JavaDocs see: https://javamail.java.net/nonav/docs/api/index.html
 */

import org.apache.commons.mail.DefaultAuthenticator
import org.apache.commons.mail.HtmlEmail
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl

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

    HtmlEmail email = new HtmlEmail()
    email.setCharset("utf-8")
    email.setHostName(host)
    email.setSmtpPort(port)
    if (emailServer.mailUsername) {
        email.setAuthenticator(new DefaultAuthenticator((String) emailServer.mailUsername, (String) emailServer.mailPassword))
        // logger.info("Set user=${emailServer.mailUsername}, password=${emailServer.mailPassword}")
    }
    if (emailServer.smtpStartTls == "Y") {
        email.setStartTLSEnabled(true)
        // email.setStartTLSRequired(true)
    }
    if (emailServer.smtpSsl == "Y") {
        email.setSSLOnConnect(true)
        email.setSslSmtpPort(port as String)
        // email.setSSLCheckServerIdentity(true)
    }

    // set the subject
    if (emailMessage.subject) email.setSubject((String) emailMessage.subject)

    // set from, reply to, bounce addresses
    email.setFrom(fromAddress, (String) emailMessage.fromName)
    if (emailTemplate?.replyToAddresses) {
        def rtList = ((String) emailTemplate.replyToAddresses).split(",")
        for (address in rtList) email.addReplyTo(address.trim())
    }
    if (emailTemplate?.bounceAddress) email.setBounceAddress((String) emailTemplate.bounceAddress)

    // set to, cc, bcc addresses
    def toList = ((String) toAddresses).split(",")
    for (toAddress in toList) email.addTo(toAddress.trim())
    if (ccAddresses) {
        def ccList = ((String) ccAddresses).split(",")
        for (ccAddress in ccList) email.addCc(ccAddress.trim())
    }
    if (bccAddresses) {
        def bccList = ((String) bccAddresses).split(",")
        for (def bccAddress in bccList) email.addBcc(bccAddress.trim())
    }

    // set the html message
    if (bodyHtml) email.setHtmlMsg(bodyHtml)
    // set the alternative plain text message
    if (bodyText) email.setTextMsg(bodyText)

    if (logger.infoEnabled) logger.info("Sending email [${email.getSubject()}] from ${email.getFromAddress()} to ${email.getToAddresses()} cc ${email.getCcAddresses()} bcc ${email.getBccAddresses()} via ${emailServer.mailUsername}@${email.getHostName()}:${email.getSmtpPort()} SSL? ${email.isSSLOnConnect()}:${email.isSSLCheckServerIdentity()} StartTLS? ${email.isStartTLSEnabled()}:${email.isStartTLSRequired()}")
    if (logger.traceEnabled) logger.trace("Sending email [${email.getSubject()}] to ${email.getToAddresses()} with bodyHtml:\n${bodyHtml}\nbodyText:\n${bodyText}")
    // email.setDebug(true)

    // send the email
    try {
        messageId = email.send()
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
