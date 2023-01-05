package dtq.rockycube.stomp;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */
public enum Command {
	CONNECT,
	CONNECTED,
	MESSAGE,
	RECEIPT,
	ERROR,
	DISCONNECT,
	SEND,
	SUBSCRIBE,
	UNSUBSCRIBE,
	ACK,
	NACK,
	COMMIT,
	ABORT,
	BEGIN
}
