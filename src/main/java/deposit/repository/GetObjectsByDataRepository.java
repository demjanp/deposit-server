package deposit.repository;

import deposit.common.result.StringMapResult;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.util.HashMap;
import java.util.Map;

public class GetObjectsByDataRepository extends BaseRepository {

	private static GetObjectsByDataRepository instance;
	
	private GetObjectsByDataRepository() {}
	
	public static synchronized GetObjectsByDataRepository getInstance() {
		if (instance == null) {
			instance = new GetObjectsByDataRepository();
		}
		return instance;
	}
	
	public StringMapResult getObjectsByData(Transaction tx, String namespaceName, String queryStr, Map<String, Object> values) {
		try {
			Map<String, Object> params = new HashMap<>(values);
			params.put("namespaceName", namespaceName);
			Result result = tx.execute(queryStr, params);
			if (result.hasNext()) {
				Map<String, String> data = (Map<String, String>) result.next().get("data");
				return new StringMapResult(data);
			}
		} catch (Exception e) {
			throwException("Error retrieving Objects", e);
		}
		return new StringMapResult(Map.of());
	}
}
