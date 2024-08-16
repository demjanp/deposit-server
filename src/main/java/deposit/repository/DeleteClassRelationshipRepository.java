package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class DeleteClassRelationshipRepository extends BaseRepository {

	private static DeleteClassRelationshipRepository instance;
	
	private DeleteClassRelationshipRepository() {}
	
	public static synchronized DeleteClassRelationshipRepository getInstance() {
		if (instance == null) {
			instance = new DeleteClassRelationshipRepository();
		}
		return instance;
	}
	
	public void deleteClassRelationship(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String startClassName, String endClassName, String label) {
		String validateQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: $startClassName})-[:RS]->(existing:Relationship {label: $label})-[:RE]->(:Class {name: $endClassName})
			RETURN existing
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"startClassName", startClassName,
			"endClassName", endClassName,
			"label", label
		);
		
		try {
			Result validation = tx.execute(validateQuery, params);
			if (!validation.hasNext()) {
				throwException("Error deleting Class Relationship " + label + ". It does not exist.");
			}
		} catch (Exception e) {
			throwException("Error deleting Class Relationship " + label, e);
		}
		
		String deleteQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Class {name: $startClassName})-[:RS]->(updated:Relationship {label: $label})-[:RE]->(target:Class {name: $endClassName})
			WITH namespace, source, target, updated
		""";
		deleteQuery += Versioning.getElementUpdateCode("ClassRelationship", true, versioningType);
		deleteQuery += """
			DETACH DELETE updated
			RETURN updated
		""";
		params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"startClassName", startClassName,
			"endClassName", endClassName,
			"label", label
		);
		try {
			tx.execute(deleteQuery, params);
		} catch (Exception e) {
			throwException("Error deleting Class Relationship " + label, e);
		}
	}
}
