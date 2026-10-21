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
package org.moqui.impl.util

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.moqui.entity.EntityValue
import org.moqui.util.SystemBinding
import org.moqui.util.WebUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import jakarta.activation.DataHandler
import jakarta.activation.DataSource
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Outgoing (SMTP) and incoming (IMAP/POP3) mail helpers on top of Jakarta Mail (Eclipse Angus), used by
 * send#EmailTemplate, send#EmailMessage and poll#EmailServer.
 *
 * When EmailServer.oauthRefreshToken is set the server authenticates with OAuth2 (SASL XOAUTH2, as required by
 * Gmail and Microsoft 365) instead of mailPassword: an access token is obtained from oauthTokenUrl (Google when
 * empty) with the refresh_token grant, cached until shortly before it expires, and passed as the password.
 */
@CompileStatic
class MailUtil {
    protected final static Logger logger = LoggerFactory.getLogger(MailUtil.class)

    public static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
    public static final String CHARSET = "UTF-8"
    protected static final int TIMEOUT_MS = 60000

    // emailServerId -> [refreshToken, accessToken, expires]
    protected static final Map<String, Map<String, Object>> tokenCache = new ConcurrentHashMap<>()
    protected static final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

    static boolean isOAuth(EntityValue emailServer) { return emailServer.get("oauthRefreshToken") as boolean }

    /** SMTP Session for an EmailServer; system properties (mail.*) are defaults that can be overridden here. */
    static Session makeSmtpSession(EntityValue emailServer, String bounceAddress) {
        Properties props = new Properties(System.getProperties())
        props.setProperty("mail.transport.protocol", "smtp")
        props.setProperty("mail.smtp.host", (String) emailServer.get("smtpHost"))
        props.setProperty("mail.smtp.port", (String) emailServer.get("smtpPort") ?: "25")
        props.setProperty("mail.smtp.connectiontimeout", TIMEOUT_MS as String)
        props.setProperty("mail.smtp.timeout", TIMEOUT_MS as String)
        if (emailServer.get("mailUsername")) props.setProperty("mail.smtp.auth", "true")
        if (emailServer.get("smtpStartTls") == "Y") props.setProperty("mail.smtp.starttls.enable", "true")
        if (emailServer.get("smtpSsl") == "Y") props.setProperty("mail.smtp.ssl.enable", "true")
        if (isOAuth(emailServer)) props.setProperty("mail.smtp.auth.mechanisms", "XOAUTH2")
        if (bounceAddress) props.setProperty("mail.smtp.from", bounceAddress)
        return Session.getInstance(props)
    }

    /** Store (IMAP/POP3) Session for an EmailServer and protocol (imap, imaps, pop3, pop3s). */
    static Session makeStoreSession(EntityValue emailServer, String protocol) {
        Properties props = new Properties(System.getProperties())
        props.setProperty("mail." + protocol + ".connectiontimeout", TIMEOUT_MS as String)
        props.setProperty("mail." + protocol + ".timeout", TIMEOUT_MS as String)
        if (isOAuth(emailServer)) props.setProperty("mail." + protocol + ".auth.mechanisms", "XOAUTH2")
        return Session.getInstance(props)
    }

    /** The secret to authenticate with: an OAuth2 access token when configured, otherwise mailPassword. */
    static String getPassword(EntityValue emailServer) {
        return isOAuth(emailServer) ? getAccessToken(emailServer) : (String) emailServer.get("mailPassword")
    }

    static String getAccessToken(EntityValue emailServer) {
        String emailServerId = (String) emailServer.get("emailServerId")
        String refreshToken = (String) emailServer.get("oauthRefreshToken")
        Map<String, Object> cached = tokenCache.get(emailServerId)
        if (cached != null && cached.refreshToken == refreshToken && (long) cached.expires > System.currentTimeMillis())
            return (String) cached.accessToken

        String tokenUrl = (String) emailServer.get("oauthTokenUrl") ?: GOOGLE_TOKEN_URL
        URI tokenUri = URI.create(tokenUrl)
        if (tokenUri.getScheme() != "https") throw new MessagingException("OAuth token URL for EmailServer ${emailServerId} must use https")
        if (!WebUtilities.hostAllowedByConf(tokenUri.getHost(), SystemBinding.getPropOrEnv("email_allowed_hosts")))
            throw new MessagingException("OAuth token host ${tokenUri.getHost()} is not in email_allowed_hosts")

        Map<String, String> form = [grant_type: "refresh_token", refresh_token: refreshToken,
                client_id: (String) emailServer.get("oauthClientId"), client_secret: (String) emailServer.get("oauthClientSecret"),
                scope: (String) emailServer.get("oauthScope")]
        String body = form.findAll({ it.value }).collect({ URLEncoder.encode(it.key, CHARSET) + "=" + URLEncoder.encode(it.value, CHARSET) }).join("&")
        HttpRequest request = HttpRequest.newBuilder(tokenUri).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build()
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200)
            throw new MessagingException("OAuth token refresh for EmailServer ${emailServerId} failed (${response.statusCode()}): ${response.body()}")

