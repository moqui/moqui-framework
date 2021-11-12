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
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl

import javax.activation.DataSource
import javax.mail.util.ByteArrayDataSource
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

    HtmlEmail email = new HtmlEmail()
    email.setCharset("utf-8")
    email.setHostName(smtpHost)
    email.setSmtpPort(smtpPort)
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
        email.setSslSmtpPort(smtpPort as String)
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

    // prep list of allowed to domains, if configured
    String allowedToDomains = emailServer.allowedToDomains
    ArrayList<String> toDomainList = null
    List<String> skippedToAddresses = null
    if (allowedToDomains) {
        toDomainList = new ArrayList<>(allowedToDomains.split(",").collect({ it.trim() }))
        skippedToAddresses = []
    }

    // set to, cc, bcc addresses
    def toList = ((String) toAddresses).split(",")
    for (toAddress in toList) {
        if (isDomainAllowed(toAddress, toDomainList)) email.addTo(toAddress.trim())
        else skippedToAddresses.add(toAddress)
    }
    if (ccAddresses) {
        def ccList = ((String) ccAddresses).split(",")
        for (ccAddress in ccList) {
            if (isDomainAllowed(ccAddress, toDomainList)) email.addCc(ccAddress.trim())
            else skippedToAddresses.add(ccAddress)
        }
    }
    if (bccAddresses) {
        def bccList = ((String) bccAddresses).split(",")
        for (def bccAddress in bccList) {
            if (isDomainAllowed(bccAddress, toDomainList)) email.addBcc(bccAddress.trim())
            else skippedToAddresses.add(bccAddress)
        }
    }

    if (!email.getToAddresses()) {
        logger.warn("Not sending EmailMessage ${emailMessageId} for Template ${emailTemplateId} with no To Addresses; To, CC, BCC addresses skipped because domain not allowed: ${skippedToAddresses} allowed domains: ${toDomainList}")
        ec.message.addMessage("Not sending email message with no To Address; address(es) skipped because domain not allowed: ${skippedToAddresses}", "warning")
        return
    } else if (skippedToAddresses) {
        logger.warn("Sending EmailMessage ${emailMessageId} for Template ${emailTemplateId} to remaining To Address(es) ${email.getToAddresses()}; some To, CC, BCC addresses skipped because domain not allowed: ${skippedToAddresses} allowed domains: ${toDomainList}")
    }

    // set the html message
    if (bodyHtml) email.setHtmlMsg(bodyHtml)
    // set the alternative plain text message
    if (bodyText) email.setTextMsg(bodyText)
    //email.setTextMsg("Your email client does not support HTML messages")

    // parameter attachments
    if (attachments instanceof List) for (Map attachmentInfo in attachments) {
        String filename = ec.resourceFacade.expand((String) attachmentInfo.fileName, null)
        if (attachmentInfo.contentText) {
            String mimeType = (String) attachmentInfo.contentType ?: ec.resourceFacade.getContentType(filename) ?: "text/plain"
            DataSource dataSource = new ByteArrayDataSource(attachmentInfo.contentText.toString(), mimeType)
            email.attach(dataSource, filename, "")
        } else if (attachmentInfo.contentBytes) {
            String mimeType = (String) attachmentInfo.contentType ?: ec.resourceFacade.getContentType(filename) ?: "application/octet-stream"
            DataSource dataSource = new ByteArrayDataSource((byte[]) attachmentInfo.contentBytes, mimeType)
            email.attach(dataSource, (String) attachmentInfo.fileName, "")
        } else if (attachmentInfo.screenRenderMode && (attachmentInfo.attachmentLocation || attachmentInfo.screenPath)) {
            renderScreenAttachment(emailTemplate, email, ec, logger, filename,
                    (String) attachmentInfo.screenRenderMode, (String) attachmentInfo.attachmentLocation,
                    (String) attachmentInfo.screenPath, (String) attachmentInfo.contentType)
        } else if (attachmentInfo.attachmentLocation) {
            // not a screen, get straight data with type depending on extension
            DataSource dataSource = ec.resource.getLocationDataSource((String) attachmentInfo.attachmentLocation)
            email.attach(dataSource, (String) attachmentInfo.fileName, "")
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

                        renderScreenAttachment(emailTemplate, emailTemplateAttachment, email, ec, logger)
                    } finally {
                        ec.contextStack.pop()
                    }
                }
            } else {
                renderScreenAttachment(emailTemplate, emailTemplateAttachment, email, ec, logger)
            }
        } else {
            // not a screen, get straight data with type depending on extension
            DataSource dataSource = ec.resource.getLocationDataSource((String) emailTemplateAttachment.attachmentLocation)
            email.attach(dataSource, (String) emailTemplateAttachment.fileName, "")
        }
    }

    if (logger.infoEnabled) logger.info("Sending email [${email.getSubject()}] from ${email.getFromAddress()} to ${email.getToAddresses()} cc ${email.getCcAddresses()} bcc ${email.getBccAddresses()} via ${emailServer.mailUsername}@${email.getHostName()}:${email.getSmtpPort()} SSL? ${email.isSSLOnConnect()}:${email.isSSLCheckServerIdentity()} StartTLS? ${email.isStartTLSEnabled()}:${email.isStartTLSRequired()}")
    if (logger.traceEnabled) logger.trace("Sending email [${email.getSubject()}] to ${email.getToAddresses()} with bodyHtml:\n${bodyHtml}\nbodyText:\n${bodyText}")
    // email.setDebug(true)

    // send the email
    try {
        messageId = email.send()
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

static void renderScreenAttachment(EntityValue emailTemplate, EntityValue emailTemplateAttachment, HtmlEmail email, ExecutionContextImpl ec, Logger logger) {
    renderScreenAttachment(emailTemplate, email, ec, logger, (String) emailTemplateAttachment.fileName,
            (String) emailTemplateAttachment.screenRenderMode, (String) emailTemplateAttachment.attachmentLocation,
            (String) emailTemplateAttachment.screenPath, null)
}
static void renderScreenAttachment(EntityValue emailTemplate, HtmlEmail email, ExecutionContextImpl ec, Logger logger,
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
                email.attach(new ByteArrayDataSource(baos.toByteArray(), "application/pdf"), filenameExp, "")
            } catch (Exception e) {
                logger.warn("Error generating PDF from XSL-FO: ${e.toString()}")
            }
        } else {
            String mimeType = contentType ?: ec.screenFacade.getMimeTypeByMode(renderMode)
            DataSource dataSource = new ByteArrayDataSource(attachmentText, mimeType)
            email.attach(dataSource, filenameExp, "")
        }
    } else {
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        attachmentRender.render(baos)

        String mimeType = contentType ?: ec.screenFacade.getMimeTypeByMode(renderMode)
        DataSource dataSource = new ByteArrayDataSource(baos.toByteArray(), mimeType)
        email.attach(dataSource, filenameExp, "")
    }
}

static boolean isDomainAllowed(String emailAddress, ArrayList<String> toDomainList) {
    if (emailAddress == null || emailAddress.isEmpty()) return false
    boolean domainAllowed = true
    if (toDomainList != null && !toDomainList.isEmpty()) {
        domainAllowed = false
        int atIndex = emailAddress.indexOf("@")
        if (atIndex == -1) return false
        String emailDomain = emailAddress.substring(atIndex + 1, emailAddress.length())

        for (toDomain in toDomainList) {
            if (emailDomain.endsWith(toDomain)) {
                domainAllowed = true
                break
            }
        }
    }
    return domainAllowed
}
