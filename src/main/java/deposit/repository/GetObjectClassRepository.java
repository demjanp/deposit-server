package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class GetObjectClassRepository extends BaseRepository {

	private static GetObjectClassRepository instance;
	
	private GetObjectClassRepository() {}
	
	public static synchronized GetObjectClassRepository getInstance() {
		if (instance == null) {
			instance = new GetObjectClassRepository();
		}
		return instance;
	}
	
    public Node getObjectClass(Transaction tx, String namespaceName, String objectId) {
		String matchQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(:Object {id: $objectId})-[:MEMBER_OF]->(existing:Class) RETURN existing
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
			throwException("Error retrieving Class for Object: " + objectId, e);
		}
		return null;
    }
}
