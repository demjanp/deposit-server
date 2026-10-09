package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public class SetObjectPositionRepository extends BaseRepository {

	private static SetObjectPositionRepository instance;
	
	private SetObjectPositionRepository() {}
	
	public static synchronized SetObjectPositionRepository getInstance() {
		if (instance == null) {
			instance = new SetObjectPositionRepository();
		}
		return instance;
	}
	
	public void setObjectPosition(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String objectId, List<Double> position) {
		String updateQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(updated:Object {id: $objectId})
			SET updated.position = $position
			WITH namespace, updated
		""";
		updateQuery += Versioning.getElementUpdateCode("Object", false, versioningType);
		updateQuery += "	RETURN updated";
		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"objectId", objectId,
			"position", position
		);
		
		try {
			Result result = tx.execute(updateQuery, params);
			if (!result.hasNext()) {
				throwException("Object update failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error executing transaction", e);
		}
	}
}
