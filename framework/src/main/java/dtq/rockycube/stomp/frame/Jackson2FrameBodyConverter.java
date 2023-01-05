package dtq.rockycube.stomp.frame;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Jackson2FrameBodyConverter implements FrameBodyConverter {
	
	private ObjectMapper ob;
	
	public Jackson2FrameBodyConverter() {
		ob = new ObjectMapper();
	}
	
	public Jackson2FrameBodyConverter(ObjectMapper ob) {
		this.ob = ob;
	}
	
	@Override
	public Object fromFrame(String body, Class<?> targetClass) {
		try {
			return ob.readValue(body, targetClass);
		} catch (JsonParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (JsonMappingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return null;
	}

	@Override
	public String toString(Object payload) {
		try {
			return ob.writeValueAsString(payload);
		} catch (JsonProcessingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return null;
	}

}
