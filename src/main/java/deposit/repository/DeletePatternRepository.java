package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.Map;

public class DeletePatternRepository extends BaseRepository {

	private static DeletePatternRepository instance;
	
	private DeletePatternRepository() {}
	
	public static synchronized DeletePatternRepository getInstance() {
		if (instance == null) {
			instance = new DeletePatternRepository();
		}
		return instance;
	}
	
	public void deletePattern(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, 
			String versioningType, String patternName) {
		
		String validateQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Pattern {name: $patternName}) RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of(
			"namespaceName", namespaceName,
			"patternName", patternName
		);
		try {
			Result validation = tx.execute(validateQuery, params);
			if (!validation.hasNext()) {
				throwException("Error deleting the Pattern " + patternName + ". It does not exist.");
			}
		} catch (Exception e) {
			throwException("Error deleting the Pattern " + patternName, e);
		}
		
		String deleteQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(updated:Pattern {name: $patternName})
			WITH namespace, updated
		""";
		deleteQuery += Versioning.getElementUpdateCode("Pattern", true, versioningType);
		deleteQuery += """
			DETACH DELETE updated
			RETURN updated
		""";
		params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"patternName", patternName
		);
		try {
			tx.execute(deleteQuery, params);
		} catch (Exception e) {
			throwException("Error deleting the Pattern " + patternName, e);
		}
	}
}
