package ars.rockycube.stomp.frame;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */

public interface FrameBodyConverter {
	
	Object fromFrame(String body, Class<?> targetClass);
	
	String toString(Object payload);
}
