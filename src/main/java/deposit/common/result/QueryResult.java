package deposit.common.result;

import java.util.Map;

public class QueryResult {
	public final Map<String, Object> result;

	public QueryResult(Map<String, Object> result) {
		this.result = result;
	}
	
	@Override
	public String toString() {
		return result.toString();
	}
}
