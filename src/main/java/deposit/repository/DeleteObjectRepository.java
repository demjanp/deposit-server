package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class DeleteObjectRepository extends BaseRepository {

	private static DeleteObjectRepository instance;
	
	private DeleteObjectRepository() {}
	
	public static synchronized DeleteObjectRepository getInstance() {
		if (instance == null) {
			instance = new DeleteObjectRepository();
		}
		return instance;
	}
	
	public void deleteObject(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String objectId) {
		String validateQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Object {id: $objectId}) RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of(
			"namespaceName", namespaceName,
			"objectId", objectId
		);
		try {
			Result validation = tx.execute(validateQuery, params);
			if (!validation.hasNext()) {
				throwException("Error deleting Object " + objectId + ". It does not exist.");
			}
		} catch (Exception e) {
			throwException("Error deleting Object " + objectId, e);
		}

		String deleteQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(updated:Object {id: $objectId})
			WITH namespace, updated
		""";
		deleteQuery += Versioning.getElementUpdateCode("Object", true, versioningType);
		deleteQuery += """
			MATCH (namespace:Namespace {name: $namespaceName})-[rel:CONTAINS]->(updated)
			DELETE rel, updated
			RETURN updated
		""";
		params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"objectId", objectId
		);
		try {
			tx.execute(deleteQuery, params);
		} catch (Exception e) {
			throwException("Error deleting Object " + objectId, e);
		}
	}
}
