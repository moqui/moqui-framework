package dtq.rockycube.stomp;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */

public class Transaction {
	
	String id;
	
	StompClient stompClient;
	
	Transaction(StompClient StompClient) {
		this.stompClient = StompClient;
	}
	
	public void commit() {
		stompClient.commit(id);
	}
	
	public void abort() {
		stompClient.abort(id);
	}
}
