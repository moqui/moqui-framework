package dtq.rockycube.stomp;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */

import java.util.Map;
import java.util.TreeMap;

public class StompHeaders {
	private Map<String, String> headers = new TreeMap<String, String>();
	
	public String getHeader(String key) {
		return headers.get(key);
	}
	
	public void addHeader(String key, String value) {
		headers.put(key, value);
	}
	
	public String getReceiptId() {
		return headers.get("receipt-id");
	}
	
	public String getMessageId() {
		return headers.get("message-id");
	}
	
	public String getSubscription() {
		return headers.get("subscription");
	}
	public String getHeartBeat() {
		return headers.get("heart-beat");
	}
	
	public Map<String, String> getHeaders() {
		Map<String, String> clonedHeaders = new TreeMap<String, String>();
		clonedHeaders.putAll(headers);
		return clonedHeaders;
	}
}
