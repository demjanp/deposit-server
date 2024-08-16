package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class CreateUserToolRepository extends BaseRepository {

	private static CreateUserToolRepository instance;
	
	private CreateUserToolRepository() {}
	
	public static synchronized CreateUserToolRepository getInstance() {
		if (instance == null) {
			instance = new CreateUserToolRepository();
		}
		return instance;
	}
	
    public Node createUserTool(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String userToolName, String data, String comment) {
		String validateQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:UserTool {name: $userToolName}) RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of(
			"namespaceName", namespaceName,
			"userToolName", userToolName
		);
		try {
			Result validation = tx.execute(validateQuery, params);
			if (validation.hasNext()) {
				throwException("Error creating the User Tool: " + userToolName + ". A User Tool with that name already exists.");
			}
		} catch (Exception e) {
			throwException("Error creating the User Tool: " + userToolName, e);
		}
		String createQuery = """
			CREATE (updated:UserTool {
				id: randomUUID(),
				name: $userToolName,
				data: $data,
				comment: $comment,
				createdAt: $timeStamp,
				createdBy: $username,
				updatedAt: $timeStamp,
				updatedBy: $username
			})
			WITH updated
			MATCH (namespace:Namespace {name: $namespaceName})
			WITH namespace, updated
			MERGE (namespace)-[:CONTAINS]->(updated)
			WITH namespace, updated
		""";
		createQuery += Versioning.getElementUpdateCode("UserTool", false, versioningType);
		createQuery += "	RETURN updated";
		params = Map.of(
		"timeStamp", timeStamp,
		"username", username,
		"namespaceName", namespaceName,
		"userToolName", userToolName,
		"data", data,
		"comment", comment
		);
		try {
			Result result = tx.execute(createQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("updated");
			} else {
				throwException("User Tool creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating User Tool with name: " + userToolName, e);
		}
		return null;
    }
}
