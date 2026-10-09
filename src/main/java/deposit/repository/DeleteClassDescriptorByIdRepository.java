package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class DeleteClassDescriptorByIdRepository extends BaseRepository {

	private static DeleteClassDescriptorByIdRepository instance;

	private DeleteClassDescriptorByIdRepository() {}

	public static synchronized DeleteClassDescriptorByIdRepository getInstance() {
		if (instance == null) {
			instance = new DeleteClassDescriptorByIdRepository();
		}
		return instance;
	}

	public String deleteClassDescriptorById(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String descriptorId) {
		String validateQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(classNode:Class)-[:HAS_DESCRIPTOR]->(existing:Descriptor {id: $descriptorId}) 
			RETURN classNode.name AS className
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"descriptorId", descriptorId
		);
		String className = null;

		try {
			Result validation = tx.execute(validateQuery, params);
			if (!validation.hasNext()) {
				throwException("Error deleting Class Descriptor " + descriptorId + ". It does not exist.");
			} else {
				Map<String, Object> row = validation.next();
				className = (String) row.get("className");
			}
		} catch (Exception e) {
			throwException("Error deleting Class Descriptor " + descriptorId, e);
		}

		String deleteQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(parent:Class)-[:HAS_DESCRIPTOR]->(updated:Descriptor {id: $descriptorId})
			WITH namespace, parent, updated
		""";
		deleteQuery += Versioning.getElementUpdateCode("ClassDescriptor", true, versioningType);
		deleteQuery += """
			DETACH DELETE updated
			RETURN updated
		""";
		params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"descriptorId", descriptorId
		);
		try {
			tx.execute(deleteQuery, params);
		} catch (Exception e) {
			throwException("Error deleting Class Descriptor " + descriptorId, e);
		}

		return className;
	}
}
