package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.Map;

public class SetVersioningTypeRepository extends BaseRepository {

	private static SetVersioningTypeRepository instance;
	
	private SetVersioningTypeRepository() {}
	
	public static synchronized SetVersioningTypeRepository getInstance() {
		if (instance == null) {
			instance = new SetVersioningTypeRepository();
		}
		return instance;
	}
	
	public void setVersioningType(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType) {
		
		String queryUpdate = """
			MATCH (namespace:Namespace {name: $name})
			SET namespace.versioningType = $versioningType
			WITH namespace
			MATCH (namespace)-[:CONTAINS]->(archive:Archive)
			WITH namespace, archive
		""";
		
		queryUpdate += Versioning.getCreateArchiveCode(versioningType);
		queryUpdate += "RETURN namespace";
		
		Map<String, Object> params = Map.of(
			"timeStamp", timeStamp,
			"username", username,
			"name", namespaceName,
			"versioningType", versioningType
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
