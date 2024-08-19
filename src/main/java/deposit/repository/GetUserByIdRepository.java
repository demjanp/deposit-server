package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class GetUserByIdRepository extends BaseRepository {

	private static GetUserByIdRepository instance;
	
	private GetUserByIdRepository() {}
	
	public static synchronized GetUserByIdRepository getInstance() {
		if (instance == null) {
			instance = new GetUserByIdRepository();
		}
		return instance;
	}
	
    public Node getUserById(Transaction tx, String userId) {
		String matchQuery = """
			MATCH (existing:User {id: $userId}) RETURN existing LIMIT 1
		""";
		Map<String, Object> params = Map.of("userId", userId);

		try {
			Result result = tx.execute(matchQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("existing");
			}
		} catch (Exception e) {
			throwException("Error retrieving User: " + userId, e);
		}
		return null;
    }
}
