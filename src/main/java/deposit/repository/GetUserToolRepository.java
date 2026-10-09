package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetUserToolRepository extends BaseRepository {

	private static GetUserToolRepository instance;
	
	private GetUserToolRepository() {}
	
	public static synchronized GetUserToolRepository getInstance() {
		if (instance == null) {
			instance = new GetUserToolRepository();
		}
		return instance;
	}
	
    public Stream<Node> getUserTool(Transaction tx, String namespaceName, String userToolName) {
		String matchQuery;
		Map<String, Object> params;
		
		if (userToolName.isEmpty()) {
			matchQuery = """
				MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:UserTool) RETURN existing
			""";
			params = Map.of("namespaceName", namespaceName);
		} else {
			matchQuery = """
				MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:UserTool {name: $userToolName}) RETURN existing
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"userToolName", userToolName
			);
		}
		
		try {
			Result result = tx.execute(matchQuery, params);
			return StreamSupport.stream(result.stream().spliterator(), false)
					.map(r -> (Node) r.get("existing"));
		} catch (Exception e) {
			throwException("Error retrieving the User Tool: " + userToolName, e);
		}
		return Stream.empty();
    }
}
