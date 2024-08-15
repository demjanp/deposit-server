package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class GetObjectByIdRepository extends BaseRepository {

	private static GetObjectByIdRepository instance;
	
	private GetObjectByIdRepository() {}
	
	public static synchronized GetObjectByIdRepository getInstance() {
		if (instance == null) {
			instance = new GetObjectByIdRepository();
		}
		return instance;
	}
	
    public Node getObjectById(Transaction tx, String namespaceName, String objectId) {
		String matchQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Object {id: $objectId}) RETURN existing LIMIT 1
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"objectId", objectId
		);

		try {
			Result result = tx.execute(matchQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("existing");
			}
		} catch (Exception e) {
			throwException("Error retrieving Object: " + objectId, e);
		}
		return null;
    }
}
