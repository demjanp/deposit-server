package deposit.repository;

import deposit.common.Versioning;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

public class CreateObjectRepository extends BaseRepository {

	private static CreateObjectRepository instance;
	
	private CreateObjectRepository() {}
	
	public static synchronized CreateObjectRepository getInstance() {
		if (instance == null) {
			instance = new CreateObjectRepository();
		}
		return instance;
	}
	
    public Node createObject(Transaction tx, ZonedDateTime timeStamp, String username, String namespaceName, String versioningType, List<Double> position) {
		String createQuery = """
			CREATE (updated:Object {
				id: randomUUID(),
				position: $position,
				createdAt: $timeStamp,
				createdBy: $username
			})
			WITH updated
			MATCH (namespace:Namespace {name: $namespaceName})
			WITH namespace, updated
			MERGE (namespace)-[:CONTAINS]->(updated)
			WITH namespace, updated
		""";
		createQuery += Versioning.getElementUpdateCode("Object", false, versioningType);
		createQuery += "	RETURN updated";
		Map<String, Object> params = Map.of(
		"timeStamp", timeStamp,
		"username", username,
		"namespaceName", namespaceName,
		"position", position
		);
		try {
			Result result = tx.execute(createQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("updated");
			} else {
				throwException("Object creation failed: No result returned");
			}
		} catch (Exception e) {
			throwException("Error creating Object", e);
		}
		return null;
	}
}

