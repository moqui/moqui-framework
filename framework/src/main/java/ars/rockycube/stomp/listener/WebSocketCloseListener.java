package ars.rockycube.stomp.listener;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */

public interface WebSocketCloseListener {
	
	/**
	 * https://developer.mozilla.org/en-US/docs/Web/API/CloseEvent
	 * 
	 * @param code
	 * @param reason
	 * @param remote
	 * @throws Exception
	 */
	void onClose(int code, String reason, boolean remote) throws Exception;
}
