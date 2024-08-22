package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetClassDescriptorCommentRepository extends BaseRepository {

	private static SetClassDescriptorCommentRepository instance;
	
	private SetClassDescriptorCommentRepository() {}
	
	public static synchronized SetClassDescriptorCommentRepository getInstance() {
		if (instance == null) {
			instance = new SetClassDescriptorCommentRepository();
		}
		return instance;
	}
	
	public void setClassDescriptorComment(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className, String descriptorName, String comment) {
		String updateQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(parent:Class)-[:HAS_DESCRIPTOR]->(updated:Descriptor {name: $descriptorName})
			SET updated.comment = $comment
			WITH namespace, parent, updated
		""";
		updateQuery += Versioning.getElementUpdateCode("ClassDescriptor", false, versioningType);
		updateQuery += "	RETURN updated";
		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"className", className,
			"descriptorName", descriptorName,
			"comment", comment
		);
		
		try {
			Result result = tx.execute(updateQuery, params);
			if (!result.hasNext()) {
				throwException("Class Descriptor update failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error executing transaction", e);
		}
	}
}
