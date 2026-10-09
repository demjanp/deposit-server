package deposit.repository;

import deposit.common.result.MapResult;

import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;


import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetQueryRepository extends BaseRepository {

	private static GetQueryRepository instance;
	
	private GetQueryRepository() {}
	
	public static synchronized GetQueryRepository getInstance() {
		if (instance == null) {
			instance = new GetQueryRepository();
		}
		return instance;
	}
	
	public Stream<MapResult> getQuery(Transaction tx, String namespaceName, String cypherQueryStr, Map<String, String> returnedKeys) {
		try {
			Result result = tx.execute(cypherQueryStr, Map.of("namespaceName", namespaceName));
			return StreamSupport.stream(result.stream().spliterator(), false)
				.map(row -> {
					Map<String, Object> queryResultMap = new HashMap<>();
					for (Map.Entry<String, String> entry : returnedKeys.entrySet()) {
						Object value = row.get(entry.getKey());
						queryResultMap.put(entry.getValue(), value);
					}
					return new MapResult(queryResultMap);
				});
		} catch (Exception e) {
			throwException("Error executing query: " + cypherQueryStr, e);
		}
		return Stream.empty();
	}

}
