package dtq.rockycube.stomp.frame;

/**
 * stomp-webSocket-java-client
 * <a href="https://github.com/adrenalinee/stomp-webSocket-java-client">...</a>
 * @author shindongseong
 * @since 2015. 11. 6.
 */

import com.google.gson.Gson;

public class GsonFrameBodyConverter implements FrameBodyConverter {
	
	private Gson gson;
	
	public GsonFrameBodyConverter() {
		gson = new Gson();
	}
	
	public GsonFrameBodyConverter(Gson gson) {
		this.gson = gson;
	}
	
	public Gson getGson() {
		return gson;
	}

	
	@Override
	public Object fromFrame(String body, Class<?> targetClass) {
		if (body == null) {
			return null;
		}
		
		if ("".equals(body.trim()) || targetClass == null) {
			return null;
		}
		return gson.fromJson(body, targetClass);
	}

	@Override
	public String toString(Object payload) {
		return gson.toJson(payload);
	}

	public void setGson(Gson gson) {
		this.gson = gson;
	}

}
