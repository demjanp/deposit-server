package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class GetClassPositionRepository extends BaseRepository {

	private static GetClassPositionRepository instance;

	private GetClassPositionRepository() {}

	public static synchronized GetClassPositionRepository getInstance() {
		if (instance == null) {
			instance = new GetClassPositionRepository();
		}
		return instance;
	}

	public double[] getClassPosition(Transaction tx, String namespaceName, String className) {
		String matchQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Class {name: $className}) 
			RETURN existing.position AS position
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"className", className
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
			throwException("Error retrieving position of Class with name: " + className, e);
			return new double[0];
		}
	}
}
