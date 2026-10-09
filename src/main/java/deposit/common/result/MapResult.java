package deposit.common.result;

import java.util.Map;

public class MapResult {
	public final Map<String, Object> data;

	public MapResult(Map<String, Object> data) {
		this.data = data;
	}
	
	@Override
	public String toString() {
		return data.toString();
	}
}
