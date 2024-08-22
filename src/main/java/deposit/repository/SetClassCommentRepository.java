package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetClassCommentRepository extends BaseRepository {

	private static SetClassCommentRepository instance;
	
	private SetClassCommentRepository() {}
	
	public static synchronized SetClassCommentRepository getInstance() {
		if (instance == null) {
			instance = new SetClassCommentRepository();
		}
		return instance;
	}
	
	public void setClassComment(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className, String comment) {
		String updateQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(updated:Class {name: $className})
			SET updated.comment = $comment
			WITH namespace, updated
		""";
		updateQuery += Versioning.getElementUpdateCode("Class", false, versioningType);
		updateQuery += "	RETURN updated";
		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"className", className,
			"comment", comment
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
