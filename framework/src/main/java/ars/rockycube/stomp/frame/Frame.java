package ars.rockycube.stomp.frame;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */

import java.util.Map;
import java.util.Map.Entry;

import ars.rockycube.stomp.Command;
import ars.rockycube.stomp.StompClient;
import ars.rockycube.stomp.StompHeaders;

import java.util.TreeMap;

public class Frame {
	public static final String LF = "\n";
	public static final String NULL = "\0";
	
	private Command command;
	
	private StompHeaders headers;
	
	private String body;
	
	private StompClient stompClient;
	
	
	Frame(Command command, Map<String, String> headers, String body) {
		this.command = command;
		this.headers = new StompHeaders();
//		this.headers.putAll(headers);
		
		if (headers != null) {
			for (String key: headers.keySet()) {
				this.headers.addHeader(key, headers.get(key));
			}
		}
		this.body = body;
	}
	
	public String toString() {
		StringBuffer sb = new StringBuffer(command.name()).append(LF);
		
		for (Entry<String, String> entry: headers.getHeaders().entrySet()) {
			sb.append(entry.getKey())
				.append(":")
				.append(entry.getValue())
				.append(LF);
		}
		
		return
			sb.append(LF)
				.append(body == null ? "" : body)
				.append(NULL).toString();
	}
	
	public static String marshall(Command command, Map<String, String> headers, String body) {
		return new Frame(command, headers, body).toString();
	}
	
	
	public static Frame unmarshall(String message) {
		String[] elements = message.split(LF);
		Command command = Command.valueOf(elements[0]);
		
		Map<String, String> headers = new TreeMap<String, String>();
		for (int i = 1, limit = elements.length; i < limit; i++) {
			String line = elements[i];
			if ("".equals(line.trim())) {
				break;
			}
			
			String[] header = line.split(":");
			headers.put(header[0], header[1]);
		}
		
		String body = elements[elements.length - 1];
		if (!(body == null || "".equals(body.trim()))) {
			body = body.substring(0, body.length() - 1);
		}
//		System.out.println("body: " + body);
		
		return new Frame(command, headers, body);
	}
	
//	public static Frame unmarshall(String message) {
//		StringTokenizer st = new StringTokenizer(message, LF);
//		Command command = Command.valueOf(st.nextToken());
//		
//		Map<String, String> headers = new TreeMap<String, String>();
//		while (st.hasMoreTokens()) {
//			String line = st.nextToken();
//			System.out.println(line);
//			if ("".equals(line.trim())) {
//				break;
//			}
//			
//			String[] header = line.split(":");
//			headers.put(header[0], header[1]);
//		}
//		
//		String body = null;
//		if (st.hasMoreTokens()) {
//			System.out.println("@@@");
//			body = st.nextToken();
//		}
//		
//		System.out.println("body: " + body);
//		
//		return new Frame(command, headers, body);
//	}
	
	
	public void ack() {
		if (stompClient == null) {
			throw new RuntimeException();
		}
		String messageID = headers.getMessageId();
		String subscription = headers.getSubscription();
		stompClient.ack(messageID, subscription, headers);
	}
	
	public void nack() {
		if (stompClient == null) {
			throw new RuntimeException();
		}
		String messageID = headers.getMessageId();
		String subscription = headers.getSubscription();
		stompClient.nack(messageID, subscription, headers);
	}
	
	
	public Command getCommand() {
		return command;
	}

	public void setCommand(Command command) {
		this.command = command;
	}

	public StompHeaders getHeaders() {
		return headers;
	}

	public void setHeaders(StompHeaders headers) {
		this.headers = headers;
	}

	public String getBody() {
		return body;
	}
	
	public void setBody(String body) {
		this.body = body;
	}

	public void setStompClient(StompClient stompClient) {
		this.stompClient = stompClient;
	}

}
