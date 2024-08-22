package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetMembershipRepository extends BaseRepository {

	private static SetMembershipRepository instance;

	private SetMembershipRepository() {}

	public static synchronized SetMembershipRepository getInstance() {
		if (instance == null) {
			instance = new SetMembershipRepository();
		}
		return instance;
	}

	public Relationship setMembership(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className, String objectId) {
		String createQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Object {id: $objectId}), (namespace)-[:CONTAINS]->(target:Class {name: $className})
			CREATE (source)-[updated:MEMBER_OF]->(target)
			SET updated.id = randomUUID(),
				updated.createdAt = $timeStamp,
				updated.createdBy = $username
			WITH namespace, source, target, updated
		""";
		
		createQuery += Versioning.getElementUpdateCode("Membership", false, versioningType);
		createQuery += "RETURN updated";
		
		Map<String, Object> params = Map.of(
		"namespaceName", namespaceName,
		"className", className,
		"objectId", objectId,
		"timeStamp", timeStamp,
		"username", username
		);
		try {
			Result result = tx.execute(createQuery, params);
			if (result.hasNext()) {
				return (Relationship) result.next().get("updated");
			} else {
				throwException("Membership creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating Membership of Object " + objectId + " in Class " + className, e);
		}
		return null;
	}
}
