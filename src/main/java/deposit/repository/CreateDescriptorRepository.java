package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class CreateDescriptorRepository extends BaseRepository {

	private static CreateDescriptorRepository instance;
	
	private CreateDescriptorRepository() {}
	
	public static synchronized CreateDescriptorRepository getInstance() {
		if (instance == null) {
			instance = new CreateDescriptorRepository();
		}
		return instance;
	}
	
    public Node createDescriptor(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String objectId, String descriptorName, Object value) {
		String validateIdQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $objectId})-[:HAS_DESCRIPTOR]->(existing:Descriptor {name: $descriptorName}) RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of(
			"namespaceName", namespaceName,
			"objectId", objectId,
			"descriptorName", descriptorName
		);
		try {
			Result validation = tx.execute(validateIdQuery, params);
			if (validation.hasNext()) {
				throwException("Error creating Descriptor. A Descriptor with the name: " + descriptorName + " already exists for Object" + objectId + ".");
			}
		} catch (Exception e) {
			throwException("Error creating Descriptor with name: " + descriptorName, e);
		}
		
		String createQuery = """
			CREATE (updated:Descriptor {
				id: randomUUID(),
				name: $descriptorName,
				value: $value,
				createdAt: $timeStamp,
				createdBy: $username,
				updatedAt: $timeStamp,
				updatedBy: $username
			})
			WITH updated
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(parent:Object {id: $objectId})
			WITH namespace, parent, updated
			MERGE (parent)-[:HAS_DESCRIPTOR]->(updated)
			WITH namespace, parent, updated
		""";
		createQuery += Versioning.getElementUpdateCode("Descriptor", false, versioningType);
		createQuery += "RETURN updated";
		params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"objectId", objectId,
			"descriptorName", descriptorName,
			"value", value
		);
		try {
			Result result = tx.execute(createQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("updated");
			} else {
				throwException("Descriptor creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating Descriptor with name: " + descriptorName, e);
		}
		return null;
    }
}
