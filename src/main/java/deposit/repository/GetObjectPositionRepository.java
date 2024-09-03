package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class GetObjectPositionRepository extends BaseRepository {

	private static GetObjectPositionRepository instance;
	
	private GetObjectPositionRepository() {}
	
	public static synchronized GetObjectPositionRepository getInstance() {
		if (instance == null) {
			instance = new GetObjectPositionRepository();
		}
		return instance;
	}
	
	public double[] getObjectPosition(Transaction tx, String namespaceName, String objectId) {
		String matchQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Object {id: $objectId}) 
			RETURN existing.position AS position
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"objectId", objectId
		);

		try {
			Result result = tx.execute(matchQuery, params);
			if (result.hasNext()) {
				Object pos = result.next().get("position");
				if (pos instanceof double[]) {
					return (double[]) pos;
				} else {
					return new double[0];
				}
			}
			return new double[0];
		} catch (Exception e) {
			throwException("Error retrieving position of Object: " + objectId, e);
			return new double[0];
		}
	}
}
