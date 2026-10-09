package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class GetClassByIdRepository extends BaseRepository {

	private static GetClassByIdRepository instance;

	private GetClassByIdRepository() {}

	public static synchronized GetClassByIdRepository getInstance() {
		if (instance == null) {
			instance = new GetClassByIdRepository();
		}
		return instance;
	}

	public Node getClassById(Transaction tx, String namespaceName, String classId) {
		String matchQuery = """
			MATCH (:Namespace {name: $namespaceName})-[:CONTAINS]->(existing:Class {id: $classId}) RETURN existing LIMIT 1
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"classId", classId
		);

		try {
			Result result = tx.execute(matchQuery, params);
			if (result.hasNext()) {
				return (Node) result.next().get("existing");
			}
		} catch (Exception e) {
			throwException("Error retrieving Class: " + classId, e);
		}
		return null;
	}
}
