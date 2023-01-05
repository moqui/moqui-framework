package dtq.rockycube.stomp.websocketClient;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */

public interface WebsocketEventHandler {
	
	void onOpen();

	void onMessage(String message);

	void onClose(int code, String reason, boolean remote);

	void onError(Exception ex);
}
