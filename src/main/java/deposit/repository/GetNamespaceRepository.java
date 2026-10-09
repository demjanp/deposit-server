package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetNamespaceRepository extends BaseRepository {

	private static GetNamespaceRepository instance;
	
	private GetNamespaceRepository() {}
	
	public static synchronized GetNamespaceRepository getInstance() {
		if (instance == null) {
			instance = new GetNamespaceRepository();
		}
		return instance;
	}
	
    public Stream<Node> getNamespace(Transaction tx, String namespaceName) {
 		String matchQuery;
		Map<String, Object> params;
		
		if (namespaceName.isEmpty()) {
			matchQuery = """
				MATCH (existing:Namespace) RETURN existing
			""";
			params = Map.of();
		} else {
			matchQuery = """
				MATCH (existing:Namespace {name: $namespaceName}) RETURN existing
			""";
			params = Map.of("namespaceName", namespaceName);
		}
		
		try {
			Result result = tx.execute(matchQuery, params);
			return StreamSupport.stream(result.stream().spliterator(), false)
					.map(r -> (Node) r.get("existing"));
		} catch (Exception e) {
			throwException("Error retrieving Namespace with name: " + namespaceName, e);
		}
		return Stream.empty();
   }
}
