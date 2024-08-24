package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetRelationshipWeightRepository extends BaseRepository {

	private static SetRelationshipWeightRepository instance;
	
	private SetRelationshipWeightRepository() {}
	
	public static synchronized SetRelationshipWeightRepository getInstance() {
		if (instance == null) {
			instance = new SetRelationshipWeightRepository();
		}
		return instance;
	}
	
	public void setRelationshipWeight(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String startObjectId, String endObjectId, String label, Double weight) {
		String updateQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Object {id: $startObjectId})-[:RS]->(updated:Relationship {label: $label})-[:RE]->(target:Object {id: $endObjectId})
			SET updated.weight = $weight
			WITH namespace, source, target, updated
		""";
		updateQuery += Versioning.getElementUpdateCode("Relationship", false, versioningType);
		updateQuery += "	RETURN updated";
		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"startObjectId", startObjectId,
			"endObjectId", endObjectId,
			"label", label,
			"weight", weight
		);
		try {
			tx.execute(updateQuery, params);
		} catch (Exception e) {
			throwException("Error updating Relationship " + label, e);
		}
	}
}
