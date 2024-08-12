package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

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
		String validateNameQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Class {name: $className}) RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of(
			"namespaceName", namespaceName,
			"className", className
		);
		try {
			Result validation = tx.execute(validateNameQuery, params);
			if (!validation.hasNext()) {
				throwException("Error deleting Class " + className + ". It does not exist.");
			}
		} catch (Exception e) {
			throwException("Error creating Class with name: " + className, e);
		}
		
		// TODO delete all objects which are members of only this class
		// TODO delete class relationships & descriptors
		
		String deleteQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(updated:Class {name: $className})
			DETACH DELETE updated
		""";
		params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"className", className
		);
		try {
			tx.execute(deleteQuery, params);
		} catch (Exception e) {
			throwException("Error deleting Class with name: " + className, e);
		}
	}
}
