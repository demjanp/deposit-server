package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class CreateRelationshipRepository extends BaseRepository {
	
	private static CreateRelationshipRepository instance;
	
	private CreateRelationshipRepository() {}
	
	public static synchronized CreateRelationshipRepository getInstance() {
		if (instance == null) {
			instance = new CreateRelationshipRepository();
		}
		return instance;
	}
	
    public Node createRelationship(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String startObjectId, String endObjectId, String label, Double weight) {
		String validateRelationshipQuery = """
			MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(oStart:Object {id: $startObjectId}),
				  (n)-[:CONTAINS]->(oEnd:Object {id: $endObjectId})
			OPTIONAL MATCH (oStart)-[:RS]->(existing:Relationship {label: $label})-[:RE]->(oEnd)
			RETURN existing
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"startObjectId", startObjectId,
			"endObjectId", endObjectId,
			"label", label
		);
		
		try {
			Result validation = tx.execute(validateRelationshipQuery, params);
			if (validation.hasNext()) {
				Map<String, Object> row = validation.next();
				if (row.get("existing") != null) {
					throwException("Error creating Relationship. The Relationship between Objects " + startObjectId + " and " + endObjectId + " already exists.");
				}
			}
		} catch (Exception e) {
			throwException("Error validating Relationship between Objects " + startObjectId + " and " + endObjectId, e);
		}
		String createQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Object {id: $startObjectId}), (namespace)-[:CONTAINS]->(target:Object {id: $endObjectId})
			CREATE (source)-[:RS]->(updated:Relationship)-[:RE]->(target)
			SET	updated.id = randomUUID(),
				updated.label = $label,
				updated.weight = $weight,
				updated.createdAt = $timeStamp,
				updated.createdBy = $username,
				updated.updatedAt = $timeStamp,
				updated.updatedBy = $username
			WITH source, target, updated
			MATCH (namespace:Namespace {name: $namespaceName})
			WITH namespace, source, target, updated
			MERGE (namespace)-[:CONTAINS]->(updated)
			WITH namespace, source, target, updated
		""";
		createQuery += Versioning.getElementUpdateCode("Relationship", false, versioningType);
		createQuery += "	RETURN updated";
		params = Map.of(
		"timeStamp", timeStamp,
		"username", username,
		"namespaceName", namespaceName,
		"startObjectId", startObjectId,
		"endObjectId", endObjectId,
		"label", label,
		"weight", weight
		);
		try {
			Result result = tx.execute(createQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("updated");
			} else {
				throwException("Relationship creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating Relationship", e);
		}
		return null;
	}
}
