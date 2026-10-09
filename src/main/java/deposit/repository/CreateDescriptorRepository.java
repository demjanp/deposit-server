package deposit.repository;

import deposit.common.FileSystem;
import deposit.common.Versioning;

import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.HashMap;
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
	
	public Node createDescriptor(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String objectId, String descriptorName, Object value, Boolean update) {
		if (!update) {
			String validateQuery = """
				MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $objectId})-[:HAS_DESCRIPTOR]->(existing:Descriptor {name: $descriptorName}) RETURN existing
			""";
			try {
				Result validation = tx.execute(validateQuery, Map.of(
					"namespaceName", namespaceName,
					"objectId", objectId,
					"descriptorName", descriptorName
				));
				if (validation.hasNext()) {
					throwException("Error creating Descriptor. A Descriptor with the name: " + descriptorName + " already exists for Object" + objectId + ".");
				}
			} catch (Exception e) {
				throwException("Error creating Descriptor with name: " + descriptorName, e);
			}
		}
		
		Map<String, Object> params = new HashMap<>();
		params.put("timeStamp", timeStamp);
		params.put("username", username);
		params.put("namespaceName", namespaceName);
		params.put("objectId", objectId);
		params.put("descriptorName", descriptorName);
		params.put("value", value);
		
		String updateQuery;
		if (update) {
			updateQuery = """
				MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(parent:Object {id: $objectId})
				MERGE (parent)-[:HAS_DESCRIPTOR]->(updated:Descriptor {name: $descriptorName})
				ON CREATE SET
					updated.id = randomUUID(),
					updated.value = $value,
					updated.createdAt = $timeStamp,
					updated.createdBy = $username
				ON MATCH SET
					updated.value = $value
				WITH namespace, parent, updated
			""";
		} else {
			updateQuery = """
				CREATE (updated:Descriptor {
					id: randomUUID(),
					name: $descriptorName,
					value: $value,
					createdAt: $timeStamp,
					createdBy: $username
				})
				WITH updated
				MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(parent:Object {id: $objectId})
				WITH namespace, parent, updated
				MERGE (parent)-[:HAS_DESCRIPTOR]->(updated)
				WITH namespace, parent, updated
			""";
		}
		updateQuery += Versioning.getElementUpdateCode("Descriptor", false, versioningType);
		
		String storedFilename = "";
		
		if (value instanceof Node) {
			Node node = (Node) value;
			if (node.getLabels().iterator().hasNext()) {
				Label firstLabel = node.getLabels().iterator().next();
				if ("DResource".equals(firstLabel.name())) {
					String resourceId = node.getProperty("id").toString();
					storedFilename = node.getProperty("storedFilename").toString();
					String filename = node.getProperty("filename").toString();
					
					params.put("resourceId", resourceId);
					params.put("filename", filename);
					params.put("storedFilename", storedFilename);
					params.put("imageFormat", node.getProperty("imageFormat").toString());
					
					FileSystem.copyToPermanent(storedFilename);
					
					params.put("value", filename);
					
					updateQuery += """
						WITH updated
						CREATE (resource:DResource {
							id: $resourceId,
							filename: $filename,
							storedFilename: $storedFilename,
							imageFormat: $imageFormat
						})
						WITH updated, resource
						CREATE (updated)-[:HAS_RESOURCE]->(resource)
					""";
				}
			}
		}
		updateQuery += "RETURN updated";

		try {
			Result result = tx.execute(updateQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("updated");
			} else {
				rollbackCopyToPermanent(storedFilename);
				throwException("Descriptor creation failed: No result returned");
			}
		} catch (Exception e) {
			rollbackCopyToPermanent(storedFilename);
			throwException("Error creating Descriptor with name: " + descriptorName, e);
		}
		return null;
	}

	private void rollbackCopyToPermanent(String filename) {
		if (!filename.isEmpty()) {
			FileSystem.deleteFromPermanent(filename);
		}
	}
}
