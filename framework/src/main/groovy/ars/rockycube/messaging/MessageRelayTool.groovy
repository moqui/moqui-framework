package ars.rockycube.messaging

import com.google.gson.Gson
import ars.rockycube.stomp.HttpHeaders
import ars.rockycube.stomp.StompHeaders
import ars.rockycube.stomp.listener.ConnectedListener
import org.moqui.context.ExecutionContext

import ars.rockycube.stomp.StompClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/*
    Simple tool to relay messages to JMS, just a one way communication
 */

class MessageRelayTool {
    protected final static Logger logger = LoggerFactory.getLogger(MessageRelayTool.class)
    protected ExecutionContext ec
    protected Gson gson

    private StompClient stompClient

    // constructor - URL + PORT
    MessageRelayTool(ExecutionContext ec, String uri, HttpHeaders httpHeaders) {
        // default constructor
        this.stompClient = StompClient.clientOverWebsocket(uri, httpHeaders)
        this.stompClient = stompClient.connect(new ConnectedListener() {
            @Override
            public void onConnected(StompHeaders stompHeaders) {
                logger.info("Communication to JMS server established")
            }
        })

        this.ec = ec
        this.gson = new Gson()
    }

    StompClient getStompClient() {
        return stompClient
    }
}