        Map resp = (Map) new JsonSlurper().parseText(response.body())
        String accessToken = (String) resp.get("access_token")
        if (!accessToken) throw new MessagingException("OAuth token response for EmailServer ${emailServerId} has no access_token")
        long expiresIn = (resp.get("expires_in") ?: 3600) as long
        tokenCache.put(emailServerId, [refreshToken: (Object) refreshToken, accessToken: accessToken,
                expires: System.currentTimeMillis() + (expiresIn - 60) * 1000L])
        return accessToken
    }

    static void clearAccessToken(EntityValue emailServer) { tokenCache.remove((String) emailServer.get("emailServerId")) }

    /**
     * Build a message with an HTML and/or plain text body and attachments:
     * multipart/mixed [ multipart/alternative [ text, html ], attachments... ], with the outer or inner multipart
     * left out when not needed.
     * @param attachments List of Maps with dataSource (DataSource) and fileName (String)
     */
    static MimeMessage buildMessage(Session session, String subject, String fromAddress, String fromName,
            List<String> toList, List<String> ccList, List<String> bccList, List<String> replyToList,
            String bodyHtml, String bodyText, List<Map<String, Object>> attachments) {
        MimeMessage message = new MimeMessage(session)
        message.setFrom(new InternetAddress(fromAddress, fromName ?: null, CHARSET))
        for (String address in toList) message.addRecipient(Message.RecipientType.TO, new InternetAddress(address, true))
        for (String address in ccList) message.addRecipient(Message.RecipientType.CC, new InternetAddress(address, true))
        for (String address in bccList) message.addRecipient(Message.RecipientType.BCC, new InternetAddress(address, true))
        if (replyToList) message.setReplyTo(replyToList.collect({ new InternetAddress(it, true) }) as InternetAddress[])
        message.setSubject(subject, CHARSET)
        message.setSentDate(new Date())

        MimeBodyPart textPart = null
        if (bodyText) { textPart = new MimeBodyPart(); textPart.setText(bodyText, CHARSET) }
        MimeBodyPart htmlPart = null
        if (bodyHtml) { htmlPart = new MimeBodyPart(); htmlPart.setText(bodyHtml, CHARSET, "html") }

        MimeMultipart alternative = null
        MimeBodyPart singleBody = htmlPart ?: textPart
        if (textPart != null && htmlPart != null) {
            alternative = new MimeMultipart("alternative")
            alternative.addBodyPart(textPart)
            alternative.addBodyPart(htmlPart)
        }

        if (attachments) {
            MimeMultipart mixed = new MimeMultipart("mixed")
            if (alternative != null) {
                MimeBodyPart altWrapper = new MimeBodyPart()
                altWrapper.setContent(alternative)
                mixed.addBodyPart(altWrapper)
            } else if (singleBody != null) {
                mixed.addBodyPart(singleBody)
            }
            for (Map<String, Object> attachment in attachments) {
                DataSource dataSource = (DataSource) attachment.dataSource
                MimeBodyPart attachPart = new MimeBodyPart()
                attachPart.setDataHandler(new DataHandler(dataSource))
                attachPart.setDisposition(Part.ATTACHMENT)
                String fileName = (String) attachment.fileName ?: dataSource.getName()
                if (fileName) attachPart.setFileName(fileName)
                mixed.addBodyPart(attachPart)
            }
            message.setContent(mixed)
        } else if (alternative != null) {
            message.setContent(alternative)
        } else if (htmlPart != null) {
            message.setText(bodyHtml, CHARSET, "html")
        } else {
            message.setText(bodyText ?: "", CHARSET)
        }
        return message
    }

    /** Send a message built with buildMessage(); returns the Message-ID header value. */
    static String send(MimeMessage message, EntityValue emailServer) {
        String username = (String) emailServer.get("mailUsername")
        try {
            sendOnce(message, username, emailServer)
        } catch (AuthenticationFailedException e) {
            if (!isOAuth(emailServer)) throw e
            // cached access token may have been revoked before it expired, get a new one and retry once
            logger.info("OAuth2 authentication failed for EmailServer ${emailServer.get('emailServerId')}, retrying with new access token: ${e.toString()}")
            clearAccessToken(emailServer)
            sendOnce(message, username, emailServer)
        }
        return message.getMessageID()
    }
    protected static void sendOnce(MimeMessage message, String username, EntityValue emailServer) {
        if (username) Transport.send(message, username, getPassword(emailServer))
        else Transport.send(message)
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
}
