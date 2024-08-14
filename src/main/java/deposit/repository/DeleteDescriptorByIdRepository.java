package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class DeleteDescriptorByIdRepository extends BaseRepository {

	private static DeleteDescriptorByIdRepository instance;
	
	private DeleteDescriptorByIdRepository() {}
	
	public static synchronized DeleteDescriptorByIdRepository getInstance() {
		if (instance == null) {
			instance = new DeleteDescriptorByIdRepository();
		}
		return instance;
	}
	
	public void deleteDescriptorById(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String descriptorId) {
		String validateQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object)-[:HAS_DESCRIPTOR]->(existing:Descriptor {id: $descriptorId}) RETURN existing
		""";
		Map<String, Object> params;
		params = Map.of(
			"namespaceName", namespaceName,
			"descriptorId", descriptorId
		);
		try {
			Result validation = tx.execute(validateQuery, params);
			if (!validation.hasNext()) {
				throwException("Error deleting Descriptor " + descriptorId + ". It does not exist.");
			}
		} catch (Exception e) {
			throwException("Error deleting Descriptor " + descriptorId, e);
		}
		
		String deleteQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(parent:Object)-[:HAS_DESCRIPTOR]->(updated:Descriptor {id: $descriptorId})
			WITH namespace, parent, updated
		""";
		deleteQuery += Versioning.getElementUpdateCode("Descriptor", true, versioningType);
		deleteQuery += """
			MATCH (parent)-[r:HAS_DESCRIPTOR]->(updated)
			DELETE r, updated
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
			throwException("Error deleting Descriptor " + descriptorId, e);
		}
	}
}
