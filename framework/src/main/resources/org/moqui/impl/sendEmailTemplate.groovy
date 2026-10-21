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

import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.util.MailUtil

import jakarta.activation.DataSource
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import jakarta.mail.util.ByteArrayDataSource
import javax.xml.transform.stream.StreamSource

import org.slf4j.Logger
import org.slf4j.LoggerFactory


Logger logger = LoggerFactory.getLogger("org.moqui.impl.sendEmailTemplate")
ExecutionContextImpl ec = context.ec

try {
    // logger.info("sendEmailTemplate with emailTemplateId [${emailTemplateId}], bodyParameters [${bodyParameters}]")

    // add the bodyParameters to the context so they are available throughout this script
    if (bodyParameters) context.putAll(bodyParameters)

    EntityValue emailTemplate = ec.entity.find("moqui.basic.email.EmailTemplate").condition("emailTemplateId", emailTemplateId).one()
    if (emailTemplate == null) ec.message.addError(ec.resource.expand('No EmailTemplate record found for ID [${emailTemplateId}]',''))
    if (ec.message.hasError()) return

    emailTypeEnumId = emailTypeEnumId ?: emailTemplate.emailTypeEnumId

    // combine ccAddresses and bccAddresses
    if (ccAddresses) {
        if (emailTemplate.ccAddresses) ccAddresses = ccAddresses + "," + emailTemplate.ccAddresses
    } else { ccAddresses = emailTemplate.ccAddresses }
    if (bccAddresses) {
        if (emailTemplate.bccAddresses) bccAddresses = bccAddresses + "," + emailTemplate.bccAddresses
    } else { bccAddresses = emailTemplate.bccAddresses }

    // prepare the fromAddress, fromName, subject, etc; no type or def so that they go into the context for templates
    fromAddress = ec.resource.expand((String) emailTemplate.fromAddress, "")
    fromName = ec.resource.expand((String) emailTemplate.fromName, "")
    subject = ec.resource.expand((String) emailTemplate.subject, "")
    webappName = (String) emailTemplate.webappName ?: "webroot"
    webHostName = (String) emailTemplate.webHostName

    // create an moqui.basic.email.EmailMessage record with info about this sent message
    // NOTE: can do anything with? purposeEnumId
    if (createEmailMessage) {
        Map cemParms = [statusId:"ES_DRAFT", subject:subject,
                        fromAddress:fromAddress, fromName:fromName, toAddresses:toAddresses, ccAddresses:ccAddresses, bccAddresses:bccAddresses,
                        contentType:"text/html", emailTypeEnumId:emailTypeEnumId,
                        emailTemplateId:emailTemplateId, emailServerId:emailTemplate.emailServerId,
                        fromUserId:(fromUserId ?: ec.user?.userId), toUserId:toUserId]
        Map cemResults = ec.service.sync().name("create", "moqui.basic.email.EmailMessage").requireNewTransaction(true)
                .parameters(cemParms).disableAuthz().call()
        emailMessageId = cemResults.emailMessageId
    }

    // prepare the html message
    def bodyRender = ec.screen.makeRender().rootScreen((String) emailTemplate.bodyScreenLocation)
            .webappName(webappName).renderMode("html")
    String bodyHtml = bodyRender.render()

    // prepare the alternative plain text message
    // render screen with renderMode=text for this
    def bodyTextRender = ec.screen.makeRender().rootScreen((String) emailTemplate.bodyScreenLocation)
            .webappName(webappName).renderMode("text")
    String bodyText = bodyTextRender.render()

    if (emailMessageId) {
        ec.service.sync().name("update", "moqui.basic.email.EmailMessage").requireNewTransaction(true)
                .parameters([emailMessageId:emailMessageId, statusId:"ES_READY", body:bodyHtml, bodyText:bodyText])
                .disableAuthz().call()
    }

    EntityList emailTemplateAttachmentList = (EntityList) emailTemplate.attachments
    emailServer = (EntityValue) emailTemplate.server

    // check a couple of required fields
    if (emailServer == null) ec.message.addError(ec.resource.expand('No EmailServer record found for EmailTemplate ${emailTemplateId}',''))
    if (!fromAddress) ec.message.addError(ec.resource.expand('From address is empty for EmailTemplate ${emailTemplateId}',''))
    if (ec.message.hasError()) {
        logger.info("Error sending email: ${ec.message.getErrorsString()}\nsubject: ${subject}\nbodyHtml:\n${bodyHtml}\nbodyText:\n${bodyText}")
        if (emailMessageId) logger.info("Email with error saved as Ready in EmailMessage [${emailMessageId}]")
        return
    }
    if (!emailServer.smtpHost) {
        logger.warn("SMTP Host is empty for EmailServer ${emailServer.emailServerId}, not sending email ${emailMessageId} template ${emailTemplateId}")
        // logger.warn("SMTP Host is empty for EmailServer ${emailServer.emailServerId}, not sending email:\nbodyHtml:\n${bodyHtml}\nbodyText:\n${bodyText}")
        return
    }

    String smtpHost = emailServer.smtpHost
    int smtpPort = (emailServer.smtpPort ?: "25") as int
    if (!org.moqui.util.WebUtilities.hostAllowedByConf(smtpHost, org.moqui.util.SystemBinding.getPropOrEnv("email_allowed_hosts"))) {
        ec.message.addError(ec.resource.expand('Email host ${host} is not in email_allowed_hosts', '', [host:smtpHost]))
        return
    }

    // reply to, bounce addresses
    List<String> replyToList = []
    if (emailTemplate.replyToAddresses) {
        def rtList = ((String) emailTemplate.replyToAddresses).split(",")
        for (address in rtList) replyToList.add(address.trim())
    }
    String bounceAddress = (String) emailTemplate.bounceAddress

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
        logger.warn("Not sending EmailMessage ${emailMessageId} for Template ${emailTemplateId} with no To Addresses; To, CC, BCC addresses skipped because domain not allowed: ${skippedToAddresses} allowed domains: ${toDomainList}")
        ec.message.addMessage("Not sending email message with no To Address; address(es) skipped because domain not allowed: ${skippedToAddresses}", "warning")
        return
    } else if (skippedToAddresses) {
        logger.warn("Sending EmailMessage ${emailMessageId} for Template ${emailTemplateId} to remaining To Address(es) ${toAddressList}; some To, CC, BCC addresses skipped because domain not allowed: ${skippedToAddresses} allowed domains: ${toDomainList}")
    }

    // attachments, each a Map with dataSource and fileName
    List<Map<String, Object>> attachmentList = []

    // parameter attachments
    if (attachments instanceof List) for (Map attachmentInfo in attachments) {
        String filename = ec.resourceFacade.expand((String) attachmentInfo.fileName, null)
        if (attachmentInfo.contentText) {
            String mimeType = (String) attachmentInfo.contentType ?: ec.resourceFacade.getContentType(filename) ?: "text/plain"
            DataSource dataSource = new ByteArrayDataSource(attachmentInfo.contentText.toString(), mimeType)
            attachmentList.add([dataSource:dataSource, fileName:filename])
        } else if (attachmentInfo.contentBytes) {
            String mimeType = (String) attachmentInfo.contentType ?: ec.resourceFacade.getContentType(filename) ?: "application/octet-stream"
            DataSource dataSource = new ByteArrayDataSource((byte[]) attachmentInfo.contentBytes, mimeType)
            attachmentList.add([dataSource:dataSource, fileName:attachmentInfo.fileName])
        } else if (attachmentInfo.screenRenderMode && (attachmentInfo.attachmentLocation || attachmentInfo.screenPath)) {
            renderScreenAttachment(emailTemplate, attachmentList, ec, logger, filename,
                    (String) attachmentInfo.screenRenderMode, (String) attachmentInfo.attachmentLocation,
                    (String) attachmentInfo.screenPath, (String) attachmentInfo.contentType)
        } else if (attachmentInfo.attachmentLocation) {
            // not a screen, get straight data with type depending on extension
            DataSource dataSource = ec.resource.getLocationDataSource((String) attachmentInfo.attachmentLocation)
            attachmentList.add([dataSource:dataSource, fileName:attachmentInfo.fileName])
        } else {
            logger.error("Attachment info invalid for email template ${emailTemplateId} to ${toList} subject '${subject}': ${attachmentInfo}")
        }
    }

    // DB configured attachments
    for (EntityValue emailTemplateAttachment in emailTemplateAttachmentList) {
        // check attachmentCondition if there is one
        String attachmentCondition = (String) emailTemplateAttachment.attachmentCondition
        if (attachmentCondition && !ec.resourceFacade.condition(attachmentCondition, null)) continue
        // if screenRenderMode render attachment, otherwise just get attachment from location
        if (emailTemplateAttachment.screenRenderMode) {
            String forEachIn = (String) emailTemplateAttachment.forEachIn
            if (forEachIn) {
                Collection forEachCol = (Collection) ec.resourceFacade.expression(forEachIn, null)
                if (forEachCol) for (Object forEachEntry in forEachCol) {
                    ec.contextStack.push()
                    try {
                        if (forEachEntry instanceof Map) { ec.contextStack.putAll((Map) forEachEntry) }
                        else { ec.contextStack.put("forEachEntry", forEachEntry) }

                        renderScreenAttachment(emailTemplate, emailTemplateAttachment, attachmentList, ec, logger)
                    } finally {
                        ec.contextStack.pop()
                    }
                }
            } else {
                renderScreenAttachment(emailTemplate, emailTemplateAttachment, attachmentList, ec, logger)
            }
        } else {
            // not a screen, get straight data with type depending on extension
            DataSource dataSource = ec.resource.getLocationDataSource((String) emailTemplateAttachment.attachmentLocation)
            attachmentList.add([dataSource:dataSource, fileName:emailTemplateAttachment.fileName])
        }
    }

    Session session = MailUtil.makeSmtpSession(emailServer, bounceAddress)
    // session.setDebug(true)
    MimeMessage message = MailUtil.buildMessage(session, (String) subject, (String) fromAddress, (String) fromName,
            toAddressList, ccAddressList, bccAddressList, replyToList, bodyHtml, bodyText, attachmentList)

    if (logger.infoEnabled) logger.info("Sending email [${subject}] from ${fromAddress} to ${toAddressList} cc ${ccAddressList} bcc ${bccAddressList} via ${emailServer.mailUsername}@${smtpHost}:${smtpPort} SSL? ${emailServer.smtpSsl == 'Y'} StartTLS? ${emailServer.smtpStartTls == 'Y'} OAuth2? ${MailUtil.isOAuth(emailServer)}")
    if (logger.traceEnabled) logger.trace("Sending email [${subject}] to ${toAddressList} with bodyHtml:\n${bodyHtml}\nbodyText:\n${bodyText}")

    // send the email
    try {
        messageId = MailUtil.send(message, emailServer)
        // if we created an EmailMessage record update it now with the messageId
        if (emailMessageId) {
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

static void renderScreenAttachment(EntityValue emailTemplate, EntityValue emailTemplateAttachment, List<Map<String, Object>> attachmentList, ExecutionContextImpl ec, Logger logger) {
    renderScreenAttachment(emailTemplate, attachmentList, ec, logger, (String) emailTemplateAttachment.fileName,
            (String) emailTemplateAttachment.screenRenderMode, (String) emailTemplateAttachment.attachmentLocation,
            (String) emailTemplateAttachment.screenPath, null)
}
static void renderScreenAttachment(EntityValue emailTemplate, List<Map<String, Object>> attachmentList, ExecutionContextImpl ec, Logger logger,
        String filename, String renderMode, String attachmentLocation, String screenPath, String contentType) {

    if (!filename) {
        String extension = renderMode == "xsl-fo" ? "pdf" : renderMode
        filename = attachmentLocation.substring(attachmentLocation.lastIndexOf("/")+1, attachmentLocation.length()-4) + "." + extension
    }
    String filenameExp = ec.resource.expand(filename, null)

    String webappName = (String) emailTemplate.webappName ?: "webroot"
    String webHostName = (String) emailTemplate.webHostName

    def attachmentRender
    if (screenPath == null || screenPath.isEmpty()) {
        attachmentRender = ec.screen.makeRender().rootScreen(attachmentLocation).webappName(webappName).renderMode(renderMode)
    } else {
        attachmentRender = ec.screen.makeRender().webappName(webappName).rootScreenFromHost(webHostName ?: "localhost")
                .screenPath(screenPath).renderMode(renderMode).lastStandalone("true")
    }

    if (ec.screenFacade.isRenderModeText(renderMode)) {
        String attachmentText = attachmentRender.render()
        if (attachmentText == null) return
        if (attachmentText.trim().length() == 0) return

        if (renderMode == "xsl-fo") {
            // use ResourceFacade.xslFoTransform() to change to PDF, then attach that
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream()
                ec.resource.xslFoTransform(new StreamSource(new StringReader(attachmentText)), null, baos, "application/pdf")
                attachmentList.add([dataSource:new ByteArrayDataSource(baos.toByteArray(), "application/pdf"), fileName:filenameExp])
            } catch (Exception e) {
                logger.warn("Error generating PDF from XSL-FO: ${e.toString()}")
            }
        } else {
            String mimeType = contentType ?: ec.screenFacade.getMimeTypeByMode(renderMode)
            DataSource dataSource = new ByteArrayDataSource(attachmentText, mimeType)
            attachmentList.add([dataSource:dataSource, fileName:filenameExp])
        }
    } else {
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        attachmentRender.render(baos)

        String mimeType = contentType ?: ec.screenFacade.getMimeTypeByMode(renderMode)
        DataSource dataSource = new ByteArrayDataSource(baos.toByteArray(), mimeType)
        attachmentList.add([dataSource:dataSource, fileName:filenameExp])
    }
}
