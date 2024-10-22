package deposit.repository;

import deposit.common.PatternData;

import org.neo4j.graphdb.Transaction;

import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class GetObjectsByPatternRepository extends BaseRepository {

	private static GetObjectsByPatternRepository instance;
	
	private GetObjectsByPatternRepository() {}
	
	public static synchronized GetObjectsByPatternRepository getInstance() {
		if (instance == null) {
			instance = new GetObjectsByPatternRepository();
		}
		return instance;
	}
	
	public Stream<Map<String, String>> getObjectsByPattern(Transaction tx, String namespaceName, PatternData.Pattern pattern, Map<String, Object> data) {
		
		System.out.println(data); // DEBUG
		/*
		try {
			Result result = tx.execute(matchQuery.toString(), params);
			return StreamSupport.stream(result.stream().spliterator(), false)
								.map(r -> (Map<String, String>) r.get("existing"));
		} catch (Exception e) {
			throwException("Error retrieving Objects", e);
		}
		*/
		return Stream.empty();
	}
}
