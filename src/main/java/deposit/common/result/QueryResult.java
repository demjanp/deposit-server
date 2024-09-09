package deposit.common.result;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;

public class QueryResult {
	private Map<String, Object> results = new HashMap<>();

	public void addResult(String variableName, Object value) {
		results.put(variableName, value);
	}

	public Object getResult(String variableName) {
		return results.get(variableName);
	}

	public Set<String> getVariables() {
		return results.keySet();
	}

	@Override
	public String toString() {
		return results.toString();
	}
}
