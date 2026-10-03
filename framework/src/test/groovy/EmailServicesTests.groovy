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

import spock.lang.*

import org.moqui.Moqui
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue
import org.moqui.impl.util.MailUtil
import org.subethamail.smtp.auth.EasyAuthenticationHandlerFactory
import org.subethamail.smtp.auth.LoginFailedException
import org.subethamail.smtp.server.SMTPServer

import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.util.concurrent.CopyOnWriteArrayList

class EmailServicesTests extends Specification {
    @Shared ExecutionContext ec
    @Shared SMTPServer smtpServer
    @Shared List<MimeMessage> received = new CopyOnWriteArrayList<>()

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        int port
        new ServerSocket(0).withCloseable { port = it.getLocalPort() }
        smtpServer = SMTPServer.port(port)
                .messageHandler({ context, from, to, data ->
                    received.add(new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(data))) })
                .authenticationHandlerFactory(new EasyAuthenticationHandlerFactory({ username, password, context ->
                    if (username != "mailtest" || password != "mailtest-pw") throw new LoginFailedException() }))
                .showAuthCapabilitiesBeforeSTARTTLS(true).requireAuth().build()
        smtpServer.start()
        // send#EmailMessage requires an authenticated user
        ec.user.loginUser("john.doe", "moqui")

        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.basic.email.EmailServer").setAll([emailServerId:"TEST_SMTP", smtpHost:"localhost",
                smtpPort:port as String, smtpStartTls:"N", smtpSsl:"N", mailUsername:"mailtest", mailPassword:"mailtest-pw"]).createOrUpdate()
        ec.entity.makeValue("moqui.basic.email.EmailTemplate").setAll([emailTemplateId:"TEST_EMAIL", emailServerId:"TEST_SMTP",
                bodyScreenLocation:"classpath://screen/SingleUseCode.xml", webappName:"webroot",
                fromAddress:"sender@example.com", fromName:"Sender Name", subject:"Test code éè",
                replyToAddresses:"reply@example.com"]).createOrUpdate()
        ec.artifactExecution.enableAuthz()
    }

    def cleanupSpec() {
        smtpServer?.stop()
        ec.artifactExecution.disableAuthz()
        ec.entity.find("moqui.basic.email.EmailMessage").condition("emailServerId", "TEST_SMTP").deleteAll()
        ec.entity.find("moqui.basic.email.EmailTemplate").condition("emailTemplateId", "TEST_EMAIL").deleteAll()
        ec.entity.find("moqui.basic.email.EmailServer").condition("emailServerId", "TEST_SMTP").deleteAll()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    def setup() { received.clear(); ec.message.clearAll() }

    def "send EmailTemplate with html and text body and attachment"() {
        when:
        Map result = ec.service.sync().name("org.moqui.impl.EmailServices.send#EmailTemplate")
                .parameters([emailTemplateId:"TEST_EMAIL", toAddresses:"to@example.com", ccAddresses:"cc@example.com",
                    bodyParameters:[code:"123456"],
                    attachments:[[fileName:"notes.txt", contentText:"attached text", contentType:"text/plain"]]])
                .disableAuthz().call()
        MimeMessage msg = received ? received[0] : null
        EntityValue emailMessage = ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", result.emailMessageId).disableAuthz().one()

        then:
        !ec.message.hasError()
        received.size() == 1
        msg.getSubject() == "Test code éè"
        msg.getFrom()[0].toString().contains("sender@example.com")
        msg.getReplyTo()[0].toString() == "reply@example.com"
        msg.getRecipients(jakarta.mail.Message.RecipientType.CC)[0].toString() == "cc@example.com"
        msg.isMimeType("multipart/mixed")
        ((Multipart) msg.getContent()).getCount() == 2
        ((Multipart) msg.getContent()).getBodyPart(0).isMimeType("multipart/alternative")
        bodyOf(msg, "text/html").contains("123456")
        bodyOf(msg, "text/plain").contains("123456")
        ((Multipart) msg.getContent()).getBodyPart(1).getFileName() == "notes.txt"
        Part.ATTACHMENT.equalsIgnoreCase(((Multipart) msg.getContent()).getBodyPart(1).getDisposition())
        emailMessage.statusId == "ES_SENT"
        emailMessage.messageId == msg.getMessageID()
    }

    def "send EmailMessage with html and text body"() {
        when:
        Map cem = ec.service.sync().name("create", "moqui.basic.email.EmailMessage").parameters([statusId:"ES_READY",
                subject:"Plain message", fromAddress:"sender@example.com", toAddresses:"to@example.com",
                body:"<html><body><p>Hello html</p></body></html>", bodyText:"Hello text", emailServerId:"TEST_SMTP"])
                .disableAuthz().call()
        ec.service.sync().name("org.moqui.impl.EmailServices.send#EmailMessage")
                .parameters([emailMessageId:cem.emailMessageId]).disableAuthz().call()
        MimeMessage msg = received ? received[0] : null
        EntityValue emailMessage = ec.entity.find("moqui.basic.email.EmailMessage")
                .condition("emailMessageId", cem.emailMessageId).disableAuthz().one()

        then:
        !ec.message.hasError()
        received.size() == 1
        msg.isMimeType("multipart/alternative")
        bodyOf(msg, "text/html").contains("Hello html")
        bodyOf(msg, "text/plain").contains("Hello text")
        emailMessage.statusId == "ES_SENT"
        emailMessage.messageId == msg.getMessageID()
    }

    def "wrong password is not sent"() {
        when:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.basic.email.EmailServer").setAll([emailServerId:"TEST_SMTP", mailPassword:"wrong"]).update()
        ec.artifactExecution.enableAuthz()
        ec.service.sync().name("org.moqui.impl.EmailServices.send#EmailTemplate")
                .parameters([emailTemplateId:"TEST_EMAIL", toAddresses:"to@example.com", bodyParameters:[code:"1"]])
                .disableAuthz().call()

        then:
        received.size() == 0
        ec.message.getMessagesString().contains("Error sending email")

        cleanup:
        ec.artifactExecution.disableAuthz()
        ec.entity.makeValue("moqui.basic.email.EmailServer").setAll([emailServerId:"TEST_SMTP", mailPassword:"mailtest-pw"]).update()
        ec.artifactExecution.enableAuthz()
        ec.message.clearAll()
    }

    def "OAuth2 EmailServer uses XOAUTH2"() {
        when:
        EntityValue es = ec.entity.makeValue("moqui.basic.email.EmailServer").setAll([emailServerId:"TEST_OAUTH",
                smtpHost:"smtp.gmail.com", smtpPort:"587", smtpStartTls:"Y", mailUsername:"user@gmail.com",
                oauthClientId:"client", oauthClientSecret:"secret", oauthRefreshToken:"refresh"])
        Properties smtpProps = MailUtil.makeSmtpSession(es, "bounce@example.com").getProperties()
        Properties imapProps = MailUtil.makeStoreSession(es, "imaps").getProperties()

        then:
        MailUtil.isOAuth(es)
        smtpProps.getProperty("mail.smtp.auth.mechanisms") == "XOAUTH2"
        smtpProps.getProperty("mail.smtp.auth") == "true"
        smtpProps.getProperty("mail.smtp.starttls.enable") == "true"
        smtpProps.getProperty("mail.smtp.from") == "bounce@example.com"
        imapProps.getProperty("mail.imaps.auth.mechanisms") == "XOAUTH2"
        !MailUtil.isOAuth(ec.entity.makeValue("moqui.basic.email.EmailServer").setAll([emailServerId:"X", mailPassword:"pw"]))
    }

    def "OAuth2 token URL must be https"() {
        when:
        MailUtil.getAccessToken(ec.entity.makeValue("moqui.basic.email.EmailServer").setAll([emailServerId:"TEST_OAUTH_HTTP",
                oauthTokenUrl:"http://localhost/token", oauthRefreshToken:"refresh"]))

        then:
        jakarta.mail.MessagingException e = thrown()
        e.message.contains("https")
    }

    static String bodyOf(Part part, String mimeType) {
        if (part.isMimeType(mimeType) && !Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) return (String) part.getContent()
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent()
            for (int i = 0; i < mp.getCount(); i++) {
                String body = bodyOf(mp.getBodyPart(i), mimeType)
                if (body != null) return body
            }
        }
        return null
    }
}
