package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.Map;

public class DeleteClassRepository extends BaseRepository {

	private static DeleteClassRepository instance;
	
	private DeleteClassRepository() {}
	
	public static synchronized DeleteClassRepository getInstance() {
		if (instance == null) {
			instance = new DeleteClassRepository();
		}
		return instance;
	}

	public void deleteClass(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className) {
		String deleteQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(updated:Class {name: $className})
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
			"className", className
		);
		try {
			tx.execute(deleteQuery, params);
		} catch (Exception e) {
			throwException("Error deleting Class " + className, e);
		}
	}
}
