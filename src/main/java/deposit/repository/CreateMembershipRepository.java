package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class CreateMembershipRepository extends BaseRepository {

	private static CreateMembershipRepository instance;

	private CreateMembershipRepository() {}

	public static synchronized CreateMembershipRepository getInstance() {
		if (instance == null) {
			instance = new CreateMembershipRepository();
		}
		return instance;
	}

	public Relationship createMembership(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className, String objectId) {
		String validateMembershipQuery = """
			MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(o:Object {id: $objectId}),
				  (n)-[:CONTAINS]->(c:Class {name: $className})
			OPTIONAL MATCH (o)-[existing:MEMBER_OF]->(c)
			RETURN existing
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"className", className,
			"objectId", objectId
		);

		try {
			Result validation = tx.execute(validateMembershipQuery, params);
			if (validation.hasNext()) {
				Map<String, Object> row = validation.next();
				if (row.get("existing") != null) {
					throwException("Error creating Membership. The membership of Object " + objectId + " in Class " + className + " already exists.");
				}
			}
		} catch (Exception e) {
			throwException("Error validating Membership of Object " + objectId + " in Class " + className, e);
		}
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
		
		params = Map.of(
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
