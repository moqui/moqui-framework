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

import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.Store
import javax.mail.internet.MimeMessage
import javax.mail.search.FlagTerm
import javax.mail.search.SearchTerm

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl

Logger logger = LoggerFactory.getLogger("org.moqui.impl.pollEmailServer")

ExecutionContextImpl ec = context.ec

EntityValue emailServer = ec.entity.find("moqui.basic.email.EmailServer").condition("emailServerId", emailServerId).one()
if (!emailServer) { ec.message.addError(ec.resource.expand('No EmailServer found for ID [${emailServerId}]','')); return }
if (!emailServer.storeHost) { ec.message.addError(ec.resource.expand('EmailServer [${emailServerId}] has no storeHost','')) }
if (!emailServer.mailUsername) { ec.message.addError(ec.resource.expand('EmailServer [${emailServerId}] has no mailUsername','')) }
if (!emailServer.mailPassword) { ec.message.addError(ec.resource.expand('EmailServer [${emailServerId}] has no mailPassword','')) }
if (ec.message.hasError()) return

String host = emailServer.storeHost
String user = emailServer.mailUsername
String password = emailServer.mailPassword
String protocol = emailServer.storeProtocol ?: "imaps"
int port = (emailServer.storePort ?: "993") as int
String storeFolder = emailServer.storeFolder ?: "INBOX"

// def urlName = new URLName(protocol, host, port as int, "", user, password)
Session session = Session.getInstance(System.getProperties())
logger.info("Polling Email from ${user}@${host}:${port}/${storeFolder}, properties ${session.getProperties()}")

Store store = session.getStore(protocol)
if (!store.isConnected()) store.connect(host, port, user, password)

// open the folder
Folder folder = store.getFolder(storeFolder)
if (folder == null || !folder.exists()) { ec.message.addError(ec.resource.expand('No ${storeFolder} folder found','')); return }

// get message count
folder.open(Folder.READ_WRITE)
int totalMessages = folder.getMessageCount()
// close and return if no messages
if (totalMessages == 0) { folder.close(false); return }

// get messages not deleted (and optionally not seen)
Flags searchFlags = new Flags(Flags.Flag.DELETED)
if (emailServer.storeSkipSeen == "Y") searchFlags.add(Flags.Flag.SEEN)
SearchTerm searchTerm = new FlagTerm(searchFlags, false)
Message[] messages = folder.search(searchTerm)
FetchProfile profile = new FetchProfile()
profile.add(FetchProfile.Item.ENVELOPE)
profile.add(FetchProfile.Item.FLAGS)
profile.add("X-Mailer")
folder.fetch(messages, profile)

logger.info("Found ${totalMessages} messages (${messages.size()} filtered) at ${user}@${host}:${port}/${storeFolder}")

for (Message message in messages) {
    if (emailServer.storeSkipSeen == "Y" && message.isSet(Flags.Flag.SEEN)) continue

    // NOTE: should we check size? long messageSize = message.getSize()
    if (message instanceof MimeMessage) {
        // use copy constructor to have it download the full message, may fix BODYSTRUCTURE issue from some email servers (see details in issue #97)
        MimeMessage fullMessage = new MimeMessage(message)
        ec.service.runEmecaRules(fullMessage, emailServerId)

        // mark seen if setup to do so
        if (emailServer.storeMarkSeen == "Y") message.setFlag(Flags.Flag.SEEN, true)
        // delete the message if setup to do so
        if (emailServer.storeDelete == "Y") message.setFlag(Flags.Flag.DELETED, true)
    } else {
        logger.warn("Doing nothing with non-MimeMessage message: ${message}")
    }
}

// expunge and close the folder
folder.close(true)
