package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class DeleteUserToolRepository extends BaseRepository {

	private static DeleteUserToolRepository instance;
	
	private DeleteUserToolRepository() {}
	
	public static synchronized DeleteUserToolRepository getInstance() {
		if (instance == null) {
			instance = new DeleteUserToolRepository();
		}
		return instance;
	}
	
	public void deleteUserTool(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String userToolName) {
		String validateQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:UserTool {name: $userToolName}) RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of(
			"namespaceName", namespaceName,
			"userToolName", userToolName
		);
		try {
			Result validation = tx.execute(validateQuery, params);
			if (!validation.hasNext()) {
				throwException("Error deleting the User Tool " + userToolName + ". It does not exist.");
			}
		} catch (Exception e) {
			throwException("Error deleting the User Tool " + userToolName, e);
		}
		
		String deleteQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(updated:UserTool {name: $userToolName})
			WITH namespace, updated
		""";
		deleteQuery += Versioning.getElementUpdateCode("UserTool", true, versioningType);
		deleteQuery += """
			DETACH DELETE updated
			RETURN updated
		""";
		params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"userToolName", userToolName
		);
		try {
			tx.execute(deleteQuery, params);
		} catch (Exception e) {
			throwException("Error deleting the User Tool " + userToolName, e);
		}
	}
}
