package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Node;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public class CreateClassRepository extends BaseRepository {

	private static CreateClassRepository instance;
	
	private CreateClassRepository() {}
	
	public static synchronized CreateClassRepository getInstance() {
		if (instance == null) {
			instance = new CreateClassRepository();
		}
		return instance;
	}
	
	public Node createClass(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String name, Long order, List<Double> position, String comment) {
		String validateNameQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Class {name: $name}) RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of(
			"namespaceName", namespaceName,
			"name", name
		);
		try {
			Result validation = tx.execute(validateNameQuery, params);
			if (validation.hasNext()) {
				throwException("Error creating Class. A Class with the name: " + name + " already exists in Namespace" + namespaceName + ".");
			}
		} catch (Exception e) {
			throwException("Error creating Class with name: " + name, e);
		}
		
		String createQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})
			WITH namespace
			CREATE (namespace)-[:CONTAINS]->(updated:Class)
			SET	updated.id = randomUUID(),
				updated.name = $name,
				updated.order = $order,
				updated.position = $position,
				updated.comment = $comment,
				updated.createdAt = $timeStamp,
				updated.createdBy = $username,
				updated.updatedAt = $timeStamp,
				updated.updatedBy = $username
			WITH namespace, updated
		""";
		createQuery += Versioning.getElementUpdateCode("Class", false);
		createQuery += "RETURN updated";
		params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"name", name,
			"order", order,
			"position", position,
			"comment", comment
		);
		try {
			Result result = tx.execute(createQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("updated");
			} else {
				throwException("Class creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating Class with name: " + name, e);
		}
		return null;
	}
}
