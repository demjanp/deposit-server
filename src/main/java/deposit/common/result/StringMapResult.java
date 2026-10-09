package deposit.common.result;

import java.util.List;
import java.util.Map;

public class StringMapResult {
	public final Map<String, List<String>> data;

	public StringMapResult(Map<String, List<String>> data) {
		this.data = data;
	}
	
	@Override
	public String toString() {
		return data.toString();
	}
}
