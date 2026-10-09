package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class DeleteMembershipRepository extends BaseRepository {

	private static DeleteMembershipRepository instance;
	
	private DeleteMembershipRepository() {}
	
	public static synchronized DeleteMembershipRepository getInstance() {
		if (instance == null) {
			instance = new DeleteMembershipRepository();
		}
		return instance;
	}
	
	public void deleteMembership(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className, String objectId) {
		String validateQuery = """
			MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(o:Object {id: $objectId}),
				  (n)-[:CONTAINS]->(c:Class {name: $className})
			OPTIONAL MATCH (o)-[existing:MEMBER_OF]->(c)
			RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of(
			"namespaceName", namespaceName,
			"objectId", objectId,
			"className", className
		);
		try {
			Result validation = tx.execute(validateQuery, params);
			if (!validation.hasNext()) {
				throwException("Error deleting Membership of Object " + objectId + " in Class " + className + ". It does not exist.");
			}
		} catch (Exception e) {
			throwException("Error deleting Membership of Object " + objectId + " in Class " + className, e);
		}
		
		String deleteQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Object {id: $objectId})-[updated:MEMBER_OF]->(target:Class {name: $className})
			WITH namespace, source, target, updated
		""";
		deleteQuery += Versioning.getElementUpdateCode("Membership", true, versioningType);
		deleteQuery += """
			DELETE updated
			RETURN updated
		""";
		params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"objectId", objectId,
			"className", className
		);
		try {
			tx.execute(deleteQuery, params);
		} catch (Exception e) {
			throwException("Error deleting Membership of Object " + objectId + " in Class " + className, e);
		}
	}
}
