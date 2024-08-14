package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class DeleteClassRelationshipByIdRepository extends BaseRepository {

	private static DeleteClassRelationshipByIdRepository instance;
	
	private DeleteClassRelationshipByIdRepository() {}
	
	public static synchronized DeleteClassRelationshipByIdRepository getInstance() {
		if (instance == null) {
			instance = new DeleteClassRelationshipByIdRepository();
		}
		return instance;
	}
	
	public void deleteClassRelationshipById(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String relationshipId) {
		String validateQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class)-[:RS]->(existing:Relationship {id: $relationshipId})
			RETURN existing
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"relationshipId", relationshipId
		);
		
		try {
			Result validation = tx.execute(validateQuery, params);
			if (!validation.hasNext()) {
				throwException("Error deleting Class Relationship " + relationshipId + ". It does not exist.");
			}
		} catch (Exception e) {
			throwException("Error deleting Class Relationship " + relationshipId, e);
		}
		
		String deleteQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Class)-[:RS]->(updated:Relationship {id: $relationshipId})-[:RE]->(target:Class)
			WITH namespace, source, target, updated
		""";
		deleteQuery += Versioning.getElementUpdateCode("ClassRelationship", true, versioningType);
		deleteQuery += """
			MATCH (source)-[rs:RS]->(updated:Relationship)-[re:RE]->(target)
			WITH updated, rs, re
			DELETE rs, re, updated
			RETURN updated
		""";
		params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"relationshipId", relationshipId
		);
		try {
			tx.execute(deleteQuery, params);
		} catch (Exception e) {
			throwException("Error deleting Class Relationship " + relationshipId, e);
		}
	}
}
