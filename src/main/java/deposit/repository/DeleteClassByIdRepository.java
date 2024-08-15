package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class DeleteClassByIdRepository extends BaseRepository {

	private static DeleteClassByIdRepository instance;
	
	private DeleteClassByIdRepository() {}
	
	public static synchronized DeleteClassByIdRepository getInstance() {
		if (instance == null) {
			instance = new DeleteClassByIdRepository();
		}
		return instance;
	}

	public void deleteClassById(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String classId) {
		String deleteQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(updated:Class {id: $classId})
			WITH namespace, updated
		""";
		deleteQuery += Versioning.getElementUpdateCode("Class", true, versioningType);
		deleteQuery += """
			MATCH (namespace:Namespace {name: $namespaceName})-[rel:CONTAINS]->(updated)
			DELETE rel, updated
			RETURN updated
		""";
		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"classId", classId
		);
		try {
			tx.execute(deleteQuery, params);
		} catch (Exception e) {
			throwException("Error deleting Class " + classId, e);
		}
	}
}
