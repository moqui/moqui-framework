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

import javax.mail.util.ByteArrayDataSource
import javax.activation.DataSource
import org.moqui.BaseException
import org.moqui.impl.context.ExecutionContextImpl

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.xml.transform.stream.StreamSource

Logger logger = LoggerFactory.getLogger("org.moqui.impl.sendEmailTemplate")

try {
    ExecutionContextImpl ec = context.ec

    // logger.info("sendEmailTemplate with emailTemplateId [${emailTemplateId}], bodyParameters [${bodyParameters}]")

    // add the bodyParameters to the context so they are available throughout this script
    if (bodyParameters) context.putAll(bodyParameters)

    def emailTemplate = ec.entity.find("moqui.basic.email.EmailTemplate").condition("emailTemplateId", emailTemplateId).one()
    if (emailTemplate == null) ec.message.addError(ec.resource.expand('No EmailTemplate record found for ID [${emailTemplateId}]',''))
    if (ec.message.hasError()) return

    // combine ccAddresses and bccAddresses
    if (ccAddresses) {
        if (emailTemplate.ccAddresses) ccAddresses = ccAddresses + "," + emailTemplate.ccAddresses
    } else { ccAddresses = emailTemplate.ccAddresses }
    if (bccAddresses) {
        if (emailTemplate.bccAddresses) bccAddresses = bccAddresses + "," + emailTemplate.bccAddresses
    } else { bccAddresses = emailTemplate.bccAddresses }

    // prepare the fromAddress, fromName, subject; no type or def so that they go into the context for templates
    fromAddress = ec.resource.expand((String) emailTemplate.fromAddress, "")
    fromName = ec.resource.expand((String) emailTemplate.fromName, "")
    subject = ec.resource.expand((String) emailTemplate.subject, "")

    // create an moqui.basic.email.EmailMessage record with info about this sent message
    // NOTE: can do anything with? purposeEnumId
    if (createEmailMessage) {
        Map cemParms = [statusId:"ES_DRAFT", subject:subject,
                        fromAddress:fromAddress, toAddresses:toAddresses, ccAddresses:ccAddresses, bccAddresses:bccAddresses,
                        contentType:"text/html", emailTypeEnumId:emailTypeEnumId,
                        emailTemplateId:emailTemplateId, emailServerId:emailTemplate.emailServerId,
                        fromUserId:(fromUserId ?: ec.user?.userId), toUserId:toUserId]
        Map cemResults = ec.service.sync().name("create", "moqui.basic.email.EmailMessage").requireNewTransaction(true)
                .parameters(cemParms).disableAuthz().call()
        emailMessageId = cemResults.emailMessageId
    }

    // prepare the html message
    def bodyRender = ec.screen.makeRender().rootScreen((String) emailTemplate.bodyScreenLocation)
            .webappName((String) emailTemplate.webappName).renderMode("html")
    String bodyHtml = bodyRender.render()

    // prepare the alternative plain text message
    // render screen with renderMode=text for this
    def bodyTextRender = ec.screen.makeRender().rootScreen((String) emailTemplate.bodyScreenLocation)
            .webappName((String) emailTemplate.webappName).renderMode("text")
    String bodyText = bodyTextRender.render()

    if (emailMessageId) {
        ec.service.sync().name("update", "moqui.basic.email.EmailMessage").requireNewTransaction(true)
                .parameters([emailMessageId:emailMessageId, statusId:"ES_READY", body:bodyHtml, bodyText:bodyText])
                .disableAuthz().call()
    }

    def emailTemplateAttachmentList = emailTemplate."moqui.basic.email.EmailTemplateAttachment"
    emailServer = emailTemplate."moqui.basic.email.EmailServer"

    // check a couple of required fields
    if (!emailServer) ec.message.addError(ec.resource.expand('No EmailServer record found for EmailTemplate ${emailTemplateId}',''))
    if (emailTemplate && !fromAddress) ec.message.addError(ec.resource.expand('From address is empty for EmailTemplate ${emailTemplateId}',''))
    if (ec.message.hasError()) {
        logger.info("Error sending email: ${ec.message.getErrorsString()}\nbodyHtml:\n${bodyHtml}\nbodyText:\n${bodyText}")
        if (emailMessageId) logger.info("Email with error saved as Ready in EmailMessage [${emailMessageId}]")
        return
    }
    if (emailServer && !emailServer.smtpHost) {
        logger.warn("SMTP Host is empty for EmailServer ${emailServer.emailServerId}, not sending email ${emailMessageId} template ${emailTemplateId}")
        // logger.warn("SMTP Host is empty for EmailServer ${emailServer.emailServerId}, not sending email:\nbodyHtml:\n${bodyHtml}\nbodyText:\n${bodyText}")
        return
    }

    String host = emailServer.smtpHost
    int port = (emailServer.smtpPort ?: "25") as int

    HtmlEmail email = new HtmlEmail()
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
        // email.setSslSmtpPort(port as String)
        // email.setSSLCheckServerIdentity(true)
    }

    // set the subject
    email.setSubject(subject)

    // set from, reply to, bounce addresses
    email.setFrom(fromAddress, fromName)
    if (emailTemplate.replyToAddresses) {
        def rtList = ((String) emailTemplate.replyToAddresses).split(",")
        for (address in rtList) email.addReplyTo(address.trim())
    }
    if (emailTemplate.bounceAddress) email.setBounceAddress((String) emailTemplate.bounceAddress)

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
    //email.setTextMsg("Your email client does not support HTML messages")

    for (emailTemplateAttachment in emailTemplateAttachmentList) {
        if (emailTemplateAttachment.screenRenderMode) {
            def attachmentRender = ec.screen.makeRender().rootScreen((String) emailTemplateAttachment.attachmentLocation)
                    .webappName((String) emailTemplate.webappName).renderMode((String) emailTemplateAttachment.screenRenderMode)
            String attachmentText = attachmentRender.render()
            if (emailTemplateAttachment.screenRenderMode == "xsl-fo") {
                // use ResourceFacade.xslFoTransform() to change to PDF, then attach that
                try {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream()
                    ec.resource.xslFoTransform(new StreamSource(new StringReader(attachmentText)), null, baos, "application/pdf")
                    email.attach(new ByteArrayDataSource(baos.toByteArray(), "application/pdf"), (String) emailTemplateAttachment.fileName, "")
                } catch (Exception e) {
                    logger.warn("Error generating PDF from XSL-FO: ${e.toString()}")
                }
            } else {
                String mimeType = ec.screen.getMimeTypeByMode(emailTemplateAttachment.screenRenderMode)
                DataSource dataSource = new ByteArrayDataSource(attachmentText, mimeType)
                email.attach(dataSource, (String) emailTemplateAttachment.fileName, "")
            }
        } else {
            // not a screen, get straight data with type depending on extension
            DataSource dataSource = ec.resource.getLocationDataSource((String) emailTemplateAttachment.attachmentLocation)
            email.attach(dataSource, (String) emailTemplateAttachment.fileName, "")
        }
    }

    if (logger.infoEnabled) logger.info("Sending email [${email.getSubject()}] from ${email.getFromAddress()} to ${email.getToAddresses()} cc ${email.getCcAddresses()} bcc ${email.getBccAddresses()} via ${emailServer.mailUsername}@${email.getHostName()}:${email.getSmtpPort()} SSL? ${email.isSSLOnConnect()}:${email.isSSLCheckServerIdentity()} TLS? ${email.isStartTLSEnabled()}:${email.isStartTLSRequired()}")
    if (logger.traceEnabled) logger.trace("Sending email [${email.getSubject()}] to ${email.getToAddresses()} with bodyHtml:\n${bodyHtml}\nbodyText:\n${bodyText}")
    // email.setDebug(true)

    // send the email
    messageId = email.send()

    if (emailMessageId) {
        ec.service.sync().name("update", "moqui.basic.email.EmailMessage").requireNewTransaction(true)
                .parameters([emailMessageId:emailMessageId, sentDate:ec.user.nowTimestamp, statusId:"ES_SENT", messageId:messageId])
                .disableAuthz().call()
    }

    return
} catch (Throwable t) {
    logger.info("Error in sendEmailTemplate groovy", t)
    throw new BaseException("Error in sendEmailTemplate", t)
}
