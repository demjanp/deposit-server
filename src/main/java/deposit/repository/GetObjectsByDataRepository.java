package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetObjectsByDataRepository extends BaseRepository {

	private static GetObjectsByDataRepository instance;
	
	private GetObjectsByDataRepository() {}
	
	public static synchronized GetObjectsByDataRepository getInstance() {
		if (instance == null) {
			instance = new GetObjectsByDataRepository();
		}
		return instance;
	}
	
	public Stream<Node> getObjectsByData(Transaction tx, String namespaceName, String queryStr, Map<String, Object> values) {
		try {
			Map<String, Object> params = new HashMap<>(values);
			params.put("namespaceName", namespaceName);
			Result result = tx.execute(queryStr, params);

			return StreamSupport.stream(result.stream().spliterator(), false)
				.flatMap(row -> row.values().stream())
				.filter(value -> value instanceof Node)
				.map(value -> (Node) value)
				.distinct();
		} catch (Exception e) {
			throwException("Error retrieving Objects", e);
		}
		return Stream.empty();
	}
}
