package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetClassDescriptorOrderRepository extends BaseRepository {

	private static SetClassDescriptorOrderRepository instance;
	
	private SetClassDescriptorOrderRepository() {}
	
	public static synchronized SetClassDescriptorOrderRepository getInstance() {
		if (instance == null) {
			instance = new SetClassDescriptorOrderRepository();
		}
		return instance;
	}
	
	public void setClassDescriptorOrder(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className, String descriptorName, Long order) {
		String updateQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(parent:Class {name: $className})-[:HAS_DESCRIPTOR]->(updated:Descriptor {name: $descriptorName})
			SET updated.order = $order
			WITH namespace, parent, updated
		""";
		updateQuery += Versioning.getElementUpdateCode("ClassDescriptor", false, versioningType);
		updateQuery += "	RETURN updated";
		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"className", className,
			"descriptorName", descriptorName,
			"order", order
		);
		
		try {
			Result result = tx.execute(updateQuery, params);
			if (!result.hasNext()) {
				throwException("Class Descriptor update failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error executing transaction", e);
		}
	}
}
