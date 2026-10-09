package deposit.repository;

import deposit.common.result.MapResult;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;


public class GetPatternRepository extends BaseRepository {

	private static GetPatternRepository instance;
	
	private GetPatternRepository() {}
	
	public static synchronized GetPatternRepository getInstance() {
		if (instance == null) {
			instance = new GetPatternRepository();
		}
		return instance;
	}
	
	public MapResult getPattern(Transaction tx, String namespaceName, String patternName) {
		String query;
		Map<String, Object> params;
		if (patternName.isEmpty()) {
			query = """
				MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(pattern:Pattern)
			""";
			params = Map.of("namespaceName", namespaceName);
		} else {
			query = """
				MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(pattern:Pattern {name: $patternName})
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"patternName", patternName
			);
		}
		query += """
			RETURN pattern.name AS patternName, pattern.data AS data
		""";
		
		ObjectMapper objectMapper = new ObjectMapper();
		Map<String, Object> data = new HashMap<>();
		try {
			Result result = tx.execute(query, params);
			while (result.hasNext()) {
				Map<String, Object> row = result.next();
				String key = row.get("patternName").toString();
				String jsonData = row.get("data").toString();
				try {
					data.put(key, objectMapper.readValue(jsonData, Map.class));
				} catch (Exception e) {
					throwException("Error parsing data for Pattern: " + patternName, e);
				}
			}
		} catch (Exception e) {
			throwException("Error retrieving the Pattern: " + patternName, e);
		}
		return new MapResult(data);
	}
}
