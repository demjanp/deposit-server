package deposit.repository;

import deposit.common.PatternData;
import deposit.common.Versioning;

import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CreatePatternRepository extends BaseRepository {

	private static CreatePatternRepository instance;
	
	private CreatePatternRepository() {}
	
	public static synchronized CreatePatternRepository getInstance() {
		if (instance == null) {
			instance = new CreatePatternRepository();
		}
		return instance;
	}
	
	public Node createPattern(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName,
			String versioningType, PatternData.Pattern pattern) {
		
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
				throwException("Error creating the Pattern: " + pattern.name + ". A Pattern with that name already exists.");
			}
		} catch (Exception e) {
			throwException("Error creating the Pattern: " + pattern.name, e);
		}
		
		ObjectMapper objectMapper = new ObjectMapper();
		String data = "";
		try {
			data = objectMapper.writeValueAsString(pattern.toMap());
		} catch (Exception e) {
			throwException("Error parsing Pattern data for: " + pattern.name, e);
		}
		
		String createQuery = """
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
		createQuery += Versioning.getElementUpdateCode("Pattern", false, versioningType);
		createQuery += "	RETURN updated";
		params = Map.of(
		"timeStamp", timeStamp,
		"username", username,
		"namespaceName", namespaceName,
		"patternName", pattern.name,
		"data", data,
		"comment", pattern.comment
		);
		try {
			Result result = tx.execute(createQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("updated");
			} else {
				throwException("Pattern creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating Pattern with name: " + pattern.name, e);
		}
		return null;
	}
}
