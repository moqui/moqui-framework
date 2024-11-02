package ars.rockycube.stomp;

/**
 * STOMP
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */
public class Heartbeat {
	int outgoing = 10000;
	
	int incoming = 10000;

	public int getOutgoing() {
		return outgoing;
	}

	public void setOutgoing(int outgoing) {
		this.outgoing = outgoing;
	}

	public int getIncoming() {
		return incoming;
	}

	public void setIncoming(int incoming) {
		this.incoming = incoming;
	}
	
}
