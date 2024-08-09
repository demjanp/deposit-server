package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class CreateClassRelationshipRepository extends BaseRepository {

	private static CreateClassRelationshipRepository instance;
	
	private CreateClassRelationshipRepository() {}
	
	public static synchronized CreateClassRelationshipRepository getInstance() {
		if (instance == null) {
			instance = new CreateClassRelationshipRepository();
		}
		return instance;
	}
	
    public Node createClassRelationship(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String startClassName, String endClassName, String label, String comment) {
		String validateRelationshipQuery = """
			MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(cStart:Class {name: $startClassName}),
				  (n)-[:CONTAINS]->(cEnd:Class {name: $endClassName})
			OPTIONAL MATCH (cStart)-[:RS]->(existing:Relationship {label: $label})-[:RE]->(cEnd)
			RETURN existing
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"startClassName", startClassName,
			"endClassName", endClassName,
			"label", label
		);

		try {
			Result validation = tx.execute(validateRelationshipQuery, params);
			if (validation.hasNext()) {
				Map<String, Object> row = validation.next();
				if (row.get("existing") != null) {
					throwException("Error creating Class Relationship. The Relationship between Classes " + startClassName + " and " + endClassName + " already exists.");
				}
			}
		} catch (Exception e) {
			throwException("Error validating Class Relationship between Classes " + startClassName + " and " + endClassName, e);
		}
		
		String createQuery = """
			MATCH (source:Class {name: $startClassName}), (target:Class {name: $endClassName})
			CREATE (source)-[:RS]->(updated:Relationship)-[:RE]->(target)
			SET	updated.id = randomUUID(),
				updated.label = $label,
				updated.comment = $comment,
				updated.createdAt = $timeStamp,
				updated.createdBy = $username,
				updated.updatedAt = $timeStamp,
				updated.updatedBy = $username
			WITH updated
			MATCH (namespace:Namespace {name: $namespaceName})
			WITH namespace, updated
			MERGE (namespace)-[:CONTAINS]->(updated)
			WITH namespace, updated
		""";
		createQuery += Versioning.getElementUpdateCode("ClassRelationship", false, versioningType);
		createQuery += "	RETURN updated";
		params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"startClassName", startClassName,
			"endClassName", endClassName,
			"label", label,
			"comment", comment
		);
		
		try {
			Result result = tx.execute(createQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("updated");
			} else {
				throwException("Class Relationship creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating Class Relationship", e);
		}
		return null;
	}
}
