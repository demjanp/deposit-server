package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetClassRepository extends BaseRepository {
	
	private static GetClassRepository instance;
	
	private GetClassRepository() {}
	
	public static synchronized GetClassRepository getInstance() {
		if (instance == null) {
			instance = new GetClassRepository();
		}
		return instance;
	}
	
	public Stream<Node> getClass(Transaction tx, String namespaceName, String className) {
		String matchQuery;
		Map<String, Object> params;
		
		if (className.isEmpty()) {
			matchQuery = """
				MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Class) RETURN existing
			""";
			params = Map.of("namespaceName", namespaceName);
		} else {
			matchQuery = """
				MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Class {name: $className}) RETURN existing
			""";
			params = Map.of(
				"namespaceName", namespaceName,
				"className", className
			);
		}
		
		try {
			Result result = tx.execute(matchQuery, params);
			return StreamSupport.stream(result.stream().spliterator(), false)
					.map(r -> (Node) r.get("existing"));
		} catch (Exception e) {
			throwException("Error retrieving Class with name: " + className, e);
		}
		return Stream.empty();
	}
}
