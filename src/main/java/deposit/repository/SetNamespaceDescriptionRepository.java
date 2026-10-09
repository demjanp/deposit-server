package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetNamespaceDescriptionRepository extends BaseRepository {

	private static SetNamespaceDescriptionRepository instance;
	
	private SetNamespaceDescriptionRepository() {}
	
	public static synchronized SetNamespaceDescriptionRepository getInstance() {
		if (instance == null) {
			instance = new SetNamespaceDescriptionRepository();
		}
		return instance;
	}
	
	public void setNamespaceDescription(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String description) {
		String queryUpdate = """
			MATCH (namespace:Namespace {name: $namespaceName})
			SET namespace.description = $description,
				namespace.updatedAt = $timeStamp,
				namespace.updatedBy = $username
			RETURN namespace
		""";
		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"namespaceName", namespaceName,
			"description", description
		);
		
		try {
			Result result = tx.execute(queryUpdate, params);
			if (!result.hasNext()) {
				throwException("Namespace update failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error executing transaction", e);
		}
		return;
	}
}
