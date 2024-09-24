package deposit.common.result;

import java.util.Map;

public class StringMapResult {
	public final Map<String, String> data;

	public StringMapResult(Map<String, String> data) {
		this.data = data;
	}
	
	@Override
	public String toString() {
		return data.toString();
	}
}
