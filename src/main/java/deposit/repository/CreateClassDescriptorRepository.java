package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class CreateClassDescriptorRepository extends BaseRepository {

	private static CreateClassDescriptorRepository instance;
	
	private CreateClassDescriptorRepository() {}
	
	public static synchronized CreateClassDescriptorRepository getInstance() {
		if (instance == null) {
			instance = new CreateClassDescriptorRepository();
		}
		return instance;
	}
	
    public Node createClassDescriptor(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String className, String name, String type, Long order, String comment) {
		String validateNameQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: $className})-[:HAS_DESCRIPTOR]->(existing:Descriptor {name: $name}) RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of(
			"namespaceName", namespaceName,
			"className", className,
			"name", name
		);
		try {
			Result validation = tx.execute(validateNameQuery, params);
			if (validation.hasNext()) {
				throwException("Error creating Class Descriptor. A Descriptor with the name: " + name + " already exists for Class" + className + ".");
			}
		} catch (Exception e) {
			throwException("Error creating Class Descriptor with name: " + name, e);
		}
		
		String createQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(parent:Class {name: $className})
			WITH namespace, parent
			CREATE (parent)-[:HAS_DESCRIPTOR]->(updated)
			SET	updated.id = randomUUID(),
				updated.name = $name,
				updated.order = $order,
				updated.comment = $comment,
				updated.createdAt = $timeStamp,
				updated.createdBy = $username,
				updated.updatedAt = $timeStamp,
				updated.updatedBy = $username
			WITH namespace, parent, updated
		""";
		createQuery += Versioning.getElementUpdateCode("ClassDescriptor", false);
		createQuery += "RETURN updated";
		params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"className", className,
			"name", name,
			"type", type,
			"order", order,
			"comment", comment
		);
		try {
			Result result = tx.execute(createQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("updated");
			} else {
				throwException("Class Descriptor creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating Class Descriptor with name: " + name, e);
		}
		return null;
    }
}
