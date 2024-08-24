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
	
    public Node createClassDescriptor(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className, String descriptorName, String type, Long order, String comment) {
		String validateNameQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Class {name: $className})-[:HAS_DESCRIPTOR]->(existing:Descriptor {name: $descriptorName}) RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of(
			"namespaceName", namespaceName,
			"className", className,
			"descriptorName", descriptorName
		);
		try {
			Result validation = tx.execute(validateNameQuery, params);
			if (validation.hasNext()) {
				throwException("Error creating Class Descriptor. A Descriptor with the name: " + descriptorName + " already exists for Class" + className + ".");
			}
		} catch (Exception e) {
			throwException("Error creating Class Descriptor with name: " + descriptorName, e);
		}
		
		String createQuery = """
			CREATE (updated:Descriptor {
				id: randomUUID(),
				name: $descriptorName,
				type: $type,
				order: $order,
				comment: $comment,
				createdAt: $timeStamp,
				createdBy: $username
			})
			WITH updated
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(parent:Class {name: $className})
			WITH namespace, parent, updated
			MERGE (parent)-[:HAS_DESCRIPTOR]->(updated)
			WITH namespace, parent, updated
		""";
		createQuery += Versioning.getElementUpdateCode("ClassDescriptor", false, versioningType);
		createQuery += "RETURN updated";
		params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"className", className,
			"descriptorName", descriptorName,
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
			throwException("Error creating Class Descriptor with name: " + descriptorName, e);
		}
		return null;
    }
}
