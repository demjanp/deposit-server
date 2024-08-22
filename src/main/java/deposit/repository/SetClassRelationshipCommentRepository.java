package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetClassRelationshipCommentRepository extends BaseRepository {

	private static SetClassRelationshipCommentRepository instance;
	
	private SetClassRelationshipCommentRepository() {}
	
	public static synchronized SetClassRelationshipCommentRepository getInstance() {
		if (instance == null) {
			instance = new SetClassRelationshipCommentRepository();
		}
		return instance;
	}
	
	public void setClassRelationshipComment(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String startClassName, String endClassName, String label, String comment) {
		String updateQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Class {name: $startClassName})-[:RS]->(updated:Relationship {label: $label})-[:RE]->(target:Class {name: $endClassName})
			SET updated.comment = $comment
			WITH namespace, source, target, updated
		""";
		updateQuery += Versioning.getElementUpdateCode("ClassRelationship", false, versioningType);
		updateQuery += "	RETURN updated";
		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"startClassName", startClassName,
			"endClassName", endClassName,
			"label", label,
			"comment", comment
		);
		try {
			tx.execute(updateQuery, params);
		} catch (Exception e) {
			throwException("Error updating Class Relationship " + label, e);
		}
	}
}
