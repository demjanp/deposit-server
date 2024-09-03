package deposit.repository;

import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Result;

import java.util.Map;

public class GetRelationshipByIdRepository extends BaseRepository {

	private static GetRelationshipByIdRepository instance;
	
	private GetRelationshipByIdRepository() {}
	
	public static synchronized GetRelationshipByIdRepository getInstance() {
		if (instance == null) {
			instance = new GetRelationshipByIdRepository();
		}
		return instance;
	}
	
	public Node[] getRelationshipById(Transaction tx, String namespaceName, String relationshipId) {
		String matchQuery = """
			MATCH (n:Namespace {name: $namespaceName})-[:CONTAINS]->(source:Object)-[:RS]->(relationship:Relationship {id: $relationshipId})-[:RE]->(target:Object)
			RETURN source, target, relationship LIMIT 1
		""";
		Map<String, Object> params = Map.of(
			"namespaceName", namespaceName,
			"relationshipId", relationshipId
		);
	
		try {
			Result result = tx.execute(matchQuery, params);
			if (result.hasNext()) {
				Map<String, Object> row = result.next();
				return new Node[]{(Node) row.get("source"), (Node) row.get("target"), (Node) row.get("relationship")};
			}
		} catch (Exception e) {
			throwException("Error retrieving Relationship", e);
		}
		return null;
	}
}
