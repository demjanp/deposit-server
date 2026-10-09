package deposit.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import deposit.common.PatternData;
import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetPatternRepository extends BaseRepository {

	private static SetPatternRepository instance;
	
	private SetPatternRepository() {}
	
	public static synchronized SetPatternRepository getInstance() {
		if (instance == null) {
			instance = new SetPatternRepository();
		}
		return instance;
	}
	
	public void setPattern(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName,
			String versioningType, PatternData.Pattern pattern) {
		
		ObjectMapper objectMapper = new ObjectMapper();
		String data = "";
		try {
			data = objectMapper.writeValueAsString(pattern.toMap());
		} catch (Exception e) {
			throwException("Error parsing Pattern data for: " + pattern.name, e);
		}
		
		boolean patternExists = false;
		String validateQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Pattern {name: $patternName}) RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of(
			"namespaceName", namespaceName,
			"patternName", pattern.name
		);
		try {
			Result validation = tx.execute(validateQuery, params);
			if (validation.hasNext()) {
				patternExists = true;
			}
		} catch (Exception e) {
			throwException("Error updating the Pattern: " + pattern.name, e);
		}
		
		String query;
		if (patternExists) {
			query = """
				MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(updated:Pattern {name: $patternName})
				SET
					updated.data = $data,
					updated.comment = $comment
				WITH namespace, updated
			""";		
		} else {
			query = """
				CREATE (updated:Pattern {
					id: randomUUID(),
					name: $patternName,
					data: $data,
					comment: $comment,
					createdAt: $timeStamp,
					createdBy: $username
				})
				WITH updated
				MATCH (namespace:Namespace {name: $namespaceName})
				WITH namespace, updated
				MERGE (namespace)-[:CONTAINS]->(updated)
				WITH namespace, updated
			""";
		}
		
		query += Versioning.getElementUpdateCode("Pattern", false, versioningType);
		query += "	RETURN updated";
		params = Map.of(
		"timeStamp", timeStamp,
		"username", username,
		"namespaceName", namespaceName,
		"patternName", pattern.name,
		"data", data,
		"comment", pattern.comment
		);
		try {
			Result result = tx.execute(query, params);
			if (!result.hasNext()) {
				throwException("Pattern update failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error updating Pattern with name: " + pattern.name, e);
		}
	}
}
