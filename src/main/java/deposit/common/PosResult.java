package deposit.common;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class PosResult {
	public float x;
	public float y;
	
	public PosResult(float x, float y) {
		this.x = x;
		this.y = y;
	}

	public String getX() {
		return x;
	}

	public String getY() {
		return y;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> pos = new HashMap<>();
		pos.put("x", x);
		pos.put("y", y);
		return pos;
	}
}
