package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.List;

public class GetQueryRepository extends BaseRepository {

	private static GetQueryRepository instance;
	
	private GetQueryRepository() {}
	
	public static synchronized GetQueryRepository getInstance() {
		if (instance == null) {
			instance = new GetQueryRepository();
		}
		return instance;
	}

	public Stream<Node[]> getQuery(Transaction tx, String namespaceName, String cypherQueryStr, List<String> returnedNodes) {
		try {
			Result result = tx.execute(cypherQueryStr, Map.of("namespaceName", namespaceName));

			// Stream over the result and map the nodes based on the provided node names
			return StreamSupport.stream(result.stream().spliterator(), false)
				.map(row -> returnedNodes.stream()
					.map(name -> (Node) row.get(name))
					.toArray(Node[]::new));
		} catch (Exception e) {
			throwException("Error executing query: " + cypherQueryStr, e);
		}
		return Stream.empty();
	}
}
