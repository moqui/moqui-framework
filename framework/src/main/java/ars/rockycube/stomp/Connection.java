package ars.rockycube.stomp;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */

import java.util.Map;

public class Connection {
	private String url;
	
	private Map<String,String> headers;
	
	private int connecttimeout = 60;
	
	public Connection(String url, Map<String,String> headers) {
		this.url = url;
		this.headers = headers;
	}
	
	public String getUrl() {
		return url;
	}

	public Map<String, String> getHeaders() {
		return headers;
	}

	public int getConnecttimeout() {
		return connecttimeout;
	}

	public void setConnecttimeout(int connecttimeout) {
		this.connecttimeout = connecttimeout;
	}
}
