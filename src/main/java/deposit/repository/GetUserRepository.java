package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetUserRepository extends BaseRepository {

	private static GetUserRepository instance;
	
	private GetUserRepository() {}
	
	public static synchronized GetUserRepository getInstance() {
		if (instance == null) {
			instance = new GetUserRepository();
		}
		return instance;
	}
	
    public Stream<Node> getUser(Transaction tx, String username) {
		String matchQuery;
		Map<String, Object> params;
		
		if (username.isEmpty()) {
			matchQuery = """
				MATCH (existing:User) RETURN existing
			""";
			params = Map.of();
		} else {
			matchQuery = """
				MATCH (existing:User {username: $username}) RETURN existing
			""";
			params = Map.of("username", username);
		}
		
		try {
			Result result = tx.execute(matchQuery, params);
			return StreamSupport.stream(result.stream().spliterator(), false)
					.map(r -> (Node) r.get("existing"));
		} catch (Exception e) {
			throwException("Error retrieving User: " + username, e);
		}
		return Stream.empty();
    }
}
