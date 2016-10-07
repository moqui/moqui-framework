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
package org.moqui.impl.tools

import groovy.transform.CompileStatic
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.moqui.impl.service.EmailEcaRule
import org.moqui.impl.util.MoquiShiroRealm
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.subethamail.smtp.MessageContext
import org.subethamail.smtp.MessageHandler
import org.subethamail.smtp.MessageHandlerFactory
import org.subethamail.smtp.RejectException
import org.subethamail.smtp.TooMuchDataException
import org.subethamail.smtp.auth.EasyAuthenticationHandlerFactory
import org.subethamail.smtp.auth.LoginFailedException
import org.subethamail.smtp.auth.UsernamePasswordValidator
import org.subethamail.smtp.server.SMTPServer

import javax.mail.Session
import javax.mail.internet.MimeMessage

/** ElasticSearch Client is used for indexing and searching documents */
@CompileStatic
class SubEthaSmtpToolFactory implements ToolFactory<SMTPServer> {
    protected final static Logger logger = LoggerFactory.getLogger(SubEthaSmtpToolFactory.class)
    final static String TOOL_NAME = "SubEthaSmtp"
    final static String EMAIL_SERVER_ID = "MOQUI_LOCAL"

    protected ExecutionContextFactoryImpl ecfi = null
    protected SMTPServer smtpServer = null
    protected EmecaMessageHandlerFactory messageHandlerFactory = null
    protected EasyAuthenticationHandlerFactory authHandlerFactory = null
    protected Session session = Session.getInstance(System.getProperties())


    /** Default empty constructor */
    SubEthaSmtpToolFactory() { }

    @Override String getName() { return TOOL_NAME }
    @Override
    void init(ExecutionContextFactory ecf) {
        ecfi = (ExecutionContextFactoryImpl) ecf

        EntityValue emailServer = ecf.entity.find("moqui.basic.email.EmailServer").condition("emailServerId", EMAIL_SERVER_ID)
                .useCache(true).disableAuthz().one()

        if (emailServer == null) {
            logger.error("Not starting SubEtha SMTP server, could not find ${EMAIL_SERVER_ID} EmailServer record")
            return
        }
        int port = emailServer.smtpPort as int

        messageHandlerFactory = new EmecaMessageHandlerFactory(this)
        authHandlerFactory = new EasyAuthenticationHandlerFactory(new MoquiUsernamePasswordValidator(ecfi))

        smtpServer = new SMTPServer(messageHandlerFactory)
        smtpServer.setAuthenticationHandlerFactory(authHandlerFactory)
        smtpServer.setPort(port)
        // TODO: support EmailServer.smtpStartTls and smtpSsl settings
        if (emailServer.smtpStartTls == "Y") smtpServer.setEnableTLS(true)
        smtpServer.start()
    }
    @Override void preFacadeInit(ExecutionContextFactory ecf) { }

    @Override
    SMTPServer getInstance(Object... parameters) {
        if (smtpServer == null) throw new IllegalStateException("SubEthaSmtpToolFactory not initialized")
        return smtpServer
    }

    @Override
    void destroy() {
        if (smtpServer != null) try {
            smtpServer.stop()
            logger.info("SubEtha SMTP server stopped")
        } catch (Throwable t) { logger.error("Error in SubEtha SMTP server stop", t) }
    }

    static class EmecaMessageHandlerFactory implements MessageHandlerFactory {
        final SubEthaSmtpToolFactory toolFactory
        EmecaMessageHandlerFactory(SubEthaSmtpToolFactory toolFactory) { this.toolFactory = toolFactory }
        @Override MessageHandler create(MessageContext ctx) { return new EmecaMessageHandler(ctx, toolFactory) }
    }

    static class EmecaMessageHandler implements MessageHandler {
        final MessageContext ctx
        final SubEthaSmtpToolFactory toolFactory

        private String from = (String) null
        private List<String> recipientList = new LinkedList<>()
        private MimeMessage mimeMessage = (MimeMessage) null

        EmecaMessageHandler(MessageContext ctx, SubEthaSmtpToolFactory toolFactory) { this.ctx = ctx; this.toolFactory = toolFactory; }

        @Override void from(String from) throws RejectException { this.from = from }
        @Override void recipient(String recipient) throws RejectException { recipientList.add(recipient) }
        @Override
        void data(InputStream data) throws RejectException, TooMuchDataException, IOException {
            // TODO: ever reject? perhaps of the from or no recipient addresses match a valid UserAccount.username?
            mimeMessage = new MimeMessage(toolFactory.session, data)
        }

        @Override
        void done() {
            // run EMECA rules
            toolFactory.ecfi.serviceFacade.runEmecaRules(mimeMessage, EMAIL_SERVER_ID)
            // always save EmailMessage record? better to let an EMECA rule do it...
            // logger.warn("Got email: ${mimeMessage.getSubject()} from ${from} recipients ${recipientList}\n${EmailEcaRule.makeBodyPartList(mimeMessage)}")
        }
    }

    static class MoquiUsernamePasswordValidator implements UsernamePasswordValidator {
        final ExecutionContextFactoryImpl ecf
        MoquiUsernamePasswordValidator(ExecutionContextFactoryImpl ecf) { this.ecf = ecf }
        @Override
        void login(String username, String password) throws LoginFailedException {
            EntityValue emailServer = ecf.entity.find("moqui.basic.email.EmailServer").condition("emailServerId", EMAIL_SERVER_ID)
                    .useCache(true).disableAuthz().one()
            if (emailServer.mailUsername == username) {
                if (emailServer.mailPassword != password) throw new LoginFailedException("Password incorrect for email root user")
            } else {
                if (!MoquiShiroRealm.checkCredentials(username, password, ecf))
                    throw new LoginFailedException(ecf.resource.expand('Username ${username} and/or password incorrect','',[username:username]))
            }
        }
    }
}
