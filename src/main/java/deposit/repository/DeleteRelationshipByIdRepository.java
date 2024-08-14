package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class DeleteRelationshipByIdRepository extends BaseRepository {

	private static DeleteRelationshipByIdRepository instance;
	
	private DeleteRelationshipByIdRepository() {}
	
	public static synchronized DeleteRelationshipByIdRepository getInstance() {
		if (instance == null) {
			instance = new DeleteRelationshipByIdRepository();
		}
		return instance;
	}
	
	public void deleteRelationshipById(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String relationshipId) {
		String validateQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object)-[:RS]->(existing:Relationship {id: $relationshipId})
			RETURN existing
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"relationshipId", relationshipId
		);
		
		try {
			Result validation = tx.execute(validateQuery, params);
			if (!validation.hasNext()) {
				throwException("Error deleting Relationship " + relationshipId + ". It does not exist.");
			}
		} catch (Exception e) {
			throwException("Error deleting Relationship " + relationshipId, e);
		}
		
		String deleteQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Object)-[:RS]->(updated:Relationship {id: $relationshipId})-[:RE]->(target:Object)
			WITH namespace, source, target, updated
		""";
		deleteQuery += Versioning.getElementUpdateCode("Relationship", true, versioningType);
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
			throwException("Error deleting Relationship " + relationshipId, e);
		}
	}
}
