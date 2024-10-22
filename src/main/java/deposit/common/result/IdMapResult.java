package deposit.common.result;

import java.util.Map;

public class IdMapResult {
	public final Map<String, String> data;

	public IdMapResult(Map<String, String> data) {
		this.data = data;
	}
	
	@Override
	public String toString() {
		return data.toString();
	}
}
