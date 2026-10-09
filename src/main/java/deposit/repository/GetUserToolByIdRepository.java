package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class GetUserToolByIdRepository extends BaseRepository {

	private static GetUserToolByIdRepository instance;
	
	private GetUserToolByIdRepository() {}
	
	public static synchronized GetUserToolByIdRepository getInstance() {
		if (instance == null) {
			instance = new GetUserToolByIdRepository();
		}
		return instance;
	}
	
    public Node getUserToolById(Transaction tx, String namespaceName, String userToolId) {
		String matchQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:UserTool {id: $userToolId}) RETURN existing LIMIT 1
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"userToolId", userToolId
		);

		try {
			Result result = tx.execute(matchQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("existing");
			}
		} catch (Exception e) {
			throwException("Error retrieving the User Tool: " + userToolId, e);
		}
		return null;
    }
}
