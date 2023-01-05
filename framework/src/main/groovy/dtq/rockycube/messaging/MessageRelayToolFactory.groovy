package dtq.rockycube.messaging

import dtq.rockycube.stomp.HttpHeaders
import dtq.rockycube.stomp.listener.DisconnectListener
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class MessageRelayToolFactory implements ToolFactory<MessageRelayTool> {
    protected final static Logger logger = LoggerFactory.getLogger(MessageRelayToolFactory.class)
    protected ExecutionContextFactory ecf
    final static String TOOL_NAME = "MessageRelayTool"

    // the tool itself
    protected MessageRelayTool messaging

    /** Default empty constructor */
    MessageRelayToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }

    @Override
    void init(ExecutionContextFactory ecf) {
        this.ecf = ecf

        // load parameters from setup
        String uri = System.properties.get("jms.server.host")
        String login = System.properties.get("jms.server.operator")
        String pwd = System.properties.get("jms.server.operator.password")

        // do not allow Tool to be started, should there be no connection info
        if (!uri) {
            logger.warn("Cannot initialize MessageRelayTool, no URI set")
            return
        }

        logger.info("Starting MessageRelayTool on '${uri}'")

        /*initializing client*/
        HttpHeaders httpHeaders = new HttpHeaders()
        httpHeaders.addHeader("login", login)
        httpHeaders.addHeader("passcode", pwd)

        messaging = new MessageRelayTool(ecf.executionContext, uri, httpHeaders)

    }

    @Override
    void preFacadeInit(ExecutionContextFactory ecf) {}

    @Override
    MessageRelayTool getInstance(Object... parameters) {
        if (messaging == null) throw new IllegalStateException("MessageRelayTool not initialized")
        return messaging
    }

    @Override
    void destroy() {
        if (messaging != null) try {
            messaging.stompClient.disconnect(new DisconnectListener() {
                @Override
                void onDisconnect() {
                    logger.info('Disconnecting MessageRelayTool')
                }
            })

            logger.info("MessageRelayTool closed")
        } catch (Throwable t) { logger.error("Error while MessageRelayTool client close procedure.", t) }
    }

    ExecutionContextFactory getEcf() { return ecf }
}
