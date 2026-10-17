package org.moqui.util;

import java.util.HexFormat;
import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.apache.commons.mail2.jakarta.HtmlEmail;

/** Test-only adapter for the JavaMail Message-ID that EmailServices persists. Transport entropy stays real. */
public final class DeterministicHtmlEmail extends HtmlEmail {
    @Override
    protected MimeMessage createMimeMessage(Session session) {
        if (!DeterministicClock.isEnabled()) throw new IllegalStateException("Deterministic email requires test mode");
        return new MimeMessage(session) {
            private String assignedId;
            @Override
            protected void updateMessageID() throws MessagingException {
                if (assignedId == null) {
                    Address[] from = getFrom();
                    String address = from != null && from.length > 0 && from[0] instanceof InternetAddress ?
                            ((InternetAddress) from[0]).getAddress() : "";
                    String host = address.contains("@") ? address.substring(address.lastIndexOf('@') + 1) : "localhost";
                    assignedId = "<" + HexFormat.of().formatHex(DeterministicRandom.bytes("mail-message-id", 12)) + "@" + host + ">";
                }
                setHeader("Message-ID", assignedId);
            }
        };
    }
}
