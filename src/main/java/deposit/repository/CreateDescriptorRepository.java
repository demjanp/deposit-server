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
	
    public Node createDescriptor(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String objectId, String descriptorName, Object value) {
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
		
		Map<String, Object> params = new HashMap<>();
		params.put("timeStamp", timeStamp);
		params.put("username", username);
		params.put("namespaceName", namespaceName);
		params.put("objectId", objectId);
		params.put("descriptorName", descriptorName);
		params.put("value", value);
		
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
		
		String storedFilename = "";
		
		if (value instanceof Node) {
			Node node = (Node) value;
			if (node.getLabels().iterator().hasNext()) {
				Label firstLabel = node.getLabels().iterator().next();
				if ("DResource".equals(firstLabel.name())) {
					storedFilename = node.getProperty("storedFilename").toString();
					String filename = node.getProperty("filename").toString();
					
					params.put("filename", filename);
					params.put("storedFilename", storedFilename);
					params.put("isImage", node.getProperty("isImage").toString());
					
					FileSystem.moveToPermanent(storedFilename);
					
					params.put("value", filename);
					
					createQuery += """
						WITH updated
						CREATE (resource:DResource {
							filename: $filename,
							storedFilename: $storedFilename,
							isImage: $isImage
						})
						WITH updated, resource
						CREATE (updated)-[:HAS_RESOURCE]->(resource)
					""";
				}
			}
		}
		
		createQuery += "RETURN updated";
		try {
			Result result = tx.execute(createQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("updated");
			} else {
				rollbackMoveToPermanent(storedFilename);
				throwException("Descriptor creation failed: No result returned");
			}
		} catch (Exception e) {
			rollbackMoveToPermanent(storedFilename);
			throwException("Error creating Descriptor with name: " + descriptorName, e);
		}
		
		return null;
    }
    
    private void rollbackMoveToPermanent(String filename) {
		if (!filename.isEmpty()) {
			FileSystem.deleteFromPermanent(filename);
		}
    }
}
