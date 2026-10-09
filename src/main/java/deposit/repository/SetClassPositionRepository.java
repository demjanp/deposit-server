package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public class SetClassPositionRepository extends BaseRepository {

	private static SetClassPositionRepository instance;
	
	private SetClassPositionRepository() {}
	
	public static synchronized SetClassPositionRepository getInstance() {
		if (instance == null) {
			instance = new SetClassPositionRepository();
		}
		return instance;
	}
	
	public void setClassPosition(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className, List<Double> position) {
		String updateQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(updated:Class {name: $className})
			SET updated.position = $position
			WITH namespace, updated
		""";
		updateQuery += Versioning.getElementUpdateCode("Class", false, versioningType);
		updateQuery += "	RETURN updated";
		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"className", className,
			"position", position
		);
		
		try {
			Result result = tx.execute(updateQuery, params);
			if (!result.hasNext()) {
				throwException("Class update failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error executing transaction", e);
		}
	}
}
