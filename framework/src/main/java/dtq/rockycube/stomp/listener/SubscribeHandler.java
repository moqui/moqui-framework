package dtq.rockycube.stomp.listener;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */

import dtq.rockycube.stomp.StompHeaders;

public interface SubscribeHandler {
	
	void onReceived(final Object payload, StompHeaders stompHeaders);
}
