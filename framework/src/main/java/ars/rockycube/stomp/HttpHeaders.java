 package ars.rockycube.stomp;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */
import java.util.LinkedHashMap;
import java.util.Map;

public class HttpHeaders {
	
	private Map<String, String> headers = new LinkedHashMap<String, String>();
	
	public String getHeader(String key) {
		return headers.get(key);
	}
	
	public void addHeader(String key, String value) {
		headers.put(key, value);
	}
	
	public void setAuthorazation(String value) {
		headers.put("Authorization", value);
	}
	
	public Map<String, String> getHeaders() {
		Map<String, String> clonedHeaders = new LinkedHashMap<String, String>();
		clonedHeaders.putAll(headers);
		return clonedHeaders;
	}
}
