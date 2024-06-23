package ars.rockycube.stomp;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */

import ars.rockycube.stomp.listener.SubscribeHandler;
import ars.rockycube.stomp.listener.SubscribeHandlerWithoutPayload;

public class Subscription {
	
	private String id;
	
	private String destination;
	
	Class<?> targetClass;
	
	SubscribeHandler listener;
	
	SubscribeHandlerWithoutPayload listenerWithoutPayload;

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getDestination() {
		return destination;
	}

	public void setDestination(String destination) {
		this.destination = destination;
	}

	public SubscribeHandler getListener() {
		return listener;
	}

	public void setListener(SubscribeHandler listener) {
		this.listener = listener;
	}

	public Class<?> getTargetClass() {
		return targetClass;
	}

	public void setTargetClass(Class<?> targetClass) {
		this.targetClass = targetClass;
	}

	public SubscribeHandlerWithoutPayload getListenerWithoutPayload() {
		return listenerWithoutPayload;
	}

	public void setListenerWithoutPayload(SubscribeHandlerWithoutPayload listenerWithoutPayload) {
		this.listenerWithoutPayload = listenerWithoutPayload;
	}
	
	
}
