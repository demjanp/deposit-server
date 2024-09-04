package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetClassOrderRepository extends BaseRepository {

	private static SetClassOrderRepository instance;
	
	private SetClassOrderRepository() {}
	
	public static synchronized SetClassOrderRepository getInstance() {
		if (instance == null) {
			instance = new SetClassOrderRepository();
		}
		return instance;
	}
	
	public void setClassOrder(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, String className, Long order) {
		String updateQuery = """
			MATCH (namespace:Namespace {name: $namespaceName})-[:CONTAINS]->(updated:Class {name: $className})
			SET updated.order = $order
			WITH namespace, updated
		""";
		updateQuery += Versioning.getElementUpdateCode("Class", false, versioningType);
		updateQuery += "	RETURN updated";
		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"className", className,
			"order", order
		);
		
		try {
			Result result = tx.execute(updateQuery, params);
			if (!result.hasNext()) {
				throwException("Class update failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error executing transaction", e);
		}
	}
}
